package com.openminis.app.ui.chat

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.R
import com.openminis.app.scheduled.ScheduledTaskDetailModel
import com.openminis.app.scheduled.ScheduledTaskManager
import com.openminis.app.scheduled.ScheduledTaskMarker
import com.openminis.app.scheduled.ScheduledTaskStore
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.theme.ChatColors
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** [T-android-scheduled-task-card] Amber — a timer, not an agent. */
internal val ScheduledAccent = Color(0xFFF59E0B)

/**
 * [T-android-scheduled-task-card / -next-fire / -cancel-from-card]
 * A fired scheduled task, rendered as a card instead of a user bubble.
 *
 * Three things the plain bubble could not say:
 *  - that a TASK wrote this, not the user;
 *  - WHEN it will run next, which until now was invisible after creation —
 *    the user had to go to Settings and read the list to find out;
 *  - that it can be stopped from right here, without going to find it.
 *
 * Cancelling asks first. A scheduled task is something the user deliberately
 * set up, so the cost of an accidental tap is not symmetric with the cost of
 * one extra confirmation.
 */
@Composable
internal fun ScheduledTaskCard(
    marker: ScheduledTaskMarker,
    modifier: Modifier = Modifier,
    // [T-scheduled-task-detail] The chat this card is shown in; the detail
    // page uses it for "this chat" wording and links to other chats.
    hostSessionId: String? = null,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    var confirming by remember { mutableStateOf(false) }
    // Local echo: the card must stop claiming a next run the moment the user
    // cancels from it; it then says "Schedule cancelled" in that slot.
    var cancelled by remember(marker.taskId) { mutableStateOf(false) }
    // [T-scheduled-task-detail] Tapping the card opens the task's detail page.
    var showingDetail by remember { mutableStateOf(false) }

    // [T-scheduled-task-detail] `now` decides whether the envelope's next-fire
    // time is still ahead. Read only at composition, a card on screen kept
    // "Next: <that time>" after the fire had happened, until something else
    // recomposed it. [T-scheduled-card-subline] The line now says "in 4 min.",
    // so it is also re-read at each minute boundary while that time is ahead.
    val now by produceState(System.currentTimeMillis(), marker.nextFireAtMs) {
        val next = marker.nextFireAtMs ?: return@produceState
        while (true) {
            val wait = next - System.currentTimeMillis()
            if (wait <= 0) break
            delay(ScheduledTaskDetailModel.nextTickMs(wait))
            value = System.currentTimeMillis()
        }
    }
    // [T-scheduled-card-subline] Offer Cancel only while the task is still a
    // live timer, as iOS does (`isCancellable`). The envelope is a snapshot, so
    // a card scrolled back to from history offered to cancel a task that had
    // long stopped. The store is the live truth here: it persists, so unlike
    // iOS's in-memory registry it stays right across a relaunch.
    val store = remember { ScheduledTaskStore(context.applicationContext) }
    val live by remember(marker.taskId) {
        store.observe().map { tasks -> tasks.any { it.id == marker.taskId && it.enabled } }
    }.collectAsState(initial = remember(marker.taskId) { store.get(marker.taskId)?.enabled == true })

    // [T-scheduled-card-subline] Port of the iOS card's subline.
    val phrases = ScheduledTaskDetailModel.CardPhrases(
        run = stringResource(R.string.scheduled_card_run),
        left = stringResource(R.string.scheduled_card_left),
        next = stringResource(R.string.scheduled_card_next),
        complete = stringResource(R.string.scheduled_card_task_complete),
        cancelled = stringResource(R.string.scheduled_card_cancelled),
    )
    // A task that is no longer live withdraws a next fire still ahead.
    val subline = ScheduledTaskDetailModel.cardSubline(marker, cancelled, now, phrases, live = live) {
        relativeFireTime(context, it, now)
    }

    // [T-sched-card-metrics] The sub-agent card's skeleton and metrics
    // (TaskCardFrame). This card used to be much taller than the sub-agent
    // card beside it: its texts set no line heights (inheriting the theme's
    // 24sp body line height) and its "Cancel this scheduled task" TextButton,
    // with Material's 40dp minimum height, set the card's height on its own.
    // Cancel is now a 24dp disc in the same slot as the sub-agent card's Stop,
    // before the chevron, so it cannot push the card taller.
    val cancelLabel = stringResource(R.string.scheduled_card_cancel)
    TaskCardFrame(
        accent = ScheduledAccent,
        onClick = { showingDetail = true },
        onClickLabel = null,
        modifier = modifier,
        icon = {
            Icon(
                Icons.Default.Schedule,
                contentDescription = null,
                tint = ScheduledAccent,
                modifier = Modifier.size(TaskCardMetrics.iconGlyph),
            )
        },
        action = if (live && !cancelled) {
            {
                TaskCardActionDisc(
                    label = cancelLabel,
                    background = ScheduledAccent,
                    onClick = { confirming = true },
                ) { TaskCardActionGlyph(Icons.Default.AlarmOff) }
            }
        } else {
            null
        },
    ) {
        TaskCardTitleRow {
            TaskCardChip(stringResource(R.string.scheduled_card_label), ScheduledAccent)
            TaskCardTitle(marker.label.ifEmpty { marker.prompt.take(40) })
        }
        // Always one status line, like the sub-agent card, so every card in the
        // flow keeps the same height. [T-scheduled-card-subline] It is never
        // empty for a fire: the prompt's first line is the last fallback.
        TaskCardStatusRow {
            Text(
                subline,
                fontSize = TaskCardMetrics.subtitleSize,
                lineHeight = TaskCardMetrics.subtitleLineHeight,
                color = ChatColors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // [T-scheduled-card-subline] Exactly the row's height, the
                // glyphs laid out unclipped and centred: the fallback is a
                // prompt's first line, which may open with an emoji taller
                // than the CJK line the row is sized for.
                modifier = Modifier
                    .height(taskCardStatusLineHeight())
                    .wrapContentHeight(Alignment.CenterVertically, unbounded = true),
            )
        }
    }

    if (showingDetail) {
        ScheduledTaskDetailSheet(
            marker = marker,
            hostSessionId = hostSessionId,
            onDismiss = { showingDetail = false },
        )
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.scheduled_card_cancel_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.scheduled_card_cancel_confirm_body,
                        marker.label.ifEmpty { marker.prompt.take(40) },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // Disable rather than delete: the run history stays
                    // readable, and the task is recoverable from Settings.
                    ScheduledTaskManager(context.applicationContext as android.app.Application)
                        .setEnabled(marker.taskId, false)
                    cancelled = true
                    confirming = false
                }) { Text(stringResource(R.string.scheduled_card_cancel_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.scheduled_card_cancel_confirm_keep))
                }
            },
        )
    }
}

/**
 * [T-scheduled-card-subline] "in 30 sec." / "in 4 min." / "in 2 hr." — iOS
 * `RelativeDateTimeFormatter` with `.short` units, which the iOS card uses for
 * "Next %@". ICU's short style gives the same forms; DateUtils does not (its
 * future form is sentence-cased, "In 4 min.", wrong mid-line). Units are
 * picked by [ScheduledTaskDetailModel.relativeUnit].
 */
internal fun relativeFireTime(context: android.content.Context, atMs: Long, nowMs: Long): String {
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val f = android.icu.text.RelativeDateTimeFormatter.getInstance(
        android.icu.util.ULocale.forLocale(locale), null,
        android.icu.text.RelativeDateTimeFormatter.Style.SHORT,
        android.icu.text.DisplayContext.CAPITALIZATION_NONE,
    )
    val sec = ((atMs - nowMs) / 1000L).coerceAtLeast(0L)
    val (value, unit) = ScheduledTaskDetailModel.relativeUnit(sec)
    val icuUnit = when (unit) {
        ScheduledTaskDetailModel.RelativeUnit.SECOND -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.SECONDS
        ScheduledTaskDetailModel.RelativeUnit.MINUTE -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MINUTES
        ScheduledTaskDetailModel.RelativeUnit.HOUR -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.HOURS
        ScheduledTaskDetailModel.RelativeUnit.DAY -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.DAYS
        ScheduledTaskDetailModel.RelativeUnit.WEEK -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.WEEKS
        ScheduledTaskDetailModel.RelativeUnit.MONTH -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MONTHS
        ScheduledTaskDetailModel.RelativeUnit.YEAR -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.YEARS
    }
    return f.format(value.toDouble(), android.icu.text.RelativeDateTimeFormatter.Direction.NEXT, icuUnit)
}

// ─── Merged from ThinkingFullDialog.kt ──────────────────────────────────────────────────

/**
 * [#467] "Show full thinking": the whole thinking text of a finished block
 * in a full-screen dialog (the style of the earlier over-100K viewer it
 * replaces), never expanded inside the chat list.
 *
 * The text is split by [ThinkingChunking] off the main thread and shown as
 * lazy items, a few chunks at first and a batch more each time the end of
 * what is shown scrolls into view. Each batch's first frame is timed; a slow
 * one switches to one chunk per tap of "Load more" ([ThinkingChunking.paceAfter]),
 * so a device that cannot keep up is never frozen by automatic loading.
 * "Copy All" copies the full text, whatever is on screen; the shown text is
 * selectable.
 *
 * [content] is the text when the dialog opened: the entry is only offered
 * once the block has finished streaming.
 */
@Composable
fun ThinkingFullDialog(content: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val thinkingBlue = Color(0xFF007AFF)
    val chunks by produceState<List<ThinkingChunking.Chunk>?>(null, content) {
        value = withContext(Dispatchers.Default) { ThinkingChunking.chunks(content) }
    }
    var revealed by remember(content) { mutableIntStateOf(0) }
    var pacing by remember(content) { mutableStateOf(ThinkingChunking.Pacing()) }
    // Bumped per batch; the watchdog below times that batch's first frame.
    var batchStartedAt by remember(content) { mutableStateOf(0L) }
    LaunchedEffect(chunks) {
        chunks?.let { revealed = ThinkingChunking.initialCount(it) }
    }
    LaunchedEffect(batchStartedAt) {
        if (batchStartedAt == 0L) return@LaunchedEffect
        withFrameNanos { }
        pacing = ThinkingChunking.paceAfter(pacing, System.currentTimeMillis() - batchStartedAt)
    }
    val reveal = {
        chunks?.let { c ->
            if (ThinkingChunking.hasMore(revealed, c.size)) {
                batchStartedAt = System.currentTimeMillis()
                revealed = ThinkingChunking.nextCount(revealed, c.size, pacing)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.thinking_full_title),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = thinkingBlue,
                        )
                        Text(
                            text = stringResource(
                                R.string.thinking_full_char_count,
                                NumberFormat.getIntegerInstance().format(content.length),
                            ),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                    MinisTextButton(onClick = {
                        try {
                            clipboard.setText(AnnotatedString(ThinkingChunking.copyText(content)))
                            Toast.makeText(context, R.string.terminal_copied_all, Toast.LENGTH_SHORT).show()
                        } catch (e: RuntimeException) {
                            // A text past the Binder transaction limit cannot go to the clipboard.
                            Toast.makeText(context, R.string.thinking_full_copy_failed, Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text(text = stringResource(R.string.terminal_copy_all))
                    }
                    MinisTextButton(onClick = onDismiss) {
                        Text(text = stringResource(android.R.string.ok))
                    }
                }
                HorizontalDivider()
                val list = chunks
                if (list == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else {
                    val state = rememberLazyListState()
                    SelectionContainer(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        LazyColumn(
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 12.dp,
                                bottom = 32.dp,
                            ),
                        ) {
                            items(count = minOf(revealed, list.size), key = { it }) { i ->
                                Text(
                                    text = ThinkingChunking.chunkText(content, list, i).removeSuffix("\n"),
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                )
                            }
                            if (ThinkingChunking.hasMore(revealed, list.size)) {
                                item(key = "more") {
                                    // The end of what is shown came into view: the next batch
                                    // (iOS LazyRevealChunks footer onAppear).
                                    if (pacing.auto) LaunchedEffect(revealed) { reveal() }
                                    Row(
                                        horizontalArrangement = Arrangement.Center,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                    ) {
                                        MinisTextButton(onClick = { reveal() }) {
                                            Text(text = stringResource(R.string.thinking_full_load_more))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Merged from StopChoiceDialog.kt ──────────────────────────────────────────────────

/**
 * [T-stop-choice] "What should stop?" - shown instead of an immediate Stop
 * when the conversation has background work a Stop would also end (sub
 * agents, scheduled tasks; [StopSummary.hasBackground]).
 *
 * Lists what is running and how many of each, then offers:
 *  - Stop Current Tool Only ([onToolOnly], the safe primary choice, nearest the
 *    thumb) - only when this chat's own turn is running ([canStopToolOnly]);
 *    with only background work there is no "current tool" to stop;
 *  - Stop All ([onStopAll], destructive);
 *  - Cancel.
 *
 * Also used by New Chat's "a task is running" guard, with its own title and
 * button labels and the [body] override.
 *
 * Drawn as the iOS action sheet the same choice uses (ChatSendButton
 * `.confirmationDialog`): a card at the bottom with the centred title and
 * message over full-width, centred buttons divided by hairlines - the safe
 * choice in the accent colour, Stop All in red - and Cancel, bold, on a card
 * of its own below. The Material alert it replaces stacked three text
 * buttons at the right edge under a left-aligned body.
 */
@Composable
internal fun StopChoiceDialog(
    summary: StopSummary,
    canStopToolOnly: Boolean,
    onDismiss: () -> Unit,
    onToolOnly: () -> Unit,
    onStopAll: () -> Unit,
    title: String = stringResource(R.string.chat_stop_choice_title),
    toolOnlyText: String = stringResource(R.string.chat_stop_choice_tool_only),
    stopAllText: String = stringResource(R.string.chat_stop_choice_all),
    body: String? = null,
) {
    val lines = buildList {
        if (canStopToolOnly) {
            add(
                if (summary.runningTools.isEmpty()) {
                    stringResource(R.string.chat_stop_choice_current_reply)
                } else {
                    stringResource(R.string.chat_stop_choice_current_tools, summary.runningTools.distinct().joinToString(", "))
                },
            )
        }
        if (summary.subAgentsRunning > 0) {
            add(pluralStringResource(R.plurals.chat_stop_choice_subagents, summary.subAgentsRunning, summary.subAgentsRunning))
        }
        if (summary.subAgentsQueued > 0) {
            add(pluralStringResource(R.plurals.chat_stop_choice_subagents_queued, summary.subAgentsQueued, summary.subAgentsQueued))
        }
        if (summary.scheduledTasks > 0) {
            add(pluralStringResource(R.plurals.chat_stop_choice_scheduled, summary.scheduledTasks, summary.scheduledTasks))
        }
    }
    val footer = buildList {
        if (canStopToolOnly) add(stringResource(R.string.chat_stop_choice_tool_only_footer))
        add(stringResource(R.string.chat_stop_choice_all_footer))
    }.joinToString(" ")
    val message = buildString {
        body?.let { append(it).append("\n\n") }
        append(lines.joinToString("\n"))
        if (lines.isNotEmpty()) append("\n\n")
        append(footer)
    }
    StopActionSheet(
        title = title,
        message = message,
        onDismiss = onDismiss,
        actions = buildList {
            if (canStopToolOnly) add(SheetAction(toolOnlyText, destructive = false, onToolOnly))
            add(SheetAction(stopAllText, destructive = true, onStopAll))
        },
    )
}

private class SheetAction(val label: String, val destructive: Boolean, val onClick: () -> Unit)

@Composable
private fun StopActionSheet(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    actions: List<SheetAction>,
) {
    val dark = ChatColors.isDark
    // iOS system colours for an action sheet: the grouped card, the tint and
    // the destructive red, in each appearance.
    val card = if (dark) Color(0xFF2C2C2E) else Color(0xFFF9F9F9)
    val accent = if (dark) Color(0xFF0A84FF) else Color(0xFF007AFF)
    val red = if (dark) Color(0xFFFF453A) else Color(0xFFFF3B30)
    val hairline = ChatColors.separator
    val shape = RoundedCornerShape(14.dp)
    val noRipple = remember { MutableInteractionSource() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Full-window so the sheet can sit at the bottom; a tap above it
        // dismisses, as outside an iOS action sheet.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(interactionSource = noRipple, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    // Taps on the sheet itself do not dismiss it.
                    .clickable(interactionSource = noRipple, indication = null, onClick = {}),
            ) {
                // [T-stop-sheet-fits] In landscape or at a large font scale the
                // sheet can be taller than the window. Cancel is measured first
                // (this card is weighted, not filling), and inside the card the
                // buttons come before the title and message, which scroll in
                // what is left - as the platform alert dialog does. Before,
                // the bottom buttons laid out past the window edge.
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .clip(shape)
                        .background(card),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .verticalScroll(androidx.compose.foundation.rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ChatColors.secondaryText,
                            textAlign = TextAlign.Center,
                        )
                        if (message.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = message,
                                fontSize = 13.sp,
                                lineHeight = 17.sp,
                                color = ChatColors.secondaryText,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    for (action in actions) {
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(hairline))
                        SheetButton(action.label, if (action.destructive) red else accent, FontWeight.Normal) {
                            action.onClick()
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth().clip(shape).background(card)) {
                    SheetButton(stringResource(R.string.cancel), accent, FontWeight.SemiBold, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun SheetButton(label: String, color: Color, weight: FontWeight, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 19.sp,
            fontWeight = weight,
            color = color,
            textAlign = TextAlign.Center,
        )
    }
}
