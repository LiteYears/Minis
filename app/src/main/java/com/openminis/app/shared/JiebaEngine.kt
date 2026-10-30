package com.openminis.app.shared

import android.content.Context
import android.icu.text.BreakIterator
import java.io.File
import java.util.Locale

/**
 * [T-android-text-segmenter] Chinese segmentation path for [TextSegmenter],
 * backed by CppJieba via JNI (see cpp/jieba_jni.cpp).
 *
 * The native layer reads its dictionaries from a directory path, but the two
 * dict files ship inside the APK's Assets, which have no filesystem path. So on
 * first use we copy `jieba/jieba.dict.utf8` + `jieba/hmm_model.utf8` out of
 * Assets into `filesDir/jieba/` and hand that directory to `nativeInit`. The
 * copy is a one-time cost gated by a version stamp in SharedPreferences — bump
 * [DICT_VERSION] to force a re-extract when the shipped dictionaries change.
 *
 * All native calls are serialized inside the C++ layer (a global mutex); on this
 * side [ensureInitialized] is `@Synchronized` so concurrent first-callers race
 * safely. Native failures surface as `initialized == false`, and every segment
 * call falls back to an empty list rather than throwing.
 */
internal class JiebaEngine(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var initialized = false

    /** True once the native dictionaries have loaded successfully. */
    val isReady: Boolean get() = initialized

    /**
     * Extract the dictionaries from Assets (first run only) and load them into
     * the native layer. Idempotent and cheap after the first successful call.
     * Returns true when Jieba is ready to segment.
     */
    @Synchronized
    fun ensureInitialized(): Boolean {
        if (initialized) return true
        if (!nativeLoaded) return false
        return try {
            val dictDir = extractDictionaries()
            initialized = nativeInit(dictDir.absolutePath)
            initialized
        } catch (t: Throwable) {
            // Missing asset, I/O error, or native load failure — leave
            // initialized=false so the facade routes to the system engine.
            android.util.Log.e(TAG, "Jieba init failed", t)
            false
        }
    }

    /** Exact-mode segmentation (HMM on). Empty list if init failed. */
    fun segment(text: String): List<String> {
        if (text.isEmpty() || !ensureInitialized()) return emptyList()
        return try {
            nativeSegment(text).asList()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "nativeSegment failed: ${t.message}")
            emptyList()
        }
    }

    /** Search-engine-mode segmentation. Empty list if init failed. */
    fun segmentForSearch(text: String): List<String> {
        if (text.isEmpty() || !ensureInitialized()) return emptyList()
        return try {
            nativeSegmentForSearch(text).asList()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "nativeSegmentForSearch failed: ${t.message}")
            emptyList()
        }
    }

    /**
     * [T-android-voice-correction] Part-of-speech tagging: `(token, tag)` pairs
     * using jieba's tag set (nr person, ns place, nz other proper noun,
     * nt organization, …). Empty list if init failed.
     *
     * The native side returns a FLAT array of alternating token/tag (see
     * jieba_jni.cpp for why); pair it up here. An odd-length array would mean a
     * truncated native result, so the trailing element is dropped rather than
     * paired with a null tag.
     */
    fun posTag(text: String): List<Pair<String, String>> {
        if (text.isEmpty() || !ensureInitialized()) return emptyList()
        return try {
            val flat = nativePosTag(text)
            val out = ArrayList<Pair<String, String>>(flat.size / 2)
            var i = 0
            while (i + 1 < flat.size) {
                out.add(flat[i] to flat[i + 1])
                i += 2
            }
            out
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "nativePosTag failed: ${t.message}")
            emptyList()
        }
    }

    /**
     * Copy the dictionary assets into `filesDir/jieba/` if not already present
     * for [DICT_VERSION]. Returns the target directory.
     */
    private fun extractDictionaries(): File {
        val targetDir = File(appContext.filesDir, "jieba")
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val extractedVersion = prefs.getInt(KEY_DICT_VERSION, -1)

        val allPresent = DICT_FILES.all { File(targetDir, it).exists() }
        if (extractedVersion == DICT_VERSION && allPresent) {
            return targetDir
        }

        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw java.io.IOException("Cannot create dict dir: $targetDir")
        }
        for (name in DICT_FILES) {
            copyAsset("jieba/$name", File(targetDir, name))
        }
        prefs.edit().putInt(KEY_DICT_VERSION, DICT_VERSION).apply()
        return targetDir
    }

    /** Stream-copy one Asset to [dest] (Assets can't be read by path). */
    private fun copyAsset(assetPath: String, dest: File) {
        appContext.assets.open(assetPath).use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        }
    }

    private external fun nativeInit(dictDir: String): Boolean
    private external fun nativeSegment(text: String): Array<String>
    private external fun nativeSegmentForSearch(text: String): Array<String>
    private external fun nativePosTag(text: String): Array<String>

    companion object {
        private const val TAG = "JiebaEngine"
        private const val PREFS_NAME = "text_segmenter"
        private const val KEY_DICT_VERSION = "jieba_dict_version"

        /** Bump when the shipped dictionaries change to force a re-extract. */
        private const val DICT_VERSION = 1

        private val DICT_FILES = listOf("jieba.dict.utf8", "hmm_model.utf8")

        @Volatile
        private var nativeLoaded = false

        init {
            try {
                System.loadLibrary("jieba_jni")
                nativeLoaded = true
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "jieba_jni library not available: ${t.message}")
                nativeLoaded = false
            }
        }
    }
}

// ─── Merged from SentenceSplitter.kt ──────────────────────────────────────────────────

/**
 * [T-android-voice-correction] Lightweight punctuation-based sentence
 * splitting. Pure rules, no third-party dependency. Port of iOS
 * `Shared/SentenceSplitter.swift`.
 *
 * The terminator set is defined ONCE here and reused by everything that reasons
 * about sentence boundaries — the edit-diff pass that aligns a before/after
 * edit, and the correction context builder's excerpt selection. Keeping a
 * single definition is deliberate: a second, slightly different set elsewhere
 * would silently misalign sentences between the two.
 *
 * NOTE this is a different set from [com.openminis.app.speech.ReadAloudPlayer]'s
 * TTS chunking terminators, which deliberately includes `\n` (a line break is a
 * natural place to breathe) and excludes `；`/`…`. These two are not
 * interchangeable; do not merge them.
 */
object SentenceSplitter {

    /**
     * Sentence-terminating punctuation, CJK + ASCII. Semicolons count — a
     * semicolon-joined clause is independently correctable.
     */
    val TERMINATORS: Set<Char> = setOf(
        '。', '！', '？', '；', '…',
        '.', '!', '?', ';',
    )

    fun isTerminator(c: Char): Boolean = TERMINATORS.contains(c)

    /**
     * Split [text] into sentences, KEEPING each sentence's trailing
     * punctuation. Whitespace-only fragments are dropped.
     *
     * Text with no terminator at all comes back as a SINGLE sentence, never an
     * empty list — callers always need something to diff, and returning empty
     * here would silently drop a user's whole edit.
     */
    fun split(text: String): List<String> {
        val sentences = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in text) {
            current.append(ch)
            if (isTerminator(ch)) {
                val trimmed = current.toString().trim()
                if (trimmed.isNotEmpty()) sentences.add(trimmed)
                current.setLength(0)
            }
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) sentences.add(tail)
        return sentences
    }
}

// ─── Merged from SystemSegmentEngine.kt ──────────────────────────────────────────────────

/**
 * [T-android-text-segmenter] Non-Chinese segmentation path for [TextSegmenter],
 * backed by the platform ICU word [BreakIterator] (API 24+; project minSdk is
 * 26). Handles English, Japanese, Korean, and every other locale ICU knows
 * about — its dictionary-based word iterator already word-splits CJK-adjacent
 * scripts like Japanese, so it's the right fallback whenever the input isn't
 * majority-Chinese enough to warrant Jieba.
 *
 * BreakIterator is NOT thread-safe (it carries mutable cursor state), so each
 * call gets its own instance rather than sharing one.
 */
internal object SystemSegmentEngine {

    /**
     * Split [text] into word tokens, dropping whitespace-only and empty spans.
     * Punctuation-only spans are dropped too so callers get real words, not
     * separators — matching the intent of Jieba's word output.
     */
    fun segment(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        val iterator = BreakIterator.getWordInstance(Locale.getDefault())
        iterator.setText(text)

        val result = ArrayList<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val token = text.substring(start, end)
            if (isMeaningfulToken(token)) {
                result.add(token)
            }
            start = end
            end = iterator.next()
        }
        return result
    }

    /**
     * BreakIterator has no separate "search" mode; the word iterator already
     * yields the finest reasonable tokens, so search reuses [segment].
     */
    fun segmentForSearch(text: String): List<String> = segment(text)

    /** Keep a span only if it contains at least one letter or digit — i.e.
     *  drop pure-whitespace and pure-punctuation separators. */
    private fun isMeaningfulToken(token: String): Boolean {
        if (token.isBlank()) return false
        return token.any { it.isLetterOrDigit() }
    }
}

// ─── Merged from TextSegmenter.kt ──────────────────────────────────────────────────

/**
 * [T-android-text-segmenter] Global unified word-segmentation facade.
 *
 * Routing: sample the first [SAMPLE_WINDOW] characters. Any Hiragana/Katakana in
 * the sample marks the text as Japanese and routes it away from Chinese
 * immediately; otherwise we measure the share of CJK Han ideographs
 * (U+4E00–U+9FFF) among non-whitespace characters, and at [CHINESE_THRESHOLD]
 * (50%) or above the text is treated as Chinese and routed to [JiebaEngine].
 * Everything else goes to the platform ICU [SystemSegmentEngine]
 * (BreakIterator), which handles English, Japanese, Korean, and the rest.
 *
 * If Jieba can't initialize (e.g. asset extraction fails on a broken install),
 * the Chinese path degrades gracefully to the system engine rather than
 * returning nothing.
 *
 * Usage:
 * ```
 * val tokens = TextSegmenter.getInstance(context).segment(text)
 * ```
 */
class TextSegmenter private constructor(context: Context) {

    private val jieba = JiebaEngine(context)

    /** Split [text] into word tokens using the best engine for its language. */
    fun segment(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return if (isMajorityChinese(text)) {
            val words = jieba.segment(text)
            // Fall back to the system engine if Jieba was unavailable.
            words.ifEmpty { SystemSegmentEngine.segment(text) }
        } else {
            SystemSegmentEngine.segment(text)
        }
    }

    /** Search-oriented segmentation (finer Chinese splits via Jieba's query
     *  mode). Non-Chinese text uses the same tokens as [segment]. */
    fun segmentForSearch(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return if (isMajorityChinese(text)) {
            val words = jieba.segmentForSearch(text)
            words.ifEmpty { SystemSegmentEngine.segmentForSearch(text) }
        } else {
            SystemSegmentEngine.segmentForSearch(text)
        }
    }

    /**
     * [T-android-voice-correction] Part-of-speech tagging, `(token, tag)` in
     * jieba's tag set (nr person, ns place, nz other proper noun, nt org, …).
     *
     * Chinese-only by nature — the tags come from the Chinese dictionary. For
     * non-Chinese text (or when Jieba is unavailable) this returns an EMPTY
     * list rather than falling back to the system segmenter with null tags:
     * callers ([com.openminis.app.speech.VocabularyFilter]) treat empty as
     * "no POS signal" and fall back to plain [segment] themselves, which keeps
     * the "did we get real tags?" question answerable.
     */
    fun posTag(text: String): List<Pair<String, String>> {
        if (text.isBlank() || !isMajorityChinese(text)) return emptyList()
        return jieba.posTag(text)
    }

    companion object {
        /** Sampling window: enough to classify language cheaply on long text. */
        internal const val SAMPLE_WINDOW = 200

        /** Han share (of non-whitespace chars) at/above which we pick Jieba. */
        internal const val CHINESE_THRESHOLD = 0.5

        /** Hiragana block start (U+3040) — kana means Japanese, never Chinese. */
        internal const val KANA_RANGE_START = 0x3040

        /** Katakana block end (U+30FF). */
        internal const val KANA_RANGE_END = 0x30FF

        @Volatile
        private var instance: TextSegmenter? = null

        fun getInstance(context: Context): TextSegmenter =
            instance ?: synchronized(this) {
                instance ?: TextSegmenter(context.applicationContext).also { instance = it }
            }

        /**
         * True when CJK Han ideographs make up at least [CHINESE_THRESHOLD] of
         * the non-whitespace characters in the first [SAMPLE_WINDOW] chars.
         *
         * Pure Unicode codepoint check (U+4E00–U+9FFF, CJK Unified Ideographs) —
         * no locale or dictionary lookups. Whitespace is excluded from the
         * denominator so leading indentation / spacing doesn't dilute the ratio.
         * A sample with no non-whitespace characters is not Chinese.
         *
         * Japanese exception: kanji live in the SAME Unicode block as Chinese
         * hanzi, so a pure Han-ratio test cannot tell the two apart —
         * "東京は日本の首都です" is 60% Han and would otherwise be handed to a
         * Chinese dictionary. Any Hiragana/Katakana ([KANA_RANGE_START]–
         * [KANA_RANGE_END]) is a reliable "this is Japanese, not Chinese" signal,
         * so the first kana in the sample short-circuits to false and the text
         * routes to the system engine. Kept symmetric with iOS
         * TextSegmenter.isMajorityChinese.
         *
         * `internal` so the module's unit tests can exercise it directly.
         */
        internal fun isMajorityChinese(text: String): Boolean {
            var hanCount = 0
            var nonSpaceCount = 0
            var seen = 0
            for (ch in text) {
                if (seen >= SAMPLE_WINDOW) break
                seen++
                // Hiragana (U+3040–U+309F) or Katakana (U+30A0–U+30FF) ⇒ Japanese.
                if (ch.code in KANA_RANGE_START..KANA_RANGE_END) return false
                if (ch.isWhitespace()) continue
                nonSpaceCount++
                if (ch.code in 0x4E00..0x9FFF) hanCount++
            }
            if (nonSpaceCount == 0) return false
            return hanCount.toDouble() / nonSpaceCount >= CHINESE_THRESHOLD
        }
    }
}
