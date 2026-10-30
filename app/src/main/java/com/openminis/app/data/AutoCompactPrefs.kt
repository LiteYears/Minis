package com.openminis.app.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.openminis.app.BuildConfig
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.logging.AppLogger
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * App-level persisted "auto-compact on threshold" toggle.
 *
 * Mirrors iOS `AIChatViewModel.autoCompactEnabled`, whose backing store is the
 * UserDefaults key `autoCompactOnThreshold` (AIChatViewModel.swift:853). The
 * key name is reused verbatim so the two platforms stay greppable as one
 * feature.
 *
 * Semantics, matching iOS:
 *   - false (the default — iOS uses a bare `UserDefaults.bool`, which is false
 *     when unset): crossing the compact threshold before a send PROMPTS the
 *     user.
 *   - true: the same crossing compacts silently and then sends.
 *
 * Global rather than per-session on purpose: iOS persists it so "future
 * conversations inherit it", which is the whole point of the one-tap opt-in
 * offered on the prompt.
 */
object AutoCompactPrefs {
    private const val PREFS = "minis_auto_compact_prefs"
    private const val KEY_ENABLED = "autoCompactOnThreshold"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedEnabled: Boolean = false

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Capture the app context and warm the cache. Called from MinisApp.onCreate,
     * so [isEnabled] is safe from call sites that have no Context — the same
     * arrangement [FastModePrefs] uses.
     */
    fun prime(context: Context) {
        appContext = context.applicationContext
        cachedEnabled = prefs(context).getBoolean(KEY_ENABLED, false)
    }

    /** Context-free read. False before [prime] runs, matching a fresh install. */
    fun isEnabled(): Boolean = cachedEnabled

    fun setEnabled(context: Context, enabled: Boolean) {
        cachedEnabled = enabled
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Context-free write for the in-chat one-tap opt-in ("Compact & always
     * auto-compact"), which runs from the ViewModel where no Activity context
     * is at hand. No-ops the persist step if [prime] never ran, but still
     * updates the cache so the current process behaves as asked.
     */
    fun setEnabled(enabled: Boolean) {
        cachedEnabled = enabled
        appContext?.let { prefs(it).edit().putBoolean(KEY_ENABLED, enabled).apply() }
    }
}

// ─── Merged from BPETokenizer.kt ──────────────────────────────────────────────────

/**
 * Approximate BPE token counter. Mirrors iOS `BPETokenizer` (cl100k_base)
 * but without the full vocabulary — we ship a lightweight heuristic by
 * default so the app size doesn't balloon by ~2 MB before a caller actually
 * needs tokenization. Drop in a real `cl100k_base.tiktoken` asset via
 * [loadVocabularyFromAssets] when a consumer needs higher fidelity.
 *
 * The heuristic: `max(1, codepointCount / 3)` for text. That's the same
 * floor iOS falls back to when vocab fails to load, and it's within ±15%
 * of real cl100k_base counts for English / mixed CJK text.
 *
 * Image tokens: iOS uses `ceil(w/32) * ceil(h/32)` with a 2048px long-edge
 * cap and a floor of 85 tokens. We match that exactly so the context bar
 * stays visually identical across platforms.
 *
 * This class is thread-safe once [loadVocabularyFromAssets] finishes (or if
 * the heuristic path is used). Vocabulary loading is one-shot via [@Volatile].
 */
object BPETokenizer {
    const val TOKENS_PER_MESSAGE = 3
    const val TOKENS_PER_REPLY = 3
    private const val IMAGE_GRID = 32
    private const val IMAGE_MAX_EDGE = 2048
    private const val IMAGE_MIN_TOKENS = 85
    private const val IMAGE_FAILURE_FALLBACK = 1000

    @Volatile private var encoder: Map<List<Byte>, Int>? = null

    /**
     * Count tokens in [text] using the loaded vocabulary if present, otherwise
     * a codepoint-based heuristic. Returns 0 for empty input.
     */
    fun countTokens(text: String): Int {
        if (text.isEmpty()) return 0
        val vocab = encoder
        if (vocab == null) return heuristicTokenCount(text)
        return bpeEncode(text.toByteArray(Charsets.UTF_8), vocab).size
    }

    /**
     * Approximate token count for an image payload. Matches iOS heuristic:
     * scale long-edge to 2048px, grid at 32×32, min 85 tokens, return 1000
     * if decoding fails (i.e. treat as a costly opaque blob).
     */
    fun countImageTokens(data: ByteArray): Int {
        if (data.isEmpty()) return 0
        // [T-ctx-builder] Bounds-only decode through the image codec seam
        // (production: the same BitmapFactory inJustDecodeBounds call), so a
        // JVM test can measure real dimensions instead of the 1000 fallback.
        val (outWidth, outHeight) = com.openminis.app.provider.ImageBudget.codec.bounds(data)
        val w = outWidth.toFloat()
        val h = outHeight.toFloat()
        if (w <= 0f || h <= 0f) return IMAGE_FAILURE_FALLBACK
        val longEdge = max(w, h)
        val scale = if (longEdge > IMAGE_MAX_EDGE) IMAGE_MAX_EDGE / longEdge else 1f
        val scaledW = w * scale
        val scaledH = h * scale
        val tokens = (ceil(scaledW / IMAGE_GRID) * ceil(scaledH / IMAGE_GRID)).toInt()
        return max(IMAGE_MIN_TOKENS, tokens)
    }

    /**
     * Opt-in: load a cl100k_base vocabulary from `assets/<fileName>`. File
     * must match tiktoken's format — one `<base64-bytes> <rank>` per line.
     * No-op if already loaded or file is missing.
     */
    fun loadVocabularyFromAssets(context: Context, fileName: String = "cl100k_base.tiktoken"): Boolean {
        if (encoder != null) return true
        return try {
            val dict = HashMap<List<Byte>, Int>(100_300)
            context.assets.open(fileName).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val space = line.indexOf(' ')
                    if (space <= 0) continue
                    val decoded = runCatching {
                        android.util.Base64.decode(
                            line.substring(0, space),
                            android.util.Base64.DEFAULT or android.util.Base64.NO_WRAP,
                        )
                    }.getOrNull() ?: continue
                    val rank = line.substring(space + 1).trim().toIntOrNull() ?: continue
                    dict[decoded.toList()] = rank
                }
            }
            if (dict.isNotEmpty()) {
                encoder = dict
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    private fun heuristicTokenCount(text: String): Int {
        var cp = 0
        var i = 0
        while (i < text.length) {
            cp++
            i += if (text[i].isHighSurrogate() && i + 1 < text.length) 2 else 1
        }
        return max(1, cp / 3)
    }

    /**
     * Greedy BPE: treat each byte as its own segment, repeatedly merge the
     * adjacent pair with the lowest rank that's still in the vocabulary, stop
     * when no mergeable pair remains. Mirrors iOS `bytePairEncode`.
     */
    private fun bpeEncode(bytes: ByteArray, vocab: Map<List<Byte>, Int>): List<Int> {
        if (bytes.isEmpty()) return emptyList()
        val segments = ArrayList<IntArray>(bytes.size)
        for (i in bytes.indices) segments.add(intArrayOf(i, i + 1))
        while (segments.size > 1) {
            var minRank = Int.MAX_VALUE
            var minIdx = -1
            for (i in 0 until segments.size - 1) {
                val start = segments[i][0]
                val end = segments[i + 1][1]
                val slice = bytes.sliceArray(start until end).toList()
                val rank = vocab[slice] ?: continue
                if (rank < minRank) {
                    minRank = rank
                    minIdx = i
                }
            }
            if (minIdx < 0) break
            segments[minIdx] = intArrayOf(segments[minIdx][0], segments[minIdx + 1][1])
            segments.removeAt(minIdx + 1)
        }
        return segments.mapNotNull { seg ->
            vocab[bytes.sliceArray(seg[0] until seg[1]).toList()]
        }
    }
}

// ─── Merged from ContextOffload.kt ──────────────────────────────────────────────────

/**
 * Per-session offload storage helpers — write large tool outputs to disk so
 * the model can `file_read` them later while we replace the in-history copy
 * with a tiny `[CONTEXT OFFLOADED] ... <linux-path>` stub.
 *
 * Mirrors iOS `AIChatViewModel.minisOffloadsPersistentDir(for:)`,
 * `offloadContextContent(_:toolId:toolName:ext:)`, and
 * `offloadContextImage(_:toolId:mimeType:)` (AIChatViewModel.swift:6964 +
 * 7170 + 7188). Same path layout — `.../offloads/tools/<name>_<id>.<ext>` —
 * so file_read paths round-trip across platforms when an Android-offloaded
 * session is opened on iOS (or vice versa) via cloud sync.
 *
 * Linux-visible mount: `/var/minis/offloads/tools/<file>`. The host base
 * `filesDir/minis-sessions/<sid>/offloads` is bind-mounted into the
 * sandbox by [com.openminis.app.sandbox.PRootKernel.perSessionSubdirs]
 * (which already includes the "offloads" subdir — no kernel changes
 * required for this feature).
 */
object ContextOffload {
    /** Linux-side mount point — keep in lock-step with iOS `minisOffloadsLinuxDir`. */
    const val LINUX_OFFLOADS_DIR = "/var/minis/offloads"

    /** Sentinel prefix on stub strings — the agent loop checks this to skip
     *  re-offloading parts that have already been processed. Mirrors iOS. */
    const val OFFLOADED_PREFIX = "[CONTEXT OFFLOADED]"

    /**
     * [T-offload-stub-system-reminder] (GH#374) Tag carried by the current stub
     * format. The pruned-argument notice is wrapped in a `<system-reminder>`
     * envelope so the model reads it as a meta-instruction about a REMOVED
     * argument instead of as content it may copy into its next call. The legacy
     * plain-text form read like content ("Content (~N tokens…) saved to: …"),
     * which is exactly why a model re-issuing its own earlier `file_write`
     * pasted the placeholder back in. Mirrors iOS `offloadedStubNoticeTag`.
     */
    const val SYSTEM_NOTICE_TAG = "[Minis System Notice]"

    /**
     * Envelope opener for the current stub format. Kept separate from
     * [SYSTEM_NOTICE_TAG] so detection can require BOTH — a bare
     * `<system-reminder>` is legitimately emitted by other subsystems (the
     * persona reminder), and must not be mistaken for an offload stub.
     * Mirrors iOS `offloadedStubEnvelope`.
     */
    const val SYSTEM_REMINDER_OPEN = "<system-reminder>"

    /**
     * Host-side persistent dir for [sessionId]'s tool offloads. Lazily
     * created on first write — callers should call [ensureToolsDir] before
     * writing.
     */
    fun toolsDir(context: Context, sessionId: String): File =
        File(context.filesDir, "minis-sessions/$sessionId/offloads/tools")

    private fun ensureToolsDir(context: Context, sessionId: String): File {
        val dir = toolsDir(context, sessionId)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Take the last 12 chars of [toolId] as a short, locally-unique suffix
     * for the on-disk filename. Anthropic IDs are `toolu_01…` (constant
     * 8-char prefix), so the trailing 12 chars are still distinguishing.
     * Mirrors iOS `shortToolId(_:)`.
     */
    private fun shortToolId(toolId: String): String =
        if (toolId.length <= 12) toolId else toolId.takeLast(12)

    private fun sanitize(name: String): String =
        name.ifEmpty { "tool" }.replace('/', '_')

    /**
     * Write tool text content to disk and return the Linux-visible path
     * the model can later pass to `file_read`. Returns the empty string
     * on any I/O failure — caller should still update the in-history part
     * with a stub so the model isn't left holding the original bytes.
     */
    fun offloadContent(
        context: Context,
        sessionId: String,
        content: String,
        toolId: String,
        toolName: String,
        ext: String = "txt",
    ): String {
        val dir = ensureToolsDir(context, sessionId)
        val fileName = "${sanitize(toolName)}_${shortToolId(toolId)}.$ext"
        val file = File(dir, fileName)
        return try {
            file.writeText(content)
            "$LINUX_OFFLOADS_DIR/tools/$fileName"
        } catch (e: Exception) {
            AppLogger.warning(TAG, "offloadContent failed: ${e.message}")
            ""
        }
    }

    /**
     * Write tool image bytes to disk and return the Linux-visible path.
     * Extension derived from MIME type — falls through to `.bin` for
     * unrecognised types so the file_read path still resolves something
     * the model can preview.
     */
    fun offloadImage(
        context: Context,
        sessionId: String,
        bytes: ByteArray,
        toolId: String,
        mimeType: String,
    ): String {
        val ext = when (mimeType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "bin"
        }
        val dir = ensureToolsDir(context, sessionId)
        val fileName = "image_${shortToolId(toolId)}.$ext"
        val file = File(dir, fileName)
        return try {
            file.writeBytes(bytes)
            "$LINUX_OFFLOADS_DIR/tools/$fileName"
        } catch (e: Exception) {
            AppLogger.warning(TAG, "offloadImage failed: ${e.message}")
            ""
        }
    }

    /**
     * Build the in-history stub that replaces an offloaded part. Format
     * is identical to iOS so a session opened on either platform shows
     * the same `[CONTEXT OFFLOADED] …` text where the real bytes used
     * to be.
     */
    fun stub(approxTokens: Int, byteCount: Int, linuxPath: String): String =
        "$OFFLOADED_PREFIX Content (~$approxTokens tokens, $byteCount bytes) saved to: $linuxPath\n" +
            "Use file_read tool to retrieve if needed."

    /**
     * [T-offload-stub-system-reminder] (GH#374) The notice handed to the model in
     * place of a `file_write` / `file_edit` payload moved to disk. Byte-identical
     * to iOS `offloadedStubNotice` so a session opened on either platform reads
     * the same text.
     *
     * Used for a pruned tool ARGUMENT specifically. [stub] still covers tool
     * RESULTS and image parts, which the model reads as observations rather than
     * as arguments it might resend.
     */
    fun prunedArgumentNotice(approxTokens: Int, byteCount: Int, linuxPath: String): String =
        "$SYSTEM_REMINDER_OPEN\n" +
            "$SYSTEM_NOTICE_TAG The original content (~$approxTokens tokens, " +
            "$byteCount bytes) of this tool argument has been pruned to save context " +
            "and offloaded to: $linuxPath\n\n" +
            "CRITICAL: This is a placeholder notice, NOT actual file content. NEVER pass " +
            "this placeholder or reuse it in subsequent file_write or file_edit calls. " +
            "If you need the original content, call file_read on the offloaded path first.\n" +
            "</system-reminder>"

    /**
     * [T-offload-placeholder-write-guard] (GH#374) True when [value] is an
     * offload placeholder rather than real content.
     *
     * The offload pass rewrites a historical `file_write`'s `content` argument
     * to [stub] to reclaim its tokens, and that rewritten call is what the model
     * sees from then on. A call re-issued from it — a retry, a resumed pending
     * call, the model copying its own earlier call — therefore carries this
     * ~130-character reference where the file body used to be, and the write
     * tools cannot tell the difference: the arguments are complete and
     * well-formed, so the placeholder lands on disk over the user's real file
     * and is reported as a success whose byte count matches the placeholder.
     *
     * Leading whitespace is trimmed so a payload a provider prefixed with a
     * newline is still caught, and the test is a PREFIX so genuine content that
     * merely mentions the marker mid-body still writes.
     */
    fun isOffloadPlaceholder(value: String): Boolean {
        val trimmed = value.trimStart()
        // Current format: the <system-reminder> envelope AND the notice tag.
        if (trimmed.startsWith(SYSTEM_REMINDER_OPEN) && trimmed.contains(SYSTEM_NOTICE_TAG)) {
            return true
        }
        // Legacy format. NOT transitional: it is still present in every session
        // persisted before this change, and in history synced from a peer on an
        // older build, so these keep arriving for as long as such histories do.
        return trimmed.startsWith(OFFLOADED_PREFIX)
    }

    /**
     * The model-facing refusal for [isOffloadPlaceholder]. [field] names the
     * argument at fault so the model knows which one to re-send.
     */
    fun placeholderWriteRefusal(field: String, path: String): String =
        "Error: '$field' is an offload placeholder, not real " +
            "content — the original text was moved out of context to free tokens and only " +
            "this reference remains in the conversation. Nothing was written to $path; the " +
            "file is unchanged. Use file_read on the path named inside the placeholder to " +
            "recover the real content, then re-issue this write with it."

    /**
     * [T-android-offload-readback] (GH#343) If [content] is a `file_read` of a
     * file we previously offloaded, return that file's path; else null.
     *
     * The problem this solves: the offload scan skips a part that already
     * `startsWith(OFFLOADED_PREFIX)`, but when the agent reads the offloaded
     * file back, `FileReadTool` prepends its own header —
     * `[<path> | <n> bytes | <m> lines | showing a-b of c]` — so the result
     * starts with `[/var/minis/offloads/…`, the prefix check misses, and the
     * content is offloaded AGAIN into a second file holding identical bytes.
     *
     * Note the loop is bounded, contrary to the original report: the stub that
     * replaces it is ~130 chars, well under the scan's 500-char floor, so the
     * copy is not itself re-offloaded. The cost is a redundant file per
     * readback, not unbounded growth.
     *
     * Returning the PATH rather than a boolean is what lets the caller re-stub
     * against the file that already exists instead of writing a duplicate —
     * and, unlike "skip anything read from the offloads dir", it keeps the
     * content compressible, so deliberately re-reading a big offloaded file to
     * analyse it in pieces does not pin those bytes in context for the rest of
     * the session.
     *
     * Only the caller can say whether this came from `file_read`; that is
     * checked there via [AgentContentPart.ToolResult.name], which is already
     * populated on both the live and the rehydrated path.
     */
    fun offloadReadbackPath(content: String): String? {
        if (!content.startsWith("[")) return null
        val close = content.indexOf(']')
        if (close <= 1) return null
        val header = content.substring(1, close)
        // Header shape is `<path> | <n> bytes | …`; the path is the first field.
        val path = header.substringBefore('|').trim()
        if (path.isEmpty()) return null
        // Anchor on the directory boundary so a sibling like
        // `/var/minis/offloads-backup/x` cannot match.
        if (path != LINUX_OFFLOADS_DIR && !path.startsWith("$LINUX_OFFLOADS_DIR/")) return null
        return path
    }

    private const val TAG = "ContextOffload"
}

// ─── Merged from ContextOverflowGuard.kt ──────────────────────────────────────────────────

/**
 * [T-android-context-overflow-selfheal] (GH#352) Recognises the one provider
 * error a session cannot recover from on its own.
 *
 * When a request exceeds the model's context window the provider answers
 * `[400] maximum context length is 1048576 … requested ≈1090600 tokens`, and
 * because the offending content is already persisted in the session's history
 * the SAME request is rebuilt on every retry and every fallback model. The
 * session is wedged permanently; the reporter's only workaround was starting a
 * new chat.
 *
 * Compaction does not save it either: the content that overflowed is usually a
 * single oversized part, and compaction summarises text — it does not remove
 * the one part that is 100x everything else combined.
 *
 * So this is matched narrowly and acted on narrowly. It is matched on TEXT,
 * which is normally unreliable, but here the consequences are bounded in both
 * directions and there is no alternative signal:
 *
 *   - false positive: one oversized part is offloaded to disk and replaced by
 *     a stub naming the file. The content is not lost and the turn proceeds.
 *   - false negative: today's behaviour, unchanged.
 *
 * Deliberately NOT matched on status alone. A bare 400 covers malformed JSON,
 * content policy, unknown parameters — none of which offloading would fix, and
 * silently mutating history for them would be worse than the error.
 */
object ContextOverflowGuard {

    /** Statuses a context-overflow refusal arrives on. */
    private val OVERFLOW_STATUSES = setOf(400, 413)

    /**
     * Phrases that identify the refusal as being about LENGTH.
     *
     * Every entry names size explicitly; none of them can match a policy
     * refusal or a parameter complaint. Both English and the Chinese wording
     * relays commonly translate to are listed.
     */
    private val OVERFLOW_MARKERS = listOf(
        "maximum context length",
        "context length exceeded",
        "context_length_exceeded",
        "reduce the length of the messages",
        "too many tokens",
        "prompt is too long",
        "request too large",
        "exceeds the maximum",
        "input is too long",
        // [T-ctx-overflow-marker-parity] iOS ContextPolicy.overflowMarkers had
        // these two and Android did not: OpenMinis#133 arrives as "[400] Your
        // input exceeds the context window…", and missing it meant no ratio
        // raise, no GH#352 self-heal, and a pinned session silently falling
        // back to a different model. Keep this list identical to iOS.
        "exceeds the context window",
        "input exceeds the context",
        // Anthropic: "input length and `max_tokens` exceed context limit: A + B > W".
        "exceed context limit",
        "上下文长度",
        "超出最大长度",
        "内容过长",
    )

    /** Generic size phrases that also appear in a byte-size (not token) rejection. */
    private val BYTE_AMBIGUOUS_MARKERS = setOf("exceeds the maximum", "request too large")

    /**
     * True when [detail] on [status] is a context-overflow refusal, i.e. the
     * request was too big rather than wrong.
     */
    fun isContextOverflow(status: Int?, detail: String?): Boolean {
        if (status == null || status !in OVERFLOW_STATUSES) return false
        val text = detail?.lowercase() ?: return false
        val hits = OVERFLOW_MARKERS.filter { text.contains(it) }
        if (hits.isEmpty()) return false
        // [T-ctx-overflow-marker-parity] Anthropic's 413 "[request_too_large]
        // Request exceeds the maximum allowed number of bytes." is a BYTE-size
        // limit (usually images/attachments), not a token overflow: raising the
        // token calibration ratio for it would poison every later size judgement.
        // Its only hits are the generic size phrases, so on byte wording those
        // two do not count; a token-specific phrase still does. Same rule on iOS.
        if (text.contains("bytes")) return hits.any { it !in BYTE_AMBIGUOUS_MARKERS }
        return true
    }
}

// ─── Merged from DeviceIdentity.kt ──────────────────────────────────────────────────

/**
 * Stable per-install device identity. Mirrors iOS `DeviceIdentity` minus the
 * cross-end iCloud Keychain sync (Android has no equivalent; spec §4.4 calls
 * this out). The uuid survives app kill but not uninstall — that's the most
 * privacy-friendly stable id Android offers without relying on
 * `Settings.Secure.ANDROID_ID` (which some OEMs reset and which Play policy
 * restricts).
 *
 * Usage patterns across the app:
 *   - `zoneName` — namespace any future multi-device sync records.
 *   - `deviceName` — friendly "Pixel 8 · A3F7"-style label for debug logs
 *     and the provider-account UI (future).
 *   - `deviceId` — opaque UUID for any "my-device" disambiguation.
 */
object DeviceIdentity {
    private const val PREFS_NAME = "minis_device_identity"
    private const val KEY_DEVICE_ID = "deviceId"

    @Volatile private var cached: String? = null

    fun deviceId(context: Context): String {
        cached?.let { return it }
        val prefs = encryptedPrefs(context)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) {
            cached = existing
            return existing
        }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, fresh).apply()
        cached = fresh
        return fresh
    }

    /** "Pixel 8 Pro · A3F7" — [displayName] plus the last four chars of [deviceId]. */
    fun deviceName(context: Context): String {
        val shortId = deviceId(context).takeLast(4).uppercase()
        return "${displayName(context)} · $shortId"
    }

    // -- User-set device name ([T-android-backup-device-name-setting]) --

    private const val NAME_PREFS = "minis_device_name"
    const val CUSTOM_NAME_KEY = "device.customName"
    /** Same cap as iOS `DeviceIdentity.customNameMaxLength`. */
    const val CUSTOM_NAME_MAX_LENGTH = 48

    /**
     * A name the user typed in Backup settings, or null. It leads every backup
     * filename and is the manifest's `device_name`, so two phones backing up
     * into one folder can be told apart — the iOS setting of the same name.
     *
     * Plain prefs, not the encrypted store holding [deviceId]: it is a
     * preference, not a secret, and a keystore failure must not lose it.
     */
    fun customName(context: Context): String? =
        context.getSharedPreferences(NAME_PREFS, Context.MODE_PRIVATE)
            .getString(CUSTOM_NAME_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    /** Blank clears the override; values are trimmed and length-capped. */
    fun setCustomName(context: Context, value: String?) {
        val trimmed = value?.trim().orEmpty()
        val prefs = context.getSharedPreferences(NAME_PREFS, Context.MODE_PRIVATE).edit()
        if (trimmed.isEmpty()) prefs.remove(CUSTOM_NAME_KEY)
        else prefs.putString(CUSTOM_NAME_KEY, trimmed.take(CUSTOM_NAME_MAX_LENGTH))
        prefs.apply()
    }

    /**
     * What [displayName] falls back to: the name set in system Settings ▸
     * About phone (defaults to the model, e.g. "Pixel 6"), else the
     * manufacturer + model label. Shown as the field's placeholder.
     */
    fun automaticName(context: Context): String {
        val system = runCatching {
            android.provider.Settings.Global.getString(
                context.contentResolver, android.provider.Settings.Global.DEVICE_NAME,
            )
        }.getOrNull()?.trim()
        if (!system.isNullOrEmpty()) return system
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.titlecase() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    }

    /** The user's name if set, otherwise [automaticName]. */
    fun displayName(context: Context): String = customName(context) ?: automaticName(context)

    fun osVersion(): String = "Android ${Build.VERSION.RELEASE}"

    /** Stable sync-zone namespace — mirrors iOS `"device-\(deviceId)"`. */
    fun zoneName(context: Context): String = "device-${deviceId(context)}"

    // T-android-keystore-aead-fail: self-healing wrapper handles
    // master-key invalidation on Samsung One UI / Android 16.
    private fun encryptedPrefs(context: Context) =
        com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, PREFS_NAME)
}

// ─── Merged from EnhancedCachePrefs.kt ──────────────────────────────────────────────────

/**
 * [T-android-enhanced-cache] Persists whether the user has acknowledged the
 * one-time Enhanced Cache confirmation dialog.
 *
 * The Enhanced Cache *toggle state* itself is per-ViewModel memory only (not
 * persisted — mirrors iOS `AIChatViewModel.enhancedCacheEnabled`). Only the
 * "user has seen and accepted the extra-billing warning" flag is durable, so
 * the confirmation AlertDialog appears exactly once per install. Mirrors iOS
 * UserDefaults key `enhancedCacheConfirmed`.
 */
object EnhancedCachePrefs {
    private const val PREFS = "minis_enhanced_cache_prefs"
    private const val KEY_CONFIRMED = "enhancedCacheConfirmed"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isConfirmed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONFIRMED, false)

    fun setConfirmed(context: Context) {
        prefs(context).edit().putBoolean(KEY_CONFIRMED, true).apply()
    }
}

// ─── Merged from EnvVarPrivacyStore.kt ──────────────────────────────────────────────────

/**
 * Persists the user's "Privacy Mode" preference for shell-execute output.
 *
 * When enabled, [EnvVarRedactor] masks any environment variable value
 * that appears verbatim in a shell tool's stdout/stderr before that
 * text is fed back to the model. The user still sees the unmasked
 * output in chat; only the bytes flowing into the agent's context are
 * rewritten.
 *
 * Default OFF — opt-in to avoid surprising users who rely on echoing
 * values back during debugging.
 *
 * Mirrors iOS `EnvVarPrivacyStore`.
 */
object EnvVarPrivacyStore {
    private const val PREFS = "envvar_privacy"
    private const val KEY = "privacy_mode_enabled"
    /** Default ON — secrets stay out of the model's context unless the user opts out. */
    private const val DEFAULT_ENABLED = true

    private var prefs: SharedPreferences? = null

    private val _enabled = MutableStateFlow(DEFAULT_ENABLED)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Hot-path read used by the redactor before any work. */
    val isEnabled: Boolean get() = _enabled.value

    /** Call once early — from MinisApp.onCreate. Idempotent. */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _enabled.value = if (p.contains(KEY)) p.getBoolean(KEY, DEFAULT_ENABLED) else DEFAULT_ENABLED
    }

    /** UI toggle. Persists eagerly. */
    fun setEnabled(value: Boolean) {
        prefs?.edit()?.putBoolean(KEY, value)?.apply()
        _enabled.value = value
    }
}

// ─── Merged from EnvVarRedactor.kt ──────────────────────────────────────────────────

/**
 * Masks env-var values that leaked into shell tool output before they
 * reach the model.
 *
 * Mask rule: `len < 8` → all `*`; `len >= 8` → first 2 + (len-4) `*` +
 * last 2 (e.g. `sk-1********ajhks`).
 *
 * Matching is plain `String.replace` — no regex — to avoid ReDoS on
 * attacker-influenced output. Values with length `<= 4` are skipped
 * to avoid mangling unrelated text — short common strings (e.g.
 * `"true"`, `"data"`, `"http"`) frequently collide with normal output,
 * and replacing every occurrence with `*` would be more disruptive
 * than leaving them visible.
 *
 * Mirrors iOS `EnvVarRedactor`.
 */
object EnvVarRedactor {
    const val MIN_MATCH_LEN = 5

    /**
     * English-only system reminder appended to a tool result when at
     * least one redaction occurred. Single string so it survives
     * downstream trimming/normalization, with explicit guidance to the
     * model on how to operate without echoing secrets.
     */
    const val SYSTEM_REMINDER = "<system-reminder>Privacy mode is ON: one or more environment variable values were detected in this output and have been masked. Prefer running commands that consume secrets via environment variable references (e.g. `curl -H \"Authorization: Bearer \$API_KEY\" ...`) rather than echoing them. If the user needs to inspect raw values, ask them to disable Privacy Mode at Settings → Environment Variables.</system-reminder>"

    /**
     * Static handoff so non-DI call sites (e.g. ShellExecutor on a
     * background coroutine) can reach the repository without plumbing
     * it through every layer. Set once from [com.openminis.app.MinisApp.onCreate].
     */
    @Volatile
    var envVarRepository: EnvVarRepository? = null

    fun mask(value: String): String {
        val n = value.length
        if (n < 8) return "*".repeat(n)
        return value.substring(0, 2) + "*".repeat(n - 4) + value.substring(n - 2, n)
    }

    /**
     * Redact [output] against the given env-var values. Returns the
     * rewritten string and the number of distinct values that produced
     * at least one replacement.
     *
     * Values are de-duplicated and processed longest-first so that
     * `BAR` appearing as a substring of a longer value `FOOBAR` does
     * not pre-empt the longer match.
     */
    fun redact(output: String, values: Collection<String>): Pair<String, Int> {
        val candidates = values
            .filter { it.length >= MIN_MATCH_LEN }
            .toSet()
            .sortedByDescending { it.length }
        if (candidates.isEmpty()) return output to 0

        var current = output
        var hits = 0
        for (v in candidates) {
            if (current.contains(v)) {
                current = current.replace(v, mask(v))
                hits++
            }
        }
        return current to hits
    }

    /**
     * Convenience wrapper — pulls values from the repository (if set)
     * and returns the possibly reminder-suffixed output plus the hit
     * count. No-op when Privacy Mode is disabled or the repository
     * isn't wired yet (e.g. very early in app boot).
     */
    fun redactIfEnabled(output: String): Pair<String, Int> {
        if (!EnvVarPrivacyStore.isEnabled) return output to 0
        val repo = envVarRepository ?: return output to 0
        // [T-envvar-disable] Disabled variables included: a value can still
        // surface (a terminal started before it was disabled, a file the agent
        // reads), and disabling a secret must not stop masking it.
        val values = repo.allValuesForRedaction().filter { it.isNotEmpty() }
        if (values.isEmpty()) return output to 0
        val (masked, hits) = redact(output, values)
        if (hits == 0) return masked to 0
        return (masked + "\n\n" + SYSTEM_REMINDER) to hits
    }
}

// ─── Merged from FastModePrefs.kt ──────────────────────────────────────────────────

/**
 * [T-speed-tier] The speed the user asked for. Wire values are what the
 * Responses API takes in `service_tier`; STANDARD omits the key entirely.
 *
 * FAST is `"priority"`, not `"fast"`: "Fast" is only the UI name (openai/codex
 * `ServiceTier::Fast` serializes to "priority"), and a literal "fast" is
 * rejected with HTTP 400 (CLIProxyAPI issue #5924).
 */
enum class SpeedTier(val storageValue: String, val wireValue: String?) {
    STANDARD("off", null),
    FAST("fast", "priority"),
    ULTRAFAST("ultrafast", "ultrafast"),
    ;

    companion object {
        fun fromStorage(value: String?): SpeedTier? = entries.firstOrNull { it.storageValue == value }
    }
}

/**
 * [T-codex-fast-mode] App-level persisted speed choice (was a Fast Mode
 * boolean mirroring iOS UserDefaults key `codexFastModeEnabled`, commit
 * fb671083). Durable and shared across sessions: switching to another chat
 * reads the same choice.
 *
 * The provider layer has no Context, and the choice is read at REQUEST-BUILD
 * time so a change applies to the very next request of an ongoing session —
 * including offload / title-gen calls that never pass through the
 * ChatViewModel. To make that possible, [prime] captures the application
 * context once at app startup (MinisApp.onCreate) and warms a volatile cache;
 * the context-free [tier] / [isEnabled] are then safe from any request builder.
 *
 * [T-speed-tier] Storage went from a boolean to three states:
 *  - the tier lives under [KEY_TIER] as "off" / "fast" / "ultrafast";
 *  - an install that only has the legacy boolean [KEY_LEGACY_ENABLED] is
 *    migrated in [prime] (true → FAST, false/absent → STANDARD) and the result
 *    written to [KEY_TIER], so the migration runs once;
 *  - every write keeps the legacy boolean in step (true for FAST and
 *    ULTRAFAST), so a downgrade to a build that only reads the boolean still
 *    sees Fast Mode as the user left it instead of silently turning it off.
 * An unreadable [KEY_TIER] value (a future build's new tier) falls back to the
 * legacy boolean for the same reason.
 */
object FastModePrefs {
    private const val PREFS = "minis_fast_mode_prefs"
    private const val KEY_LEGACY_ENABLED = "codexFastModeEnabled"
    private const val KEY_TIER = "codexSpeedTier"
    /** [T-android-ultrafast-align] iOS `catalogUltrafastKey`. */
    private const val KEY_CATALOG_ULTRAFAST = "codexCatalogUltrafastModels"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedTier: SpeedTier = SpeedTier.STANDARD

    @Volatile
    private var cachedCatalogUltrafast: List<String> = emptyList()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The migration rule, kept pure for unit tests. [storedTier] is the raw
     * [KEY_TIER] value (null when absent), [legacyEnabled] the legacy boolean
     * (null when absent).
     */
    internal fun resolveStoredTier(storedTier: String?, legacyEnabled: Boolean?): SpeedTier =
        SpeedTier.fromStorage(storedTier)
            ?: if (legacyEnabled == true) SpeedTier.FAST else SpeedTier.STANDARD

    /** Capture the app context, migrate the legacy boolean once, warm the cache. Called from MinisApp.onCreate. */
    fun prime(context: Context) {
        appContext = context.applicationContext
        val p = prefs(context)
        val stored = p.getString(KEY_TIER, null)
        val legacy = if (p.contains(KEY_LEGACY_ENABLED)) p.getBoolean(KEY_LEGACY_ENABLED, false) else null
        val tier = resolveStoredTier(stored, legacy)
        cachedTier = tier
        cachedCatalogUltrafast = p.getString(KEY_CATALOG_ULTRAFAST, null).orEmpty()
            .split(',').filter { it.isNotBlank() }
        if (stored == null) {
            p.edit().putString(KEY_TIER, tier.storageValue).apply()
        }
    }

    /**
     * Context-free read for request builders: the tier the USER chose. The
     * tier actually sent also depends on the model (see [SpeedTiers.effective]).
     * STANDARD before [prime] has ever run — matches a fresh install, and prime
     * runs before any request can be built.
     */
    fun tier(): SpeedTier = cachedTier

    /** True for FAST and ULTRAFAST: the user asked for a faster tier than standard. */
    fun isEnabled(): Boolean = cachedTier != SpeedTier.STANDARD

    fun setTier(context: Context, tier: SpeedTier) {
        cachedTier = tier
        prefs(context).edit()
            .putString(KEY_TIER, tier.storageValue)
            .putBoolean(KEY_LEGACY_ENABLED, tier != SpeedTier.STANDARD)
            .apply()
    }

    /**
     * [T-android-ultrafast-align] Lowercased ids whose Codex catalogue entry
     * declares an "ultrafast" tier, as of the last discovery (iOS
     * `catalogUltrafastKey`). Persisted so a request made before this launch's
     * discovery still knows it; empty until a discovery has run.
     */
    fun catalogUltrafastIds(): List<String> = cachedCatalogUltrafast

    /** Written by every Codex discovery (network or cache hit): the whole list, replacing the last one. */
    fun recordCatalogUltrafast(context: Context?, ids: List<String>) {
        val lowered = ids.map { it.lowercase() }.distinct()
        cachedCatalogUltrafast = lowered
        val ctx = context ?: appContext ?: return
        prefs(ctx).edit().putString(KEY_CATALOG_ULTRAFAST, lowered.joinToString(",")).apply()
    }

    /**
     * [T-android-xai-priority] Set ONLY the in-memory cache, with no Context and
     * no persistence. Exists so JVM unit tests can exercise the real read path
     * that request builders consult — there is no Robolectric in this module,
     * so the Context-taking setter is unusable there. Not for production use: a
     * write here is lost on restart.
     */
    @androidx.annotation.VisibleForTesting
    internal fun setCachedEnabledForTest(enabled: Boolean) {
        cachedTier = if (enabled) SpeedTier.FAST else SpeedTier.STANDARD
    }

    @androidx.annotation.VisibleForTesting
    internal fun setCachedTierForTest(tier: SpeedTier) {
        cachedTier = tier
    }
}

/**
 * [T-speed-tier] Which tiers a model may use, and the tier a request actually
 * sends.
 */
object SpeedTiers {
    /**
     * FALLBACK Ultrafast whitelist, matched as a lowercased id prefix (iOS
     * `ultrafastModelPrefixes`).
     *
     * [T-android-ultrafast-align] GPT-6 Astra only. OpenAI's Codex speed docs
     * list Ultrafast as "GPT-6 Astra only" (Pro and eligible Enterprise/Edu
     * plans); gpt-6.1-sol was on this list from DevDay coverage alone and is not
     * offered. An account without the entitlement still gets a 200 and is served
     * at Standard; the echoed service_tier cannot tell the two apart.
     *
     * Callers must also be on the Responses path (Codex OAuth or a
     * useResponsesAPI instance); xAI never gets Ultrafast (Priority only).
     */
    internal val ULTRAFAST_MODEL_PREFIXES = listOf("gpt-6-astra")

    /** The whitelist check alone (by id). Prefer the [LLMModel] overload. */
    fun supportsUltrafast(modelId: String): Boolean {
        val id = modelId.lowercase()
        return ULTRAFAST_MODEL_PREFIXES.any { id.startsWith(it) }
    }

    /**
     * Whether the Codex catalogue declares "ultrafast" for [model]: its own
     * catalogue entry ([LLMModel.serviceTiers]), or the account-wide list the
     * last discovery recorded ([catalogIds], iOS `catalogUltrafastKey`), which
     * also covers the same id reached through another instance.
     */
    fun catalogDeclaresUltrafast(
        model: com.openminis.app.data.model.LLMModel,
        catalogIds: Collection<String> = FastModePrefs.catalogUltrafastIds(),
    ): Boolean =
        model.serviceTiers?.any { it == SpeedTier.ULTRAFAST.wireValue } == true ||
            model.id.lowercase() in catalogIds

    /**
     * Whether [model] may use Ultrafast: the catalogue declares it, OR the id is
     * whitelisted (iOS `supportsUltrafast`).
     *
     * [T-android-ultrafast-align] User decision, replacing the catalogue-first
     * rule of T-ultrafast-catalog-tiers (where a catalogue that described the
     * model without "ultrafast" was final): the whitelist applies even then.
     * The account's catalogue today lists only "priority" for every model, and
     * Astra is still to offer Ultrafast.
     */
    fun supportsUltrafast(
        model: com.openminis.app.data.model.LLMModel,
        catalogIds: Collection<String> = FastModePrefs.catalogUltrafastIds(),
    ): Boolean = catalogDeclaresUltrafast(model, catalogIds) || supportsUltrafast(model.id)

    /**
     * The tier to send (and to show) for [chosen] on [model]. Ultrafast on a
     * model that may not use it (see [supportsUltrafast]), or on a path that
     * cannot carry it ([ultrafastPathAllowed] false), falls back to FAST. The
     * user's stored choice is left alone, so moving back to an allowed model
     * restores Ultrafast.
     */
    fun effective(
        chosen: SpeedTier,
        model: com.openminis.app.data.model.LLMModel,
        ultrafastPathAllowed: Boolean = true,
        catalogIds: Collection<String> = FastModePrefs.catalogUltrafastIds(),
    ): SpeedTier =
        if (chosen == SpeedTier.ULTRAFAST && !(ultrafastPathAllowed && supportsUltrafast(model, catalogIds))) {
            SpeedTier.FAST
        } else {
            chosen
        }

    /**
     * [T-android-ultrafast-align] Port of iOS
     * `SpeedTierSettings.ultrafastUnsupportedNote`: a log line for a request
     * where Ultrafast was chosen but is not what the model / account supports,
     * or null when there is nothing to report (another tier chosen, Ultrafast
     * declared by the catalogue, or a non-gpt id - xAI has its own "priority"
     * path, so a line there would be wrong). Two cases:
     *  - the model is not offered Ultrafast: the request goes out as Fast
     *    ("priority");
     *  - the model is offered only by the whitelist and the catalogue does not
     *    declare it: "ultrafast" is sent, but an account without the
     *    entitlement gets a 200 served at Standard, and the echo cannot tell.
     * The caller logs it under "[SpeedTier]", once per request.
     */
    fun ultrafastUnsupportedNote(
        chosen: SpeedTier,
        model: com.openminis.app.data.model.LLMModel,
        catalogIds: Collection<String> = FastModePrefs.catalogUltrafastIds(),
    ): String? {
        if (chosen != SpeedTier.ULTRAFAST || !model.id.contains("gpt", ignoreCase = true)) return null
        val declared = catalogIds.toList()
        val sent = effective(chosen, model, catalogIds = catalogIds).wireValue ?: "none"
        if (!supportsUltrafast(model, catalogIds)) {
            return "Ultrafast not supported for model=${model.id} (whitelist=$ULTRAFAST_MODEL_PREFIXES, " +
                "catalogue declares=$declared); sending service_tier=$sent"
        }
        if (!catalogDeclaresUltrafast(model, catalogIds)) {
            return "Ultrafast for model=${model.id} comes from the fallback whitelist only; the account catalogue " +
                "declares it for $declared - an account without the entitlement is likely served at Standard " +
                "(service_tier=$sent)"
        }
        return null
    }

    /**
     * The tier the chat UI shows for [chosen] on the active model:
     *  - [fastEligible] false → STANDARD (no speed control, no badge);
     *  - Ultrafast chosen but [ultrafastEligible] false → FAST, the same
     *    fallback [effective] applies to the request.
     */
    fun displayed(chosen: SpeedTier, fastEligible: Boolean, ultrafastEligible: Boolean): SpeedTier = when {
        !fastEligible -> SpeedTier.STANDARD
        chosen == SpeedTier.ULTRAFAST && !ultrafastEligible -> SpeedTier.FAST
        else -> chosen
    }
}

// ─── Merged from FileMentionIndex.kt ──────────────────────────────────────────────────

/**
 * @-mention file index for the chat composer. Mirrors iOS `FileMentionIndex`:
 * a 3-layer scanner that publishes candidates to the UI **as each layer
 * finishes** so the user sees session-local files immediately, then shared
 * roots, then (if mounts are attached) external mounts.
 *
 * ### Layers
 *   1. **Session roots** — `workspace/<sid>` + `attachments/<sid>`.
 *   2. **Shared roots** — `shared/`, `skills/`, `memory/`.
 *   3. **Mount roots** — each entry in [mountsProvider] (e.g. SAF-attached
 *      folders). Each mount always gets a self-entry so `@<mountName>` works.
 *
 * ### Scoring (for [matches])
 *   - basename equals query → 1000
 *   - basename starts with query → 800
 *   - basename contains query → 600
 *   - any path component contains → 400
 *   - full path contains → 200
 *   - else → filtered out
 *
 * Adjustments: `.mount` scope loses 10 points (prefer session-local), top-level
 * scope roots gain 50 points. Ties broken by `modifiedAt` descending, capped
 * at [DEFAULT_MATCH_LIMIT].
 */
class FileMentionIndex(
    private val filesDir: File,
    private val mountsProvider: () -> List<MountEntry> = { emptyList() },
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    constructor(context: Context) : this(File(context.filesDir, "minis-global"))

    /**
     * Scope priorities — `order` doubles as the empty-query default sort key
     * (lower wins) AND drives `rankBoost`'s scope tiebreaker on top of the
     * name-match score. The case order here mirrors the iOS counterpart in
     * `FileMentionIndex.swift`:
     *   skills > attachments > mount > shared > workspace > memory.
     *
     * `rankBoost` is added to the per-name score with a max delta (600) that
     * is smaller than the gap between adjacent name-match tiers (1000), so a
     * stronger name match in *any* scope still beats a weak match in a
     * higher-priority scope.
     */
    enum class Scope(val displayLabel: String, val order: Int, val rankBoost: Int) {
        SKILLS("skills", 0, 600),
        ATTACHMENTS("attachments", 1, 500),
        MOUNT("mount", 2, 400),
        SHARED("shared", 3, 300),
        WORKSPACE("workspace", 4, 200),
        MEMORY("memory", 5, 100),
    }

    data class MountEntry(val name: String, val root: File)

    data class Entry(
        /** Linux-visible path (what gets inserted into the composer). */
        val linuxPath: String,
        val scope: Scope,
        val mountName: String?,
        val modifiedAt: Long,
        val isDirectory: Boolean,
    ) {
        val basename: String get() = linuxPath.substringAfterLast('/').ifEmpty { linuxPath }
        val displayPath: String get() = linuxPath.removePrefix("/var/minis/")
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var lastScanSessionId: String? = null
    private var lastScanAt: Long = 0
    private var currentScanToken: UUID = UUID.randomUUID()
    private var currentJob: Job? = null

    /** Rescan if the session changed or the cache went stale. Idempotent. */
    fun refreshIfNeeded(sessionId: String) {
        val now = System.currentTimeMillis()
        val sameSession = lastScanSessionId == sessionId
        val fresh = now - lastScanAt < cacheTtlMs
        if (sameSession && fresh && _entries.value.isNotEmpty()) return

        lastScanSessionId = sessionId
        lastScanAt = now
        val token = UUID.randomUUID()
        currentScanToken = token
        currentJob?.cancel()
        currentJob = scope.launch { scan(sessionId, token) }
    }

    /** Force a rescan regardless of cache. */
    fun refresh(sessionId: String) {
        lastScanAt = 0
        refreshIfNeeded(sessionId)
    }

    private suspend fun scan(sessionId: String, token: UUID) {
        _isScanning.value = true
        val collected = mutableListOf<Entry>()
        try {
            // Layer 1: session-local roots.
            layerEntries(
                sessionId = sessionId,
                layers = listOf(
                    File(filesDir, "workspace/$sessionId") to Scope.WORKSPACE,
                    File(filesDir, "attachments/$sessionId") to Scope.ATTACHMENTS,
                ),
                linuxRootFor = { scope -> "/var/minis/${scope.displayLabel}/$sessionId" },
            ).let { newBatch ->
                collected += newBatch
                publish(token, collected)
            }

            // Layer 2: shared roots.
            layerEntries(
                sessionId = sessionId,
                layers = listOf(
                    File(filesDir, "shared") to Scope.SHARED,
                    File(filesDir, "skills") to Scope.SKILLS,
                    File(filesDir, "memory") to Scope.MEMORY,
                ),
                linuxRootFor = { scope -> "/var/minis/${scope.displayLabel}" },
            ).let { newBatch ->
                collected += newBatch
                publish(token, collected)
            }

            // Layer 3: mounts (each published individually so one slow mount
            // doesn't block the rest).
            var budget = GLOBAL_SCAN_BUDGET - collected.size
            for (mount in mountsProvider()) {
                if (currentScanToken != token) return
                // T219: align with PRoot bind path + iOS prompt wording.
                // Each mount lives under /var/minis/mounts/<name> inside the
                // sandbox; inserting that exact path keeps the agent's mental
                // model coherent across @-mention, prompt copy, and shell.
                val mountLinuxRoot = "/var/minis/mounts/${mount.name}"
                // Always inject the mount root so `@<name>` works even when budget is gone.
                collected += Entry(
                    linuxPath = mountLinuxRoot,
                    scope = Scope.MOUNT,
                    mountName = mount.name,
                    modifiedAt = mount.root.lastModified(),
                    isDirectory = true,
                )
                if (budget > 0) {
                    val mountBatch = scanDir(
                        root = mount.root,
                        linuxRoot = mountLinuxRoot,
                        scope = Scope.MOUNT,
                        mountName = mount.name,
                        maxDepth = MOUNT_SCAN_MAX_DEPTH,
                        budget = budget,
                    )
                    collected += mountBatch
                    budget -= mountBatch.size
                }
                publish(token, collected)
            }
        } finally {
            _isScanning.value = false
        }
    }

    private fun layerEntries(
        sessionId: String,
        layers: List<Pair<File, Scope>>,
        linuxRootFor: (Scope) -> String,
    ): List<Entry> {
        val out = mutableListOf<Entry>()
        for ((dir, scope) in layers) {
            if (!dir.isDirectory) continue
            val linuxRoot = linuxRootFor(scope)
            // Always include the root itself so `@workspace` / `@shared` can be referenced.
            out += Entry(
                linuxPath = linuxRoot,
                scope = scope,
                mountName = null,
                modifiedAt = dir.lastModified(),
                isDirectory = true,
            )
            out += scanDir(
                root = dir,
                linuxRoot = linuxRoot,
                scope = scope,
                mountName = null,
                maxDepth = Int.MAX_VALUE,
                budget = GLOBAL_SCAN_BUDGET,
            )
        }
        return out
    }

    private fun scanDir(
        root: File,
        linuxRoot: String,
        scope: Scope,
        mountName: String?,
        maxDepth: Int,
        budget: Int,
    ): List<Entry> {
        val out = ArrayList<Entry>()
        val rootPath = root.absolutePath
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.addLast(root to 0)
        while (queue.isNotEmpty() && out.size < budget) {
            val (dir, depth) = queue.removeFirst()
            if (depth > maxDepth) continue
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (out.size >= budget) break
                if (child.name.startsWith(".")) continue
                if (child.isDirectory && child.name in SKIP_DIR_NAMES) continue
                val relative = child.absolutePath.removePrefix(rootPath).removePrefix("/")
                if (relative.isEmpty()) continue
                val linuxPath = "$linuxRoot/$relative"
                out += Entry(
                    linuxPath = linuxPath,
                    scope = scope,
                    mountName = mountName,
                    modifiedAt = child.lastModified(),
                    isDirectory = child.isDirectory,
                )
                if (child.isDirectory && depth + 1 <= maxDepth) {
                    queue.addLast(child to depth + 1)
                }
            }
        }
        return out
    }

    private suspend fun publish(token: UUID, collected: List<Entry>) {
        if (currentScanToken != token) return
        // Deduplicate by linuxPath (later scans may revisit) and sort per defaultOrdering.
        val deduped = collected.associateBy { it.linuxPath }.values
        val sorted = deduped.sortedWith(defaultOrdering)
        withContext(Dispatchers.Main) {
            if (currentScanToken != token) return@withContext
            _entries.value = sorted
        }
    }

    /**
     * Rank entries against [query]. Empty query returns the freshest entries
     * in `defaultOrdering` (already applied to `_entries.value`).
     *
     * Score components (added together, biggest first):
     *   • name-match tier (0 / 1000 … 10000) — drives the primary order
     *   • Scope.rankBoost (0–600) — secondary preference between scopes
     *   • top-level scope-root bonus (0 / 50) — keeps a whole skill or
     *     mount above its children
     *   • recency micro-bonus (0–80) — newer wins on otherwise-equal items
     *
     * The smallest gap between adjacent name tiers (1000) is greater than
     * the maximum sum of all secondary signals (600 + 50 + 80 = 730), so a
     * stronger name match always wins regardless of scope.
     */
    fun matches(query: String, limit: Int = DEFAULT_MATCH_LIMIT): List<Entry> {
        val all = _entries.value
        if (query.isBlank()) return all.take(limit)
        val q = query.lowercase()

        val now = System.currentTimeMillis()
        val recencyDayCapMs: Long = 56L * 24 * 60 * 60 * 1000 // 8 weeks → 0..80

        val scored = mutableListOf<Pair<Int, Entry>>()
        for (entry in all) {
            val base = entry.basename.lowercase()
            val path = entry.linuxPath.lowercase()
            val stem = base.substringBeforeLast('.', missingDelimiterValue = base)

            // Tier-based name match — pick the strongest applicable tier.
            val nameScore = when {
                base == q -> 10_000                                        // exact full name
                stem.isNotEmpty() && stem == q -> 9_000                    // exact stem (basename minus ext)
                base.startsWith(q) -> 7_000                                // prefix match
                basenameWordBoundaryContains(base, q) -> 6_000             // word-boundary contains
                q in base -> 4_000                                         // generic contains in basename
                anyPathComponentMatches(path, q) -> 2_000                  // ancestor directory contains
                q in path -> 1_000                                         // anywhere in full path
                else -> 0
            }
            if (nameScore == 0) continue

            // Scope priority (max 600 — well below the 1000 tier gap).
            val scopeScore = entry.scope.rankBoost

            // Top-level scope-root bonus: typing `@codex` should surface the
            // whole `skills/codex-image` directory above its children.
            val topLevelBonus = if (isTopLevelScopeRoot(entry)) 50 else 0

            // Recency micro-bonus (≤ 80). Linear decay over 8 weeks.
            val ageMs = (now - entry.modifiedAt).coerceAtLeast(0L)
            val recencyBonus = (80.0 *
                (1.0 - (ageMs.toDouble() / recencyDayCapMs.toDouble()).coerceIn(0.0, 1.0)))
                .toInt()
                .coerceAtLeast(0)

            scored += (nameScore + scopeScore + topLevelBonus + recencyBonus) to entry
        }
        return scored
            .sortedWith(
                compareByDescending<Pair<Int, Entry>> { it.first }
                    // Tiebreak: prefer higher-priority scope, then fresher,
                    // then shorter path (a "closer" file).
                    .thenBy { it.second.scope.order }
                    .thenByDescending { it.second.modifiedAt }
                    .thenBy { it.second.linuxPath.length }
            )
            .take(limit)
            .map { it.second }
    }

    /**
     * Word-boundary substring match for the basename. Returns true if
     * `query` appears at the start of a word fragment delimited by
     * `_`, `-`, ` `, `.`, `/` (e.g. matches `parser` in `markdown_parser.py`,
     * `web` in `image-web-search.md`). Helps elevate the kind of partial
     * match users intuitively expect over arbitrary-position substrings.
     */
    private fun basenameWordBoundaryContains(base: String, query: String): Boolean {
        if (query.isEmpty() || base.isEmpty()) return false
        val separators = setOf('_', '-', ' ', '.', '/')
        var i = 0
        while (i < base.length) {
            if (base.startsWith(query, startIndex = i)) return true
            // Advance to the next word boundary.
            val sep = (i until base.length).firstOrNull { separators.contains(base[it]) }
                ?: return false
            i = sep + 1
        }
        return false
    }

    /**
     * Substring search across path components — the same path may match
     * even when the basename doesn't (e.g. typing `@mounts` finds files
     * inside `/var/minis/mounts/...`).
     */
    private fun anyPathComponentMatches(path: String, query: String): Boolean {
        return path.split('/').any { it.contains(query) }
    }

    /**
     * Is this entry the immediate child of a scope root? e.g.
     * `/var/minis/skills/foo`, `/var/minis/mounts/bar`,
     * `/var/minis/shared/baz`. These first-class `@`-targets get a +50
     * ranking bonus so typing `@foo` surfaces the whole skill directory
     * above its individual files (mirrors iOS).
     */
    private fun isTopLevelScopeRoot(entry: Entry): Boolean {
        if (!entry.isDirectory) return false
        val tops = listOf(
            "/var/minis/skills/",
            "/var/minis/mounts/",
            "/var/minis/shared/",
        )
        for (top in tops) {
            if (entry.linuxPath.startsWith(top)) {
                val rest = entry.linuxPath.removePrefix(top)
                // First-level child only — must not contain a further '/'.
                return rest.isNotEmpty() && '/' !in rest
            }
        }
        return false
    }

    private val defaultOrdering: Comparator<Entry> = compareBy<Entry> { it.scope.order }
        .thenByDescending { isTopLevelScopeRoot(it) }
        .thenBy { it.isDirectory }
        .thenByDescending { it.modifiedAt }

    companion object {
        const val DEFAULT_CACHE_TTL_MS = 10L * 60 * 1000
        const val DEFAULT_MATCH_LIMIT = 100
        const val GLOBAL_SCAN_BUDGET = 5000
        const val MOUNT_SCAN_MAX_DEPTH = 3

        private val SKIP_DIR_NAMES = setOf(
            ".git", ".svn", ".hg",
            "node_modules", ".venv", "venv", "__pycache__",
            ".build", ".gradle", ".idea", ".tox",
            ".mypy_cache", ".pytest_cache", ".ruff_cache",
        )
    }
}

// ─── Merged from MemoryGlobalPrefs.kt ──────────────────────────────────────────────────

/**
 * Global default for the memory feature, persisted in SharedPreferences.
 *
 * Two-layer model (mirrors iOS):
 *   - **Global** (this file): default for newly-created sessions.
 *     Toggled from Settings → Memory. Pref key
 *     `memory.global.enabled`, default `true`.
 *   - **Per-session** (`ChatSessionEntity.memory_enabled`): override
 *     for a specific chat, toggled via `/memory` or
 *     `SessionMemorySheet`. Once a session has a row, that row's
 *     value wins; changing the global never retroactively touches
 *     existing sessions.
 *
 * When a fresh draft VM is mounted before any DB row exists, we read
 * the global default and seed `_memoryEnabled` with it. When
 * `ensureSession` materialises the row, we pass the same value through
 * to `ChatRepository.createSession(memoryEnabled = …)` so the DB
 * snapshot matches the seed.
 */
object MemoryGlobalPrefs {
    private const val PREFS = "minis_memory_prefs"
    private const val KEY_GLOBAL_ENABLED = "memory.global.enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isGlobalEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GLOBAL_ENABLED, true)

    fun setGlobalEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GLOBAL_ENABLED, enabled).apply()
    }
}

// ─── Merged from MountedFoldersStore.kt ──────────────────────────────────────────────────

/**
 * Store for user-mounted external folders (SAF-picked trees). Mirrors iOS
 * `MountedFoldersManager` with the following deliberate differences:
 *
 *   - iOS uses `URL.bookmarkData` + `startAccessingSecurityScopedResource`;
 *     Android uses tree URIs + `takePersistableUriPermission`. The URI
 *     survives process death and reboots once persisted, so there's no
 *     "activation" step — access is always available while the permission
 *     grant is held.
 *   - The shell-level bind-mount at `/var/minis/mounts/<name>` is NOT
 *     implemented in this pass. DocumentFile doesn't expose POSIX paths,
 *     so exposing these trees inside the PRoot / iSH rootfs needs a
 *     FUSE bridge or a periodic mirror pass. Spec §2.9.4 calls out the
 *     tradeoffs; we ship MVP scaffolding (persistence + CRUD + StateFlow)
 *     and leave the shell mount as a follow-up.
 *
 * Persistence: `filesDir/minis-config/mounted-folders.json`. The path is
 * intentionally outside `minis-global/` so it can't leak into the
 * DocumentsProvider-exposed tree.
 */
class MountedFoldersStore(private val context: Context) {

    @Serializable
    data class Entry(
        val id: String = UUID.randomUUID().toString(),
        var name: String,
        val sourceDisplayName: String,
        val treeUri: String,
        val createdAt: Long = System.currentTimeMillis(),
        var isWritable: Boolean = true,
        var userAllowWrite: Boolean = true,
        /**
         * Cached POSIX host path resolved from the SAF tree URI (e.g.
         * `/storage/emulated/0/Documents/Vault`). null when the URI
         * came from a non-externalstorage provider or resolution failed —
         * UI / coordinator should treat such entries as inactive.
         */
        var resolvedHostPath: String? = null,
    ) {
        /** Final effective writable = OS-level `isWritable` AND user intent. */
        val effectiveWritable: Boolean get() = isWritable && userAllowWrite
    }

    private val storeFile: File by lazy {
        File(context.filesDir, "minis-config/mounted-folders.json").apply {
            parentFile?.mkdirs()
        }
    }

    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /**
     * T219-5: fired after every CRUD operation that mutates the persisted
     * list. MinisApp wires this to `PRootKernel.applyMountedFoldersSnapshot`
     * so future shells pick up the new bind specs without a process restart.
     * Note that already-running proot processes are unaffected — proot is
     * one-shot and the next `shell_execute` is what carries the new mounts.
     */
    var onChange: (() -> Unit)? = null

    init {
        loadFromDisk()
    }

    /**
     * Persist a new tree URI as a mount. Caller is responsible for having
     * already called `contentResolver.takePersistableUriPermission(uri, …)`
     * — typically via the SAF picker result in [SafMountHelper.handlePickerResult].
     *
     * Resolves the SAF tree URI to a real POSIX path under
     * `/storage/emulated/0/...` (Option A — see T219 spec §1.3). Returns
     * null when:
     *   - the URI came from a non-externalstorage provider (Drive, Dropbox,
     *     etc. have no POSIX path PRoot can `-b` mount);
     *   - the resolved path doesn't exist or isn't readable by us;
     *   - the name is invalid / duplicate / cap reached.
     */
    suspend fun add(
        treeUri: Uri,
        customName: String,
        userAllowWrite: Boolean = true,
    ): Entry? = mutex.withLock {
        val name = sanitizeName(customName).takeIf { it.isNotEmpty() } ?: return@withLock null
        if (_entries.value.any { it.name.equals(name, ignoreCase = true) }) return@withLock null
        if (_entries.value.size >= MAX_MOUNTS) return@withLock null

        val resolvedHostPath = resolvePosixPath(treeUri, context) ?: run {
            AppLogger.warning(TAG, "add: rejected non-resolvable URI $treeUri")
            return@withLock null
        }

        val sourceDisplayName = DocumentsContract.getTreeDocumentId(treeUri)
            .substringAfterLast(':', treeUri.lastPathSegment.orEmpty())
            .ifEmpty { name }
        // OS-level writability driven by the actual filesystem (Option A path
        // is what PRoot will use, not the SAF grant). isWritePermission can
        // be true while a per-package scoped-storage rule still rejects open(2).
        val probedWritable = probeWritable(resolvedHostPath)
        val entry = Entry(
            name = name,
            sourceDisplayName = sourceDisplayName,
            treeUri = treeUri.toString(),
            isWritable = probedWritable,
            userAllowWrite = userAllowWrite,
            resolvedHostPath = resolvedHostPath,
        )
        _entries.value = _entries.value + entry
        saveToDisk(_entries.value)
        onChange?.invoke()
        AppLogger.info(
            TAG,
            "add: name=$name host=$resolvedHostPath writable=$probedWritable ${storageDiag(context)}",
        )
        entry
    }

    /**
     * One-line storage-access diagnostic for the mount log. Distinguishes the
     * two reasons a folder can read but not write: on Android 11+ it's All Files
     * Access; on Android 10 it's whether WRITE_EXTERNAL_STORAGE was actually
     * granted at runtime (legacy opt-in alone is not enough) and whether the
     * process still holds the legacy storage view.
     */
    private fun storageDiag(context: Context): String {
        val sdk = Build.VERSION.SDK_INT
        return if (sdk >= Build.VERSION_CODES.R) {
            "sdk=$sdk allFilesAccess=${Environment.isExternalStorageManager()}"
        } else {
            val read = context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val write = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            // Issue #118: Environment.isExternalStorageLegacy() is API 29, but this
            // branch covers everything below R (30) — i.e. our whole 26..28 floor.
            // Same defect as PRootKernel.storageAccessDiag; both are reachable from
            // the boot path, so an unguarded call is a NoSuchMethodError that kills
            // the process in Application.onCreate. Below 29 scoped storage doesn't
            // exist, so the legacy view is unconditionally in effect.
            val legacyView = if (sdk >= Build.VERSION_CODES.Q) {
                Environment.isExternalStorageLegacy().toString()
            } else {
                "n/a(pre-Q)"
            }
            "sdk=$sdk readGranted=$read writeGranted=$write legacyView=$legacyView"
        }
    }

    suspend fun remove(id: String): Boolean = mutex.withLock {
        val before = _entries.value
        val after = before.filterNot { it.id == id }
        if (after.size == before.size) return@withLock false
        // Release the persisted URI grant so the system stops listing
        // us under "apps with access" and the user can re-pick later.
        before.firstOrNull { it.id == id }?.let { e ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(e.treeUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        _entries.value = after
        saveToDisk(after)
        onChange?.invoke()
        true
    }

    suspend fun rename(id: String, newName: String): Boolean = mutex.withLock {
        val trimmed = sanitizeName(newName).takeIf { it.isNotEmpty() } ?: return@withLock false
        if (_entries.value.any { it.id != id && it.name.equals(trimmed, ignoreCase = true) }) {
            return@withLock false
        }
        _entries.value = _entries.value.map { e ->
            if (e.id == id) e.copy(name = trimmed) else e
        }
        saveToDisk(_entries.value)
        onChange?.invoke()
        true
    }

    suspend fun setUserAllowWrite(id: String, allow: Boolean): Boolean = mutex.withLock {
        var changed = false
        _entries.value = _entries.value.map { e ->
            if (e.id == id && e.userAllowWrite != allow) {
                changed = true
                e.copy(userAllowWrite = allow)
            } else e
        }
        if (changed) {
            saveToDisk(_entries.value)
            onChange?.invoke()
        }
        changed
    }

    /**
     * Re-probe OS-level writability against the actual host path. Called
     * on foreground resume so changes (user revoked permission, removed
     * the folder, removable storage unmounted, …) propagate into the UI
     * and the coordinator's bind specs. Entries whose `resolvedHostPath`
     * is null (non-externalstorage) stay marked non-writable.
     */
    suspend fun refreshWritability() = mutex.withLock {
        val before = _entries.value
        val after = before.map { e ->
            val probed = e.resolvedHostPath?.let { probeWritable(it) } ?: false
            if (probed != e.isWritable) e.copy(isWritable = probed) else e
        }
        if (after != before) {
            _entries.value = after
            saveToDisk(after)
            onChange?.invoke()
        }
    }

    /**
     * Decode a SAF tree URI into a POSIX host path PRoot can `-b` mount.
     *
     * Only accepts `com.android.externalstorage.documents` URIs — those
     * encode a `volume:relPath` document id where `volume` is either
     * `primary` (the device's internal shared storage) or a removable
     * storage UUID. Cloud providers (Drive, Dropbox, …) that return tree
     * URIs without a real filesystem mapping are rejected with a null
     * return so [add] can surface the "only on-device folders" error.
     *
     * Returns null on:
     *   - non-externalstorage authority,
     *   - unknown removable volume uuid,
     *   - resolved File doesn't exist / isn't a directory / unreadable.
     */
    suspend fun resolvePosixPath(treeUri: Uri, context: Context): String? =
        withContext(Dispatchers.IO) {
            val authority = treeUri.authority
            if (authority != EXTERNALSTORAGE_AUTHORITY) {
                AppLogger.warning(TAG, "resolvePosixPath: rejecting non-externalstorage authority=$authority")
                return@withContext null
            }
            val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return@withContext null
            val sep = docId.indexOf(':')
            val volume = if (sep < 0) docId else docId.substring(0, sep)
            val relPath = if (sep < 0) "" else docId.substring(sep + 1)

            val volumeRoot = resolveVolumeRoot(context, volume) ?: run {
                AppLogger.warning(TAG, "resolvePosixPath: unknown volume=$volume in docId=$docId")
                return@withContext null
            }
            val full = if (relPath.isEmpty()) File(volumeRoot)
                else File(volumeRoot, relPath)
            if (!full.exists() || !full.isDirectory) {
                AppLogger.warning(TAG, "resolvePosixPath: path missing or not dir: ${full.absolutePath}")
                return@withContext null
            }
            // Read probe: a resolvable dir the app can't actually readdir will
            // mount but show empty. Surface it now so the log explains the
            // eventual empty-folder report instead of leaving it silent.
            val children = full.list()
            if (children == null) {
                AppLogger.warning(
                    TAG,
                    "resolvePosixPath: ${full.absolutePath} canRead=${full.canRead()} but list()=null " +
                        "(readdir blocked by scoped storage) — mount will read EMPTY on this device",
                )
            } else {
                AppLogger.info(TAG, "resolvePosixPath: ${full.absolutePath} ok childCount=${children.size}")
            }
            full.absolutePath
        }

    /**
     * Try to create + delete a hidden probe file under [hostPath]. Captures
     * the same reality PRoot will see — `open(O_WRONLY|O_CREAT)` against
     * the real filesystem — so scoped-storage restrictions or freshly
     * revoked permissions reflect honestly in the badge.
     */
    fun probeWritable(hostPath: String): Boolean {
        val dir = File(hostPath)
        if (!dir.isDirectory) return false
        val probe = File(dir, ".minis-probe-${UUID.randomUUID()}")
        return runCatching {
            probe.outputStream().use { it.write(0) }
            true
        }.onFailure { e ->
            AppLogger.warning(TAG, "probeWritable: $hostPath not writable: ${e.message}")
        }.getOrDefault(false).also {
            runCatching { probe.delete() }
        }
    }

    private fun resolveVolumeRoot(context: Context, volume: String): String? {
        if (volume.equals("primary", ignoreCase = true)) {
            return Environment.getExternalStorageDirectory()?.absolutePath
        }
        // Removable storage — match by uuid via StorageManager (API 24+ for
        // storageVolumes, but `directory` is API 30+. Fall back gracefully
        // on older devices by walking /storage/<uuid>).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            sm?.storageVolumes?.firstOrNull { it.uuid?.equals(volume, ignoreCase = true) == true }
                ?.directory?.absolutePath?.let { return it }
        }
        val fallback = File("/storage/$volume")
        return if (fallback.isDirectory) fallback.absolutePath else null
    }

    private fun loadFromDisk() {
        if (!storeFile.isFile) return
        runCatching {
            val text = storeFile.readText()
            val parsed = JSON.decodeFromString<List<Entry>>(text)
            _entries.value = parsed
        }
    }

    private suspend fun saveToDisk(list: List<Entry>) = withContext(Dispatchers.IO) {
        runCatching {
            storeFile.writeText(JSON.encodeToString(list))
        }
    }

    private fun sanitizeName(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed == "." || trimmed == "..") return ""
        if (trimmed.contains('/') || trimmed.contains(' ')) return ""
        return trimmed.take(64)
    }

    companion object {
        const val MAX_MOUNTS = 10
        private const val TAG = "MountedFolders"
        private const val EXTERNALSTORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/**
 * SAF helper — call [buildPickerIntent] from an `ActivityResultContract`,
 * then pipe the resulting Uri back through [handlePickerResult] to persist
 * the permission grant before adding it to [MountedFoldersStore].
 */
object SafMountHelper {
    fun buildPickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
    }

    /**
     * Take a persistable permission on the picked tree URI so subsequent
     * app launches can still access it without another picker round-trip.
     * Returns true on success.
     */
    fun handlePickerResult(context: Context, treeUri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        true
    }.getOrDefault(false)

    /** Sugar over [DocumentsContract.getTreeDocumentId] for display purposes. */
    fun treeDisplayPath(uri: Uri): String =
        DocumentsContract.getTreeDocumentId(uri)
}

// ─── Merged from PayloadSizeAudit.kt ──────────────────────────────────────────────────

/**
 * [T-android-payload-size-audit] (GH#352) A byte-side cross-check on the
 * token-side estimate, for the one case where the two disagree catastrophically.
 *
 * Why this exists. Two budgets guard the outgoing request and they are
 * denominated differently:
 *
 *   - [com.openminis.app.provider.ImageBudget] counts BYTES (25 MB request cap).
 *   - [BPETokenizer.countImageTokens] counts TOKENS, using the vision grid-tile
 *     heuristic — area / 32², a few thousand tokens for a large photo.
 *
 * Both are right when an image travels as a structured `image_url` block: a
 * 1 MB photo really is ~1-3k tokens to a vision model, and 1.37 MB of base64 is
 * nowhere near 25 MB. But if that same image is measured as TEXT anywhere
 * downstream, its true cost is ~960k tokens — and no gate in the app sees that,
 * because every gate is looking at a number three orders of magnitude smaller.
 *
 * Field report (GH#352): a 1,030,449-byte PNG produced
 * `[400] maximum context length is 1048576 … requested ≈10906xx tokens`, and
 * the reporter's own A/B measured the same image at 267 tokens via image_url
 * and 959,936 tokens via a text channel. Nothing in this codebase encodes an
 * image as text (every emit site uses `image_url` / `input_image`), so the
 * mismatch is believed to arise downstream — but the app's inability to NOTICE
 * it is ours, and that is what this fixes.
 *
 * The rule is deliberately a ratio, not a fixed ceiling. A fixed byte cap would
 * have to be tuned per provider and would fire on legitimately large requests;
 * a ratio only fires when the two measurements disagree by so much that one of
 * them must be wrong. Normal content cannot trip it: prose is ~3.5 bytes per
 * token, JSON tool input maybe 6-8, so even pathological text stays far under
 * [SUSPICIOUS_BYTES_PER_TOKEN]. Base64 image bytes measured against a
 * grid-tile token estimate land around 500-800 bytes/token.
 */
object PayloadSizeAudit {

    /**
     * Bytes-per-estimated-token above which a part is reported as suspicious.
     *
     * 64 is ~18x the ratio of ordinary prose and ~8x the worst realistic JSON,
     * so it cannot fire on normal content, while the failure it looks for sits
     * an order of magnitude beyond it. Chosen to make a false positive
     * essentially impossible, since the consequence of firing is a log line the
     * next investigation will trust.
     */
    const val SUSPICIOUS_BYTES_PER_TOKEN = 64

    /** Parts smaller than this are never reported — the ratio is noisy on tiny values. */
    const val MIN_BYTES_TO_AUDIT = 32 * 1024

    /**
     * Fraction of the context window at which a single part is considered
     * dangerous on its own, measured in BYTES-as-if-text.
     *
     * Deliberately conservative: at 0.5 a part must be big enough to consume
     * half the window by itself before anything acts on it, which no ordinary
     * message approaches.
     */
    const val DANGEROUS_WINDOW_FRACTION = 0.5

    /**
     * What the audit concluded about one part.
     *
     * @param bytes serialized size of the part's payload.
     * @param estimatedTokens what the app's own estimator thinks it costs.
     * @param bytesPerToken the disagreement ratio, the thing worth logging.
     * @param suspicious the two measurements disagree beyond [SUSPICIOUS_BYTES_PER_TOKEN].
     * @param dangerous this part alone could plausibly exhaust the window if
     *   measured as text downstream.
     */
    data class Finding(
        val bytes: Int,
        val estimatedTokens: Int,
        val bytesPerToken: Int,
        val suspicious: Boolean,
        val dangerous: Boolean,
    )

    /**
     * Audit one part.
     *
     * @param contextWindowTokens the model's window, for the danger test; pass
     *   0 or less when unknown, which disables that half (an unknown window is
     *   no basis for calling something dangerous).
     */
    fun audit(bytes: Int, estimatedTokens: Int, contextWindowTokens: Int): Finding {
        if (bytes < MIN_BYTES_TO_AUDIT) {
            return Finding(bytes, estimatedTokens, 0, suspicious = false, dangerous = false)
        }
        // A zero/negative estimate means the estimator had no opinion; treat it
        // as 1 so the ratio stays defined and errs toward reporting.
        val safeTokens = estimatedTokens.coerceAtLeast(1)
        val ratio = bytes / safeTokens
        val suspicious = ratio >= SUSPICIOUS_BYTES_PER_TOKEN
        // Bytes are compared against the window as if each byte were ~1 token,
        // which is the worst case this audit exists to catch (base64 measured
        // as text runs ~1.4 chars/token, so this is if anything lenient).
        val dangerous = contextWindowTokens > 0 &&
            bytes.toLong() >= (contextWindowTokens * DANGEROUS_WINDOW_FRACTION).toLong()
        return Finding(bytes, estimatedTokens, ratio, suspicious, dangerous)
    }
}

// ─── Merged from PendingUpdateStore.kt ──────────────────────────────────────────────────

/**
 * Persists a downloaded-but-not-yet-installed APK across Activity recreate /
 * process death.
 *
 * Why this exists: the original update flow held the downloaded [File]
 * reference in a Composable `remember{}` slot. When the user tapped "Open
 * Settings" to grant "install unknown apps" permission, the system pushed
 * Minis to the background; on return the Activity often recreated, the slot
 * was reset, and the UI silently asked the user to download the APK again.
 *
 * Storage: a single SharedPreferences key holding a small JSON blob. We
 * deliberately avoid DataStore here — this object is touched at most a
 * couple times per update flow, blocking access is fine, and SharedPreferences
 * is already initialised elsewhere.
 *
 * Freshness: a [PendingUpdate] older than [MAX_AGE_MS] (24 h) is discarded
 * on read so a stale APK can't auto-install on cold start a week later
 * after the GitHub release was re-rolled.
 *
 * Integrity: we compute and store sha256 if [setPending] is given the
 * file bytes; if absent, [verify] falls back to (size == expectedSize).
 */
object PendingUpdateStore {

    private const val TAG = "PendingUpdateStore"
    private const val PREFS = "pending_update"
    private const val KEY = "pending"
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L

    data class PendingUpdate(
        val targetVersionName: String,
        /** [T-update-channels] Build number of the target; null on records from before builds. */
        val targetVersionCode: Int? = null,
        val apkPath: String,
        val apkSize: Long,
        val sha256: String?,
        val downloadedAtMs: Long,
    )

    private var prefs: SharedPreferences? = null

    /** Idempotent. Safe to call from MinisApp.onCreate. */
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun requirePrefs(context: Context): SharedPreferences {
        prefs?.let { return it }
        init(context)
        return prefs!!
    }

    fun setPending(context: Context, pending: PendingUpdate) {
        val json = JSONObject().apply {
            put("targetVersionName", pending.targetVersionName)
            if (pending.targetVersionCode != null) put("targetVersionCode", pending.targetVersionCode)
            put("apkPath", pending.apkPath)
            put("apkSize", pending.apkSize)
            if (pending.sha256 != null) put("sha256", pending.sha256) else put("sha256", JSONObject.NULL)
            put("downloadedAtMs", pending.downloadedAtMs)
        }
        requirePrefs(context).edit().putString(KEY, json.toString()).apply()
        AppLogger.info(
            TAG,
            "setPending version=${pending.targetVersionName} size=${pending.apkSize} sha256=${pending.sha256 != null}",
        )
    }

    /**
     * Returns the persisted pending update, or null when:
     *  - nothing stored
     *  - JSON malformed (treated as gone, cleared)
     *  - older than [MAX_AGE_MS] (cleared)
     *  - target version no longer newer than the running build
     */
    fun getPending(context: Context): PendingUpdate? {
        val p = requirePrefs(context)
        val raw = p.getString(KEY, null) ?: return null
        val obj = runCatching { JSONObject(raw) }.getOrNull()
        if (obj == null) {
            AppLogger.warning(TAG, "stored JSON malformed, discarding")
            p.edit().remove(KEY).apply()
            return null
        }
        val pending = PendingUpdate(
            targetVersionName = obj.optString("targetVersionName"),
            targetVersionCode = if (obj.has("targetVersionCode")) obj.optInt("targetVersionCode") else null,
            apkPath = obj.optString("apkPath"),
            apkSize = obj.optLong("apkSize"),
            sha256 = obj.optString("sha256", "").takeIf { it.isNotEmpty() && it != "null" },
            downloadedAtMs = obj.optLong("downloadedAtMs"),
        )
        val age = System.currentTimeMillis() - pending.downloadedAtMs
        if (age > MAX_AGE_MS) {
            AppLogger.info(TAG, "pending update expired age=${age}ms; clearing")
            clearPending(context)
            return null
        }
        return pending
    }

    fun clearPending(context: Context) {
        requirePrefs(context).edit().remove(KEY).apply()
        AppLogger.info(TAG, "clearPending")
    }

    /**
     * Strict integrity check used before firing the install intent on resume:
     *  - file exists
     *  - length matches recorded size
     *  - if sha256 was recorded, recomputed hash matches
     *
     * Returns the File when valid, null when corrupt/missing (caller should
     * clear the pending record and re-download).
     */
    fun verify(pending: PendingUpdate): File? {
        val f = File(pending.apkPath)
        if (!f.exists()) {
            AppLogger.warning(TAG, "verify: file missing ${pending.apkPath}")
            return null
        }
        if (f.length() != pending.apkSize) {
            AppLogger.warning(TAG, "verify: size mismatch expected=${pending.apkSize} actual=${f.length()}")
            return null
        }
        if (pending.sha256 != null) {
            val actual = runCatching { sha256(f) }.getOrNull()
            if (actual == null || !actual.equals(pending.sha256, ignoreCase = true)) {
                AppLogger.warning(TAG, "verify: sha256 mismatch expected=${pending.sha256} actual=$actual")
                return null
            }
        }
        return f
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

// ─── Merged from UpdateAutoChecker.kt ──────────────────────────────────────────────────

/**
 * [T-update-channels] Automatic update checks, without WorkManager: each time
 * the whole app comes to the foreground (ProcessLifecycleOwner ON_START), ask
 * [UpdatePolicy.autoDecision] whether a request is due - at most one per 24 h
 * on stable, 6 h on beta, 15 min after a failure, never while one runs, never
 * with the switch off, never on a build without automatic checks (store).
 * Every decision is logged as `[UpdateCheck] auto …`, so a log shows that
 * repeated foregrounds made one request.
 */
object UpdateAutoChecker {

    private const val TAG = "UpdateAuto"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var installed = false

    /** From MinisApp.onCreate (main thread). Idempotent. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        UpdateStateStore.init(app)
        if (!BuildConfig.AUTO_UPDATE_CHECK) {
            AppLogger.info(TAG, "[UpdateCheck] auto checks off for this build (store)")
            return
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    maybeCheck(app, "foreground")
                }
            },
        )
    }

    /**
     * Run an automatic check if the throttle allows it. Also called when the
     * switch is turned on (checks only if the cache has expired) and after a
     * channel switch (the cache was just cleared, so it checks at once).
     */
    fun maybeCheck(context: Context, reason: String) {
        val app = context.applicationContext
        UpdateStateStore.init(app)
        val decision = UpdatePolicy.autoDecision(
            now = System.currentTimeMillis(),
            state = UpdateStateStore.throttleState(app),
            channel = UpdateStateStore.channel.value,
            localCode = BuildConfig.VERSION_CODE,
            buildAllowsAuto = BuildConfig.AUTO_UPDATE_CHECK,
            userEnabled = UpdateStateStore.autoEnabled.value,
            inFlight = UpdateChecker.isChecking,
        )
        AppLogger.info(TAG, "[UpdateCheck] auto reason=$reason decision=$decision channel=${UpdateStateStore.channel.value.id}")
        if (decision != UpdatePolicy.AutoDecision.CHECK && decision != UpdatePolicy.AutoDecision.INVALIDATE_AND_CHECK) return
        // INVALIDATE: UpdateChecker.check -> beginAttempt clears the cache
        // bound to the old channel / build before it requests.
        scope.launch { UpdateChecker.check(app, reason = "auto-$reason") }
    }
}

// ─── Merged from UpdatePolicy.kt ──────────────────────────────────────────────────

/**
 * [T-update-channels] Pure decisions of the in-app updater: version order,
 * which release a channel may take, which APK fits this device, when
 * an automatic check may run, and whether a badge shows. No Android, no I/O -
 * [UpdateChecker] and [UpdateStateStore] feed it and act on the answers.
 *
 * Distribution rules (spec, final revision - one repo, OpenMinis/OpenMinis):
 *  - stable: tag `X.Y` (patch `X.Y.Z`, never `X.Y.0`), not a prerelease;
 *  - beta: a prerelease tagged `0-beta-build<N>`, titled
 *    `Android 1.16-beta.3 (build 31)`. Clients 1.12-1.15 ignore the
 *    prerelease flag and cut a tag at its first `-`, so they read `0`: lower
 *    than any 1.x, never an update and never their "highest" release;
 *  - the FILENAME is the protocol (no latest.json): an APK named
 *    `MinisApp-<version>-build<N>-<abi>.apk` gives the version name, the
 *    build number and the ABI; GitHub's asset `digest` gives the sha256, the
 *    release's `prerelease` the channel, its `body` the changelog;
 *  - versionCode is one build counter shared by both channels and decides
 *    which of two releases is newer;
 *  - releases from before build numbers (1.12-1.14) take part only in the
 *    stable channel, by version name; `0.xx-preview` releases never.
 */
object UpdatePolicy {

    enum class Channel(val id: String) {
        STABLE("stable"),
        BETA("beta"),
        ;

        companion object {
            fun parse(raw: String?): Channel = entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: STABLE
        }
    }

    /** Minimum time between successful automatic checks, by the current channel. */
    const val STABLE_INTERVAL_MS = 24L * 60 * 60 * 1000
    const val BETA_INTERVAL_MS = 6L * 60 * 60 * 1000

    /** After a failed attempt, the next automatic one waits at least this long. */
    const val RETRY_AFTER_FAILURE_MS = 15L * 60 * 1000

    fun intervalMs(channel: Channel): Long = if (channel == Channel.BETA) BETA_INTERVAL_MS else STABLE_INTERVAL_MS

    // ── Versions ────────────────────────────────────────────────────────────

    /**
     * `X.Y[.Z...][-pre]` with an optional leading `v`. Missing numeric parts
     * are 0, so `1.16` == `1.16.0`. A pre-release sorts below its release
     * (`1.16-beta.3` < `1.16`); pre-release labels compare part by part,
     * numerically where both are numbers (`beta.10` > `beta.9`).
     */
    fun compareVersionNames(a: String, b: String): Int {
        val (an, ap) = split(a)
        val (bn, bp) = split(b)
        for (i in 0 until maxOf(an.size, bn.size)) {
            val c = (an.getOrNull(i) ?: 0).compareTo(bn.getOrNull(i) ?: 0)
            if (c != 0) return c
        }
        if (ap == null && bp == null) return 0
        if (ap == null) return 1
        if (bp == null) return -1
        val ax = ap.split('.')
        val bx = bp.split('.')
        for (i in 0 until maxOf(ax.size, bx.size)) {
            val x = ax.getOrNull(i) ?: return -1
            val y = bx.getOrNull(i) ?: return 1
            val xi = x.toIntOrNull()
            val yi = y.toIntOrNull()
            val c = if (xi != null && yi != null) xi.compareTo(yi) else x.compareTo(y)
            if (c != 0) return c
        }
        return 0
    }

    private fun split(v: String): Pair<List<Int>, String?> {
        val t = v.trim().removePrefix("v").removePrefix("V")
        val dash = t.indexOf('-')
        val core = if (dash >= 0) t.substring(0, dash) else t
        val pre = if (dash >= 0) t.substring(dash + 1).ifEmpty { null } else null
        return core.split('.').map { it.toIntOrNull() ?: 0 } to pre
    }

    /** A beta version name (`1.16-beta.3`) or beta tag (`0-beta-build31`). */
    fun isBetaName(v: String): Boolean = v.contains("-beta", ignoreCase = true)

    /** The beta tag shape old clients read as `0`. */
    fun isBetaTag(tag: String): Boolean = tag.trim().startsWith("0-beta", ignoreCase = true)

    /** The `0.xx-preview` releases kept on RELEASE_REPO: never offered, never compared. */
    fun isLegacyPreview(tag: String): Boolean = tag.trim().endsWith("-preview", ignoreCase = true)

    /** What an APK's file name says about it. */
    data class ApkName(val version: String, val build: Int, val abi: String)

    /** The release protocol: `^MinisApp-(?<ver>.+)-build(?<build>\d+)-(?<abi>[\w-]+)\.apk$`. */
    private val APK_NAME = Regex("^MinisApp-(.+)-build(\\d+)-([\\w-]+)\\.apk$")

    /**
     * `MinisApp-1.16-beta.3-build31-arm64-v8a.apk` -> (1.16-beta.3, 31,
     * arm64-v8a). Null for a name outside the protocol - including the
     * pre-build history (`MinisApp-1.14-arm64-v8a.apk`), whose build is
     * unknown and which counts by version name on the stable channel only.
     */
    fun parseApkName(name: String): ApkName? {
        val m = APK_NAME.matchEntire(name.trim()) ?: return null
        val build = m.groupValues[2].toIntOrNull() ?: return null
        val ver = m.groupValues[1].takeIf { it.isNotBlank() } ?: return null
        return ApkName(ver, build, m.groupValues[3])
    }

    fun buildFromApkName(name: String): Int? = parseApkName(name)?.build

    fun versionFromApkName(name: String): String? = parseApkName(name)?.version

    /**
     * An asset's `digest` as a lower-case sha256 hex string: `sha256:<64 hex>`
     * -> `<hex>`. Null when missing or in another form - verification is then
     * skipped, not failed.
     */
    fun parseDigest(digest: String?): String? {
        val d = digest?.trim() ?: return null
        if (!d.startsWith("sha256:", ignoreCase = true)) return null
        val hex = d.substring(7).lowercase()
        return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    /**
     * Whether a downloaded APK may be handed to the installer: it is this app
     * (same package) and a newer build than the one installed. Checked on the
     * archive itself (PackageManager.getPackageArchiveInfo), whatever its name
     * or the release said. Returns the reason for a refusal, null when fine.
     */
    fun archiveRefusal(archivePackage: String?, archiveCode: Long?, ourPackage: String, installedCode: Long): String? = when {
        archivePackage == null || archiveCode == null -> "unreadable"
        archivePackage != ourPackage -> "package"
        archiveCode <= installedCode -> "not-newer"
        else -> null
    }

    // ── Releases and APKs ───────────────────────────────────────────────────

    data class Asset(val name: String, val url: String, val size: Long, val sha256: String?)

    /** One release as read from the release list. */
    data class Release(
        val tag: String,
        val name: String,
        val prerelease: Boolean,
        val draft: Boolean,
        val body: String,
        val assets: List<Asset>,
        val htmlUrl: String = "",
    )

    /** A release this device could install. */
    data class Candidate(
        val tag: String,
        val prerelease: Boolean,
        val versionName: String,
        val versionCode: Int?,
        val apk: Asset,
        val changelog: String,
        val releaseName: String,
        val htmlUrl: String,
    )

    /** ABIs named in our APK file names. */
    private val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    /**
     * The APK for this device: the first of [supportedAbis] (best first, as
     * Build.SUPPORTED_ABIS lists them) that an APK name carries; else an APK
     * naming no ABI at all (universal); else null - an APK built for another
     * ABI must not be offered.
     */
    fun chooseApk(assets: List<Asset>, supportedAbis: List<String>): Asset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        // An APK on the protocol carries its ABI; a name with a build number
        // beats a legacy name for the same ABI.
        fun abiOf(a: Asset): String? = parseApkName(a.name)?.abi
            ?: KNOWN_ABIS.firstOrNull { a.name.contains("-$it", ignoreCase = true) }
        for (abi in supportedAbis) {
            apks.filter { abiOf(it).equals(abi, ignoreCase = true) }
                .maxByOrNull { if (parseApkName(it.name) != null) 1 else 0 }
                ?.let { return it }
        }
        return apks.firstOrNull { abiOf(it) == null }
    }

    /**
     * Whether [release] may be offered to [channel] at all:
     *  - drafts and the legacy `-preview` releases never;
     *  - stable: only a non-prerelease that is not a beta by tag;
     *  - beta: everything else (betas and stables; the build number then
     *    decides, which leaves out the pre-build history - see [isNewer]).
     */
    fun eligible(release: Release, channel: Channel): Boolean {
        if (release.draft || isLegacyPreview(release.tag)) return false
        return when (channel) {
            Channel.STABLE -> !release.prerelease && !isBetaTag(release.tag) && !isBetaName(release.tag)
            Channel.BETA -> true
        }
    }

    /**
     * [release] as a candidate for this device, or null (no APK for its ABIs).
     * Version name and build come from the APK name; a legacy name (no build)
     * keeps the tag as its version and no build number.
     */
    fun toCandidate(release: Release, supportedAbis: List<String>): Candidate? {
        val apk = chooseApk(release.assets, supportedAbis) ?: return null
        val parsed = parseApkName(apk.name)
        return Candidate(
            tag = release.tag,
            prerelease = release.prerelease,
            versionName = parsed?.version ?: release.tag.trim().removePrefix("v"),
            versionCode = parsed?.build,
            apk = apk,
            changelog = release.body,
            releaseName = release.name.ifBlank { release.tag },
            htmlUrl = release.htmlUrl,
        )
    }

    /**
     * Whether [c] is newer than the running build. versionCode decides when
     * the candidate has one; a candidate without a build number (pre-build
     * history) is compared by name, and only on the stable channel.
     */
    fun isNewer(c: Candidate, channel: Channel, localName: String, localCode: Int): Boolean = when {
        c.versionCode != null -> c.versionCode > localCode
        channel == Channel.STABLE -> compareVersionNames(c.versionName, localName) > 0
        else -> false
    }

    /** Order of two candidates: build number when both have one, else version name. */
    fun compareCandidates(a: Candidate, b: Candidate): Int =
        if (a.versionCode != null && b.versionCode != null) a.versionCode.compareTo(b.versionCode)
        else compareVersionNames(a.versionName, b.versionName)

    /** The best newer candidate, or null when the device is up to date. */
    fun pickUpdate(candidates: List<Candidate>, channel: Channel, localName: String, localCode: Int): Candidate? =
        candidates.filter { isNewer(it, channel, localName, localCode) }.maxWithOrNull(::compareCandidates)

    // ── Automatic checks ────────────────────────────────────────────────────

    /** The persisted facts the throttle needs. Times are wall-clock ms. */
    data class ThrottleState(
        val lastSuccessAt: Long?,
        val lastAttemptAt: Long?,
        /** Channel and local versionCode the cached result belongs to. */
        val cacheChannel: Channel?,
        val cacheVersionCode: Int?,
    )

    enum class AutoDecision {
        /** No check: the build or the user turned automatic checks off. */
        DISABLED,
        /** No check: one is already running. */
        IN_FLIGHT,
        /** No check: the cache is fresh for this channel. */
        FRESH,
        /** No check: the last attempt failed less than 15 min ago. */
        BACKING_OFF,
        /** Check now. */
        CHECK,
        /** Clear the cache (other channel / other installed build), then check now. */
        INVALIDATE_AND_CHECK,
    }

    /**
     * Whether an automatic check runs now. A channel switch or a new installed
     * build invalidates the cache whatever the interval; a time in the future
     * (the clock moved back) counts as expired; a failure only blocks for
     * [RETRY_AFTER_FAILURE_MS], and it never moves [ThrottleState.lastSuccessAt].
     */
    fun autoDecision(
        now: Long,
        state: ThrottleState,
        channel: Channel,
        localCode: Int,
        buildAllowsAuto: Boolean,
        userEnabled: Boolean,
        inFlight: Boolean,
    ): AutoDecision {
        if (!buildAllowsAuto || !userEnabled) return AutoDecision.DISABLED
        if (inFlight) return AutoDecision.IN_FLIGHT
        val cacheValid = state.cacheChannel == channel && state.cacheVersionCode == localCode
        if (!cacheValid && (state.cacheChannel != null || state.cacheVersionCode != null)) return AutoDecision.INVALIDATE_AND_CHECK
        val success = state.lastSuccessAt
        val expired = !cacheValid || success == null || now < success || now - success >= intervalMs(channel)
        if (!expired) return AutoDecision.FRESH
        val attempt = state.lastAttemptAt
        val failedRecently = attempt != null && (success == null || attempt > success) &&
            now >= attempt && now - attempt < RETRY_AFTER_FAILURE_MS
        return if (failedRecently) AutoDecision.BACKING_OFF else AutoDecision.CHECK
    }

    // ── Badges ──────────────────────────────────────────────────────────────

    /**
     * The update badge (About row, About's check row, and for beta the home
     * settings icon): a newer build is known and the user has neither skipped
     * nor downloaded it. Hidden on a build without automatic checks and while
     * the user has them off; the cache stays. Purely local - no request.
     */
    fun hasUpdateBadge(
        buildAllowsAuto: Boolean,
        userEnabled: Boolean,
        availableCode: Int?,
        localCode: Int,
        skippedCode: Int?,
        downloadedCode: Int?,
    ): Boolean {
        if (!buildAllowsAuto || !userEnabled || availableCode == null) return false
        return availableCode > localCode && availableCode != skippedCode && availableCode != downloadedCode
    }

    /** The home settings-icon badge: the same badge, on the beta channel only. */
    fun hasHomeBadge(hasUpdateBadge: Boolean, channel: Channel): Boolean = hasUpdateBadge && channel == Channel.BETA

    /** `1.15 (build 29)` */
    fun displayVersion(name: String, code: Int?): String = if (code != null) "$name (build $code)" else name
}
