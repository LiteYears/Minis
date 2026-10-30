package com.openminis.app.config

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.config.audit.ConfigAuditActor
import com.openminis.app.config.audit.ConfigAuditEntry
import com.openminis.app.config.audit.ConfigAuditLog
import com.openminis.app.config.audit.ConfigAuditStatus
import com.openminis.app.config.confirm.ConfigConfirmationGate
import com.openminis.app.config.confirm.ConfirmOutcome
import com.openminis.app.config.confirm.PendingConfigChange
import com.openminis.app.config.confirm.PendingConfigChangeItem
import com.openminis.app.config.readable.ConfigReadable
import com.openminis.app.config.readable.ConfigReadableRenderer
import com.openminis.app.config.readable.ReadableLine
import com.openminis.app.config.readable.ReadableSummary
import com.openminis.app.config.readable.RedactedConfigValue
import com.openminis.app.logging.AppLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Bridge between the offload handler (Kotlin / shell args / JSON
 * stdout) and the rest of the Config module (registry, gate, audit
 * log). Mirrors iOS `ConfigOffloadBridge.swift`.
 *
 * Returns plain [JSONObject] envelopes so the handler just wraps them
 * in [com.openminis.app.sandbox.NativeOffloadResult]. The handler
 * thread is on a background pool, so write paths block via runBlocking
 * on the gate suspend — matching the iOS semaphore-on-detached-Task
 * pattern.
 */
object ConfigBridge {
    private const val TAG = "ConfigBridge"
    private const val DEFAULT_PAGE_SIZE = 20
    private const val MAX_PAGE_SIZE = 100

    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** First-line gate — returns true when the master switch is on. */
    fun isEnabled(): Boolean = MinisConfigPermissionStore.isEnabled

    /**
     * [T-android-config-feature-unavailable] Envelope for a field whose feature
     * does not exist on this device / OS version. Deliberately a DISTINCT code
     * from `permission_denied` so the agent knows this isn't something the user
     * can grant or the agent can retry into — the hardware/OS simply lacks it.
     * Mirrors iOS `unavailableErrorEnvelope` (dba46d42).
     */
    fun unavailableErrorEnvelope(path: String, reason: String): JSONObject = JSONObject().apply {
        put("ok", false)
        put("error", "feature_unavailable")
        put("reason", reason)
        put("user_message", "The setting '$path' isn't available on this device: $reason")
    }

    /** Standardised "minis-config disabled" envelope. Includes user_message. */
    fun disabledErrorEnvelope(): JSONObject = JSONObject().apply {
        put("ok", false)
        put("error", "permission_denied")
        put("reason", "minis-config is disabled in Settings → Permissions.")
        put(
            "user_message",
            "I tried to change a setting but minis-config is currently disabled. " +
                "You can enable it at [Settings → Permissions](minis://settings/permissions), " +
                "then ask me again. Or change the setting yourself directly through the relevant Settings screen."
        )
    }

    // -- list-topics / topic-help --

    /**
     * [T-config-path-dotted-id] OpenMinis#390. For a collection topic, how to
     * write a child path — surfaced by `topic-help` next to the field list,
     * whose example paths now carry raw ids. Null for flat topics. Same text
     * as iOS `ConfigOffloadBridge.pathNoteForTopic` (cef38f13b).
     */
    fun pathNoteForTopic(topic: String): String? {
        if (ConfigRegistry.get().collection(topic) == null) return null
        return "Child paths are $topic.<entry_id>.<field>. Use entry_id exactly as `minis-config get $topic` prints it — dots and slashes need no escaping."
    }

    fun allTopics(): JSONArray =
        JSONArray().also { arr -> for (t in ConfigRegistry.get().topics()) arr.put(t) }

    fun fieldsForTopic(topic: String): JSONArray =
        JSONArray().also { arr ->
            for (f in ConfigRegistry.get().fields(topic = topic)) {
                arr.put(JSONObject().apply {
                    put("path", f.path)
                    put("display_name", f.displayName)
                    put("description", f.description)
                    put("schema", f.valueSchema.helpDescription)
                    put("access", f.access.name.lowercase())
                    put("risk", f.risk.name.lowercase())
                    put("revertable", f.revertable)
                })
            }
        }

    // -- read --

    /**
     * Read a single field with optional filter + pagination. Both
     * filter and pagination only apply to JSON arrays; scalar values
     * pass through unchanged. Mirrors the iOS bridge contract exactly.
     */
    fun readField(
        path: String,
        filter: String? = null,
        page: Int = 0,
        pageSize: Int = 0,
    ): JSONObject {
        val base = readFieldRaw(path)
        if (base.optBoolean("ok", false) != true) return base

        val filterText = filter?.trim()?.takeIf { it.isNotEmpty() }
        val terms: List<String> = filterText
            ?.lowercase()
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val valueJSON = base.optString("value", "null")
        val parsed = ConfigValue.decode(valueJSON) ?: return base

        fun matches(candidate: ConfigValue): Boolean {
            if (terms.isEmpty()) return true
            val hay = candidate.jsonString().lowercase()
            return terms.all { hay.contains(it) }
        }

        val out = JSONObject()
        // Copy base.
        val keys = base.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out.put(k, base.opt(k))
        }

        if (parsed is ConfigValue.Arr) {
            val totalRaw = parsed.value.size
            val postFilter = if (terms.isEmpty()) parsed.value else parsed.value.filter(::matches)
            val postFilterCount = postFilter.size

            if (terms.isNotEmpty()) {
                out.put("filtered", true)
                out.put("filter", filterText ?: "")
                out.put("total", totalRaw)
                out.put("matched", postFilterCount)
            }

            val wantsPagination = page > 0 || pageSize > 0
            if (wantsPagination) {
                val size = (if (pageSize > 0) pageSize else DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)
                val totalPages = maxOf(1, (postFilterCount + size - 1) / size)
                val requestedPage = if (page > 0) page else 1
                val pageSlice: List<ConfigValue> =
                    if (requestedPage < 1 || requestedPage > totalPages || postFilterCount == 0) {
                        emptyList()
                    } else {
                        val start = (requestedPage - 1) * size
                        val end = minOf(start + size, postFilterCount)
                        postFilter.subList(start, end)
                    }
                out.put("value", ConfigValue.Arr(pageSlice).jsonString())
                val hasNext = requestedPage < totalPages && postFilterCount > 0
                val hasPrev = requestedPage > 1
                out.put("pagination", JSONObject().apply {
                    put("page", requestedPage)
                    put("page_size", size)
                    put("total", postFilterCount)
                    put("total_pages", totalPages)
                    put("has_next", hasNext)
                    put("has_prev", hasPrev)
                })
                out.put(
                    "agent_hint",
                    paginationHint(
                        path = path, filter = filterText,
                        page = requestedPage, pageSize = size,
                        totalPages = totalPages, total = postFilterCount,
                        pageCount = pageSlice.size,
                    )
                )
            } else if (terms.isNotEmpty()) {
                out.put("value", ConfigValue.Arr(postFilter).jsonString())
            }
        } else if (terms.isNotEmpty()) {
            // Scalar value with filter — pass-or-null.
            val kept: ConfigValue = if (matches(parsed)) parsed else ConfigValue.Null
            out.put("value", kept.jsonString())
            out.put("filtered", true)
            out.put("filter", filterText ?: "")
            out.put("total", 1)
            out.put("matched", if (kept === ConfigValue.Null) 0 else 1)
        }

        return out
    }

    private fun readFieldRaw(path: String): JSONObject {
        if (!MinisConfigPermissionStore.isEnabled) return disabledErrorEnvelope()
        val field = ConfigRegistry.get().resolveField(path) ?: return JSONObject().apply {
            put("ok", false)
            put("error", "unknown_path")
            // [T-config-path-dotted-id] Says which part was wrong.
            put("reason", ConfigRegistry.get().explainUnknownPath(path))
        }
        if (field.access == ConfigAccess.HIDDEN) return JSONObject().apply {
            put("ok", false)
            put("error", "permission_denied")
            put("reason", "'$path' is intentionally not exposed to minis-config.")
        }
        // [T-android-config-feature-unavailable] The device/OS lacks the
        // feature entirely — answer precisely instead of reporting a value the
        // user can't act on.
        field.unavailableReason?.let { return unavailableErrorEnvelope(path, it) }
        return try {
            val v = field.read()
            JSONObject().apply {
                put("ok", true)
                put("value", v.jsonString())
                put("schema", field.valueSchema.helpDescription)
                put("display_name", field.displayName)
            }
        } catch (e: ConfigError.PermissionDenied) {
            // [T-minis-config-provider-add] Forward PermissionDenied verbatim
            // — used by fields that are editable but unreadable (notably
            // providers.<id>.apiKey: writes accepted, reads guarded). Without
            // this branch the previous generic catch reported "read_failed"
            // which is misleading and would confuse the agent's retry logic.
            JSONObject().apply {
                put("ok", false)
                put("error", "permission_denied")
                put("reason", e.message ?: "Read access denied.")
            }
        } catch (e: Throwable) {
            JSONObject().apply {
                put("ok", false)
                put("error", "read_failed")
                put("reason", e.message ?: e.toString())
            }
        }
    }

    private fun paginationHint(
        path: String,
        filter: String?,
        page: Int,
        pageSize: Int,
        totalPages: Int,
        total: Int,
        pageCount: Int,
    ): String {
        val filterFragment = if (filter.isNullOrEmpty()) "" else " --filter \"$filter\""
        if (total == 0) {
            return if (!filter.isNullOrEmpty()) {
                "No items match filter '$filter' under '$path'."
            } else {
                "No items under '$path'."
            }
        }
        if (page > totalPages) {
            val pgWord = if (totalPages == 1) "page" else "pages"
            return "Page $page is out of range (only $totalPages $pgWord available). " +
                "Try: minis-config get $path$filterFragment --page $totalPages"
        }
        if (totalPages <= 1) {
            val itemWord = if (total == 1) "item" else "items"
            return "Showing all $total $itemWord (1 page)."
        }
        if (page < totalPages) {
            return "Showing page $page of $totalPages ($pageCount of $total items). " +
                "To get more, use: minis-config get $path$filterFragment --page ${page + 1} --page-size $pageSize"
        }
        return "Showing page $page of $totalPages ($pageCount of $total items). This is the last page."
    }

    // -- write (single or batch) --

    /**
     * Write one or more fields. Each item is a JSON object
     * `{ "path": ..., "value_json": ... }`. Returns the standard
     * envelope including `applied[]`, `audit_ids[]`, `audit_url`,
     * and `user_message`.
     */
    fun writeFields(
        items: JSONArray,
        caption: String?,
        actorRaw: String,
        sessionId: String?,
    ): JSONObject {
        if (!MinisConfigPermissionStore.isEnabled) return disabledErrorEnvelope()
        // Bridge runs on the offload worker thread; the gate itself
        // does its own context switch to Main for the dialog.
        return runBlocking {
            performWriteBatch(
                items = items,
                caption = caption,
                actorRaw = actorRaw,
                sessionId = sessionId,
                skipConfirmation = false,
            )
        }
    }

    /**
     * Internal write path — used by both [writeFields] and the audit-
     * revert flow. `skipConfirmation` is for user-initiated reverts
     * from the in-app Logs UI: the user already confirmed via a local
     * dialog, so the gate would be redundant.
     */
    suspend fun performWriteBatch(
        items: JSONArray,
        caption: String?,
        actorRaw: String,
        sessionId: String?,
        skipConfirmation: Boolean,
    ): JSONObject {
        // 1. Resolve every path; reject the entire batch on any unknown
        //    or hidden path before bothering the user.
        val resolved = ArrayList<Resolved>(items.length())
        val resolvedItems = ArrayList<PendingConfigChangeItem>(items.length())
        // [T-config-readable] One user-facing description per resolved item,
        // index-aligned with `resolved`. Rendered HERE, before the dialog and
        // before any write, so a record about to be removed is still named.
        // Goes to the dialog row and the audit row ONLY — never into the CLI
        // response (applied[], errors, user_message stay as they were).
        val readables = ArrayList<ReadableSummary>(items.length())
        for (i in 0 until items.length()) {
            val raw = items.optJSONObject(i) ?: continue
            val rawPath = raw.optString("path", "")
            val valueJSON = raw.optString("value_json", "null")

            val isAppend = rawPath.endsWith(".append")
            val isRemove = !isAppend && rawPath.endsWith(".remove")
            val resolvePath = when {
                isAppend -> rawPath.removeSuffix(".append")
                isRemove -> rawPath.removeSuffix(".remove")
                else -> rawPath
            }

            // Collection-level append/remove: when `<base>.append` or
            // `<base>.remove` addresses a registered ConfigCollection
            // (not a field), route to collection.add() / remove(). The
            // collection owns dedup, validation, and is_custom enforcement.
            if ((isAppend || isRemove) && ConfigRegistry.get().collection(resolvePath) != null) {
                val coll = ConfigRegistry.get().collection(resolvePath)!!
                val parsedValue = ConfigValue.decode(valueJSON) ?: return JSONObject().apply {
                    put("ok", false)
                    put("error", "invalid_value")
                    put("reason", "Value JSON is not parseable for '$rawPath'.")
                }
                if (isAppend && !coll.addable) {
                    return JSONObject().apply {
                        put("ok", false)
                        put("error", "permission_denied")
                        put("reason", "Collection '${coll.basePath}' does not allow .append.")
                    }
                }
                if (isRemove && !coll.removable) {
                    return JSONObject().apply {
                        put("ok", false)
                        put("error", "permission_denied")
                        put("reason", "Collection '${coll.basePath}' does not allow .remove.")
                    }
                }
                // [T-minis-config-provider-add] Redact credential values
                // (apiKey / oauthToken / manualOAuthToken) BEFORE they reach
                // the confirmation sheet or the audit log. The collection's
                // add() still receives the un-redacted `parsedValue` so it
                // can persist the real secret to the encrypted store; only
                // the displayed / audit-logged copy is masked. A `$$ENV`
                // reference passes through (it's a pointer, not the
                // secret). Mirrors iOS ConfigOffloadBridge:435-441.
                val redactedNewValue = if (isAppend) parsedValue.redactingSecrets() else parsedValue
                resolved.add(
                    Resolved(
                        field = null,
                        collection = coll,
                        collectionVerb = if (isAppend) CollectionVerb.APPEND else CollectionVerb.REMOVE,
                        rawPath = rawPath,
                        scope = coll.basePath,
                        oldValue = ConfigValue.Null,
                        newValue = parsedValue,
                        // Audit / display copy is always the redacted form;
                        // the executor still applies `newValue` un-redacted.
                        auditNewValue = redactedNewValue,
                    )
                )
                val readable = if (isAppend) {
                    ConfigReadableRenderer.forCollectionAdd(coll, RedactedConfigValue.payload(parsedValue))
                } else {
                    ConfigReadableRenderer.forCollectionRemove(
                        coll,
                        (parsedValue as? ConfigValue.Str)?.value ?: parsedValue.displayString,
                    )
                }
                readables.add(readable)
                resolvedItems.add(
                    PendingConfigChangeItem(
                        displayName = coll.displayName,
                        path = rawPath,
                        oldDisplay = if (isAppend) "" else parsedValue.displayString,
                        newDisplay = if (isAppend) redactedNewValue.displayString else "",
                        verb = if (isAppend) "add" else "remove",
                        risk = coll.risk,
                        readable = readable,
                    )
                )
                continue
            }

            val field = ConfigRegistry.get().resolveField(resolvePath)
                ?: return JSONObject().apply {
                    put("ok", false)
                    put("error", "unknown_path")
                    // [T-config-path-dotted-id] Says which part was wrong.
                    put("reason", ConfigRegistry.get().explainUnknownPath(resolvePath))
                }

            // [T-android-config-feature-unavailable] Feature-unavailable gate
            // runs BEFORE the access check, before validation, and before the
            // confirmation dialog — never ask the user to approve a change that
            // cannot possibly apply on this device (e.g. the Android 16 Live
            // Updates surface on hardware without it).
            field.unavailableReason?.let { return unavailableErrorEnvelope(rawPath, it) }

            if (field.access != ConfigAccess.READWRITE) {
                val reason = if (field.access == ConfigAccess.HIDDEN) {
                    "'$rawPath' is intentionally not exposed to minis-config."
                } else {
                    "'$rawPath' is read-only."
                }
                return JSONObject().apply {
                    put("ok", false)
                    put("error", "permission_denied")
                    put("reason", reason)
                }
            }

            val parsedValue = ConfigValue.decode(valueJSON) ?: return JSONObject().apply {
                put("ok", false)
                put("error", "invalid_value")
                put("reason", "Value JSON is not parseable for '$rawPath'.")
            }

            val oldValue: ConfigValue = try {
                field.read()
            } catch (_: Throwable) {
                ConfigValue.Null
            }

            val newValue: ConfigValue
            val displayVerb: String
            val displayPath: String
            val displayOld: String
            val displayNew: String

            when {
                isAppend -> {
                    val arrSchema = field.valueSchema as? ConfigSchema.Array
                        ?: return JSONObject().apply {
                            put("ok", false)
                            put("error", "invalid_value")
                            put("reason", "'.append' is only valid on array-typed fields; '${field.path}' is not an array.")
                        }
                    try {
                        arrSchema.inner.validate(parsedValue)
                    } catch (e: ConfigError) {
                        return JSONObject().apply {
                            put("ok", false)
                            put("error", "validation_failed")
                            put("reason", e.message ?: e.toString())
                        }
                    }
                    val existing = (oldValue as? ConfigValue.Arr)?.value ?: emptyList()
                    newValue = ConfigValue.Arr(existing + parsedValue)
                    displayVerb = "append"
                    displayPath = rawPath
                    displayOld = ""
                    displayNew = parsedValue.displayString
                }
                isRemove -> {
                    if (field.valueSchema !is ConfigSchema.Array) {
                        return JSONObject().apply {
                            put("ok", false)
                            put("error", "invalid_value")
                            put("reason", "'.remove' is only valid on array-typed fields; '${field.path}' is not an array.")
                        }
                    }
                    val existing = (oldValue as? ConfigValue.Arr)?.value ?: emptyList()
                    val filtered = existing.filter { it != parsedValue }
                    if (filtered.size == existing.size) {
                        return JSONObject().apply {
                            put("ok", false)
                            put("error", "not_found")
                            put("reason", "No occurrence of value ${parsedValue.displayString} in '${field.path}'.")
                        }
                    }
                    newValue = ConfigValue.Arr(filtered)
                    displayVerb = "remove"
                    displayPath = rawPath
                    displayOld = parsedValue.displayString
                    displayNew = ""
                }
                else -> {
                    // [FEAT-261021-03] normalize -> schema -> field checks,
                    // all before the confirmation dialog.
                    val normalized = try {
                        field.normalize(parsedValue).also {
                            field.valueSchema.validate(it)
                            field.validate(it)
                        }
                    } catch (e: ConfigError) {
                        return JSONObject().apply {
                            put("ok", false)
                            put("error", "validation_failed")
                            put("reason", e.message ?: e.toString())
                        }
                    }
                    newValue = normalized
                    displayVerb = "set"
                    displayPath = field.path
                    displayOld = oldValue.displayString
                    displayNew = newValue.displayString
                }
            }

            // [T-minis-config-provider-add] Mask credential writes the
            // same way collection-add does: detect by the path's last
            // segment matching ConfigValue.SECRET_KEYS (apiKey /
            // oauthToken / manualOAuthToken). Field still gets the un-
            // redacted newValue (so the writer can persist the real
            // secret); only audit / display copies are masked. `$$ENV`
            // references are preserved by redactingSecrets.
            val isSecretField = field.path.substringAfterLast('.') in ConfigValue.SECRET_KEYS
            val redactedNewValue = if (isSecretField) maskScalarSecret(newValue) else newValue
            resolved.add(
                Resolved(
                    field = field,
                    collection = null,
                    collectionVerb = null,
                    rawPath = field.path,
                    scope = field.scope,
                    oldValue = oldValue,
                    newValue = newValue,
                    auditNewValue = if (isSecretField) redactedNewValue else null,
                )
            )
            // [T-config-readable] Both sides go through RedactedConfigValue —
            // the same masking as the audit copy — so no secret can reach the
            // rendered text.
            val fieldLeaf = field.path.substringAfterLast('.')
            val readable = ConfigReadableRenderer.forFieldWrite(
                field,
                old = RedactedConfigValue.field(fieldLeaf, oldValue),
                new = RedactedConfigValue.field(fieldLeaf, newValue),
            )
            readables.add(readable)
            resolvedItems.add(
                PendingConfigChangeItem(
                    displayName = field.displayName,
                    path = displayPath,
                    oldDisplay = displayOld,
                    newDisplay = if (isSecretField) redactedNewValue.displayString else displayNew,
                    verb = displayVerb,
                    risk = field.risk,
                    readable = readable,
                )
            )
        }

        // 2. Enqueue confirmation. Suspends until user acts or timeout —
        //    or skipped when the caller already collected user consent
        //    (see [skipConfirmation] doc).
        val outcome: ConfirmOutcome = if (skipConfirmation) {
            ConfirmOutcome.Approved(resolvedItems)
        } else {
            val pending = PendingConfigChange(items = resolvedItems, caption = caption)
            ConfigConfirmationGate.requestConfirmation(pending)
        }

        // 3. Map outcome → audit + user_message.
        val actor = ConfigAuditActor.fromRaw(actorRaw)
        val audit = ConfigAuditLog.get()
        return when (outcome) {
            is ConfirmOutcome.TimedOut -> {
                val now = System.currentTimeMillis()
                for ((i, r) in resolved.withIndex()) {
                    audit.append(
                        ConfigAuditEntry(
                            id = UUID.randomUUID().toString(),
                            at = now, actor = actor, sessionId = sessionId,
                            scope = r.scope, key = r.rawPath,
                            oldValueJSON = r.oldValue.jsonString(),
                            newValueJSON = r.newValue.jsonString(),
                            confirmedAt = null, status = ConfigAuditStatus.TIMEOUT,
                            revertOf = null, caption = caption,
                            readable = readables.getOrNull(i),
                        )
                    )
                }
                JSONObject().apply {
                    put("ok", false)
                    put("error", "timeout")
                    put("reason", "Confirmation timed out after ${ConfigConfirmationGate.TIMEOUT_MS / 1000}s.")
                    put("user_message", "I waited but you didn't confirm the change. Tap me again if you want me to retry.")
                }
            }
            is ConfirmOutcome.Rejected -> {
                val now = System.currentTimeMillis()
                for ((i, r) in resolved.withIndex()) {
                    audit.append(
                        ConfigAuditEntry(
                            id = UUID.randomUUID().toString(),
                            at = now, actor = actor, sessionId = sessionId,
                            scope = r.scope, key = r.rawPath,
                            oldValueJSON = r.oldValue.jsonString(),
                            newValueJSON = r.newValue.jsonString(),
                            confirmedAt = now, status = ConfigAuditStatus.REJECTED,
                            revertOf = null, caption = caption,
                            readable = readables.getOrNull(i),
                        )
                    )
                }
                JSONObject().apply {
                    put("ok", false)
                    put("error", "user_rejected")
                    put("reason", "User cancelled the change.")
                    put("user_message", "Change cancelled.")
                }
            }
            is ConfirmOutcome.Approved -> ConfigWriteWarnings.collect {
                val now = System.currentTimeMillis()
                val applied = JSONArray()
                val auditIds = JSONArray()
                // Per-row error envelope captured for any collection.add
                // that surfaces a structural error (already_exists,
                // permission_denied) — we abort the batch and surface
                // that as the call's primary error so the agent's
                // contract holds even when the user pre-approved.
                var earlyError: JSONObject? = null
                for ((idx, row) in outcome.items.withIndex()) {
                    if (idx >= resolved.size) continue
                    val r = resolved[idx]
                    val readable = readables.getOrNull(idx)
                    if (!row.isApproved) {
                        val auditId = UUID.randomUUID().toString()
                        audit.append(
                            ConfigAuditEntry(
                                id = auditId, at = now, actor = actor, sessionId = sessionId,
                                scope = r.scope, key = r.rawPath,
                                oldValueJSON = r.oldValue.jsonString(),
                                newValueJSON = r.newValue.jsonString(),
                                confirmedAt = now, status = ConfigAuditStatus.REJECTED,
                                revertOf = null, caption = caption,
                                readable = readable,
                            )
                        )
                        continue
                    }
                    try {
                        // Two op kinds: field write vs collection add/remove.
                        var effectiveOldJSON = r.oldValue.jsonString()
                        var effectiveNewJSON = r.newValue.jsonString()
                        var effectiveOldDisplay = r.oldValue.displayString
                        var effectiveNewDisplay = r.newValue.displayString
                        val displayName: String
                        val displayPath: String
                        if (r.collection != null && r.collectionVerb != null) {
                            when (r.collectionVerb) {
                                CollectionVerb.APPEND -> {
                                    // newValue is the un-redacted payload —
                                    // the collection persists the real
                                    // credential. Audit / display rows pull
                                    // from auditNewValue (redacted copy)
                                    // when supplied so the secret never
                                    // lands in the audit DB.
                                    val newId = r.collection.add(r.newValue)
                                    val auditPayload = r.auditNewValue ?: r.newValue
                                    val withId = augmentWithEntryId(auditPayload, newId)
                                    effectiveNewJSON = withId.jsonString()
                                    effectiveNewDisplay = withId.displayString
                                }
                                CollectionVerb.REMOVE -> {
                                    val entryId = (r.newValue as? ConfigValue.Str)?.value
                                        ?: throw ConfigError.InvalidValue(
                                            "${r.collection.basePath}.remove expects a string entry id."
                                        )
                                    // Snapshot the entry pre-removal so
                                    // revert can re-add it verbatim.
                                    val snapshot = snapshotCollectionChild(r.collection, entryId)
                                    r.collection.remove(entryId)
                                    effectiveOldJSON = snapshot.jsonString()
                                    effectiveOldDisplay = snapshot.displayString
                                    effectiveNewJSON = ConfigValue.Null.jsonString()
                                    effectiveNewDisplay = ""
                                }
                            }
                            displayName = r.collection.displayName
                            displayPath = r.rawPath
                        } else if (r.field != null) {
                            r.field.write(r.newValue)
                            displayName = r.field.displayName
                            displayPath = r.field.path
                            // [T-minis-config-provider-add] If this is a
                            // credential field, audit/display rows pull
                            // from the redacted copy so the secret never
                            // reaches the audit DB or the user's screen.
                            if (r.auditNewValue != null) {
                                effectiveNewJSON = r.auditNewValue.jsonString()
                                effectiveNewDisplay = r.auditNewValue.displayString
                            }
                        } else {
                            continue
                        }
                        val auditId = UUID.randomUUID().toString()
                        audit.append(
                            ConfigAuditEntry(
                                id = auditId, at = now, actor = actor, sessionId = sessionId,
                                scope = r.scope, key = r.rawPath,
                                oldValueJSON = effectiveOldJSON,
                                newValueJSON = effectiveNewJSON,
                                confirmedAt = now, status = ConfigAuditStatus.APPLIED,
                                revertOf = null, caption = caption,
                                readable = readable,
                            )
                        )
                        auditIds.put(auditId)
                        applied.put(JSONObject().apply {
                            put("path", displayPath)
                            put("display_name", displayName)
                            put("old", effectiveOldDisplay)
                            put("new", effectiveNewDisplay)
                        })
                    } catch (e: ConfigError.AlreadyExists) {
                        if (earlyError == null) {
                            earlyError = JSONObject().apply {
                                put("ok", false)
                                put("error", "already_exists")
                                put("reason", e.message ?: "already exists")
                            }
                        }
                        AppLogger.warning(TAG, "${r.rawPath}: ${e.message}")
                    } catch (e: ConfigError.PermissionDenied) {
                        if (earlyError == null) {
                            earlyError = JSONObject().apply {
                                put("ok", false)
                                put("error", "permission_denied")
                                put("reason", e.message ?: "permission denied")
                            }
                        }
                        AppLogger.warning(TAG, "${r.rawPath}: ${e.message}")
                    } catch (e: ConfigError) {
                        if (earlyError == null) {
                            earlyError = JSONObject().apply {
                                put("ok", false)
                                put("error", "invalid_value")
                                put("reason", e.message ?: "invalid value")
                            }
                        }
                        AppLogger.warning(TAG, "${r.rawPath}: ${e.message}")
                    } catch (e: Throwable) {
                        AppLogger.error(TAG, "write failed for ${r.rawPath}: ${e.message}")
                    }
                }
                if (applied.length() == 0 && earlyError != null) {
                    earlyError!!
                } else if (applied.length() == 0) {
                    JSONObject().apply {
                        put("ok", false)
                        put("error", "all_rejected")
                        put("reason", "User rejected every row.")
                        put("user_message", "No changes applied.")
                    }
                } else {
                    JSONObject().apply {
                        put("ok", true)
                        put("applied", applied)
                        put("audit_ids", auditIds)
                        put("audit_url", "minis://settings/logs?tab=config-audit")
                        put("user_message", "Settings updated. Review or revert at [Logs → Config Changes](minis://settings/logs?tab=config-audit).")
                    }
                }
            }.let { (response, warnings) ->
                // [T-envvar-disable] Non-fatal notes raised during the writes.
                if (warnings.isNotEmpty()) response.put("warnings", JSONArray(warnings))
                response
            }
        }
    }

    /**
     * One resolved entry in a write batch. Either a field op (when
     * [field] is non-null) or a collection-level add/remove (when
     * [collection] + [collectionVerb] are set). Collection ops carry
     * their own [rawPath] (e.g. "models.append") and [scope] (the
     * collection basePath, e.g. "models") because there is no
     * ConfigField to source them from.
     */
    private data class Resolved(
        val field: ConfigField?,
        val collection: ConfigCollection?,
        val collectionVerb: CollectionVerb?,
        val rawPath: String,
        val scope: String,
        val oldValue: ConfigValue,
        val newValue: ConfigValue,
        // [T-minis-config-provider-add] When non-null, audit / display
        // logging uses this instead of `newValue` — the executor still
        // applies `newValue` un-redacted so the credential reaches the
        // collection's add(). null = no redaction needed (use newValue
        // for both surfaces).
        val auditNewValue: ConfigValue? = null,
    )

    private enum class CollectionVerb { APPEND, REMOVE }

    /**
     * Mask a scalar credential write. Mirrors the per-key logic in
     * [ConfigValue.redactingSecrets] but for the case where the secret
     * IS the whole value (e.g. `providers.<id>.apiKey = "sk-…"`),
     * which the object-walking redactor wouldn't touch. `$$ENV`
     * references pass through (they're a pointer, not the secret);
     * empty strings pass through (used to clear the credential).
     * Non-string values are returned untouched (shouldn't happen for
     * credential fields, but defensive).
     */
    private fun maskScalarSecret(v: ConfigValue): ConfigValue {
        if (v !is ConfigValue.Str) return v
        if (v.value.startsWith("\$\$")) return v
        if (v.value.isEmpty()) return v
        return ConfigValue.Str("••• (hidden)")
    }

    /**
     * Splice the just-created entry id into the audit payload so the
     * revert path can locate the new row without re-reading repository
     * state (which may have churned by the time the user reverts).
     */
    private fun augmentWithEntryId(payload: ConfigValue, entryId: String): ConfigValue {
        val baseMap = (payload as? ConfigValue.Obj)?.value?.toMutableMap()
            ?: LinkedHashMap()
        baseMap["entry_id"] = ConfigValue.Str(entryId)
        return ConfigValue.Obj(baseMap)
    }

    /**
     * Build a JSON snapshot of a collection child by walking its
     * exposed fields. Used by the .remove path so audit-revert can
     * reconstruct the original entry. Only includes scalar / array
     * fields the collection actually surfaces — derived fields go
     * along for the ride but the collection.add() input contract
     * decides what's honored on replay.
     */
    private fun snapshotCollectionChild(coll: ConfigCollection, childId: String): ConfigValue {
        val out = LinkedHashMap<String, ConfigValue>()
        out["entry_id"] = ConfigValue.Str(childId)
        for (field in coll.fields(forId = childId)) {
            val leaf = field.path.substringAfterLast('.', missingDelimiterValue = field.path)
            val v = try { field.read() } catch (_: Throwable) { ConfigValue.Null }
            out[leaf] = v
        }
        return ConfigValue.Obj(out)
    }

    // -- audit --

    fun auditList(limit: Int, scope: String?): JSONObject {
        if (!MinisConfigPermissionStore.isEnabled) return disabledErrorEnvelope()
        val entries = ConfigAuditLog.get().recent(limit, scope)
        val usage = ConfigAuditLog.get().usage()
        return JSONObject().apply {
            put("ok", true)
            put("count", entries.size)
            put("capacity", usage.capacity)
            put("total_used", usage.count)
            put("entries", JSONArray().also { arr -> for (e in entries) arr.put(auditEntryDict(e)) })
        }
    }

    fun auditGet(id: String): JSONObject {
        if (!MinisConfigPermissionStore.isEnabled) return disabledErrorEnvelope()
        val e = ConfigAuditLog.get().get(id) ?: return JSONObject().apply {
            put("ok", false)
            put("error", "not_found")
            put("reason", "No audit entry with id '$id'.")
        }
        return JSONObject().apply {
            put("ok", true)
            put("entry", auditEntryDict(e))
        }
    }

    /**
     * The CLI / agent view of an audit row — an explicit allow-list.
     *
     * [T-config-readable] `e.readable` (the human-readable description) is
     * deliberately NOT emitted: it is for the user only (confirmation dialog
     * + Logs → Config Changes) and must never reach the model. Keep this a
     * field-by-field list; never serialize the whole entry. Guarded by
     * ConfigReadableLeakTest.
     */
    private fun auditEntryDict(e: ConfigAuditEntry): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("at", isoFormatter.format(Date(e.at)))
        put("actor", e.actor.raw)
        put("scope", e.scope)
        put("key", e.key)
        put("old", e.oldValueJSON)
        put("new", e.newValueJSON)
        put("status", e.status.raw)
        if (e.sessionId != null) put("session_id", e.sessionId)
        if (e.confirmedAt != null) put("confirmed_at", isoFormatter.format(Date(e.confirmedAt)))
        if (e.revertOf != null) put("revert_of", e.revertOf)
        if (!e.caption.isNullOrEmpty()) put("caption", e.caption)
    }

    /**
     * Revert a previous applied entry. Builds a synthetic write
     * operation that flips old↔new and routes through the same
     * confirmation path so the user sees what's about to roll back.
     *
     * `skipConfirmation` is for user-initiated reverts from the
     * in-app Logs UI — the user already confirmed via a local dialog,
     * and stacking a second dialog over the Logs sheet would queue
     * silently behind it.
     */
    fun auditRevert(
        id: String,
        actorRaw: String,
        sessionId: String?,
        skipConfirmation: Boolean,
    ): JSONObject {
        if (!MinisConfigPermissionStore.isEnabled) return disabledErrorEnvelope()
        val entry = ConfigAuditLog.get().get(id) ?: return JSONObject().apply {
            put("ok", false)
            put("error", "not_found")
            put("reason", "No audit entry with id '$id'.")
        }
        if (entry.status != ConfigAuditStatus.APPLIED) return JSONObject().apply {
            put("ok", false)
            put("error", "not_revertable")
            put("reason", "Entry is ${entry.status.raw}; only applied entries can be reverted.")
        }

        // Collection ops: rebuild the inverse write. .append → .remove
        // by the recorded entry_id; .remove → .append with the saved
        // snapshot payload (sans entry_id, which the collection will
        // mint fresh — chats hooked to the old id will not auto-rebind).
        val items: JSONArray = if (entry.key.endsWith(".append") || entry.key.endsWith(".remove")) {
            val basePath = entry.key.substringBeforeLast('.')
            val coll = ConfigRegistry.get().collection(basePath)
                ?: return JSONObject().apply {
                    put("ok", false)
                    put("error", "not_revertable")
                    put("reason", "Collection '$basePath' no longer registered.")
                }
            if (entry.key.endsWith(".append")) {
                // Inverse of append = remove. Pull entry_id out of the
                // recorded new value.
                val newVal = ConfigValue.decode(entry.newValueJSON) as? ConfigValue.Obj
                val entryId = (newVal?.value?.get("entry_id") as? ConfigValue.Str)?.value
                    ?: return JSONObject().apply {
                        put("ok", false)
                        put("error", "not_revertable")
                        put("reason", "Audit entry missing entry_id; cannot reverse the add.")
                    }
                JSONArray().put(JSONObject().apply {
                    put("path", "${coll.basePath}.remove")
                    put("value_json", ConfigValue.Str(entryId).jsonString())
                })
            } else {
                // Inverse of remove = append. Replay the snapshot we
                // captured at delete time, but strip volatile keys the
                // collection.add() schema may reject (entry_id, etc.).
                val oldVal = (ConfigValue.decode(entry.oldValueJSON) as? ConfigValue.Obj)?.value
                    ?: return JSONObject().apply {
                        put("ok", false)
                        put("error", "not_revertable")
                        put("reason", "Audit entry missing old-value snapshot; cannot reverse the remove.")
                    }
                // Translate the snapshot's leaf field names back into
                // collection.add() payload keys. For ModelsCollection
                // we need provider_id + model_id + display_name.
                val replay = LinkedHashMap<String, ConfigValue>()
                oldVal["providerInstanceId"]?.let { replay["instance_id"] = it }
                oldVal["modelId"]?.let { replay["model_id"] = it }
                oldVal["displayName"]?.let { replay["display_name"] = it }
                JSONArray().put(JSONObject().apply {
                    put("path", "${coll.basePath}.append")
                    put("value_json", ConfigValue.Obj(replay).jsonString())
                })
            }
        } else {
            val field = ConfigRegistry.get().resolveField(entry.key)
            if (field == null || !field.revertable) return JSONObject().apply {
                put("ok", false)
                put("error", "not_revertable")
                put("reason", "Field '${entry.key}' no longer registered, or doesn't support revert.")
            }
            JSONArray().put(JSONObject().apply {
                put("path", entry.key)
                put("value_json", entry.oldValueJSON)
            })
        }
        val res = runBlocking {
            performWriteBatch(
                items = items,
                caption = "minis-config audit revert ${entry.id.take(8)}",
                actorRaw = actorRaw,
                sessionId = sessionId,
                skipConfirmation = skipConfirmation,
            )
        }
        if (res.optBoolean("ok", false)) {
            ConfigAuditLog.get().markReverted(entry.id)
        }
        return res
    }
}

// ─── Merged from ConfigCollection.kt ──────────────────────────────────────────────────

/**
 * A dynamically-keyed group of fields, e.g. one ProviderInstance per id,
 * one ModelEntry per uuid, one ModelGroup per id. Mirrors iOS
 * `ConfigCollection` (Shared/Config/Fields/ConfigCollection.swift).
 *
 * Static fields go through the registry's flat field map. A collection
 * adds two more capabilities:
 *   1. Enumerate runtime children ([childIds]) — used for dynamic path
 *      resolution like `models.<uuid>.maxOutputTokens`.
 *   2. Define `add` / `remove` semantics — these create or dispose the
 *      whole child rather than mutate a single field.
 */
interface ConfigCollection {
    /** First segment of every member's path, e.g. `models` or `groups`. */
    val basePath: String
    val displayName: String
    val description: String

    val addable: Boolean get() = true
    val removable: Boolean get() = true

    /**
     * Risk applied to add/remove operations (children declare per-field
     * risk). Defaults to [ConfigRisk.SENSITIVE] because creating /
     * destroying structured records is more impactful than tweaking a
     * scalar.
     */
    val risk: ConfigRisk get() = ConfigRisk.SENSITIVE

    /** Currently-existing child ids. Order is meaningful where user-visible. */
    fun childIds(): List<String>

    /** Field set exposed for one child id. Empty if id missing. */
    fun fields(forId: String): List<ConfigField>

    /** JSON schema accepted by [add]. `Json` allows free-form parsing. */
    val addPayloadSchema: ConfigSchema get() = ConfigSchema.Json

    /** Create a new child. Returns the new id. */
    fun add(payload: ConfigValue): String

    /** Remove a child. Throws on missing id or non-removable. */
    fun remove(id: String)

    // [T-config-readable] Human-readable descriptions — no defaults, so a new
    // collection does not compile until it says how it reads. Mirrors iOS.

    /**
     * Localization key naming a child's field in a sentence, with the
     * child's name as %1$@ and the field label as %2$@, e.g.
     * "Group “%1$@” – %2$@".
     */
    val readableSubjectFormat: String

    /**
     * The child's user-facing name (group name, provider label, …), or null
     * when [id] is not a child. Called before the write, so a removed child
     * is still named.
     */
    fun entityLabel(id: String): String?

    /** Sentences describing an `add`. The payload is already secret-masked. */
    fun readableForAdd(payload: RedactedConfigValue): List<ReadableLine>

    /** Sentences describing a `remove`, rendered BEFORE the removal. */
    fun readableForRemove(id: String): List<ReadableLine>
}

// ─── Merged from ConfigError.kt ──────────────────────────────────────────────────

/**
 * Failure modes a [ConfigField] operation may surface. Matches iOS
 * `ConfigError`. Bridge-level error codes:
 *   TypeMismatch / InvalidValue / OutOfRange / RegexMismatch  → 1
 *   PermissionDenied                                          → 126
 *   UnknownPath                                               → 1
 *   IoError                                                   → 1
 */
sealed class ConfigError(message: String) : Exception(message) {
    class TypeMismatch(expected: String) :
        ConfigError("type_mismatch: expected $expected")

    class InvalidValue(msg: String) :
        ConfigError("invalid_value: $msg")

    class OutOfRange(min: Double?, max: Double?) :
        ConfigError(
            "out_of_range: " + listOfNotNull(
                min?.let { "min=$it" },
                max?.let { "max=$it" }
            ).joinToString(", ")
        )

    class RegexMismatch(pattern: String) :
        ConfigError("regex_mismatch: must match /$pattern/")

    class PermissionDenied(reason: String = "Hidden from minis-config") :
        ConfigError("permission_denied: $reason")

    class UnknownPath(path: String) :
        ConfigError("unknown_path: $path")

    /** Raised by collection.add when a child with the same natural key already exists. */
    class AlreadyExists(reason: String) :
        ConfigError("already_exists: $reason")

    class IoError(msg: String) :
        ConfigError("io_error: $msg")
}

// ─── Merged from ConfigField.kt ──────────────────────────────────────────────────

/**
 * One configurable setting. Concrete kinds: [com.openminis.app.config.fields.PrefsBoolField]
 * etc. for SharedPreferences-backed scalars; [com.openminis.app.config.fields.ClosureField]
 * when the storage backend is something more bespoke (a repository, a DB row, an
 * in-process singleton). Mirrors iOS `ConfigField` (Shared/Config/ConfigField.swift).
 */
interface ConfigField {
    /** Dot-path id, e.g. `appearance.theme` or `models.<uuid>.maxOutputTokens`. */
    val path: String

    /** Human-readable label rendered in the confirm dialog and topic-help. */
    val displayName: String

    /** One-line description rendered in topic-help. */
    val description: String

    val valueSchema: ConfigSchema
    val access: ConfigAccess
    val risk: ConfigRisk

    /** Whether `audit-revert` may roll a write back. */
    val revertable: Boolean

    /**
     * [T-config-readable] How a change to this field reads to the person
     * approving it (confirmation dialog) and reviewing it (Logs → Config
     * Changes). Deliberately has NO default: a new field does not compile
     * until it says how its values read. [ConfigReadable.generic] is the
     * explicit opt-out. Mirrors iOS `ConfigField.readable`.
     */
    val readable: ConfigReadable

    /**
     * [T-android-config-feature-unavailable] Non-null when the field's
     * underlying feature does not exist on this device / OS version (e.g. the
     * Android 16 "Live Updates" surface on a device without it). The bridge
     * short-circuits BOTH reads and writes with a `feature_unavailable` error
     * BEFORE validation and before the confirmation dialog — the Settings UI
     * already blocks these entries, and minis-config must not be a side door
     * around that gate.
     *
     * Default null = available everywhere; only [UnavailableField] overrides.
     * Mirrors iOS `ConfigField.unavailableReason` (dba46d42).
     */
    val unavailableReason: String?
        get() = null

    /**
     * [FEAT-261021-03] Rewrites a `set` value before validation and the
     * confirmation dialog (e.g. a proxy's name -> `proxy:<id>`), so the user
     * approves - and the audit log records - what will be stored. Default:
     * unchanged. Mirrors iOS `ConfigField.normalize`.
     */
    fun normalize(value: ConfigValue): ConfigValue = value

    /**
     * [FEAT-261021-03] Checks beyond the schema, run on the normalized value
     * BEFORE the confirmation dialog, so a value that cannot be stored is
     * refused without asking the user first. Throws [ConfigError]. Mirrors
     * iOS `ConfigField.validate`.
     */
    fun validate(value: ConfigValue) {}

    /** Read the current value. May throw [ConfigError] on backend failure. */
    fun read(): ConfigValue

    /** Apply [value]. Schema validation runs in the bridge before this. */
    fun write(value: ConfigValue)

    /** Top-level scope = first dot segment. Used for audit `--scope` filtering. */
    val scope: String
        get() = path.substringBefore('.', missingDelimiterValue = "unknown")
}

/**
 * [T-android-config-feature-unavailable] Wraps a real field so its path stays
 * REGISTERED (help/list keep showing it with correct metadata) while every read
 * and write is refused with `feature_unavailable` plus [reason].
 *
 * Keeping the path registered is the point: the agent gets a precise "this
 * device can't do that, and retrying won't help" instead of a confusing
 * `unknown_path` that looks like a typo. Mirrors iOS `UnavailableField`.
 */
class UnavailableField(
    private val delegate: ConfigField,
    private val reason: String,
) : ConfigField by delegate {
    override val unavailableReason: String = reason
}

// ─── Merged from ConfigSchema.kt ──────────────────────────────────────────────────

/**
 * Type tag + per-type constraints for a [ConfigField]. Drives the
 * pre-write validation in the bridge and `--help` rendering. Mirrors
 * iOS `ConfigValueSchema` (Shared/Config/ConfigField.swift).
 */
sealed class ConfigSchema {
    object Bool : ConfigSchema()
    data class Int(val min: kotlin.Int? = null, val max: kotlin.Int? = null) : ConfigSchema()
    data class Double(val min: kotlin.Double? = null, val max: kotlin.Double? = null) : ConfigSchema()
    data class Str(val maxLength: kotlin.Int? = null, val regex: String? = null) : ConfigSchema()
    data class StrEnum(val cases: List<String>) : ConfigSchema()
    object Path : ConfigSchema()
    data class Optional(val inner: ConfigSchema) : ConfigSchema()
    data class Array(val inner: ConfigSchema) : ConfigSchema()
    /** Free-form: validation is the writer's responsibility. */
    object Json : ConfigSchema()

    /** Human-readable schema description for `topic-help` output. */
    val helpDescription: String
        get() = when (this) {
            Bool -> "bool"
            is Int -> "int (${min?.toString() ?: "-∞"}..${max?.toString() ?: "+∞"})"
            is Double -> "double (${min?.toString() ?: "-∞"}..${max?.toString() ?: "+∞"})"
            is Str -> buildString {
                append("string")
                if (maxLength != null) append(" max $maxLength chars")
                if (regex != null) append(" /$regex/")
            }
            is StrEnum -> "one of: " + cases.joinToString(", ")
            Path -> "path"
            is Optional -> "${inner.helpDescription}?"
            is Array -> "[${inner.helpDescription}]"
            Json -> "json"
        }

    /** Pure validation. Throws [ConfigError] on failure; never mutates. */
    fun validate(value: ConfigValue) {
        when (this) {
            Bool -> {
                if (value !is ConfigValue.Bool) throw ConfigError.TypeMismatch("bool")
            }
            is Int -> {
                val i = (value as? ConfigValue.Int)?.value
                    ?: throw ConfigError.TypeMismatch("int")
                if (min != null && i < min) throw ConfigError.OutOfRange(min.toDouble(), max?.toDouble())
                if (max != null && i > max) throw ConfigError.OutOfRange(min?.toDouble(), max.toDouble())
            }
            is Double -> {
                val d: kotlin.Double = when (value) {
                    is ConfigValue.Double -> value.value
                    is ConfigValue.Int -> value.value.toDouble()
                    else -> throw ConfigError.TypeMismatch("double")
                }
                if (min != null && d < min) throw ConfigError.OutOfRange(min, max)
                if (max != null && d > max) throw ConfigError.OutOfRange(min, max)
            }
            is Str -> {
                val s = (value as? ConfigValue.Str)?.value
                    ?: throw ConfigError.TypeMismatch("string")
                if (maxLength != null && s.length > maxLength) {
                    throw ConfigError.InvalidValue("string longer than $maxLength chars")
                }
                if (regex != null) {
                    val re = try { Regex(regex) } catch (_: Throwable) { null }
                    if (re != null && !re.matches(s)) throw ConfigError.RegexMismatch(regex)
                }
            }
            is StrEnum -> {
                val s = (value as? ConfigValue.Str)?.value
                    ?: throw ConfigError.TypeMismatch("string")
                if (s !in cases) {
                    throw ConfigError.InvalidValue("must be one of: ${cases.joinToString(", ")}")
                }
            }
            Path -> {
                if (value !is ConfigValue.Str) throw ConfigError.TypeMismatch("path")
            }
            is Optional -> {
                if (value === ConfigValue.Null) return
                inner.validate(value)
            }
            is Array -> {
                val arr = (value as? ConfigValue.Arr)?.value
                    ?: throw ConfigError.TypeMismatch("array")
                for (v in arr) inner.validate(v)
            }
            Json -> Unit
        }
    }
}

enum class ConfigAccess { HIDDEN, READONLY, READWRITE }

/** Risk classification surfaced in the confirm dialog. */
enum class ConfigRisk { NORMAL, SENSITIVE, DESTRUCTIVE }

// ─── Merged from ConfigValue.kt ──────────────────────────────────────────────────

/**
 * JSON-serializable union covering every value type a [ConfigField] can
 * hold. Mirrors iOS `ConfigValue` (Shared/Config/ConfigField.swift).
 *
 * The CLI bridge encodes/decodes these as JSON; the confirm dialog
 * renders them via [displayString]; the audit log persists them as JSON
 * text in the `old_value` / `new_value` columns.
 */
sealed class ConfigValue {
    data class Bool(val value: Boolean) : ConfigValue()
    data class Int(val value: kotlin.Int) : ConfigValue()
    data class Double(val value: kotlin.Double) : ConfigValue()
    data class Str(val value: String) : ConfigValue()
    data class Arr(val value: List<ConfigValue>) : ConfigValue()
    data class Obj(val value: Map<String, ConfigValue>) : ConfigValue()
    object Null : ConfigValue()

    /**
     * Compact human-readable rendering used by the confirm dialog and
     * `--help` examples. Long strings are truncated; arrays/objects show
     * their JSON form. Mirrors iOS `displayString`.
     */
    val displayString: String
        get() = when (this) {
            is Bool -> if (value) "true" else "false"
            is Int -> value.toString()
            is Double -> value.toString()
            is Str -> {
                if (value.length > 80) {
                    "\"${value.take(60)}…${value.takeLast(15)}\""
                } else {
                    "\"$value\""
                }
            }
            is Arr, is Obj -> jsonString()
            Null -> "null"
        }

    /**
     * Returns a copy with every value under a [SECRET_KEYS] key masked.
     * `$$VAR` references pass through (they're a pointer, not the secret);
     * any other non-empty literal string becomes `"••• (hidden)"`. Recurses
     * into nested objects/arrays so a secret nested in an add-payload is
     * caught too. Mirrors iOS `ConfigValue.redactingSecrets()` —
     * [T-minis-config-provider-add].
     *
     * Apply this to add-payload / audit-row JSON when the path resolves to a
     * collection that may contain credentials. The real (un-redacted) value
     * still reaches the collection's `add()` so it can persist the secret to
     * the encrypted store; only the displayed / audit-logged copy is masked.
     */
    fun redactingSecrets(): ConfigValue = when (this) {
        is Obj -> {
            val out = LinkedHashMap<String, ConfigValue>(value.size)
            for ((k, v) in value) {
                if (k in SECRET_KEYS && v is Str &&
                    !v.value.startsWith("\$\$") && v.value.isNotEmpty()) {
                    out[k] = Str("••• (hidden)")
                } else {
                    out[k] = v.redactingSecrets()
                }
            }
            Obj(out)
        }
        is Arr -> Arr(value.map { it.redactingSecrets() })
        else -> this
    }

    /** JSON-text representation suitable for storing in audit rows. */
    fun jsonString(): String = toJsonAny().let { any ->
        when (any) {
            JSONObject.NULL -> "null"
            is JSONObject -> any.toString()
            is JSONArray -> any.toString()
            is String -> JSONObject.quote(any)
            else -> any.toString()
        }
    }

    /** Recursively convert to a JSON-compatible Any (JSONObject/JSONArray/primitive/null). */
    private fun toJsonAny(): Any = when (this) {
        is Bool -> value
        is Int -> value
        is Double -> value
        is Str -> value
        is Arr -> JSONArray().also { arr ->
            for (v in value) {
                val a = v.toJsonAny()
                if (a === JSONObject.NULL) arr.put(JSONObject.NULL) else arr.put(a)
            }
        }
        is Obj -> JSONObject().also { obj ->
            for ((k, v) in value) {
                val a = v.toJsonAny()
                obj.put(k, if (a === JSONObject.NULL) JSONObject.NULL else a)
            }
        }
        Null -> JSONObject.NULL
    }

    companion object {
        /**
         * Object keys whose values are credential-sensitive and must be
         * masked in any audit / display / confirm-sheet surface. Mirrors
         * iOS `ConfigValue.secretObjectKeys`. Used by [redactingSecrets].
         */
        val SECRET_KEYS: Set<String> = setOf(
            "apiKey", "oauthToken", "manualOAuthToken",
            // [FEAT-261021-03] Proxy passwords and the Tailscale auth key.
            "password", "authKey",
        )

        /**
         * Parse a JSON string into a ConfigValue. Returns null on malformed JSON.
         *
         * [T-container-network-dns] Null also when text is left over after the
         * first value. org.json reads an UNQUOTED value only up to the first
         * `, : ; = # [ ] { } /`, so `set network.dnsCustomServers
         * 223.5.5.5,8.8.8.8` used to store "223.5.5.5" and drop the rest, an
         * unquoted IPv6 address kept only what came before its first `:`, and
         * an unquoted URL kept "https". A silent partial write is worse than
         * an error, so the whole input must be one value; a quoted string or
         * an unquoted single word still works as before.
         */
        fun decode(json: String): ConfigValue? = try {
            val tokener = JSONTokener(json)
            val value = tokener.nextValue()
            if (tokener.nextClean() != 0.toChar()) null else fromJsonAny(value)
        } catch (_: Throwable) {
            null
        }

        private fun fromJsonAny(any: Any?): ConfigValue = when (any) {
            null, JSONObject.NULL -> Null
            is Boolean -> Bool(any)
            is kotlin.Int -> Int(any)
            is Long -> {
                if (any in kotlin.Int.MIN_VALUE.toLong()..kotlin.Int.MAX_VALUE.toLong()) Int(any.toInt())
                else Double(any.toDouble())
            }
            is kotlin.Double, is Float -> Double((any as Number).toDouble())
            is String -> Str(any)
            is JSONArray -> {
                val out = ArrayList<ConfigValue>(any.length())
                for (i in 0 until any.length()) out.add(fromJsonAny(any.opt(i)))
                Arr(out)
            }
            is JSONObject -> {
                val out = LinkedHashMap<String, ConfigValue>(any.length())
                val keys = any.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    out[k] = fromJsonAny(any.opt(k))
                }
                Obj(out)
            }
            else -> Null
        }
    }
}

// ─── Merged from ConfigWriteWarnings.kt ──────────────────────────────────────────────────

/**
 * [T-envvar-disable] Non-fatal notes a field or collection raises while it is
 * being written - the write still happens. ConfigBridge opens a scope around
 * its apply loop and returns whatever was collected as `warnings` in the
 * response, so the agent learns about it (e.g. "$$GH_TOKEN is disabled and
 * resolved to an empty string") without the batch failing.
 *
 * Thread-local: writes run synchronously inside the bridge's loop, on the
 * thread that opened the scope. Outside a scope (tests, other callers) a
 * warning is simply dropped after being logged by its caller.
 */
object ConfigWriteWarnings {
    private val current = ThreadLocal<MutableList<String>?>()

    fun add(message: String) {
        current.get()?.add(message)
    }

    /** Runs [block] collecting warnings; returns its result and the warnings. */
    fun <T> collect(block: () -> T): Pair<T, List<String>> {
        val previous = current.get()
        val list = ArrayList<String>()
        current.set(list)
        try {
            return block() to list.distinct()
        } finally {
            current.set(previous)
        }
    }
}

// ─── Merged from MinisConfigPermissionStore.kt ──────────────────────────────────────────────────

/**
 * Master switch controlling whether the agent can use the
 * `minis-config` CLI at all. Disabling short-circuits every CLI call
 * before any field lookup, ConfirmationGate enqueue, or audit write.
 *
 * The flag itself is intentionally not settable through minis-config:
 * the registry registers a hidden placeholder on the same path so any
 * agent attempt to flip the switch returns `permission_denied`.
 *
 * Mirrors iOS `MinisConfigPermissionStore`.
 */
object MinisConfigPermissionStore {
    private const val PREFS = "minis_config_permission"
    private const val KEY = "minis_config_enabled"

    /** Default: true (preserves existing behaviour for upgrade users). */
    private const val DEFAULT_ENABLED = true

    private var prefs: SharedPreferences? = null

    private val _enabled = MutableStateFlow(DEFAULT_ENABLED)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Hot-path read used by the offload bridge before any work. */
    val isEnabled: Boolean get() = _enabled.value

    /** Call once early — typically from MinisApp.onCreate. Idempotent. */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _enabled.value = if (p.contains(KEY)) p.getBoolean(KEY, DEFAULT_ENABLED) else DEFAULT_ENABLED
    }

    /** UI toggle. Persists eagerly. */
    fun setEnabled(value: Boolean) {
        prefs?.edit()?.putBoolean(KEY, value)?.apply()
        _enabled.value = value
    }
}
