package com.openminis.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.openminis.app.R
import com.openminis.app.backup.BackupRunController
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * [T-android-backup-fgs] Keeps a running backup alive while the app is in the
 * background: a `dataSync` foreground service with an ongoing progress
 * notification, a PARTIAL_WAKE_LOCK, and (where it still does anything) a
 * Wi-Fi lock.
 *
 * Stage 1 moved the run off the screen's `viewModelScope` onto
 * [BackupRunController]'s process scope, which fixes "leaving the screen
 * kills the backup". It does not fix "backgrounding the app kills the
 * backup" (user reports: dies on screen-off / cell handover). A backgrounded
 * process with no foreground service is fair game for the cached-app freezer
 * and Doze: coroutines stop being scheduled, sockets time out mid-upload, and
 * on a low-memory device the process is simply reclaimed. A foreground service
 * raises the process to a perceptible priority, and the wake lock keeps the CPU
 * running with the screen off.
 *
 * Lifecycle is driven by the controller, never by a screen:
 *  - [BackupRunController.start] calls [start] right after launching the run.
 *  - The service observes [BackupRunController.isRunning]; when the run ends
 *    it posts a separate, dismissible outcome notification, releases both
 *    locks and stops itself. Nothing else has to remember to stop it.
 *
 * `dataSync`, not the `mediaPlayback` type AgentForegroundService uses to
 * dodge the Android 14+ dataSync cap. A backup is exactly what dataSync means,
 * and it is finite. The cap (6 h per 24 h, cumulative across the app) is
 * still handled rather than ignored: [onTimeout] stops the service promptly,
 * because exceeding the grace period after the timeout throws
 * ForegroundServiceDidNotStopInTimeException and kills the WHOLE process, which
 * is a far worse outcome than losing the keep-alive for the tail of a very
 * long run.
 */
class BackupForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var observing = false
    private var lastPostedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppLogger.info(TAG, "[BackupFGS] stop requested from notification")
            BackupRunController.stop()
            return START_NOT_STICKY
        }
        // startForegroundService() must be answered with startForeground()
        // within ~5 s or the system kills the app — do it before anything else.
        goForeground(BackupRunController.statusText.value)
        acquireLocks()
        if (!observing) {
            observing = true
            observeRun()
        }
        // NOT_STICKY: if the process dies, there is no run to resume — a
        // restarted service would show a progress notification for nothing.
        return START_NOT_STICKY
    }

    private fun observeRun() {
        scope.launch {
            combine(BackupRunController.isRunning, BackupRunController.statusText) { running, status ->
                running to status
            }.distinctUntilChanged().collect { (running, status) ->
                if (running) {
                    updateProgress(status)
                } else {
                    finish()
                }
            }
        }
    }

    private fun goForeground(status: String?) {
        ensureChannel(this)
        val n = progressNotification(status)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(PROGRESS_ID, n)
            }
        }.onFailure {
            // Can be refused (e.g. ForegroundServiceStartNotAllowedException).
            // The backup still runs on the controller's scope; it just has no
            // background protection. Stop rather than linger unpromoted.
            AppLogger.error(TAG, "[BackupFGS] startForeground refused: ${it.message}")
            stopSelf()
        }
    }

    /**
     * Refresh the progress text. Throttled: the system silently drops
     * notification updates posted faster than a few per second, and upload
     * progress arrives per percent.
     */
    private fun updateProgress(status: String?) {
        val now = System.currentTimeMillis()
        if (now - lastPostedAt < PROGRESS_THROTTLE_MS) return
        lastPostedAt = now
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(PROGRESS_ID, progressNotification(status))
        }
    }

    private fun finish() {
        postOutcome()
        releaseLocks()
        stopForegroundCompat()
        stopSelf()
    }

    /**
     * A separate, dismissible notification for the result — the progress one
     * is removed with the foreground state. A backup finishing while the user
     * is elsewhere is exactly when they need telling where the package went.
     */
    private fun postOutcome() {
        val error = BackupRunController.errorText.value
        val (title, text) = when (outcomeOf(BackupRunController.lastResult.value, error)) {
            Outcome.LOCAL_PENDING ->
                getString(R.string.backup_notif_done_title) to getString(R.string.backup_notif_local_pending)
            Outcome.DELIVERED ->
                getString(R.string.backup_notif_done_title) to getString(R.string.backup_notif_done_text)
            Outcome.ISSUES ->
                getString(R.string.backup_notif_issues_title) to (error ?: "")
            Outcome.FAILED ->
                getString(R.string.backup_notif_failed_title) to (error ?: "")
            Outcome.STOPPED ->
                getString(R.string.backup_notif_stopped_title) to getString(R.string.backup_notif_stopped_text)
        }
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_completed)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(OUTCOME_ID, n) }
    }

    private fun progressNotification(status: String?): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, BackupForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.backup_notif_running_title))
            .setContentText(status ?: getString(R.string.backup_notif_running_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.backup_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = runCatching {
                getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Minis:Backup")
                    .apply {
                        setReferenceCounted(false)
                        // Safety cap: a lock must never outlive a wedged run.
                        // Longer than any backup should take, shorter than
                        // forever.
                        acquire(WAKE_LOCK_CAP_MS)
                    }
            }.onFailure { AppLogger.error(TAG, "[BackupFGS] wake lock failed: ${it.message}") }
                .getOrNull()
        }
        // Wi-Fi lock: keeps the radio from dropping to power-save mid-upload
        // with the screen off. WIFI_MODE_FULL_HIGH_PERF is deprecated and
        // documented as non-functional from API 34, and the low-latency mode
        // only applies while the app is in the foreground, so below 34 only.
        if (wifiLock == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            wifiLock = runCatching {
                @Suppress("DEPRECATION")
                (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Minis:Backup")
                    .apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        wifiLock = null
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    /**
     * The dataSync time cap was reached (Android 15+ calls this; 14 has the
     * single-argument form). Stop NOW: the grace period after this callback is
     * a few seconds, and missing it kills the process — which would take the
     * backup with it. The run keeps going on the controller's scope, just
     * without background protection from here on.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLogger.warning(TAG, "[BackupFGS] dataSync time limit reached — releasing keep-alive")
        releaseLocks()
        stopForegroundCompat()
        stopSelf()
    }

    @Deprecated("Replaced by onTimeout(Int, Int) on API 35")
    override fun onTimeout(startId: Int) = onTimeout(startId, 0)

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        observing = false
        super.onDestroy()
    }

    /** What the finished run amounts to, for the outcome notification. */
    internal enum class Outcome { LOCAL_PENDING, DELIVERED, ISSUES, FAILED, STOPPED }

    companion object {
        private const val TAG = "BackupForegroundService"

        /**
         * Classify a finished run. Pure, so the wording rule is testable:
         * a local-only package is never announced as "delivered", since it is
         * still inside the app and the notification is the prompt to move it.
         * A run that produced a package but reported an error is ISSUES (a
         * destination failed), and no package with an error is FAILED. No
         * package and no error means the user stopped it.
         */
        internal fun outcomeOf(result: BackupRunController.RunResult?, error: String?): Outcome = when {
            result != null && result.localOnly -> Outcome.LOCAL_PENDING
            result != null && error == null -> Outcome.DELIVERED
            result != null -> Outcome.ISSUES
            error != null -> Outcome.FAILED
            else -> Outcome.STOPPED
        }
        const val CHANNEL_ID = "backup"
        private const val PROGRESS_ID = 4201
        private const val OUTCOME_ID = 4202
        private const val ACTION_STOP = "com.openminis.app.backup.STOP"
        internal const val PROGRESS_THROTTLE_MS = 1_000L
        /** 6 h — the dataSync budget; a lock beyond it would outlive the service. */
        internal const val WAKE_LOCK_CAP_MS = 6 * 60 * 60 * 1000L

        /**
         * Promote the process for the running backup. Called by
         * [BackupRunController.start] from a user action, so the
         * background-start restriction does not apply; still guarded, because
         * a refused start must never stop the backup itself.
         */
        fun start(context: Context) {
            runCatching {
                val i = Intent(context, BackupForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            }.onFailure {
                AppLogger.error(TAG, "[BackupFGS] could not start: ${it.message}")
            }
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.backup_notif_channel),
                    // LOW: visible and persistent, but no sound or heads-up
                    // for every progress refresh.
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }
}

// ─── Merged from DynamicIslandSupport.kt ──────────────────────────────────────────────────

/**
 * [T-android-dynamic-island] Capability probe for Android 16 (Baklava, API 36)
 * "Live Updates" — the promoted-ongoing-notification surface users perceive as
 * the "dynamic island" / always-visible status chip.
 *
 * A device "supports the dynamic island" only when BOTH hold:
 *   1. It runs Android 16+ (`Build.VERSION.SDK_INT >= 36`), so the
 *      `Notification.ProgressStyle` + `FLAG_PROMOTED_ONGOING` +
 *      `NotificationManager.canPostPromotedNotifications()` APIs exist, AND
 *   2. `NotificationManager.canPostPromotedNotifications()` returns true — this
 *      reflects the *system + per-app user grant* (the user can disable Live
 *      Updates for this app from system settings), so it can flip at runtime.
 *
 * The probe is cached only briefly (see [capableCache]): callers re-query it
 * each time the app becomes foreground-visible (spec §3) so a permission the
 * user toggled off in system settings is picked up without needing a process
 * restart.
 *
 * As of 2026-07 this only actually returns true on Pixel 6+ hardware running
 * the Android 16 QPR that shipped Live Updates; on every other device the guard
 * short-circuits at the SDK_INT check (pre-16) or `canPostPromotedNotifications`
 * (16 without the feature), so all the mutual-exclusion logic degrades cleanly
 * to the existing overlay + plain-notification behavior.
 */
object DynamicIslandSupport {

    private const val TAG = "DynamicIslandSupport"

    /**
     * True when this device can post promoted ("dynamic island") notifications
     * right now. Runtime-safe on all API levels — returns false pre-36 without
     * touching any 36-only symbol.
     */
    /**
     * [T-android-island-capability-cache] Memoized result of the capability
     * probe below. `null` until first asked.
     *
     * The probe is a binder round trip (`getSystemService` +
     * `canPostPromotedNotifications`). It was being re-asked TWICE per second for as long as a
     * task ran: the chip ticker calls `isPromotedChipActive()` each tick and
     * `buildPromotedNotification()` asks again while building the very
     * notification that tick posts. With decorative animations fixed, those
     * binder calls were the single largest remaining app-side cost in a
     * profile of a running tool (24 samples, the top two app frames).
     *
     * [T-android-island-capability-ttl] It CAN change while the process
     * lives: the answer reflects the per-app Live Updates grant the user
     * toggles in system settings (see the class KDoc). Cached forever, a
     * revoke left applyOverlayState suppressing the floating capsule while the
     * system refused the promoted chip — neither surface showed — and a later
     * grant stayed invisible until the process died. So the cache now expires
     * after [CAPABILITY_TTL_MS] and can be dropped explicitly via
     * [invalidateCapabilityCache] (the settings screen's ON_RESUME re-probe).
     * At 2 asks/second a 5 s TTL still removes ~90% of the binder calls the
     * memoization was introduced to save.
     */
    @Volatile
    private var capableCache: Boolean? = null

    /** `SystemClock.elapsedRealtime()` at which [capableCache] was filled. */
    @Volatile
    private var capableCachedAtMs: Long = 0L

    private const val CAPABILITY_TTL_MS = 5_000L

    /** Forget the cached capability so the next ask re-probes the platform. */
    fun invalidateCapabilityCache() {
        capableCache = null
    }

    private fun cacheCapability(capable: Boolean): Boolean {
        capableCachedAtMs = android.os.SystemClock.elapsedRealtime()
        capableCache = capable
        return capable
    }

    fun isDynamicIslandCapable(context: Context): Boolean {
        // Pre-36 is a property of the OS build and truly cannot flip; no
        // binder call is involved, so nothing to cache.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        capableCache?.let { cached ->
            val age = android.os.SystemClock.elapsedRealtime() - capableCachedAtMs
            if (age in 0 until CAPABILITY_TTL_MS) return cached
        }
        return try {
            val nm = context.getSystemService(NotificationManager::class.java)
            cacheCapability(nm?.canPostPromotedNotifications() == true)
        } catch (t: Throwable) {
            // Defensive: some early/partial Baklava builds may throw if the
            // feature isn't fully wired. Treat any failure as "not capable"
            // so we fall back to the overlay + plain-notification path.
            Log.w(TAG, "canPostPromotedNotifications() failed: ${t.message}")
            cacheCapability(false)
        }
    }

    /**
     * True when the Live Updates / dynamic-island experience should be the
     * ACTIVE status surface: the device is capable AND the user enabled the
     * toggle. This is the single predicate that (a) selects the promoted
     * ProgressStyle notification branch and (b) short-circuits the floating
     * overlay so the two never render at once.
     */
    fun isDynamicIslandActive(context: Context, userEnabled: Boolean): Boolean =
        userEnabled && isDynamicIslandCapable(context)
}

// ─── Merged from OAuthWaitService.kt ──────────────────────────────────────────────────

/**
 * [T-oauth-wait-fgs] A `dataSync` foreground service that exists only while an
 * OAuth sign-in waits for its localhost redirect - see [OAuthWaitKeepAlive] for
 * why. It does no work; being in the foreground is the whole point.
 *
 * Its own type rather than AgentForegroundService (mediaPlayback, which a
 * sign-in is not) or BackupForegroundService (whose lifecycle is a backup's).
 *
 * Lifecycle: [OAuthWaitKeepAlive.begin] starts it for every flow; it stops
 * ITSELF when [OAuthWaitKeepAlive.active] reaches 0 or the last flow passes its
 * deadline. Nobody calls stopService: stopping a service between
 * startForegroundService and its startForeground is exactly the "did not then
 * call startForeground" crash, and stopSelfResult with the latest start id
 * cannot race a start that is already on its way.
 *
 * Compatibility (minSdk 26, targetSdk 35):
 *  - 26-28: ServiceCompat.startForeground drops the type; the channel is
 *    created first, idempotently.
 *  - 29+: started with FOREGROUND_SERVICE_TYPE_DATA_SYNC, matching the manifest
 *    (required from targetSdk 34). Not shortService: that is 34+ only and
 *    capped at 3 minutes, shorter than a sign-in may take.
 *  - 31+: a refused start (ForegroundServiceStartNotAllowedException) is caught
 *    in [start] and in [onStartCommand]; the sign-in continues unprotected.
 *  - 33+: a denied POST_NOTIFICATIONS only hides the notification; the service
 *    still runs, and this flow never asks for the permission.
 *  - 35: the dataSync budget is handled in [onTimeout] by stopping at once.
 */
class OAuthWaitService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Every startForegroundService must be answered with startForeground
        // within ~5 s, even one delivered after the flow already ended - so
        // this comes first, before any other work, and only then do we decide
        // whether to stay.
        if (!goForeground()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val n = OAuthWaitKeepAlive.prune()
        if (n == 0) {
            AppLogger.info(TAG, "[OAuthWait] service started after the flows ended - stopping at once")
            stopNow("no-active-flows")
            return START_NOT_STICKY
        }
        AppLogger.info(TAG, "[OAuthWait] service foreground active=$n startId=$startId")
        if (!observing) {
            observing = true
            observe()
        }
        // NOT_STICKY: after process death there is no flow left to protect;
        // a restarted service would only show a stale notification.
        return START_NOT_STICKY
    }

    private fun observe() {
        scope.launch {
            OAuthWaitKeepAlive.active.collectLatest { n ->
                if (n == 0) {
                    stopNow("all-flows-ended")
                    return@collectLatest
                }
                // Sleep to the earliest deadline, then release what expired;
                // the count change restarts this block.
                val next = OAuthWaitKeepAlive.nextDeadline() ?: return@collectLatest
                delay((next - OAuthWaitKeepAlive.now()).coerceAtLeast(0L) + 1)
                OAuthWaitKeepAlive.prune()
            }
        }
    }

    /** True when the service is now in the foreground. */
    private fun goForeground(): Boolean {
        ensureChannel(this)
        return try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(),
                // Ignored below API 29 by ServiceCompat.
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (31+) is an
            // IllegalStateException; a missing FGS-type permission is a
            // SecurityException. Either way: no protection, sign-in unaffected.
            AppLogger.error(TAG, "[OAuthWait] startForeground refused: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun stopNow(reason: String) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Only if no newer start is on its way; that one re-evaluates.
        val stopped = stopSelfResult(lastStartId)
        AppLogger.info(TAG, "[OAuthWait] service stop reason=$reason stopped=$stopped")
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.oauth_wait_notif_title))
            .setContentText(getString(R.string.oauth_wait_notif_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .build()

    private fun openAppIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * Swiping Minis away also closes the Custom Tab, which lives in Minis's
     * task: no redirect can come any more, so stop instead of holding a
     * notification for a sign-in that is gone.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        OAuthWaitKeepAlive.clear("task-removed")
        stopNow("task-removed")
        super.onTaskRemoved(rootIntent)
    }

    /**
     * The dataSync time budget is used up (Android 15+). Stop NOW: missing
     * the short grace period after this callback kills the whole process.
     * A sign-in (minutes) should never get here.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLogger.warning(TAG, "[OAuthWait] dataSync time limit reached - stopping")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Deprecated("Replaced by onTimeout(Int, Int) on API 35")
    override fun onTimeout(startId: Int) = onTimeout(startId, 0)

    override fun onDestroy() {
        scope.cancel()
        observing = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OAuthWait"
        const val CHANNEL_ID = "oauth_wait"
        private const val NOTIFICATION_ID = 4301

        /**
         * Called from [OAuthWaitKeepAlive.begin], i.e. from the user's Sign In
         * tap with Minis in the foreground. Still guarded: on 31+ a start from
         * the background throws ForegroundServiceStartNotAllowedException, and
         * nothing here may break the sign-in.
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, OAuthWaitService::class.java))
            } catch (e: Exception) {
                val kind = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is android.app.ForegroundServiceStartNotAllowedException
                ) "ForegroundServiceStartNotAllowedException" else e.javaClass.simpleName
                AppLogger.error(TAG, "[OAuthWait] could not start service ($kind): ${e.message} - sign-in continues without it")
            }
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.oauth_wait_notif_channel),
                    // LOW: visible, silent, no heads-up.
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }
}

// ─── Merged from SessionBadgeStore.kt ──────────────────────────────────────────────────

/**
 * [T-android-session-paused-badge] Per-session badge-state queue shown in the
 * session-list cell's icon corner.
 *
 * Architecture mirror of the iOS design:
 * every session holds an ordered queue of [SessionBadgeState] values. The head
 * of the queue is what the cell renders; pushing a new state (e.g. [PAUSED]
 * when a background interruption is detected) prepends to the queue so it
 * shows first. When the user enters the session the [PAUSED] state is removed
 * — any underlying state (future: [ICLOUD_SYNCING]) re-surfaces. The store is
 * persisted to SharedPreferences so a process restart preserves badges.
 *
 * Only [PAUSED] has a wired producer today; [ICLOUD_SYNCING] is reserved for
 * the upcoming Android iCloud-equivalent sync surface and is plumbed through
 * the same data path so adding it later is a one-line emit.
 */
object SessionBadgeStore {
    private const val TAG = "SessionBadgeStore"
    private const val PREFS = "session_badge_store"

    /**
     * v1 format: `"id1=PAUSED,ICLOUD_SYNCING;id2=PAUSED"` — states only, no
     * timestamps. Still READ (once, at init) so an upgrading install keeps its
     * badges, never WRITTEN again.
     */
    private const val KEY = "badge_state_by_session"

    /**
     * [T-android-group-pause-badge-restamp] v2 format:
     * `"id1=PAUSED:1723449600000,ICLOUD_SYNCING:0;id2=PAUSED:1723449600000"`
     * — each state carries the epoch-millis at which the session ENTERED it,
     * `0` meaning "unknown age" (see below).
     *
     * Why a NEW key instead of extending the old one in place: the two formats
     * then coexist without either parser having to guess which shape it is
     * looking at, and an accidental downgrade (user rolls back to an older
     * build) still finds an intact v1 blob rather than a string full of
     * `PAUSED:172…` tokens that `valueOf` would drop, silently wiping every
     * badge. The v1 key is read once at init as a fallback and left untouched
     * on disk; v2 becomes authoritative the moment it exists.
     *
     * Migration semantics: a badge restored from v1 has UNKNOWN entry time, so
     * it is imported with NO timestamp and counts as STALE for the group-card
     * freshness window. That matches iOS ("A state with no recorded timestamp
     * — legacy persisted queues predate stamping — counts as stale: silencing
     * unknown-age leftovers is the point of the window"). Session ROWS still
     * render it, unfiltered, exactly as before — nothing visible is lost.
     */
    private const val KEY_V2 = "badge_state_by_session_v2"

    /** Sentinel written for "state present, entry time unknown" (legacy import). */
    private const val NO_STAMP = 0L

    enum class SessionBadgeState {
        /** Background-interrupted while a stream was active; shown until the user re-opens the session. */
        PAUSED,
        /** Reserved for the upcoming sync-status badge (mirroring iOS iCloud). Not produced yet. */
        ICLOUD_SYNCING,
    }

    @Volatile private var prefs: SharedPreferences? = null

    // sessionId -> ordered queue (head = currently displayed).
    private val _byId = MutableStateFlow<Map<String, List<SessionBadgeState>>>(emptyMap())
    val byId: StateFlow<Map<String, List<SessionBadgeState>>> = _byId.asStateFlow()

    /**
     * sessionId -> state -> epoch-millis at which the session LAST ENTERED that
     * state. Mirrors iOS `badgeTimestamps`. Mutated in lockstep with [_byId]
     * under the same lock; UI refresh rides [byId] plus [revision] (the latter
     * covering stamp-only changes). A badge that crosses the freshness window
     * purely by TIME ELAPSING is picked up on the next ambient list
     * recomposition rather than by a dedicated timer — same as iOS.
     *
     * A state present in [_byId] but ABSENT here = unknown age = stale (see
     * [KEY_V2]).
     */
    @Volatile
    private var stamps: Map<String, Map<SessionBadgeState, Long>> = emptyMap()

    /**
     * Bumped whenever a stamp is written or dropped. Exposed through [revision]
     * so a re-stamp that leaves the queue map `equals`-identical still reaches
     * the UI: `MutableStateFlow` conflates equal values, so re-stamping a badge
     * already at the head of its queue would otherwise emit nothing — and that
     * case matters, because a badge that had aged PAST the freshness window and
     * is then genuinely re-interrupted must flip its group card's `anyPaused`
     * back on.
     */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val (restored, restoredStamps) = loadFromDisk(p)
        synchronized(this) {
            _byId.value = restored
            stamps = restoredStamps
        }
        Log.d(TAG, "init: restored ${restored.size} sessions with badges, ${restoredStamps.size} stamped")
    }

    /** Head of the queue, or null when empty. Render this in the cell. */
    fun headFor(sessionId: String): SessionBadgeState? =
        _byId.value[sessionId]?.firstOrNull()

    /**
     * Push a state to the head of [sessionId]'s queue.
     *
     * [restamp] distinguishes the two ways a state gets pushed, which the
     * caller knows and this store cannot infer (mirrors iOS
     * `pushFront(_:for:restamp:)`):
     *
     *  - `true` (default) — a REAL new entry into the state (an actual
     *    interruption just happened). The freshness window keys off the last
     *    genuine entry, so this OVERWRITES any existing stamp.
     *  - `false` — the state was merely RE-DETECTED for a session already in
     *    it (loading an old chat whose DB tail still looks interrupted, a
     *    cold-start scan, a background pre-warm). Keep the existing stamp;
     *    only stamp now when none survives.
     *
     * Why it matters: the group card only passes states entered within the
     * last 24h. If every push re-stamped, merely opening a chat interrupted
     * days ago would reset its stamp to now and a long-stale pause would flag
     * its whole group forever.
     *
     * NOTE the deliberate absence of the old `if (q.firstOrNull() == state)
     * return` early-exit: a genuine RE-interruption of a session whose PAUSED
     * badge is already at the head must still refresh the timestamp. The queue
     * shape is unchanged in that case, but the stamp is the whole point.
     */
    fun push(sessionId: String, state: SessionBadgeState, restamp: Boolean = true) {
        mutate { current ->
            val q = current[sessionId].orEmpty()
            val existing = stamps[sessionId]?.get(state)
            if (restamp || existing == null) {
                putStamp(sessionId, state, System.currentTimeMillis())
            }
            if (q.firstOrNull() == state) {
                // Queue unchanged — but a stamp may have been written above, so
                // return a COPY rather than the identity, which is what makes
                // `mutate` re-persist. `byId` itself won't re-emit (an equal
                // map is conflated); [revision], bumped by putStamp, is what
                // carries the change to the UI in that case.
                return@mutate if (restamp || existing == null) HashMap(current) else current
            }
            // De-dupe — if the same state is already elsewhere in the queue,
            // pull it forward instead of stacking duplicates.
            val deduped = q.filter { it != state }
            current + (sessionId to (listOf(state) + deduped))
        }
    }

    /** Remove [state] from [sessionId]'s queue; drop the key when empty. */
    fun remove(sessionId: String, state: SessionBadgeState) {
        mutate { current ->
            val q = current[sessionId] ?: return@mutate current
            val updated = q.filter { it != state }
            dropStamp(sessionId, state)
            if (updated.isEmpty()) current - sessionId else current + (sessionId to updated)
        }
    }

    /** Drop everything for [sessionId] (e.g. session deletion). */
    fun clear(sessionId: String) {
        mutate { current ->
            if (!current.containsKey(sessionId)) return@mutate current
            if (sessionId in stamps) {
                stamps = stamps - sessionId
                _revision.value += 1
            }
            current - sessionId
        }
    }

    /**
     * [T-android-group-pause-badge-restamp] Session ids carrying a non-unread
     * corner badge ENTERED within [windowMillis] of now. This is the
     * GROUP-CARD passthrough filter only — session rows render their badge
     * unfiltered regardless of age.
     *
     * Mirrors iOS `freshCornerBadgeSessionIds(within:)`. iOS skips its
     * `.unread` case here because that renders as a separate top-trailing dot;
     * Android's enum has no UNREAD member (PAUSED / ICLOUD_SYNCING are both
     * corner badges), so every queued state participates and no filter is
     * needed — if UNREAD is ever added it must be excluded here.
     *
     * A state with no recorded stamp (imported from the v1 persist format)
     * counts as STALE: silencing unknown-age leftovers is the point of the
     * window. The cutoff is computed ONCE for the whole pass, and the result
     * doubles as a recomposition key for the grouping pass.
     */
    fun freshCornerBadgeSessionIds(windowMillis: Long): Set<String> {
        val cutoff = System.currentTimeMillis() - windowMillis
        val states = _byId.value
        val ts = stamps
        if (states.isEmpty()) return emptySet()
        val out = HashSet<String>()
        for ((sid, queue) in states) {
            val perState = ts[sid] ?: continue
            // `perState[st] == null` → unknown age → stale, never fresh.
            if (queue.any { st -> (perState[st] ?: Long.MIN_VALUE) >= cutoff }) {
                out.add(sid)
            }
        }
        return out
    }

    /**
     * [T-android-session-paused-badge-hardkill] Reconcile [PAUSED] badges against
     * the authoritative set of currently-interrupted sessions (derived from the
     * persisted message tail by the repository). Run once at launch so a session
     * hard-killed (force-quit / process death) while running — which never hit
     * the lifecycle-callback push path — still shows the badge after restart, and
     * a session that is NO longer interrupted gets its stale badge cleared.
     *
     * Only touches [PAUSED]; other queued states are left intact. Mirrors iOS
     * SessionBadgeStore.reconcileInterruptedSessions.
     */
    fun reconcileInterruptedSessions(interruptedIds: Set<String>) {
        mutate { current ->
            val next = current.toMutableMap()
            // Add PAUSED for interrupted sessions that don't have it yet.
            for (sid in interruptedIds) {
                val q = next[sid].orEmpty()
                if (q.firstOrNull() != SessionBadgeState.PAUSED && !q.contains(SessionBadgeState.PAUSED)) {
                    next[sid] = listOf(SessionBadgeState.PAUSED) + q
                    // Reconcile RESTORES a marker after a hard kill; it is not
                    // a new entry into the paused state, so an existing stamp
                    // is PRESERVED. Only stamp now when none survives (the
                    // original push never happened/persisted) — the best
                    // available approximation of the entry time. Mirrors iOS
                    // reconcileInterruptedSessions.
                    if (stamps[sid]?.get(SessionBadgeState.PAUSED) == null) {
                        putStamp(sid, SessionBadgeState.PAUSED, System.currentTimeMillis())
                    }
                }
            }
            // Remove PAUSED from sessions no longer interrupted.
            val iterIds = next.keys.toList()
            for (sid in iterIds) {
                val q = next[sid] ?: continue
                if (q.contains(SessionBadgeState.PAUSED) && !interruptedIds.contains(sid)) {
                    val updated = q.filter { it != SessionBadgeState.PAUSED }
                    dropStamp(sid, SessionBadgeState.PAUSED)
                    if (updated.isEmpty()) next.remove(sid) else next[sid] = updated
                }
            }
            next
        }
        Log.d(TAG, "reconcileInterruptedSessions: ${interruptedIds.size} interrupted")
    }

    // ── internals ────────────────────────────────────────────────────────

    /** Write one stamp. Callers hold the [mutate] lock. */
    private fun putStamp(sessionId: String, state: SessionBadgeState, at: Long) {
        val perState = stamps[sessionId].orEmpty() + (state to at)
        stamps = stamps + (sessionId to perState)
        _revision.value += 1
    }

    /** Drop one stamp, pruning the session key when it empties. Lock held. */
    private fun dropStamp(sessionId: String, state: SessionBadgeState) {
        val perState = stamps[sessionId] ?: return
        if (state !in perState) return
        val updated = perState - state
        stamps = if (updated.isEmpty()) stamps - sessionId else stamps + (sessionId to updated)
        _revision.value += 1
    }

    private fun mutate(block: (Map<String, List<SessionBadgeState>>) -> Map<String, List<SessionBadgeState>>) {
        synchronized(this) {
            val prev = _byId.value
            val next = block(prev)
            // Identity means "nothing changed at all" — including stamps, which
            // `push` signals by handing back a copy when it wrote one under an
            // otherwise-unchanged queue.
            if (next === prev) return
            _byId.value = next
            prefs?.let { persistToDisk(it, next) }
        }
    }

    private fun persistToDisk(p: SharedPreferences, map: Map<String, List<SessionBadgeState>>) {
        // v2 encoding: "id1=PAUSED:1723449600000,ICLOUD_SYNCING:0;id2=PAUSED:0".
        // Plain text is still fine — SharedPreferences atomicity covers the
        // whole string; entries are small (a few hundred sessions × short enum
        // name + 13-digit millis) so a JSON dependency would be overkill.
        // `:0` encodes "no stamp" (legacy import) and decodes back to absent.
        val ts = stamps
        val encoded = map.entries.joinToString(";") { (id, q) ->
            val perState = ts[id]
            "$id=" + q.joinToString(",") { "${it.name}:${perState?.get(it) ?: NO_STAMP}" }
        }
        runCatching { p.edit().putString(KEY_V2, encoded).apply() }
            .onFailure { Log.w(TAG, "persist failed: ${it.message}") }
    }

    private fun loadFromDisk(
        p: SharedPreferences,
    ): Pair<Map<String, List<SessionBadgeState>>, Map<String, Map<SessionBadgeState, Long>>> {
        val rawV2 = runCatching { p.getString(KEY_V2, null) }.getOrNull()
        if (!rawV2.isNullOrBlank()) return decodeV2(rawV2)
        // No v2 blob: either a fresh install (v1 also absent → empty) or an
        // upgrade from a build that only ever wrote v1. Import the v1 queues so
        // no badge is lost; they carry NO stamps, i.e. unknown age → stale for
        // the group-card window, which is the documented choice.
        val rawV1 = runCatching { p.getString(KEY, null) }.getOrNull()
        if (rawV1.isNullOrBlank()) return emptyMap<String, List<SessionBadgeState>>() to emptyMap()
        val migrated = decodeV1(rawV1)
        Log.d(TAG, "loadFromDisk: migrated ${migrated.size} sessions from v1 (unstamped → stale)")
        return migrated to emptyMap()
    }

    private fun decodeV2(
        raw: String,
    ): Pair<Map<String, List<SessionBadgeState>>, Map<String, Map<SessionBadgeState, Long>>> {
        val queues = LinkedHashMap<String, List<SessionBadgeState>>()
        val ts = LinkedHashMap<String, Map<SessionBadgeState, Long>>()
        for (entry in raw.split(';')) {
            val eq = entry.indexOf('=')
            if (eq <= 0 || eq == entry.lastIndex) continue
            val id = entry.substring(0, eq)
            val q = mutableListOf<SessionBadgeState>()
            val perState = LinkedHashMap<SessionBadgeState, Long>()
            for (token in entry.substring(eq + 1).split(',')) {
                // "NAME:millis"; a bare "NAME" (defensive — a v1 string that
                // somehow landed under the v2 key) decodes as unstamped.
                val colon = token.lastIndexOf(':')
                val name = if (colon > 0) token.substring(0, colon) else token
                val state = runCatching { SessionBadgeState.valueOf(name) }.getOrNull() ?: continue
                q.add(state)
                val at = if (colon > 0) token.substring(colon + 1).toLongOrNull() ?: NO_STAMP else NO_STAMP
                if (at != NO_STAMP) perState[state] = at
            }
            if (q.isEmpty()) continue
            queues[id] = q
            if (perState.isNotEmpty()) ts[id] = perState
        }
        return queues to ts
    }

    private fun decodeV1(raw: String): Map<String, List<SessionBadgeState>> =
        raw.split(';').mapNotNull { entry ->
            val eq = entry.indexOf('=')
            if (eq <= 0 || eq == entry.lastIndex) return@mapNotNull null
            val id = entry.substring(0, eq)
            val q = entry.substring(eq + 1)
                .split(',')
                .mapNotNull { name -> runCatching { SessionBadgeState.valueOf(name) }.getOrNull() }
            if (q.isEmpty()) null else id to q
        }.toMap()
}
