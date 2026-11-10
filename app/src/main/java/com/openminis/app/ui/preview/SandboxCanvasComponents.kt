package com.openminis.app.ui.preview

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.sandbox.SandboxServerInstance
import com.openminis.app.sandbox.SandboxServerManager
import com.openminis.app.sandbox.SandboxServerStatus
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val LiveEmerald = Color(0xFF10B981)
private val StartingAmber = Color(0xFFF59E0B)
private val StoppedSlate = Color(0xFF64748B)

/**
 * Floating dock bar rendered in `ChatScreen` above the composer / tool status bar
 * whenever one or more local web servers are active in the Alpine sandbox.
 *
 * Gives the user instant 1-tap access to:
 * - Re-open the Interactive Micro-App Canvas (`onOpenCanvas`)
 * - Expand directly to fullscreen
 * - Hide (minimize/collapse) or Show (restore) the Open Canvas card without closing or
 *   stopping the live server (`isMinimized` / `onToggleMinimized`)
 * - Switch between active ports, restart a stopped server, or stop/dismiss a server.
 */
@Composable
fun LiveSandboxServerBar(
    servers: List<SandboxServerInstance>,
    onOpenCanvas: (SandboxServerInstance, Boolean) -> Unit,
    onStopServer: (Int) -> Unit,
    modifier: Modifier = Modifier,
    isMinimized: Boolean? = null,
    onToggleMinimized: (() -> Unit)? = null,
) {
    if (servers.isEmpty()) return

    val context = LocalContext.current
    val managerMinimized by SandboxServerManager.isCardMinimized.collectAsState()
    val effectiveMinimized = isMinimized ?: managerMinimized
    val toggleMinimizedAction = onToggleMinimized ?: { SandboxServerManager.toggleCardMinimized() }

    var selectedPort by remember { mutableIntStateOf(servers.first().port) }
    val activeServer = servers.firstOrNull { it.port == selectedPort } ?: servers.first()

    LaunchedEffect(servers) {
        if (servers.none { it.port == selectedPort }) {
            selectedPort = servers.first().port
        }
    }

    val statusColor = when (activeServer.status) {
        SandboxServerStatus.LIVE -> LiveEmerald
        SandboxServerStatus.STARTING -> StartingAmber
        SandboxServerStatus.STOPPED -> StoppedSlate
    }

    val pulseTransition = rememberInfiniteTransition(label = "sandbox_live_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (activeServer.status == SandboxServerStatus.LIVE) 0.35f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sandbox_dot_alpha",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag("live_sandbox_server_bar"),
        contentAlignment = if (effectiveMinimized) Alignment.CenterEnd else Alignment.Center,
    ) {
        AnimatedContent(
            targetState = effectiveMinimized,
            transitionSpec = {
                fadeIn(animationSpec = tween(180)) togetherWith fadeOut(animationSpec = tween(140))
            },
            label = "sandbox_card_minimize_transition",
        ) { minimized ->
            if (minimized) {
                // Compact floating pill when the Open Canvas card is hidden/minimized.
                // Keeps the server & canvas alive while freeing chat viewport space.
                Surface(
                    modifier = Modifier
                        .testTag("sandbox_minimized_pill"),
                    shape = CircleShape,
                    color = ChatColors.toolCapsuleBg,
                    tonalElevation = 4.dp,
                    shadowElevation = 5.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .border(0.75.dp, statusColor.copy(alpha = 0.55f), CircleShape)
                            .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Pulsing status dot + port + short title (tappable to open canvas)
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { onOpenCanvas(activeServer, false) }
                                .padding(vertical = 2.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .alpha(pulseAlpha)
                                    .clip(CircleShape)
                                    .background(statusColor),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = ":${activeServer.port}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = statusColor,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = activeServer.title,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 136.dp),
                            )
                            if (servers.size > 1) {
                                Spacer(modifier = Modifier.width(5.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        text = "${servers.size}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // Compact Open Canvas button inside minimized pill
                        Row(
                            modifier = Modifier
                                .height(26.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable { onOpenCanvas(activeServer, false) }
                                .padding(horizontal = 9.dp)
                                .testTag("sandbox_open_canvas_button"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Web,
                                contentDescription = stringResource(R.string.sandbox_canvas_open),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.sandbox_canvas_open),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }

                        Spacer(modifier = Modifier.width(3.dp))

                        // Show / Expand Card button
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                                .clickable { toggleMinimizedAction() }
                                .testTag("sandbox_show_card_button"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = stringResource(R.string.sandbox_canvas_show_card),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                }
            } else {
                // Full Expanded Open Canvas Card
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = ChatColors.toolCapsuleBg,
                    tonalElevation = 3.dp,
                    shadowElevation = 4.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .border(0.75.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Status indicator + Port badge
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(statusColor.copy(alpha = 0.14f))
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .alpha(pulseAlpha)
                                        .clip(CircleShape)
                                        .background(statusColor),
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = ":${activeServer.port}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor,
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Title & host:port
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onOpenCanvas(activeServer, false) },
                            ) {
                                Text(
                                    text = activeServer.title,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = activeServer.hostPortLabel,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // If stopped, show a 1-tap Start button right on the card
                            if (activeServer.status == SandboxServerStatus.STOPPED) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(LiveEmerald.copy(alpha = 0.16f))
                                        .clickable {
                                            SandboxServerManager.restartServer(context, activeServer.port)
                                        }
                                        .testTag("sandbox_card_restart_button"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = stringResource(R.string.sandbox_canvas_start),
                                        tint = LiveEmerald,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                            }

                            // Open Canvas Primary Pill
                            Row(
                                modifier = Modifier
                                    .height(34.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .clickable { onOpenCanvas(activeServer, false) }
                                    .padding(horizontal = 11.dp)
                                    .testTag("sandbox_open_canvas_button"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Web,
                                    contentDescription = stringResource(R.string.sandbox_canvas_open),
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = stringResource(R.string.sandbox_canvas_open),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }

                            Spacer(modifier = Modifier.width(3.dp))

                            // Fullscreen shortcut
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .clickable { onOpenCanvas(activeServer, true) }
                                    .testTag("sandbox_open_fullscreen_button"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Fullscreen,
                                    contentDescription = stringResource(R.string.webpreview_open_fullscreen),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }

                            // Hide / Minimize Card button (collapses card into pill without stopping server)
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .clickable { toggleMinimizedAction() }
                                    .testTag("sandbox_hide_card_button"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.sandbox_canvas_hide_card),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(19.dp),
                                )
                            }

                            // Stop / Dismiss button
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .clickable { onStopServer(activeServer.port) }
                                    .testTag("sandbox_stop_server_button"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (activeServer.status == SandboxServerStatus.LIVE) {
                                        Icons.Default.Stop
                                    } else {
                                        Icons.Default.Close
                                    },
                                    contentDescription = if (activeServer.status == SandboxServerStatus.LIVE) {
                                        stringResource(R.string.sandbox_canvas_stop)
                                    } else {
                                        stringResource(R.string.sandbox_canvas_dismiss)
                                    },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }

                        // Multi-port switcher row when more than 1 server is registered
                        if (servers.size > 1) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                servers.forEach { srv ->
                                    val isSelected = srv.port == activeServer.port
                                    val chipColor = if (srv.status == SandboxServerStatus.LIVE) LiveEmerald else StoppedSlate
                                    Row(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(
                                                if (isSelected) chipColor.copy(alpha = 0.2f)
                                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                            )
                                            .border(
                                                width = if (isSelected) 1.dp else 0.5.dp,
                                                color = if (isSelected) chipColor else Color.Transparent,
                                                shape = CircleShape,
                                            )
                                            .clickable {
                                                selectedPort = srv.port
                                                onOpenCanvas(srv, false)
                                            }
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                            .testTag("sandbox_port_chip_${srv.port}"),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(chipColor),
                                        )
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Text(
                                            text = ":${srv.port} ${srv.title}",
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                        )
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

/**
 * Interactive sub-bar and expandable Server Logs + JS Console inspector shown inside
 * `WebPreviewBottomSheet` whenever the previewed URL is a local sandbox server (`127.0.0.1:<port>`).
 */
@Composable
fun SandboxCanvasSubbar(
    holder: WebViewHolder,
    port: Int,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val servers by SandboxServerManager.servers.collectAsState()
    val server = servers.firstOrNull { it.port == port }

    var showLogsDrawer by remember { mutableStateOf(false) }
    var isSubbarCollapsed by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Server Logs, 1 = JS Console
    var serverLogText by remember { mutableStateOf("") }
    var isRestarting by remember { mutableStateOf(false) }
    var copiedFeedback by remember { mutableStateOf(false) }

    // Periodically refresh server status & logs while subbar is visible so server crashes/tracebacks are detected
    LaunchedEffect(port, showLogsDrawer) {
        SandboxServerManager.refreshStatuses(context)
        serverLogText = SandboxServerManager.readServerLogs(context, port)
        if (showLogsDrawer) {
            while (true) {
                serverLogText = SandboxServerManager.readServerLogs(context, port)
                delay(1500L)
            }
        }
    }

    val status = server?.status ?: SandboxServerStatus.LIVE
    val errorCount = holder.consoleLogs.count { it.level == "ERROR" }
    val hasServerLogErrors = remember(serverLogText) {
        val text = serverLogText
        text.contains("Traceback (most recent call last)", ignoreCase = true) ||
            text.contains("SyntaxError:", ignoreCase = true) ||
            text.contains("ReferenceError:", ignoreCase = true) ||
            text.contains("TypeError:", ignoreCase = true) ||
            text.contains("ModuleNotFoundError:", ignoreCase = true) ||
            text.contains("ImportError:", ignoreCase = true) ||
            text.contains("EADDRINUSE", ignoreCase = true) ||
            text.contains("Error:", ignoreCase = false) ||
            text.contains("Exception:", ignoreCase = false)
    }
    val hasActionableIssue = errorCount > 0 || hasServerLogErrors || status == SandboxServerStatus.STOPPED

    val triggerFixWithAi: () -> Unit = {
        scope.launch {
            val freshLogs = SandboxServerManager.readServerLogs(context, port).ifBlank { serverLogText }
            val prompt = CanvasAgentBridge.buildAutoFixPrompt(
                url = holder.currentUrl,
                port = port,
                server = server,
                consoleLogs = holder.consoleLogs,
                serverLogs = freshLogs,
            )
            CanvasAgentBridge.dispatchSendPrompt(prompt = prompt, minimizeCanvas = true)
        }
    }

    val statusColor = when {
        isRestarting || holder.isWarmingUpLocalServer || status == SandboxServerStatus.STARTING -> StartingAmber
        status == SandboxServerStatus.LIVE -> LiveEmerald
        else -> StoppedSlate
    }
    val statusLabel = when {
        isRestarting || holder.isWarmingUpLocalServer || status == SandboxServerStatus.STARTING ->
            stringResource(R.string.sandbox_canvas_badge_starting)
        status == SandboxServerStatus.LIVE ->
            stringResource(R.string.sandbox_canvas_badge_live)
        else ->
            stringResource(R.string.sandbox_canvas_badge_stopped)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        if (isSubbarCollapsed) {
            // Ultra-compact collapsed status strip so user gets maximum canvas viewport height
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isSubbarCollapsed = false }
                    .padding(horizontal = 12.dp, vertical = 3.dp)
                    .testTag("sandbox_subbar_collapsed_strip"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "$statusLabel · 127.0.0.1:$port",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (hasActionableIssue) {
                    Text(
                        text = stringResource(R.string.sandbox_canvas_fix_with_ai),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f))
                            .clickable { triggerFixWithAi() }
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.sandbox_canvas_show_subbar),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Live Status Badge
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(statusColor.copy(alpha = 0.16f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(statusColor),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "$statusLabel · :$port",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor,
                    )
                }

                // If multiple sandbox servers exist, show compact port switcher pills
                if (servers.size > 1) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        servers.forEach { srv ->
                            val active = srv.port == port
                            Text(
                                text = ":${srv.port}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                        else Color.Transparent
                                    )
                                    .clickable {
                                        holder.navigateTo(srv.url)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                // 1-Tap "Fix with AI" button surfaced directly in subbar whenever JS/server errors or stopped state exist
                if (hasActionableIssue) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .clickable { triggerFixWithAi() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("sandbox_fix_with_ai_button"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "✦ " + stringResource(R.string.sandbox_canvas_fix_with_ai),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                // Logs & JS Console Inspector toggle
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (showLogsDrawer) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            else MaterialTheme.colorScheme.surface
                        )
                        .clickable {
                            showLogsDrawer = !showLogsDrawer
                            if (showLogsDrawer) {
                                scope.launch {
                                    serverLogText = SandboxServerManager.readServerLogs(context, port)
                                }
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("sandbox_toggle_logs_button"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = stringResource(R.string.sandbox_canvas_logs),
                        tint = if (errorCount > 0 || hasServerLogErrors) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (errorCount > 0) {
                            "${stringResource(R.string.sandbox_canvas_logs)} ($errorCount)"
                        } else {
                            stringResource(R.string.sandbox_canvas_logs)
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (errorCount > 0 || hasServerLogErrors) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Start / Restart Server button
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable(enabled = !isRestarting) {
                            isRestarting = true
                            SandboxServerManager.restartServer(context, port) {
                                isRestarting = false
                                holder.reload()
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("sandbox_restart_server_button"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (status == SandboxServerStatus.STOPPED) Icons.Default.PlayArrow else Icons.Default.Refresh,
                        contentDescription = if (status == SandboxServerStatus.STOPPED) {
                            stringResource(R.string.sandbox_canvas_start)
                        } else {
                            stringResource(R.string.sandbox_canvas_restart)
                        },
                        tint = if (status == SandboxServerStatus.STOPPED) LiveEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = if (status == SandboxServerStatus.STOPPED) {
                            stringResource(R.string.sandbox_canvas_start)
                        } else {
                            stringResource(R.string.sandbox_canvas_restart)
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Stop Server button
                if (status == SandboxServerStatus.LIVE) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f))
                            .clickable {
                                SandboxServerManager.stopServer(context, port, removeFromList = false)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("sandbox_sheet_stop_button"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = stringResource(R.string.sandbox_canvas_stop),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = stringResource(R.string.sandbox_canvas_stop),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Collapse subbar chevron to maximize canvas area
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable {
                            showLogsDrawer = false
                            isSubbarCollapsed = true
                        }
                        .testTag("sandbox_collapse_subbar_button"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.sandbox_canvas_hide_subbar),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // Expandable Server Logs & JS Console Drawer
        AnimatedVisibility(visible = showLogsDrawer && !isSubbarCollapsed) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 220.dp)
                    .background(Color(0xFF0F172A))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Tab 0: Server Logs
                    Text(
                        text = stringResource(R.string.sandbox_canvas_server_logs_tab),
                        fontSize = 11.sp,
                        fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                        color = if (selectedTab == 0) Color.White else Color(0xFF94A3B8),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selectedTab == 0) Color(0xFF1E293B) else Color.Transparent)
                            .clickable { selectedTab = 0 }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    // Tab 1: JS Console
                    Text(
                        text = stringResource(R.string.sandbox_canvas_js_console_tab, holder.consoleLogs.size),
                        fontSize = 11.sp,
                        fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                        color = if (selectedTab == 1) Color.White else Color(0xFF94A3B8),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selectedTab == 1) Color(0xFF1E293B) else Color.Transparent)
                            .clickable { selectedTab = 1 }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    Spacer(modifier = Modifier.weight(1f))

                    // Always-available "Fix with AI" action in the logs drawer header
                    Text(
                        text = "✦ " + stringResource(R.string.sandbox_canvas_fix_with_ai),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFFCA5A5),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF7F1D1D).copy(alpha = 0.65f))
                            .clickable { triggerFixWithAi() }
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                            .testTag("sandbox_drawer_fix_with_ai_button"),
                    )
                    Spacer(modifier = Modifier.width(6.dp))

                    // Copy logs action
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                val textToCopy = if (selectedTab == 0) {
                                    serverLogText
                                } else {
                                    holder.consoleLogs.joinToString("\n") { entry ->
                                        "[${entry.level}] ${entry.message} (${entry.sourceId}:${entry.lineNumber})"
                                    }
                                }
                                if (textToCopy.isNotBlank()) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("Canvas Logs", textToCopy))
                                    copiedFeedback = true
                                    scope.launch {
                                        delay(1500L)
                                        copiedFeedback = false
                                    }
                                }
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.sandbox_canvas_copy_logs),
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = if (copiedFeedback) {
                                stringResource(R.string.sandbox_canvas_copied_logs)
                            } else {
                                stringResource(R.string.sandbox_canvas_copy_logs)
                            },
                            fontSize = 11.sp,
                            color = if (copiedFeedback) LiveEmerald else Color(0xFF94A3B8),
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    if (selectedTab == 1 && holder.consoleLogs.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.sandbox_canvas_clear_console),
                            fontSize = 11.sp,
                            color = Color(0xFF38BDF8),
                            modifier = Modifier
                                .clickable { holder.clearConsoleLogs() }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    } else if (selectedTab == 0) {
                        if (serverLogText.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.sandbox_canvas_clear_console),
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8),
                                modifier = Modifier
                                    .clickable {
                                        scope.launch {
                                            SandboxServerManager.clearServerLogs(context, port)
                                            serverLogText = ""
                                        }
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = stringResource(R.string.sandbox_canvas_refresh_logs),
                            fontSize = 11.sp,
                            color = Color(0xFF38BDF8),
                            modifier = Modifier
                                .clickable {
                                    scope.launch {
                                        serverLogText = SandboxServerManager.readServerLogs(context, port)
                                    }
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (selectedTab == 0) {
                        Text(
                            text = serverLogText.ifBlank {
                                stringResource(R.string.sandbox_canvas_no_server_logs)
                            },
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFE2E8F0),
                            lineHeight = 15.sp,
                        )
                    } else {
                        if (holder.consoleLogs.isEmpty()) {
                            Text(
                                text = stringResource(R.string.sandbox_canvas_no_console_logs),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF94A3B8),
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                holder.consoleLogs.forEach { entry ->
                                    val entryColor = when (entry.level) {
                                        "ERROR" -> Color(0xFFF87171)
                                        "WARNING" -> Color(0xFFFBBF24)
                                        else -> Color(0xFFE2E8F0)
                                    }
                                    Text(
                                        text = "[${entry.level}] ${entry.message} (${entry.sourceId}:${entry.lineNumber})",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = entryColor,
                                        lineHeight = 14.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * Clean warming-up overlay displayed over the WebView while a local server on
 * `127.0.0.1:<port>` is still binding its socket during the first few hundred milliseconds.
 */
@Composable
fun SandboxWarmingUpOverlay(
    hostPort: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(36.dp),
                strokeWidth = 3.dp,
                color = LiveEmerald,
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.sandbox_canvas_warming_up_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.sandbox_canvas_warming_up_subtitle, hostPort),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
