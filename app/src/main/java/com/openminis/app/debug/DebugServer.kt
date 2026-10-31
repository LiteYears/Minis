package com.openminis.app.debug

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.openminis.app.MinisApp
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.ChatViewModelStore
import com.openminis.app.ui.chat.InputAttachment
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Debug-only JSON-RPC 2.0 server on port 8321.
 * Listens on all interfaces (0.0.0.0) so it's reachable from the local network for debugging.
 * Mirrors the iOS DebugServer for parity with the debug-server CLI skill.
 *
 * IMPORTANT: Only start this in debug builds. Never start in release.
 */
class DebugServer(
    private val context: Context,
    private val port: Int = 5321,
) {
    companion object {
        private const val TAG = "DebugServer"

        /**
         * [T-android-debugserver-auth] Remote-connection auth decision, kept
         * pure for unit testing. Loopback connections (adb forward — the
         * developer's own machine over USB) stay token-free so the local
         * tooling keeps working unchanged; any NON-loopback (LAN) connection
         * must present the device token. Rationale: Android debug builds are
         * never distributed (release channel ships assembleRelease without
         * this server), but the dev workflow leaves the device reachable on
         * the LAN for remote-worker e2e — an unauthenticated 0.0.0.0 RPC
         * surface there can read logs/files and burn API quota. The iOS
         * protocol-v1 encrypted envelope (358e3ded) is deliberately NOT
         * ported: with no distribution surface the token gate is the
         * proportionate hardening (evaluated in the A8 batch report).
         */
        fun isAuthorized(isLoopback: Boolean, providedToken: String?, expectedToken: String): Boolean {
            if (isLoopback) return true
            if (expectedToken.isEmpty()) return false
            val provided = providedToken ?: return false
            if (provided.length != expectedToken.length) return false
            // Constant-time compare — don't leak the token via timing.
            var diff = 0
            for (i in expectedToken.indices) {
                diff = diff or (provided[i].code xor expectedToken[i].code)
            }
            return diff == 0
        }
    }

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val rpcHandler = DebugRPCHandler(context)

    /**
     * [T-android-debugserver-auth] Per-install token required from
     * non-loopback clients. Generated once, persisted in filesDir so the
     * developer can read it with:
     *   adb shell run-as com.openminis.app cat files/debug_server_token
     * Also logged at startup (logcat is adb-only — not readable remotely).
     */
    private val authToken: String by lazy {
        val f = java.io.File(context.filesDir, "debug_server_token")
        if (f.exists()) {
            f.readText().trim().ifEmpty { generateToken(f) }
        } else {
            generateToken(f)
        }
    }

    private fun generateToken(f: java.io.File): String {
        val bytes = ByteArray(24)
        java.security.SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        runCatching { f.writeText(token) }
        return token
    }

    @Volatile
    private var stopped = false

    fun start() {
        if (serverSocket != null) return
        stopped = false

        acceptJob = scope.launch {
            try {
                // Listen on all interfaces (0.0.0.0) for network access
                val ss = ServerSocket(port, 10)
                serverSocket = ss
                Log.i(TAG, "Debug server listening on port $port (all interfaces)")
                Log.i(TAG, "Remote (non-loopback) clients must send X-Minis-Token: $authToken")

                while (!stopped) {
                    try {
                        val client = ss.accept()
                        launch { handleConnection(client) }
                    } catch (e: Exception) {
                        if (!stopped) Log.w(TAG, "Accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server on port $port: ${e.message}")
            }
        }
    }

    fun stop() {
        stopped = true
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        Log.i(TAG, "Server stopped")
    }

    private fun handleConnection(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 30_000
                // Byte-level, so the header parser cannot pull body bytes into a
                // char decoder's buffer and so the body can be read as the exact
                // Content-Length byte count (see the body read below).
                val reader = HttpRequestReader(s.getInputStream())
                val writer = PrintWriter(s.getOutputStream(), true)

                // Read HTTP request line
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ", limit = 3)
                if (parts.size < 2) {
                    sendResponse(writer, 400, rpcHandler.errorJSON(-32700, "Parse error"))
                    return
                }
                val method = parts[0]
                // [T-android-debugserver-skill] Path (query stripped) so the
                // unauthenticated GET skill routes can be dispatched.
                val path = parts.getOrNull(1)?.substringBefore('?') ?: "/"

                // Handle CORS preflight
                if (method == "OPTIONS") {
                    sendCorsPreflightResponse(writer)
                    return
                }

                // Read headers
                var contentLength = 0
                var providedToken: String? = null
                var accept = ""
                var isBrowserCrossOriginRequest = false
                var headerCount = 0
                while (headerCount < 128) {
                    val headerLine = reader.readLine() ?: break
                    if (headerLine.isEmpty()) break
                    headerCount++
                    val lower = headerLine.lowercase()
                    if (lower.startsWith("content-length:")) {
                        contentLength = headerLine.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                    if (lower.startsWith("accept:")) {
                        accept = lower.substringAfter(":").trim()
                    }
                    if (lower.startsWith("origin:") || lower.startsWith("sec-fetch-site:")) {
                        isBrowserCrossOriginRequest = true
                    }
                    // [T-android-debugserver-auth] Token via X-Minis-Token or
                    // Authorization: Bearer — either spelling accepted.
                    if (lower.startsWith("x-minis-token:")) {
                        providedToken = headerLine.substringAfter(":").trim()
                    }
                    if (lower.startsWith("authorization:")) {
                        val v = headerLine.substringAfter(":").trim()
                        if (v.lowercase().startsWith("bearer ")) {
                            providedToken = v.substring(7).trim()
                        }
                    }
                }

                // [T-android-debugserver-auth] Gate BEFORE any RPC dispatch:
                // loopback (adb forward) is exempt for non-browser CLI tools;
                // LAN clients and browser-originated requests ALWAYS require the token
                // to prevent drive-by localhost CSRF from malicious web pages.
                val isLoopback = s.inetAddress?.isLoopbackAddress == true
                val allowLoopbackBypass = isLoopback && !isBrowserCrossOriginRequest
                if (!isAuthorized(allowLoopbackBypass, providedToken, authToken)) {
                    Log.w(TAG, "401 unauthorized ${if (isLoopback) "loopback" else s.inetAddress?.hostAddress ?: "?"} (missing/wrong token)")
                    sendResponse(writer, 401, rpcHandler.errorJSON(-32000, "Unauthorized — send X-Minis-Token (see `adb shell run-as com.openminis.app cat files/debug_server_token`)"))
                    return
                }

                // [T-android-debugserver-skill] Self-serve bootstrap routes,
                // mirroring iOS: a client that only has curl can pull the manual
                // and a working reference client straight off the device.
                //
                // Deliberately placed AFTER the auth gate above: unlike iOS
                // (whose /skill is unauthenticated because every RPC is still
                // sealed by the v1 envelope), Android's RPCs are plaintext, so
                // the token remains the only barrier for LAN clients and must not
                // be bypassed. Over `adb forward` (loopback) it's token-free,
                // which is the path the tooling actually uses.
                if (method == "GET") {
                    val wantsHuman = accept.contains("text/html") ||
                        accept.contains("text/markdown") ||
                        accept.contains("text/plain")
                    when (path) {
                        "/schema" -> {
                            sendResponse(writer, 200, schemaJSON())
                            return
                        }
                        "/", "/skill", "/skill/" -> {
                            // A bare `GET /` from a tool (no human Accept) keeps
                            // returning the machine schema, same contract as iOS.
                            if (path == "/" && !wantsHuman) {
                                sendResponse(writer, 200, schemaJSON())
                            } else {
                                sendSkill(writer, wantsHuman)
                            }
                            return
                        }
                        "/skill/examples/python", "/skill/examples/minis_rpc_android.py" -> {
                            sendSkillAsset(writer, "examples/minis_rpc_android.py", "text/x-python; charset=utf-8")
                            return
                        }
                        "/skill/examples/curl", "/skill/examples/curl.md" -> {
                            sendSkillAsset(writer, "examples/curl.md", "text/markdown; charset=utf-8")
                            return
                        }
                    }
                }

                if (method != "POST") {
                    sendResponse(writer, 405, rpcHandler.errorJSON(-32600, "Only POST accepted"))
                    return
                }

                if (contentLength <= 0) {
                    sendResponse(writer, 400, rpcHandler.errorJSON(-32700, "Empty body"))
                    return
                }
                if (contentLength > 32 * 1024 * 1024) {
                    sendResponse(writer, 400, rpcHandler.errorJSON(-32600, "Request body too large"))
                    return
                }

                // Read body.
                //
                // Content-Length counts BYTES, so the loop must too. Reading it
                // as CharArray off the decoding reader counted CHARACTERS, and
                // any multi-byte UTF-8 in the body made the two disagree: a
                // 6-byte "你好" decodes to 2 chars, so the loop kept waiting for
                // 4 more that were never coming and the request stalled until
                // one side timed out. Callers saw non-ASCII requests hang while
                // the identical \u-escaped (all-ASCII) body answered in ~25ms.
                val body = ByteArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val n = reader.read(body, totalRead, contentLength - totalRead)
                    if (n < 0) break
                    totalRead += n
                }
                val jsonBody = String(body, 0, totalRead, Charsets.UTF_8)

                val responseJSON = runBlocking {
                    rpcHandler.handle(jsonBody)
                }

                sendResponse(writer, 200, responseJSON)
            } catch (e: Exception) {
                Log.w(TAG, "Connection error: ${e.message}")
            }
        }
    }

    /// [T-android-debugserver-skill] Capability descriptor. Mirrors the iOS
    /// shape so a shared client can detect the platform: `auth` reports the
    /// Android scheme ("token-lan" — plaintext payloads, token only for
    /// non-loopback) rather than iOS's "v1" envelope, and there is no pair_path.
    private fun schemaJSON(): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        return """{"app":"MinisApp","platform":"android","version":"$version",""" +
            """"rpc":"jsonrpc-2.0","auth":"token-lan","transport":"plaintext",""" +
            """"rpc_path":"/","skill_path":"/skill"}"""
    }

    /// GET / and /skill. Human clients get raw markdown; tools get JSON with the
    /// manual plus every reference client inlined, so one request bootstraps.
    private fun sendSkill(writer: PrintWriter, wantsHuman: Boolean) {
        val skill = readSkillAsset("SKILL.md")
        if (skill == null) {
            sendResponse(writer, 503, """{"error":"skill assets not bundled in this build"}""")
            return
        }
        if (wantsHuman) {
            sendResponse(writer, 200, skill, "text/markdown; charset=utf-8")
            return
        }
        val py = readSkillAsset("examples/minis_rpc_android.py") ?: ""
        val curl = readSkillAsset("examples/curl.md") ?: ""
        val payload = org.json.JSONObject().apply {
            put("skill", skill)
            put("clients", org.json.JSONObject().apply {
                put("python", py)
                put("curl", curl)
            })
            put("client_paths", org.json.JSONObject().apply {
                put("python", "/skill/examples/python")
                put("curl", "/skill/examples/curl")
            })
        }
        sendResponse(writer, 200, payload.toString())
    }

    private fun sendSkillAsset(writer: PrintWriter, assetPath: String, contentType: String) {
        val body = readSkillAsset(assetPath)
        if (body == null) {
            sendResponse(writer, 503, """{"error":"asset not bundled: $assetPath"}""")
            return
        }
        sendResponse(writer, 200, body, contentType)
    }

    /// Reads from the DEBUG-only asset source set staged by
    /// scripts/gen_debug_skill_android.sh. Absent in release (where this whole
    /// server never starts) and absent if the generator didn't run.
    private fun readSkillAsset(relPath: String): String? = try {
        context.assets.open("debug-skill/$relPath").bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    }

    private fun sendResponse(
        writer: PrintWriter,
        statusCode: Int,
        body: String,
        contentType: String = "application/json",
    ) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            405 -> "Method Not Allowed"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        writer.print("HTTP/1.1 $statusCode $statusText\r\n")
        writer.print("Content-Type: $contentType\r\n")
        writer.print("Content-Length: ${bytes.size}\r\n")
        writer.print("Connection: close\r\n")
        writer.print("Access-Control-Allow-Origin: *\r\n")
        writer.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        writer.print("Access-Control-Allow-Headers: Content-Type, X-Minis-Token, Authorization\r\n")
        writer.print("\r\n")
        writer.print(body)
        writer.flush()
    }

    /**
     * Minimal byte-level HTTP request reader.
     *
     * Exists because the header block and the body need DIFFERENT treatment on
     * the same socket: headers are ASCII lines, the body is a Content-Length
     * count of BYTES. A BufferedReader cannot serve both — it decodes to
     * chars (so the body loop counted the wrong unit for any multi-byte UTF-8)
     * and it read ahead, so body bytes could already sit in its buffer and be
     * lost to a byte-level read. This reads one byte at a time for header lines
     * and hands the still-undrained stream to the body read.
     */
    private class HttpRequestReader(private val input: java.io.InputStream) {
        private val buf = java.io.ByteArrayOutputStream(128)

        /** Reads one CRLF/LF-terminated line as ASCII; null at end of stream. */
        fun readLine(): String? {
            buf.reset()
            var sawAny = false
            while (buf.size() < 64 * 1024) {
                val b = input.read()
                if (b < 0) return if (sawAny) buf.toString("UTF-8") else null
                sawAny = true
                if (b == '\n'.code) return buf.toString("UTF-8")
                if (b != '\r'.code) buf.write(b)
            }
            return buf.toString("UTF-8")
        }

        /** Reads exactly [len] BYTES into [dst], returning how many arrived. */
        fun read(dst: ByteArray, off: Int, len: Int): Int = input.read(dst, off, len)
    }

    private fun sendCorsPreflightResponse(writer: PrintWriter) {
        writer.print("HTTP/1.1 204 No Content\r\n")
        writer.print("Access-Control-Allow-Origin: *\r\n")
        writer.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        writer.print("Access-Control-Allow-Headers: Content-Type, X-Minis-Token, Authorization\r\n")
        writer.print("Access-Control-Max-Age: 86400\r\n")
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.flush()
    }
}

// ─── Merged from HeadlessChatRunner.kt ──────────────────────────────────────────────────

/**
 * Headless wrapper around [ChatViewModel] for the debug RPC layer.
 *
 * Issues:
 *  - [ChatViewModel] is bound to a [ViewModelStore] (so `viewModelScope` works
 *    and the streaming Job survives across collectors). The UI binds it to a
 *    NavBackStackEntry; we bind it to a process-scoped store so RPC-driven
 *    sessions survive across calls.
 *  - The runner caches one VM per sessionId so a follow-up `chat.prompt` on
 *    the same id reuses the same `viewModelScope` (no double-starts).
 *
 * Concurrency: [ChatViewModel.sendMessage] auto-enqueues if `_isStreaming` is
 * true, but we still serialize per-session via the cache: a second `wait=true`
 * on the same session can collect the same `isStreaming` Flow.
 */
internal object HeadlessChatRunner {

    /**
     * A cached provider together with the store it was built on, so the cache
     * can be checked against [ChatViewModelStore] before being trusted.
     */
    private class Cached(val store: ViewModelStore, val provider: ViewModelProvider)

    /** sessionId → provider that owns its single ChatViewModel. */
    private val providers = mutableMapOf<String, Cached>()

    private fun app(context: Context): MinisApp =
        context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")

    /**
     * [T-android-vm-evict-orphan] The cached provider for [sessionId], only
     * while its store is still the one [ChatViewModelStore] holds.
     *
     * Eviction (LRU trim, memory pressure) clears a store without telling
     * this runner — only session delete calls [forget]. A provider kept past
     * that point would build a fresh ChatViewModel inside the cleared store,
     * which the shared cache no longer tracks: never evicted, and a second
     * instance beside the one ChatScreen binds to. Drop the entry instead so
     * the next lookup goes back through `ownerFor` and shares the live store.
     */
    @Synchronized
    private fun liveCached(sessionId: String): Cached? {
        val cached = providers[sessionId] ?: return null
        if (ChatViewModelStore.isLiveStore(sessionId, cached.store)) return cached
        providers.remove(sessionId)
        return null
    }

    @Synchronized
    private fun providerFor(
        context: Context,
        sessionId: String,
        kind: ChatViewModelStore.PoolKind = ChatViewModelStore.PoolKind.NORMAL,
    ): ViewModelProvider {
        liveCached(sessionId)?.let { return it.provider }
        val app = app(context)
        // Share the process-wide ChatViewModelStore so the in-flight VM (with
        // its live streamJob + _isStreaming) is the same instance the UI's
        // ChatScreen will bind to when the user opens this session. Using a
        // private ViewModelStore here split headless and UI into two VMs, so
        // "run now" started streaming on the headless VM while the UI's VM
        // saw only a static snapshot — no thinking indicator, no live text.
        val owner = ChatViewModelStore.ownerFor(sessionId, kind)
        val provider = ViewModelProvider(
            owner,
            ChatViewModel.factory(
                sessionId = sessionId,
                chatRepository = app.chatRepository,
                providerRepository = app.providerRepository,
                appContext = app.applicationContext,
                memoryRepository = app.memoryRepository,
                skillRepository = app.skillRepository,
                mcpRepository = app.mcpRepository,
            ),
        )
        providers[sessionId] = Cached(owner.viewModelStore, provider)
        return provider
    }

    private fun viewModel(
        context: Context,
        sessionId: String,
        kind: ChatViewModelStore.PoolKind = ChatViewModelStore.PoolKind.NORMAL,
    ): ChatViewModel = providerFor(context, sessionId, kind)[ChatViewModel::class.java]

    /** [T-p1-delegate-task] The process-wide ViewModel for [sessionId] — the
     *  same instance ChatScreen binds to. Used by the helper runner (child
     *  vm) and the helper sheet (read-only mirror). */
    /**
     * [T-android-vm-store-dual-pool] [kind] classifies the session's cache pool
     * on FIRST creation only (ChatViewModelStore.poolKinds is sticky).
     *
     * It defaults to NORMAL because this object is NOT child-only: `prompt()`
     * drives ordinary sessions for the debug RPCs, and ScheduledAgentRunner
     * acquires a PARENT session's view model through here. Only the two call
     * sites that actually construct a sub agent pass CHILD. Tagging inside this
     * function instead was measured to misclassify all 14 plain sessions of the
     * multi_session baseline scenario as CHILD.
     */
    fun viewModelFor(
        context: Context,
        sessionId: String,
        kind: ChatViewModelStore.PoolKind = ChatViewModelStore.PoolKind.NORMAL,
    ): ChatViewModel = viewModel(context, sessionId, kind)

    /**
     * [T-sub-agents-v1] The live view model for [sessionId], or null.
     *
     * Read-only counterpart to [viewModelFor] for callers that must not CREATE
     * one: ViewModelProvider is not thread-safe, and constructing off the main
     * thread can hand back a second instance for the same session, splitting
     * its state. Callers that only want to observe a run that is already going
     * use this.
     */
    fun existingViewModel(sessionId: String): ChatViewModel? =
        com.openminis.app.ui.chat.ChatViewModelStore.existing(sessionId)

    /**
     * Ensure a session exists in the DB before binding a ViewModel. Mirrors
     * the in-app flow that creates a draft session lazily; for RPC-driven
     * automation we materialize it eagerly so subsequent reads can resolve
     * the id.
     */
    suspend fun ensureSession(context: Context, modelId: String? = null): String =
        withContext(Dispatchers.IO) {
            val app = app(context)
            val resolvedModel = modelId
                ?: app.providerRepository.allVisibleEntries().firstOrNull()?.baseModel?.id
                ?: "unknown"
            val s = app.chatRepository.createSession(modelId = resolvedModel, title = null)
            // Mark source so the UI session list shows it came from RPC.
            app.chatRepository.dao.updateSource(s.id, "debug")
            s.id
        }

    /**
     * Apply a model-entry / model-group override to a session before send.
     * - `modelEntryId`: bind to a specific entry (`binding=entry`).
     * - `modelGroupId`: bind to a group (`binding=group`); the picker uses
     *   the group's routing strategy at chat time.
     * Returns the human-friendly `modelName` for the response.
     */
    suspend fun applyModelOverride(
        context: Context,
        sessionId: String,
        modelEntryId: String?,
        modelGroupId: String?,
    ): String? = withContext(Dispatchers.IO) {
        if (modelEntryId != null && modelGroupId != null) {
            throw RPCException(-32602, "modelEntryId and modelGroupId are mutually exclusive")
        }
        val app = app(context)
        val cfg = app.providerRepository.config.value
        // No explicit model → bind to the user's default primary group (the
        // `isDefault: true` group in provider.groups.list), matching the in-app
        // "new chat, no model picked" path (ChatViewModel priority-3 fallback on
        // providerRepository.defaultPrimaryGroupId). Without this the session
        // kept whatever model ensureSession seeded — the first visible provider
        // entry (e.g. OpenRouter's aion-labs/aion-3.0-mini) — not the default group.
        val resolvedGroupId = modelGroupId ?: run {
            if (modelEntryId != null) return@run null
            cfg.defaultPrimaryGroupId?.takeIf { gid -> cfg.modelGroups.any { it.id == gid } }
        }
        if (modelEntryId == null && resolvedGroupId == null) return@withContext null
        if (modelEntryId != null) {
            val entry = cfg.modelEntries.firstOrNull { it.id == modelEntryId }
                ?: throw RPCException(-32602, "Entry not found: $modelEntryId")
            val instance = cfg.instances.firstOrNull { it.id == entry.providerInstanceId }
                ?: throw RPCException(-32602, "Provider instance for entry not found")
            if (!instance.isEnabled) throw RPCException(-32602, "Provider instance is disabled")
            // The binding column stores a JSON object the VM parses in
            // restoreFromBinding (ChatViewModel: {"type":"entry","entryId":…}).
            // Writing the bare literal "entry" left restoreFromBinding unable to
            // resolve the entry, so activeEntryId never flipped non-null, the
            // headless provider-resolve wait timed out, and sendMessage
            // early-returned with no LLM request — RPC-driven sessions produced
            // a lone user message and no assistant turn.
            val binding = """{"type":"entry","entryId":"${entry.id}"}"""
            app.chatRepository.updateSessionBinding(sessionId, binding, entry.baseModel.id)
            return@withContext entry.model.displayName
        }
        // modelGroupId (explicit) or the default primary group (implicit fallback)
        val group = cfg.modelGroups.firstOrNull { it.id == resolvedGroupId }
            ?: throw RPCException(-32602, "Group not found: $resolvedGroupId")
        val firstMemberId = group.memberEntryIds.firstOrNull()
        val firstMember = firstMemberId?.let { mid -> cfg.modelEntries.firstOrNull { it.id == mid } }
        val resolvedModelId = firstMember?.baseModel?.id ?: ""
        // Same JSON-object shape the VM expects for a group binding.
        val groupBinding = """{"type":"group","groupId":"${group.id}"}"""
        app.chatRepository.updateSessionBinding(sessionId, groupBinding, resolvedModelId)
        return@withContext group.name
    }

    /**
     * Send a prompt into [sessionId]. When `wait` is true, suspend until
     * `isStreaming` flips back to false (or `timeoutMs` elapses) and return
     * the concatenated text of the last assistant message; otherwise return
     * immediately with `responseText=null`.
     */
    suspend fun prompt(
        context: Context,
        sessionId: String,
        text: String,
        attachments: List<InputAttachment> = emptyList(),
        thinkingLevel: ThinkingLevel? = null,
        wait: Boolean,
        timeoutMs: Long,
        // [T-scheduled-tool-prefill] Tool calls to execute as the first turn
        // of the loop this prompt starts, before any model request. Empty =
        // an ordinary prompt.
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
    ): PromptResult = ChatViewModelStore.holdingForSend(sessionId) {
        withContext(Dispatchers.Main) {
            val vm = viewModel(context, sessionId)
            // Apply per-call thinking override BEFORE sendMessage so streamMessage
            // picks up the new level. Caller passes null to keep the VM's existing
            // setting (default OFF for a fresh VM, last user-set value otherwise).
            //
            // ChatViewModel.setThinkingLevel silently no-ops when the active model
            // hasn't resolved yet (currentModelSupportsReasoning checks
            // `currentModel?.supportsReasoning == true`, and `currentModel` is
            // populated on a viewModelScope coroutine). For a freshly-created VM
            // bound to a new session, the resolver may not have run by the time we
            // reach this line — wait until activeEntryId flips non-null so the
            // override actually applies.
            if (thinkingLevel != null) {
                // Wait off Main: ChatViewModel's init coroutine runs on viewModelScope
                // (Main.immediate), and Flow.first() suspends the calling dispatcher
                // until the value arrives. If we wait on Main here we deadlock — the
                // VM's loadSession() never gets to populate _activeEntryId. Hop to
                // Default for the wait, then come back to Main for setThinkingLevel.
                // setThinkingLevel itself silently no-ops on a model that doesn't
                // support reasoning — that gating is intentional UI parity, the
                // 3 s ceiling here just keeps us from blocking forever if the VM
                // never resolves a model (no provider configured, etc.).
                withContext(Dispatchers.Default) {
                    withTimeoutOrNull(3000L) {
                        vm.activeEntryId.first { it != null }
                    }
                }
                vm.setThinkingLevel(thinkingLevel)
            }
            // Wait for ChatViewModel.currentProvider to resolve before sendMessage.
            // The VM populates currentProvider on a viewModelScope (Main.immediate)
            // coroutine driven by providerRepository.config + the session's bound
            // entry. activeEntryId flips non-null in the SAME block that assigns
            // currentProvider (see ChatViewModel.kt ~L2030/2230/2265/2558), so we
            // use it as the readiness signal. Without this wait, sendMessage hits
            // its `currentProvider == null` early-return and the RPC returns
            // status:Completed responseText:null — the bug that blocked Step 2 e2e.
            //
            // Hop off Main for the wait: the VM's resolver runs on Main.immediate,
            // so suspending Main here would deadlock the resolver (same pattern as
            // the thinkingLevel wait above).
            val ready = withContext(Dispatchers.Default) {
                withTimeoutOrNull(5000L) {
                    vm.activeEntryId.first { it != null }
                }
            }
            if (ready == null) {
                return@withContext PromptResult(
                    status = "Error",
                    responseText = "no_provider_resolved_in_5s",
                    timedOut = false,
                )
            }
            // [T-android-submit-outcome] Ask the funnel what it did instead of
            // assuming. This used to call sendMessage() (Unit) and report "Running"
            // unconditionally — a queued prompt, a refused prompt, or one parked
            // behind the compact dialog all came back as a success.
            // [T-scheduled-preemptive-insert] Remember where the transcript ended,
            // so the reply to a scheduled fire can be found after it (below).
            val rowsBeforeSubmit = if (com.openminis.app.scheduled.ScheduledTaskMarker.isMarkerText(text)) {
                app(context).chatRepository.dao.loadMessages(sessionId).size
            } else -1
            // [T-headless-own-attachments] The files go with the prompt, not
            // through the composer: staging them there put them in front of the
            // user (or into their next send) whenever the session was busy and
            // the queued prompt went out without them.
            val outcome = vm.submitPrompt(text, prefill, attachments)
            if (outcome is ChatViewModel.SubmitOutcome.Rejected) {
                return@withContext PromptResult(status = "Rejected", responseText = outcome.reason, timedOut = false)
            }
            if (!wait) {
                val status = if (outcome is ChatViewModel.SubmitOutcome.Queued) "Queued" else "Running"
                return@withContext PromptResult(status = status, responseText = null, timedOut = false)
            }

            val finished = withTimeoutOrNull(timeoutMs) {
                if (outcome is ChatViewModel.SubmitOutcome.Compacting) {
                    // Compaction first, then the funnel re-enters send. Wait for it
                    // to finish, then give the follow-on send a moment to claim the
                    // stream before we look for its end.
                    vm.isCompacting.first { !it }
                    withTimeoutOrNull(2_000L) { vm.isStreaming.first { it } }
                }
                // A Queued prompt drains INSIDE the running loop's epilogue, before
                // that loop clears isStreaming — so waiting for the next false
                // transition covers the drained turn as well.
                if (vm.isStreaming.value) {
                    vm.isStreaming.first { !it }
                }
                true
            } ?: false

            // Best-effort: read the last assistant text from the DB so we don't
            // depend on the in-memory UI list (which may not have flushed yet).
            val app = app(context)
            val msgs = app.chatRepository.dao.loadMessages(sessionId)
            val responseText = responseTextFor(text, msgs.map { it.role to it.partsJson }, rowsBeforeSubmit)
            PromptResult(
                status = if (finished) "Completed" else "Timeout",
                responseText = responseText,
                timedOut = !finished,
            )
        }
    }

    suspend fun retry(
        context: Context,
        sessionId: String,
        messageId: String?,
        wait: Boolean,
        timeoutMs: Long,
    ): PromptResult = ChatViewModelStore.holdingForSend(sessionId) {
        withContext(Dispatchers.Main) {
            val app = app(context)
            val vm = viewModel(context, sessionId)
            val targetMsgId = messageId ?: run {
                val msgs = app.chatRepository.dao.loadMessages(sessionId)
                msgs.lastOrNull { it.role == "user" }?.id
                    ?: throw RPCException(-32602, "Session has no user messages")
            }
            // Validate it points at a user message.
            val all = app.chatRepository.dao.loadMessages(sessionId)
            val target = all.firstOrNull { it.id == targetMsgId }
                ?: throw RPCException(-32602, "Message not found in session")
            if (target.role != "user") throw RPCException(-32602, "Target is not a user message")
            val deletedCount = all.size - all.indexOf(target) - 1

            // Same readiness gate as prompt() — retryFromMessage hits the same
            // currentProvider-null early-return if invoked before resolve.
            val ready = withContext(Dispatchers.Default) {
                withTimeoutOrNull(5000L) {
                    vm.activeEntryId.first { it != null }
                }
            }
            if (ready == null) {
                return@withContext PromptResult(
                    status = "Error",
                    responseText = "no_provider_resolved_in_5s",
                    timedOut = false,
                    deletedMessageCount = deletedCount,
                    retriedMessageId = targetMsgId,
                )
            }
            vm.retryFromMessage(targetMsgId)
            if (!wait) {
                return@withContext PromptResult(
                    status = "Retrying",
                    responseText = null,
                    timedOut = false,
                    deletedMessageCount = deletedCount,
                    retriedMessageId = targetMsgId,
                )
            }
            val finished = withTimeoutOrNull(timeoutMs) {
                if (vm.isStreaming.value) {
                    vm.isStreaming.first { !it }
                }
                true
            } ?: false
            val msgs = app.chatRepository.dao.loadMessages(sessionId)
            val lastAssistant = msgs.lastOrNull { it.role == "assistant" }
            val responseText = lastAssistant?.let { extractText(it.partsJson) }
            PromptResult(
                status = if (finished) "Completed" else "Timeout",
                responseText = responseText,
                timedOut = !finished,
                deletedMessageCount = deletedCount,
                retriedMessageId = targetMsgId,
            )
        }
    }

    /**
     * [T-android-rerun-from-tool-block-position] Drive
     * [ChatViewModel.rerunFromToolBlock] headlessly so automation / e2e can
     * exercise the block-boundary re-run (the in-app trigger is a tool-bubble
     * long-press). [assistantMessageId] is the UI assistant bubble id and
     * [blockId] is the tool block's id (== its tool_use id). Returns the same
     * [PromptResult] shape as [retry]; `responseText` is the latest assistant
     * text after the re-run settles (when [wait]).
     */
    suspend fun rerunFromToolBlock(
        context: Context,
        sessionId: String,
        assistantMessageId: String,
        blockId: String,
        wait: Boolean,
        timeoutMs: Long,
    ): PromptResult = withContext(Dispatchers.Main) {
        val app = app(context)
        val vm = viewModel(context, sessionId)
        val ready = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5000L) { vm.activeEntryId.first { it != null } }
        }
        if (ready == null) {
            return@withContext PromptResult(
                status = "Error",
                responseText = "no_provider_resolved_in_5s",
                timedOut = false,
                deletedMessageCount = 0,
                retriedMessageId = assistantMessageId,
            )
        }
        val before = app.chatRepository.dao.loadMessages(sessionId).size
        // The in-memory assistant bubble id is a volatile `assistant_<ts>`
        // runtime id, not the DB row id a harness reads from chat.messages.list.
        // Resolve the live bubble that owns this tool block; fall back to the
        // caller-supplied id (covers a freshly-reloaded session whose bubble id
        // IS the DB row id).
        val liveAssistantId = vm.assistantMessageIdForToolBlock(blockId) ?: assistantMessageId
        val accepted = vm.rerunFromToolBlock(liveAssistantId, blockId)
        if (!accepted) {
            return@withContext PromptResult(
                status = "Error",
                responseText = "rerun_rejected (streaming / not_found / not_tool_block)",
                timedOut = false,
                deletedMessageCount = 0,
                retriedMessageId = assistantMessageId,
            )
        }
        if (!wait) {
            return@withContext PromptResult(
                status = "Rerunning",
                responseText = null,
                timedOut = false,
                deletedMessageCount = 0,
                retriedMessageId = assistantMessageId,
            )
        }
        val finished = withTimeoutOrNull(timeoutMs) {
            if (vm.isStreaming.value) vm.isStreaming.first { !it }
            true
        } ?: false
        val msgs = app.chatRepository.dao.loadMessages(sessionId)
        val lastAssistant = msgs.lastOrNull { it.role == "assistant" }
        PromptResult(
            status = if (finished) "Completed" else "Timeout",
            responseText = lastAssistant?.let { extractText(it.partsJson) },
            timedOut = !finished,
            deletedMessageCount = (before - msgs.size).coerceAtLeast(0),
            retriedMessageId = assistantMessageId,
        )
    }

    /**
     * Trigger [ChatViewModel.runCompactNow] on the cached VM for [sessionId].
     * When [wait] is true, suspend until [ChatViewModel.isCompacting] flips
     * back to false (or [timeoutMs] elapses), then return the resulting
     * `summary` text alongside the timing flag.
     *
     * Behaviour parity with the in-app `/compact` slash command: if no turn
     * is in flight and history is non-empty, the VM emits a system-info
     * marker on completion. We do NOT surface that marker text — callers can
     * read `chat.messages.list` to see it. The `summary` returned here is
     * the freshly written compact_marker `summary` column.
     */
    suspend fun compact(
        context: Context,
        sessionId: String,
        wait: Boolean,
        timeoutMs: Long,
        messageId: String? = null,
        includesBoundary: Boolean = true,
    ): CompactResult = withContext(Dispatchers.Main) {
        val vm = viewModel(context, sessionId)
        // Same readiness wait as prompt(): compactAll() needs currentProvider
        // resolved before it can call provider.sendMessage for the summary.
        val ready = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5000L) {
                vm.activeEntryId.first { it != null }
            }
        }
        if (ready == null) {
            return@withContext CompactResult(
                status = "Error",
                summary = null,
                timedOut = false,
                error = "no_provider_resolved_in_5s",
            )
        }
        if (vm.isStreaming.value) {
            return@withContext CompactResult(
                status = "Error",
                summary = null,
                timedOut = false,
                error = "stream_in_progress",
            )
        }
        if (vm.isCompacting.value) {
            return@withContext CompactResult(
                status = "Error",
                summary = null,
                timedOut = false,
                error = "compact_already_in_progress",
            )
        }
        // Snapshot prior summary so we can detect a no-op (e.g. nothing-to-
        // compact branch appends a system-info but leaves _compactSummary
        // untouched, never flipping _isCompacting true).
        val priorSummary = vm.compactSummary.value
        if (messageId != null) {
            vm.compactBefore(messageId, includesBoundary)
        } else {
            vm.runCompactNow()
        }
        if (!wait) {
            return@withContext CompactResult(
                status = "Running",
                summary = null,
                timedOut = false,
                error = null,
            )
        }
        // Wait for _isCompacting to flip true → false. If runCompactNow
        // early-returned (nothing to compact, no provider, etc.) it never
        // flipped true at all — give it a short grace window then bail.
        val flippedOn = withContext(Dispatchers.Default) {
            withTimeoutOrNull(2000L) {
                if (!vm.isCompacting.value) vm.isCompacting.first { it }
                true
            }
        }
        if (flippedOn != true) {
            return@withContext CompactResult(
                status = "NoOp",
                summary = vm.compactSummary.value,
                timedOut = false,
                error = "compact_skipped_no_change",
            )
        }
        val finished = withContext(Dispatchers.Default) {
            withTimeoutOrNull(timeoutMs) {
                vm.isCompacting.first { !it }
                true
            } ?: false
        }
        val newSummary = vm.compactSummary.value
        val changed = newSummary != priorSummary && !newSummary.isNullOrBlank()
        CompactResult(
            status = when {
                !finished -> "Timeout"
                changed -> "Completed"
                else -> "Completed"   // marker may have written even if string equal
            },
            summary = newSummary,
            timedOut = !finished,
            error = null,
        )
    }

    /**
     * Drive [ChatViewModel.revertCompact] on the cached VM. Returns when the
     * VM has finished the synchronous DB write — revert isn't gated by a
     * coroutine the way compact is.
     */
    suspend fun revertCompact(context: Context, sessionId: String) = withContext(Dispatchers.Main) {
        val vm = viewModel(context, sessionId)
        val ready = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5000L) {
                vm.activeEntryId.first { it != null }
            }
        }
        if (ready == null) throw RPCException(-32000, "Session VM did not resolve a provider within 5s")
        vm.revertCompact()
    }

    suspend fun cancel(context: Context, sessionId: String): Boolean = withContext(Dispatchers.Main) {
        val cached = liveCached(sessionId) ?: return@withContext false
        val vm = cached.provider[ChatViewModel::class.java]
        val wasRunning = vm.isStreaming.value
        if (wasRunning) vm.cancelStream()
        wasRunning
    }

    /**
     * [T-android-thinking-level-arch] Switch the LIVE session's bound model
     * mid-session — exactly what the in-app model picker does, by calling the
     * same [ChatViewModel.selectEntry]. Unlike passing a fresh `modelEntryId` to
     * chat.prompt (which only rewrites the DB binding and is ignored by the
     * already-loaded cached VM), this re-resolves `currentModel`/`currentProvider`
     * on the live VM. selectEntry deliberately leaves the thinking level
     * untouched, so this is the seam for verifying cross-model thinking-level
     * transfer. Returns (resolved model displayName, current thinking level
     * name) so callers can assert the transfer without a second round-trip.
     */
    suspend fun selectModel(context: Context, sessionId: String, entryId: String):
        Pair<String, String> = withContext(Dispatchers.Main) {
        val vm = viewModel(context, sessionId)
        vm.selectEntry(entryId)
        // selectEntry sets currentModel/_modelName synchronously.
        vm.modelName.value to vm.thinkingLevel.value.name
    }

    /** Drop the cached ViewModel for [sessionId] (used after delete). */
    @Synchronized
    fun forget(sessionId: String) {
        providers.remove(sessionId)
    }

    /**
     * [T-scheduled-preemptive-insert] The reply to report for a prompt.
     *
     * Ordinarily the session's last assistant text. A scheduled fire is
     * different: in a busy session it is slipped in between tool calls, and
     * the loop then goes back to the task it was running, so the LAST
     * assistant text is that task's ending, not the answer to the fire (and
     * would land in the completion notification and the run record). For a
     * `<scheduled_task>` prompt, the reply is the first assistant text after
     * that task's own envelope row. Only rows from [fromIndex] on are
     * searched, so a fire that has not been delivered yet cannot pick up the
     * reply to an earlier fire of the same task. [rows] are (role, partsJson)
     * in transcript order.
     */
    internal fun responseTextFor(sentText: String, rows: List<Pair<String, String>>, fromIndex: Int): String? {
        val marker = com.openminis.app.scheduled.ScheduledTaskMarker.parse(sentText)
        if (marker == null || fromIndex < 0) {
            return rows.lastOrNull { it.first == "assistant" }?.let { extractText(it.second) }
        }
        val envelopeIdx = (fromIndex until rows.size).lastOrNull { i ->
            rows[i].first == "user" &&
                extractText(rows[i].second)?.let { com.openminis.app.scheduled.ScheduledTaskMarker.parse(it)?.taskId } == marker.taskId
        } ?: return null
        return (envelopeIdx + 1 until rows.size).asSequence()
            .filter { rows[it].first == "assistant" }
            .mapNotNull { extractText(rows[it].second)?.takeIf { t -> t.isNotBlank() } }
            .firstOrNull()
    }

    internal fun extractText(partsJson: String): String? {
        return try {
            val arr = org.json.JSONArray(partsJson)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("type") == "text") sb.append(o.optString("value", ""))
            }
            sb.toString().ifEmpty { null }
        } catch (_: Exception) { partsJson.ifEmpty { null } }
    }

    /**
     * `status` values: "Running" (loop started, `wait=false`), "Queued" (the
     * session was busy; the prompt runs when its loop ends, `wait=false`),
     * "Completed", "Timeout", "Rejected" (`responseText` carries the
     * snake_case reason — see [ChatViewModel.SubmitOutcome.Rejected]), "Error".
     */
    data class PromptResult(
        val status: String,
        val responseText: String?,
        val timedOut: Boolean,
        val deletedMessageCount: Int = 0,
        val retriedMessageId: String? = null,
    )

    data class CompactResult(
        val status: String,
        val summary: String?,
        val timedOut: Boolean,
        val error: String?,
    )
}
