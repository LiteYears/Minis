package com.openminis.app.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * The agent loop's hard tool-round ceiling, shared by the main chat
 * (ChatViewModel.MAX_AGENT_TURNS) and sub-agent helpers / scheduled jobs
 * (HelperRunner.MAX_TURNS), so the two cannot drift apart.
 *
 * Mirrors iOS AIChatViewModel.maxAgentTurns (and helperMaxTurns, defined as
 * it). Raised 200 -> 500 on all three platforms (FEAT-261021-01); it is a
 * backstop behind ToolLoopDetector, not the normal way a turn ends.
 */
object AgentLoopLimits {
    const val MAX_AGENT_TURNS = 500
}

// ─── Merged from InterruptedTailDetector.kt ──────────────────────────────────────────────────

/**
 * Which "the agent loop stopped early" shape a session's tail matches.
 *
 * Extracted from `ChatViewModel.loadSession` so the rule can be tested
 * directly: it decides whether the user gets a Resume affordance at all, and
 * a wrong answer is invisible in code review — either the banner never
 * appears (the GH#262/#263 report) or it appears over a turn that is simply
 * still waiting.
 */
enum class InterruptedTailShape {
    /** Tools completed, but the follow-up model call never fired. */
    TOOL_RESULT_TAIL,

    /** The model asked for tools that never executed. */
    ASSISTANT_TOOL_USE,

    /** The synthetic "user stopped the previous response" reminder was
     *  committed, but `resume()` never re-entered the loop. */
    CONTINUE_REMINDER,

    /**
     * A plain-text user turn with NO reply after it at all (GH#262/#263).
     *
     * `send()` persists the user row BEFORE the reply lands, and
     * `persistAssistantTurn()` drops an assistant row that has no parts — so a
     * process death in between (or a first-turn network failure, where
     * `setInlineError` has no assistant row to attach to) leaves a tail that
     * looks finished but never got an answer.
     */
    UNANSWERED_USER_TURN,

    /** Not interrupted. */
    NONE,
}

object InterruptedTailDetector {

    /**
     * [T-port-user-ask-recovery] The tail is an assistant turn whose tool calls
     * include a user_ask question still waiting for the user (restored after a
     * kill). Not interrupted: the question's card is the affordance, and a
     * Resume banner would re-run the call and ask again.
     */
    fun awaitsUserAnswer(lastEntry: LLMMessage?, pendingToolCallIds: Set<String>): Boolean {
        if (lastEntry == null || pendingToolCallIds.isEmpty() || lastEntry.role != LLMMessage.Role.ASSISTANT) return false
        return lastEntry.contentParts.any { it is AgentContentPart.ToolUse && it.id in pendingToolCallIds }
    }

    /** Marker text of the synthetic continue reminder (see `resume()`). */
    const val CONTINUE_REMINDER_MARKER = "The user stopped the previous response"

    /**
     * Classify [lastEntry] — the final entry of `agentHistory`.
     *
     * Callers MUST additionally gate on "nothing is currently streaming"
     * (`!isStreaming && !SessionActivityTracker.isActive(sid)`). This function
     * deliberately knows nothing about liveness: it answers "what shape is
     * this tail", not "is it safe to offer Resume", and conflating the two is
     * how a still-waiting turn would get a Resume banner.
     */
    fun classify(lastEntry: LLMMessage?): InterruptedTailShape {
        if (lastEntry == null) return InterruptedTailShape.NONE
        return when (lastEntry.role) {
            LLMMessage.Role.USER -> {
                val parts = lastEntry.contentParts
                val allToolResults = parts.isNotEmpty() &&
                    parts.all { it is AgentContentPart.ToolResult }
                val isContinueReminder = parts.size == 1 &&
                    (parts.first() as? AgentContentPart.Text)?.text
                        ?.contains(CONTINUE_REMINDER_MARKER) == true
                when {
                    // Order matters: the first two describe a turn that was
                    // mid-flight; the third describes one that never started.
                    allToolResults -> InterruptedTailShape.TOOL_RESULT_TAIL
                    isContinueReminder -> InterruptedTailShape.CONTINUE_REMINDER
                    // An EMPTY user turn is not a recoverable shape — there is
                    // nothing to answer, and re-sending it would post a
                    // content-less message the API rejects.
                    parts.isEmpty() -> InterruptedTailShape.NONE
                    else -> InterruptedTailShape.UNANSWERED_USER_TURN
                }
            }
            LLMMessage.Role.ASSISTANT ->
                if (lastEntry.contentParts.any { it is AgentContentPart.ToolUse }) {
                    InterruptedTailShape.ASSISTANT_TOOL_USE
                } else {
                    // A plain assistant reply IS the completed turn.
                    InterruptedTailShape.NONE
                }
            else -> InterruptedTailShape.NONE
        }
    }

    /** True when the tail is any recoverable shape. */
    fun isInterrupted(lastEntry: LLMMessage?): Boolean =
        classify(lastEntry) != InterruptedTailShape.NONE
}

// ─── Merged from SoulIcon.kt ──────────────────────────────────────────────────

/**
 * [T-android-soul-custom-icon] The Soul identity icon: an emoji, or a
 * transparent image stored inline as a data URI.
 *
 * Port of the iOS `SoulIconImage` / emoji-normalization pair
 * (`dfa7a17b5`, `68b9ceaed`). The rules here are contract, not preference —
 * the value syncs between platforms, so both sides must agree on what is
 * storable.
 */
object SoulIcon {

    /** The stored prefix. PNG specifically: it is the format we re-encode to. */
    const val DATA_URI_PREFIX = "data:image/png;base64,"

    /** Longest edge of the stored bitmap, in pixels. ~1-4 KB as a data URI. */
    const val STORED_PIXELS = 96

    /**
     * Hard cap on the stored string. A 96px PNG lands far below this; the cap
     * exists so a hand-edited or synced-in SOUL.md cannot put an unbounded
     * blob on the single frontmatter line (there is no other size guard on
     * frontmatter, unlike the body which has its own limit).
     */
    const val MAX_DATA_URI_CHARS = 64 * 1024

    fun isDataUri(value: String): Boolean = value.startsWith(DATA_URI_PREFIX)

    /**
     * [T-android-soul-icon-sidecar] The PNG sidecar's filename, stored in the
     * `icon:` frontmatter key in place of the bytes. Byte-identical to iOS's
     * `SoulIconImage.sidecarName` — SOUL.md is a cross-device file, so the two
     * platforms must agree on what the key contains.
     */
    const val SIDECAR_NAME = "SOUL.icon.png"

    /** True when a stored `icon:` value NAMES the sidecar rather than holding bytes. */
    fun isSidecarRef(value: String): Boolean = value.trim() == SIDECAR_NAME

    /** The raw PNG bytes behind a `data:image/png;base64,…` URI, or null. */
    fun pngBytes(dataUri: String): ByteArray? {
        if (!isDataUri(dataUri)) return null
        return decodeBase64(dataUri.removePrefix(DATA_URI_PREFIX))
    }

    /** The data URI for raw PNG bytes — the in-memory form every consumer reads. */
    fun dataUri(png: ByteArray): String =
        DATA_URI_PREFIX + Base64.encodeToString(png, Base64.NO_WRAP)

    /**
     * Corner radius as a fraction of the icon's edge, matching iOS.
     *
     * Rounded, deliberately NOT a circle: at 18dp in the chat header a circle
     * eats the corners of a small avatar. 22% is the platform app-icon
     * proportion.
     */
    const val CORNER_RADIUS_FRACTION = 0.22f

    /** Why a picked image was refused. */
    enum class Rejection { UNREADABLE, TOO_LARGE }

    sealed class EncodeResult {
        data class Success(val dataUri: String) : EncodeResult()
        data class Failure(val reason: Rejection) : EncodeResult()
    }

    /**
     * Normalize a picked bitmap into the stored form: centre-cropped to 1:1,
     * downscaled to [STORED_PIXELS], PNG, base64 data URI.
     *
     * **Opaque images are accepted.** An earlier version refused anything
     * without an alpha channel, reasoning that an unframed opaque rectangle
     * reads as a broken tile. That reasoning was about PRESENTATION, so the
     * fix belongs in the renderer — every image is now clipped to a rounded
     * rectangle ([CORNER_RADIUS_FRACTION]) wherever it is drawn. With that in
     * place the refusal only turned away most of the images a user might pick.
     * Follows iOS `fe2f3ae8b`, which reversed the same rule for the same
     * reason; the two platforms must agree, since the value syncs.
     *
     * Alpha is still PRESERVED (PNG, never flattened) — it is simply no
     * longer required.
     */
    fun encode(source: Bitmap): EncodeResult {
        if (source.width <= 0 || source.height <= 0) {
            return EncodeResult.Failure(Rejection.UNREADABLE)
        }

        val square = squareCropped(source)
        // Never upscale: a 48px source stays 48px rather than being blown up
        // to 96 and looking soft.
        val side = minOf(STORED_PIXELS, square.width, square.height)
        val scaled = if (square.width == side && square.height == side) {
            square
        } else {
            Bitmap.createScaledBitmap(square, side, side, true)
        }

        val png = ByteArrayOutputStream().use { out ->
            // PNG is lossless and ignores the quality argument, so alpha and
            // exact pixels survive the round trip.
            if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                return EncodeResult.Failure(Rejection.UNREADABLE)
            }
            out.toByteArray()
        }

        // NO_WRAP is mandatory: the default inserts newlines, and the
        // frontmatter parser is strictly line-oriented — a wrapped payload
        // would be truncated at the first break.
        val encoded = DATA_URI_PREFIX + Base64.encodeToString(png, Base64.NO_WRAP)
        if (encoded.length > MAX_DATA_URI_CHARS) {
            return EncodeResult.Failure(Rejection.TOO_LARGE)
        }
        return EncodeResult.Success(encoded)
    }

    /** Decode a stored data URI. Returns null for an emoji value or garbage. */
    fun decode(value: String): Bitmap? {
        if (!isDataUri(value)) return null
        return runCatching {
            val bytes = Base64.decode(value.removePrefix(DATA_URI_PREFIX), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    /**
     * [T-android-soul-icon-config-images] Turn a `minis-config` value into
     * bitmap bytes.
     *
     * Mirrors iOS `fe2f3ae8b`: an address is only an IMPORT SOURCE. Whatever
     * it resolves to goes through the same [encode] the Settings picker uses,
     * and only the RESULT is stored — so the source file can be deleted
     * afterwards and the icon still survives attachment cleanup and syncing.
     *
     * Deliberately NOT supported on Android: `http(s)://`. iOS resolves those
     * in an async phase before its confirmation sheet; Android's
     * `ConfigField.write` is synchronous, and doing a network fetch inside it
     * would block the caller and hand a model-supplied URL a request from
     * inside the app — an SSRF surface that needs the same host-blocklist
     * treatment iOS built. Rather than ship a weaker version of that, remote
     * URLs are refused with a message pointing at the local forms. Everything
     * that does not need the network is supported.
     */
    sealed class Source {
        data class Bytes(val data: ByteArray) : Source() {
            // ByteArray needs structural equals for the data class to behave.
            override fun equals(other: Any?): Boolean =
                this === other || (other is Bytes && data.contentEquals(other.data))
            override fun hashCode(): Int = data.contentHashCode()
        }
        data class LinuxPath(val path: String) : Source()
        data class Unsupported(val reason: String) : Source()
    }

    /** Classify a config value that is not an emoji and not empty. */
    fun classifySource(raw: String): Source {
        val v = raw.trim()
        return when {
            v.startsWith("http://", true) || v.startsWith("https://", true) ->
                Source.Unsupported(
                    "remote URLs aren't supported on Android — download the file first, " +
                        "then pass a path like /var/minis/attachments/icon.png",
                )
            v.startsWith("minis://") -> {
                // minis://attachments/x.png -> /var/minis/attachments/x.png
                val rest = v.removePrefix("minis://").trimStart('/')
                if (rest.isEmpty()) Source.Unsupported("empty minis:// path")
                else Source.LinuxPath("/var/minis/$rest")
            }
            v.startsWith("data:") -> {
                val comma = v.indexOf(',')
                val meta = if (comma > 0) v.substring(0, comma) else ""
                if (comma < 0 || !meta.contains(";base64")) {
                    Source.Unsupported("only base64 data URIs are supported")
                } else {
                    decodeBase64(v.substring(comma + 1))
                        ?.let { Source.Bytes(it) }
                        ?: Source.Unsupported("the data URI's base64 could not be decoded")
                }
            }
            v.startsWith("/") -> Source.LinuxPath(v)
            // Bare base64 — auto-detected, matching iOS. Checked last so it
            // cannot shadow any of the addressed forms.
            looksLikeBareBase64(v) ->
                decodeBase64(v)?.let { Source.Bytes(it) }
                    ?: Source.Unsupported("that base64 could not be decoded")
            else -> Source.Unsupported(
                "not an emoji, a data URI, base64, a minis:// resource or a /var/minis path",
            )
        }
    }

    private fun decodeBase64(s: String): ByteArray? = runCatching {
        Base64.decode(s.trim(), Base64.DEFAULT)
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun looksLikeBareBase64(v: String): Boolean =
        v.length >= 32 && v.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }

    /**
     * The linux directories a config-supplied path may read from.
     *
     * Containment resolves symlinks on BOTH sides before comparing (same
     * construction as the backup extractor), so a symlink inside an allowed
     * directory cannot point out of it. Without the canonicalisation a
     * model-supplied `/var/minis/attachments/../../../databases/x` would walk
     * straight out of the sandbox.
     */
    val ALLOWED_LINUX_ROOTS = listOf(
        "/var/minis/attachments",
        "/var/minis/workspace",
        "/var/minis/offloads",
        "/var/minis/shared",
        "/var/minis/memory",
        "/var/minis/skills",
    )

    /** True when [candidate] really sits inside [root] after both are resolved. */
    fun isContained(candidate: java.io.File, root: java.io.File): Boolean {
        val c = runCatching { candidate.canonicalFile }.getOrElse { return false }
        val r = runCatching { root.canonicalFile }.getOrElse { return false }
        // The separator matters: it stops "/a/bc" matching root "/a/b".
        return c == r || c.path.startsWith(r.path + java.io.File.separator)
    }

    /** Centre-crop to 1:1, keeping the shorter edge. */
    private fun squareCropped(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w == h) return bitmap
        val side = minOf(w, h)
        return Bitmap.createBitmap(bitmap, (w - side) / 2, (h - side) / 2, side, side)
    }

    // ── Emoji ────────────────────────────────────────────────────────────

    /**
     * Suggestions offered in the picker.
     *
     * **All Unicode 6.0 (2010).** Non-negotiable: this value syncs to iOS, and
     * a 2021-or-later emoji renders as a blank box on a device with an older
     * font, which reads as data loss rather than a style choice. No skin tones
     * and no ZWJ sequences, for the same reason.
     */
    val SUGGESTED_EMOJI: List<String> = listOf(
        "✨", "🤖", "🐱", "🦊", "🐧", "🦉", "🌟", "⚡",
        "🧠", "💡", "🔮", "🚀", "🌊", "🍀", "🎯", "🐳",
    )

    /**
     * Keep at most one emoji, preferring whatever the user just added.
     *
     * Called PER KEYSTROKE, not on submit: typing a second emoji replaces the
     * first (we take the LAST one present), and non-emoji input is silently
     * dropped rather than surfaced as an error.
     *
     * Operates on grapheme clusters, so a flag (🇯🇵), keycap (1️⃣), ZWJ
     * sequence (👩‍💻) or skin-toned emoji (👍🏽) each survive as ONE glyph
     * instead of being sliced apart.
     */
    fun normalizeEmojiInput(input: String): String =
        graphemeClusters(input).lastOrNull { isEmojiGlyph(it) } ?: ""

    /**
     * True for a grapheme cluster that is genuinely an emoji.
     *
     * The trap this exists for: a naive "has the Emoji property" test accepts
     * bare ASCII digits, `#` and `*`, because Unicode gives those the Emoji
     * property — they are the bases of keycap sequences like 1️⃣. So a lone
     * "1" would pass and be stored as the identity icon.
     *
     * The rule, matching iOS: a MULTI-scalar cluster is accepted when any
     * scalar is emoji-ish (that keeps 1️⃣, 🇯🇵, 👩‍💻, 👍🏽); a LONE scalar
     * must have default emoji presentation (which excludes plain digits,
     * letters, CJK and punctuation).
     */
    fun isEmojiGlyph(cluster: String): Boolean {
        if (cluster.isEmpty()) return false
        val codePoints = cluster.codePoints().toArray()
        if (codePoints.isEmpty()) return false

        if (codePoints.size > 1) {
            // A variation selector-16 or keycap makes an otherwise-text base
            // render as emoji; a ZWJ or regional-indicator pair is emoji by
            // construction.
            if (codePoints.any { it == 0xFE0F || it == 0x20E3 || it == 0x200D }) return true
            if (codePoints.all { it in 0x1F1E6..0x1F1FF }) return true
            return codePoints.any { hasEmojiPresentation(it) }
        }
        return hasEmojiPresentation(codePoints[0])
    }

    /**
     * Approximates Unicode's Emoji_Presentation property: code points that
     * render as emoji by default, with no variation selector.
     *
     * Kotlin/Java exposes no Emoji_Presentation query (and
     * `Character.isEmoji` is API 35+, far above our minSdk), so this is an
     * explicit range list. It deliberately EXCLUDES the keycap bases
     * (`0-9`, `#`, `*`) and other text-default symbols, which is exactly the
     * case that a property-based test gets wrong.
     */
    private fun hasEmojiPresentation(cp: Int): Boolean = when (cp) {
        0x231A, 0x231B,                                  // watch, hourglass
        0x23E9, 0x23EA, 0x23EB, 0x23EC, 0x23F0, 0x23F3,
        0x25FD, 0x25FE,
        0x2614, 0x2615,
        0x2648, 0x2649, 0x264A, 0x264B, 0x264C, 0x264D,  // zodiac
        0x264E, 0x264F, 0x2650, 0x2651, 0x2652, 0x2653,
        0x267F, 0x2693, 0x26A1, 0x26AA, 0x26AB,          // ⚡ is here
        0x26BD, 0x26BE, 0x26C4, 0x26C5, 0x26CE, 0x26D4,
        0x26EA, 0x26F2, 0x26F3, 0x26F5, 0x26FA, 0x26FD,
        0x2705, 0x270A, 0x270B,
        0x2728,                                          // ✨ the default
        0x274C, 0x274E,
        0x2753, 0x2754, 0x2755, 0x2757,
        0x2795, 0x2796, 0x2797,
        0x27B0, 0x27BF,
        0x2B1B, 0x2B1C, 0x2B50, 0x2B55,
        -> true
        else -> when (cp) {
            in 0x2B05..0x2B07 -> false                   // text-default arrows
            in 0x1F004..0x1F0CF -> true                  // mahjong, playing card
            in 0x1F18E..0x1F19A -> true
            in 0x1F1E6..0x1F1FF -> false                 // lone regional indicator
            in 0x1F200..0x1F251 -> true
            in 0x1F300..0x1F320 -> true
            in 0x1F32D..0x1F335 -> true
            in 0x1F337..0x1F37C -> true
            in 0x1F37E..0x1F393 -> true
            in 0x1F3A0..0x1F3CA -> true
            in 0x1F3CF..0x1F3D3 -> true
            in 0x1F3E0..0x1F3F0 -> true
            in 0x1F3F4..0x1F3F4 -> true
            in 0x1F3F8..0x1F43E -> true
            in 0x1F440..0x1F4FC -> true
            in 0x1F4FF..0x1F53D -> true
            in 0x1F54B..0x1F54E -> true
            in 0x1F550..0x1F567 -> true
            in 0x1F57A..0x1F57A -> true
            in 0x1F595..0x1F596 -> true
            in 0x1F5A4..0x1F5A4 -> true
            in 0x1F5FB..0x1F64F -> true
            in 0x1F680..0x1F6C5 -> true
            in 0x1F6CC..0x1F6CC -> true
            in 0x1F6D0..0x1F6D2 -> true
            in 0x1F6EB..0x1F6EC -> true
            in 0x1F6F4..0x1F6FC -> true
            in 0x1F7E0..0x1F7EB -> true
            in 0x1F90C..0x1F93A -> true
            in 0x1F93C..0x1F945 -> true
            in 0x1F947..0x1F978 -> true
            in 0x1F97A..0x1F9CB -> true
            in 0x1F9CD..0x1F9FF -> true
            in 0x1FA70..0x1FAFF -> true
            else -> false
        }
    }

    /**
     * Split into extended grapheme clusters, for the emoji subset we care
     * about: ZWJ sequences, regional-indicator flag pairs, variation
     * selectors, keycaps and skin-tone modifiers.
     *
     * Hand-rolled rather than using `BreakIterator.getCharacterInstance()`.
     * That was the first implementation and it is **wrong for exactly the
     * cases this feature depends on** — measured on JDK 17, it reports
     * 🇯🇵 as 2 clusters, 👩‍💻 as 3 and 👍🏽 as 2, because the JDK's
     * BreakIterator implements legacy boundaries, not UAX #29 *extended*
     * grapheme clusters. Android's ICU gets those right, so a
     * BreakIterator-based version would pass on device and fail on the JVM —
     * i.e. the unit tests would be the only thing telling the truth, and only
     * by disagreeing with production. One implementation that behaves
     * identically in both places is worth more than the shared-library
     * shortcut.
     */
    fun graphemeClusters(input: String): List<String> {
        if (input.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val cps = input.codePoints().toArray()
        var i = 0
        while (i < cps.size) {
            val start = i
            i++
            // A flag is exactly two regional indicators.
            if (isRegionalIndicator(cps[start]) &&
                i < cps.size && isRegionalIndicator(cps[i])
            ) {
                i++
            } else {
                // Absorb trailing modifiers, then any ZWJ-joined segment
                // (each of which may itself carry modifiers).
                i = absorbModifiers(cps, i)
                while (i < cps.size && cps[i] == ZWJ && i + 1 < cps.size) {
                    i += 2                     // the ZWJ and the joined base
                    i = absorbModifiers(cps, i)
                }
            }
            out.add(String(cps, start, i - start))
        }
        return out
    }

    private const val ZWJ = 0x200D

    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

    /** Variation selectors, keycap, skin tones, and combining marks. */
    private fun absorbModifiers(cps: IntArray, from: Int): Int {
        var i = from
        while (i < cps.size) {
            val cp = cps[i]
            val isModifier = cp == 0xFE0E || cp == 0xFE0F ||   // variation selectors
                cp == 0x20E3 ||                                // combining keycap
                cp in 0x1F3FB..0x1F3FF ||                      // skin tones
                cp in 0x0300..0x036F ||                        // combining diacritics
                cp in 0xE0020..0xE007F                         // tag chars (subdivision flags)
            if (!isModifier) break
            i++
        }
        return i
    }
}

// ─── Merged from ToolLoopDetector.kt ──────────────────────────────────────────────────

/**
 * Per-session tool-call sliding window record. Filled in two passes:
 *  - `check()` doesn't write here; it only reads existing history.
 *  - `record()` appends a new entry, back-filling `resultHash`/`unknownToolName`
 *    from the just-finished tool execution.
 */
data class ToolCallRecord(
    val toolName: String,
    val argsHash: String,
    val resultHash: String? = null,
    val unknownToolName: String? = null,
    val toolCallId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

enum class Level { NONE, WARNING, CRITICAL }

data class LoopCheckResult(
    val level: Level,
    val message: String? = null,
    val warningKey: String? = null,
) {
    companion object {
        val NONE = LoopCheckResult(Level.NONE)
    }
}

/**
 * Threshold contract:
 *   warningThreshold < criticalThreshold < globalCircuitBreakerThreshold
 * The constructor enforces this at construction time so callers cannot
 * silently ship a misconfigured detector.
 */
data class ToolLoopConfig(
    val historySize: Int = 30,
    val warningThreshold: Int = 10,
    val unknownToolThreshold: Int = 10,
    val criticalThreshold: Int = 20,
    val globalCircuitBreakerThreshold: Int = 30,
    /**
     * [T-tool-title-reminder] Remind about a missing `tool_title` on the 1st,
     * (N+1)th, (2N+1)th… missing-title call. Mirrors iOS.
     */
    val toolTitleReminderEvery: Int = 10,
) {
    init {
        require(warningThreshold > 0) { "warningThreshold must be positive" }
        require(toolTitleReminderEvery > 0) { "toolTitleReminderEvery must be positive" }
        require(warningThreshold < criticalThreshold) {
            "warningThreshold ($warningThreshold) must be < criticalThreshold ($criticalThreshold)"
        }
        require(criticalThreshold < globalCircuitBreakerThreshold) {
            "criticalThreshold ($criticalThreshold) must be < globalCircuitBreakerThreshold ($globalCircuitBreakerThreshold)"
        }
        require(historySize >= globalCircuitBreakerThreshold) {
            "historySize ($historySize) must be >= globalCircuitBreakerThreshold ($globalCircuitBreakerThreshold)"
        }
    }
}

/**
 * Detects four classes of agent tool-call loops and emits warnings or hard
 * blocks. See fix_tool_loop_detection.md for the full behavioral spec.
 *
 * Strategy priority (highest first):
 *   1. unknown_tool_repeat       — consecutive hallucinated-tool errors.
 *   2. global_circuit_breaker    — same args + same result, ≥30 across any tool.
 *   3. known_poll_no_progress    — poll-style tool with frozen results.
 *   4. generic_repeat            — same args ≥10, regardless of result.
 *
 * One detector instance per Session.
 *
 * [T-android-concurrent-tools] Thread-safe by an instance lock. This class used
 * to say "serialize calls through the agent loop's existing single-threaded
 * dispatch" — and that dispatch is no longer single-threaded: a turn's tool
 * calls now run concurrently, so several threads reach [check] and [record] on
 * the same detector at once.
 *
 * The failure was not theoretical. On a Pixel 6 a sub agent died with
 * "Attempt to invoke virtual method 'String ToolCallRecord.getToolName()' on a
 * null object reference": ArrayDeque is unsynchronized, so a concurrent
 * `addLast` that grows the backing array leaves a transiently-null slot, and
 * the scans below dereference it. The whole run was lost — the parent got
 * "the agent ended with status failed before writing a final answer".
 *
 * Every public entry point takes the lock. Contention is irrelevant here: the
 * work is hashing a small map and scanning a 30-element window, next to tool
 * calls that take seconds.
 */
class ToolLoopDetector(private val config: ToolLoopConfig = ToolLoopConfig()) {

    /** [T-android-concurrent-tools] Guards [history] and [warningBuckets]. */
    private val lock = Any()

    private val history = ArrayDeque<ToolCallRecord>()
    // warningKey -> last bucket index already emitted, used to throttle warnings
    // so the LLM doesn't get the same warning attached on every single tool call.
    private val warningBuckets = HashMap<String, Int>()

    /**
     * [T-tool-title-reminder] Cumulative count of titled tool calls that came
     * back without a `tool_title`. Not windowed like [history]; cleared by
     * [reset] with everything else.
     */
    private var missingTitleCount = 0

    /**
     * [T-loop-reminder-scheduled-exempt] 1 after a scheduled-task fire, until
     * the next recorded call consumes it. Never above 1, never below 0.
     */
    private var scheduledFireExemption = 0

    /** Drop all in-flight state. Call on session reset / new chat. */
    fun reset() = synchronized(lock) {
        history.clear()
        warningBuckets.clear()
        missingTitleCount = 0
        scheduledFireExemption = 0
    }

    /**
     * [T-tool-title-reminder] Called after a tool ran, next to [record]. Counts
     * calls of a tool that declares `tool_title` but arrived without a usable
     * one, and returns the soft reminder when one is due: on the 1st, 11th,
     * 21st… such call ([ToolLoopConfig.toolTitleReminderEvery]). The caller
     * appends it to the model-facing tool result exactly like a loop warning.
     * Same lock, same [warningBuckets] throttle, same [reset] lifecycle.
     * Mirrors iOS `ToolLoopDetector.recordToolTitle`.
     */
    fun recordToolTitle(args: JSONObject, titleDeclared: Boolean): String? {
        if (!titleDeclared || !com.openminis.app.ui.chat.ToolTitleReminder.isMissingTitle(args)) return null
        return synchronized(lock) {
            missingTitleCount += 1
            if (!shouldEmitEvery("title:missing", missingTitleCount, config.toolTitleReminderEvery)) {
                null
            } else {
                AppLogger.info("ToolLoopDetector", "loop-detector: tool_title missing count=$missingTitleCount — soft reminder due")
                com.openminis.app.ui.chat.ToolTitleReminder.TEXT
            }
        }
    }

    /**
     * [T-loop-reminder-scheduled-exempt] Called when a scheduled task (a
     * `minis-scheduled` timer / loop) fires into this session. Polling on a
     * schedule repeats the same call by design — e.g. reading the same terminal
     * screen every 2 minutes — and each fire used to add one identical call to
     * the window, so ten fires raised "[LOOP WARNING] ... identical arguments"
     * (and 30 unchanged screens the global circuit breaker): a false positive
     * that also polluted the model's context.
     *
     * Decrements the repeat count by one per fire, floored at zero: the next
     * recorded call is not counted. The exemption does not stack (it is 0 or
     * 1), so fires that make no tool call cannot bank credit for later user
     * turns, and a fire that runs away within itself is still caught from its
     * second call. Same semantics as iOS ToolLoopDetector
     * `exemptNextCallForScheduledFire()`.
     */
    fun exemptNextCallForScheduledFire() = synchronized(lock) {
        scheduledFireExemption = 1
    }

    /** Test-only window inspection. */
    internal fun historySnapshot(): List<ToolCallRecord> = synchronized(lock) { history.toList() }

    // ─── before-execution hook ────────────────────────────────────────────────

    /**
     * Inspect the about-to-run tool against the sliding window. Caller MUST
     * skip tool execution and surface `result.message` as a tool-error result
     * when `result.level == CRITICAL`.
     */
    fun check(toolName: String, params: Map<String, Any?>): LoopCheckResult = synchronized(lock) {
        val argsHash = argsHashFor(toolName, params)

        // 1. unknown_tool_repeat — most specific signal, runs first.
        val unknownStreak = countUnknownStreakFromTail(toolName)
        if (unknownStreak >= config.unknownToolThreshold) {
            val msg = "[LOOP BLOCKED] CRITICAL: attempted unavailable tool '$toolName' " +
                "$unknownStreak times. Stop retrying that missing tool and answer without it."
            AppLogger.warning("ToolLoopDetector",
                "CRITICAL unknown_tool_repeat tool=$toolName streak=$unknownStreak")
            return LoopCheckResult(Level.CRITICAL, msg)
        }

        val noProgressStreak = getNoProgressStreak(toolName, argsHash)

        // 2. global_circuit_breaker — universal backstop, before poll-specific
        //    rule so a runaway non-poll loop can't slip past on lower thresholds.
        if (noProgressStreak >= config.globalCircuitBreakerThreshold) {
            val msg = "[LOOP BLOCKED] CRITICAL: $toolName has repeated identical " +
                "no-progress outcomes $noProgressStreak times. Session execution " +
                "blocked by global circuit breaker."
            AppLogger.warning("ToolLoopDetector",
                "CRITICAL global_circuit_breaker tool=$toolName streak=$noProgressStreak")
            return LoopCheckResult(Level.CRITICAL, msg)
        }

        // 3. known_poll_no_progress — poll tools have a tighter critical bar
        //    because polling without progress is the canonical waste case.
        if (isPollTool(toolName, params)) {
            if (noProgressStreak >= config.criticalThreshold) {
                val msg = "[LOOP BLOCKED] CRITICAL: Called $toolName $noProgressStreak " +
                    "times with identical no-progress results. Session execution blocked."
                AppLogger.warning("ToolLoopDetector",
                    "CRITICAL known_poll_no_progress tool=$toolName streak=$noProgressStreak")
                return LoopCheckResult(Level.CRITICAL, msg)
            }
            if (noProgressStreak >= config.warningThreshold) {
                val msg = "[LOOP WARNING] You have called $toolName $noProgressStreak " +
                    "times with no progress. Stop polling and either (1) increase wait " +
                    "time, or (2) report the task as failed."
                AppLogger.debug("ToolLoopDetector",
                    "WARNING known_poll_no_progress tool=$toolName streak=$noProgressStreak")
                return LoopCheckResult(Level.WARNING, msg, "poll:$toolName:$argsHash")
            }
        }

        // 4. generic_repeat — non-poll tools only; counts non-consecutive hits
        //    so flaky-but-progressing calls eventually fall out of the window.
        if (!isPollTool(toolName, params)) {
            val totalCount = history.count { it.toolName == toolName && it.argsHash == argsHash }
            if (totalCount >= config.warningThreshold) {
                val msg = "[LOOP WARNING] You have called $toolName $totalCount times " +
                    "with identical arguments. If this is not making progress, stop " +
                    "retrying and report the task as failed."
                AppLogger.debug("ToolLoopDetector",
                    "WARNING generic_repeat tool=$toolName count=$totalCount")
                return LoopCheckResult(Level.WARNING, msg, "repeat:$toolName:$argsHash")
            }
        }

        return LoopCheckResult.NONE
    }

    // ─── after-execution hook ────────────────────────────────────────────────

    /**
     * Append the just-completed tool call to the window and decide whether a
     * warning should be appended to the tool result this turn. Critical
     * outcomes are reported by `check()` *before* the call; `record()` only
     * ever returns NONE or WARNING.
     */
    fun record(
        toolName: String,
        params: Map<String, Any?>,
        result: String?,
        errorMessage: String? = null,
        toolCallId: String? = null,
    ): LoopCheckResult = synchronized(lock) {
        // [T-loop-reminder-scheduled-exempt] A scheduled fire's call is its
        // expected repeat (the poll it exists to run): not counted, so fire
        // after fire of the same command never adds up to a loop warning.
        if (scheduledFireExemption > 0) {
            scheduledFireExemption -= 1
            AppLogger.info("ToolLoopDetector", "loop-detector: $toolName exempted (scheduled fire) — not counted")
            return@synchronized LoopCheckResult.NONE
        }
        val argsHash = argsHashFor(toolName, params)
        val resultHash = resultHashFor(result, errorMessage)
        val unknownToolName = extractUnknownToolName(errorMessage)

        history.addLast(ToolCallRecord(
            toolName = toolName,
            argsHash = argsHash,
            resultHash = resultHash,
            unknownToolName = unknownToolName,
            toolCallId = toolCallId,
        ))
        while (history.size > config.historySize) history.removeFirst()

        // Re-evaluate poll/generic warnings on the post-record window so the
        // warning text reflects the current count (including the call we just
        // appended). Critical paths are check()-only by spec.
        if (isPollTool(toolName, params)) {
            val streak = getNoProgressStreak(toolName, argsHash)
            if (streak in config.warningThreshold until config.criticalThreshold) {
                val key = "poll:$toolName:$argsHash"
                if (shouldEmitWarning(key, streak)) {
                    val msg = "[LOOP WARNING] You have called $toolName $streak times " +
                        "with no progress. Stop polling and either (1) increase wait " +
                        "time, or (2) report the task as failed."
                    return LoopCheckResult(Level.WARNING, msg, key)
                }
            }
        } else {
            val totalCount = history.count { it.toolName == toolName && it.argsHash == argsHash }
            if (totalCount >= config.warningThreshold) {
                val key = "repeat:$toolName:$argsHash"
                if (shouldEmitWarning(key, totalCount)) {
                    val msg = "[LOOP WARNING] You have called $toolName $totalCount " +
                        "times with identical arguments. If this is not making " +
                        "progress, stop retrying and report the task as failed."
                    return LoopCheckResult(Level.WARNING, msg, key)
                }
            }
        }

        return LoopCheckResult.NONE
    }

    // ─── strategy helpers ────────────────────────────────────────────────────

    /**
     * Walk the window from newest to oldest and count how many *consecutive*
     * tail entries are unknown-tool errors targeting the same hallucinated
     * name. Stops at the first record without a parsed unknownToolName, or
     * one that targets a different name.
     */
    private fun countUnknownStreakFromTail(toolName: String): Int {
        var streak = 0
        for (rec in history.reversed()) {
            val unk = rec.unknownToolName ?: break
            if (unk == toolName) streak++ else break
        }
        return streak
    }

    /**
     * Count how many recent records share the given (toolName, argsHash) AND
     * a single common resultHash, scanning newest → oldest. Records of *other*
     * tools are skipped (don't reset the streak), but a result mismatch on the
     * target tool stops counting — that's the "progress observed" signal.
     */
    private fun getNoProgressStreak(toolName: String, argsHash: String): Int {
        var streak = 0
        var pinnedHash: String? = null
        for (rec in history.reversed()) {
            if (rec.toolName != toolName || rec.argsHash != argsHash) continue
            val rh = rec.resultHash ?: break
            if (pinnedHash == null) {
                pinnedHash = rh
                streak++
            } else if (rh == pinnedHash) {
                streak++
            } else {
                break
            }
        }
        return streak
    }

    private fun isPollTool(toolName: String, params: Map<String, Any?>): Boolean {
        if (toolName == "command_status") return true
        if (toolName == "process") {
            val action = (params["action"] as? String)?.lowercase()
            if (action == "poll" || action == "log") return true
        }
        return false
    }

    /**
     * Throttle warnings of the same key to once per `warningThreshold` calls,
     * so a 30-call repeat loop emits only at counts 10, 20, 30 — not on every
     * tool turn after threshold is crossed.
     */
    private fun shouldEmitEvery(warningKey: String, count: Int, period: Int): Boolean {
        // [T-tool-title-reminder] First-hit buckets: fires on 1, period+1, …
        val bucket = (count - 1) / period.coerceAtLeast(1)
        val last = warningBuckets[warningKey]
        if (last != null && last >= bucket) return false
        warningBuckets[warningKey] = bucket
        return true
    }

    private fun shouldEmitWarning(warningKey: String, currentCount: Int): Boolean {
        val bucket = currentCount / config.warningThreshold
        val last = warningBuckets[warningKey]
        if (last == bucket) return false
        warningBuckets[warningKey] = bucket
        return true
    }

    // ─── hashing / parsing primitives ────────────────────────────────────────

    private fun argsHashFor(toolName: String, params: Map<String, Any?>): String {
        // Drop UI/telemetry-only fields the model freely varies — most notably
        // `tool_title`, which models routinely counter-suffix ("Read X #1",
        // "#2", ...). Without this filter every logically identical call hashes
        // unique and the repeat/circuit-breaker strategies all silently fail.
        val filtered = if (params.keys.any { it in ARGS_HASH_IGNORED_KEYS }) {
            params.filterKeys { it !in ARGS_HASH_IGNORED_KEYS }
        } else {
            params
        }
        return sha256("$toolName:${stableJson(filtered)}")
    }

    /**
     * Stable JSON: keys sorted alphabetically at every nesting level so that
     * a Map<"b" → 2, "a" → 1> hashes identically to one inserted "a" → 1, "b" → 2.
     */
    private fun stableJson(value: Any?): String = buildString { appendStable(value) }

    private fun StringBuilder.appendStable(value: Any?) {
        when (value) {
            null -> append("null")
            is Map<*, *> -> {
                append('{')
                value.entries
                    .map { it.key?.toString().orEmpty() to it.value }
                    .sortedBy { it.first }
                    .forEachIndexed { i, (k, v) ->
                        if (i > 0) append(',')
                        append(JSONObject.quote(k)); append(':'); appendStable(v)
                    }
                append('}')
            }
            is List<*> -> {
                append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) append(',')
                    appendStable(v)
                }
                append(']')
            }
            is Array<*> -> appendStable(value.toList())
            is String -> append(JSONObject.quote(value))
            is Number, is Boolean -> append(value.toString())
            is JSONObject -> appendStable(value.toMap())
            is JSONArray -> appendStable(value.toList())
            else -> append(JSONObject.quote(value.toString()))
        }
    }

    private fun JSONObject.toMap(): Map<String, Any?> {
        val out = HashMap<String, Any?>(length())
        val keys = keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = unwrap(get(k))
        }
        return out
    }

    private fun JSONArray.toList(): List<Any?> {
        val out = ArrayList<Any?>(length())
        for (i in 0 until length()) out.add(unwrap(get(i)))
        return out
    }

    private fun unwrap(v: Any?): Any? = when (v) {
        JSONObject.NULL -> null
        is JSONObject -> v.toMap()
        is JSONArray -> v.toList()
        else -> v
    }

    /**
     * Hash only the success/failure-bearing parts of a tool result. We
     * deliberately fold the entire output text in too — the spec calls for
     * stripping noise like timestamps/requestIds, but the platform's tool
     * results don't carry those at this layer (the underlying tools already
     * sanitize them). If a future tool starts leaking volatile fields, prune
     * them here rather than at every call site.
     */
    private fun resultHashFor(result: String?, errorMessage: String?): String {
        val payload = buildString {
            append("err=")
            append(errorMessage ?: "")
            append("out=")
            append(result ?: "")
        }
        return sha256(payload)
    }

    /**
     * Two patterns cover the wording variations seen across providers:
     *   "unknown tool: foobar"          → group 1 = "foobar"
     *   "tool 'foobar' not found"       → group 1 = "foobar"
     * Both case-insensitive; quoting and surrounding whitespace tolerated.
     */
    private fun extractUnknownToolName(errorMessage: String?): String? {
        if (errorMessage.isNullOrBlank()) return null
        UNKNOWN_TOOL_RE_1.find(errorMessage)?.groupValues?.getOrNull(1)?.let { return it }
        UNKNOWN_TOOL_RE_2.find(errorMessage)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    companion object {
        /**
         * Param keys excluded from `argsHashFor`. Currently just `tool_title`
         * (a required UI label that models commonly counter-suffix). Add new
         * entries here if other purely-cosmetic fields ever leak into params.
         */
        private val ARGS_HASH_IGNORED_KEYS: Set<String> = setOf("tool_title")

        private val UNKNOWN_TOOL_RE_1 = Regex(
            """unknown tool[:\s]+["']?([a-zA-Z0-9_.\-]+)["']?""",
            RegexOption.IGNORE_CASE,
        )
        private val UNKNOWN_TOOL_RE_2 = Regex(
            """tool\s+["']?([a-zA-Z0-9_.\-]+)["']?\s+(?:not found|is not available)""",
            RegexOption.IGNORE_CASE,
        )
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
