package com.openminis.app.debug

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import com.openminis.app.MinisApp
import com.openminis.app.backup.BackupCategory
import com.openminis.app.backup.BackupExporter
import com.openminis.app.backup.BackupImporter
import com.openminis.app.backup.BackupZip
import com.openminis.app.browser.BrowserAction
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.browser.BrowserUseManager
import com.openminis.app.browser.SiteCookieCleaner
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.service.SessionActivityTracker
import java.io.File
import java.lang.ref.WeakReference
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-android-backup-subagents] Debug-only drivers for backup / restore and the
 * sub agent roster, so a cross-platform backup round trip can be exercised on a
 * device without the UI.
 *
 * They call the same [BackupExporter] / [BackupImporter] the Backup screen
 * uses; only the trigger differs. What they deliberately do NOT do:
 *  - deliver a package to a destination: the UI's only path uploads to the
 *    user's configured servers, which a test must not touch;
 *  - include credentials: `includeCredentials` defaults to false, so a test
 *    package carries no API keys or tokens.
 */
internal object BackupDebugMethods {

    private fun app(context: Context): MinisApp =
        context.applicationContext as? MinisApp ?: throw RPCException(-32000, "MinisApp not initialized")

    private fun repo(context: Context): ProviderRepository {
        val r = app(context).providerRepositoryOrNull ?: throw RPCException(-32000, "provider repository unavailable")
        r.ensureConfigLoaded()
        return r
    }

    private fun categories(params: JSONObject, default: Set<BackupCategory>?): Set<BackupCategory>? {
        val arr = params.optJSONArray("categories") ?: return default
        return (0 until arr.length()).map { i ->
            val key = arr.getString(i)
            BackupCategory.entries.firstOrNull { it.key == key }
                ?: throw RPCException(-32602, "unknown category '$key' (known: ${BackupCategory.entries.joinToString { it.key }})")
        }.toSet()
    }

    private fun SubAgentDefinition.toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("instructions", instructions)
        put("modelGroupId", modelGroupId ?: JSONObject.NULL)
        put("thinkingLevelOverride", thinkingLevelOverride?.name ?: JSONObject.NULL)
        put("isBuiltIn", isBuiltIn)
        put("sortOrder", sortOrder)
        put("updatedAt", updatedAt)
    }

    fun subAgentsList(context: Context): JSONObject =
        JSONObject().put("subAgents", JSONArray(repo(context).subAgents.map { it.toJson() }))

    fun subAgentsUpsert(context: Context, params: JSONObject): JSONObject {
        val r = repo(context)
        val name = params.optString("name").ifBlank { throw RPCException(-32602, "Missing 'name'") }
        val existing = params.optString("id").takeIf { it.isNotBlank() }?.let { id -> r.subAgents.firstOrNull { it.id == id } }
        val level = params.optString("thinkingLevelOverride").takeIf { it.isNotBlank() }
            ?.let { ThinkingLevel.parseOrNull(it) ?: throw RPCException(-32602, "bad thinkingLevelOverride '$it'") }
        val def = (existing ?: SubAgentDefinition(name = name, description = "")).copy(
            name = name,
            description = params.optString("description", existing?.description ?: ""),
            instructions = params.optString("instructions", existing?.instructions ?: ""),
            thinkingLevelOverride = level ?: existing?.thinkingLevelOverride,
        )
        r.upsertSubAgent(def)
        return JSONObject().put("id", def.id).put("subAgents", JSONArray(r.subAgents.map { it.toJson() }))
    }

    fun subAgentsDelete(context: Context, params: JSONObject): JSONObject {
        val r = repo(context)
        val id = params.optString("id").ifBlank { throw RPCException(-32602, "Missing 'id'") }
        r.deleteSubAgent(id)
        return JSONObject().put("deleted", id).put("remaining", r.subAgents.size)
    }

    /** Export into the app's files dir; no destination, no credentials by default. */
    suspend fun backupExport(context: Context, params: JSONObject): JSONObject {
        val a = app(context)
        val cats = categories(params, setOf(BackupCategory.PROVIDERS))!!
        val summary = BackupExporter(a, AppDatabase.getInstance(a)).export(
            BackupExporter.Options(
                categories = cats,
                includeCredentials = params.optBoolean("includeCredentials", false),
            ),
        )
        val outDir = File(a.filesDir, "debug-backup").apply { mkdirs() }
        val out = File(outDir, summary.packageFile.name)
        summary.packageFile.copyTo(out, overwrite = true)
        return JSONObject().apply {
            put("path", out.absolutePath)
            put("bytes", out.length())
            put("backupId", summary.backupId)
            put("members", JSONArray(BackupZip.listEntries(out)))
        }
    }

    /** Restore a package file already on the device. */
    suspend fun backupRestore(context: Context, params: JSONObject): JSONObject {
        val a = app(context)
        val pkg = File(params.optString("path").ifBlank { throw RPCException(-32602, "Missing 'path'") })
        if (!pkg.isFile) throw RPCException(-32602, "no package at ${pkg.absolutePath}")
        val work = File(a.cacheDir, "debug-restore-${System.currentTimeMillis()}")
        try {
            BackupZip.extract(pkg, work)
            val report = BackupImporter(a, AppDatabase.getInstance(a)).import(
                work,
                BackupImporter.Options(categories = categories(params, null)),
            )
            return JSONObject().apply {
                put("backupId", report.backupId)
                put("sourcePlatform", report.sourcePlatform ?: JSONObject.NULL)
                put("integrityChecked", report.integrityChecked)
                put("integrityFailed", JSONArray(report.integrityFailed))
                put("warnings", JSONArray(report.warnings))
                put("categories", JSONArray(report.categories.map { c ->
                    JSONObject().apply {
                        put("category", c.category)
                        put("imported", c.imported)
                        put("updated", c.updated)
                        put("skipped", c.skipped)
                        put("unreadable", c.unreadable)
                        put("failed", c.failed ?: JSONObject.NULL)
                    }
                }))
            }
        } finally {
            work.deleteRecursively()
        }
    }

    // -- [T-android-backup-webdav-deadline] Upload drivers ------------------

    /**
     * Add a WebDAV destination for tests. Added DISABLED, so a real backup run
     * (which delivers to every enabled destination) never sends to it; only
     * [backupUpload], which names it explicitly, does.
     */
    fun remotesAddWebdav(context: Context, params: JSONObject): JSONObject {
        val name = params.optString("name").ifBlank { throw RPCException(-32602, "Missing 'name'") }
        val url = params.optString("url").ifBlank { throw RPCException(-32602, "Missing 'url'") }
        val store = com.openminis.app.backup.remote.RcloneRemoteStore(context)
        store.add(
            name = name, backend = "webdav",
            params = mapOf("url" to url, "vendor" to "other", "user" to params.optString("user", "test")),
            secret = params.optString("password", "test"), path = params.optString("path", ""),
        )
        store.setEnabled(name, false)
        return JSONObject().put("added", name).put("enabled", false)
    }

    fun remotesRemove(context: Context, params: JSONObject): JSONObject {
        val name = params.optString("name").ifBlank { throw RPCException(-32602, "Missing 'name'") }
        com.openminis.app.backup.remote.RcloneRemoteStore(context).remove(name)
        return JSONObject().put("removed", name)
    }

    /**
     * Upload a package to ONE named destination with the real uploader.
     * `cancelAfterMs` flips the cancel flag after that long, the way Stop
     * does during a backup run. Returns how long it took and how it ended.
     */
    fun backupUpload(context: Context, params: JSONObject): JSONObject {
        val file = File(params.optString("path").ifBlank { throw RPCException(-32602, "Missing 'path'") })
        if (!file.isFile) throw RPCException(-32602, "no package at ${file.absolutePath}")
        val store = com.openminis.app.backup.remote.RcloneRemoteStore(context)
        val remote = store.remote(params.optString("remote"))
            ?: throw RPCException(-32602, "no destination named '${params.optString("remote")}'")
        store.syncToRclone()
        val cancelAfter = params.optLong("cancelAfterMs", -1L)
        val started = System.currentTimeMillis()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val timer = if (cancelAfter >= 0) {
            Thread {
                runCatching { Thread.sleep(cancelAfter) }
                cancelled.set(true)
            }.apply { isDaemon = true; start() }
        } else null
        val outcome = runCatching {
            com.openminis.app.backup.remote.RcloneChunkedUpload(context).upload(
                file, remote, "", isCancelled = { cancelled.get() },
            )
        }
        timer?.interrupt()
        val e = outcome.exceptionOrNull()
        return JSONObject().apply {
            put("ok", outcome.isSuccess)
            put("elapsedMs", System.currentTimeMillis() - started)
            put("cancelRequested", cancelled.get())
            put("error", e?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: JSONObject.NULL)
        }
    }
}

// ─── Merged from BrowserDebugMethods.kt ──────────────────────────────────────────────────

/**
 * Browser-debug RPC handlers (`debug.browser.*`).
 *
 * Mirrors the iOS `debug.browser.*` surface in `docs/debug-server-api.md`. All
 * methods operate on the application-scoped [MinisApp.sharedBrowserTabPool]
 * (the same pool the in-shell `minis-browser-use` agent drives) so external
 * automation sees the tabs the user has actually opened.
 *
 * Tab-id conventions mirror iOS: each method accepts an optional `tabId`. When
 * omitted the currently selected tab is used; an unknown id raises `-32602`
 * with `Tab not found: N`.
 */
internal object BrowserDebugMethods {

    private fun pool(context: Context): BrowserTabPool {
        val app = context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")
        return app.sharedBrowserTabPool
    }

    private fun resolveTab(pool: BrowserTabPool, params: JSONObject): BrowserTabPool.Tab {
        val tabs = pool.tabs.value
        if (tabs.isEmpty()) throw RPCException(-32000, "No tabs open")

        if (!params.has("tabId") || params.isNull("tabId")) {
            // Match iOS behavior — fall back to selectedTabId, then first tab.
            val sel = pool.selectedTabId.value
            return tabs.firstOrNull { it.id == sel } ?: tabs.first()
        }
        val id = params.optInt("tabId", -1)
        return tabs.firstOrNull { it.id == id }
            ?: throw RPCException(-32602, "Tab not found: $id")
    }

    fun listTabs(context: Context): JSONObject {
        val pool = pool(context)
        val sel = pool.selectedTabId.value
        val arr = JSONArray()
        for (tab in pool.tabs.value) {
            arr.put(JSONObject().apply {
                put("id", tab.id)
                put("url", tab.manager.currentURL.value)
                put("title", tab.manager.pageTitle.value)
                put("selected", tab.id == sel)
                put("inUse", tab.inUse)
            })
        }
        return JSONObject().put("tabs", arr)
    }

    suspend fun pageInfo(context: Context, params: JSONObject): JSONObject {
        val pool = pool(context)
        val tab = resolveTab(pool, params)
        val mgr = tab.manager
        val res = mgr.execute(BrowserActionInput(action = BrowserAction.GET_PAGE_INFO))
        // GET_PAGE_INFO returns metadata text; we surface the raw fields directly
        // for callers that want structured data without parsing the text blob.
        val viewport = readViewport(mgr)
        return JSONObject().apply {
            put("tabId", tab.id)
            put("url", mgr.currentURL.value)
            put("title", mgr.pageTitle.value)
            put("isLoading", mgr.isLoading.value)
            put("canGoBack", mgr.canGoBack.value)
            put("canGoForward", mgr.canGoForward.value)
            put("viewport", viewport)
            // Include the raw GET_PAGE_INFO text (newline-separated lines that
            // include scroll position + viewport) for clients that want the
            // same string the agent loop sees.
            put("text", res.text)
        }
    }

    private suspend fun readViewport(mgr: BrowserUseManager): JSONObject {
        // Mirror navigationMetadata's probe — same shape iOS exposes under
        // `debug.browser.pageInfo.viewport`.
        val js = "JSON.stringify({" +
            "sx:window.scrollX||0,sy:window.scrollY||0," +
            "pw:document.documentElement.scrollWidth||0," +
            "ph:document.documentElement.scrollHeight||0," +
            "vw:window.innerWidth||0,vh:window.innerHeight||0," +
            "rs:document.readyState||''" +
            "})"
        val res = mgr.execute(BrowserActionInput(action = BrowserAction.EXECUTE_JS, script = js))
        val out = JSONObject().apply {
            put("scrollX", 0)
            put("scrollY", 0)
            put("pageWidth", 0)
            put("pageHeight", 0)
            put("viewportWidth", 0)
            put("viewportHeight", 0)
            put("readyState", "")
        }
        // The result text holds the JSON literal for EXECUTE_JS — strip wrapping
        // quotes if any and parse.
        val raw = res.text.trim().trim('"').replace("\\\"", "\"")
        try {
            val parsed = JSONObject(raw)
            out.put("scrollX", parsed.optInt("sx"))
            out.put("scrollY", parsed.optInt("sy"))
            out.put("pageWidth", parsed.optInt("pw"))
            out.put("pageHeight", parsed.optInt("ph"))
            out.put("viewportWidth", parsed.optInt("vw"))
            out.put("viewportHeight", parsed.optInt("vh"))
            out.put("readyState", parsed.optString("rs"))
        } catch (_: Exception) {
            // Fall through with zeros — the page may be at about:blank.
        }
        return out
    }

    suspend fun executeJS(context: Context, params: JSONObject): JSONObject {
        val script = params.optString("script", "").ifEmpty {
            throw RPCException(-32602, "Missing 'script' param")
        }
        val pool = pool(context)
        val tab = resolveTab(pool, params)
        val res = tab.manager.execute(BrowserActionInput(action = BrowserAction.EXECUTE_JS, script = script))
        if (!res.success) throw RPCException(-32000, res.text)
        return JSONObject().put("result", res.text)
    }

    suspend fun getReadable(context: Context, params: JSONObject): JSONObject {
        val pool = pool(context)
        val tab = resolveTab(pool, params)
        val res = tab.manager.execute(BrowserActionInput(action = BrowserAction.GET_READABLE))
        if (!res.success) throw RPCException(-32000, res.text)
        // BrowserUseManager.getReadable returns formatted text; preserve the
        // same envelope iOS exposes (text + length + tabId).
        return JSONObject().apply {
            put("tabId", tab.id)
            put("text", res.text)
            put("length", res.text.length)
        }
    }

    suspend fun getText(context: Context, params: JSONObject): JSONObject {
        val pool = pool(context)
        val tab = resolveTab(pool, params)
        val selector = params.optString("selector", "").ifEmpty { null }
        val res = tab.manager.execute(BrowserActionInput(action = BrowserAction.GET_TEXT, selector = selector))
        if (!res.success) throw RPCException(-32000, res.text)
        return JSONObject().apply {
            put("tabId", tab.id)
            put("text", res.text)
            put("length", res.text.length)
        }
    }

    suspend fun screenshot(context: Context, params: JSONObject): JSONObject {
        val pool = pool(context)
        val tab = resolveTab(pool, params)
        val res = tab.manager.execute(BrowserActionInput(action = BrowserAction.SCREENSHOT))
        if (!res.success) throw RPCException(-32000, res.text)
        val b64 = res.base64Image
            ?: throw RPCException(-32000, "Screenshot returned no image data")
        // Decode size for parity with iOS (which reports `size` in bytes).
        val size = try {
            android.util.Base64.decode(b64, android.util.Base64.NO_WRAP).size
        } catch (_: Exception) { 0 }
        return JSONObject().apply {
            put("base64", b64)
            put("size", size)
            put("encoding", "jpeg")
            put("tabId", tab.id)
        }
    }

    /**
     * [ISS-040] DEV_TOOLS-only access to the WebView cookie store, to verify on
     * a device that the settings sheet's per-site delete really removes
     * cookies. Reports cookie NAMES only, never values.
     *  - `get`   {domain}: names the root of the site sends (SiteCookieCleaner).
     *  - `set`   {url, cookie}: one raw Set-Cookie value, e.g. to seed a test.
     *  - `clear` {domain}: the exact call the delete icon makes.
     */
    suspend fun cookies(params: JSONObject): JSONObject {
        if (!com.openminis.app.BuildConfig.DEV_TOOLS) throw RPCException(-32601, "cookie debug exists only in debug builds")
        val cm = android.webkit.CookieManager.getInstance()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            when (val action = params.optString("action")) {
                "get" -> {
                    val domain = params.optString("domain").ifBlank { throw RPCException(-32602, "domain required") }
                    JSONObject().put("domain", domain)
                        .put("names", JSONArray(SiteCookieCleaner.cookieNamesFor(domain, cm).sorted()))
                        .put("hasCookies", cm.hasCookies())
                }
                "set" -> {
                    // `url` is passed through as given, even a bare host and an empty
                    // cookie: that is how the old delete icon's no-op call is replayed.
                    if (!params.has("url") || !params.has("cookie")) throw RPCException(-32602, "url and cookie required")
                    val url = params.optString("url")
                    val cookie = params.optString("cookie")
                    cm.setCookie(url, cookie)
                    cm.flush()
                    JSONObject().put("ok", true)
                }
                "clear" -> {
                    val domain = params.optString("domain").ifBlank { throw RPCException(-32602, "domain required") }
                    val r = SiteCookieCleaner.clear(domain, cm)
                    JSONObject().put("domain", domain).put("cleared", r.cleared)
                        .put("found", JSONArray(r.found.sorted()))
                        .put("remaining", JSONArray(r.remaining.sorted()))
                }
                else -> throw RPCException(-32602, "action must be get | set | clear, got '$action'")
            }
        }
    }
}

// ─── Merged from ChatDebugMethods.kt ──────────────────────────────────────────────────

/**
 * Read-only `chat.*` RPC handlers — Phase 1.
 *
 * Mirrors the `chat.sessions.*` / `chat.messages.list` / `chat.models.list`
 * shape from `docs/debug-server-api.md`. Mutation handlers (`chat.prompt`,
 * `chat.retry`, `chat.session.cancel`, `chat.session.delete`) land in Phase 2
 * once the headless ChatViewModel runner is wired.
 */
internal object ChatDebugMethods {

    private fun chat(context: Context): ChatRepository =
        (context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")).chatRepository

    private fun provider(context: Context): ProviderRepository =
        (context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")).providerRepository

    suspend fun sessionsList(context: Context, params: JSONObject): JSONObject {
        val limit = params.optInt("limit", 50).coerceIn(1, 500)
        val includeEmpty = params.optBoolean("includeEmpty", false)
        val repo = chat(context)

        val all = repo.dao.listSessions()
        val arr = JSONArray()
        var emitted = 0
        val provRepo = provider(context)

        for (session in all) {
            if (emitted >= limit) break
            // Cheap "is empty" probe — load the most recent message preview to
            // gate the includeEmpty filter without joining all messages.
            val lastParts = repo.dao.lastMessageParts(session.id)
            if (!includeEmpty && lastParts == null) continue

            val modelDisplay = resolveModelDisplay(provRepo, session.modelId)
            arr.put(JSONObject().apply {
                put("id", session.id)
                put("title", session.title ?: JSONObject.NULL)
                put("modelId", session.modelId)
                put("modelName", modelDisplay)
                put("source", session.source ?: JSONObject.NULL)
                // [T-scheduled-active-session-android] Processing (own loop, sub
                // agents or own scheduled task due); isStreaming = the loop alone.
                put("isRunning", SessionActivityTracker.isProcessing(session.id))
                put("isStreaming", SessionActivityTracker.isActive(session.id))
                put("memoryEnabled", session.memoryEnabled == 1)
                put("category", session.category ?: JSONObject.NULL)
                put("pinnedAt", session.pinnedAt ?: JSONObject.NULL)
                put("lastMessagePreview", session.lastMessage ?: JSONObject.NULL)
                put("createdAt", session.createdAt)
                put("updatedAt", session.updatedAt)
            })
            emitted++
        }
        return JSONObject().apply {
            put("count", emitted)
            put("sessions", arr)
        }
    }

    suspend fun sessionsGet(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val repo = chat(context)
        val session = repo.dao.getSession(sessionId)
            ?: throw RPCException(-32602, "Session not found")
        val provRepo = provider(context)
        val msgs = repo.dao.loadMessages(sessionId)
        return JSONObject().apply {
            put("id", session.id)
            put("title", session.title ?: JSONObject.NULL)
            put("modelId", session.modelId)
            put("modelName", resolveModelDisplay(provRepo, session.modelId))
            put("source", session.source ?: JSONObject.NULL)
            put("isRunning", SessionActivityTracker.isProcessing(session.id))
            put("isStreaming", SessionActivityTracker.isActive(session.id))
            put("memoryEnabled", session.memoryEnabled == 1)
            put("category", session.category ?: JSONObject.NULL)
            put("messageCount", msgs.size)
            put("createdAt", session.createdAt)
            put("updatedAt", session.updatedAt)
        }
    }

    suspend fun messagesList(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val limit = params.optInt("limit", 200).coerceIn(1, 1000)
        val offset = params.optInt("offset", 0).coerceAtLeast(0)
        val rolesArr = params.optJSONArray("roles")
        val rolesFilter: Set<String>? = if (rolesArr != null) {
            (0 until rolesArr.length()).map { rolesArr.optString(it) }
                .filter { it.isNotEmpty() }
                .toSet()
                .takeIf { it.isNotEmpty() }
        } else null
        val includeTools = params.optBoolean("includeTools", true)
        val includeReasoning = params.optBoolean("includeReasoning", false)

        val repo = chat(context)
        repo.dao.getSession(sessionId) ?: throw RPCException(-32602, "Session not found")
        val all = repo.dao.loadMessages(sessionId)
        val filtered = if (rolesFilter == null) all else all.filter { it.role in rolesFilter }
        val sliced = filtered.drop(offset).take(limit)

        val arr = JSONArray()
        for (m in sliced) arr.put(messageToJson(m, includeTools, includeReasoning))
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("totalCount", filtered.size)
            put("count", arr.length())
            put("messages", arr)
        }
    }

    private fun messageToJson(m: MessageEntity, includeTools: Boolean, includeReasoning: Boolean): JSONObject {
        val obj = JSONObject().apply {
            put("id", m.id)
            put("role", m.role)
            put("createdAt", m.createdAt)
        }
        // partsJson is a JSON array of {type, value/...} blocks; surface it
        // as-is so callers see what's stored. Pull a flat `content` view for
        // text-only consumption.
        val parts = try { org.json.JSONArray(m.partsJson) } catch (_: Exception) { null }
        if (parts != null) {
            obj.put("parts", parts)
            val sb = StringBuilder()
            val tools = JSONArray()
            val attachments = JSONArray()
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                when (p.optString("type")) {
                    "text" -> sb.append(p.optString("value", ""))
                    "tool_call", "tool_use" -> if (includeTools) tools.put(p)
                    "tool_result" -> if (includeTools) tools.put(p)
                    "image", "file", "attachment" -> attachments.put(p)
                }
            }
            if (sb.isNotEmpty()) obj.put("content", sb.toString())
            if (tools.length() > 0) obj.put("toolCalls", tools)
            if (attachments.length() > 0) obj.put("attachments", attachments)
        } else {
            // Legacy/unstructured payload — return raw.
            obj.put("content", m.partsJson)
        }
        m.tokenUsage?.let {
            obj.put("tokenUsage", try { JSONObject(it) } catch (_: Exception) { it })
        }
        if (includeReasoning) {
            obj.put("reasoningContent", m.reasoningContent ?: JSONObject.NULL)
        }
        return obj
    }

    suspend fun sessionsUsage(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val perTurn = params.optBoolean("perTurn", false)
        val repo = chat(context)
        repo.dao.getSession(sessionId) ?: throw RPCException(-32602, "Session not found")
        val msgs = repo.dao.loadMessages(sessionId)

        var totalIn = 0L
        var totalOut = 0L
        var totalCacheCreate = 0L
        var totalCacheRead = 0L
        var totalReasoning = 0L
        var turnCount = 0
        val turnsArr = JSONArray()

        for (m in msgs) {
            if (m.role != "assistant") continue
            val raw = m.tokenUsage ?: continue
            val usage = try { JSONObject(raw) } catch (_: Exception) { continue }
            val ti = usage.optLong("inputTokens", usage.optLong("input_tokens", 0))
            val to = usage.optLong("outputTokens", usage.optLong("output_tokens", 0))
            val tcc = usage.optLong("cacheCreationTokens", usage.optLong("cache_creation_tokens", 0))
            val tcr = usage.optLong("cacheReadTokens", usage.optLong("cache_read_tokens",
                usage.optLong("prompt_cache_hit_tokens", 0)))
            val tr = usage.optLong("reasoningTokens", usage.optLong("reasoning_tokens", 0))
            totalIn += ti; totalOut += to; totalCacheCreate += tcc; totalCacheRead += tcr; totalReasoning += tr
            turnCount++
            if (perTurn) {
                turnsArr.put(JSONObject().apply {
                    put("messageId", m.id)
                    put("createdAt", m.createdAt)
                    put("inputTokens", ti)
                    put("outputTokens", to)
                    put("cacheCreationTokens", tcc)
                    put("cacheReadTokens", tcr)
                    put("reasoningTokens", tr)
                })
            }
        }
        val totals = JSONObject().apply {
            put("inputTokens", totalIn)
            put("outputTokens", totalOut)
            put("cacheCreationTokens", totalCacheCreate)
            put("cacheReadTokens", totalCacheRead)
            put("reasoningTokens", totalReasoning)
            put("turnCount", turnCount)
        }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("totals", totals)
            if (perTurn) put("turns", turnsArr)
        }
    }

    fun modelsList(context: Context, params: JSONObject): JSONObject {
        val includeHidden = params.optBoolean("includeHidden", false)
        val includeDisabled = params.optBoolean("includeDisabled", false)
        val cfg = provider(context).config.value
        val enabledIds = cfg.instances.filter { it.isEnabled || includeDisabled }.map { it.id }.toSet()

        val entriesArr = JSONArray()
        for (entry in cfg.modelEntries) {
            if (entry.providerInstanceId !in enabledIds) continue
            if (!includeHidden && entry.isHidden) continue
            val inst = cfg.instances.find { it.id == entry.providerInstanceId } ?: continue
            val effective = entry.model
            val inputs = effective.inputModalities ?: emptyList()
            entriesArr.put(JSONObject().apply {
                put("id", entry.id)
                put("modelId", effective.id)
                put("modelName", effective.displayName)
                put("providerInstanceId", inst.id)
                put("providerInstanceName", inst.label)
                put("providerType", inst.providerType.name)
                put("supportsReasoning", effective.supportsReasoning ?: JSONObject.NULL)
                put("supportsImages", "image" in inputs)
                put("supportsVideo", "video" in inputs)
                put("supportsAudio", "audio" in inputs)
                put("supportsPDF", "pdf" in inputs)
                put("contextWindow", effective.contextWindow ?: JSONObject.NULL)
                put("maxOutputTokens", effective.maxOutputTokens ?: JSONObject.NULL)
                put("hidden", entry.isHidden)
            })
        }

        val groupsArr = JSONArray()
        for (group in cfg.modelGroups) {
            val ids = JSONArray()
            for (memberId in group.memberEntryIds) ids.put(memberId)
            groupsArr.put(JSONObject().apply {
                put("id", group.id)
                put("name", group.name)
                put("strategy", group.strategy.name)
                put("isDefault", group.id == cfg.defaultPrimaryGroupId)
                put("memberEntryIds", ids)
            })
        }

        return JSONObject().apply {
            put("defaultGroupId", cfg.defaultPrimaryGroupId ?: JSONObject.NULL)
            put("groupCount", groupsArr.length())
            put("entryCount", entriesArr.length())
            put("groups", groupsArr)
            put("entries", entriesArr)
        }
    }

    private fun resolveModelDisplay(repo: ProviderRepository, modelId: String): String {
        val cfg = repo.config.value
        val entry = cfg.modelEntries.firstOrNull { it.baseModel.id == modelId || it.id == modelId }
            ?: return modelId
        return entry.model.displayName
    }
}

// ─── Merged from DebugInspectorMethods.kt ──────────────────────────────────────────────────

/**
 * Compose-Semantics + View hierarchy introspection for the debug RPC server.
 *
 * Mirrors the iOS DebugViewInspector contract (viewTree / search / inspect /
 * highlight) but takes an explicit shallow approach: Compose nodes come from
 * the public SemanticsNode API rather than a UIKit-style reflection walk.
 * That's enough for test harnesses to find buttons by text and verify
 * geometry, and means we don't need to mirror iOS's class-name strings.
 *
 * Addresses are System.identityHashCode hex strings stamped per snapshot —
 * inspect/highlight need a fresh viewTree (or search) call before they can
 * resolve a node, since hashes don't survive recomposition.
 */
object DebugInspectorMethods {

    // Snapshot of nodes by address, populated at viewTree/search time so
    // subsequent inspect/highlight can resolve back to a live View or
    // SemanticsNode without re-walking the tree. Cleared on each fresh walk.
    private val snapshot = mutableMapOf<String, NodeRef>()

    private sealed class NodeRef {
        class V(val view: WeakReference<View>) : NodeRef()
        class S(val node: SemanticsNode) : NodeRef()
    }

    /**
     * Safe-read for SemanticsConfiguration: contains() + indexed get(),
     * since `getOrNull` is not in the public API of this version.
     */
    private fun <T> SemanticsConfiguration.readOrNull(key: SemanticsPropertyKey<T>): T? {
        return if (this.contains(key)) this[key] else null
    }

    suspend fun viewTree(activity: Activity, maxDepth: Int): JSONArray =
        runOnMain {
            snapshot.clear()
            val result = JSONArray()
            val root = activity.window?.decorView ?: return@runOnMain result
            result.put(buildViewNode(root, depth = 0, maxDepth = maxDepth))
            result
        }

    suspend fun search(activity: Activity, keyword: String, scope: String): JSONArray =
        runOnMain {
            snapshot.clear()
            val matches = JSONArray()
            val root = activity.window?.decorView ?: return@runOnMain matches
            walkAndMatch(root, keyword.lowercase(), scope, matches)
            matches
        }

    suspend fun inspect(activity: Activity, address: String): JSONObject? = runOnMain {
        when (val ref = snapshot[address]) {
            is NodeRef.V -> ref.view.get()?.let { describeView(it, deep = true) }
            is NodeRef.S -> describeSemantics(ref.node, deep = true)
            null -> null
        }
    }

    suspend fun highlight(activity: Activity, address: String, color: String, durationSec: Double): Boolean =
        runOnMain {
            val rect = when (val ref = snapshot[address]) {
                is NodeRef.V -> ref.view.get()?.let { viewBounds(it) }
                is NodeRef.S -> {
                    val r = ref.node.boundsInWindow
                    intArrayOf(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
                }
                null -> null
            } ?: return@runOnMain false
            showOverlay(activity, rect, parseColor(color), (durationSec * 1000).toLong().coerceIn(100L, 10_000L))
            true
        }

    // ── View walk ───────────────────────────────────────────────────────────

    private fun buildViewNode(view: View, depth: Int, maxDepth: Int): JSONObject {
        val node = JSONObject()
        val address = stamp(view)
        node.put("address", address)
        node.put("type", view.javaClass.simpleName)
        node.put("bounds", boundsJson(viewBounds(view)))
        if (view is android.widget.TextView) {
            view.text?.toString()?.takeIf { it.isNotEmpty() }?.let { node.put("text", it) }
        }
        view.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let { node.put("description", it) }
        if (isComposeHost(view)) {
            // Append the merged Compose semantics tree (one entry per logical
            // a11y node, much fewer than the underlying View tree).
            semanticsRootOf(view)?.let { root ->
                val sem = buildSemanticsNode(root, depth = depth + 1, maxDepth = maxDepth)
                if (sem != null) {
                    val list = JSONArray().apply { put(sem) }
                    node.put("compose", list)
                }
            }
        }
        if (view is ViewGroup && depth < maxDepth) {
            val children = JSONArray()
            for (i in 0 until view.childCount) {
                children.put(buildViewNode(view.getChildAt(i), depth + 1, maxDepth))
            }
            if (children.length() > 0) node.put("children", children)
        }
        return node
    }

    private fun buildSemanticsNode(node: SemanticsNode, depth: Int, maxDepth: Int): JSONObject? {
        val address = stamp(node)
        val obj = JSONObject()
        obj.put("address", address)
        obj.put("type", "SemanticsNode")
        val text = node.config.readOrNull(SemanticsProperties.Text)
            ?.joinToString(" ") { it.text }
        if (!text.isNullOrEmpty()) obj.put("text", text)
        node.config.readOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString(" ")?.takeIf { it.isNotEmpty() }
            ?.let { obj.put("description", it) }
        node.config.readOrNull(SemanticsProperties.Role)?.toString()
            ?.let { obj.put("role", it) }
        node.config.readOrNull(SemanticsProperties.TestTag)
            ?.let { obj.put("testTag", it) }
        val r = node.boundsInWindow
        obj.put("bounds", boundsJson(intArrayOf(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())))
        if (depth < maxDepth) {
            val kids = JSONArray()
            for (child in node.children) {
                buildSemanticsNode(child, depth + 1, maxDepth)?.let(kids::put)
            }
            if (kids.length() > 0) obj.put("children", kids)
        }
        return obj
    }

    private fun walkAndMatch(view: View, keyword: String, scope: String, out: JSONArray) {
        if (matches(view, keyword, scope)) {
            out.put(describeView(view, deep = false))
        }
        if (isComposeHost(view)) {
            semanticsRootOf(view)?.let { walkSemantics(it, keyword, scope, out) }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                walkAndMatch(view.getChildAt(i), keyword, scope, out)
            }
        }
    }

    private fun walkSemantics(node: SemanticsNode, keyword: String, scope: String, out: JSONArray) {
        if (matchesSemantics(node, keyword, scope)) {
            out.put(describeSemantics(node, deep = false))
        }
        for (child in node.children) walkSemantics(child, keyword, scope, out)
    }

    private fun matches(view: View, kw: String, scope: String): Boolean {
        when (scope) {
            "type" -> return view.javaClass.simpleName.lowercase().contains(kw)
            "text" -> {
                if (view is android.widget.TextView) {
                    if (view.text?.toString()?.lowercase()?.contains(kw) == true) return true
                }
                if (view.contentDescription?.toString()?.lowercase()?.contains(kw) == true) return true
                return false
            }
        }
        // "all"
        if (view.javaClass.simpleName.lowercase().contains(kw)) return true
        if (view is android.widget.TextView && view.text?.toString()?.lowercase()?.contains(kw) == true) return true
        if (view.contentDescription?.toString()?.lowercase()?.contains(kw) == true) return true
        return false
    }

    private fun matchesSemantics(node: SemanticsNode, kw: String, scope: String): Boolean {
        val text = node.config.readOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.lowercase()
        val desc = node.config.readOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.lowercase()
        val tag = node.config.readOrNull(SemanticsProperties.TestTag)?.lowercase()
        val role = node.config.readOrNull(SemanticsProperties.Role)?.toString()?.lowercase()
        return when (scope) {
            "type" -> role?.contains(kw) == true || tag?.contains(kw) == true
            "text" -> text?.contains(kw) == true || desc?.contains(kw) == true
            else -> text?.contains(kw) == true || desc?.contains(kw) == true ||
                tag?.contains(kw) == true || role?.contains(kw) == true
        }
    }

    private fun describeView(view: View, deep: Boolean): JSONObject {
        val obj = JSONObject()
        obj.put("address", stamp(view))
        obj.put("type", view.javaClass.simpleName)
        obj.put("bounds", boundsJson(viewBounds(view)))
        if (view is android.widget.TextView) {
            view.text?.toString()?.takeIf { it.isNotEmpty() }?.let { obj.put("text", it) }
        }
        view.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let { obj.put("description", it) }
        obj.put("visible", view.visibility == View.VISIBLE)
        obj.put("enabled", view.isEnabled)
        if (deep) {
            obj.put("alpha", view.alpha.toDouble())
            obj.put("class", view.javaClass.name)
            view.id.takeIf { it != View.NO_ID }?.let {
                obj.put("idHex", String.format("0x%08x", it))
                runCatching { obj.put("idName", view.resources.getResourceEntryName(it)) }
            }
        }
        return obj
    }

    private fun describeSemantics(node: SemanticsNode, deep: Boolean): JSONObject {
        val obj = JSONObject()
        obj.put("address", stamp(node))
        obj.put("type", "SemanticsNode")
        val text = node.config.readOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
        if (!text.isNullOrEmpty()) obj.put("text", text)
        node.config.readOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString(" ")?.takeIf { it.isNotEmpty() }
            ?.let { obj.put("description", it) }
        node.config.readOrNull(SemanticsProperties.Role)?.toString()?.let { obj.put("role", it) }
        node.config.readOrNull(SemanticsProperties.TestTag)?.let { obj.put("testTag", it) }
        val r = node.boundsInWindow
        obj.put("bounds", boundsJson(intArrayOf(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())))
        if (deep) {
            val actions = JSONArray()
            // Common actions surface as keys we can probe explicitly.
            if (node.config.contains(SemanticsActions.OnClick)) actions.put("onClick")
            if (node.config.contains(SemanticsActions.OnLongClick)) actions.put("onLongClick")
            if (node.config.contains(SemanticsActions.SetText)) actions.put("setText")
            if (node.config.contains(SemanticsActions.ScrollBy)) actions.put("scrollBy")
            if (actions.length() > 0) obj.put("actions", actions)
            node.config.readOrNull(SemanticsProperties.EditableText)?.text?.takeIf { it.isNotEmpty() }
                ?.let { obj.put("editableText", it) }
            node.config.readOrNull(SemanticsProperties.StateDescription)?.let { obj.put("state", it) }
        }
        return obj
    }

    // ── Compose Semantics access ────────────────────────────────────────────

    /** True for AbstractComposeView / AndroidComposeView instances. */
    private fun isComposeHost(view: View): Boolean {
        var cls: Class<*>? = view.javaClass
        while (cls != null) {
            val name = cls.name
            if (name == "androidx.compose.ui.platform.AbstractComposeView" ||
                name == "androidx.compose.ui.platform.AndroidComposeView") return true
            cls = cls.superclass
        }
        return false
    }

    /** Walk up to find the AndroidComposeView and reflect into its semanticsOwner. */
    private fun semanticsRootOf(host: View): SemanticsNode? {
        // AbstractComposeView wraps AndroidComposeView as its only child.
        val owner = findAndroidComposeViewSemanticsOwner(host) ?: return null
        return runCatching {
            // SemanticsOwner has unmergedRootSemanticsNode (kotlin property).
            val m = owner.javaClass.getMethod("getUnmergedRootSemanticsNode")
            m.invoke(owner) as? SemanticsNode
        }.getOrNull()
    }

    private fun findAndroidComposeViewSemanticsOwner(view: View): Any? {
        // Direct AndroidComposeView
        if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") {
            return runCatching {
                val m = view.javaClass.getMethod("getSemanticsOwner")
                m.invoke(view)
            }.getOrNull()
        }
        // AbstractComposeView with a single AndroidComposeView child
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findAndroidComposeViewSemanticsOwner(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    // ── Address bookkeeping ─────────────────────────────────────────────────

    private fun stamp(view: View): String {
        val a = "0x" + Integer.toHexString(System.identityHashCode(view))
        snapshot[a] = NodeRef.V(WeakReference(view))
        return a
    }

    private fun stamp(node: SemanticsNode): String {
        val a = "0x" + Integer.toHexString(System.identityHashCode(node))
        snapshot[a] = NodeRef.S(node)
        return a
    }

    private fun viewBounds(view: View): IntArray {
        val pos = IntArray(2)
        view.getLocationOnScreen(pos)
        return intArrayOf(pos[0], pos[1], pos[0] + view.width, pos[1] + view.height)
    }

    private fun boundsJson(rect: IntArray): JSONObject {
        return JSONObject()
            .put("left", rect[0]).put("top", rect[1])
            .put("right", rect[2]).put("bottom", rect[3])
            .put("width", rect[2] - rect[0]).put("height", rect[3] - rect[1])
    }

    // ── Highlight overlay ───────────────────────────────────────────────────

    private fun showOverlay(activity: Activity, rect: IntArray, color: Int, durationMs: Long) {
        val root = activity.window.decorView as? ViewGroup ?: return
        // Convert screen coords to decorView-relative.
        val decorPos = IntArray(2)
        root.getLocationOnScreen(decorPos)
        val left = (rect[0] - decorPos[0]).coerceAtLeast(0)
        val top = (rect[1] - decorPos[1]).coerceAtLeast(0)
        val width = (rect[2] - rect[0]).coerceAtLeast(1)
        val height = (rect[3] - rect[1]).coerceAtLeast(1)

        val overlay = View(activity).apply {
            background = GradientDrawable().apply {
                setStroke(6, color)
                setColor(color and 0x00FFFFFF or 0x33000000.toInt())
            }
        }
        val lp = FrameLayout.LayoutParams(width, height).apply {
            this.leftMargin = left
            this.topMargin = top
            gravity = Gravity.START or Gravity.TOP
        }
        root.addView(overlay, lp)
        Handler(Looper.getMainLooper()).postDelayed({
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }, durationMs)
    }

    private fun parseColor(name: String): Int = when (name.lowercase()) {
        "red" -> Color.RED
        "green" -> Color.GREEN
        "blue" -> Color.BLUE
        "yellow" -> Color.YELLOW
        "purple" -> Color.parseColor("#8E24AA")
        "orange" -> Color.parseColor("#FF9800")
        else -> runCatching { Color.parseColor(name) }.getOrDefault(Color.RED)
    }

    private suspend fun <T> runOnMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { block() }
    }
}

// ─── Merged from ProviderDebugMethods.kt ──────────────────────────────────────────────────

/**
 * Read-only `provider.*` and `provider.groups.*` RPC handlers — Phase 1.
 *
 * Mirrors the read shape from `docs/debug-server-api.md`. Mutation handlers
 * (`provider.instances.create/update/delete/test`, `provider.models.*`,
 * `provider.groups.create/update/delete/setDefault`) land in Phase 2.
 *
 * Field naming is kept 1:1 with the iOS docs so the same automation scripts
 * work cross-platform; Android-only fields that have no iOS analogue (e.g.
 * `useResponsesAPI` on a single instance) are surfaced inline.
 */
internal object ProviderDebugMethods {

    private fun repo(context: Context): ProviderRepository {
        val app = context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")
        // Force the lazy load HERE, once, for every provider.* handler. These
        // handlers read `config.value` directly, and ProviderRepository loads
        // config lazily on first mutation/access — so on a cold process (app
        // just launched, user still on the session list) every read returned
        // an empty config. `provider.instances.list` answering `count: 0` for
        // a device with 20 configured providers looks exactly like data loss,
        // which is a far worse failure than a slow first call.
        app.providerRepository.ensureConfigLoaded()
        return app.providerRepository
    }

    fun types(): JSONObject {
        val arr = JSONArray()
        for (type in ProviderType.values()) {
            arr.put(typeSpec(type))
        }
        return JSONObject().put("types", arr)
    }

    private fun typeSpec(type: ProviderType): JSONObject {
        // Cross-reference iOS doc shape. Some fields don't have a direct
        // Android equivalent and are reported as null/absent rather than
        // synthesized — keeps "what's missing" honest for callers.
        val supportedCreds = JSONArray().apply {
            put("apiKey")
            if (type == ProviderType.anthropic || type == ProviderType.openAI) put("oauth")
        }
        val builtInIds = JSONArray()
        for (m in type.builtInModels) builtInIds.put(m.id)

        // T192: Anthropic now supports custom base URL too (Anthropic-
        // compatible endpoints like DeepSeek-anthropic, Bailian-anthropic).
        // iOS LLMProviderFactory.makeAnthropicProvider has always allowed
        // this — Android was the only platform reporting the field as
        // unsupported via RPC `provider.types`.
        val customBaseSupported = type == ProviderType.openAI || type == ProviderType.anthropic
        return JSONObject().apply {
            put("id", type.name)
            put("displayName", type.displayName)
            put("supportedCredentials", supportedCreds)
            put("oauthFlow", when (type) {
                ProviderType.anthropic -> "claude"
                ProviderType.openAI -> "codex"
                else -> JSONObject.NULL
            })
            put("defaultBaseURL", defaultBaseURL(type))
            put("customBaseURLSupported", customBaseSupported)
            put("appendV1SuffixConfigurable", customBaseSupported)
            put("builtInModelIds", builtInIds)
        }
    }

    private fun defaultBaseURL(type: ProviderType): String = when (type) {
        ProviderType.anthropic -> "https://api.anthropic.com"
        ProviderType.gemini -> "https://generativelanguage.googleapis.com"
        ProviderType.openAI -> "https://api.openai.com"
        ProviderType.openRouter -> "https://openrouter.ai/api/v1"
        ProviderType.xAI -> "https://api.x.ai/v1"
        ProviderType.kimiCode -> "https://api.kimi.com/coding/v1"
        ProviderType.githubCopilot -> com.openminis.app.auth.CopilotDeviceFlow.API_BASE
        // [T-android-provider-type-parity] Responses API shares the OpenAI
        // host; undrivable types have no canonical base to report.
        ProviderType.openAIResponses -> "https://api.openai.com"
        ProviderType.antigravity, ProviderType.unsupported -> ""
    }

    fun instancesList(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val includeDisabled = params.optBoolean("includeDisabled", true)
        val cfg = repo.config.value
        val arr = JSONArray()
        for (inst in cfg.instances) {
            if (!includeDisabled && !inst.isEnabled) continue
            // [T-empty-key-compat-endpoints] Mirror what routing sees:
            // usableApiKey returns "" (usable) for keyless third-party
            // compatible endpoints, null only when genuinely unusable.
            val hasKey = repo.usableApiKey(inst) != null
            val entryCount = cfg.modelEntries.count { it.providerInstanceId == inst.id }
            arr.put(JSONObject().apply {
                put("id", inst.id)
                put("label", inst.label)
                put("providerType", inst.providerType.name)
                put("credentialType", inst.credentialType.name)
                put("isEnabled", inst.isEnabled)
                put("customBaseURL", inst.customBaseURL ?: JSONObject.NULL)
                put("appendV1Suffix", inst.appendV1Suffix)
                put("useResponsesAPI", inst.useResponsesAPI)
                // [GH#68] Surface the image-endpoint picker state so harnesses
                // can verify persistence across restarts.
                put("imageEndpointMode", inst.imageEndpointMode.name)
                put("imageEndpointResolved", inst.imageEndpointResolved?.name ?: JSONObject.NULL)
                put("customUserAgent", inst.customUserAgent ?: JSONObject.NULL)
                put("createdAt", inst.createdAt)
                put("hasCredential", hasKey)
                put("modelEntryCount", entryCount)
            })
        }
        return JSONObject().apply {
            put("count", arr.length())
            put("instances", arr)
        }
    }

    fun modelsList(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val instanceId = params.optString("instanceId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'instanceId' param")
        }
        val includeHidden = params.optBoolean("includeHidden", false)
        val instance = repo.instance(instanceId)
            ?: throw RPCException(-32602, "Instance not found: $instanceId")
        val all = repo.config.value.modelEntries.filter { it.providerInstanceId == instanceId }
        val visible = if (includeHidden) all else all.filter { !it.isHidden }
        val arr = JSONArray()
        for (entry in visible) {
            val effective: LLMModel = entry.model
            arr.put(JSONObject().apply {
                put("id", entry.id)
                put("modelId", effective.id)
                put("displayName", effective.displayName)
                put("baseModelDisplayName", entry.baseModel.displayName)
                put("isCustom", entry.isCustom)
                put("isHidden", entry.isHidden)
                put("supportsReasoning", effective.supportsReasoning ?: JSONObject.NULL)
                put("contextWindow", effective.contextWindow ?: JSONObject.NULL)
                put("maxOutputTokens", effective.maxOutputTokens ?: JSONObject.NULL)
                val ov = JSONObject()
                ov.put("displayName", entry.overrides.displayName ?: JSONObject.NULL)
                ov.put("maxOutputTokens", entry.overrides.maxOutputTokens ?: JSONObject.NULL)
                ov.put("contextWindow", entry.overrides.contextWindow ?: JSONObject.NULL)
                put("overrides", ov)
                put("userModifiedAt", entry.userModifiedAt ?: JSONObject.NULL)
            })
        }
        return JSONObject().apply {
            put("instanceId", instanceId)
            put("instanceLabel", instance.label)
            put("count", arr.length())
            put("entries", arr)
        }
    }

    /**
     * Export a provider instance + its model entries + stored credential as a
     * portable JSON document. Mirrors the UI Share button. The API key is
     * base64-wrapped (same as ProviderRepository.exportInstanceJSON) — that's
     * to survive JSON-string escaping of arbitrary key bytes, not for
     * security; callers of the 5321 RPC are trusted by definition.
     */
    fun export(context: Context, params: JSONObject): JSONObject {
        val instanceId = params.optString("instanceId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'instanceId' param")
        }
        val repo = repo(context)
        val instance = repo.instance(instanceId)
            ?: throw RPCException(-32602, "Instance not found: $instanceId")
        val raw = repo.exportInstanceJSON(instanceId)
            ?: throw RPCException(-32000, "Export failed for instance $instanceId")
        // Wrap the underlying JSON document under a versioned envelope so
        // forward-compatible importers can detect the schema explicitly.
        val payload = JSONObject(raw)
        return JSONObject().apply {
            put("version", 1)
            put("exportedAt", System.currentTimeMillis())
            put("platform", "android")
            put("instanceId", instanceId)
            put("instanceLabel", instance.label)
            put("config", payload)
        }
    }

    /**
     * Import a provider instance from an export envelope (or the raw inner
     * config JSON, for cross-platform compatibility). The implementation
     * always allocates a new instance UUID — duplicate label is auto-renamed
     * by the repository.
     */
    fun import(context: Context, params: JSONObject): JSONObject {
        // Accept either the new envelope (`{version,config:{...}}`) or the
        // raw legacy config JSON. Be liberal on input — the export contract
        // is symmetric on the value side, and harnesses that capture from
        // older builds should still load.
        val raw = params.optString("configJson", "").ifEmpty {
            throw RPCException(-32602, "Missing 'configJson' param")
        }
        val parsed = try { JSONObject(raw) } catch (e: Exception) {
            throw RPCException(-32602, "Invalid configJson: ${e.message}")
        }
        val configJson = if (parsed.has("config") && parsed.optJSONObject("config") != null) {
            parsed.optJSONObject("config")!!.toString()
        } else {
            raw
        }
        val repo = repo(context)
        val resolvedLabel = repo.importInstanceJSON(configJson)
            ?: throw RPCException(-32000, "Import failed — invalid or unsupported configJson")
        // Find the freshly-created instance so we can return its id.
        val newInstance = repo.config.value.instances.lastOrNull { it.label == resolvedLabel }
            ?: throw RPCException(-32000, "Import succeeded but instance lookup failed")
        return JSONObject().apply {
            put("instanceId", newInstance.id)
            put("instanceLabel", newInstance.label)
            put("modelEntryCount", repo.entriesFor(newInstance.id).size)
            put("success", true)
        }
    }

    fun groupsList(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val includeMembers = params.optBoolean("includeMembers", true)
        val cfg = repo.config.value
        val arr = JSONArray()
        for (group in cfg.modelGroups) {
            val isDefault = group.id == cfg.defaultPrimaryGroupId
            val obj = JSONObject().apply {
                put("id", group.id)
                put("name", group.name)
                put("strategy", group.strategy.name)
                put("fallbackStrategy", group.fallbackStrategy.name)
                put("isDefault", isDefault)
                put("inAgentLoop", group.id in cfg.agentLoopGroupIds)
                val ids = JSONArray()
                for (id in group.memberEntryIds) ids.put(id)
                put("memberEntryIds", ids)
            }
            if (includeMembers) {
                val members = JSONArray()
                for (memberId in group.memberEntryIds) {
                    // SystemVoiceEntries fallback: the default voice groups are
                    // seeded with "__builtin_system_speech__/…" members that are
                    // synthesized on demand and never stored in modelEntries, so
                    // matching modelEntries alone reported `members: []` for both
                    // voice groups while memberEntryIds listed two.
                    val entry = cfg.modelEntries.find { it.id == memberId }
                        ?: com.openminis.app.data.model.SystemVoiceEntries.resolve(memberId)
                        ?: continue
                    val instLabel = cfg.instances.find { it.id == entry.providerInstanceId }?.label
                    members.put(JSONObject().apply {
                        put("entryId", entry.id)
                        put("modelId", entry.baseModel.id)
                        put("displayName", entry.model.displayName)
                        put("providerLabel", instLabel ?: "")
                    })
                }
                obj.put("members", members)
            }
            arr.put(obj)
        }
        return JSONObject().apply {
            put("defaultGroupId", cfg.defaultPrimaryGroupId ?: JSONObject.NULL)
            put("count", arr.length())
            put("groups", arr)
        }
    }

    /**
     * [T-android-debug-quicktest] `provider.quickTest` — run the UI Quick Test
     * sheet's checks headlessly. Port of the iOS debug server's
     * `debug.providers.quickTest`, which Android lacked: verifying that N voice
     * models actually synthesize or transcribe otherwise meant N manual trips
     * through the picker, and there was no way to regression-test a fleet of
     * ASR/TTS providers at all.
     *
     * Calls the SAME `performTest` the sheet calls (made internal for this),
     * rather than reimplementing the probes — a second copy would drift and
     * then "passes over RPC, fails in the app" becomes possible.
     *
     * Audio/image payloads are reported as byte counts, not base64 blobs: the
     * question this answers is "did it produce output", and a 200KB WAV per
     * model would swamp a fleet run.
     */
    suspend fun quickTest(context: Context, params: JSONObject): JSONObject {
        val entryId = params.optString("entryId")
        if (entryId.isEmpty()) throw RPCException(-32602, "Invalid params: 'entryId' is required")
        val repo = repo(context)
        val entry = repo.config.value.modelEntries.find { it.id == entryId }
            ?: com.openminis.app.data.model.SystemVoiceEntries.resolve(entryId)
            ?: throw RPCException(-32602, "Model entry not found: $entryId")

        val requested = params.optJSONArray("kinds")
        val kinds: List<com.openminis.app.ui.components.QuickTestKind> = if (requested == null) {
            com.openminis.app.ui.components.applicableKinds(entry)
        } else {
            (0 until requested.length()).mapNotNull { i ->
                when (requested.optString(i).lowercase()) {
                    "text" -> com.openminis.app.ui.components.QuickTestKind.TEXT
                    "speechout", "speech_out", "tts" -> com.openminis.app.ui.components.QuickTestKind.SPEECH_OUT
                    "transcription", "asr" -> com.openminis.app.ui.components.QuickTestKind.TRANSCRIPTION
                    "imagegen", "image_gen", "image" -> com.openminis.app.ui.components.QuickTestKind.IMAGE_GEN
                    else -> null
                }
            }
        }
        if (kinds.isEmpty()) throw RPCException(-32602, "No applicable test kinds")

        val results = JSONArray()
        for (kind in kinds) {
            val t0 = System.currentTimeMillis()
            val state = try {
                com.openminis.app.ui.components.performTest(kind, entry, repo, context)
            } catch (e: Exception) {
                com.openminis.app.ui.components.QuickTestState.Failure(
                    e.message ?: e::class.java.simpleName,
                )
            }
            val elapsed = System.currentTimeMillis() - t0
            results.put(JSONObject().apply {
                put("kind", kind.name.lowercase())
                put("elapsedMs", elapsed)
                when (state) {
                    is com.openminis.app.ui.components.QuickTestState.TextReply -> {
                        put("status", "ok"); put("detail", state.text.take(400))
                    }
                    is com.openminis.app.ui.components.QuickTestState.AudioReply -> {
                        put("status", "ok")
                        put("bytes", state.data.size)
                        put("detail", "audio ${state.data.size} bytes")
                    }
                    is com.openminis.app.ui.components.QuickTestState.ImageReply -> {
                        put("status", "ok")
                        put("bytes", state.data.size)
                        put("detail", "image ${state.data.size} bytes")
                    }
                    is com.openminis.app.ui.components.QuickTestState.Failure -> {
                        put("status", "failed"); put("detail", state.message.take(600))
                    }
                    else -> { put("status", "unknown"); put("detail", state.toString().take(200)) }
                }
            })
        }
        val inst = repo.config.value.instances.find { it.id == entry.providerInstanceId }
        return JSONObject().apply {
            put("entryId", entry.id)
            put("modelId", entry.model.id)
            put("displayName", entry.model.displayName)
            put("providerLabel", inst?.label ?: "")
            put("results", results)
        }
    }
}
