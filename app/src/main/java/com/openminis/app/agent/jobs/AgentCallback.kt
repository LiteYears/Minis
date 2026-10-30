package com.openminis.app.agent.jobs

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject

// [T-p2-agent-callback] Wire format for the messages an agent injects into
// its parent conversation: the completion report and mid-run progress
// reports. Byte-compatible with iOS AgentCallback.swift — both clients feed
// the same models, so the envelope must read identically. They travel as
// USER messages (every provider accepts those) wrapped in one fixed XML
// element so that the model can tell a system-injected callback from a
// human message, and the UI can render it as a callback card.
//
//   <agent_callback kind="finished" job="…" session="…" title="…"
//                   status="done" model="primary" elapsed="1m02s">
//   <summary>tools browser_use×2 · turns 4 · tokens in 12k / out 2k</summary>
//   <result>
//   …the agent's final answer…
//   </result>
//   </agent_callback>
//
// Progress reports use kind="progress", carry tool/activity/turn attributes
// and a <last_message> element instead of <result>.
data class AgentCallback(
    val kind: Kind,
    val jobId: String,
    val childSessionId: String?,
    val title: String,
    /** running · done · cancelled · timeout · failed · rejected */
    val status: String,
    val tier: String? = null,
    val elapsed: String? = null,
    val tool: String? = null,
    val activity: String? = null,
    val turn: Int? = null,
    /**
     * [T-sub-agents-v1] Which named sub agent produced this. Omitted for the
     * built-in — there is nothing to disambiguate.
     */
    val agentName: String? = null,
    val summary: String? = null,
    /**
     * [T-sub-agents-sibling-status] One line about the OTHER sub agents of this
     * conversation, on completion AND progress alike.
     *
     * The parent sees one result at a time and nothing else tells it whether
     * the rest of a batch is still coming, already lost, or never started — so
     * a model that fanned out three delegations and received one can quite
     * reasonably summarise as if it had them all.
     */
    val siblings: String? = null,
    val body: String,
) {
    enum class Kind(val wire: String) { PROGRESS("progress"), FINISHED("finished") }

    val xml: String
        get() {
            val attrs = mutableListOf("kind" to kind.wire, "job" to jobId)
            if (!childSessionId.isNullOrEmpty()) attrs += "session" to childSessionId
            attrs += "title" to title
            attrs += "status" to status
            if (!tier.isNullOrEmpty()) attrs += "model" to tier
            // [T-sub-agents-v1] Position matches iOS: model, agent, elapsed…
            if (!agentName.isNullOrEmpty()) attrs += "agent" to agentName
            if (!elapsed.isNullOrEmpty()) attrs += "elapsed" to elapsed
            if (!tool.isNullOrEmpty()) attrs += "tool" to tool
            if (!activity.isNullOrEmpty()) attrs += "activity" to activity
            if (turn != null && turn > 0) attrs += "turn" to turn.toString()
            val open = "<$TAG " + attrs.joinToString(" ") { "${it.first}=\"${escapeAttr(it.second)}\"" } + ">"
            val lines = mutableListOf(open)
            if (!summary.isNullOrEmpty()) lines += "<summary>${escapeText(summary)}</summary>"
            if (!siblings.isNullOrEmpty()) lines += "<other_sub_agents>${escapeText(siblings)}</other_sub_agents>"
            val bodyTag = if (kind == Kind.FINISHED) "result" else "last_message"
            lines += "<$bodyTag>"
            lines += body
            lines += "</$bodyTag>"
            lines += "</$TAG>"
            return lines.joinToString("\n")
        }

    /** Elapsed "1m02s" / "45s" → seconds. */
    val elapsedSeconds: Int?
        get() {
            val e = elapsed ?: return null
            val m = e.indexOf('m')
            return if (m >= 0) {
                val mins = e.substring(0, m).toIntOrNull() ?: 0
                val secs = e.substring(m + 1).removeSuffix("s").toIntOrNull() ?: 0
                mins * 60 + secs
            } else e.removeSuffix("s").toIntOrNull()
        }

    companion object {
        const val TAG = "agent_callback"

        /** Cheap prefix test used on every render / preview pass. */
        fun isCallbackText(text: String): Boolean =
            text.startsWith("<$TAG ") || text.startsWith("<$TAG>")

        fun parse(text: String): AgentCallback? {
            if (!isCallbackText(text)) return null
            val openEnd = text.indexOf('>').takeIf { it >= 0 } ?: return null
            // `<agent_callback>` with no attributes is accepted by
            // isCallbackText, and for it the attribute span is EMPTY — start
            // (TAG.length + 1) lands one past `openEnd`. Substring would throw
            // on a message a user could simply type, taking the renderer with
            // it, so the span is clamped instead.
            val attrStart = (TAG.length + 1).coerceAtMost(openEnd)
            val attrs = parseAttributes(text.substring(attrStart, openEnd))
            val kind = when (attrs["kind"]) {
                "progress" -> Kind.PROGRESS
                "finished" -> Kind.FINISHED
                else -> return null
            }
            val jobId = attrs["job"] ?: return null
            val after = text.substring(openEnd + 1)
            val close = after.lastIndexOf("</$TAG>")
            val inner = if (close >= 0) after.substring(0, close) else after
            val bodyTag = if (kind == Kind.FINISHED) "result" else "last_message"
            return AgentCallback(
                kind = kind,
                jobId = jobId,
                childSessionId = attrs["session"],
                title = attrs["title"] ?: "",
                status = attrs["status"] ?: (if (kind == Kind.FINISHED) "done" else "running"),
                tier = attrs["model"],
                elapsed = attrs["elapsed"],
                tool = attrs["tool"],
                activity = attrs["activity"],
                turn = attrs["turn"]?.toIntOrNull(),
                agentName = attrs["agent"],
                summary = element("summary", inner)?.let(::unescapeText),
                siblings = element("other_sub_agents", inner)?.let(::unescapeText),
                body = element(bodyTag, inner) ?: inner.trim(),
            )
        }

        private fun parseAttributes(s: String): Map<String, String> {
            val out = mutableMapOf<String, String>()
            var rest = s
            while (true) {
                val eq = rest.indexOf('=').takeIf { it >= 0 } ?: break
                val key = rest.substring(0, eq).trim()
                val afterEq = rest.substring(eq + 1)
                val q1 = afterEq.indexOf('"').takeIf { it >= 0 } ?: break
                val q2 = afterEq.indexOf('"', q1 + 1).takeIf { it >= 0 } ?: break
                out[key] = unescapeText(afterEq.substring(q1 + 1, q2))
                rest = afterEq.substring(q2 + 1)
            }
            return out
        }

        private fun element(name: String, s: String): String? {
            val open = s.indexOf("<$name>").takeIf { it >= 0 } ?: return null
            val close = s.lastIndexOf("</$name>").takeIf { it >= 0 } ?: return null
            val start = open + name.length + 2
            if (start > close) return null
            return s.substring(start, close).trim()
        }

        private fun escapeAttr(s: String): String =
            escapeText(s).replace("\"", "&quot;").replace("\n", " ")

        private fun escapeText(s: String): String =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun unescapeText(s: String): String =
            s.replace("&quot;", "\"").replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&")
    }
}

/**
 * Localized nouns for [AgentCallback] previews (session list, notifications).
 * Installed once by MinisApp from resources so the data layer never touches a
 * Context. Defaults are the English words.
 */
object AgentCallbackLabels {
    @Volatile var result: String = "Agent result"
    @Volatile var progress: String = "Agent progress"
    /** [T-preview-scheduled-tag-leak] Noun for a scheduled fire's preview line. */
    @Volatile var scheduled: String = "Scheduled task"
    @Volatile var status: Map<String, String> = emptyMap()

    fun install(context: android.content.Context) {
        val r = context.applicationContext.resources
        result = r.getString(com.openminis.app.R.string.agent_callback_result)
        progress = r.getString(com.openminis.app.R.string.agent_callback_progress)
        scheduled = r.getString(com.openminis.app.R.string.scheduled_card_label)
        status = mapOf(
            "running" to r.getString(com.openminis.app.R.string.helper_status_running),
            "done" to r.getString(com.openminis.app.R.string.helper_status_completed),
            "cancelled" to r.getString(com.openminis.app.R.string.helper_status_cancelled),
            "timeout" to r.getString(com.openminis.app.R.string.helper_status_timeout),
            "failed" to r.getString(com.openminis.app.R.string.helper_status_failed),
            "rejected" to r.getString(com.openminis.app.R.string.helper_status_rejected),
        )
    }

    fun localizedStatus(s: String): String = status[s] ?: s

    /** One-line stand-in for session previews, where the raw XML would be noise. */
    fun previewLine(cb: AgentCallback): String =
        "${if (cb.kind == AgentCallback.Kind.FINISHED) result else progress} · ${cb.title} · ${localizedStatus(cb.status)}"
}

// ─── Merged from AgentJobRegistry.kt ──────────────────────────────────────────────────

// [T-p1-delegate-task] Component B of the sub-agent / scheduled-task design
// (subagent_design_v4 §8.1–8.2), Android port of iOS `AgentJobRegistry`.
//
// The in-process registry for every job that runs a prompt in a session on
// behalf of something that is not the user's own tap: a `delegate_task`
// helper today; `minis-scheduled --target child-of-current` and background
// helpers in P2.
//
// Deliberately SEPARATE from `ScheduledTaskStore`. That store is persisted
// (AlarmManager tasks must survive a process kill); this one is not, on
// purpose — a helper's whole life is inside its parent turn, and the parent
// message's tool block is the durable record of what happened. Folding both
// into one table would either strand ephemeral helper rows on disk or drop
// the persistence the alarms need. The two share only the trigger/target
// vocabulary and the CLI contract (§8.3).
//
// No `kind` field (§8.2): purpose is expressed by `trigger` + `target`, and
// "is this a helper" is `target is ChildOfCurrent`.
//
// Intentionally different from iOS: there is no loop-end NotificationCenter
// observer. iOS needs one because its headless runs finish on a detached vm
// and the only signal is a notification that is (bug, noted in the P0
// review) only posted for non-active sessions. On Android the runner holds
// the child ViewModel and awaits its `isStreaming` flow directly, so job
// closure is explicit and does not depend on which session is on screen.
//
// Threading: all mutation happens on the main thread (callers are the agent
// loop's Main hops, Compose, and the RPC layer's Main dispatch). Reads via the
// StateFlow are safe from anywhere.

enum class AgentJobOrigin { TOOL, CLI, SHORTCUT, SCHEDULED }

sealed class AgentJobTrigger {
    object Immediate : AgentJobTrigger()
    data class Once(val afterMs: Long) : AgentJobTrigger()
    data class Loop(val intervalMs: Long, val count: Int?) : AgentJobTrigger()
    data class OnCompletion(val ofJobId: String) : AgentJobTrigger()

    val logLabel: String
        get() = when (this) {
            Immediate -> "immediate"
            is Once -> "once(+${afterMs / 1000}s)"
            is Loop -> "loop(${intervalMs / 1000}s×${count ?: "∞"})"
            is OnCompletion -> "onCompletion(${ofJobId.take(8)})"
        }
}

sealed class AgentJobTarget {
    object New : AgentJobTarget()
    data class FollowUp(val sessionId: String) : AgentJobTarget()
    data class Rerun(val sessionId: String, val messageId: String) : AgentJobTarget()
    /** A hidden child session of [parentSessionId]. [parentToolUseId] is the
     *  `delegate_task` block that spawned it, or null for a CLI child. */
    data class ChildOfCurrent(val parentSessionId: String, val parentToolUseId: String?) : AgentJobTarget()

    val isChildOfCurrent: Boolean get() = this is ChildOfCurrent
    val parentSessionIdOrNull: String? get() = (this as? ChildOfCurrent)?.parentSessionId

    val logLabel: String
        get() = when (this) {
            New -> "new"
            is FollowUp -> "followUp(${sessionId.take(8)})"
            is Rerun -> "rerun(${sessionId.take(8)}/${messageId.take(8)})"
            is ChildOfCurrent -> "childOfCurrent(${parentSessionId.take(8)} tool=${parentToolUseId?.take(8) ?: "-"})"
        }
}

sealed class AgentJobThen {
    /** The producer awaits the run in-process (wait-mode helper). */
    object None : AgentJobThen()
    /** P2: inject a result message into the parent via the submit funnel. */
    data class FollowUpParent(val template: String?) : AgentJobThen()
}

enum class AgentJobState {
    PENDING, RUNNING, DONE, CANCELLED, FAILED,
    /** [T-p2-agent-series] The wall-clock budget elapsed and the run was cut short. */
    TIMEOUT;

    /** iOS AgentJobState.rawValue — the wire word used in callbacks / agent_status. */
    val wire: String get() = name.lowercase()
}

/** One registered job. Immutable snapshot; the registry replaces entries. */
data class AgentJob(
    val id: String,
    val label: String?,
    val title: String,
    val origin: AgentJobOrigin,
    val trigger: AgentJobTrigger,
    val target: AgentJobTarget,
    val prompt: String?,
    val then: AgentJobThen,
    val state: AgentJobState = AgentJobState.PENDING,
    val firedCount: Int = 0,
    val remaining: Int? = (trigger as? AgentJobTrigger.Loop)?.count,
    /** The session the run executes in once known. */
    val runSessionId: String? = null,
    /** Which model tier actually ran ("primary"/"sub"). */
    val tierUsed: String? = null,
    /**
     * [T-sub-agents-v1] Where this run's model came from — a
     * [HelperModelOrigin] wire value. This is what the callback's `model`
     * attribute carries, matching iOS; [tierUsed] is the pre-rename legacy
     * field and no longer reaches the model.
     */
    val modelOrigin: String? = null,
    /** [T-sub-agents-v1] The named sub agent. */
    val agentName: String? = null,
    /**
     * [T-android-subagent-prompt-parity] True when this run is a RESUME of a
     * child the app lost, rather than a fresh delegation.
     *
     * Reported to the parent model in the final payload: a resumed run's tool
     * context was destroyed at the interruption, and its elapsed/turn counts
     * cover only the resumed part. Without saying so, the parent reads those
     * numbers as if they described the whole task and trusts state (an open
     * page, a live process) that no longer exists. Mirrors iOS
     * `AgentJob.wasResumed`.
     */
    val wasResumed: Boolean = false,
    /** [T-p2-progress-report] none | frequent | moderate (background mode only). */
    val progressLevel: String = "none",
    /** Last "Summary: tools … · turns … · tokens …" line the runner computed. */
    val summaryLine: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
    val resultText: String? = null,
) {
    val isActive: Boolean get() = state == AgentJobState.RUNNING || state == AgentJobState.PENDING
    val elapsedMs: Long?
        get() = startedAtMs?.let { (finishedAtMs ?: System.currentTimeMillis()) - it }
    val logLabel: String
        get() = "job ${id.take(8)} '$title' origin=$origin trigger=${trigger.logLabel} target=${target.logLabel} state=$state"
}

/** Posted ONCE per [AgentJobRegistry.finish]. */
data class AgentJobCompletion(
    val jobId: String,
    val state: AgentJobState,
    /** `.OnCompletion` jobs this finish armed (empty when none). */
    val dependentJobIds: List<String>,
)

object AgentJobRegistry {
    private const val TAG = "AgentJobRegistry"

    /**
     * [T-new-limits] Sub agent (child-session) jobs that may run at once PER
     * PARENT conversation (design §4.3; was 3 for all parents together).
     *
     * Counted per parent so one conversation's fan-out never starves
     * another's: each parent has its own 3, and a parent that is full queues
     * only its OWN further delegations. Scheduled child-of-current runs count
     * against their parent's 3 like any delegation.
     *
     * These jobs do NOT take one of SessionConcurrencyManager's slots
     * ([com.openminis.app.service.SessionConcurrencyManager.MAX_CONCURRENT]):
     * a child's loop skips acquireSlot (`helperConfig != null`). A parent
     * awaiting its helper holds its own slot, so routing helpers through the
     * same pool would deadlock every parent behind its own children (§4.4).
     * Worst case is therefore 6 parents × 3 children + 6 = 24 loops.
     */
    const val MAX_CONCURRENT_CHILD_JOBS_PER_PARENT = 3

    /**
     * [T-loadtest-limits] The per-parent cap admission uses:
     * [MAX_CONCURRENT_CHILD_JOBS_PER_PARENT] unless a DEV_TOOLS build
     * overrides it for a load test. In memory only.
     */
    @Volatile
    var maxConcurrentChildJobsPerParent: Int = MAX_CONCURRENT_CHILD_JOBS_PER_PARENT
        private set

    /**
     * [T-loadtest-limits] DEV_TOOLS only: set the PER-PARENT child cap (null =
     * default), 1..32; drains the queue. Name and RPC key (`childJobs`) kept for
     * the load-test scripts; since [T-new-limits] the number is per parent.
     */
    fun debugSetMaxConcurrentChildJobs(n: Int?): Int {
        if (!com.openminis.app.BuildConfig.DEV_TOOLS) return maxConcurrentChildJobsPerParent
        maxConcurrentChildJobsPerParent = (n ?: MAX_CONCURRENT_CHILD_JOBS_PER_PARENT).coerceIn(1, 32)
        AppLogger.info(TAG, "[T-loadtest-limits] maxConcurrentChildJobsPerParent=$maxConcurrentChildJobsPerParent")
        drainIfStalled()
        return maxConcurrentChildJobsPerParent
    }

    private val _jobs = MutableStateFlow<Map<String, AgentJob>>(emptyMap())
    val jobs: StateFlow<Map<String, AgentJob>> = _jobs.asStateFlow()

    private val _completions = MutableSharedFlow<AgentJobCompletion>(extraBufferCapacity = 16)
    val completions: SharedFlow<AgentJobCompletion> = _completions.asSharedFlow()

    /** Registered by the producer at [markRunning]: how to stop this job's run. */
    private val cancelHooks = HashMap<String, () -> Unit>()
    /** [T-p2-background-helper] Runs once when the job closes (any terminal
     *  state), BEFORE `then` is dispatched — the delegate_task runner uses it
     *  to write the final payload into the parent's tool block. */
    private val completionHooks = HashMap<String, (AgentJob) -> Unit>()

    /**
     * [T-p2-agent-series] How `then = FollowUpParent` reaches the parent
     * conversation: (parentSessionId, text, jobId) → submit as a PROGRAMMATIC
     * prompt (queued behind a running loop, never interrupting it). Installed
     * by MinisApp; the registry itself stays free of ViewModel imports.
     */
    @Volatile
    var followUpDispatcher: ((parentSessionId: String, text: String, jobId: String) -> Unit)? = null

    fun register(
        title: String,
        label: String? = null,
        origin: AgentJobOrigin,
        trigger: AgentJobTrigger,
        target: AgentJobTarget,
        prompt: String?,
        then: AgentJobThen? = null,
        /** [T-sub-agents-v1] The named sub agent; null for the built-in. */
        agentName: String? = null,
        /**
         * [T-sub-agents-resume] Claim a child session immediately.
         *
         * Normally set by markRunning, but a resume must own its child from
         * REGISTER time: anything that asks "is this child still lost?" in the
         * window before the run actually starts would otherwise start a second
         * one on the same session.
         */
        runSessionId: String? = null,
        /** [T-android-subagent-prompt-parity] See [AgentJob.wasResumed]. */
        wasResumed: Boolean = false,
    ): AgentJob {
        val resolvedThen = then ?: if (target.isChildOfCurrent) AgentJobThen.FollowUpParent(null) else AgentJobThen.None
        val job = AgentJob(
            id = UUID.randomUUID().toString(), label = label, title = title, origin = origin,
            trigger = trigger, target = target, prompt = prompt, then = resolvedThen,
            agentName = agentName, runSessionId = runSessionId, wasResumed = wasResumed,
        )
        _jobs.value = _jobs.value + (job.id to job)
        AppLogger.info(TAG, "REGISTER ${job.logLabel} then=$resolvedThen")
        return job
    }

    fun job(id: String): AgentJob? = _jobs.value[id]

    fun jobByLabel(label: String): AgentJob? = _jobs.value.values.firstOrNull { it.label == label && it.isActive }

    fun list(): List<AgentJob> = _jobs.value.values.sortedByDescending { it.createdAtMs }

    /** Active child-session jobs of [parentSessionId], oldest first — the capsule's data. */
    fun activeChildren(parentSessionId: String): List<AgentJob> =
        _jobs.value.values
            .filter { it.target.parentSessionIdOrNull == parentSessionId && it.isActive }
            .sortedBy { it.createdAtMs }

    fun jobForSession(runSessionId: String): AgentJob? =
        _jobs.value.values.firstOrNull { it.runSessionId == runSessionId }

    /**
     * [T-new-limits] Active child jobs of ONE parent. PENDING counts too: a job
     * is reserved from `register` on, so the window between registering and
     * `markRunning` (session creation, vm readiness) cannot be used to exceed
     * the cap.
     */
    fun runningChildJobCount(parentSessionId: String): Int =
        _jobs.value.values.count { it.target.parentSessionIdOrNull == parentSessionId && it.isActive }

    /** All active child jobs, every parent — diagnostics only, never admission. */
    val totalRunningChildJobCount: Int
        get() = _jobs.value.values.count { it.target.isChildOfCurrent && it.isActive }

    /** Active child jobs per parent — diagnostics (load-test snapshot). */
    fun runningChildJobCountsByParent(): Map<String, Int> =
        _jobs.value.values.filter { it.isActive }.mapNotNull { it.target.parentSessionIdOrNull }
            .groupingBy { it }.eachCount()

    /** [T-new-limits] Whether [parentSessionId] may start one more child job now. */
    fun canStartChildJob(parentSessionId: String): Boolean =
        runningChildJobCount(parentSessionId) < maxConcurrentChildJobsPerParent

    /**
     * [T-new-limits] Wait until [parentSessionId] has a free child slot, at most
     * [timeoutMs]. For the scheduled child-of-current path, which has no tool
     * call to answer "queued" to: it waits (defers the fire) instead of
     * silently exceeding the cap. True = a slot is free now.
     */
    suspend fun awaitChildSlot(parentSessionId: String, timeoutMs: Long): Boolean {
        if (canStartChildJob(parentSessionId)) return true
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            jobs.first { canStartChildJob(parentSessionId) }
            true
        } ?: false
    }

    // ── Delegation queue  [T-sub-agents-queue] ──────────────────────────────

    /**
     * A delegation that could not start yet, holding the RAW tool arguments.
     *
     * Storing the args rather than a half-built job is what lets the re-entry
     * re-resolve the definition, the model group and the limits at the moment
     * it actually starts — a roster edit while the task sat in the queue is
     * then honoured instead of baked in at enqueue time.
     */
    data class QueuedDelegation(
        val parentSessionId: String,
        val argsJson: String,
        val toolUseId: String,
        val queuedAtMs: Long = System.currentTimeMillis(),
    )

    /**
     * [T-new-limits] Backlog bound PER PARENT conversation (was 10 for all
     * parents together). Past this a delegation is refused, and says so
     * plainly ("it was NOT queued").
     */
    const val MAX_QUEUED_DELEGATIONS_PER_PARENT = 10

    private val queuedDelegations = mutableListOf<QueuedDelegation>()

    /**
     * [T-android-sidebar-subagent-running] Bumped whenever the queue changes.
     *
     * [queuedDelegations] is a plain synchronized list with no flow behind it,
     * so a delegation that is queued but not yet started would never reach a
     * Compose reader. This gives the derivation below something to observe;
     * the value is meaningless on its own.
     */
    private val queueRevision = MutableStateFlow(0)

    private val registryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * [T-android-sidebar-subagent-running] Parent sessions that have sub agent
     * work in flight, for the session list.
     *
     * A session whose own turn has ended but whose sub agents are still
     * working showed as idle in the sidebar, because that list only consults
     * the streaming tracker. The child's activity is recorded under the
     * CHILD's hidden session id, which no sidebar row carries — the row the
     * user is looking at goes dark while the work continues.
     *
     * Derived from the jobs StateFlow so the list recomposes on its own. Queued
     * delegations are deliberately included: from the user's side a delegation
     * waiting for a slot is still this conversation having work outstanding.
     */
    val parentsWithAgentWork: StateFlow<Set<String>> = MutableStateFlow(emptySet<String>()).also { flow ->
        // Recomputed synchronously on every job or queue change, rather than
        // derived with stateIn: a derived flow only publishes once its
        // collector runs, so `.value` lags its inputs — invisible in the UI,
        // but it makes the state untestable and the lag real on a slow frame.
        registryScope.launch {
            combine(_jobs, queueRevision) { jobs, _ -> jobs }.collect { flow.value = computeParentsWithAgentWork(it) }
        }
    }

    /** Pure, so the rule can be asserted directly. */
    private fun computeParentsWithAgentWork(jobs: Map<String, AgentJob>): Set<String> {
        val running = jobs.values.filter { it.isActive }.mapNotNull { it.target.parentSessionIdOrNull }
        val queued = synchronized(queuedDelegations) { queuedDelegations.map { it.parentSessionId } }
        return (running + queued).toSet()
    }

    /**
     * Synchronous read for a caller that must not wait for the flow to settle
     * (a unit test, or a one-shot check). The flow above is what Compose
     * observes; this answers the same question about the current instant.
     */
    fun hasAgentWork(parentSessionId: String): Boolean =
        parentSessionId in computeParentsWithAgentWork(_jobs.value)

    /**
     * Per-parent starters for queued delegations.
     *
     * Registered by each chat view model for its OWN session and removed when
     * that view model is cleared, so the registry can only ever start work into
     * a conversation that is still live. A single global starter would have had
     * to look a view model up by id and could resurrect one the store had
     * already released.
     */
    private val queuedStarters = java.util.concurrent.ConcurrentHashMap<String, (QueuedDelegation) -> Boolean>()

    fun registerQueuedStarter(parentSessionId: String, starter: (QueuedDelegation) -> Boolean) {
        if (parentSessionId.isEmpty()) return
        queuedStarters[parentSessionId] = starter
    }

    fun unregisterQueuedStarter(parentSessionId: String) {
        queuedStarters.remove(parentSessionId)
    }

    fun enqueueDelegation(item: QueuedDelegation): Boolean = synchronized(queuedDelegations) {
        val forParent = queuedDelegations.count { it.parentSessionId == item.parentSessionId }
        if (forParent >= MAX_QUEUED_DELEGATIONS_PER_PARENT) return false
        queuedDelegations.add(item)
        queueRevision.value++
        AppLogger.info(
            TAG,
            "QUEUE + tool=${item.toolUseId.take(12)} parent=${item.parentSessionId.take(8)} " +
                "depth=${forParent + 1} total=${queuedDelegations.size}",
        )
        true
    }

    fun queuedCount(parentSessionId: String): Int = synchronized(queuedDelegations) {
        queuedDelegations.count { it.parentSessionId == parentSessionId }
    }

    /** Whether this tool call is still waiting in the queue. */
    fun isQueued(toolUseId: String): Boolean = synchronized(queuedDelegations) {
        queuedDelegations.any { it.toolUseId == toolUseId }
    }

    /**
     * Drop a parent's whole backlog — the user stopped the turn, or the session
     * was deleted. Without this a queued task would start into a conversation
     * the user had already ended.
     */
    fun dropQueuedDelegations(parentSessionId: String, reason: String) {
        val removed = synchronized(queuedDelegations) {
            val hit = queuedDelegations.filter { it.parentSessionId == parentSessionId }
            queuedDelegations.removeAll(hit)
            queueRevision.value++
            hit.size
        }
        if (removed > 0) AppLogger.info(TAG, "QUEUE drop $removed for ${parentSessionId.take(8)} ($reason)")
    }

    /**
     * Start queued delegations whose parent has a free slot.
     *
     * [T-new-limits] The earliest entry of a parent WITH room is taken, not the
     * head of the queue: a parent that is full must not block the entries of
     * other parents behind it (head-of-line blocking).
     *
     * At most ONE per parent per call, on purpose: a fresh delegation only
     * occupies its slot once it registers (asynchronously, on its parent's
     * view model), so draining a parent's whole backlog here would blow past
     * its cap. The next `finish` of that parent's job drains its next one.
     */
    private fun drainQueuedDelegations() {
        val startedFor = HashSet<String>()
        while (true) {
            val next = synchronized(queuedDelegations) {
                val i = queuedDelegations.indexOfFirst {
                    it.parentSessionId !in startedFor && canStartChildJob(it.parentSessionId)
                }
                if (i < 0) null else queuedDelegations.removeAt(i).also { queueRevision.value++ }
            } ?: return
            val starter = queuedStarters[next.parentSessionId]
            if (starter == null) {
                // The parent conversation is not live (closed, or the process
                // was restarted). Nothing can start it, and the block is
                // surfaced as never-started on the next load rather than
                // sitting at "Queued" forever.
                AppLogger.info(
                    TAG,
                    "QUEUE parent ${next.parentSessionId.take(8)} not live — dropping ${next.toolUseId.take(12)}",
                )
                continue
            }
            val started = runCatching { starter(next) }.getOrElse { e ->
                AppLogger.warning(TAG, "QUEUE start failed for ${next.toolUseId.take(12)}: ${e.message}")
                false
            }
            AppLogger.info(TAG, "QUEUE - tool=${next.toolUseId.take(12)} parent=${next.parentSessionId.take(8)} started=$started")
            // A parent that no longer exists takes its queued work with it;
            // move on to the next rather than stalling the whole backlog.
            if (started) startedFor.add(next.parentSessionId)
        }
    }

    /**
     * [T-sub-agents-sibling-status] One line about the OTHER sub agents of a
     * conversation, for the callback envelope. Null when this job is alone.
     *
     * [interruptedCount] is supplied by the caller because it cannot be counted
     * here: the registry is in memory, so a process kill leaves no job behind —
     * and that is exactly the case that most needs reporting, since nothing
     * else will ever mention those runs again. It comes from the parent's
     * TRANSCRIPT instead (a block still holding a "running" payload with no
     * live job behind it).
     */
    fun siblingSummary(
        parentSessionId: String,
        excludingJobId: String,
        interruptedCount: Int,
    ): String? {
        val siblings = _jobs.value.values.filter {
            it.id != excludingJobId &&
                it.target.parentSessionIdOrNull == parentSessionId
        }
        val running = siblings.count { it.state == AgentJobState.RUNNING }
        val pending = siblings.count { it.state == AgentJobState.PENDING }
        val queued = queuedCount(parentSessionId)
        val parts = mutableListOf<String>()
        if (running + pending > 0) parts.add("${running + pending} still running")
        if (queued > 0) parts.add("$queued queued")
        if (interruptedCount > 0) {
            parts.add(
                "$interruptedCount interrupted (the app was restarted; " +
                    "resume with action=resume, or leave them)",
            )
        }
        if (parts.isEmpty()) return null
        return "Other sub agents in this conversation: " + parts.joinToString(", ") + "."
    }

    /**
     * Drain when a slot may have freed WITHOUT passing through [finish].
     *
     * A job killed off-path, or a watcher that threw, frees a slot with nothing
     * left to notice it — and the queue then starves silently and permanently,
     * leaving cards that read "Queued" forever. Cheap enough to call often: it
     * returns immediately unless work is waiting AND a slot is free.
     */
    fun drainIfStalled() {
        val waiting = synchronized(queuedDelegations) {
            queuedDelegations.any { canStartChildJob(it.parentSessionId) }
        }
        if (!waiting) return
        AppLogger.info(TAG, "QUEUE stalled with a free slot — draining")
        drainQueuedDelegations()
    }

    private fun update(jobId: String, f: (AgentJob) -> AgentJob): AgentJob? {
        val cur = _jobs.value[jobId] ?: return null
        val next = f(cur)
        _jobs.value = _jobs.value + (jobId to next)
        return next
    }

    fun setTierUsed(jobId: String, tier: String) { update(jobId) { it.copy(tierUsed = tier) } }

    /** [T-sub-agents-v1] Record where this run's model came from. */
    fun setModelOrigin(jobId: String, origin: String) { update(jobId) { it.copy(modelOrigin = origin) } }
    fun setProgressLevel(jobId: String, level: String) { update(jobId) { it.copy(progressLevel = level) } }
    fun setSummaryLine(jobId: String, line: String) { update(jobId) { it.copy(summaryLine = line) } }
    fun setCompletionHook(jobId: String, hook: (AgentJob) -> Unit) { completionHooks[jobId] = hook }
    /** A wait-mode helper converts to background when the user follows up. */
    fun setThen(jobId: String, then: AgentJobThen) { update(jobId) { it.copy(then = then) } }

    /** The producer has started the run in [sessionId]. [onCancel] stops it. */
    fun markRunning(jobId: String, sessionId: String?, onCancel: (() -> Unit)? = null) {
        val job = update(jobId) {
            it.copy(
                state = AgentJobState.RUNNING,
                startedAtMs = it.startedAtMs ?: System.currentTimeMillis(),
                firedCount = it.firedCount + 1,
                runSessionId = sessionId ?: it.runSessionId,
                remaining = it.remaining?.let { r -> maxOf(0, r - 1) },
            )
        } ?: return
        if (onCancel != null) cancelHooks[jobId] = onCancel
        AppLogger.info(TAG, "RUNNING ${job.logLabel} session=${sessionId?.take(8) ?: "nil"} fired=${job.firedCount}")
    }

    // ── Steering  [T-sub-agents-steer] ──────────────────────────────────────

    /**
     * Deliver a course correction to a running child. Returns false when the
     * child cannot take one (finished, or never started).
     */
    private val steerHooks = java.util.concurrent.ConcurrentHashMap<String, (String) -> Boolean>()

    /** Steers a job never got to read, reported on its result. */
    private val missedSteers = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()

    fun registerSteerHook(jobId: String, hook: (String) -> Boolean) { steerHooks[jobId] = hook }

    /**
     * Supplies whatever steers the child never got to read, asked once when the
     * job closes. Registered alongside the steer hook.
     */
    private val missedSteerDrains = java.util.concurrent.ConcurrentHashMap<String, () -> List<String>>()

    fun registerMissedSteerDrain(jobId: String, drain: () -> List<String>) { missedSteerDrains[jobId] = drain }

    /**
     * [T-android-browser-tab-ownership] Hand back the browser tabs an agent
     * owned when its job ends.
     *
     * Registered per job and fired from [finish], so it covers EVERY terminal
     * state — done, cancelled, timeout, failed — rather than only the happy
     * path. A leaked claim would keep a tab out of the chat's and its siblings'
     * reach for the life of the process, which is the same starvation the
     * ownership rules exist to prevent.
     */
    private val tabReleases = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    fun registerTabRelease(jobId: String, release: () -> Unit) { tabReleases[jobId] = release }

    fun steer(jobId: String, message: String): Boolean {
        val hook = steerHooks[jobId] ?: return false
        return runCatching { hook(message) }.getOrDefault(false)
    }

    /** Record steers the child never read, so the result can say so. */
    fun recordMissedSteers(jobId: String, messages: List<String>) {
        if (messages.isEmpty()) return
        missedSteers.getOrPut(jobId) { mutableListOf() }.addAll(messages)
    }

    fun missedSteersFor(jobId: String): List<String> = missedSteers[jobId].orEmpty().toList()

    /** Close a job. Idempotent; emits exactly one [AgentJobCompletion]. */
    fun finish(jobId: String, state: AgentJobState, result: String?) {
        val cur = _jobs.value[jobId] ?: return
        if (!cur.isActive) return
        val job = update(jobId) {
            it.copy(state = state, finishedAtMs = System.currentTimeMillis(), resultText = result)
        } ?: return
        cancelHooks.remove(jobId)
        steerHooks.remove(jobId)
        // [T-android-browser-tab-ownership] Release before the completion hook
        // so the slot is already back when the parent's next turn runs.
        tabReleases.remove(jobId)?.let { runCatching { it() } }
        // [T-sub-agents-steer] A correction that arrived after the child's last
        // turn is reported rather than silently dropped — the parent believes
        // it landed and would otherwise assume it was acted on.
        missedSteerDrains.remove(jobId)?.let { drain ->
            recordMissedSteers(jobId, runCatching { drain() }.getOrDefault(emptyList()))
        }
        AppLogger.info(TAG, "FINISH ${job.logLabel} elapsed=${(job.elapsedMs ?: 0) / 1000}s result=${result?.length ?: 0}ch")
        completionHooks.remove(jobId)?.let { hook -> runCatching { hook(_jobs.value[jobId] ?: job) } }
        // Every terminal state reports back — a parent that delegated in the
        // background must learn about a cancel / timeout / failure just as it
        // learns about success (the envelope carries `status`).
        runThen(_jobs.value[jobId] ?: job)
        val dependents = _jobs.value.values.filter { dep ->
            dep.state == AgentJobState.PENDING &&
                (dep.trigger as? AgentJobTrigger.OnCompletion)?.ofJobId == jobId
        }.map { it.id }
        if (dependents.isNotEmpty()) {
            AppLogger.info(TAG, "onCompletion dependents ${dependents.map { it.take(8) }} armed by ${jobId.take(8)} — producer runs them")
        }
        _completions.tryEmit(AgentJobCompletion(jobId, state, dependents))
        // [T-sub-agents-queue] A slot just freed.
        drainQueuedDelegations()
    }

    /** Cancel one job: runs its cancel hook (stops the child loop) then closes it. */
    fun cancel(jobId: String, reason: String) {
        val cur = _jobs.value[jobId] ?: return
        if (!cur.isActive) return
        AppLogger.info(TAG, "CANCEL ${cur.logLabel} reason=$reason")
        // [T-agent-port-round2] Close the job as CANCELLED BEFORE stopping the
        // child: the stop hook flips the child's isStreaming, which wakes the
        // runner's budget watcher on another dispatcher, and its finish(DONE)
        // raced this finish(CANCELLED) — first writer wins, so a user stop was
        // reported as "done" (device run 15:13). finish() is idempotent; with
        // the terminal state written first the watcher's write is a no-op.
        val hook = cancelHooks.remove(jobId)
        finish(jobId, AgentJobState.CANCELLED, null)
        hook?.let { runCatching { it() } }
    }

    fun cancelByLabel(label: String, reason: String) {
        _jobs.value.values.filter { it.label == label }.forEach { cancel(it.id, reason) }
    }

    /** Cascade: the parent session stopped or was deleted. */
    fun cancelAll(parentSessionId: String, reason: String) {
        activeChildren(parentSessionId).forEach { cancel(it.id, reason) }
    }

    // ── [T-android-stop-sibling-subagent] Stopping the batch ────────────

    /**
     * Stop the whole fan-out a child belongs to, not just that one child.
     *
     * A user who presses Stop on one sub agent card means "stop this work".
     * Cancelling the single job left its same-batch siblings running, so the
     * run kept spending tokens after the user believed it had stopped — the
     * card said stopped and the others simply were not shown.
     *
     * Order matters. [dropQueuedDelegations] runs FIRST because [finish]
     * calls [drainQueuedDelegations] on every terminal state: cancelling the
     * running jobs while a queue still holds entries frees a slot and starts
     * one of the very delegations being stopped. Draining the queue before
     * any cancel removes that window.
     */
    fun cancelSiblings(ofChildSessionId: String, reason: String) {
        val parent = jobForSession(ofChildSessionId)?.target?.parentSessionIdOrNull
        if (parent == null) {
            // No batch to reason about (a scheduled child-of-current run has
            // no delegating parent). Fall back to the single job so Stop still
            // stops what the user pressed it on.
            jobForSession(ofChildSessionId)?.let { if (it.isActive) cancel(it.id, reason) }
            return
        }
        muteDelegationResults(parent)
        dropQueuedDelegations(parent, reason)
        activeChildren(parent).forEach { cancel(it.id, reason) }
    }

    /**
     * Parent sessions whose delegation results must not drive the parent's
     * turn any further.
     *
     * A sibling that had ALREADY FINISHED before the user stopped the batch
     * still owns a pending callback, and delivering it restarts the parent's
     * turn — the conversation begins moving again seconds after the user
     * stopped it, which reads as the Stop button not working. The mute
     * suppresses only that parent-waking hop; the job's own result is still
     * recorded, so the transcript stays complete and auditable.
     */
    private val mutedParents = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun muteDelegationResults(parentSessionId: String) {
        mutedParents.add(parentSessionId)
        AppLogger.info(TAG, "delegation results muted for parent ${parentSessionId.take(8)}")
        // [stop-diag] Who muted, and what the set holds afterwards.
        AppLogger.info(TAG, "[stop-diag] mute ADD sid=$parentSessionId set=${mutedSnapshot()} caller=${stopDiagCaller()}")
    }

    /** Cleared when the user starts new work in that session. */
    fun clearDelegationMute(parentSessionId: String) {
        val removed = mutedParents.remove(parentSessionId)
        if (removed) {
            AppLogger.info(TAG, "delegation mute cleared for parent ${parentSessionId.take(8)}")
        }
        // [stop-diag] Logged whether or not it was set, so a missing clear on
        // a new-work entry point shows up as the absence of this line.
        AppLogger.info(TAG, "[stop-diag] mute REMOVE sid=$parentSessionId removed=$removed set=${mutedSnapshot()} caller=${stopDiagCaller()}")
    }

    fun isDelegationMuted(parentSessionId: String): Boolean {
        val muted = mutedParents.contains(parentSessionId)
        // [stop-diag] Every hit, with the caller (the loop's post-tool check,
        // runThen's callback gate, ...).
        if (muted) {
            AppLogger.info(TAG, "[stop-diag] mute HIT sid=$parentSessionId set=${mutedSnapshot()} caller=${stopDiagCaller()}")
        }
        return muted
    }

    /** [stop-diag] Current muted parent ids, for diagnostics. */
    fun mutedSnapshot(): List<String> = synchronized(mutedParents) { mutedParents.toList() }

    /** [stop-diag] Active sub agent jobs of [parentSessionId]. */
    fun activeChildCount(parentSessionId: String): Int = activeChildren(parentSessionId).size

    /** [stop-diag] The first frames above the registry, "Class.method:line" each. */
    internal fun stopDiagCaller(frames: Int = 6): String =
        Thread.currentThread().stackTrace
            .asSequence()
            .drop(1)
            .filterNot {
                it.className.startsWith("java.lang.Thread") ||
                    (it.className.endsWith("AgentJobRegistry") && it.methodName in STOP_DIAG_SELF)
            }
            .take(frames)
            .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

    private val STOP_DIAG_SELF = setOf(
        "stopDiagCaller", "stopDiagCaller\$default", "muteDelegationResults",
        "clearDelegationMute", "isDelegationMuted",
    )

    /** Drop terminal jobs so the map does not grow without bound. */
    fun pruneFinished(olderThanMs: Long = 3_600_000L) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        _jobs.value = _jobs.value.filterValues { it.isActive || (it.finishedAtMs ?: it.createdAtMs) >= cutoff }
    }

    /** Test hook. */
    internal fun resetForTest() {
        _jobs.value = emptyMap(); cancelHooks.clear(); completionHooks.clear()
        AppLogger.info(TAG, "[stop-diag] mute CLEAR-ALL (test reset) set=${mutedSnapshot()}")
        followUpDispatcher = null; mutedParents.clear()
    }

    // ── then ────────────────────────────────────────────────────────────

    private fun runThen(job: AgentJob) {
        val then = job.then as? AgentJobThen.FollowUpParent ?: return
        val parent = job.target.parentSessionIdOrNull ?: return
        // [T-android-stop-sibling-subagent] The user stopped this batch. The
        // job still finishes and still records its result; what it must not do
        // is hand that result to the parent, which would start a new turn in a
        // conversation the user just stopped.
        // [T-subagent-termination] Delivery outcomes are recorded on the child's
        // termination record (no-op for a job that has none, e.g. a scheduled
        // child-of-current run).
        val terminations = SubAgentTerminationStore.shared
        if (isDelegationMuted(parent)) {
            AppLogger.info(TAG, "then.FollowUpParent ${job.id.take(8)} suppressed — parent ${parent.take(8)} stopped")
            terminations.setDeliveryForJob(job.id, SubAgentTermination.Delivery.FAILED, "withheld: the user stopped the parent's agents")
            return
        }
        // [T-agent-wrapup-turn] Never hand the parent an empty result: say
        // what happened so it re-delegates instead of digging.
        // [T-subagent-termination] A delegate run's termination record (if it
        // has one) names WHY there is no answer and what the last tool did;
        // other jobs keep the generic note.
        val termination = terminations.terminalForJob(job.id)
        val result = if (termination != null) {
            SubAgentTerminationRules.helperResultText(job.resultText.orEmpty(), termination)
        } else {
            job.resultText?.takeIf { it.isNotBlank() } ?: HelperRunner.emptyResultNote(job.state.wire)
        }
        val text = then.template?.takeIf { it.isNotEmpty() }?.replace("{{result}}", result)
            ?: completionCallback(job, result).xml
        val dispatcher = followUpDispatcher
        if (dispatcher == null) {
            AppLogger.warning(TAG, "then.FollowUpParent ${job.id.take(8)} dropped — no dispatcher installed")
            terminations.setDeliveryForJob(job.id, SubAgentTermination.Delivery.FAILED, "no delivery path installed")
            return
        }
        AppLogger.info(TAG, "then.FollowUpParent ${job.id.take(8)} → parent ${parent.take(8)} state=${job.state.wire}")
        runCatching { dispatcher(parent, text, job.id) }
            .onFailure {
                AppLogger.warning(TAG, "then.FollowUpParent ${job.id.take(8)} failed: ${it.message}")
                terminations.setDeliveryForJob(job.id, SubAgentTermination.Delivery.FAILED, it.message ?: "delivery threw")
            }
    }

    /** The finished-envelope for [job] — iOS AgentJobRegistry.completionCallback. */
    /**
     * [T-sub-agents-sibling-status] Counts a parent's interrupted sub agents
     * from its TRANSCRIPT.
     *
     * Installed by the app because the registry has no database access — and it
     * must not be answered from the registry anyway: a process kill leaves no
     * job behind, which is precisely the state that needs reporting.
     */
    private val interruptedCounters = java.util.concurrent.ConcurrentHashMap<String, () -> Int>()

    fun registerInterruptedCounter(parentSessionId: String, counter: () -> Int) {
        if (parentSessionId.isEmpty()) return
        interruptedCounters[parentSessionId] = counter
    }

    fun unregisterInterruptedCounter(parentSessionId: String) {
        interruptedCounters.remove(parentSessionId)
    }

    private fun siblingsFor(job: AgentJob): String? {
        val parent = job.target.parentSessionIdOrNull ?: return null
        // With no live view model there is no transcript to count and no
        // conversation to report into, so zero is the honest answer.
        val interrupted = runCatching { interruptedCounters[parent]?.invoke() ?: 0 }.getOrDefault(0)
        return siblingSummary(parent, job.id, interrupted)
    }

    fun completionCallback(job: AgentJob, result: String): AgentCallback = AgentCallback(
        kind = AgentCallback.Kind.FINISHED,
        jobId = job.id,
        childSessionId = job.runSessionId,
        title = job.title,
        status = job.state.wire,
        // [T-sub-agents-v1] The origin, not the legacy tier: on iOS this
        // attribute answers "where did this model come from", and the two
        // platforms must not give the same attribute different meanings.
        tier = job.modelOrigin ?: job.tierUsed,
        elapsed = elapsedClock(job.elapsedMs),
        agentName = job.agentName,
        summary = job.summaryLine?.removePrefix("Summary: "),
        siblings = siblingsFor(job),
        body = result,
    )

    /** "1m02s" / "45s". */
    fun elapsedClock(ms: Long?): String? {
        val s = (ms ?: return null) / 1000
        return if (s >= 60) "${s / 60}m${"%02d".format(s % 60)}s" else "${s}s"
    }
}

// ─── Merged from ModelTierResolver.kt ──────────────────────────────────────────────────

// [T-p1-delegate-task] Which model tier a helper runs on (design v4 §5).
// Default `primary`; the delegating model opts IN to `sub` only for simple,
// bounded, self-verifiable work. No `auto`: a rule keyed on "is a Sub group
// configured" would downgrade complex work purely because a cheaper group
// exists, which has nothing to do with task fitness.
/**
 * [T-sub-agents-v1] Which model an Auto sub agent runs on.
 *
 * Only consulted when the definition pins no Model Group — a pinned one
 * outranks the model's choice entirely, because the user set it deliberately.
 *
 * Port of iOS `SubAgentModelChoice` (Agent/Jobs/ModelTierResolver.swift).
 */
enum class SubAgentModelChoice(val wire: String) {
    /** Continue with the same model this conversation runs on. */
    SAME_AS_PARENT("same_as_me"),

    /** The user's default group — typically their strongest model. */
    DEFAULT_MODEL("default_model"),

    /** The user's light group — typically smaller, faster, cheaper. */
    SUB_MODEL("sub_model"),
    ;

    companion object {
        /**
         * Unknown / absent values fall back to the parent's model: the safe
         * direction, since it is what the conversation is already using and
         * what the user actually chose. A fan-out that guessed
         * `default_model` would silently spend far more than the model the
         * user picked.
         */
        fun parse(raw: String?): SubAgentModelChoice {
            val v = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == v } ?: SAME_AS_PARENT
        }
    }
}

enum class HelperModelTier(val wire: String) {
    PRIMARY("primary"), SUB("sub");

    companion object {
        fun parse(raw: String?): HelperModelTier = when (raw?.trim()?.lowercase()) {
            "sub" -> SUB
            else -> PRIMARY
        }
    }
}

/**
 * [T-sub-agents-v1] Where a sub agent's model came from. Reported to the parent
 * model as `model_origin`, so a fan-out that behaves inconsistently can be
 * explained without guessing.
 *
 * Port of iOS `HelperModelOrigin`; the wire values must match.
 */
enum class HelperModelOrigin(val wire: String) {
    /** The definition pinned a Model Group — the user's choice, outranks all. */
    PINNED("pinned"),

    /** The parent conversation's own model, passed through. */
    INHERITED("inherited"),

    /** The user's default group, because the model asked for `default_model`. */
    DEFAULT_GROUP("default_group"),

    /** The user's light group, because the model asked for `sub_model`. */
    SUB_GROUP("sub_group"),
}

/** The binding a helper session should be created with, plus what was actually used. */
data class HelperModelResolution(
    /** `sessions.model_binding` JSON, or null to leave the row pinned to [seedModelId]. */
    val bindingJson: String?,
    /** `sessions.model_id` seed. */
    val seedModelId: String,
    val modelLabel: String,
    val tierUsed: HelperModelTier,
    /** [T-sub-agents-v1] How this binding was chosen. */
    val origin: HelperModelOrigin = HelperModelOrigin.INHERITED,
    /**
     * [T-sub-agents-v1] True when the definition pinned a group that could not
     * be routed, so this fell back to inheriting.
     *
     * Surfaced in the result JSON as `model_group_unavailable`: silently
     * running on a different model than the user pinned is exactly the kind of
     * thing that reads as a bug months later.
     */
    val modelGroupUnavailable: Boolean = false,
    /**
     * [T-android-subagent-model-strategy] The Model Group's own name, when the
     * binding came from one. The card names the group the user configured
     * rather than a group id, which means nothing to them.
     */
    val modelGroupName: String? = null,
    /**
     * [T-android-agent-model-identity] The RESOLVED binding's identity, as
     * three separate facts iOS keeps separate too (HelperModelIdentity.swift):
     * which configured entry the resolver picked, on which provider instance,
     * and which model id that entry names.
     *
     * [modelLabel] alone is a display string; it cannot answer "which of my two
     * Anthropic instances ran this" or survive a rename. These are what the
     * detail sheet's Model group / Model tier rows read, and what a payload
     * reloaded after a restart needs in order to still say where the run went.
     *
     * All nullable: a binding inherited from the parent conversation names no
     * entry of its own, and an old payload carries none of them.
     */
    val resolvedEntryId: String? = null,
    val resolvedProviderLabel: String? = null,
    val resolvedProviderType: String? = null,
    val resolvedModelId: String? = null,
    val resolvedModelName: String? = null,
)

object ModelTierResolver {
    private const val TAG = "ModelTierResolver"

    /**
     * - PRIMARY: copy the parent's `model_binding` verbatim when it has one (a
     *   group binding keeps group fallback; an entry pin stays a pin) and seed
     *   with the parent's `model_id`. With no binding, pin the parent's active
     *   entry.
     * - SUB: `defaultSubGroupId` → first credentialed member → group binding.
     *   Not configured / no usable member → degrade to PRIMARY, and say so in
     *   [HelperModelResolution.tierUsed] (the parent model reads `tier_used`).
     *
     * Android note: iOS routes through `ModelGroupRouter.resolve` for a
     * load-balanced pick; Android's group routing lives inside ChatViewModel's
     * boot-from-binding path, which the child vm runs itself once the binding
     * is on its row. So this only has to choose the binding, not the member —
     * the same split `ScheduledAgentRunner` already uses.
     */
    fun resolve(
        tier: HelperModelTier,
        repo: ProviderRepository,
        parentBindingJson: String?,
        parentModelId: String?,
        parentActiveEntryId: String?,
    ): HelperModelResolution? {
        fun primary(): HelperModelResolution? {
            val entries = repo.config.value.modelEntries
            if (parentBindingJson != null && parentModelId != null) {
                val label = entries.firstOrNull { it.model.id == parentModelId }?.model?.displayName ?: parentModelId
                return HelperModelResolution(parentBindingJson, parentModelId, label, HelperModelTier.PRIMARY)
            }
            val entry: ModelEntry? = parentActiveEntryId?.let { id -> entries.firstOrNull { it.id == id } }
                ?: parentModelId?.let { mid -> entries.firstOrNull { it.model.id == mid } }
            if (entry == null) {
                AppLogger.warning(TAG, "resolve(primary): parent has no resolvable model")
                return null
            }
            val binding = JSONObject().put("type", "entry").put("entryId", entry.id).toString()
            return HelperModelResolution(binding, entry.model.id, entry.model.displayName, HelperModelTier.PRIMARY)
        }

        return when (tier) {
            HelperModelTier.PRIMARY -> primary()
            HelperModelTier.SUB -> {
                val gid = repo.defaultSubGroupId
                val group = gid?.let { repo.group(it) }
                if (group == null) {
                    AppLogger.info(TAG, "resolve(sub): no Sub group configured — degrading to primary")
                    return primary()
                }
                val member = repo.availableMemberEntries(group).firstOrNull()
                if (member == null) {
                    AppLogger.info(TAG, "resolve(sub): Sub group '${group.name}' has no usable member — degrading to primary")
                    return primary()
                }
                val binding = JSONObject().put("type", "group").put("groupId", gid).toString()
                HelperModelResolution(
                    binding, member.model.id, member.model.displayName, HelperModelTier.SUB,
                    origin = HelperModelOrigin.SUB_GROUP,
                    modelGroupName = group.name,
                    resolvedEntryId = member.id,
                    resolvedProviderLabel = repo.instance(member.providerInstanceId)?.label,
                    resolvedProviderType = repo.instance(member.providerInstanceId)?.providerType?.name?.lowercase(),
                    resolvedModelId = member.model.id,
                    resolvedModelName = member.model.displayName,
                )
            }
        }
    }

    /**
     * [T-sub-agents-v1] Resolve the model a NAMED sub agent runs on.
     *
     * The order is the whole point, and it is not symmetric:
     *
     * 1. A definition that pins a Model Group wins outright — `model_choice` is
     *    ignored for it. The user set that pin deliberately, and letting a model
     *    argument override it would make the setting advisory.
     * 2. Otherwise ("Auto") the model's `model_choice` decides, defaulting to
     *    the parent's own model. That default is load-bearing: the user chose
     *    the model this conversation runs on, and a sub agent quietly running on
     *    a stronger, costlier one — several at a time in a fan-out — spends
     *    their quota on a choice they never saw.
     * 3. Every branch degrades to the parent's model rather than failing the
     *    delegation. A sub agent that runs on the wrong model still does the
     *    work; one that refuses to start does not.
     *
     * A pinned group that no longer routes is the one case worth reporting, so
     * it comes back with [HelperModelResolution.modelGroupUnavailable] set.
     */
    fun resolveForSubAgent(
        pinnedGroupId: String?,
        choice: SubAgentModelChoice,
        repo: ProviderRepository,
        parentBindingJson: String?,
        parentModelId: String?,
        parentActiveEntryId: String?,
    ): HelperModelResolution? {
        fun inherited() = resolve(
            HelperModelTier.PRIMARY, repo, parentBindingJson, parentModelId, parentActiveEntryId,
        )

        // 1. A pin outranks the model's choice.
        if (pinnedGroupId != null) {
            val group = repo.group(pinnedGroupId)
            val member = group?.let { repo.availableMemberEntries(it).firstOrNull() }
            if (group != null && member != null) {
                val binding = JSONObject().put("type", "group").put("groupId", pinnedGroupId).toString()
                return HelperModelResolution(
                    binding, member.model.id, member.model.displayName, HelperModelTier.PRIMARY,
                    origin = HelperModelOrigin.PINNED,
                    modelGroupName = group.name,
                    resolvedEntryId = member.id,
                    resolvedProviderLabel = repo.instance(member.providerInstanceId)?.label,
                    resolvedProviderType = repo.instance(member.providerInstanceId)?.providerType?.name?.lowercase(),
                    resolvedModelId = member.model.id,
                    resolvedModelName = member.model.displayName,
                )
            }
            AppLogger.warning(
                TAG,
                "resolveForSubAgent: pinned group '$pinnedGroupId' " +
                    (if (group == null) "no longer exists" else "has no usable member") +
                    " — inheriting the parent's model instead",
            )
            return inherited()?.copy(modelGroupUnavailable = true)
        }

        // 2. Auto: the model chooses, and "same as me" is the default.
        return when (choice) {
            SubAgentModelChoice.SAME_AS_PARENT -> inherited()
            SubAgentModelChoice.DEFAULT_MODEL -> {
                val gid = repo.defaultPrimaryGroupId
                val group = gid?.let { repo.group(it) }
                val member = group?.let { repo.availableMemberEntries(it).firstOrNull() }
                if (gid == null || member == null) {
                    AppLogger.info(TAG, "resolveForSubAgent(default_model): unroutable — inheriting")
                    inherited()
                } else {
                    val binding = JSONObject().put("type", "group").put("groupId", gid).toString()
                    HelperModelResolution(
                        binding, member.model.id, member.model.displayName, HelperModelTier.PRIMARY,
                        origin = HelperModelOrigin.DEFAULT_GROUP,
                        modelGroupName = group.name,
                        resolvedEntryId = member.id,
                        resolvedProviderLabel = repo.instance(member.providerInstanceId)?.label,
                        resolvedProviderType = repo.instance(member.providerInstanceId)?.providerType?.name?.lowercase(),
                        resolvedModelId = member.model.id,
                        resolvedModelName = member.model.displayName,
                    )
                }
            }
            SubAgentModelChoice.SUB_MODEL ->
                resolve(HelperModelTier.SUB, repo, parentBindingJson, parentModelId, parentActiveEntryId)
        }
    }
}
