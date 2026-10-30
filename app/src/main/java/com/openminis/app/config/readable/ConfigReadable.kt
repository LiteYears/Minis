package com.openminis.app.config.readable

import android.content.Context
import com.openminis.app.R
import com.openminis.app.config.ConfigAccess
import com.openminis.app.config.ConfigCollection
import com.openminis.app.config.ConfigField
import com.openminis.app.config.ConfigRegistry
import com.openminis.app.config.ConfigSchema
import com.openminis.app.config.ConfigValue
import com.openminis.app.logging.AppLogger
import java.net.URI
import java.text.NumberFormat
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

// [T-config-readable] Human-readable descriptions of minis-config changes.
// Android port of iOS 70f7871d7 / 6bac054f2 (Shared/Config/Readable/ConfigReadable.swift).
//
// The confirmation dialog and Logs → Config Changes used to show only the raw
// path and JSON old/new values; for a model group's `entries` that is a list
// of "<instanceUUID>/<modelId>" strings nobody can read. Every writable field
// now declares, at registration, how its values read to a person
// ([ConfigReadable]), and the bridge renders one sentence per change BEFORE
// the confirmation dialog (names of things about to be removed are gone after
// the write). The same rendering is persisted with the audit row.
//
// USER-FACING ONLY. The text is for the person approving the change and
// reviewing the log. It never reaches the model: no CLI response, audit
// list/get output or user_message carries it (see ConfigBridge.auditEntryDict).
//
// This file has no Android dependencies so JVM unit tests can cover it.

// -- Declaration (per field) --

/** How a field's change reads to a person. Required for every ConfigField. */
class ConfigReadable private constructor(
    /**
     * Localization key for the field noun ("Default thinking level"). null =
     * the field's displayName, which is then also the key.
     */
    val fieldLabel: String?,
    val formatter: ConfigValueFormatter,
    /**
     * Set only by [generic]: why this field has no dedicated template.
     * Launch validation lists every writable field that has one.
     */
    val genericReason: String?,
) {
    constructor(formatter: ConfigValueFormatter, label: String? = null) : this(label, formatter, null)

    val isGeneric: Boolean get() = genericReason != null

    companion object {
        /**
         * Explicit, greppable opt-out: renders "<field>: <old> → <new>" from
         * the raw values. Every use must say why; validation logs each
         * writable one.
         */
        fun generic(reason: String): ConfigReadable = ConfigReadable(null, ConfigValueFormatter.Generic, reason)

        /** For fields that never reach the write path (read-only / hidden). */
        val notWritable: ConfigReadable = generic("not writable through minis-config")
    }
}

/** What kind of record an id refers to, for id → name resolution. */
sealed class ConfigRefKind {
    object ModelEntry : ConfigRefKind()
    object Group : ConfigRefKind()
    object ProviderInstance : ConfigRefKind()
    data class ThinkingRule(val instanceId: String) : ConfigRefKind()
    /** `entry:<id>` / `group:<id>` session model sources. */
    object ModelSource : ConfigRefKind()
    /** A sub agent definition id (`subagents.order`). */
    object SubAgent : ConfigRefKind()
}

enum class NumberUnit { PLAIN, TOKENS, SECONDS, BYTES, POINTS }

/**
 * How a value reads. All label arguments are localization keys (English
 * source strings, see ConfigReadableStrings).
 */
sealed class ConfigValueFormatter {
    /** Raw-value fallback; only reachable through [ConfigReadable.generic]. */
    object Generic : ConfigValueFormatter()
    data class Bool(val on: String = "On", val off: String = "Off") : ConfigValueFormatter()
    /** Raw enum value → label key. Must cover every case of the schema. */
    data class EnumLabels(val labels: Map<String, String>) : ConfigValueFormatter()
    /** [zero] (a key) replaces 0, e.g. "No limit". */
    data class Number(val unit: NumberUnit = NumberUnit.PLAIN, val zero: String? = null) : ConfigValueFormatter()
    data class Text(val preview: Int = 40, val empty: String = "(empty)") : ConfigValueFormatter()
    /** Long prose: reports added / removed line counts, never the content. */
    object LongText : ConfigValueFormatter()
    /** Query, fragment and user-info are dropped (they can carry tokens). */
    data class Url(val empty: String = "Default") : ConfigValueFormatter()
    object Image : ConfigValueFormatter()
    /** Never renders the value. A `$$VAR` reference names the variable only. */
    object Secret : ConfigValueFormatter()
    data class Ref(val kind: ConfigRefKind, val empty: String = "Not set") : ConfigValueFormatter()
    data class RefList(val kind: ConfigRefKind, val ordered: Boolean) : ConfigValueFormatter()
    /**
     * Plain string lists (DNS servers, modalities); [labels] maps raw → key.
     * [splitText] reads a list stored as one string (Android keeps the
     * sandbox DNS servers as a newline-separated string).
     */
    data class StringList(
        val ordered: Boolean,
        val labels: Map<String, String> = emptyMap(),
        val splitText: ((String) -> List<String>)? = null,
    ) : ConfigValueFormatter()
    /** One value → one phrase; composed into the standard change sentence. */
    class Custom(val make: (ConfigValue) -> ReadableArg) : ConfigValueFormatter()
    /** Whole sentences from (old, new); null falls back to the generic sentence. */
    class Sentence(val make: (old: ConfigValue, new: ConfigValue) -> List<ReadableLine>?) : ConfigValueFormatter()
}

// -- Rendered form (persisted) --

/**
 * One sentence: a localization key (English format string using `%@` /
 * `%n$@` placeholders, as on iOS) plus its arguments. Names are frozen as
 * literal text at write time; labels stay keys so the sentence re-renders in
 * whatever language the UI uses later.
 */
data class ReadableLine(val key: String, val args: List<ReadableArg> = emptyList())

sealed class ReadableArg {
    /** Frozen literal: a group / model / provider name, a number, a URL. */
    data class Text(val value: String) : ReadableArg()
    /** Localization key, resolved at display time. */
    data class Key(val key: String) : ReadableArg()
    data class Line(val line: ReadableLine) : ReadableArg()
    /** Items joined with the localized ", ". */
    data class Items(val items: List<ReadableArg>) : ReadableArg()
    /** Clauses joined with the localized "; ". */
    data class Clauses(val items: List<ReadableArg>) : ReadableArg()
}

/**
 * What the confirmation dialog shows and the audit row persists (`readable`
 * column). The JSON matches the iOS Codable encoding of the same type, so a
 * record reads the same on both platforms.
 */
data class ReadableSummary(
    /** Schema version of this JSON. */
    val v: Int = 1,
    /** UI language [text] was rendered in (informational). */
    val locale: String,
    /** Pre-rendered text; used when [lines] cannot be rendered. */
    val text: String,
    val lines: List<ReadableLine>,
    /**
     * Groups rows about the same record in the batch summary, e.g.
     * "groups:<id>". null = a global setting.
     */
    val subject: String?,
) {
    /** Rendered in the CURRENT UI language from [lines]; [text] if there are none. */
    fun displayText(localize: (String) -> String?): String {
        if (lines.isEmpty()) return text
        return lines.joinToString("\n") { ReadableCore.render(it, localize) }
    }

    fun jsonString(): String = JSONObject().apply {
        put("v", v)
        put("locale", locale)
        put("text", text)
        put("lines", JSONArray().also { arr -> lines.forEach { arr.put(ReadableJson.encode(it)) } })
        if (subject != null) put("subject", subject)
    }.toString()

    companion object {
        fun decode(json: String?): ReadableSummary? {
            if (json.isNullOrEmpty()) return null
            return try {
                val o = JSONObject(json)
                val arr = o.getJSONArray("lines")
                ReadableSummary(
                    v = o.optInt("v", 1),
                    locale = o.optString("locale", ""),
                    text = o.getString("text"),
                    lines = (0 until arr.length()).map { ReadableJson.decodeLine(arr.getJSONObject(it)) },
                    subject = if (o.has("subject") && !o.isNull("subject")) o.getString("subject") else null,
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Swift's synthesized Codable shape for [ReadableLine] / [ReadableArg]:
 * `{"key":"…","args":[…]}` and `{"text":{"_0":"…"}}`, `{"key":{"_0":"…"}}`,
 * `{"line":{"_0":{…}}}`, `{"list":{"_0":[…]}}`, `{"clauses":{"_0":[…]}}`.
 */
internal object ReadableJson {
    fun encode(line: ReadableLine): JSONObject = JSONObject().apply {
        put("key", line.key)
        put("args", JSONArray().also { arr -> line.args.forEach { arr.put(encode(it)) } })
    }

    private fun encode(arg: ReadableArg): JSONObject {
        val (case, payload) = when (arg) {
            is ReadableArg.Text -> "text" to arg.value
            is ReadableArg.Key -> "key" to arg.key
            is ReadableArg.Line -> "line" to encode(arg.line)
            is ReadableArg.Items -> "list" to JSONArray().also { a -> arg.items.forEach { a.put(encode(it)) } }
            is ReadableArg.Clauses -> "clauses" to JSONArray().also { a -> arg.items.forEach { a.put(encode(it)) } }
        }
        return JSONObject().put(case, JSONObject().put("_0", payload))
    }

    fun decodeLine(o: JSONObject): ReadableLine {
        val args = o.optJSONArray("args") ?: JSONArray()
        return ReadableLine(o.getString("key"), (0 until args.length()).map { decodeArg(args.getJSONObject(it)) })
    }

    private fun decodeArg(o: JSONObject): ReadableArg {
        val case = o.keys().next()
        val body = o.getJSONObject(case)
        return when (case) {
            "text" -> ReadableArg.Text(body.getString("_0"))
            "key" -> ReadableArg.Key(body.getString("_0"))
            "line" -> ReadableArg.Line(decodeLine(body.getJSONObject("_0")))
            "list" -> ReadableArg.Items(decodeArgs(body.getJSONArray("_0")))
            "clauses" -> ReadableArg.Clauses(decodeArgs(body.getJSONArray("_0")))
            else -> throw IllegalArgumentException("unknown ReadableArg case $case")
        }
    }

    private fun decodeArgs(a: JSONArray): List<ReadableArg> =
        (0 until a.length()).map { decodeArg(a.getJSONObject(it)) }
}

// -- Redaction guarantee --

/**
 * A value that has been through the same masking the audit log and the
 * confirmation dialog apply: credential keys (`apiKey`, `oauthToken`, …)
 * collapse to a fixed mask unless they are a `$$VAR` reference, and an inline
 * image data URI collapses to "<image>". The renderer only accepts this type,
 * so an unmasked secret cannot be passed to it by mistake — every factory
 * masks.
 */
class RedactedConfigValue private constructor(val value: ConfigValue) {
    companion object {
        /** A single field's value; [fieldLeaf] is the last path segment. */
        fun field(fieldLeaf: String, value: ConfigValue): RedactedConfigValue {
            var v = value
            if (fieldLeaf in ConfigValue.SECRET_KEYS) {
                val masked = (ConfigValue.Obj(mapOf(fieldLeaf to value)).redactingSecrets() as ConfigValue.Obj).value
                masked[fieldLeaf]?.let { v = it }
            }
            if (v is ConfigValue.Str && isInlineImage(v.value)) v = ConfigValue.Str("<image>")
            return RedactedConfigValue(v)
        }

        /** A collection `add` payload: every nested credential key is masked. */
        fun payload(payload: ConfigValue): RedactedConfigValue =
            RedactedConfigValue(maskImages(payload.redactingSecrets()))

        fun isInlineImage(s: String): Boolean = s.startsWith("data:image/")

        private fun maskImages(v: ConfigValue): ConfigValue = when (v) {
            is ConfigValue.Str -> if (isInlineImage(v.value)) ConfigValue.Str("<image>") else v
            is ConfigValue.Arr -> ConfigValue.Arr(v.value.map(::maskImages))
            is ConfigValue.Obj -> ConfigValue.Obj(v.value.mapValues { maskImages(it.value) })
            else -> v
        }
    }
}

// -- Pure helpers (no app state; covered by JVM tests) --

object ReadableCore {
    /** At most this many names are listed per clause; the rest are counted. */
    const val MAX_LISTED_NAMES = 5

    /** Localized separators ("、" / "；" in Chinese). */
    const val listSeparatorKey = ", "
    const val clauseSeparatorKey = "; "

    data class ListItem(val id: String, val count: Int)

    data class ListDiff(
        /** The new first element, when ordered and it changed. */
        val newFirst: String?,
        val added: List<ListItem>,
        val removed: List<ListItem>,
        /** The elements present in both lists are in a different order. */
        val reordered: Boolean,
        /** How many elements are present in both lists. */
        val keptCount: Int,
    ) {
        val isEmpty: Boolean get() = newFirst == null && added.isEmpty() && removed.isEmpty() && !reordered
    }

    /**
     * Multiset diff of two id lists. Duplicates count: [a, a] → [a] removes
     * one a. Kept elements are compared by relative order only, so an add or
     * remove never reads as a reorder.
     */
    fun listDiff(old: List<String>, new: List<String>, ordered: Boolean): ListDiff {
        val oldCount = old.groupingBy { it }.eachCount()
        val newCount = new.groupingBy { it }.eachCount()

        val added = new.distinct().mapNotNull { id ->
            val d = (newCount[id] ?: 0) - (oldCount[id] ?: 0)
            if (d > 0) ListItem(id, d) else null
        }
        val removed = old.distinct().mapNotNull { id ->
            val d = (oldCount[id] ?: 0) - (newCount[id] ?: 0)
            if (d > 0) ListItem(id, d) else null
        }

        fun kept(list: List<String>): List<String> {
            val budget = HashMap<String, Int>()
            for ((id, n) in oldCount) budget[id] = minOf(n, newCount[id] ?: 0)
            val out = ArrayList<String>()
            for (id in list) {
                val b = budget[id] ?: 0
                if (b > 0) {
                    budget[id] = b - 1
                    out.add(id)
                }
            }
            return out
        }
        val keptOld = kept(old)
        val keptNew = kept(new)

        val first = new.firstOrNull()
        val newFirst = if (ordered && first != null && first != old.firstOrNull()) first else null
        return ListDiff(newFirst, added, removed, reordered = keptOld != keptNew, keptCount = keptNew.size)
    }

    private class Placeholder(val start: Int, val end: Int, val position: Int?)

    /**
     * Parse `%@` / `%n$@` (iOS keys) and `%s` / `%n$s` (Android resources)
     * placeholders; `%%` is literal. null when the format uses any other
     * conversion or mixes sequential and positional placeholders.
     */
    private fun placeholders(format: String): List<Placeholder>? {
        val out = ArrayList<Placeholder>()
        var sequential = false
        var positional = false
        var i = 0
        while (i < format.length) {
            if (format[i] != '%') { i++; continue }
            val start = i
            i++
            if (i >= format.length) return null
            if (format[i] == '%') { i++; continue }
            if (format[i] == '@' || format[i] == 's') {
                sequential = true
                out.add(Placeholder(start, i + 1, null))
                i++
                continue
            }
            val digitsStart = i
            while (i < format.length && format[i].isDigit()) i++
            if (i == digitsStart || i + 1 >= format.length || format[i] != '$' ||
                (format[i + 1] != '@' && format[i + 1] != 's')
            ) return null
            val n = format.substring(digitsStart, i).toIntOrNull() ?: return null
            if (n < 1) return null
            positional = true
            out.add(Placeholder(start, i + 2, n))
            i += 2
        }
        if (sequential && positional) return null
        return out
    }

    /**
     * Number of arguments a format consumes; -1 when the format is unusable
     * here. Mirrors iOS `placeholderCount`, also accepting Android's `s`.
     */
    fun placeholderCount(format: String): Int {
        val ps = placeholders(format) ?: return -1
        if (ps.isEmpty()) return 0
        return if (ps.first().position == null) ps.size else ps.maxOf { it.position!! }
    }

    /** Substitute [args] into [format]; the caller checked [placeholderCount]. */
    private fun substitute(format: String, args: List<String>): String {
        val ps = placeholders(format) ?: return format
        val sb = StringBuilder()
        var last = 0
        var seq = 0
        var i = 0
        while (i < format.length) {
            val p = ps.firstOrNull { it.start == i }
            if (p != null) {
                sb.append(format, last, p.start)
                val idx = p.position?.minus(1) ?: seq++
                sb.append(args.getOrElse(idx) { "" })
                i = p.end
                last = i
                continue
            }
            if (format[i] == '%' && i + 1 < format.length && format[i + 1] == '%') {
                sb.append(format, last, i).append('%')
                i += 2
                last = i
                continue
            }
            i++
        }
        sb.append(format, last, format.length)
        return sb.toString()
    }

    /**
     * Render with [localize] (key → format in the current language; null =
     * no translation). A translation whose placeholders do not match the
     * arguments is ignored in favour of the English key, so a bad
     * translation can never crash formatting or drop an argument.
     */
    fun render(line: ReadableLine, localize: (String) -> String?): String {
        val args = line.args.map { render(it, localize) }
        var format = localize(line.key) ?: line.key
        if (placeholderCount(format) != args.size) format = line.key
        if (placeholderCount(format) != args.size) return (listOf(line.key) + args).joinToString(" ")
        return substitute(format, args)
    }

    fun render(arg: ReadableArg, localize: (String) -> String?): String = when (arg) {
        is ReadableArg.Text -> arg.value
        is ReadableArg.Key -> localize(arg.key) ?: arg.key
        is ReadableArg.Line -> render(arg.line, localize)
        is ReadableArg.Items -> arg.items.joinToString(localize(listSeparatorKey) ?: listSeparatorKey) {
            render(it, localize)
        }
        is ReadableArg.Clauses -> arg.items.joinToString(localize(clauseSeparatorKey) ?: clauseSeparatorKey) {
            render(it, localize)
        }
    }

    /** Quote and shorten free text for a sentence. */
    fun quoted(s: String, preview: Int): String {
        val flat = s.replace("\n", " ")
        val short = if (flat.codePointCount(0, flat.length) > preview) {
            flat.substring(0, flat.offsetByCodePoints(0, preview)) + "…"
        } else {
            flat
        }
        return "“$short”"
    }

    /** Lines added / removed between two texts (multiset of lines). */
    fun lineDelta(old: String, new: String): Pair<Int, Int> {
        val o = if (old.isEmpty()) emptyList() else old.split("\n")
        val n = if (new.isEmpty()) emptyList() else new.split("\n")
        val d = listDiff(o, n, ordered = false)
        return d.added.sumOf { it.count } to d.removed.sumOf { it.count }
    }

    /** Drops user-info, query and fragment: any of them can carry a token. */
    fun safeURL(s: String): String {
        val uri = try { URI(s) } catch (_: Exception) { null }
        if (uri == null || uri.scheme == null || uri.isOpaque) {
            return s.substringBefore('?').substringBefore('#')
        }
        return try {
            URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
        } catch (_: Exception) {
            s.substringBefore('?').substringBefore('#')
        }
    }

    /** 64000 → "64K", 131072 → "128K", 1_048_576 → "1M", 1500 → "1,500". */
    fun compactCount(n: Int): String {
        if (n >= 1_000_000 && n % 1_000_000 == 0) return "${n / 1_000_000}M"
        if (n >= 1_048_576 && n % 1_048_576 == 0) return "${n / 1_048_576}M"
        if (n >= 1_000 && n % 1_000 == 0) return "${n / 1_000}K"
        if (n >= 1_024 && n % 1_024 == 0) return "${n / 1_024}K"
        return NumberFormat.getIntegerInstance(Locale.US).format(n)
    }

    /** Up to two decimals, no trailing zeros. */
    fun plainNumber(d: Double): String {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        val f = NumberFormat.getNumberInstance(Locale.US)
        f.minimumFractionDigits = 0
        f.maximumFractionDigits = 2
        f.minimumIntegerDigits = 1
        f.isGroupingUsed = false
        return f.format(d)
    }
}

// ─── Merged from ConfigReadableStrings.kt ──────────────────────────────────────────────────

/**
 * [T-config-readable] Readable-description key (the English source string, as
 * in iOS Localizable.xcstrings) → its Android string resource. The sentences
 * are persisted as keys, so lookup goes by the English text, not by a
 * resource name.
 *
 * Adding a key: add it here and to strings_config_readable.xml in values,
 * values-zh and values-zh-rTW (plus any locale iOS translates it into).
 * ConfigReadableKeysTest fails on a key used in the config sources that is
 * missing here, or on one without a Chinese translation.
 */
object ConfigReadableStrings {
    val ids: Map<String, Int> = mapOf(
        "%1\$@ and %2\$@ more" to R.string.cfgr_x_and_x_more,
        "%1\$@ ×%2\$@" to R.string.cfgr_x_x,
        "%1\$@: %2\$@" to R.string.cfgr_x_x_2,
        "%1\$@: %2\$@ (unchanged)" to R.string.cfgr_x_x_unchanged,
        "%1\$@: %2\$@ → %3\$@" to R.string.cfgr_x_x_x,
        "%1\$@: edited (+%2\$@ / −%3\$@ lines, %4\$@ characters now)" to R.string.cfgr_x_edited_x_x_lines_x_characters_now,
        "%1\$@: now uses the environment variable \$%2\$@" to R.string.cfgr_x_now_uses_the_environment_variable_x,
        "%1\$@: removed %2\$@" to R.string.cfgr_x_removed_x,
        "%@ items" to R.string.cfgr_x_items,
        "%@ pt" to R.string.cfgr_x_pt,
        "%@ s" to R.string.cfgr_x_s,
        "%@ tokens" to R.string.cfgr_x_tokens,
        "%@: cleared" to R.string.cfgr_x_cleared,
        "%@: new entry added" to R.string.cfgr_x_new_entry_added,
        "%@: unchanged" to R.string.cfgr_x_unchanged,
        "%@: updated (value hidden)" to R.string.cfgr_x_updated_value_hidden,
        "(empty)" to R.string.cfgr_empty,
        "(none)" to R.string.cfgr_none,
        "(settings object)" to R.string.cfgr_settings_object,
        ", " to R.string.cfgr_list_separator,
        "1 minute before" to R.string.cfgr_1_minute_before,
        "15 minutes before" to R.string.cfgr_15_minutes_before,
        "5 minutes before" to R.string.cfgr_5_minutes_before,
        "; " to R.string.cfgr_clause_separator,
        "API Key" to R.string.cfgr_api_key,
        "API key from the environment variable \$%@" to R.string.cfgr_api_key_from_the_environment_variable_x,
        "API key provided (value hidden)" to R.string.cfgr_api_key_provided_value_hidden,
        "Add custom model %1\$@ to “%2\$@”" to R.string.cfgr_add_custom_model_x_to_x,
        "Add provider “%1\$@” (%2\$@)" to R.string.cfgr_add_provider_x_x,
        "Add thinking rule “%1\$@” to “%2\$@”" to R.string.cfgr_add_thinking_rule_x_to_x,
        "Agent loop groups" to R.string.cfgr_agent_loop_groups,
        "Agent loop model entries" to R.string.cfgr_agent_loop_model_entries,
        "All models" to R.string.cfgr_all_models,
        "Allow minis-config" to R.string.cfgr_allow_minis_config,
        "Also on transient errors" to R.string.cfgr_also_on_transient_errors,
        "App language" to R.string.cfgr_app_language,
        "Append /v1 suffix" to R.string.cfgr_append_v1_suffix,
        "Applies to" to R.string.cfgr_applies_to,
        "Audio input" to R.string.cfgr_audio_input,
        "Audio output" to R.string.cfgr_audio_output,
        "Auto-focus input after reply" to R.string.cfgr_auto_focus_input_after_reply,
        "Automatic" to R.string.cfgr_automatic,
        "Automatic (system)" to R.string.cfgr_automatic_system,
        "Azure OpenAI mode" to R.string.cfgr_azure_openai_mode,
        "Background notifications" to R.string.cfgr_background_notifications,
        "Base URL: %@" to R.string.cfgr_base_url_x,
        "Chinese" to R.string.cfgr_chinese,
        "Container DNS mode" to R.string.cfgr_container_dns_mode,
        "Container custom DNS servers" to R.string.cfgr_container_custom_dns_servers,
        "Context limit (tokens)" to R.string.cfgr_context_limit_tokens,
        "Context window override (tokens)" to R.string.cfgr_context_window_override_tokens,
        "Create group “%1\$@” with %2\$@ models" to R.string.cfgr_create_group_x_with_x_models,
        "Custom" to R.string.cfgr_custom,
        "Custom User-Agent" to R.string.cfgr_custom_user_agent,
        "Custom base URL" to R.string.cfgr_custom_base_url,
        "Custom image" to R.string.cfgr_custom_image,
        "Dark" to R.string.cfgr_dark,
        "Default" to R.string.cfgr_default,
        "Default icon" to R.string.cfgr_default_icon,
        "Default primary group" to R.string.cfgr_default_primary_group,
        "Default sub group" to R.string.cfgr_default_sub_group,
        "Default thinking level" to R.string.cfgr_default_thinking_level,
        "Delete custom model %@" to R.string.cfgr_delete_custom_model_x,
        "Delete environment variable \$%@ (its value is removed from Keychain)" to R.string.cfgr_delete_environment_variable_x_its_value_is_remov,
        "Delete group “%1\$@” (%2\$@ models)" to R.string.cfgr_delete_group_x_x_models,
        "Delete provider “%1\$@” and its %2\$@ models" to R.string.cfgr_delete_provider_x_and_its_x_models,
        "Delete thinking rule “%1\$@” from “%2\$@”" to R.string.cfgr_delete_thinking_rule_x_from_x,
        "Disabled" to R.string.cfgr_disabled,
        "Display name" to R.string.cfgr_display_name,
        "Enabled" to R.string.cfgr_enabled,
        "English" to R.string.cfgr_english,
        "Enhanced background execution" to R.string.cfgr_enhanced_background_execution,
        "Environment variable \$%1\$@ – %2\$@" to R.string.cfgr_environment_variable_x_x,
        "Environment variables" to R.string.cfgr_environment_variables,
        "Extra large" to R.string.cfgr_extra_large,
        "Extra small" to R.string.cfgr_extra_small,
        "Fallback (in order)" to R.string.cfgr_fallback_in_order,
        "Fallback policy" to R.string.cfgr_fallback_policy,
        "Group “%1\$@” – %2\$@" to R.string.cfgr_group_x_x,
        "Hidden" to R.string.cfgr_hidden,
        "High" to R.string.cfgr_high,
        "Image input" to R.string.cfgr_image_input,
        "Image output" to R.string.cfgr_image_output,
        "Input font size" to R.string.cfgr_input_font_size,
        "It is also in: %@" to R.string.cfgr_it_is_also_in_x,
        "Keep screen awake during tasks" to R.string.cfgr_keep_screen_awake_during_tasks,
        "LLM models" to R.string.cfgr_llm_models,
        "LLM provider instances" to R.string.cfgr_llm_provider_instances,
        "Label" to R.string.cfgr_label,
        "Large" to R.string.cfgr_large,
        "Light" to R.string.cfgr_light,
        "Live Updates (dynamic island)" to R.string.cfgr_live_updates_dynamic_island,
        "Load balance" to R.string.cfgr_load_balance,
        "Logging enabled" to R.string.cfgr_logging_enabled,
        "Low" to R.string.cfgr_low,
        "Max" to R.string.cfgr_max,
        "Max output tokens" to R.string.cfgr_max_output_tokens,
        "Med" to R.string.cfgr_med,
        "Medium" to R.string.cfgr_medium,
        "Memory enabled (global default)" to R.string.cfgr_memory_enabled_global_default,
        "Message font size" to R.string.cfgr_message_font_size,
        "Modalities override" to R.string.cfgr_modalities_override,
        "Model %1\$@ – %2\$@" to R.string.cfgr_model_x_x,
        "Model groups" to R.string.cfgr_model_groups,
        "Models" to R.string.cfgr_models,
        "Name" to R.string.cfgr_name,
        "New line" to R.string.cfgr_new_line,
        "No API key yet" to R.string.cfgr_no_api_key_yet,
        "No limit" to R.string.cfgr_no_limit,
        "Not set" to R.string.cfgr_not_set,
        "Note" to R.string.cfgr_note,
        "Off" to R.string.cfgr_off,
        "On" to R.string.cfgr_on,
        "Only when the provider rejects" to R.string.cfgr_only_when_the_provider_rejects,
        "PDF input" to R.string.cfgr_pdf_input,
        "Primary model (current session)" to R.string.cfgr_primary_model_current_session,
        "Primary model: %@" to R.string.cfgr_primary_model_x,
        "Privacy Mode" to R.string.cfgr_privacy_mode,
        "Provider “%1\$@” – %2\$@" to R.string.cfgr_provider_x_x,
        "Request format" to R.string.cfgr_request_format,
        "Request format: %@" to R.string.cfgr_request_format_x,
        "Return key behavior" to R.string.cfgr_return_key_behavior,
        "Routing strategy" to R.string.cfgr_routing_strategy,
        "Scheduled task countdown" to R.string.cfgr_scheduled_task_countdown,
        "Send" to R.string.cfgr_send,
        "Show chat title pill" to R.string.cfgr_show_chat_title_pill,
        "Shown" to R.string.cfgr_shown,
        "Small" to R.string.cfgr_small,
        "Soul icon" to R.string.cfgr_soul_icon,
        "Soul language preference" to R.string.cfgr_soul_language_preference,
        "Soul name" to R.string.cfgr_soul_name,
        "Soul personality body" to R.string.cfgr_soul_personality_body,
        "Soul style" to R.string.cfgr_soul_style,
        "Status" to R.string.cfgr_status,
        "System" to R.string.cfgr_system,
        "Text input" to R.string.cfgr_text_input,
        "Text output" to R.string.cfgr_text_output,
        "Theme" to R.string.cfgr_theme,
        "Thinking level (current session)" to R.string.cfgr_thinking_level_current_session,
        "Thinking rule “%1\$@” – %2\$@" to R.string.cfgr_thinking_rule_x_x,
        "Thinking rules" to R.string.cfgr_thinking_rules,
        "Tool preview" to R.string.cfgr_tool_preview,
        "Fold Consecutive Tool Calls" to R.string.cfgr_fold_consecutive_tool_calls,
        "Ultra" to R.string.cfgr_ultra,
        "Use OpenAI Responses API" to R.string.cfgr_use_openai_responses_api,
        "Video input" to R.string.cfgr_video_input,
        "Video output" to R.string.cfgr_video_output,
        "Visibility" to R.string.cfgr_visibility,
        "Whole wait" to R.string.cfgr_whole_wait,
        "XHigh" to R.string.cfgr_xhigh,
        "added %@" to R.string.cfgr_added_x,
        "Confirm before stopping" to R.string.cfgr_confirm_before_stopping,
        "first is now %@" to R.string.cfgr_first_is_now_x,
        "group “%@”" to R.string.cfgr_group_x,
        "now empty" to R.string.cfgr_now_empty,
        "order changed" to R.string.cfgr_order_changed,
        "primary is now %@" to R.string.cfgr_primary_is_now_x,
        "removed %@" to R.string.cfgr_removed_x,
        "rest of the order unchanged" to R.string.cfgr_rest_of_the_order_unchanged,
        "unknown group %@" to R.string.cfgr_unknown_group_x,
        "unknown model %@" to R.string.cfgr_unknown_model_x,
        "unknown provider %@" to R.string.cfgr_unknown_provider_x,
        "unknown rule %@" to R.string.cfgr_unknown_rule_x,
        // [T-minis-config-coverage]
        "User interaction tool (user_ask)" to R.string.cfgr_user_interaction_tool_user_ask,
        "Question timeout (minutes)" to R.string.cfgr_question_timeout_minutes,
        "Wait indefinitely" to R.string.cfgr_wait_indefinitely,
        "Notify about questions in the background" to R.string.cfgr_notify_about_questions_in_the_background,
        "Browser Use tool" to R.string.cfgr_browser_use_tool,
        "Sub Agents tool" to R.string.cfgr_sub_agents_tool,
        "Expand Thinking While Streaming" to R.string.cfgr_expand_thinking_while_streaming,
        "Speech volume" to R.string.cfgr_speech_volume,
        "Show scheduled task count on Home" to R.string.cfgr_show_scheduled_task_count_on_home,
        "Update channel" to R.string.cfgr_update_channel,
        "Stable" to R.string.cfgr_stable,
        "Beta" to R.string.cfgr_beta,
        "Check for updates automatically" to R.string.cfgr_check_for_updates_automatically,
        "Backup device name" to R.string.cfgr_backup_device_name,
        "App font size" to R.string.cfgr_app_font_size,
        "Response idle timeout" to R.string.cfgr_response_idle_timeout,
        "Temperature" to R.string.cfgr_temperature,
        "Top P" to R.string.cfgr_top_p,
        "Sub agents" to R.string.cfgr_sub_agents,
        "Sub agent “%1\$@” – %2\$@" to R.string.cfgr_sub_agent_x_x,
        "Create sub agent “%@”" to R.string.cfgr_create_sub_agent_x,
        "Model group: %@" to R.string.cfgr_model_group_x,
        "Delete sub agent “%@”" to R.string.cfgr_delete_sub_agent_x,
        "Auto" to R.string.cfgr_auto,
        "Description" to R.string.cfgr_description,
        "Instructions" to R.string.cfgr_instructions,
        "Model group" to R.string.cfgr_model_group,
        "Reasoning override" to R.string.cfgr_reasoning_override,
        "Sub agent order" to R.string.cfgr_sub_agent_order,
        "unknown sub agent %@" to R.string.cfgr_unknown_sub_agent_x,
        "Auto-Grouping" to R.string.cfgr_auto_grouping,
        "Speed" to R.string.cfgr_speed,
        "Standard" to R.string.cfgr_standard,
        "Fast" to R.string.cfgr_fast,
        "Ultrafast" to R.string.cfgr_ultrafast,
        "Show Hidden Files" to R.string.cfgr_show_hidden_files,
        // [FEAT-261021-03] Settings > Network (minis-config network.* / proxies.*).
        "Network Routing" to R.string.cfgr_network_routing,
        "Default Route" to R.string.cfgr_default_route,
        "Route for Shell" to R.string.cfgr_route_for_shell,
        "Route for Browser" to R.string.cfgr_route_for_browser,
        "Route for MCP" to R.string.cfgr_route_for_mcp,
        "Route for Backup" to R.string.cfgr_route_for_backup,
        "Use Tailscale" to R.string.cfgr_use_tailscale,
        "Device Name" to R.string.cfgr_device_name,
        "Use Subnet Routes" to R.string.cfgr_use_subnet_routes,
        "Exit Node" to R.string.cfgr_exit_node,
        "Exit Node DNS" to R.string.cfgr_exit_node_dns,
        "Block Shell Traffic When the Exit Node Is Unreachable" to R.string.cfgr_exit_node_fail_closed,
        "Tailscale login" to R.string.cfgr_tailscale_login,
        "Tailscale auth key" to R.string.cfgr_tailscale_auth_key,
        "Sign Out and Clear" to R.string.cfgr_sign_out_and_clear,
        "Logged in" to R.string.cfgr_logged_in,
        "Signed out" to R.string.cfgr_signed_out,
        "Sign out and delete the device key" to R.string.cfgr_sign_out_and_delete_the_device_key,
        "No change" to R.string.cfgr_no_change,
        "Follow Default" to R.string.cfgr_follow_default,
        "Direct" to R.string.cfgr_direct,
        "None" to R.string.cfgr_none_value,
        "Network" to R.string.cfgr_network,
        "Proxies" to R.string.cfgr_proxies,
        "Type" to R.string.cfgr_type,
        "Server" to R.string.cfgr_server,
        "Port" to R.string.cfgr_port,
        "Username" to R.string.cfgr_username,
        "Password" to R.string.cfgr_password,
        "Bypass Hosts" to R.string.cfgr_bypass_hosts,
        "SOCKS5" to R.string.cfgr_socks5,
        "HTTP" to R.string.cfgr_http,
        "HTTPS" to R.string.cfgr_https,
        "Add %1\$@ proxy %2\$@" to R.string.cfgr_add_x_proxy_x,
        "Password provided (value hidden)" to R.string.cfgr_password_provided_value_hidden,
        "Delete proxy “%@”; routes that used it connect directly" to R.string.cfgr_delete_proxy_x_routes_that_used_it_connect_directly,
        "Proxy “%1\$@” – %2\$@" to R.string.cfgr_proxy_x_x,
    )

    /** The format for [key] in [context]'s language, or null for an unknown key. */
    fun lookup(context: Context, key: String): String? =
        ids[key]?.let { id -> runCatching { context.getString(id) }.getOrNull() }
}

// ─── Merged from ConfigReadableRenderer.kt ──────────────────────────────────────────────────

// [T-config-readable] App-side half of the readable descriptions: id → name
// resolution against the live stores, sentence assembly per formatter, and the
// launch-time template validation. The pure pieces live in ConfigReadable.kt.
// Port of iOS ConfigReadableRenderer.swift.
//
// Every sentence produced here is USER-FACING ONLY (confirmation dialog + Logs
// → Config Changes). Nothing here may be returned to the CLI or the model.

/** Names for ids, backed by the live stores; swappable in tests. */
interface ReadableNameSource {
    /** "Claude Sonnet 5.5 (Anthropic(53))", or null when no entry has this id. */
    fun modelEntryLabel(id: String): String?
    /** The instance label, its provider type's name when blank, or null. */
    fun instanceLabel(id: String): String?
    fun groupName(id: String): String?
    fun thinkingRuleLabel(instanceId: String, ruleId: String): String?
    /** Names of the groups that list this model entry. */
    fun groupsContaining(entryId: String): List<String>
    fun modelCountOf(instanceId: String): Int
    /** The sub agent's name; a default body, so name sources written before it still compile. */
    fun subAgentName(id: String): String? = null
}

private object NoNames : ReadableNameSource {
    override fun modelEntryLabel(id: String): String? = null
    override fun instanceLabel(id: String): String? = null
    override fun groupName(id: String): String? = null
    override fun thinkingRuleLabel(instanceId: String, ruleId: String): String? = null
    override fun groupsContaining(entryId: String): List<String> = emptyList()
    override fun modelCountOf(instanceId: String): Int = 0
}

object ConfigRefResolver {
    @Volatile var names: ReadableNameSource = NoNames

    /**
     * The user-facing name of a record, frozen as literal text. Unknown ids
     * read "unknown model 1234abcd…" rather than disappearing.
     */
    fun name(kind: ConfigRefKind, id: String): ReadableArg = when (kind) {
        ConfigRefKind.ModelEntry -> {
            val label = names.modelEntryLabel(id)
            when {
                label != null -> ReadableArg.Text(label)
                // A composite "<instance>/<model>" still names the model even
                // after the entry is gone.
                id.contains('/') -> {
                    val inst = id.substringBefore('/')
                    val model = id.substringAfter('/')
                    val instLabel = names.instanceLabel(inst)
                    unknown("unknown model %@", if (instLabel != null) "$model ($instLabel)" else model)
                }
                else -> unknown("unknown model %@", short(id))
            }
        }
        ConfigRefKind.Group ->
            names.groupName(id)?.let { ReadableArg.Text(it) } ?: unknown("unknown group %@", short(id))
        ConfigRefKind.ProviderInstance ->
            names.instanceLabel(id)?.let { ReadableArg.Text(it) } ?: unknown("unknown provider %@", short(id))
        is ConfigRefKind.ThinkingRule ->
            names.thinkingRuleLabel(kind.instanceId, id)?.let { ReadableArg.Text(it) }
                ?: unknown("unknown rule %@", short(id))
        ConfigRefKind.ModelSource -> when {
            id.startsWith("entry:") -> name(ConfigRefKind.ModelEntry, id.removePrefix("entry:"))
            id.startsWith("group:") -> ReadableArg.Line(
                ReadableLine("group “%@”", listOf(name(ConfigRefKind.Group, id.removePrefix("group:")))),
            )
            else -> ReadableArg.Text(id)
        }
        ConfigRefKind.SubAgent ->
            names.subAgentName(id)?.let { ReadableArg.Text(it) } ?: unknown("unknown sub agent %@", short(id))
    }

    private fun unknown(key: String, text: String) = ReadableArg.Line(ReadableLine(key, listOf(ReadableArg.Text(text))))

    fun short(id: String): String = if (id.length > 12) id.take(8) + "…" else id
}

object ConfigReadableRenderer {
    private const val TAG = "ConfigReadable"

    /** First element of a model list = the group's primary model. */
    const val primaryFirstKey = "primary is now %@"
    const val plainFirstKey = "first is now %@"

    /**
     * Key → format in the in-app UI language (null = no translation), and
     * that language's tag. Installed by ConfigBuiltins with the app context;
     * the defaults leave every key in English (JVM tests).
     */
    @Volatile var localizerFactory: () -> (String) -> String? = { { null } }
    @Volatile var currentLanguage: () -> String = { Locale.getDefault().toLanguageTag() }
    @Volatile var currentLocale: () -> Locale = { Locale.getDefault() }

    fun summary(lines: List<ReadableLine>, subject: String?): ReadableSummary {
        val localize = localizerFactory()
        val text = lines.joinToString("\n") { ReadableCore.render(it, localize) }
        return ReadableSummary(locale = currentLanguage(), text = text, lines = lines, subject = subject)
    }

    // -- Field writes (set / .append / .remove) --

    /**
     * Sentences for one field write. `.append` / `.remove` arrive as the whole
     * old and new arrays, so the list diff describes them naturally. Never
     * fails: anything unexpected falls back to the generic sentence.
     */
    fun forFieldWrite(field: ConfigField, old: RedactedConfigValue, new: RedactedConfigValue): ReadableSummary {
        val spec = field.readable
        val label = spec.fieldLabel ?: field.displayName
        var phrase: ReadableArg = ReadableArg.Key(label)
        var subject: String? = null
        val split = ConfigRegistry.splitCollectionPath(field.path)
        if (split != null) {
            val (base, id, _) = split
            val coll = runCatching { ConfigRegistry.get().collection(base) }.getOrNull()
            val entity = coll?.let { runCatching { it.entityLabel(id) }.getOrNull() }
            if (coll != null && entity != null) {
                phrase = ReadableArg.Line(
                    ReadableLine(coll.readableSubjectFormat, listOf(ReadableArg.Text(entity), ReadableArg.Key(label))),
                )
                subject = "$base:$id"
            }
        }
        val lines = try {
            sentences(spec.formatter, phrase, old.value, new.value)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[ConfigReadable] fallback path=${field.path} reason=${t.message}")
            null
        } ?: listOf(genericLine(phrase, old.value, new.value))
        if (spec.isGeneric) {
            AppLogger.info(TAG, "[ConfigReadable] generic sentence for ${field.path} (${spec.genericReason})")
        }
        return summary(lines, subject)
    }

    private fun sentences(
        f: ConfigValueFormatter,
        phrase: ReadableArg,
        old: ConfigValue,
        new: ConfigValue,
    ): List<ReadableLine>? = when (f) {
        ConfigValueFormatter.Generic -> null
        ConfigValueFormatter.Secret -> {
            val s = (new as? ConfigValue.Str)?.value
            if (s != null && s.startsWith("\$\$") && s.length > 2) {
                listOf(
                    ReadableLine(
                        "%1\$@: now uses the environment variable $%2\$@",
                        listOf(phrase, ReadableArg.Text(s.drop(2))),
                    ),
                )
            } else {
                listOf(ReadableLine("%@: updated (value hidden)", listOf(phrase)))
            }
        }
        ConfigValueFormatter.LongText -> {
            val o = stringOrEmpty(old)
            val n = stringOrEmpty(new)
            when {
                o == null || n == null -> null
                o == n -> listOf(ReadableLine("%@: unchanged", listOf(phrase)))
                n.isEmpty() -> listOf(ReadableLine("%@: cleared", listOf(phrase)))
                else -> {
                    val (added, removed) = ReadableCore.lineDelta(o, n)
                    listOf(
                        ReadableLine(
                            "%1\$@: edited (+%2\$@ / −%3\$@ lines, %4\$@ characters now)",
                            listOf(
                                phrase, ReadableArg.Text("$added"), ReadableArg.Text("$removed"),
                                ReadableArg.Text("${n.codePointCount(0, n.length)}"),
                            ),
                        ),
                    )
                }
            }
        }
        is ConfigValueFormatter.RefList -> {
            val firstKey = if (f.kind == ConfigRefKind.ModelEntry) primaryFirstKey else plainFirstKey
            listLine(phrase, strings(old), strings(new), f.ordered, firstKey) { ConfigRefResolver.name(f.kind, it) }
                ?.let { listOf(it) }
        }
        is ConfigValueFormatter.StringList -> {
            val split = f.splitText
            fun items(v: ConfigValue): List<String>? =
                if (split != null && v is ConfigValue.Str) split(v.value) else strings(v)
            listLine(phrase, items(old), items(new), f.ordered, plainFirstKey) { raw ->
                f.labels[raw]?.let { ReadableArg.Key(it) } ?: ReadableArg.Text(raw)
            }?.let { listOf(it) }
        }
        is ConfigValueFormatter.Sentence -> f.make(old, new)
        else -> {
            val o = phraseFor(f, old)
            val n = phraseFor(f, new)
            if (o == null || n == null) null else listOf(changeLine(phrase, o, n))
        }
    }

    /** "<field>: <old> → <new>", or "<field>: <value> (unchanged)". */
    fun changeLine(phrase: ReadableArg, old: ReadableArg, new: ReadableArg): ReadableLine =
        if (old == new) {
            ReadableLine("%1\$@: %2\$@ (unchanged)", listOf(phrase, new))
        } else {
            ReadableLine("%1\$@: %2\$@ → %3\$@", listOf(phrase, old, new))
        }

    fun genericLine(phrase: ReadableArg, old: ConfigValue, new: ConfigValue): ReadableLine =
        changeLine(phrase, genericPhrase(old), genericPhrase(new))

    /** Raw-value phrase for the fallback sentence. */
    fun genericPhrase(v: ConfigValue): ReadableArg = when (v) {
        ConfigValue.Null -> ReadableArg.Key("(none)")
        is ConfigValue.Bool -> ReadableArg.Key(if (v.value) "On" else "Off")
        is ConfigValue.Int -> ReadableArg.Text(v.value.toString())
        is ConfigValue.Double -> ReadableArg.Text(ReadableCore.plainNumber(v.value))
        is ConfigValue.Str ->
            if (v.value.isEmpty()) ReadableArg.Key("(empty)") else ReadableArg.Text(ReadableCore.quoted(v.value, 40))
        is ConfigValue.Arr -> ReadableArg.Line(ReadableLine("%@ items", listOf(ReadableArg.Text("${v.value.size}"))))
        is ConfigValue.Obj -> ReadableArg.Key("(settings object)")
    }

    /**
     * One value → phrase for the value formatters; null when the value does
     * not have the shape the formatter expects.
     */
    fun phraseFor(f: ConfigValueFormatter, v: ConfigValue): ReadableArg? = when (f) {
        is ConfigValueFormatter.Bool -> (v as? ConfigValue.Bool)?.let { ReadableArg.Key(if (it.value) f.on else f.off) }
        is ConfigValueFormatter.EnumLabels -> (v as? ConfigValue.Str)?.value?.let { s ->
            f.labels[s]?.let { ReadableArg.Key(it) }
                ?: if (s.isEmpty()) ReadableArg.Key("(empty)") else ReadableArg.Text(s)
        }
        is ConfigValueFormatter.Number -> {
            val d = when (v) {
                is ConfigValue.Int -> v.value.toDouble()
                is ConfigValue.Double -> v.value
                else -> null
            }
            when {
                d == null -> null
                d == 0.0 && f.zero != null -> ReadableArg.Key(f.zero)
                else -> when (f.unit) {
                    NumberUnit.PLAIN -> ReadableArg.Text(ReadableCore.plainNumber(d))
                    NumberUnit.TOKENS -> ReadableArg.Line(
                        ReadableLine("%@ tokens", listOf(ReadableArg.Text(ReadableCore.compactCount(d.toInt())))),
                    )
                    NumberUnit.SECONDS -> ReadableArg.Line(
                        ReadableLine("%@ s", listOf(ReadableArg.Text(ReadableCore.plainNumber(d)))),
                    )
                    NumberUnit.POINTS -> ReadableArg.Line(
                        ReadableLine("%@ pt", listOf(ReadableArg.Text(ReadableCore.plainNumber(d)))),
                    )
                    NumberUnit.BYTES -> ReadableArg.Text(byteCount(d.toLong()))
                }
            }
        }
        is ConfigValueFormatter.Text -> (v as? ConfigValue.Str)?.value?.let { s ->
            if (s.isEmpty()) ReadableArg.Key(f.empty) else ReadableArg.Text(ReadableCore.quoted(s, f.preview))
        }
        is ConfigValueFormatter.Url -> (v as? ConfigValue.Str)?.value?.let { s ->
            if (s.isEmpty()) ReadableArg.Key(f.empty) else ReadableArg.Text(ReadableCore.safeURL(s))
        }
        ConfigValueFormatter.Image -> (v as? ConfigValue.Str)?.value?.let { s -> imagePhrase(s) }
        is ConfigValueFormatter.Ref -> (v as? ConfigValue.Str)?.value?.let { s ->
            if (s.isEmpty()) ReadableArg.Key(f.empty) else ConfigRefResolver.name(f.kind, s)
        }
        is ConfigValueFormatter.Custom -> f.make(v)
        ConfigValueFormatter.Generic, ConfigValueFormatter.Secret, ConfigValueFormatter.LongText,
        is ConfigValueFormatter.RefList, is ConfigValueFormatter.StringList, is ConfigValueFormatter.Sentence,
        -> null
    }

    /**
     * The Soul icon. Android also accepts bare base64 and minis:// / /var/minis
     * paths as an image source (iOS only data URIs), so anything that is not a
     * single short glyph reads "Custom image" — never the payload or a path.
     */
    private fun imagePhrase(s: String): ReadableArg = when {
        s.isEmpty() -> ReadableArg.Key("Default icon")
        s == "<image>" || RedactedConfigValue.isInlineImage(s) -> ReadableArg.Key("Custom image")
        s.codePointCount(0, s.length) <= 8 && s.none { it == '/' || it == ':' } -> ReadableArg.Text(s)
        else -> ReadableArg.Key("Custom image")
    }

    /** 1536 → "1.5 KB", 10_000_000 → "10 MB" (decimal units, like iOS .file). */
    private fun byteCount(n: Long): String {
        if (n < 1000) return "$n bytes"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = n.toDouble() / 1000
        var i = 0
        while (v >= 1000 && i < units.lastIndex) { v /= 1000; i++ }
        return "${ReadableCore.plainNumber(if (v >= 100) Math.rint(v) else Math.rint(v * 10) / 10)} ${units[i]}"
    }

    // -- Lists --

    fun listLine(
        phrase: ReadableArg,
        old: List<String>?,
        new: List<String>?,
        ordered: Boolean,
        firstKey: String,
        name: (String) -> ReadableArg,
    ): ReadableLine? {
        if (old == null || new == null) return null
        val d = ReadableCore.listDiff(old, new, ordered)
        if (d.isEmpty) return ReadableLine("%@: unchanged", listOf(phrase))
        val clauses = ArrayList<ReadableArg>()
        d.newFirst?.let { clauses.add(ReadableArg.Line(ReadableLine(firstKey, listOf(name(it))))) }
        if (d.added.isNotEmpty()) clauses.add(ReadableArg.Line(ReadableLine("added %@", listOf(nameList(d.added, name)))))
        if (d.removed.isNotEmpty()) clauses.add(ReadableArg.Line(ReadableLine("removed %@", listOf(nameList(d.removed, name)))))
        if (d.reordered) {
            clauses.add(ReadableArg.Key("order changed"))
        } else if (ordered && d.keptCount > 0 && clauses.isNotEmpty()) {
            clauses.add(ReadableArg.Key("rest of the order unchanged"))
        }
        if (new.isEmpty()) clauses.add(ReadableArg.Key("now empty"))
        return ReadableLine("%1\$@: %2\$@", listOf(phrase, ReadableArg.Clauses(clauses)))
    }

    /**
     * Names merged by what they read as (two ids that name the same thing
     * count together), "×N" for repeats, the first few then "+N more".
     */
    fun nameList(items: List<ReadableCore.ListItem>, name: (String) -> ReadableArg): ReadableArg {
        val counts = LinkedHashMap<ReadableArg, Int>()
        for (item in items) {
            val n = name(item.id)
            counts[n] = (counts[n] ?: 0) + item.count
        }
        val rendered = counts.map { (n, c) ->
            if (c > 1) ReadableArg.Line(ReadableLine("%1\$@ ×%2\$@", listOf(n, ReadableArg.Text("$c")))) else n
        }
        val cap = ReadableCore.MAX_LISTED_NAMES
        if (rendered.size <= cap) return ReadableArg.Items(rendered)
        return ReadableArg.Line(
            ReadableLine(
                "%1\$@ and %2\$@ more",
                listOf(ReadableArg.Items(rendered.take(cap)), ReadableArg.Text("${rendered.size - cap}")),
            ),
        )
    }

    private fun strings(v: ConfigValue): List<String>? = when (v) {
        ConfigValue.Null -> emptyList()
        is ConfigValue.Arr -> v.value.map { (it as? ConfigValue.Str)?.value ?: it.displayString }
        else -> null
    }

    private fun stringOrEmpty(v: ConfigValue): String? = when (v) {
        ConfigValue.Null -> ""
        is ConfigValue.Str -> v.value
        else -> null
    }

    // -- Collection add / remove --

    fun forCollectionAdd(c: ConfigCollection, payload: RedactedConfigValue): ReadableSummary {
        val lines = runCatching { c.readableForAdd(payload) }.getOrElse {
            AppLogger.warning(TAG, "[ConfigReadable] fallback path=${c.basePath}.append reason=${it.message}")
            emptyList()
        }.ifEmpty { listOf(ReadableLine("%@: new entry added", listOf(ReadableArg.Key(c.displayName)))) }
        return summary(lines, "${c.basePath}:new")
    }

    /** Rendered BEFORE the removal, so the record is still named. */
    fun forCollectionRemove(c: ConfigCollection, childId: String): ReadableSummary {
        val lines = runCatching { c.readableForRemove(childId) }.getOrElse {
            AppLogger.warning(TAG, "[ConfigReadable] fallback path=${c.basePath}.remove reason=${it.message}")
            emptyList()
        }.ifEmpty {
            val label = runCatching { c.entityLabel(childId) }.getOrNull() ?: childId
            listOf(ReadableLine("%1\$@: removed %2\$@", listOf(ReadableArg.Key(c.displayName), ReadableArg.Text(label))))
        }
        return summary(lines, "${c.basePath}:$childId")
    }

    // -- Launch validation --

    /**
     * Logs every writable field still on the generic template and every enum
     * label table that misses a case of its schema — the strong reminder
     * when a new setting lands. iOS additionally asserts in DEBUG; here a
     * gap is an error log plus a unit test (ConfigReadableKeysTest), so a
     * debug build never crashes at launch over a missing label.
     */
    fun validateRegistry(fields: List<ConfigField>): List<String> {
        var writable = 0
        val generic = ArrayList<String>()
        val gaps = ArrayList<String>()
        for (f in fields) {
            if (f.access != ConfigAccess.READWRITE) continue
            writable++
            val spec = f.readable
            if (spec.isGeneric) generic.add("${f.path} (${spec.genericReason})")
            val labels = (spec.formatter as? ConfigValueFormatter.EnumLabels)?.labels ?: continue
            for (c in enumCases(f.valueSchema)) if (c !in labels) gaps.add("${f.path}: '$c'")
        }
        AppLogger.info(
            TAG,
            "[ConfigReadable] validated $writable writable fields; generic=${generic.size}" +
                if (generic.isEmpty()) "" else " " + generic.joinToString(", "),
        )
        if (gaps.isNotEmpty()) AppLogger.error(TAG, "[ConfigReadable] enum label gaps: ${gaps.joinToString("; ")}")
        return gaps
    }

    private fun enumCases(s: ConfigSchema): List<String> = when (s) {
        is ConfigSchema.StrEnum -> s.cases
        is ConfigSchema.Optional -> enumCases(s.inner)
        else -> emptyList()
    }
}

/** [T-config-readable] Raw thinking token → the label keys iOS uses. */
object ReadableThinkingLabels {
    val levels: Map<String, String> = mapOf(
        "off" to "Off", "low" to "Low", "medium" to "Med", "high" to "High",
        "xhigh" to "XHigh", "max" to "Max", "ultra" to "Ultra",
    )

    /** "" = not set (sessions start with thinking off / the model default). */
    val withNotSet: Map<String, String> = levels + ("" to "Not set")
}

/** [ReadableNameSource] over the live provider config. */
class ProviderRepositoryNames(
    private val repo: com.openminis.app.data.repository.ProviderRepository,
) : ReadableNameSource {
    private val cfg get() = repo.config.value

    override fun modelEntryLabel(id: String): String? {
        val e = cfg.modelEntries.firstOrNull { it.id == id } ?: return null
        val model = e.model.displayName.ifEmpty { e.baseModel.id }
        val label = instanceLabel(e.providerInstanceId) ?: return model
        return "$model ($label)"
    }

    override fun instanceLabel(id: String): String? {
        val inst = cfg.instances.firstOrNull { it.id == id } ?: return null
        return inst.label.trim().ifEmpty { inst.providerType.displayName }
    }

    override fun groupName(id: String): String? = cfg.modelGroups.firstOrNull { it.id == id }?.name

    override fun thinkingRuleLabel(instanceId: String, ruleId: String): String? {
        val idx = repo.thinkingRuleIds(instanceId).indexOf(ruleId)
        if (idx < 0) return null
        return repo.thinkingRules(instanceId).getOrNull(idx)?.label
    }

    override fun groupsContaining(entryId: String): List<String> =
        cfg.modelGroups.filter { entryId in it.memberEntryIds }.map { it.name }

    override fun modelCountOf(instanceId: String): Int = cfg.modelEntries.count { it.providerInstanceId == instanceId }

    override fun subAgentName(id: String): String? = repo.subAgent(id)?.name
}
