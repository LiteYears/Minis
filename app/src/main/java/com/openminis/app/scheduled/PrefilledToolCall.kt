package com.openminis.app.scheduled

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.openminis.app.agent.jobs.AgentJob
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.BackgroundSettingsRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.AgentForegroundService
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-scheduled-tool-prefill] A tool call a scheduled task makes BEFORE the
 * model is asked anything.
 *
 * Many scheduled tasks are "run this fixed command, then tell me what it
 * says" — a curl for the weather, a disk-usage check. Without this the fired
 * prompt costs two model round-trips: one where the model decides to call the
 * tool (and re-derives arguments that were known when the task was created),
 * one where it summarises the result. A prefilled call is executed up front
 * and handed to the model already answered, so only the summary request is
 * made.
 *
 * The call is not replayed by hand-writing history rows. The agent loop's
 * first turn is fed a scripted chunk stream ([asStreamChunks]) in place of a
 * provider request, so the call travels the exact path a model-emitted call
 * does: the same tool card, the same dispatch, the same persisted
 * tool_use / tool_result rows, the same agentHistory entries. A reloaded
 * session cannot tell the two apart because they were written by the same
 * code.
 *
 * [argsJson] is kept as text rather than a JSONObject so the type has value
 * equality (JSONObject compares by reference).
 */
data class PrefilledToolCall(
    val toolName: String,
    val argsJson: String,
) {
    /** The arguments as a fresh JSONObject; a malformed payload yields `{}`. */
    fun args(): JSONObject = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }

    /** The shell command, for display; null for non-shell tools. */
    val shellCommand: String?
        get() = if (toolName == SHELL_TOOL) args().optString("command", "").ifBlank { null } else null

    /**
     * Why this call cannot be stored, or null when it can. Only the tools in
     * [SUPPORTED_TOOLS] are accepted; the rest are refused up front rather
     * than failing silently every time the task fires.
     */
    fun validationError(): String? {
        if (toolName !in SUPPORTED_TOOLS) {
            return "prefilled tool '$toolName' is not supported yet (supported: ${SUPPORTED_TOOLS.joinToString()})"
        }
        val a = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return "prefilled tool arguments must be a JSON object"
        return when (toolName) {
            SHELL_TOOL -> when {
                a.optString("command", "").isBlank() -> "shell prefill needs a non-empty command"
                a.optString("command", "").length > MAX_COMMAND_LENGTH ->
                    "shell command is longer than $MAX_COMMAND_LENGTH characters; put the script in a file and run that file"
                a.has("timeout") && a.optInt("timeout", -1) !in TIMEOUT_RANGE_SEC ->
                    "shell timeout must be between ${TIMEOUT_RANGE_SEC.first}s and ${TIMEOUT_RANGE_SEC.last}s"
                else -> null
            }
            else -> null
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("tool", toolName)
        .put("args", args())

    companion object {
        const val SHELL_TOOL = "shell_execute"

        /** Same limits as iOS ScheduledPresetToolCall. */
        const val MAX_COMMAND_LENGTH = 16_000
        val TIMEOUT_RANGE_SEC: IntRange = 1..3600

        /**
         * Tools a task may prefill. Extending this is a matter of adding the
         * name here and its argument check in [validationError]; the firing
         * path is tool-agnostic because it reuses the agent loop's dispatch.
         */
        val SUPPORTED_TOOLS: Set<String> = setOf(SHELL_TOOL)

        /** Short names accepted on the CLI, mapped to the tool's real name. */
        private val ALIASES = mapOf("shell" to SHELL_TOOL, "sh" to SHELL_TOOL, "bash" to SHELL_TOOL)

        fun canonicalToolName(raw: String): String {
            val t = raw.trim()
            return ALIASES[t.lowercase()] ?: t
        }

        /**
         * A shell prefill. [title] fills `tool_title` (the card header) when
         * the caller gave none, since the tool schema requires one and the
         * model never got to write it.
         */
        fun shell(command: String, title: String? = null, timeoutSec: Int? = null): PrefilledToolCall =
            of(SHELL_TOOL, JSONObject().put("command", command).apply { timeoutSec?.let { put("timeout", it) } }, title)

        /**
         * Build a call from a tool name and its arguments, adding a
         * `tool_title` when absent. The title falls back to the first line of
         * the shell command so the card never shows a bare tool name.
         */
        fun of(toolName: String, args: JSONObject, title: String? = null): PrefilledToolCall {
            val name = canonicalToolName(toolName)
            val copy = JSONObject(args.toString())
            if (copy.optString("tool_title", "").isBlank()) {
                val fallback = title?.trim()?.takeIf { it.isNotEmpty() }
                    ?: copy.optString("command", "").lineSequence().firstOrNull()?.trim()?.take(40)
                        ?.takeIf { it.isNotEmpty() }
                if (fallback != null) copy.put("tool_title", fallback)
            }
            return PrefilledToolCall(name, copy.toString())
        }

        /** Read the stored shape; null for a missing or unusable value. */
        fun fromJson(o: JSONObject?): PrefilledToolCall? {
            if (o == null) return null
            val tool = o.optString("tool", "").takeIf { it.isNotBlank() } ?: return null
            val args = o.optJSONObject("args") ?: JSONObject()
            return PrefilledToolCall(tool, args.toString())
        }

        /**
         * A tool_use id for a prefilled call. Letters, digits and `_` only, so
         * it satisfies Anthropic's `^[a-zA-Z0-9_-]+$`, and 34 chars stays under
         * the 64-char cap OpenAI-compatible endpoints enforce. The prefix makes
         * a prefilled call identifiable in logs and request dumps.
         */
        fun newToolUseId(): String = ID_PREFIX + UUID.randomUUID().toString().replace("-", "").take(24)

        const val ID_PREFIX = "call_sched_"
    }
}

/**
 * [T-scheduled-tool-prefill] The first agent-loop turn, pre-decided. Each
 * call carries the tool_use id it will be recorded under.
 */
data class ScriptedToolTurn(val calls: List<Call>) {
    data class Call(val id: String, val toolName: String, val argsJson: String)

    /**
     * The chunk sequence a provider would have streamed for a turn that is
     * nothing but these tool calls: start, one start+complete pair per call,
     * then a `tool_use` finish. No text, no thinking, no usage — the turn
     * made no request, so it has no tokens to report.
     */
    fun asStreamChunks(): List<LLMStreamChunk> = buildList {
        add(LLMStreamChunk.Started)
        for (c in calls) {
            add(LLMStreamChunk.ToolUseStart(c.id, c.toolName))
            add(LLMStreamChunk.ToolCallComplete(c.id, c.toolName, runCatching { JSONObject(c.argsJson) }.getOrElse { JSONObject() }))
        }
        add(LLMStreamChunk.Finished(STOP_REASON))
    }

    companion object {
        const val STOP_REASON = "tool_use"

        /** True for a tool_use id minted by [PrefilledToolCall.newToolUseId]. */
        fun isScriptedId(id: String): Boolean = id.startsWith(PrefilledToolCall.ID_PREFIX)

        /**
         * Turn [prefills] into a scripted turn, keeping only calls whose tool
         * this session actually offers ([availableTools]). A call to a tool the
         * model was not given would be a history the model could never have
         * produced; dropping it falls back to the old behaviour, where the
         * model reads the prompt and decides for itself. Null when nothing is
         * left to run.
         */
        fun from(prefills: List<PrefilledToolCall>, availableTools: Set<String>): ScriptedToolTurn? {
            val calls = prefills
                .filter { it.validationError() == null && it.toolName in availableTools }
                .map { Call(PrefilledToolCall.newToolUseId(), it.toolName, it.argsJson) }
            return if (calls.isEmpty()) null else ScriptedToolTurn(calls)
        }
    }
}

// ─── Merged from ScheduledCompletionTriggers.kt ──────────────────────────────────────────────────

/**
 * [T-android-scheduled-triggers] Fires [ScheduledTriggerKind.ON_COMPLETION]
 * tasks. Port of iOS ScheduledJobRunner's `.onCompletion` arm, which waits for
 * `agentJobDidFinish` naming the upstream job and then fires once with
 * `{{result}}` replaced.
 *
 * Two kinds of upstream finish here:
 *  - another scheduled task's SCHEDULE ends: [ScheduledAgentRunner] calls
 *    [onScheduledRunFinished] after recording each scheduled run, and it
 *    releases the waiting tasks only after the last one;
 *  - an agent job (a background sub agent, `subagent_task` wait:false) closes
 *    in [AgentJobRegistry]: its `completions` flow, collected once per process
 *    by [ensureListening].
 *
 * Agent jobs live in memory, so a task waiting on one from a previous process
 * never fires; it stays listed as waiting until deleted. Scheduled tasks are
 * persisted, so waiting on one survives a restart.
 */
object ScheduledCompletionTriggers {

    private const val TAG = "ScheduledCompletion"

    /** Cap on the upstream result pasted into `{{result}}`. */
    const val RESULT_CAP_CHARS = 8_000

    private val listening = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Start collecting agent-job completions. Idempotent; cheap to call often. */
    fun ensureListening(context: Context) {
        if (!listening.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            AgentJobRegistry.completions.collect { c ->
                val result = AgentJobRegistry.job(c.jobId)?.resultText
                runCatching { onUpstreamFinished(app, c.jobId, result) }
                    .onFailure { AppLogger.warning(TAG, "on-completion for job ${c.jobId.take(8)} failed: ${it.message}") }
            }
        }
    }

    /** Enabled on-completion tasks waiting for [upstreamId]. */
    internal fun dependents(tasks: List<ScheduledTask>, upstreamId: String): List<ScheduledTask> =
        tasks.filter {
            it.enabled && it.triggerKind == ScheduledTriggerKind.ON_COMPLETION && it.onCompletionOf == upstreamId
        }

    /**
     * [T-android-scheduled-oncompletion-finish] A scheduled task's run has
     * finished: release the tasks waiting on it only when its SCHEDULE is over
     * ([scheduleFinished]), not after every run.
     *
     * iOS posts `agentJobDidFinish` once per job: a once job after its fire, a
     * loop when its count runs out, a cron job never. Android fired dependents
     * after EVERY run, so `--of` a `--interval 10m --count 6` watch ran after
     * check 1 of 6 with check 1's result, and "Run now" released it too.
     */
    suspend fun onScheduledRunFinished(context: Context, taskId: String, result: String?) {
        val task = ScheduledTaskManager(context).get(taskId)
        if (!scheduleFinished(task, System.currentTimeMillis())) return
        onUpstreamFinished(context, taskId, result)
    }

    /**
     * [T-android-scheduled-oncompletion-finish] Whether [task]'s schedule has
     * no fire left, read after a scheduled run. Pure, for tests.
     *  - AFTER / ON_COMPLETION / a calendar ONCE: their one fire was this run.
     *  - INTERVAL: only with a --count, once it is used up (counted at the
     *    alarm, so a run finishing after the LAST fire sees 0 left).
     *  - Repeating calendar: only when an end date leaves no next slot.
     * A deleted task is not "finished": nothing waiting on it fires.
     */
    internal fun scheduleFinished(task: ScheduledTask?, now: Long): Boolean {
        if (task == null) return false
        return when (task.triggerKind) {
            ScheduledTriggerKind.AFTER, ScheduledTriggerKind.ON_COMPLETION -> true
            ScheduledTriggerKind.INTERVAL -> task.remainingFires == 0
            ScheduledTriggerKind.CALENDAR ->
                task.repeatMode == ScheduledRepeatMode.ONCE ||
                    (task.endDateMs != null && task.copy(enabled = true).nextTriggerMs(now) == null)
        }
    }

    /** The prompt with `{{result}}` replaced, as iOS does; the result is capped. */
    internal fun substitute(prompt: String, result: String?): String =
        prompt.replace(ScheduledTask.RESULT_PLACEHOLDER, (result ?: "").take(RESULT_CAP_CHARS))

    /**
     * [upstreamId] finished with [result]: fire every task waiting for it. Each
     * is CLAIMED first (disabled, counted) so a repeated completion event or a
     * concurrent caller cannot fire it twice; that is also what makes it a
     * one-shot, like iOS finishing the job after its fire.
     */
    suspend fun onUpstreamFinished(context: Context, upstreamId: String, result: String?) {
        val manager = ScheduledTaskManager(context)
        val waiting = dependents(manager.list(), upstreamId)
        if (waiting.isEmpty()) return
        for (dep in waiting) {
            if (!manager.claimOneShotFire(dep.id)) continue
            AppLogger.info(TAG, "upstream ${upstreamId.take(8)} finished -> firing ${dep.id.take(8)} \"${dep.label}\"")
            ScheduledAgentRunner.run(
                context,
                dep.copy(prompt = substitute(dep.prompt, result)),
                waitForCompletion = false,
                // Its own fire, so a task waiting on IT is released in turn.
                scheduledFire = true,
            )
        }
    }
}

// ─── Merged from ScheduledInAppTimer.kt ──────────────────────────────────────────────────

/**
 * [FIX-261025-19] Fires a timed scheduled task from inside the process at its
 * exact time, next to its AlarmManager alarm - iOS's in-process
 * `ScheduledJobRunner.sleepUntil`.
 *
 * Without SCHEDULE_EXACT_ALARM (denied by default on Android 14+) the alarm is
 * inexact and the system may deliver it minutes late, so the chat's countdown
 * pill sat at 00:00 with the app open in front of it. While the process is
 * alive this timer runs the task on time instead; the alarm stays the
 * fallback for a dead or frozen process.
 *
 * Whichever reaches a run first takes it; the other finds it taken:
 * - The timer claims through [ScheduledRunNow.start] with the run key read
 *   when it started waiting ([ScheduledTaskManager.pendingRunKey]). If the
 *   alarm fired that run first, the key has moved on and the claim fails.
 * - The claim moves a relative trigger's count / anchor or records a
 *   calendar slot ([ScheduledTask.runNowSlotMs]) and re-arms the alarm for
 *   the next run, so an alarm for the taken run that was already on its way
 *   is turned away by [ScheduledTaskManager.rescheduleNext].
 *
 * It fires only while Minis is in front or its foreground service is up: a
 * run started from the background would not be allowed to start the service
 * and could be killed after its run was claimed. Otherwise the alarm, which
 * may start the service, keeps the run.
 */
object ScheduledInAppTimer {

    private const val TAG = "SchedInAppTimer"

    /** Re-read the wall clock at least this often while waiting, like iOS's 30 s step. */
    private const val SLEEP_STEP_MS = 30_000L

    private val started = AtomicBoolean(false)

    /**
     * Held for the life of the process. The store's change listener is only
     * weakly referenced by SharedPreferences, so a collector parked on it with
     * nothing else pointing at it could be collected and never wake again.
     */
    @Volatile
    private var job: Job? = null

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            // Every change to the task list (create, edit, a fire's bookkeeping)
            // re-plans from scratch.
            ScheduledTaskStore(app).observe().collectLatest { tasks -> fireAsDue(app, tasks) }
        }
    }

    private suspend fun fireAsDue(app: Context, tasks: List<ScheduledTask>) {
        // Runs this list has already been tried for: a refused or skipped one
        // waits for the next change (the alarm's fire is one) instead of spinning.
        val tried = HashSet<String>()
        while (true) {
            val due = nextDue(tasks, System.currentTimeMillis(), tried) ?: return
            AppLogger.info(TAG, "task=${due.task.id} ${due.runKey} waiting until ${due.at}")
            sleepUntil(due.at)
            tried += due.tryKey
            if (!canFire()) {
                AppLogger.info(TAG, "task=${due.task.id} due, Minis in background - left to its alarm")
                continue
            }
            // Just before the slot, so the claim takes the run this wait was for.
            val ok = withContext(Dispatchers.IO) {
                ScheduledRunNow.start(app, due.task.id, expectedRunKey = due.runKey, now = due.at - 1)
            }
            val lateMs = System.currentTimeMillis() - due.at
            AppLogger.info(TAG, "task=${due.task.id} ${due.runKey} fired=$ok late=${lateMs}ms")
        }
    }

    internal data class Due(val task: ScheduledTask, val at: Long, val runKey: String) {
        val tryKey: String get() = "${task.id}|$runKey"
    }

    /** The soonest timed run not yet tried. Pure, for tests. */
    internal fun nextDue(tasks: List<ScheduledTask>, now: Long, tried: Set<String>): Due? =
        tasks.asSequence()
            .filter { it.enabled && it.triggerKind != ScheduledTriggerKind.ON_COMPLETION }
            .mapNotNull { t ->
                val at = t.nextTriggerMs(now) ?: return@mapNotNull null
                val key = ScheduledTaskManager.pendingRunKey(t, now) ?: return@mapNotNull null
                Due(t, at, key).takeIf { it.tryKey !in tried }
            }
            .minByOrNull { it.at }

    private suspend fun sleepUntil(at: Long) {
        while (true) {
            val remaining = at - System.currentTimeMillis()
            if (remaining <= 0) return
            delay(minOf(remaining, SLEEP_STEP_MS))
        }
    }

    private fun canFire(): Boolean =
        AgentForegroundService.isAlive || runCatching {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }.getOrDefault(false)
}

// ─── Merged from ScheduledModelSource.kt ──────────────────────────────────────────────────

/**
 * [OpenMinis#445] What `minis-scheduled create --model` names, resolved at
 * create time into the same `modelBinding` the task editor writes.
 *
 * The flag used to be stored verbatim as `modelId`, and a fire matched it
 * against `model.id`: `entry:<id>` (the form `minis-config get
 * session.primaryModel` prints) or `<instanceId>/<modelId>` matched nothing and
 * the run quietly fell back to the default group, while a bare model id served
 * by two provider instances ran on whichever sorted first - possibly another
 * account. Accepted now, the same on iOS:
 *   - `entry:<entryId>` or a bare entry id -> that exact entry
 *   - `group:<groupId>` -> that group
 *   - a bare model id -> the one visible entry serving it; two or more is an
 *     error that lists their entry ids instead of picking one
 * Anything else is an error at create time.
 *
 * Pure: callers pass the entries and groups, so this is unit-tested directly.
 * The JSON is written the way the task editor and ChatViewModel write it.
 */
object ScheduledModelSource {

    data class EntryRef(val entryId: String, val modelId: String, val visible: Boolean)
    data class GroupRef(val groupId: String)

    /** [bindingJson] goes to ScheduledTask.modelBinding, [modelId] to its legacy modelId. */
    data class Resolved(val bindingJson: String, val modelId: String?, val display: String)

    private const val LOOKUP_HINT = "run `minis-config get models` for entry ids, then pass entry:<id> or group:<id>"

    /** @throws IllegalArgumentException with a message the CLI prints as is. */
    fun resolve(raw: String, entries: List<EntryRef>, groups: List<GroupRef>): Resolved {
        val s = raw.trim()
        require(s.isNotEmpty()) { "--model is empty; $LOOKUP_HINT" }
        if (s.startsWith("group:")) {
            val gid = s.removePrefix("group:")
            require(gid.isNotEmpty()) { "--model group id is empty" }
            require(groups.any { it.groupId == gid }) { "--model: no model group with id '$gid'" }
            return Resolved("""{"type":"group","groupId":"$gid"}""", null, "group:$gid")
        }
        if (s.startsWith("entry:")) {
            val eid = s.removePrefix("entry:")
            require(eid.isNotEmpty()) { "--model entry id is empty; $LOOKUP_HINT" }
            val e = entries.firstOrNull { it.entryId == eid }
                ?: throw IllegalArgumentException("--model: no model entry with id '$eid'; $LOOKUP_HINT")
            return entry(e)
        }
        entries.firstOrNull { it.entryId == s }?.let { return entry(it) }
        val serving = entries.filter { it.visible && it.modelId == s }
        return when (serving.size) {
            1 -> entry(serving.single())
            0 -> throw IllegalArgumentException("--model: '$s' is not a model entry, group or visible model id; $LOOKUP_HINT")
            else -> throw IllegalArgumentException(
                "--model: '$s' is served by ${serving.size} model entries; pick one: " +
                    serving.joinToString(", ") { "entry:${it.entryId}" },
            )
        }
    }

    /**
     * Fire-time reading of a task created before create-time resolution: its
     * modelId may hold `entry:<id>`, `group:<id>` or a bare entry id. Those are
     * honoured; anything else (a bare model id, an entry that is gone) returns
     * null and the task keeps its old modelId path unchanged.
     */
    fun healLegacy(modelId: String, entries: List<EntryRef>, groups: List<GroupRef>): Resolved? {
        val s = modelId.trim()
        val named = s.startsWith("entry:") || s.startsWith("group:") || entries.any { it.entryId == s }
        if (!named) return null
        return runCatching { resolve(s, entries, groups) }.getOrNull()
    }

    /** The task's model in the CLI's own words, for `list` / `create` output. */
    fun display(bindingJson: String?, modelId: String?): String? {
        val o = bindingJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        when (o?.optString("type")) {
            "entry" -> o.optString("entryId").takeIf { it.isNotEmpty() }?.let { return "entry:$it" }
            "group" -> o.optString("groupId").takeIf { it.isNotEmpty() }?.let { return "group:$it" }
        }
        return modelId
    }

    fun entryRefs(entries: List<ModelEntry>, visible: List<ModelEntry>): List<EntryRef> {
        val visibleIds = visible.mapTo(HashSet()) { it.id }
        return entries.map { EntryRef(it.id, it.baseModel.id, it.id in visibleIds) }
    }

    fun groupRefs(groups: List<ModelGroup>): List<GroupRef> = groups.map { GroupRef(it.id) }

    private fun entry(e: EntryRef) = Resolved(
        """{"type":"entry","entryId":"${e.entryId}"}""",
        e.modelId,
        "entry:${e.entryId}",
    )
}

// ─── Merged from ScheduledRunNow.kt ──────────────────────────────────────────────────

/**
 * [T-sched-run-now] "Run now" on the chat's scheduled-task timer pill: fire
 * the task's PENDING next run immediately, through the same path an alarm
 * takes - [ScheduledTaskManager.claimRunNow] (the alarm's claim and
 * transition) and [ScheduledAgentRunner.run] with `scheduledFire = true`.
 *
 * Never more than one execution of a run:
 * - A start already in progress here wins: [inFlight] is a check-and-mark
 *   under one lock, so a double tap starts once.
 * - A run already in progress (or an alarm's fire being started, which holds
 *   [ScheduledSessionHold] before it claims) is refused, re-checked under the
 *   store's lock at claim time - not only when the menu opened.
 * - The alarm and "Run now" claim the run in the same atomic store step; the
 *   loser finds it taken (the anchor moved on, the count consumed, or the
 *   calendar slot recorded as run) and does nothing.
 */
object ScheduledRunNow {

    private const val TAG = "SchedRunNow"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = HashSet<String>()

    /** A "Run now" of [taskId] is being started (claimed, run not yet handed off). */
    fun isStarting(taskId: String): Boolean = synchronized(inFlight) { taskId in inFlight }

    /** Whether the pill's menu offers "Run now": only while counting down to an unclaimed run. */
    fun canOffer(countdown: Boolean, nextFireAt: Long?, running: Boolean, starting: Boolean): Boolean =
        countdown && nextFireAt != null && !running && !starting

    private fun isRunning(taskId: String): Boolean =
        taskId in ScheduledSessionHold.runningTaskIds.value.keys

    /**
     * Start the pending run of [taskId] now. Returns false, silently, when it
     * cannot: a start already in progress, a run already running, or nothing
     * pending any more (fired by its alarm meanwhile, disabled, exhausted).
     */
    fun start(
        context: Context,
        taskId: String,
        manager: ScheduledTaskManager = ScheduledTaskManager(context.applicationContext),
        /** The run the menu showed ([ScheduledTaskManager.pendingRunKey]); null skips that check. */
        expectedRunKey: String? = null,
        /** [FIX-261025-19] When the claim is judged; the in-app timer passes just before the slot. */
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        synchronized(inFlight) {
            if (isRunning(taskId) || !inFlight.add(taskId)) {
                AppLogger.info(TAG, "task=$taskId not started: already running or starting")
                return false
            }
        }
        // Checked under the store lock, before this start counts as running.
        val claimed = manager.claimRunNow(taskId, now, isRunning = ::isRunning, expectedRunKey = expectedRunKey)
        if (claimed == null) {
            synchronized(inFlight) { inFlight.remove(taskId) }
            AppLogger.info(TAG, "task=$taskId not started: no pending run to take")
            return false
        }
        // Held like the alarm holds it, so the chat and the pill read "running"
        // from here; run() keeps its own hold until the fire's loop ends.
        ScheduledSessionHold.beginRun(taskId)
        synchronized(inFlight) { inFlight.remove(taskId) }
        val app = context.applicationContext
        scope.launch {
            try {
                ScheduledAgentRunner.run(app, claimed, waitForCompletion = false, scheduledFire = true)
            } catch (t: Throwable) {
                AppLogger.error(TAG, "task=$taskId run now failed: ${t.message}")
            } finally {
                ScheduledSessionHold.endRun(taskId)
            }
        }
        AppLogger.info(TAG, "task=$taskId started now")
        return true
    }
}

// ─── Merged from ScheduledSessionHold.kt ──────────────────────────────────────────────────

/**
 * [T-scheduled-active-session-android] Which chats a scheduled task keeps
 * ACTIVE between its fires: they are not finished, they are waiting to be
 * woken. Port of iOS `AgentJobRegistry.sessionsKeptActiveBySchedule`
 * (f2891c01b).
 *
 * Only a task that comes back into the chat it was created in counts - a
 * follow-up ([ScheduledTargetMode.AppendToSession]) whose target is its own
 * [ScheduledTask.creatorSessionId] - because only then is "this chat is still
 * working" true from the user's side. A task made outside any chat, or one
 * that lands in a new chat, a different chat, a hidden child run or a rerun,
 * makes no chat active (iOS: new-chat, child, rerun, other-session and
 * creator-less jobs do not count). An on-completion task counts only while its
 * upstream still exists and is unfinished; otherwise it waits for something
 * that will never happen.
 *
 * The result feeds [com.openminis.app.service.SessionActivityTracker], which
 * unions it with the running loops into the one "processing" set that
 * keep-alive, Stop, the list spinner and the notification read.
 */
object ScheduledSessionHold {

    // BEGIN heldSession
    /**
     * The chat [task] keeps active at [now], or null. [upstreamUnfinished]
     * answers for an on-completion task's upstream id.
     *
     * [FIX-261025-03] [leadMs] is Settings > Background's "scheduled task
     * countdown": a timed task holds its chat only once its next run is at
     * most [leadMs] away (0 = never). Before, it held it for the whole wait,
     * so a daily task kept the foreground service, its notification and the
     * Live Updates chip up all day. The alarm starts the service itself when
     * the run is due, so nothing needs the hold before that. An on-completion
     * task has no clock: it holds unless the countdown is off.
     */
    fun heldSession(
        task: ScheduledTask,
        now: Long,
        upstreamUnfinished: (String) -> Boolean,
        leadMs: Long = Long.MAX_VALUE,
    ): String? {
        if (!task.enabled) return null
        if (leadMs <= 0L) return null
        val target = task.targetMode as? ScheduledTargetMode.AppendToSession ?: return null
        val creator = task.creatorSessionId ?: return null
        if (target.sessionId != creator) return null
        when (task.triggerKind) {
            ScheduledTriggerKind.ON_COMPLETION -> {
                val upstream = task.onCompletionOf ?: return null
                if (!upstreamUnfinished(upstream)) return null
            }
            // Disabled at its last fire (ONCE, AFTER, an INTERVAL's last of
            // --count), or no slot left (past the end date, no custom days).
            else -> {
                val next = task.nextTriggerMs(now) ?: return null
                if (next - now > leadMs) return null
            }
        }
        return creator
    }

    /**
     * [FIX-261025-03] The next instant at which a timed task enters its
     * [leadMs] window, so the held set can be re-derived then (nothing else
     * changes at that moment), or null when no task is waiting outside it.
     */
    fun nextWindowStart(tasks: List<ScheduledTask>, now: Long, leadMs: Long): Long? {
        if (leadMs <= 0L || leadMs == Long.MAX_VALUE) return null
        return tasks.asSequence()
            .filter { it.enabled && it.triggerKind != ScheduledTriggerKind.ON_COMPLETION && runHold(it) != null }
            .mapNotNull { it.nextTriggerMs(now) }
            .map { it - leadMs }
            .filter { it > now }
            .minOrNull()
    }

    /**
     * [FIX-261025-03] [derive] for every change of the inputs, and again each
     * time a task enters its countdown window.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal fun <I, T> windowed(
        inputs: Flow<I>,
        tasksOf: (I) -> List<ScheduledTask>,
        leadOf: (I) -> Long,
        derive: (I, Long) -> T,
    ): Flow<T> = inputs.flatMapLatest { i ->
        flow {
            while (true) {
                val now = System.currentTimeMillis()
                emit(derive(i, now))
                val next = nextWindowStart(tasksOf(i), now, leadOf(i)) ?: break
                // A little past the boundary so `next - now <= lead` holds.
                delay((next - System.currentTimeMillis()).coerceAtLeast(0L) + 50L)
            }
        }
    }
    // END heldSession

    /**
     * Whether [task] would still fire (iOS `liveScheduledJobs`: enabled and
     * pending): enabled with a next slot, or an enabled on-completion task
     * still waiting. What Stop turns off, and what its confirmation counts.
     */
    fun isLive(task: ScheduledTask, now: Long): Boolean =
        task.enabled && (task.triggerKind == ScheduledTriggerKind.ON_COMPLETION || task.nextTriggerMs(now) != null)

    fun heldSessions(
        tasks: List<ScheduledTask>,
        now: Long,
        upstreamUnfinished: (String) -> Boolean,
        leadMs: Long = Long.MAX_VALUE,
    ): Set<String> =
        tasks.mapNotNullTo(LinkedHashSet()) { heldSession(it, now, upstreamUnfinished, leadMs) }

    /**
     * Whether the upstream [id] of an on-completion task is still to finish:
     * a scheduled task being run right now, a scheduled task still due (an
     * enabled on-completion one is itself waiting), or an agent job still
     * pending or running. Agent jobs live in memory, so one from a previous
     * process is gone - and its dependents never fire, so they hold nothing.
     */
    fun upstreamUnfinished(
        id: String,
        tasks: List<ScheduledTask>,
        runningTaskIds: Set<String>,
        jobs: Map<String, AgentJob>,
        now: Long,
    ): Boolean {
        if (id in runningTaskIds) return true
        tasks.firstOrNull { it.id == id }?.let { t ->
            if (!t.enabled) return false
            return t.triggerKind == ScheduledTriggerKind.ON_COMPLETION || t.nextTriggerMs(now) != null
        }
        return jobs[id]?.isActive == true
    }

    /**
     * Tasks whose fire is being run right now (alarm received → agent loop
     * finished), by task id, counted per run. [ScheduledAgentRunner.run] holds
     * it for the whole run, which covers the two moments the store alone
     * cannot: the handoff before the turn starts, and the LAST fire of a task
     * that the alarm already disabled.
     */
    private val _runningRuns = MutableStateFlow<Map<String, Int>>(emptyMap())
    val runningTaskIds: StateFlow<Map<String, Int>> = _runningRuns.asStateFlow()

    fun beginRun(taskId: String) {
        _runningRuns.update { it + (taskId to ((it[taskId] ?: 0) + 1)) }
    }

    fun endRun(taskId: String) {
        _runningRuns.update { m ->
            val n = (m[taskId] ?: 0) - 1
            if (n > 0) m + (taskId to n) else m - taskId
        }
    }

    /**
     * The chats scheduled tasks keep active, re-derived on every change to the
     * persisted task list (create, edit, enable / disable, delete, each fire's
     * bookkeeping), to the runs in flight and to the agent jobs an
     * on-completion task may wait on. Persisted input, so it is right again
     * from the first emission after a process restart.
     */
    fun observe(context: Context): Flow<Set<String>> =
        windowed(
            combine(
                ScheduledTaskStore(context.applicationContext).observe(),
                _runningRuns,
                AgentJobRegistry.jobs,
                BackgroundSettingsRepository(context.applicationContext).scheduledCountdownLead,
            ) { tasks, running, jobs, lead ->
                Inputs(tasks, running.keys, jobs, BackgroundSettingsRepository.scheduledCountdownLeadMs(lead))
            },
            tasksOf = { it.tasks },
            leadOf = { it.leadMs },
        ) { i, now -> heldNow(i.tasks, i.runningIds, i.jobs, now, i.leadMs) }.distinctUntilChanged()

    /** [FIX-261025-03] One emission of the observed inputs. */
    internal data class Inputs(
        val tasks: List<ScheduledTask>,
        val runningIds: Set<String>,
        val jobs: Map<String, AgentJob>,
        val leadMs: Long,
    )

    /**
     * [T-port-sched-wait-hold-gap] What [observe] emits for one set of inputs:
     * the chats held by their schedule, plus the chat of every task whose fire
     * is being run right now. Pure, so a whole fire sequence can be replayed
     * in tests.
     */
    fun heldNow(
        tasks: List<ScheduledTask>,
        runningIds: Set<String>,
        jobs: Map<String, AgentJob>,
        now: Long,
        leadMs: Long = Long.MAX_VALUE,
    ): Set<String> =
        heldSessions(tasks, now, { upstreamUnfinished(it, tasks, runningIds, jobs, now) }, leadMs) +
            tasks.filter { it.id in runningIds }.mapNotNull(::runHold)

    /**
     * The chat a task keeps active while one of its fires is being run, enabled
     * or not (the alarm disables a task at its last fire, before the run): the
     * same follow-up-to-creator test as [heldSession], without the schedule.
     */
    fun runHold(task: ScheduledTask): String? {
        val target = task.targetMode as? ScheduledTargetMode.AppendToSession ?: return null
        return target.sessionId.takeIf { it == task.creatorSessionId }
    }
}

// ─── Merged from ScheduledTargetDelivery.kt ──────────────────────────────────────────────────

/**
 * [T-android-scheduled-default-follow-up] Where a scheduled task's fires land,
 * decided and announced in one place. Port of iOS `ScheduledTargetDelivery`
 * (c32dda5a7), without its child-of-current case (Android's CLI has no such
 * target).
 *
 * 1. Default target. `minis-scheduled create` without `--target` used to mean
 *    `new`, so a model that asked for "remind me in this chat" and simply left
 *    the flag out got every fire in a separate chat: the result never came
 *    back to the conversation that asked for it. Run from inside a chat, the
 *    default is now `follow-up` (this chat). With no chat behind the command
 *    (a script, an external automation) it stays `new`.
 * 2. Delivery sentence. `create` answers with one plain sentence saying where
 *    the fires will land, and whether the target was defaulted, so a wrong
 *    target is visible at once and can be corrected with delete + create
 *    instead of being discovered when the first fire goes missing.
 *
 * Pure (no Android types) so `ScheduledTargetDeliveryTest` runs on the JVM.
 */
object ScheduledTargetDelivery {

    /**
     * `--target` to use: the explicit one (lower-cased), else `follow-up` when a
     * session is known (`--session` or the chat running the CLI), else `new`.
     * Returns the target and whether it was defaulted.
     */
    fun resolveTarget(explicit: String?, session: String?, callerSession: String?): Pair<String, Boolean> {
        val e = explicit?.trim()
        if (!e.isNullOrEmpty()) return e.lowercase() to false
        return (if ((session ?: callerSession) != null) "follow-up" else "new") to true
    }

    /** Where each fire lands, with what the sentence needs to name it. */
    sealed class Destination {
        abstract val kind: String

        data class FollowUp(val sessionId: String, val title: String?, val isCallerSession: Boolean) : Destination() {
            override val kind = "follow-up"
        }

        data class NewChat(val label: String?) : Destination() {
            override val kind = "new"
        }

        data class Rerun(val sessionId: String, val messageId: String, val title: String?) : Destination() {
            override val kind = "rerun"
        }
    }

    /** One sentence for the model (English: CLI output is read by the model). */
    fun sentence(d: Destination, defaulted: Boolean): String = when (d) {
        is Destination.FollowUp -> {
            val name = sessionName(d.sessionId, d.title)
            if (d.isCallerSession) {
                "Delivery: each fire runs as a new turn IN THIS CHAT ($name)." +
                    if (defaulted) {
                        " --target defaulted to follow-up because this command ran inside a chat; " +
                            "to use a separate chat instead, delete this task and re-create it with --target new."
                    } else ""
            } else {
                "Delivery: each fire runs as a new turn in ANOTHER chat ($name), NOT in this conversation. " +
                    "If the result should come back here, delete this task and re-create it without --session."
            }
        }
        is Destination.NewChat -> {
            val titled = d.label?.takeIf { it.isNotBlank() }?.let { " titled “Scheduled · $it”" } ?: ""
            "Delivery: each fire opens a NEW, separate chat$titled; the result will NOT appear in this conversation." +
                if (defaulted) {
                    " --target defaulted to new because no chat was running this command."
                } else {
                    " If the user wanted it in this chat, delete this task and re-create it with --target follow-up."
                }
        }
        is Destination.Rerun ->
            "Delivery: each fire RE-RUNS user message ${d.messageId.take(8)} in ${sessionName(d.sessionId, d.title)}: " +
                "the existing reply is regenerated, and no new prompt is added."
    }

    /** Structured twin of [sentence] for scripts. */
    fun summary(d: Destination, defaulted: Boolean): Map<String, Any> {
        val out = linkedMapOf<String, Any>("target" to d.kind, "defaulted" to defaulted)
        when (d) {
            is Destination.FollowUp -> {
                out["sessionId"] = d.sessionId
                out["isThisChat"] = d.isCallerSession
                d.title?.let { out["sessionTitle"] = it }
            }
            is Destination.NewChat -> d.label?.takeIf { it.isNotBlank() }?.let { out["newChatTitle"] = "Scheduled · $it" }
            is Destination.Rerun -> {
                out["sessionId"] = d.sessionId
                out["messageId"] = d.messageId
                d.title?.let { out["sessionTitle"] = it }
            }
        }
        return out
    }

    private fun sessionName(sid: String, title: String?): String =
        if (!title.isNullOrEmpty()) "session ${sid.take(8)} “$title”" else "session ${sid.take(8)}"
}

// ─── Merged from ScheduledTask.kt ──────────────────────────────────────────────────

/**
 * [T-android-scheduled-tasks-run-records] One recorded execution of a
 * scheduled task. The run-records screen lists these newest-first; a run with
 * a non-null [sessionId] is tappable → opens that chat.
 */
data class ScheduledRun(
    val firedAt: Long,
    val sessionId: String?,
    val preview: String?,
    val ok: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("firedAt", firedAt)
        if (sessionId != null) put("sessionId", sessionId)
        if (preview != null) put("preview", preview)
        put("ok", ok)
    }

    companion object {
        fun fromJson(o: JSONObject): ScheduledRun = ScheduledRun(
            firedAt = o.optLong("firedAt"),
            sessionId = if (o.has("sessionId")) o.optString("sessionId", null) else null,
            preview = if (o.has("preview")) o.optString("preview", null) else null,
            ok = o.optBoolean("ok", true),
        )
    }
}

/**
 * [T-android-scheduled-tasks-design / T-android-scheduled-tasks-full]
 * User-defined scheduled task that fires an AI action at a configured time.
 * Counterpart to the iOS Shortcuts surface (SendPrompt / FollowUpSession /
 * RetryRun App Intents) — on Android we own the scheduling layer ourselves
 * via AlarmManager, and the per-task [targetMode] mirrors the iOS intent set:
 *
 *   NEW_SESSION      ≈ SendPromptIntent with no session  → run prompt in a
 *                      fresh chat.
 *   APPEND_TO        ≈ FollowUpSessionIntent             → enqueue prompt into
 *                      an existing chat (follow-up).
 *   RERUN            ≈ RetryRunIntent                     → re-run an existing
 *                      chat from a chosen user message.
 *
 * Persistence: serialized to JSON via [toJson] / [fromJson] and stored in
 * [ScheduledTaskStore] (SharedPreferences). Schema lives here so the store
 * stays a thin layer over the JSON array.
 */
enum class ScheduledRepeatMode { ONCE, DAILY, WEEKDAYS, CUSTOM }

/**
 * [T-android-scheduled-triggers] How a task decides when to fire. Port of iOS
 * `AgentJobTrigger` (once / loop / cron / on-completion), persisted and armed
 * through AlarmManager instead of an in-process timer.
 *
 *  - [CALENDAR]: the original model and the default — a time of day plus
 *    [ScheduledTask.repeatMode] / days / window. Every task written before
 *    this existed is one, so the in-app editor's tasks are untouched.
 *  - [AFTER]: once, [ScheduledTask.delaySec] after [ScheduledTask.anchorMs]
 *    (`--after 30m`).
 *  - [INTERVAL]: every [ScheduledTask.intervalSec] from the anchor, at most
 *    [ScheduledTask.maxFires] times (`--interval 10m --count 6`); unbounded
 *    when maxFires is null.
 *  - [ON_COMPLETION]: once, when the scheduled task or agent job named by
 *    [ScheduledTask.onCompletionOf] finishes; `{{result}}` in the prompt is
 *    replaced by that result. No alarm: fired by [ScheduledCompletionTriggers].
 */
enum class ScheduledTriggerKind { CALENDAR, AFTER, INTERVAL, ON_COMPLETION }

/**
 * What the task does when it fires. Mirrors the iOS App Intent set.
 *  - [NewSession]: run [ScheduledTask.prompt] in a brand-new chat.
 *  - [AppendToSession]: append the prompt to an existing chat (follow-up).
 *  - [RerunMessage]: re-run an existing chat from a specific user message
 *    (the prompt is ignored — the message itself is replayed).
 */
sealed class ScheduledTargetMode {
    object NewSession : ScheduledTargetMode()
    data class AppendToSession(val sessionId: String) : ScheduledTargetMode()
    data class RerunMessage(val sessionId: String, val messageId: String) : ScheduledTargetMode()
    /**
     * [T-p2-agent-series] ≈ iOS `minis-scheduled --target child-of-current`:
     * run the prompt as an AGENT in a hidden child session of [sessionId],
     * and post the result back into that chat as an <agent_callback>
     * message (rendered as a callback card — there is no delegate block).
     */
    data class ChildOfCurrent(val sessionId: String) : ScheduledTargetMode()

    fun encode(): String = when (this) {
        NewSession -> "NEW_SESSION"
        is AppendToSession -> "APPEND_TO:$sessionId"
        is RerunMessage -> "RERUN:$sessionId:$messageId"
        is ChildOfCurrent -> "CHILD_OF:$sessionId"
    }

    /** Session this task targets, or null for NEW_SESSION. */
    val sessionIdOrNull: String?
        get() = when (this) {
            NewSession -> null
            is AppendToSession -> sessionId
            is RerunMessage -> sessionId
            is ChildOfCurrent -> sessionId
        }

    companion object {
        fun decode(raw: String?): ScheduledTargetMode {
            if (raw.isNullOrEmpty() || raw == "NEW_SESSION") return NewSession
            if (raw.startsWith("APPEND_TO:")) {
                return AppendToSession(raw.removePrefix("APPEND_TO:"))
            }
            if (raw.startsWith("CHILD_OF:")) {
                return ChildOfCurrent(raw.removePrefix("CHILD_OF:"))
            }
            if (raw.startsWith("RERUN:")) {
                // RERUN:<sessionId>:<messageId> — split on the FIRST colon only,
                // since ids are UUIDs (no embedded colons) but be defensive.
                val rest = raw.removePrefix("RERUN:")
                val idx = rest.indexOf(':')
                if (idx > 0) {
                    return RerunMessage(rest.substring(0, idx), rest.substring(idx + 1))
                }
            }
            return NewSession
        }
    }
}

data class ScheduledTask(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val timeOfDayHour: Int,                  // 0-23
    val timeOfDayMinute: Int,                // 0-59
    val repeatMode: ScheduledRepeatMode,
    val customDays: Set<Int> = emptySet(),   // Calendar.DAY_OF_WEEK values when repeatMode=CUSTOM
    val prompt: String,
    val targetMode: ScheduledTargetMode = ScheduledTargetMode.NewSession,
    val modelId: String? = null,             // null → use app default (or fall back from modelBinding's resolved entry)
    // [T-android-scheduled-task-model-binding] Mirrors ChatSessionEntity.modelBinding.
    // JSON: `{"type":"group","groupId":"..."}` or `{"type":"entry","entryId":"..."}`
    // null → "use app default" (ScheduledAgentRunner resolves to defaultPrimaryGroupId).
    // When non-null, ScheduledAgentRunner writes it onto the new session row so the
    // chat picks up the same group/entry the user chose for the task.
    val modelBinding: String? = null,
    // [T-android-scheduled-task-thinking] Reasoning effort for this task's run.
    // null = "inherit" — the session resolves its own level from the bound
    // group's default (or OFF), exactly as before this field existed, so every
    // task written by an older build keeps its current behaviour.
    val thinkingLevel: ThinkingLevel? = null,
    // [T-scheduled-tool-prefill] A tool call executed before the model is
    // asked anything; the model then only summarises its result. null = the
    // old behaviour (the model reads the prompt and decides), which is what
    // every task written before this field existed deserializes to.
    val prefillToolCall: PrefilledToolCall? = null,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    // [T-android-scheduled-tasks-full] Optional active window. The task only
    // fires on/after startDateMs and on/before endDateMs (both = start-of-day
    // local epoch ms, inclusive). null = unbounded on that side. A ONCE task
    // with a startDate fires on the first matching day at/after the start;
    // repeating tasks skip days outside the window and auto-disable once the
    // end date has fully passed.
    val startDateMs: Long? = null,
    val endDateMs: Long? = null,
    val lastFiredAt: Long? = null,
    val lastResultPreview: String? = null,
    val lastResultSessionId: String? = null,
    // [T-android-scheduled-tasks-run-records] Newest-first execution log. The
    // list screen no longer shows a result preview; instead the long-press
    // "Run records" menu opens a screen backed by this. Capped at
    // MAX_RUN_HISTORY by the manager when appending.
    val runHistory: List<ScheduledRun> = emptyList(),
    // [T-scheduled-task-detail] Total fires ever recorded. [runHistory] is
    // capped at MAX_RUN_HISTORY, so its size stops counting after 50; this
    // does not. null on tasks written before it existed — see [firesSoFar].
    val fireCount: Int? = null,
    // [T-android-scheduled-triggers] See [ScheduledTriggerKind]. All optional
    // with defaults that reproduce the calendar model, so a task written before
    // these existed deserializes to exactly what it was.
    val triggerKind: ScheduledTriggerKind = ScheduledTriggerKind.CALENDAR,
    /** [ScheduledTriggerKind.AFTER]: seconds from [anchorMs] to the fire. */
    val delaySec: Long? = null,
    /** [ScheduledTriggerKind.INTERVAL]: seconds between fires (>= 60). */
    val intervalSec: Long? = null,
    /** [ScheduledTriggerKind.INTERVAL]: total fires; null = until deleted. */
    val maxFires: Int? = null,
    /** [ScheduledTriggerKind.ON_COMPLETION]: the task id or agent job id to wait for. */
    val onCompletionOf: String? = null,
    /**
     * AFTER / INTERVAL: when the current countdown started — creation, the
     * last alarm fire (INTERVAL), or re-enabling. Absolute, so a reboot or a
     * killed process resumes the same schedule instead of restarting it.
     */
    val anchorMs: Long? = null,
    /**
     * AFTER / INTERVAL: alarm fires since the last (re)start. Counted when the
     * alarm fires, not when the run completes, so the count can never race the
     * next fire. A manual "Run now" does not count (iOS's `fireNow` does; on
     * Android it is kept off the count so it cannot use up an interval's
     * --count or finish its schedule early).
     */
    val triggeredCount: Int? = null,
    /**
     * [T-scheduled-active-session-android] The chat this task was created IN
     * (the chat running `minis-scheduled create`), iOS `creatorSessionId`.
     * A follow-up aimed back at this chat keeps it "processing" between fires
     * ([ScheduledSessionHold]). null for a task made outside any chat (the
     * editor, an RPC) and for every task written before this field existed:
     * those keep no chat active, as on iOS.
     */
    val creatorSessionId: String? = null,
    /**
     * [T-sched-run-now] CALENDAR only: the planned slot of the pending run
     * that "Run now" on the chat's timer pill already fired early. Next-run
     * computation starts after it, and an alarm for that slot that was
     * already on its way finds it taken and does not run. null otherwise.
     * Not carried over by an edit (the editor re-plans the schedule).
     *
     * [FIX-261025-19] Also the slot the in-app timer ([ScheduledInAppTimer])
     * or the alarm itself last ran, so whichever of the two reaches a slot
     * second finds it taken.
     */
    val runNowSlotMs: Long? = null,
) {

    val isCalendar: Boolean get() = triggerKind == ScheduledTriggerKind.CALENDAR

    /** AFTER / INTERVAL count from [anchorMs]. */
    val usesAnchor: Boolean
        get() = triggerKind == ScheduledTriggerKind.AFTER || triggerKind == ScheduledTriggerKind.INTERVAL

    /** Fires at most once, then disables itself (and stays listed). */
    val isOneShot: Boolean get() = when (triggerKind) {
        ScheduledTriggerKind.CALENDAR -> repeatMode == ScheduledRepeatMode.ONCE
        ScheduledTriggerKind.AFTER, ScheduledTriggerKind.ON_COMPLETION -> true
        ScheduledTriggerKind.INTERVAL -> false
    }

    /** INTERVAL fires still to come; null when unbounded or not an interval. */
    val remainingFires: Int?
        get() = if (triggerKind == ScheduledTriggerKind.INTERVAL) {
            maxFires?.let { (it - (triggeredCount ?: 0)).coerceAtLeast(0) }
        } else null

    /** [T-scheduled-task-detail] Fires recorded so far, falling back to the
     *  history length for tasks that predate [fireCount]. */
    val firesSoFar: Int get() = fireCount ?: runHistory.size

    /**
     * Wall-clock ms of the next firing time for this task, taking
     * [repeatMode] / [customDays] / [startDateMs] / [endDateMs] / current time
     * into account. Returns `null` when [enabled] is false, the task has no
     * valid next slot (CUSTOM with an empty day set), or the next slot would
     * fall after [endDateMs].
     *
     * Uses [Calendar.getInstance] so the local time zone is respected — users
     * schedule "09:00 every day" in their wall-clock sense, not UTC.
     */
    fun nextTriggerMs(now: Long = System.currentTimeMillis()): Long? {
        if (!enabled) return null

        // [T-android-scheduled-triggers] The relative triggers. An overdue
        // slot (the phone was off, the process dead) answers `now`: the alarm
        // then fires once, late, and INTERVAL continues from there — the same
        // catch-up iOS's sleepUntil gives a missed deadline.
        when (triggerKind) {
            ScheduledTriggerKind.AFTER -> {
                if ((triggeredCount ?: 0) >= 1) return null
                val d = delaySec ?: return null
                return maxOf(now, (anchorMs ?: createdAt) + d * 1000)
            }
            ScheduledTriggerKind.INTERVAL -> {
                val iv = intervalSec ?: return null
                if (maxFires != null && (triggeredCount ?: 0) >= maxFires) return null
                return maxOf(now, (anchorMs ?: createdAt) + iv * 1000)
            }
            // Event-driven: no clock time at all.
            ScheduledTriggerKind.ON_COMPLETION -> return null
            ScheduledTriggerKind.CALENDAR -> Unit
        }

        // Earliest instant we may fire: max(now, start-of-startDate). This lets
        // a task created today with a future startDate wait until that day.
        // [T-sched-run-now] A slot "Run now" fired early is not pending again.
        val floor = maxOf(now, startDateMs ?: Long.MIN_VALUE, runNowSlotMs?.let { it + 1 } ?: Long.MIN_VALUE)

        val cal = Calendar.getInstance().apply {
            timeInMillis = floor
            set(Calendar.HOUR_OF_DAY, timeOfDayHour)
            set(Calendar.MINUTE, timeOfDayMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val candidate: Long = when (repeatMode) {
            ScheduledRepeatMode.ONCE, ScheduledRepeatMode.DAILY -> {
                if (cal.timeInMillis < floor) cal.add(Calendar.DAY_OF_YEAR, 1)
                cal.timeInMillis
            }
            ScheduledRepeatMode.WEEKDAYS -> {
                if (cal.timeInMillis < floor) cal.add(Calendar.DAY_OF_YEAR, 1)
                var safety = 8
                while (safety-- > 0 && cal.get(Calendar.DAY_OF_WEEK).let {
                        it == Calendar.SATURDAY || it == Calendar.SUNDAY
                    }) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                cal.timeInMillis
            }
            ScheduledRepeatMode.CUSTOM -> {
                if (customDays.isEmpty()) return null
                if (cal.timeInMillis < floor) cal.add(Calendar.DAY_OF_YEAR, 1)
                var safety = 8
                while (safety-- > 0 && cal.get(Calendar.DAY_OF_WEEK) !in customDays) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                if (safety < 0) return null
                cal.timeInMillis
            }
        }

        // Respect the end-of-window: endDateMs is the start-of-day of the last
        // allowed day, so the task may fire any time up to end-of-that-day.
        val end = endDateMs
        if (end != null) {
            val endOfDay = Calendar.getInstance().apply {
                timeInMillis = end
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }.timeInMillis
            if (candidate > endOfDay) return null
        }
        return candidate
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("hour", timeOfDayHour)
        put("minute", timeOfDayMinute)
        put("repeatMode", repeatMode.name)
        put("customDays", customDays.joinToString(","))
        put("prompt", prompt)
        put("targetMode", targetMode.encode())
        if (modelId != null) put("modelId", modelId)
        if (modelBinding != null) put("modelBinding", modelBinding)
        if (thinkingLevel != null) put("thinkingLevel", thinkingLevel.name)
        if (prefillToolCall != null) put("prefillToolCall", prefillToolCall.toJson())
        put("enabled", enabled)
        put("createdAt", createdAt)
        if (startDateMs != null) put("startDateMs", startDateMs)
        if (endDateMs != null) put("endDateMs", endDateMs)
        if (lastFiredAt != null) put("lastFiredAt", lastFiredAt)
        if (lastResultPreview != null) put("lastResultPreview", lastResultPreview)
        if (lastResultSessionId != null) put("lastResultSessionId", lastResultSessionId)
        if (runHistory.isNotEmpty()) {
            put("runHistory", JSONArray().apply { runHistory.forEach { put(it.toJson()) } })
        }
        if (fireCount != null) put("fireCount", fireCount)
        if (triggerKind != ScheduledTriggerKind.CALENDAR) put("trigger", triggerKind.name)
        if (delaySec != null) put("delaySec", delaySec)
        if (intervalSec != null) put("intervalSec", intervalSec)
        if (maxFires != null) put("maxFires", maxFires)
        if (onCompletionOf != null) put("onCompletionOf", onCompletionOf)
        if (anchorMs != null) put("anchorMs", anchorMs)
        if (triggeredCount != null) put("triggeredCount", triggeredCount)
        if (creatorSessionId != null) put("creatorSessionId", creatorSessionId)
        if (runNowSlotMs != null) put("runNowSlotMs", runNowSlotMs)
    }

    companion object {
        /** Max recorded executions kept per task. */
        const val MAX_RUN_HISTORY = 50

        fun fromJson(o: JSONObject): ScheduledTask = ScheduledTask(
            id = o.getString("id"),
            label = o.optString("label", ""),
            timeOfDayHour = o.optInt("hour", 9),
            timeOfDayMinute = o.optInt("minute", 0),
            repeatMode = runCatching {
                ScheduledRepeatMode.valueOf(o.optString("repeatMode", "ONCE"))
            }.getOrDefault(ScheduledRepeatMode.ONCE),
            customDays = o.optString("customDays", "")
                .split(',').mapNotNull { it.trim().toIntOrNull() }.toSet(),
            prompt = o.optString("prompt", ""),
            targetMode = ScheduledTargetMode.decode(o.optString("targetMode", null)),
            modelId = if (o.has("modelId")) o.optString("modelId", null) else null,
            modelBinding = if (o.has("modelBinding")) o.optString("modelBinding", null) else null,
            thinkingLevel = o.optString("thinkingLevel", "").takeIf { it.isNotEmpty() }
                ?.let { ThinkingLevel.parseOrNull(it) },
            prefillToolCall = PrefilledToolCall.fromJson(o.optJSONObject("prefillToolCall")),
            enabled = o.optBoolean("enabled", true),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            startDateMs = if (o.has("startDateMs")) o.optLong("startDateMs") else null,
            endDateMs = if (o.has("endDateMs")) o.optLong("endDateMs") else null,
            lastFiredAt = if (o.has("lastFiredAt")) o.optLong("lastFiredAt") else null,
            lastResultPreview = if (o.has("lastResultPreview"))
                o.optString("lastResultPreview", null) else null,
            lastResultSessionId = if (o.has("lastResultSessionId"))
                o.optString("lastResultSessionId", null) else null,
            runHistory = o.optJSONArray("runHistory")?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { add(ScheduledRun.fromJson(it)) }
                    }
                }
            } ?: emptyList(),
            fireCount = if (o.has("fireCount")) o.optInt("fireCount") else null,
            // An unknown value (written by a newer build) reads as CALENDAR,
            // which at worst fires on the stored time of day rather than never.
            triggerKind = runCatching {
                ScheduledTriggerKind.valueOf(o.optString("trigger", "CALENDAR"))
            }.getOrDefault(ScheduledTriggerKind.CALENDAR),
            delaySec = if (o.has("delaySec")) o.optLong("delaySec") else null,
            intervalSec = if (o.has("intervalSec")) o.optLong("intervalSec") else null,
            maxFires = if (o.has("maxFires")) o.optInt("maxFires") else null,
            onCompletionOf = if (o.has("onCompletionOf")) o.optString("onCompletionOf", null) else null,
            anchorMs = if (o.has("anchorMs")) o.optLong("anchorMs") else null,
            triggeredCount = if (o.has("triggeredCount")) o.optInt("triggeredCount") else null,
            creatorSessionId = if (o.has("creatorSessionId")) o.optString("creatorSessionId", null) else null,
            runNowSlotMs = if (o.has("runNowSlotMs")) o.optLong("runNowSlotMs") else null,
        )

        /** `{{result}}` in an on-completion prompt, replaced by the upstream's result. */
        const val RESULT_PLACEHOLDER = "{{result}}"
    }
}

// ─── Merged from ScheduledTaskAlarmReceiver.kt ──────────────────────────────────────────────────

/**
 * [T-android-scheduled-tasks-design] Receives an AlarmManager fire and
 * hands the task off to [ScheduledAgentRunner].
 *
 * This is a HANDOFF, not a run: everything here must finish in
 * milliseconds. goAsync() covers only "load the task, arm the next
 * occurrence, start the FGS and dispatch the prompt"; the agent loop
 * itself then runs on [ScheduledAgentRunner]'s app-scoped scope, kept
 * alive by the foreground service (and its wake lock) rather than by this
 * broadcast.
 *
 * [GH#197] It used to await the entire agent loop before calling
 * pending.finish(), which held the broadcast for minutes and ANR-killed
 * the process — and with it every PRoot sandbox child. goAsync() alone
 * does not buy unlimited time; it only defers the deadline, so the work
 * it wraps still has to be short.
 */
class ScheduledTaskAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScheduledTaskManager.ACTION_FIRE) return
        val taskId = intent.getStringExtra(ScheduledTaskManager.EXTRA_TASK_ID) ?: return

        AppLogger.info(TAG, "fire received: task=$taskId")

        val pending = goAsync()
        val appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val startedAt = SystemClock.elapsedRealtime()
        scope.launch {
            // [T-scheduled-active-session-android] Held from before the alarm
            // advances the task until the handoff returns; run() holds its own
            // count until the fire's loop ends. Without this, the LAST fire's
            // "disabled" write could reach ScheduledSessionHold before run()
            // marks the task as running, and the chat would flash idle.
            ScheduledSessionHold.beginRun(taskId)
            try {
                // [GH#197] Hard ceiling on how long this broadcast can stay
                // alive. Everything inside is meant to take milliseconds; the
                // timeout exists so that if any step wedges (a stalled DB
                // read, a hung FGS start), we still release the broadcast
                // instead of holding it until the system ANRs us.
                withTimeout(HANDOFF_TIMEOUT_MS) {
                    val manager = ScheduledTaskManager(appContext)
                    val task = manager.get(taskId) ?: run {
                        AppLogger.warning(TAG, "task $taskId not found in store — skipping")
                        return@withTimeout
                    }
                    if (!task.enabled) {
                        AppLogger.info(TAG, "task $taskId disabled — skipping fire")
                        return@withTimeout
                    }
                    // Schedule the next occurrence FIRST so a long-running
                    // prompt doesn't block tomorrow's fire even if we crash.
                    // [T-android-scheduled-fire-claim] False for a stale second
                    // delivery of a relative trigger's fire: nothing to run.
                    val slot = intent.getLongExtra(ScheduledTaskManager.EXTRA_TRIGGER_AT, -1L).takeIf { it > 0 }
                    if (!manager.rescheduleNext(taskId, slot)) return@withTimeout
                    // [T-android-scheduled-triggers] Run the row as it is NOW:
                    // rescheduleNext just advanced an interval's anchor and
                    // count, and the fire's envelope shows the NEXT fire time.
                    val current = manager.get(taskId) ?: task

                    // [GH#197] waitForCompletion=false is load-bearing, not a
                    // tweak. With the default (true) this call suspends until
                    // the whole agent loop finishes — up to RUN_TIMEOUT_MS
                    // (10 min) — and because HeadlessChatRunner.prompt/retry
                    // are `withContext(Dispatchers.Main)`, that wait also
                    // lands back on the main thread. pending.finish() then
                    // sits behind it, blowing far past the ~10s budget a
                    // manifest-declared receiver gets, so the system ANRs the
                    // process and kills it — taking every PRoot sandbox child
                    // with it. The UI "Run now" path already passes false
                    // (638f3eb0b); the alarm path silently kept the default.
                    //
                    // Nothing is lost by not waiting: run() still resolves the
                    // session and starts the foreground service (which holds
                    // the wake lock) before returning, and mark-fired plus the
                    // completion notification are finished off ScheduledAgent-
                    // Runner's app-scoped bgScope, which outlives this
                    // receiver.
                    ScheduledAgentRunner.run(appContext, current, waitForCompletion = false, scheduledFire = true)
                }
            } catch (t: Throwable) {
                // Includes TimeoutCancellationException. The task stays
                // scheduled (rescheduleNext already armed the next occurrence)
                // so a wedged fire self-heals instead of killing the process.
                AppLogger.error(TAG, "task $taskId fire failed: ${t.message}")
            } finally {
                ScheduledSessionHold.endRun(taskId)
                pending.finish()
                // [GH#197] The number to watch: this is the goAsync hold time,
                // i.e. how much of the broadcast ANR budget we consumed. It
                // must stay in the tens of milliseconds. If it ever creeps
                // toward HANDOFF_TIMEOUT_MS, something on the handoff path
                // started blocking again.
                AppLogger.info(
                    TAG,
                    "task $taskId handoff done in ${SystemClock.elapsedRealtime() - startedAt}ms",
                )
            }
        }
    }

    companion object {
        private const val TAG = "ScheduledTaskRecv"

        /**
         * [GH#197] Ceiling for the whole handoff (load task → arm next
         * occurrence → start FGS + dispatch). A manifest-declared receiver
         * gets roughly 10s of background-broadcast budget before the system
         * ANRs it; 8s leaves headroom to log and finish cleanly while still
         * being ~100x more than the handoff actually needs.
         */
        private const val HANDOFF_TIMEOUT_MS = 8_000L
    }
}

// ─── Merged from ScheduledTaskDetailModel.kt ──────────────────────────────────────────────────

/**
 * [T-scheduled-task-detail] Everything the scheduled-task detail page shows,
 * derived without Android UI so it can be unit-tested. Shared design with iOS
 * (T-scheduled-task-detail), mapped onto
 * Android's model:
 *
 *  - The task is persisted ([ScheduledTaskStore]) and survives a restart, as
 *    does its run history, so the iOS "no live job → Stopped after relaunch"
 *    row does not exist here. Its Android counterpart is a task that has been
 *    DELETED while its cards stay in the chat.
 *  - Triggers are calendar-based (once / daily / weekdays / custom days at
 *    HH:mm, optionally inside a start/end window), or, since
 *    [T-android-scheduled-triggers], a relative one-shot (--after), an interval
 *    loop (--interval, --count) or on-completion. A total count is known for a
 *    one-shot, a window with an end date, or a loop with a --count.
 *  - History comes from [ScheduledTask.runHistory] (persisted, all sessions,
 *    with the session and ok flag) plus the fire whose card was tapped, which
 *    is not recorded until it completes. A newer undelivered fire replaces an
 *    older one instead of being counted, so there is no "+K merged" badge.
 *
 * The status is never "Interrupted": nothing here maps "not live" to it.
 */
object ScheduledTaskDetailModel {

    enum class Status { RUNNING, WAITING, COMPLETED, FAILED, DISABLED, DELETED }

    enum class FireState { OK, FAILED, RUNNING }

    data class FireRow(
        /** 1-based position among all fires of the task. */
        val index: Int,
        val firedAt: Long,
        val sessionId: String?,
        val state: FireState,
        val preview: String?,
        /** The fire whose card was tapped. */
        val isCurrent: Boolean,
        /** Ran in a different chat than the one the page was opened from. */
        val inOtherSession: Boolean,
    )

    data class Progress(
        /** Index of the tapped fire, when it can be identified. */
        val currentIndex: Int?,
        /** Total fires, when the task has a finite number of them. */
        val total: Int?,
        /** Fires still to come; null = unbounded. */
        val remaining: Int?,
        /** Next scheduled fire, or null when there is none. */
        val nextFireAt: Long?,
        val firesSoFar: Int,
    ) {
        /** The progress card appears only when there is something to count. */
        val visible: Boolean get() = currentIndex != null || total != null
    }

    /** Cap on the forward walk that counts remaining fires in a window. */
    private const val REMAINING_WALK_CAP = 1000

    /**
     * Is the tapped fire still being processed? True when it has not been
     * recorded yet and the chat it runs in is streaming — the recording
     * happens when the run completes.
     */
    fun isFireRunning(task: ScheduledTask?, marker: ScheduledTaskMarker, hostStreaming: Boolean): Boolean {
        val firedAt = marker.firedAtMs ?: return false
        val recorded = task?.runHistory?.any { it.firedAt == firedAt } == true
        return !recorded && hostStreaming
    }

    /** Priority order per the shared design §4, adapted to Android. */
    fun status(task: ScheduledTask?, fireRunning: Boolean, now: Long): Status {
        if (task == null) return Status.DELETED
        if (fireRunning) return Status.RUNNING
        val next = task.nextTriggerMs(now)
        if (task.isOneShot) {
            // A one-shot task disables itself after firing; its single run
            // decides how it ended. An on-completion task has no clock time:
            // enabled means it is still waiting for its upstream.
            val waiting = next != null || task.triggerKind == ScheduledTriggerKind.ON_COMPLETION
            if (task.enabled && waiting) return Status.WAITING
            val last = task.runHistory.firstOrNull() ?: return Status.DISABLED
            return if (last.ok) Status.COMPLETED else Status.FAILED
        }
        if (task.enabled) return if (next != null) Status.WAITING else Status.COMPLETED
        // Disabled: finished if its window has run out, otherwise stopped by
        // the user.
        return if (windowEnded(task, now)) Status.COMPLETED else Status.DISABLED
    }

    private fun windowEnded(task: ScheduledTask, now: Long): Boolean =
        // An interval that used up its --count ended the same way a window does.
        task.remainingFires == 0 ||
            (task.endDateMs != null && task.copy(enabled = true).nextTriggerMs(now) == null)

    fun progress(task: ScheduledTask?, marker: ScheduledTaskMarker, fireRunning: Boolean, now: Long): Progress {
        if (task == null) return Progress(null, null, null, null, 0)
        val fired = task.firesSoFar
        val next = if (task.enabled) task.nextTriggerMs(now) else null
        val remaining: Int? = when {
            task.triggerKind == ScheduledTriggerKind.ON_COMPLETION -> if (task.enabled) 1 else 0
            next == null -> 0
            task.isOneShot -> 1
            task.triggerKind == ScheduledTriggerKind.INTERVAL -> task.remainingFires
            task.endDateMs == null -> null
            else -> countFires(task, now)
        }
        val inFlight = if (fireRunning) 1 else 0
        val total = remaining?.let { fired + inFlight + it }
            ?.takeIf {
                task.isOneShot || task.endDateMs != null ||
                    (task.triggerKind == ScheduledTriggerKind.INTERVAL && task.maxFires != null)
            }
        return Progress(
            currentIndex = currentIndex(task, marker, fireRunning),
            total = total,
            remaining = remaining,
            nextFireAt = next,
            firesSoFar = fired,
        )
    }

    private fun countFires(task: ScheduledTask, now: Long): Int {
        var n = 0
        var t = task.nextTriggerMs(now) ?: return 0
        while (n < REMAINING_WALK_CAP) {
            n++
            t = task.nextTriggerMs(t + 60_000L) ?: break
        }
        return n
    }

    private fun currentIndex(task: ScheduledTask, marker: ScheduledTaskMarker, fireRunning: Boolean): Int? {
        val firedAt = marker.firedAtMs ?: return null
        val pos = task.runHistory.indexOfFirst { it.firedAt == firedAt }
        if (pos >= 0) return task.firesSoFar - pos
        return if (fireRunning) task.firesSoFar + 1 else null
    }

    /**
     * Newest first. The recorded runs, plus the tapped fire when it is still
     * running (it is recorded only when it completes). Rows are unique by
     * fire time.
     */
    fun history(
        task: ScheduledTask?,
        marker: ScheduledTaskMarker,
        hostSessionId: String?,
        fireRunning: Boolean,
    ): List<FireRow> {
        val runs = task?.runHistory ?: emptyList()
        val fired = task?.firesSoFar ?: runs.size
        val rows = runs.mapIndexed { pos, run ->
            FireRow(
                index = fired - pos,
                firedAt = run.firedAt,
                sessionId = run.sessionId,
                state = if (run.ok) FireState.OK else FireState.FAILED,
                preview = run.preview,
                isCurrent = marker.firedAtMs != null && run.firedAt == marker.firedAtMs,
                inOtherSession = run.sessionId != null && hostSessionId != null && run.sessionId != hostSessionId,
            )
        }
        val runningAt = marker.firedAtMs
        if (!fireRunning || runningAt == null || rows.any { it.firedAt == runningAt }) return rows
        val live = FireRow(
            index = fired + 1,
            firedAt = runningAt,
            sessionId = hostSessionId,
            state = FireState.RUNNING,
            preview = null,
            isCurrent = true,
            inOtherSession = false,
        )
        return listOf(live) + rows
    }

    // ── trigger text ────────────────────────────────────────────────────────

    /**
     * Localized phrases for [triggerText], filled from string resources by the
     * page and from literals by tests. `%1$s` is the time (or date + time for
     * [once]), `%2$s` the day list for [weekly].
     */
    data class TriggerPhrases(
        val once: String,
        val daily: String,
        val weekdays: String,
        val weekly: String,
        val daySeparator: String,
        /** Short names indexed by Calendar.DAY_OF_WEEK - 1 (Sunday first). */
        val dayNames: List<String>,
        val from: String,
        val until: String,
        val window: String,
        // [T-android-scheduled-triggers] `%1$s` is a duration ("10m", "2h").
        // Defaults keep literal-built phrases (tests) compiling.
        val interval: String = "Every %1${'$'}s",
        /** `%1$s` duration, `%2$d` the --count. */
        val intervalCount: String = "Every %1${'$'}s · %2${'$'}d times",
        /** `%1$s` the upstream task's label or id. */
        val onCompletion: String = "When \u201c%1${'$'}s\u201d finishes",
    )

    /** Monday-first display order of Calendar.DAY_OF_WEEK values. */
    private val WEEK_ORDER = listOf(
        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
        Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY,
    )

    /**
     * One-line human form of the task's trigger, e.g. "Every day 08:00",
     * "Every Mon, Wed 09:30 · until Oct 3". [formatTime] renders HH:mm,
     * [formatDate] renders a day (both locale-aware in the page).
     */
    fun triggerText(
        task: ScheduledTask,
        p: TriggerPhrases,
        formatTime: (hour: Int, minute: Int) -> String,
        /**
         * [T-android-scheduled-triggers] Name of an on-completion upstream.
         * Before [formatDate] so existing trailing-lambda callers still bind it.
         */
        upstreamName: (String) -> String? = { null },
        formatDate: (Long) -> String,
    ): String {
        // [T-android-scheduled-triggers] The relative triggers.
        when (task.triggerKind) {
            ScheduledTriggerKind.AFTER -> {
                // Shown as the moment it fires (or fired), in the same words as
                // a calendar one-shot: "Once at Oct 3 09:30".
                val at = (task.anchorMs ?: task.createdAt) + (task.delaySec ?: 0L) * 1000
                val cal = Calendar.getInstance().apply { timeInMillis = at }
                return p.once.format(
                    "${formatDate(at)} ${formatTime(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))}",
                )
            }
            ScheduledTriggerKind.INTERVAL -> {
                val every = formatDuration(task.intervalSec ?: 0L)
                return task.maxFires?.let { p.intervalCount.format(every, it) } ?: p.interval.format(every)
            }
            ScheduledTriggerKind.ON_COMPLETION -> {
                val of = task.onCompletionOf.orEmpty()
                return p.onCompletion.format(upstreamName(of) ?: of.take(8))
            }
            ScheduledTriggerKind.CALENDAR -> Unit
        }
        val time = formatTime(task.timeOfDayHour, task.timeOfDayMinute)
        val base = when (task.repeatMode) {
            ScheduledRepeatMode.ONCE -> {
                val at = task.startDateMs?.let { "${formatDate(it)} $time" } ?: time
                p.once.format(at)
            }
            ScheduledRepeatMode.DAILY -> p.daily.format(time)
            ScheduledRepeatMode.WEEKDAYS -> p.weekdays.format(time)
            ScheduledRepeatMode.CUSTOM -> {
                val days = WEEK_ORDER.filter { it in task.customDays }
                    .joinToString(p.daySeparator) { p.dayNames[it - 1] }
                p.weekly.format(days, time)
            }
        }
        if (task.repeatMode == ScheduledRepeatMode.ONCE) return base
        val start = task.startDateMs
        val end = task.endDateMs
        val window = when {
            start != null && end != null -> p.window.format(formatDate(start), formatDate(end))
            end != null -> p.until.format(formatDate(end))
            start != null -> p.from.format(formatDate(start))
            else -> null
        }
        return if (window == null) base else "$base · $window"
    }

    /**
     * [T-android-scheduled-triggers] A duration the way the CLI takes it:
     * "90s", "10m", "2h", "1h30m". Compact and language-neutral.
     */
    fun formatDuration(sec: Long): String {
        if (sec <= 0) return "0s"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return buildString {
            if (h > 0) append("${h}h")
            if (m > 0) append("${m}m")
            if (s > 0 || isEmpty()) append("${s}s")
        }
    }

    // ── chat card subtitle ──────────────────────────────────────────────────

    /** Localized pieces of a card's status line (iOS keys in comments). */
    data class CardPhrases(
        /** "Run %d" */
        val run: String,
        /** "%d left" */
        val left: String,
        /** "Next %@" — takes the relative time, e.g. "in 4 min." */
        val next: String,
        /** "Task complete" */
        val complete: String,
        /** Android-only local echo after cancelling from this card. */
        val cancelled: String,
    )

    /** Joins the parts of a card's status line, as iOS does. */
    const val CARD_SEPARATOR = " · "

    /**
     * [T-scheduled-card-subline] The second line of a fire's chat card, port of
     * iOS `AgentCallbackCellView.subline` for a scheduled callback:
     * "Run N" · "N left" · "Next <relative>" or "Task complete" · trigger, and
     * the prompt's first line when all of those are empty, so the card is
     * never just a title.
     *
     * Field report: once a task's next fire had passed or it was stopped, every
     * card of it showed its title over an empty line — the old rule said only
     * "next fire", and a passed next-fire time says nothing.
     *
     * The envelope is a snapshot taken when the fire started, so its next-fire
     * time goes stale: every card scrolled back to is past it. Only "this fire
     * had no next one" (no next time, or nothing left) means the task is
     * complete; a passed time says nothing about it (iOS cbf9c6961 fixed
     * "Run 1 · 4 left · Task complete" on a task still firing), and the detail
     * page shows the live state.
     *
     * [cancelled] is Android's local echo of a cancel made from this card; it
     * takes the next-fire slot. [live] is whether the task is still an enabled
     * timer: when it is not, a next fire still ahead is a promise that will not
     * be kept, so that slot says cancelled too (iOS keeps saying "Next" until
     * the time passes). A passed or absent next fire is unaffected, so a task
     * that ran out its count still reads "Task complete" on its last card.
     */
    fun cardSubline(
        marker: ScheduledTaskMarker,
        cancelled: Boolean,
        now: Long,
        p: CardPhrases,
        live: Boolean = true,
        relative: (atMs: Long) -> String,
    ): String {
        val parts = mutableListOf<String>()
        marker.fireIndex?.takeIf { it > 0 }?.let { parts += p.run.format(it) }
        marker.remaining?.let { parts += p.left.format(it) }
        val next = marker.nextFireAtMs?.takeIf { it > now }
        when {
            cancelled -> parts += p.cancelled
            next != null -> parts += if (live) p.next.format(relative(next)) else p.cancelled
            isLastFire(marker) -> parts += p.complete
        }
        marker.triggerLabel?.takeIf { it.isNotEmpty() }?.let { parts += it }
        if (parts.isEmpty()) {
            marker.prompt.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.let { parts += it }
        }
        return parts.joinToString(CARD_SEPARATOR)
    }

    enum class RelativeUnit { SECOND, MINUTE, HOUR, DAY, WEEK, MONTH, YEAR }

    /**
     * [T-scheduled-card-subline] The unit and value a relative "in …" label
     * uses for [seconds] ahead: the largest non-zero unit, truncated, as
     * Foundation's `RelativeDateTimeFormatter` picks it (90 s → 1 min, 59 min →
     * 59 min, 25 h → 1 day). Months and years are 30 / 365 days.
     */
    fun relativeUnit(seconds: Long): Pair<Long, RelativeUnit> {
        val s = seconds.coerceAtLeast(0)
        return when {
            s < 60 -> s to RelativeUnit.SECOND
            s < 3_600 -> s / 60 to RelativeUnit.MINUTE
            s < 86_400 -> s / 3_600 to RelativeUnit.HOUR
            s < 7 * 86_400 -> s / 86_400 to RelativeUnit.DAY
            s < 30 * 86_400 -> s / (7 * 86_400) to RelativeUnit.WEEK
            s < 365 * 86_400 -> s / (30 * 86_400) to RelativeUnit.MONTH
            else -> s / (365 * 86_400) to RelativeUnit.YEAR
        }
    }

    /**
     * [T-scheduled-card-subline] How long to wait before re-reading the clock
     * for a relative "in N min." label [waitMs] before the fire: to the next
     * whole-minute boundary, so the truncated minute count is never a minute
     * behind (iOS computes it at each render), and to the fire itself inside
     * the last minute, when the "Next" part goes away.
     */
    fun nextTickMs(waitMs: Long): Long =
        if (waitMs <= 60_000L) waitMs + 1 else (waitMs % 60_000L).let { if (it == 0L) 60_000L else it } + 1

    /** iOS `isLastFire`: the envelope promises no next fire, or nothing is left. */
    fun isLastFire(marker: ScheduledTaskMarker): Boolean =
        marker.nextFireAtMs == null || marker.remaining == 0

    // ── durations ───────────────────────────────────────────────────────────

    enum class DurationUnit { DAY, HOUR, MINUTE, SECOND }

    /**
     * A duration as its two largest units, e.g. 3725 s → [1 h, 2 min],
     * 90 s → [1 min, 30 s], 45 s → [45 s]. The second unit is dropped when it
     * is zero, so "2 h" rather than "2 h 0 min".
     */
    fun durationParts(totalSeconds: Long): List<Pair<DurationUnit, Int>> {
        val s = totalSeconds.coerceAtLeast(0)
        val units = listOf(
            DurationUnit.DAY to s / 86_400,
            DurationUnit.HOUR to (s % 86_400) / 3_600,
            DurationUnit.MINUTE to (s % 3_600) / 60,
            DurationUnit.SECOND to s % 60,
        )
        val first = units.indexOfFirst { it.second > 0 }
        if (first < 0) return listOf(DurationUnit.SECOND to 0)
        return units.subList(first, minOf(first + 2, units.size))
            .filterIndexed { i, (_, n) -> i == 0 || n > 0 }
            .map { (u, n) -> u to n.toInt() }
    }

    // ── delivery ────────────────────────────────────────────────────────────

    sealed class Delivery {
        object NewSession : Delivery()
        object ThisChat : Delivery()
        data class OtherChat(val sessionId: String) : Delivery()
        data class Rerun(val sessionId: String, val inThisChat: Boolean) : Delivery()
        object ChildOfThisChat : Delivery()
        data class ChildOfOtherChat(val sessionId: String) : Delivery()
    }

    fun delivery(mode: ScheduledTargetMode, hostSessionId: String?): Delivery = when (mode) {
        ScheduledTargetMode.NewSession -> Delivery.NewSession
        is ScheduledTargetMode.AppendToSession ->
            if (mode.sessionId == hostSessionId) Delivery.ThisChat else Delivery.OtherChat(mode.sessionId)
        is ScheduledTargetMode.RerunMessage -> Delivery.Rerun(mode.sessionId, mode.sessionId == hostSessionId)
        is ScheduledTargetMode.ChildOfCurrent ->
            if (mode.sessionId == hostSessionId) Delivery.ChildOfThisChat else Delivery.ChildOfOtherChat(mode.sessionId)
    }

    // ── prompt ──────────────────────────────────────────────────────────────

    /**
     * The clean prompt: the stored task's, else the envelope body (which
     * [ScheduledTaskMarker.parse] has already stripped of the prefill note
     * and the insertion reminder). Any leading "[…]" notes that still survive
     * — an envelope from another build — are removed as well.
     */
    fun cleanPrompt(task: ScheduledTask?, marker: ScheduledTaskMarker): String {
        val raw = task?.prompt?.takeIf { it.isNotBlank() } ?: marker.prompt
        var s = raw.trimStart()
        while (true) {
            s = when {
                s.startsWith("<system-reminder>") && s.contains("</system-reminder>") ->
                    s.substringAfter("</system-reminder>").trimStart()
                LEADING_NOTE.containsMatchIn(s) -> s.replaceFirst(LEADING_NOTE, "").trimStart()
                else -> return s.trimEnd()
            }
        }
    }

    /** "[Scheduled task …]" / "[This task's first step already ran …]". */
    private val LEADING_NOTE = Regex("""^\[(?:Scheduled task\b|This task's first step already ran\b)[^\]]*]""")
}

// ─── Merged from ScheduledTaskMarker.kt ──────────────────────────────────────────────────

/**
 * [T-android-scheduled-task-card] The envelope a scheduled task's prompt is
 * wrapped in so the transcript can tell it apart from something the user typed.
 *
 * A fired task sends its prompt through the ordinary prompt path, so without a
 * marker the resulting message is indistinguishable from a user message — the
 * user sees text they never wrote and has no way to learn which task produced
 * it, or that a task produced it at all.
 *
 * Modelled on `AgentCallback`: a leading tag the flattener recognises, parses,
 * and lifts into a card instead of a bubble. Kept deliberately small — it
 * carries only what the card must show and what the cancel action needs, and
 * the prompt itself follows verbatim so a build that does not know the tag
 * still shows something sensible rather than an empty bubble.
 *
 * Wire shape (attributes are single-line, the body is the untouched prompt):
 *
 *     <scheduled_task id="…" label="…" next="…">
 *     the prompt text
 *     </scheduled_task>
 *
 * [T-scheduled-tool-prefill] A task with a prefilled tool call adds
 * `prefilled="<tool>"` and opens the body with [prefillNote]. The model reads
 * the raw envelope, and the attribute alone did not say what to DO: the note
 * (verbatim the iOS wording, ScheduledPresetToolCall.envelopeNote) tells it
 * the tool call that follows was already made for it and that it should work
 * from that output, re-running only on failure — without it the model sees a
 * call it has no memory of deciding on, and may run it again. [parse] strips
 * the note back off, so the card shows the prompt alone. Readers that predate
 * the attribute ignore it.
 *
 * [T-scheduled-preemptive-insert] A fire delivered into a session whose agent
 * loop is still running is slipped in between two tool calls rather than held
 * until the whole run ends. Its envelope adds `inserted="1"` and opens with
 * [insertionReminder] instead of the prefill note: the model must learn that
 * this turn was dropped into the middle of its own work (not produced by its
 * last tool call, not started by it), what to do with a prefilled result, and
 * that it should go back to the interrupted task afterwards. [parse] strips it
 * like the note.
 *
 * [T-scheduled-card-subline] `fire` / `remaining` / `trigger` carry which run
 * this is, how many runs are left after it, and the trigger in iOS's
 * `AgentJobTrigger.logLabel` form, the same attributes the iOS scheduled
 * envelope has. The card builds its status line from them; without them a
 * card whose next-fire time had passed had nothing left to say.
 */
data class ScheduledTaskMarker(
    val taskId: String,
    val label: String,
    /** Epoch millis of the NEXT fire, or null when this was the last one. */
    val nextFireAtMs: Long?,
    /** The prompt the task actually sent. */
    val prompt: String,
    /** [T-scheduled-tool-prefill] Name of the tool the scheduler ran before
     *  the model was asked, or null for an ordinary task. */
    val prefilledTool: String? = null,
    /** [T-scheduled-preemptive-insert] True when this fire is being slipped
     *  into a running agent loop between tool calls. */
    val insertedMidTask: Boolean = false,
    /** [T-scheduled-task-detail] Epoch millis this fire started. The same value
     *  is stored as the fire's [ScheduledRun.firedAt], which is how the detail
     *  page finds "the fire whose card was tapped" in the task's history.
     *  Null on envelopes written before this existed. */
    val firedAtMs: Long? = null,
    /** [T-scheduled-card-subline] 1-based index of this fire; null for a
     *  one-shot, a manual run, or an envelope written before this existed. */
    val fireIndex: Int? = null,
    /** [T-scheduled-card-subline] Fires left after this one; null when the
     *  schedule is unbounded or not counted. */
    val remaining: Int? = null,
    /** [T-scheduled-card-subline] e.g. `loop(60s×5)`, `cron(9:00 days=[2, 3])`. */
    val triggerLabel: String? = null,
) {
    val xml: String
        get() {
            val attrs = buildString {
                append("id=\"").append(escapeAttr(taskId)).append('"')
                append(" label=\"").append(escapeAttr(label)).append('"')
                nextFireAtMs?.let { append(" next=\"").append(it).append('"') }
                firedAtMs?.let { append(" fired=\"").append(it).append('"') }
                fireIndex?.takeIf { it > 0 }?.let { append(" fire=\"").append(it).append('"') }
                remaining?.takeIf { it >= 0 }?.let { append(" remaining=\"").append(it).append('"') }
                triggerLabel?.takeIf { it.isNotEmpty() }?.let { append(" trigger=\"").append(escapeAttr(it)).append('"') }
                prefilledTool?.let { append(" prefilled=\"").append(escapeAttr(it)).append('"') }
                if (insertedMidTask) append(" inserted=\"1\"")
            }
            // The insertion reminder already says what the prefill note says,
            // so an inserted fire carries one or the other, never both.
            val note = when {
                insertedMidTask -> insertionReminder(label, prefilledTool) + "\n"
                prefilledTool != null -> prefillNote(prefilledTool) + "\n"
                else -> ""
            }
            return "<$TAG $attrs>\n$note$prompt\n</$TAG>"
        }

    companion object {
        const val TAG = "scheduled_task"

        /**
         * [T-scheduled-tool-prefill] The line a prefilled task's envelope opens
         * with. Kept word-for-word identical to iOS so both platforms tell the
         * model the same thing.
         */
        fun prefillNote(tool: String): String =
            "[This task's first step already ran for you: $tool — its call and output follow. " +
                "Work from that output; re-run only if it failed.]"

        /**
         * [T-scheduled-preemptive-insert] What an inserted fire tells the model,
         * in the short declarative style of a system reminder. It must carry
         * three facts and nothing more: this is an inserted scheduled fire (not
         * the last tool result, not the user), a prefilled command already ran
         * and should not be run again, and the prior task resumes afterwards.
         * [prefilledTool] only decides whether the prefill sentence is added.
         */
        fun insertionReminder(label: String, prefilledTool: String?): String {
            val name = label.replace("\n", " ").trim().ifEmpty { null }
                ?.let { "Scheduled task \"$it\"" } ?: "A scheduled task"
            val preset = if (prefilledTool != null) " Preset command already ran; read the result below, don't re-run it." else ""
            return "<system-reminder>$name fired and was inserted here — not your last tool result, not a user request." +
                // [T-bridge-to-reminder] "unfinished": what the dropped
                // assistant bridge used to add.
                "$preset Handle briefly, then resume your unfinished prior task.</system-reminder>"
        }

        fun isMarkerText(text: String): Boolean =
            text.startsWith("<$TAG ") || text.startsWith("<$TAG>")

        /**
         * Parse a marker, or null when [text] is not one.
         *
         * Never throws: this runs on every user message as the transcript is
         * flattened, including text a user could type by hand.
         */
        fun parse(text: String): ScheduledTaskMarker? {
            if (!isMarkerText(text)) return null
            val openEnd = text.indexOf('>').takeIf { it >= 0 } ?: return null
            // `<scheduled_task>` with no attributes leaves an EMPTY span, whose
            // start would otherwise land one past openEnd and throw.
            val attrStart = (TAG.length + 1).coerceAtMost(openEnd)
            val attrs = parseAttributes(text.substring(attrStart, openEnd))
            val id = attrs["id"]?.takeIf { it.isNotEmpty() } ?: return null
            val after = text.substring(openEnd + 1)
            val close = after.lastIndexOf("</$TAG>")
            val body = (if (close >= 0) after.substring(0, close) else after).trim()
            val prefilled = attrs["prefilled"]?.takeIf { it.isNotEmpty() }
            val inserted = attrs["inserted"] == "1"
            val label = attrs["label"].orEmpty()
            val note = when {
                inserted -> insertionReminder(label, prefilled)
                prefilled != null -> prefillNote(prefilled)
                else -> null
            }
            val prompt = when {
                note != null && body.startsWith(note) -> body.removePrefix(note).trimStart()
                else -> stripLegacyPreamble(body, inserted, prefilled != null)
            }
            return ScheduledTaskMarker(
                taskId = id,
                label = label,
                nextFireAtMs = attrs["next"]?.toLongOrNull(),
                prompt = prompt,
                prefilledTool = prefilled,
                insertedMidTask = inserted,
                firedAtMs = attrs["fired"]?.toLongOrNull(),
                fireIndex = attrs["fire"]?.toIntOrNull()?.takeIf { it > 0 },
                remaining = attrs["remaining"]?.toIntOrNull()?.takeIf { it >= 0 },
                triggerLabel = attrs["trigger"]?.takeIf { it.isNotEmpty() },
            )
        }

        /**
         * [T-scheduled-card-subline] The envelope for one fire of [task], as
         * the runner writes it. Mirrors iOS `ScheduledJobRunner.scheduledEnvelope`:
         * only a repeating schedule (iOS loop / cron) numbers its fires, and
         * `remaining` is what is left AFTER this fire.
         *
         * An interval fire is counted by the alarm receiver before the run
         * starts ([ScheduledTask.triggeredCount] already includes it), so its
         * index is that count and [ScheduledTask.remainingFires] is already the
         * after-this-fire value. A calendar fire is recorded only when its run
         * completes, so its index is one past the fires recorded so far. A
         * manual "Run now" is not a fire of the schedule and gets no index.
         */
        fun forFire(
            task: ScheduledTask,
            firedAtMs: Long,
            scheduledFire: Boolean,
            prefilledTool: String?,
            nextFireAtMs: Long? = task.nextTriggerMs(),
        ): ScheduledTaskMarker {
            val repeating = task.triggerKind == ScheduledTriggerKind.INTERVAL ||
                (task.isCalendar && task.repeatMode != ScheduledRepeatMode.ONCE)
            val fireIndex = when {
                !scheduledFire || !repeating -> null
                task.triggerKind == ScheduledTriggerKind.INTERVAL -> task.triggeredCount?.takeIf { it > 0 }
                else -> task.firesSoFar + 1
            }
            return ScheduledTaskMarker(
                taskId = task.id,
                label = task.label,
                nextFireAtMs = nextFireAtMs,
                prompt = task.prompt,
                prefilledTool = prefilledTool,
                firedAtMs = firedAtMs,
                fireIndex = fireIndex,
                remaining = if (scheduledFire) task.remainingFires else null,
                triggerLabel = triggerLabel(task),
            )
        }

        /**
         * [T-scheduled-card-subline] [task]'s trigger in iOS's
         * `AgentJobTrigger.logLabel` form, which the iOS card shows verbatim:
         * `once(+1800s)`, `loop(60s×5)` / `loop(60s×∞)`,
         * `cron(9:05 days=[1, 2, 3, 4, 5, 6, 7])` (Calendar weekday numbers,
         * Sunday = 1, the same on both platforms), `onCompletion(<8 chars>)`.
         * A calendar one-shot has no iOS counterpart; it is `once`.
         */
        fun triggerLabel(task: ScheduledTask): String = when (task.triggerKind) {
            ScheduledTriggerKind.AFTER -> "once(+${task.delaySec ?: 0}s)"
            ScheduledTriggerKind.INTERVAL -> "loop(${task.intervalSec ?: 0}s×${task.maxFires?.toString() ?: "∞"})"
            ScheduledTriggerKind.ON_COMPLETION -> "onCompletion(${task.onCompletionOf.orEmpty().take(8)})"
            ScheduledTriggerKind.CALENDAR -> {
                val days = when (task.repeatMode) {
                    ScheduledRepeatMode.ONCE -> null
                    ScheduledRepeatMode.DAILY -> (1..7).toList()
                    ScheduledRepeatMode.WEEKDAYS -> (2..6).toList()
                    ScheduledRepeatMode.CUSTOM -> task.customDays.sorted()
                }
                if (days == null) "once"
                else "cron(${task.timeOfDayHour}:${String.format(java.util.Locale.ROOT, "%02d", task.timeOfDayMinute)} days=${days.joinToString(", ", "[", "]")})"
            }
        }

        /**
         * [T-scheduled-card-subline] Envelopes written by earlier builds opened
         * with a differently worded note (59a58884f's long insertion reminder),
         * which the exact-match strip above misses. Their card fell back to
         * that reminder as its first line. Strip the leading
         * `<system-reminder>…</system-reminder>` of an inserted fire, or the
         * `[This task's first step already ran for you: …]` line of a prefilled
         * one, by shape rather than by wording.
         */
        private fun stripLegacyPreamble(body: String, inserted: Boolean, prefilled: Boolean): String {
            if (inserted && body.startsWith("<system-reminder>")) {
                val end = body.indexOf("</system-reminder>")
                if (end >= 0) return body.substring(end + "</system-reminder>".length).trimStart()
            }
            if (prefilled && body.startsWith(PREFILL_NOTE_HEAD)) {
                val nl = body.indexOf('\n')
                if (nl >= 0) return body.substring(nl + 1).trimStart()
            }
            return body
        }

        private const val PREFILL_NOTE_HEAD = "[This task's first step already ran for you:"

        /** The prompt without the envelope — what a plain reader should see. */
        fun stripMarker(text: String): String = parse(text)?.prompt ?: text

        private fun parseAttributes(s: String): Map<String, String> {
            val out = mutableMapOf<String, String>()
            var rest = s
            while (true) {
                val eq = rest.indexOf('=').takeIf { it >= 0 } ?: break
                val key = rest.substring(0, eq).trim()
                val afterEq = rest.substring(eq + 1)
                val q = afterEq.indexOf('"').takeIf { it >= 0 } ?: break
                val endQ = afterEq.indexOf('"', q + 1).takeIf { it >= 0 } ?: break
                if (key.isNotEmpty()) out[key] = unescapeAttr(afterEq.substring(q + 1, endQ))
                rest = afterEq.substring(endQ + 1)
            }
            return out
        }

        private fun escapeAttr(s: String): String = s
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("\n", " ")

        private fun unescapeAttr(s: String): String = s
            .replace("&quot;", "\"").replace("&gt;", ">")
            .replace("&lt;", "<").replace("&amp;", "&")
    }
}

// ─── Merged from ScheduledWait.kt ──────────────────────────────────────────────────

/**
 * [T-port-sched-wait-notification] What the ongoing notification shows while a
 * chat waits for its next scheduled run - the Android counterpart of the iOS
 * Live Activity's waiting card (fa7adaf9e, `liveActivityWaitCards`).
 */
data class ScheduledWait(
    /** The chat the task keeps active (its creator, see [ScheduledSessionHold]). */
    val sessionId: String,
    val taskId: String,
    val label: String,
    /** Wall-clock ms of the next run; null for an on-completion wait (no clock). */
    val nextRunMs: Long?,
    /** "2/30" (this run of a counted loop), "#7" (unbounded), null for a one-shot. */
    val runCounter: String?,
    /** The chat's title, filled in by the app from the session row. */
    val chatTitle: String? = null,
)

/**
 * [T-port-sched-running-counter] A scheduled fire whose run is in progress in
 * a chat it keeps active, with that run's counter - iOS's "Working… · 2/30"
 * on the waiting card while the fire runs (ScheduledBannerModel.runCounter
 * with running = true).
 */
data class ScheduledFiring(
    val sessionId: String,
    val taskId: String,
    /** "2/30" (this run of a counted loop) or "#7" (unbounded / repeating calendar). */
    val runCounter: String,
)

object ScheduledWaitModel {

    /**
     * Run number of the NEXT run of [task] (iOS `ScheduledBannerModel.runNumber`
     * with running = false): only a repeating schedule numbers its runs, and a
     * counted loop never shows more than its total.
     *
     * Counts match the chat card's ([ScheduledTaskMarker.forFire]): an
     * interval counts alarm fires since its last (re)start
     * ([ScheduledTask.triggeredCount], against [ScheduledTask.maxFires]); a
     * repeating calendar task counts recorded fires.
     */
    fun nextRunNumber(task: ScheduledTask): Int? = when {
        task.triggerKind == ScheduledTriggerKind.INTERVAL -> {
            val n = (task.triggeredCount ?: 0) + 1
            task.maxFires?.let { minOf(n, it) } ?: n
        }
        task.isCalendar && task.repeatMode != ScheduledRepeatMode.ONCE -> task.firesSoFar + 1
        else -> null
    }

    /**
     * [T-port-sched-running-counter] Number of the run IN PROGRESS (iOS
     * runNumber with running = true). An interval's alarm counts its fire
     * before the run starts, so the running fire is triggeredCount itself; a
     * calendar fire is recorded when its run completes, so the running one is
     * firesSoFar + 1. One-shots have no number.
     */
    fun runningRunNumber(task: ScheduledTask): Int? = when {
        task.triggerKind == ScheduledTriggerKind.INTERVAL -> {
            val n = maxOf(1, task.triggeredCount ?: 0)
            task.maxFires?.let { minOf(n, it) } ?: n
        }
        task.isCalendar && task.repeatMode != ScheduledRepeatMode.ONCE -> task.firesSoFar + 1
        else -> null
    }

    /** [T-port-sched-running-counter] The running run's counter, "2/30" / "#7", or null. */
    fun runningCounter(task: ScheduledTask): String? {
        val n = runningRunNumber(task) ?: return null
        val total = task.maxFires.takeIf { task.triggerKind == ScheduledTriggerKind.INTERVAL }
        return if (total != null) "$n/$total" else "#$n"
    }

    /**
     * [T-port-sched-running-counter] The fire being run right now in a chat
     * it keeps active (ScheduledSessionHold.runHold - the follow-up-to-creator
     * rule), with a counter; null when none. Several at once (rare): the
     * lowest task id, for a stable choice.
     */
    fun firing(tasks: List<ScheduledTask>, runningIds: Set<String>): ScheduledFiring? =
        tasks.asSequence()
            .filter { it.id in runningIds }
            .mapNotNull { t ->
                val sid = ScheduledSessionHold.runHold(t) ?: return@mapNotNull null
                val counter = runningCounter(t) ?: return@mapNotNull null
                ScheduledFiring(sid, t.id, counter)
            }
            .minByOrNull { it.taskId }

    /** [T-port-sched-running-counter] [firing], re-derived when tasks or runs change. */
    fun observeFiring(context: Context): Flow<ScheduledFiring?> =
        combine(
            ScheduledTaskStore(context.applicationContext).observe(),
            ScheduledSessionHold.runningTaskIds,
        ) { tasks, running -> firing(tasks, running.keys) }.distinctUntilChanged()

    /** iOS `ScheduledBannerModel.runCounter`: "2/30", "#7", or null. */
    fun runCounter(task: ScheduledTask): String? {
        val n = nextRunNumber(task) ?: return null
        val total = task.maxFires.takeIf { task.triggerKind == ScheduledTriggerKind.INTERVAL }
        return if (total != null) "$n/$total" else "#$n"
    }

    /**
     * The wait to show, among the tasks that keep a chat active (the same test
     * as [ScheduledSessionHold.heldSession]): the soonest next run; an
     * on-completion task only when nothing has a time (iOS
     * `ScheduledBannerModel.state`). Ties go to the lower task id so the choice
     * is stable. null when no task holds a chat.
     */
    fun pick(
        tasks: List<ScheduledTask>,
        now: Long,
        leadMs: Long = Long.MAX_VALUE,
        upstreamUnfinished: (String) -> Boolean,
    ): ScheduledWait? {
        // [FIX-261025-03] Same countdown window as the hold, so the waiting
        // card appears exactly when the chat becomes active again.
        val held = tasks.mapNotNull { t ->
            ScheduledSessionHold.heldSession(t, now, upstreamUnfinished, leadMs)?.let { sid -> t to sid }
        }
        if (held.isEmpty()) return null
        val timed = held.mapNotNull { (t, sid) -> t.nextTriggerMs(now)?.let { Triple(t, sid, it) } }
            .minWithOrNull(compareBy<Triple<ScheduledTask, String, Long>> { it.third }.thenBy { it.first.id })
        if (timed != null) {
            val (t, sid, next) = timed
            return ScheduledWait(sid, t.id, t.label, next, runCounter(t))
        }
        val (t, sid) = held.minWith(compareBy<Pair<ScheduledTask, String>> { it.first.label }.thenBy { it.first.id })
        return ScheduledWait(sid, t.id, t.label, null, runCounter(t))
    }

    /**
     * [pick], re-derived from the same inputs as [ScheduledSessionHold.observe]
     * (persisted tasks, runs in flight, agent jobs, the countdown setting), so
     * it changes exactly when a run starts or ends, a task is edited or enters
     * its countdown window - never per second.
     */
    fun observe(context: Context): Flow<ScheduledWait?> =
        ScheduledSessionHold.windowed(
            combine(
                ScheduledTaskStore(context.applicationContext).observe(),
                ScheduledSessionHold.runningTaskIds,
                AgentJobRegistry.jobs,
                BackgroundSettingsRepository(context.applicationContext).scheduledCountdownLead,
            ) { tasks, running, jobs, lead ->
                ScheduledSessionHold.Inputs(tasks, running.keys, jobs, BackgroundSettingsRepository.scheduledCountdownLeadMs(lead))
            },
            tasksOf = { it.tasks },
            leadOf = { it.leadMs },
        ) { i, now ->
            pick(i.tasks, now, i.leadMs) { ScheduledSessionHold.upstreamUnfinished(it, i.tasks, i.runningIds, i.jobs, now) }
        }.distinctUntilChanged()
}
