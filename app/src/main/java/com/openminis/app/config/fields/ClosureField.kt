package com.openminis.app.config.fields

import android.content.SharedPreferences
import com.openminis.app.config.ConfigAccess
import com.openminis.app.config.ConfigError
import com.openminis.app.config.ConfigField
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.ConfigSchema
import com.openminis.app.config.ConfigValue
import com.openminis.app.config.collections.resolveEnvReference
import com.openminis.app.config.readable.ConfigReadable
import com.openminis.app.config.readable.ConfigReadableRenderer
import com.openminis.app.config.readable.ConfigValueFormatter
import com.openminis.app.config.readable.NumberUnit
import com.openminis.app.config.readable.ReadableArg
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.network.NetworkHub
import com.openminis.app.network.NetworkRoute
import com.openminis.app.network.NetworkScope
import com.openminis.app.network.ProxyServer
import com.openminis.app.network.TailscaleNode
import com.openminis.app.network.TailscalePeer
import com.openminis.app.network.TailscaleStatus
import com.openminis.app.sandbox.ContainerDns
import com.openminis.app.sandbox.ContainerDns.DnsMode

/**
 * Hand-rolled field — use when the storage backend is not a simple
 * SharedPreferences key (a repository, a DB row, a singleton). Mirrors
 * iOS `ClosureField`.
 *
 * Schema validation runs in the bridge before [writer] is invoked; the
 * writer may still throw [ConfigError] on extra invariants ("the new
 * primary group must already exist").
 */
class ClosureField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    override val valueSchema: ConfigSchema,
    override val access: ConfigAccess = ConfigAccess.READWRITE,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
    override val revertable: Boolean = true,
    /** [T-config-readable] Required (no default) — see [ConfigField.readable]. */
    override val readable: ConfigReadable,
    private val reader: () -> ConfigValue,
    private val writer: (ConfigValue) -> Unit,
    /** [FEAT-261021-03] See [ConfigField.normalize]. */
    private val normalizer: ((ConfigValue) -> ConfigValue)? = null,
    /** [FEAT-261021-03] See [ConfigField.validate]. */
    private val validator: ((ConfigValue) -> Unit)? = null,
) : ConfigField {
    override fun normalize(value: ConfigValue): ConfigValue = normalizer?.invoke(value) ?: value
    override fun validate(value: ConfigValue) {
        validator?.invoke(value)
    }
    override fun read(): ConfigValue = reader()
    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        writer(value)
    }
}

/**
 * A read-only field. Useful for stats / status surfaces (audit log
 * usage, summary aggregates). Any write attempt returns
 * `permission_denied`; the bridge shouldn't even reach `write` since
 * `access = READONLY`, but we throw to be safe.
 */
class ReadOnlyField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    override val valueSchema: ConfigSchema,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
    private val reader: () -> ConfigValue,
) : ConfigField {
    override val access: ConfigAccess get() = ConfigAccess.READONLY
    override val revertable: Boolean get() = false
    override val readable: ConfigReadable get() = ConfigReadable.notWritable
    override fun read(): ConfigValue = reader()
    override fun write(value: ConfigValue) {
        throw ConfigError.PermissionDenied("Read-only")
    }
}

/**
 * A field that exists purely to be hidden. The registry keeps it
 * around so the bridge can return a precise `permission_denied` rather
 * than leaking implementation details with `unknown_path`.
 */
class HiddenField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    private val reason: String,
) : ConfigField {
    override val valueSchema: ConfigSchema get() = ConfigSchema.Json
    override val access: ConfigAccess get() = ConfigAccess.HIDDEN
    override val risk: ConfigRisk get() = ConfigRisk.DESTRUCTIVE
    override val revertable: Boolean get() = false
    override val readable: ConfigReadable get() = ConfigReadable.notWritable
    override fun read(): ConfigValue {
        throw ConfigError.PermissionDenied(reason)
    }
    override fun write(value: ConfigValue) {
        throw ConfigError.PermissionDenied(reason)
    }
}

// ─── Merged from ContainerDnsFields.kt ──────────────────────────────────────────────────

/**
 * [T-container-network-dns] (OpenMinis#396) minis-config fields for the
 * sandbox DNS, bound to the same storage as Settings > Rootfs > Container
 * Network:
 *
 *   minis-config get network.dnsMode                      → "auto" | "custom"
 *   minis-config set network.dnsMode custom
 *   minis-config get network.dnsCustomServers             → one address per line
 *   minis-config set network.dnsCustomServers '"223.5.5.5, 2400:3200::1"'
 *   minis-config set network.dnsCustomServers '["223.5.5.5","2400:3200::1"]'
 *
 * The server list takes a string (newline / comma / space separated) or an
 * array of strings, and every entry must be an IPv4 or IPv6 literal; one bad
 * entry rejects the whole write with the offending entries named, so a typo
 * never reaches resolv.conf. An empty list is allowed (custom mode then
 * follows the system until servers are added — see ContainerDns.plan).
 * [onChanged] re-applies resolv.conf after every successful write.
 */
internal object ContainerDnsFields {

    const val MODE_PATH = "network.dnsMode"
    const val SERVERS_PATH = "network.dnsCustomServers"

    fun build(store: ContainerDns.Store, onChanged: () -> Unit): List<ConfigField> = listOf(
        ClosureField(
            path = MODE_PATH,
            displayName = "Sandbox DNS mode",
            description = "auto (default): the sandbox's /etc/resolv.conf follows the system DNS and is rewritten on " +
                "every network change — keep this under a proxy's Fake-IP / TUN mode. custom: resolv.conf lists exactly " +
                "$SERVERS_PATH, and network changes no longer touch it. custom with an empty server list behaves like auto. " +
                "Applied immediately.",
            valueSchema = ConfigSchema.StrEnum(DnsMode.values().map { it.wire }),
            risk = ConfigRisk.NORMAL,
            revertable = true,
            // [T-config-readable] Labels = the iOS keys for the same setting.
            readable = ConfigReadable(
                ConfigValueFormatter.EnumLabels(mapOf("auto" to "Automatic (system)", "custom" to "Custom")),
                label = "Container DNS mode",
            ),
            reader = { ConfigValue.Str(store.mode().wire) },
            writer = { v ->
                store.save(DnsMode.fromWire((v as ConfigValue.Str).value), store.rawServers())
                onChanged()
            },
        ),
        ClosureField(
            path = SERVERS_PATH,
            displayName = "Sandbox custom DNS servers",
            description = "IPv4 / IPv6 addresses used when $MODE_PATH is custom: a string separated by newlines, commas " +
                "or spaces (quote it: '\"223.5.5.5, 2400:3200::1\"'), or a JSON array of strings. Every entry must be an " +
                "IP literal — hostnames are rejected. The sandbox resolver (musl) uses the first " +
                "${ContainerDns.RESOLVER_MAX_SERVERS}. Stored one per line. Applied immediately when mode is custom.",
            valueSchema = ConfigSchema.Json,
            risk = ConfigRisk.NORMAL,
            revertable = true,
            // [T-config-readable] Stored as one string (newline / comma /
            // space separated) but read as a server list: added / removed /
            // first-server clauses, like iOS's array-typed field.
            readable = ConfigReadable(
                ConfigValueFormatter.StringList(ordered = true, splitText = ::splitServers),
                label = "Container custom DNS servers",
            ),
            reader = { ConfigValue.Str(store.rawServers()) },
            writer = { v ->
                val raw = serversText(v)
                val parsed = ContainerDns.parse(raw)
                if (!parsed.isValid) {
                    throw ConfigError.InvalidValue(
                        "not an IPv4 / IPv6 address: ${parsed.invalid.joinToString(", ")}",
                    )
                }
                store.save(store.mode(), raw)
                onChanged()
            },
        ),
    )

    /** The entries of a stored / written server string, in order. */
    internal fun splitServers(raw: String): List<String> =
        raw.split('\n', ',', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }

    /** A string, or an array of strings joined one per line. */
    private fun serversText(v: ConfigValue): String = when (v) {
        is ConfigValue.Str -> v.value
        is ConfigValue.Arr -> v.value.joinToString("\n") { item ->
            (item as? ConfigValue.Str)?.value
                ?: throw ConfigError.InvalidValue("array entries must be strings (IP addresses)")
        }
        ConfigValue.Null -> ""
        else -> throw ConfigError.InvalidValue("expected a string or an array of IP address strings")
    }
}

// ─── Merged from NetworkConfigFields.kt ──────────────────────────────────────────────────

/**
 * [FEAT-261021-03] minis-config for Settings > Network - port of iOS
 * NetworkConfigFields.swift (FEAT-261019-01). Everything the Network page
 * shows can be read, and everything it lets the user change can be set:
 *
 *   network.routingEnabled            master switch (false = Restore Direct Connections)
 *   network.defaultRoute              direct | tailscale | proxy:<id or name>
 *   network.routes.<scope>            shell / browser / mcp / backup, + default
 *   network.tailscale.enabled|hostname|acceptRoutes|exitNode|exitNodeDNS|exitNodeFailClosed
 *   network.tailscale.loggedIn        false = Sign Out
 *   network.tailscale.authKey         write-only: Log In with Auth Key
 *   network.tailscale.signOutAndClear write true: Sign Out and Clear
 *   network.tailscale.status          read-only: state, login link, devices
 *   network.status                    read-only: local entry and last error
 *   proxies.<id>.*                    ProxiesCollection
 *   providers.<id>.networkRoute       ProvidersCollection, uses [NetworkConfigRoute]
 *
 * Paths, values and wording match iOS so the agent's knowledge carries over.
 * iOS's network.bonjourEnabled is left out: Android has no minis-bonjour.
 * Every write goes through NetworkHub, exactly like the Settings page, so it
 * applies at once and an open page updates live.
 */
internal object NetworkConfigFields {

    fun build(envVars: EnvVarRepository): List<ConfigField> = routing() + tailscale(envVars)

    private fun routing(): List<ConfigField> = listOf(
        ClosureField(
            path = "network.routingEnabled",
            displayName = "Network Routing",
            description = "Master switch of Settings › Network. false = everything connects directly, exactly as before the " +
                "Network settings existed (\"Restore Direct Connections\"); Tailscale, proxies and routes are paused but kept. " +
                "Takes effect at once.",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.SENSITIVE,
            readable = ConfigReadable(ConfigValueFormatter.Bool()),
            reader = { ConfigValue.Bool(!NetworkHub.settings.value.forceDirect) },
            writer = { v -> if ((v as ConfigValue.Bool).value) NetworkHub.resume() else NetworkHub.restoreDirect() },
        ),
        ClosureField(
            path = "network.defaultRoute",
            displayName = "Default Route",
            description = "Route for every part of the app set to follow the default: direct, tailscale, or proxy:<id> " +
                "(a proxy's name is accepted too, see `minis-config get proxies`). Addresses in the tailnet always go " +
                "through Tailscale while it is on.",
            valueSchema = ConfigSchema.Str(maxLength = 128),
            risk = ConfigRisk.SENSITIVE,
            readable = ConfigReadable(ConfigValueFormatter.Custom(NetworkConfigRoute::phrase)),
            reader = { ConfigValue.Str(NetworkHub.settings.value.defaultRoute) },
            writer = { v -> NetworkHub.update { it.copy(defaultRoute = (v as ConfigValue.Str).value) } },
            normalizer = { NetworkConfigRoute.normalize(it, proxies()) },
            validator = { NetworkConfigRoute.validate(it, allowFollow = false, proxies()) },
        ),
    ) + NetworkScope.entries.map { scope ->
        ClosureField(
            path = "network.routes.${scope.tag}",
            displayName = scope.configDisplayName,
            description = "Route for ${scope.configWhat}: default (follow network.defaultRoute), direct, tailscale, or " +
                "proxy:<id or name>.",
            valueSchema = ConfigSchema.Str(maxLength = 128),
            risk = ConfigRisk.SENSITIVE,
            readable = ConfigReadable(ConfigValueFormatter.Custom(NetworkConfigRoute::phrase)),
            reader = { ConfigValue.Str(NetworkHub.settings.value.route(scope).raw) },
            writer = { v -> NetworkHub.update { it.copy(scopes = it.scopes + (scope.tag to (v as ConfigValue.Str).value)) } },
            normalizer = { NetworkConfigRoute.normalize(it, proxies()) },
            validator = { NetworkConfigRoute.validate(it, allowFollow = true, proxies()) },
        )
    } + ReadOnlyField(
        path = "network.status",
        displayName = "Network status",
        description = "Read-only: whether the local entry (the SOCKS5 endpoint clients use) is listening, its port, " +
            "whether settings are being applied, and the last error shown on the Network page.",
        valueSchema = ConfigSchema.Json,
        reader = {
            val out = linkedMapOf<String, ConfigValue>(
                "routingEnabled" to ConfigValue.Bool(!NetworkHub.settings.value.forceDirect),
                "entryListening" to ConfigValue.Bool(NetworkHub.entryPort.value != null),
                "applying" to ConfigValue.Bool(NetworkHub.applying.value),
                "lastError" to (NetworkHub.lastError.value?.let { ConfigValue.Str(it) } ?: ConfigValue.Null),
            )
            NetworkHub.entryPort.value?.let { out["entryPort"] = ConfigValue.Int(it) }
            ConfigValue.Obj(out)
        },
    )

    private fun tailscale(envVars: EnvVarRepository): List<ConfigField> = listOf(
        ClosureField(
            path = "network.tailscale.enabled",
            displayName = "Use Tailscale",
            description = "Run the built-in Tailscale node (inside Minis, no VPN). Needs network.routingEnabled. After " +
                "turning it on, log in with network.tailscale.authKey or open the login link in network.tailscale.status.",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.SENSITIVE,
            readable = ConfigReadable(ConfigValueFormatter.Bool()),
            reader = { ConfigValue.Bool(NetworkHub.settings.value.tailscaleEnabled) },
            writer = { v -> NetworkHub.update { it.copy(tailscaleEnabled = (v as ConfigValue.Bool).value) } },
        ),
        ClosureField(
            path = "network.tailscale.hostname",
            displayName = "Device Name",
            description = "This device's name in the tailnet. Empty = automatic (minis-android). Letters, digits and '-' " +
                "only; changing it restarts the node.",
            valueSchema = ConfigSchema.Str(maxLength = 63, regex = "^[A-Za-z0-9-]*$"),
            risk = ConfigRisk.NORMAL,
            readable = ConfigReadable(ConfigValueFormatter.Text(empty = "Automatic")),
            reader = { ConfigValue.Str(NetworkHub.settings.value.hostname) },
            writer = { v -> NetworkHub.update { it.copy(hostname = (v as ConfigValue.Str).value) } },
            normalizer = { v -> if (v is ConfigValue.Str) ConfigValue.Str(v.value.trim()) else v },
        ),
        ClosureField(
            path = "network.tailscale.acceptRoutes",
            displayName = "Use Subnet Routes",
            description = "Let addresses that other tailnet devices share (such as a home network) go through Tailscale.",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.NORMAL,
            readable = ConfigReadable(ConfigValueFormatter.Bool()),
            reader = { ConfigValue.Bool(NetworkHub.settings.value.acceptRoutes) },
            writer = { v -> NetworkHub.update { it.copy(acceptRoutes = (v as ConfigValue.Bool).value) } },
        ),
        // [FEAT-261021-23, iOS 45510cce2] Exit node privacy for the shell (both off by default).
        ClosureField(
            path = "network.tailscale.exitNodeDNS",
            displayName = "Exit Node DNS",
            description = "With an exit node in use, the shell's DNS lookups of non-tailnet names go through the exit node too. " +
                "Off (default): they go to this device's own DNS server, as without Tailscale - that server sees the names looked up, " +
                "but DNS that only works on this device (a VPN app's, a company's) keeps working. Tailnet names always resolve through Tailscale.",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.NORMAL,
            readable = ConfigReadable(ConfigValueFormatter.Bool()),
            reader = { ConfigValue.Bool(NetworkHub.settings.value.exitNodeDNS) },
            writer = { v -> NetworkHub.update { it.copy(exitNodeDNS = (v as ConfigValue.Bool).value) } },
        ),
        ClosureField(
            path = "network.tailscale.exitNodeFailClosed",
            displayName = "Block Shell Traffic When the Exit Node Is Unreachable",
            description = "With an exit node in use, a shell connection that the app's local network entry cannot take fails instead of going out directly. " +
                "Off (default): it goes out directly, which shows the destination this device's own address. On: a stopped entry cuts the shell off until it is back.",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.NORMAL,
            readable = ConfigReadable(ConfigValueFormatter.Bool()),
            reader = { ConfigValue.Bool(NetworkHub.settings.value.exitNodeFailClosed) },
            writer = { v -> NetworkHub.update { it.copy(exitNodeFailClosed = (v as ConfigValue.Bool).value) } },
        ),
        ClosureField(
            path = "network.tailscale.exitNode",
            displayName = "Exit Node",
            description = "Tailnet device that internet traffic sent over Tailscale leaves through. Empty (or none) = no " +
                "exit node. Accepts the device's Tailscale IPv4, name or MagicDNS name; it must offer itself as an exit " +
                "node (exitNodeOption in network.tailscale.status). Stored as the IPv4.",
            valueSchema = ConfigSchema.Str(maxLength = 253),
            risk = ConfigRisk.SENSITIVE,
            readable = ConfigReadable(ConfigValueFormatter.Custom(NetworkConfigTailscale::exitNodePhrase)),
            reader = { ConfigValue.Str(NetworkHub.settings.value.exitNodeIP.orEmpty()) },
            writer = { v ->
                val ip = (v as ConfigValue.Str).value
                NetworkHub.update { it.copy(exitNodeIP = ip.ifEmpty { null }) }
            },
            normalizer = { NetworkConfigTailscale.normalizeExitNode(it, NetworkConfigTailscale.status()) },
            validator = { NetworkConfigTailscale.validateExitNode(it, NetworkConfigTailscale.status()) },
        ),
        ClosureField(
            path = "network.tailscale.loggedIn",
            displayName = "Tailscale login",
            description = "Whether the node is logged in to a tailnet. Set false to Sign Out (Tailscale stays on and waits " +
                "for a new login; the device identity is kept). It cannot be set true: log in with " +
                "network.tailscale.authKey, or have the user open the login link from network.tailscale.status.",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.SENSITIVE,
            revertable = false,
            readable = ConfigReadable(ConfigValueFormatter.Bool(on = "Logged in", off = "Signed out")),
            reader = { ConfigValue.Bool(NetworkConfigTailscale.status().isLoggedIn) },
            writer = { v -> if (!(v as ConfigValue.Bool).value) NetworkHub.logout() },
            validator = { v ->
                if (v == ConfigValue.Bool(true)) {
                    throw ConfigError.InvalidValue(
                        "Log in with network.tailscale.authKey, or open the login link from network.tailscale.status",
                    )
                }
            },
        ),
        ClosureField(
            path = "network.tailscale.authKey",
            displayName = "Tailscale auth key",
            description = "Write-only. Log In with Auth Key: write a tskey-auth-… key (or a `\$\$ENV_VAR` reference) to add " +
                "this device to the tailnet. Used once and not stored; turns Tailscale on if it is off. Cannot be read back.",
            valueSchema = ConfigSchema.Str(maxLength = 1000),
            risk = ConfigRisk.SENSITIVE,
            revertable = false,
            readable = ConfigReadable(ConfigValueFormatter.Secret),
            reader = { throw ConfigError.PermissionDenied("Tailscale auth keys are used once and never stored or exposed") },
            writer = { v ->
                val key = resolveEnvReference(envVars, (v as ConfigValue.Str).value.trim(), "network.tailscale.authKey")
                if (!key.startsWith("tskey-")) throw ConfigError.InvalidValue("A Tailscale auth key starts with tskey-")
                if (NetworkHub.settings.value.forceDirect) {
                    throw ConfigError.InvalidValue("Network routing is off; set network.routingEnabled true first")
                }
                NetworkHub.update { it.copy(tailscaleEnabled = true) }
                NetworkHub.login(key)
            },
        ),
        ClosureField(
            path = "network.tailscale.signOutAndClear",
            displayName = "Sign Out and Clear",
            description = "Write true to sign out, turn Tailscale off and delete the device key: turning Tailscale on " +
                "again adds this device as a new machine. Always reads false.",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.DESTRUCTIVE,
            revertable = false,
            readable = ConfigReadable(ConfigValueFormatter.Bool(on = "Sign out and delete the device key", off = "No change")),
            reader = { ConfigValue.Bool(false) },
            writer = { v -> if ((v as ConfigValue.Bool).value) NetworkHub.clearTailscale() },
        ),
        ReadOnlyField(
            path = "network.tailscale.status",
            displayName = "Tailscale status",
            description = "Read-only: state (off / starting / connecting / needsLogin / connected), loginURL (the browser " +
                "login link while logged out - give it to the user to open), tailnet, this device's IPs and MagicDNS " +
                "name, and devices[] (name, ips, os, online, exitNodeOption, exitNode).",
            valueSchema = ConfigSchema.Json,
            reader = { NetworkConfigTailscale.statusValue(NetworkHub.settings.value.nodeWanted, NetworkConfigTailscale.status()) },
        ),
    )

    private fun proxies(): List<ProxyServer> = NetworkHub.settings.value.proxies

    private val NetworkScope.configDisplayName: String
        get() = when (this) {
            NetworkScope.SHELL -> "Route for Shell"
            NetworkScope.BROWSER -> "Route for Browser"
            NetworkScope.MCP -> "Route for MCP"
            NetworkScope.BACKUP -> "Route for Backup"
        }

    private val NetworkScope.configWhat: String
        get() = when (this) {
            NetworkScope.SHELL -> "the sandbox shell (curl, git, pip, … via ALL_PROXY / HTTP(S)_PROXY; open shells pick a change up from their next command)"
            NetworkScope.BROWSER -> "the in-app browser, browser_use and web previews (needs a WebView with proxy override)"
            NetworkScope.MCP -> "MCP servers"
            NetworkScope.BACKUP -> "backups and mounted network folders (rclone)"
        }
}

/**
 * Route strings as minis-config reads and writes them: the raw NetworkRoute
 * values the settings store ("default", "direct", "tailscale", "proxy:<id>").
 * Pure (the proxy list is passed in) so JVM tests cover it.
 */
internal object NetworkConfigRoute {
    /**
     * Keywords are case-insensitive; a proxy is accepted as `proxy:<id>`,
     * `proxy:<name>` or a bare id / name, and stored as `proxy:<id>`.
     * Unknown text is passed on unchanged for [validate] to name.
     */
    fun normalize(v: ConfigValue, proxies: List<ProxyServer>): ConfigValue {
        val s = (v as? ConfigValue.Str)?.value?.trim() ?: throw ConfigError.TypeMismatch("string")
        when (s.lowercase()) {
            "default", "follow", "followdefault", "follow-default", "follow default" -> return ConfigValue.Str("default")
            "direct" -> return ConfigValue.Str("direct")
            "tailscale" -> return ConfigValue.Str("tailscale")
        }
        val ref = if (s.lowercase().startsWith("proxy:")) s.substring("proxy:".length) else s
        return proxyId(ref, proxies)?.let { ConfigValue.Str("proxy:$it") } ?: ConfigValue.Str(s)
    }

    fun validate(v: ConfigValue, allowFollow: Boolean, proxies: List<ProxyServer>) {
        val raw = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
        if (raw == "direct" || raw == "tailscale" || (raw == "default" && allowFollow)) return
        if (raw.startsWith("proxy:") && proxies.any { "proxy:${it.id}" == raw }) return
        val options = listOfNotNull("default".takeIf { allowFollow }, "direct", "tailscale") +
            proxies.map { "proxy:${it.id} (${it.displayName})" }
        throw ConfigError.InvalidValue("unknown route '$raw'; use one of: ${options.joinToString(", ")}")
    }

    fun phrase(v: ConfigValue): ReadableArg {
        val raw = (v as? ConfigValue.Str)?.value ?: return ConfigReadableRenderer.genericPhrase(v)
        return when (val r = NetworkRoute.parse(raw)) {
            NetworkRoute.FollowDefault -> ReadableArg.Key("Follow Default")
            NetworkRoute.Direct -> ReadableArg.Key("Direct")
            NetworkRoute.Tailscale -> ReadableArg.Text("Tailscale")
            is NetworkRoute.Proxy -> ReadableArg.Text(
                NetworkHub.settings.value.proxies.firstOrNull { it.id == r.id }?.displayName ?: raw,
            )
        }
    }

    /** Exact id first, then a unique case-insensitive name (or host:port). */
    private fun proxyId(ref: String, proxies: List<ProxyServer>): String? {
        proxies.firstOrNull { it.id == ref }?.let { return it.id }
        val named = proxies.filter { it.displayName.equals(ref, ignoreCase = true) }
        return named.singleOrNull()?.id
    }
}

internal object NetworkConfigTailscale {
    /**
     * Fresh node status (the hub's copy is refreshed only while the Network
     * page polls). Skipped unless the node is wanted and was started in this
     * process, so a disabled Tailscale never calls into Go.
     */
    fun status(): TailscaleStatus {
        if (!NetworkHub.settings.value.nodeWanted || !TailscaleNode.nodeTouched) return TailscaleStatus()
        return runCatching { TailscaleStatus.parse(TailscaleNode.status()) }.getOrDefault(TailscaleStatus())
    }

    fun statusValue(wanted: Boolean, st: TailscaleStatus): ConfigValue {
        val state = when {
            !wanted -> "off"
            st.isLoggedIn -> "connected"
            st.needsLogin -> "needsLogin"
            st.running -> "connecting"
            else -> "starting"
        }
        val out = linkedMapOf<String, ConfigValue>(
            "state" to ConfigValue.Str(state),
            "tailnet" to ConfigValue.Str(st.tailnet),
            "ips" to ConfigValue.Arr(st.ips.map { ConfigValue.Str(it) }),
            "dnsName" to ConfigValue.Str(st.dnsName),
            "devices" to ConfigValue.Arr(
                st.peers.map { p ->
                    ConfigValue.Obj(
                        linkedMapOf(
                            "name" to ConfigValue.Str(p.displayName),
                            "dnsName" to ConfigValue.Str(p.dnsName),
                            "ips" to ConfigValue.Arr(p.ips.map { ConfigValue.Str(it) }),
                            "os" to ConfigValue.Str(p.os),
                            "online" to ConfigValue.Bool(p.online),
                            "exitNodeOption" to ConfigValue.Bool(p.exitNodeOption),
                            "exitNode" to ConfigValue.Bool(p.exitNode),
                        ),
                    )
                },
            ),
        )
        if (st.needsLogin) st.authURL?.let { out["loginURL"] = ConfigValue.Str(it) }
        st.error?.takeIf { it.isNotEmpty() }?.let { out["error"] = ConfigValue.Str(it) }
        return ConfigValue.Obj(out)
    }

    fun normalizeExitNode(v: ConfigValue, st: TailscaleStatus): ConfigValue {
        val s = (v as? ConfigValue.Str)?.value?.trim() ?: throw ConfigError.TypeMismatch("string")
        if (s.isEmpty() || s.equals("none", ignoreCase = true)) return ConfigValue.Str("")
        val match = st.peers.filter { it.exitNodeOption }.firstOrNull { p ->
            s in p.ips || p.displayName.equals(s, true) || p.hostName.equals(s, true) ||
                (p.dnsName.isNotEmpty() && p.dnsName.equals(s, true))
        }
        return ConfigValue.Str(match?.let(::ipv4) ?: s)
    }

    fun validateExitNode(v: ConfigValue, st: TailscaleStatus) {
        val ip = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
        if (ip.isEmpty()) return
        if (!st.isLoggedIn) {
            throw ConfigError.InvalidValue(
                "Tailscale is not connected; an exit node can be chosen once it is (see network.tailscale.status)",
            )
        }
        val options = st.peers.filter { it.exitNodeOption }
        if (options.any { ipv4(it) == ip }) return
        throw ConfigError.InvalidValue(
            if (options.isEmpty()) "no device in this tailnet offers itself as an exit node"
            else "'$ip' is not an exit node; choose one of: " +
                options.joinToString(", ") { "${it.displayName} (${ipv4(it) ?: "?"})" } + ", or none",
        )
    }

    fun exitNodePhrase(v: ConfigValue): ReadableArg {
        val ip = (v as? ConfigValue.Str)?.value ?: return ConfigReadableRenderer.genericPhrase(v)
        if (ip.isEmpty()) return ReadableArg.Key("None")
        val peer = NetworkHub.tailscale.value.peers.firstOrNull { ip in it.ips }
        return ReadableArg.Text(peer?.let { "${it.displayName} ($ip)" } ?: ip)
    }

    private fun ipv4(p: TailscalePeer): String? = p.ips.firstOrNull { !it.contains(':') }
}

// ─── Merged from PrefsFields.kt ──────────────────────────────────────────────────

/**
 * Convenience wrappers for the most common backend (SharedPreferences).
 * Mirror iOS `AppStorage*Field`s. Add new variants here when a new
 * primitive type starts to recur — the bridge cares only about the
 * [ConfigValue] shape, so wrappers translate.
 */

class PrefsBoolField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    private val prefs: SharedPreferences,
    private val key: String,
    private val defaultValue: Boolean,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
) : ConfigField {
    override val valueSchema: ConfigSchema get() = ConfigSchema.Bool
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true
    override val readable: ConfigReadable get() = ConfigReadable(ConfigValueFormatter.Bool())

    override fun read(): ConfigValue = ConfigValue.Bool(
        if (prefs.contains(key)) prefs.getBoolean(key, defaultValue) else defaultValue
    )
    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        val b = (value as ConfigValue.Bool).value
        prefs.edit().putBoolean(key, b).apply()
    }
}

class PrefsIntField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    private val prefs: SharedPreferences,
    private val key: String,
    private val defaultValue: Int,
    private val minValue: Int? = null,
    private val maxValue: Int? = null,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
    /** [T-config-readable] Unit, and the label key shown for 0 (e.g. "Off"). */
    private val unit: NumberUnit = NumberUnit.PLAIN,
    private val zeroLabel: String? = null,
) : ConfigField {
    override val valueSchema: ConfigSchema
        get() = ConfigSchema.Int(min = minValue, max = maxValue)
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true
    override val readable: ConfigReadable get() = ConfigReadable(ConfigValueFormatter.Number(unit, zeroLabel))

    override fun read(): ConfigValue = ConfigValue.Int(
        if (prefs.contains(key)) prefs.getInt(key, defaultValue) else defaultValue
    )
    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        val i = (value as ConfigValue.Int).value
        prefs.edit().putInt(key, i).apply()
    }
}

class PrefsLongField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    private val prefs: SharedPreferences,
    private val key: String,
    private val defaultValue: Long,
    private val minValue: Long? = null,
    private val maxValue: Long? = null,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
    /** [T-config-readable] Unit, and the label key shown for 0. */
    private val unit: NumberUnit = NumberUnit.PLAIN,
    private val zeroLabel: String? = null,
) : ConfigField {
    override val readable: ConfigReadable get() = ConfigReadable(ConfigValueFormatter.Number(unit, zeroLabel))
    // Long values are surfaced as Int in the schema (Kotlin's range is
    // wider than the bridge's ConfigValue.Int — for the few sync.maxFileSize
    // -style fields we accept downcasting; the agent never sees > 2^31 here).
    override val valueSchema: ConfigSchema
        get() = ConfigSchema.Int(
            min = minValue?.toInt(),
            max = maxValue?.toInt(),
        )
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true

    override fun read(): ConfigValue {
        val v = if (prefs.contains(key)) prefs.getLong(key, defaultValue) else defaultValue
        return ConfigValue.Int(v.toInt())
    }
    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        val i = (value as ConfigValue.Int).value
        prefs.edit().putLong(key, i.toLong()).apply()
    }
}

class PrefsDoubleField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    private val prefs: SharedPreferences,
    private val key: String,
    private val defaultValue: Double,
    private val minValue: Double? = null,
    private val maxValue: Double? = null,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
) : ConfigField {
    override val readable: ConfigReadable get() = ConfigReadable(ConfigValueFormatter.Number())
    // SharedPreferences has no native Double; persist as Float bits via
    // `Float.toRawBits` packed into a Long for full precision. Most call
    // sites here use small floats (rate / pitch / volume) so the cast is
    // lossless in practice.
    override val valueSchema: ConfigSchema
        get() = ConfigSchema.Double(min = minValue, max = maxValue)
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true

    override fun read(): ConfigValue {
        val v = if (prefs.contains(key)) prefs.getFloat(key, defaultValue.toFloat()).toDouble() else defaultValue
        return ConfigValue.Double(v)
    }
    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        val d = when (value) {
            is ConfigValue.Double -> value.value
            is ConfigValue.Int -> value.value.toDouble()
            else -> throw ConfigError.TypeMismatch("double")
        }
        prefs.edit().putFloat(key, d.toFloat()).apply()
    }
}

class PrefsStringField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    private val prefs: SharedPreferences,
    private val key: String,
    private val defaultValue: String,
    private val maxLength: Int? = null,
    private val regex: String? = null,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
    /**
     * [T-config-readable] Required: a string field's values can be ids,
     * language codes, free text… only the registration knows which.
     */
    override val readable: ConfigReadable,
) : ConfigField {
    override val valueSchema: ConfigSchema
        get() = ConfigSchema.Str(maxLength = maxLength, regex = regex)
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true

    override fun read(): ConfigValue =
        ConfigValue.Str(prefs.getString(key, null) ?: defaultValue)
    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        val s = (value as ConfigValue.Str).value
        prefs.edit().putString(key, s).apply()
    }
}

/**
 * String-enum stored as a raw string in SharedPreferences. Schema's
 * StrEnum validates before write.
 */
class PrefsEnumField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    private val prefs: SharedPreferences,
    private val key: String,
    private val cases: List<String>,
    /** [T-config-readable] Required: raw case → label key, covering every case. */
    private val valueLabels: Map<String, String>,
    private val defaultValue: String,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
) : ConfigField {
    override val readable: ConfigReadable get() = ConfigReadable(ConfigValueFormatter.EnumLabels(valueLabels))
    override val valueSchema: ConfigSchema get() = ConfigSchema.StrEnum(cases)
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true

    override fun read(): ConfigValue {
        val raw = prefs.getString(key, null) ?: defaultValue
        return ConfigValue.Str(if (raw in cases) raw else defaultValue)
    }
    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        prefs.edit().putString(key, (value as ConfigValue.Str).value).apply()
    }
}

/**
 * Some legacy app preferences store the picker index as Int (e.g.
 * `theme_mode` is 0=System / 1=Light / 2=Dark) but we want the agent
 * to see clean string tokens. Reads map index → case; writes map case
 * → index. Mirrors iOS `AppStorageIntCodedEnumField`.
 */
class PrefsIntCodedEnumField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    private val prefs: SharedPreferences,
    private val key: String,
    private val cases: List<String>,
    /** [T-config-readable] Required: raw case → label key, covering every case. */
    private val valueLabels: Map<String, String>,
    private val defaultIndex: Int,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
) : ConfigField {
    override val readable: ConfigReadable get() = ConfigReadable(ConfigValueFormatter.EnumLabels(valueLabels))
    override val valueSchema: ConfigSchema get() = ConfigSchema.StrEnum(cases)
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true

    override fun read(): ConfigValue {
        val idx = if (prefs.contains(key)) prefs.getInt(key, defaultIndex) else defaultIndex
        val safe = if (idx in 0..cases.lastIndex) idx else defaultIndex
        return ConfigValue.Str(cases[safe])
    }
    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        val s = (value as ConfigValue.Str).value
        val idx = cases.indexOf(s)
        if (idx < 0) throw ConfigError.InvalidValue("must be one of: ${cases.joinToString(", ")}")
        prefs.edit().putInt(key, idx).apply()
    }
}
