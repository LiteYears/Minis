package com.openminis.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.openminis.app.BuildConfig
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.logging.AppLogger
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * Pure-logic policy that decides, given a token estimate and the model's
 * context window, whether the agent loop should offload big tool results,
 * trigger a compact/summarize pass, or surface "context exhausted" to the UI.
 *
 * Mirrors iOS `ContextPolicy` (ContextPolicy.swift): 4-tier thresholds keyed
 * off the model's context window, with no summarization algorithm embedded —
 * the actual compact + offload execution lives in the agent loop, this struct
 * only answers "what state are we in?".
 *
 * Thresholds intentionally leave headroom (10k/20k/40k below the ceiling) so
 * one more agent turn can still fit before the user sees any disruption.
 */
data class ContextPolicy(
    /** Above this token count, next tool result should be written to disk. 0 disables. */
    val offloadThreshold: Int,
    /** After offload, shrink context toward this target (lower than threshold). */
    val offloadTarget: Int,
    /** Above this, trigger a compact/summarize pass. 0 disables. */
    val compactThreshold: Int,
    /** When true, the tier is too small for auto-compact; only surface .exhausted. */
    val exhaustedOnly: Boolean,
    /** Whether the "Compact now" button is offered in the UI. */
    val manualCompactAllowed: Boolean,
) {
    enum class CheckResult { OK, NEEDS_COMPACT, EXHAUSTED }

    /**
     * Classify the current turn's token pressure. Priority:
     *   1. If compact is available and tokens crossed [compactThreshold] → NEEDS_COMPACT.
     *   2. If this is a small-window tier (`exhaustedOnly`) and we're past the
     *      offload line or 90% of the window → EXHAUSTED.
     *   3. Otherwise OK.
     */
    fun check(estimatedTokens: Int, contextWindow: Int): CheckResult {
        if (compactThreshold > 0 && estimatedTokens >= compactThreshold) {
            return CheckResult.NEEDS_COMPACT
        }
        // [T-ctx-overflow-hard-stop] Already at or past the ceiling. Whatever the
        // tier says about headroom economics is moot — the next request does not
        // fit. Compact if this tier can (manualCompactAllowed being the signal
        // that a summary is viable at all), otherwise report exhausted so the
        // caller prompts instead of sending a request that cannot succeed.
        // Without this, an exhaustedOnly tier returned OK up to its advisory line
        // and kept returning OK past 100%. iOS parity: ContextPolicy.swift.
        if (contextWindow > 0 && estimatedTokens >= contextWindow) {
            return if (manualCompactAllowed) CheckResult.NEEDS_COMPACT else CheckResult.EXHAUSTED
        }
        if (exhaustedOnly) {
            val exhaustLine = if (offloadThreshold > 0) offloadThreshold else (contextWindow * 9 / 10)
            if (estimatedTokens >= exhaustLine) return CheckResult.EXHAUSTED
        }
        return CheckResult.OK
    }

    /** Whether the next tool result should be offloaded to disk. */
    fun shouldOffload(estimatedTokens: Int): Boolean =
        offloadThreshold > 0 && estimatedTokens >= offloadThreshold

    /** What the agent loop does with its next request (see [inLoopStep]). */
    enum class InLoopStep {
        /** Under the compact line — send. */
        PROCEED,
        /** Compact in place, then re-check. */
        COMPACT,
        /** Compaction can do no more, but the request fits the window — send. */
        SEND_WITHIN_WINDOW,
        /** Over the window only by the calibrated extrapolation — send once for the provider to decide. */
        SEND_UNCALIBRATED_ONCE,
        /** Does not fit and nothing left to try. */
        STOP,
    }

    companion object {
        /**
         * [T-ctx-measure-outbound] The in-loop guard's decision table, shared
         * with iOS `ContextPolicy.inLoopStep`. Every branch that is not COMPACT
         * or STOP exists to keep a session from wedging: above the compact
         * line is still sendable, and an "over the window" that rests only on a
         * ratio (learned on other content, or borrowed from another model) gets
         * one real request, whose answer or rejection then corrects the ratio.
         *
         * [canCompact] = compaction budget left AND the last pass made progress.
         * [rawTokens] = the same request with no calibration applied.
         * A window of 0 (unknown) never stops a request.
         */
        fun inLoopStep(
            verdict: CheckResult,
            measured: Int,
            rawTokens: Int,
            window: Int,
            canCompact: Boolean,
            ratio: Double,
            uncalibratedSendUsed: Boolean,
        ): InLoopStep = when (verdict) {
            CheckResult.OK -> InLoopStep.PROCEED
            CheckResult.EXHAUSTED -> InLoopStep.STOP
            CheckResult.NEEDS_COMPACT -> when {
                canCompact -> InLoopStep.COMPACT
                window <= 0 || measured < window -> InLoopStep.SEND_WITHIN_WINDOW
                !uncalibratedSendUsed && ratio > 1.0 && rawTokens < window -> InLoopStep.SEND_UNCALIBRATED_ONCE
                else -> InLoopStep.STOP
            }
        }

        /**
         * Produce the policy for a given context window size. Four tiers:
         *   - `<32K`   → offload/compact disabled; UI tells user to start a new chat.
         *   - `32K–64K` → offload only; exhaust line = ctx − 10k.
         *   - `64K–128K` → offload + compact; headroom 10k for compact.
         *   - `≥128K`  → generous offload + compact; headroom 20k.
         */
        /**
         * [T-ctx-user-cap] A user-chosen group cap is NOT a model's native
         * window, and the tier table only makes sense for the latter. The fixed
         * 10k/20k/40k headroom subtractions — and disabling auto-compact under
         * 64K — exist because a genuinely small model cannot afford a summary
         * plus re-appended warm-up turns. When the user caps a 1M model at 32K
         * none of that holds: the model has room to run the compaction call, and
         * "keep this group under 32K" is an instruction to compact, not a reason
         * to stop compacting. iOS parity: ContextPolicy.swift `isUserCap`.
         */
        fun forUserCap(contextWindow: Int): ContextPolicy = ContextPolicy(
            offloadThreshold = (contextWindow * 0.70).toInt(),
            offloadTarget = (contextWindow * 0.55).toInt(),
            compactThreshold = (contextWindow * 0.85).toInt(),
            exhaustedOnly = false,
            manualCompactAllowed = true,
        )

        fun forContextWindow(contextWindow: Int): ContextPolicy = when {
            contextWindow < 32_000 -> ContextPolicy(
                offloadThreshold = 0,
                offloadTarget = 0,
                compactThreshold = 0,
                exhaustedOnly = true,
                manualCompactAllowed = false,
            )
            contextWindow < 64_000 -> ContextPolicy(
                offloadThreshold = contextWindow - 10_000,
                offloadTarget = contextWindow - 15_000,
                compactThreshold = 0,
                exhaustedOnly = true,
                manualCompactAllowed = true,
            )
            contextWindow < 128_000 -> ContextPolicy(
                offloadThreshold = contextWindow - 20_000,
                offloadTarget = contextWindow - 30_000,
                compactThreshold = contextWindow - 10_000,
                exhaustedOnly = false,
                manualCompactAllowed = true,
            )
            else -> ContextPolicy(
                offloadThreshold = contextWindow - 40_000,
                offloadTarget = contextWindow - 60_000,
                compactThreshold = contextWindow - 20_000,
                exhaustedOnly = false,
                manualCompactAllowed = true,
            )
        }
    }
}

/**
 * [T-ctx-measure-outbound] Sizes the request that is ABOUT to be sent, so every
 * capacity decision (compact, offload, `max_tokens`) judges the context the
 * model will actually receive. Mirrors iOS `ContextSizeMeter`
 * (ContextPolicy.swift) weight-for-weight.
 *
 * Why: the guards judged `_lastTurnContextTokens`, the provider's count for
 * the PREVIOUS request, which only another successful call ever replaced. After
 * a send-time or manual compaction nothing marked it stale, so the first check
 * of the next turn compacted again on the pre-compaction number — an extra
 * summary that folded the user's just-sent message in. After a revert it kept
 * the COMPACTED context's number and under-read the restored history. The
 * in-loop path papered over half of this with a one-shot "stale" flag.
 *
 * The provider's count is now used for what it is good at — calibration:
 *
 *     size(now) = ratio × (estimate(outbound history) + estimate(system prompt + tools))
 *
 * with `ratio = reported / estimate(that same request)`. Both sides describe
 * one request, so the ratio stays valid across compaction, offload, revert and
 * relaunch, and whatever changes the history changes the measurement at once.
 */
object ContextSizeMeter {

    /**
     * Token estimate by character class. Fitted against cl100k_base on
     * Chinese, Japanese, English prose, source code, JSON, git log and `ls`
     * output: 0.66–1.18x of the real count, versus 0.30x for CJK under a flat
     * chars/3.5. The residual is absorbed by the per-session calibration.
     */
    fun estimateTokens(text: String): Int {
        var letters = 0
        var digits = 0
        var asciiOther = 0
        var nonAscii = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when {
                cp >= 128 -> nonAscii++
                cp in 65..90 || cp in 97..122 -> letters++
                cp in 48..57 -> digits++
                else -> asciiOther++
            }
            i += Character.charCount(cp)
        }
        val tokens = letters / 4.5 + digits / 2.0 + asciiOther * 0.35 + nonAscii
        return kotlin.math.ceil(tokens).toInt()
    }

    // ── Wire framing [T-ctx-count-wire-framing] (port of iOS e48d29ee9) ─────
    //
    // A message is not only its text. On the wire every tool call carries its
    // id (twice: on the call and on the result), its arguments as a JSON
    // string INSIDE the JSON body (so every quote and backslash is escaped
    // again), and the keys and punctuation that frame the message.
    //
    // Measured on Android's own Chat Completions body (OpenAIProvider
    // .buildRequestBody, DeepSeek with reasoning echo; ContextWireFramingTest):
    // with the old 4 + 4 constants the body read 2.02x the estimate for 120
    // small tool rounds, 1.91x for a trimmed history and 1.08x for large
    // messages. That share grows with the number of messages, not with their
    // size, so the calibration ratio jumped at every trim - when the planner
    // relies on it most. iOS saw the same on a device (1.009 -> 1.194).
    //
    // The constants are the estimator applied to the compact Chat Completions
    // framing, which Android sends byte for byte like iOS; Anthropic's
    // tool_use / tool_result blocks are about the same size, and what differs
    // per provider stays with the calibration ratio.

    /** `{"role":"user","content":""}` */
    const val PER_MESSAGE_OVERHEAD = 8
    /** `"tool_calls":[{"id":"","type":"function","function":{"name":"","arguments":""}}]` */
    const val PER_TOOL_USE_OVERHEAD = 22
    /** What a tool message adds to a plain one: `"tool_call_id":""`. */
    const val PER_TOOL_RESULT_OVERHEAD = 6
    /** `"reasoning_content":""` on an assistant turn, sent even when empty. */
    const val REASONING_FIELD_OVERHEAD = 6

    /**
     * Extra tokens a string costs once it is a JSON string value: each quote,
     * backslash and newline / CR / tab gains a backslash (x 0.35, the
     * estimator's rate for ASCII punctuation). iOS `wireEscapeTokens`.
     */
    fun wireEscapeTokens(text: String): Int {
        var escaped = 0
        for (c in text) if (c == '"' || c == '\\' || c == '\n' || c == '\r' || c == '\t') escaped++
        return kotlin.math.ceil(escaped * 0.35).toInt()
    }

    /** A string as it is sent: its tokens plus its escaping. iOS `estimateWireTokens`. */
    fun estimateWireTokens(text: String): Int =
        if (text.isEmpty()) 0 else estimateTokens(text) + wireEscapeTokens(text)

    fun estimateTokens(part: AgentContentPart): Int = when (part) {
        is AgentContentPart.Text -> estimateWireTokens(part.text)
        // The arguments travel as a JSON string inside the body: their own
        // JSON (input.toString()) escaped once more.
        is AgentContentPart.ToolUse ->
            estimateTokens(part.name) + estimateTokens(part.id) + estimateWireTokens(part.input.toString()) +
                PER_TOOL_USE_OVERHEAD
        // The tool's name is not repeated on the result; its id is.
        is AgentContentPart.ToolResult ->
            estimateWireTokens(part.content) + estimateTokens(part.id) + PER_TOOL_RESULT_OVERHEAD +
                (part.imageData?.let { imageTokens(it) } ?: 0)
        is AgentContentPart.ImageData -> imageTokens(part.data)
    }

    /**
     * Providers serialise `contentParts` when present and `content` otherwise.
     *
     * [T-ctx-count-reasoning] [includeReasoning]: the provider replays
     * `reasoningContent` on every past assistant turn (DeepSeek / Chat
     * Completions echo, unsigned interleaved thinking), so it is part of the
     * request and must be counted. Leaving it out made a 341,873-token request
     * read as 146,874 (field run 2026-10-07): compaction triggered late and
     * believed it had freed space it had not.
     */
    fun estimateTokens(message: LLMMessage, includeReasoning: Boolean = false): Int {
        val body = if (message.contentParts.isNotEmpty()) {
            message.contentParts.sumOf { estimateTokens(it) }
        } else {
            estimateWireTokens(message.content)
        }
        // [T-ctx-count-wire-framing] The field costs even when empty: an
        // assistant turn with reasoningContent = "" is sent as
        // `"reasoning_content":""`. Null is not sent. (The estimate cache key
        // already tells "" from null: `|rc0` vs `|rc-1`.)
        val reasoning = if (includeReasoning && message.role == LLMMessage.Role.ASSISTANT) {
            message.reasoningContent?.let { estimateWireTokens(it) + REASONING_FIELD_OVERHEAD } ?: 0
        } else {
            0
        }
        return PER_MESSAGE_OVERHEAD + body + reasoning + message.imageParts.sumOf { imageTokens(it.data) }
    }

    /**
     * Persisted messages are cached: without it every measurement re-scans the
     * whole history several times per iteration (debug builds run interpreted).
     * The key carries the content SHAPE as well as the id, because offload
     * rewrites a persisted message in place — a changed length or offload flag
     * is a new key, so a shrunk message is re-measured. In-flight messages (no
     * dbMessageId yet) are always measured.
     */
    fun estimateTokens(messages: List<LLMMessage>, includeReasoning: Boolean = false): Int =
        messages.sumOf { estimateCached(it, includeReasoning) }

    private fun estimateCached(message: LLMMessage, includeReasoning: Boolean = false): Int {
        val id = message.dbMessageId ?: return estimateTokens(message, includeReasoning)
        val shape = buildString {
            append(id).append('|').append(message.content.length).append('|').append(message.contentParts.size)
            // [T-ctx-count-reasoning] The trim ladder empties reasoning in place.
            if (includeReasoning) append("|rc").append(message.reasoningContent?.length ?: -1)
            for (part in message.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> append("|t").append(part.text.length)
                    // [T-ctx-trim-ladder] Serialized length too: shrinking an argument keeps its key count.
                    is AgentContentPart.ToolUse -> append("|u").append(part.input.length()).append(':').append(part.input.toString().length)
                        .append(if (part.isOffloadedArgument) "o" else "")
                    is AgentContentPart.ToolResult -> append("|r").append(part.content.length).append('/').append(part.imageData?.size ?: 0)
                    is AgentContentPart.ImageData -> append("|i").append(part.data.size)
                }
            }
            for (img in message.imageParts) append("|p").append(img.data.size)
        }
        synchronized(messageEstimateCache) { messageEstimateCache[shape]?.let { return it } }
        val tokens = estimateTokens(message, includeReasoning)
        synchronized(messageEstimateCache) { messageEstimateCache[shape] = tokens }
        return tokens
    }

    private val messageEstimateCache = object : LinkedHashMap<String, Int>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 20_000
    }

    /** System prompt plus tool schemas — the part the history-only estimate omitted. */
    fun estimateFixedTokens(systemPrompt: String?, tools: List<AgentToolDefinition>): Int {
        var total = systemPrompt?.let { estimateTokens(it) } ?: 0
        for (tool in tools) {
            total += estimateTokens(tool.name) + estimateTokens(tool.description) + 10
            for ((name, param) in tool.parameters) {
                total += estimateTokens(name) + estimateTokens(param.description) + 4
                param.enumValues?.forEach { total += estimateTokens(it) + 1 }
                // [T-port-nested-schema] A nested schema's text is sent too.
                param.schema?.let { total += estimateTokens(it.estimationText) + 8 }
            }
        }
        return total
    }

    /**
     * Bounds on reported / estimated. The floor is tight on purpose: the
     * estimator's worst measured over-read is 1.18x, so a genuine ratio never
     * falls much below 0.85; a lower one means an under-reporting upstream, and
     * following it down would let an over-length request through. The ceiling
     * is loose because under-reads are real (denser tokenizers, and content we
     * do not measure such as reasoning echoes).
     */
    const val CALIBRATION_MIN = 0.8
    // [T-ctx-count-reasoning] Was 3.0. With reasoning counted the learned ratio
    // sits near 1.0-1.3; the wider ceiling only lets a meter that still misses
    // something converge on the provider's number instead of stopping at 3x.
    const val CALIBRATION_MAX = 8.0

    fun calibrationRatio(reported: Int, estimated: Int): Double? {
        if (reported <= 0 || estimated <= 0) return null
        return (reported.toDouble() / estimated).coerceIn(CALIBRATION_MIN, CALIBRATION_MAX)
    }

    fun calibrated(estimated: Int, ratio: Double): Int = kotlin.math.ceil(estimated * ratio).toInt()

    /**
     * Applied when the current model has no calibration of its own and we are
     * borrowing another model's ratio: tokenizers differ by roughly 10–30%, so
     * judging a newly selected model by the previous one's ratio under-reads it.
     * Leaning high for that one request costs at worst an early compaction; its
     * first response replaces the borrowed ratio.
     */
    const val UNCALIBRATED_MODEL_MARGIN = 1.2

    /** Where [ratioFor] got its answer, for logs. */
    fun ratioSource(modelId: String?, known: Map<String, Double>, lastLearned: Double?): String = when {
        modelId != null && known.containsKey(modelId) -> "own"
        lastLearned == null -> "default"
        else -> "borrowed"
    }

    /**
     * [T-ctx-ratio-smoothing] Share of the gap closed per sample when a sample
     * says the ratio should come DOWN (recent samples weigh 0.3, 0.21, 0.147…).
     */
    const val CALIBRATION_FALL_RATE = 0.3

    /**
     * Fold one sample into a model's ratio — an asymmetric weighted average.
     * Mirrors iOS `ContextSizeMeter.smoothed`. A too-LOW ratio under-reads and
     * can send a request over the window; a too-high one only compacts early.
     * So a higher sample applies at once, and a lower one moves the ratio only
     * part of the way: one anomalous under-count barely moves it, a real drop
     * settles within 3–4 responses.
     */
    fun smoothed(previous: Double?, sample: Double): Double {
        if (previous == null || sample >= previous) return sample
        return previous + CALIBRATION_FALL_RATE * (sample - previous)
    }

    /** The model's own ratio; else the newest learned one with the margin (never below 1.0); else 1.0. */
    fun ratioFor(modelId: String?, known: Map<String, Double>, lastLearned: Double?): Double {
        if (modelId != null) known[modelId]?.let { return it }
        val last = lastLearned ?: return 1.0
        return (maxOf(last, 1.0) * UNCALIBRATED_MODEL_MARGIN).coerceAtMost(CALIBRATION_MAX)
    }

    /**
     * The token count a context-length rejection says the request had, when it
     * states one: the larger of the numbers (the request overflowed the limit).
     * Numbers under 1000 are status codes and the like.
     */
    fun requestedTokens(overflowMessage: String): Int? {
        val cleaned = overflowMessage.replace(Regex("(?<=\\d),(?=\\d{3})"), "")
        return Regex("\\d{4,}").findAll(cleaned).mapNotNull { it.value.toIntOrNull() }.filter { it >= 1000 }.maxOrNull()
    }

    /**
     * Ratio after a rejection: the stated count ÷ our estimate of that request
     * (trusted only within 0.9–4x of the window, so a request id cannot pin the
     * ratio at its ceiling), else just past the window. Never lowered.
     */
    fun ratioAfterOverflow(current: Double, estimated: Int, requested: Int?, window: Int): Double {
        if (estimated <= 0) return current
        val plausible = requested?.takeIf { window <= 0 || (it >= window * 0.9 && it <= window * 4.0) }
        val target = plausible?.toDouble() ?: (maxOf(window, 1) * 1.02)
        return maxOf(current, target / estimated).coerceAtMost(CALIBRATION_MAX)
    }

    // ── Session calibration: replay and reload ────────────────────────────

    /** One persisted (report, estimate) pair — a usage row. Same keys as iOS StoredTokenUsage. */
    data class CalibrationSample(val reported: Int, val estimated: Int, val fixedTokens: Int, val modelId: String?)

    /** A session's calibration: per-model ratios, the newest learned one, and the fixed share. */
    data class CalibrationState(
        val ratios: Map<String, Double> = emptyMap(),
        val lastLearned: Double? = null,
        val fixedTokens: Int = 0,
        val samples: Int = 0,
    ) {
        /**
         * Reloading the SAME session keeps what was learned in memory: a ratio
         * raised by a rejection exists nowhere else (a rejected request stamps
         * no pair), and in-memory values are never older than the rows'. So
         * they win, key by key; the rows fill in models memory has not seen.
         */
        fun carryingOver(learned: CalibrationState): CalibrationState = copy(
            ratios = ratios + learned.ratios,
            lastLearned = learned.lastLearned ?: lastLearned,
            fixedTokens = if (learned.fixedTokens > 0) learned.fixedTokens else fixedTokens,
        )
    }

    /** The pair in a usage row's JSON, or null for a row written before the pair existed. */
    /**
     * [T-ctx-count-reasoning] Version of the estimate a stored sample was taken
     * with. v2 counts replayed reasoning; a v1 ratio (reported / an estimate
     * that missed the reasoning, 3-7x on DeepSeek) multiplied onto a v2
     * estimate double-counts it - field 2026-10-07: a 22K request read as
     * 229K and every turn stopped as "context full". Older rows are skipped.
     *
     * [T-ctx-estimator-version-exact] Rows with a NEWER version are skipped
     * too: a ratio is only valid for the estimator that produced it, in
     * either direction.
     *
     * [T-ctx-count-wire-framing] 3 = plus wire framing (call ids, JSON-string
     * escaping, per-message and per-tool-call keys), the same estimate iOS
     * stamps 3 since e48d29ee9. A v2 ratio already contains the framing this
     * estimate now counts (2x for small tool rounds), so v2 rows are skipped
     * like v1; iOS v3 rows from a synced session are now replayed.
     */
    const val ESTIMATOR_VERSION = 3

    fun calibrationSample(usageJson: String): CalibrationSample? {
        val o = runCatching { org.json.JSONObject(usageJson) }.getOrNull() ?: return null
        if (o.optInt("estimatorVersion", 1) != ESTIMATOR_VERSION) return null
        val reported = o.optInt("latestContextTokens", 0)
        val estimated = o.optInt("estimatedRequestTokens", 0)
        if (reported <= 0 || estimated <= 0) return null
        val model = o.optString("calibrationModelId", "").takeIf { it.isNotEmpty() }
        return CalibrationSample(reported, estimated, o.optInt("estimatedFixedTokens", 0), model)
    }

    /**
     * [T-ctx-ratio-smoothing] Rebuild calibration from persisted samples in
     * conversation order, through the same [smoothed] rule the live path
     * uses, so a reload reproduces the ratio the session had in memory rather
     * than jumping to the newest raw sample. A sample with no model id (older
     * rows) informs only [CalibrationState.lastLearned].
     */
    fun replayCalibration(samples: List<CalibrationSample>): CalibrationState {
        val ratios = mutableMapOf<String, Double>()
        var last: Double? = null
        var fixed = 0
        var count = 0
        for (s in samples) {
            val sample = calibrationRatio(s.reported, s.estimated) ?: continue
            count++
            last = if (s.modelId != null) smoothed(ratios[s.modelId], sample).also { ratios[s.modelId] = it } else sample
            fixed = s.fixedTokens
        }
        return CalibrationState(ratios, last, fixed, count)
    }

    /**
     * [T-ctx-warmup-fit] How many leading warm-up messages to drop so that
     * warm-up + [restTokens] is under [budget]. Drops whole turns: after each
     * cut it keeps dropping until the slice starts on a user-TEXT message
     * ([startsTurn]), so a tool call is never separated from its result.
     * 0 when it already fits; [sizes].size when nothing does.
     */
    fun warmUpDrop(sizes: List<Int>, startsTurn: List<Boolean>, restTokens: Int, budget: Int): Int {
        require(sizes.size == startsTurn.size)
        var total = sizes.sum()
        var drop = 0
        while (drop < sizes.size && total + restTokens >= budget) {
            total -= sizes[drop++]
            while (drop < sizes.size && !startsTurn[drop]) total -= sizes[drop++]
        }
        return drop
    }

    /**
     * `countImageTokens` decodes the image header; the measurement runs
     * several times per loop iteration, so results are cached by a cheap
     * content fingerprint.
     */
    private val imageTokenCache = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 256
    }

    fun imageTokens(data: ByteArray): Int {
        val head = data.copyOfRange(0, minOf(64, data.size)).contentHashCode()
        val tail = data.copyOfRange(maxOf(0, data.size - 64), data.size).contentHashCode()
        val key = "${data.size}-$head-$tail"
        synchronized(imageTokenCache) { imageTokenCache[key]?.let { return it } }
        val tokens = BPETokenizer.countImageTokens(data)
        synchronized(imageTokenCache) { imageTokenCache[key] = tokens }
        return tokens
    }
}

// ─── Merged from SessionForkManager.kt ──────────────────────────────────────────────────

/**
 * Clone a session (or its skill/memory artifacts) into a fresh local session.
 * Mirrors iOS `SessionForkManager`. Android MVP only implements the **local**
 * branch of the iOS API — `forkSession` (from iCloud-mirrored remote devices)
 * is deferred until we ship a cross-device sync layer; see spec §7.8.
 *
 * The returned session carries "(Copy)" suffix and entirely new message ids,
 * so the duplicate and its source are independent. Compact markers ARE cloned
 * (T-session-duplicate-compact-marker-android, iOS parity e8ac8b82) with their
 * message-id references remapped onto the duplicated messages, so the compact
 * divider/shadow is preserved on the copy.
 */
class SessionForkManager(
    private val chatRepository: ChatRepository,
    private val skillRepository: SkillRepository? = null,
    private val filesDir: File,
) {
    companion object {
        private const val TAG = "SessionForkManager"
    }

    /**
     * Deep-copy a local session: creates a new session with the same model id
     * and copies every message's payload. Returns the new session's id or
     * null if [sessionId] doesn't exist.
     *
     * Carries over user-meaningful session metadata so the duplicate looks
     * and behaves like the source:
     *   - `category` — drives the session-list icon + colour swatch (see
     *     SessionListScreen.categoryStyle). The bug that motivated this fix:
     *     a "Coding" session duplicated as a generic uncategorised chat with
     *     a default icon. iOS duplicateSession copies this too.
     *   - `modelBinding` — user-pinned model override; if the source session
     *     was pinned to a specific model, the copy should keep it pinned.
     *   - `memoryEnabled` — user-toggled per-session; expectation is that
     *     the copy inherits the source's memory setting rather than
     *     defaulting to on.
     *
     * Deliberately NOT copied (the duplicate is a new session at creation
     * time, not a clone of the source's lifecycle):
     *   - `pinnedAt` (the copy starts unpinned)
     *   - `source` (origin tag like "shortcut" — the copy was made via the
     *     duplicate button, not via the original entry point)
     *   - `editCount` (starts at 0)
     *   - `lastMessage` (createSession seeds it; refreshed by appendMessage)
     *   - `id` / `createdAt` / `updatedAt` (new identity, current timestamps)
     */
    suspend fun duplicateSession(sessionId: String): String? {
        val source = chatRepository.getSession(sessionId) ?: run {
            AppLogger.warning(TAG, "duplicateSession: $sessionId not found")
            return null
        }
        val messages = chatRepository.loadMessages(sessionId)
        val dupTitle = "${source.title ?: "Chat"} (Copy)"
        val new = chatRepository.createSession(
            modelId = source.modelId,
            title = dupTitle,
        )
        // Mirror iOS: carry over the source's category onto the copy so the
        // session-list icon + colour stay consistent with the original.
        if (source.category != null) {
            chatRepository.updateSessionTitleAndCategory(new.id, dupTitle, source.category)
        }
        // Carry over the user's per-session model binding (the "pinned model"
        // override) so duplicating a coding session pinned to GPT-5 stays
        // pinned to GPT-5 in the copy.
        if (source.modelBinding != null) {
            chatRepository.updateSessionBinding(new.id, source.modelBinding, source.modelId)
        }
        // Carry over the per-session memory toggle. Sessions default
        // memoryEnabled=1, so we only need to push the disabled state through;
        // the matching state is already in place from createSession.
        if (source.memoryEnabled == 0) {
            chatRepository.dao.updateMemoryEnabled(new.id, 0)
        }
        // T239: carry over the per-session thinking-mode override so a
        // duplicate of a "tuned to MEDIUM" session opens at MEDIUM rather
        // than reverting to the default (parity with memoryEnabled).
        source.thinkingOverride?.let { override ->
            chatRepository.dao.updateThinkingOverride(new.id, override)
        }
        // [T-session-duplicate-compact-marker-android] Track old→new message id
        // and old→new sort_order so copied compact markers (which reference DB
        // message ids + legacy sort_orders) can be remapped onto the freshly
        // minted duplicate messages. appendMessage returns the new MessageEntity
        // (id + sortOrder), so no re-query is needed. Aligns iOS e8ac8b82.
        val oldToNewId = HashMap<String, String>()
        val oldToNewSort = HashMap<String, Int>()
        for (msg in messages) {
            val newMsg = chatRepository.appendMessage(
                sessionId = new.id,
                role = msg.role,
                partsJson = msg.partsJson,
                // [T-usage-step1] Marked copied so the Usage page does not count it twice.
                tokenUsage = com.openminis.app.usage.StoredUsageJson.markCopied(msg.tokenUsage),
                reasoningContent = msg.reasoningContent,
            )
            oldToNewId[msg.id] = newMsg.id
            oldToNewSort[msg.id] = newMsg.sortOrder
        }

        // [T-session-duplicate-compact-marker-android] Copy compact markers
        // AFTER all messages are appended (so the new sort_orders exist). A
        // session with zero markers makes this a clean no-op — the normal
        // duplicate path is untouched. Each marker is remapped independently so
        // multi-compact sessions copy all dividers, in created_at order
        // (listCompactMarkers returns ASC by created_at).
        val markers = chatRepository.dao.listCompactMarkers(sessionId)
        if (markers.isNotEmpty()) {
            // Remap a referenced message id through the copy map. null → null.
            fun remapId(id: String?): String? = id?.let { oldToNewId[it] }
            // Resolve a legacy sort_order fallback to the NEW message's
            // sort_order via the referenced id; keep the original when the id
            // is null or can't be resolved (legacy markers without ids).
            fun remapSort(messageId: String?, original: Int): Int =
                messageId?.let { oldToNewSort[it] } ?: original
            var copied = 0
            for (marker in markers) {
                // All source messages are copied, so every non-null id field
                // MUST resolve. If one doesn't, the source marker is already
                // dangling — skip it rather than write a dangling copy.
                val firstKeptNew = remapId(marker.firstKeptMessageId)
                val lastCompactedNew = remapId(marker.lastCompactedMessageId)
                val boundaryNew = remapId(marker.boundaryMessageId)
                if ((marker.firstKeptMessageId != null && firstKeptNew == null) ||
                    (marker.lastCompactedMessageId != null && lastCompactedNew == null) ||
                    (marker.boundaryMessageId != null && boundaryNew == null)
                ) {
                    AppLogger.warning(
                        TAG,
                        "duplicateSession: skipping compact marker ${marker.id} — a referenced message id is not in the copy map (dangling source marker)",
                    )
                    continue
                }
                val copy = marker.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    sessionId = new.id,
                    firstKeptSortOrder = remapSort(marker.firstKeptMessageId, marker.firstKeptSortOrder),
                    uiBoundarySortOrder = marker.uiBoundarySortOrder?.let {
                        remapSort(marker.boundaryMessageId, it)
                    },
                    boundaryMessageId = boundaryNew,
                    firstKeptMessageId = firstKeptNew,
                    lastCompactedMessageId = lastCompactedNew,
                    // summary / compactedCount / createdAt / version verbatim.
                )
                chatRepository.dao.insertCompactMarker(copy)
                copied++
            }
            AppLogger.info(TAG, "duplicateSession: copied $copied/${markers.size} compact marker(s) to ${new.id}")
        }

        AppLogger.info(
            TAG,
            "duplicated session $sessionId → ${new.id} (${messages.size} msgs, " +
                "category=${source.category}, modelBinding=${source.modelBinding}, " +
                "memoryEnabled=${source.memoryEnabled})",
        )
        return new.id
    }

    /**
     * Clone a skill into the local library. When [skillRepository] is wired,
     * reuses `importFromContent` so the skill lands in DB + `SKILL.md` just
     * like a fresh import. Returns whether the import succeeded.
     */
    fun copySkill(content: String, source: SkillRepository.ImportSource = SkillRepository.ImportSource.FILE): Boolean {
        val repo = skillRepository ?: run {
            AppLogger.warning(TAG, "copySkill: SkillRepository not injected")
            return false
        }
        return repo.importFromContent(content, source) != null
    }

    /**
     * Persist a memory note (plain Markdown / text) under
     * `<filesDir>/minis-global/memory/<fileName>`. Mirrors iOS
     * `SessionForkManager.copyRemoteMemory` which writes under
     * `minisMemoryPersistentDir`. Overwrites if the file already exists.
     */
    fun copyMemory(fileName: String, content: String): Boolean {
        if (fileName.contains("/") || fileName.contains("..")) {
            AppLogger.warning(TAG, "copyMemory: rejecting unsafe fileName '$fileName'")
            return false
        }
        val dir = File(filesDir, "minis-global/memory").apply { mkdirs() }
        return try {
            File(dir, fileName).writeText(content)
            true
        } catch (e: Exception) {
            AppLogger.warning(TAG, "copyMemory: write failed: ${e.message}")
            false
        }
    }
}

/** Convenience: default-wire against application context. */
fun SessionForkManager(
    context: Context,
    chatRepository: ChatRepository,
    skillRepository: SkillRepository? = null,
): SessionForkManager = SessionForkManager(
    chatRepository = chatRepository,
    skillRepository = skillRepository,
    filesDir = context.filesDir,
)

// ─── Merged from UpdateChecker.kt ──────────────────────────────────────────────────

/**
 * Checks GitHub releases for a build newer than the running one and
 * coordinates download → install. iOS has no equivalent (sideloading is not
 * permitted) so this is Android-only.
 *
 * [T-update-channels] One repo, [BuildConfig.RELEASE_REPO]; two channels:
 *  - stable: non-prerelease releases only (tags `X.Y`);
 *  - beta: everything, including the prereleases tagged `0-beta-build<N>`.
 * versionCode (the shared build counter, from the APK name's `-build<N>-` -
 * the file name is the protocol, there is no latest.json) decides what is
 * newer; see [UpdatePolicy] for every rule and
 * [UpdateStateStore] for the cache, throttle and badge this writes.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val DOWNLOAD_FILENAME = "minis-update.apk"
    /**
     * Sub-directory of `filesDir` where we stage downloaded update APKs. We
     * moved off `cacheDir/shared/` (the original location) so the OS can't
     * evict a freshly-downloaded APK between the moment we hand the user off
     * to "install unknown apps" settings and the moment they return — the
     * eviction was a contributing factor to the "re-download after grant"
     * bug. See [PendingUpdateStore]. Exposed via `file_provider_paths.xml`
     * `<files-path name="updates" path="updates/" />`.
     */
    private const val UPDATES_DIR = "updates"

    /** Releases per page; pages are fetched until one comes back short. */
    private const val PER_PAGE = 100
    private const val MAX_PAGES = 5

    sealed class CheckResult {
        data class UpdateAvailable(
            val tagName: String,
            val versionName: String,
            val versionCode: Int?,
            val releaseName: String,
            val changelog: String,
            val apkUrl: String,
            val apkSizeBytes: Long,
            val sha256: String?,
            val releaseUrl: String,
        ) : CheckResult()
        data object UpToDate : CheckResult()
        // The repo has zero usable releases (or 404'd entirely).
        data object NoReleaseAvailable : CheckResult()
        // A newer release exists but carries no APK for this device. Distinct
        // from NoReleaseAvailable so the UI can say "newer release exists,
        // but it didn't ship an APK" instead of misleading "no release yet".
        data class NoApkAsset(val tagName: String) : CheckResult()
        data class Error(val message: String) : CheckResult()
        // GitHub returned 403 / 451 — usually a geo-block or rate-limit in CN
        // without a VPN. UI surfaces a hint with a clickable Releases link.
        data object Forbidden : CheckResult()
        // DNS / connect / read timeout — network unreachable. UI nudges the
        // user to check connectivity and retry.
        data object NetworkUnreachable : CheckResult()
    }

    sealed class DownloadResult {
        data class Success(val file: File) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
    }

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // [FEAT-261021-16] Follows the default route in Settings > Network; the
    // same client object when nothing is configured.
    private val client: OkHttpClient get() = com.openminis.app.network.NetworkHttp.client(com.openminis.app.network.NetworkHttp.DEFAULT_TAG, baseClient)

    /** One check at a time; an automatic check never starts while any is running. */
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    val isChecking: Boolean get() = inFlight.get()

    private fun UpdateStateStore.Available.toResult() = CheckResult.UpdateAvailable(
        tagName = tag, versionName = versionName, versionCode = versionCode, releaseName = releaseName,
        changelog = changelog, apkUrl = apkUrl, apkSizeBytes = apkSize, sha256 = sha256, releaseUrl = releaseUrl,
    )

    private class HttpFailure(val result: CheckResult) : Exception()

    /**
     * Check the current channel now, whatever the throttle says (a manual
     * check, or an automatic one [UpdateAutoChecker] decided to run), and
     * write the outcome into [UpdateStateStore]: a 200 or a 304 is a success,
     * anything else only stamps the attempt.
     *
     * All network work happens on [Dispatchers.IO]; safe to call from any
     * coroutine scope.
     */
    suspend fun check(context: Context, reason: String = "manual"): CheckResult = withContext(Dispatchers.IO) {
        UpdateStateStore.init(context)
        if (!inFlight.compareAndSet(false, true)) {
            AppLogger.info(TAG, "[UpdateCheck] $reason: a check is already running")
            // Wait for it rather than firing a second request.
            while (inFlight.get()) kotlinx.coroutines.delay(100)
            return@withContext UpdateStateStore.available(context)?.toResult() ?: CheckResult.UpToDate
        }
        try {
            runCheck(context, reason)
        } finally {
            inFlight.set(false)
        }
    }

    private fun runCheck(context: Context, reason: String): CheckResult {
        val channel = UpdateStateStore.channel.value
        val localCode = BuildConfig.VERSION_CODE
        val localName = BuildConfig.VERSION_NAME
        val now = System.currentTimeMillis()
        UpdateStateStore.beginAttempt(context, now, channel, localCode)
        val etag = UpdateStateStore.etag(context)
        AppLogger.info(
            TAG,
            "[UpdateCheck] request reason=$reason channel=${channel.id} local=$localName ($localCode) repo=${BuildConfig.RELEASE_REPO} etag=${etag != null}",
        )
        return try {
            val page1 = fetchPage(1, etag)
            if (page1 == null) {
                // 304: nothing changed since the cached result.
                AppLogger.info(TAG, "[UpdateCheck] 304 not modified")
                UpdateStateStore.markSuccess(context, now, etag, null, notModified = true)
                return UpdateStateStore.available(context)?.toResult() ?: CheckResult.UpToDate
            }
            val releases = page1.second.toMutableList()
            var page = 1
            var lastSize = page1.second.size
            while (lastSize >= PER_PAGE && page < MAX_PAGES) {
                page++
                val more = fetchPage(page, null)?.second ?: break
                releases += more
                lastSize = more.size
            }
            decide(context, now, page1.first, releases, channel, localName, localCode)
        } catch (f: HttpFailure) {
            f.result
        } catch (e: UnknownHostException) {
            AppLogger.error(TAG, "check failed: UnknownHostException: ${e.message}")
            CheckResult.NetworkUnreachable
        } catch (e: ConnectException) {
            AppLogger.error(TAG, "check failed: ConnectException: ${e.message}")
            CheckResult.NetworkUnreachable
        } catch (e: SocketTimeoutException) {
            AppLogger.error(TAG, "check failed: SocketTimeoutException: ${e.message}")
            CheckResult.NetworkUnreachable
        } catch (e: IOException) {
            // Catch-all for okhttp connection plumbing (e.g.
            // "failed to connect", SSL handshake errors). Most of these in
            // the CN-no-VPN scenario are effectively "can't reach github".
            AppLogger.error(TAG, "check failed: ${e.javaClass.simpleName}: ${e.message}")
            CheckResult.NetworkUnreachable
        } catch (e: Exception) {
            AppLogger.error(TAG, "check failed: ${e.javaClass.simpleName}: ${e.message}")
            CheckResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * One page of the release list, newest first: (ETag, releases), or null
     * for a 304. A 404 is an empty list.
     */
    private fun fetchPage(page: Int, etag: String?): Pair<String?, List<UpdatePolicy.Release>>? {
        val url = "https://api.github.com/repos/${BuildConfig.RELEASE_REPO}/releases?per_page=$PER_PAGE&page=$page"
        val b = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
        if (etag != null) b.header("If-None-Match", etag)
        client.newCall(b.build()).execute().use { resp ->
            AppLogger.info(TAG, "GET releases page=$page HTTP ${resp.code}")
            if (resp.code == 304) return null
            if (resp.code == 404) return null to emptyList()
            // 403 = rate-limit or geo-blocked. 451 = legal block. Both map to
            // the same "open Releases in browser" hint.
            if (resp.code == 403 || resp.code == 451) {
                AppLogger.warning(TAG, "GitHub API ${resp.code} — geo-block or rate-limit")
                throw HttpFailure(CheckResult.Forbidden)
            }
            if (!resp.isSuccessful) {
                AppLogger.warning(TAG, "GitHub API ${resp.code}")
                throw HttpFailure(CheckResult.Error("GitHub API ${resp.code}"))
            }
            val body = resp.body?.string() ?: throw HttpFailure(CheckResult.Error("empty body"))
            val arr = runCatching { JSONArray(body) }.getOrNull() ?: throw HttpFailure(CheckResult.Error("bad JSON"))
            return resp.header("ETag") to parseReleases(arr)
        }
    }

    internal fun parseReleases(arr: JSONArray): List<UpdatePolicy.Release> {
        val out = mutableListOf<UpdatePolicy.Release>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val tag = r.optString("tag_name")
            if (tag.isEmpty()) continue
            val assets = mutableListOf<UpdatePolicy.Asset>()
            val ja = r.optJSONArray("assets")
            for (j in 0 until (ja?.length() ?: 0)) {
                val a = ja!!.optJSONObject(j) ?: continue
                val url = a.optString("browser_download_url").ifEmpty { null } ?: continue
                // GitHub reports "digest": "sha256:<hex>" for every asset; absent -> no check.
                val sha = UpdatePolicy.parseDigest(a.optString("digest", "").ifEmpty { null })
                assets += UpdatePolicy.Asset(a.optString("name"), url, a.optLong("size", 0), sha)
            }
            out += UpdatePolicy.Release(
                tag = tag,
                name = r.optString("name").ifEmpty { tag },
                prerelease = r.optBoolean("prerelease", false),
                draft = r.optBoolean("draft", false),
                body = r.optString("body", ""),
                assets = assets,
                htmlUrl = r.optString("html_url", ""),
            )
        }
        return out
    }

    private fun decide(
        context: Context,
        now: Long,
        etag: String?,
        releases: List<UpdatePolicy.Release>,
        channel: UpdatePolicy.Channel,
        localName: String,
        localCode: Int,
    ): CheckResult {
        val abis = Build.SUPPORTED_ABIS.toList()
        val eligible = releases.filter { UpdatePolicy.eligible(it, channel) }
        AppLogger.info(TAG, "[UpdateCheck] releases=${releases.size} eligible=${eligible.size} channel=${channel.id}")
        if (eligible.isEmpty()) {
            UpdateStateStore.markSuccess(context, now, etag, null, notModified = false)
            return CheckResult.NoReleaseAvailable
        }
        val candidates = eligible.mapNotNull { UpdatePolicy.toCandidate(it, abis) }
        val chosen = UpdatePolicy.pickUpdate(candidates, channel, localName, localCode)
        if (chosen != null) {
            val available = UpdateStateStore.Available(
                tag = chosen.tag, versionName = chosen.versionName, versionCode = chosen.versionCode,
                apkUrl = chosen.apk.url, apkSize = chosen.apk.size, sha256 = chosen.apk.sha256,
                changelog = chosen.changelog, releaseName = chosen.releaseName, releaseUrl = chosen.htmlUrl,
            )
            UpdateStateStore.markSuccess(context, now, etag, available, notModified = false)
            AppLogger.info(
                TAG,
                "[UpdateCheck] update available: ${UpdatePolicy.displayVersion(localName, localCode)} -> " +
                    "${UpdatePolicy.displayVersion(chosen.versionName, chosen.versionCode)} tag=${chosen.tag} apk=${chosen.apk.name}",
            )
            return available.toResult()
        }
        UpdateStateStore.markSuccess(context, now, etag, null, notModified = false)
        // A newer stable by name that ships no APK for this device.
        val noApk = eligible.firstOrNull { r ->
            !r.prerelease && UpdatePolicy.compareVersionNames(r.tag, localName) > 0 &&
                UpdatePolicy.chooseApk(r.assets, abis) == null
        }
        if (noApk != null && channel == UpdatePolicy.Channel.STABLE) {
            AppLogger.info(TAG, "[UpdateCheck] ${noApk.tag} is newer but has no APK for $abis")
            return CheckResult.NoApkAsset(noApk.tag)
        }
        AppLogger.info(TAG, "[UpdateCheck] up to date: ${UpdatePolicy.displayVersion(localName, localCode)} channel=${channel.id}")
        return CheckResult.UpToDate
    }

    /** Public so UI can deep-link users to manual download when GitHub is blocked. */
    const val RELEASES_URL: String = "https://github.com/OpenMinis/OpenMinis/releases"

    /**
     * Stream the APK from [url] into `${cacheDir}/shared/minis-update.apk`,
     * surfacing progress (0..1) through [onProgress] roughly every 64 KiB.
     * Returns the on-disk [File] on success so the caller can hand it to
     * [installApk]. The path is intentionally inside `shared/` because that's
     * the only sub-directory of cacheDir already exposed by FileProvider in
     * `file_provider_paths.xml`.
     */
    suspend fun download(
        context: Context,
        url: String,
        versionName: String? = null,
        versionCode: Int? = null,
        // [T-update-channels] GitHub's asset digest; checked when present, skipped when not.
        expectedSha256: String? = null,
        onProgress: (Float) -> Unit = {},
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            // Stage under filesDir (NOT cacheDir) so the OS doesn't evict
            // the APK mid-flow while the user is in system Settings granting
            // install permission — that eviction caused the "re-download
            // after grant" regression (T-android-update-resume-33637).
            val outDir = File(context.filesDir, UPDATES_DIR).apply { mkdirs() }
            // Filename keyed by version so a partial old-version download
            // can't accidentally satisfy a check for a newer version.
            val safeName = versionName
                ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
                ?.takeIf { it.isNotEmpty() }
                ?.let { "minis-$it.apk" }
                ?: DOWNLOAD_FILENAME
            val outFile = File(outDir, safeName)
            // A previous, possibly-aborted download could leave a stale APK
            // behind that the installer would happily try to consume. Wipe it.
            if (outFile.exists()) outFile.delete()

            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext DownloadResult.Error("HTTP ${resp.code}")
                }
                val body = resp.body ?: return@withContext DownloadResult.Error("empty body")
                val total = body.contentLength().takeIf { it > 0 } ?: -1L
                body.byteStream().use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var totalRead = 0L
                        var lastReported = -1
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            totalRead += read
                            if (total > 0) {
                                val pct = ((totalRead * 100) / total).toInt()
                                if (pct != lastReported) {
                                    lastReported = pct
                                    onProgress(pct / 100f)
                                }
                            }
                        }
                    }
                }
            }
            AppLogger.info(TAG, "Downloaded ${outFile.length()} bytes to ${outFile.absolutePath}")
            // Persist so a subsequent Activity recreate (e.g. after the user
            // returns from "install unknown apps" settings) can resume the
            // install without re-downloading. sha256 computed best-effort;
            // verify() falls back to size-only when null.
            val sha = runCatching { PendingUpdateStore.sha256(outFile) }
                .onFailure { AppLogger.warning(TAG, "sha256 compute failed: ${it.message}") }
                .getOrNull()
            if (expectedSha256 != null && !expectedSha256.equals(sha, ignoreCase = true)) {
                AppLogger.error(TAG, "[UpdateCheck] sha256 mismatch - discarding the download")
                outFile.delete()
                return@withContext DownloadResult.Error(context.getString(com.openminis.app.R.string.check_update_verify_failed))
            }
            if (versionName != null) {
                PendingUpdateStore.setPending(
                    context,
                    PendingUpdateStore.PendingUpdate(
                        targetVersionName = versionName,
                        targetVersionCode = versionCode,
                        apkPath = outFile.absolutePath,
                        apkSize = outFile.length(),
                        sha256 = sha,
                        downloadedAtMs = System.currentTimeMillis(),
                    ),
                )
            }
            DownloadResult.Success(outFile)
        } catch (e: Exception) {
            AppLogger.error(TAG, "download failed: ${e.javaClass.simpleName}: ${e.message}")
            DownloadResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Whether the OS will allow this app to launch a package-installer
     * intent. On Android 8+ the user must grant "install unknown apps" per
     * source-app; older releases inherit the system-wide setting.
     */
    fun canInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Send the user to the system "install unknown apps" preferences page
     * for this package. Caller should re-check [canInstall] after the user
     * returns.
     */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Hand [apk] to the system package installer via FileProvider.
     * Caller must ensure [canInstall] before calling, otherwise the system
     * silently bounces back to the launcher. Returns false on any
     * launch failure so callers can surface an error instead of closing
     * the dialog with no visible feedback.
     */
    /**
     * If a pending APK from a previous download is still on disk and intact,
     * returns the [File]. The caller is responsible for checking
     * [canInstall] and firing [installApk]. Returns null when nothing pending
     * or when the cached file failed integrity checks — in the latter case
     * the pending record is cleared so the UI falls through to a fresh
     * download.
     */
    fun resumablePendingFile(context: Context): File? {
        val pending = PendingUpdateStore.getPending(context) ?: return null
        // Only resume if the persisted target is still newer than the running
        // build — protects against the case where the user updated by some
        // other means since the download.
        // [T-update-channels] The build number decides when the download
        // recorded one; older records fall back to the version names.
        val stale = pending.targetVersionCode?.let { it <= BuildConfig.VERSION_CODE }
            ?: (UpdatePolicy.compareVersionNames(pending.targetVersionName, BuildConfig.VERSION_NAME) <= 0)
        if (stale) {
            AppLogger.info(TAG, "pending target ${pending.targetVersionName} <= local; clearing")
            PendingUpdateStore.clearPending(context)
            return null
        }
        val file = PendingUpdateStore.verify(pending)
        if (file == null) {
            AppLogger.info(TAG, "pending APK failed integrity; clearing")
            PendingUpdateStore.clearPending(context)
            return null
        }
        return file
    }

    /**
     * [T-update-channels] Why [apk] must not be installed, read from the
     * archive itself: another package, or not newer than what is installed.
     * Null when it may go to the installer. The name, the release and the
     * digest all said it was right; this is the check that cannot be fooled
     * by a mislabelled asset.
     */
    fun archiveRefusal(context: Context, apk: File): String? {
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageArchiveInfo(apk.absolutePath, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            }
        }.getOrNull()
        val code = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() }
        val refusal = UpdatePolicy.archiveRefusal(info?.packageName, code, context.packageName, BuildConfig.VERSION_CODE.toLong())
        AppLogger.info(TAG, "[UpdateCheck] archive package=${info?.packageName} versionCode=$code refusal=$refusal")
        return refusal
    }

    fun installApk(context: Context, apk: File): Boolean {
        if (archiveRefusal(context, apk) != null) {
            // Never install it; drop it so a resume does not try again.
            PendingUpdateStore.clearPending(context)
            apk.delete()
            return false
        }
        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLogger.info(TAG, "installApk launched apk=${apk.absolutePath} size=${apk.length()}")
            // Once the installer is in flight we don't want a subsequent
            // resume to re-fire the intent (would double-prompt). Clear the
            // pending record now; if the user backs out, the next "Check for
            // Updates" tap will re-discover and re-download.
            PendingUpdateStore.clearPending(context)
            true
        } catch (e: Exception) {
            AppLogger.error(TAG, "installApk failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }
}
