package com.openminis.app.provider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.openminis.app.BuildConfig
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject

/**
 * [T-android-dsml-leak] Detects a DeepSeek tool call that leaked into the
 * reasoning channel.
 *
 * DeepSeek serializes tool calls in its own markup (DSML in V4: `<｜DSML｜invoke>`
 * / `<｜DSML｜parameter>`; `<｜tool▁calls▁begin｜>` in V3/R1). The server parses
 * that markup into `tool_calls` only after the model closes its thinking. When
 * the model writes the call while still thinking, everything stays in
 * `reasoning_content`: the opening tags are swallowed, the closing tags and the
 * argument body show up as "reasoning", `tool_calls` is empty and the turn ends
 * with `finish_reason=stop`. To the loop that looks like an empty turn.
 *
 * Measured on the official API (sid 41295a4c turn 45 replayed from the Mac):
 * 5/25 identical requests leaked, each ending in `</｜｜DSML｜｜ parameter>` with
 * completion − reasoning = 82..83 tokens (the swallowed tags). It is random
 * per request and most likely while the model writes a long tool argument
 * (a document written in chunks via file_write), so a resend usually works.
 */
object DsmlLeak {

    /**
     * Substrings only DeepSeek's tool-call serialization produces. Kept as one
     * list so a new variant is a one-line change.
     */
    val MARKERS: List<String> = listOf(
        "｜DSML｜", // V4, fullwidth bars; also matches the doubled ｜｜DSML｜｜ form
        "|DSML|", // ASCII-degraded form some relays emit
        "<｜tool▁calls▁begin｜>", // V3 / R1 special tokens (U+2581 separators)
        "<｜tool▁call▁begin｜>",
        "<｜tool▁sep｜>",
        "<｜tool▁call▁end｜>",
        "<｜tool▁calls▁end｜>",
        "tool_calls_begin", // the same tokens written with ASCII underscores
        "tool_call_begin",
    )

    /** Resends of the same request before the loop gives up and asks the user. */
    const val MAX_RETRIES: Int = 2

    /** The first marker found in [reasoning], or null. */
    fun marker(reasoning: String?): String? {
        if (reasoning.isNullOrEmpty()) return null
        return MARKERS.firstOrNull { reasoning.contains(it) }
    }

    /**
     * True when a finished turn is a tool call lost in the reasoning channel:
     * no text this turn, no tool call, a clean stop, and DeepSeek tool-call
     * markup in the reasoning.
     */
    fun isLeak(turnHasText: Boolean, hasToolCalls: Boolean, finishReason: String?, reasoning: String?): Boolean {
        if (turnHasText || hasToolCalls) return false
        if (finishReason != "stop" && finishReason != "end_turn") return false
        return marker(reasoning) != null
    }

    sealed class Step {
        /** Resend the same request; [attempt] is 1-based. */
        data class Retry(val attempt: Int) : Step()

        /** Retries spent: show the notice and offer Resume. */
        object GiveUp : Step()
    }

    fun next(retriesSoFar: Int): Step =
        if (retriesSoFar < MAX_RETRIES) Step.Retry(retriesSoFar + 1) else Step.GiveUp
}

// ─── Merged from ImageBudget.kt ──────────────────────────────────────────────────

/**
 * Per-message inline-image byte budget — mirrors iOS AIChatViewModel.swift
 * kPerImageMaxBytes / kMessageImageMaxBytes (commit b830360).
 *
 * Anthropic returns HTTP 413 ("Downloaded image content cannot exceed 30MB")
 * when the request's total inline image payload exceeds the provider cap.
 * Pre-T-imgsize we only resized each attachment to a max-edge of 2000px at
 * JPEG q=85, which is *resolution* budgeting and does nothing for byte size
 * when the source is a 12MP HEIC. We now enforce a real byte cap at two
 * layers:
 *
 *   1. Composer (ChatViewModel.prepareUserAttachments) — runs the user's
 *      brand-new attachments through [compressUnderBudget] so every part is
 *      ≤ MAX_PER_IMAGE_BYTES, then tallies cumulative bytes and drops the
 *      tail with a Snackbar notice once MAX_TOTAL_BYTES is exceeded.
 *      Both caps are measured in base64 bytes — see [estimatedBase64Length].
 *   2. Provider boundary (AnthropicProvider / OpenAIProvider) — belt and
 *      braces for history image parts that bypass the composer (e.g. tool
 *      results screenshot bytes, restored sessions, retry-after-edit). Each
 *      oversize part is silently re-encoded in-place via [compressBytes].
 *
 * URL HEAD pre-check (spec §2.b) intentionally omitted — both Android
 * providers always base64-inline image bytes (no remote URL forwarding),
 * mirroring the iOS finding in commit b830360.
 */
object ImageBudget {
    /**
     * [T-image-budget-base64] Every cap below is a budget on the bytes that
     * actually leave the device, and images leave base64-inlined inside the
     * request JSON — never as raw bytes. Base64 costs 4 bytes per 3, so a
     * budget policed against raw sizes silently permits ~1.333x what it
     * claims: 25 MB of raw image is ~33.3 MB on the wire, past the 32 MB
     * gateway limit that reported "request too large" before the text
     * history and tool schemas are even counted.
     *
     * So the constants keep their values and their meaning — a ceiling on
     * transmitted bytes — and every comparison converts the raw size with
     * [estimatedBase64Length] first. Chosen over simply scaling the numbers
     * down by 1/1.333 because the expansion applies per image: clamping N
     * images each just under a scaled cap accumulates N rounding errors,
     * while converting at the comparison is exact and leaves one obvious
     * place to reason about.
     */
    fun estimatedBase64Length(rawBytes: Int): Long = estimatedBase64Length(rawBytes.toLong())

    /** Long overload — request-level tallies sum well past Int range. */
    fun estimatedBase64Length(rawBytes: Long): Long = ((rawBytes + 2) / 3) * 4

    /**
     * Single image ceiling, in transmitted (base64) bytes.
     *
     * NOTE the asymmetry with [compressUnderBudget]: the ladder there
     * measures the *raw* JPEG it produces, so callers pass it a raw-byte
     * target derived from this cap, not the cap itself.
     */
    const val MAX_PER_IMAGE_BYTES = 5L * 1024 * 1024

    /**
     * The raw-byte target that encodes to [MAX_PER_IMAGE_BYTES]. The
     * compressor works in raw bytes, so the base64 budget has to be
     * converted back before it can be used as a compression goal.
     */
    const val MAX_PER_IMAGE_RAW_BYTES = MAX_PER_IMAGE_BYTES / 4 * 3

    /** Cumulative inline-image bytes per user message, base64-measured. */
    const val MAX_TOTAL_BYTES = 25L * 1024 * 1024

    /**
     * Cumulative inline-image bytes across ALL messages in a single
     * request body. Mirrors the per-message cap but applies at the
     * request boundary so a long history accumulating images from
     * multiple turns (browser screenshots, attachments, read_image
     * results) cannot push the request past the cap that triggered
     * factory.pub / Anthropic gateways to silently return 200 +
     * empty SSE with `finish_reason=stop`. Eldest images are elided
     * to text placeholders first.
     *
     * Base64-measured, like the caps above.
     */
    const val MAX_REQUEST_BYTES = 25L * 1024 * 1024

    /** Default re-encode target longest edge in pixels. */
    const val MAX_EDGE_PX = 2000

    /** Default re-encode JPEG quality (0-100). */
    const val JPEG_QUALITY = 80

    private const val TAG = "ImageBudget"

    /**
     * [T-image-real-tests] Every Android graphics call the image functions
     * make — here, in ReadImageTool and in [resizeJpegToMaxEdge] — goes
     * through this seam, so a JVM test can plug in a real decoder (the unit-test
     * android.jar decodes nothing). Production always uses [AndroidImageCodec],
     * which forwards each call to exactly the BitmapFactory / Bitmap call the
     * code made before the seam existed.
     */
    @Volatile
    internal var codec: ImageCodec = AndroidImageCodec

    /**
     * Resize a JPEG so its longest edge is at most `maxEdge` px. Returns null
     * if already within bounds. Mirrors iOS AIChatViewModel.resizedImageData.
     *
     * [T-image-real-tests] Moved verbatim from ChatViewModel (browser_use
     * screenshots), graphics calls through [codec].
     */
    fun resizeJpegToMaxEdge(data: ByteArray, maxEdge: Int): ByteArray? {
        val codec = codec
        val bmp = codec.decode(data) ?: return null
        val longest = maxOf(codec.width(bmp), codec.height(bmp))
        if (longest <= maxEdge) { codec.recycle(bmp); return null }
        val scale = maxEdge.toFloat() / longest
        val w = (codec.width(bmp) * scale).toInt()
        val h = (codec.height(bmp) * scale).toInt()
        val resized = codec.scale(bmp, w, h)
        codec.recycle(bmp)
        val out = java.io.ByteArrayOutputStream()
        codec.compress(resized, png = false, quality = 85, out = out)
        codec.recycle(resized)
        return out.toByteArray()
    }

    /**
     * (maxEdge, quality) candidates the per-image compressor walks until the
     * output fits under [MAX_PER_IMAGE_RAW_BYTES]. Mirrors the iOS ladder from
     * AIChatViewModel.swift compressedImageDataUnderBudget(...).
     */
    private val LADDER: List<Pair<Int, Int>> = listOf(
        2000 to 80,
        1600 to 75,
        1280 to 70,
        1024 to 65,
        896 to 55,
        768 to 50,
        640 to 45,
    )

    /**
     * Re-encode [input] to a JPEG with max longest edge [maxEdge] at JPEG
     * quality [q]. Returns the original bytes on decode/encode failure
     * (caller's existing payload is always safer than dropping the image).
     */
    fun compressBytes(input: ByteArray, maxEdge: Int = MAX_EDGE_PX, q: Int = JPEG_QUALITY): ByteArray {
        if (input.isEmpty()) return input
        return try {
            // Two-pass decode mirroring PhotosOffloadHandler.copyResized — sampled
            // bounds first to keep the in-memory bitmap proportional to maxEdge,
            // then scaled to the exact target after decode.
            val codec = codec
            val (w0, h0) = codec.bounds(input)
            if (w0 <= 0 || h0 <= 0) return input
            var sample = 1
            while (w0 / sample > maxEdge * 2 || h0 / sample > maxEdge * 2) sample *= 2
            val decoded = codec.decode(input, sample)
                ?: return input
            val scale = minOf(
                maxEdge.toFloat() / codec.width(decoded),
                maxEdge.toFloat() / codec.height(decoded),
                1f,
            )
            val out = if (scale < 1f) {
                codec.scale(
                    decoded,
                    (codec.width(decoded) * scale).toInt().coerceAtLeast(1),
                    (codec.height(decoded) * scale).toInt().coerceAtLeast(1),
                )
            } else decoded
            val baos = ByteArrayOutputStream()
            codec.compress(out, png = false, quality = q.coerceIn(1, 100), out = baos)
            if (out !== decoded) codec.recycle(out)
            codec.recycle(decoded)
            baos.toByteArray()
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "compressBytes failed (${input.size}B → keeping original): ${t.message}")
            input
        }
    }

    // -- [T-android-image-downscale-parity] One ≤MAX_EDGE_PX copy for the model --

    /** An image fitted within [MAX_EDGE_PX] for the model, with both sizes for the note. */
    class Downscaled(
        val bytes: ByteArray,
        val mimeType: String,
        val originalWidth: Int,
        val originalHeight: Int,
        val width: Int,
        val height: Int,
    )

    /**
     * Target size when [w]×[h] must fit within [maxEdge] on its longest side,
     * or null when it already fits (or the size is unknown). Pure, so the
     * arithmetic is testable without a Bitmap.
     */
    internal fun fitWithin(w: Int, h: Int, maxEdge: Int): Pair<Int, Int>? {
        if (w <= 0 || h <= 0) return null
        val longest = maxOf(w, h)
        if (longest <= maxEdge) return null
        val scale = maxEdge.toDouble() / longest
        return Pair(
            maxOf(1, Math.round(w * scale).toInt()),
            maxOf(1, Math.round(h * scale).toInt()),
        )
    }

    /**
     * [T-android-image-downscale-parity] Fit an image within [maxEdge] for the
     * model. The ONE downscale used both when a message is first sent and when
     * its history is rebuilt from the saved original after a restart, so every
     * turn resends the same reduced image — what iOS does by persisting the
     * reduced copy it sent (AIChatViewModel+Persistence saveMedia).
     *
     * Field report: DeepSeek 400 "unsupported image" on every turn after a
     * restart for a 1264×23545 long screenshot. First send fitted it within
     * 2000 px and worked; the reload path replayed the full-size original (the
     * byte budget only shrinks images over 3.75 MB, this one was 1.6 MB) and
     * DeepSeek rejects an image that tall, reporting it as a format problem.
     *
     * Keeps PNG as PNG, everything else becomes JPEG q85 — the rule the old
     * composer resize used. Decodes with inSampleSize first so a long
     * screenshot is not materialised at full size (1264×23545 is ~119 MB as a
     * bitmap). Null when the image already fits or cannot be decoded.
     */
    fun downscaleForModel(input: ByteArray, maxEdge: Int = MAX_EDGE_PX): Downscaled? {
        if (input.isEmpty()) return null
        return try {
            val codec = codec
            val (w0, h0) = codec.bounds(input)
            val (tw, th) = fitWithin(w0, h0, maxEdge) ?: return null
            var sample = 1
            while (w0 / (sample * 2) >= tw && h0 / (sample * 2) >= th) sample *= 2
            val decoded = codec.decode(input, sample) ?: return null
            val scaled = codec.scale(decoded, tw, th)
            val png = sniffFormat(input) == ImageFormat.PNG
            val baos = ByteArrayOutputStream()
            codec.compress(scaled, png = png, quality = 85, out = baos)
            if (scaled !== decoded) codec.recycle(scaled)
            codec.recycle(decoded)
            Downscaled(
                baos.toByteArray(),
                if (png) ImageFormat.PNG.mimeType!! else ImageFormat.JPEG.mimeType!!,
                w0, h0, tw, th,
            )
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "downscaleForModel failed (${input.size}B): ${t.message}")
            null
        }
    }

    /**
     * Restored history images come from a file; key the cache by that file
     * (path + size + mtime) so rerun / retry / reload do not decode the same
     * long screenshot again. Only images that needed a downscale are cached.
     */
    private val restoredDownscaleCache = object : LinkedHashMap<String, Downscaled>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Downscaled>?) = size > 16
    }

    fun downscaleForModel(file: java.io.File, bytes: ByteArray): Downscaled? {
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        synchronized(restoredDownscaleCache) { restoredDownscaleCache[key] }?.let { return it }
        val out = downscaleForModel(bytes) ?: return null
        synchronized(restoredDownscaleCache) { restoredDownscaleCache[key] = out }
        return out
    }

    /**
     * [T-android-image-downscale-parity] Text placed right after a downscaled
     * image so the model knows it is looking at a reduced copy of something
     * much larger — otherwise a 107 px wide sliver of a long screenshot reads
     * as a tiny image rather than as an unreadable one. iOS has no such note
     * (it only states dimensions in the omitted-image placeholder,
     * imagePlaceholderText); the W×H spelling follows that placeholder.
     * English-only by design: model-facing text, not UI.
     */
    fun downscaleNote(d: Downscaled, linuxPath: String?): String = buildString {
        append("[Image downscaled before sending: the original is ")
        append(d.originalWidth).append('×').append(d.originalHeight)
        append(" px, shown here at ").append(d.width).append('×').append(d.height)
        append(" px, so fine detail and small text may be unreadable.")
        if (!linuxPath.isNullOrBlank()) append(" The full-size original is saved at ").append(linuxPath).append('.')
        append(']')
    }

    // -- [T-android-image-upload-format] Format + MIME at the provider boundary --

    /**
     * What an image's bytes actually are, read from its magic number. Only the
     * four formats with a [mimeType] can be sent: they are the set DeepSeek
     * names in its 400 ("webp, png, jpeg, and gif") and the set OpenAI and
     * Anthropic accept too.
     */
    enum class ImageFormat(val mimeType: String?) {
        JPEG("image/jpeg"),
        PNG("image/png"),
        GIF("image/gif"),
        WEBP("image/webp"),
        HEIF(null),
        AVIF(null),
        BMP(null),
        TIFF(null),
        UNKNOWN(null);

        val isUploadable: Boolean get() = mimeType != null
    }

    fun sniffFormat(b: ByteArray): ImageFormat {
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        fun ascii(from: Int, s: String) = s.indices.all { at(from + it) == s[it].code }
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> ImageFormat.JPEG
            at(0) == 0x89 && ascii(1, "PNG") -> ImageFormat.PNG
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> ImageFormat.GIF
            ascii(0, "RIFF") && ascii(8, "WEBP") -> ImageFormat.WEBP
            ascii(4, "ftyp") && (ascii(8, "avif") || ascii(8, "avis")) -> ImageFormat.AVIF
            ascii(4, "ftyp") && listOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")
                .any { ascii(8, it) } -> ImageFormat.HEIF
            ascii(0, "BM") -> ImageFormat.BMP
            ascii(0, "II*\u0000") || ascii(0, "MM\u0000*") -> ImageFormat.TIFF
            else -> ImageFormat.UNKNOWN
        }
    }

    /** An image ready to inline: [mimeType] always describes [bytes] as they are. */
    class UploadImage(val bytes: ByteArray, val mimeType: String) {
        fun base64(): String = java.util.Base64.getEncoder().encodeToString(bytes)
        fun dataUrl(): String = "data:$mimeType;base64,${base64()}"
    }

    /** Sent in place of an image that cannot be turned into an accepted format. */
    const val UNSENDABLE_IMAGE_PLACEHOLDER =
        "[Image omitted: its file format could not be converted to one this model accepts]"

    /**
     * [T-android-image-upload-format] The single gate every provider inlines an
     * image through: guarantee an accepted FORMAT, then the byte budget, and
     * label the result with the MIME of the bytes that actually go out.
     *
     * Field report: DeepSeek answered every turn of a conversation with 400
     * "You have uploaded an unsupported image". Reproduced on a Pixel 4a with a
     * HEIC photo, on both paths:
     *  - first send: the composer had re-encoded the 3000 px HEIC to JPEG but
     *    kept the attachment's `image/heic` label — DeepSeek validates the
     *    declared MIME, so valid JPEG bytes were rejected;
     *  - after a restart: history is rebuilt from the saved original, so the
     *    raw HEIC went out, and the old boundary only re-encoded images over
     *    the size budget.
     * Retry resent the identical body, so the conversation could not recover.
     *
     * Returns null only when an unaccepted format cannot be decoded here; the
     * caller then sends [UNSENDABLE_IMAGE_PLACEHOLDER] instead of the pixels.
     */
    fun prepareForUpload(input: ByteArray, declaredMime: String?): UploadImage? =
        prepareForUpload(input, declaredMime, ::transcodeCached, ::compressUnderBudget, ::fitCached)

    internal fun prepareForUpload(
        input: ByteArray,
        declaredMime: String?,
        transcode: (ByteArray) -> ByteArray?,
        budget: (ByteArray) -> ByteArray,
        // [T-android-image-downscale-parity] Fit within MAX_EDGE_PX; null = already fits.
        fit: (ByteArray) -> ByteArray? = { null },
    ): UploadImage? {
        if (input.isEmpty()) return null
        val sourceFormat = sniffFormat(input)
        val accepted = if (sourceFormat.isUploadable) input else {
            transcode(input) ?: run {
                if (sourceFormat == ImageFormat.UNKNOWN) {
                    // Not a format we can name, and it would not decode. Keep
                    // the pre-existing behaviour — send it as declared — rather
                    // than silently drop an image just because the sniffer does
                    // not know its header. Only a KNOWN-unaccepted format gets
                    // the placeholder: for those the 400 is certain.
                    AppLogger.warning(
                        TAG,
                        "[ImageFormat] unrecognized ${input.size}B (declared ${declaredMime ?: "none"}) " +
                            "did not decode — sending as declared",
                    )
                    val sized = budget(input)
                    val mime = sniffFormat(sized).mimeType ?: declaredMime ?: ImageFormat.JPEG.mimeType!!
                    return UploadImage(sized, mime)
                }
                AppLogger.warning(
                    TAG,
                    "[ImageFormat] $sourceFormat (declared ${declaredMime ?: "none"}, ${input.size}B) " +
                        "could not be decoded — sending a text placeholder instead",
                )
                return null
            }
        }
        // [T-android-image-downscale-parity] Backstop for any path that did not
        // already fit the image (tool results, images added outside the composer):
        // an accepted format can still be rejected for its size — DeepSeek 400s a
        // 1264×23545 JPEG as "unsupported image".
        val fitted = fit(accepted)?.also {
            AppLogger.info(TAG, "[ImageFormat] fitted within ${MAX_EDGE_PX}px: ${accepted.size}B → ${it.size}B")
        } ?: accepted
        val sized = budget(fitted)
        val finalFormat = sniffFormat(sized)
        // The budget ladder only ever emits JPEG or returns its input, so this
        // is always uploadable; guard anyway rather than send a wrong label.
        val mime = finalFormat.mimeType ?: return null
        if (sourceFormat != finalFormat || !sameMime(declaredMime, mime)) {
            AppLogger.info(
                TAG,
                "[ImageFormat] declared=${declaredMime ?: "none"} source=$sourceFormat " +
                    "${input.size}B → sending $mime ${sized.size}B",
            )
        }
        return UploadImage(sized, mime)
    }

    private fun sameMime(declared: String?, actual: String): Boolean {
        val d = declared?.trim()?.lowercase() ?: return false
        return d == actual || (actual == "image/jpeg" && d == "image/jpg")
    }

    /**
     * Decode an unaccepted format (HEIC/HEIF, AVIF, BMP, …) and re-encode it as
     * JPEG at the composer's resolution. Null when decoding fails —
     * [compressBytes] signals that by returning its input unchanged.
     */
    private fun transcodeToJpeg(input: ByteArray): ByteArray? {
        val out = compressBytes(input, MAX_EDGE_PX, JPEG_QUALITY)
        return out.takeIf { it !== input && sniffFormat(it) == ImageFormat.JPEG }
    }

    /**
     * History images are the same ByteArray instance on every turn, and each
     * turn rebuilds the whole request — without this a single HEIC in history
     * would be decoded again on every send. Arrays hash by identity, so a
     * WeakHashMap keyed by the source array drops entries with the history.
     */
    private val transcodeCache = java.util.WeakHashMap<ByteArray, ByteArray>()

    /** Identity-keyed like [transcodeCache]; NO_FIT marks "measured, already fits". */
    private val fitCache = java.util.WeakHashMap<ByteArray, Any>()
    private val NO_FIT = Any()

    private fun fitCached(input: ByteArray): ByteArray? {
        when (val hit = synchronized(fitCache) { fitCache[input] }) {
            NO_FIT -> return null
            is ByteArray -> return hit
        }
        val out = downscaleForModel(input)?.bytes
        synchronized(fitCache) { fitCache[input] = out ?: NO_FIT }
        return out
    }

    private fun transcodeCached(input: ByteArray): ByteArray? {
        synchronized(transcodeCache) { transcodeCache[input] }?.let { return it }
        val out = transcodeToJpeg(input) ?: return null
        synchronized(transcodeCache) { transcodeCache[input] = out }
        return out
    }

    /**
     * Try increasingly aggressive (maxEdge, quality) candidates until the
     * re-encoded JPEG fits under [targetMaxBytes]. Returns the smallest
     * encoding produced when no candidate fits — never returns null because
     * "send something" beats "fail the request".
     *
     * Re-encodes from the original bytes on each candidate so the JPEG never
     * compounds artifacts.
     *
     * [T-image-budget-base64] [targetMaxBytes] is RAW bytes — this ladder
     * measures the JPEG it just produced, which has not been base64'd yet.
     * The default is therefore [MAX_PER_IMAGE_RAW_BYTES], the raw size that
     * encodes to [MAX_PER_IMAGE_BYTES]. Passing the base64 cap here would
     * compress to 5 MB raw and put ~6.7 MB on the wire — the exact overshoot
     * this change exists to remove, and the shape the provider-boundary
     * callers (Anthropic / OpenAI / Gemini) rely on by taking the default.
     */
    fun compressUnderBudget(input: ByteArray, targetMaxBytes: Long = MAX_PER_IMAGE_RAW_BYTES): ByteArray {
        if (input.size <= targetMaxBytes) return input
        var best: ByteArray = input
        var bestSize = input.size
        for ((edge, q) in LADDER) {
            val candidate = compressBytes(input, edge, q)
            if (candidate.size < bestSize) {
                best = candidate
                bestSize = candidate.size
            }
            if (candidate.size.toLong() <= targetMaxBytes) {
                AppLogger.info(TAG, "compressUnderBudget hit: ${input.size}B → ${candidate.size}B (edge=$edge q=$q)")
                return candidate
            }
        }
        AppLogger.warning(TAG, "compressUnderBudget exhausted ladder: ${input.size}B → ${best.size}B (target=${targetMaxBytes}B)")
        return best
    }

    /** Result of [applyMessageBudget]. */
    data class BudgetResult(
        /** Image bytes ready to send, in original order, dropped tail removed. */
        val keptBytes: List<ByteArray>,
        /** Number of parts whose bytes were re-encoded by the ladder. */
        val compressedCount: Int,
        /** Number of tail parts dropped because the cumulative cap was hit. */
        val droppedCount: Int,
        /** Final total payload bytes after compression + drop. */
        val totalBytes: Long,
    ) {
        val mutated: Boolean get() = compressedCount > 0 || droppedCount > 0
    }

    /**
     * Walk [bytesIn] and produce a budgeted output:
     *  - Each oversize part is run through [compressUnderBudget] first.
     *  - Then cumulative bytes are summed; once the running total would
     *    exceed [MAX_TOTAL_BYTES] the remaining tail is dropped.
     */
    fun applyMessageBudget(bytesIn: List<ByteArray>): BudgetResult {
        if (bytesIn.isEmpty()) return BudgetResult(emptyList(), 0, 0, 0L)
        val kept = ArrayList<ByteArray>(bytesIn.size)
        var compressed = 0
        var dropped = 0
        var running = 0L
        for (part in bytesIn) {
            // [T-image-budget-base64] Compare what the wire will carry, not
            // what the decoder holds. `compressUnderBudget` is given the raw
            // equivalent of the cap because its ladder measures raw JPEG.
            val sized = if (estimatedBase64Length(part.size) > MAX_PER_IMAGE_BYTES) {
                val c = compressUnderBudget(part, MAX_PER_IMAGE_RAW_BYTES)
                if (c.size != part.size) compressed += 1
                c
            } else part
            val sizedOnWire = estimatedBase64Length(sized.size)
            if (running + sizedOnWire > MAX_TOTAL_BYTES) {
                dropped += 1
                continue
            }
            kept.add(sized)
            running += sizedOnWire
        }
        if (dropped > 0 || compressed > 0) {
            AppLogger.info(
                TAG,
                "applyMessageBudget: in=${bytesIn.size} kept=${kept.size} compressed=$compressed dropped=$dropped total=${running}B",
            )
        }
        return BudgetResult(kept, compressed, dropped, running)
    }

    // ─── Request-level budget ──────────────────────────────────────────────

    /**
     * Identifies a single image part inside an outgoing request payload.
     * Used by providers to look up whether a part has been marked for
     * elision by [planRequestBudget]. The identity hash of the ByteArray
     * is sufficient — same bytes only ever appear once per request, and
     * different bytes hash uniquely enough that the worst-case collision
     * is "we keep one extra image past the budget" (safe).
     */
    @JvmInline
    value class ImagePartId(val identityHash: Int) {
        companion object {
            fun of(data: ByteArray): ImagePartId = ImagePartId(System.identityHashCode(data))
        }
    }

    /**
     * Image part scoped for budget planning. Lets the planner stay generic
     * over `LLMMessage.ImagePart` (current-turn user attachments) and
     * `AgentContentPart.ImageData` / `AgentContentPart.ToolResult.imageData`
     * (history image bytes) without depending on either type directly.
     */
    data class BudgetImage(
        val data: ByteArray,
        val linuxPath: String?,
        val mimeType: String,
        /**
         * [T-request-budget-post-gate] Base64 size of what the provider will
         * actually send for this image (the [prepareForUpload] output; 0 when
         * it goes out as a text placeholder). Null = not measured: the planner
         * falls back to the raw size clamped to [MAX_PER_IMAGE_BYTES].
         */
        val wireBytes: Long? = null,
    )

    /**
     * Result of [planRequestBudget].
     */
    data class RequestBudgetPlan(
        /** Identity hashes of image bytes that providers must NOT inline. */
        val droppedIds: Set<ImagePartId>,
        /** Map from dropped identity → original linux path (null if none). */
        val droppedPaths: Map<ImagePartId, String?>,
        /** Total bytes kept after planning (sum of per-image cap-clamped). */
        val keptBytes: Long,
        /** Total bytes elided. */
        val elidedBytes: Long,
        /** Number of images elided. */
        val droppedCount: Int,
        /** Total images considered (kept + dropped). */
        val totalCount: Int,
        /**
         * [T-request-budget-post-gate] Positions in the planned list that must
         * not be inlined. Unlike [droppedIds] (array identity, which can
         * collide and treats one array listed twice as one image) this names
         * each occurrence exactly.
         */
        val droppedIndices: Set<Int> = emptySet(),
    ) {
        val mutated: Boolean get() = droppedCount > 0
    }

    /**
     * Walk all candidate images in reverse chronological order (latest
     * first) and decide which can fit under [maxBytes]. Anything beyond
     * the cap is recorded in [RequestBudgetPlan.droppedIds] so providers
     * emit a text placeholder instead of base64 bytes.
     *
     * @param images Ordered eldest → latest. The planner reverses
     *   internally so the latest user input + most recent tool results
     *   are protected from elision.
     */
    fun planRequestBudget(
        images: List<BudgetImage>,
        maxBytes: Long = MAX_REQUEST_BYTES,
    ): RequestBudgetPlan {
        if (images.isEmpty()) {
            return RequestBudgetPlan(emptySet(), emptyMap(), 0L, 0L, 0, 0)
        }
        val dropped = HashSet<ImagePartId>()
        val droppedPaths = HashMap<ImagePartId, String?>()
        val droppedIndices = HashSet<Int>()
        var kept = 0L
        var elided = 0L
        // Walk latest → eldest so most-recent images win the budget.
        for (index in images.indices.reversed()) {
            val img = images[index]
            val id = ImagePartId.of(img.data)
            // Per-image cap-clamped size, measured as base64 because that is
            // what [maxBytes] budgets. [T-image-budget-base64] The clamp is
            // applied after the conversion so it lands on the same ceiling a
            // provider-boundary `compressUnderBudget` would have produced.
            // [T-request-budget-post-gate] A measured wire size wins: it is what
            // the request really carries.
            val effectiveSize = img.wireBytes ?: minOf(estimatedBase64Length(img.data.size), MAX_PER_IMAGE_BYTES)
            if (kept + effectiveSize <= maxBytes) {
                kept += effectiveSize
            } else {
                dropped.add(id)
                droppedPaths[id] = img.linuxPath
                droppedIndices.add(index)
                elided += effectiveSize
            }
        }
        if (dropped.isNotEmpty()) {
            AppLogger.info(
                TAG,
                "planRequestBudget: in=${images.size} kept=${images.size - dropped.size} dropped=${dropped.size} keptBytes=${kept}B elidedBytes=${elided}B cap=${maxBytes}B",
            )
        }
        return RequestBudgetPlan(
            droppedIds = dropped,
            droppedPaths = droppedPaths,
            keptBytes = kept,
            elidedBytes = elided,
            droppedCount = droppedIndices.size,
            totalCount = images.size,
            droppedIndices = droppedIndices,
        )
    }

    /** [T-image-real-tests] Result of [applyRequestBudget]: [plan] is null when nothing was elided. */
    class RequestBudgetApplied(val messages: List<LLMMessage>, val plan: RequestBudgetPlan?)

    /**
     * [T-image-real-tests] Request-level image budget over a whole outgoing
     * history: elide the eldest images past [MAX_REQUEST_BYTES] to text
     * placeholders, spilling path-less ones under [resolveAttachmentsRoot]
     * (called only when something is dropped). Moved verbatim from
     * ChatViewModel.applyRequestImageBudget, which keeps the event and the log.
     */
    fun applyRequestBudget(
        messages: List<LLMMessage>,
        resolveAttachmentsRoot: () -> java.io.File?,
    ): RequestBudgetApplied {
        // Collect every image in chronological order so the planner can
        // walk in reverse and protect the most recent images.
        data class ImageRef(val msgIdx: Int, val partIdx: Int, val image: ImageBudget.BudgetImage)
        // [T-request-budget-post-gate] Each image is measured by what the
        // provider will send: the same gate every serializer inlines through
        // (format → fit → per-image byte ladder; its decodes are cached per
        // array, so the serializer reuses them). Measuring the raw bytes
        // instead dropped images the gate shrinks: six ~3.6 MB BMPs counted
        // ~29 MB and lost one, for a request that left at ~236 KB.
        fun measured(data: ByteArray, mime: String, path: String?) = ImageBudget.BudgetImage(
            data, path, mime,
            wireBytes = prepareForUpload(data, mime)?.let { estimatedBase64Length(it.bytes.size) } ?: 0L,
        )
        val images = mutableListOf<ImageRef>()
        messages.forEachIndexed { mi, msg ->
            msg.contentParts.forEachIndexed { pi, part ->
                when (part) {
                    is AgentContentPart.ImageData -> {
                        images.add(
                            ImageRef(
                                mi, pi,
                                measured(part.data, part.mimeType, part.linuxPath),
                            )
                        )
                    }
                    is AgentContentPart.ToolResult -> {
                        val img = part.imageData
                        if (img != null) {
                            images.add(
                                ImageRef(
                                    mi, pi,
                                    measured(img, part.imageMimeType ?: "image/jpeg", part.imageLinuxPath),
                                )
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
        if (images.isEmpty()) return RequestBudgetApplied(messages, null)

        val plan = ImageBudget.planRequestBudget(images.map { it.image })
        if (!plan.mutated) return RequestBudgetApplied(messages, null)

        // For dropped images without a linuxPath, lazily spill to disk so
        // the placeholder still gives the model an addressable reference.
        val attachmentsRoot = resolveAttachmentsRoot()
        // [T-request-budget-post-gate] Keyed by position (message index + part
        // index via the planned list), not by array identity.
        val resolvedPaths = HashMap<Int, String?>()
        for ((id, ref) in images.withIndex()) {
            if (id !in plan.droppedIndices) continue
            val existing = ref.image.linuxPath
            if (existing != null) {
                resolvedPaths[id] = existing
            } else if (attachmentsRoot != null) {
                resolvedPaths[id] = ImageBudget.ensureSpillover(
                    attachmentsRoot, ref.image.data, ref.image.mimeType,
                )
            } else {
                resolvedPaths[id] = null
            }
        }

        // Build a new message list with dropped image parts replaced by
        // text placeholders. Same-message multiple drops collapse cleanly
        // because we never touch parts whose ids weren't in droppedIds.
        val byMsg = images.withIndex().groupBy { it.value.msgIdx }
        val mutated = messages.toMutableList()
        for ((mi, refs) in byMsg) {
            val msg = mutated[mi]
            val newParts = msg.contentParts.toMutableList()
            for ((id, ref) in refs) {
                if (id !in plan.droppedIndices) continue
                val path = resolvedPaths[id]
                val placeholder = AgentContentPart.Text(ImageBudget.elidedImagePlaceholder(path))
                val originalPart = newParts[ref.partIdx]
                newParts[ref.partIdx] = when (originalPart) {
                    is AgentContentPart.ImageData -> placeholder
                    is AgentContentPart.ToolResult -> originalPart.copy(
                        // Strip the bytes but keep the structural ToolResult
                        // role; append the elision marker into content so
                        // the model sees it next to the rest of the tool
                        // output. linux path remains in the part for any
                        // subsequent diagnostic round-trip.
                        imageData = null,
                        imageMimeType = null,
                        content = originalPart.content +
                            (if (originalPart.content.isEmpty()) "" else "\n") +
                            ImageBudget.elidedImagePlaceholder(path),
                    )
                    else -> originalPart
                }
            }
            mutated[mi] = msg.copy(contentParts = newParts)
        }

        return RequestBudgetApplied(mutated, plan)
    }

    /**
     * Build the text placeholder a provider emits in place of an elided
     * image. The model gets a clear, actionable hint: this image was
     * dropped to fit the budget, and (if known) the linux path where the
     * bytes are still readable via [read_image]. Without a path the model
     * just sees that an image was elided and can ask the user to re-attach.
     */
    fun elidedImagePlaceholder(linuxPath: String?): String {
        return if (linuxPath != null) {
            "[image elided to fit 25MB request budget. Original at $linuxPath — re-fetch with `read_image $linuxPath` if you need to see it.]"
        } else {
            "[image elided to fit 25MB request budget. Original bytes no longer addressable; ask the user to re-attach if needed.]"
        }
    }

    /**
     * Lazily persist [data] to a session-scoped spillover dir under
     * `attachments/spillover/<sha1>.<ext>` so an elided image without a
     * pre-existing linux path can still be referenced from the text
     * placeholder. The spillover dir is bind-mounted to
     * `/var/minis/attachments/spillover/` inside iSH (same mount as
     * `attachments/uploads/`). Returns the iSH-visible linux path on
     * success, or null if the write failed (in which case the placeholder
     * falls back to the no-path variant).
     *
     * Idempotent: if the same bytes already exist on disk under the
     * sha1 prefix, returns the existing path without re-writing.
     */
    fun ensureSpillover(
        sessionAttachmentsDir: java.io.File,
        data: ByteArray,
        mimeType: String,
    ): String? {
        if (data.isEmpty()) return null
        val ext = when (mimeType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/heic", "image/heif" -> "heic"
            else -> "bin"
        }
        val sha = try {
            val md = java.security.MessageDigest.getInstance("SHA-1")
            md.update(data)
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            // SHA-1 is required by the platform; fallback is just an
            // identity hash — collisions are tolerable here.
            System.identityHashCode(data).toString(16)
        }
        val spilloverDir = java.io.File(sessionAttachmentsDir, "spillover")
        if (!spilloverDir.exists()) {
            try {
                spilloverDir.mkdirs()
            } catch (e: Exception) {
                AppLogger.warning(TAG, "ensureSpillover mkdirs failed: ${e.message}")
                return null
            }
        }
        val file = java.io.File(spilloverDir, "$sha.$ext")
        if (!file.exists()) {
            try {
                file.writeBytes(data)
            } catch (e: Exception) {
                AppLogger.warning(TAG, "ensureSpillover write failed: ${e.message}")
                return null
            }
        }
        // Mirrors uploads mount (see ChatViewModel.prepareUserAttachments).
        return "/var/minis/attachments/spillover/$sha.$ext"
    }
}

/**
 * [T-image-real-tests] The graphics operations behind [ImageBudget.codec].
 * Bitmaps are opaque handles (`Any`) so a JVM implementation needs no
 * android.graphics types.
 */
internal interface ImageCodec {
    /** Bounds-only decode: (outWidth, outHeight) as BitmapFactory reports them. */
    fun bounds(input: ByteArray): Pair<Int, Int>

    /** Full decode at [inSampleSize]; null when it cannot be decoded. */
    fun decode(input: ByteArray, inSampleSize: Int): Any?

    /** Full decode with default options; null when it cannot be decoded. */
    fun decode(input: ByteArray): Any?

    /** Full decode of a file with default options; null when it cannot be decoded. */
    fun decodeFile(path: String): Any?

    fun width(bitmap: Any): Int

    fun height(bitmap: Any): Int

    /** Filtered scale to exactly [width]×[height]; may return [bitmap] itself. */
    fun scale(bitmap: Any, width: Int, height: Int): Any

    /** Encode as PNG when [png], else JPEG at [quality], into [out]. */
    fun compress(bitmap: Any, png: Boolean, quality: Int, out: java.io.OutputStream)

    fun recycle(bitmap: Any)
}

/** [T-image-real-tests] Production [ImageCodec]: the BitmapFactory / Bitmap calls, unchanged. */
internal object AndroidImageCodec : ImageCodec {
    override fun bounds(input: ByteArray): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(input, 0, input.size, opts)
        return Pair(opts.outWidth, opts.outHeight)
    }

    override fun decode(input: ByteArray, inSampleSize: Int): Any? =
        BitmapFactory.decodeByteArray(input, 0, input.size, BitmapFactory.Options().apply { this.inSampleSize = inSampleSize })

    override fun decode(input: ByteArray): Any? = BitmapFactory.decodeByteArray(input, 0, input.size)

    override fun decodeFile(path: String): Any? = BitmapFactory.decodeFile(path)

    override fun width(bitmap: Any): Int = (bitmap as Bitmap).width

    override fun height(bitmap: Any): Int = (bitmap as Bitmap).height

    override fun scale(bitmap: Any, width: Int, height: Int): Any =
        Bitmap.createScaledBitmap(bitmap as Bitmap, width, height, true)

    override fun compress(bitmap: Any, png: Boolean, quality: Int, out: java.io.OutputStream) {
        (bitmap as Bitmap).compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, quality, out)
    }

    override fun recycle(bitmap: Any) {
        (bitmap as Bitmap).recycle()
    }
}

// ─── Merged from JsonExt.kt ──────────────────────────────────────────────────

/**
 * Safe optString that handles the Android JSONObject quirk where
 * optString("key", "") returns the literal string "null" when the key
 * exists but its value is JSON null.
 *
 * This function returns [fallback] in both cases:
 * - key does not exist
 * - key exists but value is JSONObject.NULL (JSON null)
 */
fun JSONObject.safeOptString(key: String, fallback: String = ""): String {
    if (!has(key) || isNull(key)) return fallback
    return optString(key, fallback)
}

// ─── Merged from LLMProvider.kt ──────────────────────────────────────────────────

interface LLMProvider {
    val name: String
    var model: LLMModel

    /**
     * Effective max output tokens ceiling for the given model.
     * Priority: model.maxOutputTokens > provider-level default.
     * Used as the upper bound in dynamicMaxTokens().
     */
    fun effectiveMaxOutputTokens(model: LLMModel): Int =
        model.maxOutputTokens ?: defaultMaxOutputTokens

    /**
     * Provider-level fallback when model.maxOutputTokens is unknown. 16K is the
     * iOS default for Gemini and Antigravity; OpenAIProvider (16K Chat
     * Completions / 32K Responses) and AnthropicProvider (64K) override it,
     * as their iOS counterparts do. [T-android-max-tokens-default]
     */
    val defaultMaxOutputTokens: Int get() = 16_384

    /**
     * [T-android-tool-splits-reply-fix] True when the streamed assistant text
     * is one monolithic `content` string per response (OpenAI Chat
     * Completions): text deltas carry NO positional relationship to
     * tool_calls deltas — a non-streaming materialisation is always
     * {content, tool_calls} with content first — so ALL text deltas of one
     * streamed response belong to a single text block that precedes the tool
     * blocks. Some endpoints (qwen) flush trailing content chunks after
     * tool_calls deltas purely as a chunking artifact; reconstructing those
     * chronologically fabricates an order the wire format cannot express.
     * False for formats with genuinely ordered output blocks (Responses API
     * output items, Anthropic content blocks), where arrival order IS the
     * semantic block order.
     */
    val streamTextIsMonolithic: Boolean get() = false

    /**
     * [T-android-thinking-level-arch] PUBLIC entry — this is what every caller
     * (agent loop, fallback, quick test, model-use, …) invokes. It is NOT
     * overridden by providers: the default implementation clamps the requested
     * thinking level to the current [model]'s ceiling ONCE, then delegates to
     * [sendMessageClamped]. Making the clamp structural (rather than a call each
     * impl must remember) means no code path can send an over-range level — a
     * fallback that swapped [model] mid-flight clamps to the NEW model's limit
     * automatically. Mirrors iOS AgentProvider.streamAgentMessage → …Clamped.
     */
    suspend fun sendMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double? = null,
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): LLMResponse = sendMessageClamped(
        messages, systemPrompt, maxTokens, temperature, imageParts, tools,
        clampThinkingLevel(thinkingLevel),
    )

    /** See [sendMessage] — the clamped, provider-implemented counterpart. */
    fun streamMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double? = null,
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): Flow<LLMStreamChunk> = streamMessageClamped(
        messages, systemPrompt, maxTokens, temperature, imageParts, tools,
        clampThinkingLevel(thinkingLevel),
    )

    /**
     * [T-android-thinking-level-arch] Provider implementations override THIS
     * (not [sendMessage]). The `thinkingLevel` received here has already been
     * clamped to the model's ceiling by [sendMessage] — implementations must NOT
     * re-clamp. Mirrors iOS `streamAgentMessageClamped`.
     */
    suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse

    /** See [sendMessageClamped]. */
    fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk>

    /**
     * [T-android-thinking-level-arch] Cap a requested thinking level to the
     * current [model]'s ceiling by rank. Used by the [sendMessage]/[streamMessage]
     * default entries above; the catalog ceiling is resolved off the live
     * `model`.
     */
    /**
     * [T-ctx-count-reasoning] True when this provider's requests replay past
     * assistant turns' `reasoningContent` (DeepSeek-style `reasoning_content`
     * echo, unsigned interleaved thinking). The context meter counts it then,
     * and the trim ladder may empty it on older turns. Default: not replayed.
     */
    fun echoesReasoningContent(thinkingLevel: ThinkingLevel): Boolean = false

    fun clampThinkingLevel(level: ThinkingLevel): ThinkingLevel {
        val ceiling = model.catalogMaxThinkingLevel
        return if (level.rank > ceiling.rank) ceiling else level
    }
}

/**
 * [T-android-empty-stream-retry] Detect a silently-truncated stream.
 *
 * When a relay/upstream drops the SSE connection without an error status,
 * the provider flow completes "normally" having emitted no content and no
 * finish reason — the chat just stops mid-air with no error (user report).
 * iOS treats this as a transient error at its stream-collection layer
 * (AIChatViewModel.isEmptyResponse → LLMError.transientError) and
 * auto-retries; this operator is the Android provider-layer equivalent.
 *
 * Empty = no text / thinking / reasoning / tool-call / media chunk AND no
 * finish reason. A stream that finished WITH a stop reason but no content
 * ("stop"/"end_turn") is deliberately let through — the agent loop's
 * empty-after-tool-result reminder path (ChatViewModel) owns that case, and
 * a "length"/"max_tokens" cut is a legitimate empty. Cancellation and
 * thrown errors propagate before the check runs (code after `collect`
 * only executes on normal completion).
 */
fun Flow<LLMStreamChunk>.failOnSilentEmptyCompletion(providerName: String): Flow<LLMStreamChunk> = flow {
    var sawContent = false
    var sawFinishReason = false
    collect { chunk ->
        when (chunk) {
            is LLMStreamChunk.Text -> if (chunk.text.isNotEmpty()) sawContent = true
            is LLMStreamChunk.ThinkingDelta -> if (chunk.text.isNotEmpty()) sawContent = true
            is LLMStreamChunk.ReasoningContent -> if (chunk.content.isNotEmpty()) sawContent = true
            is LLMStreamChunk.ToolUseStart,
            is LLMStreamChunk.ToolInputDelta,
            is LLMStreamChunk.ToolCallComplete,
            is LLMStreamChunk.MediaAttachment -> sawContent = true
            is LLMStreamChunk.Finished -> if (chunk.stopReason != null) sawFinishReason = true
            else -> {}
        }
        emit(chunk)
    }
    if (!sawContent && !sawFinishReason) {
        android.util.Log.w(
            "LLMProvider",
            "$providerName: stream completed with no content and no finish reason — treating as transient upstream failure",
        )
        throw LLMError.TransientError("Server returned an empty response (connection dropped or upstream error)")
    }
}

/**
 * [T-compact-idle-timeout] Fail a stream that stops producing, while letting a
 * slow-but-alive one run as long as it needs.
 *
 * The distinction matters because the two are indistinguishable to a total
 * elapsed-time budget: a long transcript and a dead socket both just "take too
 * long". Measuring the GAP BETWEEN chunks separates them — a healthy stream
 * resets the clock thousands of times, a stuck one never resets it once — so a
 * limit can be strict about hangs without being a limit on length.
 *
 * [onTimeout] runs on the collecting coroutine and is expected to throw; it
 * takes the elapsed idle milliseconds so the caller can word its own error.
 *
 * Implemented over a channel rather than by racing a `delay()` against the
 * flow, so the timer is armed per element and the upstream is left running
 * untouched between elements. `coroutineScope` bounds the producer: normal
 * completion, a throw from [onTimeout], and cancellation all tear it down, so
 * no collection outlives the caller.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> Flow<T>.withIdleTimeout(
    idleMs: Long,
    onTimeout: (Long) -> Unit,
): Flow<T> = flow {
    coroutineScope {
        val channel = produceIn(this)
        while (true) {
            // receiveCatching, not receive(): a normally-closed channel means
            // the stream ended, which is success, while a close CAUSED by an
            // upstream error carries that error and must be re-thrown so the
            // caller sees the provider's failure rather than a silent stop.
            val result = withTimeoutOrNull(idleMs) { channel.receiveCatching() }
            if (result == null) {
                onTimeout(idleMs)
                // Defensive: a caller whose onTimeout returns instead of
                // throwing would otherwise spin here forever.
                throw LLMError.TransientError(
                    "Stream idle for ${idleMs}ms with no new data"
                )
            }
            if (result.isClosed) {
                // A close with no cause is the stream ending normally; a close
                // WITH one is the provider's error, which must reach the caller
                // rather than looking like a clean finish.
                result.exceptionOrNull()?.let { throw it }
                return@coroutineScope
            }
            emit(result.getOrThrow())
        }
    }
}

// ─── Merged from ModelReleaseIndex.kt ──────────────────────────────────────────────────

/**
 * [T-model-release-ranking] Ranks models so the newest / most capable land at
 * the top of any list the user picks from — without anyone hand-maintaining an
 * order. Mirrors iOS `ModelReleaseIndex` (Providers/ModelReleaseIndex.swift).
 *
 * Why this exists: a picker whose first entries are stale models is an active
 * hazard, not just untidy. OpenMinis#83 was filed as "GPT-5.3 CodeX Spark
 * cannot call tools"; the real cause was that the Codex backend refuses that
 * model on a ChatGPT account (`400 … not supported`), and the refusal renders
 * as an EMPTY assistant turn. A new user picked a dead model off the list and
 * concluded the app was broken. Sorting the same 12 Codex ids by release date
 * puts all 6 callable ones at the top.
 *
 * IMPORTANT — this ranks, it does NOT gate. The date/availability correlation
 * is a by-product of OpenAI's current retention policy, not a contract. Never
 * infer "callable" from a release date.
 */
object ModelReleaseIndex {
    private const val TAG = "ModelReleaseIndex"

    /** Ranking inputs for one model; `releaseDay == null` sorts last. */
    data class Rank(
        val releaseDay: Int?,
        val outputCostPerMTok: Double,
        val contextWindow: Int,
        val displayName: String,
    )

    /**
     * Newest first, then pricier (≈ larger / more capable), then bigger context,
     * then name so the order is stable and never jitters between reads.
     *
     * Undated models sink below every dated one but are NOT hidden — custom,
     * local and relay-hosted models legitimately have no catalog entry.
     */
    val comparator: Comparator<Rank> = Comparator { a, b ->
        val ad = a.releaseDay
        val bd = b.releaseDay
        if (ad != bd) {
            return@Comparator when {
                ad == null -> 1
                bd == null -> -1
                else -> bd.compareTo(ad)
            }
        }
        if (a.outputCostPerMTok != b.outputCostPerMTok) {
            return@Comparator b.outputCostPerMTok.compareTo(a.outputCostPerMTok)
        }
        if (a.contextWindow != b.contextWindow) {
            return@Comparator b.contextWindow.compareTo(a.contextWindow)
        }
        a.displayName.compareTo(b.displayName, ignoreCase = true)
    }

    private class Entry(val day: Int, val outputCost: Double?, val context: Int?)

    private var byFullId: Map<String, Entry> = emptyMap()
    private var byTail: Map<String, Entry> = emptyMap()
    private var built = false

    @Synchronized
    private fun ensureIndex() {
        if (built) return
        val full = HashMap<String, Entry>()
        val tail = HashMap<String, Entry>()
        for (provider in ModelsDevApi.registrySnapshot().values) {
            for ((rawId, m) in provider.models) {
                val day = parseReleaseDay(m.releaseDate) ?: continue
                val e = Entry(day, m.outputCost, m.contextWindow)
                val id = rawId.lowercase()
                // The same model is republished by many providers (glm-5.2 under
                // 24) and their dates disagree. Keep the NEWEST so a lagging
                // relay can't drag a current model down the list.
                full[id]?.let { if (it.day >= day) return@let else full[id] = e } ?: run { full[id] = e }
                val t = id.substringAfterLast('/')
                tail[t]?.let { if (it.day >= day) return@let else tail[t] = e } ?: run { tail[t] = e }
            }
        }
        byFullId = full
        byTail = tail
        built = true
        AppLogger.info(TAG, "release index built: full=${full.size} tail=${tail.size}")
    }

    /**
     * [T-android-model-sort-anr] Build the index now, off the main thread.
     *
     * Same work [ensureIndex] does lazily, exposed so app start can pay for it
     * on a background thread instead of leaving the bill to whichever caller
     * happens to be first. That caller was `ChatViewModel.init` on the main
     * thread, and parsing a 4.5 MB catalog there ANR'd the app while opening a
     * chat on a loaded device.
     *
     * Idempotent and safe to call from any thread: [ensureIndex] is
     * @Synchronized and returns immediately once built, so this races correctly
     * with a real caller rather than duplicating the work.
     */
    fun warmUp() = ensureIndex()

    /** Drop a cached index so a refreshed catalog is picked up. */
    @Synchronized
    fun invalidate() {
        built = false
        byFullId = emptyMap()
        byTail = emptyMap()
    }

    /** Ranking key for a model. Unknown ids get `releaseDay = null` (sink). */
    fun rank(modelId: String, displayName: String, contextWindow: Int?): Rank {
        ensureIndex()
        val hit = resolve(modelId)
        return Rank(
            releaseDay = hit?.day,
            outputCostPerMTok = hit?.outputCost ?: 0.0,
            contextWindow = contextWindow ?: hit?.context ?: 0,
            displayName = displayName,
        )
    }

    private fun resolve(raw: String): Entry? {
        val cleaned = clean(raw)
        // 1. Full id FIRST. Entries like `aion-labs/aion-2.0` and
        //    `amazon/nova-lite-v1` exist ONLY under their namespaced id; going
        //    tail-first silently loses them (58% → 80% resolution measured on a
        //    real 868-model device catalog).
        byFullId[cleaned]?.let { return it }

        var tail = stripVendorDotPrefix(cleaned.substringAfterLast('/'))
        byTail[tail]?.let { return it }

        val undated = stripDateSuffix(tail)
        if (undated != tail) byTail[undated]?.let { return it }

        // Walk the family up: gpt-5.6-sol → gpt-5.6 → gpt
        val parts = undated.split('-').toMutableList()
        while (parts.size > 1) {
            parts.removeAt(parts.size - 1)
            byTail[parts.joinToString("-")]?.let { return it }
        }

        // Deliberately NO fuzzy/substring fallback: an earlier prototype matched
        // `us.anthropic.claude-opus-4-5-20251101-v1:0` onto `claude-opus-5` — a
        // different model six months off. A wrong date is worse than none; it
        // promotes a stale model to the top, the exact failure this prevents.
        return null
    }

    private fun clean(raw: String): String {
        var s = raw.trim().lowercase().substringBefore(':').substringBefore('@')
        for (region in listOf("us.", "eu.", "apac.", "global.")) {
            if (s.startsWith(region)) { s = s.removePrefix(region); break }
        }
        return s
    }

    /** Strip a leading `vendor.` namespace but never a version dot (`gpt-5.6`). */
    private fun stripVendorDotPrefix(s: String): String {
        val dot = s.indexOf('.')
        if (dot <= 0) return s
        val head = s.substring(0, dot)
        if (!head.all { it.isLetter() }) return s
        return s.substring(dot + 1)
    }

    /** Remove a trailing `-20YYMMDD` snapshot stamp. */
    private fun stripDateSuffix(s: String): String {
        if (s.length <= 9) return s
        val tail = s.takeLast(9)
        if (tail[0] != '-') return s
        val digits = tail.drop(1)
        if (digits.length != 8 || !digits.all { it.isDigit() } || !digits.startsWith("20")) return s
        return s.dropLast(9)
    }

    /**
     * Parse `YYYY-MM-DD` or `YYYY-MM` into a sortable day number. Returns null
     * for anything else rather than guessing — 181 bundled entries use the
     * short form, and a strict parser would silently sink all of them.
     */
    fun parseReleaseDay(raw: String?): Int? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val parts = s.split('-')
        if (parts.size != 2 && parts.size != 3) return null
        val year = parts[0].takeIf { it.length == 4 }?.toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
        val day = if (parts.size == 3) {
            parts[2].toIntOrNull()?.takeIf { it in 1..31 } ?: return null
        } else 1
        // Ordering only — a plain y/m/d packing sorts identically to real dates
        // and avoids dragging in a calendar for what is purely a sort key.
        return year * 10_000 + month * 100 + day
    }
}

// ─── Merged from OpenCodeSessionHeader.kt ──────────────────────────────────────────────────

/**
 * [T-android-opencode-session-header] Attach `x-opencode-session` to requests
 * bound for OpenCode Go.
 *
 * OpenCode Go (`https://opencode.ai/zen/go/v1`, OpenAI-compatible) began
 * enforcing this header on 2026-09-06; without it the endpoint answers
 * `400 "Request is missing x-opencode-session and cannot be routed
 * efficiently"` (GH #327), which left Minis unusable against the service
 * (GH #304). The value is what the operator calls "one stable ID per
 * conversation" and is used to key their prompt cache.
 *
 * The operator's stated requirements, and how each is met:
 *
 *  1. *A non-sensitive, unique session ID (a random UUID is fine).* Chat
 *     session ids are `UUID.randomUUID()` (ChatRepository.createSession), so
 *     they carry no account, key or device identity.
 *  2. *Stable across every request, retry and resumption of one conversation;
 *     a new conversation gets a new one.* The id is the session's DB primary
 *     key, so it survives restarts and provider re-creation (model switch,
 *     fallback, OAuth refresh) — see [headersFor]'s contract about
 *     WHICH id callers must pass.
 *  3. *Keep identifying the client honestly in the User-Agent.* Untouched —
 *     this adds a header and never edits the UA, which still reports Minis
 *     via [MinisUserAgent].
 *  4. *No API key or personal information in the id.* Guaranteed by (1).
 *
 * ## Why the header is gated on the host rather than sent everywhere
 *
 * Sending it unconditionally would hand a stable per-conversation identifier
 * to every third-party endpoint a user configures, which is a tracking vector
 * we would be introducing on their behalf. The gate is a privacy control, not
 * a feature switch.
 *
 * ## Why host equality/suffix rather than `contains("opencode.ai")`
 *
 * A substring test also matches `https://opencode.ai.example.com/v1` and
 * `https://not-opencode.ai/v1` — hosts controlled by someone else entirely —
 * so the cheap check leaks the very identifier it exists to protect. Parsing
 * the URL and comparing the host (exactly, or as a dot-anchored subdomain)
 * costs one call and cannot be spoofed by a crafted prefix or suffix.
 */
object OpenCodeSessionHeader {

    /** The header OpenCode Go requires. Lowercase per their docs; HTTP header names are case-insensitive. */
    const val HEADER = "x-opencode-session"

    /** Registrable domain of the service. Matched exactly or as `*.opencode.ai`. */
    private const val HOST = "opencode.ai"

    /**
     * True when [baseUrl] points at OpenCode Go.
     *
     * Null/blank (the official-endpoint case, where `effectiveBaseURL` is null
     * because no custom base URL is set) and unparseable input are false — a
     * URL we cannot read the host of is one we must not send the id to.
     */
    fun matches(baseUrl: String?): Boolean {
        val raw = baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        // Users type the endpoint from the docs freely, and the base-URL field
        // only trims — it never adds a scheme. `toHttpUrlOrNull` requires one,
        // so a scheme-less `opencode.ai/zen/go/v1` would otherwise be read as
        // "not OpenCode" and the header silently dropped. Matches iOS, which
        // normalizes the same way ([T-opencode-session-header]).
        val normalized = if (raw.contains("://")) raw else "https://$raw"
        val host = normalized.toHttpUrlOrNull()?.host ?: return false
        // `HttpUrl.host` is already lowercased and punycode-encoded, so a
        // literal comparison is safe. The leading dot on the suffix test is
        // what stops `not-opencode.ai` from matching.
        return host == HOST || host.endsWith(".$HOST")
    }

    /**
     * The headers to merge into a provider built for [baseUrl], given the
     * conversation's [sessionId].
     *
     * Returns empty for anything that is not OpenCode Go, and for a missing or
     * blank session id — a request with no id is better than one carrying a
     * placeholder, since a wrong-but-present value would silently poison the
     * upstream prompt cache instead of failing loudly.
     *
     * **Callers must pass the PERSISTED session id.** A draft chat's id is a
     * synthetic placeholder (`__new__…`, optionally carrying `__fld__`/`__grp__`
     * routing suffixes) that `ChatViewModel.ensureSession()` replaces with a
     * real UUID when the first message lands. Passing the placeholder would
     * send one id on a conversation's first turn and a different one on every
     * turn after — requirement (2) violated silently, since the endpoint keeps
     * answering while its cache never hits. [isPersistedSessionId] is the guard,
     * applied here so no call site can forget it.
     */
    fun headersFor(baseUrl: String?, sessionId: String?): Map<String, String> {
        if (!matches(baseUrl)) return emptyMap()
        val id = sessionId?.trim().orEmpty()
        if (!isPersistedSessionId(id)) return emptyMap()
        return mapOf(HEADER to id)
    }

    /**
     * Whether [sessionId] is a real, persisted conversation id rather than a
     * draft placeholder.
     *
     * Drafts are keyed `__new__…` (ChatViewModel's draft id, which may append
     * `__fld__<folder>` / `__grp__<group>` routing segments). Rejecting the
     * whole `__…__` shape rather than the exact literal keeps this correct if
     * another synthetic key is introduced later: an unknown synthetic id is
     * refused, which costs a header on one turn, whereas accepting it would
     * break cross-turn stability for the whole conversation.
     */
    fun isPersistedSessionId(sessionId: String?): Boolean {
        val id = sessionId?.trim().orEmpty()
        return id.isNotEmpty() && !id.startsWith("__")
    }
}

// ─── Merged from ResponseIdleTimeoutSetting.kt ──────────────────────────────────────────────────

/**
 * [T-sse-idle-timeout] The per-provider "response idle timeout": the longest
 * wait for a streamed reply to start, and the floor for the wait between its
 * pieces. It is NOT a limit on how long a reply may take - a stream that
 * keeps delivering never times out. Port of iOS ResponseIdleTimeoutSetting
 * (b15ac8aa3); same default, range, presets and per-device storage.
 *
 * What it changes on Android (OpenAI-compatible Chat Completions only):
 * - The time-to-first-byte budget, 120 s until now. A local model prefilling
 *   a long context can send nothing - not even response headers - for
 *   minutes; that read as a dead connection and was retried.
 * - The socket read timeout, 600 s until now, is raised to setting + 30 s
 *   when the setting is above it, so the transport never gives up first.
 *   It is never lowered: OkHttp's read timeout counts any bytes, so it is
 *   already the between-pieces limit, and long reasoning silences on some
 *   backends need the 600 s.
 * Unlike iOS there is no stream-event watchdog to feed: hidden reasoning
 * arrives as bytes and resets the read timeout already.
 *
 * Stored per device, keyed by provider instance id; nothing stored means the
 * default, so existing providers behave exactly as before. Not a
 * ProviderInstance field (it would need a DB column, sync and backup wiring
 * for a value about this device's link to one server), so it is not synced
 * and not in backups - as on iOS.
 */
object ResponseIdleTimeoutSetting {
    const val DEFAULT_SECONDS = 120
    const val MIN_SECONDS = 30
    /** No "never": a stream that really died would hold the turn, a session slot and the keep-alive forever. */
    const val MAX_SECONDS = 3600
    val PRESETS = listOf(120, 300, 600, 1800, 3600)

    /** The transport's own read timeout, which the setting only ever raises. */
    const val SOCKET_READ_DEFAULT_SECONDS = 600

    private const val PREFS = "provider_response_idle_timeout"

    fun clamp(seconds: Int): Int = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

    fun key(instanceId: String): String = "provider.responseIdleTimeout.$instanceId"

    fun seconds(context: Context, instanceId: String?): Int {
        if (instanceId.isNullOrEmpty()) return DEFAULT_SECONDS
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains(key(instanceId))) return DEFAULT_SECONDS
        return clamp(p.getInt(key(instanceId), DEFAULT_SECONDS))
    }

    /** Storing the default removes the key, so "default" stays "never set". */
    fun set(context: Context, instanceId: String, seconds: Int?) {
        val e = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (seconds == null || clamp(seconds) == DEFAULT_SECONDS) e.remove(key(instanceId)) else e.putInt(key(instanceId), clamp(seconds))
        e.apply()
    }

    /** Socket read timeout a streaming request needs; null = leave the client's 600 s. */
    fun readTimeoutSeconds(idleSeconds: Int): Int? =
        if (idleSeconds > SOCKET_READ_DEFAULT_SECONDS) idleSeconds + 30 else null
}

// ─── Merged from StopReasons.kt ──────────────────────────────────────────────────

/**
 * [T-android-model-refusal] Terminal stop reasons that mean "the model DECLINED
 * this request". Port of iOS `AgentStopReason.refusal` (54c8ea2a4 + the OpenAI
 * mappings in OpenAIAgentProvider).
 *
 * Android carries a turn's stop reason as the provider's own string
 * ([LLMStreamChunk.Finished.stopReason]), so this is the one place that knows
 * which spellings are a refusal:
 *   - Anthropic `stop_reason: "refusal"` — the safety classifier declined the
 *     request: HTTP 200, input tokens billed, EMPTY content. Seen as a false
 *     positive on ordinary agent turns (large system prompt + tool set) on
 *     Claude Fable 5 / Sonnet 5.5.
 *   - OpenAI `finish_reason: "content_filter"` (Chat Completions), and the
 *     Responses API's `response.incomplete` with
 *     `incomplete_details.reason = "content_filter"` — the safety system
 *     blocked the response.
 *
 * A refusal is deterministic: resending the identical request is declined
 * again. It must not go through the empty-turn nudge / retry path, and it
 * must not end the run silently — the agent loop surfaces it on the reply.
 */
object StopReasons {
    const val REFUSAL = "refusal"
    const val CONTENT_FILTER = "content_filter"

    fun isRefusal(stopReason: String?): Boolean =
        stopReason == REFUSAL || stopReason == CONTENT_FILTER

    /**
     * [T-android-max-tokens-notice] The reply hit the output-token limit. Port
     * of iOS `AgentStopReason.maxTokens`. Spellings that reach the agent loop:
     *   - OpenAI Chat Completions `finish_reason: "length"` (the Responses
     *     path maps `incomplete_details.reason = "max_output_tokens"` to
     *     "length" too, OpenAIProvider);
     *   - Anthropic `stop_reason: "max_tokens"`;
     *   - Gemini `finishReason: MAX_TOKENS`, mapped to "max_tokens"
     *     (GeminiProvider).
     * "max_output_tokens" is accepted as well, in case a path forwards the
     * Responses reason unmapped.
     */
    fun isMaxTokens(stopReason: String?): Boolean =
        stopReason == "length" || stopReason == "max_tokens" || stopReason == "max_output_tokens"

    /**
     * [FIX-261018-12] The error shown for a refusal. Port of iOS
     * `AIChatViewModel.refusalErrorText`: with the provider's category
     * (`categoryMessage`, already formatted) it names the policy and says
     * resending will not help; otherwise the generic text. The provider's own
     * explanation (English, from the API) follows after a blank line.
     */
    fun refusalErrorText(categoryMessage: String?, genericMessage: String, explanation: String?): String {
        val base = categoryMessage?.takeIf { it.isNotBlank() } ?: genericMessage
        val detail = explanation?.trim()?.takeIf { it.isNotEmpty() } ?: return base
        return base + "\n\n" + detail
    }
}

// ─── Merged from ThinkingLevelCatalog.kt ──────────────────────────────────────────────────

/**
 * [T-android-thinking-level-arch] Declarative catalog of each model's thinking-
 * level ceiling. Adding a model = adding a rule; retiring one = removing a rule.
 * It touches no other code path — a model that matches no rule falls back to
 * [catalogMaxThinkingLevel]'s conservative supportsReasoning default.
 *
 * Kept content-aligned with iOS ThinkingLevelCatalog.swift (same understanding
 * of what each model can do), Kotlin idiom on this side.
 */
object ThinkingLevelCatalog {
    private data class Rule(val match: (String) -> Boolean, val max: ThinkingLevel)

    private val rules: List<Rule> = listOf(
        // GPT-5.6 family: sol / terra / luna all reach MAX. ULTRA is a
        // client-side "Max + orchestration" concept, never a wire effort — the
        // effort layer maps both MAX and ULTRA to "max". Keep in lockstep with
        // iOS ThinkingLevelCatalog.swift.
        // [T-gpt6-astra] gpt-6-astra: same tier as sol/terra. Its catalog
        // metadata lists an "ultra" wire value, but that is deliberately NOT
        // special-cased — MAX and ULTRA both go out as "max" (see above), so
        // the existing clamp already covers it.
        Rule({ it.startsWith("gpt-6-astra") }, ThinkingLevel.MAX),
        // [T-gpt6-sol-luna] gpt-6-sol / gpt-6-luna: same MAX tier as astra.
        // Sol's catalog metadata omits "ultra" (the backend rejects that tier,
        // exactly as on gpt-5.6-sol), but that needs no special case here —
        // MAX and ULTRA both serialize to "max", so the ceiling is the whole
        // story. Kept as its own rule rather than widening astra's to
        // `gpt-6-` so a future gpt-6 member with a LOWER ceiling cannot be
        // silently lifted to MAX by a family-wide match.
        Rule({ it.startsWith("gpt-6-sol") || it.startsWith("gpt-6-luna") }, ThinkingLevel.MAX),
        // [T-gpt61-sol] gpt-6.1-sol: declared tiers low/medium/high/xhigh/max,
        // so MAX. Needs its own rule — "gpt-6.1-sol" does NOT start with
        // "gpt-6-sol" (the version dot sits where the hyphen is), so without
        // this it fell through to the XHIGH default and hid the `max` tier.
        // The dash spelling covers relays that normalise dots to dashes (same
        // pair iOS ThinkingLevelCatalog.swift matches).
        Rule({ it.startsWith("gpt-6.1-sol") || it.startsWith("gpt-6-1-sol") }, ThinkingLevel.MAX),
        Rule({ it.startsWith("gpt-5.6-sol") || it.startsWith("gpt-5.6-terra") }, ThinkingLevel.MAX),
        Rule({ it.startsWith("gpt-5.6-luna") }, ThinkingLevel.MAX),
        Rule({ it.startsWith("gpt-5.5") }, ThinkingLevel.XHIGH),
        // Third-party models known to top out at high.
        // MiMo ships BOTH id spellings in the wild: catalog docs say
        // "MiMo-2.5" but the live API (api.xiaomimimo.com /v1/models) returns
        // "mimo-v2.5" / "mimo-v2.5-pro" — the old "mimo-2.5" substring missed
        // those, so the clamp passed xhigh straight through to a backend that
        // 400s on it. Match the family, not one spelling (mirrors iOS 72968c4f).
        Rule({ it.contains("mimo") || it.contains("agnes") }, ThinkingLevel.HIGH),
        // ByteDance seed (Volcano Ark "seed-1.6…"/"seed-2.0…", OpenRouter
        // "bytedance-seed/…"): rejects xhigh with "Invalid reasoning_effort:
        // xhigh". Ark's ladder tops out at high.
        Rule({ it.contains("seed-") || it.contains("bytedance-seed") }, ThinkingLevel.HIGH),
        // [T-android-deepseek-flash-scope] (GH#356, iOS 466b00f9b) DeepSeek's
        // ladder tops out at max and it rejects xhigh.
        //
        // This is a FALLBACK, not the primary fix: the catalog is consulted
        // only when the model declares no reasoning-effort tiers of its own, so
        // whenever the models.dev snapshot resolves, the declared set already
        // yields the right ceiling. It matters when that lookup misses — a
        // relay publishing the id under its own name, snapshot drift, or a
        // future `deepseek-flash-lite` — where the XHIGH default would
        // otherwise offer the user a tier the backend refuses. Exactly why the
        // neighbouring mimo / seed- entries exist.
        //
        // Covers both the bare `deepseek-flash` DeepSeek now recommends and the
        // legacy `deepseek-v4-*` alias.
        Rule({ it.startsWith("deepseek-flash") || it.contains("deepseek-v4") }, ThinkingLevel.MAX),
        // Anthropic Opus 4.x adaptive-thinking family. The old per-version
        // startsWith("claude-opus-4.7"/"claude-opus-4.6") checks never matched:
        // LLMModel.id separates the minor version with a hyphen
        // (claude-opus-4-8 / claude-opus-4-6), not a dot, so every Claude Opus
        // fell through to the XHIGH default instead of MAX — and Opus 4.8 had no
        // rule at all. Normalize dots→hyphens first, then a single prefix match
        // covers 4.6 / 4.7 / 4.8 and future 4.x (mirrors iOS normalizedHasPrefix).
        Rule({ normalizedHasPrefix(it, "claude-opus-4") }, ThinkingLevel.MAX),
        // [T-anthropic-opus55-catalog] Claude Opus 5.x. NOT covered by the
        // rule above — that one is anchored on "claude-opus-4", so Opus 5.5
        // would have fallen through to the XHIGH default and quietly denied
        // the user the `max` effort tier the model actually accepts. Spelled
        // as its own 5-series rule for the same reason the 4.x one is a
        // prefix: it has to keep matching 5.6, 5.7, … without another edit.
        Rule({ normalizedHasPrefix(it, "claude-opus-5") }, ThinkingLevel.MAX),
        // [T-anthropic-sonnet55] Claude Sonnet 5.5 declares low…max. The
        // bundled models.dev snapshot carries it now, so the declared set
        // already yields MAX; this rule is the floor for when that lookup
        // misses (a stale on-device catalog, a relay spelling), where
        // catalogMaxThinkingLevel would otherwise fall to `ruleTop ?: XHIGH`
        // and Max would vanish from the picker — the Opus 5.5 trap above.
        // Scoped to 5-5: claude-sonnet-5 itself resolves from the catalog.
        // Direct ids only (hyphen or dotted), not vendor-prefixed relay ids
        // such as `anthropic/claude-sonnet-5.5`: a rule can RAISE a ceiling
        // above declared tiers, and those relays speak their own ladder.
        // Same rule as iOS ThinkingLevelCatalog.swift (965ff194d).
        Rule({ normalizedHasPrefix(it, "claude-sonnet-5-5") }, ThinkingLevel.MAX),
    )

    /** Prefix match that treats "." and "-" interchangeably in the version
     *  separator so a rule matches whether the id is dotted or hyphenated. */
    private fun normalizedHasPrefix(id: String, prefix: String): Boolean =
        id.replace('.', '-').startsWith(prefix)

    /** Null means the catalog doesn't cover this model — the caller should fall
     *  through to the supportsReasoning default. */
    fun declaredMaxLevel(modelId: String): ThinkingLevel? {
        val lid = modelId.lowercase()
        return rules.firstOrNull { it.match(lid) }?.max
    }
}

/**
 * [T-android-thinking-level-arch] "How high can this model's thinking go?"
 * resolved through the built-in tiers only (no user override — see
 * [ModelEntry.effectiveMaxThinkingLevel] for that):
 *   1. supportsReasoning == false → OFF (checked BEFORE catalog rules so a
 *      broadened family rule can't lift a non-reasoning member's ceiling).
 *   2. ThinkingLevelCatalog rule.
 *   3. true/null → XHIGH (conservative default so a reasoning model isn't
 *      accidentally capped below the tiers every provider already accepted
 *      pre-GPT-5.6).
 */
val LLMModel.catalogMaxThinkingLevel: ThinkingLevel
    get() {
        // A model that can't reason has max level OFF regardless of any
        // catalog family rule — family rules match by id substring, so a
        // broadened rule (e.g. "mimo" covering mimo-v2.5) must not lift the
        // ceiling of that family's non-reasoning members (mimo-v2.5-tts/-asr).
        // [T-fallback-thinking-preclamp]
        if (supportsReasoning == false) return ThinkingLevel.OFF
        // [T-thinking-levels-data-driven] A declared effort set is a stronger
        // statement than any id-substring rule: it names the exact tiers the
        // backend accepts. Take its top tier as the ceiling so a model whose
        // declaration reaches beyond the hardcoded default (XHIGH) — e.g.
        // zhipuai glm-5.2 / deepseek-v4, both `["high","max"]` — is actually
        // reachable from the UI.
        //
        // Without this the two layers disagreed and the user was pinned to a
        // tier below what they asked for: the catalog declared "max", the wire
        // clamp would have passed "max" straight through, but the picker
        // topped out at the XHIGH default below — so the best a user could
        // request was "xhigh", which is NOT in `["high","max"]`, and
        // clampEffort snapped it DOWN to "high". Field report: deepseek-v4 and
        // GLM could never reach max on Android while iOS could.
        //
        // Deliberately fixes only the CEILING. clampEffort stays exactly as it
        // is — its job is to stop an undeclared tier reaching the backend
        // (which 400s), and loosening it would trade this bug for that one.
        // Mirrors iOS LLMTypes.swift `catalogMaxThinkingLevel` (47dc71b3).
        // [T-thinking-max-unreachable] …but only ever as a RAISE, never as a cut.
        //
        // The declared set is what models.dev happens to list, and it is often
        // incomplete for a model whose family rule we know reaches higher:
        // gpt-5.6-sol has an explicit MAX rule, yet commonly declares only
        // ["low","medium","high"]. Returning the declared top verbatim let that
        // incomplete list LOWER a known ceiling, so MAX vanished from the picker
        // for exactly the models the rule exists to describe. Take the higher of
        // the two. clampEffort is untouched, so this cannot send an undeclared
        // tier. Mirrors iOS LLMTypes.swift `catalogMaxThinkingLevel`.
        val ruleTop = ThinkingLevelCatalog.declaredMaxLevel(id)
        selectableThinkingLevels.lastOrNull()?.let { declaredTop ->
            return if (ruleTop != null && ruleTop.rank > declaredTop.rank) ruleTop else declaredTop
        }
        return ruleTop ?: ThinkingLevel.XHIGH
    }

/**
 * [T-thinking-levels-data-driven] The thinking levels worth OFFERING for this
 * model, derived from the catalog's declared effort tiers.
 *
 * The wire path ([com.openminis.app.provider.thinking.ThinkingRuleResolver.clampEffort])
 * snaps any request onto the declared set, so when a model declares a sparse
 * set — `["high","max"]` is the single most common sparse shape in the bundled
 * catalog (79 of the 339 deepseek-v4/glm-5.x entries that declare effort tiers,
 * including official deepseek-v4 and zhipuai glm-5.2) — the generic UI levels
 * collapse onto one or two distinct wire values. The user then drags a slider
 * that provably changes nothing.
 *
 * Returning one level per DISTINCT declared tier makes the picker honest: every
 * option the user can pick produces a different request. Empty when nothing is
 * declared, so the legacy id-rule ceiling still applies.
 *
 * OFF is never included — it is a separate toggle, not an effort tier, and the
 * OFF wire value is chosen by the resolver's off-effort handling, not here.
 * `none`/`minimal` are likewise OFF-ish tiers owned by that toggle.
 *
 * Mirrors iOS `LLMModel.selectableThinkingLevels`; the mapping list is kept in
 * the same weakest→strongest order because callers take [lastOrNull] as the
 * ceiling.
 */
val LLMModel.selectableThinkingLevels: List<ThinkingLevel>
    get() {
        val declared = reasoningEffortValues
        if (declared.isNullOrEmpty()) return emptyList()
        val mapping = listOf(
            "low" to ThinkingLevel.LOW,
            "medium" to ThinkingLevel.MEDIUM,
            "high" to ThinkingLevel.HIGH,
            "xhigh" to ThinkingLevel.XHIGH,
            "max" to ThinkingLevel.MAX,
        )
        val set = declared.map { it.lowercase() }.toSet()
        return mapping.filter { set.contains(it.first) }.map { it.second }
    }

/**
 * [T-android-thinking-level-arch] The four-level resolution the rest of the app
 * consults: the user's manual override on the entry (highest priority) wins over
 * the catalog/default. `entry.model` already folds ModelOverrides into the base
 * model, so read the ceiling off the resolved model there.
 */
val ModelEntry.effectiveMaxThinkingLevel: ThinkingLevel
    get() = overrides.maxThinkingLevel ?: model.catalogMaxThinkingLevel

// ─── Merged from ToolJsonRepair.kt ──────────────────────────────────────────────────

/**
 * JSON repair for malformed / incomplete tool calls (T-tool-json-repair b2c4f8a6).
 *
 * Mirrors the iOS implementation in AIChatViewModel.swift (repairToolArgs / preflight
 * pre-pass). Operates on the already-parsed [JSONObject] that the streaming provider
 * surfaced, optionally consulting a raw stream "tail" snapshot from the tool-input
 * chunk ring when the dict is empty (truncation case).
 *
 * Three strategies, applied in order, each gated on actually being needed:
 *
 * 1. Truncation repair — if [args] is empty but [rawTail] looks like a JSON object
 *    that just got cut, retry parsing with a small set of closure suffixes appended.
 * 2. Type coercion — for each required field present but not a String, coerce via
 *    `toString()` so the downstream blank-string preflight check has something usable.
 * 3. Fuzzy field-name match — for each missing required field, look for a sibling key
 *    whose Levenshtein distance is exactly 1 and rename it. Catches one-off typos
 *    like `comand` → `command`.
 *
 * The repaired [JSONObject] shadows the original at the preflight call site; the
 * caller logs the [Outcome.repairs] tags at WARNING level when non-empty.
 */
object ToolJsonRepair {

    /**
     * Mutates [args] in-place and returns the list of repair strategy tags
     * that fired (empty when nothing changed). Caller is responsible for
     * logging the tags at WARNING level when non-empty.
     */
    fun repair(
        toolName: String,
        args: JSONObject,
        rawTail: String?,
        tools: List<AgentToolDefinition>,
    ): List<String> {
        val toolDef = tools.firstOrNull { it.name == toolName }
            ?: return emptyList()

        val repairs = mutableListOf<String>()

        // Strategy 1: truncation repair. Only fires when the dict is empty
        // (or otherwise unusable) but the raw stream tail looks like a JSON
        // object that just got cut. Try appending closure suffixes; the
        // first one that parses wins, and we copy fields back into [args].
        if (args.length() == 0 && !rawTail.isNullOrBlank()) {
            val tail = rawTail.trim()
            val suffixes = listOf("", "\"", "\"}", "\"]}", "}", "}}", "]}", "]}}", "]", "]]")
            for (suffix in suffixes) {
                val candidate = tail + suffix
                val parsed = tryParseObject(candidate) ?: continue
                val keys = parsed.keys().asSequence().toList()
                for (k in keys) args.put(k, parsed.opt(k))
                repairs.add("truncation+" + if (suffix.isEmpty()) "noop" else suffix)
                break
            }
        }

        // Strategy 2: type coercion on required fields.
        for (field in toolDef.required) {
            if (!args.has(field)) continue
            val raw = args.opt(field) ?: continue
            if (raw is String) continue
            if (raw === JSONObject.NULL) continue
            val coerced = raw.toString()
            if (coerced.trim().isNotEmpty()) {
                args.put(field, coerced)
                repairs.add("type-coerce:$field")
            }
        }

        // Strategy 3: fuzzy field-name match for missing required fields. Skip
        // sibling keys that are themselves a recognized schema field — don't
        // steal a sibling that the tool helper would have read directly.
        val schemaFields = toolDef.parameters.keys
        for (field in toolDef.required) {
            if (args.has(field)) continue
            val keys = args.keys().asSequence().toList()
            val candidate = keys.firstOrNull { key ->
                key !in schemaFields && levenshteinAtMostOne(key, field)
            } ?: continue
            args.put(field, args.opt(candidate))
            args.remove(candidate)
            repairs.add("fuzzy:$candidate->$field")
        }

        return repairs
    }

    private fun tryParseObject(s: String): JSONObject? = try {
        JSONObject(s)
    } catch (_: Throwable) {
        null
    }

    /**
     * `true` iff Levenshtein edit distance between [a] and [b] is exactly 1
     * (case-insensitive). Tight short-circuit — we don't care about distance > 1.
     */
    private fun levenshteinAtMostOne(a: String, b: String): Boolean {
        val al = a.lowercase()
        val bl = b.lowercase()
        if (al == bl) return false // distance 0 = same key, not a repair candidate
        val diff = al.length - bl.length
        if (diff > 1 || diff < -1) return false
        if (al.length == bl.length) {
            var mismatches = 0
            for (i in al.indices) {
                if (al[i] != bl[i]) {
                    mismatches += 1
                    if (mismatches > 1) return false
                }
            }
            return mismatches == 1
        }
        val longer = if (al.length > bl.length) al else bl
        val shorter = if (al.length > bl.length) bl else al
        var i = 0
        var j = 0
        var skipped = false
        while (i < longer.length && j < shorter.length) {
            if (longer[i] == shorter[j]) {
                i += 1; j += 1
            } else if (!skipped) {
                i += 1; skipped = true
            } else {
                return false
            }
        }
        return true
    }
}

// ─── Merged from ToolPairing.kt ──────────────────────────────────────────────────

/**
 * [T-android-responses-tool-id-normalize] The one notion of "this tool result
 * answers that tool call", shared by every pairing check. Port of iOS
 * `AIChatViewModel.pairingKey` (bc142f750).
 *
 * The OpenAI Responses path stores a COMBINED id on each tool call and its
 * result, `"<call_id>|<fc_id>"` (OpenAIProvider.combineResponsesAPIIds), and
 * the wire splits it and matches on the call_id half. A history can hold both
 * forms for the same call - a model switch or a fallback to a Chat
 * Completions model, history reloaded from the DB, a relay that rewrote ids -
 * so comparing raw ids calls a correctly paired call/result an orphan:
 * the history repair then deleted a real result and injected an "interrupted"
 * placeholder for a tool that had worked.
 *
 * The key is the part before "|". Chat Completions, Anthropic and Gemini ids
 * contain no "|", so for them this is the identity and nothing changes.
 */
object ToolPairing {
    fun key(id: String): String {
        val sep = id.indexOf('|')
        return if (sep < 0) id else id.substring(0, sep)
    }
}

// ─── Merged from UserAgentOverride.kt ──────────────────────────────────────────────────

/**
 * [T-provider-custom-user-agent] Single chokepoint for the per-provider
 * User-Agent override. Each provider/models-api request builder calls this
 * right before `.build()`, so the "null/blank → fall back, non-blank →
 * replace" rule lives in exactly one place.
 *
 * `.header(...)` replaces any value the builder set earlier (e.g. the Codex
 * `codex_cli_rs/...` UA or the Anthropic OAuth `claude-cli/...` UA), so a
 * non-blank override wins over the provider default.
 *
 * **Fallback policy (T-android-default-ua):** when `customUserAgent` is
 * null/blank, we now apply [MinisUserAgent.DEFAULT] instead of leaving
 * the builder UA-less (which lets OkHttp insert its own `okhttp/4.12.0`).
 * The default carries the Minis version so request logs upstream can be
 * traced back to the app build that issued them — matching the
 * "branded UA except where a specific client identity is required"
 * intent of the feature.
 *
 * Callers that issue requests under a SPECIFIC client identity (Codex
 * OAuth's `codex_cli_rs/...`, Anthropic OAuth's `claude-cli/...`) MUST
 * pass `defaultUserAgent = null` so this helper leaves their previously-
 * set UA header alone. Everywhere else the default takes effect.
 */
fun Request.Builder.applyUserAgentOverride(
    customUserAgent: String?,
    defaultUserAgent: String? = MinisUserAgent.DEFAULT,
): Request.Builder {
    val ua = customUserAgent?.trim()
    when {
        !ua.isNullOrEmpty() -> header("User-Agent", ua)
        defaultUserAgent != null -> header("User-Agent", defaultUserAgent)
        // else: leave whatever UA the builder already had (preserves
        // Codex / Claude CLI fingerprints on OAuth paths).
    }
    return this
}

/**
 * [T-android-default-ua] Branded User-Agent used by every Minis-originated
 * outbound request that doesn't have a SDK-specific UA requirement.
 *
 * Format mirrors iOS exactly:
 *   `Minis/<version> (Android <release>; <model>)`
 *
 * e.g. `Minis/0.14-preview (Android 13; Pixel 4a)` — same shape as iOS's
 * `Minis/1.10 (iOS 26.5; iPhone)`.
 *
 * Version comes from BuildConfig (auto-tracks every release). OS release
 * from `Build.VERSION.RELEASE` (the marketing version a user recognises;
 * the API-level SDK_INT is omitted for parity with iOS, which doesn't
 * carry a sibling axis). Model is `Build.MODEL` — already broadcast by
 * default OkHttp UAs, useful for device-specific diagnosis without
 * de-anonymising the user further.
 *
 * Built lazily — only the first request constructs the string, so the
 * `Build.*` reads (cheap but JNI-bound) don't cost startup time.
 */
object MinisUserAgent {
    val DEFAULT: String by lazy {
        val release = android.os.Build.VERSION.RELEASE ?: "unknown"
        val model = (android.os.Build.MODEL ?: "unknown").trim().ifEmpty { "unknown" }
        "Minis/${BuildConfig.VERSION_NAME} (Android $release; $model)"
    }
}
