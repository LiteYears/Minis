package com.openminis.app.ui.preview

import android.webkit.JavascriptInterface
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.SandboxServerInstance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Bidirectional bridge between the Interactive Micro-App Canvas ([WebViewHolder])
 * and the active AI Agent chat session (`ChatScreen` / `ChatViewModel`).
 *
 * Capabilities:
 * 1. Exposes `window.minis` to trusted local sandbox pages (`http://127.0.0.1:<port>`
 *    and verified sandbox `file://` pages):
 *    - `window.minis.sendPrompt(prompt, keepCanvasOpen)` — sends a message directly to the AI agent.
 *    - `window.minis.insertPrompt(text)` — inserts text into the chat composer without sending.
 *    - `window.minis.fixError(errorSummary)` — triggers an automated AI debugging turn.
 *    - `window.minis.getInfo()` — returns JSON metadata about the host Canvas environment.
 * 2. Provides a 1-tap "Fix with AI" action from [SandboxCanvasSubbar] whenever JS runtime
 *    errors or server traceback logs are detected.
 */
object CanvasAgentBridge {
    private const val TAG = "CanvasAgentBridge"

    sealed class Action {
        data class SendPrompt(
            val prompt: String,
            val minimizeCanvas: Boolean = true,
        ) : Action()

        data class InsertPrompt(
            val text: String,
            val minimizeCanvas: Boolean = true,
        ) : Action()
    }

    private val _pendingAction = MutableStateFlow<Action?>(null)
    val pendingAction: StateFlow<Action?> = _pendingAction.asStateFlow()

    fun dispatchSendPrompt(prompt: String, minimizeCanvas: Boolean = true) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return
        AppLogger.info(TAG, "Dispatching Canvas -> Agent prompt (${trimmed.length} chars, minimize=$minimizeCanvas)")
        _pendingAction.value = Action.SendPrompt(trimmed, minimizeCanvas)
    }

    fun dispatchInsertPrompt(text: String, minimizeCanvas: Boolean = true) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        AppLogger.info(TAG, "Dispatching Canvas -> Composer insert (${trimmed.length} chars)")
        _pendingAction.value = Action.InsertPrompt(trimmed, minimizeCanvas)
    }

    fun consumeAction() {
        _pendingAction.value = null
    }

    /**
     * Builds a comprehensive, structured diagnostic prompt for the AI agent to fix
     * runtime JS errors and/or backend server errors on a live Canvas server.
     */
    fun buildAutoFixPrompt(
        url: String,
        port: Int,
        server: SandboxServerInstance?,
        consoleLogs: List<WebConsoleEntry>,
        serverLogs: String,
        customErrorHint: String = "",
    ): String = buildString {
        val title = server?.title?.ifBlank { "Sandbox App (:$port)" } ?: "Sandbox App (:$port)"
        appendLine("Please fix the error in the running Canvas app **$title** (`$url` on port `$port`).")
        if (server != null && server.command.isNotBlank()) {
            appendLine("- **Start command**: `${server.command}`")
            appendLine("- **Working directory**: `${server.workdir}`")
            appendLine("- **Server status**: `${server.status.name}`")
        }
        if (customErrorHint.isNotBlank()) {
            appendLine("\n### Reported Error")
            appendLine("```")
            appendLine(customErrorHint.trim().take(1500))
            appendLine("```")
        }
        val jsErrors = consoleLogs.filter { it.level == "ERROR" || it.level == "WARNING" }.takeLast(15)
        if (jsErrors.isNotEmpty()) {
            appendLine("\n### Browser JS Console Errors")
            appendLine("```")
            jsErrors.forEach { entry ->
                appendLine("[${entry.level}] ${entry.message} (${entry.sourceId}:${entry.lineNumber})")
            }
            appendLine("```")
        }
        val trimmedServerLogs = serverLogs.trim()
        if (trimmedServerLogs.isNotEmpty()) {
            val tailLines = trimmedServerLogs.lines().takeLast(35).joinToString("\n")
            appendLine("\n### Server Logs (`/tmp/minis-server-$port.log`)")
            appendLine("```")
            appendLine(tailLines.take(2500))
            appendLine("```")
        }
        appendLine("\nInspect the relevant source files in `${server?.workdir ?: "/var/minis/workspace"}`, apply the fix, and restart or verify the server on port `$port`.")
    }

    /**
     * Client-side JS injected on page load for trusted local sandbox pages.
     * Hooks uncaught runtime errors and unhandled Promise rejections so they
     * appear in [WebViewHolder.consoleLogs] and can be auto-healed in 1 tap.
     */
    const val INJECTED_CANVAS_RUNTIME_JS = """
        (function() {
            if (window.__minisCanvasHooked) return;
            window.__minisCanvasHooked = true;
            window.addEventListener('error', function(e) {
                var msg = e && e.message ? e.message : String(e);
                var stack = (e && e.error && e.error.stack) ? ('\n' + e.error.stack) : '';
                console.error('[UncaughtError] ' + msg + stack);
            });
            window.addEventListener('unhandledrejection', function(e) {
                var reason = e && e.reason ? (e.reason.stack || e.reason.message || String(e.reason)) : 'Unknown promise rejection';
                console.error('[UnhandledRejection] ' + reason);
            });
            if (window.minis) {
                try {
                    window.dispatchEvent(new CustomEvent('minis:ready', { detail: { version: 1 } }));
                } catch (_) {}
            }
        })();
    """
}

/**
 * `@JavascriptInterface` bound to `window.minis` inside [WebViewHolder].
 * Strictly gated to trusted local sandbox URLs (`127.0.0.1` / `localhost`) or
 * verified sandbox `file://` URLs.
 */
class MinisCanvasJsInterface(
    private val isTrustedOrigin: () -> Boolean,
    private val currentUrlProvider: () -> String,
    private val onAutoFixRequested: (String) -> Unit,
) {
    @JavascriptInterface
    fun sendPrompt(prompt: String) {
        if (!isTrustedOrigin()) return
        CanvasAgentBridge.dispatchSendPrompt(prompt, minimizeCanvas = true)
    }

    @JavascriptInterface
    fun sendPromptKeepOpen(prompt: String) {
        if (!isTrustedOrigin()) return
        CanvasAgentBridge.dispatchSendPrompt(prompt, minimizeCanvas = false)
    }

    @JavascriptInterface
    fun insertPrompt(text: String) {
        if (!isTrustedOrigin()) return
        CanvasAgentBridge.dispatchInsertPrompt(text, minimizeCanvas = true)
    }

    @JavascriptInterface
    fun fixError(errorSummary: String) {
        if (!isTrustedOrigin()) return
        onAutoFixRequested(errorSummary)
    }

    @JavascriptInterface
    fun getInfo(): String {
        if (!isTrustedOrigin()) return "{}"
        val url = currentUrlProvider()
        val port = com.openminis.app.sandbox.SandboxServerManager.extractPort(url) ?: 0
        return JSONObject().apply {
            put("platform", "android")
            put("bridgeVersion", 1)
            put("url", url)
            put("port", port)
        }.toString()
    }
}
