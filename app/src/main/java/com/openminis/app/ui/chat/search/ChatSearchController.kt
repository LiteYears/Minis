package com.openminis.app.ui.chat.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.ChatSearchPiece
import com.openminis.app.ui.chat.FlatChatItem
import com.openminis.app.ui.chat.chatSearchMarkdownPieces
import com.openminis.app.ui.chat.isModelSwitchNotice
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What a highlighting Text needs: the query, how many matches each unit has,
 * and which one is current. One immutable value, so moving the current match
 * recomposes only the readers whose unit gained or lost the current match.
 */
@Stable
internal data class ChatSearchHighlight(
    val query: String,
    val countByUnit: Map<ChatSearchUnitKey, Int>,
    val current: ChatSearchMatch?,
) {
    fun count(key: ChatSearchUnitKey): Int = countByUnit[key] ?: 0

    /** The current ordinal in [key], or -1. */
    fun currentOrdinal(key: ChatSearchUnitKey): Int = if (current?.key == key) current.ordinal else -1

    /** True when any unit of [rowKey] holds the current match. */
    fun rowIsCurrent(rowKey: String): Boolean = current?.key?.rowKey == rowKey

    companion object {
        val NONE = ChatSearchHighlight("", emptyMap(), null)
    }
}

/**
 * [T-in-chat-search] State of "Search in Chat" for one open conversation: the
 * query, the results, the current one, and the work that keeps them fresh.
 * Port of iOS ChatSearchController (62b421d0d).
 *
 * Held by ChatScreen (remember(sessionId)), not by ChatViewModel: typing a
 * query or stepping through results must redraw the search bar and a couple
 * of highlighted texts, not the chat page.
 *
 * Flow:
 *   query edit --250 ms--> units from the rows (off-main, cached) --> search
 *     --> newest match becomes current, [navigations] asks the list to scroll
 *   next / previous --> move current (wrapping, with a notice), scroll
 *   rows change while open --300 ms throttle--> re-search; the current match
 *     is kept by identity, nothing scrolls
 *   done --> highlights cleared, scroll position left where it is
 */
@Stable
internal class ChatSearchController(
    private val scope: CoroutineScope,
    private val source: ChatSearchUnits.TextSource,
    /** Where units are built and searched; injectable for tests. */
    private val background: CoroutineDispatcher = Dispatchers.Default,
) {
    enum class Notice { WRAPPED_TO_FIRST, WRAPPED_TO_LAST }

    var isActive by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var matchCount by mutableIntStateOf(0)
        private set
    var isTruncated by mutableStateOf(false)
        private set
    var currentIndex by mutableStateOf<Int?>(null)
        private set
    /** A search for the CURRENT query has not come back yet. */
    var isSearching by mutableStateOf(false)
        private set
    /** Short-lived "wrapped round" message. */
    var notice by mutableStateOf<Notice?>(null)
        private set
    /** Bumped to ask the search field to take focus. */
    var focusRequest by mutableIntStateOf(0)
        private set
    var highlight by mutableStateOf(ChatSearchHighlight.NONE)
        private set

    private var matches: List<ChatSearchMatch> = emptyList()

    /** The current match each time the list should scroll to it. */
    private val _navigations = MutableSharedFlow<ChatSearchMatch>(extraBufferCapacity = 4)
    val navigations: SharedFlow<ChatSearchMatch> = _navigations

    val currentMatch: ChatSearchMatch? get() = currentIndex?.let { matches.getOrNull(it) }
    val counterText: String get() = ChatSearchNavigation.counterText(currentIndex, matchCount, isTruncated)

    /** The row [key] in the rows last published (before folding), or null. */
    fun rowFor(key: String): FlatChatItem? = rows.firstOrNull { it.key == key }

    /** The rows (before tool folding) as last published by the list pipeline. Main thread. */
    private var rows: List<FlatChatItem> = emptyList()
    private var searchedSignature: Int? = null
    private var pieceCache: Map<String, ChatSearchUnits.PieceCacheEntry> = emptyMap()

    private var pending: Job? = null
    private var searchJob: Job? = null
    private var refreshJob: Job? = null
    private var noticeJob: Job? = null
    private var generation = 0

    // ── Open / close ──────────────────────────────────────────────────────

    /**
     * Shows the bar. A non-blank [prefill] (the text selected when the user
     * chose "Search in Chat") becomes the query and is searched at once;
     * otherwise the field takes focus with the previous query kept.
     */
    fun activate(prefill: String? = null) {
        val trimmed = prefill?.let { ChatSearchMatcher.normalizedQuery(it) }.orEmpty()
        if (!isActive) {
            isActive = true
            AppLogger.info(TAG, "[ChatSearch] open prefill=${trimmed.length} chars")
        }
        focusRequest++
        if (trimmed.isNotEmpty() && trimmed != query) {
            query = trimmed
            schedule(0, landOnNewest = true)
        }
    }

    /** Closes the bar: highlights go, the scroll position stays. */
    fun deactivate() {
        if (!isActive) return
        AppLogger.info(TAG, "[ChatSearch] close query=${query.length} chars results=${matches.size}")
        isActive = false
        cancelAll()
        query = ""
        apply(ChatSearchResult.EMPTY, "", landOnNewest = false)
        notice = null
    }

    fun updateQuery(value: String) {
        if (value == query) return
        query = value
        if (isActive) schedule(TYPING_DEBOUNCE_MS, landOnNewest = true)
    }

    // ── Moving between results ────────────────────────────────────────────

    /** Toward newer messages (down the list); wraps to the first. */
    fun next() = step(forward = true)

    /** Toward older messages (up the list); wraps to the last. */
    fun previous() = step(forward = false)

    private fun step(forward: Boolean) {
        val moved = ChatSearchNavigation.step(currentIndex, matches.size, forward) ?: return
        currentIndex = moved.index
        highlight = highlight.copy(current = currentMatch)
        if (moved.wrapped) showNotice(if (forward) Notice.WRAPPED_TO_FIRST else Notice.WRAPPED_TO_LAST)
        navigateToCurrent()
    }

    // ── Rows from the list pipeline ───────────────────────────────────────

    /**
     * The list pipeline's rows, before tool runs are folded. While the bar
     * is open, a change re-runs the search at most every 300 ms; the current
     * match is kept by identity and the list is not scrolled, so streaming
     * never moves what the user is reading.
     */
    fun onRows(newRows: List<FlatChatItem>) {
        // [T-in-chat-search-refresh] Only the rows are kept here: this runs on
        // the main thread on every flatten tick (~80 ms while streaming), also
        // when the bar was never opened, so the O(rows) signature is taken
        // only when a refresh is about to decide.
        rows = newRows
        if (!isActive || ChatSearchMatcher.normalizedQuery(query).isEmpty()) return
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            delay(STREAMING_THROTTLE_MS)
            // [T-in-chat-search-refresh] A refresh never cancels a search in
            // flight: on a long chat one search can outlast the 300 ms
            // throttle, and cancelling it from every refresh meant no result
            // landed for the whole streamed turn while the CPU redid the
            // search back to back. Wait for it, then refresh if the rows
            // moved on since. Only a new query cancels a running search.
            searchJob?.takeIf { it.isActive }?.join()
            // A query search still pending owns the next result; do not race it.
            // (this@: inside a coroutine a bare isActive is the coroutine's.)
            if (!this@ChatSearchController.isActive || pending?.isActive == true || isSearching) return@launch
            if (signature(rows) == searchedSignature) return@launch
            runSearch(landOnNewest = false)
        }
    }

    /**
     * Cheap fingerprint of what the units read: row keys and the lengths of
     * their texts. Enough to notice streaming, new rows, compaction, errors.
     */
    private fun signature(rows: List<FlatChatItem>): Int {
        var h = rows.size
        for (r in rows) {
            h = h * 31 + r.key.hashCode()
            h = h * 31 + when (r) {
                is FlatChatItem.AssistantMarkdownBlock -> r.rawText.length
                is FlatChatItem.AssistantText -> r.block.content.length
                is FlatChatItem.AssistantLegacyContent -> r.content.length
                is FlatChatItem.UserBubble -> r.message.content.length + r.message.attachmentNames.size
                is FlatChatItem.AssistantToolUse -> r.block.toolTitle.length + r.block.content.length
                is FlatChatItem.AssistantInfo -> r.block.content.length
                is FlatChatItem.AssistantError -> r.error.length
                else -> 0
            }
        }
        return h
    }

    // ── Searching ─────────────────────────────────────────────────────────

    private fun cancelAll() {
        pending?.cancel(); pending = null
        searchJob?.cancel(); searchJob = null
        refreshJob?.cancel(); refreshJob = null
        generation++
        isSearching = false
    }

    /**
     * Runs a search after [delayMs], replacing any search not yet finished.
     * [landOnNewest]: a new query moves to the newest match and scrolls; a
     * refresh after new content keeps the current match and does not scroll.
     */
    private fun schedule(delayMs: Long, landOnNewest: Boolean) {
        pending?.cancel()
        if (landOnNewest) {
            // A new query invalidates whatever is in flight right away, so a
            // slow old search can never land after the user typed on.
            searchJob?.cancel()
            generation++
            isSearching = ChatSearchMatcher.normalizedQuery(query).isNotEmpty()
        }
        pending = scope.launch {
            if (delayMs > 0) delay(delayMs)
            runSearch(landOnNewest)
        }
    }

    private fun runSearch(landOnNewest: Boolean) {
        pending = null
        val q = ChatSearchMatcher.normalizedQuery(query)
        if (!isActive || q.isEmpty()) {
            searchJob?.cancel()
            isSearching = false
            apply(ChatSearchResult.EMPTY, q, landOnNewest)
            return
        }
        searchJob?.cancel()
        val gen = ++generation
        val snapshot = rows
        val sig = signature(snapshot)
        val cache = pieceCache
        val started = System.nanoTime()
        searchJob = scope.launch {
            val outcome = withContext(background) {
                // Bare isActive here is the coroutine's: a cancelled (stale) run stops.
                val built = ChatSearchUnits.build(snapshot, source, cache) { !isActive } ?: return@withContext null
                val result = ChatSearchEngine.search(built.first, q) { !isActive } ?: return@withContext null
                Triple(built.first.size, built.second, result)
            } ?: return@launch
            if (gen != generation || !this@ChatSearchController.isActive) return@launch
            pieceCache = outcome.second
            searchedSignature = sig
            searchJob = null
            AppLogger.info(
                TAG,
                "[ChatSearch] searched units=${outcome.first} matches=${outcome.third.matches.size}" +
                    "${if (outcome.third.truncated) "+" else ""} ms=${(System.nanoTime() - started) / 1_000_000} " +
                    "landOnNewest=$landOnNewest",
            )
            apply(outcome.third, q, landOnNewest)
        }
    }

    private fun apply(result: ChatSearchResult, q: String, landOnNewest: Boolean) {
        val old = currentMatch
        val oldIndex = currentIndex
        matches = result.matches
        matchCount = result.matches.size
        isTruncated = result.truncated
        isSearching = false
        currentIndex = if (landOnNewest) ChatSearchNavigation.initialIndex(matches.size)
            else ChatSearchNavigation.relocate(old, oldIndex, matches)
        highlight = if (result.matches.isEmpty()) ChatSearchHighlight.NONE
            else ChatSearchHighlight(q, result.countByUnit, currentMatch)
        if (landOnNewest) navigateToCurrent()
    }

    private fun navigateToCurrent() {
        currentMatch?.let { _navigations.tryEmit(it) }
    }

    // ── Notices ───────────────────────────────────────────────────────────

    private fun showNotice(n: Notice) {
        notice = n
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(NOTICE_MS)
            notice = null
        }
    }

    companion object {
        private const val TAG = "ChatSearch"
        const val TYPING_DEBOUNCE_MS = 250L
        const val STREAMING_THROTTLE_MS = 300L
        const val NOTICE_MS = 1_600L
    }
}

// ─── Merged from ChatSearchEngine.kt ──────────────────────────────────────────────────

/*
 * [T-in-chat-search] The pure half of "Search in Chat": what a search unit is,
 * how a query matches its text, and how the current result moves. Port of iOS
 * ChatSearchEngine.swift (62b421d0d); Windows has the same rules
 * (minis-ui/src/chat/chat_search.rs).
 *
 * A match is identified by (unit key, ordinal) - "the Nth occurrence of the
 * query in this piece of visible text" - never by a character range. The
 * search runs over a text snapshot of the list rows, and the Text that draws
 * the highlight finds the Nth occurrence again in the text it actually shows.
 * Both sides call [ChatSearchMatcher.ranges], so as long as the two texts
 * agree on the occurrences, the ordinals agree too.
 *
 * No Android or Compose types: ChatSearchEngineTest runs it on the JVM.
 */

/**
 * One piece of visible text: a list row ([rowKey] = the FlatChatItem key) and
 * which separately drawn Text of that row ([field]). A row can draw several
 * texts (a user bubble and its file tiles, the paragraphs and list items of a
 * markdown fragment), and each is its own unit so the highlighter can find the
 * occurrence inside the Text that draws it.
 */
data class ChatSearchUnitKey(val rowKey: String, val field: String)

/** One unit's text, in display order. */
data class ChatSearchUnit(val key: ChatSearchUnitKey, val text: String)

/** The [ordinal]-th (0-based) non-overlapping occurrence of the query in a unit. */
data class ChatSearchMatch(val key: ChatSearchUnitKey, val ordinal: Int)

object ChatSearchMatcher {
    /**
     * The query as searched: surrounding whitespace dropped, inner spaces
     * kept. The whole string is ONE substring - "apple pie" does not find
     * "apple" alone (unlike the session list's keyword OR search).
     */
    fun normalizedQuery(raw: String): String = raw.trim()

    /**
     * Characters the renderer adds that no reader sees, skipped when matching
     * (iOS `normalizedScalar`): zero-width and hair spaces (CJK emphasis fix,
     * breakable inline code), the six-per-em space the inline parser pads
     * inline code with, the object replacement character (an inline formula's
     * placeholder, see ChatSearchMarkdown.searchable) and the zero-width
     * no-break space. "use `b` now" is drawn as "use b now" with a U+2006 on
     * each side of the b, and must still be found by "use b now".
     */
    fun isInvisible(c: Char): Boolean =
        c == '​' || c == ' ' || c == ' ' || c == '￼' || c == '﻿'

    /**
     * Case- and diacritic-insensitive form of [text] (iOS `.caseInsensitive,
     * .diacriticInsensitive`: "cafe" finds "Café"), with, for each folded
     * char, the original [start, end) it came from. Folding goes code point by
     * code point: NFD, combining marks dropped, lower-cased; invisible
     * characters fold to nothing.
     */
    private class Folded(val text: String, val start: IntArray, val end: IntArray)

    /**
     * [T-in-chat-search-fold-cost] Folded form per non-ASCII code point. The
     * fold of a code point never depends on its neighbours, and a chat uses a
     * few thousand distinct ones at most, so each is normalised once instead of
     * once per occurrence (a substring, Normalizer, two builders and a
     * lowercase per CJK character, on every search and every streaming
     * refresh). Capped as a guard; past the cap code points are folded fresh.
     */
    private val foldCache = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private const val FOLD_CACHE_MAX = 16_384

    private fun foldCodePoint(cp: Int): String {
        foldCache[cp]?.let { return it }
        val nfd = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD)
        val base = StringBuilder(nfd.length)
        var k = 0
        while (k < nfd.length) {
            val q = nfd.codePointAt(k)
            if (Character.getType(q) != Character.NON_SPACING_MARK.toInt()) base.appendCodePoint(q)
            k += Character.charCount(q)
        }
        val folded = base.toString().lowercase(Locale.ROOT)
        if (foldCache.size < FOLD_CACHE_MAX) foldCache[cp] = folded
        return folded
    }

    private fun fold(text: String): Folded {
        val out = StringBuilder(text.length)
        // Primitive arrays (grown by doubling): the boxed lists cost two
        // Integer objects per folded char.
        var starts = IntArray(text.length.coerceAtLeast(1))
        var ends = IntArray(starts.size)
        var count = 0
        fun push(start: Int, end: Int) {
            if (count == starts.size) {
                starts = starts.copyOf(count * 2)
                ends = ends.copyOf(count * 2)
            }
            starts[count] = start
            ends[count] = end
            count++
        }
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            val c0 = text[i]
            if (n == 1 && isInvisible(c0)) {
                i += n
                continue
            }
            if (cp < 0x80) {
                // ASCII fast path: no marks to drop, no allocation.
                out.append(if (c0 in 'A'..'Z') c0 + 32 else c0)
                push(i, i + n)
            } else {
                for (ch in foldCodePoint(cp)) {
                    out.append(ch)
                    push(i, i + n)
                }
            }
            i += n
        }
        return Folded(out.toString(), starts.copyOf(count), ends.copyOf(count))
    }

    /** The folded form of a query (also drops its invisible characters). */
    fun foldQuery(query: String): String = fold(query).text

    /**
     * Non-overlapping occurrences of [query] in [text], first to last, at most
     * [limit], as ranges of [text] (end exclusive: `start until end`). Empty
     * for an empty query.
     */
    fun ranges(query: String, text: String, limit: Int = Int.MAX_VALUE): List<IntRange> {
        if (query.isEmpty() || text.isEmpty() || limit <= 0) return emptyList()
        val q = foldQuery(query)
        if (q.isEmpty()) return emptyList()
        val f = fold(text)
        val out = ArrayList<IntRange>()
        var from = 0
        while (from <= f.text.length - q.length) {
            val at = f.text.indexOf(q, from)
            if (at < 0) break
            out.add(f.start[at] until f.end[at + q.length - 1])
            if (out.size >= limit) break
            from = at + q.length
        }
        return out
    }

    /** Number of occurrences, capped at [limit]. */
    fun count(query: String, text: String, limit: Int): Int = ranges(query, text, limit).size
}

data class ChatSearchResult(
    /** Every match in display order (unit order, then ordinal). */
    val matches: List<ChatSearchMatch>,
    /** Matches per unit, for the highlighter. */
    val countByUnit: Map<ChatSearchUnitKey, Int>,
    /** True when the total limit cut the list short ("2000+"). */
    val truncated: Boolean,
) {
    companion object {
        val EMPTY = ChatSearchResult(emptyList(), emptyMap(), false)
    }
}

object ChatSearchEngine {
    /** Highlights drawn in one unit; also the matches counted for it. */
    const val PER_UNIT_LIMIT = 200

    /** Matches kept for the whole chat; the count then reads "2000+". */
    const val TOTAL_LIMIT = 2000

    /**
     * Searches [units] (already in display order). Returns null when
     * [isCancelled] turns true part-way, so a stale run never publishes.
     */
    fun search(
        units: List<ChatSearchUnit>,
        query: String,
        perUnitLimit: Int = PER_UNIT_LIMIT,
        totalLimit: Int = TOTAL_LIMIT,
        isCancelled: () -> Boolean = { false },
    ): ChatSearchResult? {
        val q = ChatSearchMatcher.normalizedQuery(query)
        if (q.isEmpty() || ChatSearchMatcher.foldQuery(q).isEmpty()) return ChatSearchResult.EMPTY
        val matches = ArrayList<ChatSearchMatch>()
        val countByUnit = HashMap<ChatSearchUnitKey, Int>()
        var truncated = false
        for ((i, unit) in units.withIndex()) {
            if (i % 64 == 0 && isCancelled()) return null
            val remaining = totalLimit - matches.size
            if (remaining <= 0) {
                // Only report truncation when something was actually dropped.
                if (ChatSearchMatcher.count(q, unit.text, 1) > 0) {
                    truncated = true
                    break
                }
                continue
            }
            val n = ChatSearchMatcher.count(q, unit.text, minOf(perUnitLimit, remaining + 1))
            if (n <= 0) continue
            val kept = minOf(n, remaining, perUnitLimit)
            if (n > remaining) truncated = true
            countByUnit[unit.key] = kept
            for (ordinal in 0 until kept) matches.add(ChatSearchMatch(unit.key, ordinal))
        }
        return ChatSearchResult(matches, countByUnit, truncated)
    }
}

/**
 * Moving through the results. Index 0 is the earliest match; "next" goes
 * toward newer messages, "previous" toward older ones, both wrapping.
 */
object ChatSearchNavigation {
    data class Step(val index: Int, val wrapped: Boolean)

    /** Where a fresh search lands: the newest match (a chat reads bottom-up). */
    fun initialIndex(count: Int): Int? = if (count > 0) count - 1 else null

    /** One step from [index]; [Step.wrapped] when it went past an end and came round. */
    fun step(index: Int?, count: Int, forward: Boolean): Step? {
        if (count <= 0) return null
        if (index == null || index < 0 || index >= count) return Step(if (forward) 0 else count - 1, false)
        if (forward) return if (index + 1 < count) Step(index + 1, false) else Step(0, count > 1)
        return if (index > 0) Step(index - 1, false) else Step(count - 1, count > 1)
    }

    /**
     * The current match after the results were recomputed (new content
     * streamed in): the same match if it still exists, otherwise the old
     * index clamped to the new count.
     */
    fun relocate(previous: ChatSearchMatch?, previousIndex: Int?, matches: List<ChatSearchMatch>): Int? {
        if (matches.isEmpty()) return null
        if (previous != null) {
            val i = matches.indexOf(previous)
            if (i >= 0) return i
        }
        if (previousIndex == null) return null
        return previousIndex.coerceIn(0, matches.size - 1)
    }

    /** The "3/12" counter (1-based), "2000+" when truncated. */
    fun counterText(index: Int?, count: Int, truncated: Boolean): String {
        val total = if (truncated) "$count+" else "$count"
        if (index == null || count <= 0) return "0/$total"
        return "${index + 1}/$total"
    }
}

// ─── Merged from ChatSearchHighlights.kt ──────────────────────────────────────────────────

/*
 * [T-in-chat-search] Painting the matches where they are drawn (iOS
 * ChatSearchHighlights): each Text that is a search unit looks up its own
 * count in [ChatSearchHighlight], finds those occurrences again in the very
 * AnnotatedString it draws (ChatSearchMarkdown.searchable + the same
 * matcher), and adds a background span. Only spans are added - the text is
 * unchanged, so selection, copy and the streaming fade keep their offsets.
 */

/** The current search highlight, provided once above the message list. */
internal val LocalChatSearchHighlight = compositionLocalOf { ChatSearchHighlight.NONE }

/** The list row (FlatChatItem key) the composable belongs to; null outside the chat list. */
internal val LocalChatSearchRow = compositionLocalOf<String?> { null }

/** Background of every match, and of the current one (iOS: yellow / orange). */
internal val ChatSearchMatchBackground = Color(0x66FFD60A)
internal val ChatSearchCurrentBackground = Color(0xCCFF9F0A)

/**
 * [text] with the matches of [key] painted: the first `count(key)`
 * occurrences of the query, the current one in [current], the others in
 * [match]. [offset] shifts the search start: a unit that is the tail of the
 * drawn string (an agent callback's title after "Agent result · ").
 */
internal fun applyChatSearchHighlight(
    text: AnnotatedString,
    key: ChatSearchUnitKey,
    highlight: ChatSearchHighlight,
    match: SpanStyle = SpanStyle(background = ChatSearchMatchBackground),
    current: SpanStyle = SpanStyle(background = ChatSearchCurrentBackground),
    offset: Int = 0,
): AnnotatedString {
    val count = highlight.count(key)
    if (count <= 0 || offset > text.length) return text
    val searchable = ChatSearchMarkdown.searchable(text).substring(offset)
    val ranges = ChatSearchMatcher.ranges(highlight.query, searchable, count)
    if (ranges.isEmpty()) return text
    val currentOrdinal = highlight.currentOrdinal(key)
    return buildAnnotatedString {
        append(text)
        ranges.forEachIndexed { i, r ->
            addStyle(if (i == currentOrdinal) current else match, offset + r.first, offset + r.last + 1)
        }
    }
}

/** Highlight for a plain string unit of the current row. */
@Composable
internal fun rememberChatSearchHighlighted(text: String, field: String, offset: Int = 0): AnnotatedString {
    val row = LocalChatSearchRow.current
    val h = LocalChatSearchHighlight.current
    return remember(text, row, field, h, offset) {
        val base = AnnotatedString(text)
        if (row == null) base else applyChatSearchHighlight(base, ChatSearchUnitKey(row, field), h, offset = offset)
    }
}

/** Highlight for an AnnotatedString unit of the current row (markdown pieces). */
@Composable
internal fun rememberChatSearchHighlighted(text: AnnotatedString, field: String?): AnnotatedString {
    val row = LocalChatSearchRow.current
    val h = LocalChatSearchHighlight.current
    if (row == null || field == null || h.countByUnit.isEmpty()) return text
    return remember(text, row, field, h) { applyChatSearchHighlight(text, ChatSearchUnitKey(row, field), h) }
}

/** True when the current match is in [field] of the current row (whole-block outline). */
@Composable
internal fun chatSearchFieldIsCurrent(field: String?): Boolean {
    val row = LocalChatSearchRow.current ?: return false
    if (field == null) return false
    return LocalChatSearchHighlight.current.current?.key == ChatSearchUnitKey(row, field)
}

/**
 * Provides the highlight and this row's key to one list row. The highlight is
 * read HERE, in this small scope, so moving the current match recomposes this
 * wrapper and the Texts that read [LocalChatSearchHighlight] - not the chat
 * screen and not the row's own content.
 */
@Composable
internal fun ChatSearchRowScope(controller: ChatSearchController, rowKey: String, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalChatSearchHighlight provides controller.highlight,
        LocalChatSearchRow provides rowKey,
        content = content,
    )
}

// ─── Merged from ChatSearchMarkdown.kt ──────────────────────────────────────────────────

/*
 * [T-in-chat-search] The searchable form of a text a markdown row draws.
 *
 * The chat renders an assistant reply with StreamingMarkdownText: each block
 * (paragraph, heading, list item, ...) is its own Text over an AnnotatedString
 * built by the inline parser - markdown marks already gone, link text without
 * its URL, inline code without backticks. Search uses those same
 * AnnotatedStrings (chatSearchMarkdownPieces in StreamingMarkdownText.kt), so
 * what is searched is exactly what is shown (iOS ChatSearchMarkdownText
 * reaches the same goal by mirroring its renderer).
 */
object ChatSearchMarkdown {
    /** Compose's tag for `appendInlineContent` (an inline formula's placeholder). */
    private const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"

    /**
     * [text] with every inline-content range (an inline formula drawn as an
     * image; its LaTeX is only the placeholder's alternate text) replaced by
     * U+FFFC, which the matcher skips. Same length as the input, so offsets
     * found in the result are offsets into the drawn string: the highlighter
     * calls this on the AnnotatedString it draws and paints those ranges.
     */
    fun searchable(text: AnnotatedString): String {
        val spans = text.getStringAnnotations(INLINE_CONTENT_TAG, 0, text.length)
        if (spans.isEmpty()) return text.text
        val chars = text.text.toCharArray()
        for (s in spans) for (i in s.start until minOf(s.end, chars.size)) chars[i] = '￼'
        return String(chars)
    }
}

// ─── Merged from ChatSearchUnits.kt ──────────────────────────────────────────────────

/*
 * [T-in-chat-search] Which texts of a conversation "Search in Chat" looks at,
 * and the strings those texts are. Rule (iOS ChatSearchUnits): what the list
 * shows is searchable, what it hides is not.
 *
 *   searched                               not searched
 *   ---------------------------------      ---------------------------------
 *   user bubble text                       thinking
 *   user file tile names                   tool command / output (detail sheet)
 *   assistant text, as rendered            usage, timestamps, buttons, labels
 *   tool capsule line                      list bullets and numbers
 *   info rows (notices, dividers)          the "Ran N tools" row of a fold
 *   footer error
 *   agent callback title
 *   compacted history (same rows, dimmed)
 *
 * Not yet (phase 2, see the A2c report): a sub agent card's title / status, an
 * answered user_ask card, a scheduled task card, a model-switch notice card -
 * their texts are worked out
 * inside composables that would need the same treatment as the capsule line.
 *
 * Units are built from the list rows BEFORE tool runs are folded, so a match
 * inside a folded run is found and navigation opens the run.
 */
internal object ChatSearchUnits {
    const val BODY = "body"
    const val ERROR = "error"
    const val TITLE = "title"
    fun attachment(index: Int) = "att:$index"
    fun markdown(path: String) = "md:$path"

    /** Field prefix of a markdown piece; the rest is the RenderBlock path. */
    const val MARKDOWN_PREFIX = "md:"

    /** Texts worked out outside this builder (Android resources, the markdown renderer). */
    interface TextSource {
        /** The pieces a markdown fragment draws; default: the renderer's own. */
        fun markdownPieces(raw: String): List<ChatSearchPiece> = chatSearchMarkdownPieces(raw)

        /** The tool capsule line, or null when the row is not a plain capsule. */
        fun toolCapsule(block: AssistantBlock): String?
    }

    /** Cached pieces of one markdown row: reused while its text is unchanged. */
    class PieceCacheEntry(val raw: String, val pieces: List<ChatSearchPiece>)

    /**
     * Every searchable text of [rows], in display order (oldest first, as the
     * rows are built). [cache] is read and a fresh one returned; null when
     * [isCancelled] turns true part-way.
     */
    fun build(
        rows: List<FlatChatItem>,
        source: TextSource,
        cache: Map<String, PieceCacheEntry> = emptyMap(),
        isCancelled: () -> Boolean = { false },
    ): Pair<List<ChatSearchUnit>, Map<String, PieceCacheEntry>>? {
        val units = ArrayList<ChatSearchUnit>(rows.size * 2)
        val newCache = HashMap<String, PieceCacheEntry>()
        fun add(row: String, field: String, text: String) {
            if (text.isNotEmpty()) units.add(ChatSearchUnit(ChatSearchUnitKey(row, field), text))
        }
        fun markdown(row: String, raw: String) {
            if (raw.isEmpty()) return
            // Same string instance or equal text: the parse is reused.
            val hit = cache[row]?.takeIf { it.raw == raw }
            val pieces = hit?.pieces ?: source.markdownPieces(raw)
            newCache[row] = hit ?: PieceCacheEntry(raw, pieces)
            for (p in pieces) add(row, markdown(p.path), p.text)
        }
        for ((i, row) in rows.withIndex()) {
            if (i % 32 == 0 && isCancelled()) return null
            when (row) {
                is FlatChatItem.UserBubble -> {
                    val m = row.message
                    // File tiles sit above the bubble; image tiles show no name
                    // (UserAttachmentList: the first imageUris.size names are images).
                    m.attachmentNames.drop(m.imageUris.size).forEachIndexed { k, name -> add(row.key, attachment(k), name) }
                    if (m.content.isNotBlank()) add(row.key, BODY, m.content)
                }
                is FlatChatItem.AgentCallbackCard -> add(row.key, TITLE, row.callback.title)
                is FlatChatItem.AssistantMarkdownBlock -> markdown(row.key, row.rawText)
                is FlatChatItem.AssistantText -> markdown(row.key, row.block.content)
                is FlatChatItem.AssistantLegacyContent -> markdown(row.key, row.content)
                is FlatChatItem.AssistantToolUse -> source.toolCapsule(row.block)?.let { add(row.key, BODY, it) }
                // A model-switch notice is drawn as a card of separate lines
                // (ModelSwitchNoticeCard), not this one text: phase 2.
                is FlatChatItem.AssistantInfo ->
                    if (!isModelSwitchNotice(row.block)) add(row.key, BODY, row.block.content)
                is FlatChatItem.AssistantError -> add(row.key, ERROR, row.error)
                is FlatChatItem.AssistantHeader, is FlatChatItem.AssistantThinking,
                is FlatChatItem.AssistantToolGroup, is FlatChatItem.AssistantTyping,
                is FlatChatItem.AssistantUsage, is FlatChatItem.ScheduledTaskCardItem -> Unit
            }
        }
        return units to newCache
    }
}
