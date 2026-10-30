package com.openminis.app.scheduled

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.openminis.app.MinisApp
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.AgentForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * [T-android-scheduled-tasks-design] Headless agent launch for scheduled
 * tasks. Mirrors the shape of iOS SendPromptIntent.perform():
 *
 *  - resolve a session id (NEW_SESSION → create row; APPEND_TO → reuse)
 *  - run the prompt through the existing agent loop (via [HeadlessChatRunner])
 *  - (the completion notification is BackgroundTaskNotifier's, posted when
 *    the run's agent loop ends - [T-port-sched-notif-single])
 *  - hand the result preview back to [ScheduledTaskManager] for the row
 *
 * Concurrency: serialised per-session by [HeadlessChatRunner]'s VM cache —
 * two scheduled prompts hitting the same session id are queued via
 * ChatViewModel.enqueuePrompt.
 */
object ScheduledAgentRunner {

    private const val TAG = "ScheduledAgentRunner"
    private const val RUN_TIMEOUT_MS = 10 * 60 * 1000L  // 10 min ceiling

    /**
     * [T-new-limits] How long a child-of-current fire waits for its parent to
     * have a free sub agent slot before the fire is skipped (logged). Generous
     * because a busy parent's agents usually finish within their 10 min default
     * budget; short enough that a stuck parent does not pin a fire forever.
     */
    internal const val CHILD_SLOT_WAIT_MS = 30 * 60 * 1000L

    /**
     * [T-new-limits] Whether a fire of [task] must wait for a sub agent slot:
     * only a child-of-current task, and only while its parent already runs
     * AgentJobRegistry.maxConcurrentChildJobsPerParent sub agents. Pure.
     */
    internal fun needsChildSlot(task: ScheduledTask): String? {
        val mode = task.targetMode as? ScheduledTargetMode.ChildOfCurrent ?: return null
        return mode.sessionId.takeIf { !com.openminis.app.agent.jobs.AgentJobRegistry.canStartChildJob(it) }
    }

    /**
     * App-scoped scope for fire-and-forget completion work when a caller asks
     * NOT to wait (the "Run now" button). Outlives the editor screen so the
     * agent loop + completion notification finish even after the user leaves.
     */
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fire a scheduled task.
     *
     * @param waitForCompletion when true, suspend until the agent loop
     *   finishes, then mark-fired + post the completion notification before
     *   returning. When false, return as soon as the session is resolved and
     *   the prompt is dispatched — the agent keeps running in the background
     *   and the completion (mark-fired + notification) is finished off an
     *   app-scoped coroutine. Mirrors iOS SendPromptIntent's waitForResult
     *   flag.
     *
     *   [GH#197] NEVER pass true from a BroadcastReceiver. Waiting here can
     *   take up to [RUN_TIMEOUT_MS] (10 min) and the wait lands on the main
     *   thread (HeadlessChatRunner.prompt/retry are
     *   `withContext(Dispatchers.Main)`), so a receiver that waits blows its
     *   ~10s broadcast budget and gets the whole process ANR-killed, along
     *   with every PRoot sandbox child. The alarm path passing the default
     *   `true` was exactly that bug. Waiting is only safe off a broadcast —
     *   e.g. the minis-scheduled CLI, which runs in its own offload thread.
     * @return the session id once the action has been DISPATCHED (resolved +
     *   prompt sent), or null when the runner couldn't even start (no provider,
     *   target chat gone, MinisApp not initialized).
     * @param scheduledFire true for a fire of the task's own schedule (its
     *   alarm, or its on-completion trigger); false for "Run now". Only a
     *   scheduled fire can finish the schedule and release the tasks waiting
     *   on it ([ScheduledCompletionTriggers.onScheduledRunFinished]).
     */
    suspend fun run(
        context: Context,
        task: ScheduledTask,
        waitForCompletion: Boolean = true,
        scheduledFire: Boolean = false,
    ): String? {
        // [T-android-scheduled-lateinit-crash-156] `as? MinisApp` only rules out
        // a null / wrong-type Application — it does NOT mean the Application is
        // INITIALIZED, which is what the old comment here claimed. Every
        // `app.chatRepository` read below goes through a lateinit getter that
        // throws UninitializedPropertyAccessException when onCreate
        // early-returned (safe-mode) or aborted partway.
        //
        // GH#156: an alarm fires while the process is in that state and the app
        // crashes. A scheduled task is the worst case for this because the alarm
        // can START the process — Android creates the Application, onCreate
        // early-returns under safe-mode, and the receiver runs against a
        // permanently half-built app with no Activity anywhere in the picture
        // (so MainActivity's guard never gets a say).
        //
        // Skipping the run is the right degradation: the task stays scheduled
        // and its next occurrence was already armed by the receiver before this
        // call, so a skipped fire self-heals on the following launch.
        val app = context.applicationContext as? MinisApp ?: run {
            AppLogger.error(TAG, "Application is not MinisApp — skipping task ${task.id}")
            return null
        }
        if (!app.subsystemsReady()) {
            AppLogger.error(
                TAG,
                "MinisApp subsystems not initialized (safe-mode or failed init) — skipping task ${task.id}",
            )
            return null
        }

        // Kick the FGS so the agent loop survives Doze / screen-off. The
        // existing service is idempotent and reused by chat UI; we pass a
        // generic status string so it shows up in the ongoing notification.
        AgentForegroundService.startService(
            context = app,
            sessionCount = 1,
            toolStatus = "Scheduled: ${task.label.ifBlank { "task" }}",
        )

        // [T-new-limits] A child-of-current run counts against its parent's
        // sub agent cap (AgentJobRegistry.MAX_CONCURRENT_CHILD_JOBS_PER_PARENT)
        // like any delegation. It used to register without checking, so a fire
        // landing on a parent with 3 running agents silently made it 4. There
        // is no tool call to answer "queued" to, so the fire is DEFERRED until
        // a slot frees (at most CHILD_SLOT_WAIT_MS, then skipped and logged),
        // and nothing is created meanwhile (no orphan child session).
        //  - waitForCompletion=false (the alarm receiver, Run now, triggers):
        //    the wait and the re-run happen on bgScope and this returns at once,
        //    so a receiver's ~10 s goAsync budget is never spent waiting
        //    (GH#197). Returns the PARENT session id: the fire is accepted and
        //    its result will land there.
        //  - waitForCompletion=true (the CLI, on its own offload thread): waits
        //    inline.
        needsChildSlot(task)?.let { parentSid ->
            val registry = com.openminis.app.agent.jobs.AgentJobRegistry
            AppLogger.info(
                TAG,
                "task ${task.id}: parent ${parentSid.take(8)} runs " +
                    "${registry.runningChildJobCount(parentSid)}/${registry.maxConcurrentChildJobsPerParent} " +
                    "sub agents — deferring this fire until a slot frees (wait=$waitForCompletion)",
            )
            if (!waitForCompletion) {
                bgScope.launch {
                    if (registry.awaitChildSlot(parentSid, CHILD_SLOT_WAIT_MS)) {
                        run(context, task, waitForCompletion = false, scheduledFire = scheduledFire)
                    } else {
                        AppLogger.warning(TAG, "task ${task.id}: no sub agent slot freed in ${CHILD_SLOT_WAIT_MS / 60_000} min for parent ${parentSid.take(8)} — fire skipped, cap not exceeded")
                    }
                }
                return parentSid
            }
            if (!registry.awaitChildSlot(parentSid, CHILD_SLOT_WAIT_MS)) {
                AppLogger.warning(TAG, "task ${task.id}: no sub agent slot freed in ${CHILD_SLOT_WAIT_MS / 60_000} min for parent ${parentSid.take(8)} — fire skipped, cap not exceeded")
                return null
            }
        }

        // [T-scheduled-task-detail] One timestamp for this fire, written into
        // its envelope and its run record so the two can be matched.
        val firedAt = System.currentTimeMillis()
        val sessionId = withContext(Dispatchers.IO) {
            resolveSessionId(app, task)
        } ?: return null

        AppLogger.info(
            TAG,
            "running task=${task.id} label=\"${task.label}\" " +
                "mode=${task.targetMode.encode()} session=$sessionId wait=$waitForCompletion",
        )

        // [T-scheduled-active-session-android] The fire counts as running until
        // its loop ends, so its chat stays processing through the handoff and
        // through the last fire of a task the alarm already disabled.
        ScheduledSessionHold.beginRun(task.id)
        if (waitForCompletion) {
            val result = try {
                dispatch(app, task, sessionId, wait = true, firedAt = firedAt, scheduledFire = scheduledFire)
            } finally {
                ScheduledSessionHold.endRun(task.id)
            }
            // [T-port-sched-notif-single] Blank -> null: the detail sheet hides
            // an empty preview, where the old hard-coded placeholder showed as
            // untranslated English.
            val preview = (result.responseText ?: "").take(200).ifBlank { null }
            val ok = result.status != "Error" && result.status != "Timeout" && result.status != "Rejected"
            ScheduledTaskManager(app).markFired(task.id, sessionId, preview, ok = ok, firedAt = firedAt)
            // [T-android-scheduled-triggers] Tasks waiting on this one run
            // once its whole schedule is over.
            if (scheduledFire) ScheduledCompletionTriggers.onScheduledRunFinished(app, task.id, result.responseText)
            return sessionId
        }

        // Fire-and-forget: the session is resolved and the FGS is up, so the
        // task HAS started. Run the actual dispatch + completion entirely off
        // the app scope (wait=true so we still mark-fired + notify when it
        // finishes), and return the session id immediately so the UI can show
        // "task started" without blocking on the agent loop. Leaving the editor
        // can't cancel it because bgScope outlives the screen.
        bgScope.launch {
            val result = try {
                dispatch(app, task, sessionId, wait = true, firedAt = firedAt, scheduledFire = scheduledFire)
            } finally {
                ScheduledSessionHold.endRun(task.id)
            }
            // [T-port-sched-notif-single] Blank -> null: the detail sheet hides
            // an empty preview, where the old hard-coded placeholder showed as
            // untranslated English.
            val preview = (result.responseText ?: "").take(200).ifBlank { null }
            val ok = result.status != "Error" && result.status != "Timeout" && result.status != "Rejected"
            ScheduledTaskManager(app).markFired(task.id, sessionId, preview, ok = ok, firedAt = firedAt)
            // [T-android-scheduled-triggers] Tasks waiting on this one run
            // once its whole schedule is over.
            if (scheduledFire) ScheduledCompletionTriggers.onScheduledRunFinished(app, task.id, result.responseText)
        }
        return sessionId
    }

    /**
     * [T-android-scheduled-tasks-full] Branch on target mode, mirroring the iOS
     * App Intent set:
     *   NewSession / AppendToSession → HeadlessChatRunner.prompt (the prompt is
     *     sent as a fresh user turn; for append it lands in the existing
     *     session, for new it's the first turn of the freshly-created one).
     *   RerunMessage → HeadlessChatRunner.retry from the chosen message id (the
     *     message is replayed; task.prompt is ignored, matching iOS
     *     RetryRunIntent which has no prompt param).
     */
    private suspend fun dispatch(
        app: MinisApp,
        task: ScheduledTask,
        sessionId: String,
        wait: Boolean,
        firedAt: Long,
        scheduledFire: Boolean,
    ): HeadlessChatRunner.PromptResult = runCatching {
        // [T-android-scheduled-task-card] Wrap the prompt so the transcript can
        // tell it apart from something the user typed. Without this the fired
        // message is an ordinary user bubble: the user sees text they never
        // wrote, with nothing saying which task produced it. The prompt follows
        // the tag verbatim, so a reader that does not know the tag still shows
        // the text rather than an empty bubble.
        //
        // RerunMessage is deliberately NOT wrapped — it replays an existing
        // message rather than sending a new one, so there is nothing to mark.
        //
        // [T-scheduled-tool-prefill] The prefilled call, if any, runs as the
        // loop's first turn instead of a model request; the envelope names it
        // so the model knows the scheduler made that call, not itself. A rerun
        // ignores it along with the prompt — it sends nothing new.
        val prefill = listOfNotNull(task.prefillToolCall?.takeIf { it.validationError() == null })
        //
        // [T-scheduled-card-subline] The envelope also numbers the fire and
        // names its trigger, which the card's status line is built from.
        val marked = ScheduledTaskMarker.forFire(
            task = task,
            firedAtMs = firedAt,
            scheduledFire = scheduledFire,
            prefilledTool = prefill.firstOrNull()?.toolName,
        ).xml

        val mode = task.targetMode
        if (mode is ScheduledTargetMode.ChildOfCurrent) {
            val r = HeadlessChatRunner.prompt(
                context = app,
                sessionId = sessionId,
                text = marked,
                attachments = emptyList(),
                // [T-android-scheduled-task-thinking] Was hardcoded null, so a
                // task could not run with any reasoning effort. null still means
                // "inherit" inside HeadlessChatRunner.
                thinkingLevel = task.thinkingLevel,
                wait = wait,
                timeoutMs = com.openminis.app.agent.jobs.HelperRunner.MAX_MINUTES * 60_000L,
                prefill = prefill,
            )
            closeChildJob(app, sessionId, r)
            r
        } else if (mode is ScheduledTargetMode.RerunMessage) {
            HeadlessChatRunner.retry(
                context = app,
                sessionId = sessionId,
                messageId = mode.messageId,
                wait = wait,
                timeoutMs = RUN_TIMEOUT_MS,
            )
        } else {
            HeadlessChatRunner.prompt(
                context = app,
                sessionId = sessionId,
                text = marked,
                attachments = emptyList(),
                // [T-android-scheduled-task-thinking] Was hardcoded null, so a
                // task could not run with any reasoning effort. null still means
                // "inherit" inside HeadlessChatRunner.
                thinkingLevel = task.thinkingLevel,
                wait = wait,
                timeoutMs = RUN_TIMEOUT_MS,
                prefill = prefill,
            )
        }
    }.getOrElse { t ->
        AppLogger.error(TAG, "task ${task.id} dispatch failed: ${t.message}")
        HeadlessChatRunner.PromptResult(
            status = "Error",
            responseText = "Error: ${t.message}",
            timedOut = false,
        )
    }

    private suspend fun resolveSessionId(app: MinisApp, task: ScheduledTask): String? {
        return when (val mode = task.targetMode) {
            is ScheduledTargetMode.AppendToSession -> {
                // Follow-up: the target session must still exist. If the user
                // deleted it, abort rather than silently spawning a new chat.
                if (app.chatRepository.getSession(mode.sessionId) == null) {
                    AppLogger.warning(TAG, "task ${task.id}: follow-up session ${mode.sessionId} gone — abort")
                    null
                } else {
                    mode.sessionId
                }
            }
            is ScheduledTargetMode.RerunMessage -> {
                if (app.chatRepository.getSession(mode.sessionId) == null) {
                    AppLogger.warning(TAG, "task ${task.id}: re-run session ${mode.sessionId} gone — abort")
                    null
                } else {
                    mode.sessionId
                }
            }
            is ScheduledTargetMode.ChildOfCurrent -> resolveChildOfCurrent(app, task, mode.sessionId)
            ScheduledTargetMode.NewSession -> {
                // Resolution priority (mirrors the UI's normal-chat boot):
                //   1. task.modelBinding (user picked a group or specific entry
                //      on the task editor) → write it through verbatim so the
                //      new session lights up the same group/entry chip.
                //   2. legacy task.modelId (pre-binding, kept for backcompat) →
                //      pin to that entry, no binding.
                //   3. default primary group → write a group binding so the
                //      session boots through the user's default group.
                //   4. first visible entry → last-resort fallback.
                // [OpenMinis#445] A task created by an older `minis-scheduled
                // --model` holds the raw flag in modelId; `entry:<id>`,
                // `group:<id>` or a bare entry id is honoured here as the
                // binding it names. A bare model id keeps the legacy path.
                val healed = if (task.modelBinding == null) {
                    task.modelId?.let { raw ->
                        val cfg = app.providerRepository.config.value
                        ScheduledModelSource.healLegacy(
                            raw,
                            ScheduledModelSource.entryRefs(cfg.modelEntries, app.providerRepository.allVisibleEntries()),
                            ScheduledModelSource.groupRefs(cfg.modelGroups),
                        )
                    }
                } else null
                if (healed != null) {
                    AppLogger.info(TAG, "task ${task.id}: legacy --model '${task.modelId}' read as ${healed.display}")
                }
                val explicitBinding = healed?.bindingJson ?: task.modelBinding
                val pinnedModelId = if (healed != null) healed.modelId else task.modelId
                val defaultGroupId =
                    if (explicitBinding == null && pinnedModelId == null) {
                        app.providerRepository.defaultPrimaryGroupId
                    } else null

                // Derive the seed modelId for the session row (must be valid
                // even before restoreFromBinding runs).
                val seedModelId: String = pinnedModelId
                    ?: run {
                        // Try to peek a member from whichever binding we'll
                        // write — explicit takes priority, then default group.
                        val groupIdForSeed: String? = explicitBinding
                            ?.let { parseGroupIdFromBinding(it) }
                            ?: defaultGroupId
                        val entryIdForSeed: String? = explicitBinding
                            ?.let { parseEntryIdFromBinding(it) }

                        // Entry binding → use that entry's model id.
                        entryIdForSeed
                            ?.let { eid -> app.providerRepository.config.value.modelEntries.firstOrNull { it.id == eid }?.model?.id }
                            ?: groupIdForSeed
                                ?.let { gid -> app.providerRepository.group(gid) }
                                // [T-android-group-resolve-skip-uncredentialed]
                                // Credential-aware filter — a scheduled run
                                // seeded with an uncredentialed member would
                                // fail unattended, with no user around to see
                                // the auth error.
                                ?.let { g -> app.providerRepository.availableMemberEntries(g).firstOrNull()?.model?.id }
                    }
                    ?: app.providerRepository.allVisibleEntries().firstOrNull()?.baseModel?.id
                    ?: run {
                        AppLogger.warning(TAG, "task ${task.id}: no provider — abort")
                        return null
                    }
                val title = task.label.ifBlank { "Scheduled task" }
                val memoryOn = com.openminis.app.data.MemoryGlobalPrefs.isGlobalEnabled(app)
                val session = app.chatRepository.createSession(
                    modelId = seedModelId,
                    title = title,
                    memoryEnabled = memoryOn,
                )
                app.chatRepository.dao.updateSource(session.id, "scheduled")

                // Write a model_binding when the task carried one OR when we
                // fell back to defaultPrimaryGroupId. Pinned modelId (no
                // binding) leaves modelBinding null so the chat layer treats
                // it as a hard-pinned entry, matching what the user picked.
                val bindingToWrite: String? = explicitBinding
                    ?: defaultGroupId?.let { """{"type":"group","groupId":"$it"}""" }
                if (bindingToWrite != null) {
                    app.chatRepository.updateSessionBinding(session.id, bindingToWrite, seedModelId)
                }
                session.id
            }
        }
    }

    // Mirrors ChatViewModel.restoreFromBinding's JSON shape. Robust against
    // malformed JSON — both parsers return null and the caller fall-backs.
    /**
     * [T-p2-agent-series] Mirror of iOS ScheduledJobRunner's `.childOfCurrent`:
     * a hidden agent child of [parentSid] on the parent's primary tier, with
     * the helper config (turn cap, no memory, parent's browser tabs, shared
     * workspace) and a registry job whose `then = FollowUpParent` posts the
     * result back into the parent as an <agent_callback>. The prompt itself
     * is dispatched by [dispatch] on the child session id; [closeChildJob]
     * closes the job when that returns.
     */
    private suspend fun resolveChildOfCurrent(app: MinisApp, task: ScheduledTask, parentSid: String): String? {
        // [T-tools-granular-switches] A child-of-current task IS a helper: a
        // hidden session with helper config that reports back to the parent.
        // The user who turned Agents off must not keep getting them from a
        // scheduled task set up earlier. Checked at fire time, not at
        // creation, so flipping the switch takes effect on the next run.
        // Mirrors iOS ScheduledJobRunner's AgentToolSwitch.agents guard.
        if (!com.openminis.app.tools.AgentToolSwitch.AGENTS.isEnabled(app)) {
            AppLogger.warning(TAG, "task ${task.id}: agents are turned off in settings — abort child-of-current")
            return null
        }
        val parentRow = app.chatRepository.getSession(parentSid)
        if (parentRow == null) {
            AppLogger.warning(TAG, "task ${task.id}: parent session ${parentSid} gone — abort")
            return null
        }
        val resolution = com.openminis.app.agent.jobs.ModelTierResolver.resolve(
            tier = com.openminis.app.agent.jobs.HelperModelTier.PRIMARY, repo = app.providerRepository,
            parentBindingJson = parentRow.modelBinding, parentModelId = parentRow.modelId, parentActiveEntryId = null,
        ) ?: run {
            AppLogger.warning(TAG, "task ${task.id}: parent has no model — abort")
            return null
        }
        val title = task.label.ifBlank { task.prompt.take(40) }
        val child = app.chatRepository.createSession(
            modelId = resolution.seedModelId,
            title = com.openminis.app.agent.jobs.HelperRunner.childSessionTitle(app, title),
            memoryEnabled = false,
            parentSessionId = parentSid,
            parentToolUseId = null,
        )
        app.chatRepository.dao.updateSource(child.id, "scheduled")
        resolution.bindingJson?.let { app.chatRepository.updateSessionBinding(child.id, it, resolution.seedModelId) }
        val registry = com.openminis.app.agent.jobs.AgentJobRegistry
        val job = registry.register(
            title = title, label = task.label.ifBlank { null },
            origin = com.openminis.app.agent.jobs.AgentJobOrigin.SCHEDULED,
            trigger = com.openminis.app.agent.jobs.AgentJobTrigger.Immediate,
            target = com.openminis.app.agent.jobs.AgentJobTarget.ChildOfCurrent(parentSid, null),
            prompt = task.prompt, then = com.openminis.app.agent.jobs.AgentJobThen.FollowUpParent(null),
        )
        registry.setTierUsed(job.id, resolution.tierUsed.wire)
        withContext(Dispatchers.Main) {
            val parentVm = HeadlessChatRunner.viewModelFor(app, parentSid)
            // [T-android-vm-store-dual-pool] The CHILD is tagged; `parentVm`
            // above is a normal session and deliberately keeps the default.
            HeadlessChatRunner.viewModelFor(
                app, child.id, com.openminis.app.ui.chat.ChatViewModelStore.PoolKind.CHILD,
            ).also {
                it.helperConfig = com.openminis.app.agent.jobs.HelperConfig(
                    parentSessionId = parentSid, parentToolUseId = "", jobId = job.id,
                    maxTurns = com.openminis.app.agent.jobs.HelperRunner.MAX_TURNS, title = title, tierUsed = resolution.tierUsed,
                )
                it.adoptBrowserTabPool(parentVm.browserTabPool)
                registry.markRunning(job.id, child.id) { if (it.isStreaming.value) it.cancelStream() }
            }
        }
        AppLogger.info(TAG, "task ${task.id}: child ${child.id.take(8)} of ${parentSid.take(8)} job=${job.id.take(8)} tier=${resolution.tierUsed.wire}")
        return child.id
    }

    /** Close a child-of-current job after its prompt returned; `then` posts the callback. */
    private suspend fun closeChildJob(app: MinisApp, childId: String, result: HeadlessChatRunner.PromptResult) {
        val registry = com.openminis.app.agent.jobs.AgentJobRegistry
        val job = registry.jobForSession(childId)?.takeIf { it.isActive } ?: return
        val hr = com.openminis.app.agent.jobs.HelperRunner
        // [T-agent-port-round2] The headless wait gave up but the child may still
        // be running: stop it, or it would keep working as an orphan after the
        // job is closed (and the callback would report a stale partial).
        if (result.timedOut) runCatching { HeadlessChatRunner.cancel(app, childId) }
        val facts = hr.childRunFacts(app.chatRepository.dao.loadMessages(childId))
        registry.setSummaryLine(job.id, hr.runSummaryLine(facts.toolNames, facts.turns, facts.input, facts.output, facts.cacheRead))
        val state = when {
            result.timedOut || result.status == "Timeout" -> com.openminis.app.agent.jobs.AgentJobState.TIMEOUT
            result.status == "Error" || result.status == "Rejected" -> com.openminis.app.agent.jobs.AgentJobState.FAILED
            else -> com.openminis.app.agent.jobs.AgentJobState.DONE
        }
        registry.finish(job.id, state, facts.lastText)
    }

    private fun parseGroupIdFromBinding(json: String): String? = runCatching {
        val o = org.json.JSONObject(json)
        if (o.optString("type") == "group") {
            o.optString("groupId").takeIf { it.isNotEmpty() }
        } else null
    }.getOrNull()

    private fun parseEntryIdFromBinding(json: String): String? = runCatching {
        val o = org.json.JSONObject(json)
        if (o.optString("type") == "entry") {
            o.optString("entryId").takeIf { it.isNotEmpty() }
        } else null
    }.getOrNull()
}

// ─── Merged from ScheduledTaskManager.kt ──────────────────────────────────────────────────

/**
 * [T-android-scheduled-tasks-design] Schedules / cancels AlarmManager
 * entries for [ScheduledTask] rows held in [ScheduledTaskStore]. Pairs with
 * [ScheduledTaskAlarmReceiver] which fires on the trigger and hands the
 * task off to [ScheduledAgentRunner].
 *
 * Repeating tasks (DAILY / WEEKDAYS / CUSTOM) are NOT scheduled via
 * AlarmManager.setRepeating — that primitive is inexact on Android 19+
 * and Doze makes it worse. Instead the receiver re-schedules the next
 * occurrence after every fire, giving Doze-tolerant precision.
 */
class ScheduledTaskManager(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val store = ScheduledTaskStore(context)

    init { removeObsoleteNotificationChannel() }

    /** Read-only access to persistence for callers that only need data. */
    fun store(): ScheduledTaskStore = store

    fun list(): List<ScheduledTask> = store.all()
    fun get(taskId: String): ScheduledTask? = store.get(taskId)

    fun create(task: ScheduledTask): ScheduledTask {
        // [T-android-scheduled-triggers] A relative trigger counts from now.
        val armed = if (task.usesAnchor && task.anchorMs == null) {
            task.copy(anchorMs = System.currentTimeMillis())
        } else task
        store.upsert(armed)
        if (armed.enabled) registerAlarm(armed)
        if (armed.triggerKind == ScheduledTriggerKind.ON_COMPLETION) {
            ScheduledCompletionTriggers.ensureListening(context)
        }
        return armed
    }

    /**
     * Save an edited task.
     *
     * [T-android-scheduled-triggers] Bookkeeping the editor does not own is
     * carried over from the stored row: the editor rebuilds the task from its
     * fields, so saving used to reset the run history and fire count (and
     * would reset the new relative triggers' anchor and fire count). Only a
     * value the incoming task leaves empty is taken from the stored one.
     */
    fun update(task: ScheduledTask): ScheduledTask {
        cancelAlarm(task.id)
        val merged = store.get(task.id)?.let { prev -> mergeBookkeeping(task, prev) } ?: task
        store.upsert(merged)
        if (merged.enabled) registerAlarm(merged)
        return merged
    }

    fun setEnabled(taskId: String, enabled: Boolean) {
        val t = store.get(taskId) ?: return
        // [T-android-scheduled-triggers] Re-enabling a relative trigger starts
        // it over: "after 30m" counts from now again, and an interval gets its
        // full count back. A calendar task simply resumes its schedule.
        val updated = if (enabled && t.usesAnchor) {
            t.copy(enabled = true, anchorMs = System.currentTimeMillis(), triggeredCount = 0)
        } else if (enabled && t.triggerKind == ScheduledTriggerKind.ON_COMPLETION) {
            t.copy(enabled = true, triggeredCount = 0)
        } else {
            t.copy(enabled = enabled)
        }
        store.upsert(updated)
        if (enabled) registerAlarm(updated) else cancelAlarm(taskId)
    }

    /**
     * [T-android-scheduled-triggers] Atomically take a one-shot fire that is
     * not driven by an alarm (on-completion): false when it is disabled or has
     * already fired, so two callers cannot both fire it.
     */
    fun claimOneShotFire(taskId: String): Boolean =
        store.update(taskId) { t ->
            if (!t.enabled || (t.triggeredCount ?: 0) >= 1) null
            else t.copy(enabled = false, triggeredCount = 1)
        } != null

    fun delete(taskId: String) {
        cancelAlarm(taskId)
        store.delete(taskId)
    }

    /**
     * [T-scheduled-active-session-android] Turn off every task [sessionId]
     * created that would still fire (iOS `cancelScheduled(createdBy:)`): the
     * user's Stop in that chat, or the chat being deleted. Same meaning as the
     * card's Cancel - disabled, alarm cancelled, row and run history kept - so
     * a stopped loop can be turned back on. One atomic pass over the store per
     * task. Returns how many were turned off.
     */
    fun disableAllCreatedBy(sessionId: String, reason: String): Int {
        val live = createdByEnabled(store.all(), sessionId)
        var n = 0
        for (t in live) {
            val off = store.update(t.id) { cur -> if (cur.enabled) cur.copy(enabled = false) else null }
            if (off != null) {
                cancelAlarm(t.id)
                n++
            }
        }
        if (n > 0) AppLogger.info(TAG, "disabled $n scheduled task(s) created by ${sessionId.take(8)}: $reason")
        return n
    }

    /**
     * Re-register every enabled task with AlarmManager. Called from
     * [com.openminis.app.offload.AlarmReceiver]'s BOOT_COMPLETED branch
     * so persisted tasks survive a device reboot.
     */
    fun rescheduleAll() {
        val tasks = store.all()
        if (tasks.isEmpty()) {
            AppLogger.info(TAG, "rescheduleAll: no tasks")
            return
        }
        var count = 0
        // [T-android-scheduled-triggers] On-completion tasks have no alarm; they
        // need the agent-job completion stream collected in this process.
        if (tasks.any { it.enabled && it.triggerKind == ScheduledTriggerKind.ON_COMPLETION }) {
            ScheduledCompletionTriggers.ensureListening(context)
        }
        for (t in tasks) {
            if (!t.enabled) continue
            registerAlarm(t)
            count++
        }
        AppLogger.info(TAG, "rescheduleAll: re-registered $count enabled task(s)")
    }

    /**
     * Called by the receiver after firing — for repeating tasks, schedule
     * the next occurrence. ONCE tasks get disabled in-place (enabled=false)
     * so they linger in the list with their lastResult* metadata visible.
     */
    /**
     * Returns whether this alarm delivery is a real fire that should run. Only
     * a relative trigger can say no — see [relativeAlarmDue].
     */
    fun rescheduleNext(taskId: String, firedSlotMs: Long? = null): Boolean {
        val t = store.get(taskId) ?: return false
        // [T-android-scheduled-triggers] Counted HERE, when the alarm fires and
        // before the run starts, so the next slot never depends on a run that
        // may take minutes to record itself.
        //
        // [T-android-scheduled-fire-claim] Checked and counted in ONE atomic
        // step. A cold start by this very alarm also runs rescheduleAll, which
        // can read the row before it is counted, see it overdue and arm it for
        // "now" again — a second delivery of the same fire. Both deliveries
        // then counted and ran. Now the second finds the fire already taken
        // (the anchor has moved on), re-arms the real next slot that the stale
        // registration replaced (same PendingIntent), and does not run.
        if (!t.isCalendar) {
            val now = System.currentTimeMillis()
            var arm = false
            val stored = store.update(taskId) { cur ->
                if (!cur.enabled || !relativeAlarmDue(cur, now)) return@update null
                val (next, a) = afterAlarmFire(cur, now) ?: return@update null
                arm = a
                next
            }
            if (stored == null) {
                val cur = store.get(taskId)
                AppLogger.info(TAG, "alarm for task=$taskId is not a due fire (enabled=${cur?.enabled}) — not running")
                if (cur != null && cur.enabled) registerAlarm(cur)
                return false
            }
            if (arm) registerAlarm(stored)
            return true
        }
        // [T-sched-run-now] Calendar fires are claimed in the same atomic
        // step as relative ones now: an alarm for a slot "Run now" already
        // fired early must not run it a second time. It used to read the row
        // and write it back unlocked.
        val now = System.currentTimeMillis()
        val stored = store.update(taskId) { cur ->
            if (!cur.enabled || calendarSlotTakenByRunNow(cur, now)) return@update null
            // Mark fired ONCE task as disabled — keep row so the user can
            // see its last result, re-enable if they want to re-run.
            if (cur.repeatMode == ScheduledRepeatMode.ONCE) {
                cur.copy(enabled = false)
            } else {
                // [FIX-261025-19] Record the slot as run, as Run now does, so
                // the in-app timer reaching the same slot after this alarm
                // finds it taken instead of running it again.
                firedSlotMs?.let { cur.copy(runNowSlotMs = it) } ?: cur
            }
        }
        if (stored == null) {
            val cur = store.get(taskId)
            AppLogger.info(TAG, "alarm for task=$taskId: slot already run by Run now (enabled=${cur?.enabled}) — not running")
            if (cur != null && cur.enabled) registerAlarm(cur)
            return false
        }
        if (stored.enabled) registerAlarm(stored)
        return true
    }

    /**
     * [T-sched-run-now] "Run now" from the chat's timer pill: take the task's
     * PENDING next run and mark it fired, in one atomic store step - the same
     * lock and the same transition the alarm's own fire uses
     * ([rescheduleNext]), so the alarm and "Run now" can never both run the
     * same run. Returns the row to run, or null when there is nothing to
     * take: disabled, exhausted, an on-completion task, or [isRunning] says a
     * run of it is already in progress / being started (checked under the
     * same lock). The next alarm is re-armed for the run after the taken one,
     * replacing the old registration (same PendingIntent).
     *
     * [expectedRunKey] makes it a compare-and-set: the run the menu showed
     * ([pendingRunKey] when it was drawn) must still be the pending one. If
     * the alarm fired it in between, the pending run is now the following one
     * and the claim fails instead of taking that one too.
     */
    fun claimRunNow(
        taskId: String,
        now: Long = System.currentTimeMillis(),
        isRunning: (String) -> Boolean = { false },
        expectedRunKey: String? = null,
    ): ScheduledTask? {
        val stored = store.update(taskId) { cur ->
            if (isRunning(cur.id)) return@update null
            if (expectedRunKey != null && pendingRunKey(cur, now) != expectedRunKey) return@update null
            runNowTransition(cur, now)
        } ?: return null
        if (stored.enabled && stored.nextTriggerMs(now) != null) registerAlarm(stored) else cancelAlarm(taskId)
        AppLogger.info(
            TAG,
            "run now task=$taskId kind=${stored.triggerKind} count=${stored.triggeredCount} " +
                "slot=${stored.runNowSlotMs} enabled=${stored.enabled} next=${stored.nextTriggerMs(now)}",
        )
        return stored
    }

    fun markFired(
        taskId: String,
        sessionId: String?,
        resultPreview: String?,
        ok: Boolean = true,
        // [T-scheduled-task-detail] When the fire STARTED. The run record used
        // to be stamped at completion, which could be minutes later and could
        // not be matched to the fire's envelope (which carries the start).
        firedAt: Long = System.currentTimeMillis(),
    ) {
        val now = firedAt
        // [T-android-scheduled-tasks-run-records] Prepend a run record
        // (newest-first), capped at MAX_RUN_HISTORY. lastResult* are kept in
        // sync for back-compat but are no longer surfaced in the list UI.
        val run = ScheduledRun(firedAt = now, sessionId = sessionId, preview = resultPreview, ok = ok)
        // [T-android-scheduled-fire-claim] Atomic: a run finishing minutes
        // after its fire must not write back a count / anchor the next fire
        // has moved on since.
        store.update(taskId) { t ->
            t.copy(
                lastFiredAt = now,
                lastResultPreview = resultPreview,
                lastResultSessionId = sessionId,
                runHistory = (listOf(run) + t.runHistory).take(ScheduledTask.MAX_RUN_HISTORY),
                fireCount = t.firesSoFar + 1,
            )
        }
    }

    private fun registerAlarm(task: ScheduledTask) {
        val triggerAt = task.nextTriggerMs() ?: run {
            AppLogger.warning(TAG, "task ${task.id} has no next trigger — skipping register")
            return
        }
        val pi = buildPendingIntent(task.id, triggerAt)
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            AppLogger.info(TAG, "registered task=${task.id} label=\"${task.label}\" triggerAt=$triggerAt")
        } catch (e: SecurityException) {
            // S+ users may have revoked SCHEDULE_EXACT_ALARM — fall back
            // to inexact so we still fire eventually rather than dropping.
            AppLogger.warning(
                TAG,
                "exact-alarm denied for task=${task.id} (${e.message}); falling back to inexact",
            )
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (t2: Throwable) {
                AppLogger.error(TAG, "inexact fallback failed for task=${task.id}: ${t2.message}")
            }
        }
    }

    private fun cancelAlarm(taskId: String) {
        val pi = buildPendingIntent(taskId)
        alarmManager.cancel(pi)
        pi.cancel()
    }

    private fun buildPendingIntent(taskId: String, triggerAt: Long? = null): PendingIntent {
        val intent = Intent(context, ScheduledTaskAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_TASK_ID, taskId)
            // [FIX-261025-19] The slot this alarm is for; FLAG_UPDATE_CURRENT
            // replaces it on every re-registration.
            if (triggerAt != null) putExtra(EXTRA_TRIGGER_AT, triggerAt)
        }
        val requestCode = taskId.hashCode() and 0x7FFFFFFF
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    /**
     * [T-port-sched-notif-single] Nothing posts on [OBSOLETE_CHANNEL_ID] any
     * more: a scheduled run's notification is BackgroundTaskNotifier's (Task
     * Completion channel), which follows the Task Notifications toggle, skips
     * the foreground and is cleared when the app opens. The old channel had an
     * English-only name and would otherwise sit empty in
     * the system settings, so it is deleted once per process; deleting also
     * clears any stale entry still posted on it.
     */
    private fun removeObsoleteNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!obsoleteChannelChecked.compareAndSet(false, true)) return
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(OBSOLETE_CHANNEL_ID) != null) {
                nm.deleteNotificationChannel(OBSOLETE_CHANNEL_ID)
                AppLogger.info(TAG, "deleted obsolete notification channel $OBSOLETE_CHANNEL_ID")
            }
        }
    }

    companion object {
        private const val TAG = "ScheduledTaskManager"

        /**
         * [T-android-scheduled-fire-claim] Tolerance for an alarm delivered a
         * moment before its slot (clock granularity). Exact alarms are not
         * delivered early, so anything well before the slot is a stale
         * registration, not the fire.
         */
        private const val ALARM_EARLY_SLACK_MS = 5_000L

        /**
         * [T-android-scheduled-fire-claim] Whether an alarm delivered at [now]
         * is [t]'s pending fire: the slot has come, and a --after has not
         * fired / an interval has count left. Pure, for tests. Calendar and
         * on-completion kinds are not decided here.
         */
        internal fun relativeAlarmDue(t: ScheduledTask, now: Long): Boolean {
            val anchor = t.anchorMs ?: t.createdAt
            return when (t.triggerKind) {
                ScheduledTriggerKind.AFTER ->
                    (t.triggeredCount ?: 0) < 1 && anchor + (t.delaySec ?: 0L) * 1000 <= now + ALARM_EARLY_SLACK_MS
                ScheduledTriggerKind.INTERVAL -> {
                    val iv = t.intervalSec ?: return false
                    (t.maxFires == null || (t.triggeredCount ?: 0) < t.maxFires) &&
                        anchor + iv * 1000 <= now + ALARM_EARLY_SLACK_MS
                }
                ScheduledTriggerKind.ON_COMPLETION, ScheduledTriggerKind.CALENDAR -> true
            }
        }

        /**
         * [T-android-scheduled-triggers] What a relative trigger's row becomes
         * when its alarm fires at [now], and whether to arm the next one. Pure,
         * for tests; null for kinds the alarm does not drive.
         *  - AFTER: fired once -> disabled (kept in the list, like ONCE).
         *  - INTERVAL: counted and re-anchored to [now]; the last of --count
         *    disables it instead of arming another.
         */
        internal fun afterAlarmFire(t: ScheduledTask, now: Long): Pair<ScheduledTask, Boolean>? = when (t.triggerKind) {
            ScheduledTriggerKind.AFTER -> t.copy(enabled = false, triggeredCount = 1) to false
            ScheduledTriggerKind.INTERVAL -> {
                val n = (t.triggeredCount ?: 0) + 1
                val next = t.copy(triggeredCount = n, anchorMs = now)
                if (t.maxFires != null && n >= t.maxFires) next.copy(enabled = false) to false else next to true
            }
            ScheduledTriggerKind.ON_COMPLETION, ScheduledTriggerKind.CALENDAR -> null
        }

        /**
         * [T-sched-run-now] Calendar: whether an alarm delivered at [now] is
         * for the slot "Run now" already fired early. The registration for
         * that slot is replaced when the run is taken, so this only answers
         * true for an alarm that was already on its way: delivered within a
         * late window of the slot (Doze can hold an alarm a few minutes).
         */
        internal fun calendarSlotTakenByRunNow(t: ScheduledTask, now: Long): Boolean {
            val slot = t.runNowSlotMs ?: return false
            return now >= slot - ALARM_EARLY_SLACK_MS && now <= slot + RUN_NOW_LATE_WINDOW_MS
        }

        /**
         * [T-sched-run-now] Identity of the task's pending run, for the
         * compare-and-set in [claimRunNow]: the run number of a relative
         * trigger (its time moves with "now" once overdue), the planned slot
         * of a calendar task. null when nothing is pending.
         */
        internal fun pendingRunKey(t: ScheduledTask, now: Long): String? {
            if (!t.enabled) return null
            return when (t.triggerKind) {
                ScheduledTriggerKind.AFTER, ScheduledTriggerKind.INTERVAL ->
                    if (t.nextTriggerMs(now) == null) null else "run:${t.triggeredCount ?: 0}"
                ScheduledTriggerKind.CALENDAR -> t.nextTriggerMs(now)?.let { "slot:$it" }
                ScheduledTriggerKind.ON_COMPLETION -> null
            }
        }

        /** [T-sched-run-now] How late an alarm for a taken slot may still arrive. */
        private const val RUN_NOW_LATE_WINDOW_MS = 30 * 60_000L

        /**
         * [T-sched-run-now] What [t] becomes when "Run now" takes its pending
         * run at [now]; null when there is none. Pure, for tests.
         *  - AFTER / INTERVAL: exactly the alarm's own transition
         *    ([afterAlarmFire]) - counted once and re-anchored at [now], so the
         *    next interval runs from the manual run, as from a natural one, and
         *    --count is used up once.
         *  - CALENDAR: the pending slot is recorded as run ([runNowSlotMs]);
         *    ONCE is disabled as its alarm would. The schedule resumes at the
         *    slot after it.
         *  - ON_COMPLETION: nothing pending on a clock - null.
         */
        internal fun runNowTransition(t: ScheduledTask, now: Long): ScheduledTask? {
            if (!t.enabled) return null
            return when (t.triggerKind) {
                ScheduledTriggerKind.AFTER, ScheduledTriggerKind.INTERVAL -> {
                    if (t.nextTriggerMs(now) == null) null else afterAlarmFire(t, now)?.first
                }
                ScheduledTriggerKind.CALENDAR -> {
                    val slot = t.nextTriggerMs(now) ?: return null
                    t.copy(runNowSlotMs = slot, enabled = t.repeatMode != ScheduledRepeatMode.ONCE)
                }
                ScheduledTriggerKind.ON_COMPLETION -> null
            }
        }

        /**
         * [T-android-scheduled-triggers] [incoming] with the bookkeeping it
         * leaves empty taken from [prev]. Pure, for tests.
         */
        /**
         * [T-scheduled-active-session-android] The tasks [disableAllCreatedBy]
         * turns off: enabled ones [sessionId] created - nothing aimed at it from
         * elsewhere (a daily task made in the editor survives Stop). Pure.
         */
        internal fun createdByEnabled(tasks: List<ScheduledTask>, sessionId: String): List<ScheduledTask> =
            tasks.filter { it.enabled && it.creatorSessionId == sessionId }

        internal fun mergeBookkeeping(incoming: ScheduledTask, prev: ScheduledTask): ScheduledTask =
            incoming.copy(
                runHistory = incoming.runHistory.ifEmpty { prev.runHistory },
                fireCount = incoming.fireCount ?: prev.fireCount,
                anchorMs = incoming.anchorMs ?: prev.anchorMs,
                triggeredCount = incoming.triggeredCount ?: prev.triggeredCount,
                // [T-scheduled-active-session-android] The editor does not know
                // which chat created the task; an edit must not forget it.
                creatorSessionId = incoming.creatorSessionId ?: prev.creatorSessionId,
            )
        const val ACTION_FIRE = "com.openminis.app.scheduled.FIRE"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TRIGGER_AT = "trigger_at"
        /** [T-port-sched-notif-single] The retired per-task completion channel. */
        const val OBSOLETE_CHANNEL_ID = "minis_scheduled_tasks"
        private val obsoleteChannelChecked = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}

// ─── Merged from ScheduledTaskStore.kt ──────────────────────────────────────────────────

/**
 * [T-android-scheduled-tasks-design] SharedPreferences-backed JSON array of
 * [ScheduledTask] rows. Same pattern as [com.openminis.app.offload.AlarmOffloadManager]
 * — small dataset, low write frequency, no Room migration cost.
 */
class ScheduledTaskStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun all(): List<ScheduledTask> {
        val raw = prefs.getString(KEY_TASKS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    runCatching { ScheduledTask.fromJson(o) }
                        .onSuccess { add(it) }
                        .onFailure { AppLogger.warning(TAG, "skip malformed row: ${it.message}") }
                }
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "load failed: ${t.message}")
            emptyList()
        }
    }

    fun get(taskId: String): ScheduledTask? = all().firstOrNull { it.id == taskId }

    // [T-android-scheduled-fire-claim] Every write is a read-modify-write of
    // the WHOLE list, and writers run on several threads (the alarm receiver,
    // a run's completion on IO, the task screens, the CLI). Unserialised, two
    // writes that overlap lose one of them — a count or anchor that just moved
    // silently moves back, and an interval then overshoots its --count.
    // apply() updates the in-memory map before returning, so the next read
    // under the lock already sees the previous write.
    fun upsert(task: ScheduledTask): Unit = synchronized(LOCK) {
        val current = all().filter { it.id != task.id }
        write(current + task)
    }

    fun delete(taskId: String): Unit = synchronized(LOCK) {
        write(all().filter { it.id != taskId })
    }

    /**
     * [T-android-scheduled-fire-claim] Atomic read-modify-write of one row:
     * [transform] sees the row as stored right now and returns its new value,
     * or null to leave it unchanged. Returns what was written (null when the
     * row is missing or nothing was written).
     */
    fun update(taskId: String, transform: (ScheduledTask) -> ScheduledTask?): ScheduledTask? = synchronized(LOCK) {
        val tasks = all()
        val cur = tasks.firstOrNull { it.id == taskId } ?: return null
        val next = transform(cur) ?: return null
        write(tasks.map { if (it.id == taskId) next else it })
        next
    }

    fun clear() {
        prefs.edit().remove(KEY_TASKS).apply()
    }

    private fun write(tasks: List<ScheduledTask>) {
        val arr = JSONArray()
        for (t in tasks) arr.put(t.toJson())
        prefs.edit().putString(KEY_TASKS, arr.toString()).apply()
    }

    /**
     * Cold flow that emits the current task list whenever the prefs file
     * changes. Used by [ScheduledTasksViewModel] to keep the list screen
     * live.
     */
    fun observe(): Flow<List<ScheduledTask>> = callbackFlow {
        trySend(all())
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_TASKS || key == null) {
                trySend(all())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // [T-android-sched-badge-toggle] Whether the home toolbar's clock icon
    // shows the enabled-task count. Default on, so nothing changes until the
    // user opts out from the Scheduled Tasks screen's menu.
    fun showHomeBadge(): Boolean = prefs.getBoolean(KEY_SHOW_HOME_BADGE, true)

    fun setShowHomeBadge(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_HOME_BADGE, show).apply()
    }

    /** Live [showHomeBadge], so the session list reacts without a restart. */
    fun observeShowHomeBadge(): Flow<Boolean> = callbackFlow {
        trySend(showHomeBadge())
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_SHOW_HOME_BADGE || key == null) {
                trySend(showHomeBadge())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        private const val TAG = "ScheduledTaskStore"

        /**
         * [T-android-sched-badge-toggle] The number the home clock icon shows,
         * or null for no badge: hidden when the user turned it off or when no
         * task is enabled.
         */
        fun homeBadgeCount(enabledTaskCount: Int, showBadge: Boolean): Int? =
            enabledTaskCount.takeIf { showBadge && it > 0 }

        /** Process-wide: stores are constructed per call site, the file is one. */
        private val LOCK = Any()
        private const val PREFS_NAME = "minis_scheduled_tasks_prefs"
        private const val KEY_TASKS = "tasks_json"
        private const val KEY_SHOW_HOME_BADGE = "show_home_badge"
    }
}
