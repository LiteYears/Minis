package com.openminis.app.sandbox

import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import com.openminis.app.logging.AppLogger
import com.openminis.app.terminal.MinisOpenUrlBroker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket

enum class SandboxServerStatus {
    STARTING,
    LIVE,
    STOPPED,
}

data class SandboxServerInstance(
    val port: Int,
    val url: String,
    val title: String,
    val command: String = "",
    val workdir: String = "/var/minis/workspace",
    val sessionId: String = "",
    val pid: Int = 0,
    val status: SandboxServerStatus = SandboxServerStatus.LIVE,
    val startedAtMs: Long = System.currentTimeMillis(),
    val lastCheckedAtMs: Long = System.currentTimeMillis(),
) {
    val hostPortLabel: String
        get() = "127.0.0.1:$port"
}

/**
 * Manages live local web servers ("Artifacts on Steroids" / Interactive Micro-App Canvas)
 * running inside the Alpine PRoot sandbox on `127.0.0.1:<port>`.
 *
 * Capabilities:
 * - Parses OSC 1337 `MinisSandboxServer` markers emitted by `/usr/local/bin/minis-serve`.
 * - Automatically detects newly listening ports on `127.0.0.1` / `0.0.0.0` across
 *   `shell_execute` invocations via `/proc/net/tcp[6]` diffing + command/output URL scanning.
 * - Probes TCP loopback sockets (`127.0.0.1:<port>`) to verify live server readiness.
 * - Reads server stdout/stderr logs directly from the rootfs (`/tmp/minis-server-<port>.log`).
 * - Stops or restarts sandbox servers directly from the Interactive Micro-App Canvas UI.
 */
object SandboxServerManager {
    private const val TAG = "SandboxServerManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _servers = MutableStateFlow<List<SandboxServerInstance>>(emptyList())
    val servers: StateFlow<List<SandboxServerInstance>> = _servers.asStateFlow()

    /**
     * Emits the latest server URL that should be auto-opened in the Interactive
     * Micro-App Canvas sheet.
     */
    private val _pendingAutoOpenServer = MutableStateFlow<SandboxServerInstance?>(null)
    val pendingAutoOpenServer: StateFlow<SandboxServerInstance?> = _pendingAutoOpenServer.asStateFlow()

    /**
     * OSC 1337 marker: ESC ] 1337 ; MinisSandboxServer = <json> (BEL | ESC \)
     */
    private val SERVER_MARKER_REGEX =
        Regex("\u001B]1337;MinisSandboxServer=([^\u0007\u001B]*)(?:\u0007|\u001B\\\\)")

    /**
     * Matches localhost / loopback URLs printed by common web servers:
     * Flask, FastAPI/Uvicorn, Streamlit, Node/Vite/Express, Go, python -m http.server, etc.
     */
    private val LOCAL_URL_REGEX =
        Regex("""https?://(?:127\.0\.0\.1|localhost|0\.0\.0\.0|\[::1?]):(\d{2,5})(/[^\s"'<>)\]]*)?""", RegexOption.IGNORE_CASE)

    /**
     * Matches port numbers in server start commands, e.g. `--port 8000`, `-p 5000`,
     * `http.server 8080`, `:8000`, `minis-serve 8000`.
     */
    private val COMMAND_PORT_REGEX =
        Regex("""(?:--port[=\s]+|-p\s+|http\.server\s+|minis-serve\s+(?:start\s+)?|127\.0\.0\.1:|localhost:|0\.0\.0\.0:)(\d{4,5})\b""", RegexOption.IGNORE_CASE)

    /** Ports reserved or used internally by Android/ADB that shouldn't be treated as user micro-apps. */
    private val IGNORED_PORTS = setOf(5037, 5555, 8600)

    fun consumeAutoOpenServer() {
        _pendingAutoOpenServer.value = null
    }

    /**
     * True if [url] points to a local sandbox server (`127.0.0.1` / `localhost` / `0.0.0.0`).
     */
    fun isLocalSandboxUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        val port = uri.port
        return port in 1..65535 && (host == "127.0.0.1" || host == "localhost" || host == "0.0.0.0" || host == "::1")
    }

    /**
     * Normalize `http://0.0.0.0:<port>/...` or `http://localhost:<port>/...` to
     * `http://127.0.0.1:<port>/...` so Android WebView connects reliably over IPv4 loopback.
     */
    fun normalizeLocalUrl(url: String): String {
        val uri = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return url
        val host = uri.host?.lowercase() ?: return url
        if (host == "0.0.0.0" || host == "localhost" || host == "::1") {
            val port = uri.port
            if (port > 0) {
                val pathAndQuery = buildString {
                    val path = uri.encodedPath
                    append(if (path.isNullOrEmpty()) "/" else path)
                    uri.encodedQuery?.takeIf { it.isNotEmpty() }?.let { append("?").append(it) }
                    uri.encodedFragment?.takeIf { it.isNotEmpty() }?.let { append("#").append(it) }
                }
                return "${uri.scheme ?: "http"}://127.0.0.1:$port$pathAndQuery"
            }
        }
        return url
    }

    fun extractPort(url: String?): Int? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return null
        return uri.port.takeIf { it in 1..65535 }
    }

    fun getServerByPort(port: Int): SandboxServerInstance? =
        _servers.value.firstOrNull { it.port == port }

    fun getServerForUrl(url: String?): SandboxServerInstance? {
        if (!isLocalSandboxUrl(url)) return null
        val port = extractPort(url) ?: return null
        return getServerByPort(port)
    }

    /**
     * Strip `MinisSandboxServer` OSC 1337 markers from [text], updating the registry
     * for any `start` / `open` / `stop` payloads found.
     */
    fun extractAndProcessMarkers(
        text: String,
        sessionId: String,
        commandHint: String = "",
    ): String {
        if (!text.contains("MinisSandboxServer=")) return text
        val matches = SERVER_MARKER_REGEX.findAll(text).toList()
        if (matches.isEmpty()) return text

        for (match in matches) {
            val jsonStr = match.groupValues.getOrNull(1).orEmpty()
            if (jsonStr.isNotEmpty()) {
                handleMarkerJson(jsonStr, sessionId, commandHint)
            }
        }
        return SERVER_MARKER_REGEX.replace(text, "")
    }

    private fun handleMarkerJson(jsonStr: String, sessionId: String, commandHint: String) {
        runCatching {
            val obj = JSONObject(jsonStr)
            val action = obj.optString("action", "start")
            val port = obj.optInt("port", 0)
            if (port !in 1..65535) return

            if (action == "stop") {
                markServerStopped(port)
                return
            }

            val rawUrl = obj.optString("url", "http://127.0.0.1:$port/")
            val normalizedUrl = normalizeLocalUrl(rawUrl)
            val cmd = obj.optString("cmd", commandHint)
            val rawTitle = obj.optString("title", "").trim()
            val title = if (rawTitle.isNotEmpty() && !rawTitle.startsWith("Sandbox App")) {
                rawTitle
            } else {
                inferServerTitle(port, cmd, rawTitle)
            }
            val workdir = obj.optString("workdir", "/var/minis/workspace").ifBlank { "/var/minis/workspace" }
            val pid = obj.optInt("pid", 0)

            val instance = registerOrUpdateServer(
                port = port,
                url = normalizedUrl,
                title = title,
                command = cmd,
                workdir = workdir,
                sessionId = sessionId,
                pid = pid,
                status = SandboxServerStatus.LIVE,
                autoOpen = true,
            )
            AppLogger.info(TAG, "Registered sandbox server via marker: port=$port url=${instance.url} title=${instance.title}")
        }.onFailure { e ->
            AppLogger.warning(TAG, "Failed to parse MinisSandboxServer marker: ${e.message}")
        }
    }

    /**
     * Snapshot currently listening TCP ports from `/proc/net/tcp` and `/proc/net/tcp6`.
     * Because PRoot shares the host network namespace, any server listening inside
     * the Alpine sandbox appears here with state `0A` (TCP_LISTEN).
     */
    fun snapshotListeningPorts(): Set<Int> {
        val result = mutableSetOf<Int>()
        for (path in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            runCatching {
                val file = File(path)
                if (!file.canRead()) return@runCatching
                file.useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("sl")) continue
                        val parts = trimmed.split(Regex("\\s+"))
                        if (parts.size < 4) continue
                        val state = parts[3].uppercase()
                        if (state != "0A") continue // 0A = TCP_LISTEN
                        val localAddr = parts[1]
                        val portHex = localAddr.substringAfterLast(':', "")
                        val port = portHex.toIntOrNull(16) ?: continue
                        if (port in 1024..65535 && port !in IGNORED_PORTS) {
                            result.add(port)
                        }
                    }
                }
            }
        }
        return result
    }

    /**
     * Fast loopback TCP connect check on `127.0.0.1:[port]`.
     */
    fun probePort(port: Int, timeoutMs: Int = 250): Boolean {
        if (port !in 1..65535) return false
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Inspect a completed (or streaming) `shell_execute` command and its output
     * to automatically discover any new sandbox web server started by the AI,
     * even if the AI started it via raw `python3`, `flask`, `uvicorn`, `node`,
     * `streamlit`, or `nohup`/`setsid` without calling `minis-serve`.
     */
    suspend fun detectAfterCommand(
        context: Context,
        sessionId: String,
        command: String,
        output: String,
        beforePorts: Set<Int>,
    ) = withContext(Dispatchers.IO) {
        // 1. Sync any /tmp/minis-server-*.json files written inside the rootfs
        syncRootfsServerMeta(context, sessionId)

        // 2. Collect candidate ports from:
        //    a) Newly bound listening ports in /proc/net/tcp[6]
        //    b) Local URLs printed in output (e.g., "Running on http://127.0.0.1:5000")
        //    c) Port flags in the command itself (e.g., "--port 8000", "http.server 8080")
        val afterPorts = snapshotListeningPorts()
        val newlyListeningPorts = (afterPorts - beforePorts).toMutableSet()

        val urlMatches = LOCAL_URL_REGEX.findAll(output).toList()
        val urlByPort = linkedMapOf<Int, String>()
        for (m in urlMatches) {
            val p = m.groupValues.getOrNull(1)?.toIntOrNull() ?: continue
            if (p in 1024..65535 && p !in IGNORED_PORTS) {
                urlByPort.putIfAbsent(p, normalizeLocalUrl(m.value))
            }
        }

        val cmdPorts = COMMAND_PORT_REGEX.findAll(command)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .filter { it in 1024..65535 && it !in IGNORED_PORTS }
            .toSet()

        // Wait briefly (up to 1.2s) if the command looks like a background server
        // start and a candidate port is still binding.
        val allCandidates = (newlyListeningPorts + urlByPort.keys + cmdPorts)
        if (allCandidates.isEmpty()) return@withContext

        val looksLikeServerCommand = command.contains("http.server", ignoreCase = true) ||
            command.contains("flask", ignoreCase = true) ||
            command.contains("uvicorn", ignoreCase = true) ||
            command.contains("fastapi", ignoreCase = true) ||
            command.contains("streamlit", ignoreCase = true) ||
            command.contains("minis-serve", ignoreCase = true) ||
            command.contains("gunicorn", ignoreCase = true) ||
            command.contains("node ", ignoreCase = true) ||
            command.contains("npm ", ignoreCase = true) ||
            command.contains("serve", ignoreCase = true) ||
            urlByPort.isNotEmpty() ||
            newlyListeningPorts.isNotEmpty()

        if (!looksLikeServerCommand) return@withContext

        for (port in allCandidates) {
            // Skip if already registered and live on this exact port within the last 2 seconds
            val existing = getServerByPort(port)
            if (existing != null &&
                existing.status == SandboxServerStatus.LIVE &&
                System.currentTimeMillis() - existing.startedAtMs < 2500L
            ) {
                continue
            }

            var isListening = port in afterPorts || probePort(port, 200)
            if (!isListening && (port in cmdPorts || port in urlByPort.keys)) {
                // Give a newly forked background Python/Node server a brief window to finish binding
                repeat(5) {
                    delay(220)
                    if (probePort(port, 200)) {
                        isListening = true
                        return@repeat
                    }
                }
            }

            if (isListening) {
                val url = urlByPort[port] ?: "http://127.0.0.1:$port/"
                val title = inferServerTitle(port, command, existing?.title.orEmpty())
                val pid = readRootfsPid(context, port) ?: existing?.pid ?: 0
                val instance = registerOrUpdateServer(
                    port = port,
                    url = url,
                    title = title,
                    command = command.take(300),
                    workdir = "/var/minis/workspace",
                    sessionId = sessionId,
                    pid = pid,
                    status = SandboxServerStatus.LIVE,
                    autoOpen = true,
                )
                // Also offer to MinisOpenUrlBroker so ChatScreen auto-opens the Live Preview Canvas!
                MinisOpenUrlBroker.offer(instance.url)
                AppLogger.info(TAG, "Auto-detected live sandbox server on port $port -> ${instance.url}")
            }
        }
    }

    /**
     * Synchronize metadata files written by `/usr/local/bin/minis-serve` in `/tmp/minis-server-*.json`.
     */
    fun syncRootfsServerMeta(context: Context, defaultSessionId: String = "") {
        runCatching {
            val tmpDir = File(RootfsManager.getInstance(context).rootfsDir, "tmp")
            if (!tmpDir.isDirectory) return
            val jsonFiles = tmpDir.listFiles { f ->
                f.name.startsWith("minis-server-") && f.name.endsWith(".json")
            } ?: return
            for (jf in jsonFiles) {
                runCatching {
                    val obj = JSONObject(jf.readText())
                    val port = obj.optInt("port", 0)
                    if (port !in 1..65535) return@runCatching
                    val url = normalizeLocalUrl(obj.optString("url", "http://127.0.0.1:$port/"))
                    val cmd = obj.optString("cmd", "")
                    val rawTitle = obj.optString("title", "")
                    val title = inferServerTitle(port, cmd, rawTitle)
                    val workdir = obj.optString("workdir", "/var/minis/workspace")
                    val pid = readRootfsPid(context, port) ?: obj.optInt("pid", 0)
                    val isLive = probePort(port, 180)
                    val existing = getServerByPort(port)
                    registerOrUpdateServer(
                        port = port,
                        url = url,
                        title = title,
                        command = cmd.ifBlank { existing?.command.orEmpty() },
                        workdir = workdir,
                        sessionId = existing?.sessionId?.ifBlank { defaultSessionId } ?: defaultSessionId,
                        pid = pid,
                        status = if (isLive) SandboxServerStatus.LIVE else SandboxServerStatus.STOPPED,
                        autoOpen = false,
                    )
                }
            }
        }
    }

    private fun readRootfsPid(context: Context, port: Int): Int? {
        return runCatching {
            val pidFile = File(RootfsManager.getInstance(context).rootfsDir, "tmp/minis-server-$port.pid")
            if (pidFile.isFile) pidFile.readText().trim().toIntOrNull() else null
        }.getOrNull()
    }

    fun registerOrUpdateServer(
        port: Int,
        url: String,
        title: String,
        command: String = "",
        workdir: String = "/var/minis/workspace",
        sessionId: String = "",
        pid: Int = 0,
        status: SandboxServerStatus = SandboxServerStatus.LIVE,
        autoOpen: Boolean = false,
    ): SandboxServerInstance {
        val normalizedUrl = normalizeLocalUrl(url)
        val now = System.currentTimeMillis()
        lateinit var resolvedInstance: SandboxServerInstance
        _servers.update { list ->
            val current = list.toMutableList()
            val idx = current.indexOfFirst { it.port == port }
            resolvedInstance = if (idx >= 0) {
                val prev = current[idx]
                prev.copy(
                    url = normalizedUrl.ifBlank { prev.url },
                    title = title.ifBlank { prev.title },
                    command = command.ifBlank { prev.command },
                    workdir = workdir.ifBlank { prev.workdir },
                    sessionId = sessionId.ifBlank { prev.sessionId },
                    pid = if (pid > 0) pid else prev.pid,
                    status = status,
                    lastCheckedAtMs = now,
                ).also { current[idx] = it }
            } else {
                SandboxServerInstance(
                    port = port,
                    url = normalizedUrl,
                    title = title.ifBlank { "Sandbox App (:$port)" },
                    command = command,
                    workdir = workdir,
                    sessionId = sessionId,
                    pid = pid,
                    status = status,
                    startedAtMs = now,
                    lastCheckedAtMs = now,
                ).also { current.add(0, it) }
            }
            current
        }
        if (autoOpen && status != SandboxServerStatus.STOPPED) {
            _pendingAutoOpenServer.value = resolvedInstance
        }
        return resolvedInstance
    }

    /**
     * When a localhost WebView finishes loading and reports a non-empty HTML `<title>`,
     * enrich the server's title so the Live Micro-App Canvas pill displays the real app name.
     */
    fun updatePageTitle(url: String, pageTitle: String) {
        val cleanTitle = pageTitle.trim()
        if (cleanTitle.isEmpty() || cleanTitle.startsWith("http://") || cleanTitle.startsWith("127.0.0.1")) return
        val port = extractPort(url) ?: return
        var found = false
        _servers.update { list ->
            val idx = list.indexOfFirst { it.port == port }
            if (idx >= 0) {
                found = true
                val prev = list[idx]
                if (prev.title != cleanTitle) {
                    list.toMutableList().also { it[idx] = prev.copy(title = cleanTitle) }
                } else {
                    list
                }
            } else {
                list
            }
        }
        if (!found && isLocalSandboxUrl(url)) {
            registerOrUpdateServer(
                port = port,
                url = url,
                title = cleanTitle,
                status = SandboxServerStatus.LIVE,
                autoOpen = false,
            )
        }
    }

    fun markServerStopped(port: Int) {
        val now = System.currentTimeMillis()
        _servers.update { list ->
            val idx = list.indexOfFirst { it.port == port }
            if (idx >= 0) {
                list.toMutableList().also {
                    it[idx] = it[idx].copy(
                        status = SandboxServerStatus.STOPPED,
                        lastCheckedAtMs = now,
                    )
                }
            } else {
                list
            }
        }
    }

    fun removeServer(port: Int) {
        _servers.update { list -> list.filterNot { it.port == port } }
    }

    /**
     * Refresh TCP connectivity state for all registered servers.
     */
    fun refreshStatuses(context: Context? = null) {
        scope.launch {
            context?.let { syncRootfsServerMeta(it) }
            val snapshot = _servers.value
            if (snapshot.isEmpty()) return@launch
            val now = System.currentTimeMillis()
            val statusByPort = snapshot.associate { srv ->
                srv.port to if (probePort(srv.port, 200)) SandboxServerStatus.LIVE else SandboxServerStatus.STOPPED
            }
            _servers.update { list ->
                list.map { srv ->
                    val newStatus = statusByPort[srv.port] ?: srv.status
                    srv.copy(status = newStatus, lastCheckedAtMs = now)
                }
            }
        }
    }

    /**
     * Read stdout/stderr logs for the sandbox server on [port] using a bounded
     * tail read (at most 128 KB) so huge log files never cause OutOfMemoryError.
     */
    suspend fun readServerLogs(context: Context, port: Int, maxLines: Int = 120): String =
        withContext(Dispatchers.IO) {
            val rootfsDir = RootfsManager.getInstance(context).rootfsDir
            val candidates = listOf(
                File(rootfsDir, "tmp/minis-server-$port.log"),
                File(rootfsDir, "tmp/server-$port.log"),
                File(rootfsDir, "tmp/server.log"),
                File(rootfsDir, "tmp/app.log"),
            )
            val logFile = candidates.firstOrNull { it.isFile && it.length() > 0 }
                ?: File(rootfsDir, "tmp/minis-server-$port.log")
            if (!logFile.isFile) {
                return@withContext ""
            }
            runCatching {
                val maxTailBytes = 128 * 1024L
                val length = logFile.length()
                val text = if (length <= maxTailBytes) {
                    logFile.readText(Charsets.UTF_8)
                } else {
                    RandomAccessFile(logFile, "r").use { raf ->
                        val startOffset = length - maxTailBytes
                        raf.seek(startOffset)
                        val buf = ByteArray(maxTailBytes.toInt())
                        val n = raf.read(buf)
                        val decoded = if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
                        val firstNl = decoded.indexOf('\n')
                        if (firstNl in 0 until decoded.length - 1) decoded.substring(firstNl + 1) else decoded
                    }
                }
                text.lines().takeLast(maxLines).joinToString("\n")
            }.getOrDefault("")
        }

    /**
     * Stop a running sandbox server on [port] and clean up its PID/meta files.
     */
    fun stopServer(context: Context, port: Int, removeFromList: Boolean = false) {
        scope.launch {
            val srv = getServerByPort(port)
            val pid = readRootfsPid(context, port) ?: srv?.pid ?: 0
            if (pid > 1) {
                runCatching { Os.kill(-pid, OsConstants.SIGTERM) }
                runCatching { Os.kill(pid, OsConstants.SIGTERM) }
                delay(150)
                runCatching { Os.kill(-pid, OsConstants.SIGKILL) }
                runCatching { Os.kill(pid, OsConstants.SIGKILL) }
            }
            runCatching {
                val rootfsDir = RootfsManager.getInstance(context).rootfsDir
                File(rootfsDir, "tmp/minis-server-$port.pid").delete()
                File(rootfsDir, "tmp/minis-server-$port.json").delete()
            }
            // Also invoke minis-serve stop inside the guest in case the server was started without a pidfile
            val sessionId = srv?.sessionId?.ifBlank { "default" } ?: "default"
            runCatching {
                ExecutionCoordinator.execute(
                    sessionId = sessionId,
                    command = "/usr/local/bin/minis-serve stop $port >/dev/null 2>&1 || true",
                    timeout = 4_000L,
                )
            }
            if (removeFromList) {
                removeServer(port)
            } else {
                markServerStopped(port)
            }
            AppLogger.info(TAG, "Stopped sandbox server on port $port (pid=$pid)")
        }
    }

    /**
     * Restart a sandbox server using its recorded command and working directory.
     */
    fun restartServer(
        context: Context,
        port: Int,
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
        val srv = getServerByPort(port) ?: run {
            onComplete?.invoke(false)
            return
        }
        val cmd = srv.command.trim()
        if (cmd.isEmpty()) {
            onComplete?.invoke(false)
            return
        }
        scope.launch {
            registerOrUpdateServer(
                port = srv.port,
                url = srv.url,
                title = srv.title,
                command = srv.command,
                workdir = srv.workdir,
                sessionId = srv.sessionId,
                status = SandboxServerStatus.STARTING,
            )
            val sessionId = srv.sessionId.ifBlank { "default" }
            val launchCmd = if (cmd.contains("minis-serve")) {
                cmd
            } else {
                val safeTitle = srv.title.replace("'", "'\\''")
                val safeWorkdir = srv.workdir.ifBlank { "/var/minis/workspace" }.replace("'", "'\\''")
                "/usr/local/bin/minis-serve start --port $port --title '$safeTitle' --workdir '$safeWorkdir' -- $cmd"
            }
            runCatching {
                ExecutionCoordinator.execute(
                    sessionId = sessionId,
                    command = launchCmd,
                    timeout = 18_000L,
                )
            }
            val live = probePort(port, 400)
            registerOrUpdateServer(
                port = srv.port,
                url = srv.url,
                title = srv.title,
                command = srv.command,
                workdir = srv.workdir,
                sessionId = srv.sessionId,
                pid = readRootfsPid(context, port) ?: srv.pid,
                status = if (live) SandboxServerStatus.LIVE else SandboxServerStatus.STOPPED,
            )
            withContext(Dispatchers.Main) {
                onComplete?.invoke(live)
            }
        }
    }

    internal fun inferServerTitle(port: Int, command: String, rawTitle: String = ""): String {
        val trimmedTitle = rawTitle.trim()
        if (trimmedTitle.isNotEmpty() && !trimmedTitle.startsWith("Sandbox App")) {
            return trimmedTitle
        }
        val lower = command.lowercase()
        val scriptMatch = Regex("""([a-zA-Z0-9_-]+)\.(?:py|js|ts|go|sh)\b""").find(command)
        val scriptName = scriptMatch?.groupValues?.getOrNull(1)
            ?.replace('_', ' ')
            ?.replace('-', ' ')
            ?.split(' ')
            ?.filter { it.isNotBlank() }
            ?.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        return when {
            lower.contains("streamlit") ->
                if (!scriptName.isNullOrBlank()) "Streamlit · $scriptName (:$port)" else "Streamlit App (:$port)"
            lower.contains("uvicorn") || lower.contains("fastapi") ->
                if (!scriptName.isNullOrBlank()) "FastAPI · $scriptName (:$port)" else "FastAPI Server (:$port)"
            lower.contains("flask") ->
                if (!scriptName.isNullOrBlank()) "Flask · $scriptName (:$port)" else "Flask App (:$port)"
            lower.contains("sqlite") -> "SQLite Dashboard (:$port)"
            lower.contains("http.server") -> "Static Web Server (:$port)"
            lower.contains("node") || lower.contains("npm") || lower.contains("vite") ->
                if (!scriptName.isNullOrBlank()) "Node · $scriptName (:$port)" else "Node.js App (:$port)"
            lower.contains("go run") -> "Go Web App (:$port)"
            !scriptName.isNullOrBlank() -> "$scriptName (:$port)"
            else -> "Micro-App Canvas (:$port)"
        }
    }
}
