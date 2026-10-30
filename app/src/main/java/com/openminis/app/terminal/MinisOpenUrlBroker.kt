package com.openminis.app.terminal

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide broker for URLs captured from shell stdout via the OSC
 * `MinisOpenURL` marker (emitted by `/usr/local/bin/minis-open`). The active
 * chat screen collects [pendingUrl] and routes the captured URL into its
 * existing link-tap handler, which in turn dispatches:
 *
 *   * `http(s)://` / `about:`  → `UrlPreviewSheet` via `LocalInAppBrowserLauncher`
 *   * `minis://<deep-link>`    → `DeepLinkHandler`
 *   * `minis://<host>/<path>`  → in-app file preview by extension
 *
 * Whichever observer handles the URL calls [consume] so sibling observers
 * skip it. This mirrors iOS `MinisOpenURLBroker`.
 *
 * The broker also tracks which top-most host ([toolSheetVisible] /
 * [terminalVisible]) is currently presented so the chat screen can defer web
 * previews when a nested sheet or the fullscreen terminal should take
 * priority. On Android today only the chat screen consumes URLs, but the
 * flags are kept for parity so future callers can opt into the same
 * coordination pattern without reshuffling the API.
 */
object MinisOpenUrlBroker {
    private val _pendingUrl = MutableStateFlow<Uri?>(null)
    val pendingUrl: StateFlow<Uri?> = _pendingUrl.asStateFlow()

    private val _toolSheetVisible = MutableStateFlow(false)
    val toolSheetVisible: StateFlow<Boolean> = _toolSheetVisible.asStateFlow()

    private val _terminalVisible = MutableStateFlow(false)
    val terminalVisible: StateFlow<Boolean> = _terminalVisible.asStateFlow()

    fun offer(uri: Uri) { _pendingUrl.value = uri }

    fun offer(uriString: String) {
        val parsed = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        if (!isSupportedScheme(parsed.scheme)) return
        _pendingUrl.value = parsed
    }

    fun consume() { _pendingUrl.value = null }

    fun setToolSheetVisible(visible: Boolean) { _toolSheetVisible.value = visible }
    fun setTerminalVisible(visible: Boolean) { _terminalVisible.value = visible }

    /**
     * Schemes `minis-open` may emit and that the host knows how to route.
     *   * `http` / `https` / `about` → in-app WebView preview
     *   * `minis`                    → deep link or in-app file preview
     */
    fun isSupportedScheme(scheme: String?): Boolean {
        val s = scheme?.lowercase() ?: return false
        return s == "http" || s == "https" || s == "about" || s == "minis"
    }

    /**
     * True for schemes that render as a WebView preview. Callers use this to
     * suppress presentation while a top-most host (tool sheet, fullscreen
     * terminal) is visible and wants to handle it itself.
     */
    fun isWebScheme(scheme: String?): Boolean {
        val s = scheme?.lowercase() ?: return false
        return s == "http" || s == "https" || s == "about"
    }
}

// ─── Merged from MinisUrlMarker.kt ──────────────────────────────────────────────────

/**
 * Parses the OSC 1337 `MinisOpenURL` escape sequence emitted by the rootfs
 * shim at `/usr/local/bin/minis-open`. The shim stands in for `xdg-open`,
 * `sensible-browser`, `www-browser`, and `$BROWSER`; whenever an in-sandbox
 * command tries to open a URL, it prints:
 *
 *     ESC ] 1337 ; MinisOpenURL = <url> BEL
 *
 * This parser strips the marker from the displayed shell output and returns
 * the captured URLs so the host can present the in-app preview (WebView for
 * http(s)/about, file preview for minis://).
 *
 * Mirrors iOS `MinisURLMarker` in `AIChatViewModel.swift`.
 */
object MinisUrlMarker {
    /**
     * ESC ] 1337 ; MinisOpenURL = <url> (BEL | ESC \)
     *
     * The URL runs until the first BEL (0x07) or ESC (0x1B) — the ST
     * terminator `ESC \` is accepted as an alternative to BEL for robustness.
     */
    private val PATTERN = Regex("\u001B]1337;MinisOpenURL=([^\u0007\u001B]*)(?:\u0007|\u001B\\\\)")

    /**
     * Extract every captured URL from [text] and return the text with every
     * marker sequence removed. When [text] contains no marker, returns
     * `(text, emptyList())` untouched (no allocation overhead).
     */
    fun extract(text: String): Pair<String, List<String>> {
        if (!text.contains("MinisOpenURL=")) return text to emptyList()
        val matches = PATTERN.findAll(text).toList()
        if (matches.isEmpty()) return text to emptyList()

        val urls = matches.map { it.groupValues[1] }.filter { it.isNotEmpty() }
        val cleaned = PATTERN.replace(text, "")
        return cleaned to urls
    }

    /** Convenience: drop the markers, discard captured URLs. */
    fun stripMarkers(text: String): String {
        if (!text.contains("MinisOpenURL=")) return text
        return PATTERN.replace(text, "")
    }

    /**
     * Convenience for streaming single-line callers: return the first captured
     * URL or null. Use [extract] when you may encounter multiple markers on a
     * single line (rare but possible when a command chains several opens).
     */
    fun extractUrl(text: String): String? {
        if (!text.contains("MinisOpenURL=")) return null
        return PATTERN.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }
    }
}
