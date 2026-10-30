package com.openminis.app.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * [T-md-inline-html] The few HTML tags models mix into markdown (most often in
 * table cells): `<br>` and a handful of inline styles. Everything else - any
 * tag with attributes, `<div>`, `<span style=…>`, `<img>`, `<a>`, `<script>` -
 * stays literal text; nothing is parsed as HTML or executed.
 *
 * No HTML parser: the inline parsers call [brLength] / [openAt] when they meet
 * a `<`, so text without one costs a single char comparison per position.
 *
 * Rules (shared with iOS / Windows, src/shared/markdown/html-tags.md):
 *  - Only bare tags: `<` + name + optional spaces + `>` (any case); `<br>` may
 *    also end in `/>`. Attributes, `<b/>`, `</br>`, comments stay literal.
 *  - `<br>`, `<br/>`, `<br />` are a line break, also in table cells; a newline
 *    right after one adds nothing (`a<br>\nb` is one break, not a blank line).
 *  - `<b>`/`<strong>`, `<i>`/`<em>`, `<u>`, `<s>`/`<del>`/`<strike>`, `<code>`,
 *    `<sub>`, `<sup>` style their content only when the matching close tag is on
 *    the same line. Different names never pair; the outer pair of crossing tags
 *    wins and the inner unmatched one is literal. An open tag without one (a stream that has only delivered
 *    `<b>` so far) is shown literally until the close arrives; nothing after it
 *    is swallowed. Pairing within one line also keeps the streaming parser's
 *    "prefix ++ suffix == whole" split at newlines valid.
 *  - Code spans are skipped when looking for the close tag, and the callers
 *    never look inside code (fenced blocks are not inline text; `…` is
 *    consumed before its contents are scanned), so `<br>` in code stays as is.
 *  - Content is parsed again, so `<b>**x**</b>` and `**<b>x</b>**` both nest.
 */
internal object InlineHtmlTags {

    enum class Kind { BOLD, ITALIC, UNDERLINE, STRIKE, CODE, SUB, SUP }

    /** An open tag at some offset: its lowercased [name] and source [length]. */
    data class Open(val name: String, val kind: Kind, val length: Int)

    private val tags = listOf(
        Open("b", Kind.BOLD, 3), Open("strong", Kind.BOLD, 8),
        Open("i", Kind.ITALIC, 3), Open("em", Kind.ITALIC, 4),
        Open("u", Kind.UNDERLINE, 3),
        Open("s", Kind.STRIKE, 3), Open("del", Kind.STRIKE, 5), Open("strike", Kind.STRIKE, 8),
        Open("code", Kind.CODE, 6),
        Open("sub", Kind.SUB, 5), Open("sup", Kind.SUP, 5),
    )
    private const val MAX_NAME = 6 // "strong", "strike"

    /**
     * Cheap pre-check for the inline parsers: every supported tag has a letter
     * right after the `<`, so `1 < 2`, `a<-b` and a stray `</x>` are rejected
     * without any further scan.
     */
    fun mayStartTag(text: String, i: Int): Boolean =
        i + 1 < text.length && text[i + 1].let { it in 'a'..'z' || it in 'A'..'Z' }

    private fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    /** Source length of a `<br>` / `<br >` / `<br/>` / `<br />` at [i] (any case), or 0. */
    fun brLength(text: String, i: Int): Int {
        if (text[i] != '<' || !text.regionMatches(i + 1, "br", 0, 2, ignoreCase = true)) return 0
        var j = i + 3
        while (j < text.length && text[j] == ' ') j++
        if (j < text.length && text[j] == '/') j++
        return if (j < text.length && text[j] == '>') j + 1 - i else 0
    }

    /** Where text resumes after a `<br>` of [length] at [i]: past one newline that follows it. */
    fun afterBr(text: String, i: Int, length: Int): Int {
        val end = i + length
        return if (end < text.length && text[end] == '\n') end + 1 else end
    }

    /** The whitelisted open tag at [i] (`<name>` or `<name >`, any case), or null. */
    fun openAt(text: String, i: Int): Open? {
        if (text[i] != '<') return null
        var j = i + 1
        while (j < text.length && j - i - 1 <= MAX_NAME && isAsciiLetter(text[j])) j++
        val len = j - i - 1
        if (len == 0 || len > MAX_NAME) return null
        while (j < text.length && text[j] == ' ') j++
        if (j >= text.length || text[j] != '>') return null
        // No allocation on the common path: compare in place against the few names.
        for (t in tags) {
            if (t.name.length == len && text.regionMatches(i + 1, t.name, 0, len, ignoreCase = true)) {
                val length = j + 1 - i
                return if (length == t.length) t else t.copy(length = length)
            }
        }
        return null
    }

    /** Length of the close tag `</name>` / `</name >` for [open] at [k], or 0. */
    private fun closeTagLength(text: String, k: Int, open: Open): Int {
        if (text[k] != '<' || k + 1 >= text.length || text[k + 1] != '/') return 0
        if (!text.regionMatches(k + 2, open.name, 0, open.name.length, ignoreCase = true)) return 0
        var j = k + 2 + open.name.length
        while (j < text.length && text[j] == ' ') j++
        return if (j < text.length && text[j] == '>') j + 1 - k else 0
    }

    /** Offset just past the close tag that [Pairs.closeOf] gave for [open]. */
    fun closeEnd(text: String, close: Int, open: Open): Int = close + closeTagLength(text, close, open)

    /** Which open tag of one inline text is closed where; see [pairs]. */
    class Pairs internal constructor(private val closes: HashMap<Int, Int>) {
        /** Start of the close tag pairing the open tag at [open], or -1. */
        fun closeOf(open: Int): Int = closes[open] ?: -1
    }

    /**
     * Pairs every whitelisted open tag of [text] with its close tag in ONE
     * linear pass - a stack per name, cleared at each newline, code spans
     * skipped - so a line of thousands of unclosed `<b>` costs O(n), not a
     * search to the end of the line for each. Same-name tags nest. Crossing
     * pairs of different names both pair here; the inner one is then inside
     * the outer one's content, which is parsed again on its own and finds no
     * partner there, so the outer pair wins as the spec asks.
     */
    fun pairs(text: String): Pairs {
        val closes = HashMap<Int, Int>()
        val stacks = HashMap<String, ArrayDeque<Int>>()
        var k = 0
        while (k < text.length) {
            val c = text[k]
            when {
                c == '\n' -> stacks.clear()
                c == '`' -> {
                    val end = text.indexOf('`', k + 1)
                    val nl = text.indexOf('\n', k + 1)
                    if (end != -1 && (nl == -1 || end < nl)) { k = end + 1; continue }
                }
                c == '<' && k + 1 < text.length && text[k + 1] == '/' -> {
                    val name = closeNameAt(text, k)
                    if (name != null) stacks[name]?.removeLastOrNull()?.let { closes[it] = k }
                }
                c == '<' -> {
                    val open = openAt(text, k)
                    if (open != null) {
                        stacks.getOrPut(open.name) { ArrayDeque() }.addLast(k)
                        k += open.length
                        continue
                    }
                }
            }
            k++
        }
        return Pairs(closes)
    }

    /** The whitelisted name of a close tag `</name>` / `</name >` at [k], or null. */
    private fun closeNameAt(text: String, k: Int): String? {
        var j = k + 2
        while (j < text.length && j - k - 2 <= MAX_NAME && isAsciiLetter(text[j])) j++
        val len = j - k - 2
        if (len == 0 || len > MAX_NAME) return null
        while (j < text.length && text[j] == ' ') j++
        if (j >= text.length || text[j] != '>') return null
        return tags.firstOrNull { it.name.length == len && text.regionMatches(k + 2, it.name, 0, len, ignoreCase = true) }?.name
    }

    /** [pairs] of one text, computed on the first [closeOf] (a `<br>` needs none). */
    class LazyPairs(private val text: String) {
        private var pairs: Pairs? = null
        fun closeOf(open: Int): Int = (pairs ?: pairs(text).also { pairs = it }).closeOf(open)
    }

    /**
     * For Copy as plain text: `<br>` becomes a newline and paired style tags
     * go, their content kept; unpaired or unknown tags and code spans stay as
     * they are (the caller unwraps code afterwards).
     */
    fun toPlain(s: String): String {
        if ('<' !in s && '&' !in s) return s
        val out = StringBuilder(s.length)
        appendPlain(s, out)
        return out.toString()
    }

    private fun appendPlain(s: String, out: StringBuilder) {
        var pairs: Pairs? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '`') {
                val end = s.indexOf('`', i + 1)
                val nl = s.indexOf('\n', i + 1)
                if (end != -1 && (nl == -1 || end < nl)) {
                    out.append(s, i, end + 1); i = end + 1; continue
                }
            }
            // `\<br>` and `&lt;br&gt;` are the literal text `<br>`, as on screen.
            if (isEscapedLiteral(s, i)) { out.append(s[i + 1]); i += 2; continue }
            if (c == '&') {
                val entity = entityAt(s, i)
                if (entity != null) { out.append(entity.first); i += entity.second; continue }
            }
            if (c == '<' && mayStartTag(s, i)) {
                val br = brLength(s, i)
                if (br > 0) {
                    // The caller joins lines with a newline of its own: a
                    // `<br>` ending this line would make a blank line.
                    if (i + br < s.length) out.append('\n')
                    i = afterBr(s, i, br)
                    continue
                }
                val open = openAt(s, i)
                if (open != null) {
                    val close = (pairs ?: pairs(s).also { pairs = it }).closeOf(i)
                    if (close != -1) {
                        appendPlain(s.substring(i + open.length, close), out)
                        i = closeEnd(s, close, open)
                        continue
                    }
                }
            }
            out.append(c); i++
        }
    }

    /**
     * For Copy as rich text: replaces `<br>` and paired whitelisted tags with
     * [marker]-wrapped indexes into [sink] (canonical HTML, e.g. `<br/>`,
     * `<strong>`), so the caller can escape everything else and restore them.
     * A pair crossing an already open one is left as text, so the HTML nests.
     */
    fun protectForHtml(s: String, sink: MutableList<String>, marker: Char): String {
        if ('<' !in s && '&' !in s) return s
        val pairs = pairs(s)
        val out = StringBuilder(s.length)
        fun token(html: String) {
            sink.add(html)
            out.append(marker).append(sink.size - 1).append(marker)
        }
        // Emitted pairs still open: (source offset of the close tag, its HTML).
        val open = ArrayDeque<Pair<Int, String>>()
        var i = 0
        while (i < s.length) {
            val top = open.lastOrNull()
            if (top != null && top.first == i) {
                open.removeLast()
                token(top.second)
                i = s.indexOf('>', i) + 1
                continue
            }
            // Literal `<`/`&` (escaped or as an entity) stay literal in the HTML.
            if (isEscapedLiteral(s, i)) { token(escapeHtml(s[i + 1].toString())); i += 2; continue }
            if (s[i] == '&') {
                val entity = entityAt(s, i)
                if (entity != null) { token(escapeHtml(entity.first)); i += entity.second; continue }
            }
            if (s[i] == '<' && mayStartTag(s, i)) {
                val br = brLength(s, i)
                if (br > 0) { token("<br/>"); i += br; continue }
                val tag = openAt(s, i)
                val close = if (tag != null) pairs.closeOf(i) else -1
                if (tag != null && close != -1 && (top == null || close < top.first)) {
                    val name = htmlName(tag.kind)
                    token("<$name>")
                    open.addLast(close to "</$name>")
                    i += tag.length
                    continue
                }
            }
            out.append(s[i]); i++
        }
        return out.toString()
    }

    private fun escapeHtml(t: String): String =
        t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** A backslash-escaped `<` or `&` at [i]: the character itself, never a tag or entity. */
    fun isEscapedLiteral(text: String, i: Int): Boolean =
        text[i] == '\\' && i + 1 < text.length && (text[i + 1] == '<' || text[i + 1] == '&')

    private val namedEntities = mapOf(
        "lt" to "<", "gt" to ">", "amp" to "&", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
    )

    /**
     * The HTML entity at [i] - `&lt;` `&gt;` `&amp;` `&quot;` `&apos;` `&nbsp;`,
     * or a numeric `&#60;` / `&#x3C;` - as (decoded text, source length), or
     * null. The decoded text is appended as is and never scanned again, so
     * `&lt;br&gt;` shows the literal `<br>` (spec "Showing a tag literally").
     * Only these named entities: CommonMark knows all of HTML5's, iOS (cmark)
     * decodes them all; the rest stay as written here.
     */
    fun entityAt(text: String, i: Int): Pair<String, Int>? {
        if (text[i] != '&') return null
        val semi = text.indexOf(';', i + 1)
        if (semi == -1 || semi - i > 10) return null
        if (i + 1 < semi && text[i + 1] == '#') {
            val hex = i + 2 < semi && (text[i + 2] == 'x' || text[i + 2] == 'X')
            val digits = text.substring(if (hex) i + 3 else i + 2, semi)
            if (digits.isEmpty() || digits.length > (if (hex) 6 else 7)) return null
            val cp = digits.toIntOrNull(if (hex) 16 else 10) ?: return null
            val valid = cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF
            return (if (valid) String(Character.toChars(cp)) else "\uFFFD") to semi + 1 - i
        }
        val decoded = namedEntities[text.substring(i + 1, semi)] ?: return null
        return decoded to semi + 1 - i
    }

    /**
     * For one-line previews (session list, search snippets, skill subtitles,
     * read-aloud): `<br>` becomes a space and every whitelisted style tag, open
     * or close, paired or not, goes - its text stays. Other tags stay as written.
     * Code is left exactly as it is: fenced blocks, indented code blocks and
     * backtick spans are recognised FIRST and copied verbatim, so
     * `` `a<br>b` `` still previews as `a<br>b`. `\<` and the entities of
     * [entityAt] give their literal character, which is never re-scanned.
     * Newlines are kept; the caller folds them.
     */
    fun stripForPreview(text: String): String {
        if (text.indexOf('<') < 0 && text.indexOf('&') < 0) return text
        val out = StringBuilder(text.length)
        var fenceChar = ' '
        var fenceLen = 0
        var prevBlank = true
        var indented = false
        var start = 0
        while (true) {
            val nl = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            var lead = start
            while (lead < nl && text[lead] == ' ') lead++
            val blank = (lead until nl).all { text[it].isWhitespace() }
            val run = if (lead - start <= 3 && lead < nl && (text[lead] == '`' || text[lead] == '~')) {
                var r = lead
                while (r < nl && text[r] == text[lead]) r++
                r - lead
            } else {
                0
            }
            val code: Boolean
            if (fenceLen > 0) {
                code = true
                if (run >= fenceLen && text[lead] == fenceChar) fenceLen = 0
            } else if (run >= 3) {
                code = true
                fenceChar = text[lead]
                fenceLen = run
            } else if (!blank && (lead - start >= 4 || (nl > start && text[start] == '\t')) && (prevBlank || indented)) {
                code = true
                indented = true
            } else {
                code = blank && indented
                if (!blank) indented = false
            }
            if (code) out.append(text, start, nl) else appendPreviewLine(text, start, nl, out)
            prevBlank = blank
            if (nl >= text.length) break
            out.append('\n')
            start = nl + 1
        }
        return out.toString()
    }

    private fun appendPreviewLine(text: String, from: Int, to: Int, out: StringBuilder) {
        var i = from
        while (i < to) {
            val c = text[i]
            when {
                c == '`' -> {
                    var r = i
                    while (r < to && text[r] == '`') r++
                    val n = r - i
                    // The matching run of exactly n backticks on this line.
                    var k = r
                    var close = -1
                    while (k < to) {
                        if (text[k] == '`') {
                            var e = k
                            while (e < to && text[e] == '`') e++
                            if (e - k == n) { close = e; break }
                            k = e
                        } else {
                            k++
                        }
                    }
                    val end = if (close == -1) r else close
                    out.append(text, i, end)
                    i = end
                }
                isEscapedLiteral(text, i) -> { out.append(text[i + 1]); i += 2 }
                c == '&' -> {
                    val entity = entityAt(text, i)
                    if (entity != null && i + entity.second <= to) {
                        out.append(entity.first); i += entity.second
                    } else {
                        out.append(c); i++
                    }
                }
                c == '<' && mayStartTag(text, i) -> {
                    val br = brLength(text, i)
                    val tag = if (br > 0) br else openAt(text, i)?.length ?: 0
                    if (br > 0) out.append(' ')
                    if (tag > 0 && i + tag <= to) i += tag else { out.append(c); i++ }
                }
                c == '<' && i + 1 < to && text[i + 1] == '/' && closeNameAt(text, i) != null -> {
                    i = text.indexOf('>', i) + 1
                }
                else -> { out.append(c); i++ }
            }
        }
    }

    private fun htmlName(kind: Kind): String = when (kind) {
        Kind.BOLD -> "strong"
        Kind.ITALIC -> "em"
        Kind.UNDERLINE -> "u"
        Kind.STRIKE -> "del"
        Kind.CODE -> "code"
        Kind.SUB -> "sub"
        Kind.SUP -> "sup"
    }
}

// ─── Merged from MarkdownParser.kt ──────────────────────────────────────────────────

/**
 * Lightweight markdown parser that converts raw markdown text into a list of block nodes.
 * Supports: headings, code blocks (fenced), blockquotes, bullet/numbered/task lists,
 * thematic breaks, tables, and paragraphs. Inline parsing is handled separately.
 */
object MarkdownParser {

    sealed class Block {
        data class Heading(val level: Int, val content: String) : Block()
        data class Paragraph(val content: String) : Block()
        data class CodeBlock(val language: String, val code: String) : Block()
        data class Blockquote(val blocks: List<Block>) : Block()
        data class BulletList(val items: List<ListItem>) : Block()
        data class NumberedList(val startNumber: Int, val items: List<ListItem>) : Block()
        data class Table(val headers: List<String>, val alignments: List<Alignment>, val rows: List<List<String>>) : Block()
        data object ThematicBreak : Block()

        /**
         * Display-mode LaTeX. Either extracted from `$$ … $$` / `\[ … \]`,
         * or promoted from a paragraph that contained nothing but a single
         * inline-math placeholder. mhchem (\ce{…}, \pu{…}) is supported via
         * the bundled assets/katex/mhchem.min.js extension.
         */
        data class MathBlock(val latex: String) : Block()

        /**
         * Media blocks extracted from `![alt](url)` when the line only contains
         * that one image-syntax node. Routing by file extension mirrors iOS
         * SelectableMarkdownView (nativeImageExts / nativeVideoExts / nativeAudioExts).
         */
        data class Image(val alt: String, val url: String) : Block()
        data class Video(val alt: String, val url: String) : Block()
        data class Audio(val alt: String, val url: String) : Block()
    }

    /**
     * A single extracted math expression, identified by a unique placeholder
     * inserted into the markdown stream before block-parsing. After the AST
     * is built, the placeholders are looked up by id and restored either as
     * MathBlock (display) or kept inline (rendered by the inline pass).
     */
    data class MathSpan(val placeholder: String, val latex: String, val isBlock: Boolean)

    /**
     * Holder shared with [MarkdownText] so the inline parser can also resolve
     * placeholders to their original LaTeX. Threaded through composition by
     * passing the whole list of spans alongside the parsed blocks.
     */
    data class ParseResult(val blocks: List<Block>, val mathSpans: List<MathSpan>)

    /** Object Replacement Character (U+FFFC) — sentinel used by math placeholders. */
    private const val ORC = '￼'

    private val nativeVideoExts = setOf("mp4", "mov", "m4v", "avi", "mkv", "webm")
    private val nativeAudioExts = setOf("mp3", "m4a", "wav", "aac", "ogg", "flac")

    /** Regex for a standalone `![alt](url)` node on its own line. */
    private val standaloneImageRegex = Regex("""^\s*!\[([^\]]*)\]\(([^)\s]+)\)\s*$""")

    // Block patterns are compiled once. Each Regex(...) built inside the
    // per-line loop compiled a fresh ICU pattern whose native memory waits for
    // a GC to be freed; parsing a long session that way piled up native
    // mappings until vm.max_map_count was hit and Scudo aborted (OpenMinis#426).
    private val thematicBreakRegex = Regex("^\\s{0,3}([-*_])\\s*\\1\\s*\\1(\\s*\\1)*\\s*$")
    private val headingRegex = Regex("^(#{1,6})\\s+(.+)$")
    private val bulletItemRegex = Regex("^\\s{0,3}[-*+]\\s+(.*)$")
    private val bulletMarkerRegex = Regex("^\\s{0,3}[-*+]\\s")
    private val bulletStartRegex = Regex("^\\s{0,3}[-*+]\\s+")
    // Marker digits capped at 2; keep the two numbered-list patterns in sync.
    private val numberedItemRegex = Regex("^\\s{0,3}(\\d{1,2})[.)\\s]\\s*(.*)$")
    private val numberedStartRegex = Regex("^\\s{0,3}\\d{1,2}[.)\\s]\\s")
    private val tableSeparatorCellRegex = Regex("^:?-+:?$")
    private val blankLineRegex = Regex("\\n[ \\t]*\\n")
    private val trailingNewlineRegex = Regex("\\n[ \\t]*$")
    private val mathPlaceholderRegex = Regex("$ORC" + "MATH(\\d+)" + "$ORC")

    /**
     * Classify a media URL by file extension. Extracts the extension from the
     * *last path segment only* so filenames that contain '#' or '?' (either
     * literal or percent-encoded) still get their real extension. Handles both
     * unencoded names (e.g. `foo#China.mp4`) and encoded ones (`foo%23China.mp4`).
     */
    private fun mediaBlockFor(alt: String, url: String): Block {
        val lastSeg = url.substringAfterLast('/')
        val decoded = runCatching { java.net.URLDecoder.decode(lastSeg, "UTF-8") }.getOrDefault(lastSeg)
        val ext = decoded.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in nativeVideoExts -> Block.Video(alt, url)
            in nativeAudioExts -> Block.Audio(alt, url)
            else -> Block.Image(alt, url)
        }
    }

    data class ListItem(val content: String, val checked: Boolean? = null)

    enum class Alignment { LEFT, CENTER, RIGHT }

    /**
     * Top-level entry: extract math, run the standard block parser on the
     * placeholder-substituted text, then run the math-restore pass that
     * promotes single-math paragraphs to MathBlock. Inline math placeholders
     * stay in the paragraph text so the inline pass can render them via
     * KaTeX. Mirrors iOS MarkdownMathExtractor.extract → cmark → restore.
     */
    fun parseWithMath(markdown: String): ParseResult {
        val (cleaned, spans) = extractMath(markdown)
        val rawBlocks = parse(cleaned)
        val restored = restoreMath(rawBlocks, spans)
        return ParseResult(restored, spans)
    }

    fun parse(markdown: String): List<Block> {
        android.util.Log.d("MdParser", "parse() len=${markdown.length} preview=${markdown.take(160).replace("\n","\\n")}")
        val lines = markdown.lines()
        val blocks = mutableListOf<Block>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            // Fenced code block
            if (line.trimStart().startsWith("```")) {
                val indent = line.indexOf('`')
                val fence = line.trimStart()
                val lang = fence.removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size) {
                    val cl = lines[i]
                    if (cl.trimStart().startsWith("```") && cl.trim() == "```") {
                        i++
                        break
                    }
                    codeLines.add(cl)
                    i++
                }
                blocks.add(Block.CodeBlock(lang, codeLines.joinToString("\n")))
                continue
            }

            // Thematic break
            if (line.matches(thematicBreakRegex)) {
                blocks.add(Block.ThematicBreak)
                i++
                continue
            }

            // ATX Heading
            val headingMatch = headingRegex.find(line)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                val content = headingMatch.groupValues[2].trimEnd().removeSuffix("#").trimEnd()
                blocks.add(Block.Heading(level, content))
                i++
                continue
            }

            // Blockquote
            if (line.trimStart().startsWith("> ") || line.trimStart() == ">") {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && (lines[i].trimStart().startsWith("> ") || lines[i].trimStart() == ">")) {
                    val ql = lines[i].trimStart()
                    quoteLines.add(if (ql == ">") "" else ql.removePrefix("> "))
                    i++
                }
                val inner = parse(quoteLines.joinToString("\n"))
                blocks.add(Block.Blockquote(inner))
                continue
            }

            // Table (must have at least header + separator)
            if (i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
                val table = parseTable(lines, i)
                if (table != null) {
                    blocks.add(table.first)
                    i = table.second
                    continue
                }
            }

            // Bullet list (-, *, +)
            val bulletMatch = bulletItemRegex.find(line)
            if (bulletMatch != null) {
                val items = mutableListOf<ListItem>()
                while (i < lines.size) {
                    val bm = bulletItemRegex.find(lines[i])
                    if (bm == null) break
                    val content = bm.groupValues[1]
                    items.add(parseListItem(content))
                    i++
                    // Collect continuation lines (indented)
                    while (i < lines.size && lines[i].startsWith("  ") && !bulletMarkerRegex.matches(lines[i])) {
                        items[items.lastIndex] = items.last().copy(
                            content = items.last().content + "\n" + lines[i].trimStart()
                        )
                        i++
                    }
                }
                blocks.add(Block.BulletList(items))
                continue
            }

            // Numbered list. Marker digits are capped at 2 so a paragraph
            // starting with a year or big number ("2020. 年的…") isn't
            // misparsed as ordered-list item #2020 (user report). Real lists
            // rarely exceed 99 items; CommonMark itself caps markers at 9
            // digits, we deliberately go tighter.
            val numMatch = numberedItemRegex.find(line)
            if (numMatch != null) {
                val startNum = numMatch.groupValues[1].toIntOrNull() ?: 1
                val items = mutableListOf<ListItem>()
                while (i < lines.size) {
                    val nm = numberedItemRegex.find(lines[i])
                    if (nm == null) break
                    items.add(ListItem(nm.groupValues[2]))
                    i++
                }
                blocks.add(Block.NumberedList(startNum, items))
                continue
            }

            // Standalone image / video / audio on its own line:
            //   ![alt](minis://workspace/clip.mp4)
            // Routed by extension so the media renderer can pick the right preview.
            val mediaMatch = standaloneImageRegex.find(line)
            if (mediaMatch != null) {
                val alt = mediaMatch.groupValues[1]
                val url = mediaMatch.groupValues[2]
                val blk = mediaBlockFor(alt, url)
                android.util.Log.d("MdParser", "media match: alt=\"$alt\" url=$url -> ${blk::class.simpleName}")
                blocks.add(blk)
                i++
                continue
            } else if (line.contains("![") && line.contains("](")) {
                android.util.Log.d("MdParser", "image-like line did NOT match standalone regex: ${line.take(160)}")
            }

            // Blank line — skip
            if (line.isBlank()) {
                i++
                continue
            }

            // Paragraph (collect contiguous non-blank lines that don't match other patterns)
            val paraLines = mutableListOf(line)
            i++
            while (i < lines.size) {
                val pl = lines[i]
                if (pl.isBlank() || pl.trimStart().startsWith("```") ||
                    pl.trimStart().startsWith("# ") || pl.trimStart().startsWith("> ") ||
                    bulletStartRegex.containsMatchIn(pl) ||
                    // Keep in sync with the numbered-list marker above (≤2 digits).
                    numberedStartRegex.containsMatchIn(pl) ||
                    pl.matches(thematicBreakRegex) ||
                    standaloneImageRegex.containsMatchIn(pl)
                ) break
                paraLines.add(pl)
                i++
            }
            blocks.add(Block.Paragraph(paraLines.joinToString("\n")))
        }

        return blocks
    }

    private fun parseListItem(content: String): ListItem {
        // Task list: [x] or [ ]
        if (content.startsWith("[x] ") || content.startsWith("[X] ")) {
            return ListItem(content.substring(4), checked = true)
        }
        if (content.startsWith("[ ] ")) {
            return ListItem(content.substring(4), checked = false)
        }
        return ListItem(content)
    }

    private fun isTableSeparator(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.contains('-')) return false
        val cells = trimmed.split('|').filter { it.isNotBlank() }
        return cells.all { it.trim().matches(tableSeparatorCellRegex) }
    }

    private fun parseTable(lines: List<String>, startIdx: Int): Pair<Block.Table, Int>? {
        val headerLine = lines[startIdx]
        val separatorLine = lines[startIdx + 1]

        val headers = splitTableRow(headerLine)
        val sepCells = splitTableRow(separatorLine)
        if (headers.isEmpty() || sepCells.isEmpty()) return null

        val alignments = sepCells.map { cell ->
            val trimmed = cell.trim()
            when {
                trimmed.startsWith(':') && trimmed.endsWith(':') -> Alignment.CENTER
                trimmed.endsWith(':') -> Alignment.RIGHT
                else -> Alignment.LEFT
            }
        }

        var i = startIdx + 2
        val rows = mutableListOf<List<String>>()
        while (i < lines.size) {
            val row = lines[i]
            if (row.isBlank() || !row.contains('|')) break
            rows.add(splitTableRow(row))
            i++
        }

        return Block.Table(headers, alignments, rows) to i
    }

    private fun splitTableRow(line: String): List<String> {
        val trimmed = line.trim().removePrefix("|").removeSuffix("|")
        // [T-minis-url-fullwidth-pipe-android] Fast path: the only reason to
        // protect a `|` from the cell split is a markdown link whose URL
        // contains a raw ASCII pipe, e.g. `[f](minis://ns/a|b.png)` — the model
        // (or a hand-authored row) can emit an un-encoded `|` inside the URL,
        // and a naive `split('|')` would chop the link in two. App-generated
        // minis:// URLs already percent-encode `|` to `%7C`, so this is purely
        // defence-in-depth. Rows WITHOUT a `](` link keep the original exact
        // `split('|')` behaviour verbatim — important because the paren-aware
        // walk below would otherwise swallow a `|` after an unbalanced `(` in a
        // plain text cell (e.g. `| a (note | b |`). Fullwidth `｜` (U+FF5C) is
        // never split by either path since `split('|')` matches only U+007C.
        if (!trimmed.contains("](")) {
            return trimmed.split('|').map { it.trim() }
        }
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (ch in trimmed) {
            if (ch == '(' && depth == 0) depth++
            else if (ch == ')' && depth > 0) depth--
            if (ch == '|' && depth == 0) {
                cells.add(current.toString().trim())
                current.clear()
            } else {
                current.append(ch)
            }
        }
        cells.add(current.toString().trim())
        return cells
    }

    // ─── Math extraction ──────────────────────────────────────────────────────
    //
    // Walks the source character-by-character, swallowing four math delimiters:
    //
    //   $$ … $$    display math
    //   \[ … \]    display math (LaTeX-style)
    //   $ … $      inline math
    //   \( … \)    inline math (LaTeX-style)
    //
    // Fenced code blocks and inline code spans are skipped verbatim so the
    // delimiters inside them survive (chat transcripts often paste raw
    // markdown source). Each match is replaced with a sentinel like
    // ￼ MATH<n> ￼ so the placeholder cannot collide with normal
    // Markdown syntax (￼ = Object Replacement Character, never appears
    // in user prose). Mirrors iOS MarkdownMathExtractor.extract verbatim.

    private fun extractMath(markdown: String): Pair<String, List<MathSpan>> {
        val spans = mutableListOf<MathSpan>()
        val out = StringBuilder()
        val chars = markdown
        val n = chars.length
        var i = 0

        var inFence = false
        var fenceChar = '`'
        var fenceLen = 0

        // [T-android-latex-code-mask] Precomputed once: which indices sit
        // inside a fence or inline code, so a closing `$`/`$$` can never be
        // matched across a code boundary (issue #117 defect 3).
        val codeMask = buildCodeMask(chars)

        fun atLineStart(idx: Int): Boolean = idx == 0 || chars[idx - 1] == '\n' || chars[idx - 1] == '\r'

        while (i < n) {
            // Detect opening fence at line start
            if (!inFence && atLineStart(i)) {
                val c = chars[i]
                if (c == '`' || c == '~') {
                    var fl = 0
                    var j = i
                    while (j < n && chars[j] == c) { fl++; j++ }
                    if (fl >= 3) {
                        inFence = true
                        fenceChar = c
                        fenceLen = fl
                        while (i < n && chars[i] != '\n') { out.append(chars[i]); i++ }
                        if (i < n) { out.append(chars[i]); i++ }
                        continue
                    }
                }
            }

            if (inFence) {
                if (atLineStart(i) && chars[i] == fenceChar) {
                    var fl = 0
                    var j = i
                    while (j < n && chars[j] == fenceChar) { fl++; j++ }
                    if (fl >= fenceLen) {
                        inFence = false
                        while (i < n && chars[i] != '\n') { out.append(chars[i]); i++ }
                        if (i < n) { out.append(chars[i]); i++ }
                        continue
                    }
                }
                out.append(chars[i]); i++
                continue
            }

            // Inline code spans — copy verbatim, including the dollar signs inside.
            if (chars[i] == '`') {
                var run = 0
                var j = i
                while (j < n && chars[j] == '`') { run++; j++ }
                var k = j
                var found = false
                while (k <= n - run) {
                    var match = 0
                    while (k + match < n && chars[k + match] == '`') match++
                    if (match == run) {
                        for (idx in i until k + match) out.append(chars[idx])
                        i = k + match
                        found = true
                        break
                    }
                    k += if (match > 0) match else 1
                }
                if (found) continue
                for (idx in i until j) out.append(chars[idx])
                i = j
                continue
            }

            // \$ — preserve escaped dollar verbatim.
            if (chars[i] == '\\' && i + 1 < n && chars[i + 1] == '$') {
                out.append(chars[i]); out.append(chars[i + 1])
                i += 2
                continue
            }

            // \[ … \] — display math.
            if (chars[i] == '\\' && i + 1 < n && chars[i + 1] == '[') {
                val end = findClose(chars, i + 2, "\\]")
                if (end != null) {
                    val latex = stripBlockquoteMarkers(chars.substring(i + 2, end))
                    spans.add(MathSpan(makePlaceholder(spans.size), latex, true))
                    out.append(spans.last().placeholder)
                    i = end + 2
                    continue
                }
            }

            // \( … \) — inline math.
            if (chars[i] == '\\' && i + 1 < n && chars[i + 1] == '(') {
                val end = findClose(chars, i + 2, "\\)")
                if (end != null) {
                    val latex = chars.substring(i + 2, end)
                    spans.add(MathSpan(makePlaceholder(spans.size), latex, false))
                    out.append(spans.last().placeholder)
                    i = end + 2
                    continue
                }
            }

            // $$ … $$ — display math.
            if (chars[i] == '$' && i + 1 < n && chars[i + 1] == '$') {
                val end = findDoubleDollar(chars, i + 2, codeMask)
                // [T-android-latex-code-mask] The closer must be outside code
                // (codeMask, above) AND a multi-line body must still look like
                // a formula — otherwise an unclosed `$$` swallows prose.
                if (end != null && isPlausibleDisplayBody(chars.substring(i + 2, end))) {
                    val latex = stripBlockquoteMarkers(chars.substring(i + 2, end))
                    spans.add(MathSpan(makePlaceholder(spans.size), latex, true))
                    out.append(spans.last().placeholder)
                    i = end + 2
                    continue
                }
            }

            // $ … $ — inline math (with currency-skip heuristic).
            // [T-math-dollar-rules] `${NAME}` and `$NAME` are shell variables,
            // not openers (rules 2 and 3 above `variableNameLength`).
            if (chars[i] == '$' && i + 1 < n && chars[i + 1] != '$' && chars[i + 1] != ' ' &&
                !opensVariable(chars, i)
            ) {
                val end = findSingleDollar(chars, i + 1, codeMask)
                if (end != null) {
                    val latex = chars.substring(i + 1, end)
                    // [T-android-table-cell-katex-false-positive] Also skip a
                    // span that is really a table-cell artifact (iOS 5b1d72515).
                    if (looksLikeMath(latex) && !isTablePipeArtifact(latex)) {
                        spans.add(MathSpan(makePlaceholder(spans.size), latex, false))
                        out.append(spans.last().placeholder)
                        i = end + 1
                        continue
                    }
                }
            }

            out.append(chars[i])
            i++
        }

        return out.toString() to spans
    }

    private fun makePlaceholder(idx: Int): String = "${ORC}MATH$idx$ORC"

    /**
     * Strip blockquote markers from a multi-line math body. Math is extracted
     * before block-level parsing, so a display formula written inside a
     * blockquote drags the continuation lines' `> ` markers into the LaTeX:
     *
     *     > $$
     *     > E = mc^2
     *     > $$
     *
     * used to yield `"\n> E = mc^2\n> "` and render a literal `>`. Only strip
     * when *every* non-blank line after the first carries a `^ {0,3}> ?`
     * marker — that proves the span really sits inside a quote, and protects
     * legitimate formula content whose lines begin with `>` (comparisons,
     * matrix rows). Mirrors iOS MarkdownMathExtractor.stripBlockquoteMarkers.
     */
    private fun stripBlockquoteMarkers(latex: String): String {
        if (!latex.contains('\n')) return latex

        // The opening `$$` / `\[` is consumed by the caller, so the first
        // segment is the tail of the marker line and never carries a marker
        // of its own — validate and strip only lines 2…n.
        val lines = latex.split("\n").toMutableList()
        var sawMarker = false
        for (idx in 1 until lines.size) {
            val line = lines[idx]
            var cursor = 0
            while (cursor < line.length && line[cursor] == ' ' && cursor < 3) cursor++
            if (cursor >= line.length) continue // blank line: neutral
            if (line[cursor] != '>') return latex // a bare line — not a quote
            sawMarker = true
            cursor++
            if (cursor < line.length && line[cursor] == ' ') cursor++
            lines[idx] = line.substring(cursor)
        }
        if (!sawMarker) return latex
        return lines.joinToString("\n")
    }

    private fun findClose(chars: String, from: Int, close: String): Int? {
        val n = chars.length
        var i = from
        while (i <= n - close.length) {
            var match = true
            for (j in close.indices) {
                if (chars[i + j] != close[j]) { match = false; break }
            }
            if (match) return i
            i++
        }
        return null
    }

    /**
     * [T-android-latex-code-mask] (issue #117 defect 3, iOS bce7e2ed)
     *
     * The main [extractMath] loop skips fenced blocks and inline code when
     * deciding where a formula may *open*, but the closing-delimiter searches
     * were blind forward scans. So one bare `$$` in prose — a model that
     * forgot to close it, or text merely *explaining* LaTeX — paired with a
     * `$$` inside a later ``` fence and swallowed every paragraph between
     * them, including the fence's own opening line. The renderer then saw the
     * orphaned closing ``` as a NEW fence and turned the remainder into a code
     * block: the "a whole section suddenly disappeared" symptom.
     *
     * This precomputes, once per parse, which indices sit inside fenced blocks
     * or inline code, mirroring the main loop's rules. A closer inside the mask
     * is rejected.
     */
    private fun buildCodeMask(chars: String): BooleanArray {
        val n = chars.length
        val mask = BooleanArray(n)
        var i = 0
        var inFence = false
        var fenceChar = '`'
        var fenceLen = 0

        fun atLineStart(idx: Int): Boolean =
            idx == 0 || chars[idx - 1] == '\n' || chars[idx - 1] == '\r'

        while (i < n) {
            if (!inFence && atLineStart(i)) {
                val c = chars[i]
                if (c == '`' || c == '~') {
                    var fl = 0
                    var j = i
                    while (j < n && chars[j] == c) { fl++; j++ }
                    if (fl >= 3) {
                        inFence = true; fenceChar = c; fenceLen = fl
                        while (i < n && chars[i] != '\n') { mask[i] = true; i++ }
                        if (i < n) { mask[i] = true; i++ }
                        continue
                    }
                }
            }
            if (inFence) {
                if (atLineStart(i) && chars[i] == fenceChar) {
                    var fl = 0
                    var j = i
                    while (j < n && chars[j] == fenceChar) { fl++; j++ }
                    if (fl >= fenceLen) {
                        inFence = false
                        while (i < n && chars[i] != '\n') { mask[i] = true; i++ }
                        if (i < n) { mask[i] = true; i++ }
                        continue
                    }
                }
                mask[i] = true; i++
                continue
            }
            // Inline code span: `…` / ``…`` — same run-matching rule the main
            // loop uses when it copies these verbatim.
            if (chars[i] == '`') {
                var run = 0
                var j = i
                while (j < n && chars[j] == '`') { run++; j++ }
                var k = j
                var closed = -1
                while (k < n) {
                    if (chars[k] == '`') {
                        var r2 = 0
                        var m = k
                        while (m < n && chars[m] == '`') { r2++; m++ }
                        if (r2 == run) { closed = m; break }
                        k = m
                    } else k++
                }
                val end = if (closed > 0) closed else j
                for (idx in i until end) mask[idx] = true
                i = end
                continue
            }
            i++
        }
        return mask
    }

    /**
     * [T-android-latex-code-mask] A multi-line `$$` body must still look like a
     * formula, so a genuinely unclosed `$$` cannot eat prose even when the
     * closer is outside any code span. Single-line `$$…$$` is accepted
     * unconditionally, leaving ordinary display math untouched. Mirrors iOS.
     */
    private fun isPlausibleDisplayBody(body: String): Boolean {
        if (!body.contains('\n')) return true
        // A blank line means a paragraph break — prose, not one formula.
        if (blankLineRegex.containsMatchIn(body)) return false
        // The conventional block shape puts the closing `$$` alone on its own
        // line, i.e. the body ends with a newline (plus optional indent). That
        // is a strong enough signal on its own — requiring a LaTeX glyph here
        // too would wrongly demote glyph-free but valid math such as
        // "$$\n1 + 2 = 3\n$$" to plain text.
        if (trailingNewlineRegex.containsMatchIn(body)) return true
        // Otherwise the closer is mid-line, which is the shape a stray
        // delimiter in prose produces — require a LaTeX-ish glyph.
        return body.any { it == '\\' || it == '^' || it == '_' || it == '{' || it == '}' }
    }

    private fun findDoubleDollar(chars: String, from: Int, codeMask: BooleanArray?): Int? {
        val n = chars.length
        var i = from
        while (i < n - 1) {
            if (chars[i] == '$' && chars[i + 1] == '$' &&
                (codeMask == null || !codeMask[i])
            ) return i
            i++
        }
        return null
    }

    private fun findSingleDollar(chars: String, from: Int, codeMask: BooleanArray?): Int? {
        val n = chars.length
        var i = from
        while (i < n) {
            if (chars[i] == '\\' && i + 1 < n) { i += 2; continue }
            val plainDollar = chars[i] == '$' && (codeMask == null || !codeMask[i])
            // [T-math-dollar-rules] Rule 1: the dollar of an amount or of a
            // variable starts that, it does not end a formula
            // (`$5-$10`, `$HOME/bin:$PATH`).
            if (plainDollar && startsAmountOrVariable(chars, i)) return null
            if (plainDollar && (i == 0 || chars[i - 1] != ' ')) return i
            // Rule 4: after a blank it opens the next thing. What began with a
            // digit ends there, as the amount it was: in "$10 and $x+1$" the
            // formula is x+1.
            if (plainDollar && from < n && isAsciiDigit(chars[from])) return null
            if (chars[i] == '\n') return null
            i++
        }
        return null
    }

    // ─── Amounts and shell variables ─────────────────────────────────────────
    //
    // [T-math-dollar-rules] Port of iOS 216160978 + 23635b48e (the Windows
    // port has the same rules in minis-markdown/src/math.rs). All three run
    // one list of test vectors: DollarMathRulesTest here,
    // MathExtractorDollarRulesTests.swift on iOS, DOLLAR_VECTORS on Windows.
    //
    // A dollar is also money and a shell's variable mark: "$5-$10" lost both
    // dollars and became the italic formula "5-", and PATH=$HOME/bin:$PATH
    // lost its dollars the same way, because the closer was simply the first
    // `$` not after a blank. Four rules keep those out of the formulas;
    // everything else is the extraction as it was.
    //  1. A `$` followed by a digit (`$10`), by `{NAME}` or by an upper-case
    //     name (`$HOME`) never *closes* a formula - it begins something.
    //  2. `${NAME}` never opens a formula.
    //  3. `$NAME` (upper case, two characters or more) does not open one when
    //     the name is followed by what follows a variable - the end of the
    //     line, one of `/ : ; , . " ' ) ]`, or a blank and then a word. It
    //     still does when a formula goes on: `$AB$`, `$PV = nRT$`,
    //     `$AB \parallel CD$`.
    //  4. What opens with `$` and a digit (`$2x+1=5$` is a formula, `$10` an
    //     amount) does not reach past a later `$` that stands after a blank.
    // A `$` followed by a digit may still open a formula: an amount on its own
    // was never one ([looksLikeMath]). Known, accepted losses: `$AB/CD$` and
    // `$AB:CD$` read as variables.

    private fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'
    private fun isAsciiUpper(c: Char): Boolean = c in 'A'..'Z'
    private fun isAsciiLower(c: Char): Boolean = c in 'a'..'z'

    /**
     * The length of an upper-case variable name at [from], 0 when there is
     * none: two or more of `A-Z 0-9` starting with a letter, then any number
     * of `_WORD` parts that each start with a letter (`HOME`, `MY_VAR`,
     * `XDG_CONFIG_HOME`), with no lower-case letter after it (`$NaCl`).
     *
     * An underscore is also a subscript, so the name stops short of one that
     * is followed by a digit, and a single letter is no name: `$X_1, X_2$`,
     * `$H_0: \mu = 0$`, `$P_1 V_1 = P_2 V_2$` are formulas (iOS 23635b48e: the
     * first version counted `_` and digits into the name).
     */
    private fun variableNameLength(chars: String, from: Int): Int {
        val n = chars.length
        fun upper(i: Int) = i < n && isAsciiUpper(chars[i])
        fun part(i: Int) = i < n && (isAsciiUpper(chars[i]) || isAsciiDigit(chars[i]))
        if (!upper(from)) return 0
        var len = 1
        while (part(from + len)) len++
        if (len < 2) return 0
        while (from + len < n && chars[from + len] == '_' && upper(from + len + 1)) {
            len += 2
            while (part(from + len)) len++
        }
        // What follows continues the word (`NaCl`) or is a subscript (`AB_1`):
        // a formula's, not a variable's.
        val continued = from + len < n && isAsciiLower(chars[from + len])
        val subscripted = from + len < n && chars[from + len] == '_'
        return if (continued || subscripted) 0 else len
    }

    /**
     * `{NAME}` at [from], any case: `[A-Za-z_][A-Za-z0-9_]*` in braces - and
     * not followed by `^` or `_`, which make it a formula's group
     * (`${a}^{2}+{b}^{2}$`, `${x}^2$`).
     */
    private fun isBracedVariable(chars: String, from: Int): Boolean {
        val n = chars.length
        if (from >= n || chars[from] != '{') return false
        var name = 0
        while (from + 1 + name < n) {
            val c = chars[from + 1 + name]
            if (!(c in 'a'..'z' || isAsciiUpper(c) || isAsciiDigit(c) || c == '_')) break
            name++
        }
        if (name == 0 || isAsciiDigit(chars[from + 1])) return false
        if (from + 1 + name >= n || chars[from + 1 + name] != '}') return false
        val after = from + 2 + name
        return !(after < n && (chars[after] == '^' || chars[after] == '_'))
    }

    /** Rule 1: the `$` at [at] begins an amount or a variable. */
    private fun startsAmountOrVariable(chars: String, at: Int): Boolean {
        if (at + 1 < chars.length && isAsciiDigit(chars[at + 1])) return true
        return isBracedVariable(chars, at + 1) || variableNameLength(chars, at + 1) > 0
    }

    /** Rules 2 and 3: the `$` at [at] is a variable's, not a formula's opener. */
    private fun opensVariable(chars: String, at: Int): Boolean {
        val n = chars.length
        if (isBracedVariable(chars, at + 1)) return true
        val len = variableNameLength(chars, at + 1)
        if (len == 0) return false
        val after = at + 1 + len
        if (after >= n) return true
        val c = chars[after]
        if (c == '\n' || c == '\r') return true
        if (c in "/:;,.\"')]") return true
        if (c == ' ' || c == '\t') {
            var k = after
            while (k < n && (chars[k] == ' ' || chars[k] == '\t')) k++
            if (k >= n) return true
            return chars[k].isLetterOrDigit() || chars[k] == '\n' || chars[k] == '\r'
        }
        return false
    }

    /**
     * Currency-skip heuristic: $5 / $10.99 should stay as plain text. Treat
     * the `$ … $` content as math only if it has a math-ish character
     * (`\\^_{}` / common big-symbol unicode) or is longer than 2 chars (so
     * `x+y` qualifies but bare digits like `5` do not). Mirrors iOS.
     */
    private fun looksLikeMath(content: String): Boolean {
        if (content.isEmpty()) return false
        // T208 Layer B: previous heuristic rejected single-letter math like
        // `$c$` or `$i$` — `length > 2` returned false and the literal
        // dollar-wrapped text leaked into the rendered output. Any of:
        //   - obvious LaTeX glyphs (`\`, `^`, `_`, `{`, `}`, integrals…)
        //   - short alphanumeric content with no whitespace and no leading
        //     digit (currency like `$5` filtered out by the leading-digit
        //     check; `$5.99` further filtered by the dot rule below)
        // qualifies as math. Mirrors iOS MarkdownMathExtractor.looksLikeMath.
        val mathChars = charArrayOf('\\', '^', '_', '{', '}', '∫', '∑', '∏', '√', '+', '-', '=', '<', '>')
        for (c in content) {
            if (c in mathChars) return true
        }
        // Currency heuristic: `$5`, `$5.99`, `$1,000` look numeric. Skip.
        if (content[0].isDigit()) return false
        // Whitespace at either edge usually means a stray dollar, not math.
        if (content.first().isWhitespace() || content.last().isWhitespace()) return false
        // Short ASCII identifier-ish content (`$c$`, `$x$`, `$pi$`) up to
        // 30 chars is almost certainly a variable reference.
        if (content.length <= 30 && content.all { it.isLetterOrDigit() }) return true
        return content.length > 2
    }

    /**
     * [T-android-table-cell-katex-false-positive] True when an inline `$ … $`
     * candidate is really a markdown table-cell artifact — a `$` that paired
     * across `|` column separators — rather than a formula. Port of iOS
     * `isTablePipeArtifact` (5b1d72515); Windows has the same rule
     * (`is_table_pipe_artifact`, minis-markdown/src/math.rs).
     *
     * Math is extracted from the raw markdown before table structure is known,
     * so a row like `| a | $x-1 | y$ |` paired the two dollars and rendered
     * "x-1 | y" as one serif-italic formula across two cells. Two signals,
     * both of which a real formula avoids:
     *  1. An unescaped pipe with whitespace on a side — the column separator
     *     is written ` | ` / `| ` / ` |`; real LaTeX keeps bars flush.
     *  2. An ODD number of unescaped pipes — absolute-value / norm bars come in
     *     pairs (`|x|`, `\frac{|a|}{|b|}`), so `n+1|` is a stray separator.
     * Escaped `\|` (a LaTeX norm) is never counted.
     */
    private fun isTablePipeArtifact(content: String): Boolean {
        var bare = 0
        for (idx in content.indices) {
            if (content[idx] != '|') continue
            if (idx > 0 && content[idx - 1] == '\\') continue // escaped norm bar
            bare++
            val prevIsSpace = idx > 0 && content[idx - 1].isWhitespace()
            val nextIsSpace = idx + 1 < content.length && content[idx + 1].isWhitespace()
            if (prevIsSpace || nextIsSpace) return true
        }
        return bare % 2 == 1
    }

    /**
     * Walk the AST replacing math placeholders. Display-math placeholders
     * promote (or replace) their containing paragraph to a [Block.MathBlock];
     * inline placeholders remain in the paragraph text so the inline parser
     * can render them by looking up the placeholder in the [MathSpan] list.
     */
    private fun restoreMath(blocks: List<Block>, spans: List<MathSpan>): List<Block> {
        if (spans.isEmpty()) return blocks
        val byPlaceholder = spans.associateBy { it.placeholder }
        return blocks.flatMap { restoreBlock(it, byPlaceholder) }
    }

    private fun restoreBlock(block: Block, map: Map<String, MathSpan>): List<Block> {
        return when (block) {
            is Block.Paragraph -> restoreParagraph(block.content, map)
            is Block.Heading -> listOf(block)  // headings keep their inline placeholders
            is Block.Blockquote -> listOf(Block.Blockquote(block.blocks.flatMap { restoreBlock(it, map) }))
            is Block.BulletList,
            is Block.NumberedList,
            is Block.Table,
            is Block.CodeBlock,
            is Block.MathBlock,
            is Block.ThematicBreak,
            is Block.Image,
            is Block.Video,
            is Block.Audio -> listOf(block)
        }
    }

    /**
     * Splits a paragraph that contains math placeholders. Each display-math
     * placeholder breaks the paragraph and emits a [Block.MathBlock] of its
     * own; inline placeholders stay embedded so the inline parser can render
     * them through KaTeX. A paragraph that contained nothing but a single
     * display-math placeholder collapses to one MathBlock with no surrounding
     * Paragraph stub.
     */
    private fun restoreParagraph(content: String, map: Map<String, MathSpan>): List<Block> {
        if (!content.contains(ORC)) return listOf(Block.Paragraph(content))

        val out = mutableListOf<Block>()
        val buf = StringBuilder()
        val regex = mathPlaceholderRegex
        var lastEnd = 0
        for (match in regex.findAll(content)) {
            val full = match.value
            val span = map[full] ?: continue
            // Append text before this placeholder.
            if (match.range.first > lastEnd) {
                buf.append(content, lastEnd, match.range.first)
            }
            if (span.isBlock) {
                // Flush any accumulated text as a paragraph, then emit the math block.
                if (buf.isNotBlank()) {
                    out.add(Block.Paragraph(buf.toString().trimEnd()))
                }
                buf.clear()
                out.add(Block.MathBlock(span.latex))
            } else {
                // Keep inline placeholder in-stream — inline pass will render it.
                buf.append(full)
            }
            lastEnd = match.range.last + 1
        }
        if (lastEnd < content.length) buf.append(content, lastEnd, content.length)
        if (buf.isNotBlank()) out.add(Block.Paragraph(buf.toString().trimEnd()))
        // Empty paragraph (only whitespace remained) — drop entirely.
        return if (out.isEmpty()) listOf(Block.Paragraph(content)) else out
    }
}

// ─── Merged from SyntaxHighlighter.kt ──────────────────────────────────────────────────

/**
 * Lightweight syntax highlighter for code blocks.
 * Applies keyword/string/comment/number coloring for common languages.
 */
object SyntaxHighlighter {

    private val keywordColor = Color(0xFF569CD6)     // blue
    private val stringColor = Color(0xFFCE9178)       // orange
    private val commentColor = Color(0xFF6A9955)      // green
    private val numberColor = Color(0xFFB5CEA8)       // light green
    private val typeColor = Color(0xFF4EC9B0)         // teal
    private val defaultColor = Color(0xFFD4D4D4)      // light gray

    private val commonKeywords = setOf(
        "if", "else", "for", "while", "return", "break", "continue", "switch", "case",
        "default", "try", "catch", "finally", "throw", "new", "delete", "typeof", "instanceof",
        "class", "interface", "enum", "struct", "func", "fun", "fn", "def", "async", "await",
        "import", "export", "from", "package", "module", "use", "pub", "private", "public",
        "protected", "static", "final", "const", "let", "var", "val", "mut",
        "true", "false", "null", "nil", "None", "self", "this", "super",
        "in", "is", "as", "not", "and", "or", "with", "yield", "lambda",
        "override", "abstract", "sealed", "data", "object", "companion",
        "suspend", "inline", "when", "where", "guard", "defer", "do",
    )

    private val typeKeywords = setOf(
        "int", "float", "double", "string", "bool", "boolean", "void", "char", "byte",
        "long", "short", "String", "Int", "Float", "Double", "Bool", "Boolean",
        "List", "Map", "Set", "Array", "HashMap", "ArrayList", "Optional",
    )

    // Token regex: strings, comments, numbers, words
    private val TOKEN_REGEX = Regex(
        """(//[^\n]*|#[^\n]*|/\*[\s\S]*?\*/)|("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`)|(\b\d+\.?\d*\b)|(\b[a-zA-Z_]\w*\b)|([^\s\w]+|\s+)"""
    )

    fun highlight(code: String, language: String): AnnotatedString = buildAnnotatedString {
        if (language.isEmpty()) {
            pushStyle(SpanStyle(color = defaultColor))
            append(code)
            pop()
            return@buildAnnotatedString
        }

        for (match in TOKEN_REGEX.findAll(code)) {
            val comment = match.groups[1]?.value
            val string = match.groups[2]?.value
            val number = match.groups[3]?.value
            val word = match.groups[4]?.value
            val other = match.groups[5]?.value

            when {
                comment != null -> {
                    pushStyle(SpanStyle(color = commentColor))
                    append(comment)
                    pop()
                }
                string != null -> {
                    pushStyle(SpanStyle(color = stringColor))
                    append(string)
                    pop()
                }
                number != null -> {
                    pushStyle(SpanStyle(color = numberColor))
                    append(number)
                    pop()
                }
                word != null -> {
                    val color = when {
                        word in commonKeywords -> keywordColor
                        word in typeKeywords -> typeColor
                        word.first().isUpperCase() -> typeColor
                        else -> defaultColor
                    }
                    pushStyle(SpanStyle(color = color))
                    append(word)
                    pop()
                }
                other != null -> {
                    pushStyle(SpanStyle(color = defaultColor))
                    append(other)
                    pop()
                }
            }
        }
    }
}
