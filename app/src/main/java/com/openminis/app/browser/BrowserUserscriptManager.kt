package com.openminis.app.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import java.io.File
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Built-in Tampermonkey / Greasemonkey Userscript Engine for the OpenMinis Browser.
 *
 * Allows both the AI agent (via `browser_use` actions `install_userscript`,
 * `list_userscripts`, `toggle_userscript`, `remove_userscript`) and the user
 * (via the Tampermonkey Userscripts sheet in BrowserSheet) to install persistent
 * JavaScript userscripts that automatically run on matching websites.
 *
 * Features:
 * - Parses standard `// ==UserScript== ... // ==/UserScript==` metadata headers
 *   (`@name`, `@description`, `@version`, `@match`, `@include`, `@exclude`,
 *   `@run-at`, `@grant`), or accepts structured parameters (`script_name`,
 *   `match`, `run_at`, `script`).
 * - Supports `@run-at`: `document-start` (injected in `onPageStarted`),
 *   `document-end` (injected in `onPageFinished`), and `document-idle`
 *   (injected after `requestIdleCallback` / short settle delay).
 * - Provides a comprehensive Tampermonkey `GM_*` API shim in every script's scope:
 *   `unsafeWindow`, `GM_info`, `GM_addStyle`, `GM_addElement`, `GM_getValue`,
 *   `GM_setValue`, `GM_deleteValue`, `GM_listValues`, `GM_xmlhttpRequest` /
 *   `GM.xmlHttpRequest`, `GM_setClipboard`, `GM_registerMenuCommand`,
 *   `GM_notification`, `GM_log`, and `GM_waitForElement(selector, callback, timeoutMs)`.
 */
object BrowserUserscriptManager {

    private const val TAG = "BrowserUserscripts"
    private const val SCRIPTS_FILENAME = "browser_userscripts.json"
    private const val VALUES_FILENAME = "browser_userscript_values.json"

    enum class RunAt(val value: String) {
        DOCUMENT_START("document-start"),
        DOCUMENT_END("document-end"),
        DOCUMENT_IDLE("document-idle");

        val wireValue: String get() = value

        companion object {
            fun fromString(raw: String?): RunAt {
                return when (raw?.trim()?.lowercase()?.replace('_', '-')) {
                    "document-start", "start", "early" -> DOCUMENT_START
                    "document-end", "end", "domcontentloaded" -> DOCUMENT_END
                    "document-idle", "idle", "load" -> DOCUMENT_IDLE
                    else -> DOCUMENT_END
                }
            }
        }
    }

    data class Userscript(
        val id: String,
        val name: String,
        val description: String,
        val version: String,
        val matches: List<String>,
        val excludes: List<String>,
        val runAt: RunAt,
        val code: String,
        val enabled: Boolean = true,
        val updatedAtMs: Long = System.currentTimeMillis(),
    ) {
        val matchPatterns: List<String> get() = matches
    }

    data class PresetUserscript(
        val id: String,
        val name: String,
        val description: String,
        val matches: List<String>,
        val runAt: RunAt,
        val code: String,
    )

    val BUILTIN_PRESETS: List<PresetUserscript> = listOf(
        PresetUserscript(
            id = "anti-annoyance-shield",
            name = "Anti-Annoyance & Cookie Banner Shield",
            description = "Automatically hides intrusive cookie consent banners, newsletter popups, and unlocks scroll-locked pages.",
            matches = listOf("*://*/*"),
            runAt = RunAt.DOCUMENT_IDLE,
            code = """
                // ==UserScript==
                // @name         Anti-Annoyance & Cookie Banner Shield
                // @description  Hides common cookie banners, overlay popups, and restores page scrolling
                // @match        *://*/*
                // @run-at       document-idle
                // ==/UserScript==
                GM_addStyle([
                    '#onetrust-consent-sdk, .onetrust-pc-dark-filter, #CybotCookiebotDialog, #usercentrics-root,',
                    '.qc-cmp2-container, .cc-window, .cc-banner, #cookie-banner, .cookie-banner, .cookie-consent,',
                    '[id*="cookie-notice"], [class*="cookie-banner"], [aria-label*="cookie" i][role="dialog"],',
                    '[aria-label*="consent" i][role="dialog"] { display: none !important; visibility: hidden !important; pointer-events: none !important; }',
                    'html.sp-message-open, body.modal-open-noscroll { overflow: auto !important; position: static !important; }'
                ].join('\n'));
                if (document.body && getComputedStyle(document.body).overflow === 'hidden') {
                    var dialogs = document.querySelectorAll('[role="dialog"], .modal, [class*="modal"]');
                    var hasVisibleDialog = false;
                    for (var i = 0; i < dialogs.length; i++) {
                        var r = dialogs[i].getBoundingClientRect();
                        if (r.width > 120 && r.height > 80) { hasVisibleDialog = true; break; }
                    }
                    if (!hasVisibleDialog) {
                        document.body.style.setProperty('overflow', 'auto', 'important');
                        document.documentElement.style.setProperty('overflow', 'auto', 'important');
                    }
                }
            """.trimIndent(),
        ),
        PresetUserscript(
            id = "unlock-copy-selection",
            name = "Unlock Text Selection & Copy",
            description = "Forces text selection, copy/paste, and context menus to work on websites that disable them.",
            matches = listOf("*://*/*"),
            runAt = RunAt.DOCUMENT_END,
            code = """
                // ==UserScript==
                // @name         Unlock Text Selection & Copy
                // @description  Re-enables user-select, copy, cut, and contextmenu on restricted sites
                // @match        *://*/*
                // @run-at       document-end
                // ==/UserScript==
                GM_addStyle('* { -webkit-user-select: text !important; user-select: text !important; }');
                ['copy', 'cut', 'paste', 'selectstart', 'contextmenu', 'dragstart'].forEach(function(evt) {
                    window.addEventListener(evt, function(e) { e.stopPropagation(); }, true);
                });
            """.trimIndent(),
        ),
        PresetUserscript(
            id = "auto-expand-articles",
            name = "Auto-Expand Read More & Full Articles",
            description = "Automatically expands truncated article bodies, 'Read more' buttons, and collapsed <details> blocks.",
            matches = listOf("*://*/*"),
            runAt = RunAt.DOCUMENT_IDLE,
            code = """
                // ==UserScript==
                // @name         Auto-Expand Read More & Full Articles
                // @description  Expands truncated articles and collapsed sections for easier reading
                // @match        *://*/*
                // @run-at       document-idle
                // ==/UserScript==
                document.querySelectorAll('details:not([open])').forEach(function(d) { d.open = true; });
                var expandSelectors = [
                    'button[class*="read-more" i]', 'a[class*="read-more" i]',
                    'button[class*="show-more" i]', '[aria-label*="read more" i]',
                    '[data-testid*="read-more" i]'
                ];
                expandSelectors.forEach(function(sel) {
                    document.querySelectorAll(sel).forEach(function(btn) {
                        try { btn.click(); } catch (e) {}
                    });
                });
            """.trimIndent(),
        ),
        PresetUserscript(
            id = "spa-api-interceptor",
            name = "SPA API & Fetch Inspector (__minis_network_log__)",
            description = "Records JSON responses from fetch() and XMLHttpRequest into window.__minis_network_log__ so the agent can inspect raw API data.",
            matches = listOf("*://*/*"),
            runAt = RunAt.DOCUMENT_START,
            code = """
                // ==UserScript==
                // @name         SPA API & Fetch Inspector (__minis_network_log__)
                // @description  Captures recent fetch/XHR JSON responses into window.__minis_network_log__
                // @match        *://*/*
                // @run-at       document-start
                // ==/UserScript==
                if (!window.__minis_network_log__) {
                    window.__minis_network_log__ = [];
                    var origFetch = window.fetch;
                    if (origFetch) {
                        window.fetch = async function() {
                            var args = arguments;
                            var resp = await origFetch.apply(this, args);
                            try {
                                var clone = resp.clone();
                                var ct = (clone.headers && clone.headers.get('content-type')) || '';
                                if (ct.indexOf('json') !== -1 || ct.indexOf('text') !== -1) {
                                    clone.text().then(function(body) {
                                        window.__minis_network_log__.push({
                                            type: 'fetch',
                                            url: String(args[0] && args[0].url ? args[0].url : args[0]),
                                            status: clone.status,
                                            bodyPreview: (body || '').substring(0, 4000),
                                            time: Date.now()
                                        });
                                        if (window.__minis_network_log__.length > 40) window.__minis_network_log__.shift();
                                    }).catch(function(){});
                                }
                            } catch (e) {}
                            return resp;
                        };
                    }
                }
            """.trimIndent(),
        ),
    )

    val builtinPresets: List<PresetUserscript> get() = BUILTIN_PRESETS

    private val lock = Any()
    private var initialized = false
    private val _scripts = MutableStateFlow<List<Userscript>>(emptyList())
    val scripts: StateFlow<List<Userscript>> = _scripts.asStateFlow()
    val scriptsFlow: StateFlow<List<Userscript>> get() = scripts

    // scriptId -> (key -> rawJsonString)
    private val scriptStorage = HashMap<String, MutableMap<String, String>>()

    fun ensureLoaded(context: Context): List<Userscript> {
        synchronized(lock) {
            if (!initialized) {
                val appCtx = context.applicationContext
                _scripts.value = readScriptsFromDisk(appCtx)
                readValuesFromDisk(appCtx)
                initialized = true
            }
            return _scripts.value
        }
    }

    fun normalizeId(rawName: String): String {
        return rawName.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9._\\-]+"), "-")
            .trim('-')
            .ifEmpty { "userscript-${System.currentTimeMillis()}" }
    }

    /**
     * Parse Tampermonkey `// ==UserScript==` header block if present in [rawCode].
     */
    data class ParsedHeader(
        val name: String?,
        val description: String?,
        val version: String?,
        val matches: List<String>,
        val excludes: List<String>,
        val runAt: RunAt?,
    )

    fun parseHeader(rawCode: String): ParsedHeader {
        val lines = rawCode.lines()
        val startIdx = lines.indexOfFirst { it.trim().startsWith("// ==UserScript==", ignoreCase = true) }
        val endIdx = lines.indexOfFirst { it.trim().startsWith("// ==/UserScript==", ignoreCase = true) }
        if (startIdx < 0 || endIdx <= startIdx) {
            return ParsedHeader(null, null, null, emptyList(), emptyList(), null)
        }
        var name: String? = null
        var description: String? = null
        var version: String? = null
        var runAt: RunAt? = null
        val matches = mutableListOf<String>()
        val excludes = mutableListOf<String>()

        val directiveRegex = Regex("""^//\s*@([a-zA-Z0-9_-]+)\s+(.+)$""")
        for (i in (startIdx + 1) until endIdx) {
            val m = directiveRegex.find(lines[i].trim()) ?: continue
            val key = m.groupValues[1].lowercase()
            val value = m.groupValues[2].trim()
            if (value.isEmpty()) continue
            when (key) {
                "name" -> if (name == null) name = value
                "description" -> if (description == null) description = value
                "version" -> if (version == null) version = value
                "match", "include" -> matches.add(value)
                "exclude", "exclude-match" -> excludes.add(value)
                "run-at" -> runAt = RunAt.fromString(value)
            }
        }
        return ParsedHeader(name, description, version, matches, excludes, runAt)
    }

    /**
     * Install or update a Tampermonkey userscript.
     * If the active browser tab's URL matches the script, also injects it immediately!
     */
    suspend fun installUserscript(
        context: Context,
        manager: BrowserUseManager?,
        rawName: String?,
        rawCode: String?,
        rawMatches: List<String>?,
        rawRunAt: String?,
        enabled: Boolean = true,
    ): BrowserActionResult {
        ensureLoaded(context)
        val code = rawCode?.trim().orEmpty()
        if (code.isEmpty()) {
            return BrowserActionResult.error(
                "install_userscript requires 'script' (JavaScript code or full // ==UserScript== block).",
            )
        }

        val header = parseHeader(code)
        val currentUrl = manager?.currentURL?.value?.takeIf { it.isNotBlank() && it != "about:blank" }
        val fallbackHost = currentUrl?.let { runCatching { URI(it).host }.getOrNull() }

        val effectiveName = rawName?.trim()?.takeIf { it.isNotEmpty() }
            ?: header.name?.takeIf { it.isNotEmpty() }
            ?: fallbackHost?.let { "userscript-$it" }
            ?: "custom-userscript"

        val id = normalizeId(effectiveName)
        val effectiveMatches = when {
            !rawMatches.isNullOrEmpty() -> rawMatches.map { it.trim() }.filter { it.isNotEmpty() }
            header.matches.isNotEmpty() -> header.matches
            !fallbackHost.isNullOrBlank() -> listOf("*://$fallbackHost/*", "*://*.$fallbackHost/*")
            else -> listOf("*://*/*")
        }
        val effectiveExcludes = header.excludes
        val effectiveRunAt = if (!rawRunAt.isNullOrBlank()) {
            RunAt.fromString(rawRunAt)
        } else {
            header.runAt ?: RunAt.DOCUMENT_END
        }
        val effectiveDesc = header.description ?: "Tampermonkey userscript for ${effectiveMatches.joinToString(", ")}"
        val effectiveVer = header.version ?: "1.0"

        val scriptObj = Userscript(
            id = id,
            name = effectiveName,
            description = effectiveDesc,
            version = effectiveVer,
            matches = effectiveMatches,
            excludes = effectiveExcludes,
            runAt = effectiveRunAt,
            code = code,
            enabled = enabled,
            updatedAtMs = System.currentTimeMillis(),
        )

        synchronized(lock) {
            val updated = _scripts.value.filterNot { it.id == id || it.name.equals(effectiveName, ignoreCase = true) } + scriptObj
            _scripts.value = updated.sortedBy { it.name.lowercase() }
            writeScriptsToDisk(context.applicationContext, _scripts.value)
        }

        // If a page is currently loaded in the target tab and matches, run it right now!
        var immediateRunReport = ""
        if (enabled && manager != null && !currentUrl.isNullOrBlank() && matchesUrl(scriptObj, currentUrl)) {
            val runOut = manager.runSingleUserscriptNow(scriptObj)
            immediateRunReport = "\n  Immediate execution on current page ($currentUrl): $runOut"
        }

        return BrowserActionResult(
            text = buildString {
                appendLine("Installed Tampermonkey userscript '${scriptObj.name}' (id: ${scriptObj.id})")
                appendLine("  Match patterns: ${scriptObj.matches.joinToString(", ")}")
                appendLine("  Run-at: ${scriptObj.runAt.value}")
                append("  Status: ${if (scriptObj.enabled) "enabled (auto-injects on every matching navigation)" else "disabled"}")
                append(immediateRunReport)
            },
        )
    }

    fun installPreset(context: Context, preset: PresetUserscript): Userscript {
        ensureLoaded(context)
        val scriptObj = Userscript(
            id = preset.id,
            name = preset.name,
            description = preset.description,
            version = "1.0",
            matches = preset.matches,
            excludes = emptyList(),
            runAt = preset.runAt,
            code = preset.code,
            enabled = true,
            updatedAtMs = System.currentTimeMillis(),
        )
        synchronized(lock) {
            val updated = _scripts.value.filterNot { it.id == preset.id } + scriptObj
            _scripts.value = updated.sortedBy { it.name.lowercase() }
            writeScriptsToDisk(context.applicationContext, _scripts.value)
        }
        return scriptObj
    }

    suspend fun toggleUserscript(
        context: Context,
        manager: BrowserUseManager?,
        rawName: String?,
        explicitEnabled: Boolean?,
    ): BrowserActionResult {
        ensureLoaded(context)
        val query = rawName?.trim().orEmpty()
        if (query.isEmpty()) {
            return BrowserActionResult.error("toggle_userscript requires 'script_name' (or 'name').")
        }
        val targetId = normalizeId(query)
        var toggled: Userscript? = null
        synchronized(lock) {
            val current = _scripts.value
            val match = current.firstOrNull {
                it.id.equals(query, ignoreCase = true) ||
                    it.id == targetId ||
                    it.name.equals(query, ignoreCase = true)
            } ?: return BrowserActionResult.error(
                "Userscript '$query' not found. Use list_userscripts to see installed scripts.",
            )
            val newState = explicitEnabled ?: !match.enabled
            val updatedScript = match.copy(enabled = newState, updatedAtMs = System.currentTimeMillis())
            toggled = updatedScript
            _scripts.value = current.map { if (it.id == match.id) updatedScript else it }
            writeScriptsToDisk(context.applicationContext, _scripts.value)
        }
        val resultScript = toggled!!
        val currentUrl = manager?.currentURL?.value.orEmpty()
        if (resultScript.enabled && manager != null && currentUrl.isNotBlank() && matchesUrl(resultScript, currentUrl)) {
            manager.runSingleUserscriptNow(resultScript)
        } else if (!resultScript.enabled && manager != null) {
            manager.removeUserscriptStyles(resultScript.id)
        }
        return BrowserActionResult(
            text = "Userscript '${resultScript.name}' (${resultScript.id}) is now ${if (resultScript.enabled) "ENABLED" else "DISABLED"}.",
        )
    }

    suspend fun removeUserscript(
        context: Context,
        manager: BrowserUseManager?,
        rawName: String?,
    ): BrowserActionResult {
        ensureLoaded(context)
        val query = rawName?.trim().orEmpty()
        if (query.isEmpty()) {
            return BrowserActionResult.error("remove_userscript requires 'script_name' (or 'name').")
        }
        val targetId = normalizeId(query)
        var removed: Userscript? = null
        synchronized(lock) {
            val current = _scripts.value
            val match = current.firstOrNull {
                it.id.equals(query, ignoreCase = true) ||
                    it.id == targetId ||
                    it.name.equals(query, ignoreCase = true)
            } ?: return BrowserActionResult.error(
                "Userscript '$query' not found. Use list_userscripts to see installed scripts.",
            )
            removed = match
            _scripts.value = current.filterNot { it.id == match.id }
            scriptStorage.remove(match.id)
            writeScriptsToDisk(context.applicationContext, _scripts.value)
            writeValuesToDisk(context.applicationContext)
        }
        val deleted = removed!!
        manager?.removeUserscriptStyles(deleted.id)
        return BrowserActionResult(
            text = "Removed Tampermonkey userscript '${deleted.name}' (id: ${deleted.id}).",
        )
    }

    fun listUserscriptsResult(context: Context, currentUrl: String? = null): BrowserActionResult {
        val list = ensureLoaded(context)
        if (list.isEmpty()) {
            return BrowserActionResult(
                text = "No Tampermonkey userscripts installed. Use action='install_userscript' with 'script_name', 'match' (e.g. '*://*.example.com/*'), and 'script' to install one.",
            )
        }
        val text = buildString {
            appendLine("Installed Tampermonkey userscripts (${list.size}):")
            for (s in list) {
                val status = if (s.enabled) "ENABLED" else "DISABLED"
                val activeHere = if (!currentUrl.isNullOrBlank() && s.enabled && matchesUrl(s, currentUrl)) " [ACTIVE ON CURRENT PAGE]" else ""
                appendLine("  - ${s.name} (id: ${s.id}) [$status, ${s.runAt.value}]$activeHere")
                appendLine("    match: ${s.matches.joinToString(", ")}")
                if (s.description.isNotBlank()) {
                    appendLine("    desc: ${s.description.take(120)}")
                }
            }
        }.trimEnd()
        return BrowserActionResult(text = text)
    }

    /**
     * Return all enabled userscripts that match [url] and optionally filter by [runAt].
     */
    fun matchingScriptsForUrl(context: Context, url: String?, runAt: RunAt? = null): List<Userscript> {
        if (url.isNullOrBlank() || url == "about:blank") return emptyList()
        val all = ensureLoaded(context)
        return all.filter { script ->
            script.enabled &&
                (runAt == null || script.runAt == runAt) &&
                matchesUrl(script, url)
        }
    }

    fun matchesUrl(script: Userscript, url: String): Boolean {
        if (url.isBlank() || url == "about:blank") return false
        if (script.excludes.any { patternMatchesUrl(it, url) }) return false
        return script.matches.any { patternMatchesUrl(it, url) }
    }

    // Evaluates standard Tampermonkey @match / @include patterns against url.
    internal fun patternMatchesUrl(pattern: String, url: String): Boolean {
        val p = pattern.trim()
        if (p.isEmpty()) return false
        if (p == "*" || p.equals("<all_urls>", ignoreCase = true) || p == "*://*/*" || p == "http*://*/*") {
            return url.startsWith("http://") || url.startsWith("https://") || url.startsWith("minis://")
        }
        if (p.length >= 2 && p.startsWith("/") && p.endsWith("/")) {
            return runCatching { Regex(p.substring(1, p.length - 1), RegexOption.IGNORE_CASE).containsMatchIn(url) }
                .getOrDefault(false)
        }
        // If user passed a bare domain like "example.com" or "*.example.com", match its host
        if (!p.contains("://") && !p.startsWith("*")) {
            val host = runCatching { URI(url).host?.lowercase() }.getOrNull().orEmpty()
            val cleanDom = p.lowercase().removePrefix("*.").trimEnd('/')
            if (host.isNotEmpty() && (host == cleanDom || host.endsWith(".$cleanDom"))) return true
        }
        // Convert Tampermonkey glob pattern to regex
        val normalizedGlob = p.replace("://*.", "://(?:[^/]+\\.)?")
        val sb = StringBuilder("^")
        var i = 0
        while (i < normalizedGlob.length) {
            if (normalizedGlob.startsWith("(?:[^/]+\\.)?", i)) {
                sb.append("(?:[^/]+\\.)?")
                i += "(?:[^/]+\\.)?".length
                continue
            }
            when (val ch = normalizedGlob[i]) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.', '+', '^', '$', '(', ')', '[', ']', '{', '}', '|', '\\' -> {
                    sb.append('\\').append(ch)
                }
                else -> sb.append(ch)
            }
            i++
        }
        sb.append("$")
        return runCatching { Regex(sb.toString(), RegexOption.IGNORE_CASE).matches(url) }.getOrDefault(false)
    }

    // ── GM_* Value Persistence ──────────────────────────────────────────────

    fun setScriptValue(context: Context, scriptId: String, key: String, rawJsonValue: String) {
        synchronized(lock) {
            ensureLoaded(context)
            val map = scriptStorage.getOrPut(scriptId) { HashMap() }
            map[key] = rawJsonValue
            writeValuesToDisk(context.applicationContext)
        }
    }

    fun deleteScriptValue(context: Context, scriptId: String, key: String) {
        synchronized(lock) {
            ensureLoaded(context)
            scriptStorage[scriptId]?.remove(key)
            writeValuesToDisk(context.applicationContext)
        }
    }

    fun copyToClipboard(context: Context, text: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("userscript", text))
        } catch (e: Exception) {
            Log.w(TAG, "GM_setClipboard failed: ${e.message}")
        }
    }

    private fun getScriptStorageJson(scriptId: String): String {
        synchronized(lock) {
            val map = scriptStorage[scriptId] ?: return "{}"
            val obj = JSONObject()
            for ((k, v) in map) {
                obj.put(k, v)
            }
            return obj.toString()
        }
    }

    /**
     * Wraps a [Userscript] in a self-contained Tampermonkey `GM_*` runtime IIFE.
     * When [captureReturn] is true (used by `install_userscript` immediate execution),
     * returns a JSON summary of execution; when false (used on automatic page-load
     * injection), guards against duplicate injection per document lifecycle.
     */
    fun buildInjectionJavaScript(script: Userscript, captureReturn: Boolean = false): String {
        val scriptIdEsc = BrowserUseJS.jsQuote(script.id)
        val scriptNameEsc = BrowserUseJS.jsQuote(script.name)
        val scriptVerEsc = BrowserUseJS.jsQuote(script.version)
        val initialStoreJson = BrowserUseJS.jsQuote(getScriptStorageJson(script.id))
        val guardKey = "${script.id}:${script.runAt.value}"
        val guardKeyEsc = BrowserUseJS.jsQuote(guardKey)

        return """
            (async function() {
                try {
                    window.__minis_userscripts_ran__ = window.__minis_userscripts_ran__ || {};
                    if (!${captureReturn} && window.__minis_userscripts_ran__['$guardKeyEsc'] === location.href) {
                        return JSON.stringify({skipped: true, id: '$scriptIdEsc'});
                    }
                    window.__minis_userscripts_ran__['$guardKeyEsc'] = location.href;

                    var __gm_store_raw__ = JSON.parse('$initialStoreJson');
                    var __gm_store__ = {};
                    Object.keys(__gm_store_raw__).forEach(function(k) {
                        try { __gm_store__[k] = JSON.parse(__gm_store_raw__[k]); }
                        catch (e) { __gm_store__[k] = __gm_store_raw__[k]; }
                    });
                    var __gm_logs__ = [];
                    var unsafeWindow = window;
                    var GM_info = {
                        scriptHandler: 'OpenMinis Tampermonkey',
                        version: '1.0',
                        script: {
                            id: '$scriptIdEsc',
                            name: '$scriptNameEsc',
                            version: '$scriptVerEsc',
                            runAt: '${script.runAt.value}'
                        }
                    };
                    function GM_addStyle(css) {
                        var style = document.createElement('style');
                        style.setAttribute('data-minis-userscript', '$scriptIdEsc');
                        style.textContent = String(css || '');
                        (document.head || document.documentElement || document.body).appendChild(style);
                        return style;
                    }
                    function GM_addElement(parentOrTag, tagOrAttrs, maybeAttrs) {
                        var parent = (typeof parentOrTag === 'string') ? (document.head || document.body || document.documentElement) : parentOrTag;
                        var tag = (typeof parentOrTag === 'string') ? parentOrTag : tagOrAttrs;
                        var attrs = (typeof parentOrTag === 'string') ? (tagOrAttrs || {}) : (maybeAttrs || {});
                        var el = document.createElement(tag);
                        Object.keys(attrs).forEach(function(k) {
                            if (k === 'textContent') el.textContent = attrs[k];
                            else if (k === 'innerHTML') el.innerHTML = attrs[k];
                            else el.setAttribute(k, String(attrs[k]));
                        });
                        if (parent) parent.appendChild(el);
                        return el;
                    }
                    function GM_getValue(key, defaultValue) {
                        return Object.prototype.hasOwnProperty.call(__gm_store__, key) ? __gm_store__[key] : defaultValue;
                    }
                    function GM_setValue(key, value) {
                        __gm_store__[key] = value;
                        try {
                            if (window.__minis__ && window.__minis__.gmSetValue) {
                                window.__minis__.gmSetValue('$scriptIdEsc', String(key), JSON.stringify(value));
                            }
                        } catch (e) {}
                    }
                    function GM_deleteValue(key) {
                        delete __gm_store__[key];
                        try {
                            if (window.__minis__ && window.__minis__.gmDeleteValue) {
                                window.__minis__.gmDeleteValue('$scriptIdEsc', String(key));
                            }
                        } catch (e) {}
                    }
                    function GM_listValues() {
                        return Object.keys(__gm_store__);
                    }
                    function GM_setClipboard(text) {
                        try {
                            if (window.__minis__ && window.__minis__.gmSetClipboard) {
                                window.__minis__.gmSetClipboard(String(text || ''));
                            } else if (navigator.clipboard && navigator.clipboard.writeText) {
                                navigator.clipboard.writeText(String(text || ''));
                            }
                        } catch (e) {}
                    }
                    function GM_log() {
                        var msg = Array.prototype.slice.call(arguments).map(function(a) {
                            return (typeof a === 'object') ? JSON.stringify(a) : String(a);
                        }).join(' ');
                        __gm_logs__.push(msg);
                        console.log('[Userscript:$scriptNameEsc]', msg);
                    }
                    function GM_registerMenuCommand(caption, fn) {
                        window.__minis_userscript_commands__ = window.__minis_userscript_commands__ || {};
                        window.__minis_userscript_commands__[caption] = fn;
                        return caption;
                    }
                    function GM_notification(textOrDetails, title) {
                        var txt = (typeof textOrDetails === 'object' && textOrDetails) ? (textOrDetails.text || '') : String(textOrDetails || '');
                        GM_log('Notification:', title || '', txt);
                    }
                    function GM_waitForElement(selector, callback, timeoutMs) {
                        var existing = document.querySelector(selector);
                        if (existing) {
                            try { callback(existing); } catch (e) {}
                            return Promise.resolve(existing);
                        }
                        return new Promise(function(resolve) {
                            var timer = null;
                            var obs = new MutationObserver(function() {
                                var found = document.querySelector(selector);
                                if (found) {
                                    obs.disconnect();
                                    if (timer) clearTimeout(timer);
                                    try { callback(found); } catch (e) {}
                                    resolve(found);
                                }
                            });
                            obs.observe(document.documentElement || document, {childList: true, subtree: true});
                            timer = setTimeout(function() {
                                obs.disconnect();
                                resolve(null);
                            }, timeoutMs || 10000);
                        });
                    }
                    function GM_xmlhttpRequest(details) {
                        var method = (details && details.method) || 'GET';
                        var url = (details && details.url) || '';
                        var headers = (details && details.headers) || {};
                        var body = details ? details.data : undefined;
                        var p = fetch(url, {
                            method: method,
                            headers: headers,
                            body: (method === 'GET' || method === 'HEAD') ? undefined : body,
                            credentials: (details && details.anonymous) ? 'omit' : 'include'
                        }).then(async function(resp) {
                            var text = await resp.text();
                            var resObj = {
                                status: resp.status,
                                statusText: resp.statusText,
                                finalUrl: resp.url,
                                responseText: text,
                                response: (details && details.responseType === 'json') ? (function(){ try { return JSON.parse(text); } catch(e) { return null; } })() : text
                            };
                            if (details && typeof details.onload === 'function') details.onload(resObj);
                            return resObj;
                        }).catch(function(err) {
                            if (details && typeof details.onerror === 'function') details.onerror(err);
                            throw err;
                        });
                        return p;
                    }
                    var GM = {
                        info: GM_info,
                        addStyle: GM_addStyle,
                        addElement: GM_addElement,
                        getValue: function(k, d) { return Promise.resolve(GM_getValue(k, d)); },
                        setValue: function(k, v) { GM_setValue(k, v); return Promise.resolve(); },
                        deleteValue: function(k) { GM_deleteValue(k); return Promise.resolve(); },
                        listValues: function() { return Promise.resolve(GM_listValues()); },
                        setClipboard: GM_setClipboard,
                        xmlHttpRequest: GM_xmlhttpRequest,
                        registerMenuCommand: GM_registerMenuCommand,
                        notification: GM_notification,
                        waitForElement: GM_waitForElement
                    };

                    var __user_fn__ = async function() {
                        ${script.code}
                    };
                    var __ret__ = await __user_fn__();
                    return JSON.stringify({
                        ok: true,
                        id: '$scriptIdEsc',
                        name: '$scriptNameEsc',
                        result: (__ret__ === undefined) ? null : __ret__,
                        logs: __gm_logs__
                    });
                } catch (e) {
                    console.error('[Userscript:$scriptNameEsc] error:', e);
                    return JSON.stringify({
                        ok: false,
                        id: '$scriptIdEsc',
                        name: '$scriptNameEsc',
                        error: (e && e.message) ? e.message : String(e)
                    });
                }
            })()
        """.trimIndent()
    }

    // ── Disk Persistence ────────────────────────────────────────────────────

    private fun readScriptsFromDisk(context: Context): List<Userscript> {
        val file = File(context.filesDir, SCRIPTS_FILENAME)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            val out = mutableListOf<Userscript>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                val name = obj.optString("name", id)
                val desc = obj.optString("description", "")
                val version = obj.optString("version", "1.0")
                val matchesArr = obj.optJSONArray("matches")
                val matches = if (matchesArr != null) {
                    (0 until matchesArr.length()).mapNotNull { matchesArr.optString(it).takeIf(String::isNotBlank) }
                } else {
                    listOf("*://*/*")
                }
                val excludesArr = obj.optJSONArray("excludes")
                val excludes = if (excludesArr != null) {
                    (0 until excludesArr.length()).mapNotNull { excludesArr.optString(it).takeIf(String::isNotBlank) }
                } else {
                    emptyList()
                }
                val runAt = RunAt.fromString(obj.optString("runAt", "document-end"))
                val code = obj.optString("code", "")
                if (code.isBlank()) continue
                val enabled = obj.optBoolean("enabled", true)
                val updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis())
                out.add(
                    Userscript(
                        id = id,
                        name = name,
                        description = desc,
                        version = version,
                        matches = matches,
                        excludes = excludes,
                        runAt = runAt,
                        code = code,
                        enabled = enabled,
                        updatedAtMs = updatedAtMs,
                    )
                )
            }
            out.sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read userscripts: ${e.message}")
            emptyList()
        }
    }

    private fun writeScriptsToDisk(context: Context, scripts: List<Userscript>) {
        try {
            val arr = JSONArray()
            for (s in scripts) {
                val obj = JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("description", s.description)
                    put("version", s.version)
                    put("matches", JSONArray(s.matches))
                    put("excludes", JSONArray(s.excludes))
                    put("runAt", s.runAt.value)
                    put("code", s.code)
                    put("enabled", s.enabled)
                    put("updatedAtMs", s.updatedAtMs)
                }
                arr.put(obj)
            }
            File(context.filesDir, SCRIPTS_FILENAME).writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write userscripts: ${e.message}")
        }
    }

    private fun readValuesFromDisk(context: Context) {
        val file = File(context.filesDir, VALUES_FILENAME)
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            scriptStorage.clear()
            val scriptIds = root.keys()
            while (scriptIds.hasNext()) {
                val sid = scriptIds.next()
                val sub = root.optJSONObject(sid) ?: continue
                val map = HashMap<String, String>()
                val keys = sub.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = sub.optString(k, "null")
                }
                scriptStorage[sid] = map
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read userscript values: ${e.message}")
        }
    }

    private fun writeValuesToDisk(context: Context) {
        try {
            val root = JSONObject()
            for ((sid, map) in scriptStorage) {
                val sub = JSONObject()
                for ((k, v) in map) sub.put(k, v)
                root.put(sid, sub)
            }
            File(context.filesDir, VALUES_FILENAME).writeText(root.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write userscript values: ${e.message}")
        }
    }

    // ── Tool & WebViewClient Convenience Entry Points ───────────────────────

    suspend fun installFromTool(
        context: Context,
        manager: BrowserUseManager?,
        scriptName: String?,
        code: String?,
        matchPatterns: List<String>?,
        runAt: String?,
        enabled: Boolean?,
    ): BrowserActionResult {
        ensureLoaded(context)
        val presetMatch = if (code.isNullOrBlank() && !scriptName.isNullOrBlank()) {
            val norm = normalizeId(scriptName)
            BUILTIN_PRESETS.firstOrNull {
                it.id.equals(scriptName.trim(), ignoreCase = true) ||
                    it.id == norm ||
                    it.name.equals(scriptName.trim(), ignoreCase = true)
            }
        } else {
            null
        }
        if (presetMatch != null) {
            return installUserscript(
                context = context,
                manager = manager,
                rawName = presetMatch.name,
                rawCode = presetMatch.code,
                rawMatches = matchPatterns ?: presetMatch.matches,
                rawRunAt = runAt ?: presetMatch.runAt.value,
                enabled = enabled ?: true,
            )
        }
        return installUserscript(
            context = context,
            manager = manager,
            rawName = scriptName,
            rawCode = code,
            rawMatches = matchPatterns,
            rawRunAt = runAt,
            enabled = enabled ?: true,
        )
    }

    suspend fun toggleUserscriptResult(
        context: Context,
        manager: BrowserUseManager?,
        nameOrId: String?,
        enabled: Boolean?,
    ): BrowserActionResult = toggleUserscript(
        context = context,
        manager = manager,
        rawName = nameOrId,
        explicitEnabled = enabled,
    )

    suspend fun removeUserscriptResult(
        context: Context,
        manager: BrowserUseManager?,
        nameOrId: String?,
    ): BrowserActionResult = removeUserscript(
        context = context,
        manager = manager,
        rawName = nameOrId,
    )

    fun buildAutoInjectionBundle(
        context: Context,
        url: String?,
        timing: RunAt,
    ): List<Pair<Userscript, String>> {
        val matching = matchingScriptsForUrl(context, url, timing)
        return matching.map { s -> s to buildInjectionJavaScript(s, captureReturn = false) }
    }

    fun wrapWithGMRuntime(context: Context, script: Userscript): String {
        ensureLoaded(context)
        return buildInjectionJavaScript(script, captureReturn = true)
    }
}
