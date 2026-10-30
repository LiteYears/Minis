package com.openminis.app.ui.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.openminis.app.R
import com.openminis.app.browser.BrowserHistoryStore
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.MinisTextButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Centralized router for non-http(s) URL schemes that show up inside our
 * various WebViews. Without this, schemes like `intent://`, `market://`,
 * `tel:`, `mailto:`, `sms:`, `geo:` would be handed to the page-load
 * pipeline and the user would see `net::ERR_UNKNOWN_URL_SCHEME` (T134).
 *
 * Behavior:
 *
 *   * `intent://...` — parsed via `Intent.parseUri(_, URI_INTENT_SCHEME)`
 *     so `action`, `category`, and extras encoded after `#Intent;` get
 *     restored. We strip `FLAG_ACTIVITY_NEW_DOCUMENT` and the package
 *     selector (set when the spec restricts to one app) before fanning
 *     out via `startActivity`. If no app handles the intent, the spec's
 *     `browser_fallback_url` extra is honoured; absent that, a Toast.
 *   * Other "open in app" schemes — fired as a plain `ACTION_VIEW`
 *     against the URI. ActivityNotFoundException → Toast.
 *   * `http`, `https`, `about`, `file` — return `false` so the WebView
 *     loads them normally.
 *
 * Returns `true` when the URL has been (or should have been) routed
 * away from the WebView; the caller should also return `true` from
 * `shouldOverrideUrlLoading` in that case.
 */
object BrowserExternalSchemeHandler {
    private const val TAG = "BrowserExternalScheme"

    private val INTERNAL_SCHEMES = setOf("http", "https", "about", "file")

    /**
     * `intent://` schemes that require Intent.parseUri (NOT plain
     * ACTION_VIEW) to round-trip correctly.
     */
    private val INTENT_SCHEME = "intent"
    private val ANDROID_APP_SCHEME = "android-app"

    /**
     * "Open in another app" schemes that are fine to dispatch as a bare
     * `ACTION_VIEW` (the system will resolve to dialer / mail / maps /
     * Play Store etc.). `intent`/`android-app` are NOT in this list —
     * they need `parseUri`.
     *
     * [T-android-user-initiated-scheme-dispatch] This list now only gates the
     * [Origin.AGENT_BACKGROUND] path. A user-initiated tap dispatches ANY
     * scheme, so the list never has to grow to keep up with new apps.
     */
    private val EXTERNAL_VIEW_SCHEMES = setOf(
        "tel", "mailto", "sms", "smsto", "mms", "mmsto",
        "geo", "market", "whatsapp", "tg", "weixin",
    )

    /**
     * [T-android-user-initiated-scheme-dispatch] Who asked for this navigation.
     *
     * T318 blocked unrecognized app schemes because the agent driving a
     * headless browser could silently hurl the user into Taobao/Weibo/etc.
     * mid-task. That reasoning holds ONLY for navigation the user did not ask
     * for. The same handler is also wired to deliberate taps in chat, and
     * there the block is simply wrong: the user pointed at a map link and got
     * a Toast.
     *
     * The distinction is the ORIGIN of the navigation, not the scheme — an
     * allowlist can never keep pace with new apps, which is the actual defect.
     */
    enum class Origin {
        /**
         * The user tapped something they can see: a chat-message link, or a
         * link inside a visible in-app browser / preview WebView. Their intent
         * is explicit, so every scheme dispatches normally.
         */
        USER_INITIATED,

        /**
         * The agent is driving a background/headless WebView
         * ([com.openminis.app.browser.BrowserUseManager]) and the PAGE chose
         * to navigate. The user is not watching and did not ask, so unknown
         * app schemes stay blocked — this is T318's original case.
         */
        AGENT_BACKGROUND,
    }

    /**
     * [T-android-user-initiated-scheme-dispatch] What [handle] will do with a
     * scheme. Split out from [handle] so the routing decision is pure and can
     * be unit-tested on the JVM — [handle] itself needs a `Context` and fires
     * Toasts / `startActivity`, none of which exist in a plain JVM test.
     */
    internal enum class Route {
        /** `http`/`https`/`about`/`file` — let the WebView load it. */
        LOAD_IN_WEBVIEW,

        /** `intent:` / `android-app:` — needs `Intent.parseUri`. */
        PARSE_URI,

        /** Dispatch as a plain `ACTION_VIEW`. */
        VIEW_INTENT,

        /** Swallow it and tell the user (agent-background only). */
        BLOCK,
    }

    /**
     * [T-android-user-initiated-scheme-dispatch] Decide how [scheme] should be
     * routed given who asked for it.
     *
     * The only asymmetry between the two origins is the final fallback: a
     * scheme nobody recognizes dispatches for [Origin.USER_INITIATED] (they
     * tapped it on purpose) and is blocked for [Origin.AGENT_BACKGROUND] (the
     * page decided, and the user is not even looking at it).
     */
    internal fun route(scheme: String?, origin: Origin): Route {
        val s = scheme?.lowercase() ?: return Route.LOAD_IN_WEBVIEW
        return when {
            s in INTERNAL_SCHEMES -> Route.LOAD_IN_WEBVIEW
            s == INTENT_SCHEME || s == ANDROID_APP_SCHEME -> Route.PARSE_URI
            s in EXTERNAL_VIEW_SCHEMES -> Route.VIEW_INTENT
            origin == Origin.USER_INITIATED -> Route.VIEW_INTENT
            else -> Route.BLOCK
        }
    }

    /**
     * Whether [url] is an external scheme that this handler would route
     * away from any in-app WebView. Used by callers (e.g. the chat-link
     * resolver) that need to decide *before* a `loadUrl` whether the
     * URL should bypass the in-app browser entirely — `loadUrl` doesn't
     * trigger `shouldOverrideUrlLoading` for the initial URL, so an
     * `intent://...` would otherwise reach the WebView and produce
     * `ERR_UNKNOWN_URL_SCHEME` (T136).
     */
    fun shouldHandleExternally(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { url.toUri() }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        return scheme !in INTERNAL_SCHEMES
    }

    /**
     * Decide whether [uri] should be intercepted and, if so, route it to
     * an external Activity. Returns `true` when the WebView should NOT
     * load the URL itself.
     */
    fun handle(
        context: Context,
        url: String?,
        origin: Origin = Origin.USER_INITIATED,
    ): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { url.toUri() }.getOrNull() ?: return false
        return handle(context, uri, origin)
    }

    fun handle(
        context: Context,
        uri: Uri?,
        origin: Origin = Origin.USER_INITIATED,
    ): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme in INTERNAL_SCHEMES) return false

        return when (route(scheme, origin)) {
            Route.PARSE_URI -> handleIntentScheme(context, uri.toString())
            Route.VIEW_INTENT -> handleViewIntent(context, uri)
            Route.LOAD_IN_WEBVIEW -> false
            Route.BLOCK -> {
                // T318: the agent is driving a background WebView and the PAGE
                // decided to navigate (`toutiao://`, `snssdk141://`, `weibo://`
                // and friends). Firing ACTION_VIEW here would throw the user
                // out of Minis into another app mid-task, for something they
                // never asked for, and would destroy the agent's browsing
                // context. Swallow it.
                AppLogger.info(TAG, "blocked unknown scheme: $scheme (uri=$uri, origin=$origin)")
                Toast.makeText(
                    context,
                    context.getString(R.string.external_link_blocked, scheme),
                    Toast.LENGTH_SHORT,
                ).show()
                true
            }
        }
    }

    private fun handleIntentScheme(context: Context, url: String): Boolean {
        val intent = runCatching {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        }.getOrElse {
            AppLogger.warning(TAG, "parseUri failed for $url: ${it.message}")
            return true
        }

        // Drop selector + browser-only flags so the system resolves the
        // intent against any matching Activity, not just the package the
        // page suggested (which is often unavailable).
        intent.selector = null
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val fallback = intent.getStringExtra("browser_fallback_url")

        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            AppLogger.info(TAG, "No app handles $url; fallback=${fallback != null}")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "startActivity failed for $url: ${e.message}")
        }

        // No handler — try fallback URL if the page provided one.
        if (!fallback.isNullOrBlank()) {
            try {
                val fb = Intent(Intent.ACTION_VIEW, fallback.toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fb)
                return true
            } catch (e: Exception) {
                AppLogger.warning(TAG, "fallback $fallback failed: ${e.message}")
            }
        }

        Toast.makeText(
            context,
            context.getString(R.string.external_link_no_app),
            Toast.LENGTH_SHORT,
        ).show()
        return true
    }

    private fun handleViewIntent(context: Context, uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                context,
                context.getString(R.string.external_link_no_app),
                Toast.LENGTH_SHORT,
            ).show()
        } catch (e: Exception) {
            AppLogger.warning(TAG, "ACTION_VIEW failed for $uri: ${e.message}")
        }
        return true
    }
}

// ─── Merged from BrowserHistorySheet.kt ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserHistorySheet(
    historyStore: BrowserHistoryStore,
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }

    val entries = remember(searchQuery, historyStore.getEntries().size) {
        if (searchQuery.isBlank()) historyStore.groupedByDay()
        else mapOf("Results" to historyStore.search(searchQuery))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .navigationBarsPadding(),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.browser_history_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                MinisTextButton(
                    onClick = { showClearConfirm = true },
                    enabled = historyStore.getEntries().isNotEmpty(),
                ) {
                    Text(stringResource(R.string.browser_history_clear), color = MaterialTheme.colorScheme.error)
                }
                MinisTextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.browser_history_done))
                }
            }

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.browser_history_search)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                textStyle = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(8.dp))

            if (entries.values.flatten().isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.browser_history_empty_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.browser_history_empty_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                ) {
                    for ((dayLabel, dayEntries) in entries) {
                        if (dayEntries.isEmpty()) continue
                        item(key = "header_$dayLabel") {
                            Text(
                                dayLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(dayEntries, key = { it.id }) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigate(entry.url) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    Icons.Default.Language,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        entry.title.ifEmpty { entry.domain },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        entry.url,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(entry.timestamp)),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(start = 46.dp))
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.browser_history_clear_dialog_title)) },
            text = { Text(stringResource(R.string.browser_history_clear_dialog_message)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    historyStore.clear()
                    showClearConfirm = false
                }) {
                    Text(stringResource(R.string.browser_history_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

// ─── Merged from BrowserWebView.kt ──────────────────────────────────────────────────

/**
 * Composable wrapper around Android WebView, mirroring iOS BrowserWebView.
 *
 * [T-android-browser-blank] Hosts the WebView inside a FrameLayout container
 * and swaps it in `update` instead of returning the WebView from `factory`.
 * AndroidView's factory runs ONCE per node — with the old direct-return
 * approach, switching tabs (or the pool recreating a WebView after idle
 * eviction) recomposed with a NEW WebView instance but the screen kept
 * showing the stale one: the sheet header/URL bar (StateFlows from the
 * current tab manager) looked correct while the content area rendered a
 * dead/blank WebView — the reported black/white screen. iOS avoids this via
 * `.id(pool.selectedTabId)` remounting; the container+update pattern is the
 * Android equivalent.
 *
 * Ask all ancestor views (including the host ModalBottomSheet) to stop
 * intercepting touches as soon as the user puts a finger down on the
 * WebView, so page scroll gestures never drag the sheet.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun BrowserWebView(
    webView: WebView,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context -> FrameLayout(context) },
        update = { container ->
            val mounted = container.getChildAt(0)
            if (mounted !== webView) {
                container.removeAllViews()
                // The WebView may still be parented to a previous container
                // (sheet re-open racing the old node's disposal) — detach
                // first or addView throws "child already has a parent".
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN,
                        MotionEvent.ACTION_MOVE,
                        MotionEvent.ACTION_POINTER_DOWN ->
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL ->
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
                container.addView(
                    webView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
        onRelease = { container ->
            // Free the WebView so a later mount (same or different sheet)
            // can re-parent it without hitting the has-a-parent guard.
            container.removeAllViews()
        },
        modifier = modifier,
    )
}

// ─── Merged from BrowserDownloadsSheet.kt ──────────────────────────────────────────────────

/**
 * [T-android-browser-download-ux] Downloads panel — Android port of iOS
 * BrowserDownloadPanelSheet (T-browser-download-ux v2/v3): entries
 * newest-first; in-flight rows show live progress + a cancel button;
 * completed rows open the file via ACTION_VIEW (the Android "Show in Files"
 * equivalent — same FileProvider path FilePreviewScreen uses); failed rows
 * show the reason. "Clear" drops ALL finished records (completed AND failed,
 * v3 semantics); in-flight rows stay. Opening the panel marks terminal
 * entries seen, which zeroes the toolbar badge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserDownloadsSheet(
    tabPool: BrowserTabPool,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val downloads by tabPool.downloads.collectAsState()

    // Terminal entries stop counting toward the badge once the user has
    // actually looked at the panel.
    LaunchedEffect(Unit) { tabPool.markDownloadsSeen() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.browser_downloads_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (downloads.any { it.state != BrowserTabPool.DownloadState.DOWNLOADING }) {
                    MinisTextButton(onClick = { tabPool.clearFinishedDownloads() }) {
                        Text(stringResource(R.string.browser_downloads_clear))
                    }
                }
            }
            if (downloads.isEmpty()) {
                Text(
                    stringResource(R.string.browser_downloads_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn {
                    items(downloads, key = { it.id }) { entry ->
                        DownloadRow(
                            entry = entry,
                            onCancel = { tabPool.cancelDownload(entry.id) },
                            onOpen = { openDownloadedFile(context, entry) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    entry: BrowserTabPool.DownloadEntry,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        val (icon, tint) = when (entry.state) {
            BrowserTabPool.DownloadState.DOWNLOADING ->
                Icons.Default.Download to MaterialTheme.colorScheme.primary
            BrowserTabPool.DownloadState.COMPLETED ->
                Icons.Default.CheckCircle to androidx.compose.ui.graphics.Color(0xFF34C759)
            BrowserTabPool.DownloadState.FAILED ->
                Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // Middle truncation keeps the extension visible (iOS v3).
                BrowserTabPool.middleTruncated(entry.filename, head = 24, tail = 12),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            when (entry.state) {
                BrowserTabPool.DownloadState.DOWNLOADING -> {
                    val context = LocalContext.current
                    val doneText = android.text.format.Formatter
                        .formatShortFileSize(context, entry.bytesDone)
                    val statusText = if (entry.totalBytes > 0) {
                        val totalText = android.text.format.Formatter
                            .formatShortFileSize(context, entry.totalBytes)
                        val pct = (entry.bytesDone * 100 / entry.totalBytes).toInt()
                        "$pct% · $doneText / $totalText"
                    } else {
                        doneText
                    }
                    Text(
                        statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (entry.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = {
                                (entry.bytesDone.toFloat() / entry.totalBytes).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                BrowserTabPool.DownloadState.COMPLETED -> {
                    val context = LocalContext.current
                    Text(
                        android.text.format.Formatter
                            .formatShortFileSize(context, entry.bytesDone),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BrowserTabPool.DownloadState.FAILED -> {
                    Text(
                        entry.failureReason
                            ?: stringResource(R.string.browser_downloads_failed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        when (entry.state) {
            BrowserTabPool.DownloadState.DOWNLOADING -> {
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.Default.Cancel,
                        contentDescription = stringResource(R.string.browser_downloads_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            BrowserTabPool.DownloadState.COMPLETED -> {
                IconButton(onClick = onOpen) {
                    Icon(
                        Icons.Default.OpenInNew,
                        contentDescription = stringResource(R.string.browser_downloads_open),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            BrowserTabPool.DownloadState.FAILED -> Unit
        }
    }
}

/** ACTION_VIEW via FileProvider — same pattern as FilePreviewScreen's
 *  openExternally; the `minis-sessions/` root is a declared provider path. */
private fun openDownloadedFile(context: Context, entry: BrowserTabPool.DownloadEntry) {
    val file = entry.destination ?: return
    if (!file.exists()) return
    try {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
        val ext = file.extension.lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, null))
    } catch (e: Exception) {
        android.util.Log.w("BrowserDownloads", "open failed: ${e.message}")
    }
}
