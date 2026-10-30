package com.openminis.app.speech.correction

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.openminis.app.MinisApp
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.shared.SentenceSplitter
import com.openminis.app.shared.TextSegmenter
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

/**
 * [T-android-correction-admission-multisignal] Decides whether one diffed edit
 * span (`from` → `to`, already jieba-widened to word boundaries by
 * [VoiceCorrectionDiff.tokenPairs]) is an ASR/IME-style mistake worth learning
 * into `confusion_dictionary`, or a plain content rewrite to discard.
 *
 * Port of iOS `Agent/Speech/CorrectionAdmission.swift` (bf0234a7).
 *
 * Replaces the old single-axis rule (`phoneticSim >= 0.6 AND lengthRatio <= 2`),
 * which — because [PinyinNormalizer.normalize] passes Latin/digits through
 * literally — mis-scored the most common real edits (Chinese-English mixing,
 * acronym expansion, digit normalization) as rewrites and dropped them. Analysis
 * of 35 real correction_events on iOS: the old rule kept ~1 of the ~14 that
 * should have been collected.
 *
 * Pure value logic — no DB, no provider, no Android framework — so it unit-tests
 * standalone (the normalizer is injected, since the production one needs
 * `android.icu`, which is unavailable on the JVM).
 */
object CorrectionAdmission {

    /** Tunables, grouped so they're easy to retune from the tests. */
    object Config {
        /**
         * Phonetic-key Levenshtein similarity at/above which a span counts as a
         * homophone/near-homophone (covers Chinese homophones AND literally
         * similar Latin like cloud/claude). Kept at the historical 0.6 — the new
         * acronym/digit signals cover the sub-0.6 cases (MediaS→Media Server
         * etc.) without loosening this axis into false positives.
         */
        const val PHONETIC_SIMILARITY = 0.6

        /**
         * A span may be short but still be a rewrite if it's a big fraction of
         * the whole sentence. `editLocality` = changed-span chars / sentence
         * chars; above this the edit is treated as a rewrite regardless of the
         * other signals. Replaces lengthRatio as the primary "rewrite" guard.
         */
        const val MAX_EDIT_LOCALITY = 0.6

        /**
         * Acronym/subsequence relation requires the short side to be
         * meaningfully shorter than the long side (else "abc" ⊆ "abcd"
         * trivially matches near-equal strings and lets rewrites through).
         */
        const val MAX_ACRONYM_LENGTH_FRACTION = 0.9

        /**
         * [T-android-correction-latin-phonetic] Signal 4: Levenshtein similarity
         * of the two consonant skeletons at or above which a same-syllable-count
         * Latin pair counts as a near-sound. 0.30 is the loosest value that still
         * admits linux↔minis (skeletons lnx/mns, sim 0.33) while rejecting
         * cursor↔claude (crsr/cld, 0.25) — the latter already passes Signal 1.
         */
        const val LATIN_SKELETON_SIMILARITY = 0.30
    }

    /**
     * Why a span was admitted or rejected — surfaced in logs (privacy-safe: it
     * names the SIGNAL, never the user's text) and asserted in tests.
     */
    sealed class Verdict {
        /** Phonetic near-match. */
        data class Homophone(val sim: Double) : Verdict()

        /** One side is an ordered subsequence of the other. */
        object Acronym : Verdict()

        /** Chinese-number ↔ arabic / alphanumeric term. */
        object DigitNorm : Verdict()

        /** Latin near-sound (consonant-skeleton match) — e.g. linux→minis. */
        object LatinPhonetic : Verdict()

        data class RejectedReword(val sim: Double) : Verdict()

        /** Same characters, only order changed. */
        object RejectedReorder : Verdict()

        data class RejectedTooGlobal(val locality: Double) : Verdict()

        val isAdmitted: Boolean
            get() = when (this) {
                is Homophone, is Acronym, is DigitNorm, is LatinPhonetic -> true
                is RejectedReword, is RejectedReorder, is RejectedTooGlobal -> false
            }

        /** Privacy-safe reason tag for logs — never contains user text. */
        val reason: String
            get() = when (this) {
                is Homophone -> "homophone(sim=%.2f)".format(sim)
                is Acronym -> "acronym"
                is DigitNorm -> "digit_norm"
                is LatinPhonetic -> "latin_phonetic"
                is RejectedReword -> "reword(sim=%.2f)".format(sim)
                is RejectedReorder -> "reorder"
                is RejectedTooGlobal -> "too_global(loc=%.2f)".format(locality)
            }
    }

    /**
     * Judge one span. [sentenceLength] is the character length of the sentence
     * the span was diffed within — used for the locality guard. Pass the max of
     * the before/after sentence lengths (the recorder has both).
     *
     * Signal order is load-bearing and mirrors iOS: locality → reorder →
     * homophone → digitNorm → acronym.
     */
    fun judge(
        from: String,
        to: String,
        normalizer: PhoneticNormalizer,
        sentenceLength: Int,
    ): Verdict {
        val a = from.trim()
        val b = to.trim()

        // Locality first: a span that IS most of the sentence is a rewrite even
        // if it happens to look phonetically similar. Uses the larger side so a
        // long deletion still counts as a big edit.
        val spanLen = maxOf(a.length, b.length)
        val locality = if (sentenceLength > 0) spanLen.toDouble() / sentenceLength.toDouble() else 1.0
        if (locality > Config.MAX_EDIT_LOCALITY) {
            return Verdict.RejectedTooGlobal(locality)
        }

        // Pure reorder (same multiset of characters, different order) → the user
        // rearranged words, not fixed a mis-hear. Caught BEFORE phonetics,
        // because reordered text often keeps a high phonetic similarity.
        if (isPureReorder(a, b)) return Verdict.RejectedReorder

        val keyA = normalizer.normalize(a)
        val keyB = normalizer.normalize(b)

        // Signal 1 — homophone / near-homophone (phonetic-key Levenshtein).
        val sim = PinyinNormalizer.similarity(keyA, keyB)
        if (sim >= Config.PHONETIC_SIMILARITY && keyA.isNotEmpty()) {
            return Verdict.Homophone(sim)
        }

        // Signal 2 — digit / term normalization (十七→17, 二V三→v3, A P P→APP).
        if (isDigitTermNormalization(a, b)) return Verdict.DigitNorm

        // Signal 3 — acronym ⇄ expansion (MediaS→Media Server, worker→work on,
        // 上帝→上). One phonetic key is an ordered subsequence of the other and
        // meaningfully shorter.
        if (isAcronymRelated(keyA, keyB)) return Verdict.Acronym

        // Signal 4 — Latin near-sound (linux→minis). Levenshtein over the full
        // key scores these ~0.40 because the consonants differ outright, but the
        // words share syllable rhythm and a consonant landmark, which is what an
        // ASR actually confuses. Gated on the ORIGINAL text being Latin-script:
        // PinyinNormalizer renders Chinese as toneless a-z, so testing the KEY
        // for "purely a-z" would not exclude Chinese at all and would let
        // unrelated pairs like 挂载/规则 (guazai/guize) through.
        if (isLatinOrigin(a) && isLatinOrigin(b) && isLatinPhoneticSimilar(keyA, keyB)) {
            return Verdict.LatinPhonetic
        }

        return Verdict.RejectedReword(sim)
    }

    // -- Signals --

    /**
     * True when the two strings are anagrams over their non-space characters —
     * i.e. the same content reordered, not a substitution.
     */
    fun isPureReorder(a: String, b: String): Boolean {
        fun bag(s: String): Map<Char, Int> {
            val m = HashMap<Char, Int>()
            for (c in s) if (!c.isWhitespace()) m[c] = (m[c] ?: 0) + 1
            return m
        }
        val ba = bag(a)
        val bb = bag(b)
        // Require a real reorder: same non-empty multiset AND the ordered
        // strings actually differ (identical strings are handled elsewhere).
        if (ba.isEmpty() || ba != bb) return false
        return a != b
    }

    /**
     * One side's phonetic key is an ordered subsequence of the other's and
     * clearly shorter — an initialism / abbreviation of an expansion.
     */
    fun isAcronymRelated(keyA: String, keyB: String): Boolean {
        val short = if (keyA.length <= keyB.length) keyA else keyB
        val long = if (keyA.length <= keyB.length) keyB else keyA
        if (short.isEmpty() || short.length >= long.length) return false
        if (short.length.toDouble() / long.length.toDouble() >= Config.MAX_ACRONYM_LENGTH_FRACTION) {
            return false
        }
        // Ordered-subsequence test.
        var idx = 0
        for (ch in short) {
            val found = long.indexOf(ch, idx)
            if (found < 0) return false
            idx = found + 1
        }
        return true
    }

    /**
     * One side carries Chinese number words while the other carries Arabic
     * digits — the user normalized spoken numbers ("十七"→"17", "二V三"→"v3"),
     * which pinyin similarity scores as totally dissimilar. Also covers spaced
     * alphanumeric terms ("G P L 二 V 三"→"GPLv3") via the digit presence.
     */
    fun isDigitTermNormalization(a: String, b: String): Boolean {
        val chineseNumerals = "零一二三四五六七八九十百千万两".toSet()
        fun hasChineseNumeral(s: String) = s.any { it in chineseNumerals }
        fun hasArabicDigit(s: String) = s.any { it in '0'..'9' }
        // Direction-agnostic: spoken (Chinese numeral) on one side, digit on the other.
        val aCn = hasChineseNumeral(a)
        val bCn = hasChineseNumeral(b)
        val aDigit = hasArabicDigit(a)
        val bDigit = hasArabicDigit(b)
        return (aCn && bDigit && !bCn) || (bCn && aDigit && !aCn)
    }

    /**
     * [T-android-correction-latin-phonetic] True when the ORIGINAL span is
     * Latin-script — ASCII letters, digits and separators only, with at least
     * one letter.
     *
     * Used to keep Signal 4 off Chinese input: [PinyinNormalizer] renders 挂载
     * as "guazai", which is indistinguishable from a real Latin word once you
     * only look at the key. Testing the key for "purely a-z" would therefore be
     * a no-op for Chinese and would let unrelated pairs like 挂载/规则
     * (guazai/guize — same syllable count, anchored 'g') through.
     */
    fun isLatinOrigin(s: String): Boolean {
        var sawLetter = false
        for (ch in s) {
            when {
                ch.isLetter() -> {
                    // Non-ASCII letter (CJK, accented, Cyrillic…) → not Latin origin.
                    if (ch.code > 0x7F) return false
                    sawLetter = true
                }
                ch.isWhitespace() -> {}
                ch in '0'..'9' -> {}
                ch == '-' || ch == '_' || ch == '.' -> {}
                else -> return false
            }
        }
        return sawLetter
    }

    /**
     * [T-android-correction-latin-phonetic] True when two Latin phonetic keys
     * plausibly sound alike to an ASR: same syllable count (vowel-group
     * parity), consonant skeletons that are Levenshtein-similar
     * (≥ [Config.LATIN_SKELETON_SIMILARITY]) AND sharing a consonant at the
     * same skeleton index.
     *
     * Why the skeleton rather than a consonant multiset: a bag of consonants
     * throws away order, and linux/minis (l,n,x vs m,n,s) then scores exactly
     * the same as linux/ninja (l,n,x vs n,n,j) — so no threshold can separate
     * the real confusion from the spurious one. The skeleton keeps position, and
     * the same-index anchor requires the shared consonant to land in the same
     * slot ("n" is 2nd in both lnx and mns). cat/dog shares no consonant at any
     * index and is rejected.
     *
     * Deliberately loose — it answers "could an ASR have confused these?", not
     * "should this correction apply". The locality guard here and the vocabulary
     * evidence downstream are the real gatekeepers. Known accepted false
     * positive: linux↔ninja, indistinguishable from linux↔minis on every
     * feature available to this signal.
     */
    fun isLatinPhoneticSimilar(keyA: String, keyB: String): Boolean {
        val vowels = setOf('a', 'e', 'i', 'o', 'u')

        // Syllable count = number of maximal vowel-character groups.
        fun syllableCount(s: String): Int {
            var count = 0
            var inVowel = false
            for (c in s) {
                if (c in vowels) {
                    if (!inVowel) { count++; inVowel = true }
                } else {
                    inVowel = false
                }
            }
            return count
        }

        val scA = syllableCount(keyA)
        val scB = syllableCount(keyB)
        if (scA == 0 || scB == 0 || scA != scB) return false

        val skelA = keyA.filter { it !in vowels }
        val skelB = keyB.filter { it !in vowels }
        if (skelA.isEmpty() || skelB.isEmpty()) return false

        // Same-index consonant anchor.
        val anchored = (0 until minOf(skelA.length, skelB.length)).any { skelA[it] == skelB[it] }
        if (!anchored) return false

        return PinyinNormalizer.similarity(skelA, skelB) >= Config.LATIN_SKELETON_SIMILARITY
    }
}

// ─── Merged from CorrectionContextBuilder.kt ──────────────────────────────────────────────────

/**
 * [T-android-voice-correction] Scores how "rare" a term is, i.e. how likely a
 * speech recognizer is to get it wrong. Port of iOS `RareContentScorer`.
 *
 * The common-hanzi set is inlined rather than loaded from a resource — it is
 * needed on the correction critical path and this avoids any I/O. The last two
 * chunks are deliberately app-domain-tuned (网络服务器数据配置…、闪挂载屏幕键盘…):
 * treating our own technical vocabulary as COMMON stops it from flooding the
 * rare-content digest, where it would crowd out the user's actual proper nouns.
 */
object RareContentScorer {

    /** A term at or above this enters the rare-content digest. */
    const val DIGEST_THRESHOLD = 40

    /** A sentence containing a term at or above this "carries rare content". */
    const val STRONG_THRESHOLD = 60

    /** A plain Latin word (`Issue`, `commit`) on its own: the zh word list cannot judge it. */
    const val PLAIN_LATIN_SCORE = 20

    /**
     * [T-android-voice-mixed-term-restore] A plain Latin word that RECURS in a
     * Chinese conversation is a term the user is talking about, and exactly what
     * a Chinese recognizer transliterates into look-alike hanzi ("Issue" ->
     * "遗穴"). On iOS, "Issue" appeared 10+ times in the scanned turns and never
     * reached the digest, while ordinary words built from common hanzi (状态,
     * 彻底) did at 40. Promoted to this score: above those, below the 90 of
     * identifier-shaped tokens. Port of iOS 8622ea275.
     */
    const val RECURRING_LATIN_SCORE = 50

    /** Occurrences across the scanned messages before a plain Latin word is promoted. */
    const val RECURRING_LATIN_MIN_COUNT = 3

    private val COMMON_HANZI: Set<Char> = buildSet {
        val chunks = listOf(
            "的一是了我不人在他有这个上们来到时大地为子中你说生国年着就那和要她出也得里后自以会家可下而过天去能对小多然于心学么之都好看起发当没成只如事把还用第样道想作种开美总从无情己面最女但现前些所同日手又行意动方期它头经长儿回位分爱老因很给名法间知世什两次使身者被高已亲其进此话常与活正感",
            "见明问力理点文几定本公特做外孩相西果走将月十实向声车全信重三机工物气每并别真打太新比才便夫再书部水像眼等体却加电主界门利海受听表德少克代员许先口由死安写性马光白或住难望教命花结乐色更拉东神记处让母父应直字场平报友关放至张认接告入笑内英军候民岁往何度山觉路带万男边风解叫任金快原吃妈变通师立象数四失满战远格士音轻目条呢",
            "病始达深完今提求清王化空业思切怎非找片罗钱语元喜曾离飞科言干流欢约各即指合反题必该论交终林请医晚制球决传画保读运及则房早院量苦火布品近坐产答星精视五连司巴奇管类未朋且婚台夜青北队久乎越观落尽形影红爸百令周吧识步希亚术留市半热送兴造谈容极随演收首根讲整式取照办强石古华拿计您装似足双妻尼转诉米称丽客南领节衣站黑刻统断",
            "福城故历惊脸选包紧争另建维绝树系伤示愿持千史谁准联妇纪基买志静阿诗独复痛消社算义竟确酒需单治卡幸兰念举仅钟怕共毛句息功官待究跟穿室易游程号居考突皮哪费倒价图具刚脑永歌响商礼细专黄块脚线怪按顾泪央曲牌秀要迷夏漂坏烟余雪托兄弟斗智排供香吸尝奶推雨类顶层楼灯洗牛左右初旁鱼低店油器假隔简卖爷帮航草叶伸投纸叹听份妹沙软吹",
            "呀园杀汽冷弄绍导族桌纯攻误修复测试代码文件版本网络服务器数据配置环境系统功能问题项目模型语音识别浏览设备设置权限支持切换启录音频视频消播放暂停继续退出登录账户密码搜索下载安装更新升级删除添加编辑保存取消确认提交发送接收显示隐藏打开关闭",
            "闪挂载屏幕键盘触摸滑按钮菜单窗口标签页链接地址邮箱脑软硬件程序停止恢复崩溃错警告提示格式类型参数函数变量组列表典循环判断条件返调接口请求响应超缓存队列线程进内盘",
        )
        chunks.forEach { chunk -> chunk.forEach { add(it) } }
    }

    fun isCjk(c: Char): Boolean = c.code in 0x4E00..0x9FFF

    fun isRareCjkChar(c: Char): Boolean = isCjk(c) && c !in COMMON_HANZI

    /**
     * Rarity score for [term].
     *
     * [rank] is a NEGATIVE signal only: a term present in the background list is
     * ordinary and scores 0. Absence proves nothing (the list is short by
     * design), so being out-of-vocabulary never *by itself* makes a term rare.
     */
    fun score(term: String, rank: (String) -> Int?): Int {
        val t = term.trim()
        if (t.isEmpty() || t.length > 24) return 0
        if (!t.any { it.isLetter() || isCjk(it) }) return 0
        if (rank(t) != null) return 0

        val cjkChars = t.filter { isCjk(it) }
        val hasCjk = cjkChars.isNotEmpty()
        val letters = t.filter { it.isAsciiLetter() }
        val hasLatin = letters.isNotEmpty()
        val hasDigit = t.any { it.isAsciiDigit() }

        if (hasCjk && hasLatin) return 90
        if (hasLatin) {
            if (hasDigit) return 90
            val interiorUpper = letters.drop(1).any { it.isUpperCase() }
            val allCaps = letters.length >= 2 && letters.all { it.isUpperCase() }
            val hasJoiner = t.any { it in "-_./" }
            if (interiorUpper || allCaps || hasJoiner) return 90
            // A plain word scores below DIGEST_THRESHOLD on purpose: only
            // technically-shaped Latin belongs in the digest, unless it recurs
            // (RECURRING_LATIN_SCORE, applied by the builder).
            return PLAIN_LATIN_SCORE
        }
        if (!hasCjk) return 0

        val rareCount = cjkChars.count { it !in COMMON_HANZI }
        if (cjkChars.length == 1) return if (rareCount == 1) 75 else 0
        val ratio = rareCount.toDouble() / cjkChars.length.toDouble()
        return 40 + Math.round(ratio * 50.0).toInt()
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}

/** Conversation evidence handed to the correction prompt. */
data class ConversationContext(
    val rareTermsDigest: String? = null,
    val recentExcerpts: List<String> = emptyList(),
    /** [T-android-voice-viewport-context] What is on screen; null when unavailable. */
    val screen: ScreenContext? = null,
) {
    val isEmpty: Boolean
        get() = rareTermsDigest.isNullOrEmpty() && recentExcerpts.isEmpty() && screen == null

    companion object {
        val EMPTY = ConversationContext()
    }
}

/**
 * A message the builder may draw context from. `text` is expected to already
 * have attachment markup stripped by the caller.
 */
data class CorrectionSourceMessage(val role: Role, val text: String) {
    /** Raw values are rendered directly into the prompt. */
    enum class Role(val label: String) { USER("用户"), ASSISTANT("AI") }
}

/**
 * [T-android-voice-correction] Builds the budgeted conversation context.
 * Port of iOS `Agent/Speech/CorrectionContextBuilder.swift`.
 */
object CorrectionContextBuilder {

    private const val TAG = "CorrectionContext"

    private data class ScoredSentence(val text: String, val strongScore: Int)
    private data class ScannedMessage(
        val role: CorrectionSourceMessage.Role,
        val newestIndex: Int,
        val sentences: List<ScoredSentence>,
    )

    fun build(
        messages: List<CorrectionSourceMessage>,
        segment: (String) -> List<String>,
        rank: (String) -> Int?,
    ): ConversationContext {
        if (messages.isEmpty()) return ConversationContext.EMPTY
        val started = System.currentTimeMillis()

        // Newest first; index 0 is the newest message.
        val scanned = messages.takeLast(CorrectionContextBudget.MAX_MESSAGES_SCANNED).reversed()

        // term.lowercase() -> (score, count, display) so WGHome/wghome merge
        // while the first-seen casing is what gets shown.
        val rareTerms = HashMap<String, Triple<Int, Int, String>>()
        val scannedMessages = mutableListOf<ScannedMessage>()
        // [T-android-voice-mixed-term-restore] Plain Latin words, counted for the
        // recurring-term promotion below. Promotion only makes sense where Latin
        // words stand out: in an English chat "file" and "code" recur too and are
        // not terms, so it is gated on CJK terms outnumbering plain Latin ones.
        val plainLatin = HashMap<String, kotlin.Pair<Int, String>>()
        var cjkTermCount = 0
        var plainLatinTotal = 0

        for ((idx, m) in scanned.withIndex()) {
            val stripped = m.text.trim()
            if (stripped.isEmpty()) continue
            val capped = stripped.take(CorrectionContextBudget.PER_MESSAGE_SCAN_CAP)
            val sentences = SentenceSplitter.split(capped).map { sentence ->
                var strongest = 0
                for (term in runCatching { segment(sentence) }.getOrElse { emptyList() }) {
                    if (term.any { RareContentScorer.isCjk(it) }) cjkTermCount++
                    val s = RareContentScorer.score(term, rank)
                    if (s == RareContentScorer.PLAIN_LATIN_SCORE) {
                        plainLatinTotal++
                        if (!StopWords.contains(term)) {
                            val key = term.lowercase()
                            val prev = plainLatin[key]
                            plainLatin[key] = (prev?.first ?: 0) + 1 to (prev?.second ?: term)
                        }
                        continue
                    }
                    if (s >= RareContentScorer.DIGEST_THRESHOLD) {
                        val key = term.lowercase()
                        val prev = rareTerms[key]
                        rareTerms[key] = if (prev == null) {
                            Triple(s, 1, term)
                        } else {
                            Triple(maxOf(prev.first, s), prev.second + 1, prev.third)
                        }
                    }
                    if (s >= RareContentScorer.STRONG_THRESHOLD) strongest = maxOf(strongest, s)
                }
                ScoredSentence(sentence, strongest)
            }
            scannedMessages.add(ScannedMessage(m.role, idx, sentences))
        }

        var promotedLatin = 0
        if (cjkTermCount > plainLatinTotal) {
            for ((key, entry) in plainLatin) {
                if (entry.first >= RareContentScorer.RECURRING_LATIN_MIN_COUNT && rareTerms[key] == null) {
                    rareTerms[key] = Triple(RareContentScorer.RECURRING_LATIN_SCORE, entry.first, entry.second)
                    promotedLatin++
                }
            }
        }

        val digest = buildDigest(rareTerms)
        val excerpts = buildExcerpts(scannedMessages)

        Log.i(
            TAG,
            "[Context] messages=${scannedMessages.size}/${messages.size} " +
                "excerptChars=${excerpts.sumOf { it.length }}/${CorrectionContextBudget.MESSAGE_EXCERPTS} " +
                "digestChars=${digest?.length ?: 0}/${CorrectionContextBudget.RARE_DIGEST} " +
                "recurringLatin=$promotedLatin " +
                "durationMs=${System.currentTimeMillis() - started}",
        )
        return ConversationContext(rareTermsDigest = digest, recentExcerpts = excerpts)
    }

    /** Rarest first, then most frequent, then longest. */
    private fun buildDigest(rareTerms: Map<String, Triple<Int, Int, String>>): String? {
        val ranked = rareTerms.values.sortedWith(
            compareByDescending<Triple<Int, Int, String>> { it.first }
                .thenByDescending { it.second }
                .thenByDescending { it.third.length },
        )
        val terms = mutableListOf<String>()
        var chars = 0
        for (entry in ranked) {
            val cost = entry.third.length + if (terms.isEmpty()) 0 else 1 // +1 for "、"
            // `continue`, NOT `break`: a term too long to fit is skipped so
            // shorter later terms can still be packed in.
            if (chars + cost > CorrectionContextBudget.RARE_DIGEST) continue
            terms.add(entry.third)
            chars += cost
        }
        return if (terms.isEmpty()) null else terms.joinToString("、")
    }

    /**
     * Walk newest → oldest. The two newest turns always contribute plain
     * lead-in grounding; older messages earn a slot ONLY via sentences that
     * carry rare content. Output is re-sorted oldest → newest so the model
     * reads chronologically.
     */
    private fun buildExcerpts(scannedMessages: List<ScannedMessage>): List<String> {
        var budget = CorrectionContextBudget.MESSAGE_EXCERPTS
        val rendered = mutableListOf<Pair<Int, String>>()

        for (m in scannedMessages) {
            if (budget <= 80) break
            val isGrounding = m.newestIndex < 2
            val perMessageCap = minOf(
                if (isGrounding) CorrectionContextBudget.GROUNDING_EXCERPT
                else CorrectionContextBudget.PER_MESSAGE_EXCERPT,
                budget,
            )

            val picked = mutableListOf<String>()
            var used = 0
            // Returns false to stop taking from this message.
            fun take(sentence: String): Boolean {
                var s = sentence
                if (s.length > perMessageCap - used) {
                    // Truncation is allowed only for the FIRST sentence taken;
                    // otherwise we'd cut mid-thought with room already spent.
                    if (picked.isNotEmpty()) return false
                    s = s.take(maxOf(0, perMessageCap - used - 1)) + "…"
                }
                if (s.isEmpty()) return false
                picked.add(s)
                used += s.length
                return used < perMessageCap
            }

            if (isGrounding) {
                for (s in m.sentences) if (!take(s.text)) break
            } else {
                for (s in m.sentences) if (s.strongScore > 0 && !take(s.text)) break
            }
            if (picked.isEmpty()) continue

            val position = if (m.newestIndex == 0) "最新" else "前第${m.newestIndex + 1}条"
            val line = "【${m.role.label}·$position】${picked.joinToString("")}"
            // The per-message cap bounds the SENTENCES, but the rendered line
            // also carries the role label, so a line can still overshoot the
            // remaining budget. Drop it rather than exceed the total — the
            // budget is a promise to the prompt assembler, not a suggestion.
            if (line.length > budget) continue
            rendered.add(m.newestIndex to line)
            budget -= line.length
        }
        return rendered.sortedByDescending { it.first }.map { it.second }
    }
}

// ─── Merged from CorrectionFallback.kt ──────────────────────────────────────────────────

/**
 * A model a correction may use, the group it came from ("sub" / "primary"), and
 * that group's fallback strategy - which decides what a failure on it does.
 */
data class CorrectionModel(
    val entry: ModelEntry,
    val groupKind: String,
    val fallbackStrategy: FallbackStrategy = FallbackStrategy.default,
)

/**
 * [T-voice-correction-fallback] Try a correction on each model in turn until one
 * answers. Port of iOS `CorrectionFallback` (Providers/Voice/CorrectionStrategy.swift).
 *
 * Before this a correction called ONE model - the sub group's first available
 * member, else the primary group's - and on its first error returned the
 * original text. A user log (iOS, 2026-10-15) shows the cost: every manual
 * correction failed with Anthropic's "credit balance is too low" while the chat
 * on the same device moved to the next model of the group (`🔀ROUTE … fallbackable
 * error`) and title generation retried on its next candidate.
 *
 * Reuses the chat's pieces rather than a parallel mechanism (owner decision,
 * 2026-10-15): the candidates are the groups' available members in router
 * order ([com.openminis.app.ui.chat.TitleCandidates.expandGroup]), and what a
 * failure does is the agent loop's rule for the failed model's group - see
 * [decide].
 *
 * Time: each CALL has its own [VoiceCorrectionConfig.CORRECTION_BUDGET_MS]
 * (15 s), not the whole chain. A call still running then is abandoned and the
 * next model is tried, whatever the strategy - a hung model must not cost the
 * correction. The chain is bounded by [MAX_ATTEMPTS] models.
 */
object CorrectionFallback {
    private const val TAG = "CorrectionFallback"

    /** Models tried per correction, as iOS. */
    const val MAX_ATTEMPTS = 3

    /** The chat's same-model retry ladder (1 s, 2 s, 4 s). */
    val SAME_MODEL_RETRY_DELAYS_MS: List<Long> =
        com.openminis.app.ui.chat.ChatViewModel.AUTO_RETRY_DELAYS_SEC.map { it * 1000L }

    /** Sub members first, then primary members not already listed. */
    fun order(
        sub: List<ModelEntry>,
        primary: List<ModelEntry>,
        subStrategy: FallbackStrategy = FallbackStrategy.default,
        primaryStrategy: FallbackStrategy = FallbackStrategy.default,
    ): List<CorrectionModel> {
        val seen = HashSet<String>()
        val out = mutableListOf<CorrectionModel>()
        for (e in sub) if (seen.add(e.id)) out += CorrectionModel(e, "sub", subStrategy)
        for (e in primary) if (seen.add(e.id)) out += CorrectionModel(e, "primary", primaryStrategy)
        return out
    }

    /** What a failed call leads to. */
    enum class Action { RETRY_SAME, NEXT_MODEL, STOP }

    /**
     * The agent loop's rule (ChatViewModel's stream-failure branch), applied to
     * one correction call:
     * - `Always Fall Back`: any error moves to the next model at once.
     * - Otherwise a network error or a transient error (incl. a 5xx carried as
     *   one) is first retried on the same model along the chat's ladder; then,
     *   and for every other error, the next model is tried only for a rate
     *   limit, a 5xx or an [LLMError.isFallbackable] error (rejected key,
     *   provider rejection such as an empty balance, certificate) - anything
     *   else stops with the original text, as the chat stops and shows it.
     * A call that ran past its budget is not decided here: it always moves on.
     */
    fun decide(error: Throwable, strategy: FallbackStrategy, retriesSoFar: Int, maxRetries: Int): Action {
        if (strategy == FallbackStrategy.always) return Action.NEXT_MODEL
        val err = error as? LLMError
        val transient = err is LLMError.NetworkError || err is LLMError.TransientError
        if (transient && retriesSoFar < maxRetries) return Action.RETRY_SAME
        val worthSwitching = err is LLMError.RateLimited || err?.isHttpServerError == true || err?.isFallbackable == true
        return if (worthSwitching) Action.NEXT_MODEL else Action.STOP
    }

    /** How the chat's group fallback would treat [t] - for the log line. */
    fun routeClass(t: Throwable): String {
        val err = t as? LLMError ?: return "other"
        return when {
            err.isFallbackable || err.isHttpServerError -> "fallbackable"
            err.isRetryable -> "retryable"
            else -> "other"
        }
    }

    /** One failed attempt, for the final error and the log. */
    data class Attempt(val model: CorrectionModel, val error: Throwable)

    private sealed class Call<out R> {
        class Answered<R>(val value: R) : Call<R>()
        class Failed(val error: Throwable) : Call<Nothing>()
        object TimedOut : Call<Nothing>()
    }

    /**
     * One call bounded by [budgetMs]. The provider's HTTP call is BLOCKING
     * inside the coroutine, so a timeout around it alone does not cut it short
     * (measured: a 5 s budget returned after 9.5 s). The call runs detached and
     * only the await is timed; a call that overran is cancelled and its result
     * discarded.
     */
    private suspend fun <R> bounded(budgetMs: Long, block: suspend () -> R): Call<R> {
        val work = CoroutineScope(Dispatchers.IO).async { block() }
        try {
            val call = withTimeoutOrNull(budgetMs) {
                try {
                    Call.Answered(work.await())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Call.Failed(e)
                }
            }
            if (call == null) work.cancel()
            return call ?: Call.TimedOut
        } catch (e: CancellationException) {
            work.cancel()
            throw e
        }
    }

    /**
     * Run [attempt] on [models] (at most [maxAttempts]) and return the first
     * answer, the model that gave it and its 1-based position. When the chain
     * ends without one, throws [CorrectionError.AllModelsFailed] with each
     * failure; [CorrectionError.NoModelAvailable] when there was nothing to try.
     */
    suspend fun <R> run(
        models: List<CorrectionModel>,
        maxAttempts: Int = MAX_ATTEMPTS,
        callBudgetMs: Long = VoiceCorrectionConfig.CORRECTION_BUDGET_MS,
        retryDelaysMs: List<Long> = SAME_MODEL_RETRY_DELAYS_MS,
        attempt: suspend (CorrectionModel) -> R,
    ): Triple<R, CorrectionModel, Int> {
        val tries = models.take(maxAttempts)
        if (tries.isEmpty()) throw CorrectionError.NoModelAvailable
        val failures = mutableListOf<Attempt>()
        models@ for ((index, model) in tries.withIndex()) {
            val n = "${index + 1}/${tries.size}"
            val id = model.entry.model.id
            val more = index + 1 < tries.size
            var retries = 0
            while (true) {
                log(
                    "[VoiceCorrection][Correct] attempt $n model=$id group=${model.groupKind} " +
                        "strategy=${model.fallbackStrategy}" + (if (retries > 0) " retry=$retries" else ""),
                )
                when (val call = bounded(callBudgetMs) { attempt(model) }) {
                    is Call.Answered -> {
                        if (index > 0) {
                            log("[VoiceCorrection][Correct] succeeded on attempt $n model=$id after ${failures.size} failure(s)")
                        }
                        return Triple(call.value, model, index + 1)
                    }
                    is Call.TimedOut -> {
                        failures += Attempt(model, CorrectionError.ModelTimedOut(callBudgetMs))
                        log(
                            "[VoiceCorrection][Correct] attempt $n TIMEOUT model=$id after ${callBudgetMs}ms - " +
                                if (more) "switching to the next model" else "no model left",
                        )
                        continue@models
                    }
                    is Call.Failed -> {
                        val e = call.error
                        // A provider may surface our own cancellation as an error.
                        if (!currentCoroutineContext().isActive) throw CancellationException("correction cancelled")
                        val action = decide(e, model.fallbackStrategy, retries, retryDelaysMs.size)
                        val what = when {
                            action == Action.RETRY_SAME -> "retrying the same model in ${retryDelaysMs[retries]}ms"
                            action == Action.STOP -> "stopping (group strategy ${model.fallbackStrategy} does not fall back on this error)"
                            more -> "switching to the next model"
                            else -> "no model left"
                        }
                        log(
                            "[VoiceCorrection][Correct] attempt $n FAILED model=$id group=${model.groupKind} " +
                                "class=${routeClass(e)} error=${e.javaClass.simpleName}: ${e.message?.take(160)} - $what",
                        )
                        when (action) {
                            Action.RETRY_SAME -> {
                                delay(retryDelaysMs[retries])
                                retries++
                            }
                            Action.NEXT_MODEL -> {
                                failures += Attempt(model, e)
                                continue@models
                            }
                            Action.STOP -> {
                                failures += Attempt(model, e)
                                throw CorrectionError.AllModelsFailed(failures)
                            }
                        }
                    }
                }
            }
        }
        throw CorrectionError.AllModelsFailed(failures)
    }

    private fun log(line: String) {
        Log.i(TAG, line)
    }
}

/**
 * [T-voice-correction-fallback] The short reason the voice panel shows after a
 * failed correction, as a string resource. Reuses the localized labels and the
 * classifier the sub-agent cards use ([com.openminis.app.agent.jobs.HelperRunner.errorKind]):
 * "credit balance is too low" reads as quota exhausted, a 429 as rate limited,
 * a model that ran past its budget as timed out.
 * Null when the cause is not recognisable - the panel then shows only "failed",
 * because a wrong reason is worse than none.
 */
object CorrectionFailureReason {
    fun res(suggestion: CorrectionSuggestion): Int? {
        val reason = suggestion.rejectedReason ?: return null
        return when {
            reason.startsWith("llm_error:") ->
                com.openminis.app.agent.jobs.HelperRunner.errorKind(suggestion.failureDetail ?: reason)
                    ?.takeIf { it != com.openminis.app.agent.jobs.HelperRunner.ErrorKind.CANCELLED }
                    ?.res
            else -> null
        }
    }
}

// ─── Merged from CorrectionSignalSource.kt ──────────────────────────────────────────────────

/**
 * [T-android-voice-correction] One source of correction candidates.
 * Port of iOS `Providers/Voice/CorrectionSignalSource.swift`.
 *
 * Lookup is BATCHED by contract — a 30-token transcript must not become 30
 * round-trips — and never throws: a failing source degrades to no candidates
 * rather than failing the whole correction.
 */
interface CorrectionSignalSource {
    val sourceIdentifier: String

    /** Weight applied to every candidate's confidence during fusion. */
    val defaultWeight: Double

    fun lookupCandidates(phoneticKeys: List<String>, locale: String, limit: Int): List<CorrectionCandidate>
}

/**
 * @property evidence human-readable provenance, rendered VERBATIM into the
 *   prompt's confusion block (e.g. "张山→张三（出现3次）"). Keeping the wording
 *   with the source that produced it stops the prompt builder from having to
 *   know each source's shape.
 */
data class CorrectionCandidate(
    val phoneticKey: String,
    val term: String,
    val confidence: Double,
    val sourceIdentifier: String,
    val evidence: String,
)

/**
 * Learned ASR corrections. Weight 1.0 — a pair the user actually fixed is the
 * strongest evidence available.
 */
class ConfusionDictionarySource(
    private val db: VoiceCorrectionDb,
    private val minConfidence: Double = VoiceCorrectionConfig.MIN_CONFUSION_CONFIDENCE,
) : CorrectionSignalSource {

    override val sourceIdentifier = "confusion_dictionary"
    override val defaultWeight = 1.0

    override fun lookupCandidates(
        phoneticKeys: List<String>,
        locale: String,
        limit: Int,
    ): List<CorrectionCandidate> =
        db.lookupConfusion(phoneticKeys, locale, minConfidence, limit).map { row ->
            CorrectionCandidate(
                phoneticKey = row.phoneticKey,
                term = row.correctedTerm,
                confidence = row.confidence,
                sourceIdentifier = sourceIdentifier,
                evidence = "${row.variants.firstOrNull() ?: row.phoneticKey}→${row.correctedTerm}" +
                    "（出现${row.frequency}次）",
            )
        }
}

/**
 * Terms the user types often. Weight 0.6, and confidence saturates at
 * `min(1, frequency/10)`, so a term typed 50 times cannot outrank a confirmed
 * correction on volume alone — a confusion row at confidence ≥ 0.6 always wins.
 */
class TypedVocabularySource(
    private val db: VoiceCorrectionDb,
) : CorrectionSignalSource {

    override val sourceIdentifier = "typed_vocabulary"
    override val defaultWeight = 0.6

    override fun lookupCandidates(
        phoneticKeys: List<String>,
        locale: String,
        limit: Int,
    ): List<CorrectionCandidate> =
        db.lookupVocabulary(phoneticKeys, locale, limit).map { row ->
            CorrectionCandidate(
                phoneticKey = row.phoneticKey,
                term = row.term,
                confidence = minOf(1.0, row.frequency / 10.0),
                sourceIdentifier = sourceIdentifier,
                evidence = "${row.term}（常用词，出现${row.frequency}次）",
            )
        }
}

// ─── Merged from CorrectionStrategy.kt ──────────────────────────────────────────────────

/** Result of one correction attempt. */
data class CorrectionOutcome(
    val correctedText: String,
    val modelGroupUsed: String,
    val durationMs: Int,
)

sealed class CorrectionError(message: String) : Exception(message) {
    object NoModelAvailable : CorrectionError("no correction model configured")
    object EmptyResponse : CorrectionError("model returned nothing usable")

    /**
     * [T-voice-correction-fallback] Every candidate model failed. The message is
     * the last model's error, so the panel's reason names a real cause (e.g. the
     * provider's "credit balance is too low"); every attempt is kept for the log.
     */
    /** [T-voice-correction-fallback] One call ran past its own budget. */
    class ModelTimedOut(budgetMs: Long) : CorrectionError("timed out after ${budgetMs}ms")

    class AllModelsFailed(val attempts: List<CorrectionFallback.Attempt>) : CorrectionError(
        attempts.lastOrNull()?.error?.message ?: "every correction model failed",
    )
}

/**
 * [T-android-voice-correction] Turns a transcript plus evidence into corrected
 * text. Port of iOS `Providers/Voice/CorrectionStrategy.swift`.
 */
interface CorrectionStrategy {
    val strategyIdentifier: String

    suspend fun correct(
        transcript: String,
        candidates: List<CorrectionCandidate>,
        context: ConversationContext,
        locale: String,
    ): CorrectionOutcome
}

/**
 * [T-android-voice-correction] LLM correction. The prompt and the response
 * cleaning are reproduced VERBATIM from iOS, including the full-width
 * separators (`、` between terms, `→` between original and fix) — they are part
 * of the contract with the model, not incidental formatting.
 *
 * [T-voice-correction-fallback] The candidate list and the single model call are
 * separate seams: the run walks the candidates with [CorrectionFallback], and
 * tests drive it with fake models instead of a provider.
 */
class LlmCorrectionStrategy internal constructor(
    private val candidates: () -> List<CorrectionModel>,
    private val callModel: suspend (model: CorrectionModel, request: CorrectionRequest) -> String,
    private val callBudgetMs: Long = VoiceCorrectionConfig.CORRECTION_BUDGET_MS,
    private val retryDelaysMs: List<Long> = CorrectionFallback.SAME_MODEL_RETRY_DELAYS_MS,
) : CorrectionStrategy {

    /** What one model call sends; the same for every candidate. */
    internal data class CorrectionRequest(
        val prompt: String,
        val systemPrompt: String,
        val transcriptChars: Int,
    )

    constructor(context: Context, repository: ProviderRepository) : this(
        candidates = { resolveCandidates(repository) },
        callModel = { model, request -> sendViaProvider(context, repository, model, request) },
    )

    override val strategyIdentifier = "llm"

    override suspend fun correct(
        transcript: String,
        candidates: List<CorrectionCandidate>,
        context: ConversationContext,
        locale: String,
    ): CorrectionOutcome {
        val started = System.currentTimeMillis()
        val models = candidates()
        if (models.isEmpty()) {
            Log.e(TAG, "[VoiceCorrection][Correct] no correction model available (neither sub nor primary group resolves)")
            throw CorrectionError.NoModelAvailable
        }
        val profile = CorrectionLanguageProfile.resolve(locale, transcript)
        val request = CorrectionRequest(
            prompt = buildPrompt(transcript, candidates, context, profile),
            systemPrompt = systemPrompt(profile),
            transcriptChars = transcript.length,
        )
        val (cleaned, model, attempt) = CorrectionFallback.run(
            models,
            callBudgetMs = callBudgetMs,
            retryDelaysMs = retryDelaysMs,
        ) { m ->
            cleanResponse(callModel(m, request)).ifEmpty { throw CorrectionError.EmptyResponse }
        }
        return CorrectionOutcome(
            correctedText = cleaned,
            modelGroupUsed = if (attempt > 1) "${model.groupKind}+fallback$attempt" else model.groupKind,
            durationMs = (System.currentTimeMillis() - started).toInt(),
        )
    }

    companion object {
        private const val TAG = "CorrectionStrategy"

        /** Router-order seed for a correction, which belongs to no chat (as iOS). */
        private const val ROUTER_SESSION = "voice-correction"

        /**
         * [T-voice-correction-fallback] The sub group's available members in
         * router order, then the primary group's (iOS CorrectionModelResolver
         * .candidates). Available = provider enabled + credentialed, the filter
         * the chat's group fallback applies; text-capable only, as for titles -
         * the same "lightweight model" title generation uses.
         */
        internal fun resolveCandidates(repository: ProviderRepository): List<CorrectionModel> {
            fun ring(groupId: String?): List<ModelEntry> {
                val group = groupId?.let { repository.group(it) } ?: return emptyList()
                return com.openminis.app.ui.chat.TitleCandidates
                    .expandGroup(group, repository.availableMemberEntries(group), ROUTER_SESSION)
                    .filter { com.openminis.app.ui.chat.TitleCandidates.isTitleEligible(it) }
            }
            fun strategy(groupId: String?) =
                groupId?.let { repository.group(it) }?.fallbackStrategy ?: FallbackStrategy.default
            return CorrectionFallback.order(
                sub = ring(repository.defaultSubGroupId),
                primary = ring(repository.defaultPrimaryGroupId),
                subStrategy = strategy(repository.defaultSubGroupId),
                primaryStrategy = strategy(repository.defaultPrimaryGroupId),
            )
        }

        /** One correction request on one model. */
        private suspend fun sendViaProvider(
            context: Context,
            repository: ProviderRepository,
            model: CorrectionModel,
            request: CorrectionRequest,
        ): String {
            val entry = model.entry
            val instance = repository.instance(entry.providerInstanceId)
                ?: throw IllegalStateException("provider instance ${entry.providerInstanceId} not found")
            // [T-android-keyless-provider-selection] usableApiKey — a keyless
            // self-hosted correction model is usable. See QuickTestSheet.
            val apiKey = repository.requestCredential(instance)
                ?: throw IllegalStateException("no credential for ${instance.label}")
            // [T-android-provider-entry-rules] Voice input can run before the chat
            // is persisted, so a correction is a one-off conversation of its own.
            val provider = ProviderFactory.create(
                instance, apiKey, entry.model, context,
                sessionId = ProviderFactory.oneOffSessionId(), overrides = entry.overrides,
            )
            // [T-android-voice-correction-diag] Name the resolved model BEFORE the
            // request. A field report on 2026-08-15 showed only
            // "correction failed / LLMError$InvalidApiKey" with a stack — which
            // provider instance was actually used had to be reverse-engineered by
            // matching the stack's provider class against every configured
            // instance on the device (it turned out to be one with no API key
            // saved). A user's log will never support that, so the identity has to
            // be in the line itself.
            Log.i(
                TAG,
                "[correction] resolved instance=${instance.label} (${instance.id}) " +
                    "type=${instance.providerType} model=${entry.model.id} " +
                    "group=${model.groupKind} hasKey=${apiKey.isNotBlank()}",
            )
            val response = provider.sendMessage(
                messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = request.prompt)),
                systemPrompt = request.systemPrompt,
                maxTokens = correctionMaxTokens(request.transcriptChars, entry.model.maxOutputTokens),
                // Reasoning traces add latency for no benefit here, and on some
                // providers yield empty text with finish_reason=tool_calls.
                thinkingLevel = ThinkingLevel.OFF,
            )
            return response.text
        }

        /**
         * [T-android-voice-mixed-term-restore] The rules half of the prompt. Port of
         * iOS 8622ea275 (T-voice-mixed-term-restore); the English text is iOS's,
         * verbatim.
         *
         * It used to be the output contract alone, and the user prompt asked only
         * for "同音字/识别错误". A Chinese recognizer's commonest miss on mixed
         * speech is not a homophone: an English term spoken inside a Chinese
         * sentence comes back as look-alike hanzi. On iOS, "下一个 Issue" became
         * "下一个遗穴", and with "Issue" on screen ten times the model still
         * answered "议题" — it searched Chinese words only and picked the one
         * that MEANS issue.
         *
         * Three layers: the output contract; the setting (the transcript is a
         * message to the agent, usually a reply that picks or names something the
         * agent just offered); and how recognition fails, per language
         * ([CorrectionLanguageProfile]). The method is shared: sound it out,
         * restore the reference's spelling, never translate.
         */
        fun systemPrompt(profile: CorrectionLanguageProfile): String =
            "You fix speech-recognition errors in transcribed text. Output ONLY the corrected text, " +
                "with no explanation, no quotes, and no preamble. If the text needs no correction, output it unchanged.\n\n" +
                "The situation: the user is talking to an AI agent in a chat app by voice, and the " +
                "transcript is the message they are about to send it. Read the transcript as one whole " +
                "message to that agent and work out what the user most likely means by it, given the " +
                "conversation. Most of the time they are answering or acting on what the agent just said: " +
                "picking one of the options it offered, naming something it mentioned (a noun, a file, an " +
                "item in a list, an issue number, a step), or referring to content it described. Those " +
                "names and options are the likeliest words in the transcript, so a misheard span that sounds " +
                "like one of them almost certainly is that one.\n\n" +
                "How recognition goes wrong for this user:\n" +
                profile.failureModes + "\n\n" +
                "How to fix it:\n" +
                "- A span that reads oddly in its sentence is a suspect. Sound it out and compare it with " +
                "the terms in the reference material: what is on screen, the recent conversation, the rare " +
                "terms, the user's vocabulary and their past corrections. If a term there, in any language, " +
                "sounds like the span and fits the sentence, replace the span with that term, written " +
                "exactly as the reference material writes it (same spelling, letter case and script).\n" +
                "- Restore the term itself; never translate it into another language or swap it for a " +
                "word that merely means the same.\n" +
                "- Use only terms supported by the reference material or common knowledge. Leave natural, " +
                "plausible wording alone, and do not rephrase or polish."

        /**
         * Output budget for the correction call: 1.2× the input, floored at
         * [MIN_OUTPUT_TOKENS], capped by the model.
         *
         * ⚠️ The floor has to cover REASONING TOKENS, not just the answer.
         * iOS raised it from 256 to 512 after adaptive thinking ate the whole
         * budget; on Android, MiMo blew through 512 as well — measured against
         * mimo-v2.5-pro, a correction prompt spent all 512 output tokens on
         * reasoning (reasoning_tokens=511), returned `content: ""` with
         * `finish_reason: length`, and every correction failed as
         * EmptyResponse. The same prompt at 4096 answers correctly.
         *
         * Reasoning length scales with prompt complexity, not transcript
         * length, so the FLOOR is what matters here — 1.2× a short transcript
         * will never be enough on a thinking model.
         */
        const val MIN_OUTPUT_TOKENS = 4096

        fun correctionMaxTokens(transcriptChars: Int, modelCap: Int?): Int {
            val target = maxOf(MIN_OUTPUT_TOKENS, Math.ceil(transcriptChars * 1.2).toInt())
            if (modelCap == null || modelCap <= 0) return target
            return minOf(target, modelCap)
        }

        /**
         * Assemble the user prompt.
         *
         * Evidence blocks are deliberately PEERS — none is presented as
         * outranking another. The conversation block is explicitly labelled
         * "不是需要处理的内容" because without it models start correcting the quoted
         * previous turn instead of the transcript.
         */
        fun buildPrompt(
            transcript: String,
            candidates: List<CorrectionCandidate>,
            context: ConversationContext,
            profile: CorrectionLanguageProfile = CorrectionLanguageProfile.resolve("zh", transcript),
        ): String {
            val blocks = mutableListOf<String>()

            // Block 1 — typed vocabulary, greedily filled to its budget.
            val vocabTerms = candidates
                .filter { it.sourceIdentifier == "typed_vocabulary" }
                .map { it.term }
                .distinct()
            packed(vocabTerms, CorrectionContextBudget.VOCAB_BLOCK)?.let {
                blocks.add(
                    "以下是该用户的常用词汇（来自历史文字输入，仅供参考）：\n" +
                        it.joinToString("、"),
                )
            }

            // Block 2 — confusion history, using each candidate's own evidence line.
            val confusionLines = candidates
                .filter { it.sourceIdentifier == "confusion_dictionary" }
                .map { it.evidence }
                .distinct()
            packed(confusionLines, CorrectionContextBudget.CONFUSION_BLOCK)?.let {
                blocks.add(
                    "以下是该用户过去的语音识别纠错记录（原文→修正，按可信度排序）：\n" +
                        it.joinToString("\n"),
                )
            }

            // Block 3 — rare-content digest (re-clamped defensively).
            context.rareTermsDigest?.takeIf { it.isNotEmpty() }?.let { digest ->
                blocks.add(
                    "以下是近期对话中出现的少见词/专有名词（语音识别容易听错，供参考）：\n" +
                        digest.take(CorrectionContextBudget.RARE_DIGEST),
                )
            }

            // Block 4 — conversation context.
            if (context.recentExcerpts.isNotEmpty()) {
                blocks.add(
                    "以下是当前对话的最近上下文（供理解语境使用，不是需要处理的内容）：\n" +
                        context.recentExcerpts.joinToString("\n"),
                )
            }

            // [T-android-voice-viewport-context] Blocks 5 + 6 — what the user
            // is looking at. Kept apart from block 4 (the newest turns): after
            // scrolling back, the two differ, and the screen is what the
            // dictation is about.
            val screen = context.screen
            if (screen != null && screen.viewportLines.isNotEmpty()) {
                blocks.add(
                    "以下是用户说话时屏幕上可见的对话内容（以屏幕中心为基准由近到远选取，按时间顺序排列；" +
                        "供理解语境使用，不是需要处理的内容）：\n" +
                        screen.viewportLines.joinToString("\n"),
                )
            }
            if (screen?.latestReply != null) {
                val where = if (screen.latestReplyOnScreen) "，此刻也显示在屏幕上" else "，此刻不在屏幕可见范围内"
                blocks.add(
                    "以下是本会话最新一条 AI 回复$where（供理解语境使用，不是需要处理的内容）：\n" +
                        screen.latestReply,
                )
            }

            val evidence = if (blocks.isEmpty()) "" else blocks.joinToString("\n\n") + "\n\n"
            // [T-android-voice-mixed-term-restore] Frame the transcript as a message
            // to the AI, and name this language's error kinds: "同音字" alone steered
            // the model to Chinese-only substitutes (iOS 8622ea275).
            val errorKinds = profile.errorKinds
            val lead = if (screen != null && screen.viewportLines.isNotEmpty()) {
                "请结合以上参考信息，尤其是用户说话时屏幕上可见的对话内容，" +
                    "推断用户这句要发给 AI 的话的整体意图（常常是在回应、选择或提及 AI 刚提到的名词、内容或选项），" +
                    "据此判断并修正下面这段语音转录文本中可能的$errorKinds。"
            } else {
                "请结合以上参考信息和对话上下文，推断用户这句要发给 AI 的话的整体意图，" +
                    "判断并修正下面这段语音转录文本中可能的$errorKinds。"
            }
            Log.i(TAG, "[CorrectionPrompt] profile=${profile.name.lowercase()} evidenceBlocks=${blocks.size} transcript=${transcript.length}")
            return evidence +
                lead +
                "如果转录内容本身已经正确、或者是无法用参考信息判断的正常表达，请不要修改。" +
                "只输出修正后的文本，不要输出任何解释。\n\n" +
                "原文：$transcript"
        }

        /**
         * Greedily take items until [budget] characters are spent, counting one
         * extra char per item for the separator. Returns null when nothing fits.
         */
        private fun packed(items: List<String>, budget: Int): List<String>? {
            val kept = mutableListOf<String>()
            var used = 0
            for (item in items) {
                val cost = item.length + if (kept.isEmpty()) 0 else 1
                if (used + cost > budget) break
                kept.add(item)
                used += cost
            }
            return kept.ifEmpty { null }
        }

        private val PREFIXES = listOf(
            "修正后的文本：", "修正后：", "修正：", "Corrected:", "Output:",
        )
        private val QUOTE_PAIRS = listOf(
            '"' to '"', '“' to '”', '「' to '」', '\'' to '\'',
        )

        /**
         * Strip decoration the model may add around the answer.
         *
         * This is not cosmetic: leftover fences or a "修正后：" prefix inflate the
         * character-change ratio, and a perfectly good correction then gets
         * rejected as `diff_too_large`.
         */
        fun cleanResponse(raw: String): String {
            var t = raw.trim()

            if (t.startsWith("```")) {
                val lines = t.split("\n")
                    .dropWhile { it.trimStart().startsWith("```") }
                    .takeWhile { !it.trimStart().startsWith("```") }
                t = lines.joinToString("\n").trim()
            }
            for (p in PREFIXES) {
                if (t.startsWith(p)) t = t.removePrefix(p).trim()
            }
            // Loop all pairs so nested quoting peels layer by layer.
            for ((open, close) in QUOTE_PAIRS) {
                if (t.length >= 2 && t.first() == open && t.last() == close) {
                    t = t.substring(1, t.length - 1)
                }
            }
            return t.trim()
        }
    }
}

/**
 * [T-android-voice-mixed-term-restore] How speech recognition fails differs by
 * language, so the prompt's failure-mode rules are kept per language instead of
 * one Chinese-centred list applied to everyone. Port of iOS
 * `CorrectionLanguageProfile` (8622ea275); the prompt text is iOS's, verbatim.
 *
 * Chosen from what the transcript is written in, with the recognizer locale as
 * the tie-breaker: a user on a zh recognizer who dictates an English sentence
 * gets the Western rules, and a kanji-only Japanese transcript still reads as
 * Japanese because the locale says so.
 */
enum class CorrectionLanguageProfile {
    CHINESE, JAPANESE, KOREAN, WESTERN, GENERIC;

    /** The "How recognition goes wrong" bullets of the system prompt. */
    val failureModes: String
        get() = when (this) {
            CHINESE ->
                "- They speak Chinese mixed with English words, product names and code identifiers. " +
                    "A Chinese recognizer often writes such an English word as Chinese characters that only " +
                    "sound like it (for example \"遗穴\" for \"Issue\", \"扣米特\" for \"commit\", \"杰森\" for \"JSON\").\n" +
                    "- Chinese names, product names and jargon come back as common words with the same or a " +
                    "similar sound (同音字 / 近音词), often words that are valid on their own.\n" +
                    "- If the context says \"Issue\", write \"Issue\", not a Chinese word with the same meaning " +
                    "such as \"议题\" or \"问题\"."
            JAPANESE ->
                "- They speak Japanese mixed with English words, product names and code identifiers. " +
                    "The recognizer often writes such a term in katakana (\"イシュー\" for \"Issue\", \"コミット\" for " +
                    "\"commit\") or splits it into unrelated words.\n" +
                    "- It picks the wrong kanji among homophones (同音異義語, e.g. 機能/昨日, 公開/後悔) and " +
                    "chooses between kana and kanji inconsistently.\n" +
                    "- Write a term the way the reference material writes it: Latin letters if it is written " +
                    "in Latin letters there, katakana if it is written in katakana there."
            KOREAN ->
                "- They speak Korean mixed with English words, product names and code identifiers. " +
                    "The recognizer often writes such a term in Hangul (\"이슈\" for \"Issue\", \"커밋\" for " +
                    "\"commit\") or splits it into unrelated words.\n" +
                    "- It confuses words that sound alike, and misplaces word spacing (띄어쓰기) around " +
                    "terms. Fix spacing only around a term you restore.\n" +
                    "- Write a term the way the reference material writes it: Latin letters if it is written " +
                    "in Latin letters there, Hangul if it is written in Hangul there."
            WESTERN ->
                "- Product names, code identifiers and file names are split into or merged with ordinary " +
                    "words (\"get hub\" for \"GitHub\", \"cube cuddle\" for \"kubectl\", \"read me dot MD\" for " +
                    "\"README.md\"), and lose their letter case and punctuation.\n" +
                    "- Names from other languages (often Chinese) are spelled out phonetically.\n" +
                    "- Homophones and near-homophones are swapped (their/there, accept/except, " +
                    "four/for). Leave spelling variants, grammar and style alone."
            GENERIC ->
                "- Names, product names, code identifiers and words from other languages are misheard as " +
                    "ordinary words, or spelled out phonetically in the user's script.\n" +
                    "- Words that sound alike are swapped."
        }

    /** What the user prompt's lead asks the model to fix (the user prompt is Chinese for every language). */
    val errorKinds: String
        get() = when (this) {
            CHINESE -> "同音字、近音词，以及被识别成普通词或音译汉字的专有名词（中文或英文，英文请按参考信息中的原拼写还原，不要翻译成中文）"
            JAPANESE -> "同音异义的汉字，以及被写成片假名、被拆开或被识别成普通词的术语和专有名词（按参考信息中的写法还原，不要翻译）"
            KOREAN -> "近音词，以及被写成韩文字母、被拆开或被识别成普通词的术语和专有名词及其周围的空格（按参考信息中的写法还原，不要翻译）"
            WESTERN -> "同音/近音词，以及被拆开、合并或按读音拼写的专有名词、产品名和代码标识符（按参考信息中的原写法还原，包括大小写和符号）"
            GENERIC -> "同音/近音词，以及被识别成普通词或按读音拼写的专有名词（按参考信息中的写法还原，不要翻译）"
        }

    companion object {
        /** Hangul > kana > han > Latin/Cyrillic/Greek, then the locale. Same order as iOS. */
        fun resolve(locale: String, transcript: String): CorrectionLanguageProfile {
            val key = PhoneticNormalizerRegistry.normalizedLocaleKey(locale)
            var han = false; var kana = false; var hangul = false; var western = false
            var i = 0
            while (i < transcript.length) {
                val cp = transcript.codePointAt(i)
                when (cp) {
                    in 0x3040..0x30FF, in 0x31F0..0x31FF, in 0xFF66..0xFF9D -> kana = true
                    in 0xAC00..0xD7AF, in 0x1100..0x11FF, in 0x3130..0x318F -> hangul = true
                    in 0x4E00..0x9FFF, in 0x3400..0x4DBF -> han = true
                    in 0x41..0x5A, in 0x61..0x7A, in 0xC0..0x24F, in 0x370..0x3FF, in 0x400..0x4FF -> western = true
                }
                i += Character.charCount(cp)
            }
            if (hangul) return KOREAN
            if (kana) return JAPANESE
            if (han) return if (key == "ja") JAPANESE else CHINESE
            if (western) return WESTERN
            return when (key) {
                "zh", "yue", "wuu" -> CHINESE
                "ja" -> JAPANESE
                "ko" -> KOREAN
                else -> GENERIC
            }
        }
    }
}

// ─── Merged from ScreenContextBuilder.kt ──────────────────────────────────────────────────

/**
 * [T-android-voice-viewport-context] What the user is LOOKING AT when they
 * dictate, as correction evidence.
 *
 * The history-mining context ([CorrectionContextBuilder]) always reads the
 * newest turns. A user who has scrolled back through a long session and
 * dictates a question about what is on screen gets a correction grounded in
 * the wrong part of the conversation. This block adds the on-screen text,
 * alongside (not instead of) the history block:
 *
 *  - Window: the viewport's centre line, extended half a screen past each
 *    viewport edge, so two screen-heights in total. Visible rows have exact
 *    geometry from the list's layout info; rows beyond them are placed by an
 *    estimated height (their text length times the px-per-char measured on
 *    the visible rows).
 *  - Content: user text, assistant reply text, and tool-call TITLES only (the
 *    line the tool pill shows, never arguments or output).
 *  - Budget: [CorrectionContextBudget.SCREEN_VIEWPORT] characters, filled
 *    nearest-to-centre first, so what the user is looking at straight on is
 *    the last thing to be cut. Output is re-sorted chronologically.
 *  - Latest reply: the newest assistant reply, on screen or not, in its own
 *    block capped at [CorrectionContextBudget.SCREEN_LATEST_REPLY]. When that
 *    reply is inside the window its rows are left out of the viewport block,
 *    so it appears exactly once (flagged as on screen).
 *
 * Pure: no Compose or Android UI types, so the geometry and budgeting are
 * unit-tested on the JVM.
 */
object ScreenContextBuilder {

    private const val TAG = "CorrectionContext"

    enum class Kind(val label: String) { USER("用户"), ASSISTANT("AI"), TOOL("工具") }

    /**
     * One row of the chat list, in chronological order (oldest first). [kind]
     * is null for rows that carry no text for correction (headers, thinking,
     * usage…); they still count for geometry.
     */
    data class Segment(
        val key: String,
        val messageId: String,
        val kind: Kind?,
        val text: String,
    )

    /** A row the list has laid out: its key, main-axis offset and size in px. */
    data class VisibleItem(val key: String, val offset: Int, val size: Int)

    /** Everything captured from the screen at the moment correction starts. */
    data class Snapshot(
        val segments: List<Segment>,
        val visible: List<VisibleItem>,
        val viewportStart: Int,
        val viewportEnd: Int,
        /**
         * True when older rows sit at LARGER offsets (a reverseLayout list,
         * which is how the chat renders). Needed to place off-screen rows on
         * the correct side.
         */
        val olderAtLargerOffsets: Boolean,
    )

    /** The newest assistant reply, from the full message list. */
    data class LatestReply(val messageId: String, val text: String)

    /** One selected row, with its distance from the viewport centre (px). */
    data class Picked(val index: Int, val kind: Kind, val text: String, val distance: Int)

    /**
     * Rows inside the two-screen window, nearest to the centre first. Rows of
     * [excludeMessageId] with [Kind.ASSISTANT] are skipped (the latest reply,
     * carried by its own block). Returns the picks and whether any skipped row
     * fell inside the window.
     */
    fun selectWindow(snapshot: Snapshot, excludeMessageId: String?): Pair<List<Picked>, Boolean> {
        val segments = snapshot.segments
        val height = snapshot.viewportEnd - snapshot.viewportStart
        if (segments.isEmpty() || height <= 0) return emptyList<Picked>() to false
        val indexByKey = HashMap<String, Int>(segments.size * 2)
        segments.forEachIndexed { i, s -> indexByKey[s.key] = i }
        val visible = snapshot.visible
            .mapNotNull { v -> indexByKey[v.key]?.let { it to v } }
            .sortedBy { it.first }
        if (visible.isEmpty()) return emptyList<Picked>() to false

        val center = (snapshot.viewportStart + snapshot.viewportEnd) / 2.0
        // The window is the viewport plus half a screen past each edge, i.e.
        // one screen-height from the centre line in either direction.
        val reach = height * (0.5 + CorrectionContextBudget.SCREEN_EXTENSION_SCREENS)

        // Which way is "older" on screen. Read from the rows themselves when two
        // or more are laid out (the oldest one sits at the larger offset in a
        // reverseLayout list); the caller's flag covers a single visible row.
        val olderAtLarger = if (visible.size >= 2) {
            visible.first().second.offset > visible.last().second.offset
        } else {
            snapshot.olderAtLargerOffsets
        }

        // Signed position along the chronological axis: negative = older.
        fun signedCenterOf(v: VisibleItem): Double {
            val mid = v.offset + v.size / 2.0
            return if (olderAtLarger) center - mid else mid - center
        }

        val distance = DoubleArray(segments.size) { Double.NaN }
        for ((i, v) in visible) distance[i] = signedCenterOf(v)

        // px per char from the visible text rows, to place rows nobody laid out.
        val textVisible = visible.filter { segments[it.first].text.isNotBlank() }
        val chars = textVisible.sumOf { segments[it.first].text.length }
        val px = textVisible.sumOf { it.second.size }
        val pxPerChar = if (chars > 0 && px > 0) px.toDouble() / chars else height / 400.0
        val minRowPx = height / 20.0
        fun estimate(i: Int): Double {
            val t = segments[i].text
            return if (t.isBlank()) minRowPx else max(minRowPx, t.length * pxPerChar)
        }

        // Walk outward from the first / last laid-out row.
        val (loIdx, loItem) = visible.first()
        val (hiIdx, hiItem) = visible.last()
        var edge = signedCenterOf(loItem) - loItem.size / 2.0
        for (i in loIdx - 1 downTo 0) {
            if (!distance[i].isNaN()) continue
            val h = estimate(i)
            distance[i] = edge - h / 2.0
            edge -= h
            if (-edge > reach) break
        }
        edge = signedCenterOf(hiItem) + hiItem.size / 2.0
        for (i in hiIdx + 1 until segments.size) {
            if (!distance[i].isNaN()) continue
            val h = estimate(i)
            distance[i] = edge + h / 2.0
            edge += h
            if (edge > reach) break
        }

        var excludedInWindow = false
        val picks = mutableListOf<Picked>()
        for (i in segments.indices) {
            val d = distance[i]
            if (d.isNaN() || abs(d) > reach) continue
            val s = segments[i]
            val kind = s.kind ?: continue
            if (s.text.isBlank()) continue
            if (excludeMessageId != null && s.messageId == excludeMessageId && kind == Kind.ASSISTANT) {
                excludedInWindow = true
                continue
            }
            picks.add(Picked(i, kind, s.text.trim(), d.roundToInt()))
        }
        return picks.sortedBy { abs(it.distance) } to excludedInWindow
    }

    /**
     * Fill [budget] nearest-first. A row that does not fit whole is cut only
     * while at least [MIN_PARTIAL] characters remain, keeping the end nearer
     * the centre: an older row keeps its tail, a newer one its head. The
     * result is chronological.
     */
    fun pack(picks: List<Picked>, budget: Int): List<String> {
        val kept = mutableListOf<Pair<Int, String>>()
        var left = budget
        for (p in picks) {
            // The prompt joins lines with "\n"; the budget covers the block as
            // rendered, separators included.
            val sep = if (kept.isEmpty()) 0 else 1
            val label = "【${p.kind.label}】"
            val full = label + p.text
            val room = left - sep
            val line = when {
                full.length <= room -> full
                room - label.length - 1 >= MIN_PARTIAL -> {
                    val textRoom = room - label.length - 1
                    if (p.distance < 0) label + "…" + p.text.takeLast(textRoom)
                    else label + p.text.take(textRoom) + "…"
                }
                else -> continue
            }
            kept.add(p.index to line)
            left -= line.length + sep
            if (left < MIN_PARTIAL) break
        }
        return kept.sortedBy { it.first }.map { it.second }
    }

    /**
     * Cap the latest reply at [budget]: keep its opening (the topic) and most
     * of its ending (the conclusion or question the user is answering).
     */
    fun capLatestReply(text: String, budget: Int): String {
        val t = text.trim()
        if (t.length <= budget) return t
        val head = budget / 3
        val tail = budget - head - 1
        return t.take(head) + "…" + t.takeLast(tail)
    }

    /**
     * Build the screen block. Null when there is nothing on screen to use and
     * no latest reply.
     */
    fun build(snapshot: Snapshot?, latest: LatestReply?): ScreenContext? {
        val started = System.currentTimeMillis()
        val (picks, latestOnScreen) = if (snapshot != null) {
            selectWindow(snapshot, latest?.messageId)
        } else {
            emptyList<Picked>() to false
        }
        val lines = pack(picks, CorrectionContextBudget.SCREEN_VIEWPORT)
        val reply = latest?.text?.takeIf { it.isNotBlank() }
            ?.let { capLatestReply(it, CorrectionContextBudget.SCREEN_LATEST_REPLY) }
        if (lines.isEmpty() && reply == null) return null
        val result = ScreenContext(lines, reply, latestOnScreen && reply != null)
        Log.i(
            TAG,
            "[Screen] visibleRows=${snapshot?.visible?.size ?: 0} windowRows=${picks.size} " +
                "keptLines=${lines.size} viewportChars=${result.viewportChars}/${CorrectionContextBudget.SCREEN_VIEWPORT} " +
                "latestReplyChars=${reply?.length ?: 0}/${CorrectionContextBudget.SCREEN_LATEST_REPLY} " +
                "latestReplyOnScreen=${result.latestReplyOnScreen} durationMs=${System.currentTimeMillis() - started}",
        )
        return result
    }

    /** Smallest cut worth keeping; below this a row is skipped, not stubbed. */
    const val MIN_PARTIAL = 40
}

/** The on-screen evidence, rendered by the prompt as its own blocks. */
data class ScreenContext(
    /** Chronological, each prefixed with its role label. */
    val viewportLines: List<String>,
    val latestReply: String?,
    /** The latest reply was inside the viewing window (and so left out of [viewportLines]). */
    val latestReplyOnScreen: Boolean,
) {
    val viewportChars: Int get() = viewportLines.sumOf { it.length }
}

// ─── Merged from TypedVocabularyBuilder.kt ──────────────────────────────────────────────────

/**
 * [T-android-voice-correction] Mines the terms a user actually types into
 * personal vocabulary, so speech recognition of those same words can be
 * corrected later. Port of iOS `Agent/Speech/TypedVocabularyBuilder.swift`.
 *
 * Typed text is high-quality signal precisely because it is unambiguous — the
 * user spelled it out. That makes it the natural complement to the confusion
 * dictionary, which only learns from things that went wrong.
 */
class TypedVocabularyBuilder(
    private val context: Context,
    private val db: VoiceCorrectionDb?,
    private val dao: ChatDao,
    private val posTag: (String) -> List<Pair<String, String>>,
    private val segment: (String) -> List<String>,
    private val rank: (String) -> Int?,
) {

    private val buildLock = Mutex()

    /**
     * Build if enough time has passed. Cheap to call after every send — the
     * throttle makes the common case a no-op.
     */
    suspend fun buildIfNeeded(force: Boolean = false) {
        val database = db ?: return
        if (!force) {
            val last = database.metaValue(KEY_LAST_BUILD_TIME)?.toDoubleOrNull() ?: 0.0
            val nowSec = System.currentTimeMillis() / 1000.0
            if (nowSec - last < MIN_INTERVAL_SECONDS) return
        }
        build(force)
    }

    /**
     * Mine every user message newer than the stored watermark.
     *
     * @param force restart from the beginning of history instead of the cursor.
     */
    suspend fun build(force: Boolean = false) {
        // Consent gates even a forced build — "collect nothing" must mean it.
        if (!VoiceCorrectionConsent.isEnabled(context)) return
        val database = db ?: return
        // Non-blocking: a build already running means this one has nothing to add.
        if (!buildLock.tryLock()) return

        try {
            withContext(Dispatchers.IO) {
                val started = System.currentTimeMillis()
                val cursorMs = if (force) 0L
                else database.metaValue(KEY_CURSOR)?.toLongOrNull() ?: 0L

                val messages = runCatching {
                    dao.loadUserMessagesSince(cursorMs, MAX_MESSAGES_PER_BUILD)
                }.getOrElse {
                    Log.e(TAG, "failed to load messages", it)
                    return@withContext
                }
                if (messages.isEmpty()) return@withContext

                var newest = cursorMs
                var candidates = 0
                var accepted = 0
                var rejected = 0

                for (m in messages) {
                    newest = maxOf(newest, m.createdAt)
                    val text = stripAttachmentMarkup(extractText(m.partsJson))
                    if (text.isBlank()) continue
                    val day = VoiceCorrectionDb.dayKey(m.createdAt / 1000.0)

                    for ((term, occurrences, tag) in
                    VocabularyFilter.candidates(text, posTag, segment)) {
                        candidates++
                        when (val d = VocabularyFilter.evaluate(term, tag, rank)) {
                            is VocabularyFilter.Decision.Accepted -> {
                                val key = PinyinNormalizer.normalize(term)
                                if (key.isEmpty()) { rejected++; continue }
                                database.upsertVocabulary(
                                    term = term,
                                    phoneticKey = key,
                                    // Only zh is supported today; the column
                                    // exists so adding a language is a data
                                    // change, not a migration.
                                    locale = "zh",
                                    posTag = tag,
                                    backgroundRank = rank(term),
                                    occurrences = occurrences,
                                    day = day,
                                )
                                accepted++
                            }
                            is VocabularyFilter.Decision.Rejected -> {
                                rejected++
                                if (d.reason.isNotEmpty()) Unit
                            }
                        }
                    }
                }

                database.setMetaValue(KEY_CURSOR, newest.toString())
                database.setMetaValue(
                    KEY_LAST_BUILD_TIME,
                    (System.currentTimeMillis() / 1000.0).toString(),
                )
                Log.i(
                    TAG,
                    "[Vocab] messages=${messages.size} candidates=$candidates " +
                        "accepted=$accepted rejected=$rejected " +
                        "durationMs=${System.currentTimeMillis() - started}",
                )
            }
        } finally {
            buildLock.unlock()
        }
    }

    /**
     * Pull the plain text out of a stored parts JSON blob.
     *
     * Only text parts are read: tool calls and media references would otherwise
     * push filenames, tool ids and base64 into the user's "vocabulary".
     */
    internal fun extractText(partsJson: String): String = runCatching {
        val arr = JSONArray(partsJson)
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("type") != "text") continue
            val t = obj.optString("text")
            if (t.isNotEmpty()) { if (sb.isNotEmpty()) sb.append('\n'); sb.append(t) }
        }
        sb.toString()
    }.getOrElse { "" }

    companion object {
        private const val TAG = "TypedVocabulary"

        /** Watermark of the newest message already mined (epoch ms). */
        private const val KEY_CURSOR = "vocab_last_built_at"

        /** When the last build ran (epoch seconds), for the throttle. */
        private const val KEY_LAST_BUILD_TIME = "vocab_last_build_time"

        private const val MIN_INTERVAL_SECONDS = 3600.0

        /** Bounds a first run over a long history. */
        private const val MAX_MESSAGES_PER_BUILD = 500

        /**
         * Remove the attachment envelope the composer wraps around files, so
         * filenames don't become vocabulary.
         */
        internal fun stripAttachmentMarkup(text: String): String {
            val start = text.indexOf("<user-attached-files>")
            if (start < 0) return text.trim()
            val endTag = "</user-attached-files>"
            val end = text.indexOf(endTag, start)
            if (end < 0) return text.trim()
            return (text.substring(0, start) + text.substring(end + endTag.length)).trim()
        }
    }
}

// ─── Merged from VoiceCorrection.kt ──────────────────────────────────────────────────

/**
 * [T-android-voice-correction] Single entry point for the correction feature —
 * builds the object graph once and exposes the few calls the UI layer needs.
 *
 * Everything here degrades to a no-op when the database can't be opened, so a
 * caller never has to null-check or reason about availability. Capture calls
 * are fire-and-forget on an IO scope: the user's typing or recording must never
 * wait on segmentation or SQLite.
 */
object VoiceCorrection {

    private const val TAG = "VoiceCorrection"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialized = false

    private var db: VoiceCorrectionDb? = null
    private var recorder: VoiceCorrectionRecorder? = null
    private var engine: VoiceCorrectionEngine? = null
    private var vocabularyBuilder: TypedVocabularyBuilder? = null

    // [T-android-correction-context-wiring] Kept so buildConversationContext can
    // feed the same segmenter + rarity ranking the retrieval path uses, without
    // exposing them or rebuilding jieba. Null until ensureInitialized succeeds.
    private var segment: ((String) -> List<String>)? = null
    private var rank: ((String) -> Int?)? = null

    /** Build the graph. Idempotent; safe to call from any entry point. */
    @Synchronized
    fun ensureInitialized(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        initialized = true

        db = VoiceCorrectionDb.getInstance(app)
        val database = db
        if (database == null) {
            Log.w(TAG, "database unavailable — correction disabled for this process")
            return
        }

        val segmenter = TextSegmenter.getInstance(app)
        val segment: (String) -> List<String> = { segmenter.segment(it) }
        val posTag: (String) -> List<Pair<String, String>> = { segmenter.posTag(it) }
        val background = BackgroundWordFrequency.getInstance(app)
        val rank: (String) -> Int? = { background.rank(it) }
        this.segment = segment
        this.rank = rank

        recorder = VoiceCorrectionRecorder(app, database, segment)

        // [T-android-safemode-lateinit-crash-147] subsystemsReady() first: the
        // safe call only rules out a null Application, while the lateinit
        // getter itself throws when onCreate early-returned under safe-mode.
        // Null here already degrades gracefully (correction is skipped).
        val repository = (app as? MinisApp)
            ?.takeIf { it.subsystemsReady() }
            ?.providerRepository
        if (repository != null) {
            engine = VoiceCorrectionEngine(
                sources = listOf(
                    ConfusionDictionarySource(database),
                    TypedVocabularySource(database),
                ),
                strategy = LlmCorrectionStrategy(app, repository),
                db = database,
                segment = segment,
                consent = { VoiceCorrectionConsent.isEnabled(app) },
            )
        }

        // Straight from the Room singleton rather than adding an accessor to
        // MinisApp — AppDatabase.getInstance is already the shared handle.
        val dao = runCatching {
            com.openminis.app.data.db.AppDatabase.getInstance(app).chatDao()
        }.getOrNull()
        if (dao != null) {
            vocabularyBuilder = TypedVocabularyBuilder(app, database, dao, posTag, segment, rank)
        }
    }

    /**
     * Prime the segmenter dictionaries off the critical path. The first jieba
     * call loads ~5MB and takes seconds; without this the first correction (or
     * first capture) stalls visibly.
     */
    fun warmUp(context: Context) {
        ensureInitialized(context)
        scope.launch { engine?.warmUp() }
    }

    /** The correction engine, or null when unavailable. */
    fun engine(context: Context): VoiceCorrectionEngine? {
        ensureInitialized(context)
        return engine
    }

    /**
     * [T-android-correction-context-wiring] Build the budgeted conversation
     * context for a correction pass from the chat's in-memory UI messages.
     * Android port of iOS `voiceCorrectionContext(from:)` (AIChatView.swift).
     *
     * Only real conversation turns feed the context:
     *  - `system` rows (compact dividers + systemInfo notices) are dropped, so
     *    UI copy never leaks into the rare-terms digest — mirrors iOS filtering
     *    out compactDivider / systemInfo.
     *  - assistant prose lives in text `toolBlocks`, not always in `content`
     *    (which can hold a stale title fragment), so for assistant turns the
     *    joined text blocks are the source of truth and `content` is the
     *    fallback. tool_use / thinking / info blocks stay out — commands and
     *    traces are not conversational vocabulary.
     * Returns [ConversationContext.EMPTY] when correction is unavailable or
     * there is nothing to draw from.
     */
    fun buildConversationContext(
        context: Context,
        messages: List<com.openminis.app.ui.chat.ChatMessage>,
        screen: ScreenContextBuilder.Snapshot? = null,
    ): ConversationContext {
        ensureInitialized(context)
        val seg = segment ?: return ConversationContext.EMPTY
        val rnk = rank ?: return ConversationContext.EMPTY

        // [T-android-voice-viewport-context] The on-screen blocks, next to (not
        // instead of) the history-mined ones below.
        val screenContext = runCatching {
            ScreenContextBuilder.build(screen, latestReply(messages))
        }.onFailure { Log.w(TAG, "screen context failed", it) }.getOrNull()
        screenContext?.let { logScreenContext(it) }

        val source = toSourceMessages(messages)
        if (source.isEmpty()) {
            return if (screenContext == null) ConversationContext.EMPTY
            else ConversationContext(screen = screenContext)
        }
        return CorrectionContextBuilder.build(source, seg, rnk).copy(screen = screenContext)
    }

    /**
     * [T-android-voice-viewport-context] The newest assistant reply's text,
     * read the same way [toSourceMessages] reads assistant turns (text blocks
     * first, `content` as fallback, attachment markup stripped). Its message id
     * lets the screen block drop the same reply's on-screen rows.
     */
    internal fun latestReply(
        messages: List<com.openminis.app.ui.chat.ChatMessage>,
    ): ScreenContextBuilder.LatestReply? {
        for (msg in messages.asReversed()) {
            if (msg.role != "assistant") continue
            var text = msg.content
            if (msg.toolBlocks.isNotEmpty()) {
                val blockText = msg.toolBlocks.filter { it.isText }.joinToString("\n") { it.content }
                if (blockText.isNotBlank()) text = blockText
            }
            val stripped = TypedVocabularyBuilder.stripAttachmentMarkup(text).trim()
            if (stripped.isNotEmpty()) return ScreenContextBuilder.LatestReply(msg.id, stripped)
        }
        return null
    }

    /**
     * Debug builds print the exact text the screen blocks carry, so extraction
     * can be checked against what is on the phone: filter logcat on
     * `CorrectionContext` and look for `[Screen:viewport]` / `[Screen:latest]`.
     * Release builds keep only the counts line from [ScreenContextBuilder.build]:
     * this is conversation content. Chunked because logcat truncates long lines.
     */
    private fun logScreenContext(screen: ScreenContext) {
        if (!com.openminis.app.BuildConfig.DEBUG) return
        fun chunked(prefix: String, text: String) {
            text.chunked(800).forEachIndexed { i, part ->
                Log.d("CorrectionContext", "$prefix${if (i > 0) " (cont.)" else ""} $part")
            }
        }
        screen.viewportLines.forEachIndexed { i, line ->
            chunked("[Screen:viewport] #${i + 1}/${screen.viewportLines.size} len=${line.length}", line)
        }
        screen.latestReply?.let {
            chunked("[Screen:latest] onScreen=${screen.latestReplyOnScreen} len=${it.length}", it)
        }
    }

    /**
     * [T-android-correction-context-wiring] The pure UI-message → source-message
     * transform behind [buildConversationContext]. Kept device-independent (no
     * segmenter / SQLite) so the role filter and assistant-text-block extraction
     * can be unit-tested. See [buildConversationContext] for the rules.
     */
    internal fun toSourceMessages(
        messages: List<com.openminis.app.ui.chat.ChatMessage>,
    ): List<CorrectionSourceMessage> = messages.mapNotNull { msg ->
        val role = when (msg.role) {
            "user" -> CorrectionSourceMessage.Role.USER
            "assistant" -> CorrectionSourceMessage.Role.ASSISTANT
            else -> return@mapNotNull null // drop system dividers / notices
        }
        var text = msg.content
        if (role == CorrectionSourceMessage.Role.ASSISTANT && msg.toolBlocks.isNotEmpty()) {
            val blockText = msg.toolBlocks
                .filter { it.isText }
                .joinToString("\n") { it.content }
            if (blockText.isNotBlank()) text = blockText
        }
        val stripped = TypedVocabularyBuilder.stripAttachmentMarkup(text)
        if (stripped.isBlank()) null else CorrectionSourceMessage(role, stripped)
    }

    // -- capture (fire-and-forget) --

    /**
     * The user edited an ASR transcript. Capability #2.
     */
    fun captureTranscriptEdit(
        context: Context,
        before: String,
        after: String,
        locale: String = "zh",
    ) {
        if (before == after) return
        ensureInitialized(context)
        val r = recorder ?: return
        scope.launch {
            runCatching { r.recordTranscriptEdit(before, after, locale) }
                .onFailure { Log.w(TAG, "transcript capture failed", it) }
        }
    }

    /**
     * The user selected text in a plain input field and replaced it.
     * Capability #3 — silent background capture, no UI.
     */
    fun captureTextInputEdit(
        context: Context,
        before: String,
        after: String,
        locale: String = "zh",
    ) {
        if (before == after) return
        ensureInitialized(context)
        val r = recorder ?: return
        scope.launch {
            runCatching { r.recordTextInputEdit(before, after, locale) }
                .onFailure { Log.w(TAG, "text-input capture failed", it) }
        }
    }

    /** The user applied an AI correction — metrics only. */
    fun captureSuggestionAccepted(
        context: Context,
        original: String,
        corrected: String,
        summary: String,
    ) {
        ensureInitialized(context)
        val r = recorder ?: return
        scope.launch {
            runCatching { r.recordSuggestionAccepted(original, corrected, summary) }
                .onFailure { Log.w(TAG, "accept capture failed", it) }
        }
    }

    /** Mine typed vocabulary if the throttle allows. Cheap to call on send. */
    fun mineVocabularyIfNeeded(context: Context) {
        ensureInitialized(context)
        val b = vocabularyBuilder ?: return
        scope.launch {
            runCatching { b.buildIfNeeded() }
                .onFailure { Log.w(TAG, "vocabulary build failed", it) }
        }
    }

    /** Wipe all learning data. Backs the settings "Clear Collected Data" action. */
    fun clearAllData(context: Context) {
        ensureInitialized(context)
        scope.launch {
            db?.clearAll()
            engine?.clearCache()
        }
    }

    /** Row counts for the settings screen. */
    suspend fun counts(context: Context): Map<String, Int> {
        ensureInitialized(context)
        return db?.counts() ?: emptyMap()
    }
}

// ─── Merged from VoiceCorrectionConfig.kt ──────────────────────────────────────────────────

/**
 * [T-android-voice-correction] Tunables for the correction pipeline. Values
 * mirror iOS `Providers/Voice/CorrectionSignalSource.swift` exactly — changing
 * one here without changing it there makes the two platforms behave
 * differently on the same dictionary.
 */
object VoiceCorrectionConfig {

    /** Retrieval floor: below this a learned pair stops being offered. */
    const val MIN_CONFUSION_CONFIDENCE = 0.3

    /** Cap on candidates carried into the prompt. */
    const val MAX_CANDIDATES = 20

    /**
     * Observational budget for the retrieval stage. Exceeding it is LOGGED, not
     * enforced — matching iOS, where the only real timeout is the whole-
     * correction one below.
     */
    const val RETRIEVAL_BUDGET_MS = 80L

    /**
     * Hung-request ceiling for the LLM call, NOT a latency target.
     *
     * History, because this number has been wrong twice:
     *  - The design originally specified 800ms. On a real device with a real
     *    provider that timed out EVERY time (~830-860ms, request still in
     *    flight), so the trigger moved to "transcript has landed and the user
     *    is reading it" — nothing is blocked while this runs — and iOS raised
     *    the ceiling to 5s.
     *  - 5s was still too tight on Android. Measured against MiMo on a Pixel
     *    4a, a SHORT prompt round-trips in ~4.1s; a correction prompt carries
     *    evidence blocks plus conversation context and reliably exceeded 5s
     *    (observed 9.5s), so every correction returned llm_timeout.
     *
     * 15s is sized to catch a genuinely hung request while leaving room for a
     * slow provider on a slow device. Nothing waits on it — the user is
     * reading their transcript — so a generous ceiling costs nothing, whereas
     * a tight one silently disables the feature.
     *
     * [T-voice-correction-fallback] Per model CALL, not per correction (owner
     * decision 2026-10-15, same on iOS): a call still running after 15s is
     * abandoned and the next candidate model is tried.
     */
    const val CORRECTION_BUDGET_MS = 15_000L

    /**
     * Reject an LLM result that changed more than half the characters — that is
     * a rewrite, not a correction.
     */
    const val MAX_CHAR_CHANGE_RATIO = 0.5

    /** A term must appear this often before retrieval will offer it. */
    const val VOCAB_MIN_FREQUENCY = 3

    /**
     * …and on this many distinct days. A term typed 50 times in one afternoon
     * is not yet established vocabulary.
     */
    const val VOCAB_MIN_DISTINCT_DAYS = 2

    /** Admission score a mined term must reach. */
    const val VOCAB_MIN_SCORE = 2

    /** Background-list rank above which a term still counts as "rare". */
    const val VOCAB_BACKGROUND_RANK_THRESHOLD = 3000

    /** Candidate cache time-to-live. */
    const val CACHE_TTL_MS = 300_000L

    /** Candidate cache entry ceiling. */
    const val CACHE_LIMIT = 200

    // -- Recorder admission --
    //
    // [T-android-correction-admission-multisignal] The live admission rule now
    // lives in CorrectionAdmission (multi-signal: homophone / acronym /
    // digit-norm, guarded by edit locality and a reorder check) — see
    // CorrectionAdmission.Config for its tunables. The two constants below are
    // the retired single-axis rule; they are NOT consulted when deciding
    // admission any more and are kept only because PhoneticNormalizerTest uses
    // the similarity floor to assert the normalizer's own behaviour.

    /**
     * Phonetic similarity floor. Still the value CorrectionAdmission uses for
     * its homophone signal (Config.PHONETIC_SIMILARITY), kept here for the
     * normalizer tests.
     */
    const val PHONETIC_SIMILARITY_THRESHOLD = 0.6

    /**
     * Length-ratio ceiling of the retired rule. Superseded by
     * CorrectionAdmission.Config.MAX_EDIT_LOCALITY, which measures the edit
     * against its SENTENCE rather than against the other side of the span.
     */
    @Deprecated("Superseded by CorrectionAdmission.Config.MAX_EDIT_LOCALITY")
    const val MAX_LENGTH_RATIO = 2.0

    /** Context snippet stored alongside a learned pair. */
    const val CONTEXT_SAMPLE_CHARS = 50
}

/**
 * Character budgets for the correction prompt, in characters (not tokens).
 * Mirrors iOS `CorrectionContextBudget`.
 */
object CorrectionContextBudget {
    /** Reported only; enforcement is implicit via the two sub-budgets. */
    const val MESSAGE_CONTEXT_TOTAL = 5000

    const val RARE_DIGEST = 200
    const val MESSAGE_EXCERPTS = 4800
    const val PER_MESSAGE_EXCERPT = 600
    const val GROUNDING_EXCERPT = 500
    const val CONFUSION_BLOCK = 800
    const val VOCAB_BLOCK = 400

    /** Cost guards, not prompt-size guards: bound the work a single build does. */
    const val MAX_MESSAGES_SCANNED = 12
    const val PER_MESSAGE_SCAN_CAP = 2000

    // [T-android-voice-viewport-context] The on-screen blocks (ScreenContextBuilder).
    // Separate from the history budgets above: the two sources coexist and
    // neither eats the other's room.

    /** Viewport block: text around the screen's centre, separators included. */
    const val SCREEN_VIEWPORT = 2000

    /** The newest assistant reply, on screen or not. */
    const val SCREEN_LATEST_REPLY = 2000

    /** How far past each viewport edge the window reaches, in screen-heights. */
    const val SCREEN_EXTENSION_SCREENS = 0.5
}

// ─── Merged from VoiceCorrectionConsent.kt ──────────────────────────────────────────────────

/**
 * [T-android-voice-correction] Opt-in consent for storing correction learning
 * data. Port of iOS `VoiceCorrectionCollectionConsent`, same preference keys so
 * a config moved between platforms keeps the user's choice.
 *
 * ## What this gates
 * WRITES ONLY. Reading existing data is never gated: with collection off,
 * correction keeps working exactly as before — it simply stops learning. The
 * guards live in the three writers (recorder, engine, vocabulary builder)
 * rather than at each call site, so a future caller is covered automatically.
 *
 * Both flags default to FALSE. Nothing is collected until the user says yes.
 */
object VoiceCorrectionConsent {

    private const val PREFS = "voice_correction_prefs"

    /** Key names match iOS UserDefaults so the semantics stay recognisable. */
    private const val KEY_ENABLED = "voiceCorrectionCollectionEnabled"
    private const val KEY_PROMPTED = "voiceCorrectionCollectionPrompted"

    /** True when the user has agreed to store learning data. Default false. */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * True once the one-time prompt has been shown AND answered — whatever the
     * answer was. It never fires again after that.
     */
    fun hasPrompted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PROMPTED, false)

    fun setPrompted(context: Context, prompted: Boolean) {
        prefs(context).edit().putBoolean(KEY_PROMPTED, prompted).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

// ─── Merged from VoiceCorrectionDb.kt ──────────────────────────────────────────────────

/**
 * [T-android-voice-correction] SQLite store for voice smart correction.
 * Port of iOS `Providers/Voice/VoiceCorrectionDB.swift`.
 *
 * ## Why raw SQLite and not Room (the project's usual choice)
 * Two behaviours here are load-bearing and Room cannot express them cleanly:
 *
 *  1. **Self-heal on corruption.** This is regenerable LEARNING data — losing
 *     it degrades correction to "no personalization", it does not break the
 *     app. So an unopenable file is deleted and recreated empty rather than
 *     propagated as an error. Room's `fallbackToDestructiveMigration` covers
 *     version mismatch, NOT a corrupt file.
 *  2. **Unconditional additive column repair.** [ensureIdempotentColumns] runs
 *     on EVERY open, BEFORE the version gate. ProviderConfigDB learned this the
 *     hard way on iOS: a column was added without bumping the schema version,
 *     every already-current DB took the early return, never gained the column,
 *     and the statements referencing it failed to prepare — silently wiping
 *     every provider from the UI. Room's migrations are version-to-version
 *     edges with no "repair regardless of version" slot.
 *
 * It also lives in its OWN file, not in minis.db: "clear my correction data" is
 * then one file to wipe, and a corrupt learning DB can never take the rest of
 * the app down with it.
 *
 * ## Threading
 * Every method is synchronous and must be called off the main thread (callers
 * use Dispatchers.IO). `SQLiteDatabase` is internally synchronized, and WAL
 * lets the send-time read path proceed without blocking the capture writer.
 */
class VoiceCorrectionDb private constructor(private val dbFile: File) {

    private var db: SQLiteDatabase? = null

    init {
        db = openOrRebuild(dbFile)
    }

    // ---- Confusion dictionary ----

    /**
     * Upsert one learned ASR correction. Dedup key is
     * (phonetic key, corrected term, locale): the same intent recorded via a
     * different misspelling folds into one row and bumps `frequency`, which is
     * the whole point of an aggregate table rather than an append-only log.
     *
     * Re-writing a row also clears `archived_at` — evidence of continued use
     * revives a row that cleanup had retired.
     */
    fun upsertConfusion(
        phoneticKey: String,
        originalVariant: String,
        correctedTerm: String,
        locale: String,
        contextSample: String?,
        asrProvider: String,
        source: String = SOURCE_VOICE,
    ) {
        val d = db ?: return
        val now = System.currentTimeMillis() / 1000.0
        runCatching {
            var existingId: String? = null
            var frequency = 0
            var negative = 0
            var variantsJson = "[]"
            d.rawQuery(
                """
                SELECT id, frequency, negative_feedback_count, original_variants
                FROM confusion_dictionary
                WHERE original_phonetic_key = ? AND corrected_term = ? AND locale = ?
                LIMIT 1
                """.trimIndent(),
                arrayOf(phoneticKey, correctedTerm, locale),
            ).use { c ->
                if (c.moveToFirst()) {
                    existingId = c.getString(0)
                    frequency = c.getInt(1)
                    negative = c.getInt(2)
                    variantsJson = c.getString(3) ?: "[]"
                }
            }

            val variants = decodeVariants(variantsJson).toMutableList()
            if (!variants.contains(originalVariant)) variants.add(originalVariant)
            val encoded = encodeVariants(variants)

            if (existingId != null) {
                val newFreq = frequency + 1
                val values = ContentValues().apply {
                    put("original_variants", encoded)
                    put("frequency", newFreq)
                    put("confidence", confidence(newFreq, negative))
                    put("last_seen", now)
                    if (contextSample != null) put("context_before_sample", contextSample)
                    else putNull("context_before_sample")
                    putNull("archived_at")
                }
                d.update("confusion_dictionary", values, "id = ?", arrayOf(existingId))
            } else {
                val values = ContentValues().apply {
                    put("id", UUID.randomUUID().toString())
                    put("original_phonetic_key", phoneticKey)
                    put("original_variants", encoded)
                    put("corrected_term", correctedTerm)
                    put("locale", locale)
                    put("frequency", 1)
                    put("negative_feedback_count", 0)
                    put("confidence", confidence(1, 0))
                    if (contextSample != null) put("context_before_sample", contextSample)
                    else putNull("context_before_sample")
                    put("asr_provider", asrProvider)
                    put("source", source)
                    put("last_seen", now)
                    putNull("archived_at")
                }
                d.insert("confusion_dictionary", null, values)
            }
            // [privacy] Lengths only — these pairs are verbatim user speech.
            // The row keeps the truth; the log does not need it.
            Log.i(
                TAG,
                "[Record] upsert originalLen=${originalVariant.length} " +
                    "correctedLen=${correctedTerm.length} locale=$locale source=$source",
            )
        }.onFailure { Log.e(TAG, "upsertConfusion failed", it) }
    }

    /**
     * Negative feedback: a correction we proposed was rejected. Never write a
     * POSITIVE row for an accepted AI suggestion — the suggestion was itself
     * derived from this table, so feeding it back would make the system learn
     * from an echo of its own output.
     */
    fun recordNegativeFeedback(phoneticKey: String, correctedTerm: String, locale: String) {
        val d = db ?: return
        runCatching {
            d.execSQL(
                """
                UPDATE confusion_dictionary
                SET negative_feedback_count = negative_feedback_count + 1,
                    confidence = CAST(frequency AS REAL) /
                                 (frequency + (negative_feedback_count + 1) * 2.0)
                WHERE original_phonetic_key = ? AND corrected_term = ? AND locale = ?
                """.trimIndent(),
                arrayOf(phoneticKey, correctedTerm, locale),
            )
        }.onFailure { Log.e(TAG, "recordNegativeFeedback failed", it) }
    }

    /**
     * Batch lookup for many phonetic keys in ONE statement: a 30-token
     * transcript must not become 30 round-trips. Archived and low-confidence
     * rows are excluded in SQL, not in Kotlin, so the index does the work.
     */
    fun lookupConfusion(
        phoneticKeys: List<String>,
        locale: String,
        minConfidence: Double,
        limit: Int,
    ): List<ConfusionRow> {
        val d = db ?: return emptyList()
        if (phoneticKeys.isEmpty()) return emptyList()
        val placeholders = phoneticKeys.joinToString(",") { "?" }
        val args = (phoneticKeys + listOf(locale, minConfidence.toString(), limit.toString()))
            .toTypedArray()
        return runCatching {
            val out = mutableListOf<ConfusionRow>()
            d.rawQuery(
                """
                SELECT id, original_phonetic_key, original_variants, corrected_term,
                       locale, frequency, negative_feedback_count, confidence, last_seen
                FROM confusion_dictionary
                WHERE original_phonetic_key IN ($placeholders)
                  AND locale = ?
                  AND confidence >= ?
                  AND archived_at IS NULL
                ORDER BY confidence DESC, frequency DESC
                LIMIT ?
                """.trimIndent(),
                args,
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        ConfusionRow(
                            id = c.getString(0),
                            phoneticKey = c.getString(1),
                            variants = decodeVariants(c.getString(2) ?: "[]"),
                            correctedTerm = c.getString(3),
                            locale = c.getString(4),
                            frequency = c.getInt(5),
                            negativeFeedbackCount = c.getInt(6),
                            confidence = c.getDouble(7),
                            lastSeen = c.getDouble(8),
                        ),
                    )
                }
            }
            out
        }.getOrElse {
            Log.e(TAG, "lookupConfusion failed", it)
            emptyList()
        }
    }

    // ---- Typed vocabulary ----

    /**
     * Upsert a mined vocabulary term. `distinct_days` only increments when the
     * day key differs from the last recorded one — a term typed 50 times in one
     * afternoon must not look as established as one used across a week.
     */
    fun upsertVocabulary(
        term: String,
        phoneticKey: String,
        locale: String,
        posTag: String?,
        backgroundRank: Int?,
        occurrences: Int,
        day: String,
    ) {
        val d = db ?: return
        val now = System.currentTimeMillis() / 1000.0
        runCatching {
            var id: String? = null
            var freq = 0
            var days = 0
            var lastDay: String? = null
            d.rawQuery(
                "SELECT id, frequency, distinct_days, last_day FROM typed_vocabulary " +
                    "WHERE term = ? AND locale = ? LIMIT 1",
                arrayOf(term, locale),
            ).use { c ->
                if (c.moveToFirst()) {
                    id = c.getString(0); freq = c.getInt(1)
                    days = c.getInt(2); lastDay = c.getString(3)
                }
            }
            if (id != null) {
                val values = ContentValues().apply {
                    put("frequency", freq + occurrences)
                    put("distinct_days", if (lastDay != day) days + 1 else days)
                    put("last_day", day)
                    put("last_seen", now)
                    if (posTag != null) put("pos_tag", posTag)
                    if (backgroundRank != null) put("background_rank", backgroundRank)
                    putNull("archived_at")
                }
                d.update("typed_vocabulary", values, "id = ?", arrayOf(id))
            } else {
                val values = ContentValues().apply {
                    put("id", UUID.randomUUID().toString())
                    put("term", term)
                    put("phonetic_key", phoneticKey)
                    put("locale", locale)
                    if (posTag != null) put("pos_tag", posTag) else putNull("pos_tag")
                    put("frequency", occurrences)
                    put("distinct_days", 1)
                    put("last_day", day)
                    if (backgroundRank != null) put("background_rank", backgroundRank)
                    else putNull("background_rank")
                    put("last_seen", now)
                    put("source", "typed")
                    putNull("archived_at")
                }
                d.insert("typed_vocabulary", null, values)
            }
        }.onFailure { Log.e(TAG, "upsertVocabulary failed", it) }
    }

    /**
     * Vocabulary lookup. The frequency / distinct-days floors are enforced HERE
     * at retrieval, not at intake — a term must be storable at frequency=1 to
     * ever accumulate to the threshold.
     */
    fun lookupVocabulary(
        phoneticKeys: List<String>,
        locale: String,
        limit: Int,
        minFrequency: Int = VoiceCorrectionConfig.VOCAB_MIN_FREQUENCY,
        minDistinctDays: Int = VoiceCorrectionConfig.VOCAB_MIN_DISTINCT_DAYS,
    ): List<VocabularyRow> {
        val d = db ?: return emptyList()
        if (phoneticKeys.isEmpty()) return emptyList()
        val placeholders = phoneticKeys.joinToString(",") { "?" }
        val args = (
            phoneticKeys + listOf(
                locale, minFrequency.toString(), minDistinctDays.toString(), limit.toString(),
            )
            ).toTypedArray()
        return runCatching {
            val out = mutableListOf<VocabularyRow>()
            d.rawQuery(
                """
                SELECT id, term, phonetic_key, locale, pos_tag, frequency, distinct_days
                FROM typed_vocabulary
                WHERE phonetic_key IN ($placeholders)
                  AND locale = ?
                  AND frequency >= ?
                  AND distinct_days >= ?
                  AND archived_at IS NULL
                ORDER BY frequency DESC
                LIMIT ?
                """.trimIndent(),
                args,
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        VocabularyRow(
                            id = c.getString(0), term = c.getString(1),
                            phoneticKey = c.getString(2), locale = c.getString(3),
                            posTag = c.getString(4), frequency = c.getInt(5),
                            distinctDays = c.getInt(6),
                        ),
                    )
                }
            }
            out
        }.getOrElse {
            Log.e(TAG, "lookupVocabulary failed", it)
            emptyList()
        }
    }

    // ---- Meta / events ----

    fun metaValue(key: String): String? {
        val d = db ?: return null
        return runCatching {
            d.rawQuery("SELECT value FROM correction_meta WHERE key = ? LIMIT 1", arrayOf(key))
                .use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    fun setMetaValue(key: String, value: String) {
        val d = db ?: return
        runCatching {
            d.execSQL(
                "INSERT INTO correction_meta(key, value) VALUES(?, ?) " +
                    "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(key, value),
            )
        }.onFailure { Log.e(TAG, "setMetaValue failed", it) }
    }

    /** Outcome is one of "suggested" / "accepted" / "reverted" — metrics only. */
    fun recordCorrectionEvent(
        original: String,
        corrected: String,
        outcome: String,
        sourceSummary: String?,
    ) {
        val d = db ?: return
        runCatching {
            val values = ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("created_at", System.currentTimeMillis() / 1000.0)
                put("original", original)
                put("corrected", corrected)
                put("outcome", outcome)
                if (sourceSummary != null) put("source_summary", sourceSummary)
                else putNull("source_summary")
            }
            d.insert("correction_events", null, values)
        }.onFailure { Log.e(TAG, "recordCorrectionEvent failed", it) }
    }

    /** Row counts per table, for the settings screen and debug metrics. */
    fun counts(): Map<String, Int> {
        val d = db ?: return emptyMap()
        return runCatching {
            listOf("confusion_dictionary", "typed_vocabulary", "correction_events")
                .associateWith { t ->
                    d.rawQuery("SELECT COUNT(*) FROM $t", null)
                        .use { if (it.moveToFirst()) it.getInt(0) else 0 }
                }
        }.getOrElse { emptyMap() }
    }

    /** Wipe every learning table. Backs the "Clear Collected Data" button. */
    fun clearAll() {
        val d = db ?: return
        runCatching {
            d.beginTransaction()
            try {
                d.execSQL("DELETE FROM confusion_dictionary")
                d.execSQL("DELETE FROM typed_vocabulary")
                d.execSQL("DELETE FROM correction_events")
                d.execSQL("DELETE FROM correction_meta")
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
            // Reclaim pages in bounded chunks rather than one long blocking
            // VACUUM. rawQuery, not execSQL — see openAndPrepare.
            d.rawQuery("PRAGMA incremental_vacuum(1000)", null).use { it.moveToFirst() }
            Log.i(TAG, "[Privacy] all correction learning data cleared")
        }.onFailure { Log.e(TAG, "clearAll failed", it) }
    }

    // ---- Open / schema ----

    private fun openOrRebuild(file: File): SQLiteDatabase? {
        openAndPrepare(file)?.let { return it }
        // Self-heal: throw the file away and come back as a cold-start empty DB.
        // A WAL database has sidecars; leaving a stale -wal/-shm beside a fresh
        // main file is its own corruption source, so drop those too.
        Log.e(TAG, "[DBHealth] open failed — rebuilding empty (size=${file.length()})")
        runCatching {
            file.delete()
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
        }
        return openAndPrepare(file).also {
            if (it == null) Log.e(TAG, "[DBHealth] rebuild failed — correction disabled")
        }
    }

    private fun openAndPrepare(file: File): SQLiteDatabase? = runCatching<SQLiteDatabase> {
        file.parentFile?.mkdirs()
        val d = SQLiteDatabase.openOrCreateDatabase(file, null)
        // ⚠️ PRAGMAs that RETURN A ROW must go through rawQuery, not execSQL.
        // Android's execSQL rejects any statement producing results with
        // "Queries can be performed using SQLiteDatabase query or rawQuery
        // methods only" — `journal_mode` returns the resulting mode and
        // `auto_vacuum` returns the setting, so both fail there. (iOS's
        // sqlite3_exec has no such restriction, which is why the ported code
        // needed adjusting rather than transcribing.)
        //
        // auto_vacuum MUST be set before any table is created — SQLite ignores
        // it afterwards. INCREMENTAL (not FULL) so cleanup can reclaim pages in
        // bounded chunks instead of one long blocking VACUUM.
        d.rawQuery("PRAGMA auto_vacuum = INCREMENTAL", null).use { it.moveToFirst() }
        d.rawQuery("PRAGMA journal_mode = WAL", null).use { it.moveToFirst() }
        // synchronous returns nothing, so execSQL is fine here.
        d.execSQL("PRAGMA synchronous = NORMAL")
        runMigrations(d)
        d
    }.onFailure { Log.e(TAG, "[DBHealth] openAndPrepare failed", it) }.getOrNull()

    /**
     * Order is load-bearing:
     *   1. [ensureIdempotentColumns] — EVERY open, BEFORE the version gate.
     *   2. versioned structural migrations — only when current < [SCHEMA_VERSION].
     *   3. stamp user_version.
     *
     * Step 1 must precede the gate; see the class comment for the incident that
     * rule exists to prevent.
     */
    private fun runMigrations(d: SQLiteDatabase) {
        val current = d.rawQuery("PRAGMA user_version", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

        val patched = ensureIdempotentColumns(d)

        // Downgrade safety: an OLD build's compiled-in version is lower than the
        // user_version a NEWER build stamped. It takes this early return and
        // reads only the columns it knows about. That works ONLY because every
        // statement above names its columns explicitly (never SELECT *), so
        // extra columns are invisible rather than shifting indices.
        if (current >= SCHEMA_VERSION) {
            if (patched > 0) Log.i(TAG, "[Migration] at v$current, columns patched=$patched")
            return
        }

        d.beginTransaction()
        try {
            if (current < 1) {
                d.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS confusion_dictionary (
                        id                      TEXT PRIMARY KEY,
                        original_phonetic_key   TEXT NOT NULL,
                        original_variants       TEXT NOT NULL,
                        corrected_term          TEXT NOT NULL,
                        locale                  TEXT NOT NULL,
                        frequency               INTEGER NOT NULL DEFAULT 1,
                        negative_feedback_count INTEGER NOT NULL DEFAULT 0,
                        confidence              REAL NOT NULL DEFAULT 0.5,
                        context_before_sample   TEXT,
                        asr_provider            TEXT NOT NULL DEFAULT '',
                        source                  TEXT NOT NULL DEFAULT '$SOURCE_VOICE',
                        last_seen               REAL NOT NULL,
                        archived_at             REAL DEFAULT NULL,
                        device_id               TEXT,
                        last_synced_at          REAL
                    )
                    """.trimIndent(),
                )
                d.execSQL("CREATE INDEX IF NOT EXISTS idx_confusion_phonetic ON confusion_dictionary(original_phonetic_key)")
                d.execSQL("CREATE INDEX IF NOT EXISTS idx_confusion_locale ON confusion_dictionary(locale)")
                d.execSQL("CREATE INDEX IF NOT EXISTS idx_confusion_confidence ON confusion_dictionary(confidence DESC)")

                d.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS typed_vocabulary (
                        id              TEXT PRIMARY KEY,
                        term            TEXT NOT NULL,
                        phonetic_key    TEXT NOT NULL,
                        locale          TEXT NOT NULL,
                        pos_tag         TEXT,
                        frequency       INTEGER NOT NULL DEFAULT 1,
                        distinct_days   INTEGER NOT NULL DEFAULT 1,
                        last_day        TEXT,
                        background_rank INTEGER,
                        last_seen       REAL NOT NULL,
                        source          TEXT NOT NULL DEFAULT 'typed',
                        archived_at     REAL DEFAULT NULL
                    )
                    """.trimIndent(),
                )
                d.execSQL("CREATE INDEX IF NOT EXISTS idx_vocab_phonetic ON typed_vocabulary(phonetic_key)")

                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS correction_meta (" +
                        "key TEXT PRIMARY KEY, value TEXT NOT NULL)",
                )
                d.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS correction_events (
                        id             TEXT PRIMARY KEY,
                        created_at     REAL NOT NULL,
                        original       TEXT NOT NULL,
                        corrected      TEXT NOT NULL,
                        outcome        TEXT NOT NULL,
                        source_summary TEXT
                    )
                    """.trimIndent(),
                )
                d.execSQL("CREATE INDEX IF NOT EXISTS idx_events_created ON correction_events(created_at DESC)")
            }
            d.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        Log.i(TAG, "[Migration] schema $current → $SCHEMA_VERSION")
    }

    /**
     * Additive-only column repairs, run on every open regardless of
     * user_version. Each entry also ships in the v1 CREATE TABLE above; this is
     * the belt-and-braces that repairs a DB written by a build predating the
     * column. Returns how many columns were actually added.
     */
    private fun ensureIdempotentColumns(d: SQLiteDatabase): Int {
        var added = 0
        fun columns(table: String): Set<String> = runCatching {
            d.rawQuery("PRAGMA table_info($table)", null).use { c ->
                val s = mutableSetOf<String>()
                while (c.moveToNext()) s.add(c.getString(1))
                s
            }
        }.getOrElse { emptySet() }

        fun ensure(table: String, column: String, ddl: String) {
            val existing = columns(table)
            // Empty = table not created yet (fresh DB; the v1 CREATE includes
            // the column). Skip so we don't log a spurious "no such table".
            if (existing.isEmpty() || existing.contains(column)) return
            runCatching {
                d.execSQL("ALTER TABLE $table ADD COLUMN $ddl")
                added++
                Log.i(TAG, "[Migration] schema repair: added $table.$column")
            }.onFailure { Log.e(TAG, "column repair failed for $table.$column", it) }
        }
        ensure("confusion_dictionary", "archived_at", "archived_at REAL DEFAULT NULL")
        ensure("confusion_dictionary", "source", "source TEXT NOT NULL DEFAULT '$SOURCE_VOICE'")
        ensure("typed_vocabulary", "archived_at", "archived_at REAL DEFAULT NULL")
        ensure("typed_vocabulary", "last_day", "last_day TEXT")
        return added
    }

    companion object {
        private const val TAG = "VoiceCorrectionDB"

        /**
         * Bumped ONLY for versioned structural migrations. Additive columns go
         * through [ensureIdempotentColumns] and do NOT require a bump.
         */
        const val SCHEMA_VERSION = 1

        /** Signal captured from editing an ASR transcript. */
        const val SOURCE_VOICE = "voice"

        /**
         * Signal captured from a select-and-replace edit in a plain text field.
         * Kept distinct from [SOURCE_VOICE] so the two can be weighted or
         * cleared independently later.
         */
        const val SOURCE_TEXT = "text_input"

        @Volatile
        private var instance: VoiceCorrectionDb? = null

        /**
         * Shared instance, or null if even the rebuild-empty fallback failed —
         * in which case every caller degrades to "no correction" rather than
         * crashing.
         */
        fun getInstance(context: Context): VoiceCorrectionDb? {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: runCatching {
                    val file = File(context.applicationContext.filesDir, "voice-correction.db")
                    VoiceCorrectionDb(file).takeIf { it.db != null }
                }.getOrNull()?.also { instance = it }
            }
        }

        /**
         * `frequency / (frequency + negative * 2)` — a rejection costs double a
         * confirmation. (3,0)=1.0, (3,1)=0.6, (1,3)=0.25 which is below the 0.3
         * retrieval floor, so a thrice-rejected pair stops being offered.
         */
        fun confidence(frequency: Int, negative: Int): Double {
            val f = maxOf(frequency, 0).toDouble()
            val n = maxOf(negative, 0).toDouble()
            val denominator = f + n * 2.0
            if (denominator <= 0) return 0.5
            return f / denominator
        }

        /** yyyy-MM-dd in the device zone, the key for `distinct_days`. */
        fun dayKey(epochSeconds: Double): String {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = (epochSeconds * 1000).toLong()
            return "%04d-%02d-%02d".format(
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH),
            )
        }

        internal fun decodeVariants(json: String): List<String> = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrElse { emptyList() }

        internal fun encodeVariants(variants: List<String>): String =
            JSONArray().apply { variants.forEach { put(it) } }.toString()
    }
}

/** One row of the confusion dictionary, as read by the retrieval path. */
data class ConfusionRow(
    val id: String,
    val phoneticKey: String,
    val variants: List<String>,
    val correctedTerm: String,
    val locale: String,
    val frequency: Int,
    val negativeFeedbackCount: Int,
    val confidence: Double,
    val lastSeen: Double,
)

/** One row of the typed-vocabulary table. */
data class VocabularyRow(
    val id: String,
    val term: String,
    val phoneticKey: String,
    val locale: String,
    val posTag: String?,
    val frequency: Int,
    val distinctDays: Int,
)

// ─── Merged from VoiceCorrectionDiff.kt ──────────────────────────────────────────────────

/**
 * [T-android-voice-correction] Turns a before/after edit into learnable
 * confusion pairs. Port of iOS `Agent/Speech/VoiceCorrectionDiff.swift`.
 *
 * ## Why character-level LCS and not token-level
 * Token-level alignment was tried on iOS and FAILS. Fixing a name changes how
 * the segmenter cuts its neighbour:
 *
 *     "张山说..."  -> ["张山", "说", ...]
 *     "张三说..."  -> ["张三说", ...]
 *
 * The LCS then emits a 2-for-1 replace, which any "1-for-1 only" filter
 * silently discards, and the summary comes back empty. Re-segmentation after a
 * substitution is the NORM, not an edge case.
 *
 * So: diff at character level (stable), then widen each replacement out to the
 * word boundaries of the ORIGINAL so the learned pair reads as 张山→张三 rather
 * than the minimal 山→三.
 */
object VoiceCorrectionDiff {

    /** One learned substitution. */
    data class Pair(val from: String, val to: String)

    /**
     * Share of the text that changed: Levenshtein over edit units, normalized by
     * the longer side. Compared against [VoiceCorrectionConfig.MAX_CHAR_CHANGE_RATIO]
     * to reject an LLM that rewrote the whole utterance instead of fixing it.
     *
     * [T-android-voice-mixed-term-restore] Each CJK character or symbol is one
     * unit, and each run of ASCII letters/digits is ONE unit. Counting raw
     * characters made restoring an English term look like a rewrite: "看看遗穴"
     * -> "看看Issue" is one word swapped, but 5 of 7 characters differ (0.71 >
     * 0.5) and the correction was thrown away. A Latin word is one spoken word,
     * so it is one unit here (2 of 4 = 0.5, accepted). Port of iOS 8622ea275.
     */
    fun charChangeRatio(from: String, to: String): Double {
        val a = editUnits(from)
        val b = editUnits(to)
        if (a.isEmpty() && b.isEmpty()) return 0.0
        return levenshtein(a, b).toDouble() / maxOf(a.size, b.size, 1).toDouble()
    }

    internal fun editUnits(text: String): List<String> {
        val units = mutableListOf<String>()
        val word = StringBuilder()
        for (c in text) {
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') { word.append(c); continue }
            if (word.isNotEmpty()) { units.add(word.toString()); word.setLength(0) }
            units.add(c.toString())
        }
        if (word.isNotEmpty()) units.add(word.toString())
        return units
    }

    private fun levenshtein(a: List<String>, b: List<String>): Int {
        if (a.isEmpty()) return b.size
        if (b.isEmpty()) return a.size
        var previous = IntArray(b.size + 1) { it }
        var current = IntArray(b.size + 1)
        for (i in 1..a.size) {
            current[0] = i
            for (j in 1..b.size) {
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
            }
            val t = previous; previous = current; current = t
        }
        return previous[b.size]
    }

    /**
     * Extract word-level substitution pairs between [from] and [to].
     *
     * Only `.replace` operations become pairs — a pure insertion or deletion
     * carries no "this was misheard as that" information.
     */
    fun tokenPairs(from: String, to: String, tokenize: (String) -> List<String>): List<Pair> {
        if (from.isEmpty() || to.isEmpty() || from == to) return emptyList()

        val a = from.map { it.toString() }
        val b = to.map { it.toString() }

        // Word spans of the ORIGINAL only. Segmenters drop whitespace, so we
        // cannot assume the tokens concatenate back to the source — locate each
        // one forward from a cursor instead.
        // Half-open [start, end) spans of each word in the original.
        val spans = wordSpans(from, tokenize)
        fun wordContaining(index: Int): kotlin.Pair<Int, Int>? =
            spans.firstOrNull { index >= it.first && index < it.second }

        val out = mutableListOf<Pair>()
        var ai = 0
        var bi = 0
        for (op in LcsDiff.diff(a, b)) {
            when (op) {
                is LcsDiff.Op.Equal -> { ai += op.items.size; bi += op.items.size }
                is LcsDiff.Op.Insert -> bi += op.items.size
                is LcsDiff.Op.Delete -> ai += op.items.size
                is LcsDiff.Op.Replace -> {
                    val oldStart = ai
                    val oldEnd = ai + op.old.size
                    val newStart = bi
                    val newEnd = bi + op.new.size

                    // Widen the "was" side to whole words…
                    var lo = oldStart
                    var hi = oldEnd
                    wordContaining(oldStart)?.let { lo = minOf(lo, it.first) }
                    if (oldEnd > oldStart) {
                        wordContaining(oldEnd - 1)?.let { hi = maxOf(hi, it.second) }
                    }
                    // …and pad the "became" side by the same amount on each
                    // side. Safe because the padding characters came from
                    // `.equal` runs, so they are identical in both strings.
                    val padLeft = oldStart - lo
                    val padRight = hi - oldEnd
                    val nLo = maxOf(0, newStart - padLeft)
                    val nHi = minOf(b.size, newEnd + padRight)

                    val was = a.subList(lo, minOf(hi, a.size)).joinToString("")
                    val became = b.subList(minOf(nLo, b.size), nHi).joinToString("")

                    ai = oldEnd
                    bi = newEnd

                    if (was.isEmpty() || became.isEmpty()) continue
                    if (was == became) continue
                    // Drop length-imbalanced runs — those are rewrites, not
                    // mishearings.
                    val ratio = maxOf(was.length, became.length).toDouble() /
                        maxOf(1, minOf(was.length, became.length)).toDouble()
                    if (ratio > 2.0) continue
                    out.add(Pair(was, became))
                }
            }
        }
        return out
    }

    /**
     * Half-open [start, end) spans of each segmented word within [text],
     * located by walking forward so whitespace between tokens doesn't desync.
     */
    private fun wordSpans(
        text: String,
        tokenize: (String) -> List<String>,
    ): List<kotlin.Pair<Int, Int>> {
        val tokens = runCatching { tokenize(text) }.getOrElse { emptyList() }
        val spans = mutableListOf<kotlin.Pair<Int, Int>>()
        var cursor = 0
        for (t in tokens) {
            if (t.isEmpty()) continue
            val idx = text.indexOf(t, cursor)
            if (idx < 0) continue
            spans.add(idx to idx + t.length)
            cursor = idx + t.length
        }
        return spans
    }
}

/**
 * Classic O(n·m) LCS diff over token lists. Contiguous runs coalesce, and a
 * region where both sides have content becomes a single [Op.Replace] rather
 * than a delete followed by an insert — which is what lets the caller widen it
 * to word boundaries.
 */
object LcsDiff {

    sealed interface Op {
        data class Equal(val items: List<String>) : Op
        data class Replace(val old: List<String>, val new: List<String>) : Op
        data class Insert(val items: List<String>) : Op
        data class Delete(val items: List<String>) : Op
    }

    fun diff(a: List<String>, b: List<String>): List<Op> {
        if (a.isEmpty() && b.isEmpty()) return emptyList()
        if (a.isEmpty()) return listOf(Op.Insert(b))
        if (b.isEmpty()) return listOf(Op.Delete(a))

        // Suffix LCS table, filled backwards.
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.size - 1 downTo 0) {
            for (j in b.size - 1 downTo 0) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1
                else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }

        val ops = mutableListOf<Op>()
        val pendingOld = mutableListOf<String>()
        val pendingNew = mutableListOf<String>()
        val pendingEqual = mutableListOf<String>()

        fun flushChanges() {
            if (pendingOld.isEmpty() && pendingNew.isEmpty()) return
            when {
                pendingOld.isNotEmpty() && pendingNew.isNotEmpty() ->
                    ops.add(Op.Replace(pendingOld.toList(), pendingNew.toList()))
                pendingOld.isNotEmpty() -> ops.add(Op.Delete(pendingOld.toList()))
                else -> ops.add(Op.Insert(pendingNew.toList()))
            }
            pendingOld.clear(); pendingNew.clear()
        }
        fun flushEqual() {
            if (pendingEqual.isEmpty()) return
            ops.add(Op.Equal(pendingEqual.toList()))
            pendingEqual.clear()
        }

        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            if (a[i] == b[j]) {
                flushChanges()
                pendingEqual.add(a[i]); i++; j++
            } else {
                flushEqual()
                if (lcs[i + 1][j] >= lcs[i][j + 1]) { pendingOld.add(a[i]); i++ }
                else { pendingNew.add(b[j]); j++ }
            }
        }
        flushEqual()
        while (i < a.size) { pendingOld.add(a[i]); i++ }
        while (j < b.size) { pendingNew.add(b[j]); j++ }
        flushChanges()
        return ops
    }
}

// ─── Merged from VoiceCorrectionEngine.kt ──────────────────────────────────────────────────

/** The engine's verdict on one transcript. */
data class CorrectionSuggestion(
    val original: String,
    val corrected: String,
    val hasChange: Boolean,
    val modelGroupUsed: String,
    val durationMs: Int,
    /** Non-null when we deliberately declined to change anything. */
    val rejectedReason: String? = null,
    val diffSummary: String = "",
    val appliedPairs: List<VoiceCorrectionDiff.Pair> = emptyList(),
    /**
     * [T-voice-correction-fallback] The model's own error text when every
     * model failed (e.g. "credit balance is too low"), so the panel can name
     * the cause instead of only "failed". Null otherwise.
     */
    val failureDetail: String? = null,
)

/**
 * [T-android-voice-correction] Retrieve → fuse → correct.
 * Port of iOS `Agent/Speech/VoiceCorrectionEngine.swift`.
 *
 * Never throws: every failure path returns a suggestion carrying the ORIGINAL
 * text and a `rejectedReason`. Correction is an enhancement, so a broken model
 * or a network blip must leave the user's transcript exactly as it was.
 */
class VoiceCorrectionEngine(
    private val sources: List<CorrectionSignalSource>,
    private val strategy: CorrectionStrategy,
    private val db: VoiceCorrectionDb?,
    private val segment: (String) -> List<String>,
    private val consent: () -> Boolean,
) {

    private data class CacheEntry(val candidates: List<CorrectionCandidate>, val at: Long)

    private val cacheLock = Mutex()
    private val cache = LinkedHashMap<String, CacheEntry>()

    /**
     * Full correction pass. `persistEvent` is honoured only when collection
     * consent is granted.
     */
    suspend fun correct(
        transcript: String,
        locale: String = "zh",
        context: ConversationContext = ConversationContext.EMPTY,
        persistEvent: Boolean = true,
    ): CorrectionSuggestion {
        val started = System.currentTimeMillis()
        val text = transcript.trim()
        if (text.isEmpty()) {
            return CorrectionSuggestion(transcript, transcript, false, "none", 0, "empty_input")
        }

        // Zero candidates is NOT a bail-out: the model can still fix things
        // using the conversation context and rare-term digest alone.
        val candidates = retrieveCandidates(text, locale)

        // [T-voice-correction-fallback] No ceiling over the whole run: each
        // model call has its own CORRECTION_BUDGET_MS, enforced by
        // CorrectionFallback (which also handles the blocking HTTP call that a
        // plain timeout cannot cut short), and the chain is bounded by its
        // model count. A whole-run ceiling here would starve the later models.
        val outcome = try {
            withContext(Dispatchers.IO) {
                strategy.correct(text, candidates, context, locale)
            }
        } catch (t: Throwable) {
            // [T-voice-correction-fallback] The strategy already walked every
            // candidate model; the last one's error is the reason to show.
            val last = (t as? CorrectionError.AllModelsFailed)?.attempts?.lastOrNull()?.error ?: t
            Log.w(
                TAG,
                "[VoiceCorrection][Correct] FAILED reason=llm_error:${last.javaClass.simpleName} " +
                    "attempts=${(t as? CorrectionError.AllModelsFailed)?.attempts?.size ?: 0} fallback=original_text " +
                    "durationMs=${System.currentTimeMillis() - started}",
                t,
            )
            return CorrectionSuggestion(
                transcript, transcript, false, "none",
                (System.currentTimeMillis() - started).toInt(),
                "llm_error:${last.javaClass.simpleName}",
                failureDetail = last.message,
            )
        }

        val corrected = outcome.correctedText
        if (corrected == text) {
            // Not a failure — the model looked and found nothing to fix.
            return CorrectionSuggestion(
                transcript, transcript, false, outcome.modelGroupUsed, outcome.durationMs,
            )
        }

        // A model that rewrote more than half the characters did not correct
        // the transcript, it replaced it.
        val ratio = VoiceCorrectionDiff.charChangeRatio(text, corrected)
        if (ratio > VoiceCorrectionConfig.MAX_CHAR_CHANGE_RATIO) {
            Log.i(TAG, "[Correct] rejected diff_too_large ratio=${"%.2f".format(ratio)}")
            return CorrectionSuggestion(
                transcript, transcript, false, outcome.modelGroupUsed, outcome.durationMs,
                "diff_too_large",
            )
        }

        val pairs = VoiceCorrectionDiff.tokenPairs(text, corrected, segment)
        val summary = pairs.joinToString("，") { "${it.from}→${it.to}" }

        if (persistEvent && consent()) {
            db?.recordCorrectionEvent(text, corrected, "suggested", summary.ifEmpty { null })
        }

        return CorrectionSuggestion(
            original = transcript,
            corrected = corrected,
            hasChange = true,
            modelGroupUsed = outcome.modelGroupUsed,
            durationMs = (System.currentTimeMillis() - started).toInt(),
            diffSummary = summary,
            appliedPairs = pairs,
        )
    }

    /**
     * Stage 1 + 2: phonetic keys → per-source lookup (concurrent) → fusion.
     */
    suspend fun retrieveCandidates(transcript: String, locale: String): List<CorrectionCandidate> {
        val normalizer = PhoneticNormalizerRegistry.normalizer(locale) ?: return emptyList()
        val started = System.currentTimeMillis()

        val keys = runCatching { segment(transcript) }.getOrElse { emptyList() }
            // Single characters are dropped: too many homophones, all noise.
            .filter { it.length >= 2 }
            .map { normalizer.normalize(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (keys.isEmpty()) return emptyList()

        val (cached, missing) = partitionByCache(keys, locale)
        val fetched = if (missing.isEmpty()) emptyList() else coroutineScope {
            // One task per source, each seeing all missing keys: wall-clock is
            // the slowest source, not the sum.
            sources.map { source ->
                async(Dispatchers.IO) {
                    runCatching {
                        source.lookupCandidates(missing, locale, VoiceCorrectionConfig.MAX_CANDIDATES)
                    }.getOrElse { emptyList() }
                }
            }.flatMap { it.await() }
        }
        if (missing.isNotEmpty()) storeInCache(missing, fetched, locale)

        val elapsed = System.currentTimeMillis() - started
        if (elapsed > VoiceCorrectionConfig.RETRIEVAL_BUDGET_MS) {
            // Observational only — matching iOS, retrieval is never aborted.
            Log.i(TAG, "[Retrieve] OVER BUDGET ${elapsed}ms keys=${keys.size}")
        }
        return fuse(cached + fetched)
    }

    /**
     * Score = sourceWeight × confidence, deduped by `phoneticKey|term` keeping
     * the better score, then the top [VoiceCorrectionConfig.MAX_CANDIDATES].
     */
    private fun fuse(candidates: List<CorrectionCandidate>): List<CorrectionCandidate> {
        val weights = sources.associate { it.sourceIdentifier to it.defaultWeight }
        fun score(c: CorrectionCandidate) = (weights[c.sourceIdentifier] ?: 0.5) * c.confidence

        val best = HashMap<String, CorrectionCandidate>()
        for (c in candidates) {
            val k = "${c.phoneticKey}|${c.term}"
            val existing = best[k]
            if (existing != null && score(existing) >= score(c)) continue
            best[k] = c
        }
        return best.values.sortedByDescending { score(it) }
            .take(VoiceCorrectionConfig.MAX_CANDIDATES)
    }

    // -- cache --
    //
    // Keys are namespaced by locale, which iOS does NOT do: its cache is keyed
    // on the bare phonetic key, so switching language can serve candidates
    // learned in the other locale. Cheap to get right here.

    private suspend fun partitionByCache(
        keys: List<String>,
        locale: String,
    ): Pair<List<CorrectionCandidate>, List<String>> = cacheLock.withLock {
        val now = System.currentTimeMillis()
        val hits = mutableListOf<CorrectionCandidate>()
        val missing = mutableListOf<String>()
        for (key in keys) {
            val entry = cache["$locale|$key"]
            if (entry != null && now - entry.at < VoiceCorrectionConfig.CACHE_TTL_MS) {
                hits.addAll(entry.candidates)
            } else {
                missing.add(key)
            }
        }
        hits to missing
    }

    private suspend fun storeInCache(
        keys: List<String>,
        candidates: List<CorrectionCandidate>,
        locale: String,
    ) = cacheLock.withLock {
        val byKey = candidates.groupBy { it.phoneticKey }
        val now = System.currentTimeMillis()
        // NEGATIVE results are cached deliberately: a key with no candidates is
        // a useful fact, and re-querying it every pass is pure cost.
        for (key in keys) cache["$locale|$key"] = CacheEntry(byKey[key] ?: emptyList(), now)

        if (cache.size > VoiceCorrectionConfig.CACHE_LIMIT) {
            val excess = cache.size - VoiceCorrectionConfig.CACHE_LIMIT
            cache.entries.sortedBy { it.value.at }.take(excess).forEach { cache.remove(it.key) }
        }
    }

    suspend fun clearCache() = cacheLock.withLock { cache.clear() }

    /**
     * Fusion in isolation. Exposed because the full retrieval path normalizes
     * through `android.icu`, which is unavailable on the JVM, so scoring and
     * dedup would otherwise only ever be covered on a device.
     */
    internal fun fuseForTest(candidates: List<CorrectionCandidate>) = fuse(candidates)

    /**
     * Prime the segmenter off the critical path. The first jieba call loads a
     * ~5MB dictionary plus the HMM model — measured at ~1.9s on iOS, versus
     * 10-12ms once warm. Without this the first correction feels broken.
     * Idempotent and safe to call repeatedly.
     */
    fun warmUp() {
        runCatching {
            segment("预热分词词典")
            PinyinNormalizer.normalize("预热")
        }.onFailure { Log.w(TAG, "warmUp failed", it) }
    }

    /**
     * Everything [correct] does except calling the model — for diagnostics.
     * Populates the cache as a side effect; never persists.
     */
    suspend fun dryRun(
        transcript: String,
        locale: String = "zh",
        context: ConversationContext = ConversationContext.EMPTY,
    ): Triple<List<String>, List<CorrectionCandidate>, String> {
        val tokens = runCatching { segment(transcript) }.getOrElse { emptyList() }
        val candidates = retrieveCandidates(transcript, locale)
        val prompt = LlmCorrectionStrategy.buildPrompt(
            transcript, candidates, context, CorrectionLanguageProfile.resolve(locale, transcript),
        )
        return Triple(tokens, candidates, prompt)
    }

    companion object {
        private const val TAG = "VoiceCorrectionEngine"
    }
}

// ─── Merged from VoiceCorrectionRecorder.kt ──────────────────────────────────────────────────

/**
 * [T-android-voice-correction] Captures the user's own fixes as training
 * signal. Port of iOS `Agent/Speech/VoiceCorrectionRecorder.swift`.
 *
 * Two entry points share ONE admission rule, differing only in `source`:
 *  - [recordTranscriptEdit] — the user edited an ASR transcript.
 *  - [recordTextInputEdit]  — the user selected text in a plain input field and
 *    replaced it.
 *
 * ## The admission rule
 * An edit is learned only when the before/after are PHONETICALLY CLOSE
 * (similarity ≥ 0.6) and comparable in length (ratio ≤ 2.0). That is what
 * separates "the recognizer misheard me" from "I changed my mind" — and it is
 * why the plain-text path can reuse this untouched: a select-and-replace that
 * swaps in something that sounds nothing like the original is a rewrite, and
 * rewrites teach the dictionary nothing but noise.
 *
 * ## Privacy
 * Every entry point is consent-gated, and NOTHING here logs verbatim user text
 * — only lengths and scores. The database row keeps the truth; the log does
 * not need it.
 *
 * Callers must invoke these off the main thread; they do blocking SQLite work.
 */
class VoiceCorrectionRecorder(
    private val context: Context,
    private val db: VoiceCorrectionDb?,
    private val segment: (String) -> List<String>,
) {

    /** A fix made to a speech-recognition transcript. */
    fun recordTranscriptEdit(
        before: String,
        after: String,
        locale: String = "zh",
        asrProvider: String = "",
    ) = recordEdit(before, after, locale, asrProvider, VoiceCorrectionDb.SOURCE_VOICE)

    /**
     * A select-and-replace edit in an ordinary text field.
     *
     * The trigger is the edit ACTION itself; whether it counts as a correction
     * is decided here by the same phonetic test used for transcripts. Silent
     * background capture — there is no UI and the user feels nothing.
     */
    fun recordTextInputEdit(
        before: String,
        after: String,
        locale: String = "zh",
    ) = recordEdit(before, after, locale, "", VoiceCorrectionDb.SOURCE_TEXT)

    private fun recordEdit(
        before: String,
        after: String,
        locale: String,
        asrProvider: String,
        source: String,
    ) {
        if (!VoiceCorrectionConsent.isEnabled(context)) return
        val b = before.trim()
        val a = after.trim()
        if (b.isEmpty() || a.isEmpty() || b == a) return

        val normalizer = PhoneticNormalizerRegistry.normalizer(locale) ?: return
        val database = db ?: return

        var accepted = 0
        var droppedRewrite = 0
        // [T-android-correction-admission-multisignal] Signal names only — the
        // Verdict.reason strings never contain user text, so logging which
        // signal fired stays privacy-safe while making admission debuggable.
        val admitReasons = mutableListOf<String>()
        val rejectReasons = mutableListOf<String>()

        for ((fromText, toText, contextSample) in alignedCandidates(b, a)) {
            val fromKey = normalizer.normalize(fromText)
            if (fromKey.isEmpty()) continue

            // [T-android-correction-admission-multisignal] Multi-signal
            // admission (homophone / acronym / digit-norm, guarded by edit
            // locality and a reorder check) replaces the old single-axis
            // `phoneticSim >= 0.6 AND lengthRatio <= 2`, which dropped most
            // real ASR mistakes (Chinese-English mixing, acronym expansion,
            // digit normalization). `contextSample` is the sentence the span
            // was diffed within, so its length is the locality denominator;
            // fall back to the whole edited text when a sentence isn't known.
            val sentenceLength = contextSample?.length ?: maxOf(b.length, a.length)
            val verdict = CorrectionAdmission.judge(
                from = fromText,
                to = toText,
                normalizer = normalizer,
                sentenceLength = sentenceLength,
            )
            if (!verdict.isAdmitted) {
                droppedRewrite++
                rejectReasons.add(verdict.reason)
                continue
            }
            admitReasons.add(verdict.reason)
            database.upsertConfusion(
                phoneticKey = fromKey,
                originalVariant = fromText,
                correctedTerm = toText,
                locale = locale,
                contextSample = contextSample?.take(VoiceCorrectionConfig.CONTEXT_SAMPLE_CHARS),
                asrProvider = asrProvider,
                source = source,
            )
            accepted++
        }
        // Lengths, counts and signal names only — never the text itself.
        Log.i(
            TAG,
            "[Record] source=$source beforeLen=${b.length} afterLen=${a.length} " +
                "accepted=$accepted droppedRewrite=$droppedRewrite " +
                "admit=$admitReasons reject=$rejectReasons",
        )
    }

    /**
     * Pair up what changed, sentence by sentence when possible.
     *
     * When both sides split into the same number of sentences we diff only the
     * ones that differ, using the original sentence as context. When the counts
     * differ the user split or merged sentences, and a positional alignment
     * would emit garbage pairs — so fall back to diffing the whole text, which
     * is coarser but never wrong.
     *
     * @return triples of (was, became, contextSample)
     */
    private fun alignedCandidates(
        before: String,
        after: String,
    ): List<Triple<String, String, String?>> {
        val beforeSentences = SentenceSplitter.split(before)
        val afterSentences = SentenceSplitter.split(after)

        val out = mutableListOf<Triple<String, String, String?>>()
        if (beforeSentences.size == afterSentences.size && beforeSentences.isNotEmpty()) {
            for (i in beforeSentences.indices) {
                val bs = beforeSentences[i]
                val `as` = afterSentences[i]
                if (bs == `as`) continue
                for (p in VoiceCorrectionDiff.tokenPairs(bs, `as`, segment)) {
                    out.add(Triple(p.from, p.to, bs))
                }
            }
        } else {
            for (p in VoiceCorrectionDiff.tokenPairs(before, after, segment)) {
                out.add(Triple(p.from, p.to, before))
            }
        }
        return out
    }

    /**
     * The user applied an AI correction. Records the OUTCOME for metrics and
     * nothing else.
     *
     * Deliberately does NOT write a confusion row: the suggestion was derived
     * from that very table, so writing it back would feed the model's own
     * output in as fresh evidence and the system would learn from an echo of
     * itself.
     */
    fun recordSuggestionAccepted(original: String, corrected: String, summary: String) {
        if (!VoiceCorrectionConsent.isEnabled(context)) return
        db?.recordCorrectionEvent(original, corrected, "accepted", summary.ifEmpty { null })
    }

    /**
     * The user rejected a proposed correction — negative feedback only, which
     * lowers the pair's confidence toward the retrieval floor.
     */
    fun recordSuggestionRejected(
        pairs: List<VoiceCorrectionDiff.Pair>,
        locale: String = "zh",
    ) {
        if (!VoiceCorrectionConsent.isEnabled(context)) return
        val database = db ?: return
        val normalizer = PhoneticNormalizerRegistry.normalizer(locale) ?: return
        for (p in pairs) {
            val key = normalizer.normalize(p.from)
            if (key.isEmpty()) continue
            database.recordNegativeFeedback(key, p.to, locale)
        }
        database.recordCorrectionEvent(
            pairs.joinToString("，") { it.from },
            pairs.joinToString("，") { it.to },
            "reverted",
            null,
        )
    }

    companion object {
        private const val TAG = "VoiceCorrectionRec"
    }
}
