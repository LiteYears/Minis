package com.openminis.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openminis.app.R
import com.openminis.app.agent.Level
import com.openminis.app.agent.ToolLoopDetector
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.BPETokenizer
import com.openminis.app.data.ContextOffload
import com.openminis.app.data.ContextPolicy
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.i18n.uppercaseForDisplay
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.terminal.MinisOpenUrlBroker
import com.openminis.app.terminal.MinisUrlMarker
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.FileEditTool
import com.openminis.app.tools.FileReadTool
import com.openminis.app.tools.FileWriteTool
import com.openminis.app.tools.MemoryTools
import com.openminis.app.tools.ReadImageTool
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.ui.components.DialogTextField
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.decorativePhase
import com.openminis.app.ui.components.decorativePingPong
import com.openminis.app.ui.components.rememberDecorativeTick
import com.openminis.app.ui.sandbox.FileItem
import com.openminis.app.ui.sandbox.ImagePreview
import com.openminis.app.ui.sandbox.TextPreview
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SettingsSwitch
import com.openminis.app.ui.settings.SkillRowItem
import com.openminis.app.ui.settings.SkillSourceUrl
import com.openminis.app.ui.theme.ChatColors
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject

// [T-android-split-chat] Self-contained "thinking / streaming" dot indicators
// extracted verbatim from ChatScreen.kt. `internal` so the chat package can
// still reference them. No logic change — code moved as-is.

@Composable
internal fun BouncingDots(color: Color) {
    // [T-android-decorative-anim-perf] Was three infinite transitions read in
    // composition and animated through PADDING — a layout-phase property, so
    // every frame recomposed and re-laid-out the row. Now one shared 30 fps
    // tick, read inside the layer lambda, moving translationY only.
    val tick = rememberDecorativeTick()
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { i ->
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .graphicsLayer {
                        val p = decorativePingPong(decorativePhase(tick.value, 700, i * 120))
                        translationY = -2.dp.toPx() * p
                    }
                    .background(color, CircleShape),
            )
        }
    }
}

// iOS-style streaming "..." after tool title — 3 dots bouncing inline with text
@Composable
internal fun StreamingDotsText() {
    // [T-android-decorative-anim-perf] `Modifier.offset(y = …)` in its
    // non-lambda form is a layout-phase read; the dots re-laid-out the pill
    // title row every frame. Layer translation instead, from the shared tick.
    val tick = rememberDecorativeTick()
    Row {
        repeat(3) { i ->
            Text(
                text = ".",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.graphicsLayer {
                    val p = decorativePingPong(decorativePhase(tick.value, 700, i * 120))
                    translationY = -3.dp.toPx() * p
                },
            )
        }
    }
}

// ─── Compaction progress ──────────────────────────────────────────────────────

/**
 * [T-android-compact-progress] Live status while a compaction runs, with a
 * Cancel affordance.
 *
 * Compaction used to present as a single unchanging flag for however long it
 * took — which on a slow or rate-limited model could be many minutes, and is
 * indistinguishable from a hang. This ticks once a second so the elapsed count
 * visibly moves, and names the split segment when the run had to fall back to
 * summarising halves, so "still working" is legible rather than inferred.
 *
 * [T-android-compact-progress-divider] Drawn as the same centred divider the
 * finished compaction leaves behind ([FallbackInfoBlock], iOS
 * compactDividerRow / systemDividerRow with `isCompactLoading`): a thin rule
 * on each side, a small spinner, the status and Cancel in the middle. The
 * spinner gives way to the fold icon and the "N messages compacted" divider
 * (whose info icon opens the summary) when the run ends. Cancel stays in the
 * row: unlike iOS, the composer has no Stop button while compacting.
 */
@Composable
internal fun CompactProgressIndicator(
    progress: ChatViewModel.CompactProgress,
    onCancel: () -> Unit,
) {
    // Re-reads the clock every second; the changing value is what makes the
    // row demonstrably alive.
    var elapsedSec by remember(progress.startedAtMs) { mutableStateOf(0) }
    LaunchedEffect(progress.startedAtMs) {
        while (true) {
            elapsedSec = ((System.currentTimeMillis() - progress.startedAtMs) / 1000L).toInt()
            kotlinx.coroutines.delay(1000)
        }
    }
    val fg = ChatColors.secondaryText
    val rule = ChatColors.separator

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f).height(1.dp).background(rule))
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                color = fg,
                strokeWidth = 1.5.dp,
            )
            Text(
                text = if (progress.depth > 0) {
                    // Only surfaced once a split actually happened — saying
                    // "segment 1" on the common single-call path would imply a
                    // complexity that isn't there.
                    stringResource(
                        R.string.compact_progress_split,
                        elapsedSec,
                        progress.callsIssued,
                        progress.callBudget,
                    )
                } else {
                    stringResource(R.string.compact_progress, elapsedSec)
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = fg,
                maxLines = 1,
                // [T-compact-progress-cancel-fits] Weighted (not filling) so
                // Cancel - the only way to stop a compaction - is measured
                // first and the status ellipsizes into what is left. Unweighted,
                // a long status (German split text, large font) took the whole
                // width and left Cancel at zero.
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = stringResource(R.string.cancel),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
        Box(modifier = Modifier.weight(1f).height(1.dp).background(rule))
    }
}

// ─── Typing Indicator (three dots pulsing) ────────────────────────────────────

/**
 * [T-android-retry-badge] With [retryAttempt] > 0 and a click handler, a
 * yellow "↻ n/[retryMax]" badge leads the line while an automatic retry is in
 * progress; it goes when the retried request answers, the ladder ends or the
 * user stops. The read-only sub-agent transcript passes nothing.
 */
@Composable
internal fun TypingIndicator(
    retryAttempt: Int = 0,
    retryMax: Int = 0,
    onRetryBadgeClick: (() -> Unit)? = null,
) {
    // [T-android-decorative-anim-perf] Shared 30 fps tick instead of a private
    // 90 fps infinite transition; the layer-lambda read below was already right.
    val tick = rememberDecorativeTick()
    // Live Soul name → "<custom name> is thinking…" when the user renamed
    // the assistant in Soul settings. SoulStore.cachedMetadata is a StateFlow
    // that's updated on save (SoulSettingsScreen) and at app start
    // (MinisApp.onCreate via refreshCache); collectAsState makes Compose
    // recompose the indicator immediately when it changes.
    val soulMeta by com.openminis.app.agent.SoulStore.cachedMetadata.collectAsState()
    val soulName = soulMeta.name.trim().ifEmpty { "Minis" }

    Row(
        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (retryAttempt > 0 && onRetryBadgeClick != null) {
            androidx.compose.foundation.layout.Box(modifier = Modifier.align(Alignment.CenterVertically)) {
                RetryBadge(attempt = retryAttempt, maxAttempts = retryMax, onClick = onRetryBadgeClick)
            }
        }
        Text(
            text = stringResource(R.string.chat_typing_indicator, soulName),
            fontSize = 15.sp,
            color = ChatColors.tertiaryText,
        )
        // Animated bouncing dots
        val dots = listOf(".", ".", ".")
        dots.forEachIndexed { index, dot ->
            Text(
                text = dot,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = ChatColors.tertiaryText,
                modifier = Modifier.graphicsLayer {
                    val p = decorativePingPong(decorativePhase(tick.value, 800, index * 150))
                    translationY = -6.dp.toPx() * p
                },
            )
        }
    }
}

// ─── Merged from ChatViewModelSlashExt.kt ──────────────────────────────────────────────────

// [T-android-split-chat] Slash-menu STATE methods (filter / open / dismiss /
// menu-state) extracted from ChatViewModel as extension functions. The action
// dispatcher executeSlashCommand stays in the class (entangled with compact/
// thinking/memory). 7 slash-state fields flipped private->internal. Verbatim.


/** Filter available commands by current filter text, with dynamic
 *  localized subtitles. Subtitles read strings.xml via the injected
 *  Context so the panel respects system locale (T241).
 *
 *  [T-skill-slash a88ea8f9] After the built-in rows, append every
 *  enabled installed Skill as a `/<skill-name>` slash entry — tap
 *  fills the composer with `/<name>` and dismisses the menu (see
 *  [executeSlashCommand]). Disabled skills are hidden so toggling a
 *  skill off in Settings naturally removes it from the menu without
 *  uninstalling it.
 *
 *  [T-session-skill-toggle-override-global-android] Enablement is
 *  resolved per-session, not globally: [SkillRepository.isEnabledForSession]
 *  returns the session-scoped override when one exists and falls back to
 *  the global toggle otherwise. The previous code filtered on the raw
 *  global `isEnabled` flag, so a skill turned off globally but turned ON
 *  for this session never appeared in the `/` picker even though the
 *  prompt-injection path ([SkillRepository.skillPromptFragment]) already
 *  honored the override — the agent knew about the skill but the user
 *  couldn't surface it via slash.
 */
internal fun ChatViewModel.filteredSlashCommands(): List<SlashCommand> {
    val filter = _slashFilter.value.lowercase()
    val base = availableSlashCommands.map { cmd ->
        when (cmd.id) {
            "compact" -> cmd.copy(
                subtitle = context.getString(R.string.slash_compact_subtitle),
            )
            "memory" -> cmd.copy(
                subtitle = context.getString(
                    if (_memoryEnabled.value) R.string.slash_memory_writes_on
                    else R.string.slash_memory_writes_off,
                ),
            )
            "thinking" -> cmd.copy(
                subtitle = if (!currentModelSupportsReasoning) {
                    context.getString(R.string.slash_thinking_unsupported)
                } else {
                    context.getString(
                        R.string.slash_thinking_subtitle,
                        _thinkingLevel.value.localizedName(context),
                    )
                },
            )
            "clear" -> cmd.copy(
                subtitle = context.getString(R.string.slash_clear_subtitle),
            )
            else -> cmd
        }
    }
    val sid = activeSessionId
    val skillRows: List<SlashCommand> = skillRepository?.skills?.value
        ?.filter { skillRepository.isEnabledForSession(it.id, sid) }
        ?.sortedBy { it.name.lowercase() }
        ?.map { skill ->
            val trimmed = skill.description.trim()
            val sub = if (trimmed.isNotEmpty()) trimmed else "Skill · v${skill.version}"
            SlashCommand(
                id = "skill:${skill.id}",
                icon = Icons.Outlined.Extension,
                title = skill.name,
                subtitle = sub,
                isSkill = true,
            )
        } ?: emptyList()
    // [T-mcp-integration-android] MCP servers appear in the / picker too,
    // tagged [mcp] with a wrench icon to distinguish them from skills (⚡).
    // All servers enabled for this session are shown (not just the Top-20
    // disclosed in the system prompt). Tapping fills the composer with the
    // server name; discovery/call happens model-side via minis-mcp-cli.
    val mcpRows: List<SlashCommand> = mcpRepository?.servers?.value
        ?.filter { mcpRepository.isEnabledForSession(it.id, sid) }
        ?.sortedBy { it.id.lowercase() }
        ?.map { server ->
            val note = server.note?.trim().orEmpty()
            val sub = if (note.isNotEmpty()) "[mcp] $note" else "[mcp] ${server.transportSummary}"
            SlashCommand(
                id = "mcp:${server.id}",
                icon = Icons.Outlined.Build,
                title = server.id,
                subtitle = sub,
                isMcp = true,
            )
        } ?: emptyList()
    val all = base + skillRows + mcpRows
    return if (filter.isEmpty()) all else all.filter { it.title.lowercase().contains(filter) }
}

/**
 * Update slash-menu state based on composer text. Call from the composer's
 * `onValueChange`. Mirrors iOS `updateSlashMenuState()`.
 */
internal fun ChatViewModel.updateSlashMenuState(text: String) {
    // [T-android-slash-menu-align-ios-prepend] Over-content mode: the
    // composer reads `/<typed-filter> <saved-original>` (see
    // showSlashMenuOverInput). The filter is the token between the leading
    // "/" and the first whitespace — everything after that is the user's
    // preserved original text and must NOT join the filter, or the menu
    // would search the whole `/<filter> <original>` string and never match.
    // Mirrors iOS updateSlashMenuState's over-input branch.
    if (savedInputBeforeSlash != null) {
        val starts = text.startsWith("/") || text.startsWith("／")
        if (!starts) {
            // The leading "/" was deleted — close the menu and leave
            // over-content mode WITHOUT restoring (the user is editing the
            // composer directly now). Drop the saved original so we don't
            // later re-strip a prefix that no longer exists.
            savedInputBeforeSlash = null
            _showSlashMenu.value = false
            _slashMenuSelectedIndex.value = -1
            return
        }
        val afterSlash = text.drop(1)
        val end = afterSlash.indexOfFirst { it.isWhitespace() }.let { if (it < 0) afterSlash.length else it }
        _slashFilter.value = afterSlash.substring(0, end)
        _showSlashMenu.value = true
        _slashMenuSelectedIndex.value = -1
        return
    }
    // Typed-"/" path (no saved original): the whole input is the slash
    // query. Accept full-width "／" (U+FF0F) too — see tryExecuteInputAsSlashCommand.
    val starts = text.startsWith("/") || text.startsWith("／")
    if (starts && !text.contains("\n") && text.length < 30) {
        _showSlashMenu.value = true
        _slashFilter.value = text.drop(1)
        _slashMenuSelectedIndex.value = -1
    } else {
        _showSlashMenu.value = false
        _slashMenuSelectedIndex.value = -1
    }
}

/**
 * "/" button tapped. If composer is empty, set input to "/"; otherwise save
 * and show the menu directly without clobbering existing input.
 * Returns the (possibly updated) input text.
 */
internal fun ChatViewModel.showSlashMenuOverInput(currentInput: String): String {
    val trimmed = currentInput.trim()
    // [T-android-slash-menu-align-ios-prepend] Boundary: the composer text
    // already starts with "/" (the user hand-typed a slash command). Don't
    // prepend another "/ " (which would make "/ /foo") — treat it exactly
    // like the typed-"/" path: open the menu over the existing text and let
    // updateSlashMenuState derive the filter from it. No saved original.
    if (currentInput.startsWith("/") || currentInput.startsWith("／")) {
        savedInputBeforeSlash = null
        updateSlashMenuState(currentInput)
        return currentInput
    }
    return if (trimmed.isEmpty()) {
        // Empty composer: behave like typing "/" — the input becomes the
        // slash query itself, so we're NOT "over content" (no saved text).
        savedInputBeforeSlash = null
        _showSlashMenu.value = true
        _slashFilter.value = ""
        _slashMenuSelectedIndex.value = -1
        "/"
    } else {
        // [T-android-slash-menu-align-ios-prepend] iOS parity: PREPEND "/ "
        // so the composer reads `/ <original>`, save the original for
        // dismiss/skill-prepend, and land the caret right after the slash
        // (index 1) so the user types into the menu's filter slot —
        // identical to having typed "/" at the start themselves.
        savedInputBeforeSlash = currentInput
        _showSlashMenu.value = true
        _slashFilter.value = ""
        _slashMenuSelectedIndex.value = -1
        _pendingCaret.value = 1
        "/ $currentInput"
    }
}

/**
 * Dismiss the slash menu. Returns the text the composer should restore to —
 * either the previously-saved text (if opened via button over content) or
 * an empty string (if opened by typing "/").
 */
internal fun ChatViewModel.dismissSlashMenu(currentInput: String): String {
    // [T-android-slash-menu-align-ios-prepend] iOS parity:
    // - Over-content (saved != null): restore the saved ORIGINAL, stripping
    //   the "/ " prefix showSlashMenuOverInput injected. We restore `saved`,
    //   NOT the live `currentInput` (which is "/ <original>" possibly with a
    //   filter token), so the prefix never lingers and the body is intact.
    // - Typed-"/" (saved == null): the input was the slash query → clear it.
    val saved = savedInputBeforeSlash
    savedInputBeforeSlash = null
    _showSlashMenu.value = false
    _slashMenuSelectedIndex.value = -1
    if (saved != null) {
        _pendingCaret.value = saved.length
        return saved
    }
    return if (currentInput.startsWith("/") || currentInput.startsWith("／")) "" else currentInput
}

/**
 * [T-android-slash-send-keeps-text] End the slash session because the composer
 * was SENT.
 *
 * Unlike [dismissSlashMenu] this restores nothing and returns nothing: the body
 * text has just been sent as a message, so the stashed original must be dropped
 * rather than written back into the (now cleared) composer. Without this, the
 * "/" button's over-content mode survived the send and the just-sent text
 * reappeared in the input — reported on Android only.
 *
 * Safe to call unconditionally: when no slash session is open every assignment
 * below is already its own value.
 */
internal fun ChatViewModel.endSlashSessionForSend() {
    savedInputBeforeSlash = null
    _showSlashMenu.value = false
    _slashMenuSelectedIndex.value = -1
    _slashFilter.value = ""
}

internal fun ChatViewModel.slashMenuSetSelectedIndex(index: Int) {
    _slashMenuSelectedIndex.value = index
}

// ─── Merged from OffloadPermissionDialog.kt ──────────────────────────────────────────────────

/**
 * Dialog shown when an ASK_ONCE tool requests permission.
 *
 * T338: three session-scoped options. None of them ever upgrade the
 * stored PermissionLevel — to make a permanent change the user goes
 * to Settings → Permissions and picks Bypass / Not Allowed there.
 *
 *   • Allow in this session — grant for the remaining session, no
 *     more prompts for this tool until the session ends.
 *   • Allow once            — grant only this call; next call re-prompts.
 *   • Deny in this session  — refuse + remember, so the agent can't
 *     spam the same dialog after the user already said no.
 *
 * Dismissing via tap-outside or system back maps to DENY_SESSION too;
 * a silent dismiss should not invite the agent to keep retrying.
 */
@Composable
fun OffloadPermissionDialog() {
    val request by OffloadPermissionManager.pendingRequest.collectAsState()
    val req = request ?: return

    AlertDialog(
        onDismissRequest = {
            OffloadPermissionManager.respondToRequest(OffloadPermissionManager.Response.DENY_SESSION)
        },
        title = {
            Text(
                stringResource(R.string.offload_perm_dialog_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.offload_perm_dialog_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    req.toolTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.offload_perm_dialog_tool_label, req.toolName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    req.description,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Allow in this session — happy path goes first.
                MinisButton(
                    onClick = { OffloadPermissionManager.respondToRequest(OffloadPermissionManager.Response.ALLOW_SESSION) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.offload_perm_allow_session))
                }
                // Allow once — no caching; next call re-prompts.
                MinisOutlinedButton(
                    onClick = { OffloadPermissionManager.respondToRequest(OffloadPermissionManager.Response.ALLOW_ONCE) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.offload_perm_allow_once))
                }
                // Deny in this session — refuse + remember.
                MinisTextButton(
                    onClick = { OffloadPermissionManager.respondToRequest(OffloadPermissionManager.Response.DENY_SESSION) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.offload_perm_deny_session))
                }
            }
        },
        dismissButton = null,
    )
}

// ─── Merged from SpeechLanguagePickerSheet.kt ──────────────────────────────────────────────────

/**
 * Modal sheet listing the active recognizer's supported languages. Tapping a
 * row selects the locale (which, if called during an active session, cancels
 * and restarts recognition in the new language — see
 * [SpeechRecognitionManager.selectLocale]).
 *
 * Locales are sorted so anything matching the device's system preferences
 * floats to the top, then the remainder alphabetically by display name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechLanguagePickerSheet(
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val supported by SpeechRecognitionManager.supportedLocales.collectAsState()
    val current by SpeechRecognitionManager.locale.collectAsState()

    val ordered = remember(supported) {
        val systemLang = Locale.getDefault().language
        supported.sortedWith(
            compareByDescending<Locale> { it.language == systemLang }
                .thenBy { it.getDisplayName(Locale.getDefault()) }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ChatColors.background,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.speech_lang_picker_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = ChatColors.primaryText,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            if (ordered.isEmpty()) {
                Text(
                    text = stringResource(R.string.speech_lang_picker_empty),
                    fontSize = 13.sp,
                    color = ChatColors.secondaryText,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(ordered, key = { it.toLanguageTag() }) { locale ->
                        val selected = locale.toLanguageTag() == current.toLanguageTag()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    SpeechRecognitionManager.selectLocale(locale)
                                    onDismiss()
                                }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .background(ChatColors.secondaryBg, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = locale.language.uppercase().take(2),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ChatColors.primaryText,
                                )
                            }
                            Spacer(modifier = Modifier.size(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = locale.getDisplayName(locale).ifEmpty { locale.toLanguageTag() },
                                    fontSize = 15.sp,
                                    color = ChatColors.primaryText,
                                )
                                Text(
                                    text = locale.toLanguageTag(),
                                    fontSize = 12.sp,
                                    color = ChatColors.secondaryText,
                                )
                            }
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.speech_lang_picker_selected),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

// ─── Merged from SessionEnteringDots.kt ──────────────────────────────────────────────────

/**
 * [T-android-session-entering-dots] Whether the chat area shows the entry
 * dots: a real session whose rows have not been published yet. Mirrors the
 * iOS gate in AIChatView (`(isLoadingSession || enteringUnloaded) &&
 * messages.isEmpty`), where entering means "not loaded yet":
 * - a new chat has nothing to load;
 * - once loadSession has finished, an empty list is an empty session, and a
 *   later reload with rows on screen shows nothing (the iOS user report of a
 *   grey "…" over content that read as a glitch);
 * - safe mode skips loadSession, which then never reports loaded, so the
 *   dots would never leave.
 */
internal fun showSessionEnteringIndicator(
    isDraft: Boolean,
    sessionLoaded: Boolean,
    messagesEmpty: Boolean,
    safeMode: Boolean,
): Boolean = !isDraft && !sessionLoaded && messagesEmpty && !safeMode

/**
 * [T-android-session-entering-dots] The three-dot "…" shown while entering a
 * session, the Android form of iOS SessionLoadingCard / LoadingDotsView: three
 * accent dots breathing between 0.55x / 0.3 alpha and 1x / 0.95 alpha, 0.5 s
 * each way, each one 0.16 s behind the previous.
 *
 * Driven by the shared decorative tick and applied through graphicsLayer only,
 * like [BouncingDots], so it costs a layer update per tick rather than a
 * recomposition per frame.
 */
@Composable
internal fun SessionEnteringDots(modifier: Modifier = Modifier) {
    val tick = rememberDecorativeTick()
    val color = MaterialTheme.colorScheme.primary
    Box(modifier = modifier.height(24.dp), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(DOT_SIZE * 0.7f)) {
            repeat(3) { i ->
                Box(
                    modifier = Modifier
                        .size(DOT_SIZE)
                        .graphicsLayer {
                            val p = SessionEnteringDots.level(tick.value, i)
                            val scale = SessionEnteringDots.MIN_SCALE + (1f - SessionEnteringDots.MIN_SCALE) * p
                            scaleX = scale
                            scaleY = scale
                            alpha = SessionEnteringDots.MIN_ALPHA +
                                (SessionEnteringDots.MAX_ALPHA - SessionEnteringDots.MIN_ALPHA) * p
                        }
                        .background(color, CircleShape),
                )
            }
        }
    }
}

private val DOT_SIZE = 8.dp

internal object SessionEnteringDots {
    /** One full grow-and-shrink: iOS easeInOut(0.5).repeatForever(autoreverses). */
    const val PERIOD_MS = 1_000
    const val STAGGER_MS = 160
    const val MIN_SCALE = 0.55f
    const val MIN_ALPHA = 0.3f
    const val MAX_ALPHA = 0.95f

    /** Delay before the dots fade in, so a session that opens at once never flashes them. */
    const val ENTER_DELAY_MS = 120
    const val ENTER_MS = 200

    /** iOS `.animation(.easeOut(duration: 0.28))` on the card's exit. */
    const val EXIT_MS = 280

    /** 0..1 eased level of dot [index] at [nanos]. */
    fun level(nanos: Long, index: Int): Float {
        val x = decorativePingPong(decorativePhase(nanos, PERIOD_MS, index * STAGGER_MS))
        return x * x * (3f - 2f * x) // smoothstep, the ease-in-out shape
    }
}

// ─── Merged from StandardChatSheet.kt ──────────────────────────────────────────────────

/**
 * Standardized half-screen modal sheet used by every popup launched from the
 * chat input "⋯" menu. Mirrors [CompactSummarySheet]: 90% screen height by
 * default, the same header row (optional leading action / centered title /
 * close button), 0.5dp separator, and a body slot that fills the rest. The
 * body stays independent — each call site supplies its own [content].
 *
 * Uses a compact custom drag handle: the Material3 default reserves ~22dp of
 * padding above and below the indicator, which produced too much whitespace
 * between the indicator and the title — this version tightens it to 6dp / 4dp.
 *
 * [heightFraction] lets a caller request a smaller detent — for example
 * [TokenUsageSheet] passes 0.5f to match iOS's `.medium` detent
 * (AIChatView.swift:508). The fraction is clamped to (0, 1] so callers can't
 * accidentally collapse the sheet to nothing.
 *
 * [onBack] lets a sheet with inner pages take system back first: return true
 * when it handled the press (e.g. popped a page), false to close the sheet as
 * usual. Needed because the sheet's own back-dismiss is registered on the
 * window at overlay priority (Android 13+), ahead of any BackHandler in
 * [content], so with it on, a BackHandler inside the sheet never runs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandardChatSheet(
    title: String,
    onDismiss: () -> Unit,
    leadingAction: (@Composable () -> Unit)? = null,
    heightFraction: Float = 0.9f,
    onBack: (() -> Boolean)? = null,
    content: @Composable () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val sheetHeight = (configuration.screenHeightDp * heightFraction.coerceIn(0.1f, 1f)).dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ChatColors.background,
        dragHandle = { CompactDragHandle() },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = onBack == null),
    ) {
        if (onBack != null) {
            BackHandler {
                if (!onBack()) {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight),
        ) {
            StandardChatSheetHeader(
                title = title,
                onDismiss = onDismiss,
                leadingAction = leadingAction,
            )
            HorizontalDivider(thickness = 0.5.dp, color = ChatColors.separator)
            Box(modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}

/**
 * Slim replacement for [androidx.compose.material3.BottomSheetDefaults.DragHandle].
 * Same 32×4 indicator pill, but with 6dp top + 4dp bottom padding so the title
 * sits closer to the indicator than the Material default (22dp / 22dp).
 */
@Composable
private fun CompactDragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 4.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(4.dp)
                .background(
                    color = ChatColors.secondaryText.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(2.dp),
                ),
        )
    }
}

/**
 * Shared header row used by all chat sheets — close button on the right,
 * centered title, and an optional leading slot. Reserving a 48.dp slot on the
 * left when [leadingAction] is null keeps the title optically centered.
 */
@Composable
fun StandardChatSheetHeader(
    title: String,
    onDismiss: () -> Unit,
    leadingAction: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingAction != null) {
            leadingAction()
        } else {
            Spacer(modifier = Modifier.size(48.dp))
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = ChatColors.primaryText,
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.standard_sheet_close),
                tint = ChatColors.secondaryText,
            )
        }
    }
}

// ─── Merged from MemoryDetailScreens.kt ──────────────────────────────────────────────────

/**
 * Stateless detail body composables used by [SessionMemorySheet] when a row
 * is tapped. The sheet swaps these in for the list view, swaps the header's
 * leading slot to a back-arrow, and (for write-detail) provides Edit / Save /
 * Revoke toolbar items in the trailing slot.
 *
 * All editing state lives in the parent (the sheet) so a single Save button
 * in the header can read the latest buffer without passing references around.
 */

/**
 * Read-only / editable viewer for an auto-injected memory file (GLOBAL.md or
 * a daily log). Mirrors iOS `MemoryContentView`.
 *
 * When [isEditing] is true, [editedContent] is the current text and
 * [onEditedContentChange] is invoked on every keystroke. The parent owns
 * the buffer and renders the Save button.
 */
@Composable
fun MemoryFileViewerBody(
    initialContent: String,
    isEditing: Boolean,
    editedContent: String,
    onEditedContentChange: (String) -> Unit,
    showSavedToast: Boolean,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (isEditing) {
            // [T-android-editor-keyboard-overlap] The caret stays above the
            // keyboard; see DocumentEditorField.
            com.openminis.app.ui.components.DocumentEditorField(
                value = editedContent,
                onValueChange = onEditedContentChange,
                modifier = Modifier.fillMaxSize(),
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = ChatColors.primaryText,
                ),
            )
        } else {
            SelectionContainer(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = initialContent.ifEmpty { "(empty)" },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ChatColors.primaryText,
                    lineHeight = 18.sp,
                )
            }
        }

        if (showSavedToast) SavedToast(modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun SavedToast(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(bottom = 12.dp)
            .background(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = RoundedCornerShape(50),
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = stringResource(R.string.memory_save_toast),
            color = MaterialTheme.colorScheme.inverseOnSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Detail body for a memory_write op-log entry. Shows the original written
 * content plus the tool result in read mode; in edit mode swaps to a
 * BasicTextField over the written content. Mirrors iOS
 * `MemoryWriteDetailView`. Toolbar (Edit / Save / Revoke) is rendered by the
 * parent sheet.
 */
@Composable
fun MemoryWriteDetailBody(
    record: MemoryToolRecord,
    isEditing: Boolean,
    editedContent: String,
    onEditedContentChange: (String) -> Unit,
    showSavedToast: Boolean,
) {
    val written = record.writtenContent ?: ""

    Box(modifier = Modifier.fillMaxSize()) {
        if (isEditing) {
            // [T-android-editor-keyboard-overlap] The caret stays above the
            // keyboard; see DocumentEditorField.
            com.openminis.app.ui.components.DocumentEditorField(
                value = editedContent,
                onValueChange = onEditedContentChange,
                modifier = Modifier.fillMaxSize(),
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = ChatColors.primaryText,
                ),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (written.isNotEmpty()) {
                    SectionHeader(text = stringResource(R.string.memory_section_written_content))
                    SelectionContainer {
                        Text(
                            text = written,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = ChatColors.primaryText,
                            lineHeight = 18.sp,
                        )
                    }
                }

                SectionHeader(text = stringResource(R.string.memory_section_tool_result))
                SelectionContainer {
                    Text(
                        text = record.output,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = ChatColors.secondaryText,
                        lineHeight = 18.sp,
                    )
                }
            }
        }

        if (showSavedToast) SavedToast(modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * Detail body for a memory_get op-log entry. Mirrors iOS `MemoryGetDetailView`.
 */
@Composable
fun MemoryGetDetailBody(record: MemoryToolRecord) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val keywords = record.keywords
        if (!keywords.isNullOrEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = ChatColors.secondaryText,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = keywords,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ChatColors.primaryText,
                )
            }
        }
        SelectionContainer {
            Text(
                text = record.output,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = ChatColors.primaryText,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercaseForDisplay(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
        color = ChatColors.secondaryText,
    )
}

/**
 * Confirmation dialog for revoking a memory_write entry. Wording matches iOS
 * `MemoryWriteDetailView` so users on both platforms see the same prompt.
 */
@Composable
fun RevokeConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_revoke_dialog_title)) },
        text = { Text(stringResource(R.string.memory_revoke_dialog_message)) },
        confirmButton = {
            MinisTextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.memory_action_revoke),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            MinisTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * Result-of-mutation dialog: shows the success/not-found/I-O-error message
 * from [MemoryRepository.EntryMutationResult].
 */
@Composable
fun MutationResultDialog(
    result: MemoryRepository.EntryMutationResult,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val msg = when (result) {
        is MemoryRepository.EntryMutationResult.Success ->
            ctx.getString(R.string.memory_revoke_result_removed, result.dateStr)
        is MemoryRepository.EntryMutationResult.NotFound ->
            ctx.getString(R.string.memory_revoke_result_not_found)
        is MemoryRepository.EntryMutationResult.IOError ->
            ctx.getString(R.string.memory_revoke_result_io_error, result.message)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(msg) },
        confirmButton = {
            MinisTextButton(onClick = onDismiss) { Text("OK") }
        },
    )
}

// ─── Merged from TitlePromptLocale.kt ──────────────────────────────────────────────────

/**
 * Shared system prompt for session title generation. Used by BOTH the auto
 * path (ChatViewModel.generateSessionTitleIfNeeded) and the manual Regenerate
 * path (SessionListViewModel.regenerateTitle) so the two never drift. Matches
 * iOS callSubModelForTitle's system prompt verbatim.
 *
 * Callers pass this as the bare `systemPrompt`; for OAuth Anthropic instances
 * AnthropicProvider.resolveSystemPrompt force-prepends the Claude Code prefix
 * block at the provider layer (and strips a caller-supplied one), so callers
 * do NOT need to prepend it themselves.
 */
internal const val TITLE_GEN_SYSTEM_PROMPT: String =
    "You generate concise titles for conversations. You MUST respond with a single valid JSON object: {\"title\": \"...\", \"category\": \"...\"}. No other text."

/**
 * Build the bilingual language directive appended to the title-generation
 * user prompt so the model produces a title in the user's UI language even
 * when the conversation contents are in another language.
 *
 * Resolution: `Locale.getDefault()` — the app does not currently expose an
 * in-app language override. If parsing the locale ever fails, falls back to
 * "en" / "English" rather than throwing, so title generation never breaks
 * over a malformed locale.
 */
internal fun titleLanguageDirective(locale: Locale = Locale.getDefault()): String {
    val (code, human) = resolveTitleLanguageCode(locale)
    return buildString {
        append("\n\n")
        append("The user's app interface language is \"").append(code)
            .append("\" (").append(human).append("). Generate the title primarily in this language. ")
        append("If the conversation content is in a different language, you may keep proper nouns ")
        append("from it, but the overall title language should match the interface language.\n")
        append("用户的 App 界面语言是 \"").append(code).append("\"（").append(human).append("）。")
        append("请优先使用该语言生成标题。如果对话内容是其他语言，可保留专有名词，但标题整体语言应与界面语言一致。")
    }
}

private fun resolveTitleLanguageCode(locale: Locale): Pair<String, String> {
    return try {
        val lang = locale.language.takeIf { it.isNotEmpty() } ?: return "en" to "English"
        // Distinguish Simplified vs Traditional Chinese — both render as "zh"
        // bare, but title style differs meaningfully between them.
        if (lang == "zh") {
            val script = locale.script
            val region = locale.country
            val isTraditional = script.equals("Hant", ignoreCase = true)
                || region in setOf("TW", "HK", "MO")
            return if (isTraditional) "zh-Hant" to "繁體中文 / Traditional Chinese"
            else "zh-Hans" to "简体中文 / Simplified Chinese"
        }
        val human = humanReadable[lang] ?: locale.getDisplayLanguage(Locale.ENGLISH)
            .ifEmpty { lang }
        lang to human
    } catch (_: Exception) {
        "en" to "English"
    }
}

private val humanReadable: Map<String, String> = mapOf(
    "en" to "English",
    "ja" to "日本語 / Japanese",
    "ko" to "한국어 / Korean",
    "fr" to "Français / French",
    "de" to "Deutsch / German",
    "es" to "Español / Spanish",
    "it" to "Italiano / Italian",
    "pt" to "Português / Portuguese",
    "ru" to "Русский / Russian",
    "ar" to "العربية / Arabic",
    "hi" to "हिन्दी / Hindi",
    "vi" to "Tiếng Việt / Vietnamese",
    "th" to "ไทย / Thai",
    "id" to "Bahasa Indonesia / Indonesian",
    "tr" to "Türkçe / Turkish",
    "nl" to "Nederlands / Dutch",
    "pl" to "Polski / Polish",
    "hr" to "Hrvatski / Croatian",
    "hu" to "Magyar / Hungarian",
    "cs" to "Čeština / Czech",
    "fa" to "فارسی / Persian",
)

// ─── Merged from MinisMarkdownTextToolbar.kt ──────────────────────────────────────────────────

/**
 * Replaces the system Copy/Cut/Paste/SelectAll toolbar that a
 * [androidx.compose.foundation.text.selection.SelectionContainer] normally
 * invokes. Surfaces three actions, mirroring iOS `SelectableMarkdownTextView`:
 *
 *   - **Copy** — selected substring (delegates to `onCopyRequested`).
 *   - **Copy Markdown** — full raw markdown of the message that owns the
 *     selection. Resolved via the [MessageBoundsRegistry] lookup with the
 *     selection rect; if no message matches (cross-message selection or
 *     out-of-bounds rect) the action is hidden so we never copy the wrong
 *     content.
 *   - **Copy Rich Text** — same lookup, exported as HTML+plain dual ClipData.
 *
 * The toolbar is driven by a [mutableStateOf]-backed state holder; pair it
 * with [MinisMarkdownTextToolbarHost] which renders the floating popup. The
 * split exists because [TextToolbar] is a non-composable platform interface
 * but our UI must live inside the composition.
 */
internal class MinisMarkdownTextToolbar(
    private val context: Context,
    private val registry: MessageBoundsRegistry,
    /**
     * Invoked when the user taps **Add to Chat Input**. Receives the
     * currently-selected substring (resolved via a clipboard round-trip
     * through the Compose-supplied [onCopyRequested] callback — see
     * [addSelectionToInput]); the host is expected to append it to the
     * chat composer's input state. Null disables the action.
     */
    private val onAddToInput: ((String) -> Unit)? = null,
    /**
     * [T-android-selection-readaloud] Invoked when the user taps **Read Aloud**.
     * Receives the currently-selected substring (same clipboard round-trip as
     * [addSelectionToInput]); the host speaks it through ReadAloudPlayer. Null
     * disables the action. The callback owns the player's lifecycle — this
     * toolbar deliberately does not create one it could never dispose.
     */
    private val onReadAloud: ((String) -> Unit)? = null,
    /**
     * [T-android-readaloud-selection-vs-reply] Invoked when the user taps
     * **Read from Start**. Receives the owning message's markdown source (not
     * the selection); the host speaks it through the same reader. Null hides
     * the action — the caller passes null while the reply is still streaming,
     * matching iOS.
     */
    private val onReadFromStart: ((String) -> Unit)? = null,
    /**
     * [T-android-readaloud-selection-vs-reply] Whether a reply is streaming
     * right now. A lambda, not a Boolean: this toolbar is constructed once
     * inside `remember`, so a captured value would freeze at construction time
     * and the gate would answer for the wrong moment.
     */
    private val isStreamingNow: () -> Boolean = { false },
    /**
     * [T-android-markdown-table-copy-actions] The MinisTextKit selection
     * controller. This toolbar is the one Compose's SelectionContainer shows
     * (via LocalTextToolbar), and a table-cell long-press also sets the
     * MinisTextKit selection, so we read the table actions for the selected
     * message off the controller and append "Copy Table" / "Copy Table Image"
     * to THIS bar — keeping all actions in one row instead of a separate popup.
     */
    internal val selectionController: SelectionController? = null,
    /**
     * [T-multi-select-share] Opens the share picker with the round of the selected
     * message preselected (null when it cannot be told which message). Null hides
     * the action; shown last, as on iOS.
     */
    private val onShareChat: ((String?) -> Unit)? = null,
    /**
     * [T-in-chat-search] "Search in Chat" with the selection as the query
     * (iOS: the assistant text selection menu). Null hides the action.
     */
    private val onSearchInChat: ((String) -> Unit)? = null,
) : TextToolbar {

    internal var state by mutableStateOf(ToolbarState())
        private set

    internal val canAddToInput: Boolean get() = onAddToInput != null

    internal val canShareChat: Boolean get() = onShareChat != null

    internal fun shareChat() {
        val sink = onShareChat ?: return
        sink(
            selectionController?.singleMessageId()
                ?: selectionController?.selection?.value?.start?.shard?.messageId,
        )
    }

    override val status: TextToolbarStatus
        get() = if (state.visible) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        state = ToolbarState(
            visible = true,
            rect = rect,
            onCopyRequested = onCopyRequested,
            originatingMarkdown = registry.markdownAt(rect),
            lastShownAtMs = android.os.SystemClock.uptimeMillis(),
        )
    }

    override fun hide() {
        state = state.copy(visible = false)
    }

    /**
     * Extract the selected substring and forward to [onAddToInput] so the host
     * can append it to the chat input. See [withSelection] for how the
     * selection is resolved. No-op when [onAddToInput] isn't wired.
     */
    internal fun addSelectionToInput() {
        val sink = onAddToInput ?: return
        withSelection(sink)
    }

    /**
     * [T-android-selection-readaloud] Speak just the selected substring (not the
     * whole message) through the host-supplied reader.
     */
    internal fun readAloudSelection() {
        val sink = onReadAloud ?: return
        withSelection(sink)
    }

    internal val canReadAloud: Boolean get() = onReadAloud != null

    internal val canSearchInChat: Boolean get() = onSearchInChat != null

    internal fun searchSelectionInChat() {
        val sink = onSearchInChat ?: return
        withSelection(sink)
    }

    /**
     * [T-android-readaloud-selection-vs-reply] Speak the WHOLE message that
     * owns the current selection, from its start — iOS's "Read from Start".
     *
     * Unlike [readAloudSelection] this needs no clipboard round-trip: the
     * message's markdown source is already resolved into
     * [ToolbarState.originatingMarkdown] when the bar is shown, and that is
     * exactly the text to read. It is also why the action can only be offered
     * when that field is non-null — a selection whose owning message we cannot
     * identify has no "whole reply" to restart.
     */
    internal fun readFromStart() {
        val sink = onReadFromStart ?: return
        val source = state.originatingMarkdown ?: return
        if (source.isNotBlank()) sink(source)
    }

    internal val canReadFromStart: Boolean
        get() = onReadFromStart != null &&
            state.originatingMarkdown != null &&
            // Suppressed mid-stream, matching iOS: restarting a reply that is
            // still arriving would narrate a text that keeps growing under the
            // narration. Read Selection stays available — a range the user
            // could select already exists.
            !isStreamingNow()

    /**
     * Resolve the current selection and hand it to [sink].
     *
     * Compose's [TextToolbar] interface only exposes the selection indirectly
     * via the supplied `onCopyRequested` callback — there is no public "give me
     * the AnnotatedString selection" API in `foundation` 1.7. We round-trip
     * through the system clipboard:
     *
     *   1. Snapshot the user's current primary clip (so we can restore it — we
     *      don't want a feature that silently overwrites what they had copied).
     *   2. Invoke `onCopyRequested` — Compose writes the selected substring to
     *      the clipboard.
     *   3. Read that text back as the selection.
     *   4. Restore the original clip.
     *   5. Forward the selection to [sink].
     *
     * Shared by [addSelectionToInput] and [readAloudSelection] so both actions
     * use the identical restore-the-user's-clipboard discipline. If anything
     * goes wrong (`onCopyRequested` absent, blank selection) this is a no-op —
     * it never throws and never leaves the clipboard in an unrelated state.
     */
    private fun withSelection(sink: (String) -> Unit) {
        val onCopy = state.onCopyRequested ?: return
        val cm = clipboard()
        val original = runCatching { cm.primaryClip }.getOrNull()
        try {
            onCopy.invoke()
            val selection = runCatching {
                cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            }.getOrNull()?.trim().orEmpty()
            if (selection.isNotEmpty()) sink(selection)
        } finally {
            // Restore the user's prior clipboard so the action stays
            // additive — only the chat input / speech changes, the clipboard
            // is exactly as the user left it.
            if (original != null) runCatching { cm.setPrimaryClip(original) }
        }
    }

    private fun clipboard(): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    internal fun copyMarkdown() {
        val md = state.originatingMarkdown ?: return
        MarkdownClipboard.copyMarkdown(context, md)
        toast(R.string.selection_copied_as_markdown_toast)
    }

    internal fun copyRichText() {
        val md = state.originatingMarkdown ?: return
        MarkdownClipboard.copyRichText(context, md)
        toast(R.string.selection_copied_as_rich_text_toast)
    }

    /**
     * [T-android-copy-full-plain-text] Copy the message as RENDERED plain
     * text: markdown markers resolved to what the user sees, but table cells
     * and math expressions still expanded — dropping those would lose content
     * rather than formatting. Mirrors iOS `1b4353367`.
     */
    internal fun copyPlainText() {
        val md = state.originatingMarkdown ?: return
        MarkdownClipboard.copyPlain(context, md)
        toast(R.string.selection_copied_as_plain_text_toast)
    }

    private fun toast(resId: Int) {
        Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
    }

    internal data class ToolbarState(
        val visible: Boolean = false,
        val rect: Rect = Rect.Zero,
        val onCopyRequested: (() -> Unit)? = null,
        /** Joined markdown of the message that owns [rect], or null if cross-message. */
        val originatingMarkdown: String? = null,
        /**
         * `SystemClock.uptimeMillis()` of the most recent [showMenu] call, or
         * 0 if the toolbar has never been shown. Updated only on `showMenu` —
         * [hide] preserves the previous value so consumers can distinguish a
         * mid-drag hide (recent timestamp) from a stale post-clear state
         * (timestamp many seconds old). Used by ChatScreen's selection
         * auto-scroll loop to bail when no fresh selection activity has been
         * observed for a long time, regardless of the sticky `selectionActive`
         * debounce.
         */
        val lastShownAtMs: Long = 0L,
    )
}

/**
 * Renders the floating toolbar controlled by [MinisMarkdownTextToolbar]. Place
 * exactly one instance under the same `CompositionLocalProvider` that supplies
 * the toolbar so it draws above the selected text.
 */
@Composable
internal fun MinisMarkdownTextToolbarHost(toolbar: MinisMarkdownTextToolbar) {
    val state = toolbar.state
    if (!state.visible) return
    val anchor = remember(state.rect) { state.rect.toIntRectRounded() }
    val positionProvider = remember(anchor) { FloatingToolbarPositionProvider(anchor) }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = { toolbar.hide() },
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        // [T-android-text-toolbar-dark-visibility] In dark mode the bare
        // `surface` colour is nearly the same as the chat background, so the
        // floating menu vanished into the content behind it. Use a distinctly
        // elevated surface (surfaceContainerHigh — a lighter grey in dark, a
        // subtle shade in light) plus a thin outline border and a stronger
        // shadow so the bar reads as a popped-out floating element.
        val barColor = MaterialTheme.colorScheme.surfaceContainerHigh
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = barColor,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            border = androidx.compose.foundation.BorderStroke(
                0.5.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            ),
        ) {
            // [T-android-copy-full-reply-scope] Make the action row
            // horizontally scrollable, matching the MinisTextKit selection bar.
            // Buttons never wrap (ToolbarButton is maxLines=1/softWrap=false to
            // avoid the truncation documented there), so without a scroll the
            // trailing actions simply fall off the screen edge and become
            // unreachable. With Copy + Add to Input + Read Aloud + three
            // full-message copies + two table actions this row reaches eight
            // items, and the "Copy Full …" labels are the longest yet.
            val toolbarScroll = rememberScrollState()
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(barColor)
                    .height(40.dp)
                    .horizontalScroll(toolbarScroll),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarButton(label = stringResource(R.string.selection_copy)) {
                    state.onCopyRequested?.invoke()
                    toolbar.hide()
                }
                // "Add to Chat Input" — available for any selection so it
                // works for tool result output, user/assistant bubbles,
                // and cross-message selections alike. Hidden when the
                // host didn't wire an onAddToInput callback (e.g. a
                // future screen that uses the same toolbar but has no
                // composer to receive the text).
                if (toolbar.canAddToInput) {
                    ToolbarDivider()
                    ToolbarButton(
                        label = stringResource(R.string.selection_add_to_chat_input),
                    ) {
                        toolbar.addSelectionToInput()
                        toolbar.hide()
                    }
                }
                // [T-in-chat-search] The selection becomes the query.
                if (toolbar.canSearchInChat) {
                    ToolbarDivider()
                    ToolbarButton(label = stringResource(R.string.chat_search_in_chat)) {
                        toolbar.searchSelectionInChat()
                        toolbar.hide()
                    }
                }
                // [T-android-selection-readaloud] Speak just the selected
                // substring through Minis TTS (provider voice with the system
                // engine as fallback), mirroring iOS's "Read Aloud / Read
                // Selection" selection-menu action. Available for any
                // selection, like Add to Chat Input.
                if (toolbar.canReadAloud) {
                    ToolbarDivider()
                    ToolbarButton(
                        label = stringResource(R.string.selection_read_aloud),
                    ) {
                        toolbar.readAloudSelection()
                        toolbar.hide()
                    }
                }
                // [T-android-readaloud-selection-vs-reply] "Read from Start"
                // beside "Read Selection", mirroring iOS's pair. Flat here
                // rather than grouped: this bar is already a flat scrolling row
                // with no submenu machinery, and the two labels state their own
                // scope.
                if (toolbar.canReadFromStart) {
                    ToolbarDivider()
                    ToolbarButton(
                        label = stringResource(R.string.selection_read_from_start),
                    ) {
                        toolbar.readFromStart()
                        toolbar.hide()
                    }
                }
                // [T-android-copy-full-reply-scope] The full-message copies
                // only appear when the selection sits inside a single known
                // message — otherwise it's ambiguous which message's source to
                // copy.
                //
                // Every label says "Full", because this toolbar is raised BY a
                // selection and sits inches from plain "Copy": without the
                // word, a user who highlighted one sentence and tapped "Copy
                // Markdown" silently got the entire message, with nothing in
                // the UI explaining the gap. Naming the scope is the fix
                // itself (iOS a7b3e5bef).
                if (state.originatingMarkdown != null) {
                    ToolbarDivider()
                    ToolbarButton(label = stringResource(R.string.selection_copy_full_markdown)) {
                        toolbar.copyMarkdown()
                        toolbar.hide()
                    }
                    ToolbarDivider()
                    ToolbarButton(label = stringResource(R.string.selection_copy_full_rich_text)) {
                        toolbar.copyRichText()
                        toolbar.hide()
                    }
                    // Plain Text copies the RENDERED text — headings, lists and
                    // quotes as the user sees them, with no `#`/`-`/`**`/`>`
                    // left behind. Tables and math still expand: dropping those
                    // would lose content, not formatting (iOS 1b4353367).
                    ToolbarDivider()
                    ToolbarButton(label = stringResource(R.string.selection_copy_full_plain_text)) {
                        toolbar.copyPlainText()
                        toolbar.hide()
                    }
                }
                // [T-android-markdown-table-copy-actions] When the selected
                // message contains a table, append Copy Table / Copy Table
                // Image to THIS same row (one menu, all items) instead of the
                // table rendering its own separate popup.
                val tableActions = toolbar.selectionController?.selectionTableActions()
                if (tableActions != null) {
                    ToolbarDivider()
                    ToolbarButton(label = stringResource(R.string.markdown_table_copy_table)) {
                        tableActions.copyTableMarkdown()
                        toolbar.hide()
                    }
                    ToolbarDivider()
                    ToolbarButton(label = stringResource(R.string.markdown_table_copy_table_image)) {
                        tableActions.copyTableImage()
                        toolbar.hide()
                    }
                }
                // [T-multi-select-share] "Share Chat…" is the last item.
                if (toolbar.canShareChat) {
                    ToolbarDivider()
                    ToolbarButton(label = stringResource(R.string.chat_share_share_chat)) {
                        toolbar.hide()
                        toolbar.shareChat()
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarButton(label: String, onClick: () -> Unit) {
    // maxLines=1 + softWrap=false is load-bearing: the parent Row has a
    // fixed height(40.dp), so any label that wraps to a second line
    // shows only its first word on screen. Without this guard,
    // "Copy Rich Text" renders as just "Copy" — visually a duplicate of
    // the first toolbar item — and a longer "Add to input box" label collapses to "Add to".
    // See T-android-text-toolbar-dup-copy.
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun ToolbarDivider() {
    Box(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .width(0.5.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    )
}

private fun Rect.toIntRectRounded(): IntRect = IntRect(
    left = left.toInt(),
    top = top.toInt(),
    right = right.toInt(),
    bottom = bottom.toInt(),
)

/**
 * Positions the floating toolbar above the selection when there's room,
 * otherwise below it. Clamps horizontally to the window bounds.
 */
private class FloatingToolbarPositionProvider(
    private val anchor: IntRect,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val gap = 8
        val centerX = anchor.left + anchor.width / 2
        val x = (centerX - popupContentSize.width / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val aboveY = anchor.top - popupContentSize.height - gap
        val y = if (aboveY >= 0) {
            aboveY
        } else {
            (anchor.bottom + gap)
                .coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0))
        }
        return IntOffset(x, y)
    }
}

// ─── Merged from SessionSkillsSheet.kt ──────────────────────────────────────────────────

/**
 * Bottom sheet showing all skills with per-session enable/disable toggles.
 * Mirrors iOS SessionSkillsView. Uses the [StandardChatSheet] shell plus the
 * shared [SettingsSection] / [SkillRowItem] primitives so each skill row
 * looks identical to the Settings → Skills screen.
 *
 * The "Enable All / Disable All" controls live to the right of the section
 * header (mirroring iOS section-actions), keeping the sheet's standard top
 * header reserved for the title + close button.
 *
 * [T-android-session-skill-detail] Tapping a row opens that skill's detail
 * (SKILL.md in full, then its other files; a file opens a text / image
 * preview), like iOS SessionSkillDetailView. The pages are swapped inside the
 * sheet rather than navigated to, so the chat stays underneath; the list's
 * state (overrides, search, scroll) is held above the page switch and is
 * unchanged on return. Long-pressing a skill installed from a URL offers
 * "Copy Source URL", the same menu as Settings -> Skills.
 */
@Composable
fun SessionSkillsSheet(
    sessionId: String,
    skillRepository: SkillRepository,
    onDismiss: () -> Unit,
) {
    val skills by skillRepository.skills.collectAsState()

    // [T-android-session-skill-override-init-timing] Key the seed on `skills`
    // so the override map re-derives when the live skills list arrives.
    // A keyless `remember {}` seeds once from the possibly-empty first-
    // composition list and then never re-runs, so every row's switch is stuck
    // at the global default (the same stale-override race already fixed in
    // SessionMcpsSheet — keeping the two sheets symmetric). Mirrors iOS
    // ed861471 (T-ios-session-skill-override-init-timing) for the
    // initial-state half of that fix; the per-draft id binding half is
    // already covered on Android by the `__new__<uuid>` route arg (every
    // draft has its own stable session id), unlike iOS which previously
    // coerced nil to "" and bled overrides across drafts.
    val overrides = remember(skills) {
        mutableStateMapOf<String, Boolean>().apply {
            for (skill in skills) {
                put(skill.id, skillRepository.isEnabledForSession(skill.id, sessionId))
            }
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val filteredSkills by remember(skills) {
        derivedStateOf {
            val q = searchQuery.trim().lowercase()
            if (q.isEmpty()) skills
            else skills.filter {
                it.name.lowercase().contains(q) || it.description.lowercase().contains(q)
            }
        }
    }

    // Held here, above the page switch, so returning from a detail page finds
    // the list exactly as it was left.
    val listScroll = rememberScrollState()
    var page by remember { mutableStateOf<SessionSkillsPage>(SessionSkillsPage.List) }

    // A skill deleted while its page is open: fall back to the list.
    val pageSkill = page.skillId?.let { id -> skills.find { it.id == id } }
    val pageSkillGone = page.skillId != null && pageSkill == null
    LaunchedEffect(pageSkillGone) {
        if (pageSkillGone) page = SessionSkillsPage.List
    }

    val currentPage = page
    StandardChatSheet(
        title = when (currentPage) {
            SessionSkillsPage.List -> stringResource(R.string.session_skills_title)
            is SessionSkillsPage.Detail -> pageSkill?.name.orEmpty()
            is SessionSkillsPage.File -> currentPage.relativePath.substringAfterLast('/')
        },
        onDismiss = onDismiss,
        // System back pops one page; on the list it closes the sheet.
        onBack = {
            if (page == SessionSkillsPage.List) false else {
                page = page.back()
                true
            }
        },
        leadingAction = if (currentPage == SessionSkillsPage.List) null else {
            {
                IconButton(onClick = { page = currentPage.back() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                    )
                }
            }
        },
    ) {
        when (currentPage) {
            is SessionSkillsPage.Detail -> {
                if (pageSkill != null) {
                    SessionSkillDetail(
                        skill = pageSkill,
                        skillRepository = skillRepository,
                        onFileClick = { path -> page = SessionSkillsPage.File(pageSkill.id, path) },
                    )
                }
                return@StandardChatSheet
            }
            is SessionSkillsPage.File -> {
                SessionSkillFilePreview(skillRepository, currentPage.id, currentPage.relativePath)
                return@StandardChatSheet
            }
            SessionSkillsPage.List -> Unit
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(listScroll),
        ) {
            if (skills.isEmpty()) {
                EmptySkillsCard()
            } else {
                // Search field — filters by skill name + description.
                DialogTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = stringResource(R.string.skills_search_placeholder),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp),
                )
                // Header row: small-caps "INSTALLED" label on the left,
                // Enable-All / Disable-All TextButtons on the right. Echoes
                // the SettingsSection header exactly so visual rhythm carries
                // over from Settings.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .padding(start = 32.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.session_skills_header_installed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.5.sp,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // Enable/Disable All operate on the FILTERED subset so a
                    // user can bulk-toggle the result of a search.
                    MinisTextButton(
                        onClick = {
                            for (skill in filteredSkills) {
                                overrides[skill.id] = true
                                skillRepository.setSessionOverride(sessionId, skill.id, true)
                            }
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp, vertical = 0.dp,
                        ),
                    ) {
                        Text(stringResource(R.string.session_skills_enable_all), fontSize = 12.sp)
                    }
                    MinisTextButton(
                        onClick = {
                            for (skill in filteredSkills) {
                                overrides[skill.id] = false
                                skillRepository.setSessionOverride(sessionId, skill.id, false)
                            }
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp, vertical = 0.dp,
                        ),
                    ) {
                        Text(stringResource(R.string.session_skills_disable_all), fontSize = 12.sp)
                    }
                }

                if (filteredSkills.isEmpty()) {
                    Text(
                        text = stringResource(R.string.skills_search_no_match, searchQuery),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 24.dp),
                    )
                } else {
                    SettingsSection(
                        footer = stringResource(R.string.session_skills_footer),
                    ) {
                        filteredSkills.forEachIndexed { index, skill ->
                            // Long-press offers "Copy Source URL" only for a
                            // skill installed from one, as in Settings -> Skills.
                            val sourceUrl = SkillSourceUrl.copyable(skill)
                            var menuExpanded by remember(skill.id) { mutableStateOf(false) }
                            Box {
                                SkillRowItem(
                                    name = skill.name,
                                    description = skill.description,
                                    importSource = skill.importSource,
                                    isEnabled = overrides[skill.id] ?: skill.isEnabled,
                                    onToggle = { enabled ->
                                        overrides[skill.id] = enabled
                                        skillRepository.setSessionOverride(sessionId, skill.id, enabled)
                                    },
                                    onClick = { page = SessionSkillsPage.Detail(skill.id) },
                                    showDivider = index < filteredSkills.size - 1,
                                    onLongClick = sourceUrl?.let {
                                        {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            menuExpanded = true
                                        }
                                    },
                                )
                                if (sourceUrl != null) {
                                    MinisMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.skill_copy_source_url)) },
                                            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                                            onClick = {
                                                menuExpanded = false
                                                SkillSourceUrl.copy(context, sourceUrl)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.padding(bottom = 24.dp))
            }
        }
    }
}

@Composable
private fun EmptySkillsCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.Description,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            )
            Text(
                stringResource(R.string.session_skills_empty_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.session_skills_empty_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

/** Which page the sheet shows. Detail and File pages name their skill. */
internal sealed interface SessionSkillsPage {
    data object List : SessionSkillsPage
    data class Detail(val id: String) : SessionSkillsPage
    data class File(val id: String, val relativePath: String) : SessionSkillsPage

    val skillId: String?
        get() = when (this) {
            List -> null
            is Detail -> id
            is File -> id
        }

    /** The page the back arrow / system back returns to. */
    fun back(): SessionSkillsPage = when (this) {
        List, is Detail -> List
        is File -> Detail(id)
    }
}

/** Files listed under "Other Files": everything except SKILL.md itself. */
internal fun sessionSkillOtherFiles(all: List<String>): List<String> =
    all.filterNot { it.equals("SKILL.md", ignoreCase = true) }

/**
 * One skill's detail page: SKILL.md as it is on disk (front matter included,
 * like iOS readSkillContent; the stored body if the file is unreadable), then
 * the skill's other files.
 */
@Composable
private fun SessionSkillDetail(
    skill: SkillRepository.Skill,
    skillRepository: SkillRepository,
    onFileClick: (String) -> Unit,
) {
    // Re-read when the skill record changes (an update or rescan bumps
    // updatedAt), like the Settings detail page.
    val skillMd = remember(skill.id, skill.updatedAt) {
        skillRepository.readSkillFile(skill.id, "SKILL.md") ?: skill.body
    }
    val otherFiles = remember(skill.id, skill.updatedAt) {
        sessionSkillOtherFiles(skillRepository.listSkillFiles(skill.id))
            .map { it to skillFileItem(skillRepository, skill.id, it).isImageFile }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SettingsSection(header = "SKILL.md") {
            SelectionContainer {
                Text(
                    text = skillMd,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                )
            }
        }
        if (otherFiles.isNotEmpty()) {
            SettingsSection(header = stringResource(R.string.session_skill_other_files)) {
                otherFiles.forEachIndexed { index, (path, isImage) ->
                    SettingsRow(
                        title = path,
                        icon = if (isImage) {
                            Icons.Outlined.Image
                        } else {
                            Icons.Outlined.Description
                        },
                        iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        titleColor = MaterialTheme.colorScheme.onSurface,
                        onClick = { onFileClick(path) },
                        showDivider = index < otherFiles.size - 1,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.padding(bottom = 24.dp))
    }
}

/** Images are scaled to the sheet width; everything else is monospaced text. */
@Composable
private fun SessionSkillFilePreview(
    skillRepository: SkillRepository,
    skillId: String,
    relativePath: String,
) {
    val item = remember(skillId, relativePath) { skillFileItem(skillRepository, skillId, relativePath) }
    if (item.isImageFile) ImagePreview(item) else TextPreview(item)
}

private fun skillFileItem(skillRepository: SkillRepository, skillId: String, relativePath: String): FileItem {
    val file = java.io.File(skillRepository.skillFileHostPath(skillId, relativePath))
    return FileItem(
        file = file,
        name = file.name,
        isDirectory = false,
        isSymlink = false,
        size = file.length(),
        modifiedMs = file.lastModified(),
    )
}

// ─── Merged from SessionMcpsSheet.kt ──────────────────────────────────────────────────

private const val MCP_NOTE_TRUNC = 200

/**
 * Bottom sheet showing all configured MCP servers with per-session
 * enable/disable toggles. Mirrors [SessionSkillsSheet]: [StandardChatSheet]
 * shell + shared [SettingsSection] / [SettingsRow] primitives. Each row shows
 * the server name + truncated note, with a Switch writing a session override
 * via [MCPRepository.setSessionOverride].
 *
 * [T-android-session-mcp-detail] Like the Skills sheet (and iOS
 * SessionMCPsView), the list has a search field (name + note) and Enable All /
 * Disable All acting on the filtered rows. Tapping a row opens a read-only
 * detail page inside the sheet: transport, URL or command + args, startup
 * timeout, OAuth state, the note, and the NAMES of headers and env vars. Their
 * values are never shown: they routinely hold API keys, and this sheet sits
 * over a chat that may be screen-shared. Long-pressing the URL copies it.
 * The list's state is held above the page switch, so it is unchanged on
 * return; system back pops the detail page via [StandardChatSheet]'s onBack.
 */
@Composable
fun SessionMcpsSheet(
    sessionId: String,
    mcpRepository: MCPRepository,
    onDismiss: () -> Unit,
) {
    val servers by mcpRepository.servers.collectAsState()

    // [T-mcp-review-fixes-android] Key the seed on `servers` so the override map
    // re-derives when the live server list arrives. A keyless remember{} seeds
    // once from a possibly-empty first-composition list and then never re-runs,
    // leaving every toggle stuck at its default (the review's stale-override race).
    val overrides = remember(servers) {
        mutableStateMapOf<String, Boolean>().apply {
            for (server in servers) {
                put(server.id, mcpRepository.isEnabledForSession(server.id, sessionId))
            }
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    val filteredServers by remember(servers) {
        derivedStateOf { filterMcpServers(servers, searchQuery) }
    }

    // Held here, above the page switch, so returning from a detail page finds
    // the list exactly as it was left.
    val listScroll = rememberScrollState()
    var detailId by remember { mutableStateOf<String?>(null) }

    // A server removed while its page is open: fall back to the list.
    val detailServer = detailId?.let { id -> servers.find { it.id == id } }
    val detailGone = detailId != null && detailServer == null
    LaunchedEffect(detailGone) {
        if (detailGone) detailId = null
    }

    fun setAll(enabled: Boolean) {
        for (server in filteredServers) {
            overrides[server.id] = enabled
            mcpRepository.setSessionOverride(sessionId, server.id, enabled)
        }
    }

    StandardChatSheet(
        title = detailServer?.id ?: stringResource(R.string.session_mcps_title),
        onDismiss = onDismiss,
        // System back closes the detail page; on the list it closes the sheet.
        onBack = {
            if (detailId == null) false else {
                detailId = null
                true
            }
        },
        leadingAction = if (detailServer == null) null else {
            {
                IconButton(onClick = { detailId = null }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                    )
                }
            }
        },
    ) {
        if (detailServer != null) {
            SessionMcpDetail(detailServer)
            return@StandardChatSheet
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(listScroll),
        ) {
            if (servers.isEmpty()) {
                EmptyMcpsCard()
            } else {
                DialogTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = stringResource(R.string.mcps_search_placeholder),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp),
                )
                // Same header row as the Skills sheet: section label on the
                // left, Enable All / Disable All (on the filtered rows) on the
                // right.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .padding(start = 32.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.mcp_section_servers).uppercaseForDisplay(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.5.sp,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    MinisTextButton(
                        onClick = { setAll(true) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp, vertical = 0.dp,
                        ),
                    ) {
                        Text(stringResource(R.string.session_skills_enable_all), fontSize = 12.sp)
                    }
                    MinisTextButton(
                        onClick = { setAll(false) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp, vertical = 0.dp,
                        ),
                    ) {
                        Text(stringResource(R.string.session_skills_disable_all), fontSize = 12.sp)
                    }
                }

                if (filteredServers.isEmpty()) {
                    Text(
                        text = stringResource(R.string.mcps_search_no_match, searchQuery),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 24.dp),
                    )
                } else {
                    SettingsSection(
                        footer = stringResource(R.string.session_mcps_footer),
                    ) {
                        filteredServers.forEachIndexed { index, server ->
                            val note = server.note?.trim().orEmpty()
                            val sub = note
                                .ifEmpty { server.transportSummary }
                                .let { if (it.length > MCP_NOTE_TRUNC) it.substring(0, MCP_NOTE_TRUNC) + "…" else it }
                            SettingsRow(
                                title = server.id,
                                subtitle = sub.takeIf { it.isNotBlank() },
                                onClick = { detailId = server.id },
                                showDivider = index < filteredServers.size - 1,
                                trailing = {
                                    SettingsSwitch(
                                        checked = overrides[server.id] ?: server.enabled,
                                        onCheckedChange = { enabled ->
                                            overrides[server.id] = enabled
                                            mcpRepository.setSessionOverride(sessionId, server.id, enabled)
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.padding(bottom = 24.dp))
            }
        }
    }
}

/** Servers whose name or note contains [query] (trimmed, case-insensitive), like iOS. */
internal fun filterMcpServers(
    servers: List<MCPRepository.MCPServerConfig>,
    query: String,
): List<MCPRepository.MCPServerConfig> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return servers
    return servers.filter { it.id.lowercase().contains(q) || it.note.orEmpty().lowercase().contains(q) }
}

/**
 * Header / env names for the detail page, sorted. Only the names: the values
 * are never handed to the UI.
 */
internal fun mcpSecretNames(map: Map<String, String>): List<String> = map.keys.sorted()

/** Read-only detail of one server. Nothing here writes to the config. */
@Composable
private fun SessionMcpDetail(server: MCPRepository.MCPServerConfig) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val authorized = remember(server.id, server.oauth) {
        server.oauth != null && com.openminis.app.mcp.oauth.MCPOAuthStore.isAuthorized(context, server.id)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SettingsSection {
            McpDetailRow(
                label = stringResource(R.string.session_mcp_detail_transport),
                value = if (server.isStdio) {
                    stringResource(R.string.mcp_transport_stdio)
                } else {
                    stringResource(R.string.mcp_transport_http) + " / " + stringResource(R.string.mcp_transport_sse)
                },
            )
            if (server.isStdio) {
                McpDetailDivider()
                McpDetailRow(stringResource(R.string.mcp_form_command), server.command.orEmpty(), monospace = true)
                if (server.args.isNotEmpty()) {
                    McpDetailDivider()
                    McpDetailRow(stringResource(R.string.session_mcp_detail_args), server.args.joinToString(" "), monospace = true)
                }
                McpDetailDivider()
                McpDetailRow(
                    stringResource(R.string.mcp_form_startup_timeout),
                    server.startupTimeoutSeconds?.toString() ?: stringResource(R.string.mcp_form_startup_timeout_placeholder),
                )
            } else {
                val url = server.url.orEmpty()
                McpDetailDivider()
                // Long-press copies the URL, like a skill's Source row.
                McpDetailRow(
                    label = stringResource(R.string.mcp_form_url),
                    value = url,
                    monospace = true,
                    onLongClick = url.takeIf { it.isNotBlank() }?.let {
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            copyMcpUrl(context, it)
                        }
                    },
                )
                if (server.oauth != null) {
                    McpDetailDivider()
                    McpDetailRow(
                        "OAuth",
                        stringResource(if (authorized) R.string.mcp_form_oauth_authorized else R.string.session_mcp_oauth_not_authorized),
                    )
                }
            }
        }

        val note = server.note?.trim().orEmpty()
        if (note.isNotEmpty()) {
            SettingsSection(header = stringResource(R.string.session_mcp_detail_note)) {
                McpDetailText(note)
            }
        }

        val headerNames = mcpSecretNames(server.headers)
        val envNames = mcpSecretNames(server.env)
        if (headerNames.isNotEmpty()) {
            SettingsSection(header = stringResource(R.string.session_mcp_detail_headers)) {
                McpDetailText(headerNames.joinToString("\n"), monospace = true)
            }
        }
        if (envNames.isNotEmpty()) {
            SettingsSection(header = stringResource(R.string.session_mcp_detail_env)) {
                McpDetailText(envNames.joinToString("\n"), monospace = true)
            }
        }
        if (headerNames.isNotEmpty() || envNames.isNotEmpty()) {
            Text(
                text = stringResource(R.string.session_mcp_detail_values_hidden),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
                lineHeight = 16.sp,
            )
        }
        Spacer(modifier = Modifier.padding(bottom = 24.dp))
    }
}

/** Label above a full-width value; selectable unless the row takes a long-press. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun McpDetailRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(2.dp))
        val text: @Composable () -> Unit = {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.let {
                    if (monospace) it.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp) else it
                },
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        // A SelectionContainer would take the long-press for itself.
        if (onLongClick != null) text() else SelectionContainer { text() }
    }
}

@Composable
private fun McpDetailText(text: String, monospace: Boolean = false) {
    SelectionContainer {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.let {
                if (monospace) it.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp) else it
            },
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun McpDetailDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}

private fun copyMcpUrl(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
    // Android 13+ shows its own clipboard chip; the Toast covers < 13.
    Toast.makeText(context, context.getString(R.string.session_mcp_url_copied), Toast.LENGTH_SHORT).show()
}

@Composable
private fun EmptyMcpsCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.Extension,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            )
            Text(
                stringResource(R.string.session_mcps_empty_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.session_mcps_empty_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}
