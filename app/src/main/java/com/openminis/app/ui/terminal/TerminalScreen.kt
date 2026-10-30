package com.openminis.app.ui.terminal

import com.openminis.app.R
import androidx.compose.ui.res.stringResource

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Eject
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.sandbox.TerminalSession
import com.openminis.app.terminal.MinisOpenUrlBroker
import com.openminis.app.ui.terminal.canvas.TerminalNativeViewCompose
import com.openminis.app.ui.terminal.canvas.TerminalInputView
import com.openminis.app.ui.terminal.canvas.rememberTerminalInputController
import com.openminis.app.ui.terminal.emulator.TerminalEmulator

// iOS-matched palette with rich dark accents
private val TerminalBg = Color(0xFF0A0C10)
private val TerminalHeaderBg = Color(0xFF12161F)
private val TerminalFg = Color(0xFFE2E8F0)
private val TerminalGreen = Color(0xFF34C759)
private val TerminalAmber = Color(0xFFF59E0B)
private val TerminalRed = Color(0xFFEF4444)
private val TerminalCyan = Color(0xFF38BDF8)
private val AccessoryBg = Color(0xFF181C26)
private val AccButtonBg = Color(0xFF283040)
private val AccButtonActive = Color(0xFF007AFF)
private val TopButtonBg = Color(0xFF222836)

private data class TerminalSnippetCategory(
    val title: String,
    val snippets: List<Pair<String, String>>,
)

private val TERMINAL_SNIPPET_CATEGORIES = listOf(
    TerminalSnippetCategory(
        title = "Workspace",
        snippets = listOf(
            "ls /var/minis" to "ls -la /var/minis",
            "cd workspace" to "cd /var/minis/workspace && ls -la",
            "Disk usage" to "du -sh /var/minis/* 2>/dev/null || df -h",
            "Find files" to "find /var/minis -maxdepth 3",
            "Skills list" to "ls -la /var/minis/skills",
        ),
    ),
    TerminalSnippetCategory(
        title = "System",
        snippets = listOf(
            "Sys info" to "uname -a && cat /etc/os-release 2>/dev/null",
            "Processes" to "ps aux 2>/dev/null || ps",
            "Storage" to "df -h",
            "Memory" to "free -m 2>/dev/null || cat /proc/meminfo | head -n 6",
            "Env vars" to "env | sort",
        ),
    ),
    TerminalSnippetCategory(
        title = "Alpine APK",
        snippets = listOf(
            "apk update" to "apk update",
            "Installed pkgs" to "apk info | sort",
            "Add curl & git" to "apk add --no-cache curl git",
            "Add python3" to "apk add --no-cache python3 py3-pip",
            "Mirrors" to "cat /etc/apk/repositories 2>/dev/null",
        ),
    ),
    TerminalSnippetCategory(
        title = "Dev & Net",
        snippets = listOf(
            "DNS config" to "cat /etc/resolv.conf",
            "Ping 1.1.1.1" to "ping -c 3 1.1.1.1",
            "Python ver" to "python3 --version",
            "Node ver" to "node --version",
            "Git status" to "git status",
        ),
    ),
)

@Composable
fun TerminalScreen(
    terminalSession: TerminalSession,
    onBack: () -> Unit,
    initCommand: String? = null,
    /**
     * When non-null, binds this terminal to the given chat session —
     * TerminalSession.start() will chdir into /var/minis and pick up the
     * session's env vars (mirrors iOS "Open Terminal" from chat).
     */
    sessionId: String? = null,
) {
    val emulator = remember { TerminalEmulator() }
    val context = LocalContext.current
    val inputController = rememberTerminalInputController()
    var ctrlActive by remember { mutableStateOf(false) }
    var showSnippetsDrawer by remember { mutableStateOf(false) }
    var selectedCategoryIndex by remember { mutableIntStateOf(0) }

    val sessionState by terminalSession.state.collectAsStateEffect()
    val isProotAvailable = remember(context) {
        RootfsManager.getInstance(context).isProotAvailable
    }

    // Pipe PTY output → emulator.
    LaunchedEffect(terminalSession) {
        terminalSession.outputBytes.collect { bytes ->
            emulator.feed(bytes)
        }
    }

    // Wire emulator responses (DSR etc.) back to the PTY.
    DisposableEffect(terminalSession, emulator) {
        emulator.onResponse = { data -> terminalSession.sendRawBytes(data) }
        onDispose { emulator.onResponse = null }
    }

    // Clear emulator when session clearOutput() ticks.
    val clearVersion by terminalSession.clearVersion.collectAsStateEffect()
    LaunchedEffect(clearVersion) {
        if (clearVersion > 0) {
            emulator.feed("\u001Bc".toByteArray())   // RIS — full reset
        }
    }

    // Start session + optional initCommand.
    LaunchedEffect(Unit) {
        if (!terminalSession.isRunning) terminalSession.start(sessionId = sessionId)
        if (!initCommand.isNullOrBlank()) {
            // Pre-fill at the prompt without newline so the user can review.
            kotlinx.coroutines.delay(500)
            terminalSession.sendText(initCommand)
        }
    }

    val hasHardwareKeyboard = com.openminis.app.ui.components.rememberHasHardwareKeyboard()
    LaunchedEffect(hasHardwareKeyboard) {
        if (hasHardwareKeyboard) inputController.requestFocus()
    }

    DisposableEffect(Unit) {
        onDispose { terminalSession.stop() }
    }

    DisposableEffect(Unit) {
        MinisOpenUrlBroker.setTerminalVisible(true)
        onDispose { MinisOpenUrlBroker.setTerminalVisible(false) }
    }

    var previewUrl by remember { mutableStateOf<String?>(null) }
    val pendingUrl by MinisOpenUrlBroker.pendingUrl.collectAsStateEffect()
    LaunchedEffect(pendingUrl) {
        val uri = pendingUrl ?: return@LaunchedEffect
        if (MinisOpenUrlBroker.isWebScheme(uri.scheme)) {
            previewUrl = uri.toString()
        }
        MinisOpenUrlBroker.consume()
    }

    val accessoryBarHeightDp = 44.dp

    Box(modifier = Modifier.fillMaxSize().background(TerminalBg)) {
        // Main content: top bar + optional snippet drawer + canvas.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .imePadding()
                .padding(bottom = accessoryBarHeightDp),
        ) {
            TerminalTopBar(
                sessionState = sessionState,
                isProotAvailable = isProotAvailable,
                sessionId = sessionId,
                snippetsExpanded = showSnippetsDrawer,
                onToggleSnippets = { showSnippetsDrawer = !showSnippetsDrawer },
                onRestart = {
                    emulator.feed("\u001Bc".toByteArray())
                    terminalSession.start(sessionId = sessionId)
                },
                onClose = {
                    terminalSession.stop()
                    onBack()
                },
                onClear = {
                    terminalSession.sendRawBytes(byteArrayOf(0x15))
                    terminalSession.clearOutput()
                    emulator.feed("\u001Bc".toByteArray())
                },
            )

            AnimatedVisibility(
                visible = showSnippetsDrawer,
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    )
                ) + fadeIn(tween(180)),
                exit = shrinkVertically(
                    animationSpec = spring(stiffness = Spring.StiffnessMedium)
                ) + fadeOut(tween(140)),
            ) {
                TerminalSnippetsDrawer(
                    selectedCategoryIndex = selectedCategoryIndex,
                    onSelectCategory = { selectedCategoryIndex = it },
                    onRunSnippet = { cmd ->
                        emulator.scrollOffset = 0
                        if (!terminalSession.isRunning) {
                            terminalSession.start(sessionId = sessionId)
                        }
                        terminalSession.sendText("$cmd\n")
                    },
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                TerminalNativeViewCompose(
                    emulator = emulator,
                    onResize = { cols, rows ->
                        emulator.resize(cols, rows)
                        terminalSession.setWindowSize(cols, rows)
                    },
                    onTap = { inputController.requestFocus() },
                )
                TerminalInputView(
                    onInput = { bytes ->
                        emulator.scrollOffset = 0
                        if (ctrlActive && bytes.size == 1) {
                            val ch = bytes[0].toInt().toChar().uppercaseChar()
                            if (ch in 'A'..'Z') {
                                terminalSession.sendRawBytes(byteArrayOf((ch - 'A' + 1).toByte()))
                                ctrlActive = false
                                return@TerminalInputView
                            }
                        }
                        terminalSession.sendRawBytes(bytes)
                    },
                    applicationCursorKeys = emulator.applicationCursorKeys,
                    controller = inputController,
                    modifier = Modifier.size(1.dp),
                )
            }
        }

        // Accessory bar anchored to bottom and lifted above the IME via imePadding()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .imePadding()
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            KeyboardAccessoryBar(
                ctrlActive = ctrlActive,
                keyboardVisible = inputController.isFocused,
                snippetsActive = showSnippetsDrawer,
                onCtrlToggle = { ctrlActive = !ctrlActive },
                onToggleKeyboard = {
                    if (inputController.isFocused) inputController.clearFocus()
                    else inputController.requestFocus()
                },
                onToggleSnippets = { showSnippetsDrawer = !showSnippetsDrawer },
                onCopyAll = { copyAllToClipboard(context, emulator) },
                onPaste = {
                    pasteFromClipboard(context) { text ->
                        emulator.scrollOffset = 0
                        terminalSession.sendText(text)
                    }
                },
                onSendRaw = { bytes ->
                    emulator.scrollOffset = 0
                    terminalSession.sendRawBytes(bytes)
                },
                onSendText = { text ->
                    emulator.scrollOffset = 0
                    terminalSession.sendText(text)
                },
                onArrow = { dir ->
                    emulator.scrollOffset = 0
                    val prefix = if (emulator.applicationCursorKeys)
                        byteArrayOf(0x1B, 'O'.code.toByte())
                    else byteArrayOf(0x1B, '['.code.toByte())
                    terminalSession.sendRawBytes(prefix + byteArrayOf(dir.code.toByte()))
                },
            )
        }

        previewUrl?.let { url ->
            com.openminis.app.ui.components.UrlPreviewSheet(
                url = url,
                onDismiss = { previewUrl = null },
            )
        }
    }
}

// ── Tiny helper: collectAsState for StateFlow<T> ──
@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateEffect(): androidx.compose.runtime.State<T> {
    val state = remember { androidx.compose.runtime.mutableStateOf(value) }
    LaunchedEffect(this) { collect { state.value = it } }
    return state
}

// ─── Top bar ──────────────────────────────────────────────────────────────────

@Composable
private fun TerminalTopBar(
    sessionState: TerminalSession.State,
    isProotAvailable: Boolean,
    sessionId: String?,
    snippetsExpanded: Boolean,
    onToggleSnippets: () -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit,
    onClear: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "terminal_status_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_scale",
    )

    val statusColor = when (sessionState) {
        TerminalSession.State.RUNNING -> TerminalGreen
        TerminalSession.State.BOOTING, TerminalSession.State.IDLE -> TerminalAmber
        TerminalSession.State.STOPPED -> TerminalRed
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(TerminalHeaderBg, TerminalBg)
                )
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularIconButton(
            icon = Icons.Default.Close,
            contentDescription = stringResource(R.string.common_close),
            tint = TerminalFg,
            onClick = onClose,
        )
        Spacer(modifier = Modifier.weight(1f))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .scale(if (sessionState == TerminalSession.State.RUNNING) pulseScale else 1f)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Text(
                    stringResource(R.string.terminal_title),
                    color = TerminalFg,
                    style = TextStyle(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    ),
                )
                // Engine badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isProotAvailable) TerminalGreen.copy(alpha = 0.16f)
                            else TerminalCyan.copy(alpha = 0.16f)
                        )
                        .border(
                            0.5.dp,
                            if (isProotAvailable) TerminalGreen.copy(alpha = 0.45f)
                            else TerminalCyan.copy(alpha = 0.45f),
                            RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (isProotAvailable) "ALPINE" else "HOST SH",
                        color = if (isProotAvailable) TerminalGreen else TerminalCyan,
                        style = TextStyle(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }
            if (sessionId != null) {
                Text(
                    text = "chat:${sessionId.take(8)} · /var/minis",
                    color = TerminalFg.copy(alpha = 0.55f),
                    style = TextStyle(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.sp,
                    ),
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (sessionState == TerminalSession.State.STOPPED) {
                CircularIconButton(
                    icon = Icons.Default.Refresh,
                    contentDescription = "Restart Shell",
                    tint = TerminalAmber,
                    onClick = onRestart,
                )
            }
            CircularIconButton(
                icon = Icons.Default.Bolt,
                contentDescription = "Quick Snippets",
                tint = if (snippetsExpanded) Color.White else TerminalCyan,
                backgroundColor = if (snippetsExpanded) AccButtonActive else TopButtonBg,
                onClick = onToggleSnippets,
            )
            CircularIconButton(
                icon = Icons.Default.Brush,
                contentDescription = stringResource(R.string.terminal_clear),
                tint = TerminalGreen,
                onClick = onClear,
            )
        }
    }
}

@Composable
private fun TerminalSnippetsDrawer(
    selectedCategoryIndex: Int,
    onSelectCategory: (Int) -> Unit,
    onRunSnippet: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF131822))
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Category selector row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TERMINAL_SNIPPET_CATEGORIES.forEachIndexed { idx, cat ->
                val selected = idx == selectedCategoryIndex
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) AccButtonActive else Color(0xFF1F2735))
                        .clickable { onSelectCategory(idx) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = cat.title,
                        color = if (selected) Color.White else TerminalFg.copy(alpha = 0.75f),
                        style = TextStyle(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }

        // Snippets chips row
        val activeCategory = TERMINAL_SNIPPET_CATEGORIES.getOrElse(selectedCategoryIndex) {
            TERMINAL_SNIPPET_CATEGORIES.first()
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            activeCategory.snippets.forEach { (label, command) ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E2636))
                        .border(0.5.dp, TerminalCyan.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .clickable { onRunSnippet(command) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "❯",
                        color = TerminalGreen,
                        style = TextStyle(fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp),
                    )
                    Column {
                        Text(
                            text = label,
                            color = TerminalFg,
                            style = TextStyle(
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                        Text(
                            text = command,
                            color = TerminalFg.copy(alpha = 0.5f),
                            style = TextStyle(
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 9.sp,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CircularIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    backgroundColor: Color = TopButtonBg,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ─── Copy All & Paste (#451) ──────────────────────────────────────────────────

private fun copyAllToClipboard(context: Context, emulator: TerminalEmulator) {
    val text = emulator.copyableText()
    if (text.isEmpty()) return
    runCatching {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("Minis Shell", text))
    }.onSuccess {
        Toast.makeText(context, R.string.terminal_copied_all, Toast.LENGTH_SHORT).show()
    }.onFailure {
        com.openminis.app.logging.AppLogger.error("TerminalScreen", "copy all (${text.length} chars) failed: $it")
        Toast.makeText(
            context,
            context.getString(R.string.image_copy_failed_toast, it.message ?: it.javaClass.simpleName),
            Toast.LENGTH_SHORT,
        ).show()
    }
}

private fun pasteFromClipboard(context: Context, onText: (String) -> Unit) {
    runCatching {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = cb.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val text = item?.coerceToText(context)?.toString()
        if (!text.isNullOrEmpty()) {
            onText(text)
        } else {
            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        }
    }
}

// ─── Keyboard accessory bar ───────────────────────────────────────────────────

@Composable
private fun KeyboardAccessoryBar(
    ctrlActive: Boolean,
    keyboardVisible: Boolean,
    snippetsActive: Boolean,
    onCtrlToggle: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onToggleSnippets: () -> Unit,
    onCopyAll: () -> Unit,
    onPaste: () -> Unit,
    onSendRaw: (ByteArray) -> Unit,
    onSendText: (String) -> Unit,
    onArrow: (Char) -> Unit,
) {
    val scrollState = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AccessoryBg)
            .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp)),
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuickCommandButton(
                label = stringResource(if (keyboardVisible) R.string.terminal_hide_keyboard else R.string.terminal_show_keyboard),
                icon = if (keyboardVisible) Icons.Default.KeyboardHide else Icons.Outlined.Keyboard,
                onClick = onToggleKeyboard,
            )
            QuickCommandButton(
                label = "Snippets",
                icon = Icons.Default.Bolt,
                isActive = snippetsActive,
                onClick = onToggleSnippets,
            )
            QuickCommandButton(
                label = "Paste",
                icon = Icons.Default.ContentPaste,
                onClick = onPaste,
            )
            QuickCommandButton(
                label = stringResource(R.string.terminal_copy_all),
                icon = Icons.Outlined.ContentCopy,
                onClick = onCopyAll,
            )
            QuickCommandButton("Esc", iconText = "⎋") { onSendRaw(byteArrayOf(0x1B)) }
            QuickCommandButton("Tab", icon = Icons.AutoMirrored.Filled.KeyboardTab) { onSendRaw(byteArrayOf(0x09)) }
            QuickCommandButton("⏎", iconText = "⏎") { onSendRaw(byteArrayOf(0x0D)) }
            QuickCommandButton("Ctrl", iconText = "^", isActive = ctrlActive, onClick = onCtrlToggle)
            QuickCommandButton("\u2191", icon = Icons.Default.KeyboardArrowUp) { onArrow('A') }
            QuickCommandButton("\u2193", icon = Icons.Default.KeyboardArrowDown) { onArrow('B') }
            QuickCommandButton("\u2190", icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft) { onArrow('D') }
            QuickCommandButton("\u2192", icon = Icons.AutoMirrored.Filled.KeyboardArrowRight) { onArrow('C') }
            QuickCommandButton("C-c", icon = Icons.Outlined.Cancel) { onSendRaw(byteArrayOf(0x03)) }
            QuickCommandButton("C-d", icon = Icons.Default.Eject) { onSendRaw(byteArrayOf(0x04)) }
            QuickCommandButton("C-z", icon = Icons.Outlined.PauseCircle) { onSendRaw(byteArrayOf(0x1A)) }
            QuickCommandButton("|") { onSendText("|") }
            QuickCommandButton("/") { onSendText("/") }
            QuickCommandButton("-") { onSendText("-") }
            QuickCommandButton("~") { onSendText("~") }
        }
    }
}

@Composable
private fun QuickCommandButton(
    label: String,
    icon: ImageVector? = null,
    iconText: String? = null,
    isActive: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = if (isActive) AccButtonActive else AccButtonBg
    val fg = if (isActive) Color.White else TerminalGreen
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            icon != null -> Icon(icon, null, tint = fg, modifier = Modifier.size(12.dp))
            iconText != null -> Text(iconText, color = fg, style = TextStyle(fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp))
        }
        Text(label, color = fg, style = TextStyle(fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp), maxLines = 1)
    }
}
