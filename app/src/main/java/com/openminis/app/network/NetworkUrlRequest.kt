package com.openminis.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import com.openminis.app.logging.AppLogger
import com.openminis.rclone.gomobile.Gomobile
import com.openminis.rclone.gomobile.LogSink
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * [FEAT-261021-16] Settings > Network for call sites written on
 * HttpURLConnection (skill downloads, browser downloads, image viewer,
 * Gemini / Antigravity account calls, the shell's reachability probe).
 *
 * HttpURLConnection cannot carry the local entry's per-tag credentials:
 * Java answers an HTTPS proxy's 407 only through the process-wide
 * java.net.Authenticator, and a Proxy-Authorization header set by the caller
 * would also be sent to the origin server. So:
 *  - no route for [open]'s tag (the unconfigured app): the request is the
 *    same HttpURLConnection the call site used to make - same calls, same
 *    defaults (system proxy selector, no timeout unless the caller set one);
 *  - a route: the request goes through OkHttp via [NetworkHttp.client], with
 *    the same method, headers, body and timeouts, HttpURLConnection's
 *    User-Agent unless the caller set one, and the same error contract
 *    ([Response.body] throws for an HTTP error status).
 */
object NetworkUrlRequest {

    class Response internal constructor(
        val code: Int,
        /** Content-Length, or -1 when unknown. */
        val contentLength: Long,
        private val input: () -> InputStream,
        private val onClose: () -> Unit,
    ) : Closeable {
        /** The body, as HttpURLConnection.getInputStream gives it: an IOException for status >= 400. */
        fun body(): InputStream = input()

        fun text(): String = body().bufferedReader().readText()

        override fun close() = onClose()
    }

    /**
     * HttpURLConnection's own defaults: no connect / read timeout, and no
     * redirect between https and http.
     */
    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followSslRedirects(false)
            .connectTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    /** The routed OkHttp client for [tag], or null when HttpURLConnection is used unchanged. */
    internal fun routedClient(tag: String): OkHttpClient? {
        val ep = NetworkRuntime.endpoint(tag) ?: return null
        if (ep.httpPort == null) return null
        return NetworkHttp.client(tag, baseClient)
    }

    fun open(
        tag: String,
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        connectTimeoutMs: Int? = null,
        readTimeoutMs: Int? = null,
    ): Response {
        val routed = routedClient(tag) ?: return openDirect(url, method, headers, body, connectTimeoutMs, readTimeoutMs)
        val client = if (connectTimeoutMs == null && readTimeoutMs == null) routed else routed.newBuilder().apply {
            connectTimeoutMs?.let { connectTimeout(it.toLong(), TimeUnit.MILLISECONDS) }
            readTimeoutMs?.let { readTimeout(it.toLong(), TimeUnit.MILLISECONDS) }
        }.build()
        val builder = Request.Builder().url(url)
        for ((k, v) in headers) builder.header(k, v)
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            System.getProperty("http.agent")?.takeIf { it.isNotEmpty() }?.let { builder.header("User-Agent", it) }
        }
        val type = (headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
            ?: "application/x-www-form-urlencoded").toMediaTypeOrNull()
        val requestBody = body?.toRequestBody(type) ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody(type) else null
        builder.method(method, requestBody)
        val resp = client.newCall(builder.build()).execute()
        val code = resp.code
        return Response(
            code,
            resp.body?.contentLength() ?: -1L,
            input = {
                if (code >= 400) throw IOException("HTTP $code")
                resp.body?.byteStream() ?: java.io.ByteArrayInputStream(ByteArray(0))
            },
            onClose = { resp.close() },
        )
    }

    /** Exactly what the call sites did before the Network module. */
    private fun openDirect(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        connectTimeoutMs: Int?,
        readTimeoutMs: Int?,
    ): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        if (method != "GET") conn.requestMethod = method
        connectTimeoutMs?.let { conn.connectTimeout = it }
        readTimeoutMs?.let { conn.readTimeout = it }
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.write(body)
        }
        val code = conn.responseCode
        return Response(code, conn.contentLengthLong, input = { conn.inputStream }, onClose = { conn.disconnect() })
    }
}

// ─── Merged from ShellTransparentNet.kt ──────────────────────────────────────────────────

/**
 * [FEAT-261021-11] Transparent forwarding of the sandbox shell's TCP
 * connections - the Android side of iOS's ShellTransparentNet (iSH xnet,
 * FEAT-261020-07).
 *
 * Programs that ignore proxy variables (ssh, nc, database clients, static
 * binaries) connect directly. proot's xnet extension (deps/proot
 * src/extension/xnet, `--xnet=<routes file>`) hands their connect() to Go's
 * transparent listener (nethub/xmap.go) when the destination is in the
 * routes file's hook prefixes; Go routes it for the "shell" tag with the
 * same Decide as everything else.
 *
 * This object keeps the routes file in step with Settings > Network:
 *  - the prefixes come from Go (NetHookRoutes, the same state Decide reads):
 *    the tailnet's prefixes while Tailscale is usable, for every route;
 *    0/0 for an exit node or an upstream proxy route; the device's current
 *    networks and a proxy's address bypass list exempt;
 *  - while forwarding has nothing to do - Restore Direct Connections, no
 *    entry, a direct shell route with no usable tailnet, nothing hooked -
 *    the file is DELETED and the listener stopped, so proot touches nothing
 *    and every connection is direct exactly as before;
 *  - proot itself falls back to direct when the listener does not answer.
 *
 * Synced on every runtime publish, after every apply, on a network change
 * and with the 30 s health check; a sync only writes when the text changed.
 * Nothing runs for an app that never turned the Network module on.
 */
object ShellTransparentNet {

    private const val TAG = "Network"
    const val SHELL_TAG = "shell"

    /** The routes file proot reads (`--xnet=`), set by [init]; null before. */
    @Volatile
    var routesPath: String? = null
        private set

    private lateinit var routesFile: File
    private lateinit var mapDir: File
    /** The sandbox's /etc/resolv.conf (RootfsManager.rootfsDir): the shell's nameservers. */
    private lateinit var guestResolvConf: File
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "xnet-sync").apply { isDaemon = true } }

    @Volatile
    private var lastText: String? = null
    @Volatile
    private var listening = false
    @Volatile
    private var lastOffReason: String? = null

    /** [FEAT-261021-23] The stage 2 part of the routes file now, null while forwarding is off. */
    @Volatile
    private var stage2Now: Stage2? = null

    fun currentStage2(): Stage2? = stage2Now?.takeIf { routesPath != null && routesFile.exists() }

    fun offReason(): String? = lastOffReason

    /** Called from NetworkHub.init: removes what an earlier process left behind. */
    fun init(context: Context) {
        if (routesPath != null) return
        val dir = File(context.filesDir, "net").apply { mkdirs() }
        routesFile = File(dir, "xnet.routes")
        mapDir = File(dir, "xnet-map")
        guestResolvConf = File(context.filesDir, "alpine-rootfs/etc/resolv.conf")
        routesPath = routesFile.absolutePath
        // A file from an earlier process names a listener that is gone.
        if (routesFile.delete()) AppLogger.info(TAG, "[Net][Xparent] removed the routes file of an earlier process")
        mapDir.listFiles()?.forEach { it.delete() }
        NetworkRuntime.addListener { sync("runtime gen=${it.generation}") }
        runCatching {
            context.applicationContext.getSystemService(ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = sync("network change")
                    override fun onLost(network: Network) = sync("network lost")
                })
        }
    }

    /** Recompute the routes file (on a background thread; callers just call it). */
    fun sync(reason: String) {
        if (routesPath == null) return
        worker.execute { runCatching { syncNow(reason) }.onFailure { off("sync failed: ${it.message}") } }
    }

    /** Tests and the debug RPC: wait for queued syncs. */
    fun awaitIdle(timeoutMs: Long = 5000) {
        if (routesPath == null) return
        val f = worker.submit { }
        runCatching { f.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    private fun syncNow(reason: String) {
        val snap = NetworkRuntime.snapshot
        val settings = NetworkHub.settings.value
        val decision = wanted(settings.forceDirect, snap.port != null, snap.route(SHELL_TAG), snap.tailscaleEnabled)
        if (decision != null) {
            off(decision)
            return
        }
        // [FEAT-261021-23] The shell's nameservers go with the snapshot: their
        // port 53 is DNS - answered by the app while the node is up (MagicDNS
        // names resolve), never sent through an exit node as an ordinary
        // destination (that resolver only exists here).
        val resolvers = guestResolvers()
        val hook = TailscaleNode.hookRoutes2(SHELL_TAG, localPrefixes(), resolvers)
        fun list(key: String) = hook.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val hooks = list("hook")
        val exempt = list("exempt")
        if (hooks.isEmpty()) {
            off("nothing to hook (route=${hook.optString("route")}, tailnet=${hook.optBoolean("tailnet")})")
            return
        }
        mapDir.mkdirs()
        val ports = TailscaleNode.xparentStart(mapDir.absolutePath, SHELL_TAG)
        listening = true
        // Q3 (iOS): fail closed only where going out directly would bypass an
        // exit node, and only when the user asked for it.
        val failClosed = settings.exitNodeFailClosed && hook.optString("route") == "tailscale" && hook.optBoolean("everything")
        val stage2 = if (ports.udpPort > 0 && snap.port != null && snap.password.isNotEmpty()) Stage2(
            udpPort = ports.udpPort, socksPort = snap.port, user = SHELL_TAG, password = snap.password,
            udpHook = list("udpHook"), resolvers = list("resolvers"), dns = hook.optBoolean("dnsIntercept"),
            failClosed = failClosed,
        ) else null
        val text = routesText(ports.port4, ports.port6, mapDir.absolutePath, hooks, exempt, stage2)
        if (text == lastText && routesFile.exists()) return
        val tmp = File(routesFile.parentFile, routesFile.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(routesFile)) {
            tmp.delete()
            off("could not write the routes file")
            return
        }
        lastText = text
        lastOffReason = null
        stage2Now = stage2
        AppLogger.info(TAG, "[Net][Xparent] routes: ${hooks.size} hook, ${exempt.size} exempt, " +
            "route=${hook.optString("route")} everything=${hook.optBoolean("everything")}" +
            (stage2?.let { " udp=${it.udpHook.size} resolvers=${it.resolvers.joinToString(",").ifEmpty { "-" }} " +
                "dns=${if (it.dns) "intercept" else "direct"} failClosed=${it.failClosed}" } ?: " udp=off") + " ($reason)")
    }

    /**
     * Why forwarding must be off, or null when Go should be asked for the
     * prefixes. Pure, for the unit test.
     */
    internal fun wanted(forceDirect: Boolean, entryUp: Boolean, shellRoute: NetworkRoute, tailnetUsable: Boolean): String? = when {
        forceDirect -> "Restore Direct Connections"
        !entryUp -> "no local entry"
        shellRoute == NetworkRoute.Direct && !tailnetUsable -> "shell direct and no usable tailnet"
        else -> null
    }

    /** Delete the file first (proot stops at once), then stop the listener. */
    private fun off(reason: String) {
        val removed = routesFile.exists() && routesFile.delete()
        lastText = null
        stage2Now = null
        if (listening) {
            TailscaleNode.xparentStop()
            listening = false
        }
        if (removed || reason != lastOffReason) {
            if (removed || lastOffReason != null) AppLogger.info(TAG, "[Net][Xparent] off: $reason - shell connections go direct")
            lastOffReason = reason
        }
    }

    /**
     * [FEAT-261021-23] Stage 2 of the routes file: UDP and DNS. The password
     * is the entry's own (the shell gets it in its proxy variables anyway);
     * the file is in the app's private storage, outside the guest's rootfs.
     */
    data class Stage2(
        val udpPort: Int,
        val socksPort: Int,
        val user: String,
        val password: String,
        val udpHook: List<String>,
        val resolvers: List<String>,
        val dns: Boolean,
        val failClosed: Boolean,
    )

    /** The routes file proot's xnet extension reads. Pure, for the unit test. */
    internal fun routesText(
        port4: Int, port6: Int, mapDir: String, hook: List<String>, exempt: List<String>, stage2: Stage2? = null,
    ): String =
        buildString {
            append("# MinisApp Settings > Network -> proot xnet (FEAT-261021-11)\n")
            append("port4 ").append(port4).append('\n')
            if (port6 > 0) append("port6 ").append(port6).append('\n')
            append("map ").append(mapDir).append('\n')
            hook.forEach { append("hook ").append(it).append('\n') }
            exempt.forEach { append("exempt ").append(it).append('\n') }
            if (stage2 != null) {
                append("udpport ").append(stage2.udpPort).append('\n')
                append("socks ").append(stage2.socksPort).append('\n')
                append("user ").append(stage2.user).append('\n')
                append("pass ").append(stage2.password).append('\n')
                stage2.udpHook.forEach { append("udphook ").append(it).append('\n') }
                stage2.resolvers.forEach { append("resolver ").append(it).append('\n') }
                append("dns ").append(if (stage2.dns) 1 else 0).append('\n')
                if (stage2.failClosed) append("failclosed 1\n")
            }
        }

    /**
     * The nameservers of the sandbox's /etc/resolv.conf (ContainerDns' auto
     * or custom list; never changed here). Pure parsing in [parseResolvers].
     */
    private fun guestResolvers(): List<String> =
        runCatching { parseResolvers(guestResolvConf.readText()) }.getOrDefault(emptyList())

    internal fun parseResolvers(text: String): List<String> = text.lineSequence()
        .map { it.substringBefore('#').trim().split(Regex("\\s+")) }
        .filter { it.size >= 2 && it[0] == "nameserver" }
        .map { it[1].substringBefore('%') }
        .distinct()
        .toList()

    /** The device's current networks as masked CIDRs (loopback and point-to-point links left out). */
    private fun localPrefixes(): List<String> {
        val all = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        val out = LinkedHashSet<String>()
        for (ni in all) {
            if (runCatching { !ni.isUp || ni.isLoopback || ni.isPointToPoint }.getOrDefault(true)) continue
            for (ia in ni.interfaceAddresses) {
                val a = ia.address ?: continue
                if (a.isLinkLocalAddress || a.isLoopbackAddress) continue
                maskedPrefix(a, ia.networkPrefixLength.toInt())?.let { out += it }
            }
        }
        return out.toList()
    }

    /** "10.1.1.0/24" for 10.1.1.37 with length 24. Pure, for the unit test. */
    internal fun maskedPrefix(a: InetAddress, length: Int): String? {
        val bytes = a.address
        val max = bytes.size * 8
        if (length !in 1..max) return null
        for (i in bytes.indices) {
            val keep = (length - i * 8).coerceIn(0, 8)
            bytes[i] = (bytes[i].toInt() and (0xff shl (8 - keep)) and 0xff).toByte()
        }
        val host = when (a) {
            is Inet4Address, is Inet6Address -> InetAddress.getByAddress(bytes).hostAddress?.substringBefore('%')
            else -> null
        } ?: return null
        return "$host/$length"
    }

    /**
     * The proot option for every shell builder (agent fresh / pooled shells,
     * the terminal, PRootKernel): `--xnet=<routes file>` only while the
     * routes file exists - forwarding is on - and the binary knows the
     * option. Otherwise nothing, so with forwarding off a shell's proot
     * command is exactly what it was before this feature (no syscall is
     * even watched). A terminal or pooled shell started while forwarding was
     * off keeps going direct until it is restarted; agent commands spawn a
     * process each and always follow.
     */
    fun prootArgs(proot: File): List<String> {
        val routes = routesPath ?: return emptyList()
        if (!File(routes).exists()) return emptyList()
        return if (prootSupportsXnet(proot)) listOf("--xnet=$routes") else emptyList()
    }

    @Volatile
    private var prootSupport: Boolean? = null

    /**
     * Whether the proot binary knows `--xnet` (a build with an older proot
     * would reject the option and every shell command with it). Asked once.
     */
    fun prootSupportsXnet(proot: File): Boolean {
        prootSupport?.let { return it }
        val ok = runCatching {
            val p = ProcessBuilder(proot.absolutePath, "--help").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor(3, TimeUnit.SECONDS)
            text.contains("--xnet")
        }.getOrDefault(false)
        prootSupport = ok
        AppLogger.info(TAG, "[Net][Xparent] proot ${if (ok) "supports" else "does NOT support"} --xnet")
        return ok
    }
}

// ─── Merged from TailscaleNode.kt ──────────────────────────────────────────────────

/**
 * Kotlin side of the embedded Tailscale node, mirroring
 * `src/ios/Agent/Network/TailscaleNode.swift`.
 *
 * The node lives in Go (deps/rclone-mobile/tsnet) inside the same gomobile
 * library as rclone: an app can load only one Go runtime. Every entry point
 * returns the JSON envelope `{"ok":true[,"result":…]}` /
 * `{"ok":false,"error":"…"}` the iOS exports use; [call] unwraps it.
 *
 * The Go calls block (Start waits for the control server, Dial for the
 * peer), so callers run them off the main thread.
 */
object TailscaleNode {

    private const val TAG = "Network"

    class NodeException(message: String) : Exception(message)

    /**
     * Login links (login.tailscale.com/a/<token>) are bearer-like: whoever
     * opens one can claim this node, and tsnet prints the link every 5 s
     * while it waits. Same pattern as iOS.
     */
    private val loginLink = Regex("""(login\.tailscale\.com/a/)[A-Za-z0-9]+""")

    fun redact(line: String): String = loginLink.replace(line, "$1<redacted>")

    internal fun isVerbose(line: String): Boolean =
        line.contains("[v1]") || line.contains("[v2]") || line.contains("JSON]")

    @Volatile
    private var loggerInstalled = false

    /**
     * Whether this process ever started the node / the local entry. Nothing
     * stops or polls them otherwise, so an app that never turned the module
     * on never calls into Go from here (see NetworkHub).
     */
    @Volatile
    var nodeTouched = false
        private set

    @Volatile
    var entryTouched = false
        private set

    /** Routes the Go node's log lines into AppLogger as `[Net][TS]`. */
    @Synchronized
    fun installLogger() {
        if (loggerInstalled) return
        Gomobile.tsSetLogger(object : LogSink {
            override fun line(line: String) {
                // [FEAT-261021-05] A proxy / the node failing N times in a row.
                NetworkAlertCenter.ingest(line)
                // The entry's own lines already carry [Net][Entry|Policy|Proxy|Dial].
                val text = if (line.startsWith("[Net][")) redact(line) else "[Net][TS] " + redact(line)
                // tailscale's verbose levels ([v1] / [v2] and its structured
                // "[v\u0000JSON]" lines - dozens per netmap update) go to the
                // Verbose log level only; Info keeps state changes and errors.
                if (isVerbose(line)) AppLogger.debug(TAG, text) else AppLogger.info(TAG, text)
            }
        })
        loggerInstalled = true
    }

    /**
     * The node key and login state (tailscaled.state). noBackupFilesDir: kept
     * out of Android's cloud and device-transfer backups, since a node key
     * restored on another device would impersonate this one.
     */
    fun stateDirectory(context: Context): File = File(context.noBackupFilesDir, "tailscale")

    private fun call(raw: String): Any? {
        val obj = runCatching { JSONObject(raw) }.getOrNull()
            ?: throw NodeException("tsnet returned invalid JSON")
        if (obj.optBoolean("ok")) return obj.opt("result")
        throw NodeException(obj.optString("error").ifEmpty { "tsnet call failed" })
    }

    private fun callObject(raw: String): JSONObject = call(raw) as? JSONObject ?: JSONObject()

    /**
     * Starts the node. `authKey` null/empty = interactive login (the status
     * then carries `authURL`). The key is never logged.
     */
    fun start(context: Context, authKey: String?, hostname: String) {
        installLogger()
        val dir = stateDirectory(context)
        if (!dir.isDirectory && !dir.mkdirs()) throw NodeException("cannot create the Tailscale state directory")
        nodeTouched = true
        TailscalePathMonitor.start(context)
        AppLogger.info(TAG, "[Net][TS] start hostname=$hostname authKey=${if (authKey.isNullOrEmpty()) "none" else "provided"} dir=noBackupFiles/tailscale")
        val t0 = System.nanoTime()
        try {
            call(Gomobile.tsStart(authKey.orEmpty(), hostname, dir.absolutePath))
            AppLogger.info(TAG, "[Net][TS] start ok in ${"%.2f".format((System.nanoTime() - t0) / 1e9)}s")
        } catch (e: NodeException) {
            AppLogger.error(TAG, "[Net][TS] start FAILED: ${redact(e.message.orEmpty())}")
            throw e
        }
    }

    /** Accepts (true) or ignores subnet routes peers advertise. */
    fun setAcceptRoutes(on: Boolean) {
        call(Gomobile.tsSetAcceptRoutes(on))
        AppLogger.info(TAG, "[Net][TS] accept subnet routes = $on")
    }

    /** Uses the peer with Tailscale IP `ip` as exit node ("" = none). */
    fun setExitNode(ip: String) {
        call(Gomobile.tsSetExitNode(ip))
        AppLogger.info(TAG, "[Net][TS] exit node = ${ip.ifEmpty { "none" }}")
    }

    fun stop() {
        call(Gomobile.tsStop())
        AppLogger.info(TAG, "[Net][TS] stopped")
    }

    fun logout() {
        call(Gomobile.tsLogout())
        AppLogger.info(TAG, "[Net][TS] logged out")
    }

    /**
     * running, backendState, authURL, tailscaleIPs, dnsName, tailnet,
     * magicDNSSuffix, peers[], socks, error.
     */
    fun status(): JSONObject = callObject(Gomobile.tsStatus())

    /** Prefixes reachable through the tailnet now, and the MagicDNS suffix. */
    fun routes(): JSONObject = callObject(Gomobile.tsRoutes())

    /** Dials `address` ("host:port") through the tailnet; milliseconds. */
    fun dialTest(address: String, timeoutMs: Int = 8000): Long =
        callObject(Gomobile.tsDialTest(address, timeoutMs.toLong())).optLong("ms", -1)

    // Local SOCKS5 entry (nethub)

    /**
     * Applies the routing configuration (nethub.Config JSON, proxy passwords
     * included, so never logged) and makes sure the entry listens.
     */
    data class Entry(val port: Int, val httpPort: Int?, val password: String)

    fun configureEntry(config: JSONObject): Entry {
        // [FEAT-261021-05] The entry logs through the same sink as the node
        // (tsnet.Logf); with proxies but no Tailscale it was never installed,
        // so neither its [Net] lines nor the [Net][Alert] ones reached the app.
        installLogger()
        entryTouched = true
        val r = callObject(Gomobile.netConfigure(config.toString()))
        val port = r.optInt("port", 0)
        val password = r.optString("password")
        if (port <= 0 || password.isEmpty()) throw NodeException("local entry did not report its port")
        return Entry(port, r.optInt("httpPort", 0).takeIf { it > 0 }, password)
    }

    fun stopEntry() {
        call(Gomobile.netStop())
    }

    // [FEAT-261021-11] Transparent forwarding for proot's xnet extension.

    /** nethub.HookSnapshot for [tag] given the device's networks (CIDRs). */
    fun hookRoutes(tag: String, local: List<String>): JSONObject =
        callObject(Gomobile.netHookRoutes(tag, org.json.JSONArray(local).toString()))

    /**
     * [FEAT-261021-23] hookRoutes plus the stage 2 fields - udpHook,
     * resolvers, dnsIntercept - given the shell's nameservers (iOS
     * MinisNetHookRoutes2).
     */
    fun hookRoutes2(tag: String, local: List<String>, resolvers: List<String>): JSONObject =
        callObject(Gomobile.netHookRoutes2(tag, org.json.JSONArray(local).toString(), org.json.JSONArray(resolvers).toString()))

    data class XparentPorts(val port4: Int, val port6: Int, val udpPort: Int)

    /** Starts (or keeps) the listener; its ports (0 = none). */
    fun xparentStart(mapDir: String, tag: String): XparentPorts {
        installLogger()
        val r = callObject(Gomobile.netXparentStart(mapDir, tag))
        return XparentPorts(r.optInt("port4"), r.optInt("port6"), r.optInt("udpPort"))
    }

    /**
     * [FEAT-261021-23] The shell's UDP and DNS paths checked from inside the
     * app (iOS MinisNetShellProbe): {udp:{ms|error}, dns:[{name,via,rule,rcode,answers,ms,error}]}.
     */
    fun shellProbe(resolver: String, names: List<String>, timeoutMs: Int = 5000): JSONObject =
        callObject(Gomobile.netShellProbe(resolver, org.json.JSONArray(names).toString(), timeoutMs.toLong()))

    fun xparentStop() {
        runCatching { call(Gomobile.netXparentStop()) }
    }

    /** {port4, port6, connections, failures} */
    fun xparentStatus(): JSONObject = callObject(Gomobile.netXparentStatus())

    /** {listening, addr, httpAddr, connections, failures} */
    fun entryStatus(): JSONObject = callObject(Gomobile.netStatus())

    /** {alive, addr, httpAddr, active, connections, failures, goroutines, heapMB} (iOS MinisNetHealth). */
    fun health(): JSONObject = callObject(Gomobile.netHealth())

    /** Connects to the entry as a client would (auth + relay); milliseconds. Throws with stage/class. */
    fun selfTest(timeoutMs: Int = 5000): Long = callObject(Gomobile.netSelfTest(timeoutMs.toLong())).optLong("ms", -1)

    /** iOS MinisNetDiagnose's checks, from Go: {checks:[{name,status,detail,ms}], runtime}. */
    fun goDiagnose(timeoutMs: Int = 8000): JSONObject = callObject(Gomobile.netDiagnose(timeoutMs.toLong()))

    /** Where a connection from `tag` to `host` would go: {kind, route}. */
    fun decide(tag: String, host: String): JSONObject = callObject(Gomobile.netDecide(tag, host))

    /**
     * Dials `address` for `tag` exactly as an entry client would be routed.
     * Returns the raw envelope's fields so a failure keeps its kind/route.
     */
    fun probe(tag: String, address: String, timeoutMs: Int = 8000): JSONObject {
        val obj = JSONObject(Gomobile.netProbe(tag, address, timeoutMs.toLong()))
        return obj.optJSONObject("result") ?: obj
    }

    /** Connects through one upstream proxy to `target`; milliseconds. */
    fun testUpstream(upstream: JSONObject, target: String, timeoutMs: Int = 8000): Long =
        callObject(Gomobile.netTestUpstream(upstream.toString(), target, timeoutMs.toLong())).optLong("ms", -1)
}
