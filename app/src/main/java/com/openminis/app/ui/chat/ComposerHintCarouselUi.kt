package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.openminis.app.R
import com.openminis.app.agent.userask.UserAskAnswer
import com.openminis.app.agent.userask.UserAskCenter
import com.openminis.app.agent.userask.UserAskParser
import com.openminis.app.agent.userask.UserAskQuestion
import com.openminis.app.agent.userask.UserAskQuestionType
import com.openminis.app.agent.userask.UserAskResolver
import com.openminis.app.agent.userask.UserAskResultStatus
import com.openminis.app.agent.userask.UserAskRisk
import com.openminis.app.agent.userask.UserAskState
import com.openminis.app.i18n.uppercaseForDisplay
import kotlinx.coroutines.delay

/**
 * [T-port-composer-hint-carousel] One usage page, with its figures tinted by
 * its own tier: the context page ("Context 62% used · 124k / 200k") or the
 * quota page ("Quota 5h 14% / 7d 32% used"). Highlights are located in the
 * RESOLVED string so a translation may reorder around them.
 */
@Composable
internal fun contextUsagePageText(hint: ContextUsageHint, page: UsageHintPage): AnnotatedString {
    return when (page) {
        UsageHintPage.CONTEXT -> {
            val percent = hint.highlights.getOrNull(0) ?: ""
            val size = hint.highlights.getOrNull(1) ?: ""
            val context = stringResource(R.string.chat_context_usage_hint, percent, size)
            val color = ContextUsageColors.textHighlight(hint.tier)
            buildAnnotatedString {
                append(context)
                for (segment in hint.highlights) {
                    if (segment.isEmpty()) continue
                    val start = context.indexOf(segment)
                    if (start < 0) continue
                    addStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium), start, start + segment.length)
                }
            }
        }
        UsageHintPage.QUOTA -> {
            val quota = hint.quotaText.orEmpty()
            val color = ContextUsageColors.textHighlight(hint.quotaTier)
            buildAnnotatedString {
                append(quota)
                // A normal quota stays plain text.
                val q = hint.quotaHighlight
                if (!q.isNullOrEmpty() && hint.quotaTier != ContextUsage.Tier.NORMAL) {
                    val start = quota.indexOf(q)
                    if (start >= 0) addStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium), start, start + q.length)
                }
            }
        }
    }
}

/** The whole set of figures in one string, for a screen reader. */
@Composable
internal fun contextUsageSpokenText(hint: ContextUsageHint): String {
    val parts = ArrayList<String>(2)
    for (p in hint.pages()) parts.add(contextUsagePageText(hint, p).text)
    return parts.joinToString(" · ")
}

/**
 * [T-port-composer-hint-carousel] A usage page in the placeholder: one line,
 * shrunk just enough to fit (never below [MIN_FIT_SCALE] of [fontSize]; an
 * ellipsis only past that), as iOS fits each page. Not drawn until it fits,
 * so a shrinking pass never shows a clipped frame. The screen reader hears
 * every page at once.
 */
@Composable
internal fun UsageHintPageText(hint: ContextUsageHint, page: UsageHintPage, fontSize: TextUnit, color: Color) {
    val text = contextUsagePageText(hint, page)
    val spoken = contextUsageSpokenText(hint)
    var scale by remember(text.text, fontSize) { mutableFloatStateOf(1f) }
    var ready by remember(text.text, fontSize) { mutableStateOf(false) }
    Text(
        text = text,
        color = color,
        fontSize = fontSize * scale,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout ->
            if (layout.hasVisualOverflow && scale > MIN_FIT_SCALE) {
                scale = (scale - FIT_STEP).coerceAtLeast(MIN_FIT_SCALE)
            } else {
                ready = true
            }
        },
        modifier = Modifier
            .semantics { contentDescription = spoken }
            .drawWithContent { if (ready) drawContent() },
    )
}

private const val MIN_FIT_SCALE = 0.75f
private const val FIT_STEP = 0.05f

/**
 * [T-port-composer-hint-carousel] The page the transient usage line shows:
 * turns every [ComposerHintCarousel.INTERVAL_MS] while [ComposerHintCarousel.canTurn]
 * holds, and only while the screen is resumed (a hidden composer does not use
 * up its rounds). New figures start their own rounds.
 */
@Composable
internal fun rememberHintCarouselPage(hint: ContextUsageHint?, composerEmpty: Boolean, screenReaderOn: Boolean): Int {
    val carousel = remember { ComposerHintCarousel() }
    var page by remember { mutableIntStateOf(0) }
    if (hint != null && hint.generation != carousel.generation) {
        carousel.adopt(hint.generation)
        page = carousel.page
    }
    val pageCount = hint?.pages()?.size ?: 0
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(hint?.generation, pageCount, composerEmpty, screenReaderOn, lifecycleOwner) {
        if (!carousel.canTurn(pageCount, hint != null, composerEmpty, screenReaderOn)) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (carousel.canTurn(pageCount, true, composerEmpty, screenReaderOn)) {
                delay(ComposerHintCarousel.INTERVAL_MS)
                page = carousel.turn(pageCount)
            }
        }
    }
    return if (hint == null) 0 else page
}

// ─── Merged from TokenUsageSheet.kt ──────────────────────────────────────────────────

/**
 * Session Token Usage bottom sheet — mirrors iOS `TokenUsageSheet` and uses
 * the standardized chat sheet shell so its header/dismiss behavior matches
 * every other "⋯" menu sheet.
 *
 * Sections (top to bottom):
 *   - Context: Context Used / Context Window / Max Output
 *   - Thinking (only when the model supports reasoning): On/Off / Level / Supported
 *   - Tokens (Session Total): Input (incl. cache) / Output
 *   - Cache (Session Total): Cache Read / Cache Write
 *   - Agent Loop: Total Loops
 *
 * Data loads asynchronously via [ChatViewModel.loadSessionTokenStats] when the
 * sheet appears; we intentionally don't hold a live subscription — token
 * counters change per API call, not per keystroke, so pull-on-open is enough.
 */
@Composable
fun TokenUsageSheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    var stats by remember { mutableStateOf<ChatViewModel.SessionTokenStats?>(null) }
    val contextWindow = remember { viewModel.currentModelContextWindow }
    val maxOutput = remember { viewModel.currentModelMaxOutputTokens }
    val thinking = remember { viewModel.thinkingInfo() }

    LaunchedEffect(Unit) {
        stats = viewModel.loadSessionTokenStats()
    }

    StandardChatSheet(
        title = stringResource(R.string.token_usage_sheet_title),
        onDismiss = onDismiss,
        // T148: iOS uses .presentationDetents([.medium]) for the same sheet
        // (AIChatView.swift:508). Match that proportion on Android so the
        // half-screen feel is consistent — the token-usage view holds maybe
        // a screenful of stat rows max and looked overgrown at 90%.
        heightFraction = 0.5f,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val s = stats
            val onText = stringResource(R.string.common_on)
            val offText = stringResource(R.string.common_off)
            val yesText = stringResource(R.string.common_yes)
            val noText = stringResource(R.string.common_no)
            StatSection(title = stringResource(R.string.token_usage_section_context)) {
                StatRow(stringResource(R.string.token_usage_context_used), formatTokens(s?.context ?: 0))
                contextWindow?.let { StatRow(stringResource(R.string.token_usage_context_window), formatTokens(it)) }
                maxOutput?.let { StatRow(stringResource(R.string.token_usage_max_output), formatTokens(it)) }
            }

            thinking?.let { t ->
                StatSection(title = stringResource(R.string.token_usage_section_thinking)) {
                    StatRow(stringResource(R.string.token_usage_thinking_label), if (t.enabled) onText else offText)
                    if (t.enabled) StatRow(stringResource(R.string.token_usage_thinking_level), t.level)
                    StatRow(stringResource(R.string.token_usage_thinking_supported), if (t.supported) yesText else noText)
                }
            }

            StatSection(title = stringResource(R.string.token_usage_section_tokens)) {
                val inputTotal = (s?.input ?: 0L) + (s?.cacheRead ?: 0L) + (s?.cacheWrite ?: 0L)
                StatRow(stringResource(R.string.token_usage_input_with_cache), formatTokens(inputTotal))
                StatRow(stringResource(R.string.token_usage_output), formatTokens(s?.output ?: 0L))
            }

            StatSection(title = stringResource(R.string.token_usage_section_cache)) {
                StatRow(stringResource(R.string.token_usage_cache_read), formatTokens(s?.cacheRead ?: 0L))
                StatRow(stringResource(R.string.token_usage_cache_write), formatTokens(s?.cacheWrite ?: 0L))
                // [T-android-token-usage-cache-hit-rate] iOS parity. Hidden
                // rather than shown as 0.0% when nothing was cached, so a
                // provider that does no caching does not look like a cache
                // miss — see SessionTokenStats.cacheHitRate.
                s?.cacheHitRate?.let {
                    StatRow(
                        stringResource(R.string.token_usage_cache_hit_rate),
                        String.format(java.util.Locale.US, "%.1f%%", it),
                    )
                }
            }

            // [T-android-token-usage-output-speed] iOS parity. Always shown,
            // with "—" until a turn has streamed measurably — the row's
            // absence would otherwise read as "this build has no speed".
            StatSection(title = stringResource(R.string.token_usage_section_speed)) {
                val speed = s?.outputTokensPerSecond
                StatRow(
                    stringResource(R.string.token_usage_output_speed),
                    if (speed != null) String.format(java.util.Locale.US, "%.1f tok/s", speed) else "—",
                )
            }

            StatSection(title = stringResource(R.string.token_usage_section_agent_loop)) {
                StatRow(stringResource(R.string.token_usage_total_loops), (s?.loopCount ?: 0).toString())
            }
        }
    }
}

@Composable
private fun StatSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title.uppercaseForDisplay(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        content()
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = value,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

// iOS formatting: "%.1fM" >= 1M, "%.1fK" >= 1k, raw otherwise.
private fun formatTokens(n: Long): String = when {
    n >= 1_000_000L -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000L -> String.format("%.1fK", n / 1_000.0)
    else -> n.toString()
}

private fun formatTokens(n: Int): String = formatTokens(n.toLong())

// ─── Merged from UserAskTile.kt ──────────────────────────────────────────────────

/**
 * [T-android-user-ask-tile] The 100x65dp mini preview tile of a user_ask call
 * in the tool status bar: a miniature of the card instead of a bare wrench
 * (iOS T-user-ask-tile-rich, UserAskSummary.pendingTile / resolvedTile).
 *
 * Waiting: the card's title with the header chip and "1/3", the question (in
 * monospace for a confirm's command), then the options with what is picked so
 * far (○ / ●, ☐ / ☑ for multi-select) or the confirm's ⚠ impact line - tinted
 * blue / orange / red like the card. Answered: each question, with its answer
 * under it ("→ Pear", muted for a timeout / skip / cancel).
 */

// ── Pure model (unit-tested) ───────────────────────────────────────────────

internal enum class UserAskTileTitle { NEEDS_ANSWER, NEEDS_APPROVAL, HIGH_RISK, QUESTION_FOR_YOU }

internal data class UserAskTileLine(val kind: Kind, val text: String, val maxLines: Int) {
    sealed class Kind {
        data object Question : Kind()
        data object Command : Kind()
        data object Impact : Kind()
        data object Topic : Kind()
        data class Option(val marker: String, val selected: Boolean) : Kind()
        /** [text] is the user's own answer; empty = show the status's word instead. */
        data class Answer(val status: UserAskResultStatus, val muted: Boolean) : Kind()
    }
}

internal data class UserAskTileContent(
    val title: UserAskTileTitle,
    /** The question's header chip, shown after the title ("Needs your answer · Size"). */
    val header: String?,
    /** "1/3" on a multi-question ask, else empty. */
    val trailing: String,
    val lines: List<UserAskTileLine>,
)

internal object UserAskTileModel {
    /** The fixed tile height and the sizes it is laid out with (iOS UserAskSummaryView.tileSize). */
    const val TILE_HEIGHT = 65f
    const val TITLE_SIZE = 7f
    const val LINE_SIZE = 5.5f

    private fun flat(s: String?) = s.orEmpty().replace('\n', ' ').trim()

    fun cardTitle(q: UserAskQuestion): UserAskTileTitle = when {
        q.type == UserAskQuestionType.OPTIONS -> UserAskTileTitle.NEEDS_ANSWER
        q.risk == UserAskRisk.HIGH -> UserAskTileTitle.HIGH_RISK
        else -> UserAskTileTitle.NEEDS_APPROVAL
    }

    /** Waiting for an answer: question [current] as a miniature card. */
    fun pendingTile(questions: List<UserAskQuestion>, current: Int, answer: UserAskAnswer?): UserAskTileContent {
        val q = questions.getOrNull(current) ?: return UserAskTileContent(UserAskTileTitle.QUESTION_FOR_YOU, null, "", emptyList())
        val lines = mutableListOf<UserAskTileLine>()
        val text = flat(q.question)
        if (text.isNotEmpty()) {
            val kind = if (q.type == UserAskQuestionType.CONFIRM) UserAskTileLine.Kind.Command else UserAskTileLine.Kind.Question
            lines += UserAskTileLine(kind, text, 2)
        }
        if (q.type == UserAskQuestionType.CONFIRM) {
            flat(q.impact).takeIf { it.isNotEmpty() }?.let { lines += UserAskTileLine(UserAskTileLine.Kind.Impact, it, 2) }
        } else {
            val picked = answer?.selected.orEmpty().toSet()
            q.options.orEmpty().forEachIndexed { i, o ->
                val on = i in picked
                val marker = if (q.multiSelect) (if (on) "☑" else "☐") else (if (on) "●" else "○")
                lines += UserAskTileLine(UserAskTileLine.Kind.Option(marker, on), flat(o.label), 1)
            }
            flat(answer?.otherText).takeIf { it.isNotEmpty() }?.let {
                lines += UserAskTileLine(UserAskTileLine.Kind.Option(if (q.multiSelect) "☑" else "●", true), it, 1)
            }
        }
        return UserAskTileContent(
            title = cardTitle(q),
            header = q.header?.trim()?.takeIf { it.isNotEmpty() },
            trailing = if (questions.size > 1) "${current + 1}/${questions.size}" else "",
            lines = lines,
        )
    }

    /** Not the user's own choice: drawn neutral instead of in the tint. */
    fun isMuted(s: UserAskResultStatus): Boolean = when (s) {
        UserAskResultStatus.DENIED, UserAskResultStatus.TIMEOUT,
        UserAskResultStatus.DISMISSED, UserAskResultStatus.UNAVAILABLE -> true
        else -> false
    }

    /** Settled: each question's text, then its answer. A question with no result entry reads as skipped. */
    fun resolvedTile(questions: List<UserAskQuestion>, results: List<UserAskResolver.Parsed>): UserAskTileContent {
        val lines = mutableListOf<UserAskTileLine>()
        questions.forEachIndexed { i, q ->
            val topic = flat(q.question).ifEmpty { flat(q.header) }
            if (topic.isNotEmpty()) lines += UserAskTileLine(UserAskTileLine.Kind.Topic, topic, if (questions.size == 1) 2 else 1)
            val r = results.getOrNull(i)
            val status = r?.status ?: UserAskResultStatus.DISMISSED
            val given = if (status == UserAskResultStatus.ANSWERED || status == UserAskResultStatus.OTHER) {
                r?.answer.orEmpty().filter { it.isNotEmpty() }.joinToString(", ")
            } else {
                ""
            }
            lines += UserAskTileLine(UserAskTileLine.Kind.Answer(status, isMuted(status)), given, 1)
        }
        return UserAskTileContent(UserAskTileTitle.QUESTION_FOR_YOU, null, "", lines)
    }

    /** How many lines fit under the title: line height ~ 1.25 x size plus 1dp spacing (iOS capacity). */
    fun capacity(tileHeight: Float = TILE_HEIGHT, titleSize: Float = TITLE_SIZE, lineSize: Float = LINE_SIZE): Int {
        val usable = tileHeight - 4 - 3 - (titleSize * 1.25f + 1)
        return maxOf(1, (usable / (lineSize * 1.25f + 1)).toInt())
    }

    /**
     * Entries that fit [capacity] lines (each costs its maxLines; a two-line
     * entry squeezed into the last free line keeps one). What does not fit
     * becomes "+N", which needs a line of its own.
     */
    fun fitTile(lines: List<UserAskTileLine>, capacity: Int): Pair<List<UserAskTileLine>, Int> {
        if (lines.sumOf { it.maxLines } <= capacity) return lines to 0
        var budget = maxOf(0, capacity - 1)
        val shown = mutableListOf<UserAskTileLine>()
        for (l in lines) {
            if (budget <= 0) break
            val take = minOf(l.maxLines, budget)
            budget -= take
            shown += l.copy(maxLines = take)
        }
        return shown to (lines.size - shown.size)
    }
}

// ── Tile ──────────────────────────────────────────────────────────────────

/**
 * The user_ask tile's content, or [fallback] (the generic tool tile) for a
 * call that never became a question.
 */
@Composable
internal fun UserAskThumbContent(block: AssistantBlock, fallback: @Composable () -> Unit) {
    val asks by UserAskCenter.asks.collectAsState()
    val live = asks.values.firstOrNull { it.toolCallId == block.id }
    val request = remember(block.toolArgs) { UserAskParser.parse(block.toolArgs).getOrNull() }
    val stored = remember(block.content) { UserAskResolver.parseResults(block.content) }
    val phase = UserAskCardPhase.resolve(
        livePending = live?.state == UserAskState.PENDING,
        liveTerminal = live?.state?.isTerminal == true,
        hasResults = stored.isNotEmpty(),
        requestParses = request != null,
        status = block.toolStatus,
    )
    val (content, tint) = when (phase) {
        UserAskCardPhase.PENDING -> live!!.let { a ->
            val i = a.currentIndex.coerceIn(a.questions.indices)
            UserAskTileModel.pendingTile(a.questions, i, a.answers.getOrNull(i)) to UserAskTint.forQuestion(a.questions.getOrNull(i))
        }
        UserAskCardPhase.WAITING -> {
            val qs = request?.questions
            if (qs.isNullOrEmpty()) { fallback(); return }
            UserAskTileModel.pendingTile(qs, 0, null) to UserAskTint.forQuestion(qs.first())
        }
        UserAskCardPhase.RESOLVED_LIVE -> live!!.let {
            UserAskTileModel.resolvedTile(it.questions, UserAskResolver.parseResults(it.resultJson)) to UserAskTint.question
        }
        UserAskCardPhase.RESOLVED_STORED -> UserAskTileModel.resolvedTile(request!!.questions, stored) to UserAskTint.question
        UserAskCardPhase.STOPPED_STORED -> UserAskTileModel.resolvedTile(request!!.questions, emptyList()) to UserAskTint.question
        UserAskCardPhase.NOT_ASKED -> { fallback(); return }
    }
    UserAskTile(content, tint)
}

@Composable
private fun UserAskTile(content: UserAskTileContent, tint: Color) {
    val (lines, more) = UserAskTileModel.fitTile(content.lines, UserAskTileModel.capacity())
    val titleSize = UserAskTileModel.TITLE_SIZE.sp
    val lineSize = UserAskTileModel.LINE_SIZE.sp
    val title = stringResource(
        when (content.title) {
            UserAskTileTitle.NEEDS_ANSWER -> R.string.user_ask_needs_answer
            UserAskTileTitle.NEEDS_APPROVAL -> R.string.user_ask_needs_approval
            UserAskTileTitle.HIGH_RISK -> R.string.user_ask_high_risk
            UserAskTileTitle.QUESTION_FOR_YOU -> R.string.user_ask_question_for_you
        },
    ) + (content.header?.let { " · $it" } ?: "")
    val statusWords = statusWords()
    Column(modifier = Modifier.fillMaxSize().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 1.dp)) {
            Text(
                title, fontSize = titleSize, lineHeight = titleSize * 1.25f, fontWeight = FontWeight.Bold,
                color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (content.trailing.isNotEmpty()) {
                Spacer(Modifier.width(2.dp))
                // Its own line height: the theme's would make the title row
                // twice as tall as the title.
                Text(
                    content.trailing, fontSize = lineSize, lineHeight = lineSize * 1.25f,
                    fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1,
                )
            }
        }
        for (l in lines) {
            Text(
                tileLineText(l, tint, statusWords),
                fontSize = lineSize, lineHeight = lineSize * 1.25f,
                maxLines = l.maxLines, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 1.dp),
            )
        }
        if (more > 0) {
            Text("+$more", fontSize = lineSize, lineHeight = lineSize * 1.25f, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.45f))
        }
    }
}

@Composable
private fun statusWords(): Map<UserAskResultStatus, String> = mapOf(
    UserAskResultStatus.ANSWERED to stringResource(R.string.user_ask_answered),
    UserAskResultStatus.OTHER to stringResource(R.string.user_ask_answered),
    UserAskResultStatus.DELEGATED to stringResource(R.string.user_ask_left_to_minis),
    UserAskResultStatus.APPROVED to stringResource(R.string.user_ask_allow),
    UserAskResultStatus.DENIED to stringResource(R.string.user_ask_cancel),
    UserAskResultStatus.TIMEOUT to stringResource(R.string.user_ask_timed_out),
    UserAskResultStatus.DISMISSED to stringResource(R.string.user_ask_skipped),
    UserAskResultStatus.UNAVAILABLE to stringResource(R.string.user_ask_could_not_ask),
)

private fun tileLineText(l: UserAskTileLine, tint: Color, words: Map<UserAskResultStatus, String>): AnnotatedString =
    buildAnnotatedString {
        val white = Color.White
        when (val k = l.kind) {
            UserAskTileLine.Kind.Question ->
                withStyle(SpanStyle(color = white.copy(alpha = 0.9f), fontWeight = FontWeight.Medium)) { append(l.text) }
            UserAskTileLine.Kind.Command ->
                withStyle(SpanStyle(color = white.copy(alpha = 0.85f), fontFamily = FontFamily.Monospace)) { append(l.text) }
            UserAskTileLine.Kind.Impact -> {
                withStyle(SpanStyle(color = tint)) { append("⚠ ") }
                withStyle(SpanStyle(color = white.copy(alpha = 0.6f))) { append(l.text) }
            }
            UserAskTileLine.Kind.Topic ->
                withStyle(SpanStyle(color = white.copy(alpha = 0.6f))) { append(l.text) }
            is UserAskTileLine.Kind.Option -> {
                withStyle(SpanStyle(color = if (k.selected) tint else white.copy(alpha = 0.45f))) { append(k.marker + " ") }
                withStyle(
                    SpanStyle(
                        color = if (k.selected) tint else white.copy(alpha = 0.7f),
                        fontWeight = if (k.selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                ) { append(l.text) }
            }
            is UserAskTileLine.Kind.Answer -> {
                withStyle(SpanStyle(color = white.copy(alpha = 0.35f))) { append("→ ") }
                withStyle(SpanStyle(color = if (k.muted) white.copy(alpha = 0.45f) else tint, fontWeight = FontWeight.Medium)) {
                    append(l.text.ifEmpty { words[k.status].orEmpty() })
                }
            }
        }
    }
