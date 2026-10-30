package com.openminis.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import android.webkit.HttpAuthHandler
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.util.EncryptedPrefsFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * [FEAT-261021-05, iOS e720112cf] A "[Net][Alert]" / "[Net][Recovered]" line
 * from the Go entry (deps/rclone-mobile/nethub/exitalert.go): a proxy or the
 * Tailscale node failed several connections in a row, or works again. Same
 * parser as iOS `NetworkAlertEvent`.
 */
data class NetworkAlertEvent(
    val subject: Subject,
    val recovered: Boolean = false,
    val failures: Int = 0,
    val stage: String = "",
    /** nethub failure class: timeout, dns, refused, unreachable, proxy-auth, proxy-rejected, tls, closed, other. */
    val failureClass: String = "",
    /** The underlying error, one line (no secrets: host:port and status only). */
    val detail: String = "",
) {
    sealed class Subject {
        data class Proxy(val id: String) : Subject()
        object Tailscale : Subject()
    }

    companion object {
        fun parse(line: String): NetworkAlertEvent? {
            val alert = line.indexOf("[Net][Alert] ")
            val recoveredAt = line.indexOf("[Net][Recovered] ")
            val (recovered, rest) = when {
                alert >= 0 -> false to line.substring(alert + "[Net][Alert] ".length)
                recoveredAt >= 0 -> true to line.substring(recoveredAt + "[Net][Recovered] ".length)
                else -> return null
            }
            val fields = HashMap<String, String>()
            var detail = ""
            var cursor = rest
            while (cursor.isNotEmpty()) {
                if (cursor.startsWith("err=")) {
                    detail = cursor.removePrefix("err=")
                    break
                }
                val token = cursor.substringBefore(' ')
                cursor = cursor.removePrefix(token).trimStart(' ')
                val eq = token.indexOf('=')
                if (eq > 0) fields[token.substring(0, eq)] = token.substring(eq + 1)
            }
            val subject = when (fields["exit"]) {
                "proxy" -> Subject.Proxy(fields["id"]?.takeIf { it.isNotEmpty() } ?: return null)
                "tailscale" -> Subject.Tailscale
                else -> return null
            }
            return NetworkAlertEvent(
                subject = subject,
                recovered = recovered,
                failures = fields["failures"]?.toIntOrNull() ?: 0,
                stage = fields["stage"].orEmpty(),
                failureClass = fields["class"].orEmpty(),
                detail = detail,
            )
        }
    }
}

/**
 * Shows a banner when a proxy or Tailscale keeps failing, so the user can
 * decide whether to stop using it; tapping it opens that setting. Port of
 * iOS `NetworkAlertCenter`.
 *
 * No banner while the device itself is offline (the network is the problem,
 * not the proxy), for a proxy or node the settings no longer use, or for a
 * subject the user dismissed in the last [SNOOZE_MS]. The latest failure per
 * subject stays in [failing] for the settings page until it recovers.
 */
object NetworkAlertCenter {

    private const val TAG = "Network"
    const val SNOOZE_MS = 30 * 60 * 1000L

    private val _banner = MutableStateFlow<NetworkAlertEvent?>(null)
    val banner: StateFlow<NetworkAlertEvent?> = _banner.asStateFlow()

    private val _failing = MutableStateFlow<Map<NetworkAlertEvent.Subject, NetworkAlertEvent>>(emptyMap())
    val failing: StateFlow<Map<NetworkAlertEvent.Subject, NetworkAlertEvent>> = _failing.asStateFlow()

    /** Set by NetworkHub.init: whether the device has a validated network. Tests replace it. */
    @Volatile
    var isOnline: () -> Boolean = { true }

    /** Tests replace it. */
    @Volatile
    var now: () -> Long = System::currentTimeMillis

    private val snoozedUntil = HashMap<NetworkAlertEvent.Subject, Long>()

    /** Called from the Go log callback (any thread). */
    fun ingest(line: String) {
        if (!line.contains("[Net][Alert]") && !line.contains("[Net][Recovered]")) return
        NetworkAlertEvent.parse(line)?.let { handle(it, NetworkHub.settings.value) }
    }

    @Synchronized
    internal fun handle(e: NetworkAlertEvent, s: NetworkSettings) {
        if (e.recovered) {
            _failing.update { it - e.subject }
            if (_banner.value?.subject == e.subject) _banner.value = null
            AppLogger.info(TAG, "[Net][Alert] ${describe(e.subject)} works again - banner cleared")
            return
        }
        if (!inUse(e.subject, s)) return
        _failing.update { it + (e.subject to e) }
        if (!isOnline()) {
            AppLogger.info(TAG, "[Net][Alert] ${describe(e.subject)} failed ${e.failures}x while the device is offline - no banner")
            return
        }
        if ((snoozedUntil[e.subject] ?: 0L) > now()) {
            AppLogger.info(TAG, "[Net][Alert] ${describe(e.subject)} failed ${e.failures}x - banner dismissed earlier, not shown again yet")
            return
        }
        AppLogger.warning(TAG, "[Net][Alert] ${describe(e.subject)} failed ${e.failures}x in a row " +
            "(stage=${e.stage} class=${e.failureClass}) - showing banner")
        _banner.value = e
    }

    /** The close button: hide it, and keep it hidden for this subject a while. */
    @Synchronized
    fun dismissBanner() {
        val b = _banner.value ?: return
        snoozedUntil[b.subject] = now() + SNOOZE_MS
        _banner.value = null
    }

    /** A tap on the banner: it goes away; the caller opens the setting. */
    @Synchronized
    fun takeBanner(): NetworkAlertEvent? = _banner.value.also { _banner.value = null }

    /** Settings changed: forget subjects they no longer use. */
    @Synchronized
    fun prune(s: NetworkSettings) {
        _failing.update { m -> m.filterKeys { inUse(it, s) } }
        _banner.value?.let { if (!inUse(it.subject, s)) _banner.value = null }
    }

    private fun inUse(subject: NetworkAlertEvent.Subject, s: NetworkSettings): Boolean = when (subject) {
        is NetworkAlertEvent.Subject.Proxy -> !s.forceDirect && s.proxies.any { it.id == subject.id }
        NetworkAlertEvent.Subject.Tailscale -> s.nodeWanted
    }

    private fun describe(subject: NetworkAlertEvent.Subject) = when (subject) {
        is NetworkAlertEvent.Subject.Proxy -> "proxy ${subject.id.take(8)}"
        NetworkAlertEvent.Subject.Tailscale -> "tailscale"
    }

    /** Tests only. */
    @Synchronized
    internal fun resetForTest() {
        _banner.value = null
        _failing.value = emptyMap()
        snoozedUntil.clear()
        isOnline = { true }
        now = System::currentTimeMillis
    }
}

// ─── Merged from NetworkDiagnostics.kt ──────────────────────────────────────────────────

/**
 * "Network Diagnostics" (Settings > Network button and `debug.net.diagnose`):
 * checks each stage a connection goes through - policy -> local entry ->
 * tailnet / upstream proxy / direct -> target - and says, per stage, pass /
 * fail / skipped and why. The output is redacted: no entry password, proxy
 * credential, auth key, login link or token ever appears in it; proxies are
 * named, not addressed.
 *
 * Blocking (dials). Run off the main thread.
 */
object NetworkDiagnostics {

    private const val TAG = "Network"
    const val PROBE_HOST = "www.apple.com"
    private const val PROBE_PORT = 443
    /**
     * Route checks dial a host users rarely put in a bypass list, so a broken
     * proxy shows up on the scopes that use it (www.apple.com is a common
     * bypass entry and would report "direct" instead).
     */
    const val ROUTE_PROBE_HOST = "example.com"
    private const val TIMEOUT_MS = 8000

    enum class Result { PASS, FAIL, WARN, SKIP }

    data class Check(val name: String, val result: Result, val detail: String)

    data class Report(val checks: List<Check>, val at: Long = System.currentTimeMillis()) {
        val failed: Int get() = checks.count { it.result == Result.FAIL }

        fun toText(): String = buildString {
            append("Minis network diagnostics\n")
            for (c in checks) append("[${c.result}] ${c.name}: ${c.detail}\n")
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("failed", failed)
            put("checks", JSONArray().apply {
                for (c in checks) put(JSONObject().put("name", c.name).put("result", c.result.name).put("detail", c.detail))
            })
            put("text", toText())
        }
    }

    fun run(context: Context): Report {
        val checks = mutableListOf<Check>()
        val s = NetworkHub.settings.value
        val rt = NetworkRuntime.snapshot

        // 1. Module / policy
        when {
            s.forceDirect -> checks += Check("Module", Result.PASS, "off (Restore Direct Connections): every connection is direct, settings kept")
            s.isUnconfigured || (!s.needsEntry && !s.nodeWanted) -> checks += Check("Module", Result.PASS, "nothing configured: every connection is direct (unchanged behaviour)")
            else -> checks += Check("Module", Result.PASS, "on: default route=${routeLabel(s, s.defaultRoute)}, tailscale=${s.tailscaleEnabled}, proxies=${s.proxies.size}")
        }

        // 2. OS network
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = cm?.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val iface = net?.let { cm.getLinkProperties(it) }?.interfaceName
        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            checks += Check("Device network", if (validated) Result.PASS else Result.WARN,
                "default interface ${iface ?: "?"}" + (if (validated) "" else " (Android has not validated internet access)") +
                    (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) "; a VPN is active" else ""))
        } else {
            checks += Check("Device network", Result.FAIL, "no network with internet access (offline / airplane mode)")
        }

        // 3. DNS + direct path (system, not through Go)
        val t0 = System.nanoTime()
        val dns = runCatching { InetAddress.getAllByName(PROBE_HOST).first() }
        if (dns.isSuccess) {
            checks += Check("DNS", Result.PASS, "$PROBE_HOST resolved in ${ms(t0)} ms")
            val t1 = System.nanoTime()
            val tcp = runCatching { Socket().use { it.connect(InetSocketAddress(dns.getOrThrow(), PROBE_PORT), TIMEOUT_MS) } }
            checks += if (tcp.isSuccess) Check("Direct connection", Result.PASS, "$PROBE_HOST:$PROBE_PORT in ${ms(t1)} ms")
            else Check("Direct connection", Result.FAIL, "$PROBE_HOST:$PROBE_PORT: ${classify(tcp.exceptionOrNull())}")
        } else {
            checks += Check("DNS", Result.FAIL, "$PROBE_HOST: ${classify(dns.exceptionOrNull())}")
            checks += Check("Direct connection", Result.SKIP, "no DNS")
        }

        // Nothing below runs Go code for an app that never turned the module on.
        if (!s.needsEntry && !s.nodeWanted) {
            checks += Check("Local entry", Result.SKIP, "not needed (no route uses it)")
            checks += Check("Tailscale", Result.SKIP, if (s.forceDirect) "off (Restore Direct Connections)" else "off")
            return finish(checks)
        }

        // 4. Local entry
        val entry = runCatching { TailscaleNode.health() }.getOrNull()
        val listening = entry?.optBoolean("alive") == true
        when {
            !listening -> checks += Check("Local entry", Result.FAIL,
                "not listening" + (NetworkHub.lastError.value?.let { " ($it)" } ?: "") + " -> clients fall back to direct")
            rt.port == null -> checks += Check("Local entry", Result.WARN, "listening, but not published to clients yet")
            else -> {
                val t = runCatching { TailscaleNode.selfTest() }
                checks += if (t.isSuccess) Check("Local entry", Result.PASS,
                    "127.0.0.1:${rt.port} (http ${rt.httpPort ?: "off"}): auth + relay ok in ${t.getOrThrow()} ms; " +
                        "${entry.optLong("connections")} connections, ${entry.optLong("failures")} failed, ${entry.optLong("active")} active")
                else Check("Local entry", Result.FAIL, "self-test: " + classifyText(t.exceptionOrNull()?.message.orEmpty()))
                val httpPort = rt.httpPort
                if (httpPort == null) checks += Check("Local entry (HTTP)", Result.WARN,
                    "HTTP front not listening: model requests, MCP sign-in and the browser go direct")
                else {
                    val h0 = System.nanoTime()
                    val h = runCatching { httpFrontSelfTest(httpPort, rt.password) }
                    checks += if (h.isSuccess) Check("Local entry (HTTP)", Result.PASS,
                        "127.0.0.1:$httpPort: refuses a client without the password (407), tunnels with it, in ${ms(h0)} ms")
                    else Check("Local entry (HTTP)", Result.FAIL, "self-test: " + classifyText(h.exceptionOrNull()?.message.orEmpty()))
                }
            }
        }

        // 5. Tailscale
        var tsRunning = false
        var magicName: String? = null
        if (s.nodeWanted) {
            val st = runCatching { TailscaleStatus.parse(TailscaleNode.status()) }.getOrDefault(TailscaleStatus())
            tsRunning = st.isLoggedIn
            checks += when {
                st.isLoggedIn -> Check("Tailscale", Result.PASS,
                    "connected to ${st.tailnet.ifEmpty { "tailnet" }} as ${st.ips.firstOrNull { !it.contains(':') } ?: "?"}; ${st.peers.count { it.online }}/${st.peers.size} devices online")
                st.needsLogin -> Check("Tailscale", Result.FAIL, "not logged in (sign in under Settings > Network); non-tailnet traffic is unaffected")
                !st.running -> Check("Tailscale", Result.FAIL, "node not running" + (NetworkHub.lastError.value?.let { " ($it)" } ?: ""))
                else -> Check("Tailscale", Result.WARN, "state ${st.backendState.ifEmpty { "starting" }}" + (st.error?.let { ": $it" } ?: ""))
            }
            if (tsRunning) {
                val peer = st.peers.firstOrNull { it.online && it.dnsName.isNotEmpty() }
                if (peer == null) {
                    checks += Check("Tailnet connection", Result.SKIP, "no other device online")
                } else {
                    val name = peer.dnsName
                    magicName = name.trimEnd('.')
                    val d = runCatching { TailscaleNode.decide("default", name) }.getOrNull()
                    checks += Check("MagicDNS", if (d?.optString("kind") == "tailscale") Result.PASS else Result.FAIL,
                        "$name -> ${d?.optString("kind") ?: "?"}")
                    val t2 = System.nanoTime()
                    val dial = runCatching { TailscaleNode.dialTest("$name:22", TIMEOUT_MS) }
                    val err = dial.exceptionOrNull()?.message.orEmpty()
                    checks += when {
                        dial.isSuccess -> Check("Tailnet connection", Result.PASS, "${peer.displayName}:22 in ${ms(t2)} ms")
                        err.contains("refused") -> Check("Tailnet connection", Result.PASS, "${peer.displayName} reachable (port 22 closed) in ${ms(t2)} ms")
                        else -> Check("Tailnet connection", Result.FAIL, "${peer.displayName}: ${classifyText(err)}")
                    }
                }
            }
        } else {
            checks += Check("Tailscale", Result.SKIP, "off")
        }

        // 6. Upstream proxies that some route uses
        val used = (listOf(s.defaultRoute) + s.scopes.values + s.providerRoutes.values)
            .mapNotNull { (NetworkRoute.parse(it) as? NetworkRoute.Proxy)?.id }.toSet()
        for (p in s.proxies) {
            if (p.id !in used) {
                checks += Check("Proxy ${p.displayName}", Result.SKIP, "not used by any route")
                continue
            }
            val r = runCatching { NetworkHub.testProxy(p, NetworkHub.proxyPassword(p.id), "$PROBE_HOST:$PROBE_PORT") }
            checks += if (r.isSuccess) Check("Proxy ${p.displayName}", Result.PASS, "${ProxyServer.kindLabel(p.kind)} -> $PROBE_HOST in ${r.getOrThrow()} ms")
            else Check("Proxy ${p.displayName}", Result.FAIL, "${ProxyServer.kindLabel(p.kind)}: ${classifyText(r.exceptionOrNull()?.message.orEmpty())}")
        }
        for (id in used - s.proxies.map { it.id }.toSet()) {
            checks += Check("Proxy (deleted)", Result.WARN, "a route names a proxy that no longer exists; it connects directly")
        }

        // 7. Where each scope goes, end to end through the entry's own logic
        if (listening) {
            for (tag in listOf("default") + NetworkScope.entries.map { it.tag }) {
                val t3 = System.nanoTime()
                val pr = runCatching { TailscaleNode.probe(tag, "$ROUTE_PROBE_HOST:$PROBE_PORT", TIMEOUT_MS) }.getOrNull()
                val kind = pr?.optString("kind").orEmpty()
                val route = pr?.optString("route").orEmpty()
                val err = pr?.optString("error").orEmpty()
                checks += if (pr != null && err.isEmpty()) Check("Route $tag", Result.PASS, "$route -> $kind, $ROUTE_PROBE_HOST in ${ms(t3)} ms")
                else Check("Route $tag", Result.FAIL, "$route -> $kind: ${classifyText(err)}")
            }
        }
        // [FEAT-261021-18] G5: a WebView without PROXY_OVERRIDE stays direct
        // whatever the Browser route says (WebViewNetworkProxy); say so here
        // too, not only under Settings > Network.
        if (rt.endpoint(NetworkScope.BROWSER.tag) != null && !WebViewNetworkProxy.isSupported()) {
            checks += Check("Browser (WebView)", Result.WARN,
                "this device's WebView cannot use a proxy: the browser connects directly, whatever its route")
        }
        // [FEAT-261021-23] The shell's UDP and DNS (proot's xnet, stage 2).
        checks += shellUdpDnsChecks(listening, magicName)
        // [FEAT-261021-18] What the shell does not route, said here so
        // nobody takes it for working.
        checks += Check("Inbound / ICMP", Result.SKIP,
            "not supported: tailnet devices cannot connect to services in the shell, and ping is not routed")
        return finish(checks)
    }

    /**
     * [FEAT-261021-23] "Shell UDP": a round trip through the entry's UDP
     * relay; "Shell DNS": a MagicDNS name (when the node has a peer) and a
     * public name looked up as the shell's intercepted DNS is answered, with
     * the shell's own nameserver. SKIP while the shell's forwarding is off -
     * its UDP and DNS are then direct, exactly as before.
     */
    private fun shellUdpDnsChecks(entryListening: Boolean, magicName: String?): List<Check> {
        val s2 = ShellTransparentNet.currentStage2()
        if (!entryListening || s2 == null) {
            val why = ShellTransparentNet.offReason() ?: "forwarding is off"
            return listOf(Check("Shell UDP / DNS", Result.SKIP, "$why: the shell's UDP and DNS go direct (unchanged behaviour)"))
        }
        val names = listOfNotNull(magicName, ROUTE_PROBE_HOST)
        val resolver = s2.resolvers.firstOrNull().orEmpty()
        val r = runCatching { TailscaleNode.shellProbe(resolver, names, TIMEOUT_MS) }
        val probe = r.getOrNull() ?: return listOf(Check("Shell UDP", Result.FAIL, classifyText(r.exceptionOrNull()?.message.orEmpty())))
        val out = mutableListOf<Check>()
        val udp = probe.optJSONObject("udp")
        val stats = runCatching { TailscaleNode.xparentStatus() }.getOrNull()
        val counts = stats?.let {
            val m = it.optJSONObject("udp")
            val rl = it.optJSONObject("relay")
            "; connected sockets: ${m?.optLong("opened") ?: 0} sessions, ${m?.optLong("sent") ?: 0} sent / ${m?.optLong("received") ?: 0} received, " +
                "${m?.optLong("unmapped") ?: 0} unmapped; sendto: ${rl?.optLong("opened") ?: 0} associations, ${rl?.optLong("sent") ?: 0} sent"
        }.orEmpty()
        out += if (udp != null && udp.has("ms")) Check("Shell UDP", Result.PASS,
            "relay round trip in ${udp.optLong("ms")} ms; udp hook ${s2.udpHook.size} prefixes$counts")
        else Check("Shell UDP", Result.FAIL, classifyText(udp?.optString("error").orEmpty()))
        if (!s2.dns) {
            out += Check("Shell DNS", Result.SKIP, "not intercepted (the node is down): the shell asks ${resolver.ifEmpty { "its resolver" }} directly, as before")
            return out
        }
        val dns = probe.optJSONArray("dns")
        if (resolver.isEmpty() || dns == null || dns.length() == 0) {
            out += Check("Shell DNS", Result.WARN, "no nameserver in the shell's resolv.conf")
            return out
        }
        val parts = (0 until dns.length()).map { dns.optJSONObject(it) }
        val failed = parts.filter { it.optString("error").isNotEmpty() || it.optInt("answers") == 0 }
        val text = parts.joinToString("; ") {
            "${it.optString("name")} via ${it.optString("via")} (${it.optString("rule")}) " +
                (it.optString("error").ifEmpty { "${it.optString("rcode")}, ${it.optInt("answers")} answers in ${it.optLong("ms")} ms" })
        }
        out += Check("Shell DNS", if (failed.isEmpty()) Result.PASS else Result.FAIL, "resolver $resolver: $text")
        return out
    }

    /**
     * [FEAT-261021-18] The entry's HTTP front checks its own password: a
     * CONNECT without it must get 407 (so another app on this device, which
     * can reach 127.0.0.1, cannot use it), and one with it must tunnel. The
     * target is a throwaway listener of this check on loopback, which every
     * route reaches directly, so an upstream proxy or tailnet problem cannot
     * fail it, and the entry's own ports see no stray connection. The probe
     * tag is "diag".
     */
    private fun httpFrontSelfTest(httpPort: Int, password: String) {
        java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { target ->
            target.soTimeout = 5000
            fun connect(auth: String?): String = Socket().use { sock ->
                sock.connect(InetSocketAddress("127.0.0.1", httpPort), 2000)
                sock.soTimeout = 5000
                val dst = "127.0.0.1:${target.localPort}"
                val req = "CONNECT $dst HTTP/1.1\r\nHost: $dst\r\n" +
                    (auth?.let { "Proxy-Authorization: $it\r\n" } ?: "") + "\r\n"
                sock.getOutputStream().apply { write(req.toByteArray()); flush() }
                sock.getInputStream().bufferedReader().readLine().orEmpty()
            }
            val anonymous = connect(null)
            require(anonymous.contains(" 407")) { "a client without the password was not refused: ${anonymous.take(60)}" }
            val ok = connect(okhttp3.Credentials.basic("diag", password))
            require(ok.contains(" 200")) { "CONNECT with the password failed: ${ok.take(60)}" }
            target.accept().close() // the tunnel really reached the target
        }
    }

    private fun finish(checks: List<Check>): Report {
        val r = Report(checks)
        AppLogger.info(TAG, "[Net][Diag] ${checks.size} checks, ${r.failed} failed: " +
            checks.joinToString("; ") { "${it.name}=${it.result}" })
        return r
    }

    private fun routeLabel(s: NetworkSettings, raw: String): String = when (val r = NetworkRoute.parse(raw)) {
        is NetworkRoute.Proxy -> "proxy:" + (s.proxies.firstOrNull { it.id == r.id }?.displayName ?: "(deleted)")
        else -> r.raw
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    private fun classify(t: Throwable?): String = classifyText((t?.javaClass?.simpleName ?: "") + ": " + (t?.message ?: ""))

    /** Short reason class + the message, as the logs classify it. */
    fun classifyText(msg: String): String {
        val m = msg.lowercase()
        val cls = when {
            "407" in m || "authentication" in m || "auth" in m && "fail" in m -> "auth-failed"
            "unknownhost" in m || "no such host" in m || "lookup" in m || "unable to resolve" in m -> "dns"
            "refused" in m -> "refused"
            "timeout" in m || "timed out" in m || "deadline" in m -> "timeout"
            "unreachable" in m || "no route" in m -> "unreachable"
            "tls" in m || "handshake" in m || "certificate" in m -> "tls"
            "proxy connect" in m || "502" in m || "403" in m -> "proxy-rejected"
            "nostate" in m || "needslogin" in m || "not started" in m -> "tailscale-not-ready"
            else -> "error"
        }
        return "$cls (${TailscaleNode.redact(msg.trim().take(160))})"
    }
}

// ─── Merged from NetworkMonitor.kt ──────────────────────────────────────────────────

/**
 * Monitors network connectivity changes using ConnectivityManager.NetworkCallback.
 * Evicts the OkHttp connection pool on network transitions to prevent stale connections.
 */
class NetworkMonitor {

    enum class NetworkStatus {
        CONNECTED,
        DISCONNECTED
    }

    companion object {
        private const val TAG = "NetworkMonitor"

        /**
         * [T-android-stale-conn-retry-hang] App-wide ConnectionPool shared by
         * every long-lived LLM provider OkHttpClient (OpenAI / Anthropic /
         * Gemini) — OkHttp explicitly supports sharing one pool across
         * clients. Routing them all through this instance is what lets
         * [evictConnectionPool] actually reach provider connections:
         * previously eviction only covered the single client registered via
         * [start], and MinisApp registers none, so eviction was a no-op.
         * Through a local VPN/proxy (e.g. clash at 127.0.0.1:7890) the TCP
         * socket to localhost survives network flaps, so the pool kept
         * handing the dead h2 tunnel to every retry — requests wrote into it
         * and hung forever waiting for response headers.
         */
        val sharedLLMConnectionPool = okhttp3.ConnectionPool(
            5, 5, java.util.concurrent.TimeUnit.MINUTES,
        )
    }

    private val _status = MutableStateFlow(NetworkStatus.DISCONNECTED)
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var okHttpClient: OkHttpClient? = null
    private var appContext: Context? = null

    /**
     * Background scope for DNS-refresh side effects. Kept off the callback
     * thread so ConnectivityManager isn't held up by file I/O.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Registers a network callback to observe connectivity changes.
     * Optionally accepts a shared OkHttpClient whose connection pool will be
     * evicted on network transitions.
     *
     * @param context Application or activity context.
     * @param client Optional shared OkHttpClient for connection pool eviction.
     */
    fun start(context: Context, client: OkHttpClient? = null) {
        okHttpClient = client
        appContext = context.applicationContext
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager

        val cm = connectivityManager ?: run {
            Log.e(TAG, "ConnectivityManager not available")
            return
        }

        // Set initial state
        val activeNetwork = cm.activeNetwork
        val capabilities = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        _status.value = if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
            NetworkStatus.CONNECTED
        } else {
            NetworkStatus.DISCONNECTED
        }
        Log.d(TAG, "Initial network status: ${_status.value}")

        // Mirror iOS NetworkMonitor.swift:23-26 — do an immediate DNS write so
        // resolv.conf is populated before the first NetworkCallback fires. The
        // callback is async and can lag by hundreds of ms on cold start.
        refreshSandboxDns("initial")

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                val previousStatus = _status.value
                _status.value = NetworkStatus.CONNECTED
                if (previousStatus == NetworkStatus.DISCONNECTED) {
                    Log.d(TAG, "Network transition: DISCONNECTED -> CONNECTED")
                    evictConnectionPool()
                }
                // Always refresh sandbox DNS on availability — an interface
                // swap (Wi-Fi → cellular) can fire onAvailable without a
                // prior onLost, and the new interface carries new DNS servers.
                refreshSandboxDns("onAvailable")
            }

            override fun onLost(network: Network) {
                _status.value = NetworkStatus.DISCONNECTED
                Log.d(TAG, "Network transition: CONNECTED -> DISCONNECTED")
                evictConnectionPool()
                // Rewrite resolv.conf even when disconnected so it falls back
                // to 8.8.8.8 / 8.8.4.4 instead of sitting stale with a DNS
                // server that's no longer reachable.
                refreshSandboxDns("onLost")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val hasInternet = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                )
                val newStatus = if (hasInternet) NetworkStatus.CONNECTED else NetworkStatus.DISCONNECTED
                if (newStatus != _status.value) {
                    Log.d(TAG, "Network capabilities changed: ${_status.value} -> $newStatus")
                    _status.value = newStatus
                    evictConnectionPool()
                    refreshSandboxDns("onCapabilitiesChanged")
                }
            }
        }

        networkCallback = callback
        cm.registerNetworkCallback(request, callback)
        Log.d(TAG, "Network monitoring started")
    }

    /**
     * Unregisters the network callback. Should be called during cleanup.
     */
    fun stop() {
        networkCallback?.let { callback ->
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
                Log.d(TAG, "Network monitoring stopped")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Callback was not registered: ${e.message}")
            }
        }
        networkCallback = null
        connectivityManager = null
        okHttpClient = null
        appContext = null
    }

    /**
     * Evicts all idle connections from the OkHttp connection pools
     * to prevent stale connection reuse after a network change.
     * Always evicts [sharedLLMConnectionPool] (all LLM provider clients),
     * plus the optional client registered via [start].
     */
    private fun evictConnectionPool() {
        sharedLLMConnectionPool.evictAll()
        okHttpClient?.connectionPool?.evictAll()
        Log.d(TAG, "OkHttp connection pools evicted (shared LLM pool + registered client)")
    }

    /**
     * Refresh the sandbox rootfs' /etc/resolv.conf from the current system
     * DNS configuration. Runs on IO so we don't block the ConnectivityManager
     * callback thread with file I/O. Safe to call before the rootfs has been
     * extracted — [RootfsManager.refreshDns] no-ops when the rootfs is missing.
     *
     * Mirrors iOS NetworkMonitor.swift:26,60 which calls refreshDns() on every
     * NWPath update so already-running shells pick up the new nameservers the
     * next time they resolve a hostname (musl's getaddrinfo re-reads
     * resolv.conf on each lookup — no cache to invalidate).
     */
    private fun refreshSandboxDns(reason: String) {
        val ctx = appContext ?: return
        scope.launch {
            try {
                RootfsManager.getInstance(ctx).refreshDns()
                Log.d(TAG, "[DNS] sandbox resolv.conf refreshed ($reason)")
            } catch (t: Throwable) {
                Log.w(TAG, "[DNS] refresh failed ($reason): ${t.message}")
            }
        }
    }
}

// ─── Merged from NetworkRuntime.kt ──────────────────────────────────────────────────

/**
 * The local SOCKS5 entry as one client uses it: 127.0.0.1:[port], the
 * client's policy [tag] as username, the per-process [password].
 */
data class SocksEndpoint(
    val port: Int,
    val tag: String,
    val password: String,
    /** The entry's HTTP proxy front (OkHttp, WebView); null if it is not listening. */
    val httpPort: Int? = null,
) {
    val host: String get() = "127.0.0.1"

    /** socks5h://tag:password@127.0.0.1:port (remote DNS), for ALL_PROXY etc. */
    val url: String
        get() = "socks5h://${enc(tag)}:${enc(password)}@$host:$port"

    /**
     * http://tag:password@127.0.0.1:httpPort, for HTTP(S)_PROXY: clients
     * that cannot speak SOCKS (BusyBox wget, apk, Python urllib / pip).
     */
    val httpUrl: String?
        get() = httpPort?.let { "http://${enc(tag)}:${enc(password)}@$host:$it" }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    override fun toString(): String = "SocksEndpoint(127.0.0.1:$port, tag=$tag)"
}

/**
 * A thread-safe snapshot of what clients need from Settings > Network,
 * mirroring iOS `NetworkRuntime`: whether the local entry listens, its
 * credentials, and which route each policy tag resolves to. [NetworkHub]
 * publishes it after every apply; OkHttp clients, the shell, WebView and
 * rclone read it on any thread.
 *
 * The rule that keeps an unconfigured app unchanged lives in [endpoint]:
 * no entry -> null -> the caller builds exactly what it built before the
 * Network module existed.
 */
object NetworkRuntime {

    private const val TAG = "Network"

    data class Snapshot(
        val port: Int? = null,
        /** HTTP proxy front of the same entry (OkHttp, WebView). */
        val httpPort: Int? = null,
        val password: String = "",
        val tailscaleEnabled: Boolean = false,
        val defaultRoute: String = "direct",
        /** tag -> raw route ("default" = follow defaultRoute) */
        val tagRoutes: Map<String, String> = emptyMap(),
        /** Bumped on every change; clients built for an older value are rebuilt. */
        val generation: Int = 0,
        /**
         * [FIX-261025-11] The entry is wanted (a proxy / Tailscale route or
         * Tailscale on) but is not listening: it failed to configure, or died
         * and is being restarted. See [endpoint].
         */
        val entryDown: Boolean = false,
    ) {
        /** The concrete route for [tag] (never FollowDefault). */
        fun route(tag: String): NetworkRoute {
            val r = NetworkRoute.parse(tagRoutes[tag] ?: "default")
            return if (r == NetworkRoute.FollowDefault) NetworkRoute.parse(defaultRoute).let {
                if (it == NetworkRoute.FollowDefault) NetworkRoute.Direct else it
            } else r
        }

        /**
         * The entry is used when the route is not plain direct, or when
         * Tailscale is on (tailnet destinations must be split off whatever
         * the route). Otherwise - and whenever the entry is not listening -
         * null: the client stays exactly as before the Network module.
         */
        fun endpoint(tag: String): SocksEndpoint? {
            // [FIX-261025-11] Fail closed. While the entry is down, a proxy or
            // Tailscale route gets an endpoint nothing listens on, so its
            // requests fail instead of leaving from the device's own address
            // around the proxy / exit node the user chose (privacy, region).
            // A direct route still goes direct, exactly as before the module,
            // so the default setup never notices an entry problem.
            if (entryDown) {
                return if (route(tag) == NetworkRoute.Direct) null
                else SocksEndpoint(BLOCKED_PORT, tag, BLOCKED_PASSWORD, BLOCKED_PORT)
            }
            val p = port ?: return null
            if (password.isEmpty()) return null
            if (route(tag) == NetworkRoute.Direct && !tailscaleEnabled) return null
            return SocksEndpoint(p, tag, password, httpPort)
        }

        override fun toString(): String =
            "Snapshot(gen=$generation, entry=${if (entryDown) "DOWN" else port ?: "off"}, http=${httpPort ?: "off"}, tailscale=$tailscaleEnabled, default=$defaultRoute, tags=${tagRoutes.size})"
    }

    /**
     * [FIX-261025-11] Where a proxy / Tailscale route points while the entry is
     * down: loopback port 1, which nothing on Android listens on, so a
     * connection is refused at once rather than timing out.
     */
    const val BLOCKED_PORT = 1
    internal const val BLOCKED_PASSWORD = "entry-down"

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    private val listeners = mutableListOf<(Snapshot) -> Unit>()

    /** Policy tag of a provider instance (same as iOS). */
    fun providerTag(instanceId: String) = "prov:$instanceId"

    /** Shortcut for clients: the entry to use for [tag], or null for direct. */
    fun endpoint(tag: String): SocksEndpoint? = snapshot.endpoint(tag)

    @Synchronized
    fun publish(next: Snapshot) {
        val cur = snapshot
        val same = next.copy(generation = cur.generation) == cur
        if (same) return
        val n = next.copy(generation = cur.generation + 1)
        snapshot = n
        AppLogger.info(TAG, "[Net][Session] runtime gen=${n.generation} entry=${if (n.entryDown) "DOWN" else n.port ?: "off"} http=${n.httpPort ?: "off"} tailscale=${n.tailscaleEnabled} default=${n.defaultRoute} tags=${n.tagRoutes.size}")
        listeners.toList().forEach { runCatching { it(n) } }
    }

    /** Called after every published change (on the publishing thread). */
    @Synchronized
    fun addListener(l: (Snapshot) -> Unit) {
        listeners += l
    }

    /** Tests only. */
    @Synchronized
    internal fun resetForTest() {
        snapshot = Snapshot()
        listeners.clear()
    }
}

// ─── Merged from NetworkSettings.kt ──────────────────────────────────────────────────

/**
 * Where connections of one kind go. Raw values are what the Go routing core
 * (deps/rclone-mobile/nethub) reads, the same strings as iOS `NetworkRoute`:
 * "direct", "tailscale", "proxy:<id>", and "default" for a scope that
 * follows the default route.
 */
sealed class NetworkRoute {
    object FollowDefault : NetworkRoute()
    object Direct : NetworkRoute()
    object Tailscale : NetworkRoute()
    data class Proxy(val id: String) : NetworkRoute()

    val raw: String
        get() = when (this) {
            FollowDefault -> "default"
            Direct -> "direct"
            Tailscale -> "tailscale"
            is Proxy -> "proxy:$id"
        }

    companion object {
        fun parse(raw: String?): NetworkRoute = when {
            raw == "direct" -> Direct
            raw == "tailscale" -> Tailscale
            raw != null && raw.startsWith("proxy:") -> Proxy(raw.removePrefix("proxy:"))
            else -> FollowDefault
        }
    }
}

/**
 * A user-configured upstream proxy. The password is NOT here: it lives in the
 * Keystore-encrypted store under the proxy's id ([NetworkSecrets]).
 * Field names match iOS `ProxyServer` (Codable), so the JSON is one format.
 */
@Serializable
data class ProxyServer(
    val id: String = UUID.randomUUID().toString().uppercase(),
    val name: String = "",
    /** socks5 | http | https */
    val kind: String = "socks5",
    val host: String = "",
    val port: Int = 1080,
    val username: String = "",
    /** Hosts that skip this proxy: exact host, "*.suffix" / ".suffix", CIDR, IP. */
    val bypass: List<String> = emptyList(),
) {
    val displayName: String get() = name.ifEmpty { "$host:$port" }

    companion object {
        val KINDS = listOf("socks5", "http", "https")

        fun kindLabel(kind: String) = when (kind) {
            "http" -> "HTTP"
            "https" -> "HTTPS"
            else -> "SOCKS5"
        }

        fun defaultPort(kind: String) = when (kind) {
            "http" -> 8080
            "https" -> 443
            else -> 1080
        }
    }
}

/** The scopes the user can route separately; the SOCKS username (tag) is the raw value. */
enum class NetworkScope(val tag: String) {
    SHELL("shell"), BROWSER("browser"), MCP("mcp"), BACKUP("backup");
}

/**
 * Everything in Settings > Network. Same JSON keys and defaults as iOS
 * `NetworkSettings`, so a future shared spec (src/shared/network/) can
 * describe both; every field has a default so older or partial data decodes.
 *
 * [forceDirect] is the "Restore Direct Connections" switch: while true the
 * module is off - no node, no local entry, every client exactly as before
 * the module existed - but the configuration is kept for when it is turned
 * back on.
 */
@Serializable
data class NetworkSettings(
    val tailscaleEnabled: Boolean = false,
    /** Node name in the tailnet; empty = "minis-android". */
    val hostname: String = "",
    val acceptRoutes: Boolean = false,
    /** Tailscale IP of the exit node in use; null = none. */
    val exitNodeIP: String? = null,
    /** Raw route (never "default"). */
    val defaultRoute: String = "direct",
    /** NetworkScope.tag -> raw route ("default" = follow). */
    val scopes: Map<String, String> = emptyMap(),
    val proxies: List<ProxyServer> = emptyList(),
    /**
     * Provider instance id -> raw route ("default" = follow). Device-local on
     * purpose: routes name this device's proxies and tailnet, so they are not
     * part of the synced ProviderInstance record (and adding a column there
     * risks the add-column wipe).
     */
    val providerRoutes: Map<String, String> = emptyMap(),
    val forceDirect: Boolean = false,
    /**
     * [FEAT-261021-23, iOS 45510cce2] Exit node privacy for the shell, both
     * off by default. With an exit node in use: a shell connection or
     * datagram the local entry cannot take fails (ENETUNREACH) instead of
     * leaving directly from the device's own address...
     */
    val exitNodeFailClosed: Boolean = false,
    /**
     * ...and the shell's DNS for non-tailnet names goes through the exit
     * node too, instead of to the device's own resolver (which then sees
     * every name looked up, but keeps resolvers that only work here).
     */
    val exitNodeDNS: Boolean = false,
) {
    fun route(scope: NetworkScope): NetworkRoute = NetworkRoute.parse(scopes[scope.tag] ?: "default")

    fun providerRoute(instanceId: String): NetworkRoute = NetworkRoute.parse(providerRoutes[instanceId] ?: "default")

    /** Policy tag -> raw route, as the Go core and [NetworkRuntime] read it. */
    val tagRoutes: Map<String, String>
        get() = buildMap {
            for (s in NetworkScope.entries) put(s.tag, route(s).raw)
            for ((id, r) in providerRoutes) put(NetworkRuntime.providerTag(id), r)
        }

    /** Whether anything needs the local entry at all. */
    val needsEntry: Boolean
        get() {
            if (forceDirect) return false
            if (tailscaleEnabled) return true
            if (NetworkRoute.parse(defaultRoute) != NetworkRoute.Direct) return true
            return (scopes.values + providerRoutes.values).any {
                val r = NetworkRoute.parse(it)
                r != NetworkRoute.Direct && r != NetworkRoute.FollowDefault
            }
        }

    /** Whether the Tailscale node should run. */
    val nodeWanted: Boolean get() = tailscaleEnabled && !forceDirect

    /** Removes proxy [id]; routes that named it fall back (the Go core would go direct too). */
    fun withoutProxy(id: String): NetworkSettings {
        val r = "proxy:$id"
        return copy(
            proxies = proxies.filterNot { it.id == id },
            defaultRoute = if (defaultRoute == r) "direct" else defaultRoute,
            scopes = scopes.mapValues { (_, v) -> if (v == r) "default" else v },
            providerRoutes = providerRoutes.filterValues { it != r },
        )
    }

    /**
     * [FEAT-261021-05] "Stop Using This Proxy": routes that named proxy [id]
     * go direct (default route) or back to the default (scopes, providers);
     * the proxy itself is kept.
     */
    fun stopUsing(id: String): NetworkSettings {
        val r = "proxy:$id"
        return copy(
            defaultRoute = if (defaultRoute == r) "direct" else defaultRoute,
            scopes = scopes.mapValues { (_, v) -> if (v == r) "default" else v },
            providerRoutes = providerRoutes.filterValues { it != r },
        )
    }

    /** True for a user who never touched Settings > Network (or reset it). */
    val isUnconfigured: Boolean get() = this == NetworkSettings()
}

/** Persistence of [NetworkSettings] (plain prefs; no secrets in it). */
object NetworkSettingsStore {
    private const val PREFS = "minis_network"
    /** Same key as iOS's UserDefaults key. */
    const val KEY = "network.settings.v1"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    fun decode(raw: String?): NetworkSettings =
        raw?.let { runCatching { json.decodeFromString<NetworkSettings>(it) }.getOrNull() } ?: NetworkSettings()

    fun encode(s: NetworkSettings): String = json.encodeToString(s)

    fun load(context: Context): NetworkSettings =
        decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(context: Context, s: NetworkSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, encode(s)).apply()
    }
}

/**
 * Proxy passwords, one entry per proxy id, in a Keystore-backed
 * EncryptedSharedPreferences file (the store every other credential uses).
 * Never logged.
 */
object NetworkSecrets {
    private const val FILE = "minis_network_secrets"

    private fun prefs(context: Context) = EncryptedPrefsFactory.safeCreate(context.applicationContext, FILE)

    fun password(context: Context, proxyId: String): String =
        runCatching { prefs(context).getString("proxy.$proxyId", null) }.getOrNull().orEmpty()

    fun setPassword(context: Context, proxyId: String, password: String) {
        runCatching {
            val e = prefs(context).edit()
            if (password.isEmpty()) e.remove("proxy.$proxyId") else e.putString("proxy.$proxyId", password)
            e.apply()
        }.onFailure { AppLogger.error("Network", "[Net][Proxy] could not store the password for a proxy: ${it.javaClass.simpleName}") }
    }

    fun remove(context: Context, proxyId: String) = setPassword(context, proxyId, "")
}

// ─── Merged from NetworkShellEnvironment.kt ──────────────────────────────────────────────────

/**
 * [T-net-android-phase3] Proxy environment for programs run in the proot
 * shell, following the "Shell" scope in Settings > Network - the counterpart
 * of iOS `NetworkShellEnvironment` (same variables, same values).
 *
 * Empty when the scope's route is direct or the local entry is not
 * listening, so the shell environment is exactly as before. Otherwise
 * ALL_PROXY points at the local SOCKS5 entry with remote DNS (socks5h, so
 * MagicDNS names resolve through the route) for curl, git and Go, and
 * HTTP(S)_PROXY at the entry's HTTP proxy front for the tools that cannot
 * use SOCKS (see [ShellProxyVariables]); no_proxy keeps loopback local.
 * proot shares the host network namespace, so 127.0.0.1 inside the guest is
 * the app's own entry.
 *
 * Read when a shell process is spawned, and [FEAT-261021-10] pushed into
 * every running shell (agent shells and terminals) when the Shell route's
 * variables change - see [watchRuntime].
 */
object NetworkShellEnvironment {

    private const val TAG = "Network"
    const val NO_PROXY = ShellProxyVariables.NO_PROXY

    @Volatile
    private var lastBroadcast: Map<String, String>? = null

    /**
     * [FEAT-261021-10] Calls [onChange] after a runtime publish that changes
     * the shell's variables (a Shell route set or cleared, the entry
     * restarted on a new port, Routing turned off). Running shells then get
     * the new block exported in place - no restart, nothing they run is
     * interrupted. The first publish only records the baseline.
     */
    fun watchRuntime(onChange: (cleared: Set<String>) -> Unit) {
        NetworkRuntime.addListener {
            changedSinceLastBroadcast(proxyVariables())?.let(onChange)
        }
    }

    /**
     * null when unchanged (or the first call); otherwise the keys the last
     * block set that this one does not - e.g. ALL_PROXY when the Shell route
     * goes back to direct, which the system-proxy block does not cover. Only
     * those are cleared, so an ALL_PROXY the user set as an environment
     * variable is never touched by this module.
     */
    @Synchronized
    internal fun changedSinceLastBroadcast(vars: Map<String, String>): Set<String>? {
        val before = lastBroadcast
        lastBroadcast = vars
        if (before == null || before == vars) return null
        return before.keys - vars.keys
    }

    /** Tests only. */
    @Synchronized
    internal fun resetForTest() {
        lastBroadcast = null
        lastLogged = null
    }

    @Volatile
    private var lastLogged: String? = null

    fun proxyVariables(): Map<String, String> {
        val snap = NetworkRuntime.snapshot
        val tag = NetworkScope.SHELL.tag
        val ep = snap.endpoint(tag)
        val route = snap.route(tag)
        val shellVars = ShellProxyVariables.make(ep?.url, ep?.httpUrl, routeIsDirect = route == NetworkRoute.Direct)
        // [FEAT-261021-19, iOS 59362a754 G3] minis-mcp-cli's own route (the
        // "MCP" scope), whatever the shell's: the entry decides per the mcp tag.
        // [FIX-261025-11] While the entry is down, an MCP route through a proxy
        // / Tailscale points minis-mcp-cli at the blocked endpoint (fail
        // closed) instead of leaving MINIS_MCP_PROXY unset, which sent it out
        // directly around the user's proxy. A direct MCP route stays unset.
        val mcpBlocked = if (snap.entryDown) snap.endpoint(NetworkScope.MCP.tag) else null
        val vars = shellVars + if (mcpBlocked != null) {
            ShellProxyVariables.mcp(mcpBlocked.port, mcpBlocked.httpPort, mcpBlocked.password)
        } else {
            ShellProxyVariables.mcp(snap.port, snap.httpPort, snap.password)
        }
        // Variable names and ports only - never a URL (it carries the entry
        // password). Logged when it changes, not per spawn.
        val state = when {
            shellVars.isEmpty() -> "none (route=${route.raw}; shells connect directly)"
            snap.entryDown -> "BLOCKED (entry down; proxy variables point at a closed port) route=${route.raw}"
            ep?.httpPort != null -> "ALL_PROXY -> socks5h://shell:<password>@127.0.0.1:${ep.port}, " +
                "HTTP(S)_PROXY -> http://shell:<password>@127.0.0.1:${ep.httpPort}, NO_PROXY=$NO_PROXY route=${route.raw}"
            else -> "ALL_PROXY -> socks5h://shell:<password>@127.0.0.1:${ep?.port}, NO_PROXY=$NO_PROXY; " +
                "HTTP(S)_PROXY NOT set (the http front is not listening, so wget / apk / pip connect directly) route=${route.raw}"
        }
        val mcpState = when {
            ShellProxyVariables.MCP_PROXY_KEY !in vars -> "MINIS_MCP_PROXY none (entry off: minis-mcp-cli as before)"
            mcpBlocked != null -> "MINIS_MCP_PROXY -> BLOCKED (entry down) route=${snap.route(NetworkScope.MCP.tag).raw}"
            else -> "MINIS_MCP_PROXY -> http://mcp:<password>@127.0.0.1:${snap.httpPort} route=${snap.route(NetworkScope.MCP.tag).raw}"
        }
        if ("$state; $mcpState" != lastLogged) {
            lastLogged = "$state; $mcpState"
            AppLogger.info(TAG, "[Net][Shell] new shells: $state; $mcpState gen=${snap.generation}")
        }
        return vars
    }
}

/**
 * Which proxy variables the shell gets (iOS `ShellProxyVariables`).
 *
 * [FIX-261021-01, iOS b1b1fe2c7] Only when the user routed the shell
 * somewhere (Tailscale or a proxy). With Tailscale on, the entry also exists
 * for a DIRECT route - so OkHttp can split tailnet hosts off - but exporting
 * it to the shell then broke the common tools for every Tailscale user, so a
 * direct shell gets nothing; a tailnet host is still reachable on purpose
 * with `curl --proxy` or by routing the Shell scope.
 *
 * [iOS 62f3577a3] HTTP(S)_PROXY name the entry's HTTP front (http://), not
 * the SOCKS entry: BusyBox wget, Alpine apk (libfetch) and Python urllib /
 * pip / requests cannot use a socks5h:// HTTP(S)_PROXY, and wget's error
 * then printed the URL, entry password included, into the agent's
 * transcript. Without the front they are left unset (those tools connect
 * directly) rather than pointed at SOCKS again.
 */
object ShellProxyVariables {
    const val NO_PROXY = "localhost,127.0.0.1,::1"

    fun make(socksUrl: String?, httpUrl: String?, routeIsDirect: Boolean): Map<String, String> {
        if (routeIsDirect || socksUrl.isNullOrEmpty()) return emptyMap()
        val vars = linkedMapOf(
            "ALL_PROXY" to socksUrl, "all_proxy" to socksUrl,
            "NO_PROXY" to NO_PROXY, "no_proxy" to NO_PROXY,
        )
        if (!httpUrl.isNullOrEmpty()) {
            for (k in listOf("HTTP_PROXY", "http_proxy", "HTTPS_PROXY", "https_proxy")) vars[k] = httpUrl
        }
        return vars
    }

    /**
     * [FEAT-261021-19, iOS 59362a754 G3] The variable only minis-mcp-cli
     * reads: the local entry's HTTP front with the `mcp` tag, set whenever
     * the front listens - also for a direct MCP route, so the entry (not the
     * shell's proxy variables or its transparent forwarding) applies the MCP
     * scope. Nothing while the entry is off: the cli behaves as before.
     */
    const val MCP_PROXY_KEY = "MINIS_MCP_PROXY"

    fun mcp(port: Int?, httpPort: Int?, password: String): Map<String, String> {
        if (port == null || httpPort == null || password.isEmpty()) return emptyMap()
        val url = SocksEndpoint(port, NetworkScope.MCP.tag, password, httpPort).httpUrl ?: return emptyMap()
        return mapOf(MCP_PROXY_KEY to url)
    }
}

// ─── Merged from NetworkStatusMonitor.kt ──────────────────────────────────────────────────

/**
 * [FEAT-261021-07, iOS a6e6c10d1] Live state behind the small pills under
 * the title on the home screen: whether Tailscale is connected and whether
 * each proxy in use answers. Users who turned Tailscale on or set a proxy
 * long ago may not remember it when connections misbehave; the pills make
 * the routing visible. With nothing configured the pills, and this
 * monitor's work, are absent.
 *
 * A proxy in use is probed (NetworkHub.testProxy) when the app comes to the
 * front, when its settings change, and every [PROBE_INTERVAL_MS] while the
 * app is in front; a settling Tailscale node is asked for its state every
 * [TICK_MS]. Nothing runs in the background.
 */
object NetworkStatusMonitor {

    private const val TAG = "Network"
    const val PROBE_INTERVAL_MS = 180_000L
    const val TICK_MS = 10_000L

    enum class ProxyHealth { CHECKING, AVAILABLE, UNAVAILABLE }

    private val _proxyHealth = MutableStateFlow<Map<String, ProxyHealth>>(emptyMap())
    val proxyHealth: StateFlow<Map<String, ProxyHealth>> = _proxyHealth.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probedAt = HashMap<String, Pair<ProxyServer, Long>>()
    private val inFlight = HashSet<String>()
    private var tickJob: Job? = null
    @Volatile
    private var inFront = false

    /** Proxies that some route actually sends traffic through. A proxy that is only saved gets no pill. */
    fun proxiesInUse(s: NetworkSettings): List<ProxyServer> {
        if (s.forceDirect) return emptyList()
        val routes = (listOf(s.defaultRoute) + s.scopes.values + s.providerRoutes.values).toSet()
        return s.proxies.filter { "proxy:${it.id}" in routes }
    }

    /** Whether the pills have anything to show (and the monitor anything to do). */
    fun wanted(s: NetworkSettings): Boolean = s.nodeWanted || proxiesInUse(s).isNotEmpty()

    /** Called by NetworkHub.init: follows every settings change. */
    fun start(settings: StateFlow<NetworkSettings>) {
        scope.launch { settings.collect { settingsChanged(it) } }
    }

    @Synchronized
    fun onForeground() {
        inFront = true
        val s = NetworkHub.settings.value
        probeStale(s)
        updateTicker(s)
    }

    @Synchronized
    fun onBackground() {
        inFront = false
        tickJob?.cancel()
        tickJob = null
    }

    @Synchronized
    private fun settingsChanged(s: NetworkSettings) {
        val ids = proxiesInUse(s).map { it.id }.toSet()
        _proxyHealth.update { m -> m.filterKeys { it in ids } }
        probedAt.keys.retainAll(ids)
        if (!inFront) return
        probeStale(s)
        updateTicker(s)
    }

    private fun updateTicker(s: NetworkSettings) {
        if (!wanted(s)) {
            tickJob?.cancel()
            tickJob = null
            return
        }
        if (tickJob != null) return
        tickJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    @Synchronized
    private fun tick() {
        val s = NetworkHub.settings.value
        // Running and NeedsLogin stay put until the user acts; the entry
        // health check refreshes them every 30 s anyway. Anything else is on
        // its way somewhere, so look more often.
        val ts = NetworkHub.tailscale.value
        if (s.nodeWanted && !ts.isLoggedIn && !ts.needsLogin) NetworkHub.refreshStatus()
        probeStale(s)
        updateTicker(s)
    }

    private fun probeStale(s: NetworkSettings) {
        val now = System.currentTimeMillis()
        for (proxy in proxiesInUse(s)) {
            if (proxy.id in inFlight) continue
            val last = probedAt[proxy.id]
            if (last != null && last.first == proxy && now - last.second < PROBE_INTERVAL_MS) continue
            probe(proxy, now)
        }
    }

    private fun probe(proxy: ProxyServer, now: Long) {
        inFlight += proxy.id
        probedAt[proxy.id] = proxy to now
        if (proxy.id !in _proxyHealth.value) _proxyHealth.update { it + (proxy.id to ProxyHealth.CHECKING) }
        scope.launch {
            val health = runCatching { NetworkHub.testProxy(proxy, NetworkHub.proxyPassword(proxy.id)) }.fold(
                { ms ->
                    AppLogger.info(TAG, "[Net][Badge] proxy ${proxy.id.take(8)} answers ($ms ms)")
                    ProxyHealth.AVAILABLE
                },
                { e ->
                    AppLogger.warning(TAG, "[Net][Badge] proxy ${proxy.id.take(8)} probe failed: " +
                        NetworkDiagnostics.classifyText(e.message.orEmpty()))
                    ProxyHealth.UNAVAILABLE
                },
            )
            synchronized(this@NetworkStatusMonitor) {
                inFlight -= proxy.id
                // Dropped or edited while the probe ran: its result describes
                // settings that no longer exist.
                if (proxy in proxiesInUse(NetworkHub.settings.value)) _proxyHealth.update { it + (proxy.id to health) }
            }
        }
    }

    /**
     * What a pill says, ordered by severity so several proxies show their
     * worst. Real traffic failing ([NetworkAlertCenter.failing]) beats an
     * older successful probe; the alert clears itself once the proxy recovers.
     */
    enum class ProxyLevel { AVAILABLE, CHECKING, UNAVAILABLE }

    fun proxyLevel(id: String, health: Map<String, ProxyHealth>, failing: Set<NetworkAlertEvent.Subject>): ProxyLevel = when {
        NetworkAlertEvent.Subject.Proxy(id) in failing -> ProxyLevel.UNAVAILABLE
        health[id] == ProxyHealth.AVAILABLE -> ProxyLevel.AVAILABLE
        health[id] == ProxyHealth.UNAVAILABLE -> ProxyLevel.UNAVAILABLE
        else -> ProxyLevel.CHECKING
    }

    /** The Tailscale pill's state, the same reading of the node as the status row in Settings > Network. */
    enum class TailscaleLevel { CONNECTING, CONNECTED, UNAVAILABLE, NOT_LOGGED_IN, AWAITING_APPROVAL, NOT_CONNECTED }

    fun tailscaleLevel(ts: TailscaleStatus, applying: Boolean, lastError: String?, failing: Boolean): TailscaleLevel = when {
        applying && !ts.running -> TailscaleLevel.CONNECTING
        // Real connections through the node keep failing: say so even though
        // the node itself reports Running.
        ts.isLoggedIn -> if (failing) TailscaleLevel.UNAVAILABLE else TailscaleLevel.CONNECTED
        ts.needsLogin -> TailscaleLevel.NOT_LOGGED_IN
        ts.backendState == "NeedsMachineAuth" -> TailscaleLevel.AWAITING_APPROVAL
        ts.running -> TailscaleLevel.CONNECTING
        // Enabled but not running: still starting, or failed to start (the
        // reason is in Settings > Network).
        lastError == null -> TailscaleLevel.CONNECTING
        else -> TailscaleLevel.NOT_CONNECTED
    }
}

// ─── Merged from TailscaleLoginFlow.kt ──────────────────────────────────────────────────

/**
 * The browser sign-in to the built-in Tailscale node, one tap from start to
 * finish (Settings > Network > Log In with Browser).
 *
 * Why a flow of its own: tsnet only produces the sign-in URL a moment after
 * the node reaches NeedsLogin (StartLoginInteractive, then a round trip to
 * the control server), and Settings polled the status every 2 s. The row was
 * shown as soon as the state said NeedsLogin but had no click handler until a
 * later poll brought the URL, so the first tap did nothing and only the
 * second one opened the browser. Now a tap always starts the flow: the row
 * shows that it is getting the page, the status is polled quickly until the
 * URL is there, and [opens] hands it to the screen, which opens it in a
 * Custom Tab (the default browser's cookies, so an existing Google / Apple /
 * GitHub / Microsoft sign-in or passkey is reused).
 *
 * While the browser is open the node is polled until it is Running; then
 * [returnToApp] brings Minis back (which also closes the tab, see
 * NetworkSettingsScreen). Leaving the browser without signing in returns to
 * the not-signed-in state silently, and a new tap starts over.
 *
 * [FIX-261021-11] A sign-in URL is opened at most once. tailscale never
 * replaces one by itself: when the round trip that should deliver the
 * result is lost (the browser said "success", the node got
 * "register request: http 410: auth path not found"), it keeps waiting on
 * that dead URL, and StartLoginInteractive hands the same URL out again for
 * 7 days - so every retry opened a page that could no longer complete. A
 * tap that finds only an already-opened URL, or a login error, restarts
 * the node ([freshLogin]) and opens the new URL it brings.
 *
 * The URL itself is never logged.
 */
class TailscaleLoginFlow(
    private val scope: CoroutineScope,
    /** A fresh node status (calls into Go; run on [scope]). */
    private val status: () -> TailscaleStatus,
    /** Brings the app's activity to the front after the sign-in completed. */
    private val returnToApp: () -> Unit,
    /** Publishes a status to Settings at once, without waiting for its poll. */
    private val publish: (TailscaleStatus) -> Unit = {},
    /** Restarts the node so tsnet asks the control server for a new sign-in URL. */
    private val freshLogin: suspend () -> Unit = {},
    private val urlPollMs: Long = 400,
    private val urlTimeoutMs: Long = 20_000,
    private val loginPollMs: Long = 1_000,
    private val loginTimeoutMs: Long = 10 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface State {
        data object Idle : State
        /** Tapped; waiting for tsnet's sign-in URL. */
        data object OpeningPage : State
        /** The sign-in page is open in the browser; waiting for Running. */
        data object InBrowser : State
        data class Failed(val reason: Reason) : State
    }

    enum class Reason {
        /** tsnet produced no sign-in URL in time (control server unreachable…). */
        NO_SIGN_IN_PAGE,
        /** No app could open the URL. The copy-link fallback is offered. */
        NO_BROWSER,
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _opens = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** URLs the screen must open now. */
    val opens: SharedFlow<String> = _opens.asSharedFlow()

    /** The last URL handed out, for the copy-link fallback. Never logged. */
    @Volatile var lastUrl: String? = null
        private set

    private var job: Job? = null
    private var leftApp = false

    /** URLs already handed to the browser (never logged); see the class doc. */
    private val opened = LinkedHashSet<String>()

    /** The user tapped Log In with Browser. */
    @Synchronized
    fun begin() {
        if (_state.value == State.OpeningPage) return // a double tap: already on its way
        job?.cancel()
        leftApp = false
        _state.value = State.OpeningPage
        AppLogger.info(TAG, "[Net][TS] login: requested, waiting for the sign-in page")
        job = scope.launch {
            var started = now()
            var restarted = false
            while (true) {
                val st = status()
                if (st.isLoggedIn) {
                    AppLogger.info(TAG, "[Net][TS] login: already signed in")
                    publish(st)
                    _state.value = State.Idle
                    return@launch
                }
                val url = st.authURL
                val stale = url != null && synchronized(opened) { url in opened }
                val failed = st.loginError
                if (!restarted && (stale || failed != null)) {
                    AppLogger.info(TAG, "[Net][TS] login: the last sign-in page can no longer complete (" +
                        (failed?.replace(LOGIN_LINK, "$1<redacted>") ?: "already opened once") + "); restarting the node for a new one")
                    restarted = true
                    freshLogin()
                    started = now()
                    continue
                }
                if (url != null && !stale) {
                    synchronized(opened) {
                        opened += url
                        if (opened.size > 8) opened.remove(opened.first())
                    }
                    lastUrl = url
                    AppLogger.info(TAG, "[Net][TS] login: sign-in page ready after ${now() - started}ms, opening the browser")
                    _state.value = State.InBrowser
                    _opens.tryEmit(url)
                    waitForSignIn()
                    return@launch
                }
                if (now() - started >= urlTimeoutMs) {
                    AppLogger.warning(TAG, "[Net][TS] login: no sign-in page after ${urlTimeoutMs}ms (state=${st.backendState.ifEmpty { "none" }}${st.error?.let { ", error" } ?: ""})")
                    _state.value = State.Failed(Reason.NO_SIGN_IN_PAGE)
                    return@launch
                }
                delay(urlPollMs)
            }
        }
    }

    private suspend fun waitForSignIn() {
        val started = now()
        while (true) {
            delay(loginPollMs)
            val st = status()
            if (st.isLoggedIn) {
                AppLogger.info(TAG, "[Net][TS] login: authorized after ${now() - started}ms, returning to the app")
                publish(st)
                _state.value = State.Idle
                returnToApp()
                return
            }
            if (now() - started >= loginTimeoutMs) {
                AppLogger.warning(TAG, "[Net][TS] login: timed out after ${loginTimeoutMs}ms in the browser")
                _state.value = State.Idle
                return
            }
        }
    }

    /** The screen could not open the URL in any app. */
    @Synchronized
    fun browserUnavailable() {
        job?.cancel()
        AppLogger.warning(TAG, "[Net][TS] login: no browser could open the sign-in page; offering the link to copy")
        _state.value = State.Failed(Reason.NO_BROWSER)
    }

    /** The app went to the background (the browser came up). */
    fun appHidden() {
        if (_state.value == State.InBrowser) leftApp = true
    }

    /**
     * The app is in front again. After the browser, without a completed
     * sign-in, the user closed it: back to not signed in, no error.
     */
    @Synchronized
    fun appShown() {
        if (_state.value != State.InBrowser || !leftApp) return
        leftApp = false
        job?.cancel()
        AppLogger.info(TAG, "[Net][TS] login: user returned without completing the sign-in")
        _state.value = State.Idle
        // A sign-in completed in the last second shows at the next poll.
        scope.launch { publish(status()) }
    }

    /** Clears a failure message (e.g. when the screen goes away). */
    fun reset() {
        job?.cancel()
        _state.value = State.Idle
    }

    private fun now(): Long = clock()

    private companion object {
        const val TAG = "Network"
        val LOGIN_LINK = Regex("""(login\.tailscale\.com/a/)[A-Za-z0-9]+""")
    }
}

// ─── Merged from WebViewNetworkProxy.kt ──────────────────────────────────────────────────

/**
 * [T-net-android-phase3] The "Browser" scope for the app's WebViews, via
 * androidx.webkit ProxyController - the counterpart of iOS setting
 * WKWebsiteDataStore.proxyConfigurations.
 *
 * The override is process-wide (every WebView in the app), as iOS's shared
 * data store is. It points at the local entry's HTTP front: Chromium cannot
 * authenticate to a SOCKS proxy, but answers an HTTP proxy's 407 through
 * WebViewClient.onReceivedHttpAuthRequest, which every WebViewClient in the
 * app forwards to [answerAuth]. Loopback is never proxied.
 *
 * Zero regression: ProxyController is not touched at all unless a route
 * needs it, and the override is cleared the moment it no longer does
 * (route back to direct, entry down, Restore Direct Connections). A WebView
 * without PROXY_OVERRIDE support stays direct and Settings says so.
 */
object WebViewNetworkProxy {

    private const val TAG = "Network"
    /** realm the entry's HTTP front sends in its challenge (nethub/httpentry.go). */
    private const val REALM = "Minis"

    private val direct = Executor { it.run() }

    @Volatile
    private var applied: String? = null

    /** Credentials for the current override, read by [answerAuth]. */
    @Volatile
    private var credentials: Pair<String, String>? = null

    fun isSupported(): Boolean =
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) }.getOrDefault(false)

    /** Called on every runtime publish (NetworkHub registers it). */
    fun apply(snapshot: NetworkRuntime.Snapshot) {
        val ep = snapshot.endpoint(NetworkScope.BROWSER.tag)
        val hp = ep?.httpPort
        val want = hp?.let { "127.0.0.1:$it" }
        if (want == applied) {
            credentials = if (ep != null && hp != null) ep.tag to ep.password else null
            return
        }
        if (!isSupported()) {
            if (want != null) AppLogger.warning(TAG, "[Net][Browser] this WebView has no PROXY_OVERRIDE: the browser stays direct")
            return
        }
        try {
            if (want == null) {
                credentials = null
                ProxyController.getInstance().clearProxyOverride(direct) {}
                AppLogger.info(TAG, "[Net][Browser] WebView -> direct (override cleared)")
            } else {
                credentials = ep!!.tag to ep.password
                val config = ProxyConfig.Builder()
                    .addProxyRule("http://$want")
                    .addBypassRule("localhost")
                    .addBypassRule("127.0.0.1")
                    .addBypassRule("[::1]")
                    .build()
                ProxyController.getInstance().setProxyOverride(config, direct) {}
                AppLogger.info(TAG, "[Net][Browser] WebView -> local entry $want (tag=${ep.tag})")
            }
            applied = want
        } catch (t: Throwable) {
            // Never let a WebView quirk take the app down; the browser then
            // keeps whatever it had (direct, if nothing was ever set).
            AppLogger.error(TAG, "[Net][Browser] proxy override failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * For WebViewClient.onReceivedHttpAuthRequest: answers the local entry's
     * proxy challenge and returns true; false for any other site's auth
     * request, which the caller handles as before.
     */
    fun answerAuth(handler: HttpAuthHandler, host: String?, realm: String?, pageUrl: String?): Boolean {
        val c = credentials ?: return false
        if (realm != REALM) return false
        if (host != null && host != "127.0.0.1" && host != "localhost" && !host.startsWith("127.0.0.1:")) return false
        // Loopback pages bypass the proxy, so a challenge while one is shown
        // comes from a local server, not the entry: never hand it the password.
        val pageHost = pageUrl?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() }
        if (pageHost == "127.0.0.1" || pageHost == "localhost" || pageHost == "::1") return false
        handler.proceed(c.first, c.second)
        return true
    }
}
