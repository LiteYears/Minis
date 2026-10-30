package com.openminis.app.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.util.Log
import com.openminis.app.data.db.WebAppShortcutDao
import com.openminis.app.data.db.WebAppShortcutEntity
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ModelReleaseIndex
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * T-android-dynamic-app-icon: Manage the launcher-icon alias the system
 * resolves for MAIN/LAUNCHER. Mirrors iOS
 * `UIApplication.setAlternateIconName` — the user picks an icon variant
 * in Settings → Appearance → App Icon, and we toggle the corresponding
 * `<activity-alias>` enabled/disabled via PackageManager.
 *
 * Wire:
 *   - All three aliases target MainActivity (declared in AndroidManifest).
 *   - At any time exactly ONE alias is enabled. Selecting a new variant
 *     enables that alias and disables the others.
 *   - DONT_KILL_APP keeps the current Activity alive across the toggle;
 *     the launcher may still take a few seconds to refresh its icon
 *     cache (this is a launcher-side caching detail we don't control).
 *
 * The current selection is also persisted to SharedPreferences so the
 * Appearance screen can render the correct checkmark before reading
 * back the PackageManager state (which can lag right after a toggle).
 */
object AppIconRepository {
    private const val TAG = "AppIconRepository"
    private const val PREFS = "app_icon_prefs"
    private const val KEY_SELECTED_ID = "selected_icon_id"
    private const val PACKAGE_NAME = "com.openminis.app"

    enum class Variant(val id: String, val aliasClass: String) {
        Auto("auto", "$PACKAGE_NAME.MainActivityIconAuto"),
        ClassicLight("classic_light", "$PACKAGE_NAME.MainActivityIconLight"),
        ClassicDark("classic_dark", "$PACKAGE_NAME.MainActivityIconDark"),
        ;

        companion object {
            fun fromId(id: String?): Variant = entries.firstOrNull { it.id == id } ?: Auto
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(context: Context): Variant =
        Variant.fromId(prefs(context).getString(KEY_SELECTED_ID, Variant.Auto.id))

    /**
     * Apply [target] as the active launcher icon. Disables every other
     * alias in one PackageManager pass + persists the selection.
     * No-op when [target] is already current — the PackageManager call
     * is mildly expensive (writes to package state) and the launcher
     * doesn't appreciate repeated unchanged toggles.
     */
    fun apply(context: Context, target: Variant): Boolean {
        val ctx = context.applicationContext
        val current = current(ctx)
        if (current == target) return false
        val pm = ctx.packageManager
        try {
            for (variant in Variant.entries) {
                val component = ComponentName(ctx, variant.aliasClass)
                val desiredState = if (variant == target) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                pm.setComponentEnabledSetting(
                    component,
                    desiredState,
                    PackageManager.DONT_KILL_APP,
                )
            }
            prefs(ctx).edit().putString(KEY_SELECTED_ID, target.id).apply()
            Log.i(TAG, "icon switched ${current.id} → ${target.id}")
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "icon switch failed: ${t.message}", t)
            return false
        }
    }
}

// ─── Merged from BackgroundSettingsRepository.kt ──────────────────────────────────────────────────

/**
 * T180-bg-notif: persistence for background-related toggles. Currently
 * only "Task Notifications" lives here; iOS exposes the same toggle in
 * `EnhancedBackgroundSettingsView` bound to
 * `BackgroundKeepAliveManager.backgroundNotificationsEnabled`.
 *
 * Default value is `true` to match iOS, where the toggle ships ON so
 * Live Activity and task-completion notifications work out-of-the-box
 * on first install. The user can opt out from Settings.
 */
class BackgroundSettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _taskNotificationsEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_TASK_NOTIFICATIONS, DEFAULT_TASK_NOTIFICATIONS))

    /**
     * Live state of the toggle. Compose surfaces collect this so flipping
     * the switch in Settings is reflected immediately at every consumer
     * (notifier, FG service status text, etc).
     */
    val taskNotificationsEnabled: StateFlow<Boolean> =
        _taskNotificationsEnabled.asStateFlow()

    fun setTaskNotificationsEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_TASK_NOTIFICATIONS, value).apply()
        _taskNotificationsEnabled.value = value
    }

    /**
     * T-bg-overlay phase 2: "show floating tool-status overlay while the
     * app is backgrounded" toggle. Defaults to OFF — the overlay needs
     * SYSTEM_ALERT_WINDOW which is a separate system permission flow, so
     * we won't surface anything until the user opts in.
     */
    private val _backgroundOverlayEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_BG_OVERLAY_ENABLED, false))
    val backgroundOverlayEnabled: StateFlow<Boolean> =
        _backgroundOverlayEnabled.asStateFlow()

    fun setBackgroundOverlayEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_BG_OVERLAY_ENABLED, value).apply()
        _backgroundOverlayEnabled.value = value
    }

    /**
     * [T-android-dynamic-island] "Show live status on the dynamic island"
     * toggle (Android 16 Live Updates). Defaults to OFF — the capability only
     * exists on Android 16+ with the per-app grant, and when ON it REPLACES the
     * floating overlay (mutual exclusion in AgentForegroundService.applyOverlayState),
     * so we don't want it silently changing behavior on upgrade. Reactive:
     * flipping it re-drives the FG service's combined flow so the overlay
     * appears/disappears without an app restart.
     */
    private val _dynamicIslandEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC_ISLAND_ENABLED, false))
    val dynamicIslandEnabled: StateFlow<Boolean> =
        _dynamicIslandEnabled.asStateFlow()

    fun setDynamicIslandEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_ISLAND_ENABLED, value).apply()
        _dynamicIslandEnabled.value = value
    }

    /**
     * [FIX-261025-03] How long before a scheduled task's next run its chat
     * counts as active again, bringing back the ongoing notification, the
     * capsule / Live Updates countdown and the keep-alive. Stored as an index
     * into [SCHEDULED_COUNTDOWN_CASES]; see [scheduledCountdownLeadMs].
     * Default 1 minute: holding the chat for the whole wait kept a daily
     * task's notification and status chip up all day.
     */
    private val _scheduledCountdownLead =
        MutableStateFlow(readScheduledCountdownLead(prefs))
    val scheduledCountdownLead: StateFlow<Int> = _scheduledCountdownLead.asStateFlow()

    fun setScheduledCountdownLead(index: Int) {
        val safe = index.takeIf { it in SCHEDULED_COUNTDOWN_CASES.indices } ?: DEFAULT_SCHEDULED_COUNTDOWN_LEAD
        prefs.edit().putInt(KEY_SCHEDULED_COUNTDOWN_LEAD, safe).apply()
        _scheduledCountdownLead.value = safe
    }

    /**
     * `minis-config set background.notifications|dynamicIsland|...` writes
     * this prefs file directly (ConfigBuiltins PrefsBoolField), not through
     * the setters above. Without this listener the flows kept the value read
     * at construction, so a tester's `minis-config set background.dynamicIsland
     * false` left the ongoing notification promoted to the lock-screen chip
     * until the app restarted. Held in a field: SharedPreferences keeps only
     * a weak reference to its listeners.
     */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        when (key) {
            KEY_TASK_NOTIFICATIONS ->
                _taskNotificationsEnabled.value = p.getBoolean(key, DEFAULT_TASK_NOTIFICATIONS)
            KEY_BG_OVERLAY_ENABLED -> _backgroundOverlayEnabled.value = p.getBoolean(key, false)
            KEY_DYNAMIC_ISLAND_ENABLED -> _dynamicIslandEnabled.value = p.getBoolean(key, false)
            KEY_SCHEDULED_COUNTDOWN_LEAD -> _scheduledCountdownLead.value = readScheduledCountdownLead(p)
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    /**
     * Last persisted overlay position (window x/y in pixels) from the
     * previous drag, or null when none was ever stored - the overlay
     * controller then picks a default near the bottom-left, 10 dp from each
     * edge ([T-bg-overlay-polish]).
     *
     * [T-overlay-position-restore] "Never stored" is the keys being absent,
     * not a value: a capsule dragged partly off the left or top edge has a
     * negative coordinate, and the old `-1` default made such a position
     * look like no position at all.
     */
    fun getOverlayPosition(landscape: Boolean = false): Pair<Int, Int>? {
        val (kx, ky) = overlayKeys(landscape)
        if (!prefs.contains(kx) || !prefs.contains(ky)) return null
        return prefs.getInt(kx, 0) to prefs.getInt(ky, 0)
    }

    fun setOverlayPosition(x: Int, y: Int, landscape: Boolean = false) {
        val (kx, ky) = overlayKeys(landscape)
        prefs.edit().putInt(kx, x).putInt(ky, y).apply()
    }

    /**
     * [T-overlay-orientation-position] Portrait keeps the original keys, so
     * the one position earlier builds stored is read as the portrait one
     * without a migration, and an older build reading this file still finds
     * a position. Landscape gets its own pair.
     */
    private fun overlayKeys(landscape: Boolean): Pair<String, String> =
        if (landscape) KEY_BG_OVERLAY_LAND_X to KEY_BG_OVERLAY_LAND_Y else KEY_BG_OVERLAY_X to KEY_BG_OVERLAY_Y

    companion object {
        internal const val PREFS_NAME = "background_settings"
        internal const val KEY_TASK_NOTIFICATIONS = "taskNotificationsEnabled"
        internal const val DEFAULT_TASK_NOTIFICATIONS = true
        private const val KEY_BG_OVERLAY_ENABLED = "backgroundOverlayEnabled"
        private const val KEY_BG_OVERLAY_X = "backgroundOverlayX"
        private const val KEY_BG_OVERLAY_Y = "backgroundOverlayY"
        private const val KEY_BG_OVERLAY_LAND_X = "backgroundOverlayLandscapeX"
        private const val KEY_BG_OVERLAY_LAND_Y = "backgroundOverlayLandscapeY"
        internal const val KEY_DYNAMIC_ISLAND_ENABLED = "dynamicIslandEnabled"

        internal const val KEY_SCHEDULED_COUNTDOWN_LEAD = "scheduledCountdownLead"

        /** minis-config values, in stored-index order. */
        internal val SCHEDULED_COUNTDOWN_CASES = listOf("off", "1m", "5m", "15m", "always")
        internal const val DEFAULT_SCHEDULED_COUNTDOWN_LEAD = 1

        internal fun readScheduledCountdownLead(p: SharedPreferences): Int =
            p.getInt(KEY_SCHEDULED_COUNTDOWN_LEAD, DEFAULT_SCHEDULED_COUNTDOWN_LEAD)
                .takeIf { it in SCHEDULED_COUNTDOWN_CASES.indices } ?: DEFAULT_SCHEDULED_COUNTDOWN_LEAD

        /**
         * The lead for a stored index: 0 = never (no wait is shown), [Long.MAX_VALUE] =
         * the whole wait (the behavior before this setting).
         */
        fun scheduledCountdownLeadMs(index: Int): Long = when (index) {
            0 -> 0L
            1 -> 60_000L
            2 -> 5 * 60_000L
            3 -> 15 * 60_000L
            else -> Long.MAX_VALUE
        }
    }
}

// ─── Merged from EnvVarRepository.kt ──────────────────────────────────────────────────

/**
 * Manages environment variables with encrypted value storage.
 * Metadata (key names, IDs) stored in JSON file.
 * Values stored in EncryptedSharedPreferences (AES256-GCM).
 * Enabled / disabled state stored in [EnvVarStateStore] (its own file, so
 * builds that predate it cannot drop it when they rewrite the metadata).
 * Mirrors iOS EnvVarStore.
 */
class EnvVarRepository internal constructor(
    private val dir: File,
    private val values: ValueStore,
) {

    /** The secret side: one value per variable NAME. */
    interface ValueStore {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun remove(key: String)
    }

    constructor(context: Context) : this(context.filesDir, EncryptedValueStore(context))

    private class EncryptedValueStore(private val context: Context) : ValueStore {
        private val prefs: SharedPreferences by lazy {
            // T-android-keystore-aead-fail: self-healing wrapper.
            com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, ENCRYPTED_PREFS_NAME)
        }
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
        override fun remove(key: String) { prefs.edit().remove(key).apply() }
    }

    companion object {
        private const val TAG = "EnvVarRepository"
        internal const val METADATA_FILE = "env-vars.json"
        private const val ENCRYPTED_PREFS_NAME = "env_var_values"
        private val KEY_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*$")
    }

    data class EnvVarEntry(
        val id: String = UUID.randomUUID().toString(),
        val key: String,
        /**
         * Optional human-readable description of what this variable is for.
         * Empty when omitted. Stored in the JSON metadata file (not secret),
         * mirrors iOS EnvVarEntry.note.
         */
        val note: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        /**
         * [T-envvar-disable] A disabled variable keeps its value and note but
         * is not injected into any shell / terminal / MCP process. Not
         * persisted in env-vars.json - see [EnvVarStateStore].
         */
        val enabled: Boolean = true,
    )

    private val _entries = MutableStateFlow<List<EnvVarEntry>>(emptyList())
    val entries: StateFlow<List<EnvVarEntry>> = _entries.asStateFlow()

    private val states = EnvVarStateStore(dir)

    private val metadataFile: File
        get() = File(dir, METADATA_FILE)

    init {
        loadMetadata()
    }

    // -- Validation --

    fun isValidKey(key: String): Boolean = KEY_REGEX.matches(key)

    // Keep printable ASCII (0x20-0x7E) and tab (0x09); iOS paste can inject
    // invisible control scalars (e.g. \u009B) that break shell env injection.
    private fun sanitizeValue(value: String): String =
        value.filter { ch ->
            val c = ch.code
            (c in 0x20..0x7E) || c == 0x09
        }

    fun isDuplicateKey(key: String, excludeId: String? = null): Boolean =
        _entries.value.any { it.key.equals(key, ignoreCase = true) && it.id != excludeId }

    /**
     * [T-envvar-disable] Names held by more than one entry (case-insensitive,
     * upper-cased). The add / edit form refuses duplicates, but an older build,
     * a restore or a merged file can still produce them; the list marks them so
     * the user can clean up.
     */
    fun duplicateKeys(): Set<String> =
        _entries.value.groupBy { it.key.uppercase() }.filterValues { it.size > 1 }.keys

    // -- CRUD --

    /**
     * Adds a new variable. [enabled] is only for a restore that carries a
     * disabled variable; every user-facing add creates an enabled one.
     */
    fun add(key: String, value: String, note: String = "", enabled: Boolean = true): Boolean {
        val normalizedKey = key.trim().uppercase()
        if (!isValidKey(normalizedKey)) return false
        if (isDuplicateKey(normalizedKey)) return false

        val entry = EnvVarEntry(key = normalizedKey, note = note.trim(), enabled = enabled)
        _entries.value = _entries.value + entry
        values.put(normalizedKey, sanitizeValue(value))
        saveMetadata()
        if (!enabled) states.setEnabled(entry.id, normalizedKey, false)
        Log.i(TAG, "Added env var: $normalizedKey enabled=$enabled")
        return true
    }

    /**
     * Changes the name, value and note. Deliberately has NO `enabled`
     * parameter: every writer that only means to change the value or note
     * (backup restore, minis-config note, the deep link form) goes through
     * here, and a defaulted `enabled = true` would silently re-enable a
     * variable the user turned off. Use [setEnabled] for that.
     */
    fun update(id: String, newKey: String, newValue: String, newNote: String = ""): Boolean {
        val normalizedKey = newKey.trim().uppercase()
        if (!isValidKey(normalizedKey)) return false

        val current = _entries.value.find { it.id == id } ?: return false

        // Check for duplicate (excluding self)
        if (isDuplicateKey(normalizedKey, excludeId = id)) return false

        // Delete old Keychain entry if key changed
        if (current.key != normalizedKey) {
            values.remove(current.key)
        }

        // Update metadata; `enabled` is carried over by copy().
        _entries.value = _entries.value.map {
            if (it.id == id) it.copy(key = normalizedKey, note = newNote.trim()) else it
        }

        // Save new value
        values.put(normalizedKey, sanitizeValue(newValue))
        saveMetadata()
        states.renamed(id, normalizedKey)
        Log.i(TAG, "Updated env var: ${current.key} → $normalizedKey")
        return true
    }

    /** [T-envvar-disable] Turns a variable on or off; its value and note are untouched. */
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val current = _entries.value.find { it.id == id } ?: return false
        if (current.enabled == enabled && states.state(id) != null) return true
        states.setEnabled(id, current.key, enabled)
        _entries.value = _entries.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        Log.i(TAG, "Env var ${current.key} enabled=$enabled")
        return true
    }

    fun delete(id: String) {
        val entry = _entries.value.find { it.id == id } ?: return
        values.remove(entry.key)
        _entries.value = _entries.value.filter { it.id != id }
        saveMetadata()
        // A new variable with the same name must not inherit this one's state.
        states.remove(id)
        Log.i(TAG, "Deleted env var: ${entry.key}")
    }

    fun getValue(key: String): String? = values.get(key)

    /** [T-envvar-disable] Whether the variable named [key] exists and is disabled. */
    fun isDisabled(key: String): Boolean =
        _entries.value.any { it.key.equals(key, ignoreCase = true) && !it.enabled }

    /**
     * Returns the ENABLED env vars as a Map<String, String> for sandbox
     * injection (shells, terminals, and through them MCP stdio servers).
     * Reads directly from storage to be thread-safe.
     */
    fun allAsDict(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (entry in _entries.value) {
            if (!entry.enabled) continue
            val value = values.get(entry.key)
            if (value != null) {
                result[entry.key] = value
            }
        }
        return result
    }

    /**
     * [T-envvar-disable] Every stored value, disabled ones included, for the
     * output redactor: a disabled secret can still reach tool output (a
     * terminal started before it was disabled, a file the agent reads), and
     * it must stay masked.
     */
    fun allValuesForRedaction(): List<String> =
        _entries.value.mapNotNull { values.get(it.key) }

    // -- Metadata Persistence --

    private fun saveMetadata() {
        try {
            val array = JSONArray()
            for (entry in _entries.value) {
                val obj = JSONObject()
                obj.put("id", entry.id)
                obj.put("key", entry.key)
                if (entry.note.isNotEmpty()) obj.put("note", entry.note)
                obj.put("createdAt", entry.createdAt)
                array.put(obj)
            }
            metadataFile.writeText(array.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata: ${e.message}")
        }
    }

    private fun loadMetadata() {
        try {
            if (!metadataFile.exists()) {
                states.prune(emptySet())
                return
            }
            val array = JSONArray(metadataFile.readText())
            val entries = mutableListOf<EnvVarEntry>()
            var mintedIds = false
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                // An entry without an id gets one now, and it is written back
                // below: a fresh random id on every load would leave a disabled
                // state nothing to attach to.
                val id = obj.optString("id", "").ifEmpty { mintedIds = true; UUID.randomUUID().toString() }
                entries.add(EnvVarEntry(
                    id = id,
                    key = obj.optString("key", ""),
                    note = obj.optString("note", ""),
                    createdAt = obj.optLong("createdAt", 0),
                    enabled = states.isEnabled(id),
                ))
            }
            _entries.value = entries
            if (mintedIds) saveMetadata()
            // Only after a successful read: forget states of variables that are
            // gone, including ones an older build deleted.
            states.prune(entries.map { it.id }.toSet())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load metadata: ${e.message}")
        }
    }
}

// ─── Merged from EnvVarStateStore.kt ──────────────────────────────────────────────────

/**
 * [T-envvar-disable] Where each environment variable's enabled / disabled state
 * lives: its own file, `env-vars-state.json`, NOT a field in `env-vars.json`.
 *
 * Why a separate file: builds before this feature read and rewrite
 * `env-vars.json` with only id / key / note / createdAt. A field stored there
 * would be dropped the first time an older build saved (note edit, value edit,
 * add, delete), so a downgrade - or a restore made by an older build - followed
 * by an upgrade would silently turn every disabled variable back on. Older
 * builds never open this file, so whatever they do to `env-vars.json`, the
 * state survives.
 *
 * Indexed by the entry's `id`, not its name:
 * - an older build editing a variable keeps its id, so the state follows it
 *   (including a rename, which keeps the id);
 * - deleting a variable and creating one with the same name - in this build or
 *   an older one - gives the new entry a new id, so it never inherits the old
 *   variable's state. States whose id no longer exists are pruned on load.
 *
 * Absent = enabled. Only a disabled variable needs a record, but an explicit
 * re-enable is kept as a record too, so that [enabledUpdatedAt] (the
 * last-writer-wins clock for this field, independent of value / note edits)
 * is not lost.
 *
 * File shape (version 1):
 * ```
 * {"version":1,"states":{"<entry id>":{"key":"GH_TOKEN","enabled":false,"enabledUpdatedAt":1727740800000}}}
 * ```
 */
class EnvVarStateStore(private val dir: File) {

    data class State(val key: String, val enabled: Boolean, val enabledUpdatedAt: Long)

    private val file: File get() = File(dir, FILE_NAME)
    private var states: MutableMap<String, State> = LinkedHashMap()

    /** True when the file existed but could not be parsed: never prune or overwrite it then. */
    var unreadable: Boolean = false
        private set

    init {
        load()
    }

    fun isEnabled(id: String): Boolean = states[id]?.enabled ?: true

    fun state(id: String): State? = states[id]

    /**
     * Records [enabled] for [id]. The clock never goes backwards for an entry:
     * a write stamped earlier than the one it replaces (device clock moved
     * back) is stamped one millisecond after it instead, so the newest user
     * action always carries the newest stamp.
     */
    fun setEnabled(id: String, key: String, enabled: Boolean, now: Long = System.currentTimeMillis()) {
        val previous = states[id]
        val stamp = if (previous != null && now <= previous.enabledUpdatedAt) previous.enabledUpdatedAt + 1 else now
        states[id] = State(key, enabled, stamp)
        save()
    }

    /** A rename keeps the variable (same id); keep the informational key in step. */
    fun renamed(id: String, newKey: String) {
        val s = states[id] ?: return
        if (s.key == newKey) return
        states[id] = s.copy(key = newKey)
        save()
    }

    fun remove(id: String) {
        if (states.remove(id) != null) save()
    }

    /**
     * Drops states whose variable no longer exists - deleted here, or deleted
     * (and possibly re-created under a new id) by an older build that knows
     * nothing about this file. Only call with the ids of a SUCCESSFUL metadata
     * load: an empty list from a failed read must not erase every state.
     */
    fun prune(liveIds: Set<String>) {
        if (unreadable) return
        val before = states.size
        states.keys.retainAll(liveIds)
        if (states.size != before) save()
    }

    private fun load() {
        val f = file
        if (!f.exists()) return
        try {
            val root = JSONObject(f.readText())
            val obj = root.optJSONObject("states") ?: JSONObject()
            val loaded = LinkedHashMap<String, State>()
            for (id in obj.keys()) {
                val s = obj.optJSONObject(id) ?: continue
                loaded[id] = State(
                    key = s.optString("key", ""),
                    enabled = s.optBoolean("enabled", true),
                    enabledUpdatedAt = s.optLong("enabledUpdatedAt", 0L),
                )
            }
            states = loaded
        } catch (e: Exception) {
            // Keep the file as it is: overwriting an unreadable file would turn
            // every disabled variable back on.
            unreadable = true
        }
    }

    private fun save() {
        if (unreadable) return
        val obj = JSONObject()
        for ((id, s) in states) {
            obj.put(id, JSONObject().apply {
                put("key", s.key)
                put("enabled", s.enabled)
                put("enabledUpdatedAt", s.enabledUpdatedAt)
            })
        }
        val root = JSONObject().apply {
            put("version", 1)
            put("states", obj)
        }
        // Write-then-rename so a crash mid-write never leaves a truncated file.
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(root.toString())
            tmp.delete()
        }
    }

    companion object {
        const val FILE_NAME = "env-vars-state.json"
    }
}

// ─── Merged from InternalTags.kt ──────────────────────────────────────────────────

/**
 * [T-preview-scheduled-tag-leak] The app-authored envelopes that can sit in a
 * message's text, and how a one-line preview (session list, restore rebuild)
 * gets rid of them. Pure, so every variant is unit-tested.
 *
 * - `<system-reminder>…</system-reminder>`: runtime nudges in user-role rows.
 * - `<user-attached-files>…</user-attached-files>`: the attached-file listing
 *   appended to a user turn for the model.
 * - `[attached image: …]` / `[image omitted to save context …]`: attachment
 *   captions written for the model.
 * - `<scheduled_task …>…</scheduled_task>`: a scheduled fire
 *   (ScheduledTaskMarker), previewed as one readable line instead.
 * - `<agent_callback …>`: handled by AgentCallback.parse / its preview line.
 *
 * The strippers ignore case and tolerate a tag that was never closed (a row
 * cut short, a hand-typed fragment): an unclosed opener drops everything
 * after it, never leaving the raw tag on screen. A stray closing tag is
 * dropped too.
 */
internal object InternalTags {

    private val SYSTEM_REMINDER = Regex(
        """<system-reminder\b[^>]*>.*?(?:</system-reminder\s*>|\z)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val ATTACHED_FILES = Regex(
        """<user-attached-files\b[^>]*>.*?(?:</user-attached-files\s*>|\z)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val STRAY_CLOSERS = Regex(
        """</(?:system-reminder|user-attached-files|scheduled_task)\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val ATTACHMENT_MARKERS = listOf(
        Regex("""\[attached [A-Za-z]+:[^\]]*]"""),
        Regex("""\[image omitted to save context[^\]]*]"""),
    )
    private val SCHEDULED_OPENER = Regex("""^\s*<scheduled_task\b""", RegexOption.IGNORE_CASE)

    fun stripSystemReminders(raw: String): String =
        SYSTEM_REMINDER.replace(raw, "").trim()

    /** Every envelope a preview must not show, plus the attachment captions. */
    fun stripForPreview(raw: String): String {
        var s = SYSTEM_REMINDER.replace(raw, "")
        s = ATTACHED_FILES.replace(s, "")
        s = STRAY_CLOSERS.replace(s, "")
        if (s.contains('[')) for (re in ATTACHMENT_MARKERS) s = re.replace(s, "")
        return s.trim()
    }

    /**
     * The preview line of a scheduled fire's row, or null when [text] is not
     * one. iOS previews a fire (an agent callback of kind "scheduled") as
     * "Scheduled task · <title> · <status>"; Android's envelope carries no
     * status, so it is "<noun> · <label>", the prompt standing in for a blank
     * label. An envelope too damaged to parse still reads as the noun alone,
     * never as the raw tag.
     */
    fun scheduledPreviewLine(text: String, noun: String, clean: (String) -> String): String? {
        val bare = stripSystemReminders(text)
        if (!SCHEDULED_OPENER.containsMatchIn(bare)) return null
        val marker = com.openminis.app.scheduled.ScheduledTaskMarker.parse(bare)
            ?: return noun
        val name = marker.label.trim().ifEmpty { clean(marker.prompt) }.trim()
        return if (name.isEmpty()) noun else "$noun · $name"
    }
}

// ─── Merged from MCPRepository.kt ──────────────────────────────────────────────────

/**
 * Manages MCP (Model Context Protocol) server configs.
 *
 * Mirrors [SkillRepository] in architecture, but the source of truth for the
 * server list is a single Claude-Desktop-compatible JSON file rather than a
 * SQLite table:
 *   - Server configs live in `/var/minis/mcp-servers/servers.json` (host:
 *     `minis-global/mcp-servers/servers.json`) in the `{ "mcpServers": { … } }`
 *     format. This is the SAME file the `minis-mcp-cli` Python tool reads/writes
 *     inside PRoot, and the file browser can edit — so all three surfaces stay
 *     in sync without an Android-side sync layer (matches Android's local-only
 *     skills/provider model; no CloudKit / whole-file sync here).
 *   - Session enable/disable overrides live in `mcp_session_overrides` (SQLite),
 *     exactly mirroring SkillRepository's `session_skill_overrides`.
 *   - Prompt-fragment generation discloses the Top-N enabled servers, reusing
 *     the same description-truncation cap as skills.
 *
 * No usage-count / recent-use tracking is persisted server-side (the CLI owns
 * invocation); "recent" ordering falls back to recent-add (createdAt desc),
 * which is the best signal Android has locally.
 */
class MCPRepository(private val context: Context) {

    companion object {
        private const val TAG = "MCPRepository"
        private const val DB_NAME = "mcp.db"
        private const val DB_VERSION = 1
        private const val MAX_MCPS_IN_PROMPT = 20
        // Reuse the skill description cap so MCP notes truncate identically.
        private const val MAX_NOTE_LENGTH = 200
    }

    // -- Data Types --

    /**
     * A single MCP server entry. [id] is the server name (the key in the
     * `mcpServers` object). Exactly one transport is populated: HTTP/SSE
     * (url + headers) OR STDIO (command + args + env).
     */
    data class MCPServerConfig(
        val id: String,
        val note: String? = null,
        val enabled: Boolean = true,
        // HTTP / SSE transport
        val url: String? = null,
        val headers: Map<String, String> = emptyMap(),
        // STDIO transport
        val command: String? = null,
        val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(),
        /**
         * Per-server startup/handshake timeout (seconds) for a STDIO server's
         * first MCP `initialize`. Minis config, not MCP protocol; enforcement is
         * entirely in the in-guest `minis-mcp-cli` daemon. Round-tripped verbatim
         * so an edit/import/export never drops it. Null = daemon default (60s).
         * [T-mcp-startup-timeout]
         */
        val startupTimeoutSeconds: Int? = null,
        /**
         * [T-android-mcp-oauth] Non-secret Static-OAuth config for an HTTP/SSE
         * server (client id + endpoints + scopes). Null = no OAuth (static
         * headers only). The client secret and issued tokens are NOT here — they
         * live in [com.openminis.app.mcp.oauth.MCPOAuthStore]. Round-tripped
         * verbatim through servers.json.
         */
        val oauth: com.openminis.app.mcp.oauth.MCPOAuthConfig? = null,
        /** Local-only: when this entry was first written, for recent-add ordering. */
        val createdAt: Long = System.currentTimeMillis(),
    ) {
        val isStdio: Boolean get() = !command.isNullOrBlank()
        /** Short transport descriptor for list rows: the URL or the command line. */
        val transportSummary: String
            get() = if (isStdio) {
                (listOf(command).plus(args)).joinToString(" ").trim()
            } else {
                url ?: ""
            }
    }

    // -- State --

    private val _servers = MutableStateFlow<List<MCPServerConfig>>(emptyList())
    val servers: StateFlow<List<MCPServerConfig>> = _servers.asStateFlow()

    private val db: SQLiteDatabase by lazy {
        McpDbHelper(context).writableDatabase
    }

    /** Host dir backing `/var/minis/mcp-servers` (mirrors skills' minis-global dir). */
    private val mcpDir: File
        get() = File(context.filesDir, "minis-global/mcp-servers")

    private val serversFile: File
        get() = File(mcpDir, "servers.json")

    init {
        load()
    }

    // -- Load / Save (servers.json is the source of truth) --

    /** Re-read `servers.json` from disk and re-publish [servers]. */
    fun load() {
        _servers.value = readServersFile()
    }

    /** Alias matching SkillRepository.reloadFromDisk() so callers read symmetrically. */
    fun reloadFromDisk() = load()

    private fun readServersFile(): List<MCPServerConfig> {
        val file = serversFile
        if (!file.exists()) return emptyList()
        val text = try { file.readText() } catch (e: Exception) {
            Log.w(TAG, "Failed to read servers.json: ${e.message}")
            return emptyList()
        }
        if (text.isBlank()) return emptyList()
        val root = try { JSONObject(text) } catch (e: Exception) {
            Log.w(TAG, "servers.json is not valid JSON: ${e.message}")
            return emptyList()
        }
        val obj = root.optJSONObject("mcpServers") ?: return emptyList()
        val out = mutableListOf<MCPServerConfig>()
        // [T-mcp-review-fixes-android] FIX 2: createdAt must be a STABLE recency
        // signal. Entries written by the CLI / JSON-import have no createdAt; we
        // must NOT mint now() on every read (that churns the createdAt-desc
        // Top-20 sort and floats those servers to the top forever). Assign a
        // stable value once on first sight and persist it back to servers.json.
        // Stagger the assigned timestamps by file order so a batch of
        // createdAt-less entries keeps a deterministic (not all-equal) order.
        var needsPersist = false
        val now = System.currentTimeMillis()
        var idx = 0
        for (name in obj.keys()) {
            val entry = obj.optJSONObject(name) ?: continue
            val parsed = parseEntry(name, entry)
            if (!entry.has("createdAt")) {
                needsPersist = true
                // Older-looking, stable, and order-preserving: earlier keys get
                // slightly smaller stamps so iteration order is the tiebreak.
                out.add(parsed.copy(createdAt = now - (idx + 1)))
            } else {
                out.add(parsed)
            }
            idx++
        }
        val sorted = out.sortedByDescending { it.createdAt }
        // Persist the freshly-assigned createdAt values once so subsequent reads
        // are stable. Pass the list explicitly — _servers.value isn't updated
        // until load() returns this.
        if (needsPersist) save(sorted)
        return sorted
    }

    /** Write [list] (defaults to current [servers]) to servers.json in mcpServers format. */
    private fun save(list: List<MCPServerConfig> = _servers.value) {
        mcpDir.mkdirs()
        val mcpServers = JSONObject()
        for (s in list) {
            mcpServers.put(s.id, entryToJson(s))
        }
        val root = JSONObject().put("mcpServers", mcpServers)
        try {
            serversFile.writeText(root.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write servers.json: ${e.message}")
        }
    }

    private fun parseEntry(name: String, entry: JSONObject): MCPServerConfig {
        // `disabled: true` (Cursor variant) maps to enabled=false; `enabled`
        // takes precedence when present. Default enabled=true.
        val enabled = when {
            entry.has("enabled") -> entry.optBoolean("enabled", true)
            entry.has("disabled") -> !entry.optBoolean("disabled", false)
            else -> true
        }
        val headers = entry.optJSONObject("headers")?.let { stringMap(it) } ?: emptyMap()
        val env = entry.optJSONObject("env")?.let { stringMap(it) } ?: emptyMap()
        val args = entry.optJSONArray("args")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }
        } ?: emptyList()
        val note = entry.optString("note", "").ifBlank { null }
        val createdAt = entry.optLong("createdAt", System.currentTimeMillis())
        return MCPServerConfig(
            id = name,
            note = note,
            enabled = enabled,
            url = entry.optString("url", "").ifBlank { null },
            headers = headers,
            command = entry.optString("command", "").ifBlank { null },
            args = args,
            env = env,
            startupTimeoutSeconds = parseStartupTimeout(entry),
            oauth = com.openminis.app.mcp.oauth.MCPOAuthConfig.fromJson(entry.optJSONObject("oauth")),
            createdAt = createdAt,
        )
    }

    /**
     * [T-mcp-startup-timeout] Read a STDIO startup timeout, honouring the same
     * primary-field-then-alias priority as the in-guest CLI so a config migrated
     * from another MCP client keeps its value. Validation/enforcement is the
     * daemon's job; here we only preserve an integer if one is present.
     */
    private fun parseStartupTimeout(entry: JSONObject): Int? {
        val keys = listOf(
            "startupTimeoutSeconds", "startup_timeout_sec", "startupTimeout",
            "handshakeTimeout", "initializeTimeout",
        )
        for (key in keys) {
            if (!entry.has(key) || entry.isNull(key)) continue
            // optInt returns 0 for a non-numeric string; distinguish that from a
            // real 0 by re-parsing the raw value.
            val raw = entry.get(key)
            when (raw) {
                is Number -> return raw.toInt()
                is String -> raw.trim().toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun entryToJson(s: MCPServerConfig): JSONObject {
        val o = JSONObject()
        if (s.isStdio) {
            o.put("command", s.command)
            if (s.args.isNotEmpty()) o.put("args", JSONArray(s.args))
            if (s.env.isNotEmpty()) o.put("env", JSONObject(s.env))
            s.startupTimeoutSeconds?.let { o.put("startupTimeoutSeconds", it) }
        } else if (!s.url.isNullOrBlank()) {
            o.put("url", s.url)
            if (s.headers.isNotEmpty()) o.put("headers", JSONObject(s.headers))
            // [T-android-mcp-oauth] Persist the non-secret OAuth config alongside
            // the URL. Secret + tokens live in MCPOAuthStore, never in JSON.
            // [T-mcp-oauth-signin-android] A browser sign-in entry is saved
            // even before its first sign-in (iOS #432: dropping it made the
            // entry reopen as "None" and the guest stop sending the token).
            s.oauth?.takeIf { it.isConfigured || it.isDynamic }?.let { o.put("oauth", it.toJson()) }
        }
        s.note?.takeIf { it.isNotBlank() }?.let { o.put("note", it) }
        o.put("enabled", s.enabled)
        o.put("createdAt", s.createdAt)
        return o
    }

    private fun stringMap(obj: JSONObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (k in obj.keys()) out[k] = obj.optString(k, "")
        return out
    }

    /**
     * [T-mcp-server-export-android] Serialize a SINGLE server as standard,
     * importable `{ "mcpServers": { "<id>": { … } } }` JSON. Values are exported
     * VERBATIM ($$VAR references and literals as-is). The output is the same
     * shape importJSON reads, so it round-trips. [server] may be built from the
     * live edit-form fields, so the export reflects unsaved edits.
     */
    fun exportServerJSON(server: MCPServerConfig): String {
        val mcpServers = JSONObject().put(server.id, entryToJson(server))
        return JSONObject().put("mcpServers", mcpServers).toString(2)
    }

    // -- CRUD --

    /** Add or overwrite a server (rename/overwrite is last-write-wins by id). */
    fun add(server: MCPServerConfig): Boolean {
        if (server.id.isBlank()) return false
        _servers.value = _servers.value.filter { it.id != server.id } + server
        save()
        Log.i(TAG, "Added MCP server: ${server.id}")
        return true
    }

    fun update(server: MCPServerConfig): Boolean {
        if (_servers.value.none { it.id == server.id }) return false
        _servers.value = _servers.value.map { if (it.id == server.id) server else it }
        save()
        return true
    }

    fun delete(id: String) {
        db.execSQL("DELETE FROM mcp_session_overrides WHERE mcp_id=?", arrayOf(id))
        _servers.value = _servers.value.filter { it.id != id }
        save()
        Log.i(TAG, "Deleted MCP server: $id")
    }

    fun setEnabled(id: String, enabled: Boolean) {
        _servers.value = _servers.value.map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }
        save()
    }

    fun toggle(id: String) {
        val current = _servers.value.find { it.id == id } ?: return
        setEnabled(id, !current.enabled)
    }

    // -- Session Overrides (mirror SkillRepository) --

    fun isEnabledForSession(mcpId: String, sessionId: String): Boolean {
        val cursor = db.rawQuery(
            "SELECT is_enabled FROM mcp_session_overrides WHERE session_id=? AND mcp_id=?",
            arrayOf(sessionId, mcpId)
        )
        val override = if (cursor.moveToFirst()) cursor.getInt(0) == 1 else null
        cursor.close()
        if (override != null) return override
        return _servers.value.find { it.id == mcpId }?.enabled ?: false
    }

    /**
     * [T-mcp-review-fixes-android] FIX 3: sparse-clear to match iOS. When the
     * chosen [enabled] equals the server's CURRENT global enabled, DELETE the
     * override row instead of pinning it — so a later flip of the global
     * default propagates to this session (a permanently-pinned row would leave
     * the session stuck at the old value). Only store a row when the session
     * value genuinely diverges from the global default. Read precedence
     * (override → global → false) in [isEnabledForSession] is unchanged.
     */
    fun setSessionOverride(sessionId: String, mcpId: String, enabled: Boolean) {
        val globalEnabled = _servers.value.find { it.id == mcpId }?.enabled
        if (globalEnabled != null && enabled == globalEnabled) {
            db.execSQL(
                "DELETE FROM mcp_session_overrides WHERE session_id=? AND mcp_id=?",
                arrayOf(sessionId, mcpId)
            )
        } else {
            db.execSQL(
                "INSERT OR REPLACE INTO mcp_session_overrides (session_id, mcp_id, is_enabled) VALUES (?, ?, ?)",
                arrayOf<Any>(sessionId, mcpId, if (enabled) 1 else 0)
            )
        }
    }

    fun clearSessionOverrides(sessionId: String) {
        db.execSQL("DELETE FROM mcp_session_overrides WHERE session_id=?", arrayOf(sessionId))
    }

    /**
     * [T-android-session-skill-override-init-timing] Re-point every
     * `mcp_session_overrides` row that was written against the draft session
     * id ([fromDraft], e.g. `__new__<uuid>`) onto the persisted session id
     * ([toReal]) once `ensureSession()` creates the real DB row. Without
     * this hop, a pre-first-message MCP toggle stays bound to the draft key
     * and becomes invisible the next time the chat is opened under its real
     * id. Paired with [SkillRepository.renameSessionOverrides].
     */
    fun renameSessionOverrides(fromDraft: String, toReal: String) {
        if (fromDraft == toReal) return
        db.execSQL(
            "UPDATE OR REPLACE mcp_session_overrides SET session_id=? WHERE session_id=?",
            arrayOf<Any>(toReal, fromDraft),
        )
    }

    // -- JSON Import (4 format variants, last-write-wins on name) --

    /**
     * Parse pasted JSON and import any server entries. Returns the imported
     * configs (already merged into state + written to disk), empty on no-op.
     *
     * Accepted formats (probed in priority order):
     *   1. `{ "mcpServers": { "name": { … } } }` — standard Claude Desktop.
     *   2. `{ "name": { "command"|"url": … } }` — name-keyed object.
     *   3. `{ "command": "npx", … }` / `{ "url": … }` — single bare entry
     *      (name derived from a `name` field or "imported-mcp").
     *   4. Any of the above with `disabled: true` → enabled=false.
     * Rename/overwrite is last-write-wins by name.
     */
    fun importJSON(text: String): List<MCPServerConfig> {
        val root = try { JSONObject(text.trim()) } catch (e: Exception) {
            Log.w(TAG, "importJSON: not valid JSON: ${e.message}")
            return emptyList()
        }
        val parsed = parseImportRoot(root)
        if (parsed.isEmpty()) return emptyList()
        // Merge: overwrite same-name entries, keep the rest.
        val incomingIds = parsed.mapTo(HashSet()) { it.id }
        _servers.value = _servers.value.filter { it.id !in incomingIds } + parsed
        _servers.value = _servers.value.sortedByDescending { it.createdAt }
        save()
        Log.i(TAG, "Imported ${parsed.size} MCP server(s)")
        return parsed
    }

    /**
     * Preview-only parse: how many servers a paste would import, without
     * mutating state. Powers the import sheet's "found N servers" hint.
     */
    fun previewImport(text: String): Int {
        val root = try { JSONObject(text.trim()) } catch (_: Exception) { return 0 }
        return parseImportRoot(root).size
    }

    private fun parseImportRoot(root: JSONObject): List<MCPServerConfig> {
        // Variant 1: { "mcpServers": { … } }
        root.optJSONObject("mcpServers")?.let { obj ->
            return obj.keys().asSequence().mapNotNull { name ->
                obj.optJSONObject(name)?.let { parseEntry(name, it) }
            }.toList()
        }
        // Variant 3: single bare entry { "command"|"url": … }
        if (root.has("command") || root.has("url")) {
            val name = root.optString("name", "").ifBlank { "imported-mcp" }
            return listOf(parseEntry(name, root))
        }
        // Variant 2: name-keyed object { "name": { "command"|"url": … } }
        val out = mutableListOf<MCPServerConfig>()
        for (name in root.keys()) {
            val entry = root.optJSONObject(name) ?: continue
            if (entry.has("command") || entry.has("url")) {
                out.add(parseEntry(name, entry))
            }
        }
        return out
    }

    // -- Prompt Fragment (Top 20) --

    /**
     * Build the system-prompt fragment disclosing enabled MCP servers, Top
     * [MAX_MCPS_IN_PROMPT] sorted by recent-add (createdAt desc — Android has
     * no server-side use tracking; the CLI owns invocation). Notes are
     * truncated at [MAX_NOTE_LENGTH] to match the skill description cap.
     * Returns null when the session has no enabled servers. Format per the
     * feature design doc §6a.
     */
    fun mcpPromptFragment(sessionId: String): String? {
        val enabled = _servers.value
            .filter { isEnabledForSession(it.id, sessionId) }
            .sortedByDescending { it.createdAt }
        if (enabled.isEmpty()) return null

        val selected = enabled.take(MAX_MCPS_IN_PROMPT)

        return buildString {
            append("Available MCP Servers (use minis-mcp-cli to discover and call):\n")
            for (s in selected) {
                var note = s.note ?: ""
                if (note.length > MAX_NOTE_LENGTH) note = note.substring(0, MAX_NOTE_LENGTH) + "…"
                append("- ").append(s.id)
                if (note.isNotBlank()) append(": ").append(note)
                append("\n")
            }
            append("\n")
            append("To use: run `minis-mcp-cli tools <server>` to see available tools,\n")
            append("then `minis-mcp-cli call <server> <tool> [args]` to invoke.\n")
            // [T-mcp-dollar-var-systemprompt-android] Document the $$VAR runtime
            // env placeholder (mirrors iOS 5fa9e6a9). Agent-facing English — not
            // localized; wording must match iOS verbatim.
            append("When adding or modifying an MCP server config (via minis-mcp-cli add / the UI), use \$\$VARNAME in env/headers/url values as a placeholder resolved at runtime from the system/App environment variables — do not hardcode secrets; reference an existing App environment variable as \$\$NAME.")
        }
    }

    // -- Database Helper (session overrides only) --

    private class McpDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE mcp_session_overrides (
                    session_id TEXT NOT NULL,
                    mcp_id TEXT NOT NULL,
                    is_enabled INTEGER NOT NULL,
                    PRIMARY KEY (session_id, mcp_id)
                )
            """)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 only — no migrations yet.
        }
    }
}

// ─── Merged from MemoryRepository.kt ──────────────────────────────────────────────────

/**
 * Manages the memory directory (`minis-global/memory/`).
 * Mirrors iOS memory system:
 *   - GLOBAL.md: user-maintained via Settings; the agent may update it when the user asks
 *   - YYYY-MM-DD.md: daily logs with timestamped entries, agent writes via memory_write
 *   - memory_get: fuzzy keyword search across files
 *   - loadGlobalMemoryFragment() / loadRecentDailyMemoryFragment(): emit
 *     two separate text blocks for the system prompt (mirrors iOS exactly)
 */
class MemoryRepository(private val memoryDir: File) {

    companion object {
        private const val TAG = "MemoryRepository"
        private const val GLOBAL_FILE = "GLOBAL.md"
        private const val MAX_INJECT_LINES = 200
        // memory_get full-dump (no keywords): cap at 500 lines — matches iOS
        // `maxTotalLines = 500` in AIChatViewModel+MemoryTools.swift.
        private const val MAX_DUMP_LINES = 500
        // memory_get keyword search: cap at 60 lines. iOS caps at 60 *entries*
        // (timestamp-delimited memory_write blocks); Android's keyword search
        // is line-based with ±2 context windows, so we keep the same algorithm
        // and align on the 60 magnitude as the line budget.
        private const val MAX_SEARCH_LINES = 60
        private const val MAX_LOOKBACK_DAYS = 30
        private const val MAX_RECENT_FILES = 3

        /**
         * [XSessionDiag] Vocabulary that suggests an injected daily log describes
         * a TASK a model might try to resume, rather than a plain fact. Purely a
         * log tag — nothing branches on it, and both languages are listed because
         * the reported incidents were Chinese-language sessions.
         */
        private val DIAG_TASK_KEYWORDS = listOf(
            "任务", "调研", "继续", "接着", "未完成", "下一步",
            "task", "continue", "resume", "TODO",
        )
        // [T-memory-get-truncate-android] Hard byte ceiling on memory_get
        // output. Line caps alone (MAX_DUMP_LINES / MAX_SEARCH_LINES) don't
        // bound bandwidth when a single matched line is itself huge — TG
        // 37452 hit a 70KB single-call result that froze the chat UI for
        // several seconds when expanded. 30KB is the comfort budget for
        // an agent tool result that needs to be both rendered AND fed
        // back into the next LLM call. Counted as UTF-8 bytes (matches
        // what the provider sees over the wire).
        private const val MAX_OUTPUT_BYTES = 30 * 1024  // 30 KB
    }

    init {
        memoryDir.mkdirs()
    }

    // -- memory_write --

    /**
     * Append a timestamped entry to today's daily log.
     * New entries are prepended (newest first).
     * Returns a success/error message string.
     */
    fun writeMemory(content: String): String {
        if (content.isBlank()) return "Error: Missing required 'content' parameter"

        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val fileName = "${dateFmt.format(Date())}.md"
        val file = File(memoryDir, fileName)

        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val timestamp = timeFmt.format(Date())
        val entry = "<!-- $timestamp -->\n$content\n\n"

        val existing = if (file.exists()) file.readText() else ""
        val newContent = entry + existing

        return try {
            file.writeText(newContent)
            Log.i(TAG, "Memory written to $fileName (${content.length} chars)")
            "Memory saved to $fileName (${content.length} chars)"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write memory", e)
            "Error writing memory: ${e.message}"
        }
    }

    // -- memory_get --

    /**
     * Fuzzy keyword search across memory files.
     * @param keywords space-separated, case-insensitive, ALL must match
     * @param scope "daily" (logs only) or "all" (include GLOBAL.md)
     * @return search results with context lines
     */
    fun getMemory(keywords: String, scope: String): String {
        val keywordList = keywords.trim()
            .lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }

        val filesToSearch = mutableListOf<Pair<String, File>>() // label to file

        if (scope == "all") {
            val globalFile = File(memoryDir, GLOBAL_FILE)
            if (globalFile.exists() && globalFile.length() > 0) {
                filesToSearch.add(GLOBAL_FILE to globalFile)
            }
        }

        // Daily logs sorted descending
        val dailyFiles = memoryDir.listFiles()
            ?.filter { it.extension == "md" && it.name != GLOBAL_FILE }
            ?.sortedByDescending { it.name }
            ?: emptyList()

        for (file in dailyFiles) {
            filesToSearch.add(file.name to file)
        }

        if (filesToSearch.isEmpty()) {
            return "No memory files found."
        }

        val results = mutableListOf<String>()
        var totalLines = 0
        // [T-memory-get-truncate-android] UTF-8 byte tally — see
        // MAX_OUTPUT_BYTES rationale in the companion object. Bytes are
        // accumulated AFTER appending each result entry; the line check is
        // still consulted first so we never over-allocate slicing windows.
        var totalBytes = 0
        // Total ranges/files matched vs. files actually included in the
        // returned output. Reported in the truncation note so the caller
        // can tell whether the cap dropped further matches.
        var totalMatchedFiles = 0
        var includedFiles = 0
        var byteCapHit = false
        // Two separate caps so a full dump (no keywords) gets enough room to
        // show recent daily logs while keyword searches stay tight enough not
        // to flood agent context.
        val lineCap = if (keywordList.isEmpty()) MAX_DUMP_LINES else MAX_SEARCH_LINES

        for ((label, file) in filesToSearch) {
            if (totalLines >= lineCap || byteCapHit) break
            val content = try { file.readText() } catch (_: Exception) { continue }
            if (content.isEmpty()) continue
            val budget = lineCap - totalLines

            val entry: String? = if (keywordList.isEmpty()) {
                // Return file preview
                val lines = content.lines()
                val take = minOf(lines.size, budget)
                val preview = lines.take(take).joinToString("\n")
                val truncated = if (lines.size > take) " (showing first $take of ${lines.size} lines)" else ""
                totalLines += take
                totalMatchedFiles += 1
                "[$label$truncated]\n$preview"
            } else {
                // Keyword search with ±2 context window
                val lines = content.lines()
                val matchedRanges = mutableListOf<IntRange>()

                for (i in lines.indices) {
                    val windowStart = maxOf(0, i - 2)
                    val windowEnd = minOf(lines.size - 1, i + 2)
                    val windowText = lines.subList(windowStart, windowEnd + 1)
                        .joinToString(" ").lowercase()

                    if (keywordList.all { windowText.contains(it) }) {
                        matchedRanges.add(windowStart..windowEnd)
                    }
                }

                if (matchedRanges.isEmpty()) {
                    null
                } else {
                    totalMatchedFiles += 1
                    val merged = mergeRanges(matchedRanges)
                    val fileMatches = mutableListOf<String>()

                    for (range in merged) {
                        val chunkLines = range.last - range.first + 1
                        if (totalLines + chunkLines > lineCap) {
                            val remaining = lineCap - totalLines
                            if (remaining > 0) {
                                fileMatches.add(lines.subList(range.first, range.first + remaining).joinToString("\n"))
                                totalLines += remaining
                            }
                            break
                        }
                        fileMatches.add(lines.subList(range.first, range.last + 1).joinToString("\n"))
                        totalLines += chunkLines
                    }

                    if (fileMatches.isNotEmpty())
                        "[$label — ${fileMatches.size} match(es)]\n${fileMatches.joinToString("\n---\n")}"
                    else null
                }
            }

            if (entry != null) {
                results.add(entry)
                includedFiles += 1
                // Byte accounting is conservative: count the entry itself
                // PLUS the "\n\n" separator between entries (added at the
                // joinToString tail). We break AFTER appending so a single
                // large entry never gets silently dropped — the cap acts as
                // a "this was the last one we'll show" gate, not a guillotine
                // on the current entry.
                totalBytes += entry.toByteArray(Charsets.UTF_8).size + 2
                if (totalBytes >= MAX_OUTPUT_BYTES) byteCapHit = true
            }
        }

        if (results.isEmpty()) {
            return "No matches found for keywords: ${keywordList.joinToString(", ")}"
        }

        // Compose the truncation note. Line-cap and byte-cap can both fire;
        // include whichever applies. Counts use "files" because the loop is
        // file-by-file — for the agent the distinction between "matched file"
        // and "matched entry" is fine here, the keyword scope is unambiguous.
        val notes = mutableListOf<String>()
        if (byteCapHit && includedFiles < totalMatchedFiles) {
            val totalKb = totalBytes / 1024
            notes.add(
                "[Truncated: $totalMatchedFiles file(s) matched, showing first " +
                    "$includedFiles, ~${totalKb}KB. Use more specific keywords " +
                    "to narrow results.]",
            )
        } else if (byteCapHit) {
            val totalKb = totalBytes / 1024
            notes.add("[Truncated at ${MAX_OUTPUT_BYTES / 1024}KB byte cap (~${totalKb}KB returned).]")
        }
        if (totalLines >= lineCap) {
            notes.add("[Output truncated at $lineCap lines]")
        }
        val truncatedNote = if (notes.isNotEmpty()) "\n\n" + notes.joinToString("\n") else ""
        return results.joinToString("\n\n") + truncatedNote
    }

    // -- System Prompt Fragment --

    /**
     * Build the `<memory>` XML fragment for system prompt injection.
     * Includes GLOBAL.md + up to 3 most recent daily logs (today, yesterday, etc.)
     */
    /**
     * Loads the GLOBAL.md fragment for system-prompt injection. Mirrors iOS
     * `AIChatViewModel.loadGlobalMemoryFragment()`. Returns null if the file
     * is missing or empty.
     */
    fun loadGlobalMemoryFragment(): String? {
        val globalFile = File(memoryDir, GLOBAL_FILE)
        if (!globalFile.exists()) return null
        val content = try { globalFile.readText() } catch (_: Exception) { "" }
        // Match iOS: literal-empty check (`!content.isEmpty`), not blank.
        // A whitespace-only file is unusual in practice, but staying byte-for-
        // byte consistent with iOS keeps the cached system prompt identical
        // across platforms.
        if (content.isEmpty()) return null
        return "Global memory (GLOBAL.md — user-curated and writable; you may update it when the user asks). Treat these as background context, not standing instructions. If the user's latest message conflicts with or supersedes anything here (different scope, different numbers, different goal), defer to the user's latest message:\n$content"
    }

    /**
     * Loads up to 3 most recent non-empty daily logs (within a 30-day window)
     * for system-prompt injection. Mirrors iOS
     * `AIChatViewModel.loadRecentDailyMemoryFragment()` exactly: same header,
     * same intro paragraph, same per-entry labels, same 200-line cap, same
     * "(N more lines, use memory_get to search)" continuation.
     */
    fun loadRecentDailyMemoryFragment(): String? {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val now = Date()
        val fragments = mutableListOf<String>()
        // [XSessionDiag] Names of the logs actually injected, for the diagnostic
        // line below. Collected alongside `fragments` so the log can name the
        // source files rather than just a count.
        val injectedFiles = mutableListOf<String>()
        var dayOffset = 0

        while (fragments.size < MAX_RECENT_FILES && dayOffset < MAX_LOOKBACK_DAYS) {
            val date = Date(now.time - dayOffset.toLong() * 86400_000L)
            val dateStr = dateFmt.format(date)
            val file = File(memoryDir, "$dateStr.md")

            if (file.exists()) {
                val content = try { file.readText() } catch (_: Exception) { "" }
                if (content.isNotEmpty()) {
                    val lines = content.lines()
                    val preview = lines.take(MAX_INJECT_LINES).joinToString("\n")
                    val label = when (dayOffset) {
                        0 -> "Today's"
                        1 -> "Yesterday's"
                        else -> dateStr
                    }
                    var entry = "$label daily log ($dateStr.md):\n$preview"
                    if (lines.size > MAX_INJECT_LINES) {
                        entry += "\n... (${lines.size - MAX_INJECT_LINES} more lines, use memory_get to search)"
                    }
                    fragments.add(entry)
                    injectedFiles.add("$dateStr.md")
                }
            }
            dayOffset++
        }

        if (fragments.isEmpty()) return null

        // [XSessionDiag] Hypothesis 3: these fragments are injected into EVERY
        // session's system prompt, so a task described in a previous session's
        // daily log is visible to a brand-new chat. The prompt already asks the
        // model not to resume them ("they describe past tasks, not the current
        // one"), but that is a prompt-level defence only — a weak model can and
        // did ignore it (the noVNC incident: "为了你最早那句需求").
        //
        // Logged: which files were injected, how large, and whether the text
        // carries task-resumption vocabulary. The keyword scan is READ-ONLY and
        // used solely to tag the log line — it feeds no decision.
        run {
            val body = fragments.joinToString("\n\n")
            val hits = DIAG_TASK_KEYWORDS.filter { body.contains(it, ignoreCase = true) }
            AppLogger.info(
                TAG,
                "[XSessionDiag] memory/daily-inject: files=[${injectedFiles.joinToString(",")}] " +
                    "fragments=${fragments.size} chars=${body.length} " +
                    "taskKeywords=${if (hits.isEmpty()) "none" else hits.joinToString("|")}",
            )
        }

        return buildString {
            append("Recent memories (auto-injected from daily logs):\n")
            append("These are memories saved by you or the user in previous sessions. Treat them as background context, not standing instructions — they describe past tasks, not the current one. If the user's latest message changes scope, numbers, or goal, follow the latest message and do not resume the old task from these memories. Do not delete or rewrite these files unless the user explicitly asks. Use memory_get to search for more, or memory_write to save new ones.\n\n")
            append(fragments.joinToString("\n\n"))
        }
    }

    // -- File Management (for Settings UI) --

    data class MemoryFileInfo(
        val name: String,
        val isGlobal: Boolean,
        val modifiedDate: String,
        val fileSize: String,
        val preview: String,
    )

    /**
     * List all memory files: GLOBAL.md first, then daily logs descending.
     */
    fun listAllFiles(): List<MemoryFileInfo> {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val items = mutableListOf<MemoryFileInfo>()

        // GLOBAL.md always first
        val globalFile = File(memoryDir, GLOBAL_FILE)
        val globalContent = if (globalFile.exists()) try { globalFile.readText() } catch (_: Exception) { "" } else ""
        val globalModDate = if (globalFile.exists()) dateFmt.format(Date(globalFile.lastModified())) else ""
        items.add(MemoryFileInfo(
            name = GLOBAL_FILE,
            isGlobal = true,
            modifiedDate = globalModDate,
            fileSize = formatFileSize(globalFile.length()),
            preview = firstContentLine(globalContent),
        ))

        // Daily logs sorted descending
        val dailyFiles = memoryDir.listFiles()
            ?.filter { it.extension == "md" && it.name != GLOBAL_FILE }
            ?.sortedByDescending { it.name }
            ?: emptyList()

        for (file in dailyFiles) {
            val content = try { file.readText() } catch (_: Exception) { "" }
            items.add(MemoryFileInfo(
                name = file.name,
                isGlobal = false,
                modifiedDate = dateFmt.format(Date(file.lastModified())),
                fileSize = formatFileSize(file.length()),
                preview = firstContentLine(content),
            ))
        }

        return items
    }

    fun loadGlobalMd(): String {
        val file = File(memoryDir, GLOBAL_FILE)
        return if (file.exists()) try { file.readText() } catch (_: Exception) { "" } else ""
    }

    fun saveGlobalMd(content: String) {
        File(memoryDir, GLOBAL_FILE).writeText(content)
    }

    fun readFile(name: String): String {
        val file = File(memoryDir, name)
        return if (file.exists()) try { file.readText() } catch (_: Exception) { "" } else ""
    }

    fun saveFile(name: String, content: String) {
        File(memoryDir, name).writeText(content)
    }

    fun deleteFile(name: String): Boolean {
        if (name == GLOBAL_FILE) return false // Cannot delete GLOBAL.md
        return File(memoryDir, name).delete()
    }

    // -- Entry-level operations (used by Session Memory revoke/edit) --

    /**
     * Result of [revokeEntry] / [replaceEntryBody]. Mirrors iOS
     * `revokeEntry` / `replaceEntryInLog` return shapes.
     */
    sealed class EntryMutationResult {
        /** Entry found in [dateStr].md and the requested mutation succeeded. */
        data class Success(val dateStr: String) : EntryMutationResult()

        /** Scanned today + yesterday but the body never matched. */
        data object NotFound : EntryMutationResult()

        /** Match found but writing the new file content failed. */
        data class IOError(val message: String) : EntryMutationResult()
    }

    /**
     * Remove a memory_write entry whose body matches [writtenContent] from
     * today's or yesterday's daily log. Mirrors iOS
     * `MemoryWriteDetailView.revokeEntry()`.
     *
     * Each entry on disk is `<!-- YYYY-MM-DD HH:mm:ss -->\n{body}\n\n`. The
     * comment marker is the canonical entry boundary; we split on it via
     * regex, locate the entry whose trimmed body equals the trimmed
     * [writtenContent], then erase the entire range (marker + body +
     * trailing whitespace).
     *
     * Scope is intentionally limited to today + yesterday to match iOS — older
     * entries are presumed already syndicated into the model's longer-term
     * memory and shouldn't be silently mutated by an undo button.
     */
    fun revokeEntry(writtenContent: String): EntryMutationResult {
        val trimmedTarget = writtenContent.trim()
        val candidates = candidateDateStrings()
        val markerRegex = Regex("""<!-- \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} -->\n""")

        for (dateStr in candidates) {
            val file = File(memoryDir, "$dateStr.md")
            if (!file.exists()) continue
            val content = try { file.readText() } catch (_: Exception) { continue }

            val matches = markerRegex.findAll(content).toList()
            if (matches.isEmpty()) continue

            for ((i, match) in matches.withIndex()) {
                val bodyStart = match.range.last + 1
                val entryEnd = matches.getOrNull(i + 1)?.range?.first ?: content.length
                val body = content.substring(bodyStart, entryEnd)
                if (body.trim() != trimmedTarget) continue

                val newContent = content.removeRange(match.range.first, entryEnd)
                return try {
                    file.writeText(newContent)
                    Log.i(TAG, "Revoked memory entry from $dateStr.md")
                    EntryMutationResult.Success(dateStr)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write $dateStr.md after revoke", e)
                    EntryMutationResult.IOError(e.message ?: "Unknown I/O error")
                }
            }
        }
        return EntryMutationResult.NotFound
    }

    /**
     * Replace the body of an existing memory_write entry whose body matches
     * [oldContent], substituting [newContent]. Same scoping/matching rules as
     * [revokeEntry]. Mirrors iOS `MemoryWriteDetailView.replaceEntryInLog()`.
     */
    fun replaceEntryBody(oldContent: String, newContent: String): EntryMutationResult {
        val trimmedOld = oldContent.trim()
        val trimmedNew = newContent.trim()
        val candidates = candidateDateStrings()
        val markerRegex = Regex("""<!-- \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} -->\n""")

        for (dateStr in candidates) {
            val file = File(memoryDir, "$dateStr.md")
            if (!file.exists()) continue
            val content = try { file.readText() } catch (_: Exception) { continue }

            val matches = markerRegex.findAll(content).toList()
            if (matches.isEmpty()) continue

            for ((i, match) in matches.withIndex()) {
                val bodyStart = match.range.last + 1
                val entryEnd = matches.getOrNull(i + 1)?.range?.first ?: content.length
                val body = content.substring(bodyStart, entryEnd)
                if (body.trim() != trimmedOld) continue

                // iOS replaces with `trimmed + "\n\n"` so the on-disk
                // separator between entries stays uniform.
                val replacement = "$trimmedNew\n\n"
                val newFileContent = content.replaceRange(bodyStart, entryEnd, replacement)
                return try {
                    file.writeText(newFileContent)
                    Log.i(TAG, "Replaced memory entry body in $dateStr.md")
                    EntryMutationResult.Success(dateStr)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write $dateStr.md after edit", e)
                    EntryMutationResult.IOError(e.message ?: "Unknown I/O error")
                }
            }
        }
        return EntryMutationResult.NotFound
    }

    private fun candidateDateStrings(): List<String> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val now = Date()
        return listOf(fmt.format(now), fmt.format(Date(now.time - 86400_000L)))
    }

    // -- Internal --

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        return "%.1f MB".format(mb)
    }

    private fun firstContentLine(content: String): String {
        return content.lines()
            .firstOrNull { it.isNotBlank() && !it.startsWith("<!--") }
            ?.take(100)
            ?: ""
    }

    private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val result = mutableListOf(sorted[0])
        for (i in 1 until sorted.size) {
            val last = result.last()
            val current = sorted[i]
            if (current.first <= last.last + 1) {
                result[result.lastIndex] = last.first..maxOf(last.last, current.last)
            } else {
                result.add(current)
            }
        }
        return result
    }
}

// ─── Merged from ModelEntryRanking.kt ──────────────────────────────────────────────────

/**
 * [T-model-release-ranking] The one place that orders a list of [ModelEntry]
 * for display: newest / most capable first.
 *
 * Extracted from `ProviderRepository` because the ordering has two kinds of
 * caller and only one of them goes through the repository. `entriesFor` /
 * `visibleEntries` / `allVisibleEntries` return sorted lists, but the model
 * picker sheets build their per-provider lists by filtering a `ProviderConfig`
 * snapshot directly — they never touch those accessors, so they were showing
 * raw config order (instance cluster, then model id alphabetically) and
 * silently losing the release ranking the provider-detail list already had.
 *
 * Why the ranking matters at all: a picker whose first entries are stale is an
 * active hazard. OpenMinis#83 was filed as "GPT-5.3 CodeX Spark cannot call
 * tools"; the real cause was that the backend refuses that model, and the
 * refusal renders as an EMPTY assistant turn. A user picked a dead model off
 * the top of the list and concluded the app was broken.
 *
 * Mirrors iOS `ProviderConfigStore.releaseRankOrder`, which
 * `UnifiedModelPicker` applies for exactly the same reason.
 */
object ModelEntryRanking {

    /**
     * Falls back to the model id so the ordering is total and stable when two
     * entries rank identically; otherwise the list could visibly reshuffle
     * between reads.
     */
    private val releaseRankOrder = Comparator<Pair<ModelEntry, ModelReleaseIndex.Rank>> { a, b ->
        val byRank = ModelReleaseIndex.comparator.compare(a.second, b.second)
        if (byRank != 0) byRank else a.first.baseModel.id.compareTo(b.first.baseModel.id)
    }

    /**
     * [T-android-model-sort-anr] Rank each entry ONCE, then sort the ranked
     * pairs — the decorate-sort-undecorate shape.
     *
     * A plain `sortedWith(comparator)` that called [ModelReleaseIndex.rank] on
     * both sides of every comparison ran ranking O(n log n) times instead of
     * O(n). On a real device carrying 939 visible entries across 19 provider
     * instances that is ~18,600 rank computations for one sort, on the main
     * thread during `ChatViewModel.init` -> `loadSession` ->
     * `applyNewChatDefaultModel`. Under memory pressure it blew past the 5s
     * input-dispatch deadline and the app ANR'd while opening a chat. `rank()`
     * is a hash lookup, not free: it also faults in the lazily-built index on
     * first call.
     *
     * Keep this shape in any new caller — the picker sheets sort per provider
     * instance, so the naive form would pay that cost once per section.
     */
    fun sortedByReleaseRank(entries: List<ModelEntry>): List<ModelEntry> {
        // Nothing to order, and — more importantly — nothing that should force
        // the release index to be built. A device with no models configured
        // must not pay a catalog parse here.
        if (entries.size < 2) return entries
        return entries
            .map {
                it to ModelReleaseIndex.rank(
                    it.baseModel.id, it.baseModel.displayName, it.baseModel.contextWindow,
                )
            }
            .sortedWith(releaseRankOrder)
            .map { it.first }
    }
}

// ─── Merged from ModelReconciler.kt ──────────────────────────────────────────────────

/**
 * [T-provider-refresh-outlives-screen] (GH#265) Runs a provider's model-list
 * refresh in a scope that belongs to the repository, not to the screen that
 * asked for it.
 *
 * The bug: Add Provider saved the instance, launched
 * `refreshModels(instance)` on the screen's `rememberCoroutineScope()`, and
 * called `onSaved()` on the very next line, which pops the screen. Popping
 * disposes the composition and cancels that scope, so the fetch died before
 * it reached the network and the new provider kept the built-in placeholder
 * catalog — for xAI, a list without `grok-4.6` — until the user found
 * Models ▸ Refresh by hand. A model refresh is background data sync; it must
 * not share the lifetime of a screen that is about to go away.
 *
 * The instance is looked up by id when the job RUNS, so it refreshes what is
 * saved at that moment (a deleted instance is skipped), and a failure is
 * logged rather than thrown into a scope with no one listening.
 */
internal class ModelReconciler(
    private val scope: CoroutineScope,
    private val lookup: (String) -> ProviderInstance?,
    private val refresh: suspend (ProviderInstance, Boolean) -> Unit,
) {
    fun trigger(instanceId: String, forceRefresh: Boolean): Job = scope.launch {
        val instance = lookup(instanceId)
        if (instance == null) {
            Log.i(TAG, "[ModelReconcile] $instanceId no longer exists; skipped")
            return@launch
        }
        try {
            refresh(instance, forceRefresh)
            Log.i(TAG, "[ModelReconcile] refreshed ${instance.id} (${instance.providerType}) force=$forceRefresh")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "[ModelReconcile] refresh failed for ${instance.id}: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "ProviderRepo"
    }
}

// ─── Merged from SessionSearch.kt ──────────────────────────────────────────────────

/**
 * [T-android-search-visible-only] Session-list search: what a result shows and
 * which messages may make a session one. Port of iOS ChatStore
 * (6b0ee14c1 T-search-hit-line, bbd21900a T-search-visible-only).
 *
 * A result must show WHY it matched: its title holds the query, or a line of a
 * message the user can actually see does. Search used to match the raw
 * `parts_json` — tool-call ids and Gemini thought signatures included, random
 * base64 in which a short keyword like "gpt" turns up by chance — and then
 * showed a snippet from the oldest text hit only, so rows appeared with
 * nothing in them explaining the match.
 *
 * Pure: no database, no Android. [ChatRepository.searchSessions] runs the SQL.
 */
object SessionSearch {

    // ── Keywords ([T-search-multi-keyword], iOS b09df3824) ─────────────────
    //
    // The entry used to be ONE `LIKE '%entry%'`, spaces included: "apple
    // banana" found only sessions holding exactly that text in that order,
    // and `%` / `_` in it were wildcards ("100%" matched "1000 items", "a_b"
    // matched "axb"). Now whitespace separates keywords and a session holding
    // ANY of them is a result (iOS `SearchKeywords`; Windows
    // minis-core/src/search.rs has the same rules).
    //
    // Bind layout of every statement below, for n = terms.size:
    //   ?1 … ?n        the LIKE patterns ([likePattern]), see [searchArgs]
    //   ?n+1 … ?2n     the bare keywords (to cut the window of an oversized
    //                  row around the first one present, see [cappedParts])
    //   ?2n+1          the session id ([olderMatchesSql] only)

    /** Keywords past this many are ignored: each one adds a LIKE per message. */
    const val MAX_TERMS = 10

    /**
     * The keywords of [query]: split on any whitespace (runs of it, tabs,
     * newlines, the full-width space), empties dropped, a keyword repeated in
     * another letter case kept once, at most [MAX_TERMS]. Empty for a blank
     * query. Port of iOS `SearchKeywords.terms`.
     */
    fun searchTerms(query: String): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        val piece = StringBuilder()
        fun flush(): Boolean {
            if (piece.isEmpty()) return false
            val term = piece.toString()
            piece.setLength(0)
            if (seen.add(term.lowercase())) out.add(term)
            return out.size == MAX_TERMS
        }
        for (c in query) {
            if (!c.isWhitespace()) piece.append(c) else if (flush()) return out
        }
        flush()
        return out
    }

    /**
     * `%term%` for `LIKE … ESCAPE '\'`: `\`, `%` and `_` in the keyword are
     * matched as themselves, not as wildcards. Port of iOS `likePattern`.
     */
    fun likePattern(term: String): String {
        val escaped = StringBuilder(term.length + 2)
        for (c in term) {
            if (c == '\\' || c == '%' || c == '_') escaped.append('\\')
            escaped.append(c)
        }
        return "%$escaped%"
    }

    /** The arguments for a search statement over [terms]: patterns, then the bare keywords. */
    fun searchArgs(terms: List<String>): Array<Any> =
        (terms.map { likePattern(it) } + terms).toTypedArray()

    /**
     * `(column LIKE ?1 ESCAPE '\' OR column LIKE ?2 ESCAPE '\' …)`: [column]
     * holds any of the [termCount] patterns. Port of iOS `anyLike`.
     */
    fun anyLike(column: String, termCount: Int): String =
        (1..maxOf(1, termCount)).joinToString(" OR ", "(", ")") { "$column LIKE ?$it ESCAPE '\\'" }

    /**
     * SQL condition: message `m` holds one of the patterns in a field the user
     * can see — a text part, a tool call's input, or a tool's output. The plain
     * LIKE stays first as the cheap prefilter; `json_each` only runs on its
     * survivors. Identical to iOS `visibleHitClause(termCount:)`.
     */
    fun visibleHitClause(termCount: Int): String = """
        ${anyLike("m.parts_json", termCount)} AND EXISTS (
            SELECT 1 FROM json_each(m.parts_json) p
            WHERE (json_extract(p.value, '$.type') = 'text' AND ${anyLike("json_extract(p.value, '$.value')", termCount)})
               OR (json_extract(p.value, '$.type') = 'toolUse' AND ${anyLike("json_extract(p.value, '$.value.input')", termCount)})
               OR (json_extract(p.value, '$.type') = 'toolResult' AND ${anyLike("json_extract(p.value, '$.value.output')", termCount)}))
    """

    /**
     * [T-android-search-blob-cap] Longest `parts_json` a search query returns
     * whole. Android reads a result row through a 2 MB CursorWindow: one bigger
     * row throws SQLiteBlobTooBigException, which is also an SQLiteException,
     * so it used to be mistaken for "no JSON1" and the legacy query re-read the
     * same row and threw again, uncaught — every search for a word inside a big
     * restored tool output crashed the app. Such rows exist: iOS caps nothing,
     * and a backup restore inserts them as they are. Also bounds the memory a
     * broad query holds, since every result carries its hit message.
     */
    const val MAX_HIT_JSON_CHARS = 200_000

    /** Characters of an oversized row returned around its first hit. */
    const val HIT_WINDOW_CHARS = 4_000

    /**
     * Prefix of such a window. A real `parts_json` always starts with `[`, so
     * the mark cannot collide with one; it tells [searchSnippet] to read the
     * window as raw text, while any other unparseable row still gives no line,
     * as on iOS.
     */
    const val WINDOW_MARK = "#minis-search-window#"

    /**
     * `col` whole when it is short enough, else a [HIT_WINDOW_CHARS] window
     * starting a little before the first (ASCII case-insensitive, like LIKE)
     * occurrence of the first keyword, in the order typed, that `col` holds —
     * the keyword [searchSnippet] then takes its line from — marked with
     * [WINDOW_MARK]. The bare keywords are bound to ?n+1 … ?2n. The window is
     * not valid JSON; [searchSnippet] reads it as raw text.
     */
    fun cappedParts(col: String, termCount: Int): String {
        val n = maxOf(1, termCount)
        val firstHit = (n + 1..2 * n).joinToString(", ", "COALESCE(", ", 1)") { "NULLIF(instr(lower($col), lower(?$it)), 0)" }
        return """
        CASE WHEN length($col) <= $MAX_HIT_JSON_CHARS THEN $col
             ELSE '$WINDOW_MARK' || substr($col, max(1, $firstHit - ${HIT_WINDOW_CHARS / 4}), $HIT_WINDOW_CHARS)
        END"""
    }

    /**
     * Matching sessions (title, or a visible-field hit) with each session's
     * NEWEST such message: SQLite returns a bare column beside MAX() from that
     * row. Binds [searchArgs].
     */
    fun visibleSearchSql(termCount: Int): String = """
        WITH newest AS (
            SELECT m.session_id, m.parts_json, MAX(m.sort_order)
            FROM messages m
            WHERE ${visibleHitClause(termCount)}
            GROUP BY m.session_id)
        SELECT s.*, ${cappedParts("n.parts_json", termCount)} AS hit_parts_json
        FROM sessions s
        LEFT JOIN newest n ON n.session_id = s.id
        WHERE ${anyLike("s.title", termCount)} OR n.session_id IS NOT NULL
        ORDER BY s.updated_at DESC
    """

    /** Fallback when the SQLite build has no JSON functions: raw-JSON LIKE, as before. Binds [searchArgs]. */
    fun legacySearchSql(termCount: Int): String = """
        SELECT s.*,
               (SELECT ${cappedParts("m2.parts_json", termCount)}
                FROM messages m2
                WHERE m2.session_id = s.id AND ${anyLike("m2.parts_json", termCount)}
                ORDER BY m2.sort_order DESC LIMIT 1) AS hit_parts_json
        FROM sessions s
        WHERE ${anyLike("s.title", termCount)}
           OR EXISTS (SELECT 1 FROM messages m WHERE m.session_id = s.id AND ${anyLike("m.parts_json", termCount)})
        ORDER BY s.updated_at DESC
    """

    /**
     * Up to 20 matching messages of one session, newest first (the
     * look-further-back pass). Binds [searchArgs], then the session id at
     * ?2n+1.
     */
    fun olderMatchesSql(visibleOnly: Boolean, termCount: Int): String {
        val n = maxOf(1, termCount)
        return """
        SELECT ${cappedParts("m.parts_json", n)} AS parts_json FROM messages m
        WHERE m.session_id = ?${2 * n + 1} AND ${if (visibleOnly) visibleHitClause(n) else anyLike("m.parts_json", n)}
        ORDER BY m.sort_order DESC LIMIT $OLDER_LOOKBACK
    """
    }

    /** Messages tried per session when the newest match shows nothing. */
    const val OLDER_LOOKBACK = 20

    /** Sessions per search that may take the look-further-back pass (newest first). */
    const val OLDER_LOOKBACK_SESSIONS = 40

    /**
     * The line of [text] holding the first case-insensitive hit of [query], cut
     * to at most [maxLength] characters that keep the hit visible: [lead]
     * characters before it, the rest after, "…" marking each cut. Null when
     * [text] does not contain [query]. Port of iOS `searchHitLine`.
     *
     * Always [lead] characters before the hit, even near the line's end: a
     * row shows two lines, and a window that back-fills to [maxLength] before
     * a late hit pushed the keyword out of view.
     *
     * Counted in code points (iOS counts Characters), so a window never splits
     * a surrogate pair.
     */
    fun searchHitLine(text: String, query: String, maxLength: Int = 90, lead: Int = 18): String? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val hit = text.indexOf(q, ignoreCase = true)
        if (hit < 0) return null
        var lineStart = hit
        while (lineStart > 0 && !isNewline(text[lineStart - 1])) lineStart--
        var lineEnd = hit + q.length
        while (lineEnd < text.length && !isNewline(text[lineEnd])) lineEnd++
        val line = text.substring(lineStart, lineEnd)

        val cpCount = line.codePointCount(0, line.length)
        val hitStart = line.codePointCount(0, hit - lineStart)
        val hitLength = line.codePointCount(hit - lineStart, hit - lineStart + q.length)
        var from = 0
        var to = cpCount
        if (cpCount > maxLength) {
            from = maxOf(0, hitStart - lead)
            to = minOf(cpCount, maxOf(from + maxLength, hitStart + hitLength))
        }
        var window = line.substring(line.offsetByCodePoints(0, from), line.offsetByCodePoints(0, to))
        // Trim the window's own edge whitespace, but never into the hit.
        val body = window.trim { it == ' ' || it == '\t' }
        if (body.contains(q, ignoreCase = true)) window = body
        return (if (from > 0) "…" else "") + window + (if (to < cpCount) "…" else "")
    }

    private fun isNewline(c: Char) = c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029' || c == '\u0085'

    /**
     * The string values of a tool call's JSON input, depth-first, object keys
     * in sorted order for determinism. Empty when the input is not JSON. Port
     * of iOS `toolInputStrings`.
     */
    fun toolInputStrings(input: String): List<String> {
        // org.json's tokener is lenient: it reads unquoted text ("ls -la") as a
        // string. iOS JSONSerialization rejects that, so only a real object,
        // array or quoted string that is the WHOLE input counts as JSON here.
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed[0] !in "{[\"") return emptyList()
        val root = try {
            val tokener = JSONTokener(trimmed)
            tokener.nextValue().also { if (tokener.more()) return emptyList() }
        } catch (_: Exception) {
            return emptyList()
        }
        val out = mutableListOf<String>()
        fun walk(v: Any?) {
            when (v) {
                is String -> out.add(v)
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
                is JSONObject -> v.keys().asSequence().sorted().forEach { walk(v.opt(it)) }
            }
        }
        walk(root)
        return out
    }

    /**
     * The first keyword, in the order typed, that occurs in [text]
     * (case-insensitive). The keyword a hit line is taken from. Port of iOS
     * `SearchKeywords.firstHit`.
     */
    fun firstHit(text: String, terms: List<String>): String? =
        terms.firstOrNull { it.isNotEmpty() && text.contains(it, ignoreCase = true) }

    /**
     * Every occurrence of every keyword in [text] as char ranges, in text
     * order, with overlapping and touching occurrences joined ("gpt" and
     * "gpt-5" in "GPT-5" are one range). What a result row highlights. Port of
     * iOS `SearchKeywords.hitRanges` / Windows `hit_ranges`.
     */
    fun hitRanges(text: String, terms: List<String>): List<IntRange> {
        val found = ArrayList<IntRange>()
        for (term in terms) {
            if (term.isEmpty()) continue
            var from = 0
            while (from < text.length) {
                val at = text.indexOf(term, from, ignoreCase = true)
                if (at < 0) break
                found.add(at until at + term.length)
                from = at + term.length
            }
        }
        found.sortBy { it.first }
        val merged = ArrayList<IntRange>(found.size)
        for (r in found) {
            val last = merged.lastOrNull()
            // Inclusive ranges: touching means r.first == last.last + 1.
            if (last != null && r.first <= last.last + 1) {
                merged[merged.size - 1] = last.first..maxOf(last.last, r.last)
            } else {
                merged.add(r)
            }
        }
        return merged
    }

    /** One keyword: the whole trimmed [query], as before keywords were split. */
    fun searchSnippet(partsJson: String, query: String): String? =
        query.trim().takeIf { it.isNotEmpty() }?.let { searchSnippet(partsJson, listOf(it)) }

    /**
     * The display line for a matched message: the first hit in its visible
     * text, then in its tool calls' inputs, then in their outputs. Tool hits
     * are prefixed with 🔧 (and the tool name for an input). Each candidate
     * text gives the line of the first keyword, in the order typed, that it
     * holds ([firstHit]). Null when no part shows any of [terms]. Port of iOS
     * `searchSnippet(fromPartsJSON:terms:)`.
     */
    fun searchSnippet(partsJson: String, terms: List<String>): String? {
        fun hitLine(text: String): Pair<String, String>? {
            val term = firstHit(text, terms) ?: return null
            return searchHitLine(text, term)?.let { it to term }
        }
        // [T-android-search-blob-cap] The window cut out of an oversized row
        // (see MAX_HIT_JSON_CHARS) is raw JSON text: its raw line still shows
        // why the session matched, which beats dropping the result.
        if (partsJson.startsWith(WINDOW_MARK)) return hitLine(partsJson.removePrefix(WINDOW_MARK))?.first
        val parts = try {
            val arr = JSONArray(partsJson)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } catch (_: Exception) {
            return null
        }
        for (p in parts) {
            if (p.optString("type") != "text") continue
            val t = stripAttachmentMarkers(stripAttachedFiles(ChatRepository.stripSystemReminders(p.optString("value"))))
            // [T-md-inline-html] The snippet reads like the preview: <br> → space,
            // style tags out, code untouched. A term only the raw text holds (a
            // search for "<b>") still finds its line there.
            val shown = com.openminis.app.ui.markdown.InlineHtmlTags.stripForPreview(t)
            (hitLine(shown) ?: hitLine(t))?.let { (line, term) -> return readable(line, term) }
        }
        for (p in parts) {
            if (p.optString("type") != "toolUse") continue
            val v = p.optJSONObject("value") ?: continue
            val input = v.optString("input")
            // The input's string values (what the tool card shows), so the line
            // is real text rather than escaped JSON; the raw JSON only as a last
            // resort (a hit in a key name).
            for (text in toolInputStrings(input) + input) {
                hitLine(text)?.let { return "🔧 ${v.optString("name")}: ${it.first}" }
            }
        }
        for (p in parts) {
            if (p.optString("type") != "toolResult") continue
            val output = p.optJSONObject("value")?.optString("output").orEmpty()
            hitLine(output)?.let { return "🔧 ${it.first}" }
        }
        return null
    }

    /** Markdown syntax stripped from the line, when that keeps the hit intact. */
    internal fun readable(line: String, query: String): String {
        val plain = stripInlineMarkdown(line).trim { it == ' ' || it == '\t' }
        return if (plain.contains(query.trim(), ignoreCase = true)) plain else line
    }

    private val MD_LINK = Regex("""!?\[([^\]]*)]\([^)]*\)""")
    private val MD_EMPHASIS = Regex("""(\*\*|__|~~|`)""")
    private val MD_LINE_PREFIX = Regex("""^\s*(#{1,6}\s+|>\s?|[-*+]\s+|\d+[.)]\s+)""")

    /**
     * Inline Markdown only (a single line): links and images to their text,
     * emphasis / code / strike markers, and a leading heading, quote or list
     * marker. iOS uses its full MarkdownStripper; a hit line needs no more.
     */
    internal fun stripInlineMarkdown(line: String): String {
        var s = MD_LINE_PREFIX.replace(line, "")
        s = MD_LINK.replace(s) { it.groupValues[1] }
        s = MD_EMPHASIS.replace(s, "")
        return s
    }

    private fun stripAttachedFiles(text: String): String {
        val start = text.indexOf("<user-attached-files>")
        if (start < 0) return text
        val endTag = "</user-attached-files>"
        val end = text.indexOf(endTag, start)
        return if (end >= 0) text.substring(0, start) + text.substring(end + endTag.length) else text.substring(0, start)
    }

    private val ATTACHMENT_MARKERS = listOf(
        Regex("""\[attached [A-Za-z]+:[^\]]*]"""),
        Regex("""\[image omitted to save context[^\]]*]"""),
    )

    /** iOS `stripAttachmentMarkers`: drop the model-facing attachment captions. */
    private fun stripAttachmentMarkers(text: String): String {
        if (!text.contains('[')) return text
        var out = text
        for (re in ATTACHMENT_MARKERS) out = re.replace(out, "")
        return out
    }
}

// ─── Merged from SkillDiskSignature.kt ──────────────────────────────────────────────────

/**
 * [T-android-skill-scan-parity] Pure disk-side helpers for [SkillRepository].
 *
 * Kept free of SQLite so the scan cost and the prune rule can be tested on a
 * plain JVM with real temp directories.
 */
internal object SkillDiskSignature {

    /** Minimum custom skills before the breaker applies — tiny libraries prune freely. */
    private const val BREAKER_MIN_COUNT = 3

    /** Share of custom skills missing at once that reads as "storage not mounted", not "user deleted". */
    private const val BREAKER_RATIO = 0.40

    /**
     * Ids of skill directories under [skillsDir] that contain a SKILL.md.
     * One listFiles() plus one stat per directory — O(n).
     */
    fun skillIdsOnDisk(skillsDir: File): Set<String> {
        val dirs = skillsDir.listFiles() ?: return emptySet()
        val ids = HashSet<String>(dirs.size)
        for (dir in dirs) {
            if (dir.isDirectory && File(dir, "SKILL.md").isFile) ids.add(dir.name)
        }
        return ids
    }

    /**
     * Cheap fingerprint of everything a reload would pick up: which skill
     * directories exist, and each SKILL.md's mtime and size. Stands in for the
     * iOS fakefs change notifier, which Android's proot has no equivalent of.
     *
     * Covers the shell cases that matter: `git clone` / `mkdir` + write (new
     * directory), `rm -rf` (directory gone), and `cat >` / `sed -i` / editor
     * saves on an existing SKILL.md (mtime or size change). Files other than
     * SKILL.md are ignored — nothing loaded into memory reads them.
     *
     * Returns 0 for a missing directory, which differs from any populated one.
     */
    fun compute(skillsDir: File): Long {
        val dirs = skillsDir.listFiles() ?: return 0L
        dirs.sortBy { it.name }
        var h = 1125899906842597L
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val md = File(dir, "SKILL.md")
            h = 31 * h + dir.name.hashCode()
            h = 31 * h + md.lastModified()
            h = 31 * h + md.length()
        }
        return h
    }

    /**
     * [T-android-skill-circuit-breaker] True when so many custom skills are
     * missing at once that the storage is more likely unmounted or mid-restore
     * than deliberately emptied, so no orphan row may be pruned this load.
     */
    fun breakerTripped(totalCustom: Int, missingCustom: Int): Boolean =
        totalCustom >= BREAKER_MIN_COUNT &&
            missingCustom >= BREAKER_MIN_COUNT &&
            missingCustom.toDouble() / totalCustom >= BREAKER_RATIO
}

// ─── Merged from WebAppShortcutRepository.kt ──────────────────────────────────────────────────

/**
 * T-pwa-1 (renamed Pwa → WebApp): thin wrapper around
 * [WebAppShortcutDao]. Constructed once in [com.openminis.app.MinisApp]
 * and shared across UI surfaces (chat attachment chip in T-pwa-2, file
 * browser row in T-pwa-3).
 */
class WebAppShortcutRepository(private val dao: WebAppShortcutDao) {

    suspend fun create(
        htmlPath: String,
        pathScope: String,
        scopeContext: String?,
        title: String,
        iconRef: String,
        iconCachePath: String?,
        sourceSessionId: String?,
    ): WebAppShortcutEntity {
        val entity = WebAppShortcutEntity(
            id = UUID.randomUUID().toString(),
            htmlPath = htmlPath,
            pathScope = pathScope,
            scopeContext = scopeContext,
            title = title,
            iconRef = iconRef,
            iconCachePath = iconCachePath,
            createdAt = System.currentTimeMillis(),
            sourceSessionId = sourceSessionId,
        )
        dao.insert(entity)
        return entity
    }

    suspend fun get(id: String): WebAppShortcutEntity? = dao.getById(id)

    suspend fun list(): List<WebAppShortcutEntity> = dao.getAll()

    suspend fun delete(id: String) = dao.deleteById(id)

    suspend fun update(entity: WebAppShortcutEntity) = dao.update(entity)

    companion object {
        const val SCOPE_SESSION_ATTACHMENT = "session_attachment"
        const val SCOPE_SHARED = "shared"
        const val SCOPE_MOUNT = "mount"
    }
}

// ─── Merged from SkillRepository.kt ──────────────────────────────────────────────────

/**
 * Manages skill metadata (SQLite) and SKILL.md files on disk.
 * Mirrors iOS SkillStore architecture:
 *   - Metadata in `skills.db` (name, description, version, source, enabled)
 *   - SKILL.md in `minis-global/skills/<id>/SKILL.md`
 *   - Session overrides in `session_skill_overrides` table
 *   - Prompt fragment generation for system prompt injection
 */
class SkillRepository(private val context: Context) {

    companion object {
        private const val TAG = "SkillRepository"
        private const val DB_NAME = "skills.db"
        private const val DB_VERSION = 3
        private const val MAX_SKILLS_IN_PROMPT = 20
        private const val MAX_SKILL_DESC_LENGTH = 200
        private const val RECENT_WINDOW_MS = 7L * 24 * 3600 * 1000
        private const val RECENT_SLOTS = 10
        private const val NORMALIZE_THRESHOLD = 1000.0

        /** [T-android-skill-export] How long an exported zip stays on disk
         *  before the next export sweeps it. 24h matches iOS — long enough that
         *  any Save-to-Files / AirDrop / upload consumer has finished. */
        private const val EXPORT_TTL_MS = 24L * 3600 * 1000

        /** Pure SKILL.md frontmatter parser; lives on the companion so JVM unit
         *  tests can drive it without a Context. */
        internal fun parseSkillMd(content: String): ParsedSkill? {
            // CRLF / CR endings are already handled by `lines()` and `trim()`,
            // but a leading UTF-8 BOM (Windows Notepad) is not whitespace to
            // `trimStart()` and hid the opening fence, so the file was rejected.
            val trimmed = content.removePrefix("﻿").trimStart()
            if (!trimmed.startsWith("---")) return null

            // Find the closing `---` on a line by itself (matches iOS — and avoids
            // false matches against horizontal rules later in the document).
            val lines = trimmed.lines()
            var frontmatterEndLine = -1
            for (i in 1 until lines.size) {
                if (lines[i].trim() == "---") {
                    frontmatterEndLine = i
                    break
                }
            }
            if (frontmatterEndLine < 0) return null

            var name = ""
            var description = ""
            var version = "1.0.0"

            var i = 1
            while (i < frontmatterEndLine) {
                val line = lines[i]
                val colonIdx = line.indexOf(':')
                if (colonIdx < 0) { i++; continue }
                val key = line.substring(0, colonIdx).trim().lowercase()
                val rawValue = line.substring(colonIdx + 1).trim()

                // YAML block scalar: `|` keeps newlines, `>` folds them into spaces.
                // Body lines continue while they are indented (or empty); a
                // dedented line ends the block.
                //
                // T151: also accept the YAML chomping indicators `>-`, `>+`, `|-`,
                // `|+` (and any explicit indentation digit suffix). Real-world
                // skill frontmatter — qbt-hub, for one — opens its description
                // with `description: >-` so trailing newlines are stripped, but
                // the previous strict `==` match treated that as a plain string
                // literal, leaving the renderer to display the raw `>-` marker.
                val isBlockScalar = rawValue.startsWith("|") || rawValue.startsWith(">")
                val resolved: String
                if (isBlockScalar && i + 1 < frontmatterEndLine) {
                    val fold = rawValue.startsWith(">")
                    val blockLines = mutableListOf<String>()
                    var j = i + 1
                    while (j < frontmatterEndLine) {
                        val next = lines[j]
                        if (next.isEmpty() || next[0].isWhitespace()) {
                            blockLines.add(next.trim())
                        } else break
                        j++
                    }
                    resolved = if (fold) {
                        blockLines.joinToString(" ").trim()
                    } else {
                        blockLines.joinToString("\n").trim('\n')
                    }
                    i = j
                } else {
                    resolved = rawValue
                    i++
                }

                when (key) {
                    "name" -> name = resolved
                    "description" -> description = resolved
                    "version" -> version = resolved
                }
            }

            if (name.isBlank()) return null

            val bodyStartLine = frontmatterEndLine + 1
            val body = if (bodyStartLine < lines.size) {
                lines.subList(bodyStartLine, lines.size).joinToString("\n").trim('\n')
            } else ""

            return ParsedSkill(name, description, version, body)
        }
    }

    private val baseHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // [FEAT-261021-16] Follows the default route in Settings > Network; the
    // same client object when nothing is configured.
    private val httpClient: OkHttpClient get() = com.openminis.app.network.NetworkHttp.client(com.openminis.app.network.NetworkHttp.DEFAULT_TAG, baseHttpClient)

    /**
     * Repository-owned scope for fire-and-forget background work that must
     * outlive the calling Composable / ViewModel — notably the sibling-file
     * download that runs after a SKILL.md import. SupervisorJob so a single
     * download failure doesn't poison the next one.
     */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // -- Data Types --

    enum class ImportSource(val value: String) {
        URL("url"),
        FILE("file"),
        BUNDLED("bundled"),
        SESSION("session");

        companion object {
            fun from(value: String): ImportSource =
                entries.find { it.value == value } ?: FILE
        }
    }

    data class Skill(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val description: String = "",
        val version: String = "1.0.0",
        val importSource: ImportSource = ImportSource.FILE,
        val isEnabled: Boolean = true,
        val installedAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
        val body: String = "",
        /** Original GitHub URL when importSource is URL (null otherwise). */
        val sourceURL: String? = null,
        /** Cumulative read count of this skill's SKILL.md; normalized to 0–100 after exceeding 1000. */
        val useCount: Double = 0.0,
    )

    enum class UsageFrequency { NEVER, LOW, REGULAR, HIGH }

    // -- State --

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    private val db: SQLiteDatabase by lazy {
        SkillDbHelper(context).writableDatabase
    }

    private val skillsDir: File
        get() = File(context.filesDir, "minis-global/skills")

    // [T-android-skill-scan-parity] Declared before init{}: Kotlin initializes
    // properties in source order and init{} already calls loadAll().
    /** Every in-memory edit goes through [mutateSkills] so a background [loadAll] can tell whether the list changed under it. */
    private val skillsLock = Any()
    private var mutationSeq = 0L // guarded by skillsLock
    private val reloadRequests = Channel<String>(Channel.CONFLATED)
    private val forceReloadPending = AtomicBoolean(false)
    @Volatile private var lastDiskSignature: Long? = null

    init {
        // [T-android-safemode-lateinit-crash-147] Never let a bad skill take
        // the whole app down. This constructor runs inline in
        // MinisApp.onCreate, BEFORE subsystemsInitialized is set, so anything
        // escaping here aborts onCreate with the repositories half-assigned.
        // The Application object is then permanently broken (onCreate never
        // re-runs), every subsequent launch crashes on the first Compose frame
        // with "lateinit property chatRepository has not been initialized",
        // and each crash writes a log that re-trips the crash-burst detector —
        // the self-sustaining loop reported in GH#147, where the user could
        // only recover by reinstalling.
        //
        // loadAll() reads rows written by third-party skill imports and uses
        // getColumnIndexOrThrow plus SKILL.md parsing, so a malformed skill
        // from an external hub is exactly the kind of input that can throw.
        // Degrading to "some skills missing from the list" is always better
        // than an app that cannot start.
        runCatching { loadAll() }.onFailure {
            Log.e(TAG, "loadAll failed — continuing with ${_skills.value.size} skill(s): ${it.message}", it)
        }
        runCatching { installBundledSkills() }.onFailure {
            Log.e(TAG, "installBundledSkills failed — continuing: ${it.message}", it)
        }
        startReloadWorker()
    }

    // -- CRUD --

    fun add(name: String, description: String, body: String, version: String = "1.0.0", source: ImportSource = ImportSource.FILE, sourceURL: String? = null): Skill? {
        val id = slugify(name)
        if (id.isBlank()) return null
        if (_skills.value.any { it.id == id }) return null

        val skill = Skill(
            id = id,
            name = name,
            description = description,
            version = version,
            importSource = source,
            body = body,
            sourceURL = sourceURL,
        )

        insertDb(skill)
        writeSkillMd(skill)
        mutateSkills { it + skill }
        Log.i(TAG, "Added skill: ${skill.id}")
        return skill
    }

    fun update(id: String, name: String? = null, description: String? = null, body: String? = null): Boolean {
        val current = _skills.value.find { it.id == id } ?: return false
        val updated = current.copy(
            name = name ?: current.name,
            description = description ?: current.description,
            body = body ?: current.body,
            updatedAt = System.currentTimeMillis(),
        )

        db.execSQL(
            "UPDATE skills SET name=?, description=?, version=?, updated_at=? WHERE id=?",
            arrayOf<Any>(updated.name, updated.description, updated.version, updated.updatedAt, id)
        )
        writeSkillMd(updated)
        mutateSkills { list -> list.map { if (it.id == id) updated else it } }
        return true
    }

    fun delete(id: String) {
        db.execSQL("DELETE FROM skills WHERE id=?", arrayOf(id))
        db.execSQL("DELETE FROM session_skill_overrides WHERE skill_id=?", arrayOf(id))
        val dir = File(skillsDir, id)
        dir.deleteRecursively()
        mutateSkills { list -> list.filter { it.id != id } }
        Log.i(TAG, "Deleted skill: $id")
    }

    fun setEnabled(id: String, enabled: Boolean) {
        db.execSQL("UPDATE skills SET is_enabled=? WHERE id=?", arrayOf<Any>(if (enabled) 1 else 0, id))
        mutateSkills { list ->
            list.map { if (it.id == id) it.copy(isEnabled = enabled) else it }
        }
    }

    // -- Session Overrides --

    fun isEnabledForSession(skillId: String, sessionId: String): Boolean {
        val cursor = db.rawQuery(
            "SELECT is_enabled FROM session_skill_overrides WHERE session_id=? AND skill_id=?",
            arrayOf(sessionId, skillId)
        )
        val override = if (cursor.moveToFirst()) cursor.getInt(0) == 1 else null
        cursor.close()
        if (override != null) return override
        return _skills.value.find { it.id == skillId }?.isEnabled ?: false
    }

    fun setSessionOverride(sessionId: String, skillId: String, enabled: Boolean) {
        db.execSQL(
            "INSERT OR REPLACE INTO session_skill_overrides (session_id, skill_id, is_enabled) VALUES (?, ?, ?)",
            arrayOf<Any>(sessionId, skillId, if (enabled) 1 else 0)
        )
    }

    fun clearSessionOverrides(sessionId: String) {
        db.execSQL("DELETE FROM session_skill_overrides WHERE session_id=?", arrayOf(sessionId))
    }

    /**
     * [T-android-session-skill-override-init-timing] Re-point every
     * `session_skill_overrides` row that was written against the draft
     * session id ([fromDraft], e.g. `__new__<uuid>`) onto the persisted
     * session id ([toReal]) once `ensureSession()` creates the real DB row.
     * Without this hop, a pre-first-message skill toggle stays bound to the
     * draft key and becomes invisible the next time the chat is opened
     * under its real id — exactly the symptom a user reported. Paired with
     * [MCPRepository.renameSessionOverrides].
     */
    fun renameSessionOverrides(fromDraft: String, toReal: String) {
        if (fromDraft == toReal) return
        db.execSQL(
            "UPDATE OR REPLACE session_skill_overrides SET session_id=? WHERE session_id=?",
            arrayOf<Any>(toReal, fromDraft),
        )
    }

    // -- Prompt Fragment --

    /**
     * Build the system-prompt fragment that makes skills discoverable.
     * Discloses up to [MAX_SKILLS_IN_PROMPT] skills with 3-tier priority
     * (bundled > 7-day recent > most-used), matching iOS SkillStore.
     * Returns null when the session has no enabled skills.
     */
    fun skillPromptFragment(sessionId: String): String? {
        val enabled = _skills.value.filter { isEnabledForSession(it.id, sessionId) }
        if (enabled.isEmpty()) return null

        val total = enabled.size
        val selected: List<Skill>
        val hasMore: Boolean

        if (total <= MAX_SKILLS_IN_PROMPT) {
            selected = enabled.sortedByDescending { it.updatedAt }
            hasMore = false
        } else {
            val picked = linkedMapOf<String, Skill>() // preserves insertion order + id dedupe
            // Priority 1: bundled
            enabled.filter { it.importSource == ImportSource.BUNDLED }
                .forEach { picked.putIfAbsent(it.id, it) }
            // Priority 2: recently updated (within 7 days), up to RECENT_SLOTS more
            val cutoff = System.currentTimeMillis() - RECENT_WINDOW_MS
            val recentLimit = minOf(RECENT_SLOTS, MAX_SKILLS_IN_PROMPT - picked.size).coerceAtLeast(0)
            enabled.asSequence()
                .filter { it.updatedAt > cutoff && it.id !in picked }
                .sortedByDescending { it.updatedAt }
                .take(recentLimit)
                .forEach { picked.putIfAbsent(it.id, it) }
            // Priority 3: fill remaining slots by useCount (desc)
            if (picked.size < MAX_SKILLS_IN_PROMPT) {
                val remaining = MAX_SKILLS_IN_PROMPT - picked.size
                enabled.asSequence()
                    .filter { it.id !in picked }
                    .sortedByDescending { it.useCount }
                    .take(remaining)
                    .forEach { picked.putIfAbsent(it.id, it) }
            }
            selected = picked.values.toList()
            hasMore = total > selected.size
        }

        val xml = buildString {
            append("<available_skills>\n")
            for (skill in selected) {
                var desc = skill.description
                if (desc.length > MAX_SKILL_DESC_LENGTH) {
                    desc = desc.substring(0, MAX_SKILL_DESC_LENGTH) + "…"
                }
                append("  <skill>\n")
                append("    <name>").append(escapeXml(skill.name)).append("</name>\n")
                append("    <description>").append(escapeXml(desc)).append("</description>\n")
                append("    <path>/var/minis/skills/").append(skill.id).append("/SKILL.md</path>\n")
                append("  </skill>\n")
            }
            append("</available_skills>")
        }

        return buildString {
            append("Skills:\n")
            append("Reusable instruction sets stored at /var/minis/skills/<name>/SKILL.md. Read the SKILL.md file to load full instructions before using a skill.\n\n")
            append(xml)
            if (hasMore) {
                val selectedIds = selected.mapTo(HashSet(selected.size)) { it.id }
                val omitted = enabled.filter { it.id !in selectedIds }
                val maxUndisclosed = (100 - selected.size).coerceAtLeast(0)
                val names = omitted.take(maxUndisclosed).joinToString(", ") { it.name }
                append("\n\n")
                append(omitted.size).append(" more skills not shown above: ").append(names)
                // [T-prompt-search-neutral] Names no search tool (iOS parity).
                append(". List or search /var/minis/skills/ to find the rest.")
            }
        }
    }

    /**
     * Record that a skill's SKILL.md was read. Matches iOS `SkillStore.recordSkillUse`:
     * bumps `useCount` by 1 and normalizes all counts to 0–100 when any exceeds 1000,
     * so long-lived installs don't drift into multi-thousand-read territory.
     */
    fun recordSkillUse(skillId: String) {
        val current = _skills.value.find { it.id == skillId } ?: return
        val bumped = current.copy(useCount = current.useCount + 1.0)
        db.execSQL(
            "UPDATE skills SET use_count = use_count + 1 WHERE id=?",
            arrayOf<Any>(skillId)
        )
        mutateSkills { list ->
            val next = list.map { if (it.id == skillId) bumped else it }
            if (bumped.useCount > NORMALIZE_THRESHOLD) normalizeUseCounts(next) else next
        }
    }

    private fun normalizeUseCounts(list: List<Skill>): List<Skill> {
        val maxCount = list.maxOfOrNull { it.useCount } ?: 0.0
        if (maxCount <= 0.0) return list
        val scaled = list.map { it.copy(useCount = it.useCount / maxCount * 100.0) }
        db.beginTransaction()
        try {
            for (s in scaled) {
                db.execSQL(
                    "UPDATE skills SET use_count=? WHERE id=?",
                    arrayOf<Any>(s.useCount, s.id)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return scaled
    }

    /** Coarse-grained usage band for UI surfacing (matches iOS UsageFrequency). */
    fun usageFrequency(skillId: String): UsageFrequency {
        val skill = _skills.value.find { it.id == skillId } ?: return UsageFrequency.NEVER
        if (skill.useCount == 0.0) return UsageFrequency.NEVER
        val maxCount = _skills.value.maxOfOrNull { it.useCount } ?: 0.0
        if (maxCount <= 0.0) return UsageFrequency.NEVER
        val normalized = skill.useCount / maxCount * 100.0
        return when {
            normalized < 20.0 -> UsageFrequency.LOW
            normalized < 60.0 -> UsageFrequency.REGULAR
            else -> UsageFrequency.HIGH
        }
    }

    /**
     * Extract skill id from `/var/minis/skills/<id>/SKILL.md`. Returns null unless
     * the path is exactly a SKILL.md read of a known skill — sub-resource reads
     * under `scripts/` etc. don't count toward usage.
     */
    fun skillIdFromPath(path: String): String? {
        val prefix = "/var/minis/skills/"
        if (!path.startsWith(prefix)) return null
        if (!path.endsWith("/SKILL.md")) return null
        val rest = path.substring(prefix.length)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val candidate = rest.substring(0, slash)
        return if (_skills.value.any { it.id == candidate }) candidate else null
    }

    // -- Import from SKILL.md Content --

    /**
     * Parse SKILL.md content (YAML frontmatter + markdown body) and import.
     * Returns the created Skill or null on failure.
     */
    fun importFromContent(content: String, source: ImportSource = ImportSource.FILE, sourceURL: String? = null): Skill? {
        val parsed = parseSkillMd(content) ?: return null
        val id = slugify(parsed.name)
        if (id.isNotBlank() && _skills.value.any { it.id == id }) {
            // Replace existing: update in place so URL updates refresh contents.
            val current = _skills.value.first { it.id == id }
            val replaced = current.copy(
                name = parsed.name,
                description = parsed.description,
                version = parsed.version,
                importSource = source,
                body = parsed.body,
                updatedAt = System.currentTimeMillis(),
                sourceURL = sourceURL ?: current.sourceURL,
            )
            db.execSQL(
                "UPDATE skills SET name=?, description=?, version=?, import_source=?, source_url=?, updated_at=? WHERE id=?",
                arrayOf<Any?>(
                    replaced.name, replaced.description, replaced.version,
                    replaced.importSource.value, replaced.sourceURL,
                    replaced.updatedAt, id
                )
            )
            writeSkillMd(replaced)
            mutateSkills { list -> list.map { if (it.id == id) replaced else it } }
            return replaced
        }
        return add(
            name = parsed.name,
            description = parsed.description,
            body = parsed.body,
            version = parsed.version,
            source = source,
            sourceURL = sourceURL,
        )
    }

    /**
     * Returns the SKILL.md file path for a given skill (for display in UI).
     */
    fun skillMdPath(id: String): String = "/var/minis/skills/$id/SKILL.md"

    // -- Import from Zip Archive --

    /**
     * Import a skill from a .zip archive (mirrors iOS `SkillStore.importFromArchive`).
     * The archive must contain a `SKILL.md` at the root or one directory deep;
     * bundled sibling files (scripts/, references/, assets/, etc.) are extracted
     * alongside it into `skillsDir/<id>/`.
     */
    fun importFromArchive(input: InputStream): Skill? {
        val entries = try { readZipEntries(input) } catch (e: Exception) {
            Log.w(TAG, "Failed to read zip archive: ${e.message}")
            return null
        }
        if (entries.isEmpty()) return null

        // Accept SKILL.md at root or one level deep (matches iOS behavior).
        val skillMdEntry = entries.firstOrNull { entry ->
            val name = entry.name
            name == "SKILL.md" || (name.endsWith("/SKILL.md") && name.count { it == '/' } == 1)
        } ?: return null

        val skillContent = try { String(skillMdEntry.data, Charsets.UTF_8) } catch (_: Exception) { return null }
        if (skillContent.isBlank()) return null

        val skill = importFromContent(skillContent, ImportSource.FILE) ?: return null

        val prefix = if (skillMdEntry.name == "SKILL.md") "" else skillMdEntry.name.dropLast("SKILL.md".length)
        val skillDir = File(skillsDir, skill.id)
        skillDir.mkdirs()

        for (entry in entries) {
            if (entry.isDirectory) continue
            var relativePath = entry.name
            if (prefix.isNotEmpty() && relativePath.startsWith(prefix)) {
                relativePath = relativePath.drop(prefix.length)
            }
            if (relativePath == "SKILL.md" || relativePath.startsWith(".") || relativePath.isEmpty()) continue
            // Guard against zip-slip: reject path traversal.
            if (relativePath.contains("..")) continue

            val destFile = File(skillDir, relativePath)
            destFile.parentFile?.mkdirs()
            try { destFile.writeBytes(entry.data) } catch (e: Exception) {
                Log.w(TAG, "Failed to write $relativePath: ${e.message}")
            }
        }
        return skill
    }

    private data class ZipEntryData(val name: String, val isDirectory: Boolean, val data: ByteArray)

    private fun readZipEntries(input: InputStream): List<ZipEntryData> {
        val out = mutableListOf<ZipEntryData>()
        ZipInputStream(input).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name
                val isDir = entry.isDirectory
                val data = if (isDir) ByteArray(0) else {
                    val buf = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = zis.read(chunk)
                        if (n <= 0) break
                        buf.write(chunk, 0, n)
                    }
                    buf.toByteArray()
                }
                out.add(ZipEntryData(name, isDir, data))
                zis.closeEntry()
            }
        }
        return out
    }

    // -- Import from GitHub URL --

    data class GitHubInfo(
        val user: String,
        val repo: String,
        val branch: String,
        /** Directory path (without SKILL.md leaf) */
        val dirPath: String,
    )

    /**
     * Download SKILL.md from a GitHub URL and recursively download sibling files
     * from the same directory. Mirrors iOS SkillStore.importFromGitHub.
     */
    suspend fun importFromGitHub(urlString: String): Skill? = withContext(Dispatchers.IO) {
        val rawURL = githubToRawURL(urlString) ?: return@withContext null
        val content = httpGetString(rawURL) ?: return@withContext null
        val skill = importFromContent(content, ImportSource.URL, sourceURL = urlString) ?: return@withContext null

        val ghInfo = parseGitHubURL(urlString) ?: return@withContext skill
        val skillDir = File(skillsDir, skill.id)
        // Detach the sibling-file download from the caller's coroutine scope.
        // The previous design ran the recursion inline, so any navigation
        // away from the import screen — or a ViewModel finishing its initial
        // composition — would cancel the recursion mid-flight and leave the
        // skill with only SKILL.md (T152 root cause).
        backgroundScope.launch {
            val outcome = downloadSiblingFiles(ghInfo, skillDir, skill)
            val bumped = skill.copy(updatedAt = System.currentTimeMillis())
            db.execSQL(
                "UPDATE skills SET updated_at=? WHERE id=?",
                arrayOf<Any>(bumped.updatedAt, skill.id)
            )
            mutateSkills { list -> list.map { if (it.id == skill.id) bumped else it } }
            if (!outcome.isComplete) {
                Log.w(TAG, "importFromGitHub partial for ${skill.id}: ${outcome.reason ?: "${outcome.filesFailed} file(s) failed"}")
            }
        }
        skill
    }

    /** Outcome of [updateFromURL]: either an updated skill or a human-readable reason. */
    sealed class UpdateResult {
        data class Success(val skill: Skill) : UpdateResult()
        /**
         * SKILL.md fetched + persisted, but the sibling-file recursion
         * (scripts/, references/, …) ran into trouble. The skill is usable
         * but its bundled resources may be incomplete. T152 fix: this state
         * used to be silently squashed into a plain Success.
         */
        data class PartialSuccess(val skill: Skill, val reason: String) : UpdateResult()
        data class Failure(val reason: String) : UpdateResult()
    }

    /**
     * Aggregate outcome of a sibling-file download walk — surfaced through
     * [UpdateResult.PartialSuccess] when SKILL.md imported but sibling files
     * (scripts/, references/, …) could not all be retrieved. `reason` is
     * non-null only when [filesFailed] > 0 or the recursion bailed before
     * listing the directory at all.
     */
    data class SiblingDownloadOutcome(
        val filesWritten: Int,
        val filesFailed: Int,
        val reason: String?,
    ) {
        val isComplete: Boolean get() = filesFailed == 0 && reason == null
    }

    /**
     * Re-fetch the SKILL.md for an existing URL-sourced skill and update its
     * record in place — does NOT create a new skill. Also re-downloads sibling
     * files from the same GitHub directory so bundled scripts/references stay
     * in sync with upstream. Returns a typed result with an explicit reason on
     * failure so the UI can surface *why* the update failed.
     */
    suspend fun updateFromURL(skillId: String): UpdateResult = withContext(Dispatchers.IO) {
        val existing = _skills.value.find { it.id == skillId }
            ?: return@withContext UpdateResult.Failure("Skill not found")
        if (existing.importSource != ImportSource.URL) {
            return@withContext UpdateResult.Failure("Skill was not imported from a URL")
        }
        val urlString = existing.sourceURL
        if (urlString.isNullOrBlank()) {
            // Older imports (pre-fix) never persisted the source URL. Users need
            // to re-import the skill from Minis Skills so the URL gets saved.
            return@withContext UpdateResult.Failure(
                "No source URL on file. Re-import this skill from Minis Skills to enable updates."
            )
        }

        val rawURL = githubToRawURL(urlString)
            ?: return@withContext UpdateResult.Failure("Could not build a raw download URL from $urlString")
        val content = try { httpGetString(rawURL) } catch (e: Exception) {
            return@withContext UpdateResult.Failure("Network error: ${e.message ?: "unknown"}")
        }
        if (content == null) {
            return@withContext UpdateResult.Failure("Download failed (HTTP error) for $rawURL")
        }
        val parsed = parseSkillMd(content)
            ?: return@withContext UpdateResult.Failure("Downloaded SKILL.md is not valid (missing YAML frontmatter or 'name:' field)")

        val ok = update(
            id = skillId,
            name = parsed.name,
            description = parsed.description,
            body = parsed.body,
        )
        if (!ok) return@withContext UpdateResult.Failure("Failed to write updated skill")

        // Re-download sibling files (scripts/, references/, etc.). Unlike
        // importFromGitHub, the user invoked this synchronously and is
        // looking at a spinner — so we await the outcome here and surface
        // any failure as PartialSuccess so the UI can show *what* went
        // wrong (e.g. "GitHub API 403 (likely rate limited)") instead of
        // claiming success while scripts/ stays empty.
        val ghInfo = parseGitHubURL(urlString)
        var siblingOutcome: SiblingDownloadOutcome? = null
        if (ghInfo != null) {
            val skillDir = File(skillsDir, skillId)
            val current = _skills.value.first { it.id == skillId }
            siblingOutcome = downloadSiblingFiles(ghInfo, skillDir, current)
        }

        val fresh = _skills.value.find { it.id == skillId }
            ?: return@withContext UpdateResult.Failure("Skill disappeared during update")
        if (siblingOutcome != null && !siblingOutcome.isComplete) {
            val reason = siblingOutcome.reason
                ?: "${siblingOutcome.filesFailed} sibling file(s) failed to download"
            return@withContext UpdateResult.PartialSuccess(fresh, reason)
        }
        UpdateResult.Success(fresh)
    }

    // -- Export to Zip Archive --

    /**
     * [T-android-skill-export] Zip the whole `skillsDir/<id>/` directory for
     * sharing. Android port of iOS `SkillDetailView.shareSkill`; the round-trip
     * partner of [importFromArchive], which already accepts exactly this shape
     * (SKILL.md at the archive root plus any sibling files).
     *
     * Three safeguards carried over from iOS, each of which fixed a real bug
     * there — do not simplify them away:
     *
     *  1. **Unique per-export directory** (`share/skill-export-<uuid>/`).
     *     Consecutive shares of the same skill must not overwrite or delete a
     *     zip that an earlier share's consumer (Save to Files / Drive / a chat
     *     app) may still be reading.
     *  2. **Non-empty verification** before returning. iOS shipped an "exported
     *     an empty file" bug precisely because a failed copy was swallowed;
     *     here any failure throws or returns null rather than vending a zip
     *     nobody can import.
     *  3. **Stale sweep** of exports older than [EXPORT_TTL_MS], run before each
     *     new export instead of deleting on share-sheet dismissal — by then any
     *     pending consumer of an older export has long finished.
     *
     * Written under `cacheDir/share/` because that is a root already declared
     * in `file_provider_paths.xml`; a file outside a declared root makes
     * `FileProvider.getUriForFile` throw and the user just sees a share failure.
     *
     * @return the zip file, or null when the skill has no directory / no files.
     */
    fun exportSkillToZip(skillId: String): File? {
        val skill = _skills.value.find { it.id == skillId }
        val dir = File(skillsDir, skillId)
        if (!dir.isDirectory) {
            Log.w(TAG, "exportSkillToZip: no directory for $skillId")
            return null
        }
        val relPaths = listSkillFiles(skillId)
        if (relPaths.isEmpty()) {
            Log.w(TAG, "exportSkillToZip: $skillId has no files to export")
            return null
        }

        sweepStaleExports()

        val exportDir = File(File(context.cacheDir, "share"), "skill-export-${UUID.randomUUID()}")
        if (!exportDir.mkdirs() && !exportDir.isDirectory) {
            Log.w(TAG, "exportSkillToZip: could not create $exportDir")
            return null
        }
        // Filesystem-safe, single-component zip name (mirrors iOS's sanitizing).
        val safeName = (skill?.name ?: skillId)
            .replace('/', '-')
            .replace(':', '-')
            .replace('\\', '-')
            .trim()
            .ifEmpty { "skill" }
        val zipFile = File(exportDir, "$safeName.zip")

        return try {
            ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zos ->
                for (rel in relPaths) {
                    val src = File(dir, rel)
                    if (!src.isFile) continue
                    // Store entries with forward slashes — the portable
                    // separator every unzip tool (and importFromArchive) expects.
                    zos.putNextEntry(ZipEntry(rel.replace(File.separatorChar, '/')))
                    FileInputStream(src).buffered().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            // Safeguard 2: never vend an empty/missing archive.
            if (!zipFile.isFile || zipFile.length() <= 0L) {
                Log.w(TAG, "exportSkillToZip: produced an empty zip for $skillId")
                zipFile.delete()
                return null
            }
            Log.i(TAG, "exportSkillToZip: $skillId -> ${zipFile.name} (${zipFile.length()} bytes, ${relPaths.size} files)")
            zipFile
        } catch (e: Exception) {
            Log.w(TAG, "exportSkillToZip failed for $skillId: ${e.message}")
            runCatching { zipFile.delete() }
            null
        }
    }

    /**
     * Safeguard 3: delete `skill-export-*` directories older than
     * [EXPORT_TTL_MS]. Called before each new export rather than when the share
     * sheet closes, so a consumer still reading a previous export is never
     * pulled out from under.
     */
    private fun sweepStaleExports() {
        val root = File(context.cacheDir, "share")
        val now = System.currentTimeMillis()
        val entries = root.listFiles() ?: return
        for (entry in entries) {
            if (!entry.isDirectory || !entry.name.startsWith("skill-export-")) continue
            if (now - entry.lastModified() < EXPORT_TTL_MS) continue
            runCatching { entry.deleteRecursively() }
                .onFailure { Log.w(TAG, "sweepStaleExports: could not delete ${entry.name}") }
        }
    }

    /**
     * List every file inside `skillsDir/<id>/` (recursive). Returns relative
     * paths from the skill root, with SKILL.md first and the rest sorted by
     * name. Used by the Skill detail UI to enumerate bundled scripts.
     */
    fun listSkillFiles(skillId: String): List<String> {
        val dir = File(skillsDir, skillId)
        if (!dir.isDirectory) return emptyList()
        val rootLen = dir.absolutePath.length + 1
        val files = dir.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .map { it.absolutePath.substring(rootLen) }
            .toList()
        return files.sortedWith(compareBy(
            { if (it.equals("SKILL.md", ignoreCase = true)) 0 else 1 },
            { it },
        ))
    }

    /** Absolute host path for a file inside a skill's directory. */
    fun skillFileHostPath(skillId: String, relativePath: String): String =
        File(File(skillsDir, skillId), relativePath).absolutePath

    /**
     * Re-read SKILL.md from disk and push any frontmatter changes back into the
     * DB + in-memory state. Call after the agent (or user) edits SKILL.md on
     * disk so the store reflects the new name/description/version/body.
     * Returns the refreshed skill or null if the file is gone or malformed.
     */
    fun rescanFromDisk(skillId: String): Skill? {
        val current = _skills.value.find { it.id == skillId } ?: return null
        val file = File(File(skillsDir, skillId), "SKILL.md")
        if (!file.exists()) {
            // [T-android-skill-orphan-db-row] A rescan that finds nothing on
            // disk used to only paint "SKILL.md missing or invalid" and leave
            // the record in place, so the one action explicitly meant to
            // reconcile with disk detected the orphan and still refused to act
            // on it. Drop it here too — same BUNDLED exemption as loadAll(),
            // since a bundled skill's file is restored by installBundledSkills.
            if (current.importSource != ImportSource.BUNDLED) {
                delete(skillId)
                Log.i(TAG, "Rescan found no SKILL.md — removed orphan skill: $skillId")
            }
            return null
        }
        val parsed = parseSkillMd(file.readText()) ?: return null
        val refreshed = current.copy(
            name = parsed.name,
            description = parsed.description,
            version = parsed.version,
            body = parsed.body,
            updatedAt = System.currentTimeMillis(),
        )
        db.execSQL(
            "UPDATE skills SET name=?, description=?, version=?, updated_at=? WHERE id=?",
            arrayOf<Any>(refreshed.name, refreshed.description, refreshed.version, refreshed.updatedAt, skillId)
        )
        mutateSkills { list -> list.map { if (it.id == skillId) refreshed else it } }
        return refreshed
    }

    /**
     * Rename a skill in place — updates the `name:` line in frontmatter and DB
     * but keeps the slugged id (and therefore the path) stable so references
     * from existing sessions don't break.
     */
    fun renameSkill(skillId: String, newName: String): Boolean {
        val current = _skills.value.find { it.id == skillId } ?: return false
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return false
        val file = File(File(skillsDir, skillId), "SKILL.md")
        if (file.exists()) {
            val original = file.readText()
            val updated = original.replaceFirst(Regex("(?m)^name:\\s*.*$"), "name: $trimmed")
            if (updated != original) file.writeText(updated)
        }
        val refreshed = current.copy(name = trimmed, updatedAt = System.currentTimeMillis())
        db.execSQL(
            "UPDATE skills SET name=?, updated_at=? WHERE id=?",
            arrayOf<Any>(refreshed.name, refreshed.updatedAt, skillId)
        )
        mutateSkills { list -> list.map { if (it.id == skillId) refreshed else it } }
        return true
    }

    /** Read an arbitrary file inside a skill's directory (e.g. scripts/foo.py). */
    fun readSkillFile(skillId: String, relativePath: String): String? {
        if (relativePath.contains("..")) return null
        val file = File(File(skillsDir, skillId), relativePath)
        if (!file.isFile) return null
        return try { file.readText() } catch (_: Exception) { null }
    }

    /** Write an arbitrary file inside a skill's directory. Creates parents. */
    fun writeSkillFile(skillId: String, relativePath: String, content: String): Boolean {
        if (relativePath.contains("..")) return false
        val file = File(File(skillsDir, skillId), relativePath)
        file.parentFile?.mkdirs()
        return try {
            file.writeText(content)
            // SKILL.md edits must flow back to DB metadata.
            if (relativePath.equals("SKILL.md", ignoreCase = true)) rescanFromDisk(skillId)
            true
        } catch (_: Exception) { false }
    }

    private suspend fun downloadSiblingFiles(ghInfo: GitHubInfo, destDir: File, skill: Skill): SiblingDownloadOutcome {
        Log.i(TAG, "[siblings] start skill=${skill.id} dir=${ghInfo.dirPath} repo=${ghInfo.user}/${ghInfo.repo}@${ghInfo.branch}")
        val agg = AggregateOutcome()
        downloadGitHubDirectory(
            user = ghInfo.user,
            repo = ghInfo.repo,
            branch = ghInfo.branch,
            remotePath = ghInfo.dirPath,
            localDir = destDir,
            relativeTo = "",
            skill = skill,
            depth = 0,
            outcome = agg,
        )
        val finalReason = agg.firstReason
        Log.i(TAG, "[siblings] done skill=${skill.id} written=${agg.filesWritten} failed=${agg.filesFailed} reason=${finalReason ?: "(none)"}")
        return SiblingDownloadOutcome(agg.filesWritten, agg.filesFailed, finalReason)
    }

    /**
     * Mutable accumulator threaded through the recursion. Tracks a
     * machine-friendly count plus the FIRST human-readable reason so the UI
     * can show one specific cause (e.g. "GitHub API 403 (rate limited)")
     * rather than a generic "some files failed".
     */
    private class AggregateOutcome {
        var filesWritten = 0
        var filesFailed = 0
        var firstReason: String? = null
        fun recordFailure(reason: String) {
            filesFailed += 1
            if (firstReason == null) firstReason = reason
        }
        fun recordReason(reason: String) {
            if (firstReason == null) firstReason = reason
        }
    }

    private suspend fun downloadGitHubDirectory(
        user: String,
        repo: String,
        branch: String,
        remotePath: String,
        localDir: File,
        relativeTo: String,
        skill: Skill,
        depth: Int,
        outcome: AggregateOutcome,
    ) {
        if (depth > 5) {
            val msg = "Max recursion depth (5) at $remotePath — stopping"
            Log.w(TAG, "[siblings] $msg")
            outcome.recordReason(msg)
            return
        }
        val encodedPath = URLEncoder.encode(remotePath, "UTF-8").replace("+", "%20").replace("%2F", "/")
        val apiURL = "https://api.github.com/repos/$user/$repo/contents/$encodedPath?ref=$branch"

        // 1-shot retry on transient failure (HTTP 403 rate-limited, network
        // exception). The retry waits 1.5s — enough to clear short-lived
        // OkHttp connection-pool issues, not enough to fix a real rate-limit
        // window (which is per-hour) but cheap enough that it's worth trying.
        val body = fetchContentsWithRetry(apiURL, outcome) ?: return

        val items = try {
            JSONArray(body)
        } catch (e: Exception) {
            // Some endpoints return a JSON object on error (e.g. {"message":...})
            // even with HTTP 200. Capture the first-line snippet for triage.
            val snippet = body.lineSequence().firstOrNull()?.take(160) ?: "(empty)"
            val msg = "GitHub contents API returned non-array JSON for $remotePath: $snippet"
            Log.w(TAG, "[siblings] $msg (${e.javaClass.simpleName}: ${e.message})")
            outcome.recordReason(msg)
            return
        }

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val name = item.optString("name")
            if (name.isEmpty()) continue
            val type = item.optString("type")
            val relPath = if (relativeTo.isEmpty()) name else "$relativeTo/$name"

            if (type == "dir") {
                val subRemote = if (remotePath.isEmpty()) name else "$remotePath/$name"
                downloadGitHubDirectory(
                    user = user, repo = repo, branch = branch,
                    remotePath = subRemote, localDir = localDir, relativeTo = relPath,
                    skill = skill, depth = depth + 1, outcome = outcome,
                )
                continue
            }

            if (type != "file") continue
            // Skip SKILL.md at root (already imported)
            if (relativeTo.isEmpty() && name.equals("SKILL.md", ignoreCase = true)) continue
            val downloadURL = item.optString("download_url")
            if (downloadURL.isEmpty()) {
                Log.w(TAG, "[siblings] missing download_url for $relPath — skipping")
                outcome.recordFailure("Missing download_url for $relPath")
                continue
            }

            val fileData = fetchBytesWithRetry(downloadURL)
            if (fileData == null) {
                val msg = "Failed to download $relPath from $downloadURL"
                Log.w(TAG, "[siblings] $msg")
                outcome.recordFailure(msg)
                continue
            }
            val destFile = File(localDir, relPath)
            destFile.parentFile?.mkdirs()
            val existing = if (destFile.exists()) destFile.readBytes() else null
            if (existing == null || !existing.contentEquals(fileData)) {
                destFile.writeBytes(fileData)
                Log.i(TAG, "[siblings] wrote $relPath (${fileData.size}B)")
            } else {
                Log.i(TAG, "[siblings] kept $relPath (unchanged, ${fileData.size}B)")
            }
            outcome.filesWritten += 1
        }
    }

    /**
     * Fetch a GitHub Contents-API URL, retrying once on transient failure
     * (HTTP 403/429/5xx, IOException). Returns the response body string on
     * success, or null after both attempts fail — in which case the failure
     * reason is recorded into [outcome] so the UI can surface it.
     */
    private suspend fun fetchContentsWithRetry(apiURL: String, outcome: AggregateOutcome): String? {
        var lastReason: String? = null
        repeat(2) { attempt ->
            try {
                val req = Request.Builder()
                    .url(apiURL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (body != null) return body
                        lastReason = "GitHub contents API returned empty body"
                    } else {
                        val isTransient = resp.code == 403 || resp.code == 429 || resp.code in 500..599
                        // 403 from api.github.com is almost always rate-limit;
                        // call it out specifically so users know waiting helps.
                        val hint = if (resp.code == 403) " (likely anonymous rate limit — wait an hour or sign in)" else ""
                        lastReason = "GitHub contents API HTTP ${resp.code}$hint for $apiURL"
                        if (!isTransient) {
                            Log.w(TAG, "[siblings] non-retryable ${lastReason}")
                            outcome.recordReason(lastReason!!)
                            return null
                        }
                    }
                }
            } catch (e: Exception) {
                lastReason = "GitHub contents API ${e.javaClass.simpleName}: ${e.message ?: "unknown"} for $apiURL"
            }
            if (attempt == 0) {
                Log.w(TAG, "[siblings] retrying after transient failure: $lastReason")
                try { delay(1500) } catch (_: Exception) {}
            }
        }
        Log.w(TAG, "[siblings] both attempts failed: $lastReason")
        outcome.recordReason(lastReason ?: "GitHub contents API unavailable")
        return null
    }

    /** Same retry policy as [fetchContentsWithRetry], for raw file blobs. */
    private suspend fun fetchBytesWithRetry(url: String): ByteArray? {
        repeat(2) { attempt ->
            try {
                val req = Request.Builder().url(url).get().build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return resp.body?.bytes()
                    val isTransient = resp.code == 403 || resp.code == 429 || resp.code in 500..599
                    if (!isTransient) {
                        Log.w(TAG, "[siblings] HTTP ${resp.code} non-retryable for $url")
                        return null
                    }
                    Log.w(TAG, "[siblings] HTTP ${resp.code} on $url (attempt ${attempt + 1})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "[siblings] ${e.javaClass.simpleName}: ${e.message} for $url (attempt ${attempt + 1})")
            }
            if (attempt == 0) {
                try { delay(1500) } catch (_: Exception) {}
            }
        }
        return null
    }

    /**
     * Parse a GitHub URL into components. Returns null for non-GitHub URLs.
     * Supports:
     *   - github.com/user/repo/blob|tree/branch/path/SKILL.md
     *   - raw.githubusercontent.com/user/repo/branch/path/SKILL.md
     */
    private fun parseGitHubURL(urlString: String): GitHubInfo? {
        val normalized = normalizeURL(urlString)
        val uri = try { Uri.parse(normalized) } catch (_: Exception) { return null }
        val host = uri.host ?: return null
        val parts = uri.pathSegments.filter { it.isNotEmpty() }

        if (host == "raw.githubusercontent.com") {
            // /user/repo/branch/path/to/SKILL.md
            if (parts.size < 3) return null
            val user = parts[0]; val repo = parts[1]; val branch = parts[2]
            val dirParts = parts.drop(3).toMutableList()
            if (dirParts.isNotEmpty() && dirParts.last().equals("SKILL.md", ignoreCase = true)) {
                dirParts.removeAt(dirParts.size - 1)
            }
            return GitHubInfo(user, repo, branch, dirParts.joinToString("/"))
        }

        if (host != "github.com") return null
        // /user/repo/blob|tree/branch/path/...
        if (parts.size < 4) return null
        val user = parts[0]; val repo = parts[1]; val branch = parts[3]
        val dirParts = parts.drop(4).toMutableList()
        if (dirParts.isNotEmpty() && dirParts.last().equals("SKILL.md", ignoreCase = true)) {
            dirParts.removeAt(dirParts.size - 1)
        }
        return GitHubInfo(user, repo, branch, dirParts.joinToString("/"))
    }

    /**
     * Convert a GitHub URL to a raw.githubusercontent.com SKILL.md URL.
     * Returns null if the URL cannot be parsed.
     */
    private fun githubToRawURL(urlString: String): String? {
        val normalized = normalizeURL(urlString)
        val uri = try { Uri.parse(normalized) } catch (_: Exception) { return null }
        val host = uri.host ?: return null
        val parts = uri.pathSegments.filter { it.isNotEmpty() }

        if (host == "raw.githubusercontent.com") {
            return if (parts.lastOrNull()?.equals("SKILL.md", ignoreCase = true) == true) {
                normalized
            } else {
                val base = normalized.trimEnd('/')
                "$base/SKILL.md"
            }
        }

        if (host != "github.com") return null
        if (parts.size < 4) return null
        val user = parts[0]; val repo = parts[1]; val branch = parts[3]
        val pathParts = parts.drop(4).toMutableList()
        var path = pathParts.joinToString("/")
        if (!path.endsWith("SKILL.md", ignoreCase = true)) {
            path = if (path.isEmpty()) "SKILL.md" else "$path/SKILL.md"
        }
        return "https://raw.githubusercontent.com/$user/$repo/$branch/$path"
    }

    private fun normalizeURL(urlString: String): String {
        val trimmed = urlString.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "https://$trimmed"
    }

    private fun httpGetString(url: String): String? = try {
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    } catch (e: Exception) {
        Log.w(TAG, "httpGetString failed $url: ${e.message}")
        null
    }

    private fun httpGetBytes(url: String): ByteArray? = try {
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()
        }
    } catch (e: Exception) {
        Log.w(TAG, "httpGetBytes failed $url: ${e.message}")
        null
    }


    // -- Bundled Skills --

    private fun installBundledSkills() {
        val bundledId = "skill-creator"
        val bundledVersion = "2.0.0"
        val existing = _skills.value.find { it.id == bundledId }

        // Skip if local version is same or newer
        if (existing != null && existing.version >= bundledVersion) return

        val parsed = parseSkillMd(SKILL_CREATOR_CONTENT) ?: return
        if (existing != null) {
            // Upgrade existing
            val updated = existing.copy(
                description = parsed.description,
                version = bundledVersion,
                body = parsed.body,
                updatedAt = System.currentTimeMillis(),
            )
            db.execSQL(
                "UPDATE skills SET description=?, version=?, updated_at=? WHERE id=?",
                arrayOf<Any>(updated.description, bundledVersion, updated.updatedAt, bundledId)
            )
            writeSkillMd(updated)
            mutateSkills { list -> list.map { if (it.id == bundledId) updated else it } }
            Log.i(TAG, "Upgraded bundled skill: $bundledId → v$bundledVersion")
        } else {
            // Fresh install
            add(
                name = parsed.name,
                description = parsed.description,
                body = parsed.body,
                source = ImportSource.BUNDLED,
            )
            Log.i(TAG, "Installed bundled skill: $bundledId (v$bundledVersion)")
        }
    }

    // -- Reload --

    /**
     * Re-scan `skillsDir` and the SQLite registry, re-publishing the
     * resulting list via the [skills] StateFlow. Mirrors iOS
     * `SkillStore.reload()` (Agent/Session/SkillStore.swift). Use this after
     * an out-of-band install (agent shell `git clone`, agent file_write of a
     * SKILL.md, on Skills screen entry) so newly dropped directories are
     * promoted from disk into the registry without requiring an app restart.
     *
     * Compat: does not delete or reset enabled state — DB rows are the
     * source of truth for `is_enabled`, and disk-only directories are
     * auto-discovered as ImportSource.SESSION (matching the existing init
     * path), not overwriting any preserved DB toggle.
     */
    fun reloadFromDisk() {
        requestReload("reloadFromDisk", force = true)
    }

    /**
     * [T-android-skill-scan-parity] Queue a background rescan. Mirrors how iOS
     * keeps disk work off the send path: SkillStore.skillPromptFragment() only
     * reads the in-memory list, and disk is reconciled on discrete events
     * (foreground, file_write/file_edit of a SKILL.md, the debounced fakefs
     * notifier after shell activity, backup restore).
     *
     * Requests are conflated and served one at a time on [Dispatchers.IO].
     * With [force] false the scan is skipped unless [SkillDiskSignature]
     * differs from the one taken at the last scan — one stat per skill
     * directory, so a shell command that never touched /var/minis/skills costs
     * a few milliseconds off the main thread instead of a full reload on it.
     */
    fun requestReload(reason: String, force: Boolean = false) {
        if (force) forceReloadPending.set(true)
        reloadRequests.trySend(reason)
    }

    private fun startReloadWorker() {
        backgroundScope.launch {
            for (reason in reloadRequests) {
                val force = forceReloadPending.getAndSet(false)
                runCatching {
                    val signature = SkillDiskSignature.compute(skillsDir)
                    if (!force && signature == lastDiskSignature) return@runCatching
                    val started = System.currentTimeMillis()
                    loadAllCommitted()
                    lastDiskSignature = signature
                    Log.i(TAG, "reload reason=$reason force=$force skills=${_skills.value.size} tookMs=${System.currentTimeMillis() - started}")
                }.onFailure {
                    Log.e(TAG, "background reload failed reason=$reason: ${it.message}", it)
                }
            }
        }
    }

    // -- Internal --

    private inline fun mutateSkills(transform: (List<Skill>) -> List<Skill>) {
        synchronized(skillsLock) {
            _skills.value = transform(_skills.value)
            mutationSeq++
        }
    }

    /**
     * Run [loadAll] until its result can be published without discarding a
     * concurrent [mutateSkills]. A scan that raced an add/update simply runs
     * again — it then reads the DB row that edit wrote. Three attempts cover
     * any realistic burst; after that the last scan wins, and the DB (which
     * every edit wrote first) is still correct for the next reload.
     */
    private fun loadAllCommitted() {
        repeat(3) { if (loadAll(requireSeq = true)) return }
        loadAll(requireSeq = false)
    }

    private fun loadAll(requireSeq: Boolean = false): Boolean {
        val seqAtStart = synchronized(skillsLock) { mutationSeq }
        // Load from DB
        val dbSkills = mutableListOf<Skill>()
        // [T-android-skill-scan-parity] One listing of skillsDir up front, then
        // set lookups. The circuit breaker used to re-query every row and stat
        // every SKILL.md once PER missing skill — O(missing x total). A user
        // with 283 of 490 skills missing paid ~139k stat() calls plus 283
        // Log.e lines per load, about two seconds, and that ran on the main
        // thread on every send.
        val storageReady = skillsDir.isDirectory
        val onDisk = SkillDiskSignature.skillIdsOnDisk(skillsDir)
        val customIds = mutableListOf<String>()
        db.rawQuery("SELECT id, import_source FROM skills", null).use { c ->
            while (c.moveToNext()) {
                if (ImportSource.from(c.getString(1)) != ImportSource.BUNDLED) customIds.add(c.getString(0))
            }
        }
        val missingCustom = customIds.count { it !in onDisk }
        val pruneAllowed = storageReady && !SkillDiskSignature.breakerTripped(customIds.size, missingCustom)
        if (missingCustom > 0 && !pruneAllowed) {
            if (!storageReady) {
                Log.w(TAG, "skillsDir unready, skipping orphan pruning for $missingCustom skill(s)")
            } else {
                Log.e(TAG, "Circuit breaker tripped ($missingCustom / ${customIds.size} missing). Keeping all orphan rows to prevent mass data wipe.")
            }
        }
        // [T-android-safemode-lateinit-crash-147] `use` so the cursor is closed
        // even when a row throws — the old code only reached close() on the
        // success path and leaked on any failure.
        db.rawQuery("SELECT * FROM skills ORDER BY installed_at DESC", null).use { cursor ->
        while (cursor.moveToNext()) {
            // [T-android-safemode-lateinit-crash-147] Per-row isolation: one
            // malformed skill (a third-party import with a missing column or
            // an unparseable SKILL.md) must not discard every OTHER skill, and
            // must not escape into MinisApp.onCreate. Skip the bad row, keep
            // the rest.
            try {
            val id = cursor.getString(cursor.getColumnIndexOrThrow("id"))
            val importSource = ImportSource.from(cursor.getString(cursor.getColumnIndexOrThrow("import_source")))
            // [T-android-skill-orphan-db-row] Prune rows whose skill directory
            // is gone. The agent deletes skills with `rm -rf` from the shell,
            // which never reaches delete() — so the DB row survived and this
            // merge, which only ever ADDED disk-only skills (the auto-discover
            // pass below), read the orphan straight back into _skills on every
            // load. The entry stayed in the settings list through screen
            // re-entry AND cold start, was still navigable, and — worse than
            // cosmetic — skillPromptFragment() kept advertising it to the model
            // with a dead /var/minis/skills/<id>/SKILL.md path. Mirrors iOS
            // SkillStore.loadSkills(), which does dbDeleteSkill(id:)+continue
            // when SKILL.md is missing.
            //
            // BUNDLED is exempt on purpose: init{} runs loadAll() BEFORE
            // installBundledSkills(), so on the first load after a fresh
            // install the skill-creator row legitimately exists with nothing on
            // disk yet. Pruning it here would discard its use_count moments
            // before the installer re-materializes the file.
            //
            // Only one location to check, unlike iOS's Library+rootfs pair:
            // PRootKernel.registerGlobalBindMounts binds /var/minis/skills
            // straight to this same filesDir/minis-global/skills.
            // [T-android-skill-circuit-breaker] Pruning is decided once per load
            // (pruneAllowed above), never when storage is unready or when a
            // large share of custom skills is missing at once.
            if (importSource != ImportSource.BUNDLED && id !in onDisk && pruneAllowed) {
                db.execSQL("DELETE FROM skills WHERE id=?", arrayOf(id))
                db.execSQL("DELETE FROM session_skill_overrides WHERE skill_id=?", arrayOf(id))
                Log.i(TAG, "Pruned orphan skill row (no SKILL.md on disk): $id")
                continue
            }
            val body = readSkillMdBody(id)
            val sourceUrlIdx = cursor.getColumnIndex("source_url")
            val useCountIdx = cursor.getColumnIndex("use_count")
            var description = cursor.getString(cursor.getColumnIndexOrThrow("description"))
            var name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
            var version = cursor.getString(cursor.getColumnIndexOrThrow("version"))
            // Self-heal rows persisted by an earlier parser that treated YAML
            // block scalars (`|` and `>`) as literal one-character description
            // values. Re-parse the on-disk SKILL.md whenever the stored
            // description is stale and update the row in place so the skills
            // list shows the real frontmatter description.
            //
            // [T-android-skill-empty-description-never-refreshes] GH#215
            // (iOS 7d0cc49a7): an EMPTY description is stale too, not just the
            // literal "|" / ">" leftovers the original check knew about.
            //
            // How a skill gets stuck: the agent writes SKILL.md from the shell,
            // and the skill can be registered while that file is still being
            // written and has no `description:` line yet. "" is persisted, and
            // because the stale test only matched "|" / ">", every later load
            // trusted that "" forever. Since the DB row is keyed by directory
            // name it survives delete-and-recreate, so touching the file,
            // rewriting the frontmatter and even `rm -rf` + recreate all fail
            // to heal it — only renaming the skill works, because that mints a
            // new row id.
            // [T-android-skill-stale-name] The NAME is stale-checked too, and
            // independently of the description.
            //
            // Before, healing was gated entirely on the description: a row
            // whose description was fine but whose name was empty never
            // entered this block at all, and even when it did, the name was
            // only filled in `if (name.isBlank())` — inside a branch that
            // additionally required a non-blank re-parsed description. A skill
            // could therefore sit with a good description and a blank name
            // forever, showing as an empty row in the skills list while the
            // real name sat in SKILL.md one directory away.
            //
            // Note on scope: the iOS report (e4af3ad81) described names stuck
            // at the literal "Untitled Skill". That constant is iOS-only —
            // grep finds no such string in this codebase, and Android's
            // parseSkillMd `return null`s on a missing name rather than
            // substituting a placeholder, so a row can only ever be stuck
            // BLANK here, never at that literal. The check below is written
            // against blankness for that reason; adding the iOS literal would
            // be matching a value this platform cannot produce.
            val nameStale = name.isBlank()
            val descStale = description == ">" || description == "|" || description.isBlank()
            if (descStale || nameStale) {
                val skillMd = File(skillsDir, "$id/SKILL.md")
                if (skillMd.exists()) {
                    val reparsed = parseSkillMd(runCatching { skillMd.readText() }.getOrNull() ?: "")
                    // Only ever REPLACE an empty/placeholder value with a real
                    // one, never the reverse. A mid-edit or malformed SKILL.md
                    // parses to null or yields an empty description, and
                    // writing that back would wipe good metadata — the exact
                    // hazard that kept this check narrow in the first place.
                    // Combined with the guards above (the stored value must be
                    // blank or a block-scalar leftover to get here), a name or
                    // description the user deliberately set can never be
                    // overwritten.
                    if (reparsed != null) {
                        var changed = false
                        if (descStale && reparsed.description.isNotBlank()) {
                            description = reparsed.description
                            changed = true
                        }
                        // parseSkillMd returns null unless it found a non-blank
                        // name, so reparsed.name is always real here — but the
                        // isNotBlank() guard is kept so this stays correct if
                        // that parser contract ever loosens.
                        if (nameStale && reparsed.name.isNotBlank()) {
                            name = reparsed.name
                            changed = true
                        }
                        if (version.isBlank()) {
                            version = reparsed.version
                            changed = true
                        }
                        if (changed) {
                            db.execSQL(
                                "UPDATE skills SET name=?, description=?, version=?, updated_at=? WHERE id=?",
                                arrayOf<Any>(name, description, version, System.currentTimeMillis(), id),
                            )
                            Log.i(TAG, "Self-healed skill name/description for $id")
                        }
                    }
                }
            }
            dbSkills.add(Skill(
                id = id,
                name = name,
                description = description,
                version = version,
                importSource = importSource,
                isEnabled = cursor.getInt(cursor.getColumnIndexOrThrow("is_enabled")) == 1,
                installedAt = cursor.getLong(cursor.getColumnIndexOrThrow("installed_at")),
                updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                body = body,
                sourceURL = if (sourceUrlIdx >= 0 && !cursor.isNull(sourceUrlIdx)) cursor.getString(sourceUrlIdx) else null,
                useCount = if (useCountIdx >= 0) cursor.getDouble(useCountIdx) else 0.0,
            ))
            } catch (t: Throwable) {
                Log.e(TAG, "skipping unreadable skill row: ${t.message}", t)
            }
        }
        }

        // Auto-discover skills on disk without DB entries
        val known = dbSkills.mapTo(HashSet(dbSkills.size)) { it.id }
        for (dirName in onDisk) {
            val dir = File(skillsDir, dirName)
            val skillMd = File(dir, "SKILL.md")
            if (dirName !in known) {
                // The listing was taken at the top of loadAll; on the background
                // worker a shell `rm -rf` can land in between, so a vanished
                // file skips this entry instead of aborting the whole reload.
                val parsed = runCatching { parseSkillMd(skillMd.readText()) }.getOrNull()
                if (parsed != null) {
                    val skill = Skill(
                        id = dir.name,
                        name = parsed.name,
                        description = parsed.description,
                        importSource = ImportSource.SESSION,
                        body = parsed.body,
                    )
                    insertDb(skill)
                    dbSkills.add(skill)
                    Log.i(TAG, "Auto-discovered skill: ${dir.name}")
                }
            }
        }

        synchronized(skillsLock) {
            if (requireSeq && mutationSeq != seqAtStart) return false
            _skills.value = dbSkills
        }
        return true
    }

    private fun insertDb(skill: Skill) {
        db.execSQL(
            """INSERT OR REPLACE INTO skills (id, name, description, version, import_source, source_url, is_enabled, installed_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                skill.id, skill.name, skill.description, skill.version,
                skill.importSource.value, skill.sourceURL,
                if (skill.isEnabled) 1 else 0,
                skill.installedAt, skill.updatedAt,
            )
        )
    }

    private fun writeSkillMd(skill: Skill) {
        val dir = File(skillsDir, skill.id)
        dir.mkdirs()
        val content = buildString {
            appendLine("---")
            appendLine("name: ${skill.name}")
            appendLine("description: ${skill.description}")
            appendLine("version: ${skill.version}")
            appendLine("---")
            append(skill.body)
        }
        File(dir, "SKILL.md").writeText(content)
    }

    private fun readSkillMdBody(id: String): String {
        val file = File(skillsDir, "$id/SKILL.md")
        if (!file.exists()) return ""
        val parsed = parseSkillMd(file.readText())
        return parsed?.body ?: ""
    }

    data class ParsedSkill(
        val name: String,
        val description: String,
        val version: String = "1.0.0",
        val body: String,
    )

    /**
     * Parse a SKILL.md file into [ParsedSkill]. Mirrors iOS SkillStore.parse(skillMD:).
     *
     * Recognized YAML frontmatter forms (delimited by `---` … `---`):
     *
     *   description: short text
     *   description: |
     *     line one
     *     line two           ← preserved with newlines
     *   description: >
     *     line one
     *     line two           ← folded into a single space-joined line
     *
     * The block-scalar variants (`|` and `>`) matter in practice — many
     * authored SKILL.md files (e.g. hyperframes, bilibili-hub) use
     * `description: >` to either fold a multi-line description or as a
     * placeholder with an empty body. The previous parser stored the
     * literal string `">"` for those skills, which is what surfaced in the
     * skills list as a confusing single-character description.
     *
     * Returns null only if the document doesn't start with `---` or if
     * the closing `---` is missing — caller treats null as "not a SKILL.md".
     * If frontmatter parses but `name` is blank we also return null so
     * import paths can fall back (e.g. derive name from the GitHub URL).
     */
    /**
     * Public re-exposure for the "Update from File" UI. Same parser, same
     * `null = invalid SKILL.md` contract. Lives on the instance for symmetry
     * with the rest of the API surface, even though the parse itself is
     * stateless.
     */
    fun parseSkillMdPublic(content: String): ParsedSkill? = parseSkillMd(content)

    private fun slugify(name: String): String =
        name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    // -- Database Helper --

    private class SkillDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE skills (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    version TEXT NOT NULL DEFAULT '1.0.0',
                    import_source TEXT NOT NULL DEFAULT 'file',
                    source_url TEXT,
                    is_enabled INTEGER NOT NULL DEFAULT 1,
                    installed_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    use_count REAL NOT NULL DEFAULT 0
                )
            """)
            db.execSQL("""
                CREATE TABLE session_skill_overrides (
                    session_id TEXT NOT NULL,
                    skill_id TEXT NOT NULL,
                    is_enabled INTEGER NOT NULL,
                    PRIMARY KEY (session_id, skill_id)
                )
            """)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                try {
                    db.execSQL("ALTER TABLE skills ADD COLUMN source_url TEXT")
                } catch (_: Exception) { /* column may already exist */ }
            }
            if (oldVersion < 3) {
                try {
                    db.execSQL("ALTER TABLE skills ADD COLUMN use_count REAL NOT NULL DEFAULT 0")
                } catch (_: Exception) { /* column may already exist */ }
            }
        }
    }
}

// Bundled skill-creator content (matches iOS SkillStore.skillCreatorContent)
private val SKILL_CREATOR_CONTENT = """
---
name: skill-creator
version: 2.0.0
description: Guide for creating effective skills. This skill should be used when users want to create a new skill (or update an existing skill) that extends Claude's capabilities with specialized knowledge, workflows, or tool integrations.
---

# Skill Creator

This skill provides guidance for creating effective skills.

## About Skills

Skills are modular, self-contained packages that extend Claude's capabilities by providing
specialized knowledge, workflows, and tools. Think of them as "onboarding guides" for specific
domains or tasks—they transform Claude from a general-purpose agent into a specialized agent
equipped with procedural knowledge that no model can fully possess.

### What Skills Provide

1. Specialized workflows - Multi-step procedures for specific domains
2. Tool integrations - Instructions for working with specific file formats or APIs
3. Domain expertise - Company-specific knowledge, schemas, business logic
4. Bundled resources - Scripts, references, and assets for complex and repetitive tasks

## Core Principles

### Concise is Key

The context window is a public good. Skills share the context window with everything else Claude needs: system prompt, conversation history, other Skills' metadata, and the actual user request.

**Default assumption: Claude is already very smart.** Only add context Claude doesn't already have. Challenge each piece of information: "Does Claude really need this explanation?" and "Does this paragraph justify its token cost?"

Prefer concise examples over verbose explanations.

### Set Appropriate Degrees of Freedom

Match the level of specificity to the task's fragility and variability:

- **High freedom (text-based instructions)**: Use when multiple approaches are valid.
- **Medium freedom (pseudocode or scripts with parameters)**: Use when a preferred pattern exists.
- **Low freedom (specific scripts, few parameters)**: Use when operations are fragile, consistency is critical, or a specific sequence must be followed.

### Anatomy of a Skill

Every skill consists of a required SKILL.md file and optional bundled resources:

```
skill-name/
├── SKILL.md (required)
│   ├── YAML frontmatter (name + description required)
│   └── Markdown instructions
└── Bundled Resources (optional)
    ├── scripts/       - Executable code
    ├── references/    - Documentation loaded as needed
    └── assets/        - Files used in output (templates, icons, etc.)
```

#### SKILL.md Frontmatter

- `name` (required): The skill name
- `description` (required): What the skill does and when to trigger it. Be comprehensive—this is the primary triggering mechanism.

#### SKILL.md Body

Instructions and guidance, loaded after the skill triggers. Keep under 500 lines; split into reference files when approaching this limit.

### Progressive Disclosure

Skills use three loading levels:
1. **Metadata** - Always in context (~100 words)
2. **SKILL.md body** - When skill triggers (<5k words)
3. **Bundled resources** - As needed (unlimited)

## Skill Creation Process

1. **Understand** the skill with concrete examples from the user
2. **Plan** reusable contents (scripts, references, assets)
3. **Create** the SKILL.md with proper frontmatter and instructions
4. **Test** by using the skill on real tasks
5. **Iterate** based on actual usage

### Writing the SKILL.md

- Use imperative/infinitive form
- `description` field should include all "when to use" triggers (body is loaded after triggering)
- Only add context Claude doesn't already have
- Prefer concise examples over verbose explanations
- Keep essential workflow in SKILL.md; move detailed reference material to separate files

### What NOT to Include

Do not create extraneous files: README.md, INSTALLATION_GUIDE.md, CHANGELOG.md, etc. The skill should only contain what an AI agent needs to do the job.
""".trimIndent()
