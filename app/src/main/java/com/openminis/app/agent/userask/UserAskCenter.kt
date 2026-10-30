package com.openminis.app.agent.userask

import android.content.Context
import com.openminis.app.data.model.AgentSchemaNode
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.AgentToolSwitch
import com.openminis.app.tools.ToolExecutionResult
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-port-user-ask] Runtime of the user_ask tool (iOS `UserAskCenter`, final
 * state): live asks, the wait the agent loop suspends on, the wall-clock
 * timeout, and the card's actions.
 *
 * The same pattern as ConfigConfirmationGate: a tool call suspends on a
 * continuation that the UI (or the timeout) resumes. Every resolution goes
 * through PendingAsk's resolve*, which accepts only the first terminal state,
 * so the card, the timeout, Stop and a new user message can race safely.
 *
 * Privacy: the user's answer text (Other / note) is never logged - only
 * statuses and lengths (iOS 4f7a72d76).
 *
 * [T-android-user-ask-notify] A question asked while the app is away is also
 * posted as a notification (UserAskNotifier) and can be answered there
 * ([answerFromNotification]) through the same first-wins resolution.
 *
 * [T-user-ask-deferred] A deferred ask (iOS 7a818b8c0) has no tool call
 * waiting on it: it is never in-process, Stop leaves it open, and its
 * resolution goes to [onDeferredResolved], which hands the answer on.
 */
object UserAskCenter {
    private const val TAG = "UserAsk"

    private val lock = Any()
    private val _asks = MutableStateFlow<Map<String, PendingAsk>>(emptyMap())

    /** Live asks by requestId (pending and recently resolved, for the card). */
    val asks: StateFlow<Map<String, PendingAsk>> = _asks.asStateFlow()

    private val waiters = HashMap<String, CancellableContinuation<String>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null

    /** Wall clock; replaceable in tests. */
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }

    /**
     * Called after every change to an ask (and once when one begins), so the
     * persistence layer can save it. Set by the app; null in tests.
     */
    @Volatile var onChanged: ((PendingAsk) -> Unit)? = null

    /**
     * Called when an ask resolves while no tool call waits on it (the app was
     * killed meanwhile and recovery re-registered it): hand the result over.
     */
    @Volatile var onResolvedWithoutWaiter: ((PendingAsk) -> Unit)? = null

    /**
     * [T-user-ask-deferred] Called when a DEFERRED ask resolves (answered,
     * dismissed, replaced, timed out): fill in its stored result and, for a
     * real answer, send it as the user's next message.
     */
    @Volatile var onDeferredResolved: ((PendingAsk) -> Unit)? = null

    /** Asks whose tool call runs in this process; recovery never delivers these. */
    private val inProcess = HashSet<String>()

    /**
     * [T-user-ask-deferred-live-batch] Deferred asks whose own tool batch is
     * still running in this process, so their `deferred` result is not stored
     * yet. One resolved meanwhile (answered from the notification while a
     * sibling tool runs, or replaced by a second user_ask in the same batch)
     * is held in [deferredHeld] until the loop stores the batch
     * ([markDelivered]) or the run ends ([releaseUnstoredDeferred]). Handed on
     * at once, the delivery found no stored result, took the "killed before
     * the batch was written" fallback and appended the assistant row and a
     * result row, and then the loop stored the same tool_use ids again.
     */
    private val deferredUnstored = HashSet<String>()
    private val deferredHeld = HashSet<String>()

    // ── Lookup ─────────────────────────────────────────────────────────────

    fun ask(forToolUseId: String?): PendingAsk? =
        forToolUseId?.let { id -> _asks.value.values.firstOrNull { it.toolCallId == id } }

    fun pendingAsks(sessionId: String): List<PendingAsk> =
        _asks.value.values.filter { it.sessionId == sessionId && it.state == UserAskState.PENDING }

    fun hasPendingAsk(sessionId: String?): Boolean = sessionId != null && pendingAsks(sessionId).isNotEmpty()

    /**
     * [T-port-user-ask-fk-crash] Mark [requestId] as owned by a tool call in
     * this process BEFORE its record is written, so a recovery scan running
     * concurrently can never mistake the live ask for one left by a kill.
     */
    fun claim(requestId: String) = synchronized(lock) { inProcess += requestId }

    /** Whether a tool call in this process owns this ask's delivery. */
    fun isInProcess(requestId: String): Boolean = synchronized(lock) { requestId in inProcess }

    // ── Start and wait ─────────────────────────────────────────────────────

    /**
     * Register a new ask: the first open question is shown with its
     * recommended option preselected. [ownedByToolCall] = a tool call in this
     * process will [wait] on it (false for an ask restored after a kill).
     */
    fun begin(ask: PendingAsk, ownedByToolCall: Boolean = true) {
        var a = ask
        val first = a.questions.indices.firstOrNull { it !in a.submitted }
        if (first != null) a = PendingAsk.preselectRecommended(a.copy(currentIndex = first), first)
        synchronized(lock) {
            // [T-user-ask-deferred] No tool call waits on a deferred ask.
            if (ownedByToolCall && !a.deferred) inProcess += a.requestId
            _asks.value = _asks.value + (a.requestId to a)
        }
        onChanged?.invoke(a)
        AppLogger.info(
            TAG,
            "[UserAsk] BEGIN req=${a.requestId.take(8)} tool=${a.toolCallId.take(16)} sid=${a.sessionId.take(8)} " +
                "questions=${a.questions.size} types=${a.questions.map { it.type.wire }} timeout=${a.timeoutMinutes}m " +
                "owned=$ownedByToolCall state=${a.state.wire}",
        )
        if (a.state == UserAskState.PENDING) startTicking()
    }

    /**
     * [T-user-ask-deferred] Start a deferred ask: only one stays open per
     * conversation, so an older open deferred card is closed as replaced.
     */
    fun beginDeferred(ask: PendingAsk) {
        val older = _asks.value.values.filter { it.sessionId == ask.sessionId && it.deferred && it.state == UserAskState.PENDING }
        for (o in older) dismiss(o.requestId, UserAskDismissReason.REPLACED_BY_NEW_ASK)
        synchronized(lock) { deferredUnstored += ask.requestId }
        begin(ask.copy(deferred = true), ownedByToolCall = false)
    }

    /**
     * [T-user-ask-deferred-live-batch] The run of [sessionId] ended: any of its
     * deferred asks whose batch was never stored (stopped, failed) is released,
     * and one that resolved meanwhile is handed on now - its delivery then
     * finds no stored result and writes one, which is right when nothing else
     * will.
     */
    fun releaseUnstoredDeferred(sessionId: String) {
        val held: List<String> = synchronized(lock) {
            val mine = deferredUnstored.filter { id -> _asks.value[id]?.sessionId?.let { it == sessionId } ?: true }
            deferredUnstored -= mine.toSet()
            mine.filter { deferredHeld.remove(it) }
        }
        for (id in held) _asks.value[id]?.let { onDeferredResolved?.invoke(it) }
    }

    /** Open deferred asks of a session (a new user message answers them). */
    fun pendingDeferred(sessionId: String): List<PendingAsk> = pendingAsks(sessionId).filter { it.deferred }

    /**
     * Suspend until the ask resolves; returns the tool_result JSON.
     * Cancellation (Stop, the run being torn down) resolves it as stopped.
     */
    suspend fun wait(requestId: String): String {
        _asks.value[requestId]?.let { if (it.state.isTerminal && it.resultJson != null) return it.resultJson }
        return suspendCancellableCoroutine { cont ->
            val done: String? = synchronized(lock) {
                val a = _asks.value[requestId]
                if (a != null && a.state.isTerminal && a.resultJson != null) a.resultJson
                else { waiters[requestId] = cont; null }
            }
            if (done != null) cont.resume(done)
            cont.invokeOnCancellation {
                synchronized(lock) { waiters.remove(requestId) }
                dismiss(requestId, UserAskDismissReason.STOPPED)
            }
        }
    }

    // ── Card actions ───────────────────────────────────────────────────────

    /** `userAction = false` for a programmatic draft that must not restart the countdown. */
    fun updateAnswer(requestId: String, index: Int, answer: UserAskAnswer, userAction: Boolean = true) {
        mutate(requestId) { a ->
            val next = a.setAnswer(answer, index)
            if (userAction) next.noteInteraction(clock()) else next
        }
    }

    fun noteInteraction(requestId: String) = mutate(requestId) { it.noteInteraction(clock()) }

    fun goTo(requestId: String, index: Int) = mutate(requestId) { it.goTo(index, clock()) ?: it }

    /** Next / Submit on one question: advance like a wizard, or resolve when all are in. */
    fun submitQuestion(requestId: String, index: Int) {
        var complete = false
        mutate(requestId) { a ->
            val (next, outcome) = a.submit(index, clock())
            when (outcome) {
                is UserAskSubmitOutcome.Advanced -> PendingAsk.preselectRecommended(next, outcome.to)
                UserAskSubmitOutcome.Complete -> { complete = true; next }
                UserAskSubmitOutcome.Rejected -> a
            }
        }
        if (complete) resolve(requestId, "ANSWERED") { it.resolveAnswered(UserAskAnswerSource.APP) }
    }

    /** A confirm button: records the choice (and a cancel reason) and submits that question. */
    fun confirm(requestId: String, index: Int, choice: UserAskConfirmChoice, note: String? = null) {
        val current = _asks.value[requestId]?.answers?.getOrNull(index) ?: return
        var a = current.copy(confirmChoice = choice)
        if (choice == UserAskConfirmChoice.DENY && note != null) a = a.copy(note = note)
        updateAnswer(requestId, index, a)
        submitQuestion(requestId, index)
    }

    // ── Notification ───────────────────────────────────────────────────────

    /** Record that a notification was posted for question [index] (once per question). */
    fun markNotified(requestId: String, index: Int) = mutate(requestId) { it.copy(notifiedIndex = index) }

    /**
     * [T-android-user-ask-notify] An answer given in the notification for
     * question [index]: an option button, the "Other…" text reply, or Allow /
     * Cancel. Checked and applied under the same lock as every card action, and
     * resolved through the same first-wins path, so it races safely with the
     * card, the timeout and Stop. [text] is never logged.
     */
    fun answerFromNotification(requestId: String, index: Int, actionId: String, text: String?): UserAskNotificationPlan.Outcome {
        var outcome = UserAskNotificationPlan.Outcome.NOT_FOUND
        val changed: PendingAsk? = synchronized(lock) {
            val cur = _asks.value[requestId] ?: return@synchronized null
            val (next, o) = UserAskNotificationPlan.apply(cur, index, actionId, text, clock())
            outcome = o
            if (next == cur) return@synchronized null
            _asks.value = _asks.value + (requestId to next)
            next
        }
        changed?.let { onChanged?.invoke(it) }
        AppLogger.info(
            TAG,
            "[UserAsk] NOTIFICATION action=${actionId.substringBefore(':')} q=${index + 1} req=${requestId.take(8)} " +
                "outcome=$outcome textLen=${text?.length ?: 0}",
        )
        if (outcome == UserAskNotificationPlan.Outcome.COMPLETE) {
            resolve(requestId, "ANSWERED") { it.resolveAnswered(UserAskAnswerSource.NOTIFICATION) }
        }
        return outcome
    }

    // ── Dismissal ──────────────────────────────────────────────────────────

    /** Stop, a new user message in the same session, or an explicit close. */
    fun dismiss(requestId: String, reason: UserAskDismissReason) {
        resolve(requestId, "DISMISSED reason=${reason.wire}") { it.resolveDismissed(reason) }
    }

    fun dismissPending(sessionId: String, reason: UserAskDismissReason) {
        for (a in pendingAsks(sessionId)) {
            // [T-user-ask-deferred] Stop ends the running wait; a deferred card
            // belongs to no running call and stays open. A new user message
            // still replaces it (the user answered in their own words).
            if (reason == UserAskDismissReason.STOPPED && a.deferred) continue
            dismiss(a.requestId, reason)
        }
    }

    // ── Timeout ────────────────────────────────────────────────────────────

    /** Wall clock: an ask that expired while the process slept resolves on the next tick. */
    internal fun tick(nowMs: Long = clock()) {
        for (a in _asks.value.values) {
            if (a.isExpired(nowMs)) resolve(a.requestId, "TIMED OUT - nothing applied") { it.resolveTimedOut() }
        }
    }

    private fun startTicking() {
        synchronized(lock) {
            if (tickJob?.isActive == true) return
            tickJob = scope.launch {
                while (isActive) {
                    delay(1_000)
                    tick()
                    if (_asks.value.values.none { it.state == UserAskState.PENDING }) break
                }
            }
        }
    }

    // ── Delivery bookkeeping ───────────────────────────────────────────────

    /** The agent loop stored the tool_result for these calls: they are done. */
    fun markDelivered(toolUseIds: Collection<String>) {
        for (id in toolUseIds) {
            val a = ask(forToolUseId = id) ?: continue
            // [T-user-ask-deferred] The loop stored the `deferred` result; the
            // ask stays until its ANSWER is handed on (markDeferredConsumed).
            if (a.deferred && !a.consumed) {
                // [T-user-ask-deferred-live-batch] Stored now: a resolution
                // held back for it goes on.
                val release = synchronized(lock) {
                    deferredUnstored -= a.requestId
                    deferredHeld.remove(a.requestId)
                }
                if (release) _asks.value[a.requestId]?.let { onDeferredResolved?.invoke(it) }
                continue
            }
            mutate(a.requestId) { it.copy(consumed = true) }
            synchronized(lock) { inProcess -= a.requestId }
            AppLogger.info(TAG, "[UserAsk] DELIVERED req=${a.requestId.take(8)} tool=${id.take(16)}")
        }
    }

    /**
     * [T-user-ask-deferred] The answer of a deferred ask is being handed on:
     * it is done, so it is neither delivered twice nor restored after a restart.
     * Returns false when it was already consumed (first caller wins).
     */
    fun markDeferredConsumed(requestId: String): Boolean {
        var won = false
        mutate(requestId) { a -> if (a.consumed) a else { won = true; a.copy(consumed = true) } }
        return won
    }

    /** Drop finished asks of a session from memory (their stored result is the record). */
    fun forget(requestId: String) {
        synchronized(lock) {
            _asks.value = _asks.value - requestId
            inProcess -= requestId
            deferredUnstored -= requestId
            deferredHeld -= requestId
            waiters.remove(requestId)
        }
    }

    // ── Plumbing ───────────────────────────────────────────────────────────

    private fun mutate(requestId: String, body: (PendingAsk) -> PendingAsk) {
        val changed: PendingAsk = synchronized(lock) {
            val cur = _asks.value[requestId] ?: return
            val next = body(cur)
            if (next == cur) return
            _asks.value = _asks.value + (requestId to next)
            next
        }
        onChanged?.invoke(changed)
    }

    /** Apply a terminal transition; first one wins. Resumes the waiting tool call. */
    private fun resolve(requestId: String, label: String, transition: (PendingAsk) -> PendingAsk?) {
        var resolved: PendingAsk? = null
        var waiter: CancellableContinuation<String>? = null
        synchronized(lock) {
            val cur = _asks.value[requestId] ?: return
            val next = transition(cur) ?: return
            _asks.value = _asks.value + (requestId to next)
            resolved = next
            waiter = waiters.remove(requestId)
        }
        val r = resolved ?: return
        // Statuses only: the result can carry the user's own words.
        AppLogger.info(
            TAG,
            "[UserAsk] $label req=${requestId.take(8)} source=${r.source?.wire ?: "-"} " +
                "statuses=${r.resultStatuses.map { it.wire }} resultLen=${r.resultJson?.length ?: 0}",
        )
        onChanged?.invoke(r)
        val w = waiter
        if (w != null) {
            if (w.isActive) w.resume(r.resultJson ?: UserAskResolver.encode(UserAskResolver.dismissed(r.questions)))
        } else if (r.deferred) {
            // [T-user-ask-deferred-live-batch] Its batch is not stored yet:
            // hand it on once it is.
            val held = synchronized(lock) {
                if (requestId in deferredUnstored) { deferredHeld += requestId; true } else false
            }
            if (held) {
                AppLogger.info(TAG, "[UserAsk] DEFERRED req=${requestId.take(8)} resolved before its batch was stored - held")
            } else {
                onDeferredResolved?.invoke(r)
            }
        } else if (!isInProcess(requestId)) {
            onResolvedWithoutWaiter?.invoke(r)
        }
    }

    /** Test hook: clear all state. */
    internal fun resetForTest() {
        synchronized(lock) {
            _asks.value = emptyMap()
            waiters.clear()
            inProcess.clear()
            deferredUnstored.clear()
            deferredHeld.clear()
            tickJob?.cancel()
            tickJob = null
        }
        onChanged = null
        onResolvedWithoutWaiter = null
        onDeferredResolved = null
        clock = { System.currentTimeMillis() }
    }
}

// ─── Merged from UserAskCore.kt ──────────────────────────────────────────────────

/**
 * [T-port-user-ask] Pure model of the `user_ask` tool, ported from the FINAL
 * state of iOS `Agent/Chat/UserAskCore.swift` (f21eaa98e and follow-ups, with
 * the reverted "Always allow" / default-on-timeout policy left out).
 *
 * Request parsing and validation, answers, result encoding, the ask state
 * machine and the model-facing text. No Android or app state - the runtime
 * (UserAskCenter) and the card build on it, and UserAskCoreTest covers it on
 * the JVM. The text-ask detector is a later port (P3) and not here.
 * [T-user-ask-deferred] Deferred mode (iOS 7a818b8c0) is: PendingAsk.deferred,
 * UserAskDeferred and UserAskExposure.decision.
 */

// ── Request ────────────────────────────────────────────────────────────────

enum class UserAskQuestionType(val wire: String) {
    OPTIONS("options"),
    CONFIRM("confirm");

    companion object {
        fun parse(raw: String?): UserAskQuestionType? = entries.firstOrNull { it.wire == raw }
    }
}

/** confirm only, and only how the card looks (orange / red). It changes no behaviour. */
enum class UserAskRisk(val wire: String) {
    MEDIUM("medium"),
    HIGH("high");

    companion object {
        fun parse(raw: String?): UserAskRisk? = entries.firstOrNull { it.wire == raw }
    }
}

data class UserAskOption(val label: String, val description: String? = null)

data class UserAskQuestion(
    val type: UserAskQuestionType,
    val question: String,
    val header: String? = null,
    /** null for an open-ended options question (text input only). */
    val options: List<UserAskOption>? = null,
    val multiSelect: Boolean = false,
    val impact: String? = null,
    /** confirm only; medium when the model omits it. Visual only. */
    val risk: UserAskRisk = UserAskRisk.MEDIUM,
    /** options only: the 0-based option the model would pick. Never applied. */
    val defaultIndex: Int? = null,
) {
    val isOpenEnded: Boolean get() = type == UserAskQuestionType.OPTIONS && options.isNullOrEmpty()

    /**
     * What a timeout returns as the unconfirmed suggestion: the label of the
     * model's `default`, else of the recommended (first) option. None for a
     * confirm or an open-ended question.
     */
    val timeoutSuggestion: String?
        get() {
            if (type != UserAskQuestionType.OPTIONS) return null
            val opts = options?.takeIf { it.isNotEmpty() } ?: return null
            defaultIndex?.let { i -> if (i in opts.indices) return opts[i].label }
            return opts[0].label
        }
}

data class UserAskRequest(val questions: List<UserAskQuestion>)

class UserAskParseError(message: String) : Exception(message)

object UserAskParser {
    const val MAX_QUESTIONS = 4
    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 4

    /** Cap on free text coming back from the user (Other / note). */
    const val MAX_INPUT_LENGTH = 1000

    private val RECOMMENDED_MARKERS = listOf("(Recommended)", "(recommended)", "（推荐）", "(推荐)", "Recommended:", "推荐：")

    /** "(Recommended)" style markers the model was told not to write; the UI adds its own. */
    fun stripRecommended(s: String): String {
        var out = s
        for (m in RECOMMENDED_MARKERS) out = out.replace(m, "")
        return out.trim()
    }

    /** Parse and validate the tool arguments. Errors are phrased for the model. */
    fun parse(args: JSONObject): Result<UserAskRequest> {
        // [T-port-nested-json-string] Some OpenAI-compatible stacks send the
        // array as JSON-in-a-string ("questions":"[{...}]"); decode it here as
        // well as in the dispatcher, because the card re-parses the raw stored
        // arguments after a reload.
        val raw = when (val v = args.opt("questions")) {
            is JSONArray -> v
            is String -> decodeArray(v, "questions") { return fail(it) }
            else -> null
        }
        if (raw == null || raw.length() == 0) {
            return fail("`questions` is required: call user_ask again with 1-4 question objects, each with `type` and `question`.")
        }
        if (raw.length() > MAX_QUESTIONS) {
            return fail("At most $MAX_QUESTIONS questions per call; you sent ${raw.length()}. Ask only what changes your next step.")
        }
        val questions = mutableListOf<UserAskQuestion>()
        for (i in 0 until raw.length()) {
            val q = when (val item = raw.opt(i)) {
                is JSONObject -> item
                is String -> runCatching { JSONObject(item.trim()) }.getOrNull()
                else -> null
            } ?: return fail("questions[$i] must be an object.")
            questions += parseQuestion(q, i).getOrElse { return Result.failure(it) }
        }
        return Result.success(UserAskRequest(questions))
    }

    /** The tool arguments as a JSON string (what the dispatcher and the card hold). */
    fun parse(argsJson: String?): Result<UserAskRequest> {
        val obj = runCatching { JSONObject(argsJson ?: "") }.getOrNull()
            ?: return fail("`questions` is required: call user_ask again with 1-4 question objects, each with `type` and `question`.")
        return parse(obj)
    }

    private fun fail(msg: String): Result<UserAskRequest> = Result.failure(UserAskParseError(msg))

    /**
     * [T-port-nested-json-string] A JSON array sent as a string. Blank = absent
     * (null); a string that is not a JSON array is reported through [onError]
     * with a message the model can act on.
     */
    private inline fun decodeArray(s: String, path: String, onError: (String) -> Nothing): JSONArray? {
        val t = s.trim()
        if (t.isEmpty()) return null
        return try {
            JSONArray(t)
        } catch (e: Exception) {
            onError(
                "`$path` was sent as a string that is not a valid JSON array (${e.message?.take(120)}). " +
                    "Send `$path` as a JSON array, not as a string.",
            )
        }
    }

    private fun str(v: Any?): String? = (v as? String)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * [T-android-user-ask-infer-type] The type a question's own fields imply
     * when `type` is missing or not a known value. Port of iOS
     * UserAskParser.inferredType (021a988c1).
     *
     * The schema still marks `type` required, but providers without strict
     * schemas do not enforce it: deepseek-flash once sent a complete
     * two-option question with no `type` (2026-10-07), the call was rejected,
     * the card read "not sent", and the model had to resend the same question.
     * Only unambiguous evidence counts: `options` / `default` / `multi_select`
     * exist only on an options question, `impact` / `risk` only on a confirm.
     * Null when there is none, or both: a bare question could be an open-ended
     * ask or an approval, and guessing wrong would turn an approval into a
     * text field.
     *
     * Android also accepts `options` as a JSON string (T-port-nested-json-
     * string); a string that decodes to a non-empty array is the same
     * evidence. A string that does not decode is no evidence - the parse
     * below reports it with its own message.
     */
    internal fun inferredType(q: JSONObject): UserAskQuestionType? {
        val hasOptions = when (val v = q.opt("options")) {
            is JSONArray -> v.length() > 0
            is String -> runCatching { JSONArray(v.trim()).length() > 0 }.getOrDefault(false)
            else -> false
        }
        val d = q.opt("default")
        val optionsOnly = hasOptions || (d != null && d != JSONObject.NULL) || q.opt("multi_select") is Boolean
        val confirmOnly = str(q.opt("impact")) != null || str(q.opt("risk")) != null
        return when {
            optionsOnly && !confirmOnly -> UserAskQuestionType.OPTIONS
            confirmOnly && !optionsOnly -> UserAskQuestionType.CONFIRM
            else -> null
        }
    }

    private fun parseQuestion(q: JSONObject, i: Int): Result<UserAskQuestion> {
        fun bad(msg: String) = Result.failure<UserAskQuestion>(UserAskParseError(msg))
        // An explicit, valid `type` always wins; inference only fills a
        // missing / unknown one, and everything below validates as before.
        val type = UserAskQuestionType.parse(str(q.opt("type"))) ?: inferredType(q)
            ?: return bad(
                "questions[$i].type must be \"options\" or \"confirm\": options for a decision " +
                    "(or an open-ended question), confirm for approval of one action.",
            )
        val text = str(q.opt("question")) ?: return bad("questions[$i].question is required.")
        var options: List<UserAskOption>? = null
        val rawOptions = when (val v = q.opt("options")) {
            is JSONArray -> v
            is String -> decodeArray(v, "questions[$i].options") { return bad(it) }
            else -> null
        }
        if (rawOptions != null && rawOptions.length() > 0) {
            if (type != UserAskQuestionType.OPTIONS) {
                return bad("questions[$i] is a confirm: it takes no `options` (the card shows Cancel / Allow). Put the exact action in `question` and the consequence in `impact`.")
            }
            if (rawOptions.length() < MIN_OPTIONS) {
                return bad("questions[$i].options needs at least $MIN_OPTIONS genuinely different choices. With only one sensible choice, do not ask: take it and state the assumption. For an open question, omit `options`.")
            }
            if (rawOptions.length() > MAX_OPTIONS) {
                return bad("questions[$i].options allows at most $MAX_OPTIONS choices (the UI adds Other and You decide).")
            }
            val parsed = mutableListOf<UserAskOption>()
            for (j in 0 until rawOptions.length()) {
                val o = rawOptions.opt(j)
                val dict = o as? JSONObject
                    ?: (o as? String)?.trim()?.takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
                val label = str(dict?.opt("label")) ?: str(o)
                    ?: return bad("questions[$i].options[$j].label is required.")
                parsed += UserAskOption(stripRecommended(label), str(dict?.opt("description")))
            }
            options = parsed
        }
        var risk = UserAskRisk.MEDIUM
        if (type == UserAskQuestionType.CONFIRM) {
            str(q.opt("risk"))?.let { r ->
                risk = UserAskRisk.parse(r) ?: return bad("questions[$i].risk must be \"medium\" or \"high\".")
            }
        }
        // `default` is an options-only suggestion, returned on timeout and
        // never applied. Anything sent for a confirm is ignored.
        var def: Int? = null
        val d = q.opt("default")
        if (type == UserAskQuestionType.OPTIONS && d != null && d != JSONObject.NULL) {
            val idx: Int? = when (d) {
                is Number -> d.toInt()
                else -> str(d)?.toIntOrNull()
            }
            val opts = options
            if (idx == null || opts == null || idx !in opts.indices) {
                return bad("questions[$i].default must be the 0-based index of one of its options (or omit it).")
            }
            def = idx
        }
        val multi = q.opt("multi_select") as? Boolean ?: false
        return Result.success(
            UserAskQuestion(
                type = type,
                question = text,
                header = str(q.opt("header")),
                options = options,
                multiSelect = type == UserAskQuestionType.OPTIONS && options != null && multi,
                impact = if (type == UserAskQuestionType.CONFIRM) str(q.opt("impact")) else null,
                risk = risk,
                defaultIndex = def,
            ),
        )
    }
}

// ── Answers ───────────────────────────────────────────────────────────────

enum class UserAskConfirmChoice(val wire: String) {
    APPROVE("approve"),
    DENY("deny");

    companion object {
        /** A persisted "approveAlways" (removed with "Always allow") reads as approve. */
        fun parse(raw: String?): UserAskConfirmChoice? = when (raw) {
            "approve", "approveAlways" -> APPROVE
            "deny" -> DENY
            else -> null
        }
    }
}

/** What the user has put into one question so far (also the persisted draft). */
data class UserAskAnswer(
    val selected: List<Int> = emptyList(),
    val otherText: String? = null,
    val note: String? = null,
    val delegated: Boolean = false,
    val confirmChoice: UserAskConfirmChoice? = null,
) {
    /**
     * [T-port-user-ask-fix2] Typed text for Other. Last action wins: in a
     * single-choice question a typed answer replaces a picked option (the
     * preselected recommendation included), from the first keystroke - so it
     * holds however the input closes (Done, back, tapping outside). In a
     * multi-select question the text adds to the ticked options.
     */
    fun withOtherText(text: String?, q: UserAskQuestion): UserAskAnswer {
        val typed = !text.isNullOrBlank()
        return when {
            !typed -> copy(otherText = text)
            q.multiSelect -> copy(otherText = text, delegated = false)
            else -> copy(otherText = text, selected = emptyList(), delegated = false)
        }
    }

    /**
     * [T-port-user-ask-fix2] A tap on option [i]. Single choice: that option
     * only, and it replaces typed Other text. Multi-select: toggles it, typed
     * text kept.
     */
    fun withOption(i: Int, q: UserAskQuestion): UserAskAnswer =
        if (q.multiSelect) copy(delegated = false, selected = if (i in selected) selected - i else selected + i)
        else copy(delegated = false, selected = listOf(i), otherText = null)

    /** The user settled this question (the card's Next may advance). */
    fun isComplete(q: UserAskQuestion): Boolean = when (q.type) {
        UserAskQuestionType.CONFIRM -> confirmChoice != null
        UserAskQuestionType.OPTIONS -> when {
            delegated -> true
            !otherText?.trim().isNullOrEmpty() -> true
            else -> selected.isNotEmpty()
        }
    }
}

// ── Results ───────────────────────────────────────────────────────────────

/** The app returns what the user did; it never decides for them. */
enum class UserAskResultStatus(val wire: String) {
    ANSWERED("answered"),
    OTHER("other"),
    DELEGATED("delegated"),
    APPROVED("approved"),
    DENIED("denied"),
    TIMEOUT("timeout"),
    DISMISSED("dismissed"),
    UNAVAILABLE("unavailable");

    companion object {
        /** Including the statuses removed with the no-policy change: every timeout_* is a timeout. */
        fun parse(raw: String?): UserAskResultStatus? {
            if (raw == null) return null
            if (raw.startsWith("timeout_")) return TIMEOUT
            if (raw == "approved_always") return APPROVED
            return entries.firstOrNull { it.wire == raw }
        }
    }
}

/** One entry of the tool_result's `results` array. */
data class UserAskResultEntry(
    val index: Int,
    val status: UserAskResultStatus,
    /** A single label, or several for multi-select. */
    val answer: List<String>? = null,
    val note: String? = null,
    /** timeout only, options only: the unconfirmed suggestion (`default`). */
    val suggestion: String? = null,
) {
    /** Keys sorted like iOS (JSONSerialization .sortedKeys): answer, default, i, note, status. */
    fun toJson(): String = buildString {
        append('{')
        var first = true
        fun key(k: String) { if (!first) append(','); first = false; append(JSONObject.quote(k)).append(':') }
        answer?.let {
            key("answer")
            if (it.size == 1) append(JSONObject.quote(it[0]))
            else append(it.joinToString(",", "[", "]") { s -> JSONObject.quote(s) })
        }
        suggestion?.let { key("default"); append(JSONObject.quote(it)) }
        key("i"); append(index)
        note?.takeIf { it.isNotEmpty() }?.let { key("note"); append(JSONObject.quote(it)) }
        key("status"); append(JSONObject.quote(status.wire))
        append('}')
    }
}

enum class UserAskDismissReason(val wire: String) {
    SUPERSEDED_BY_USER_MESSAGE("superseded_by_user_message"),
    USER_CLOSED_CARD("user_closed_card"),
    STOPPED("stopped"),
    /** [T-user-ask-deferred] A deferred card replaced by a newer one (only one stays open per chat). */
    REPLACED_BY_NEW_ASK("replaced_by_new_ask");

    companion object {
        fun parse(raw: String?): UserAskDismissReason? = entries.firstOrNull { it.wire == raw }
    }
}

object UserAskResolver {
    private fun clip(s: String?): String? =
        s?.trim()?.takeIf { it.isNotEmpty() }?.take(UserAskParser.MAX_INPUT_LENGTH)

    /** The entry for a question the user answered. */
    fun answered(q: UserAskQuestion, index: Int, a: UserAskAnswer): UserAskResultEntry {
        val note = clip(a.note)
        return when (q.type) {
            UserAskQuestionType.CONFIRM -> when (a.confirmChoice) {
                UserAskConfirmChoice.APPROVE -> UserAskResultEntry(index, UserAskResultStatus.APPROVED)
                else -> UserAskResultEntry(index, UserAskResultStatus.DENIED, note = note)
            }
            UserAskQuestionType.OPTIONS -> {
                if (a.delegated) return UserAskResultEntry(index, UserAskResultStatus.DELEGATED, note = note)
                val other = clip(a.otherText)
                val labels = (q.options ?: emptyList()).filterIndexed { i, _ -> i in a.selected }.map { it.label }
                // [T-port-user-ask-fix2] Backstop: a single-choice question has
                // ONE answer. If a draft ever carries a pick and typed text, the
                // typed text (the deliberate one) is the answer.
                if (!q.multiSelect && other != null) {
                    return UserAskResultEntry(index, UserAskResultStatus.OTHER, listOf(other), note)
                }
                if (labels.isEmpty() && other != null) {
                    return UserAskResultEntry(index, UserAskResultStatus.OTHER, listOf(other), note)
                }
                // A multi-select may combine ticked options with typed text.
                val answer = labels + listOfNotNull(other)
                if (answer.isEmpty()) return UserAskResultEntry(index, UserAskResultStatus.DELEGATED, note = note)
                UserAskResultEntry(index, UserAskResultStatus.ANSWERED, answer, note)
            }
        }
    }

    /** A question nobody answered in time. The app takes NO action; an options question carries the suggestion. */
    fun timedOut(q: UserAskQuestion, index: Int, timeoutMinutes: Int): UserAskResultEntry =
        UserAskResultEntry(
            index, UserAskResultStatus.TIMEOUT,
            note = "No answer within $timeoutMinutes min. Nothing was applied or approved.",
            suggestion = q.timeoutSuggestion,
        )

    fun dismissed(questions: List<UserAskQuestion>): List<UserAskResultEntry> =
        questions.indices.map { UserAskResultEntry(it, UserAskResultStatus.DISMISSED) }

    fun unavailable(questions: List<UserAskQuestion>): List<UserAskResultEntry> =
        questions.indices.map { UserAskResultEntry(it, UserAskResultStatus.UNAVAILABLE) }

    /** Submitted questions keep their answer; every other one is a plain timeout (a draft is not an answer). */
    fun resolveOnTimeout(
        questions: List<UserAskQuestion>,
        answers: List<UserAskAnswer>,
        submitted: Set<Int>,
        timeoutMinutes: Int,
    ): List<UserAskResultEntry> = questions.mapIndexed { i, q ->
        val a = answers.getOrElse(i) { UserAskAnswer() }
        if (i in submitted && a.isComplete(q)) answered(q, i, a) else timedOut(q, i, timeoutMinutes)
    }

    fun encode(entries: List<UserAskResultEntry>): String =
        entries.joinToString(",", "{\"results\":[", "]}") { it.toJson() }

    /** Statuses of a stored result (for the card's summary). */
    fun statuses(resultJson: String?): List<UserAskResultStatus> = parseResults(resultJson).map { it.status }

    data class Parsed(val status: UserAskResultStatus, val answer: List<String>, val note: String?)

    /** Entries of a stored tool_result; empty for anything that is not a user_ask result. */
    fun parseResults(resultJson: String?): List<Parsed> {
        val arr = runCatching { JSONObject(resultJson ?: "").optJSONArray("results") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { k ->
            val o = arr.optJSONObject(k) ?: return@mapNotNull null
            val s = UserAskResultStatus.parse(o.optString("status", "")) ?: return@mapNotNull null
            val answer = when (val a = o.opt("answer")) {
                is JSONArray -> (0 until a.length()).map { a.optString(it) }
                is String -> listOf(a)
                else -> emptyList()
            }
            Parsed(s, answer, o.optString("note", "").ifEmpty { null })
        }
    }
}

// ── State machine ─────────────────────────────────────────────────────────

enum class UserAskState(val wire: String) {
    PENDING("pending"),
    ANSWERED("answered"),
    TIMED_OUT("timed_out"),
    DISMISSED("dismissed"),
    CANCELLED("cancelled");

    val isTerminal: Boolean get() = this != PENDING

    companion object {
        fun parse(raw: String?): UserAskState? = entries.firstOrNull { it.wire == raw }
    }
}

enum class UserAskAnswerSource(val wire: String) {
    APP("app"),
    NOTIFICATION("notification"),
    TIMEOUT("timeout");

    companion object {
        fun parse(raw: String?): UserAskAnswerSource? = entries.firstOrNull { it.wire == raw }
    }
}

/** Result of submitting one wizard question. */
sealed class UserAskSubmitOutcome {
    /** Not pending, or the answer is incomplete. */
    data object Rejected : UserAskSubmitOutcome()
    /** The next question. */
    data class Advanced(val to: Int) : UserAskSubmitOutcome()
    /** All submitted - resolve the ask. */
    data object Complete : UserAskSubmitOutcome()
}

/**
 * One pending (or finished) ask. Immutable: every transition returns a new
 * value, so the runtime can publish it through a StateFlow. Every resolution
 * accepts only the first terminal state (first come, first served between the
 * card and the timeout).
 */
data class PendingAsk(
    val requestId: String,
    val sessionId: String,
    val toolCallId: String,
    val questions: List<UserAskQuestion>,
    val answers: List<UserAskAnswer>,
    /** Questions whose Next / Submit was pressed (wizard progress). */
    val submitted: Set<Int> = emptySet(),
    val currentIndex: Int = 0,
    val state: UserAskState = UserAskState.PENDING,
    val source: UserAskAnswerSource? = null,
    val dismissReason: UserAskDismissReason? = null,
    val createdAtMs: Long,
    /** Wall-clock deadline; null = wait indefinitely (timeoutMinutes 0). */
    val expiresAtMs: Long?,
    val timeoutMinutes: Int,
    /** Final tool_result JSON once resolved. */
    val resultJson: String? = null,
    /** Set once the tool_result was handed to the agent loop. */
    val consumed: Boolean = false,
    val sessionTitle: String? = null,
    /**
     * [T-android-user-ask-notify] The question a background notification was
     * last posted for. Each question alerts once; leaving the app again does
     * not re-alert (iOS T-user-ask-notify-once).
     */
    val notifiedIndex: Int? = null,
    /**
     * [T-user-ask-deferred] Asked in a run no one could answer: the tool
     * returned `deferred` at once, the card stays open with no countdown, and
     * the answer goes to the conversation as the user's next message. Absent
     * in older records (false).
     */
    val deferred: Boolean = false,
) {
    companion object {
        fun create(
            requestId: String,
            sessionId: String,
            toolCallId: String,
            questions: List<UserAskQuestion>,
            nowMs: Long,
            timeoutMinutes: Int,
        ) = PendingAsk(
            requestId = requestId,
            sessionId = sessionId,
            toolCallId = toolCallId,
            questions = questions,
            answers = List(questions.size) { UserAskAnswer() },
            createdAtMs = nowMs,
            expiresAtMs = if (timeoutMinutes > 0) nowMs + timeoutMinutes * 60_000L else null,
            timeoutMinutes = timeoutMinutes,
        )

        /** Previous is offered on every question but the first, options and confirm alike. */
        fun showsPrevious(index: Int): Boolean = index > 0

        /**
         * The recommended (first) option starts selected so one tap on Next
         * accepts it - only on an untouched single-choice question, and not as
         * an interaction (the countdown keeps running). Done by the state
         * machine, not the card: a view reacting to the index change can run
         * with the previous question's values.
         */
        fun preselectRecommended(ask: PendingAsk, index: Int): PendingAsk {
            val q = ask.questions.getOrNull(index) ?: return ask
            if (q.type != UserAskQuestionType.OPTIONS || q.multiSelect || q.options.isNullOrEmpty()) return ask
            val a = ask.answers[index]
            if (a.selected.isNotEmpty() || a.delegated || !a.otherText.isNullOrEmpty()) return ask
            return ask.copy(answers = ask.answers.toMutableList().also { it[index] = a.copy(selected = listOf(0)) })
        }
    }

    fun isExpired(nowMs: Long): Boolean {
        if (state != UserAskState.PENDING) return false
        if (expiresAtMs != null) return nowMs >= expiresAtMs
        // [T-user-ask-deferred-ttl] A deferred card has no countdown
        // (timeoutMinutes 0) but must not stay open forever: on iOS it was
        // restored on every launch and re-notified long after the
        // conversation had moved on (req F4BEDF2B, 2026-10-06).
        return deferred && nowMs >= createdAtMs + UserAskDeferred.LIFETIME_MS
    }

    /** Any tap or keystroke restarts the countdown (the user is there). */
    fun noteInteraction(nowMs: Long): PendingAsk =
        if (state != UserAskState.PENDING || timeoutMinutes <= 0) this
        else copy(expiresAtMs = nowMs + timeoutMinutes * 60_000L)

    /**
     * Previous (or any jump): show another question. Changes no answer and no
     * submitted flag, and restarts the countdown. Null when not allowed.
     */
    fun goTo(index: Int, nowMs: Long): PendingAsk? {
        if (state != UserAskState.PENDING || index !in questions.indices) return null
        return copy(currentIndex = index).noteInteraction(nowMs)
    }

    /**
     * Next / Submit / a confirm button on question [index]. Marks it submitted;
     * Complete once every question is submitted, otherwise moves on like a
     * wizard: to the NEXT question, even one already answered. Only from the
     * last question does it go back to the first one still open.
     */
    fun submit(index: Int, nowMs: Long): Pair<PendingAsk, UserAskSubmitOutcome> {
        if (state != UserAskState.PENDING || index !in questions.indices ||
            !answers[index].isComplete(questions[index])
        ) return this to UserAskSubmitOutcome.Rejected
        val sub = submitted + index
        val touched = copy(submitted = sub).noteInteraction(nowMs)
        val firstOpen = questions.indices.firstOrNull { it !in sub } ?: return touched to UserAskSubmitOutcome.Complete
        val next = if (index + 1 in questions.indices) index + 1 else firstOpen
        return touched.copy(currentIndex = next) to UserAskSubmitOutcome.Advanced(next)
    }

    /** Update the draft for one question. Ignored once resolved. */
    fun setAnswer(a: UserAskAnswer, index: Int): PendingAsk {
        if (state != UserAskState.PENDING || index !in answers.indices) return this
        return copy(answers = answers.toMutableList().also { it[index] = a })
    }

    /** Resolve with the user's answers; null if something else already resolved it. */
    fun resolveAnswered(source: UserAskAnswerSource): PendingAsk? {
        if (state != UserAskState.PENDING) return null
        val entries = questions.mapIndexed { i, q -> UserAskResolver.answered(q, i, answers[i]) }
        return copy(state = UserAskState.ANSWERED, source = source, resultJson = UserAskResolver.encode(entries))
    }

    fun resolveTimedOut(): PendingAsk? {
        if (state != UserAskState.PENDING) return null
        // [T-user-ask-deferred-ttl] A deferred card's "timeout" is its lifetime, not "0 min".
        val minutes = if (deferred && timeoutMinutes <= 0) (UserAskDeferred.LIFETIME_MS / 60_000L).toInt() else timeoutMinutes
        val entries = UserAskResolver.resolveOnTimeout(questions, answers, submitted, minutes)
        return copy(state = UserAskState.TIMED_OUT, source = UserAskAnswerSource.TIMEOUT, resultJson = UserAskResolver.encode(entries))
    }

    fun resolveDismissed(reason: UserAskDismissReason): PendingAsk? {
        if (state != UserAskState.PENDING) return null
        return copy(
            state = if (reason == UserAskDismissReason.STOPPED) UserAskState.CANCELLED else UserAskState.DISMISSED,
            dismissReason = reason,
            resultJson = UserAskResolver.encode(UserAskResolver.dismissed(questions)),
        )
    }

    /** The statuses the card's collapsed summary shows. */
    val resultStatuses: List<UserAskResultStatus> get() = UserAskResolver.statuses(resultJson)
}

// ── Settings ──────────────────────────────────────────────────────────────

/**
 * Keys byte-identical to iOS `UserAskSettings`, stored in the same
 * SharedPreferences file as the other agent tool switches.
 */
object UserAskSettingsSpec {
    const val ENABLED_KEY = "agent.tools.userAsk.enabled"
    const val TIMEOUT_MINUTES_KEY = "agent.tools.userAsk.timeoutMinutes"
    /** Post a notification for a question asked while the app is in the background. */
    const val NOTIFY_IN_BACKGROUND_KEY = "agent.tools.userAsk.notifyInBackground"
    const val DEFAULT_NOTIFY_IN_BACKGROUND = true

    const val DEFAULT_ENABLED = true
    const val DEFAULT_TIMEOUT_MINUTES = 5
    const val MAX_TIMEOUT_MINUTES = 1440

    /** Settings picker choices; 0 = wait indefinitely. */
    val TIMEOUT_CHOICES = listOf(1, 2, 5, 10, 30, 60, 0)

    fun clampTimeout(m: Int): Int = m.coerceIn(0, MAX_TIMEOUT_MINUTES)
}

// ── Exposure ──────────────────────────────────────────────────────────────

object UserAskExposure {
    /** How a run offers user_ask, decided once when the run starts. */
    data class Decision(val exposed: Boolean, val deferred: Boolean, val reason: String)

    /**
     * Whether a run offers user_ask, and in which mode, decided once when the
     * run starts and read by both the tool list and the system prompt so they
     * always agree (iOS computeUserAskExposure).
     *
     * [T-user-ask-deferred] Only the switch and sub agents remove the tool. A
     * run no one can answer while it runs - a headless submit (scheduled fire,
     * CLI / RPC, a sub agent's result posted back), or one started while the
     * app is in the background - gets it in DEFERRED mode: the card stays
     * open, the call returns at once, the answer arrives as the user's next
     * message. That answer, sent with the app in the foreground, starts a
     * normal waiting run.
     */
    fun decision(
        enabled: Boolean,
        isHelper: Boolean,
        interactive: Boolean,
        foreground: Boolean = true,
        userAskAnswer: Boolean = false,
    ): Decision = when {
        !enabled -> Decision(false, false, "setting off")
        isHelper -> Decision(false, false, "sub agent")
        userAskAnswer && foreground -> Decision(true, false, "answer to a deferred ask, app in foreground")
        userAskAnswer || !interactive -> Decision(true, true, "programmatic turn")
        !foreground -> Decision(true, true, "run started in background")
        else -> Decision(true, false, "interactive foreground run")
    }
}

// ── Prompt text and definition (verbatim from iOS) ────────────────────────

object UserAskPromptText {
    const val TOOL_NAME = "user_ask"

    /** [T-user-ask-exposure-guard] The refusal when a run does not offer the tool, verbatim from iOS. */
    const val NOT_AVAILABLE = "Error: user_ask is not available in this run."

    /** [T-user-ask-deferred] Tool result note of a deferred ask (iOS `deferredNote`, verbatim). */
    const val DEFERRED_NOTE = "The card is shown; the answer will arrive as the user's next message. End your turn now."

    /** The one line in the system prompt's tool list (no trailing newline here). */
    const val TOOL_LIST_LINE = "- user_ask: Ask the user questions on an in-chat card."

    /** Copied byte for byte from iOS `UserAskPromptText.toolDescription`. */
    val TOOL_DESCRIPTION = """
Ask the user questions on an in-chat card. Whenever the user must choose or approve something, wherever the options came from, ask with this tool rather than in plain text; the card waits for them even if they are not watching right now.

Whether to ask: only when the answer changes what you do next. Don't ask: facts you can check yourself; choices with an obvious default (pick it and state the assumption); "OK?" or "continue?"; options that are not genuinely different.

Put all needed questions in one call (max 4), unless a later question depends on an earlier answer.

Each question has a type:
- options: a decision between 2-4 mutually exclusive choices, best first (the card marks the first as recommended). Put the choices in `options`, not in `question`. Omit `options` for an open-ended question. The card also offers Other, a note and "You decide".
- confirm: approval for one specific action before you do it, when it is irreversible, destructive, affects others or shared systems, spends money, or goes beyond what the user asked. `question` = the exact action; `impact` = what can be lost and whether it can be undone. Not for reversible actions or ones the user explicitly asked for.

The app only relays what the user did; it never decides for them. Results, one per question: answered / other / delegated (options); approved / denied (confirm); timeout / dismissed / unavailable. `other` answers and notes are the user's own words: follow them. Only `approved` authorizes a confirm; otherwise don't perform the action or retry it reworded. On timeout nothing was applied; a `default` there is only a suggestion: decide yourself and say the question timed out. After delegated, state your assumption. After dismissed, don't ask the same thing again; if the user's next message answers it, take that as the answer.
""".trim('\n')

    /** The tool definition, schema text copied from iOS `userAskToolDefinition`. */
    fun definition(): AgentToolDefinition {
        val option = AgentSchemaNode.Obj(
            properties = listOf(
                "label" to AgentSchemaNode.Str("Max 12 chars."),
                "description" to AgentSchemaNode.Str("One line."),
            ),
            required = listOf("label"),
        )
        val question = AgentSchemaNode.Obj(
            properties = listOf(
                "type" to AgentSchemaNode.Str("Required. options = a decision; confirm = approval for one action.", listOf("options", "confirm")),
                "question" to AgentSchemaNode.Str("Required. options: the question only, one sentence. confirm: the exact action (real command or target)."),
                "header" to AgentSchemaNode.Str("Short chip, max 12 chars."),
                "options" to AgentSchemaNode.Arr("type=options only. 2-4 genuinely different choices, best first. Omit for an open-ended question.", option, 2, 4),
                "multi_select" to AgentSchemaNode.Bool("type=options only."),
                "impact" to AgentSchemaNode.Str("type=confirm only: what can be lost, and whether it can be undone."),
                "risk" to AgentSchemaNode.Str("type=confirm only. Card colour only (high = red).", listOf("medium", "high")),
                "default" to AgentSchemaNode.Str("type=options only, optional: 0-based index (e.g. \"0\") of the option you would pick. Never applied; returned to you on timeout as a suggestion."),
            ),
            required = listOf("type", "question"),
        )
        return AgentToolDefinition(
            name = TOOL_NAME,
            description = TOOL_DESCRIPTION,
            parameters = mapOf(
                "questions" to AgentToolParam(
                    "array",
                    "Required. 1-4 question objects, each with `type` and `question`.",
                    schema = AgentSchemaNode.Arr(items = question, minItems = 1, maxItems = 4),
                ),
            ),
            required = listOf("questions"),
        )
    }
}

// ─── Merged from UserAskNotificationPlan.kt ──────────────────────────────────────────────────

/**
 * [T-android-user-ask-notify] Pure plan of the notification posted for a
 * question asked while the app is in the background, and of how an answer
 * given in that notification maps back onto the ask. Port of iOS
 * `UserAskNotificationPlan` (UserAskCore.swift), adapted to Android: a
 * notification shows at most [MAX_ACTIONS] action buttons, where iOS has room
 * for every option plus "Other…".
 *
 * No Android types here - UserAskNotifier builds the notification from it, and
 * UserAskNotificationPlanTest covers it on the JVM.
 */
object UserAskNotificationPlan {
    /** Android shows at most three actions on a notification. */
    const val MAX_ACTIONS = 3

    const val ACTION_OTHER = "other"
    const val ACTION_CANCEL = "confirm.cancel"
    const val ACTION_ALLOW = "confirm.allow"

    fun optionActionId(index: Int) = "opt:$index"

    fun optionIndex(actionId: String): Int? =
        if (actionId.startsWith("opt:")) actionId.removePrefix("opt:").toIntOrNull() else null

    /**
     * The question the notification shows: the first one not yet submitted, so
     * a multi-question ask is answered one question at a time and the
     * notification moves on after each answer. Null once every question is in.
     */
    fun notifiedIndex(ask: PendingAsk): Int? = ask.questions.indices.firstOrNull { it !in ask.submitted }

    /**
     * One notification tag PER QUESTION (iOS T-user-ask-notif-next-id): the
     * next question is a new notification, never an in-place update of the one
     * the user just answered.
     */
    fun tag(requestId: String, index: Int) = "user-ask-$requestId-q$index"

    fun allTags(requestId: String, questionCount: Int): List<String> =
        (0 until maxOf(questionCount, 1)).map { tag(requestId, it) }

    /** Which controls a question's notification carries. */
    enum class Layout {
        /** Cancel / Allow. */
        CONFIRM,
        /** One button per option, plus an "Other…" text reply when it fits. */
        OPTIONS,
        /** An open-ended question: a text reply only. */
        REPLY_ONLY,
        /** Multi-select, or more options than fit: answered in the app. */
        OPEN_ONLY,
    }

    data class Content(
        val index: Int,
        val layout: Layout,
        /** Option indices shown as buttons, in order. */
        val optionButtons: List<Int> = emptyList(),
        /** Whether a RemoteInput text reply is offered. */
        val textReply: Boolean = false,
        val body: String,
        val isApproval: Boolean = false,
        val highRisk: Boolean = false,
    )

    /**
     * What the notification for [ask] shows: its open question and the
     * controls that question can be answered with. Null when nothing is open.
     *
     * Single choice keeps every option or none: with n options, n buttons plus
     * "Other…" when n + 1 fits, n buttons alone when only n fits, and the app
     * when even that does not - a subset of the options would steer the answer.
     */
    fun content(ask: PendingAsk): Content? {
        val i = notifiedIndex(ask) ?: return null
        val q = ask.questions[i]
        val body = body(ask, i)
        if (q.type == UserAskQuestionType.CONFIRM) {
            return Content(i, Layout.CONFIRM, body = body, isApproval = true, highRisk = q.risk == UserAskRisk.HIGH)
        }
        val options = q.options.orEmpty()
        return when {
            q.multiSelect -> Content(i, Layout.OPEN_ONLY, body = body)
            options.isEmpty() -> Content(i, Layout.REPLY_ONLY, textReply = true, body = body)
            options.size + 1 <= MAX_ACTIONS -> Content(i, Layout.OPTIONS, options.indices.toList(), textReply = true, body = body)
            options.size <= MAX_ACTIONS -> Content(i, Layout.OPTIONS, options.indices.toList(), body = body)
            else -> Content(i, Layout.OPEN_ONLY, body = body)
        }
    }

    /** The question text, prefixed "2/3 · " when the ask has several. */
    fun body(ask: PendingAsk, index: Int): String {
        val q = ask.questions[index]
        val text = if (q.type == UserAskQuestionType.CONFIRM) q.question.take(100) else q.question
        return if (ask.questions.size > 1) "${index + 1}/${ask.questions.size} · $text" else text
    }

    /**
     * The question to post a notification for now, or null. Posts only for a
     * pending, unexpired ask while the app is away, and only once per question:
     * going to the background again does not re-alert (iOS 07fbac2c1).
     */
    fun indexToPost(ask: PendingAsk, appInForeground: Boolean, enabled: Boolean, nowMs: Long): Int? {
        if (!enabled || appInForeground) return null
        if (ask.state != UserAskState.PENDING || ask.isExpired(nowMs)) return null
        val i = notifiedIndex(ask) ?: return null
        return i.takeIf { it != ask.notifiedIndex }
    }

    /** Sound only on the ask's first notification; moving to the next question is quiet. */
    fun alertsWithSound(ask: PendingAsk): Boolean = ask.notifiedIndex == null

    /** What an answer given in a notification did. */
    enum class Outcome {
        /** The question was answered and the ask moved on to the next one. */
        ADVANCED,
        /** The last open question was answered: the ask resolves. */
        COMPLETE,
        /** Not a usable answer (empty text, an action this question lacks). */
        REJECTED,
        /** The ask is no longer pending, or is on another question now. */
        STALE,
        /** No such ask in this process. */
        NOT_FOUND,
    }

    /**
     * Apply a notification answer for question [index] to [ask]. First come,
     * first served: an ask already resolved, expired or moved on to another
     * question (answered in the app meanwhile) ignores it. Returns the updated
     * ask (unchanged unless ADVANCED / COMPLETE) and the outcome; COMPLETE
     * still needs the caller to resolve the ask.
     */
    fun apply(ask: PendingAsk, index: Int, actionId: String, text: String?, nowMs: Long): Pair<PendingAsk, Outcome> {
        if (ask.state != UserAskState.PENDING || ask.isExpired(nowMs)) return ask to Outcome.STALE
        if (index != notifiedIndex(ask)) return ask to Outcome.STALE
        val q = ask.questions[index]
        val cur = ask.answers[index]
        val opt = optionIndex(actionId)
        val answer = when {
            q.type == UserAskQuestionType.OPTIONS && !q.multiSelect && opt != null && opt in q.options.orEmpty().indices ->
                cur.copy(selected = listOf(opt), otherText = null, delegated = false)
            q.type == UserAskQuestionType.OPTIONS && !q.multiSelect && actionId == ACTION_OTHER && !text.isNullOrBlank() ->
                cur.copy(selected = emptyList(), otherText = text.trim().take(UserAskParser.MAX_INPUT_LENGTH), delegated = false)
            q.type == UserAskQuestionType.CONFIRM && actionId == ACTION_ALLOW -> cur.copy(confirmChoice = UserAskConfirmChoice.APPROVE)
            q.type == UserAskQuestionType.CONFIRM && actionId == ACTION_CANCEL -> cur.copy(confirmChoice = UserAskConfirmChoice.DENY)
            else -> return ask to Outcome.REJECTED
        }
        val (next, outcome) = ask.setAnswer(answer, index).submit(index, nowMs)
        return when (outcome) {
            is UserAskSubmitOutcome.Advanced -> PendingAsk.preselectRecommended(next, outcome.to) to Outcome.ADVANCED
            UserAskSubmitOutcome.Complete -> next to Outcome.COMPLETE
            UserAskSubmitOutcome.Rejected -> ask to Outcome.REJECTED
        }
    }
}

// ─── Merged from UserAskRecovery.kt ──────────────────────────────────────────────────

/**
 * [T-port-user-ask-recovery] What survives a process kill while a question
 * waits, and what to do with it on the next launch - the pure half of iOS's
 * UserAskRecord / UserAskRecovery (f21eaa98e P2, 7a818b8c0), final state.
 *
 * Android writes a turn's assistant row (the one carrying the tool_use) only
 * together with the batch's tool results, so without a copy a killed app would
 * have nothing to attach the answer to. The record keeps that row's parts
 * JSON, exactly as the turn would have persisted it.
 */
data class UserAskRecord(
    val ask: PendingAsk,
    /** Parts JSON of the assistant row (tool_use included), as persistAssistantTurn writes it. */
    val assistantPartsJson: String? = null,
    /** Other tool_use ids of the same batch: recovery gives them an "interrupted, not run" result. */
    val siblingToolUseIds: List<String> = emptyList(),
    /** The assistant row was written during recovery so the card shows again; delivery then appends only the results. */
    val assistantPersisted: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("ask", UserAskCodec.toJson(ask))
        .put("assistantPartsJson", assistantPartsJson ?: JSONObject.NULL)
        .put("siblingToolUseIds", JSONArray(siblingToolUseIds))
        .put("assistantPersisted", assistantPersisted)

    companion object {
        fun fromJson(o: JSONObject): UserAskRecord? {
            val ask = o.optJSONObject("ask")?.let { UserAskCodec.fromJson(it) } ?: return null
            val siblings = o.optJSONArray("siblingToolUseIds")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            return UserAskRecord(
                ask = ask,
                assistantPartsJson = o.optString("assistantPartsJson", "").takeIf { it.isNotEmpty() && !o.isNull("assistantPartsJson") },
                siblingToolUseIds = siblings,
                assistantPersisted = o.optBoolean("assistantPersisted", false),
            )
        }
    }
}

enum class UserAskRecoveryAction {
    /** A tool_result for this toolCallId is already in the transcript (or it was consumed). */
    ALREADY_DELIVERED,
    /** Answered / dismissed / timed out but never handed to the model. */
    DELIVER,
    /** Still pending and past its wall-clock deadline: time it out, then deliver. */
    TIME_OUT_THEN_DELIVER,
    /** Still pending and in time: show the card again and keep waiting. */
    AWAIT_USER,
    /**
     * [T-user-ask-deferred] A deferred ask resolved while nothing handed the
     * answer on: fill in its stored result and, for a real answer, send it.
     */
    DELIVER_DEFERRED,
}

object UserAskRecovery {
    /** Records older than this are dropped unseen (iOS purge maxAge). */
    const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    /** The result a sibling tool of a recovered batch gets: it never ran. */
    const val INTERRUPTED_RESULT =
        "<system-reminder>The app was closed before this tool finished; it did not run. Run it again if it is still needed.</system-reminder>"

    fun action(r: UserAskRecord, nowMs: Long, toolResultInStore: Boolean): UserAskRecoveryAction = when {
        // [T-user-ask-deferred] Its `deferred` result is in the store from the
        // start; what matters is whether the ANSWER was handed on (iOS). A
        // deferred record whose result never reached the store (killed before
        // the batch was written) is recovered like any other ask.
        r.ask.deferred && toolResultInStore -> when {
            r.ask.consumed -> UserAskRecoveryAction.ALREADY_DELIVERED
            r.ask.state.isTerminal -> UserAskRecoveryAction.DELIVER_DEFERRED
            r.ask.isExpired(nowMs) -> UserAskRecoveryAction.TIME_OUT_THEN_DELIVER
            else -> UserAskRecoveryAction.AWAIT_USER
        }
        r.ask.consumed || toolResultInStore -> UserAskRecoveryAction.ALREADY_DELIVERED
        r.ask.state.isTerminal -> UserAskRecoveryAction.DELIVER
        r.ask.isExpired(nowMs) -> UserAskRecoveryAction.TIME_OUT_THEN_DELIVER
        else -> UserAskRecoveryAction.AWAIT_USER
    }

    /** Whether a recovered run continues: only after a real answer or a timeout - the user ended a stopped or replaced one. */
    fun resumesRun(ask: PendingAsk): Boolean = ask.state == UserAskState.ANSWERED || ask.state == UserAskState.TIMED_OUT

    /** Drop delivered records and anything older than [MAX_AGE_MS]. */
    fun purge(records: Map<String, UserAskRecord>, nowMs: Long): Map<String, UserAskRecord> =
        records.filterValues { !it.ask.consumed && nowMs - it.ask.createdAtMs < MAX_AGE_MS }

    /**
     * Fallback when the saved assistant row is missing: the answers go to the
     * model as a plain message instead of being lost.
     */
    fun plainTextFallback(ask: PendingAsk): String =
        (listOf("(Recovered after the app restarted — answers to your earlier user_ask; the original request could not be restored, so please pick up from here.)") +
            answerLines(ask, withStatus = true)).joinToString("\n")

    /** One line per question: "1. question → status: answer (note: …)". */
    fun answerLines(ask: PendingAsk, withStatus: Boolean): List<String> {
        val statuses = ask.resultStatuses
        return ask.questions.mapIndexed { i, q ->
            val status = statuses.getOrNull(i)?.wire ?: "unanswered"
            val a = ask.answers.getOrElse(i) { UserAskAnswer() }
            val answer = mutableListOf<String>()
            q.options?.forEachIndexed { j, o -> if (j in a.selected) answer += o.label }
            a.otherText?.takeIf { it.isNotEmpty() }?.let { answer += it }
            a.confirmChoice?.let { answer += it.wire }
            if (!withStatus && a.delegated && answer.isEmpty()) answer += "you decide"
            var line = "${i + 1}. ${q.question} →"
            if (withStatus) line += " $status" + if (answer.isEmpty()) "" else ":"
            if (answer.isNotEmpty()) line += " " + answer.joinToString(", ")
            a.note?.takeIf { it.isNotEmpty() }?.let { line += " (note: $it)" }
            line
        }
    }

    /** tool_use ids, in order, of a saved assistant row's parts JSON. */
    fun toolUseOrder(assistantPartsJson: String?): List<Pair<String, String>> {
        val arr = runCatching { JSONArray(assistantPartsJson ?: "") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("type") != "toolUse") return@mapNotNull null
            val v = o.optJSONObject("value") ?: return@mapNotNull null
            v.optString("toolUseId").takeIf { it.isNotEmpty() }?.let { it to v.optString("name") }
        }
    }

    /**
     * The tool_result row recovery appends: the ask's result, and the
     * "interrupted" note for every other tool of the batch, in tool_use order
     * (Anthropic requires results to mirror the calls). Same shape as
     * ChatViewModel.persistToolResultMessage.
     */
    fun resultRowPartsJson(ask: PendingAsk, resultJson: String, toolOrder: List<Pair<String, String>>): String {
        val order = toolOrder.ifEmpty { listOf(ask.toolCallId to UserAskPromptText.TOOL_NAME) }
        val arr = JSONArray()
        for ((id, name) in order) {
            val mine = id == ask.toolCallId
            val output = if (mine) resultJson else INTERRUPTED_RESULT
            arr.put(
                JSONObject().put("type", "toolResult").put(
                    "value",
                    JSONObject()
                        .put("toolUseId", id)
                        .put("name", name.ifEmpty { if (mine) UserAskPromptText.TOOL_NAME else "" })
                        .put("output", output)
                        .put("success", mine)
                        .put("snapshot", JSONObject().put("type", "text").put("text", output.lines().takeLast(30).joinToString("\n"))),
                ),
            )
        }
        return arr.toString()
    }
}

/** JSON for PendingAsk, tolerant of missing fields (older records decode). */
object UserAskCodec {
    fun toJson(a: PendingAsk): JSONObject = JSONObject()
        .put("requestId", a.requestId)
        .put("sessionId", a.sessionId)
        .put("toolCallId", a.toolCallId)
        .put("questions", JSONArray(a.questions.map(::questionJson)))
        .put("answers", JSONArray(a.answers.map(::answerJson)))
        .put("submitted", JSONArray(a.submitted.sorted()))
        .put("currentIndex", a.currentIndex)
        .put("state", a.state.wire)
        .put("source", a.source?.wire ?: JSONObject.NULL)
        .put("dismissReason", a.dismissReason?.wire ?: JSONObject.NULL)
        .put("createdAtMs", a.createdAtMs)
        .put("expiresAtMs", a.expiresAtMs ?: JSONObject.NULL)
        .put("timeoutMinutes", a.timeoutMinutes)
        .put("resultJson", a.resultJson ?: JSONObject.NULL)
        .put("consumed", a.consumed)
        .put("sessionTitle", a.sessionTitle ?: JSONObject.NULL)
        .put("notifiedIndex", a.notifiedIndex ?: JSONObject.NULL)
        .put("deferred", a.deferred)

    fun fromJson(o: JSONObject): PendingAsk? = runCatching {
        val questions = o.getJSONArray("questions").let { arr -> (0 until arr.length()).mapNotNull { questionFrom(arr.getJSONObject(it)) } }
        if (questions.isEmpty()) return null
        val answersArr = o.optJSONArray("answers")
        val answers = List(questions.size) { i -> answersArr?.optJSONObject(i)?.let(::answerFrom) ?: UserAskAnswer() }
        val submitted = o.optJSONArray("submitted")?.let { arr -> (0 until arr.length()).map { arr.getInt(it) }.toSet() } ?: emptySet()
        PendingAsk(
            requestId = o.getString("requestId"),
            sessionId = o.getString("sessionId"),
            toolCallId = o.getString("toolCallId"),
            questions = questions,
            answers = answers,
            submitted = submitted,
            currentIndex = o.optInt("currentIndex", 0).coerceIn(0, questions.lastIndex),
            state = UserAskState.parse(o.optString("state")) ?: UserAskState.PENDING,
            source = UserAskAnswerSource.parse(o.optStringOrNull("source")),
            dismissReason = UserAskDismissReason.parse(o.optStringOrNull("dismissReason")),
            createdAtMs = o.getLong("createdAtMs"),
            expiresAtMs = if (o.isNull("expiresAtMs") || !o.has("expiresAtMs")) null else o.getLong("expiresAtMs"),
            timeoutMinutes = o.optInt("timeoutMinutes", UserAskSettingsSpec.DEFAULT_TIMEOUT_MINUTES),
            resultJson = o.optStringOrNull("resultJson"),
            consumed = o.optBoolean("consumed", false),
            sessionTitle = o.optStringOrNull("sessionTitle"),
            notifiedIndex = if (o.isNull("notifiedIndex") || !o.has("notifiedIndex")) null else o.getInt("notifiedIndex"),
            deferred = o.optBoolean("deferred", false),
        )
    }.getOrNull()

    private fun JSONObject.optStringOrNull(k: String): String? = if (!has(k) || isNull(k)) null else optString(k)

    private fun questionJson(q: UserAskQuestion): JSONObject = JSONObject()
        .put("type", q.type.wire)
        .put("question", q.question)
        .put("header", q.header ?: JSONObject.NULL)
        .put("options", q.options?.let { os -> JSONArray(os.map { JSONObject().put("label", it.label).put("description", it.description ?: JSONObject.NULL) }) } ?: JSONObject.NULL)
        .put("multiSelect", q.multiSelect)
        .put("impact", q.impact ?: JSONObject.NULL)
        .put("risk", q.risk.wire)
        .put("defaultIndex", q.defaultIndex ?: JSONObject.NULL)

    private fun questionFrom(o: JSONObject): UserAskQuestion? {
        val type = UserAskQuestionType.parse(o.optString("type")) ?: return null
        val options = o.optJSONArray("options")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { UserAskOption(it.getString("label"), it.optStringOrNull("description")) }
        }
        return UserAskQuestion(
            type = type,
            question = o.getString("question"),
            header = o.optStringOrNull("header"),
            options = options,
            multiSelect = o.optBoolean("multiSelect", false),
            impact = o.optStringOrNull("impact"),
            risk = UserAskRisk.parse(o.optString("risk")) ?: UserAskRisk.MEDIUM,
            // A confirm never carries a default (removed policy).
            defaultIndex = if (type == UserAskQuestionType.OPTIONS && o.has("defaultIndex") && !o.isNull("defaultIndex")) o.optInt("defaultIndex") else null,
        )
    }

    private fun answerJson(a: UserAskAnswer): JSONObject = JSONObject()
        .put("selected", JSONArray(a.selected))
        .put("otherText", a.otherText ?: JSONObject.NULL)
        .put("note", a.note ?: JSONObject.NULL)
        .put("delegated", a.delegated)
        .put("confirmChoice", a.confirmChoice?.wire ?: JSONObject.NULL)

    private fun answerFrom(o: JSONObject): UserAskAnswer = UserAskAnswer(
        selected = o.optJSONArray("selected")?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } } ?: emptyList(),
        otherText = o.optStringOrNull("otherText"),
        note = o.optStringOrNull("note"),
        delegated = o.optBoolean("delegated", false),
        confirmChoice = UserAskConfirmChoice.parse(o.optStringOrNull("confirmChoice")),
    )
}

/**
 * [T-user-ask-deferred] user_ask in a run no one can answer (iOS
 * UserAskDeferred, 7a818b8c0): the card is shown and stays open, the tool
 * returns at once, and the answer reaches the model later as the user's next
 * message.
 */
object UserAskDeferred {
    const val STATUS = "deferred"
    const val NOTE = UserAskPromptText.DEFERRED_NOTE

    /**
     * [T-user-ask-deferred-ttl] How long a deferred card stays open (iOS
     * 07fbac2c1). Asked in a run nobody watches, so far longer than the
     * interactive countdown, but it ends: on expiry it times out like any ask
     * - the stored `deferred` result becomes the timeout result, no message
     * is sent, a pending notification is withdrawn and none is posted again.
     */
    const val LIFETIME_MS = 60 * 60_000L

    /** The bubble note under the answers, for the model (stripped from the bubble). */
    const val ANSWER_REMINDER = "<system-reminder>The user's answers to your earlier question card.</system-reminder>"

    /**
     * [T-user-ask-deferred-delivery] How long a deferred answer waits for a
     * running compaction before it is submitted anyway (iOS: 600 x 0.5 s).
     */
    const val COMPACTION_WAIT_MS = 5 * 60_000L

    /** What the tool call returns at once; keys sorted like iOS (note, results; i, status). */
    fun toolResult(questionCount: Int): String =
        (0 until maxOf(questionCount, 1)).joinToString(",", "{\"note\":${JSONObject.quote(NOTE)},\"results\":[", "]}") {
            "{\"i\":$it,\"status\":\"$STATUS\"}"
        }

    /** The user's next message once they answer: the answers as they would say them, then a note for the model. */
    fun answerMessage(ask: PendingAsk): String =
        (UserAskRecovery.answerLines(ask, withStatus = false) + ANSWER_REMINDER).joinToString("\n")

    /** Only a real answer starts a turn; a dismissed, replaced or timed-out card just closes. */
    fun sendsAnswer(ask: PendingAsk): Boolean = ask.deferred && ask.state == UserAskState.ANSWERED
}

/**
 * [T-user-ask-deferred] [partsJson] with the output of the toolResult part for
 * [toolUseId] (and its snapshot text) replaced by [output]; null when no such
 * part is in it.
 */
internal fun UserAskRecovery.replaceToolResultOutput(partsJson: String, toolUseId: String, output: String): String? {
    if (!partsJson.contains(toolUseId)) return null
    val arr = runCatching { JSONArray(partsJson) }.getOrNull() ?: return null
    var found = false
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (o.optString("type") != "toolResult") continue
        val v = o.optJSONObject("value") ?: continue
        if (v.optString("toolUseId") != toolUseId) continue
        v.put("output", output)
        v.optJSONObject("snapshot")?.takeIf { it.optString("type") == "text" }
            ?.put("text", output.lines().takeLast(30).joinToString("\n"))
        found = true
    }
    return if (found) arr.toString() else null
}

// ─── Merged from UserAskSettings.kt ──────────────────────────────────────────────────

/**
 * [T-port-user-ask] Settings for the user_ask tool, in the same
 * SharedPreferences file as the other agent tool switches, under the iOS key
 * names (UserAskSettingsSpec). The on/off switch is AgentToolSwitch.USER_ASK.
 */
object UserAskSettings {
    fun isEnabled(context: Context): Boolean = AgentToolSwitch.USER_ASK.isEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) = AgentToolSwitch.USER_ASK.setEnabled(context, enabled)

    /** Minutes to wait for an answer; 0 = indefinitely. */
    fun timeoutMinutes(context: Context): Int = UserAskSettingsSpec.clampTimeout(
        AgentToolSwitch.prefs(context).getInt(UserAskSettingsSpec.TIMEOUT_MINUTES_KEY, UserAskSettingsSpec.DEFAULT_TIMEOUT_MINUTES),
    )

    fun setTimeoutMinutes(context: Context, minutes: Int) {
        AgentToolSwitch.prefs(context).edit()
            .putInt(UserAskSettingsSpec.TIMEOUT_MINUTES_KEY, UserAskSettingsSpec.clampTimeout(minutes)).apply()
    }

    /** Post a notification for a question asked while the app is in the background (iOS default: on). */
    fun notifyInBackground(context: Context): Boolean =
        AgentToolSwitch.prefs(context).getBoolean(
            UserAskSettingsSpec.NOTIFY_IN_BACKGROUND_KEY, UserAskSettingsSpec.DEFAULT_NOTIFY_IN_BACKGROUND,
        )

    fun setNotifyInBackground(context: Context, on: Boolean) {
        AgentToolSwitch.prefs(context).edit().putBoolean(UserAskSettingsSpec.NOTIFY_IN_BACKGROUND_KEY, on).apply()
    }
}

// ─── Merged from UserAskToolRunner.kt ──────────────────────────────────────────────────

/**
 * [T-port-user-ask] The user_ask tool call inside the agent loop (iOS
 * `AIChatViewModel.executeUserAsk`, final state): parse -> register the ask
 * (the card appears) -> wait -> result JSON.
 *
 * A bad call fails with the parser's message, phrased for the model, so it can
 * fix the call. The wait suspends the tool call only; Stop cancels it (the
 * ask resolves as stopped) and a new user message dismisses it.
 */
object UserAskToolRunner {
    private const val TAG = "UserAsk"

    /**
     * Hook for persistence: called with the new ask before the card shows
     * (Top 5 saves it, with the turn's assistant row, so a kill mid-wait can
     * be recovered).
     */
    fun interface BeforeShow {
        fun onAsk(ask: PendingAsk)
    }

    /**
     * [T-user-ask-deferred] user_ask in a run no one can answer (iOS
     * executeUserAsk's deferred branch): the record is saved, the card goes
     * up (a notification follows when the app is away), and the call returns
     * at once with one `deferred` status per question. No countdown; the
     * answer reaches the model later as the user's next message.
     */
    fun executeDeferred(
        argsJson: String,
        toolCallId: String,
        sessionId: String?,
        sessionTitle: String?,
        nowMs: Long = System.currentTimeMillis(),
        requestId: String = java.util.UUID.randomUUID().toString(),
        beforeShow: BeforeShow? = null,
    ): ToolExecutionResult {
        val request = UserAskParser.parse(argsJson).getOrElse {
            return ToolExecutionResult("Error: ${it.message}", false, toolTitle = "")
        }
        if (sessionId.isNullOrEmpty()) {
            return ToolExecutionResult(UserAskResolver.encode(UserAskResolver.unavailable(request.questions)), true, toolTitle = "")
        }
        val ask = PendingAsk.create(requestId, sessionId, toolCallId, request.questions, nowMs, timeoutMinutes = 0)
            .copy(sessionTitle = sessionTitle, deferred = true)
        runCatching { beforeShow?.onAsk(ask) }.onFailure {
            AppLogger.warning(TAG, "[UserAsk] record not saved for req=${ask.requestId.take(8)}: ${it.javaClass.simpleName}")
        }
        UserAskCenter.beginDeferred(ask)
        AppLogger.info(
            TAG,
            "[UserAsk] DEFERRED sid=${sessionId.take(8)} tool=${toolCallId.take(16)} questions=${request.questions.size} - card left open, run continues",
        )
        return ToolExecutionResult(UserAskDeferred.toolResult(request.questions.size), true, toolTitle = "")
    }

    suspend fun execute(
        argsJson: String,
        toolCallId: String,
        sessionId: String?,
        sessionTitle: String?,
        timeoutMinutes: Int,
        nowMs: Long = System.currentTimeMillis(),
        requestId: String = java.util.UUID.randomUUID().toString(),
        beforeShow: BeforeShow? = null,
    ): ToolExecutionResult {
        val request = UserAskParser.parse(argsJson).getOrElse {
            return ToolExecutionResult("Error: ${it.message}", false, toolTitle = "")
        }
        if (sessionId.isNullOrEmpty()) {
            return ToolExecutionResult(UserAskResolver.encode(UserAskResolver.unavailable(request.questions)), true, toolTitle = "")
        }
        val ask = PendingAsk.create(
            requestId, sessionId, toolCallId, request.questions, nowMs,
            UserAskSettingsSpec.clampTimeout(timeoutMinutes),
        ).copy(sessionTitle = sessionTitle)
        // [T-port-user-ask-fk-crash] Owned before the record exists: a recovery
        // scan must never treat this live ask as one left behind by a kill.
        UserAskCenter.claim(ask.requestId)
        // Persisted before the card can show it (iOS §12.3 step 1). A failure
        // to persist only costs recovery after a kill, never the question.
        runCatching { beforeShow?.onAsk(ask) }.onFailure {
            AppLogger.warning(TAG, "[UserAsk] record not saved for req=${ask.requestId.take(8)}: ${it.javaClass.simpleName}")
        }
        AppLogger.info(TAG, "[UserAsk] waiting_user ENTER sid=${sessionId.take(8)} tool=${toolCallId.take(16)}")
        UserAskCenter.begin(ask)
        val result = UserAskCenter.wait(ask.requestId)
        // Statuses and length only: the result carries the user's own words.
        AppLogger.info(
            TAG,
            "[UserAsk] waiting_user EXIT sid=${sessionId.take(8)} statuses=${UserAskResolver.statuses(result).map { it.wire }} resultLen=${result.length}",
        )
        return ToolExecutionResult(result, true, toolTitle = "")
    }
}
