package com.openminis.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.openminis.app.MainActivity
import com.openminis.app.R
import com.openminis.app.agent.userask.PendingAsk
import com.openminis.app.agent.userask.UserAskCenter
import com.openminis.app.agent.userask.UserAskNotificationPlan
import com.openminis.app.agent.userask.UserAskNotificationPlan.Layout
import com.openminis.app.agent.userask.UserAskNotificationPlan.Outcome
import com.openminis.app.agent.userask.UserAskSettings
import com.openminis.app.agent.userask.UserAskState
import com.openminis.app.config.confirm.PendingConfigChange
import com.openminis.app.data.db.MessageHeadRow
import com.openminis.app.data.repository.BackgroundSettingsRepository
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.scheduled.ScheduledTaskMarker
import com.openminis.app.ui.chat.ChatMessage
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * T180-bg-notif: posts task-completion notifications when an agent
 * session finishes while the app is backgrounded. Mirrors iOS
 * `BackgroundKeepAliveManager.postBackgroundTaskNotification` (L274).
 *
 * Trigger contract: this class is hooked into [com.openminis.app.service.SessionActivityTracker]
 * so a session transitioning from active → inactive (i.e. its agent
 * loop completed) deterministically reaches `notifyTaskCompleted`. The
 * tracker itself is the single source of truth for "is this session
 * still streaming" — using its callback avoids invading
 * `ChatViewModel`, whose 4 stream-finally blocks would all need the
 * same hook.
 *
 * Behaviour rules:
 * - Skip silently if the user has disabled Task Notifications
 *   ([BackgroundSettingsRepository.taskNotificationsEnabled] = false).
 * - Skip silently if the app is currently in foreground — the user is
 *   already looking at the chat, no need to interrupt.
 * - Tap on the notification deep-links into the originating chat via
 *   `minis://session/<sessionId>` (existing
 *   `DeepLinkHandler.OpenSession` path).
 *
 * On Android the absence of `responseSummary` from the spec is
 * intentional: extracting plain text from a `parts_json` blob is
 * fragile; the notification's deep-link opens the chat where the
 * full response is rendered, matching the user's likely intent.
 */
class BackgroundTaskNotifier(
    private val context: Context,
    private val chatRepository: ChatRepository,
    private val backgroundSettings: BackgroundSettingsRepository,
    private val isAppForeground: () -> Boolean,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        ensureChannel()
    }

    /**
     * Called by [com.openminis.app.service.SessionActivityTracker] when a
     * session finishes (active → inactive transition). Looks up the
     * session title via [chatRepository] and posts the notification, off
     * the main thread. No-ops silently if the user has disabled
     * notifications or the app is in foreground.
     */
    fun notifyTaskCompleted(sessionId: String, outcome: BackgroundTurnOutcome = BackgroundTurnOutcome.COMPLETED) {
        if (!backgroundSettings.taskNotificationsEnabled.value) return
        if (isAppForeground()) return

        scope.launch {
            try {
                val session = chatRepository.getSession(sessionId)
                // [T-android-subagent-no-system-notification] A sub agent run
                // is not a task the user is waiting on — it is one step inside
                // the parent's turn, and its result is already delivered into
                // the parent chat as an "代理任务·摘要" card. Posting a system
                // notification for it interrupts the user to tell them about
                // work they did not start, and a parent that delegates several
                // children fires one per child.
                //
                // iOS never did this. Its notification has exactly ONE call
                // site — AIChatViewModel+BackgroundTask, the MAIN chat's
                // background-completion path — and `HelperRunner` (the sub
                // agent runner) posts nothing at all; it only reads
                // SessionActivityTracker for display. Android diverged because
                // the notifier is wired to SessionActivityTracker's completion
                // hook, which fires for EVERY session, child sessions included.
                //
                // Checked here rather than at the tracker hook because the
                // session row is already being loaded for the title, so the
                // parent test is free, and the tracker stays a pure
                // "is this session streaming" signal with no opinion about
                // which sessions deserve a notification.
                if (session?.isChild == true) {
                    AppLogger.info(
                        TAG,
                        "skipping completion notification for sub agent session " +
                            sessionId.take(8),
                    )
                    return@launch
                }
                val rawTitle = session?.title?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.notif_task_completed_default_title)
                // [T-bgnotif-outcome] A run that did not finish is not "completed".
                val (title, body) = when (outcome) {
                    BackgroundTurnOutcome.FAILED ->
                        "❌ $rawTitle" to context.getString(R.string.notif_task_failed_body)
                    BackgroundTurnOutcome.INTERRUPTED ->
                        "⏸ $rawTitle" to context.getString(R.string.notif_task_paused_body)
                    BackgroundTurnOutcome.COMPLETED ->
                        rawTitle to context.getString(R.string.notif_task_completed_body)
                }
                // [T-port-sched-notif-single] A run opened by a scheduled fire
                // gets one entry per (chat, task) that each run replaces in
                // place (iOS b0c0c87ea). This is now the ONLY notification a
                // scheduled run posts: ScheduledAgentRunner used to add a
                // second, hard-coded English one that ignored this toggle and
                // the foreground check and was never cleared.
                val taskId = ScheduledRunNotification.taskIdOpeningTurn { offset ->
                    chatRepository.dao.messageHeadNewestFirst(sessionId, offset)
                }
                postNotification(sessionId, title, body, ScheduledRunNotification.tag(taskId))
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "notifyTaskCompleted failed: ${t.message}")
            }
        }
    }

    private fun postNotification(sessionId: String, title: String, body: String, tag: String? = null) {
        // Pre-Tiramisu: we don't need the runtime permission, just post.
        // Tiramisu+: NotificationManagerCompat.areNotificationsEnabled
        // is the right gate — POST_NOTIFICATIONS is requested at toggle-on
        // time in Settings (separate flow), and if it was denied we silently
        // skip rather than crash.
        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            return
        }

        val deepLink = Uri.parse("minis://session/$sessionId")
        val launchIntent = Intent(Intent.ACTION_VIEW, deepLink).apply {
            // FLAG_ACTIVITY_NEW_TASK because we're posting from a
            // background scope without an Activity context.
            // FLAG_ACTIVITY_CLEAR_TOP so MainActivity (singleTask) reuses
            // the existing instance and routes the deep-link via
            // onNewIntent rather than spawning a duplicate.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        try {
            nm.notify(tag, sessionId.hashCode(), notification)
        } catch (se: SecurityException) {
            // POST_NOTIFICATIONS not granted — silently no-op rather
            // than crashing the agent-completion path.
            AppLogger.info(TAG, "notify denied (POST_NOTIFICATIONS not granted)")
        }
    }

    /**
     * T298: cancel every notification posted on the
     * [CHANNEL_ID] channel. Called from MinisApp's foreground transition
     * (Activity start count 0 → 1) so the user never finds a stale "task
     * completed" entry waiting in the tray when they open the app — they
     * just saw the result, the notification has served its purpose.
     *
     * Implementation: walk [NotificationManager.activeNotifications] (API
     * 23+, lower bound is API 26 for our app) and cancel any whose
     * channelId matches ours. We can't filter by channel directly because
     * each notification id is the session hash — there's no single id to
     * cancel — and `cancelAll()` would also nuke the FG service banner.
     */
    fun cancelAllCompletedNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return
        try {
            val active = nm.activeNotifications ?: return
            for (sb in active) {
                if (sb.notification?.channelId == CHANNEL_ID) {
                    nm.cancel(sb.tag, sb.id)
                }
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "cancelAllCompletedNotifications failed: ${t.message}")
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_task_completed_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_task_completed_channel_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "TaskNotifier"
        const val CHANNEL_ID = "minis_task_completed"
    }
}

// ─── Merged from BackgroundTurnOutcome.kt ──────────────────────────────────────────────────

/**
 * [T-bgnotif-outcome] How a run ended, for the background task notification
 * (iOS BackgroundTurnOutcome, 387bca64c).
 *
 * The notification used to read "completed" unless the run THREW
 * ([com.openminis.app.service.SessionActivityTracker.markStreamError] is only
 * called from the loop's exception handlers). Every deliberate stop of an
 * unfinished turn ended the loop normally and was announced as done: a
 * stream that dropped mid-reply, a max-tokens cut, a refusal, an empty reply
 * that survived the reminder, the DSML give-up (all an inline error plus
 * Resume), the context-full stop (Resume only), and a Stop from the overlay
 * while the app was in the background.
 *
 * iOS's first cause, background time running out, has no Android
 * counterpart: the agent runs in a foreground service.
 */
enum class BackgroundTurnOutcome {
    COMPLETED,
    FAILED,
    INTERRUPTED;

    companion object {
        /**
         * In iOS's order: an error (thrown, or shown on this turn's reply) is
         * a failure; otherwise a turn that can be resumed, or that the user
         * stopped, is paused; anything else completed.
         */
        fun decide(streamError: Boolean, turnError: Boolean, canResume: Boolean, userStopped: Boolean): BackgroundTurnOutcome =
            when {
                streamError || turnError -> FAILED
                canResume || userStopped -> INTERRUPTED
                else -> COMPLETED
            }

        /**
         * The error on THIS turn's reply: the last assistant row after the
         * last user row actually sent (a queued prompt is not sent yet). Not
         * the list's last row, which can be a queued prompt or a notice
         * behind the reply, and never an earlier turn's error.
         */
        fun currentTurnError(messages: List<ChatMessage>): String? {
            val lastSent = messages.indexOfLast { it.role == "user" && !it.isQueued }
            for (i in messages.indices.reversed()) {
                if (i <= lastSent) break
                val m = messages[i]
                if (m.role == "assistant") return m.error?.takeIf { it.isNotBlank() }
            }
            return null
        }
    }
}

// ─── Merged from ConfigConfirmNotifier.kt ──────────────────────────────────────────────────

/**
 * [T-android-config-confirm-timeout] Posts a local notification when a
 * minis-config change is waiting for user approval AND the app is backgrounded,
 * so the user knows to return before the (now 120s) timeout. Android port of
 * iOS `ConfigConfirmationGate.notifyIfBackgrounded` (T-config-confirm-timeout-bg).
 *
 * The confirm dialog is mounted at app root (ConfigConfirmDialogHost, bound to
 * ConfigConfirmationGate.pending), so tapping the notification only needs to
 * FOREGROUND the app — the still-pending dialog is then simply visible again.
 *
 * Gated by the same "Task Notifications" background setting the task-completion
 * notifier uses ([BackgroundSettingsRepository.taskNotificationsEnabled]) and
 * suppressed while the app is in foreground (the user can already see the
 * dialog). The gate itself guards against sending twice per change id.
 */
class ConfigConfirmNotifier(
    private val context: Context,
    private val backgroundSettings: BackgroundSettingsRepository,
    private val isAppForeground: () -> Boolean,
) {

    init {
        ensureChannel()
    }

    /**
     * Post the "awaiting approval" notification for [change] if — and only if —
     * the app is currently backgrounded and notifications are enabled. Safe to
     * call from any thread; a no-op when the guards fail. Returns true when a
     * notification was actually posted (so the caller's once-per-id bookkeeping
     * only marks ids we really notified).
     */
    fun notifyIfBackgrounded(change: PendingConfigChange): Boolean {
        if (isAppForeground()) return false
        if (!backgroundSettings.taskNotificationsEnabled.value) {
            AppLogger.info(TAG, "bg-notify skipped id=${change.id} — taskNotificationsEnabled=false")
            return false
        }

        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            return false
        }

        // Show the caption (e.g. "Update multi-model writing workflow: …") so
        // the user gets the gist without opening the app; fall back to a
        // row-count summary when there's no caption. Mirrors iOS.
        val caption = change.caption?.trim().orEmpty()
        val body = when {
            caption.isNotEmpty() -> caption
            change.items.size > 1 ->
                context.getString(R.string.notif_config_confirm_body_multi, change.items.size)
            else -> context.getString(R.string.notif_config_confirm_body_single)
        }

        // Tapping just needs to bring MainActivity forward; the root-mounted
        // confirm dialog is still bound to the pending change. singleTask +
        // CLEAR_TOP reuses the existing instance rather than spawning a copy.
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            change.id.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_config_confirm_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()

        return try {
            nm.notify(NOTIFICATION_TAG, change.id.hashCode(), notification)
            AppLogger.info(TAG, "bg-notify post id=${change.id} bodyLen=${body.length}")
            true
        } catch (se: SecurityException) {
            AppLogger.info(TAG, "notify denied (POST_NOTIFICATIONS not granted)")
            false
        }
    }

    /** Cancel a pending-approval notification once its change resolves, so a
     *  stale "awaiting approval" entry never lingers after approve/reject/timeout. */
    fun cancel(changeId: String) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG, changeId.hashCode())
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_config_confirm_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_config_confirm_channel_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ConfigConfirmNotifier"
        const val CHANNEL_ID = "minis_config_confirm"
        private const val NOTIFICATION_TAG = "config-confirm"
    }
}

// ─── Merged from ScheduledRunNotification.kt ──────────────────────────────────────────────────

/**
 * [T-port-sched-notif-single] Which scheduled task opened the run that just
 * ended, so [BackgroundTaskNotifier] can give that task's runs one
 * notification per (chat, task) that each run replaces in place. Port of iOS
 * `BackgroundTaskNotificationID` / `scheduledJobIdOpeningCurrentTurn`
 * (b0c0c87ea).
 *
 * The run is identified from the transcript, as iOS does: a fire arrives as a
 * `<scheduled_task id="…">` envelope in the user row that opened the turn.
 * Rows are read newest-first and stop at the first turn-starting user row
 * (one with anything besides tool results), which is almost always within the
 * last few rows.
 */
internal object ScheduledRunNotification {

    /** Upper bound on rows read back to find the turn's opening user row. */
    const val MAX_SCAN_ROWS = 64

    /**
     * The notification tag for a run opened by task [taskId], or null for any
     * other completion. Posted with the chat's usual id, so the (tag, id) pair
     * is one entry per (chat, task): separate tasks in one chat, and the same
     * task id in another chat, keep their own entries, while an ordinary
     * completion keeps the untagged per-chat entry it always had.
     */
    fun tag(taskId: String?): String? = taskId?.let { "job-$it" }

    /**
     * The scheduled task id whose envelope opened the newest turn, or null.
     * [rowAt] returns the row at that offset counting back from the newest
     * (offset 0 = newest), or null past the oldest.
     */
    suspend fun taskIdOpeningTurn(rowAt: suspend (offset: Int) -> MessageHeadRow?): String? {
        for (offset in 0 until MAX_SCAN_ROWS) {
            val row = rowAt(offset) ?: return null
            if (row.role != "user") continue
            if (!ChatRepository.isTurnStartingUserParts(row.partsJson)) continue
            return taskIdIn(row.partsJson)
        }
        return null
    }

    /**
     * The task id of a scheduled envelope among [partsJson]'s text parts. The
     * envelope may sit next to other parts (e.g. a reminder); a user who
     * merely quotes an envelope mid-text is not a fire, since the marker must
     * open its part.
     */
    fun taskIdIn(partsJson: String): String? {
        val arr = runCatching { org.json.JSONArray(partsJson) }.getOrNull() ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") != "text") continue
            val text = o.optString("value", "").trim()
            ScheduledTaskMarker.parse(text)?.let { return it.taskId }
        }
        return null
    }
}

// ─── Merged from UserAskNotificationReceiver.kt ──────────────────────────────────────────────────

/**
 * [T-android-user-ask-notify] An answer given in a user_ask notification: an
 * option button, Allow / Cancel, or the "Other…" text reply. Hands it to
 * UserAskCenter (first wins against the card, the timeout and Stop); the
 * notifier then moves the notification on to the next question or clears it.
 */
class UserAskNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(UserAskNotifier.EXTRA_REQUEST_ID) ?: return
        val index = intent.getIntExtra(UserAskNotifier.EXTRA_INDEX, -1)
        val action = intent.getStringExtra(UserAskNotifier.EXTRA_ACTION) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(UserAskNotifier.REMOTE_INPUT_KEY)?.toString()
        val outcome = UserAskCenter.answerFromNotification(requestId, index, action, text)
        // A RemoteInput reply keeps its notification alive until the app
        // updates it; end that before moving on (the next question, a repost).
        if (action == UserAskNotificationPlan.ACTION_OTHER) {
            UserAskNotifier.endReply(UserAskNotificationPlan.tag(requestId, index))
        }
        when (outcome) {
            Outcome.ADVANCED, Outcome.COMPLETE -> Unit
            // Empty reply, or the ask is on another question now: show the open
            // question again (this also ends the reply field's spinner).
            Outcome.REJECTED, Outcome.STALE -> UserAskNotifier.repost(requestId)
            // Not in this process (it was killed meanwhile): the agent run that
            // waited for it is gone, and the chat restores the card on open.
            Outcome.NOT_FOUND -> UserAskNotifier.cancel(UserAskNotificationPlan.tag(requestId, index))
        }
    }
}

// ─── Merged from UserAskNotifier.kt ──────────────────────────────────────────────────

/**
 * [T-android-user-ask-notify] Posts a notification for a user_ask question
 * asked while the app is in the background (or the screen is off), answerable
 * from the notification - iOS UserAskCenter.postNotification /
 * handleNotificationResponse.
 *
 * Driven by UserAskCenter.asks and the process lifecycle, so every rule is one
 * reconciliation pass:
 *  - a pending ask with the app away posts its open question, once per question
 *    (PendingAsk.notifiedIndex), with sound only on the first;
 *  - an ask that resolves (answered, timed out, Stop, a new user message) or is
 *    forgotten loses its notification;
 *  - coming back to the app clears the notifications - the card is on screen -
 *    without re-alerting on the next trip to the background.
 *
 * Gated by the user_ask "Notify when in the background" setting and by the
 * notification permission; without either it silently posts nothing.
 */
object UserAskNotifier {
    private const val TAG = "UserAsk"
    const val CHANNEL_ID = "minis_user_ask"

    internal const val EXTRA_REQUEST_ID = "userAskRequestId"
    internal const val EXTRA_INDEX = "userAskQuestionIndex"
    internal const val EXTRA_ACTION = "userAskAction"
    internal const val REMOTE_INPUT_KEY = "userAskText"

    private val installed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: Context

    /** Tags posted per request, so a resolved or foregrounded ask clears exactly those. */
    private val posted = HashMap<String, Int>()

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        app = context.applicationContext
        ensureChannel()
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
            .map { it.isAtLeast(Lifecycle.State.STARTED) }
        scope.launch {
            combine(UserAskCenter.asks, foreground) { asks, fg -> asks to fg }
                .collect { (asks, fg) -> runCatching { sync(asks, fg) }.onFailure {
                    AppLogger.warning(TAG, "[UserAsk] notifier sync failed: ${it.javaClass.simpleName}: ${it.message}")
                } }
        }
    }

    private fun sync(asks: Map<String, PendingAsk>, appInForeground: Boolean) {
        // Resolved or forgotten: its notification goes.
        for (rid in posted.keys.toList()) {
            val a = asks[rid]
            if (a == null || a.state != UserAskState.PENDING) cancelAll(rid)
        }
        if (appInForeground) {
            // The card is on screen; the notification would only go stale.
            for (rid in posted.keys.toList()) cancelAll(rid)
            return
        }
        val enabled = UserAskSettings.notifyInBackground(app)
        val now = System.currentTimeMillis()
        for (a in asks.values) {
            val i = UserAskNotificationPlan.indexToPost(a, appInForeground = false, enabled = enabled, nowMs = now) ?: continue
            post(a, sound = UserAskNotificationPlan.alertsWithSound(a))
            // Marked even when nothing could be posted (permission off), so the
            // question is not retried on every change of the ask.
            UserAskCenter.markNotified(a.requestId, i)
        }
    }

    /**
     * Re-post the open question quietly: after a rejected or stale answer from
     * the notification (which also clears the reply field's spinner).
     */
    fun repost(requestId: String) {
        if (!installed.get()) return
        val a = UserAskCenter.asks.value[requestId]
        if (a == null || a.state != UserAskState.PENDING) { cancelAll(requestId); return }
        post(a, sound = false)
    }

    /** Clear one notification (an answer for an ask this process no longer has). */
    fun cancel(tag: String) {
        if (!installed.get()) return
        NotificationManagerCompat.from(app).cancel(tag, NOTIFICATION_ID)
    }

    /**
     * End a direct reply on [tag]. Android 15+ keeps a notification answered
     * through RemoteInput alive (FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY) and
     * ignores the app cancelling it; only an update from the app ends that.
     * Seen on the Pixel 6: after replying to question 2, question 3 was posted
     * and question 2 stayed in the shade. So update it first, then cancel.
     */
    fun endReply(tag: String) {
        if (!installed.get()) return
        val nm = NotificationManagerCompat.from(app)
        runCatching {
            nm.notify(
                tag, NOTIFICATION_ID,
                NotificationCompat.Builder(app, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification_question)
                    .setContentTitle(app.getString(R.string.user_ask_needs_answer))
                    .setSilent(true)
                    .setTimeoutAfter(1_000)
                    .build(),
            )
        }
        nm.cancel(tag, NOTIFICATION_ID)
    }

    private fun cancelAll(requestId: String) {
        val count = posted.remove(requestId) ?: UserAskCenter.asks.value[requestId]?.questions?.size ?: 4
        val nm = NotificationManagerCompat.from(app)
        for (t in UserAskNotificationPlan.allTags(requestId, count)) nm.cancel(t, NOTIFICATION_ID)
    }

    private fun post(ask: PendingAsk, sound: Boolean): Boolean {
        val c = UserAskNotificationPlan.content(ask) ?: return false
        val nm = NotificationManagerCompat.from(app)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            AppLogger.info(TAG, "[UserAsk] NOTIFY skipped req=${ask.requestId.take(8)}: notifications disabled")
            return false
        }
        val tag = UserAskNotificationPlan.tag(ask.requestId, c.index)
        val label = app.getString(
            when {
                c.highRisk -> R.string.user_ask_notif_high_risk
                c.isApproval -> R.string.user_ask_needs_approval
                else -> R.string.user_ask_needs_answer
            },
        )
        val b = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_question)
            .setContentTitle(ask.sessionTitle?.takeIf { it.isNotBlank() } ?: label)
            .setSubText(label)
            .setContentText(c.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(c.body))
            .setContentIntent(openChat(ask))
            .setAutoCancel(true)
            .setSilent(!sound)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPublicVersion(
                NotificationCompat.Builder(app, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification_question)
                    .setContentTitle(label)
                    .setContentText(app.getString(R.string.user_ask_notif_hidden_body))
                    .build(),
            )
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        ask.expiresAtMs?.let { b.setTimeoutAfter((it - System.currentTimeMillis()).coerceAtLeast(1_000)) }
        when (c.layout) {
            Layout.CONFIRM -> {
                b.addAction(button(ask, c.index, UserAskNotificationPlan.ACTION_CANCEL, app.getString(R.string.user_ask_cancel)))
                // Allow asks the system to unlock the device first (iOS
                // .authenticationRequired): no approving from a locked screen.
                b.addAction(
                    NotificationCompat.Action.Builder(
                        0, app.getString(R.string.user_ask_allow),
                        broadcast(ask, c.index, UserAskNotificationPlan.ACTION_ALLOW, mutable = false),
                    ).setAuthenticationRequired(true).build(),
                )
            }
            Layout.OPTIONS, Layout.REPLY_ONLY -> {
                val options = ask.questions[c.index].options.orEmpty()
                for (j in c.optionButtons) {
                    b.addAction(button(ask, c.index, UserAskNotificationPlan.optionActionId(j), options[j].label))
                }
                if (c.textReply) {
                    val title = app.getString(if (c.layout == Layout.REPLY_ONLY) R.string.user_ask_your_answer else R.string.user_ask_other)
                    val input = RemoteInput.Builder(REMOTE_INPUT_KEY)
                        .setLabel(app.getString(R.string.user_ask_your_answer))
                        .build()
                    b.addAction(
                        NotificationCompat.Action.Builder(
                            0, title, broadcast(ask, c.index, UserAskNotificationPlan.ACTION_OTHER, mutable = true),
                        ).addRemoteInput(input).setAllowGeneratedReplies(false).build(),
                    )
                }
            }
            Layout.OPEN_ONLY -> b.addAction(0, app.getString(R.string.user_ask_notif_open_app), openChat(ask))
        }
        // One notification per ask: clear the request's other questions.
        for (t in UserAskNotificationPlan.allTags(ask.requestId, ask.questions.size)) {
            if (t != tag) nm.cancel(t, NOTIFICATION_ID)
        }
        return try {
            nm.notify(tag, NOTIFICATION_ID, b.build())
            posted[ask.requestId] = ask.questions.size
            AppLogger.info(
                TAG,
                "[UserAsk] NOTIFY req=${ask.requestId.take(8)} q=${c.index + 1}/${ask.questions.size} layout=${c.layout} sound=$sound",
            )
            true
        } catch (se: SecurityException) {
            AppLogger.info(TAG, "[UserAsk] NOTIFY denied (POST_NOTIFICATIONS not granted)")
            false
        }
    }

    private fun button(ask: PendingAsk, index: Int, action: String, title: String) =
        NotificationCompat.Action.Builder(0, title, broadcast(ask, index, action, mutable = false)).build()

    private fun broadcast(ask: PendingAsk, index: Int, action: String, mutable: Boolean): PendingIntent {
        val intent = Intent(app, UserAskNotificationReceiver::class.java)
            .setAction("com.openminis.app.USER_ASK_ANSWER")
            // Distinct data per action, so PendingIntents never collapse into one.
            .setData(Uri.parse("minis-userask://${ask.requestId}/$index/$action"))
            .putExtra(EXTRA_REQUEST_ID, ask.requestId)
            .putExtra(EXTRA_INDEX, index)
            .putExtra(EXTRA_ACTION, action)
        // RemoteInput fills the intent in, which needs a mutable PendingIntent.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(app, "${ask.requestId}/$index/$action".hashCode(), intent, flags)
    }

    /** Opens the chat (the card is its newest message), like the task-finished notification. */
    private fun openChat(ask: PendingAsk): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("minis://session/${ask.sessionId}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            app, "open/${ask.requestId}".hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(app, NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // High: a question that times out in minutes should pop up, not wait in the shade.
        val channel = NotificationChannel(
            CHANNEL_ID, app.getString(R.string.user_ask_notif_channel_name), NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = app.getString(R.string.user_ask_notif_channel_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    private const val NOTIFICATION_ID = 7301
}
