package com.openminis.app.config.collections

import com.openminis.app.config.ConfigAccess
import com.openminis.app.config.ConfigCollection
import com.openminis.app.config.ConfigError
import com.openminis.app.config.ConfigField
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.ConfigSchema
import com.openminis.app.config.ConfigValue
import com.openminis.app.config.fields.ClosureField
import com.openminis.app.config.fields.HiddenField
import com.openminis.app.config.fields.ReadOnlyField
import com.openminis.app.config.readable.ConfigReadable
import com.openminis.app.config.readable.ConfigReadableRenderer
import com.openminis.app.config.readable.ConfigRefKind
import com.openminis.app.config.readable.ConfigRefResolver
import com.openminis.app.config.readable.ConfigValueFormatter
import com.openminis.app.config.readable.NumberUnit
import com.openminis.app.config.readable.ReadableArg
import com.openminis.app.config.readable.ReadableLine
import com.openminis.app.config.readable.ReadableThinkingLabels
import com.openminis.app.config.readable.RedactedConfigValue
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentLimits
import com.openminis.app.data.model.SubAgentRoster
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.network.NetworkHub
import com.openminis.app.network.ProxyServer
import com.openminis.app.provider.thinking.ThinkingRule
import com.openminis.app.provider.thinking.ThinkingRuleCoding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/**
 * Exposes `EnvVarRepository` entries under `envvars.<key>.…`. Mirrors
 * iOS `EnvVarsCollection`.
 *
 * IMPORTANT: values are NEVER exposed — neither read nor write here.
 * The agent can list keys, attach a note, or remove a variable, but
 * the actual secret only ever moves through the encrypted prefs and
 * the user-facing UI. Adding a variable goes through the deep link
 * `minis://settings/environments?create_key=…` so the user supplies
 * the value directly.
 */
class EnvVarsCollection(
    private val repo: EnvVarRepository,
) : ConfigCollection {
    override val basePath: String get() = "envvars"
    override val displayName: String get() = "Environment variables"
    override val description: String get() =
        "Metadata only — values are stored in encrypted prefs and never exposed."
    override val addable: Boolean get() = false
    override val removable: Boolean get() = true
    override val risk: ConfigRisk get() = ConfigRisk.SENSITIVE
    override val addPayloadSchema: ConfigSchema get() = ConfigSchema.Json

    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun childIds(): List<String> = repo.entries.value.map { it.key }

    override fun fields(forId: String): List<ConfigField> {
        val entry = repo.entries.value.firstOrNull { it.key == forId } ?: return emptyList()
        return listOf(
            createdAtField(forId),
            noteField(forId),
            enabledField(forId),
            // Hidden guard: an agent attempt to read or write the
            // value gets a precise permission_denied.
            HiddenField(
                path = "envvars.$forId.value",
                displayName = "Value",
                description = "Hidden — manage via Settings → Environment Variables.",
                reason = "Env var values are stored in encrypted prefs and never exposed to minis-config",
            ),
        )
    }

    override fun add(payload: ConfigValue): String {
        throw ConfigError.PermissionDenied(
            "Use the deep link `minis://settings/environments?create_key=…` so the user enters the value directly"
        )
    }

    override fun remove(id: String) {
        val entry = repo.entries.value.firstOrNull { it.key == id }
            ?: throw ConfigError.UnknownPath("envvars.$id")
        repo.delete(entry.id)
    }

    // -- [T-config-readable] Readable descriptions (mirrors iOS EnvVarsCollection) --
    //
    // Only the variable NAME ever appears; there is no code path here that can
    // reach a value (values live in the encrypted store and minis-config
    // never reads them).

    override val readableSubjectFormat: String get() = "Environment variable $%1\$@ – %2\$@"

    override fun entityLabel(id: String): String? = repo.entries.value.firstOrNull { it.key == id }?.key

    /** `add` is refused (a value is required); nothing to describe. */
    override fun readableForAdd(payload: RedactedConfigValue): List<ReadableLine> = emptyList()

    override fun readableForRemove(id: String): List<ReadableLine> {
        val e = repo.entries.value.firstOrNull { it.key == id } ?: return emptyList()
        return listOf(
            ReadableLine("Delete environment variable $%@ (its value is removed from Keychain)", listOf(ReadableArg.Text(e.key))),
        )
    }

    private fun createdAtField(key: String): ConfigField =
        ReadOnlyField(
            path = "envvars.$key.createdAt",
            displayName = "Created at",
            description = "ISO-8601 timestamp.",
            valueSchema = ConfigSchema.Str(),
            reader = {
                val e = repo.entries.value.firstOrNull { it.key == key }
                if (e == null) ConfigValue.Null
                else ConfigValue.Str(isoFormatter.format(Date(e.createdAt)))
            },
        )

    /**
     * [T-envvar-disable] On / off without touching the value. SENSITIVE: turning
     * a variable back on hands its secret to every later shell, so the user sees
     * it in the confirmation sheet like any other credential change.
     */
    private fun enabledField(key: String): ConfigField =
        ClosureField(
            path = "envvars.$key.enabled",
            displayName = "Enabled",
            description = "Whether the variable is injected into shells, terminals and MCP servers. " +
                "A disabled variable keeps its value and note.",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.Bool(on = "Enabled", off = "Disabled"), label = "Status"),
            reader = {
                val e = repo.entries.value.firstOrNull { it.key == key }
                if (e == null) ConfigValue.Null else ConfigValue.Bool(e.enabled)
            },
            writer = { v ->
                val b = (v as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("bool")
                val e = repo.entries.value.firstOrNull { it.key == key }
                    ?: throw ConfigError.UnknownPath("envvars.$key")
                repo.setEnabled(e.id, b)
            },
        )

    private fun noteField(key: String): ConfigField =
        ClosureField(
            path = "envvars.$key.note",
            displayName = "Note",
            description = "Free-form description (not secret). Empty string clears it.",
            valueSchema = ConfigSchema.Str(maxLength = 500),
            risk = ConfigRisk.NORMAL,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.Text(preview = 60)),
            reader = {
                val e = repo.entries.value.firstOrNull { it.key == key }
                if (e == null) ConfigValue.Null
                else ConfigValue.Str(e.note)
            },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                val e = repo.entries.value.firstOrNull { it.key == key }
                    ?: throw ConfigError.UnknownPath("envvars.$key")
                // Update via a value-preserving path: read existing value
                // back out of encrypted prefs, then write the new note
                // alongside it. The repo's [update] keeps the secret
                // in place when the same key is provided.
                val currentValue = repo.getValue(e.key) ?: ""
                repo.update(e.id, e.key, currentValue, s)
            },
        )
}

// ─── Merged from GroupsCollection.kt ──────────────────────────────────────────────────

/**
 * Exposes ModelGroup fields under `groups.<id>.…`. Mirrors iOS
 * `GroupsCollection`. Add/remove fully supported — users frequently
 * want to spin up named bundles for fallback.
 */
class GroupsCollection(
    private val repo: ProviderRepository,
) : ConfigCollection {
    override val basePath: String get() = "groups"
    override val displayName: String get() = "Model groups"
    override val description: String get() =
        "Named bundles of models with fallback / load-balance routing."
    override val addable: Boolean get() = true
    override val removable: Boolean get() = true
    override val risk: ConfigRisk get() = ConfigRisk.SENSITIVE
    override val addPayloadSchema: ConfigSchema get() = ConfigSchema.Json

    override fun childIds(): List<String> = repo.config.value.modelGroups.map { it.id }

    override fun fields(forId: String): List<ConfigField> {
        if (group(forId) == null) return emptyList()
        return listOf(
            nameField(forId),
            strategyField(forId),
            fallbackStrategyField(forId),
            defaultThinkingLevelField(forId),
            contextLimitField(forId),
            entriesField(forId),
        )
    }

    override fun add(payload: ConfigValue): String {
        val obj = (payload as? ConfigValue.Obj)?.value
            ?: throw ConfigError.InvalidValue("Expected JSON object")
        val name = (obj["name"] as? ConfigValue.Str)?.value
            ?: throw ConfigError.InvalidValue("`name` required")
        if (name.isEmpty()) throw ConfigError.InvalidValue("`name` required")

        // Accept either `entries` (matches the group's display field + the
        // aggregate `groups` summary key) or the legacy `members` alias.
        val members = mutableListOf<String>()
        val entryArr = (obj["entries"] as? ConfigValue.Arr)?.value
            ?: (obj["members"] as? ConfigValue.Arr)?.value
        entryArr?.forEach { v ->
            val s = (v as? ConfigValue.Str)?.value
            if (!s.isNullOrEmpty()) members.add(s)
        }

        val strategy = (obj["strategy"] as? ConfigValue.Str)?.value?.let {
            runCatching { RoutingStrategy.valueOf(it) }.getOrNull()
        } ?: RoutingStrategy.fallback

        val fallback = (obj["fallback_strategy"] as? ConfigValue.Str)?.value?.let {
            runCatching { FallbackStrategy.valueOf(it) }.getOrNull()
        } ?: FallbackStrategy.default

        // Wire token is lowercase (`off`/`low`/…) to match iOS; empty / absent
        // means "no override" (null). An unrecognised token is ignored (null).
        val defaultThinking = (obj["default_thinking_level"] as? ConfigValue.Str)?.value
            ?.takeIf { it.isNotEmpty() }
            ?.let { thinkingLevelFromToken(it) }

        // 0 (or absent / non-positive) means "no limit" (null).
        val contextLimit = (obj["context_limit_tokens"] as? ConfigValue.Int)?.value
            ?.takeIf { it > 0 }

        val g = ModelGroup(
            name = name,
            memberEntryIds = members,
            strategy = strategy,
            fallbackStrategy = fallback,
            defaultThinkingLevel = defaultThinking,
            contextLimitTokens = contextLimit,
        )
        repo.addGroup(g)
        return g.id
    }

    override fun remove(id: String) {
        if (group(id) == null) throw ConfigError.UnknownPath("groups.$id")
        repo.removeGroup(id)
    }

    // -- [T-config-readable] Readable descriptions (mirrors iOS GroupsCollection) --

    override val readableSubjectFormat: String get() = "Group “%1\$@” – %2\$@"

    override fun entityLabel(id: String): String? = group(id)?.name

    override fun readableForAdd(payload: RedactedConfigValue): List<ReadableLine> {
        val d = (payload.value as? ConfigValue.Obj)?.value ?: return emptyList()
        val name = (d["name"] as? ConfigValue.Str)?.value ?: "?"
        val entries = ((d["entries"] ?: d["members"]) as? ConfigValue.Arr)?.value
            ?.mapNotNull { (it as? ConfigValue.Str)?.value?.takeIf(String::isNotEmpty) }
            .orEmpty()
        val lines = mutableListOf(
            ReadableLine("Create group “%1\$@” with %2\$@ models", listOf(ReadableArg.Text(name), ReadableArg.Text("${entries.size}"))),
        )
        entries.firstOrNull()?.let {
            lines.add(ReadableLine("Primary model: %@", listOf(ConfigRefResolver.name(ConfigRefKind.ModelEntry, it))))
        }
        return lines
    }

    override fun readableForRemove(id: String): List<ReadableLine> {
        val g = group(id) ?: return emptyList()
        return listOf(
            ReadableLine("Delete group “%1\$@” (%2\$@ models)", listOf(ReadableArg.Text(g.name), ReadableArg.Text("${g.memberEntryIds.size}"))),
        )
    }

    // -- Field factories --

    private fun group(id: String): ModelGroup? =
        repo.config.value.modelGroups.firstOrNull { it.id == id }

    private fun mutate(id: String, apply: (ModelGroup) -> Unit) {
        val published = group(id) ?: throw ConfigError.UnknownPath("groups.$id")
        // [T-android-provider-mutator-lock] Apply to a COPY, then hand that to
        // updateGroup. `group(id)` returns the live object out of the published
        // config, so mutating it in place — the `entries` field writer does
        // memberEntryIds.clear()/addAll — races Compose readers iterating the
        // same list, reachable from minis-config and the agent loop. The
        // deep-copied memberEntryIds is what makes the structural edits safe;
        // ProvidersCollection.mutate already uses this shape.
        val draft = published.copy(memberEntryIds = published.memberEntryIds.toMutableList())
        apply(draft)
        repo.updateGroup(draft)
    }

    private fun nameField(id: String): ConfigField =
        ClosureField(
            path = "groups.$id.name",
            displayName = "Name",
            description = "User-visible label.",
            valueSchema = ConfigSchema.Str(maxLength = 200),
            risk = ConfigRisk.NORMAL,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.Text()),
            reader = {
                val g = group(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Str(g.name)
            },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                mutate(id) { it.name = s }
            },
        )

    private fun strategyField(id: String): ConfigField =
        ClosureField(
            path = "groups.$id.strategy",
            displayName = "Routing strategy",
            description = "fallback (try in order) / loadBalance (distribute).",
            valueSchema = ConfigSchema.StrEnum(listOf("fallback", "loadBalance")),
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.EnumLabels(strategyLabels)),
            reader = {
                val g = group(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Str(g.strategy.name)
            },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                val parsed = runCatching { RoutingStrategy.valueOf(s) }.getOrNull()
                    ?: throw ConfigError.InvalidValue("Unknown strategy")
                mutate(id) { it.strategy = parsed }
            },
        )

    private fun fallbackStrategyField(id: String): ConfigField =
        ClosureField(
            path = "groups.$id.fallbackStrategy",
            displayName = "Fallback policy",
            description = "default (only on rate limiting / 5xx) / always (also on transient errors).",
            valueSchema = ConfigSchema.StrEnum(listOf("default", "always")),
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.EnumLabels(fallbackLabels)),
            reader = {
                val g = group(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Str(g.fallbackStrategy.name)
            },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                val parsed = runCatching { FallbackStrategy.valueOf(s) }.getOrNull()
                    ?: throw ConfigError.InvalidValue("Unknown fallback strategy")
                mutate(id) { it.fallbackStrategy = parsed }
            },
        )

    private fun defaultThinkingLevelField(id: String): ConfigField =
        ClosureField(
            path = "groups.$id.defaultThinkingLevel",
            displayName = "Default thinking level",
            description = "Applied to new sessions bound to this group. Empty = off.",
            valueSchema = ConfigSchema.StrEnum(listOf("", "off", "low", "medium", "high", "xhigh")),
            risk = ConfigRisk.NORMAL,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.EnumLabels(ReadableThinkingLabels.withNotSet)),
            reader = {
                val g = group(id) ?: return@ClosureField ConfigValue.Null
                // Wire token is lowercase to match iOS; null → empty string.
                ConfigValue.Str(g.defaultThinkingLevel?.let { thinkingLevelToToken(it) } ?: "")
            },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                mutate(id) {
                    it.defaultThinkingLevel = if (s.isEmpty()) null else thinkingLevelFromToken(s)
                }
            },
        )

    private fun contextLimitField(id: String): ConfigField =
        ClosureField(
            path = "groups.$id.contextLimitTokens",
            displayName = "Context limit (tokens)",
            description = "0 = no limit (use model's native context window).",
            valueSchema = ConfigSchema.Int(min = 0, max = 4_000_000),
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.Number(NumberUnit.TOKENS, zero = "No limit")),
            reader = {
                val g = group(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Int(g.contextLimitTokens ?: 0)
            },
            writer = { v ->
                val i = (v as? ConfigValue.Int)?.value ?: throw ConfigError.TypeMismatch("int")
                mutate(id) { it.contextLimitTokens = if (i == 0) null else i }
            },
        )

    private val strategyLabels = mapOf("fallback" to "Fallback (in order)", "loadBalance" to "Load balance")

    /** Android's `default` is iOS's `limited`: fall back only when the provider rejects. */
    private val fallbackLabels = mapOf("default" to "Only when the provider rejects", "always" to "Also on transient errors")

    /** iOS uses lowercase tokens (`off`/`low`/…); Android `ThinkingLevel.name`
     *  is uppercase. Round-trip identical strings to keep the CLI contract
     *  cross-platform. */
    private fun thinkingLevelToToken(level: ThinkingLevel): String = when (level) {
        ThinkingLevel.OFF -> "off"
        ThinkingLevel.LOW -> "low"
        ThinkingLevel.MEDIUM -> "medium"
        ThinkingLevel.HIGH -> "high"
        ThinkingLevel.XHIGH -> "xhigh"
        // [T-android-thinking-level-arch] GPT-5.6 higher tiers.
        ThinkingLevel.MAX -> "max"
        ThinkingLevel.ULTRA -> "ultra"
    }

    private fun thinkingLevelFromToken(token: String): ThinkingLevel? = when (token) {
        "off" -> ThinkingLevel.OFF
        "low" -> ThinkingLevel.LOW
        "medium" -> ThinkingLevel.MEDIUM
        "high" -> ThinkingLevel.HIGH
        "xhigh" -> ThinkingLevel.XHIGH
        "max" -> ThinkingLevel.MAX
        "ultra" -> ThinkingLevel.ULTRA
        else -> null
    }

    /**
     * Entries live as a JSON array of model entry UUIDs. Writing
     * replaces the whole list — the same path covers add / remove /
     * reorder in one operation. Use `groups.<id>.entries.append`
     * or `.remove` (handled by the bridge) for single-element ops.
     */
    private fun entriesField(id: String): ConfigField =
        ClosureField(
            path = "groups.$id.entries",
            displayName = "Entries",
            description = "Ordered list of model entry UUIDs.",
            valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.RefList(ConfigRefKind.ModelEntry, ordered = true), label = "Models"),
            reader = {
                val g = group(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Arr(g.memberEntryIds.map { ConfigValue.Str(it) })
            },
            writer = { v ->
                val arr = (v as? ConfigValue.Arr)?.value ?: throw ConfigError.TypeMismatch("array")
                val ids = arr.mapNotNull { (it as? ConfigValue.Str)?.value }
                val valid = repo.config.value.modelEntries.map { it.id }.toSet()
                for (eid in ids) {
                    if (eid !in valid) throw ConfigError.InvalidValue("Unknown model entry uuid: $eid")
                }
                mutate(id) {
                    it.memberEntryIds.clear()
                    it.memberEntryIds.addAll(ids)
                }
            },
        )
}

// ─── Merged from ProxiesCollection.kt ──────────────────────────────────────────────────

/**
 * [FEAT-261021-03] `proxies.<id>.*` - Settings > Network > Proxies (port of
 * iOS ProxiesCollection, FEAT-261019-01). Passwords go to the encrypted store
 * through NetworkHub.saveProxy and are write-only here, like provider API
 * keys; `password` is in ConfigValue.SECRET_KEYS, so an add payload and a
 * password write are masked in the confirmation dialog and the audit log.
 */
class ProxiesCollection(private val envVars: EnvVarRepository) : ConfigCollection {
    override val basePath: String get() = "proxies"
    override val displayName: String get() = "Proxies"
    override val description: String get() =
        "Upstream HTTP, HTTPS or SOCKS5 proxies (Settings › Network › Proxies). Use one as a route with proxy:<id> in " +
            "network.defaultRoute, network.routes.<scope> or providers.<id>.networkRoute. Removing a proxy sends routes " +
            "that used it back to direct / default."
    override val risk: ConfigRisk get() = ConfigRisk.SENSITIVE

    /** {"type": "socks5|http|https", "host": "…", "port": 1080, "name"?, "username"?, "password"?, "bypass"?: […]} */
    override val addPayloadSchema: ConfigSchema get() = ConfigSchema.Json

    override fun childIds(): List<String> = NetworkHub.settings.value.proxies.map { it.id }

    override fun fields(forId: String): List<ConfigField> {
        if (proxy(forId) == null) return emptyList()
        val id = forId
        return listOf(
            stringField(id, "name", "Name", "Shown in route pickers; empty = host:port.", 100,
                ConfigReadable(ConfigValueFormatter.Text(empty = "(empty)")), { it.name }) { p, s -> p.copy(name = s) },
            ClosureField(
                path = "proxies.$id.type",
                displayName = "Type",
                description = "socks5, http or https.",
                valueSchema = ConfigSchema.StrEnum(ProxyServer.KINDS),
                risk = ConfigRisk.SENSITIVE,
                readable = ConfigReadable(
                    ConfigValueFormatter.EnumLabels(mapOf("socks5" to "SOCKS5", "http" to "HTTP", "https" to "HTTPS")),
                ),
                reader = { proxy(id)?.let { ConfigValue.Str(it.kind) } ?: ConfigValue.Null },
                writer = { v -> mutate(id) { it.copy(kind = (v as ConfigValue.Str).value) } },
            ),
            stringField(id, "host", "Server", "Proxy host name or IP address.", 253,
                ConfigReadable(ConfigValueFormatter.Text()), { it.host }) { p, s -> p.copy(host = s) },
            ClosureField(
                path = "proxies.$id.port",
                displayName = "Port",
                description = "1-65535.",
                valueSchema = ConfigSchema.Int(min = 1, max = 65535),
                risk = ConfigRisk.SENSITIVE,
                readable = ConfigReadable(ConfigValueFormatter.Number()),
                reader = { proxy(id)?.let { ConfigValue.Int(it.port) } ?: ConfigValue.Null },
                writer = { v -> mutate(id) { it.copy(port = (v as ConfigValue.Int).value) } },
            ),
            stringField(id, "username", "Username", "Empty = no authentication.", 256,
                ConfigReadable(ConfigValueFormatter.Text(empty = "(empty)")), { it.username }) { p, s -> p.copy(username = s) },
            ClosureField(
                path = "proxies.$id.password",
                displayName = "Password",
                description = "Write-only. The proxy password (or a `\$\$ENV_VAR` reference); empty clears it. Kept in " +
                    "the encrypted store; cannot be read back.",
                valueSchema = ConfigSchema.Str(maxLength = 1000),
                risk = ConfigRisk.SENSITIVE,
                revertable = false,
                readable = ConfigReadable(ConfigValueFormatter.Secret),
                reader = { throw ConfigError.PermissionDenied("Proxy passwords are kept encrypted and never exposed to minis-config") },
                writer = { v ->
                    val raw = (v as ConfigValue.Str).value
                    val p = proxy(id) ?: throw ConfigError.UnknownPath("proxies.$id")
                    NetworkHub.saveProxy(p, if (raw.isEmpty()) "" else resolveEnvReference(envVars, raw, "password"))
                },
            ),
            ClosureField(
                path = "proxies.$id.bypass",
                displayName = "Bypass Hosts",
                description = "Hosts that connect directly instead of through this proxy: exact host, *.example.com (also " +
                    "matches subdomains), CIDR or IP. Accepts a JSON array or one comma/newline-separated list; " +
                    ".append/.remove take one entry. Local addresses always bypass the proxy.",
                valueSchema = ConfigSchema.Array(ConfigSchema.Str(maxLength = 253)),
                risk = ConfigRisk.NORMAL,
                readable = ConfigReadable(ConfigValueFormatter.StringList(ordered = false)),
                reader = { proxy(id)?.let { p -> ConfigValue.Arr(p.bypass.map { ConfigValue.Str(it) }) } ?: ConfigValue.Null },
                writer = { v ->
                    val list = (v as ConfigValue.Arr).value.mapNotNull { (it as? ConfigValue.Str)?.value }
                    mutate(id) { it.copy(bypass = list) }
                },
                normalizer = ::normalizeBypass,
            ),
        )
    }

    override fun add(payload: ConfigValue): String {
        val p = proxyFromPayload(payload)
        val password = ((payload as ConfigValue.Obj).value["password"] as? ConfigValue.Str)?.value?.trim()
            ?.let { resolveEnvReference(envVars, it, "password") }
        NetworkHub.saveProxy(p, password)
        return p.id
    }

    override fun remove(id: String) {
        if (proxy(id) == null) throw ConfigError.UnknownPath("proxies.$id")
        NetworkHub.deleteProxy(id)
    }

    // -- Readable descriptions --

    override val readableSubjectFormat: String get() = "Proxy “%1\$@” – %2\$@"

    override fun entityLabel(id: String): String? = proxy(id)?.displayName

    /** The payload is already masked: a password shows only as given or not. */
    override fun readableForAdd(payload: RedactedConfigValue): List<ReadableLine> {
        val d = (payload.value as? ConfigValue.Obj)?.value ?: return emptyList()
        fun text(key: String) = (d[key] as? ConfigValue.Str)?.value?.takeIf { it.isNotEmpty() }
        val port = (d["port"] as? ConfigValue.Int)?.value?.toString().orEmpty()
        val kind = ProxyServer.kindLabel(text("type")?.lowercase() ?: "socks5")
        val server = if (port.isEmpty()) text("host") ?: "?" else "${text("host") ?: "?"}:$port"
        val lines = mutableListOf(
            ReadableLine("Add %1\$@ proxy %2\$@", listOf(ReadableArg.Text(kind), ReadableArg.Text(text("name") ?: server))),
        )
        if (text("password") != null) lines.add(ReadableLine("Password provided (value hidden)"))
        return lines
    }

    override fun readableForRemove(id: String): List<ReadableLine> {
        val p = proxy(id) ?: return emptyList()
        return listOf(ReadableLine("Delete proxy “%@”; routes that used it connect directly", listOf(ReadableArg.Text(p.displayName))))
    }

    // -- Helpers --

    private fun proxy(id: String): ProxyServer? = NetworkHub.settings.value.proxies.firstOrNull { it.id == id }

    /** Saves through the hub, keeping the stored password (null = untouched). */
    private fun mutate(id: String, change: (ProxyServer) -> ProxyServer) {
        val p = proxy(id) ?: throw ConfigError.UnknownPath("proxies.$id")
        NetworkHub.saveProxy(change(p), null)
    }

    private fun stringField(
        id: String,
        leaf: String,
        displayName: String,
        description: String,
        max: Int,
        readable: ConfigReadable,
        get: (ProxyServer) -> String,
        set: (ProxyServer, String) -> ProxyServer,
    ): ConfigField = ClosureField(
        path = "proxies.$id.$leaf",
        displayName = displayName,
        description = description,
        valueSchema = ConfigSchema.Str(maxLength = max),
        risk = ConfigRisk.SENSITIVE,
        readable = readable,
        reader = { proxy(id)?.let { ConfigValue.Str(get(it)) } ?: ConfigValue.Null },
        writer = { v -> mutate(id) { p -> set(p, (v as ConfigValue.Str).value) } },
        normalizer = { v -> if (v is ConfigValue.Str) ConfigValue.Str(v.value.trim()) else v },
        validator = { v ->
            if (leaf == "host" && (v as? ConfigValue.Str)?.value.isNullOrEmpty()) {
                throw ConfigError.InvalidValue("host cannot be empty")
            }
        },
    )

    companion object {
        /** A proxy from an `add` payload (pure; the password is handled by [add]). */
        internal fun proxyFromPayload(payload: ConfigValue): ProxyServer {
            val d = (payload as? ConfigValue.Obj)?.value
                ?: throw ConfigError.TypeMismatch("object, e.g. {\"type\":\"socks5\",\"host\":\"10.0.0.2\",\"port\":1080}")
            fun string(key: String): String? = when (val v = d[key]) {
                null, ConfigValue.Null -> null
                is ConfigValue.Str -> v.value.trim()
                else -> throw ConfigError.InvalidValue("$key must be a string")
            }
            val kind = string("type")?.lowercase()?.also {
                if (it !in ProxyServer.KINDS) throw ConfigError.InvalidValue("type must be one of: socks5, http, https")
            } ?: "socks5"
            val host = string("host")?.takeIf { it.isNotEmpty() } ?: throw ConfigError.InvalidValue("host is required")
            val port = when (val v = d["port"]) {
                null, ConfigValue.Null -> ProxyServer.defaultPort(kind)
                is ConfigValue.Int -> v.value.takeIf { it in 1..65535 }
                    ?: throw ConfigError.InvalidValue("port must be an integer 1-65535")
                else -> throw ConfigError.InvalidValue("port must be an integer 1-65535")
            }
            val bypass = when (val v = d["bypass"]) {
                null, ConfigValue.Null -> emptyList()
                is ConfigValue.Str -> splitList(v.value)
                is ConfigValue.Arr -> v.value.mapNotNull { (it as? ConfigValue.Str)?.value?.trim() }.filter { it.isNotEmpty() }
                else -> throw ConfigError.InvalidValue("bypass must be an array of strings or a comma-separated string")
            }
            return ProxyServer(
                name = string("name").orEmpty(),
                kind = kind,
                host = host,
                port = port,
                username = string("username").orEmpty(),
                bypass = bypass,
            )
        }

        /** A comma/newline-separated string or an array -> trimmed, non-empty entries. */
        internal fun normalizeBypass(v: ConfigValue): ConfigValue = when (v) {
            is ConfigValue.Str -> ConfigValue.Arr(splitList(v.value).map { ConfigValue.Str(it) })
            is ConfigValue.Arr -> ConfigValue.Arr(
                v.value.map { item ->
                    ConfigValue.Str((item as? ConfigValue.Str)?.value?.trim() ?: throw ConfigError.TypeMismatch("string"))
                }.filter { it.value.isNotEmpty() },
            )
            else -> throw ConfigError.TypeMismatch("array of strings, or a comma/newline-separated string")
        }

        private fun splitList(s: String): List<String> =
            s.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    }
}

// ─── Merged from SubAgentsCollection.kt ──────────────────────────────────────────────────

/**
 * [T-minis-config-coverage] Exposes the Sub Agents roster under
 * `subagents.<id>.…` - what Settings > Sub Agents can do: add, edit, delete,
 * reorder. Port of iOS `SubAgentsCollection` (same paths, payload keys, limits
 * and refusals), so one script works on both platforms.
 *
 * The built-in agent is addressed as `subagents.general` ([BUILT_IN_ALIAS]);
 * its stored id `builtin.general` resolves too. It is only partly writable:
 * its name and description are canonical English that
 * [SubAgentRoster.normalize] restores on every load, so a write would seem to
 * succeed and then revert. Those two fields are read-only for it, `remove`
 * refuses it, and its model, reasoning override and instructions are writable
 * like any other agent's.
 */
class SubAgentsCollection(
    private val repo: ProviderRepository,
) : ConfigCollection {
    override val basePath: String get() = "subagents"
    override val displayName: String get() = "Sub agents"
    override val description: String get() =
        "Named sub agents the assistant can delegate to (Settings › Sub Agents). The built-in one cannot be deleted and its name/description are fixed."
    override val addable: Boolean get() = true
    override val removable: Boolean get() = true

    /** Sensitive: a definition changes what work is handed off and which model runs it. */
    override val risk: ConfigRisk get() = ConfigRisk.SENSITIVE

    /**
     * Add payload:
     * {
     *   "name": "Translator",                     // required, <= 40, unique
     *   "description": "Translate zh/en docs.",   // required, <= 200
     *   "instructions": "Always keep tone.",      // optional, <= 4000
     *   "model_group": "<group id or name>",      // optional; omit = Auto
     *   "thinking": "high",                       // optional; omit = follow the group
     *   "position": 1                             // optional insert index among custom agents
     * }
     */
    override val addPayloadSchema: ConfigSchema get() = ConfigSchema.Json

    private fun realId(segment: String): String =
        if (segment == BUILT_IN_ALIAS) SubAgentDefinition.BUILT_IN_ID else segment

    private fun def(id: String): SubAgentDefinition? = repo.subAgent(id)

    // -- Children --

    override fun childIds(): List<String> = repo.subAgents.map { pathSegment(it.id) }

    override fun fields(forId: String): List<ConfigField> {
        val id = realId(forId)
        val d = def(id) ?: return emptyList()
        val seg = pathSegment(id)
        return listOf(
            nameField(id, seg, d.isBuiltIn),
            descriptionField(id, seg, d.isBuiltIn),
            instructionsField(id, seg, d.isBuiltIn),
            modelGroupField(id, seg),
            thinkingField(id, seg),
            builtInField(id, seg),
        )
    }

    // -- Add / remove --

    override fun add(payload: ConfigValue): String {
        val obj = (payload as? ConfigValue.Obj)?.value
            ?: throw ConfigError.InvalidValue("Expected a JSON object")
        val name = checkedName((obj["name"] as? ConfigValue.Str)?.value
            ?: throw ConfigError.InvalidValue("`name` is required"), excluding = null)
        val desc = checkedDescription((obj["description"] as? ConfigValue.Str)?.value
            ?: throw ConfigError.InvalidValue(
                "`description` is required — it is what the assistant reads to decide when to use this sub agent",
            ))
        val instructions = (obj["instructions"] as? ConfigValue.Str)?.value?.let(::checkedInstructions).orEmpty()
        val groupId = (obj["model_group"] as? ConfigValue.Str)?.value?.trim()
            ?.takeIf { it.isNotEmpty() }?.let(::resolveGroupId)
        val thinking = (obj["thinking"] as? ConfigValue.Str)?.value?.let(::parseThinking)
        // Checked before writing, so the caller is told the limit instead of
        // normalize() silently dropping the new row.
        if (repo.subAgents.size >= SubAgentLimits.MAX_COUNT) {
            throw ConfigError.InvalidValue(
                "You can define at most ${SubAgentLimits.MAX_COUNT} sub agents (including the built-in one). Remove one first.",
            )
        }
        val created = SubAgentDefinition(
            name = name,
            description = desc,
            instructions = instructions,
            modelGroupId = groupId,
            thinkingLevelOverride = thinking,
            isBuiltIn = false,
            // Past every existing row: normalize() renumbers densely.
            sortOrder = Int.MAX_VALUE,
        )
        repo.upsertSubAgent(created)

        // `position` is applied as a reorder afterwards: upsert appends, and
        // the order is what the model sees.
        val pos = (obj["position"] as? ConfigValue.Int)?.value
        if (pos != null && pos >= 0) {
            val ids = customIds(repo).toMutableList()
            if (ids.remove(created.id)) {
                ids.add(minOf(pos, ids.size), created.id)
                repo.reorderSubAgents(listOf(SubAgentDefinition.BUILT_IN_ID) + ids)
            }
        }
        return created.id
    }

    override fun remove(id: String) {
        val real = realId(id)
        val d = def(real) ?: throw ConfigError.UnknownPath("subagents.$id")
        if (d.isBuiltIn) {
            throw ConfigError.PermissionDenied(
                "The built-in sub agent is the target of every delegation that names no agent, so it cannot be deleted. You can change its model and instructions instead.",
            )
        }
        repo.deleteSubAgent(real)
    }

    // -- [T-config-readable] Readable descriptions (keys as on iOS) --

    override val readableSubjectFormat: String get() = "Sub agent “%1\$@” – %2\$@"

    override fun entityLabel(id: String): String? = def(realId(id))?.name

    override fun readableForAdd(payload: RedactedConfigValue): List<ReadableLine> {
        val d = (payload.value as? ConfigValue.Obj)?.value ?: return emptyList()
        val name = (d["name"] as? ConfigValue.Str)?.value ?: "?"
        val lines = mutableListOf(ReadableLine("Create sub agent “%@”", listOf(ReadableArg.Text(name))))
        (d["model_group"] as? ConfigValue.Str)?.value?.takeIf { it.isNotEmpty() }?.let {
            lines.add(ReadableLine("Model group: %@", listOf(groupPhrase(ConfigValue.Str(it)))))
        }
        return lines
    }

    override fun readableForRemove(id: String): List<ReadableLine> {
        val d = def(realId(id)) ?: return emptyList()
        return listOf(ReadableLine("Delete sub agent “%@”", listOf(ReadableArg.Text(d.name))))
    }

    /** `model_group` reads back as a group NAME and is written as a name or an id; "" = Auto. */
    private fun groupPhrase(v: ConfigValue): ReadableArg {
        val s = (v as? ConfigValue.Str)?.value?.trim() ?: return ConfigReadableRenderer.genericPhrase(v)
        if (s.isEmpty()) return ReadableArg.Key("Auto")
        return ReadableArg.Text(repo.config.value.modelGroups.firstOrNull { it.id == s }?.name ?: s)
    }

    // -- Validation shared by add and the field writers --

    private fun checkedName(raw: String, excluding: String?): String {
        val name = raw.trim()
        if (name.isEmpty()) throw ConfigError.InvalidValue("`name` must not be empty")
        if (name.length > SubAgentLimits.NAME_MAX_LENGTH) {
            throw ConfigError.InvalidValue("`name` must be at most ${SubAgentLimits.NAME_MAX_LENGTH} characters")
        }
        val key = SubAgentRoster.nameKey(name)
        if (repo.subAgents.any { it.id != excluding && SubAgentRoster.nameKey(it.name) == key }) {
            throw ConfigError.InvalidValue(
                "Another sub agent is already called '$name'. Names are how the model addresses an agent, so they must be unique.",
            )
        }
        return name
    }

    private fun checkedDescription(raw: String): String {
        val desc = raw.trim()
        if (desc.isEmpty()) throw ConfigError.InvalidValue("`description` must not be empty")
        if (desc.length > SubAgentLimits.DESCRIPTION_MAX_LENGTH) {
            throw ConfigError.InvalidValue(
                "`description` must be at most ${SubAgentLimits.DESCRIPTION_MAX_LENGTH} characters — it costs main-conversation tokens on every turn",
            )
        }
        return desc
    }

    private fun checkedInstructions(raw: String): String {
        val text = raw.trim()
        if (text.length > SubAgentLimits.INSTRUCTIONS_MAX_LENGTH) {
            throw ConfigError.InvalidValue(
                "`instructions` must be at most ${SubAgentLimits.INSTRUCTIONS_MAX_LENGTH} characters",
            )
        }
        return text
    }

    /** "" = not set (null). OFF is not offered, as in the editor and on iOS. */
    private fun parseThinking(raw: String): ThinkingLevel? {
        val token = raw.trim().lowercase()
        if (token.isEmpty()) return null
        return THINKING_TOKENS.entries.firstOrNull { it.value == token }?.key
            ?: throw ConfigError.InvalidValue(
                "`thinking` must be one of: ${THINKING_TOKENS.values.joinToString(", ")} — or \"\" to unset it",
            )
    }

    /** A group id, or a group name, so a caller can write what `get groups` shows. */
    private fun resolveGroupId(raw: String): String {
        val trimmed = raw.trim()
        val groups = repo.config.value.modelGroups
        if (groups.any { it.id == trimmed }) return trimmed
        val key = SubAgentRoster.nameKey(trimmed)
        val matches = groups.filter { SubAgentRoster.nameKey(it.name) == key }
        if (matches.size == 1) return matches[0].id
        if (matches.size > 1) {
            throw ConfigError.InvalidValue("More than one model group is called '$trimmed' — use its id instead.")
        }
        val known = groups.joinToString(", ") { it.name }.ifEmpty { "(none configured)" }
        throw ConfigError.InvalidValue("Unknown model group '$trimmed'. Available: $known")
    }

    private fun mutate(id: String, apply: (SubAgentDefinition) -> SubAgentDefinition) {
        val current = def(id) ?: throw ConfigError.UnknownPath("subagents.${pathSegment(id)}")
        repo.upsertSubAgent(apply(current))
    }

    /** Says WHY and what to do instead: the value is load-bearing for the model. */
    private fun builtInFieldDenied(field: String) = ConfigError.PermissionDenied(
        "The built-in sub agent's $field is fixed: the assistant matches on the name and reads the description to decide what to delegate, and both are restored on every load. Change its model or instructions instead, or add your own sub agent.",
    )

    // -- Field factories --

    private fun nameField(id: String, seg: String, isBuiltIn: Boolean): ConfigField =
        ClosureField(
            path = "subagents.$seg.name",
            displayName = "Name",
            description = if (isBuiltIn) {
                "Fixed for the built-in sub agent — it is the value the assistant passes as `agent`."
            } else {
                "How the assistant addresses this sub agent. Must be unique; at most ${SubAgentLimits.NAME_MAX_LENGTH} characters."
            },
            valueSchema = ConfigSchema.Str(maxLength = SubAgentLimits.NAME_MAX_LENGTH),
            // Advertised read-only for the built-in, so topic-help agrees with
            // the refusal in the writer.
            access = if (isBuiltIn) ConfigAccess.READONLY else ConfigAccess.READWRITE,
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.Text()),
            reader = { def(id)?.let { ConfigValue.Str(it.name) } ?: ConfigValue.Null },
            writer = { v ->
                if (isBuiltIn) throw builtInFieldDenied("name")
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                val name = checkedName(s, excluding = id)
                mutate(id) { it.copy(name = name) }
            },
        )

    private fun descriptionField(id: String, seg: String, isBuiltIn: Boolean): ConfigField =
        ClosureField(
            path = "subagents.$seg.description",
            displayName = "Description",
            description = if (isBuiltIn) {
                "Fixed for the built-in sub agent — it is what the assistant reads to decide what to delegate."
            } else {
                "When to use this sub agent. The assistant picks by this text, so write it as \"use this when …\"; at most ${SubAgentLimits.DESCRIPTION_MAX_LENGTH} characters. Details belong in instructions."
            },
            valueSchema = ConfigSchema.Str(maxLength = SubAgentLimits.DESCRIPTION_MAX_LENGTH),
            access = if (isBuiltIn) ConfigAccess.READONLY else ConfigAccess.READWRITE,
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.Text(preview = 60)),
            reader = { def(id)?.let { ConfigValue.Str(it.description) } ?: ConfigValue.Null },
            writer = { v ->
                if (isBuiltIn) throw builtInFieldDenied("description")
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                val desc = checkedDescription(s)
                mutate(id) { it.copy(description = desc) }
            },
        )

    /** Writable for every agent: the built-in's are the standing instructions of unnamed delegations. */
    private fun instructionsField(id: String, seg: String, isBuiltIn: Boolean): ConfigField =
        ClosureField(
            path = "subagents.$seg.instructions",
            displayName = "Instructions",
            description = if (isBuiltIn) {
                "Standing instructions appended to every delegation that does not name a sub agent. Custom sub agents use their own instead — these are NOT prepended to them. At most ${SubAgentLimits.INSTRUCTIONS_MAX_LENGTH} characters."
            } else {
                "Standing instructions appended to this sub agent's brief. Only this sub agent uses them. At most ${SubAgentLimits.INSTRUCTIONS_MAX_LENGTH} characters. Empty to clear."
            },
            valueSchema = ConfigSchema.Str(maxLength = SubAgentLimits.INSTRUCTIONS_MAX_LENGTH),
            risk = ConfigRisk.NORMAL,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.LongText),
            reader = { def(id)?.let { ConfigValue.Str(it.instructions) } ?: ConfigValue.Null },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                val text = checkedInstructions(s)
                mutate(id) { it.copy(instructions = text) }
            },
        )

    private fun modelGroupField(id: String, seg: String): ConfigField =
        ClosureField(
            path = "subagents.$seg.model_group",
            displayName = "Model group",
            description = "Model Group this sub agent runs on, by id or name. Empty string = Auto: the assistant chooses per task between this conversation's model, the default group and the light group.",
            valueSchema = ConfigSchema.Str(),
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.Custom(::groupPhrase)),
            reader = {
                val d = def(id) ?: return@ClosureField ConfigValue.Null
                val gid = d.modelGroupId ?: return@ClosureField ConfigValue.Str("")
                // The name when it resolves; a dangling id is shown as it is.
                ConfigValue.Str(repo.config.value.modelGroups.firstOrNull { it.id == gid }?.name ?: gid)
            },
            writer = { v ->
                val raw = ((v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")).trim()
                val gid = if (raw.isEmpty()) null else resolveGroupId(raw)
                mutate(id) { it.copy(modelGroupId = gid) }
            },
        )

    private fun thinkingField(id: String, seg: String): ConfigField =
        ClosureField(
            path = "subagents.$seg.thinking",
            displayName = "Reasoning override",
            description = "Reasoning level every run of this sub agent uses, overriding its Model Group's default: ${THINKING_TOKENS.values.joinToString(", ")}. Empty string = not set: follow the Model Group, or the delegating conversation when the model is Auto. Models that cannot reason this hard are clamped to their own ceiling.",
            valueSchema = ConfigSchema.Str(),
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.EnumLabels(ReadableThinkingLabels.withNotSet)),
            reader = {
                val d = def(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Str(d.thinkingLevelOverride?.let { THINKING_TOKENS[it] } ?: "")
            },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                val level = parseThinking(s)
                mutate(id) { it.copy(thinkingLevelOverride = level) }
            },
        )

    /** Read-only, so `get` output explains on its own why some writes are refused. */
    private fun builtInField(id: String, seg: String): ConfigField =
        ReadOnlyField(
            path = "subagents.$seg.built_in",
            displayName = "Built-in",
            description = "True for the general sub agent, which cannot be deleted and whose name and description are fixed.",
            valueSchema = ConfigSchema.Bool,
            reader = { def(id)?.let { ConfigValue.Bool(it.isBuiltIn) } ?: ConfigValue.Null },
        )

    companion object {
        /** Path segment of the built-in (`subagents.general.…`), as on iOS. */
        const val BUILT_IN_ALIAS = "general"

        /** Wire tokens as on iOS (lowercase); OFF is not a valid override. */
        private val THINKING_TOKENS: Map<ThinkingLevel, String> = linkedMapOf(
            ThinkingLevel.LOW to "low",
            ThinkingLevel.MEDIUM to "medium",
            ThinkingLevel.HIGH to "high",
            ThinkingLevel.XHIGH to "xhigh",
            ThinkingLevel.MAX to "max",
            ThinkingLevel.ULTRA to "ultra",
        )

        fun pathSegment(id: String): String =
            if (id == SubAgentDefinition.BUILT_IN_ID) BUILT_IN_ALIAS else id

        private fun customIds(repo: ProviderRepository): List<String> =
            repo.subAgents.filter { !it.isBuiltIn }.map { it.id }

        /**
         * `subagents.order` — the custom agents in the order the assistant
         * sees them. A flat field, so writing the array reorders in one
         * audited step. The built-in is always first and is not in the list.
         */
        fun orderField(repo: ProviderRepository): ConfigField =
            ClosureField(
                path = "subagents.order",
                displayName = "Sub agent order",
                description = "Ordered ids of the CUSTOM sub agents. This is the order the assistant sees them in; the built-in one is always first and is not part of this list.",
                valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
                risk = ConfigRisk.NORMAL,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.RefList(ConfigRefKind.SubAgent, ordered = true)),
                reader = { ConfigValue.Arr(customIds(repo).map { ConfigValue.Str(it) }) },
                writer = { v ->
                    val arr = (v as? ConfigValue.Arr)?.value ?: throw ConfigError.TypeMismatch("array")
                    val ids = arr.mapNotNull { (it as? ConfigValue.Str)?.value }
                    val current = customIds(repo)
                    // Only a permutation: a short list would move the omitted
                    // agents to the end, an unknown id would do nothing.
                    if (ids.size != current.size || ids.toSet() != current.toSet()) {
                        throw ConfigError.InvalidValue(
                            "Must be a permutation of the ${current.size} custom sub agent id(s). The built-in one is always first and must not appear.",
                        )
                    }
                    repo.reorderSubAgents(listOf(SubAgentDefinition.BUILT_IN_ID) + ids)
                },
            )

        /** `get subagents`: what exists, which is writable, and each one's model. */
        fun summaryField(repo: ProviderRepository): ConfigField =
            ReadOnlyField(
                path = "subagents",
                displayName = "Sub agents (summary)",
                description = "Read-only roster view. Each entry's writable paths are subagents.<id>.{name,description,instructions,model_group,thinking}; the built-in one refuses name/description and cannot be removed. Reorder with subagents.order.",
                valueSchema = ConfigSchema.Json,
                reader = {
                    val groups = repo.config.value.modelGroups
                    val roster = repo.subAgents
                    val items = roster.map { d ->
                        val obj = linkedMapOf<String, ConfigValue>(
                            "id" to ConfigValue.Str(d.id),
                            "name" to ConfigValue.Str(d.name),
                            "description" to ConfigValue.Str(d.description),
                            "built_in" to ConfigValue.Bool(d.isBuiltIn),
                            "path" to ConfigValue.Str("subagents.${pathSegment(d.id)}"),
                        )
                        if (d.instructions.isNotEmpty()) obj["instructions"] = ConfigValue.Str(d.instructions)
                        val gid = d.modelGroupId
                        if (gid != null) {
                            obj["model_group"] = ConfigValue.Str(groups.firstOrNull { it.id == gid }?.name ?: gid)
                        } else {
                            // "Auto" is a setting, not an absence.
                            obj["model_group"] = ConfigValue.Str("")
                            obj["model"] = ConfigValue.Str("auto — chosen per task")
                        }
                        d.thinkingLevelOverride?.let { obj["thinking"] = ConfigValue.Str(THINKING_TOKENS[it] ?: "") }
                        if (d.isBuiltIn) {
                            obj["note"] = ConfigValue.Str(
                                "Built-in: name and description are fixed and it cannot be deleted; model and instructions are writable.",
                            )
                        }
                        ConfigValue.Obj(obj)
                    }
                    ConfigValue.Obj(
                        linkedMapOf(
                            "count" to ConfigValue.Int(items.size),
                            "max" to ConfigValue.Int(SubAgentLimits.MAX_COUNT),
                            "agents" to ConfigValue.Arr(items),
                        ),
                    )
                },
            )
    }
}

// ─── Merged from ThinkingRulesCollection.kt ──────────────────────────────────────────────────

/**
 * [T-android-thinking-rules-phase2 / parity with iOS ThinkingRulesCollection.swift]
 * Exposes user-authored custom thinking rules to minis-config under
 * `thinkingrules.<instanceId>:<ruleId>.<field>`.
 *
 * Only CUSTOM rules are enumerable; built-in rules are never children, so they have no
 * writable path — "you cannot modify a built-in" is a structural guarantee, not a runtime
 * check. Child id is the composite `<instanceId>:<ruleId>` because the config path splits
 * give a collection exactly ONE id segment; instance ids are UUIDs (no colon), so a split
 * on the FIRST ':' cleanly separates instance from rule.
 */
class ThinkingRulesCollection(
    private val repo: ProviderRepository,
) : ConfigCollection {
    override val basePath: String get() = "thinkingrules"
    override val displayName: String get() = "Thinking rules"
    override val description: String get() =
        "User-authored rules that decide which thinking parameters an OpenAI-compatible provider sends."
    override val addable: Boolean get() = true
    override val removable: Boolean get() = true
    override val risk: ConfigRisk get() = ConfigRisk.SENSITIVE
    override val addPayloadSchema: ConfigSchema get() = ConfigSchema.Json

    // ---- child enumeration ----

    override fun childIds(): List<String> = buildList {
        for (inst in repo.config.value.instances) {
            for (id in repo.thinkingRuleIds(inst.id)) add("${inst.id}:$id")
        }
    }

    private fun split(childId: String): Pair<String, String>? {
        val i = childId.indexOf(':')
        if (i <= 0 || i >= childId.length - 1) return null
        return childId.substring(0, i) to childId.substring(i + 1)
    }

    private fun ruleOf(childId: String): Triple<String, String, ThinkingRule>? {
        val (instanceId, ruleId) = split(childId) ?: return null
        val ids = repo.thinkingRuleIds(instanceId)
        val idx = ids.indexOf(ruleId)
        if (idx < 0) return null
        val rule = repo.thinkingRules(instanceId).getOrNull(idx) ?: return null
        return Triple(instanceId, ruleId, rule)
    }

    // ---- fields ----

    override fun fields(forId: String): List<ConfigField> {
        val (instanceId, ruleId, _) = ruleOf(forId) ?: return emptyList()
        return listOf(
            labelField(forId, instanceId, ruleId),
            scopeField(forId, instanceId, ruleId),
            wireFormatField(forId, instanceId, ruleId),
            providerField(forId, instanceId),
        )
    }

    private fun labelField(childId: String, instanceId: String, ruleId: String) = ClosureField(
        path = "$basePath.$childId.label",
        displayName = "Label",
        description = "Human-readable name shown in the rule list and the resolution trace.",
        valueSchema = ConfigSchema.Str(),
        readable = ConfigReadable(ConfigValueFormatter.Text()),
        reader = { ConfigValue.Str(ruleOf(childId)?.third?.label ?: "") },
        writer = { v ->
            val label = (v as? ConfigValue.Str)?.value ?: throw ConfigError.InvalidValue("expected string")
            val rule = ruleOf(childId)?.third ?: throw ConfigError.InvalidValue("rule no longer exists")
            repo.saveThinkingRule(instanceId, rule.copy(label = label), id = ruleId)
        },
    )

    private fun scopeField(childId: String, instanceId: String, ruleId: String) = ClosureField(
        path = "$basePath.$childId.scope",
        displayName = "Scope",
        description = "\"all\" for every model, or a glob pattern like \"deepseek-v4*\".",
        valueSchema = ConfigSchema.Str(),
        risk = ConfigRisk.SENSITIVE,
        readable = ConfigReadable(ConfigValueFormatter.Custom(::scopePhrase), label = "Applies to"),
        reader = {
            val s = ruleOf(childId)?.third?.scope
            ConfigValue.Str(if (s is ThinkingRule.Scope.ModelPattern) s.pattern else "all")
        },
        writer = { v ->
            val str = (v as? ConfigValue.Str)?.value ?: throw ConfigError.InvalidValue("expected string")
            val rule = ruleOf(childId)?.third ?: throw ConfigError.InvalidValue("rule no longer exists")
            val scope = if (str.equals("all", true) || str.isBlank()) {
                ThinkingRule.Scope.AllModels
            } else {
                ThinkingRule.Scope.ModelPattern(str)
            }
            repo.saveThinkingRule(instanceId, rule.copy(scope = scope), id = ruleId)
        },
    )

    private fun wireFormatField(childId: String, instanceId: String, ruleId: String) = ClosureField(
        path = "$basePath.$childId.wireFormat",
        displayName = "Wire format",
        description = "JSON {\"type\":\"reasoning_effort\",\"offValue\":\"none\"} etc. — how the thinking control appears on the wire.",
        valueSchema = ConfigSchema.Json,
        risk = ConfigRisk.SENSITIVE,
        readable = ConfigReadable(ConfigValueFormatter.Custom(::wireFormatPhrase), label = "Request format"),
        reader = {
            val json = ThinkingRuleCoding.encodeWireFormat(ruleOf(childId)?.third?.wireFormat)
            configValueFromJson(json)
        },
        writer = { v ->
            val json = jsonStringFromConfigValue(v)
            val fmt = ThinkingRuleCoding.decodeWireFormat(json)
                ?: throw ConfigError.InvalidValue("unrecognized wire format JSON")
            val rule = ruleOf(childId)?.third ?: throw ConfigError.InvalidValue("rule no longer exists")
            repo.saveThinkingRule(instanceId, rule.copy(wireFormat = fmt), id = ruleId)
        },
    )

    private fun providerField(childId: String, instanceId: String) = ReadOnlyField(
        path = "$basePath.$childId.provider",
        displayName = "Provider",
        description = "The provider instance this rule belongs to.",
        valueSchema = ConfigSchema.Str(),
        reader = {
            val label = repo.config.value.instances.find { it.id == instanceId }?.label ?: instanceId
            ConfigValue.Str("$label ($instanceId)")
        },
    )

    // ---- add / remove ----

    override fun add(payload: ConfigValue): String {
        val obj = (payload as? ConfigValue.Obj)?.value
            ?: throw ConfigError.InvalidValue("Expected JSON object")
        val instanceId = (obj["provider"] as? ConfigValue.Str)?.value
            ?: throw ConfigError.InvalidValue("`provider` (instance id) required")
        if (repo.config.value.instances.none { it.id == instanceId }) {
            throw ConfigError.InvalidValue("provider instance not found: $instanceId")
        }
        val label = (obj["label"] as? ConfigValue.Str)?.value
            ?: throw ConfigError.InvalidValue("`label` required")

        val scopeStr = (obj["scope"] as? ConfigValue.Str)?.value ?: "all"
        val scope = if (scopeStr.equals("all", true) || scopeStr.isBlank()) {
            ThinkingRule.Scope.AllModels
        } else {
            ThinkingRule.Scope.ModelPattern(scopeStr)
        }

        val wfJson = when (val wf = obj["wire_format"]) {
            is ConfigValue.Obj, is ConfigValue.Str -> jsonStringFromConfigValue(wf)
            else -> throw ConfigError.InvalidValue("`wire_format` required (JSON object)")
        }
        val fmt = ThinkingRuleCoding.decodeWireFormat(wfJson)
            ?: throw ConfigError.InvalidValue("unrecognized wire_format")

        val rule = ThinkingRule(
            kind = ThinkingRule.Kind.CUSTOM,
            scope = scope,
            wireFormat = fmt,
            label = label,
        )
        // New rules insert at the top (priority 0) — a rule overriding a built-in is
        // useless below it.
        val ruleId = repo.saveThinkingRule(instanceId, rule, id = null)
        return "$instanceId:$ruleId"
    }

    override fun remove(id: String) {
        if (id.startsWith("builtin:")) {
            throw ConfigError.PermissionDenied(
                "Built-in rules are part of the app and cannot be removed. Add a rule above one to override it.",
            )
        }
        val (instanceId, ruleId) = split(id)
            ?: throw ConfigError.InvalidValue("bad rule id: $id")
        repo.deleteThinkingRule(instanceId, ruleId)
    }

    // ---- [T-config-readable] Readable descriptions (mirrors iOS ThinkingRulesCollection) ----

    override val readableSubjectFormat: String get() = "Thinking rule “%1\$@” – %2\$@"

    override fun entityLabel(id: String): String? = ruleOf(id)?.third?.label

    override fun readableForAdd(payload: RedactedConfigValue): List<ReadableLine> {
        val d = (payload.value as? ConfigValue.Obj)?.value ?: return emptyList()
        val label = (d["label"] as? ConfigValue.Str)?.value ?: "?"
        val provider = (d["provider"] as? ConfigValue.Str)?.value
            ?.let { ConfigRefResolver.name(ConfigRefKind.ProviderInstance, it) } ?: ReadableArg.Text("?")
        val lines = mutableListOf(
            ReadableLine("Add thinking rule “%1\$@” to “%2\$@”", listOf(ReadableArg.Text(label), provider)),
        )
        d["wire_format"]?.let { wf ->
            val fmt = runCatching { ThinkingRuleCoding.decodeWireFormat(jsonStringFromConfigValue(wf)) }.getOrNull()
            if (fmt != null) {
                lines.add(ReadableLine("Request format: %@", listOf(ReadableArg.Text(com.openminis.app.ui.settings.wireFormatSummary(fmt)))))
            }
        }
        return lines
    }

    override fun readableForRemove(id: String): List<ReadableLine> {
        val (instanceId, _, rule) = ruleOf(id) ?: return emptyList()
        return listOf(
            ReadableLine(
                "Delete thinking rule “%1\$@” from “%2\$@”",
                listOf(ReadableArg.Text(rule.label), ConfigRefResolver.name(ConfigRefKind.ProviderInstance, instanceId)),
            ),
        )
    }

    private fun scopePhrase(v: ConfigValue): ReadableArg {
        val s = (v as? ConfigValue.Str)?.value ?: return ConfigReadableRenderer.genericPhrase(v)
        return if (s.equals("all", ignoreCase = true) || s.isBlank()) ReadableArg.Key("All models") else ReadableArg.Text(s)
    }

    /** wire_format JSON → the one-line summary the rule list shows. */
    private fun wireFormatPhrase(v: ConfigValue): ReadableArg {
        if (v == ConfigValue.Null || (v is ConfigValue.Str && v.value.isBlank())) return ReadableArg.Key("(none)")
        val fmt = runCatching { ThinkingRuleCoding.decodeWireFormat(jsonStringFromConfigValue(v)) }.getOrNull()
            ?: return ConfigReadableRenderer.genericPhrase(v)
        return ReadableArg.Text(com.openminis.app.ui.settings.wireFormatSummary(fmt))
    }

    // ---- JSON <-> ConfigValue bridge ----

    private fun configValueFromJson(json: String?): ConfigValue {
        if (json.isNullOrBlank()) return ConfigValue.Str("")
        return try {
            val o = JSONObject(json)
            ConfigValue.Obj(o.keys().asSequence().associateWith { k -> ConfigValue.Str(o.get(k).toString()) })
        } catch (_: Exception) {
            ConfigValue.Str(json)
        }
    }

    private fun jsonStringFromConfigValue(v: ConfigValue): String = when (v) {
        is ConfigValue.Str -> v.value
        is ConfigValue.Obj -> JSONObject().apply {
            for ((k, vv) in v.value) {
                when (vv) {
                    is ConfigValue.Str -> put(k, vv.value)
                    is ConfigValue.Int -> put(k, vv.value)
                    is ConfigValue.Obj -> put(k, JSONObject(jsonStringFromConfigValue(vv)))
                    else -> put(k, vv.toString())
                }
            }
        }.toString()
        else -> v.toString()
    }
}
