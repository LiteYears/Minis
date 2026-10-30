package com.openminis.app.ui.sessions

import android.content.Context
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.SessionSearch
import com.openminis.app.logging.AppLogger
import org.json.JSONObject

/**
 * [T-folder-pages] An open folder shows its members 20 at a time, with a
 * "Show N more (M left)" row for the rest. Port of iOS 14bc208b1
 * (`ContentView.folderPage` / `folderPagesRevealing`).
 *
 * Before, an open folder listed every member, so a busy folder took the whole
 * sidebar and pushed every other folder and date bucket off screen.
 *
 * Pure, so the page maths is tested without Compose.
 */
internal object FolderPages {
    const val PAGE_SIZE = 20

    /**
     * Members shown and still hidden for [pages] pages of [total]. A page
     * count below 1 counts as the first page; a page size below 1 as 1.
     * 15 → 15/0, 20 → 20/0, 21 → 20/1, 45 → 20/25 → 40/5 → 45/0.
     */
    fun page(total: Int, pages: Int, pageSize: Int = PAGE_SIZE): Pair<Int, Int> {
        val all = maxOf(0, total)
        val shown = minOf(all.toLong(), maxOf(1, pages).toLong() * maxOf(1, pageSize)).toInt()
        return shown to (all - shown)
    }

    /**
     * Pages needed so the member at [index] is on screen, never fewer than
     * [stored] (or 1). A row that must stay visible — the session open in the
     * detail pane, a chat just started in the folder — is never paged out.
     */
    fun revealing(index: Int?, stored: Int, pageSize: Int = PAGE_SIZE): Int {
        val floor = maxOf(1, stored)
        if (index == null || index < 0) return floor
        return maxOf(floor, index / maxOf(1, pageSize) + 1)
    }

    /** What the "Show N more" row offers: the next page, capped by what is left. */
    fun nextPageCount(hidden: Int, pageSize: Int = PAGE_SIZE): Int = minOf(maxOf(0, hidden), maxOf(1, pageSize))

    // ── Stored pages (SessionListViewModel.folderPages) ───────────────────

    /**
     * The open folder changed to [open] (null = all folded): every other
     * folder's entry goes, so a folder that closes reopens on page 1.
     */
    fun afterOpenChanged(pages: Map<String, Int>, open: String?): Map<String, Int> =
        pages.filterKeys { it == open }

    /** "Show more" on [folderId]: one page past [pagesShown]. Ignored unless it is the open folder. */
    fun afterShowMore(pages: Map<String, Int>, open: String?, folderId: String, pagesShown: Int): Map<String, Int> =
        if (folderId != open) pages else pages + (folderId to maxOf(1, pagesShown) + 1)

    /** Keep the [revealed] pages a selection opened; never narrows. Ignored unless it is the open folder. */
    fun afterReveal(pages: Map<String, Int>, open: String?, folderId: String, revealed: Int): Map<String, Int> =
        if (folderId != open || (pages[folderId] ?: 1) >= revealed) pages else pages + (folderId to revealed)

    // ── Search pages (SessionListViewModel.searchFolderPages) ─────────────

    /**
     * [T-search-folder-pages] "Show more" on a folder in a search result set
     * (iOS e709a821e): every result folder is open, so any of them pages, and
     * the pages are kept apart from the normal ones - a search neither reads
     * nor resets those. Reset when a new result set arrives.
     */
    fun afterSearchShowMore(pages: Map<String, Int>, folderId: String, pagesShown: Int): Map<String, Int> =
        pages + (folderId to maxOf(1, pagesShown) + 1)
}

// ─── Merged from SearchFolders.kt ──────────────────────────────────────────────────

/**
 * [T-search-folder-expand] Folders in a search result set (iOS 6b0ee14c1,
 * ContentView.scheduleSearch / computeGroupedSessionIDs).
 *
 * While a result set is on screen every folder in it opens with its matching
 * members, and a folder whose NAME matches is a result in its own right: it
 * brings all its members along (the folder is the reason they match) and shows
 * even when it has none. Pure, so the rules are tested without Compose.
 */
internal object SearchFolders {

    /** Folders whose name holds any of the query's keywords (the same OR rule as sessions). */
    fun matchedFolderIds(folders: List<FolderEntity>, query: String): Set<String> {
        val terms = SessionSearch.searchTerms(query)
        if (terms.isEmpty()) return emptySet()
        return folders.filter { SessionSearch.firstHit(it.name, terms) != null }.map { it.id }.toSet()
    }

    /**
     * The result set: the sessions the search matched plus every member of a
     * name-matched folder, newest first like the normal list (the sort is
     * stable, so equal times keep the search's order).
     */
    fun withFolderMembers(
        results: List<ChatSessionEntity>,
        all: List<ChatSessionEntity>,
        matchedFolderIds: Set<String>,
    ): List<ChatSessionEntity> {
        if (matchedFolderIds.isEmpty()) return results
        val ids = results.mapTo(HashSet()) { it.id }
        val extra = all.filter { s -> s.folderId != null && s.folderId in matchedFolderIds && s.id !in ids }
        if (extra.isEmpty()) return results
        return (results + extra).sortedByDescending { it.updatedAt }
    }
}

// ─── Merged from SearchHighlight.kt ──────────────────────────────────────────────────

/**
 * Build an [AnnotatedString] that highlights every case-insensitive
 * occurrence of every keyword of [query] inside [text] with the theme's
 * tertiaryContainer background. Blank query returns the plain string with no
 * spans.
 *
 * Used by the home session-list search results to point the user at the
 * exact spans that matched their query, in both the session title and the
 * extracted message snippet.
 *
 * [T-search-multi-keyword] (iOS b09df3824) The query is split into keywords
 * exactly as the search splits it ([SessionSearch.searchTerms]), so "apple
 * banana" marks both words wherever they are; overlapping or touching hits
 * are one span ([SessionSearch.hitRanges]), so "gpt" and "gpt-5" do not fight
 * over "GPT-5". Splitting on whitespace also keeps the earlier fix
 * ([T-android-search-visible-only]): a query with a leading space, " GPT",
 * marks every "GPT", not only those after a space.
 */
@Composable
fun highlightedAnnotatedString(text: String, query: String): AnnotatedString {
    if (query.isBlank() || text.isEmpty()) return AnnotatedString(text)
    val highlightBg = MaterialTheme.colorScheme.tertiaryContainer
    val highlightFg = MaterialTheme.colorScheme.onTertiaryContainer
    val ranges = SessionSearch.hitRanges(text, SessionSearch.searchTerms(query))
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var idx = 0
        for (r in ranges) {
            if (r.first > idx) append(text.substring(idx, r.first))
            withStyle(SpanStyle(background = highlightBg, color = highlightFg)) {
                append(text.substring(r.first, r.last + 1))
            }
            idx = r.last + 1
        }
        if (idx < text.length) append(text.substring(idx))
    }
}

/**
 * Locate the first case-insensitive occurrence of [query] in [text] and
 * return a ~[radius]-char window centred on the hit. Newlines collapse to
 * spaces; ellipses prefix/suffix when truncated. Returns null if no hit.
 */
fun snippetAround(text: String, query: String, radius: Int = 50): String? {
    if (query.isBlank() || text.isEmpty()) return null
    val lower = text.lowercase()
    val pos = lower.indexOf(query.lowercase())
    if (pos < 0) return null
    val start = (pos - radius).coerceAtLeast(0)
    val end = (pos + query.length + radius).coerceAtMost(text.length)
    val core = text.substring(start, end).replace('\n', ' ').replace('\r', ' ')
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < text.length) "…" else ""
    return prefix + core + suffix
}

// ─── Merged from SessionDuplicateTitle.kt ──────────────────────────────────────────────────

/**
 * [ISS-038] Title of a duplicated session. Port of iOS SessionDuplicateTitle
 * (03da252a1); pure, so the rules are unit-tested as shipped.
 *
 * - Format: "<base> (<word>)" for the first copy, "<base> (<word> N)" for
 *   N >= 2, with ASCII parentheses and a space before them. The word is
 *   localized ("Copy", "副本").
 * - Base: the source title minus ONE trailing copy suffix, so copying
 *   "Foo (Copy 2)" never gives "Foo (Copy 2) (Copy)". A trailing suffix is
 *   "(" or a full-width "(", a known word in any shipped language
 *   (case-insensitive), an optional integer >= 2 without a leading zero, then
 *   ")" or its full-width form, at the very end after trimming. Anything else
 *   is part of the title: "Foo (draft)", "Foo (Copy) notes", "Foo (Copy 2x)",
 *   "Foo (Copy 0)", "Foo (Copy 1)". A title that is nothing but a suffix keeps
 *   itself as the base.
 * - Number: the SMALLEST free number among existing titles with the same base,
 *   in any language - deleting "Foo (Copy 2)" and copying again refills 2.
 * - Blank source title: [untitledTitle] is the base.
 */
internal object SessionDuplicateTitle {

    fun make(
        sourceTitle: String?,
        existingTitles: Collection<String>,
        suffixWord: String,
        knownSuffixWords: List<String>,
        untitledTitle: String,
    ): String {
        val words = recognizedWords(suffixWord, knownSuffixWords)
        val trimmed = (sourceTitle ?: "").trim()
        val base = if (trimmed.isEmpty()) untitledTitle else (parse(trimmed, words)?.first ?: trimmed)
        val taken = HashSet<Int>()
        for (title in existingTitles) {
            val p = parse(title, words) ?: continue
            if (p.first == base) taken.add(p.second)
        }
        var number = 1
        while (number in taken) number++
        return format(base, suffixWord, number)
    }

    fun format(base: String, word: String, number: Int): String =
        if (number <= 1) "$base ($word)" else "$base ($word $number)"

    fun recognizedWords(suffixWord: String, known: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (w in listOf(suffixWord, "Copy") + known) {
            val t = w.trim()
            if (t.isEmpty() || !seen.add(t.lowercase())) continue
            out.add(t)
        }
        return out
    }

    /** (base, number) when [title] ends with a copy suffix; number 1 without a number. Null otherwise. */
    fun parse(title: String, words: List<String>): Pair<String, Int>? {
        val t = title.trim()
        val last = t.lastOrNull() ?: return null
        if (last != ')' && last != '）') return null
        val open = t.indexOfLast { it == '(' || it == '（' }
        if (open < 0) return null
        val inner = t.substring(open + 1, t.length - 1).trim()
        val base = t.substring(0, open).trim()
        if (base.isEmpty()) return null
        for (word in words) {
            if (inner.equals(word, ignoreCase = true)) return base to 1
            if (inner.length <= word.length || !inner.regionMatches(0, word, 0, word.length, ignoreCase = true)) continue
            val rest = inner.substring(word.length)
            if (rest[0] != ' ' && rest[0] != '　') continue
            val digits = rest.substring(1).trim()
            if (digits.isEmpty() || digits.length > 9 || digits.startsWith("0") || !digits.all { it in '0'..'9' }) continue
            val n = digits.toInt()
            if (n < 2) continue
            return base to n
        }
        return null
    }
}

// ─── Merged from SessionTitleRegenerator.kt ──────────────────────────────────────────────────

/**
 * [GH#210] Regenerate Title - the session list's "Regenerate Title" and
 * `minis-sessions-cli update --title-auto` [T-sessions-cli-update] run this
 * one implementation. Moved verbatim out of SessionListViewModel so a
 * caller without the list's ViewModel (the in-shell CLI) gets the same
 * candidates, prompt, write and first-message fallback.
 */
class SessionTitleRegenerator(
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
    private val context: Context,
) {
    private companion object {
        const val TAG = "SessionListVM"

        /**
         * The placeholder ChatViewModel writes for a session whose title has
         * not been generated yet. The fallback path keys off this exact string
         * (plus null/blank), which is what keeps a user-typed title from ever
         * being overwritten.
         */
        const val NEW_CHAT_TITLE = "New Chat"
    }

    /**
     * [GH#210] Generate a title for [id] purely from what is in the DB.
     *
     * Takes no ViewModel state, so the same code serves any USER-INITIATED
     * entry point. It is deliberately never called automatically: an implicit
     * LLM request the user did not ask for — e.g. sweeping untitled sessions
     * at list load — would mean a burst of hidden network calls at startup,
     * which is not an acceptable trade for a cosmetic title.
     *
     * @param origin tags the log line so different entry points can be told
     *   apart when reconciling dispatch against outcome.
     * @return true when a title was written (LLM or fallback).
     */
    suspend fun regenerate(id: String, origin: String): Boolean {
        val startedAt = System.currentTimeMillis()
        var firstUserRaw: String? = null
        try {
                val session = chatRepository.getSession(id) ?: return false
                val messages = chatRepository.loadMessages(id)
                if (messages.isEmpty()) return false

                // [T-titlegen-context-first-last-pair] Summary = first user +
                // first assistant, plus (when the session has more than one user
                // turn) the last user + last assistant, each truncated to 200
                // chars — so a regenerated title reflects a mid/late topic shift
                // rather than only the opener.
                val userMessages = messages.filter { it.role == "user" }
                // Keep the untruncated first user message for the fallback path.
                firstUserRaw = userMessages.firstOrNull()?.let { extractText(it.partsJson) }
                val userText = firstUserRaw?.take(200) ?: return false
                // First/last assistant *text* message — skip tool-only messages
                // whose extracted text is blank so the summary carries real prose.
                val assistantTexts = messages.filter { it.role == "assistant" }
                    .map { extractText(it.partsJson) }
                    .filter { it.isNotBlank() }
                val firstAssistantText = assistantTexts.firstOrNull()?.take(200) ?: ""
                val hasMultipleUserTurns = userMessages.size > 1
                val lastUserText = if (hasMultipleUserTurns) userMessages.lastOrNull()?.let { extractText(it.partsJson) }?.take(200) ?: "" else ""
                val lastAssistantText = if (hasMultipleUserTurns) assistantTexts.lastOrNull()?.take(200) ?: "" else ""

                val prompt = buildString {
                    append("Based on the following conversation, generate a short title (max 6 words) that captures the topic. ")
                    append("Also pick a task category from: code, writing, research, analysis, creative, chat, math, translation, health, finance, travel, education, design, productivity, support, other.\n\n")
                    append("You MUST respond with valid JSON only. Example:\n")
                    append("{\"title\": \"Debug Login Page Issue\", \"category\": \"code\"}\n\n")
                    append("User: $userText\n")
                    if (firstAssistantText.isNotEmpty()) append("Assistant: $firstAssistantText\n")
                    if (lastUserText.isNotEmpty()) append("User: $lastUserText\n")
                    if (lastAssistantText.isNotEmpty()) append("Assistant: $lastAssistantText\n")
                    append(com.openminis.app.ui.chat.titleLanguageDirective())
                }

                // [T-titlegen-group-order] Candidates in iOS order (title sub
                // group → the session's own model/group → default primary
                // group → every other eligible model), each GROUP walked whole
                // in the order the agent loop falls back through it — not just
                // its first member. T334 eligibility and availability are
                // applied inside. See TitleCandidates.
                val candidates = com.openminis.app.ui.chat.TitleCandidates.forSession(
                    providerRepository,
                    sessionId = id,
                    primary = com.openminis.app.ui.chat.TitleCandidates.PrimarySource.parse(session.modelBinding),
                    sessionModelId = session.modelId,
                )
                val written = com.openminis.app.ui.chat.TitleCandidates.walk(candidates, origin = origin) { entry ->
                    // [T-android-opencode-subtask-session] Regenerate is this
                    // chat's traffic: tagged with the chat's id so an OpenCode
                    // Go title model gets `x-opencode-session`.
                    val provider = com.openminis.app.ui.chat.TitleCandidates.providerFor(
                        providerRepository, context, entry, sessionId = id,
                    ) ?: return@walk null
                    AppLogger.info(
                        "TitleGen",
                        "dispatch origin=$origin session=${id.take(8)} model=${entry.model.id}",
                    )
                    // T334: reasoning models burn the whole budget on hidden
                    // thinking before emitting content; give them room.
                    val titleMaxTokens = if (entry.model.supportsReasoning == true) 2048 else 100
                    // [T-android-titlegen-reasoning] thinking OFF, matching iOS
                    // callSubModelForTitle and the auto-title path; the T334
                    // budget bump stays as a net where OFF is a no-op (Qwen3).
                    val response = provider.sendMessage(
                        messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
                        // [T-android-titlegen-systemprompt-unify] Shared with the
                        // auto path; passed bare (the OAuth Claude Code prefix is
                        // handled at the provider layer).
                        systemPrompt = com.openminis.app.ui.chat.TITLE_GEN_SYSTEM_PROMPT,
                        maxTokens = titleMaxTokens,
                        // [T-android-titlegen-temperature] null so the field is
                        // omitted — gpt-5.x 400s on anything but 1.
                        temperature = null,
                        thinkingLevel = ThinkingLevel.OFF,
                    )
                    val (title, category) = parseTitleResponse(response.text)
                    if (title.isEmpty()) {
                        // T334: an empty result is a failure of THIS candidate
                        // (often budget exhaustion on a reasoning model) — log
                        // why and let the walk try the next one.
                        Log.w(
                            TAG,
                            "Title regen empty from ${entry.model.displayName}: " +
                                "stopReason=${response.stopReason} textLen=${response.text.length} " +
                                "maxTokens=$titleMaxTokens supportsReasoning=${entry.model.supportsReasoning}",
                        )
                        return@walk null
                    }
                    chatRepository.updateSessionTitleAndCategory(id, title, category)
                    AppLogger.info(
                        "TitleGen",
                        "outcome=set origin=$origin session=${id.take(8)} " +
                            "model=${entry.model.id} elapsedMs=${System.currentTimeMillis() - startedAt}",
                    )
                    true
                }
                if (written == true) return true
            AppLogger.warning(
                "TitleGen",
                "outcome=no-title origin=$origin session=${id.take(8)} " +
                    "reason=all-candidates-exhausted " +
                    "elapsedMs=${System.currentTimeMillis() - startedAt}",
            )
        } catch (e: Exception) {
            AppLogger.warning(
                "TitleGen",
                "outcome=exception origin=$origin session=${id.take(8)} " +
                    "${e.javaClass.simpleName}: ${e.message?.take(200)} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAt}",
            )
        }
        // Every failure exit lands here. Mirrors iOS applyFallbackTitle: the
        // session must never be left permanently untitled just because the LLM
        // was unreachable.
        return applyFallbackTitle(id, firstUserRaw, origin)
    }

    /**
     * [GH#210] Write a title derived from the first user message.
     *
     * Mirrors iOS `applyFallbackTitle` — including its re-read of the session
     * immediately before writing. That re-check is the guard that keeps this
     * from clobbering a title the user typed (or a concurrent attempt set)
     * while the LLM call was in flight, which can be tens of seconds.
     */
    private suspend fun applyFallbackTitle(id: String, firstUserRaw: String?, origin: String): Boolean {
        val current = chatRepository.getSession(id)?.title?.trim()
        if (!current.isNullOrEmpty() && current != NEW_CHAT_TITLE) {
            AppLogger.info(
                "TitleGen",
                "outcome=fallback-skipped origin=$origin session=${id.take(8)} reason=already-titled",
            )
            return false
        }
        val cleaned = fallbackTitleFrom(firstUserRaw)
        if (cleaned == null) {
            AppLogger.warning(
                "TitleGen",
                "outcome=fallback-unavailable origin=$origin session=${id.take(8)} " +
                    "reason=first-user-message-empty-after-cleanup",
            )
            return false
        }
        chatRepository.updateSessionTitle(id, cleaned)
        // Length only — never the prompt text itself.
        AppLogger.info(
            "TitleGen",
            "outcome=fallback origin=$origin session=${id.take(8)} titleLen=${cleaned.length}",
        )
        return true
    }

    /**
     * Strip the composer's `<user-attached-files>` block, collapse whitespace
     * and truncate to 30 chars. Same shape as iOS `fallbackTitle(fromFirst‑
     * UserMessage:)` and ChatViewModel.applyFallbackTitleFromFirstMessage, so
     * a title recovered here is indistinguishable from one written by the auto
     * path. Returns null when nothing usable remains.
     */
    private fun fallbackTitleFrom(raw: String?): String? {
        var text = raw ?: return null
        val startIdx = text.indexOf("<user-attached-files>")
        if (startIdx >= 0) {
            val endTag = "</user-attached-files>"
            val endIdx = text.indexOf(endTag, startIdx)
            text = if (endIdx >= 0) {
                text.substring(0, startIdx) + text.substring(endIdx + endTag.length)
            } else {
                text.substring(0, startIdx)
            }
        }
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.length > 30) cleaned.take(30).trimEnd() + "…" else cleaned
    }

    private fun extractText(partsJson: String): String {
        return try {
            val arr = org.json.JSONArray(partsJson)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                if (obj.optString("type") != "text") return@mapNotNull null
                val value = obj.optString("value")
                // [T-android-retry-attachment-loss] The user-message
                // <user-attached-files> XML is now persisted as a text part so
                // the model keeps file paths across retry/reload. Drop it from
                // session-list previews + search snippets so the inventory XML
                // never surfaces as visible session content (iOS strips it the
                // same way in ChatStore.toChatMessage).
                if (value.contains("<user-attached-files>")) {
                    val start = value.indexOf("<user-attached-files>")
                    val endTag = "</user-attached-files>"
                    val end = value.indexOf(endTag, start)
                    val cleaned = if (end >= 0) {
                        value.substring(0, start) + value.substring(end + endTag.length)
                    } else {
                        value.substring(0, start)
                    }.trim()
                    cleaned.ifEmpty { null }
                } else {
                    value
                }
            }.joinToString("\n")
        } catch (_: Exception) {
            partsJson
        }
    }

    private fun parseTitleResponse(text: String): Pair<String, String?> {
        val cleaned = text.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        try {
            val json = JSONObject(cleaned)
            val title = json.optString("title", "").trim()
            val category = json.optString("category", "").trim().ifEmpty { null }
            if (title.isNotEmpty()) return title to category
        } catch (_: Exception) {}
        val titleMatch = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"").find(cleaned)
        val catMatch = Regex("\"category\"\\s*:\\s*\"([^\"]+)\"").find(cleaned)
        if (titleMatch != null) {
            return titleMatch.groupValues[1].trim() to catMatch?.groupValues?.getOrNull(1)?.trim()
        }
        val firstLine = cleaned.lines().firstOrNull()?.trim() ?: ""
        return firstLine.take(50) to null
    }
}
