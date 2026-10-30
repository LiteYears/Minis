package com.openminis.app.sandbox.offload

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.openminis.app.MinisApp
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.config.ConfigBridge
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.safeOptString
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.ui.chat.ChatViewModelStore
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-android-browser-cli-own-pool] Which tab pool a `minis-browser-use` call
 * drives. Port of iOS `BrowserUseOffloadBridge.pool(for:)`.
 *
 * The CLI used to run every call on `MinisApp.sharedBrowserTabPool`, one pool
 * for the whole process. Shells from different chats run concurrently, so
 * chats scripting the browser (for example two chats on two shopping sites)
 * navigated each other's page, and read text and screenshots from whichever
 * site the other chat had just opened. It also meant a chat's agent
 * (`browser_use`) and its shell (`minis-browser-use`) saw different tabs.
 *
 * Now the call runs on the CALLING chat's pool:
 *  1. The live ChatViewModel of the caller (`MINIS_CHAT_SESSION_ID`, set per
 *     process by the shell). A sub agent's view model has adopted its
 *     parent's pool, so a sub agent lands on the parent's tab set, as its
 *     `browser_use` does.
 *  2. The live view model of the session whose files are mounted
 *     ([ExecutionCoordinator.mountedSessionIdFor]: the parent for a helper
 *     whose own view model is gone).
 *  3. A fallback pool for that session, reused by later calls and created only
 *     while the session still exists. A deleted session is an error, not a
 *     zombie pool.
 *  4. No session at all (a shell that is not a chat's, e.g. the interactive
 *     terminal): the process-wide shared pool, iOS's "__unmounted__" sentinel.
 */
internal object BrowserCliPools {

    private const val TAG = "BrowserCliPools"

    /** Bridge-owned pools for sessions with no live view model. */
    private val fallbackPools = ConcurrentHashMap<String, BrowserTabPool>()

    sealed interface Resolution<out P> {
        /** [via] names the source, for the log line and tests. */
        data class Found<P>(val pool: P, val via: String) : Resolution<P>
        data class SessionGone(val sessionId: String) : Resolution<Nothing>
    }

    /**
     * The resolution order, separated from Android types so it is testable.
     * [liveVm] returns the pool of a live view model for a session id;
     * [fallback] an existing fallback pool; [create] makes (and records) one.
     */
    internal fun <P> choose(
        callerSid: String?,
        mountedSid: String?,
        liveVm: (String) -> P?,
        fallback: (String) -> P?,
        sessionExists: (String) -> Boolean,
        create: (String) -> P,
        shared: () -> P,
    ): Resolution<P> {
        if (callerSid == null) return Resolution.Found(shared(), "shared(no-session)")
        liveVm(callerSid)?.let { return Resolution.Found(it, "vm") }
        val owner = mountedSid ?: callerSid
        if (owner != callerSid) liveVm(owner)?.let { return Resolution.Found(it, "vm(mounted)") }
        fallback(owner)?.let { return Resolution.Found(it, "fallback") }
        if (!sessionExists(owner)) return Resolution.SessionGone(owner)
        return Resolution.Found(create(owner), "fallback(new)")
    }

    fun resolve(app: MinisApp, callerSid: String?): Resolution<BrowserTabPool> {
        val mounted = callerSid?.let { ExecutionCoordinator.mountedSessionIdFor(it) }
        return choose(
            callerSid = callerSid,
            mountedSid = mounted,
            liveVm = { sid -> ChatViewModelStore.existing(sid)?.browserTabPool },
            fallback = { sid -> fallbackPools[sid] },
            sessionExists = { sid ->
                // chatRepositoryOrNull: the lateinit repository throws when
                // onCreate stopped early (safe mode). Unknown means "gone".
                val dao = app.chatRepositoryOrNull?.dao
                dao != null && runCatching { runBlocking { dao.getSession(sid) } != null }.getOrDefault(false)
            },
            create = { sid ->
                fallbackPools.getOrPut(sid) {
                    Log.i(TAG, "fallback browser pool for session ${sid.take(8)} (no live chat view model)")
                    BrowserTabPool(app).also { it.setSession(sid) }
                }
            },
            shared = { app.sharedBrowserTabPool },
        )
    }

    /**
     * Drop a deleted session's fallback pool and its WebViews. Pools owned by a
     * live view model follow that view model's lifecycle and are untouched.
     */
    fun release(sessionId: String) {
        val pool = fallbackPools.remove(sessionId) ?: return
        Handler(Looper.getMainLooper()).post { pool.destroyAllTabs() }
        Log.i(TAG, "released fallback browser pool for session ${sessionId.take(8)}")
    }
}

// ─── Merged from CalendarOffloadHandler.kt ──────────────────────────────────────────────────

/**
 * android-calendar — list, create, update, delete events; query free/busy.
 *
 * Mirrors apple-calendar (NativeOffloads/CalendarOffload.m) — same flag
 * names and subcommands so prompts and the agent loop are platform-
 * agnostic. Aliases are preserved for backwards compatibility with older
 * prompts (--max ↔ --limit, --description ↔ --notes).
 *
 * Usage:
 *   android-calendar list [--today | --days N | --start S --end E]
 *                          [--limit N] [--calendar NAME]
 *   android-calendar create --title T --start S [--end E]
 *                          [--notes N] [--location L] [--all-day]
 *                          [--alarm <minutes>] [--calendar NAME | --calendar-id ID]
 *   android-calendar update --id <event_id> [--title ...] [--start ...] [--end ...]
 *                          [--all-day] [--notes ...] [--location ...] [--alarm <minutes>]
 *                          [--calendar NAME | --calendar-id ID]
 *   android-calendar delete --id <event_id>
 *   android-calendar freebusy --start <ISO> --end <ISO>
 *   android-calendar calendars                List writable calendars
 */
class CalendarOffloadHandler(private val context: Context) : NativeOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1), booleanFlags = setOf("today", "all-day"))
        // [T-offload-defaults-batch-android] Help only on explicit
        // --help/-h; no subcommand defaults to `list` (resolveDateRange
        // already defaults to the last-24h..now window with no flags).
        if (args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, HELP)
        }
        // T330: tri-state agent gate before any work.
        OffloadGate.enforce("calendar", "android-calendar", args, request)?.let { return it }

        return try {
            when (val sub = args.positional.firstOrNull() ?: "list") {
                "list" -> doList(args)
                "create" -> doCreate(args)
                "update" -> doUpdate(args)
                "delete" -> doDelete(args)
                "freebusy" -> doFreebusy(args)
                "calendars" -> doListCalendars(args)
                else -> NativeOffloadResult(2, "android-calendar: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            NativeOffloadResult(
                1,
                OffloadOutput.formatBody(JSONObject().put("error", "internal").put("message", e.message ?: "unknown").toString(), args) + "\n",
            )
        }
    }

    private fun hasRead() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED
    private fun hasWrite() = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED

    /**
     * Request one or more calendar permissions if not yet granted, blocking
     * the tool invocation until the user responds (or permanently denies).
     * Mirrors the location handler's pattern: system dialog → settings gate.
     * Returns null if permission ends up granted; otherwise a ready-to-return
     * error [NativeOffloadResult] describing why we gave up.
     */
    private fun ensurePermission(
        permissions: List<String>,
        satisfied: () -> Boolean,
        humanLabel: String,
        settingsId: String,
        args: OffloadArgs,
    ): NativeOffloadResult? {
        if (satisfied()) return null
        AppLogger.warning(TAG, "$humanLabel not granted — routing through permission flow")
        val result = runBlocking {
            var r = OffloadPermissionManager.requestAndroidPermission(permissions)
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED &&
                OffloadPermissionManager.pollForPermissionGrant(satisfied)
            ) {
                AppLogger.info(TAG, "Calendar $humanLabel permission granted during post-DENY poll")
                r = OffloadPermissionManager.AndroidPermissionResult.GRANTED
            }
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED) {
                r = OffloadPermissionManager.requestSettingsGate(
                    OffloadPermissionManager.SettingsGateRequest(
                        id = settingsId,
                        title = "Calendar permission needed",
                        message = "Minis needs $humanLabel permission to $humanLabel your calendar. Open Settings to allow it.",
                        settingsAction = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        requiresPackageUri = true,
                        positiveLabel = "Open Settings",
                    ),
                    check = { satisfied() },
                )
            }
            r
        }
        return when (result) {
            OffloadPermissionManager.AndroidPermissionResult.GRANTED -> null
            OffloadPermissionManager.AndroidPermissionResult.DENIED -> NativeOffloadResult(
                77,
                OffloadOutput.formatBody(
                    JSONObject()
                        .put("error", "permission_denied")
                        .put("message", "The user declined the $humanLabel permission.")
                        .toString(),
                    args,
                ) + "\n",
            )
            OffloadPermissionManager.AndroidPermissionResult.TIMEOUT -> NativeOffloadResult(
                77,
                OffloadOutput.formatBody(
                    JSONObject()
                        .put("error", "timeout")
                        .put("message", "Timed out waiting for the user to grant the $humanLabel permission.")
                        .toString(),
                    args,
                ) + "\n",
            )
        }
    }

    private fun doList(args: OffloadArgs): NativeOffloadResult {
        ensurePermission(
            permissions = listOf(Manifest.permission.READ_CALENDAR),
            satisfied = { hasRead() },
            humanLabel = "read",
            settingsId = Manifest.permission.READ_CALENDAR,
            args = args,
        )?.let { return it }

        val (startMs, endMs) = resolveDateRange(args)
            ?: return NativeOffloadResult(
                2,
                "android-calendar list: invalid or missing date range. Use --today, --days N, or --start/--end (ISO 8601 or YYYY-MM-DD).\n",
            )
        // --limit is the apple-calendar name; --max kept as alias.
        val limit = args.getInt("limit") ?: args.getInt("max") ?: DEFAULT_LIMIT
        val calFilter = args.get("calendar")

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME,
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        // [T-android-calendar-update-all-day] One day of slack each side: an
        // all-day event's DTSTART is a UTC midnight, which is up to a day away
        // from its local date. The rows are filtered on their LOCAL start below.
        val selArgs = arrayOf((startMs - DAY_MS).toString(), (endMs + DAY_MS).toString())
        val sort = "${CalendarContract.Events.DTSTART} ASC"

        val cursor = try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI, projection, selection, selArgs, sort,
            )
        } catch (e: SecurityException) {
            return NativeOffloadResult(77, providerBlockedJson("READ_CALENDAR", e, args))
        } catch (e: Throwable) {
            return NativeOffloadResult(1, providerErrorJson(e, args))
        } ?: return NativeOffloadResult(1, providerErrorJson(null, args))

        // Two-pass: gather all matches (so we can compute total_available
        // before truncating to --limit, matching apple-calendar's
        // _warning + total_available shape).
        val rows = ArrayList<Pair<Long, JSONObject>>()
        cursor.use {
            while (it.moveToNext()) {
                val calName = it.getString(8) ?: ""
                if (calFilter != null && !calName.contains(calFilter, ignoreCase = true)) continue
                // [T-android-calendar-update-all-day] All-day rows are stored as
                // UTC midnights; report and filter them as the local days they
                // are (00:00:00 .. 23:59:59), as create/update and iOS do.
                // Formatting the raw value printed 08:00 -> 08:00 next day in
                // UTC+8, and west of UTC filed the event under the day before.
                val allDay = it.getInt(6) == 1
                val rawEnd = if (it.isNull(3)) null else it.getLong(3)
                val (s0, e0) = if (allDay) allDayLocalRange(it.getLong(2), rawEnd)
                    else it.getLong(2) to (rawEnd ?: 0L)
                if (s0 < startMs || s0 > endMs) continue
                rows += s0 to
                    JSONObject()
                        .put("id", it.getLong(0))
                        .put("title", it.getString(1) ?: "")
                        .put("start", formatIso(s0))
                        .put("start_ms", s0)
                        .put("end", formatIso(e0))
                        .put("end_ms", e0)
                        .put("location", it.getString(4) ?: "")
                        .put("notes", it.getString(5) ?: "")
                        .put("description", it.getString(5) ?: "")
                        // iOS emits `is_all_day`; `all_day` kept as an alias so
                        // an existing script reading the old key still works.
                        .put("is_all_day", it.getInt(6) == 1)
                        .put("all_day", it.getInt(6) == 1)
                        .put("calendar_id", it.getLong(7))
                        .put("calendar", calName)
            }
        }
        // Sorted by LOCAL start (the SQL order is by raw DTSTART).
        val matches = JSONArray()
        rows.sortedBy { it.first }.forEach { matches.put(it.second) }

        val total = matches.length()
        val truncated = total > limit
        val events = JSONArray()
        for (i in 0 until minOf(total, limit)) events.put(matches.getJSONObject(i))

        if (events.length() == 0) {
            // Check whether the user actually has any writable calendar account —
            // if not, the "no events" answer is misleading. Report the real reason.
            val accounts = listWritableCalendars()
            if (accounts.length() == 0) {
                return NativeOffloadResult(
                    0,
                    OffloadOutput.formatBody(
                        JSONObject()
                            .put("events", JSONArray())
                            .put("warning", "calendar_no_account")
                            .put("message", "No calendar account is configured on this device. Ask the user to add a Google or Exchange account in system Settings → Accounts.")
                            .toString(),
                        args,
                    ) + "\n",
                )
            }
        }
        val data = JSONObject()
            .put("events", events)
            .put("range", JSONObject().put("start", formatIso(startMs)).put("end", formatIso(endMs)))
            .put("count", events.length())
        if (truncated) {
            data.put("_warning", "Results truncated by --limit. Returned $limit of $total total records. Use a larger --limit to retrieve more data.")
            data.put("total_available", total)
        }
        return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
    }

    private fun doCreate(args: OffloadArgs): NativeOffloadResult {
        ensurePermission(
            permissions = listOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR),
            satisfied = { hasWrite() },
            humanLabel = "write",
            settingsId = Manifest.permission.WRITE_CALENDAR,
            args = args,
        )?.let { return it }

        val title = args.get("title")
            ?: return NativeOffloadResult(2, "android-calendar create: --title is required\n")
        val startStr = args.get("start")
            ?: return NativeOffloadResult(2, "android-calendar create: --start is required\n")
        val startMs = parseDate(startStr)
            ?: return NativeOffloadResult(2, "android-calendar: invalid --start '$startStr'\n")
        // Reject a malformed --end outright rather than silently substituting
        // start+1h: the caller asked for a specific range, and quietly filing
        // an hour-long event is the same class of silent-wrong-answer as the
        // lenient parsing above.
        val endArg = args.get("end")
        val endParsed = endArg?.let { parseDate(it) }
        if (endArg != null && endParsed == null) {
            return NativeOffloadResult(2, "android-calendar: invalid --end '$endArg'\n")
        }
        // [T-android-calendar-all-day] All-day when asked explicitly, or when
        // BOTH bounds are bare dates — a date with no time cannot describe a
        // timed event, so treating "2026-12-01" as a zero-length event at
        // midnight was never what the caller meant. Matches iOS
        // CalendarOffload.m's rule exactly, including that a bare start with a
        // timed end stays a timed event.
        val allDay = args.hasFlag("all-day") ||
            (isDateOnly(startStr) && isDateOnly(endArg))
        val startMsFinal: Long
        val endMs: Long
        if (allDay) {
            val (s0, e0) = allDayBounds(startMs, endParsed)
            startMsFinal = s0
            endMs = e0
        } else {
            startMsFinal = startMs
            endMs = endParsed ?: (startMs + 60 * 60 * 1000L)
        }
        // Notes — apple-calendar uses --notes; --description kept as alias.
        val notes = args.get("notes") ?: args.get("description")
        val alarmMinutes = args.getInt("alarm")

        val target = resolveTarget(args, "create")
        if (target is Target.Failed) return target.result
        val calendarId = (target as? Target.Found)?.cal?.id
            ?: pickWritableCalendar()
            ?: return missingCalendarError(args)

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMsFinal)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            notes?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            args.get("location")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            if (allDay) {
                putAllDay(this, startMsFinal, endMs)
            }
        }

        return try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri == null) {
                NativeOffloadResult(1, OffloadOutput.formatBody(JSONObject().put("error", "insert_failed").put("message", "ContentResolver.insert returned null").toString(), args) + "\n")
            } else {
                val eventId = uri.lastPathSegment?.toLongOrNull()
                if (eventId != null && alarmMinutes != null && alarmMinutes >= 0) {
                    insertReminder(eventId, alarmMinutes)
                }
                AppLogger.info(TAG, "create: id=$eventId title='$title' alarm=$alarmMinutes")
                val data = JSONObject()
                    .put("id", eventId ?: -1L)
                    .put("title", title)
                    .put("start", formatIso(startMsFinal))
                    .put("end", formatIso(endMs))
                    .putCalendar((target as? Target.Found)?.cal ?: calendarById(calendarId))
                    // Same key `list` emits, so both shapes agree — iOS puts
                    // is_all_day on both too.
                    .put("is_all_day", allDay)
                if (notes != null) data.put("notes", notes)
                if (alarmMinutes != null && alarmMinutes >= 0) data.put("alarm_minutes_before", alarmMinutes)
                NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
            }
        } catch (e: SecurityException) {
            NativeOffloadResult(77, providerBlockedJson("WRITE_CALENDAR", e, args))
        } catch (e: Throwable) {
            NativeOffloadResult(1, providerErrorJson(e, args))
        }
    }

    // ── update ──────────────────────────────────────────────────────────

    private fun doUpdate(args: OffloadArgs): NativeOffloadResult {
        ensurePermission(
            permissions = listOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR),
            satisfied = { hasWrite() },
            humanLabel = "write",
            settingsId = Manifest.permission.WRITE_CALENDAR,
            args = args,
        )?.let { return it }

        val id = args.getLong("id")
            ?: return NativeOffloadResult(2, "android-calendar update: --id <event_id> is required\n")

        // Build a sparse ContentValues with only the fields the caller supplied.
        val values = ContentValues()
        args.get("title")?.let { values.put(CalendarContract.Events.TITLE, it) }
        val startStr = args.get("start")
        val endStr = args.get("end")
        val newStart = startStr?.let { s ->
            parseDate(s) ?: return NativeOffloadResult(2, "android-calendar update: invalid --start '$s'\n")
        }
        val newEnd = endStr?.let { e ->
            parseDate(e) ?: return NativeOffloadResult(2, "android-calendar update: invalid --end '$e'\n")
        }
        // [T-android-calendar-update-all-day] iOS parity (fe7a01bc5,
        // 86e91526e). Same rule create applies, over the arguments actually
        // PASSED: all-day when asked (--all-day) or when every date given is a
        // bare YYYY-MM-DD — inferring from an absent argument would turn a
        // timed event all-day on an unrelated --title edit. Back to timed is
        // --start/--end with a real time; no --not-all-day flag.
        val sawDate = startStr != null || endStr != null
        val allBare = sawDate &&
            (startStr == null || isDateOnly(startStr)) && (endStr == null || isDateOnly(endStr))
        val makeAllDay = args.hasFlag("all-day") || allBare
        val carriesTime = (startStr != null && !isDateOnly(startStr)) ||
            (endStr != null && !isDateOnly(endStr))
        if (makeAllDay || sawDate) {
            val cur = readTiming(id)
                ?: return NativeOffloadResult(1, "android-calendar update: no event with --id $id\n")
            val switching = makeAllDay != cur.allDay
            if (switching && cur.rrule != null) {
                return NativeOffloadResult(
                    2,
                    "android-calendar update: event $id repeats; switching a recurring event between all-day and timed is not supported\n",
                )
            }
            // The event's CURRENT range in local wall-clock terms, so a lone
            // --all-day (no dates) snaps the bounds the event already has, and a
            // lone --start keeps the end it has.
            val (curStart, curEnd) = if (cur.allDay) {
                allDayLocalRange(cur.dtStart, cur.dtEnd)
            } else {
                cur.dtStart to (cur.dtEnd ?: (cur.dtStart + HOUR_MS))
            }
            when {
                makeAllDay -> {
                    val (s0, e0) = allDayBounds(newStart ?: curStart, newEnd ?: curEnd)
                    putAllDay(values, s0, e0)
                }
                cur.allDay && carriesTime -> {
                    // Back to timed. The stored all-day range is UTC midnights,
                    // so every field moves together: flag off, local zone back,
                    // real times — a DTSTART write alone under ALL_DAY=1 would
                    // be snapped back to a midnight by the provider.
                    val s0 = newStart ?: curStart
                    var e0 = newEnd ?: curEnd
                    if (e0 <= s0) e0 = s0 + HOUR_MS
                    values.put(CalendarContract.Events.ALL_DAY, 0)
                    values.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                    values.put(CalendarContract.Events.DTSTART, s0)
                    values.put(CalendarContract.Events.DTEND, e0)
                }
                else -> {
                    newStart?.let { values.put(CalendarContract.Events.DTSTART, it) }
                    newEnd?.let { values.put(CalendarContract.Events.DTEND, it) }
                }
            }
        }
        args.get("location")?.let { values.put(CalendarContract.Events.EVENT_LOCATION, it) }
        (args.get("notes") ?: args.get("description"))?.let {
            values.put(CalendarContract.Events.DESCRIPTION, it)
        }
        // --calendar / --calendar-id: move the event. No flag = stay put; a
        // target that does not resolve refuses the whole update instead of
        // moving the event to the default calendar
        // ([T-android-calendar-exact-target]).
        when (val target = resolveTarget(args, "update")) {
            is Target.Failed -> return target.result
            is Target.Found -> values.put(CalendarContract.Events.CALENDAR_ID, target.cal.id)
            Target.NotRequested -> {}
        }
        val alarmMinutes = args.getInt("alarm")

        if (values.size() == 0 && alarmMinutes == null) {
            return NativeOffloadResult(2, "android-calendar update: nothing to update — supply at least one field flag\n")
        }

        return try {
            val updated = if (values.size() > 0) {
                val uri = CalendarContract.Events.CONTENT_URI.buildUpon()
                    .appendPath(id.toString()).build()
                context.contentResolver.update(uri, values, null, null)
            } else 0
            if (alarmMinutes != null && alarmMinutes >= 0) {
                replaceReminder(id, alarmMinutes)
            }
            AppLogger.info(TAG, "update: id=$id rows=$updated alarm=$alarmMinutes")
            val data = JSONObject()
                .put("id", id)
                .put("updated", updated > 0 || alarmMinutes != null)
                .put("rows", updated)
                .putCalendar(calendarOfEvent(id))
            // [T-android-calendar-update-all-day] Echo the event as stored, so
            // the caller sees whether it is all-day and the range it landed on
            // (all-day as local 00:00:00 - 23:59:59, the shape create and iOS
            // report).
            readTiming(id)?.let { t ->
                val (s0, e0) = if (t.allDay) allDayLocalRange(t.dtStart, t.dtEnd)
                    else t.dtStart to (t.dtEnd ?: t.dtStart)
                data.put("start", formatIso(s0)).put("end", formatIso(e0)).put("is_all_day", t.allDay)
            }
            if (alarmMinutes != null && alarmMinutes >= 0) data.put("alarm_minutes_before", alarmMinutes)
            NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
        } catch (e: SecurityException) {
            NativeOffloadResult(77, providerBlockedJson("WRITE_CALENDAR", e, args))
        } catch (e: Throwable) {
            NativeOffloadResult(1, providerErrorJson(e, args))
        }
    }

    // ── delete ──────────────────────────────────────────────────────────

    private fun doDelete(args: OffloadArgs): NativeOffloadResult {
        ensurePermission(
            permissions = listOf(Manifest.permission.WRITE_CALENDAR),
            satisfied = { hasWrite() },
            humanLabel = "write",
            settingsId = Manifest.permission.WRITE_CALENDAR,
            args = args,
        )?.let { return it }

        val id = args.getLong("id")
            ?: return NativeOffloadResult(2, "android-calendar delete: --id <event_id> is required\n")

        return try {
            val uri = CalendarContract.Events.CONTENT_URI.buildUpon()
                .appendPath(id.toString()).build()
            // Captured before the row is gone.
            val cal = calendarOfEvent(id)
            val rows = context.contentResolver.delete(uri, null, null)
            if (rows <= 0) {
                val body = JSONObject().put("error", "not_found")
                    .put("id", id)
                    .put("message", "No event with id=$id (already deleted, or not visible to this app).")
                    .toString()
                return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
            }
            AppLogger.info(TAG, "delete: id=$id rows=$rows")
            val data = JSONObject().put("id", id).put("deleted", true).put("rows", rows).putCalendar(cal)
            NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
        } catch (e: SecurityException) {
            NativeOffloadResult(77, providerBlockedJson("WRITE_CALENDAR", e, args))
        } catch (e: Throwable) {
            NativeOffloadResult(1, providerErrorJson(e, args))
        }
    }

    // ── freebusy ────────────────────────────────────────────────────────

    private fun doFreebusy(args: OffloadArgs): NativeOffloadResult {
        ensurePermission(
            permissions = listOf(Manifest.permission.READ_CALENDAR),
            satisfied = { hasRead() },
            humanLabel = "read",
            settingsId = Manifest.permission.READ_CALENDAR,
            args = args,
        )?.let { return it }

        val startStr = args.get("start") ?: return NativeOffloadResult(2, "android-calendar freebusy: --start is required\n")
        val endStr = args.get("end") ?: return NativeOffloadResult(2, "android-calendar freebusy: --end is required\n")
        val startMs = parseDate(startStr) ?: return NativeOffloadResult(2, "android-calendar: invalid --start '$startStr'\n")
        val endMs = parseDate(endStr) ?: return NativeOffloadResult(2, "android-calendar: invalid --end '$endStr'\n")
        if (endMs <= startMs) {
            return NativeOffloadResult(2, "android-calendar freebusy: --end must be after --start\n")
        }

        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
        )
        val selection = "${CalendarContract.Events.DTSTART} < ? AND ${CalendarContract.Events.DTEND} > ?"
        val selArgs = arrayOf(endMs.toString(), startMs.toString())
        val sort = "${CalendarContract.Events.DTSTART} ASC"

        val cursor = try {
            context.contentResolver.query(CalendarContract.Events.CONTENT_URI, projection, selection, selArgs, sort)
        } catch (e: SecurityException) {
            return NativeOffloadResult(77, providerBlockedJson("READ_CALENDAR", e, args))
        } catch (e: Throwable) {
            return NativeOffloadResult(1, providerErrorJson(e, args))
        } ?: return NativeOffloadResult(1, providerErrorJson(null, args))

        val busy = JSONArray()
        cursor.use {
            while (it.moveToNext()) {
                val isAllDay = it.getInt(3) == 1
                if (isAllDay) continue // skip all-day per apple-calendar
                busy.put(
                    JSONObject()
                        .put("title", it.getString(0) ?: "")
                        .put("start", formatIso(it.getLong(1)))
                        .put("end", formatIso(it.getLong(2))),
                )
            }
        }

        // Compute free slots in [startMs, endMs] from the busy list.
        // The busy list is already sorted by start; we still cap each busy
        // segment to the query window so an event that straddles the window
        // doesn't poison the cursor walk.
        val free = JSONArray()
        var cursorMs = startMs
        for (i in 0 until busy.length()) {
            val b = busy.getJSONObject(i)
            val bStart = parseDate(b.getString("start")) ?: continue
            val bEnd = parseDate(b.getString("end")) ?: continue
            val segStart = maxOf(bStart, startMs)
            val segEnd = minOf(bEnd, endMs)
            if (cursorMs < segStart) {
                free.put(JSONObject().put("start", formatIso(cursorMs)).put("end", formatIso(segStart)))
            }
            if (segEnd > cursorMs) cursorMs = segEnd
        }
        if (cursorMs < endMs) {
            free.put(JSONObject().put("start", formatIso(cursorMs)).put("end", formatIso(endMs)))
        }

        AppLogger.info(TAG, "freebusy: busy=${busy.length()} free=${free.length()}")
        val data = JSONObject().put("busy", busy).put("free", free)
        return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
    }

    // ── Reminders helpers (calendar alarms) ─────────────────────────────

    /**
     * Insert a single reminder row for [eventId] at [minutesBefore]. Mirrors
     * iOS EKAlarm.alarmWithRelativeOffset(-N * 60). Reminders.METHOD_ALERT
     * is the standard reminder type; ContentResolver.insert returns null on
     * provider failure, which we silently swallow — the event itself is
     * already created/updated.
     */
    private fun insertReminder(eventId: Long, minutesBefore: Int) {
        val values = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, minutesBefore)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        try {
            context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, values)
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "insertReminder failed for event=$eventId: ${e.message}")
        }
    }

    /** Remove existing reminders for [eventId] then insert one at [minutesBefore]. */
    private fun replaceReminder(eventId: Long, minutesBefore: Int) {
        try {
            context.contentResolver.delete(
                CalendarContract.Reminders.CONTENT_URI,
                "${CalendarContract.Reminders.EVENT_ID} = ?",
                arrayOf(eventId.toString()),
            )
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "replaceReminder delete failed for event=$eventId: ${e.message}")
        }
        insertReminder(eventId, minutesBefore)
    }

    private fun doListCalendars(args: OffloadArgs): NativeOffloadResult {
        ensurePermission(
            permissions = listOf(Manifest.permission.READ_CALENDAR),
            satisfied = { hasRead() },
            humanLabel = "read",
            settingsId = Manifest.permission.READ_CALENDAR,
            args = args,
        )?.let { return it }
        val arr = listWritableCalendars()
        return NativeOffloadResult(0, OffloadOutput.formatBody(arr.toString(2), args) + "\n")
    }

    // ── Range / calendar resolution ─────────────────────────────────────

    /**
     * Mirror apple-calendar resolve_date_range: --today (today only, [00:00,
     * +24h)) → --days N (last N days through now, with start at 00:00) →
     * --start/--end (explicit, defaults to last 24h on either side). Returns
     * null only when --start/--end are supplied but unparseable.
     */
    private fun resolveDateRange(args: OffloadArgs): Pair<Long, Long>? {
        val now = System.currentTimeMillis()
        if (args.hasFlag("today")) {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val s = cal.timeInMillis
            return s to (s + 24 * 60 * 60 * 1000L)
        }
        args.getInt("days")?.let { days ->
            val n = if (days <= 0) 7 else days
            val cal = Calendar.getInstance().apply {
                timeInMillis = now - n * 24 * 60 * 60 * 1000L
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            return cal.timeInMillis to now
        }
        val startStr = args.get("start")
        val endStr = args.get("end")
        val startMs = if (startStr != null) parseDate(startStr) ?: return null
                      else now - 24 * 60 * 60 * 1000L
        val endMs = if (endStr != null) parseDate(endStr) ?: return null
                    else now
        // EventStore predicates don't tolerate start > end on iOS; mirror that.
        return if (startMs > endMs) endMs to startMs else startMs to endMs
    }

    /**
     * [T-android-calendar-exact-target] The calendar a write targets, from
     * --calendar-id or --calendar. Port of iOS 8200eeb81 (issue #282).
     *
     * --calendar used to match by substring ("Work" could land in "Workout")
     * and, on a miss, silently fell back to the auto-picked calendar while
     * still reporting success - for `update` that meant MOVING the event to
     * the default calendar because of a typo. Now a name is an exact,
     * case-insensitive match against the writable calendars; a miss, or two
     * calendars with the same name, fails with every candidate listed; and
     * --calendar-id must name a writable calendar. The `list --calendar`
     * filter keeps substring matching - "show things in calendars containing
     * Work" is a legitimate query, as on iOS.
     */
    private sealed class Target {
        object NotRequested : Target()
        class Found(val cal: CalRef) : Target()
        class Failed(val result: NativeOffloadResult) : Target()
    }

    private fun resolveTarget(args: OffloadArgs, verb: String): Target {
        val idArg = args.get("calendar-id")
        val name = args.get("calendar")
        if (idArg == null && name == null) return Target.NotRequested
        val cals = writableCalendarRefs()
        val pick = if (idArg != null) {
            val id = idArg.trim().toLongOrNull()
                ?: return Target.Failed(targetError(args, verb, "invalid --calendar-id '$idArg' (expected a number from `android-calendar calendars`).", cals))
            pickCalendarById(id, cals)
        } else {
            pickCalendarByName(name!!, cals)
        }
        return when (pick) {
            is CalPick.Found -> Target.Found(pick.cal)
            is CalPick.Missing -> Target.Failed(targetError(args, verb, pick.message, cals))
            is CalPick.Ambiguous -> Target.Failed(targetError(args, verb, pick.message, pick.matches))
        }
    }

    private fun targetError(args: OffloadArgs, verb: String, message: String, candidates: List<CalRef>): NativeOffloadResult {
        val list = JSONArray()
        for (c in candidates) list.put(c.toJson())
        val body = JSONObject()
            .put("error", "invalid_args")
            .put("message", "android-calendar $verb: $message Nothing was written.")
            .put("candidates", list)
        AppLogger.warning(TAG, "$verb: calendar target refused — $message")
        return NativeOffloadResult(2, OffloadOutput.formatBody(body.toString(2), args) + "\n")
    }

    private fun writableCalendarRefs(): List<CalRef> {
        val arr = listWritableCalendars()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CalRef(o.optLong("id"), o.optString("display_name"), o.optString("account_name"))
        }
    }

    /** The calendar an event is in, for the write echo; null when unreadable. */
    /** What update needs to know about an event's current timing. */
    private data class EventTiming(val dtStart: Long, val dtEnd: Long?, val allDay: Boolean, val rrule: String?)

    private fun readTiming(eventId: Long): EventTiming? = try {
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI.buildUpon().appendPath(eventId.toString()).build(),
            arrayOf(
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.RRULE,
            ),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) null
            else EventTiming(
                dtStart = c.getLong(0),
                dtEnd = if (c.isNull(1)) null else c.getLong(1),
                allDay = c.getInt(2) == 1,
                rrule = c.getString(3)?.takeIf { it.isNotBlank() },
            )
        }
    } catch (e: SecurityException) {
        null
    }

    private fun calendarOfEvent(eventId: Long): CalRef? = try {
        val eventCalId = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI.buildUpon().appendPath(eventId.toString()).build(),
            arrayOf(CalendarContract.Events.CALENDAR_ID), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        eventCalId?.let { calendarById(it) }
    } catch (e: Throwable) {
        null
    }

    private fun calendarById(id: Long): CalRef? = try {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI.buildUpon().appendPath(id.toString()).build(),
            arrayOf(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) CalRef(id, c.getString(0) ?: "", c.getString(1) ?: "") else null }
    } catch (e: Throwable) {
        null
    }

    /**
     * [T-android-calendar-exact-target] Every write names WHERE it landed,
     * not only a title: calendar titles are not unique (the reporter of iOS
     * issue #282 had two calendars called "Work"). Mirrors iOS 381e59dc7's
     * calendar_id / calendar_source.
     */
    private fun JSONObject.putCalendar(cal: CalRef?): JSONObject {
        if (cal == null) return this
        put("calendar_id", cal.id)
        put("calendar", cal.name)
        put("calendar_source", cal.account)
        return this
    }

    private fun missingCalendarError(args: OffloadArgs): NativeOffloadResult =
        NativeOffloadResult(
            1,
            OffloadOutput.formatBody(
                JSONObject()
                    .put("error", "calendar_no_account")
                    .put("message", "No writable calendar found. Ask the user to add a Google, Exchange, or local calendar account, or pass --calendar <name> / --calendar-id <id>. Run `android-calendar calendars` to list available calendars.")
                    .toString(),
                args,
            ) + "\n",
        )

    /** ISO 8601 with offset (e.g. 2026-04-26T13:51:00+08:00). Matches
     *  apple-calendar noff_format_date so cross-platform consumers see
     *  identical strings. */
    private fun formatIso(ms: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        return sdf.format(java.util.Date(ms))
    }

    // ── Calendar account selection ───────────────────────────────────────

    /**
     * Enumerate writable calendars (CALENDAR_ACCESS_LEVEL >= 500 = OWNER/CONTRIBUTOR).
     * Used by `create` for pick-best and by `calendars` subcommand for debugging.
     */
    private fun listWritableCalendars(): JSONArray {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val arr = JSONArray()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
                arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    arr.put(
                        JSONObject()
                            .put("id", c.getLong(0))
                            .put("display_name", c.getString(1) ?: "")
                            .put("account_name", c.getString(2) ?: "")
                            .put("account_type", c.getString(3) ?: "")
                            .put("access_level", c.getInt(4))
                            .put("is_primary", c.getInt(5) == 1),
                    )
                }
            }
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "listWritableCalendars: ${e.message}")
        }
        return arr
    }

    /**
     * Pick the best calendar for new events:
     *   1. IS_PRIMARY=1 on a com.google account (avoids Samsung-Calendar-only inserts on One UI)
     *   2. Any com.google account
     *   3. Any IS_PRIMARY=1
     *   4. First writable calendar
     */
    private fun pickWritableCalendar(): Long? {
        val cals = listWritableCalendars()
        if (cals.length() == 0) return null
        fun score(obj: JSONObject): Int {
            var s = 0
            if (obj.optString("account_type") == "com.google") s += 100
            if (obj.optBoolean("is_primary")) s += 50
            // Prefer owner over contributor
            if (obj.optInt("access_level") >= CalendarContract.Calendars.CAL_ACCESS_OWNER) s += 10
            return s
        }
        var bestIdx = 0
        var bestScore = Int.MIN_VALUE
        for (i in 0 until cals.length()) {
            val s = score(cals.getJSONObject(i))
            if (s > bestScore) { bestScore = s; bestIdx = i }
        }
        return cals.getJSONObject(bestIdx).optLong("id", -1L).takeIf { it > 0 }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun providerBlockedJson(perm: String, e: SecurityException, args: OffloadArgs): String {
        val body = JSONObject().put("error", "provider_blocked")
            .put("message", "$perm is granted but the calendar provider refused the query (likely an OEM privacy layer like MIUI 'Other Permissions'). Ask the user to open app info and allow calendar access there. Underlying: ${e.message}")
            .toString()
        return OffloadOutput.formatBody(body, args) + "\n"
    }

    private fun providerErrorJson(e: Throwable?, args: OffloadArgs): String {
        val body = JSONObject().put("error", "provider_error")
            .put("message", e?.message ?: "Calendar provider failed or missing on this device.")
            .toString()
        return OffloadOutput.formatBody(body, args) + "\n"
    }


    companion object {
        /**
         * [T-android-calendar-all-day] True for a bare `YYYY-MM-DD` with no time.
         *
         * Positional rather than formatter-based, mirroring iOS
         * `noff_is_date_only_string`: a formatter is lenient about trailing text
         * and would also accept the date PREFIX of a datetime, which is exactly
         * the case this has to reject.
         */
        internal fun isDateOnly(s: String?): Boolean {
            val t = s?.trim() ?: return false
            if (t.length != 10) return false
            for (i in 0 until 10) {
                val c = t[i]
                if (i == 4 || i == 7) { if (c != '-') return false } else if (!c.isDigit()) return false
            }
            return parseDate(t) != null
        }

        /**
         * [T-android-calendar-all-day] Snap a range onto whole local days:
         * 00:00:00.000 of the start day through 23:59:59 of the end day.
         *
         * Mirrors iOS `noff_all_day_bounds`, including the two edge rules: an end
         * before the start collapses to a single day, and the last second is
         * computed as (next midnight - 1s) via calendar arithmetic so a 23h or 25h
         * DST day still lands on its own last second rather than an hour adrift.
         */
        private const val HOUR_MS = 60 * 60 * 1000L
        private const val DAY_MS = 24 * HOUR_MS

        /**
         * [T-android-calendar-update-all-day] Put an all-day range into
         * [values] in the form the calendar provider requires: ALL_DAY=1,
         * EVENT_TIMEZONE=UTC, DTSTART the UTC midnight of the first day and
         * DTEND the UTC midnight after the last day (exclusive). [localStart]
         * / [localEnd] are the local day bounds from [allDayBounds].
         *
         * Storing LOCAL midnight (what create did) is not a midnight in UTC:
         * in Asia/Shanghai it is 16:00Z of the previous day, which the
         * provider then snaps down to that previous day's midnight — the event
         * landed a day early. See allDayStoredRange.
         */
        internal fun putAllDay(values: ContentValues, localStart: Long, localEnd: Long) {
            val (s, e) = allDayStoredRange(localStart, localEnd)
            values.put(CalendarContract.Events.ALL_DAY, 1)
            values.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            values.put(CalendarContract.Events.DTSTART, s)
            values.put(CalendarContract.Events.DTEND, e)
        }

        /**
         * The provider's all-day storage for the LOCAL days containing
         * [localStartMs] .. [localEndMs]: UTC midnight of the first local date,
         * and UTC midnight of the day after the last (exclusive end). An end
         * before the start collapses to one day, as in [allDayBounds].
         */
        internal fun allDayStoredRange(
            localStartMs: Long,
            localEndMs: Long?,
            local: TimeZone = TimeZone.getDefault(),
        ): Pair<Long, Long> {
            fun utcMidnightOfLocalDay(ms: Long): Long {
                val l = Calendar.getInstance(local).apply { timeInMillis = ms }
                return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    clear()
                    set(l.get(Calendar.YEAR), l.get(Calendar.MONTH), l.get(Calendar.DAY_OF_MONTH))
                }.timeInMillis
            }
            val s = utcMidnightOfLocalDay(localStartMs)
            var e = utcMidnightOfLocalDay(localEndMs ?: localStartMs)
            if (e < s) e = s
            // UTC has no DST, so a day is always 24 h here.
            return s to (e + DAY_MS)
        }

        /**
         * Inverse of [allDayStoredRange]: a stored all-day range back to local
         * day bounds (00:00:00 of the first day .. 23:59:59 of the last), the
         * shape create's output and iOS report.
         */
        internal fun allDayLocalRange(
            dtStartUtc: Long,
            dtEndUtc: Long?,
            local: TimeZone = TimeZone.getDefault(),
        ): Pair<Long, Long> {
            fun localMidnightOfUtcDay(ms: Long): Long {
                val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
                return Calendar.getInstance(local).apply {
                    clear()
                    set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH))
                }.timeInMillis
            }
            val start = localMidnightOfUtcDay(dtStartUtc)
            val lastDayUtc = maxOf(dtStartUtc, (dtEndUtc ?: (dtStartUtc + DAY_MS)) - DAY_MS)
            val nextMidnight = Calendar.getInstance(local).apply {
                timeInMillis = localMidnightOfUtcDay(lastDayUtc)
                add(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis
            return start to (nextMidnight - 1000L)
        }

        internal fun allDayBounds(startMs: Long, endMs: Long?): Pair<Long, Long> {
            fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
                timeInMillis = ms
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val startDay = startOfDay(startMs)
            var endDay = startOfDay(endMs ?: startMs)
            if (endDay < startDay) endDay = startDay
            val nextMidnight = Calendar.getInstance().apply {
                timeInMillis = endDay
                add(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis
            return startDay to (nextMidnight - 1000L)
        }

        internal fun parseDate(s: String): Long? {
            // Relative offsets (apple-calendar parity): "-7d", "-2h", "+30m".
            val rel = Regex("""^([+-]?)(\d+)([dhm])$""").matchEntire(s.trim())
            if (rel != null) {
                val sign = if (rel.groupValues[1] == "-") -1L else 1L
                val n = rel.groupValues[2].toLong()
                val unitMs = when (rel.groupValues[3]) {
                    "d" -> 24 * 60 * 60 * 1000L
                    "h" -> 60 * 60 * 1000L
                    "m" -> 60 * 1000L
                    else -> return null
                }
                return System.currentTimeMillis() + sign * n * unitMs
            }
            val formats = listOf(
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm",
                "yyyy-MM-dd",
            )
            // [T-android-calendar-strict-date] isLenient=false + a full-length parse.
            //
            // SimpleDateFormat is lenient by default and rolls out-of-range fields
            // over instead of rejecting them: "2026-13-45" parsed as 2027-02-14 and
            // "…T99:99:99" as four days later at 04:40. Both then created a real
            // event, silently, at a time the caller never asked for — worse than an
            // error, because nothing surfaces. Lenient parsing also stops at the
            // first unparseable character, so a trailing-garbage string matched on
            // its prefix; ParsePosition makes the whole string load-bearing.
            // iOS gets this from NSISO8601DateFormatter, which is strict already.
            val trimmed = s.trim()
            for (f in formats) {
                try {
                    val sdf = SimpleDateFormat(f, Locale.US).apply {
                        isLenient = false
                        timeZone = if (f.endsWith("'Z'")) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
                    }
                    val pos = java.text.ParsePosition(0)
                    val parsed = sdf.parse(trimmed, pos)
                    if (parsed != null && pos.index == trimmed.length) return parsed.time
                } catch (_: Throwable) {}
            }
            return null
        }
        /** A writable calendar as the target matcher sees it. */
        internal data class CalRef(val id: Long, val name: String, val account: String) {
            fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("account", account)
        }

        internal sealed class CalPick {
            data class Found(val cal: CalRef) : CalPick()
            data class Missing(val message: String) : CalPick()
            data class Ambiguous(val message: String, val matches: List<CalRef>) : CalPick()
        }

        /** [T-android-calendar-exact-target] Exact, case-insensitive, trimmed. */
        internal fun pickCalendarByName(name: String, cals: List<CalRef>): CalPick {
            val want = name.trim()
            val matches = cals.filter { it.name.trim().equals(want, ignoreCase = true) }
            return when {
                matches.size == 1 -> CalPick.Found(matches.single())
                matches.isEmpty() -> CalPick.Missing(
                    "no writable calendar is named '$name' (exact match, case-insensitive). " +
                        "Pass one of the candidate names, or --calendar-id <id>.",
                )
                else -> CalPick.Ambiguous(
                    "${matches.size} writable calendars are named '$name'. " +
                        "Pass --calendar-id <id> with one of the candidates.",
                    matches,
                )
            }
        }

        internal fun pickCalendarById(id: Long, cals: List<CalRef>): CalPick =
            cals.firstOrNull { it.id == id }?.let { CalPick.Found(it) }
                ?: CalPick.Missing(
                    "no writable calendar has id $id. Pass one of the candidate ids, or --calendar <name>.",
                )

        private const val TAG = "CalendarOffload"
        private const val DEFAULT_LIMIT = 50
        private const val HELP = """android-calendar — list, create, update, delete events; query free/busy
                                    (mirrors apple-calendar)

Usage:
  android-calendar                  Same as `list` (default subcommand)
  android-calendar list [--today | --days N | --start S --end E]
                        [--limit N] [--calendar NAME]
  android-calendar create --title T --start S [--end E]
                         [--notes N] [--location L] [--all-day]
                         [--alarm <minutes>] [--calendar NAME | --calendar-id ID]
  android-calendar update --id <event_id> [--title ...] [--start ...] [--end ...]
                         [--all-day] [--notes ...] [--location ...] [--alarm <minutes>]
                         [--calendar NAME | --calendar-id ID]
      --all-day, or bare dates (YYYY-MM-DD), make it an all-day event; pass
      --start/--end with a time to make an all-day event timed again.
  android-calendar delete --id <event_id>
  android-calendar freebusy --start <ISO> --end <ISO>
  android-calendar calendars             List writable calendars (debugging)

On create/update, --calendar is an EXACT name (case-insensitive) and
--calendar-id must be a writable calendar's id; anything else fails with
the candidates listed and writes nothing. On list, --calendar filters by
substring. Writes echo calendar_id, calendar and calendar_source.

Dates accept ISO 8601 (YYYY-MM-DDThh:mm[:ss][Z|±HH:MM]) or relative
shorthand (-7d, -2h, +30m). YYYY-MM-DD is treated as midnight local.

Aliases for backwards compatibility:
  --max ↔ --limit
  --description ↔ --notes

Errors return JSON: {"error":"...","message":"..."}.
"""
    }
}

// ─── Merged from ConfigOffloadHandler.kt ──────────────────────────────────────────────────

/**
 * `minis-config` offload handler. Mirrors iOS `ConfigOffload.m`.
 *
 * Subcommands: list-topics, topic-help, get, set, set-batch,
 * audit-list, audit-get, audit-revert. The shell-friendly flags
 * `--filter`/`-f`, `--page`/`-p`, `--page-size`/`-s`, `--caption`,
 * `--actor`, `--session` follow exact iOS semantics.
 *
 * Collection-level writes:
 *   set models.append '{"provider_id":"…","model_id":"…", …}'
 *     → ModelsCollection.add(); creates a custom (is_custom=true) entry.
 *   set models.remove '"<entry-id>"'
 *     → ModelsCollection.remove(); rejected for non-custom entries.
 *
 * Future: per-entry hide via the existing
 *   set models.<entry_id>.isHidden true   (entry_id verbatim, dots included)
 * path. Already supported by ModelsCollection.isHiddenField; nothing
 * else needs to change in this handler when the agent picks it up.
 *
 * Exit codes:
 *   0    success
 *   1    invalid args / validation failed
 *   124  timeout
 *   125  user_rejected / all_rejected
 *   126  permission_denied
 */
class ConfigOffloadHandler : NativeOffloadHandler {
    private companion object {
        const val TAG = "ConfigOffload"
        const val EXIT_OK = 0
        const val EXIT_INVALID_ARGS = 1
        const val EXIT_TIMEOUT = 124
        const val EXIT_USER_REJECTED = 125
        const val EXIT_PERMISSION_DENIED = 126

        const val HELP_TEXT =
            "minis-config - read or change Minis app settings (logged + revertable)\n" +
                "\n" +
                "USAGE:\n" +
                "  minis-config <subcommand> [args]\n" +
                "\n" +
                "DISCOVERY:\n" +
                "  list-topics                  Show all configurable topics.\n" +
                "  topic-help <topic>           Show fields under one topic.\n" +
                "                               Besides providers / models / groups, topics\n" +
                "                               cover app preferences (appearance, chat,\n" +
                "                               background, speech, scheduled, updates,\n" +
                "                               backup), the agent's optional tools\n" +
                "                               (chat.tools.*, agentTools.userAsk.*) and the\n" +
                "                               sub agent roster (subagents).\n" +
                "\n" +
                "PATHS:\n" +
                "  <topic>.<field> or <topic>.<entry_id>.<field>. Use entry_id exactly as\n" +
                "  `get <topic>` prints it — dots and slashes are fine, no escaping, e.g.\n" +
                "    get models.<uuid>/glm-5.1.contextWindow\n" +
                "\n" +
                "FIELD I/O:\n" +
                "  get <path> [--filter <kw>] [--page N] [--page-size N]\n" +
                "                               Read one field. --filter (-f) does\n" +
                "                               case-insensitive AND matching on\n" +
                "                               whitespace-separated keywords against the\n" +
                "                               JSON form of each array element. --page\n" +
                "                               (-p, default 1) and --page-size (-s,\n" +
                "                               default 20, max 100) paginate array\n" +
                "                               results. Filter runs first; pagination\n" +
                "                               total reflects the post-filter count.\n" +
                "  set <path> <value-json>      Write one field. Triggers user confirm.\n" +
                "  set <path> --file <f>        Read value-json from file <f> instead of an\n" +
                "                               argv positional. Use this when the value\n" +
                "                               contains quotes / backslashes / newlines /\n" +
                "                               $ / backticks (e.g. soul.body) — it bypasses\n" +
                "                               shell escaping. Write the JSON to a temp file\n" +
                "                               first, then: set <path> --file /tmp/v.json\n" +
                "  set <path>.append <elem>     Append one element to an array-typed field,\n" +
                "                               or — when <path> is a collection (e.g.\n" +
                "                               `models`) — add a new custom child. Payload\n" +
                "                               for collections is a JSON object, e.g.\n" +
                "                                 set models.append '{\"provider_id\":\"<uuid>\",\n" +
                "                                   \"model_id\":\"gpt-foo\",\"display_name\":\"GPT Foo\"}'\n" +
                "  set <path>.remove <elem>     Drop every occurrence of <elem> from an\n" +
                "                               array-typed field, or — for collections —\n" +
                "                               remove a custom child by its entry id, e.g.\n" +
                "                                 set models.remove '\"<entry-uuid>\"'\n" +
                "                               Built-in / API-reported entries cannot be\n" +
                "                               removed (use `set models.<entryId>.isHidden\n" +
                "                               true` to hide instead).\n" +
                "  add <topic> <value-json>     Create a new child in an addable collection\n" +
                "                               (e.g. providers, groups, subagents). The app generates\n" +
                "                               its UUID (returned in applied[0].new). Same\n" +
                "                               as `set <topic>.append <value-json>`. Supports\n" +
                "                               --file like `set`. e.g.\n" +
                "                               add groups '{\"name\":\"Light\",\"entries\":[...]}'\n" +
                "                               add subagents '{\"name\":\"Translator\",\n" +
                "                                 \"description\":\"Use for zh/en translation.\"}'\n" +
                "  set-batch                    Read JSON array of {path,value_json} from\n" +
                "                               stdin. One confirm dialog for the batch.\n" +
                "                               Each item may use the .append/.remove suffix.\n" +
                "\n" +
                "AUDIT:\n" +
                "  audit-list [--limit N] [--scope <topic>]\n" +
                "  audit-get  <audit-id>\n" +
                "  audit-revert <audit-id>      Roll back a previous applied entry.\n" +
                "\n" +
                "FLAGS:\n" +
                "  --session <id>               Tag the audit row with the active session.\n" +
                "  --actor agent|user|...       Override audit actor (default: agent).\n" +
                "  --caption <text>             Caption shown above the confirm dialog.\n" +
                "  --help, -h                   Show this help.\n" +
                "\n" +
                "EXIT CODES:\n" +
                "  0    success\n" +
                "  1    invalid args / validation failed\n" +
                "  124  confirmation timed out (30 s)\n" +
                "  125  user rejected\n" +
                "  126  permission denied (master switch off, or hidden field)\n" +
                "\n" +
                "Every write requires user confirmation in-app. The response includes\n" +
                "a `user_message` field — relay it to the user so they know how to\n" +
                "review or revert via Logs → Config Changes.\n"
    }

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        // argv[0] is the program name; subcommand and options follow.
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) {
            return NativeOffloadResult(EXIT_OK, OffloadOutput.formatBody(HELP_TEXT, args) + "\n")
        }

        val sub = args.positional.firstOrNull() ?: return cmdListTopics(args)
        return try {
            when (sub) {
                "list-topics" -> cmdListTopics(args)
                "topic-help" -> cmdTopicHelp(args)
                "get" -> cmdGet(args)
                "set" -> cmdSet(args)
                "add" -> cmdAdd(args)
                "set-batch" -> cmdSetBatch(args, request)
                "audit-list" -> cmdAuditList(args)
                "audit-get" -> cmdAuditGet(args)
                "audit-revert" -> cmdAuditRevert(args)
                else -> errorResult(
                    args, EXIT_INVALID_ARGS,
                    "INVALID_ARGS",
                    "Unknown subcommand '$sub'. Use --help.",
                    appendHelp = true,
                )
            }
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            errorResult(args, EXIT_INVALID_ARGS, "INTERNAL", e.message ?: "unknown error")
        }
    }

    // -- Subcommands --

    private fun cmdListTopics(args: OffloadArgs): NativeOffloadResult {
        if (!ConfigBridge.isEnabled()) {
            return envelopeResult(args, ConfigBridge.disabledErrorEnvelope())
        }
        val out = JSONObject().apply {
            put("ok", true)
            put("tool", "minis-config")
            put("subcommand", "list-topics")
            put("data", JSONObject().apply { put("topics", ConfigBridge.allTopics()) })
        }
        return envelopeResult(args, out)
    }

    private fun cmdTopicHelp(args: OffloadArgs): NativeOffloadResult {
        val topic = args.positional.getOrNull(1)
        if (topic.isNullOrEmpty()) {
            return errorResult(
                args, EXIT_INVALID_ARGS,
                "INVALID_ARGS",
                "topic-help <topic> requires a topic name. Use list-topics to see available topics."
            )
        }
        if (!ConfigBridge.isEnabled()) {
            return envelopeResult(args, ConfigBridge.disabledErrorEnvelope())
        }
        val out = JSONObject().apply {
            put("ok", true)
            put("tool", "minis-config")
            put("subcommand", "topic-help")
            put("data", JSONObject().apply {
                put("topic", topic)
                put("fields", ConfigBridge.fieldsForTopic(topic))
                // [T-config-path-dotted-id] Collections say how to spell a child path.
                ConfigBridge.pathNoteForTopic(topic)?.let { put("path_note", it) }
            })
        }
        return envelopeResult(args, out)
    }

    private fun cmdGet(args: OffloadArgs): NativeOffloadResult {
        val path = args.positional.getOrNull(1) ?: return errorResult(
            args, EXIT_INVALID_ARGS, "INVALID_ARGS",
            "get <path> requires a field path."
        )
        val filter = args.get("filter", "f")
        val page = args.getInt("page", "p")?.coerceAtLeast(0) ?: 0
        val pageSize = args.getInt("page-size", "s")?.coerceAtLeast(0) ?: 0
        val envelope = ConfigBridge.readField(path = path, filter = filter, page = page, pageSize = pageSize)
        return envelopeResult(args, envelope)
    }

    private fun cmdSet(args: OffloadArgs): NativeOffloadResult {
        val path = args.positional.getOrNull(1)
        // [T-android-minis-config-set-shell-escape] (issue #36) `--file <path>`
        // reads the value-json from a file instead of an argv positional. The
        // value normally rides through the guest shell as a positional arg, so
        // busybox ash mangles embedded double-quotes / backslashes / newlines /
        // $ / backticks before this handler sees them — breaking large free-text
        // writes like soul.body. Reading from a file bypasses shell escaping
        // entirely. Mirrors iOS 5dcff277.
        val fileArg = args.get("file")
        val valueJSON = if (fileArg != null) {
            readLinuxPath(fileArg) ?: return errorResult(
                args, EXIT_INVALID_ARGS, "INVALID_ARGS",
                "--file: could not read value from '$fileArg'."
            )
        } else {
            args.positional.getOrNull(2)
        }
        if (path == null || valueJSON == null) {
            return errorResult(
                args, EXIT_INVALID_ARGS, "INVALID_ARGS",
                "set <path> <value-json> requires both arguments " +
                    "(or use: set <path> --file <path-to-value-json>)."
            )
        }
        val caption = args.get("caption")
        val actor = args.get("actor") ?: "agent"
        val sessionId = args.get("session")
        val items = JSONArray().put(JSONObject().apply {
            put("path", path)
            put("value_json", valueJSON)
        })
        val envelope = ConfigBridge.writeFields(items, caption, actor, sessionId)
        return envelopeResult(args, envelope)
    }

    /**
     * `add <topic> <value-json>` — create a new child in an addable collection
     * (e.g. `add providers …`, `add groups …`). Sugar over `set
     * <topic>.append <value-json>`: it reuses the exact same collection-add
     * path in the bridge, so the confirm dialog, audit row, redaction, exit
     * codes, and the new child's auto-generated UUID (returned in
     * applied[0].new) are all identical. Mirrors iOS `cmd_add`. (The bridge's
     * collection-add suffix is `.append` on Android.)
     */
    private fun cmdAdd(args: OffloadArgs): NativeOffloadResult {
        val topic = args.positional.getOrNull(1)
        val fileArg = args.get("file")
        val valueJSON = if (fileArg != null) {
            readLinuxPath(fileArg) ?: return errorResult(
                args, EXIT_INVALID_ARGS, "INVALID_ARGS",
                "--file: could not read value from '$fileArg'."
            )
        } else {
            args.positional.getOrNull(2)
        }
        if (topic == null || valueJSON == null) {
            return errorResult(
                args, EXIT_INVALID_ARGS, "INVALID_ARGS",
                "add <topic> <value-json> requires both arguments " +
                    "(or use: add <topic> --file <path-to-value-json>). " +
                    "Example: add groups '{\"name\":\"Light\",\"entries\":[...]}'."
            )
        }
        val caption = args.get("caption")
        val actor = args.get("actor") ?: "agent"
        val sessionId = args.get("session")
        val items = JSONArray().put(JSONObject().apply {
            put("path", "$topic.append")
            put("value_json", valueJSON)
        })
        val envelope = ConfigBridge.writeFields(items, caption, actor, sessionId)
        return envelopeResult(args, envelope)
    }

    private fun cmdSetBatch(args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        // Batch JSON is passed positionally on Android — there is no
        // stdin pipe on the native_offload protocol. Support either:
        //   set-batch <json-array>      (shell can pass literal)
        //   set-batch --batch <json>    (env-friendly form)
        // The shape is the same as iOS stdin: array of
        // {path, value_json}.
        val raw = args.positional.getOrNull(1) ?: args.get("batch") ?: return errorResult(
            args, EXIT_INVALID_ARGS, "INVALID_ARGS",
            "set-batch expects a JSON array argument with elements {\"path\":..., \"value_json\":...}."
        )
        val parsed = try {
            JSONArray(raw)
        } catch (_: Throwable) {
            return errorResult(
                args, EXIT_INVALID_ARGS, "INVALID_ARGS",
                "set-batch payload is not a JSON array."
            )
        }
        if (parsed.length() == 0) {
            return errorResult(args, EXIT_INVALID_ARGS, "INVALID_ARGS", "Empty batch.")
        }
        if (parsed.length() > 50) {
            return errorResult(args, EXIT_INVALID_ARGS, "INVALID_ARGS", "Batch capped at 50 items.")
        }
        val caption = args.get("caption")
        val actor = args.get("actor") ?: "agent"
        val sessionId = args.get("session")
        val envelope = ConfigBridge.writeFields(parsed, caption, actor, sessionId)
        return envelopeResult(args, envelope)
    }

    private fun cmdAuditList(args: OffloadArgs): NativeOffloadResult {
        val limit = args.getInt("limit")?.coerceIn(1, 1000) ?: 100
        val scope = args.get("scope")
        val envelope = ConfigBridge.auditList(limit, scope)
        return envelopeResult(args, envelope)
    }

    private fun cmdAuditGet(args: OffloadArgs): NativeOffloadResult {
        val id = args.positional.getOrNull(1) ?: return errorResult(
            args, EXIT_INVALID_ARGS, "INVALID_ARGS",
            "audit-get <audit-id> requires an id."
        )
        val envelope = ConfigBridge.auditGet(id)
        return envelopeResult(args, envelope)
    }

    private fun cmdAuditRevert(args: OffloadArgs): NativeOffloadResult {
        val id = args.positional.getOrNull(1) ?: return errorResult(
            args, EXIT_INVALID_ARGS, "INVALID_ARGS",
            "audit-revert <audit-id> requires an id."
        )
        val actor = args.get("actor") ?: "agent-revert"
        val sessionId = args.get("session")
        val envelope = ConfigBridge.auditRevert(id, actor, sessionId, skipConfirmation = false)
        return envelopeResult(args, envelope)
    }

    // -- helpers --

    private fun envelopeResult(args: OffloadArgs, envelope: JSONObject): NativeOffloadResult {
        val exit = exitCodeFromEnvelope(envelope)
        val body = OffloadOutput.formatBody(envelope.toString(2), args) + "\n"
        return NativeOffloadResult(exit, body)
    }

    private fun exitCodeFromEnvelope(envelope: JSONObject): Int {
        if (envelope.optBoolean("ok", false)) return EXIT_OK
        return when (envelope.optString("error")) {
            "permission_denied" -> EXIT_PERMISSION_DENIED
            "timeout" -> EXIT_TIMEOUT
            "user_rejected", "all_rejected" -> EXIT_USER_REJECTED
            // already_exists is a structural validation failure: map to
            // the generic invalid-args exit so the agent can still
            // distinguish via the JSON `error` field. Mirrors iOS.
            "already_exists" -> EXIT_INVALID_ARGS
            else -> EXIT_INVALID_ARGS
        }
    }

    private fun errorResult(
        args: OffloadArgs,
        exitCode: Int,
        code: String,
        reason: String,
        appendHelp: Boolean = false,
    ): NativeOffloadResult {
        val obj = JSONObject().apply {
            put("ok", false)
            put("error", code.lowercase())
            put("reason", reason)
        }
        val body = OffloadOutput.formatBody(obj.toString(2), args) + "\n" +
            (if (appendHelp) HELP_TEXT else "")
        return NativeOffloadResult(exitCode, body)
    }

    /**
     * [T-android-minis-config-set-shell-escape] Read a file's text by its
     * guest (Linux) path, resolving it to the host path through the rootfs /
     * bind mounts. Same helper shape as ModelUseOffloadHandler.readLinuxPath —
     * used by `set --file` so the value-json never transits the shell. Returns
     * null when the path can't be resolved, doesn't exist, or read fails.
     */
    private fun readLinuxPath(linuxPath: String): String? {
        val hostFile: File = PRootKernel.resolveHostPath(linuxPath) ?: return null
        if (!hostFile.exists() || !hostFile.isFile) return null
        return try { hostFile.readText() } catch (_: Throwable) { null }
    }
}

// ─── Merged from ContactsOffloadHandler.kt ──────────────────────────────────────────────────

/**
 * Host-side implementation of `android-contacts` — reads + writes the device
 * contacts via the ContactsContract ContentProvider.
 *
 * Usage:
 *   android-contacts list [--max N]
 *   android-contacts search <query> [--max N]
 *   android-contacts get <id>
 *   android-contacts delete <id>
 *   android-contacts --help
 */
class ContactsOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val rawArgs = request.argv.drop(1)
        if (rawArgs.isEmpty() || rawArgs[0] == "-h" || rawArgs[0] == "--help") {
            return NativeOffloadResult(0, HELP_TEXT)
        }
        // T54: parse for shared --compact / -q / --quiet handling. Subcommand
        // dispatch still uses the raw `rawArgs` because Contacts encodes
        // positional args (id, query) in subcommand-specific positions and
        // OffloadArgs strips flag/positional ordering away.
        val parsedArgs = OffloadArgs(rawArgs)

        // T330: tri-state agent gate before any work or system permission ask.
        OffloadGate.enforce("contacts", "android-contacts", parsedArgs, request)?.let { return it }

        // delete needs WRITE_CONTACTS in addition to READ; the read-only path
        // is fine with READ_CONTACTS alone.
        val needsWrite = rawArgs[0] == "delete"
        ensurePermission(needsWrite = needsWrite, args = parsedArgs)?.let { return it }

        return try {
            when (val sub = rawArgs[0]) {
                "list" -> doList(rawArgs.drop(1), parsedArgs)
                "search" -> doSearch(rawArgs.drop(1), parsedArgs)
                "get" -> doGet(rawArgs.drop(1), parsedArgs)
                "delete" -> doDelete(rawArgs.drop(1), parsedArgs)
                else -> NativeOffloadResult(2, "android-contacts: unknown subcommand '$sub'\n$HELP_TEXT")
            }
        } catch (e: SecurityException) {
            AppLogger.warning(TAG, "SecurityException from ContactsProvider2: ${e.message}")
            val body = org.json.JSONObject()
                .put("error", "contacts_blocked_by_oem")
                .put("message", "READ_CONTACTS is granted but the contacts provider refused the query. On MIUI/HyperOS this usually means the user needs to enable Contacts access in the per-app 'Other Permissions' / 'Privacy' panel; on ColorOS/OxygenOS check Phone Manager → Privacy Permissions. Underlying: ${e.message}")
                .toString()
            NativeOffloadResult(77, OffloadOutput.formatBody(body, parsedArgs) + "\n")
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            val body = org.json.JSONObject()
                .put("error", "contacts_provider_error")
                .put("message", e.message ?: "unknown contacts provider error")
                .toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, parsedArgs) + "\n")
        }
    }

    private fun hasPermission(needsWrite: Boolean = false): Boolean {
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (!needsWrite) return read
        val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        return read && write
    }

    /**
     * Drive the same system-dialog → settings-gate flow as location/calendar.
     * Returns null if permission ends up granted, otherwise a ready-to-return
     * error result describing why we gave up. When [needsWrite] is true the
     * permission set additionally includes WRITE_CONTACTS — required by the
     * `delete` subcommand.
     */
    private fun ensurePermission(needsWrite: Boolean = false, args: OffloadArgs): NativeOffloadResult? {
        if (hasPermission(needsWrite)) return null
        val perms = if (needsWrite) {
            listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        } else {
            listOf(Manifest.permission.READ_CONTACTS)
        }
        AppLogger.warning(TAG, "${perms.joinToString("+")} not granted — routing through permission flow")
        val result = runBlocking {
            var r = OffloadPermissionManager.requestAndroidPermission(perms)
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED &&
                OffloadPermissionManager.pollForPermissionGrant({ hasPermission(needsWrite) })
            ) {
                AppLogger.info(TAG, "Contacts permission granted during post-DENY poll")
                r = OffloadPermissionManager.AndroidPermissionResult.GRANTED
            }
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED) {
                r = OffloadPermissionManager.requestSettingsGate(
                    OffloadPermissionManager.SettingsGateRequest(
                        id = if (needsWrite) "CONTACTS_RW" else Manifest.permission.READ_CONTACTS,
                        title = "Contacts permission needed",
                        message = if (needsWrite) {
                            "Minis needs read + write contacts permission to delete entries. Open Settings to allow it."
                        } else {
                            "Minis needs contacts permission to read your address book. Open Settings to allow it."
                        },
                        settingsAction = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        requiresPackageUri = true,
                        positiveLabel = "Open Settings",
                    ),
                    check = { hasPermission(needsWrite) },
                )
            }
            r
        }
        return when (result) {
            OffloadPermissionManager.AndroidPermissionResult.GRANTED -> null
            OffloadPermissionManager.AndroidPermissionResult.DENIED -> NativeOffloadResult(
                77,
                OffloadOutput.formatBody(
                    JSONObject().put("error", "permission_denied")
                        .put("message", "The user declined the contacts permission.")
                        .toString(),
                    args,
                ) + "\n",
            )
            OffloadPermissionManager.AndroidPermissionResult.TIMEOUT -> NativeOffloadResult(
                77,
                OffloadOutput.formatBody(
                    JSONObject().put("error", "timeout")
                        .put("message", "Timed out waiting for the user to grant the contacts permission.")
                        .toString(),
                    args,
                ) + "\n",
            )
        }
    }

    private fun doList(args: List<String>, parsedArgs: OffloadArgs): NativeOffloadResult {
        val max = parseMax(args, default = 50)
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.HAS_PHONE_NUMBER,
        )
        val sort = "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
        val cursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI, projection, null, null, sort,
        ) ?: return NativeOffloadResult(1, OffloadOutput.formatBody("android-contacts: failed to query contacts", parsedArgs) + "\n")

        val arr = JSONArray()
        cursor.use {
            var n = 0
            while (it.moveToNext() && n < max) {
                arr.put(readContactRow(it))
                n++
            }
        }
        return NativeOffloadResult(0, OffloadOutput.formatBody(arr.toString(2), parsedArgs) + "\n")
    }

    private fun doSearch(args: List<String>, parsedArgs: OffloadArgs): NativeOffloadResult {
        val query = args.firstOrNull { !it.startsWith("-") }
            ?: return NativeOffloadResult(2, "android-contacts search: missing <query>\n")
        val max = parseMax(args, default = 20)

        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.HAS_PHONE_NUMBER,
        )
        val selection = "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?"
        val selectionArgs = arrayOf("%$query%")
        val sort = "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
        val cursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI, projection, selection, selectionArgs, sort,
        ) ?: return NativeOffloadResult(1, OffloadOutput.formatBody("android-contacts: failed to query contacts", parsedArgs) + "\n")

        val arr = JSONArray()
        cursor.use {
            var n = 0
            while (it.moveToNext() && n < max) {
                arr.put(readContactRow(it))
                n++
            }
        }
        if (arr.length() == 0) {
            return NativeOffloadResult(0, OffloadOutput.formatBody("No contacts found matching '$query'.", parsedArgs) + "\n")
        }
        return NativeOffloadResult(0, OffloadOutput.formatBody(arr.toString(2), parsedArgs) + "\n")
    }

    private fun doGet(args: List<String>, parsedArgs: OffloadArgs): NativeOffloadResult {
        val id = args.firstOrNull()?.toLongOrNull()
            ?: return NativeOffloadResult(2, "android-contacts get: missing or invalid <id>\n")

        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.HAS_PHONE_NUMBER,
        )
        val cursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            "${ContactsContract.Contacts._ID} = ?",
            arrayOf(id.toString()),
            null,
        ) ?: return NativeOffloadResult(1, OffloadOutput.formatBody("android-contacts: failed to query contact", parsedArgs) + "\n")

        cursor.use {
            if (!it.moveToFirst()) {
                return NativeOffloadResult(1, OffloadOutput.formatBody("android-contacts: no contact with id=$id", parsedArgs) + "\n")
            }
            val obj = readContactRow(it)
            return NativeOffloadResult(0, OffloadOutput.formatBody(obj.toString(2), parsedArgs) + "\n")
        }
    }

    /**
     * Delete a single contact by aggregated `Contacts._ID`. We first try the
     * aggregated `Contacts.CONTENT_URI` path which cascades raw rows on most
     * accounts; when that returns 0 we fall back to deleting every
     * `RawContacts` row whose `CONTACT_ID` matches — this catches stub rows
     * that the aggregated URI silently refuses (commonly orphaned raw rows
     * left by sync adapters or test inserts).
     */
    private fun doDelete(args: List<String>, parsedArgs: OffloadArgs): NativeOffloadResult {
        val id = args.firstOrNull()?.toLongOrNull()
            ?: return NativeOffloadResult(2, "android-contacts delete: missing or invalid <id>\n")

        var deleted = context.contentResolver.delete(
            ContactsContract.Contacts.CONTENT_URI,
            "${ContactsContract.Contacts._ID} = ?",
            arrayOf(id.toString()),
        )
        if (deleted <= 0) {
            // Fallback: delete raw_contacts rows linked to this aggregated id.
            // We don't have the account credentials needed for the
            // CALLER_IS_SYNCADAPTER hard-delete dance, so this leaves a
            // tombstone that real sync adapters will eventually purge — fine
            // for our use case (the contact disappears from Contacts immediately).
            deleted = context.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                arrayOf(id.toString()),
            )
        }
        if (deleted <= 0) {
            val body = JSONObject().put("error", "not_found")
                .put("id", id)
                .put("message", "No contact with id=$id (already deleted, or the OS hides it).")
                .toString()
            return NativeOffloadResult(1, OffloadOutput.formatBody(body, parsedArgs) + "\n")
        }
        val body = JSONObject().put("id", id).put("deleted", deleted).toString(2)
        return NativeOffloadResult(0, OffloadOutput.formatBody(body, parsedArgs) + "\n")
    }

    private fun readContactRow(c: android.database.Cursor): JSONObject {
        val id = c.getLong(0)
        val name = c.getString(1) ?: ""
        val hasPhone = c.getInt(2) > 0
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("name", name)
        if (hasPhone) {
            val phones = readPhones(id)
            if (phones.length() > 0) obj.put("phones", phones)
        }
        val emails = readEmails(id)
        if (emails.length() > 0) obj.put("emails", emails)
        return obj
    }

    private fun readPhones(contactId: Long): JSONArray {
        val arr = JSONArray()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use {
            while (it.moveToNext()) arr.put(it.getString(0) ?: "")
        }
        return arr
    }

    private fun readEmails(contactId: Long): JSONArray {
        val arr = JSONArray()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use {
            while (it.moveToNext()) arr.put(it.getString(0) ?: "")
        }
        return arr
    }

    private fun parseMax(args: List<String>, default: Int): Int {
        val i = args.indexOfFirst { it == "--max" || it == "-n" }
        if (i < 0 || i + 1 >= args.size) return default
        return args[i + 1].toIntOrNull() ?: default
    }

    companion object {
        private const val TAG = "ContactsOffload"
        private const val HELP_TEXT = """android-contacts — read + delete device contacts (requires READ_CONTACTS, plus WRITE_CONTACTS for delete)

Usage:
  android-contacts list [--max N]         List contacts (default 50)
  android-contacts search <query> [--max N]  Search by name (default 20)
  android-contacts get <id>               Get a single contact by id
  android-contacts delete <id>            Delete a contact by id (PERMANENT — cascades all raw rows)
  android-contacts --help                 Show this help

Output is JSON. Each contact has: id, name, phones[], emails[].
"""
    }
}

// ─── Merged from DebugOffloadHandler.kt ──────────────────────────────────────────────────

/**
 * minis-debug — DEBUG-ONLY CLI wrapper for the local DebugServer JSON-RPC
 * endpoint at 127.0.0.1:5321.
 *
 * Lets a user (or agent) in the PRoot shell drive the in-app debug RPC
 * without hand-rolling curl + JSON. Each subcommand maps to one RPC
 * method registered in DebugMethodRegistry; the response envelope from
 * the server is forwarded verbatim through OffloadOutput so the standard
 * `--compact` / `-q` / `--quiet` flags work the same way as every other
 * `android-*` / `minis-*` offload.
 *
 * Registration is guarded by `BuildConfig.DEBUG` in MinisApp.kt so the
 * Release APK contains no trace of this command.
 */
class DebugOffloadHandler(@Suppress("UNUSED_PARAMETER") context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1), booleanFlags = BOOLEAN_FLAGS)
        if (request.argv.size <= 1 || args.hasFlag("h", "help") && args.positional.isEmpty()) {
            return NativeOffloadResult(if (request.argv.size <= 1) 2 else 0, HELP)
        }

        // Per-subcommand --help: `minis-debug ls --help` prints the same
        // top-level help so the user discovers the full surface either
        // way. Cheaper than per-subcommand help strings; the help text
        // already enumerates every subcommand's flags.
        if (args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, HELP)
        }

        val sub = args.positional.getOrNull(0)
            ?: return NativeOffloadResult(2, HELP)

        val rpc: RpcCall = try {
            buildRpcCall(sub, args)
        } catch (e: BadArgsException) {
            return NativeOffloadResult(2, "minis-debug $sub: ${e.message}\n")
        }

        return try {
            val response = postJsonRpc(rpc.method, rpc.params)
            // DebugServer returns a JSON-RPC 2.0 envelope: {jsonrpc, id, result|error}.
            // We pass it through formatBody so --compact / -q work; -q strips to
            // the iOS-style {ok, data, error} envelope when present, otherwise
            // returns the raw object. Exit code reflects RPC error.
            val obj = try { JSONObject(response) } catch (_: Exception) { null }
            val isError = obj?.has("error") == true
            NativeOffloadResult(
                if (isError) 1 else 0,
                OffloadOutput.formatBody(response, args) + "\n",
            )
        } catch (e: ConnectionRefused) {
            val body = JSONObject()
                .put("error", "debug_server_unreachable")
                .put("message", "Debug server not running on 127.0.0.1:5321. Is this a debug build?")
                .toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        } catch (e: SocketTimeoutException) {
            val body = JSONObject()
                .put("error", "debug_server_timeout")
                .put("message", "Debug server did not respond within ${TIMEOUT_MS / 1000}s.")
                .toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        } catch (e: Throwable) {
            Log.w(TAG, "uncaught: ${e.message}", e)
            val body = JSONObject()
                .put("error", "internal")
                .put("message", e.message ?: e.javaClass.simpleName)
                .toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    // ── Subcommand → JSON-RPC mapping ────────────────────────────────────────

    private data class RpcCall(val method: String, val params: JSONObject)
    private class BadArgsException(msg: String) : RuntimeException(msg)

    private fun buildRpcCall(sub: String, args: OffloadArgs): RpcCall {
        val rest = args.positional.drop(1)
        return when (sub) {
            "discover" -> RpcCall("rpc.discover", JSONObject())

            "appInfo" -> RpcCall("debug.appInfo", JSONObject())

            "screenshot" -> {
                val p = JSONObject()
                args.getDouble("scale")?.let { p.put("scale", it) }
                RpcCall("debug.screenshot", p)
            }

            "ls" -> {
                val p = JSONObject()
                rest.firstOrNull()?.let { p.put("path", it) }
                if (args.hasFlag("recursive")) p.put("recursive", true)
                args.getInt("maxDepth")?.let { p.put("maxDepth", it) }
                RpcCall("debug.ls", p)
            }

            "read" -> {
                val path = rest.firstOrNull()
                    ?: throw BadArgsException("missing <path>")
                val p = JSONObject().put("path", path)
                args.getInt("offset")?.let { p.put("offset", it) }
                args.getInt("limit")?.let { p.put("limit", it) }
                if (args.hasFlag("base64")) p.put("base64", true)
                RpcCall("debug.readFile", p)
            }

            "write" -> {
                val path = rest.firstOrNull()
                    ?: throw BadArgsException("missing <path>")
                val content = args.get("content")
                    ?: throw BadArgsException("missing --content <text>")
                val p = JSONObject().put("path", path).put("content", content)
                args.get("encoding")?.let { p.put("encoding", it) }
                RpcCall("debug.writeFile", p)
            }

            "exec" -> {
                if (rest.isEmpty()) throw BadArgsException("missing <command...>")
                val command = rest.joinToString(" ")
                RpcCall("debug.shellExecute", JSONObject().put("command", command))
            }

            // Android-only debug-build methods, exposed because they live
            // in DEBUG_ONLY_METHODS of DebugMethodRegistry and are useful
            // from a shell context. Both take an `args` JSON array; we
            // accept either a positional command line or `--args '["a","b"]'`.
            "shizuku" -> RpcCall("debug.shizuku.exec", buildExecForwarder(rest, args))
            "model-use", "modelUse" -> RpcCall("debug.modelUse.exec", buildExecForwarder(rest, args))

            // Generic escape hatch — call any registered method directly.
            // `minis-debug call <method> [--params '<json>']`
            "call" -> {
                val method = rest.firstOrNull()
                    ?: throw BadArgsException("missing <method>")
                val raw = args.get("params")
                val params = if (raw.isNullOrBlank()) JSONObject() else try {
                    JSONObject(raw)
                } catch (_: Exception) {
                    throw BadArgsException("--params must be a JSON object")
                }
                RpcCall(method, params)
            }

            else -> throw BadArgsException("unknown subcommand '$sub' (try `minis-debug --help`)")
        }
    }

    /**
     * Build the `{args: [...]}` envelope expected by `debug.shizuku.exec` /
     * `debug.modelUse.exec`. If the user passes `--args '["foo","bar"]'`
     * we take that verbatim; otherwise we use the remaining positional
     * arguments as the argv slice.
     */
    private fun buildExecForwarder(rest: List<String>, args: OffloadArgs): JSONObject {
        val explicit = args.get("args")
        val argvArr = if (explicit != null) {
            try { JSONArray(explicit) }
            catch (_: Exception) { throw BadArgsException("--args must be a JSON array of strings") }
        } else {
            JSONArray().apply { rest.forEach { put(it) } }
        }
        return JSONObject().put("args", argvArr)
    }

    // ── Raw HTTP POST over Socket ────────────────────────────────────────────

    private class ConnectionRefused(cause: Throwable) : IOException(cause)

    /**
     * Hand-rolled HTTP/1.1 POST so we don't pull in OkHttp or HttpURLConnection's
     * connection-pool quirks for what is a single localhost round-trip.
     * DebugServer reads exactly Content-Length bytes off the wire and writes
     * an HTTP/1.1 response with its own Content-Length header — see
     * DebugServer.handleConnection.
     */
    private fun postJsonRpc(method: String, params: JSONObject): String {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", method)
            .put("params", params)
            .toString()
        val bodyBytes = body.toByteArray(Charsets.UTF_8)

        val socket = Socket()
        try {
            try {
                socket.connect(InetSocketAddress("127.0.0.1", DEBUG_SERVER_PORT), TIMEOUT_MS)
            } catch (e: java.net.ConnectException) {
                throw ConnectionRefused(e)
            }
            socket.soTimeout = TIMEOUT_MS

            val out: OutputStream = socket.getOutputStream()
            val req = buildString {
                append("POST / HTTP/1.1\r\n")
                append("Host: 127.0.0.1:$DEBUG_SERVER_PORT\r\n")
                append("Content-Type: application/json\r\n")
                append("Content-Length: ${bodyBytes.size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }.toByteArray(Charsets.UTF_8)
            out.write(req)
            out.write(bodyBytes)
            out.flush()

            return readHttpResponse(BufferedInputStream(socket.getInputStream()))
        } finally {
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Minimal HTTP/1.1 response parser: status line, headers, then body
     * sized by Content-Length (DebugServer always sends one). We don't
     * support chunked transfer because DebugServer doesn't emit it.
     */
    private fun readHttpResponse(input: BufferedInputStream): String {
        // Read header section: bytes up to and including "\r\n\r\n".
        val headerBuf = ByteArrayOutputStream()
        var prev = -1
        var prev2 = -1
        var prev3 = -1
        while (true) {
            val b = input.read()
            if (b == -1) throw IOException("debug server closed connection before headers complete")
            headerBuf.write(b)
            if (prev3 == 0x0D && prev2 == 0x0A && prev == 0x0D && b == 0x0A) break
            prev3 = prev2
            prev2 = prev
            prev = b
        }
        val headerText = headerBuf.toString("UTF-8")
        var contentLength = -1
        for (line in headerText.split("\r\n")) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            if (line.substring(0, idx).equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: -1
            }
        }
        if (contentLength < 0) {
            // Fall back to read-to-EOF — DebugServer normally sets the
            // header, but we'd rather degrade than fail outright.
            val rest = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                rest.write(buf, 0, n)
            }
            return rest.toString("UTF-8")
        }

        val bodyBuf = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(bodyBuf, read, contentLength - read)
            if (n < 0) throw IOException("debug server closed connection at $read / $contentLength bytes")
            read += n
        }
        return String(bodyBuf, Charsets.UTF_8)
    }

    companion object {
        private const val TAG = "DebugOffload"
        private const val DEBUG_SERVER_PORT = 5321
        private const val TIMEOUT_MS = 30_000

        /**
         * Declared boolean flags so OffloadArgs doesn't greedily consume the
         * following positional token as a value. e.g. `--recursive /sdcard`
         * must parse as flag + positional, not `--recursive=/sdcard`.
         */
        private val BOOLEAN_FLAGS = setOf("recursive", "base64")

        private const val HELP = """minis-debug — CLI for the in-app DebugServer JSON-RPC (debug builds only)

Usage:
  minis-debug <subcommand> [options]

SUBCOMMANDS:
  discover                           List all registered RPC methods (rpc.discover)
  appInfo                            Show platform / SDK / build info (debug.appInfo)
  screenshot [--scale N]             Capture foreground Activity (debug.screenshot)

  ls [path] [--recursive] [--maxDepth N]
                                     List a path inside the PRoot rootfs (debug.ls)
  read <path> [--offset N] [--limit N] [--base64]
                                     Read a file from PRoot rootfs (debug.readFile)
  write <path> --content <text> [--encoding utf8|base64]
                                     Write a file inside PRoot rootfs (debug.writeFile)
  exec <command...>                  Run a shell command via debug.shellExecute

Android-only (DEBUG_ONLY_METHODS in DebugMethodRegistry):
  shizuku <argv...>                  Invoke android-shizuku-cli (debug.shizuku.exec)
  model-use <argv...>                Invoke minis-model-use (debug.modelUse.exec)

Escape hatch (for any method not listed above):
  call <method> [--params '<json>']  Invoke an arbitrary registered method

GLOBAL OPTIONS:
  --help, -h                         Show this help
  --compact                          Minimize JSON output
  -q, --quiet                        Output only the data/error field of the
                                     iOS-style envelope (when present)

EXAMPLES:
  minis-debug discover
  minis-debug appInfo
  minis-debug ls /sdcard --recursive --maxDepth 2
  minis-debug read /etc/passwd --offset 0 --limit 100
  minis-debug exec uname -a
  minis-debug call debug.logs.list --params '{}'

The handler talks to 127.0.0.1:$DEBUG_SERVER_PORT (DebugServer). If the server
isn't running you'll see `debug_server_unreachable` — Release builds don't
ship this command at all.
"""
    }
}

// ─── Merged from DeviceOffloadHandler.kt ──────────────────────────────────────────────────

/**
 * android-device — device model, OS version, battery, and storage info.
 *
 * Usage:
 *   android-device                Same as `all`
 *   android-device info
 *   android-device battery
 *   android-device storage
 *   android-device all
 */
class DeviceOffloadHandler(private val context: Context) : NativeOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) return NativeOffloadResult(0, HELP)

        val sub = args.positional.firstOrNull() ?: "all"
        return try {
            val out = when (sub) {
                "info" -> deviceInfo().toString(2)
                "battery" -> batteryInfo().toString(2)
                "storage" -> storageInfo().toString(2)
                "all" -> JSONObject()
                    .put("device", deviceInfo())
                    .put("battery", batteryInfo())
                    .put("storage", storageInfo())
                    .toString(2)
                else -> return NativeOffloadResult(2, "android-device: unknown subcommand '$sub'\n$HELP")
            }
            NativeOffloadResult(0, OffloadOutput.formatBody(out, args) + "\n")
        } catch (e: Throwable) {
            val body = JSONObject().put("error", "internal")
                .put("message", e.message ?: "unknown").toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    private fun String?.orFallback(alt: String): String =
        this?.takeIf { it.isNotBlank() && it != "unknown" } ?: alt

    private fun deviceInfo(): JSONObject {
        val runtime = Runtime.getRuntime()
        val manufacturer = Build.MANUFACTURER.orFallback(Build.BRAND.orFallback("unknown"))
        val model = Build.MODEL.orFallback(Build.DEVICE.orFallback(Build.PRODUCT.orFallback("unknown")))
        return JSONObject()
            .put("manufacturer", manufacturer)
            .put("model", model)
            .put("brand", Build.BRAND.orFallback("unknown"))
            .put("device", Build.DEVICE.orFallback("unknown"))
            .put("product", Build.PRODUCT.orFallback("unknown"))
            .put("android_version", Build.VERSION.RELEASE.orFallback("unknown"))
            .put("sdk_level", Build.VERSION.SDK_INT)
            .put("security_patch", Build.VERSION.SECURITY_PATCH.orFallback("unknown"))
            .put("board", Build.BOARD.orFallback("unknown"))
            .put("hardware", Build.HARDWARE.orFallback("unknown"))
            .put("supported_abis", runCatching { Build.SUPPORTED_ABIS.joinToString(", ") }.getOrDefault(""))
            .put("available_processors", runtime.availableProcessors())
            .put("total_memory_mb", runtime.totalMemory() / (1024 * 1024))
            .put("free_memory_mb", runtime.freeMemory() / (1024 * 1024))
            .put("max_memory_mb", runtime.maxMemory() / (1024 * 1024))
    }

    private fun batteryInfo(): JSONObject {
        val json = JSONObject()
        val bi = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return json.put("error", "Battery info unavailable")

        val level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        json.put("level_percent", pct)

        val status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        json.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
        json.put("status", when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        })
        json.put("power_source", when (bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "none"
        })
        val temp = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        if (temp > 0) {
            val c = temp / 10.0
            // Clamp to plausible range; devices that report in K or in raw °C
            // would otherwise surface a wildly wrong number.
            if (c in -20.0..80.0) json.put("temperature_celsius", c)
            else json.put("temperature_celsius_raw", temp)
        }
        json.put("health", when (bi.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> "unknown"
        })
        return json
    }

    private fun storageInfo(): JSONObject {
        val json = JSONObject()
        try {
            val s = StatFs(Environment.getDataDirectory().path)
            val total = s.totalBytes
            val free = s.availableBytes
            json.put("internal_total_gb", String.format("%.1f", total / 1e9))
                .put("internal_free_gb", String.format("%.1f", free / 1e9))
                .put("internal_used_gb", String.format("%.1f", (total - free) / 1e9))
                .put("note", "Active partition only; Huawei PrivateSpace / Xiaomi Second Space figures reflect the currently unlocked container.")
        } catch (e: Throwable) {
            json.put("error", e.message ?: "StatFs unavailable")
        }
        json.put("app_data_mb", String.format("%.1f", dirSize(context.filesDir) / 1e6))
            .put("app_cache_mb", String.format("%.1f", dirSize(context.cacheDir) / 1e6))
        return json
    }

    /**
     * [T-android-storage-symlink-inflation] Fourth copy of the sizing walk,
     * found by the sweep that followed 754e65eac. Same following-walk defect:
     * `walkTopDown()`, `isFile` and `length()` all resolve symlinks, so a
     * directory link had its subtree counted again.
     *
     * Worth fixing even though this is "only" a diagnostic: the number goes to
     * the MODEL as `app_data_mb`, and an agent reasoning about whether to free
     * space from a figure several times the truth is worse than one with no
     * figure at all.
     */
    private fun dirSize(dir: File): Long =
        com.openminis.app.data.session.SessionStorage.directorySize(dir)

    companion object {
        private const val HELP = """android-device — device model, OS, battery, storage (JSON)

Usage:
  android-device [all]       Everything (default)
  android-device info        Model, OS, memory
  android-device battery     Battery level, charging state, temperature
  android-device storage     Internal storage, app data/cache size
"""
    }
}

// ─── Merged from LocationOffloadHandler.kt ──────────────────────────────────────────────────

/**
 * android-location — current location, reverse geocoding, forward geocoding.
 *
 * Mirrors apple-location (NativeOffloads/LocationOffload.m): same three
 * subcommands and same flag names so prompts and the agent loop are
 * platform-agnostic.
 *
 * The `current` subcommand layers three strategies because
 * `getLastKnownLocation` returns null far more often on Android
 * than on iOS (especially on MIUI/EMUI/ColorOS after background idle):
 *   1. Walk every enabled provider with getLastKnownLocation
 *   2. If all null, request a single fresh fix via
 *      getCurrentLocation (API 30+) or requestSingleUpdate (<30)
 *   3. On timeout, return a structured error naming the enabled
 *      providers so the caller can explain exactly what's wrong.
 *
 * Usage:
 *   android-location current [--timeout SEC]
 *   android-location geocode --lat <lat> --lon <lon>     (--lng alias accepted)
 *   android-location forward --address "<addr>"
 *   android-location geocode <lat> <lon>                 (legacy positional)
 */
class LocationOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        // [T-location-default-current-android] Help only on explicit
        // --help/-h. No subcommand defaults to `current` (mirrors iOS) —
        // `android-location` == `android-location current`, and flags pass
        // through: `android-location --compact` == `current --compact`.
        if (args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, HELP)
        }
        // T330: tri-state agent gate before any work.
        OffloadGate.enforce("location", "android-location", args, request)?.let { return it }

        return try {
            when (val sub = args.positional.firstOrNull() ?: "current") {
                "current" -> current(args.getInt("timeout") ?: 8, args)
                "geocode" -> handleGeocode(args)
                "forward" -> handleForward(args)
                else -> NativeOffloadResult(2, "android-location: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            NativeOffloadResult(1, OffloadOutput.formatBody("android-location: ${e.message ?: "unknown error"}", args) + "\n")
        }
    }

    /**
     * Resolve --lat / --lon (with --lng alias for apple-location parity)
     * and fall back to legacy positional `<lat> <lon>`. Returns null if
     * neither form supplies a valid pair.
     */
    private fun handleGeocode(args: OffloadArgs): NativeOffloadResult {
        // Project convention is --lon (matches photos `near`); --lng is
        // accepted as an alias to keep prompts that came from apple-
        // location working unchanged.
        val lat = args.getDouble("lat")
            ?: args.positional.getOrNull(1)?.toDoubleOrNull()
        val lon = args.getDouble("lon", "lng")
            ?: args.positional.getOrNull(2)?.toDoubleOrNull()
        if (lat == null || lon == null) {
            return NativeOffloadResult(
                2,
                "android-location geocode: --lat <lat> --lon <lon> required (positional <lat> <lon> also accepted)\n",
            )
        }
        return geocode(lat, lon, args)
    }

    // ── Permission / provider helpers ────────────────────────────

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasFinePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun enabledProviders(lm: LocationManager): List<String> =
        try { lm.allProviders.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) } }
        catch (_: Throwable) { emptyList() }

    // ── current ───────────────────────────────────────────────────

    private fun current(timeoutSec: Int, args: OffloadArgs): NativeOffloadResult {
        if (!hasPermission()) {
            AppLogger.warning(TAG, "ACCESS_FINE/COARSE_LOCATION not granted — routing through permission flow")
            val permissions = listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            val result = runBlocking {
                var r = OffloadPermissionManager.requestAndroidPermission(permissions)
                if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED &&
                    OffloadPermissionManager.pollForPermissionGrant({ hasPermission() })
                ) {
                    AppLogger.info(TAG, "Location permission granted during post-DENY poll")
                    r = OffloadPermissionManager.AndroidPermissionResult.GRANTED
                }
                if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED) {
                    r = OffloadPermissionManager.requestSettingsGate(
                        OffloadPermissionManager.SettingsGateRequest(
                            id = Manifest.permission.ACCESS_FINE_LOCATION,
                            title = "Location permission needed",
                            message = "Minis needs location permission to get your current location. Open Settings to allow it.",
                            settingsAction = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            requiresPackageUri = true,
                            positiveLabel = "Open Settings",
                        ),
                        check = { hasPermission() },
                    )
                }
                r
            }
            when (result) {
                OffloadPermissionManager.AndroidPermissionResult.GRANTED -> {} // fall through
                OffloadPermissionManager.AndroidPermissionResult.DENIED -> {
                    val body = JSONObject()
                        .put("error", "permission_denied")
                        .put("message", "The user declined the location permission.")
                        .toString()
                    return NativeOffloadResult(77, OffloadOutput.formatBody(body, args) + "\n")
                }
                OffloadPermissionManager.AndroidPermissionResult.TIMEOUT -> {
                    val body = JSONObject()
                        .put("error", "timeout")
                        .put("message", "Timed out waiting for the user to grant location permission.")
                        .toString()
                    return NativeOffloadResult(77, OffloadOutput.formatBody(body, args) + "\n")
                }
            }
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return NativeOffloadResult(1, OffloadOutput.formatBody("""{"error":"service_unavailable","message":"LocationManager not available on this device"}""", args) + "\n")

        val enabled = enabledProviders(lm)
        val locationEnabled = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
            else enabled.any { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER }
        } catch (_: Throwable) { false }

        if (!locationEnabled || enabled.isEmpty()) {
            val body = JSONObject()
                .put("error", "location_services_disabled")
                .put("message", "Location services are off on this device. Ask the user to enable Location in system settings.")
                .put("enabled_providers", JSONArray(enabled))
                .toString()
            return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }

        // Strategy 1: last-known across all providers
        val best = enabled
            .mapNotNull {
                runCatching {
                    @Suppress("MissingPermission")
                    lm.getLastKnownLocation(it)
                }.getOrNull()
            }
            .maxByOrNull { it.time }
        if (best != null) {
            AppLogger.debug(TAG, "last-known hit provider=${best.provider} age=${System.currentTimeMillis() - best.time}ms")
            return NativeOffloadResult(0, OffloadOutput.formatBody(buildLocationJson(best), args) + "\n")
        }

        // Strategy 2: request a single fresh fix with timeout
        AppLogger.debug(TAG, "no last-known, requesting fresh fix (enabled=$enabled)")
        val fresh = requestFreshLocation(lm, enabled, timeoutSec)
        if (fresh != null) {
            AppLogger.debug(TAG, "fresh fix provider=${fresh.provider} accuracy=${fresh.accuracy}m")
            return NativeOffloadResult(0, OffloadOutput.formatBody(buildLocationJson(fresh), args) + "\n")
        }

        // Strategy 3: give up with explicit diagnostics
        val oemHint = if (OsCompat.isXiaomi || OsCompat.isHuawei ||
            Build.MANUFACTURER.equals("OPPO", ignoreCase = true) ||
            Build.MANUFACTURER.equals("OnePlus", ignoreCase = true) ||
            Build.MANUFACTURER.equals("Vivo", ignoreCase = true)
        ) {
            " On ${OsCompat.oemLabel()} devices, disable battery optimization and enable autostart for Minis so background location stays active."
        } else ""
        val body = JSONObject()
            .put("error", "location_unavailable_stale")
            .put("message", "No location fix available after ${timeoutSec}s.$oemHint")
            .put("enabled_providers", JSONArray(enabled))
            .put("has_fine_permission", hasFinePermission())
            .toString()
        return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
    }

    private fun requestFreshLocation(
        lm: LocationManager,
        enabled: List<String>,
        timeoutSec: Int,
    ): android.location.Location? {
        // Prefer NETWORK > FUSED > GPS for speed (GPS may take 30s+ cold).
        val ordered = listOf(
            LocationManager.NETWORK_PROVIDER,
            "fused",
            LocationManager.GPS_PROVIDER,
        ).filter { it in enabled }
        if (ordered.isEmpty()) return null

        val perProvTimeoutMs = (timeoutSec.coerceAtLeast(1) * 1000L / ordered.size)
            .coerceAtLeast(1500L)

        for (provider in ordered) {
            val latch = CountDownLatch(1)
            val holder = arrayOfNulls<android.location.Location>(1)
            val cancel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) CancellationSignal() else null

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    @Suppress("MissingPermission")
                    lm.getCurrentLocation(
                        provider,
                        cancel,
                        // [GH #453] Run the callback where it is delivered: it
                        // only stores the fix and counts down. A fresh
                        // newSingleThreadExecutor() per provider per call was
                        // never shut down, so its idle thread stayed until a GC
                        // finalised the executor - up to 3 threads a call.
                        DIRECT_EXECUTOR,
                    ) { loc ->
                        holder[0] = loc
                        latch.countDown()
                    }
                } else {
                    val listener = object : android.location.LocationListener {
                        override fun onLocationChanged(location: android.location.Location) {
                            holder[0] = location
                            latch.countDown()
                        }
                        override fun onProviderDisabled(p: String) { latch.countDown() }
                        override fun onProviderEnabled(p: String) {}
                        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                        override fun onStatusChanged(p: String?, status: Int, extras: android.os.Bundle?) {}
                    }
                    @Suppress("MissingPermission", "DEPRECATION")
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                    // Clean up listener on timeout path below.
                    try {
                        latch.await(perProvTimeoutMs, TimeUnit.MILLISECONDS)
                    } finally {
                        try { @Suppress("DEPRECATION") lm.removeUpdates(listener) } catch (_: Throwable) {}
                    }
                    holder[0]?.let { return it }
                    continue
                }

                if (latch.await(perProvTimeoutMs, TimeUnit.MILLISECONDS)) {
                    holder[0]?.let { return it }
                } else {
                    cancel?.cancel()
                }
            } catch (e: Throwable) {
                AppLogger.debug(TAG, "fresh fix via '$provider' failed: ${e.message}")
            }
        }
        return null
    }

    private fun buildLocationJson(loc: android.location.Location): String {
        val json = JSONObject()
            .put("latitude", loc.latitude)
            .put("longitude", loc.longitude)
            .put("accuracy_meters", loc.accuracy)
            .put("altitude_meters", loc.altitude)
            .put("provider", loc.provider ?: "unknown")
            .put("timestamp_ms", loc.time)

        try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val addrs = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
            val addr = addrs?.firstOrNull()
            if (addr != null) {
                val parts = mutableListOf<String>()
                addr.thoroughfare?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
                addr.subLocality?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
                addr.locality?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
                addr.adminArea?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
                addr.countryName?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
                if (parts.isNotEmpty()) json.put("address", parts.joinToString(", "))
            }
        } catch (_: Throwable) {
            // Geocoder service missing or offline — ok to omit address.
        }
        return json.toString(2)
    }

    private fun geocode(lat: Double, lon: Double, args: OffloadArgs): NativeOffloadResult {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val addrs = geocoder.getFromLocation(lat, lon, 1)
            val addr = addrs?.firstOrNull()
                ?: return NativeOffloadResult(0, OffloadOutput.formatBody("No address found for ($lat, $lon).", args) + "\n")
            val lines = (0..addr.maxAddressLineIndex).map { addr.getAddressLine(it) }
            NativeOffloadResult(0, OffloadOutput.formatBody(lines.joinToString("\n"), args) + "\n")
        } catch (e: Throwable) {
            val body = """{"error":"geocoder_unavailable","message":"Reverse geocoding failed: ${e.message}. This device may lack a geocoder backend."}"""
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    // ── forward geocoding ────────────────────────────────────────────────

    /**
     * Forward-geocode an address string to lat/lon + a structured placemark.
     * Mirrors apple-location `forward --address "<addr>"`. Geocoder.getFromLocationName
     * returns a list of candidates; we take the first since iOS does the same.
     * On devices without a geocoder backend (Huawei HMS-only, some stripped
     * China ROMs) Geocoder.isPresent() returns false — return a structured
     * error so the model can explain instead of silently failing.
     */
    private fun handleForward(args: OffloadArgs): NativeOffloadResult {
        val address = args.get("address")
        if (address.isNullOrBlank()) {
            return NativeOffloadResult(2, "android-location forward: --address <addr> is required\n")
        }
        if (!Geocoder.isPresent()) {
            val body = JSONObject()
                .put("error", "geocoder_unavailable")
                .put("message", "No geocoder backend is registered on this device. Common on Huawei HMS-only devices and some stripped China ROMs. Forward geocoding requires Google Play Services or an OEM-equivalent provider.")
                .toString()
            return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
        return try {
            // [T-android-location-cjk-geocode] C6: with a non-Chinese system
            // locale the geocoder often returns empty for CJK queries
            // (e.g. Beijing/Shanghai written in Chinese characters). Detect
            // CJK and retry once with Locale.CHINA before giving up.
            val containsCjk = CJK_REGEX.containsMatchIn(address)
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            var addrs = geocoder.getFromLocationName(address, 1)
            if (addrs.isNullOrEmpty() && containsCjk) {
                AppLogger.warning(
                    TAG,
                    "forward: empty result for CJK address '$address' with locale ${Locale.getDefault()} — retrying with Locale.CHINA",
                )
                addrs = try {
                    @Suppress("DEPRECATION")
                    Geocoder(context, Locale.CHINA).getFromLocationName(address, 1)
                } catch (e: Throwable) {
                    AppLogger.warning(TAG, "forward: Locale.CHINA retry threw: ${e.message}")
                    null
                }
            }
            val addr = addrs?.firstOrNull()
            if (addr == null) {
                AppLogger.warning(
                    TAG,
                    "forward: no_match for '$address' (locale=${Locale.getDefault()}, cjkRetry=$containsCjk)",
                )
                // On GMS-less Chinese ROMs Geocoder.isPresent() can be true
                // while every query returns empty — the hint below is the
                // best we can do without a second backend.
                val hint = if (containsCjk) {
                    "The system geocoder may lack Chinese place-name coverage on this device (common without Google Play Services). Try the address in pinyin/English, pass coordinates directly (--lat/--lon via geocode), or check the spelling."
                } else {
                    "Check the address spelling, or pass coordinates directly (--lat/--lon via geocode)."
                }
                val body = JSONObject()
                    .put("error", "no_match")
                    .put("message", "No coordinates found for address '$address'. $hint")
                    .put("address", address)
                    .toString()
                return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
            }
            AppLogger.info(TAG, "forward: '$address' → ${addr.latitude},${addr.longitude}")
            val lines = (0..addr.maxAddressLineIndex).map { addr.getAddressLine(it) }
            val data = JSONObject()
                .put("latitude", addr.latitude)
                .put("longitude", addr.longitude)
                .put("address", lines.joinToString(", "))
                .put("query", address)
            addr.locality?.takeIf { it.isNotBlank() }?.let { data.put("locality", it) }
            addr.adminArea?.takeIf { it.isNotBlank() }?.let { data.put("admin_area", it) }
            addr.countryName?.takeIf { it.isNotBlank() }?.let { data.put("country", it) }
            addr.postalCode?.takeIf { it.isNotBlank() }?.let { data.put("postal_code", it) }
            NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
        } catch (e: Throwable) {
            val body = JSONObject()
                .put("error", "geocoder_failed")
                .put("message", "Forward geocoding failed: ${e.message}")
                .put("address", address)
                .toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    companion object {
        private val DIRECT_EXECUTOR = Executor { it.run() }

        private const val TAG = "LocationOffload"

        // [T-android-location-cjk-geocode] CJK Unified Ideographs (+ Ext A,
        // compatibility block) — enough to detect Chinese place names.
        private val CJK_REGEX = Regex("[\\u4E00-\\u9FFF\\u3400-\\u4DBF\\uF900-\\uFAFF]")
        private const val HELP = """android-location — current location, reverse + forward geocoding (mirrors apple-location)

Usage:
  android-location                  Same as `current` (default subcommand)
  android-location current [--timeout SEC]
                                    Last-known or fresh fix + address
  android-location geocode --lat <lat> --lon <lon>
                                    Reverse-geocode to an address
  android-location forward --address "<addr>"
                                    Forward-geocode address to lat/lon

Aliases:
  geocode <lat> <lon>     Legacy positional form (kept for backwards compat)
  --lng                   Alias for --lon (apple-location naming)

Requires ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION. Default current
timeout 8s. Forward / reverse geocoding requires a geocoder backend
(Google Play Services or OEM equivalent).
Errors return JSON: {"error":"...","message":"...","enabled_providers":[...]}.
"""
    }
}

// ─── Merged from ModelUseOffloadHandler.kt ──────────────────────────────────────────────────

/**
 * `minis-model-use` — list, search, and invoke LLM models from Alpine shell.
 * Mirrors iOS ModelUseOffload.m + ModelUseOffloadBridge.swift.
 *
 * Subcommands:
 *   minis-model-use list [--provider <name>] [--modality <m>]
 *   minis-model-use search <query> [--provider <name>] [--modality <m>]
 *   minis-model-use run --model <id_or_name> [--input <path>] [--output <path>]
 *                       [--system <text>] [--system-file <path>]
 *                       [--max-tokens N] [--temperature F]
 *
 * Only entries/groups exposed via the "Available Models in Agent Loop" section
 * in Settings > Model Groups are visible (see ProviderRepository.resolvedAgentLoopEntries).
 */
class ModelUseOffloadHandler(
    private val context: Context,
    private val providerRepository: ProviderRepository,
) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help") || args.positional.isEmpty()) {
            return NativeOffloadResult(if (args.positional.isEmpty()) 2 else 0, HELP)
        }

        return try {
            when (val sub = args.positional[0]) {
                "list" -> cmdList(args)
                "search" -> cmdSearch(args)
                "run" -> cmdRun(args, request)
                else -> NativeOffloadResult(2, "minis-model-use: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "uncaught: ${e.message}", e)
            NativeOffloadResult(
                1,
                JSONObject().put("error", "model_use_failed")
                    .put("message", e.message ?: "unknown").toString() + "\n",
            )
        }
    }

    private fun cmdList(args: OffloadArgs): NativeOffloadResult {
        val providerFilter = args.get("provider")?.lowercase()
        val modalityFilter = args.get("modality")?.lowercase()

        var entries = providerRepository.resolvedAgentLoopEntries()
        if (providerFilter != null) {
            entries = entries.filter {
                it.model.provider.lowercase().contains(providerFilter) ||
                    providerInstanceLabel(it).lowercase().contains(providerFilter)
            }
        }
        if (modalityFilter != null) {
            entries = entries.filter { matchesModality(it, modalityFilter) }
        }

        val body = JSONObject().apply {
            put("models", JSONArray().apply { entries.forEach { put(entryDict(it)) } })
            put("count", entries.size)
            if (entries.isEmpty()) put("hint", NO_MODELS_HINT)
            else put("usage", USAGE_HINT)
        }
        return NativeOffloadResult(0, body.toString(2) + "\n")
    }

    private fun cmdSearch(args: OffloadArgs): NativeOffloadResult {
        val query = args.positional.getOrNull(1)
            ?: return NativeOffloadResult(
                2,
                "minis-model-use search: no query. Usage: minis-model-use search <query>\n",
            )
        val q = query.lowercase()
        val modalityFilter = args.get("modality")?.lowercase()

        var matches = providerRepository.resolvedAgentLoopEntries().filter { entry ->
            entry.model.id.lowercase().contains(q) ||
                entry.model.displayName.lowercase().contains(q) ||
                entry.model.provider.lowercase().contains(q)
        }
        if (modalityFilter != null) {
            matches = matches.filter { matchesModality(it, modalityFilter) }
        }

        val body = JSONObject().apply {
            put("query", query)
            put("models", JSONArray().apply { matches.forEach { put(entryDict(it)) } })
            put("count", matches.size)
            if (matches.isEmpty()) put("hint", NO_MODELS_HINT)
            else put("usage", USAGE_HINT)
        }
        return NativeOffloadResult(0, body.toString(2) + "\n")
    }

    private fun cmdRun(args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        // [T-offload-caller-session] The chat this run belongs to: the session
        // whose /var/minis this shell sees, i.e. the PARENT for a sub agent
        // (iOS resolves the caller from the task's fs_context, which a helper
        // shares with its parent; b99e4da87). Used for the provider's session
        // header and for where generated media and --output files are written.
        // The raw MINIS_CHAT_SESSION_ID is the helper's own hidden session, so
        // its images landed in minis-sessions/<helperId>/attachments: not at
        // the /var/minis/attachments path the guest was told, and never
        // visible from the parent chat.
        val caller = OffloadCallerSession.resolve(request.sessionId)
        val callerSid = caller.sid
        AppLogger.info(TAG, "[ModelUse] run caller=${callerSid?.take(8) ?: "nil"} via=${caller.source}")
        val modelArg = args.get("model")
            ?: return NativeOffloadResult(2, "--model is required. Usage: minis-model-use run --model <id_or_name>\n")

        // Optional provider scoping — disambiguates when multiple instances expose the same model_id.
        val providerFilter = args.get("provider")

        // Resolve entry (restricted to agent-loop-visible)
        val entry = resolveEntry(modelArg, providerFilter)
            ?: return NativeOffloadResult(
                2,
                JSONObject().put("error", "model_not_found")
                    .put(
                        "message",
                        if (providerFilter != null)
                            "No model '$modelArg' under provider '$providerFilter'. Use 'minis-model-use list' to see available combinations."
                        else
                            "Model '$modelArg' not visible to the agent. Add it in Settings > Model Groups > Available Models in Agent Loop.",
                    )
                    .toString() + "\n",
            )

        // Modality precheck: if --output extension implies a media modality
        // the model doesn't advertise, fail fast before hitting the API.
        // Mirrors iOS ModelUseOffloadBridge.performRun:208-228.
        val outputPath = args.get("output")
        // [T-android-model-use-relative-output] A relative --output can't be
        // resolved reliably: this handler runs in-process and does NOT inherit
        // the shell's cwd, so PRootKernel.resolveHostPath would silently drop a
        // relative path onto the rootfs root (e.g. "gen_output.json" ->
        // <rootfs>/gen_output.json) and report an unreadable bare-relative path
        // that read_image later rejects. Fail fast with a clear message asking
        // for an absolute path instead. `file://` URLs are already absolute.
        if (outputPath != null && !outputPath.startsWith("/") && !outputPath.startsWith("file://")) {
            return NativeOffloadResult(
                2,
                JSONObject().put("error", "invalid_output_path")
                    .put(
                        "message",
                        "--output must be an absolute path (e.g. /var/minis/workspace/out.jpg). " +
                            "Got the relative path '$outputPath', which cannot be resolved because " +
                            "minis-model-use does not inherit the shell's working directory.",
                    )
                    .toString() + "\n",
            )
        }
        val outputExt = outputPath?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val outputs = entry.model.outputModalities.orEmpty()
        val requiredModality = when {
            isImageExt(outputExt) -> "image_output".takeIf { "image" !in outputs }
            isAudioExt(outputExt) -> "audio_output".takeIf { "audio" !in outputs }
            isVideoExt(outputExt) -> "video_output".takeIf { "video" !in outputs }
            else -> null
        }
        if (requiredModality != null) {
            val supported = buildList {
                if ("text" in entry.model.inputModalities.orEmpty()) add("text_input")
                if ("text" in outputs) add("text_output")
                if ("image" in entry.model.inputModalities.orEmpty()) add("image_input")
                if ("image" in outputs) add("image_output")
                if ("audio" in entry.model.inputModalities.orEmpty()) add("audio_input")
                if ("audio" in outputs) add("audio_output")
                if ("video" in entry.model.inputModalities.orEmpty()) add("video_input")
                if ("video" in outputs) add("video_output")
            }
            return NativeOffloadResult(
                2,
                JSONObject().put("error", "modality_not_supported")
                    .put("message", "Model '${entry.model.displayName}' does not support $requiredModality. Supported modalities: ${supported.joinToString(", ")}. Use 'minis-model-use list' to find a model with the required capability.")
                    .toString() + "\n",
            )
        }

        // System prompt: --system takes precedence over --system-file
        val explicitSystem = args.get("system") ?: args.get("system-file")?.let { readLinuxPath(it) }

        // Parse input messages: --input <path> | stdin
        val inputText = when {
            args.get("input") != null -> readLinuxPath(args.get("input")!!)
                ?: return NativeOffloadResult(
                    2,
                    "minis-model-use run: cannot read --input '${args.get("input")}'\n",
                )
            else -> ""
        }
        val parsed = try {
            parseMessages(inputText)
        } catch (e: ImageInputError) {
            return NativeOffloadResult(
                2,
                JSONObject().put("error", "image_input_error")
                    .put("message", e.message ?: "image input could not be resolved")
                    .toString() + "\n",
            )
        } catch (e: AudioInputError) {
            // [GH#67] Malformed input_audio → explicit error, not a silent drop.
            return NativeOffloadResult(
                2,
                JSONObject().put("error", "audio_input_error")
                    .put("message", e.message ?: "audio input could not be parsed")
                    .toString() + "\n",
            )
        }
        // Any `role: system` messages from the input JSON are folded into the
        // system prompt — the Android LLMMessage enum only exposes user/assistant.
        val inlineSystem = parsed.filter { it.role == "system" }
            .joinToString("\n") { it.content }
        val nonSystem = parsed.filter { it.role != "system" }

        // [GH#67] Audio input needs a model that declares the audio input
        // modality — explicit error instead of the historical silent drop.
        // Mirrors iOS ModelUseOffloadBridge's hasAudioInput gate.
        if (nonSystem.any { it.audios.isNotEmpty() } &&
            "audio" !in entry.model.inputModalities.orEmpty()
        ) {
            return NativeOffloadResult(
                2,
                JSONObject().put("error", "modality_not_supported")
                    .put(
                        "message",
                        "Model '${entry.model.displayName}' does not support audio_input, " +
                            "but the input contains an input_audio block. " +
                            "Use 'minis-model-use list --modality audio' to find an audio-capable model.",
                    )
                    .toString() + "\n",
            )
        }

        val messages = nonSystem.map { textMessage(it.role, it.content, it.audios) }
        val hasAudioInput = messages.any { it.audioParts.isNotEmpty() }
        // [T-android-model-use-multimodal] Forward image_url blocks as the
        // top-level `imageParts` argument — providers (OpenAI / Anthropic /
        // Gemini) attach these to the LAST user message in the conversation
        // when building the wire request. Collecting images from the LAST
        // user-role ParsedMessage matches that contract: any image attached
        // to an earlier turn would be silently ignored on the provider side
        // (this is a known limitation; iOS has the same per-call shape).
        val imageParts = nonSystem.lastOrNull { it.role == "user" }?.images.orEmpty()
        val systemPrompt = when {
            explicitSystem != null && inlineSystem.isNotEmpty() -> "$explicitSystem\n\n$inlineSystem"
            explicitSystem != null -> explicitSystem
            inlineSystem.isNotEmpty() -> inlineSystem
            else -> null
        }

        val maxTokens = args.get("max-tokens")?.toIntOrNull() ?: 4096
        val temperature = args.get("temperature")?.toDoubleOrNull()

        // Build provider + call — runBlocking is acceptable here: this handler
        // is invoked off the main thread by the offload server.
        val instance = providerRepository.instance(entry.providerInstanceId)
            ?: return NativeOffloadResult(2, "minis-model-use run: provider instance not found\n")
        // [T-android-provider-entry-rules] Same credential rule as every other
        // entry: refreshed OAuth token, keyless/OAuth-held credentials allowed.
        val apiKey = kotlinx.coroutines.runBlocking { providerRepository.requestCredential(instance) }
            ?: return NativeOffloadResult(
                2,
                JSONObject().put("error", "missing_api_key")
                    .put("message", "No API key configured for provider ${entry.model.provider}.")
                    .toString() + "\n",
            )
        // [T-android-opencode-subtask-session] The calling chat's id, so an
        // OpenCode Go model gets its required `x-opencode-session` header and
        // does not answer 400 "Request is missing x-opencode-session". This
        // run is that chat's traffic (iOS b99e4da87 / 4d002af1c tag it the
        // same way). The header itself is still gated on the OpenCode host and
        // on a persisted id inside the provider, so no other endpoint sees it.
        val provider = ProviderFactory.create(
            instance, apiKey, entry.model, context,
            sessionId = callerSid, overrides = entry.overrides,
        )

        // [GH#67] input_audio serialization is implemented for the OpenAI
        // chat/completions + responses paths only. Other provider types would
        // drop the audio at their own serialization layer — fail loudly here
        // instead. Mirrors iOS ModelUseOffloadBridge.
        if (hasAudioInput && provider !is com.openminis.app.provider.openai.OpenAIProvider) {
            return NativeOffloadResult(
                2,
                JSONObject().put("error", "audio_input_unsupported_provider")
                    .put(
                        "message",
                        "input_audio is only supported for OpenAI-compatible providers " +
                            "(chat/completions and responses paths) for now; provider type " +
                            "'${instance.providerType}' would silently drop the audio.",
                    )
                    .toString() + "\n",
            )
        }

        // [T-android-model-use-passthrough-mode] Explicit passthrough envelope —
        // the raw-mode escape hatch. Checked before image routing and message
        // dispatch because body_mode=replace requests may carry no messages at
        // all. Only OpenAI-compatible providers; others error clearly. Mirrors
        // iOS ModelUseOffloadBridge passthrough branch.
        val ptSpec = parsePassthroughEnvelope(inputText)
        if (ptSpec.active) {
            val openAI = provider as? com.openminis.app.provider.openai.OpenAIProvider
                ?: return NativeOffloadResult(
                    2,
                    JSONObject().put("error", "passthrough_unsupported")
                        .put(
                            "message",
                            "passthrough mode is not supported for provider type " +
                                "'${instance.providerType}' yet — only OpenAI-compatible " +
                                "providers. Use the standard OpenAI input format instead.",
                        ).toString() + "\n",
                )
            return performRawPassthrough(
                spec = ptSpec,
                entry = entry,
                openAI = openAI,
                systemPrompt = systemPrompt,
                messages = messages,
                maxTokens = maxTokens,
                outputPath = outputPath,
                sessionId = callerSid,
            )
        }

        // [T-android-model-use-passthrough-mode GH#72] Standard mode: promote the
        // explicit `extra_body` / `extra_headers` envelope (previously image-
        // path-only) to the chat/responses text path too, and honor a custom
        // absolute `endpoint` path. Explicit envelope only — implicit unknown
        // top-level keys stay image-path-only so the chat schema keeps its
        // OpenAI top-level semantics. Mirrors iOS standard-mode promotion.
        // [T-model-use-passthrough-warnings] `callWarnings` collects every
        // provided-but-ignored/downgraded field (starting with envelope-
        // lookalike warnings from the passthrough parser); `appliedExtras`
        // confirms which passthrough capabilities actually took effect. Both
        // are attached to the final result JSON. Mirrors iOS performRun.
        val callWarnings = ptSpec.warnings.toMutableList()
        val appliedExtras = JSONObject()
        (provider as? com.openminis.app.provider.openai.OpenAIProvider)?.let { openAI ->
            val chatExtra = parseChatExtraBody(inputText, callWarnings)
            if (chatExtra.isNotEmpty()) {
                openAI.chatExtraBody = chatExtra
                appliedExtras.put("extra_body_keys", JSONArray(chatExtra.keys.sorted()))
                Log.i(TAG, "[ModelUseRoute] CHAT-PASSTHROUGH extra_body keys=[${chatExtra.keys.sorted().joinToString(",")}]")
            }
            val chatHdrs = parseExtraHeaders(inputText, callWarnings)
            if (chatHdrs.isNotEmpty()) {
                openAI.chatExtraHeaders = chatHdrs
                appliedExtras.put("extra_headers_keys", JSONArray(chatHdrs.keys.sorted()))
            }
            parseCustomEndpointPath(inputText)?.let { customPath ->
                openAI.absoluteEndpointOverride = customPath
                appliedExtras.put("custom_endpoint", customPath)
                Log.i(TAG, "[ModelUseRoute] CUSTOM-ENDPOINT absolute path=$customPath")
            }
        }

        // [T-android-image-endpoint-mode] Image-output routing (mirrors iOS
        // ModelUseOffloadBridge image branch). For OpenAI-compatible API-key
        // instances whose target model emits images, route through
        // /v1/images/generations per the instance's imageEndpointMode (with
        // auto-probe + cache), instead of the chat-completions path. OAuth
        // instances are excluded — the Codex gpt-image-2 path is handled inside
        // OpenAIProvider's isCodexImageModel branch on the normal sendMessage
        // call below, and a Codex OAuth token can't reach the Images API. On a
        // route-missing failure (auto mode) we fall through to the chat path.
        val imageRouted = tryImageGenerationRoute(
            entry = entry,
            instance = instance,
            provider = provider,
            inputJson = inputText,
            fallbackPromptMessages = nonSystem,
            inputImages = imageParts,
            outputPath = outputPath,
            outputExt = outputExt,
            sessionId = callerSid,
            callWarnings = callWarnings,
        )
        if (imageRouted != null) return attachCallFeedback(imageRouted, callWarnings, appliedExtras)

        val response = try {
            runBlocking {
                provider.sendMessage(
                    messages = messages,
                    systemPrompt = systemPrompt,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    imageParts = imageParts,
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "sendMessage failed for ${entry.model.id}: ${e.message}", e)
            val base = e.message ?: "model_use_failed"
            val hint = imageParamHint(entry)
            val combined = if (hint.isEmpty()) base else "$base\n\n$hint"
            return NativeOffloadResult(
                1,
                JSONObject().put("error", "model_use_failed")
                    .put("message", combined).toString() + "\n",
            )
        }

        // Write output — media-first if the model returned image/audio/video and
        // --output has a media extension, else fall back to text. Mirrors iOS
        // ModelUseOffloadBridge.performRun (non-streaming branch).
        val sessionId = callerSid
        // [T-android-model-use-no-media-fails] A run that had to produce media
        // — the caller asked for a .png/.mp3/... output, or the model can only
        // output images — and got none must FAIL, not exit 0. Otherwise the
        // agent reads success, writes ![](minis://attachments/...) for a file
        // that was never written, and the user sees an image that "exists"
        // but cannot be opened. That included writing the model's text reply
        // INTO the requested out.png, so the phantom even had a file.
        val mediaRequired = (outputPath != null && (isImageExt(outputExt) || isAudioExt(outputExt) || isVideoExt(outputExt))) ||
            ("image" in outputs && "text" !in outputs)
        if (mediaRequired && response.mediaAttachments.isEmpty()) {
            return noMediaResult(entry, response.text, outputPath, endpoint = "chat")
        }
        val mediaFiles = JSONArray()
        val ts = System.currentTimeMillis() / 1000
        val modelSlug = entry.model.id.replace("/", "_")
        if (outputPath != null) {
            // [T-android-model-use-session-scoped-write] Prefer the caller
            // session's own host dir; fall back to the global resolver only when
            // sessionId is null or the path isn't a session-scoped /var/minis
            // subdir. Guaranteed absolute here (relative --output rejected above).
            val hostFile = sessionScopedHostFile(outputPath, sessionId)
                ?: PRootKernel.resolveHostPath(outputPath)
                ?: return NativeOffloadResult(
                    2,
                    "minis-model-use run: cannot resolve --output '$outputPath'\n",
                )
            val firstMedia = response.mediaAttachments.firstOrNull()
            val outputIsMedia = isImageExt(outputExt) || isAudioExt(outputExt) || isVideoExt(outputExt)
            if (firstMedia != null && outputIsMedia) {
                hostFile.parentFile?.mkdirs()
                hostFile.writeBytes(firstMedia.data)
                logModelUseWrite(outputPath, hostFile, sessionId)
                mediaFiles.put(JSONObject().apply {
                    put("type", firstMedia.type.value)
                    put("mime_type", firstMedia.mimeType)
                    put("path", outputPath)
                    put("size", firstMedia.data.size)
                })
            } else {
                Log.d(
                    "ModelUseImage",
                    "handler text fallback: path=$outputPath textLen=${response.text.length} " +
                        "mediaAttachments=${response.mediaAttachments.size} outputIsMedia=$outputIsMedia",
                )
                hostFile.parentFile?.mkdirs()
                hostFile.writeText(response.text)
            }
        } else if (response.mediaAttachments.isNotEmpty()) {
            // [T-android-model-use-session-scoped-write] Auto-save to the caller
            // session's attachments dir, not the global (last-writer-wins) mount.
            val attachDir = sessionScopedHostFile("/var/minis/attachments", sessionId)
                ?: PRootKernel.resolveHostPath("/var/minis/attachments")
            if (attachDir != null) {
                attachDir.mkdirs()
                for ((idx, media) in response.mediaAttachments.withIndex()) {
                    val ext = mimeToExt(media.mimeType)
                    val fileName = "model-use-$modelSlug-$ts-$idx.$ext"
                    val hostFile = File(attachDir, fileName)
                    hostFile.writeBytes(media.data)
                    val responsePath = "/var/minis/attachments/$fileName"
                    logModelUseWrite(responsePath, hostFile, sessionId)
                    mediaFiles.put(JSONObject().apply {
                        put("type", media.type.value)
                        put("mime_type", media.mimeType)
                        put("path", responsePath)
                        put("size", media.data.size)
                    })
                }
            }
        }

        val body = JSONObject().apply {
            put("model", entry.model.id)
            put("text", response.text)
            response.usage?.let { u ->
                put("usage", JSONObject().apply {
                    put("input_tokens", u.inputTokens)
                    put("output_tokens", u.outputTokens)
                })
            }
            if (outputPath != null) put("output_file", outputPath)
            if (mediaFiles.length() > 0) put("media_files", mediaFiles)
        }
        return attachCallFeedback(
            NativeOffloadResult(0, body.toString(2) + "\n"),
            callWarnings, appliedExtras,
        )
    }

    /**
     * [T-android-image-endpoint-mode] Parsed image-generation knobs from the
     * input JSON top level (native /images/generations shape) with
     * `generation_config` as a lower-priority fallback. Mirrors iOS
     * ModelUseOffloadBridge.parseImageGenerationConfig.
     */
    private data class ImageGenConfig(
        val prompt: String? = null,
        val n: Int = 1,
        val size: String? = null,
        val quality: String? = null,
        val endpointOverride: com.openminis.app.data.model.ImageEndpointMode? = null,
    )

    private fun parseImageGenConfig(inputJson: String): ImageGenConfig {
        val obj = try {
            val t = inputJson.trim()
            if (t.startsWith("{")) JSONObject(t) else null
        } catch (_: Throwable) { null } ?: return ImageGenConfig()

        var prompt: String? = null
        var n = 1
        var size: String? = null
        var quality: String? = null

        // generation_config first (lower priority).
        obj.optJSONObject("generation_config")?.let { gc ->
            val gn = if (gc.has("n")) gc.optInt("n", 1)
            else if (gc.has("number_of_images")) gc.optInt("number_of_images", 1) else 1
            n = maxOf(1, gn)
            size = gc.safeOptString("size", "").ifEmpty { gc.safeOptString("image_size", "").ifEmpty { null } }
            quality = gc.safeOptString("quality", "").ifEmpty { null }
        }
        // Top-level overrides.
        obj.safeOptString("prompt", "").takeIf { it.isNotEmpty() }?.let { prompt = it }
        if (obj.has("n")) n = maxOf(1, obj.optInt("n", n))
        obj.safeOptString("size", "").takeIf { it.isNotEmpty() }?.let { size = it }
        obj.safeOptString("quality", "").takeIf { it.isNotEmpty() }?.let { quality = it }

        // Endpoint override (image_endpoint / endpoint / generation_config.image_endpoint).
        val rawEndpoint = obj.safeOptString("image_endpoint", "").ifEmpty {
            obj.safeOptString("endpoint", "").ifEmpty {
                obj.optJSONObject("generation_config")?.safeOptString("image_endpoint", "").orEmpty()
            }
        }
        val endpoint = when (rawEndpoint.lowercase()) {
            "auto" -> com.openminis.app.data.model.ImageEndpointMode.auto
            "images_generations", "images-generations", "images-gen" ->
                com.openminis.app.data.model.ImageEndpointMode.imagesGenerations
            "chat_completions", "chat-completions", "chat" ->
                com.openminis.app.data.model.ImageEndpointMode.chatCompletions
            else -> null
        }
        return ImageGenConfig(prompt, n, size, quality, endpoint)
    }

    /**
     * [T-android-model-use-image-passthrough GH#62] Provider-specific extras
     * forwarded verbatim to the /images/generations request.
     */
    private data class ImagePassthrough(
        val body: Map<String, Any?> = emptyMap(),
        val headers: Map<String, String> = emptyMap(),
        val path: String? = null,
    )

    /**
     * Keys the handler already interprets itself — never treated as implicit
     * passthrough body fields, so existing callers' structure/behavior is
     * unchanged. (Explicit `extra_body` can still re-introduce any of these.)
     * Mirrors iOS ModelUseOffloadBridge.imageReservedKeys.
     */
    private val imageReservedKeys: Set<String> = setOf(
        "messages", "model", "chat_model", "prompt", "n", "number_of_images",
        "size", "image_size", "quality", "generation_config", "endpoint",
        "image_endpoint", "endpoint_path", "extra_body", "extra_headers",
        "stream", "temperature", "max_tokens",
    )

    /**
     * [T-android-model-use-image-passthrough GH#62] Parse passthrough extras from
     * the input JSON. Two complementary modes, both fully optional and additive
     * (absent → empty, old calls unaffected):
     *   • Explicit envelope: `extra_body` (object), `extra_headers` (string map),
     *     `endpoint_path` (string). Unambiguous; recommended in help.
     *   • Implicit: any TOP-LEVEL key not in [imageReservedKeys] is folded into
     *     the body — so `{"prompt":..,"image":"data:..","size":"2K"}` just
     *     works for Seedream-style image-to-image without an envelope.
     * Explicit `extra_body` wins over implicit keys on conflict. Values are kept
     * as raw org.json types so JSONObject.put() re-serializes them faithfully.
     * Mirrors iOS ModelUseOffloadBridge.parseImagePassthrough.
     */
    private fun parseImagePassthrough(
        inputJson: String,
        warnings: MutableList<String> = mutableListOf(),
    ): ImagePassthrough {
        val obj = try {
            val t = inputJson.trim()
            if (t.startsWith("{")) JSONObject(t) else null
        } catch (_: Throwable) { null } ?: return ImagePassthrough()

        val body = LinkedHashMap<String, Any?>()
        // Implicit: unknown top-level keys → body.
        for (key in obj.keys()) {
            if (key in imageReservedKeys) continue
            body[key] = obj.opt(key)
        }
        // Explicit envelope overrides/augments the implicit keys.
        // [T-model-use-passthrough-warnings] Type mismatches warn instead of
        // silently skipping (deduped against the standard-mode parser's copy).
        obj.opt("extra_body")?.takeIf { it != JSONObject.NULL }?.let { raw ->
            if (raw is JSONObject) {
                for (key in raw.keys()) body[key] = raw.opt(key)
            } else {
                warnings.add(
                    "extra_body is not a JSON object (actual type: ${jsonTypeName(raw)}) — ignored. " +
                        "Wrap your fields in an object: \"extra_body\":{...}.",
                )
            }
        }
        val headers = LinkedHashMap<String, String>()
        obj.opt("extra_headers")?.takeIf { it != JSONObject.NULL }?.let { raw ->
            if (raw is JSONObject) {
                for (key in raw.keys()) {
                    val v = raw.opt(key)
                    if (v is String) {
                        headers[key] = v
                    } else {
                        warnings.add(
                            "extra_headers.$key value is not a string (actual type: ${jsonTypeName(v)}) — " +
                                "this header was ignored. Header values must be JSON strings.",
                        )
                    }
                }
            } else {
                warnings.add(
                    "extra_headers is not a JSON object (actual type: ${jsonTypeName(raw)}) — " +
                        "all custom headers were ignored.",
                )
            }
        }
        val path = obj.safeOptString("endpoint_path", "").trim().takeIf { it.isNotEmpty() }
        return ImagePassthrough(body, headers, path)
    }

    // MARK: - Call feedback [T-model-use-passthrough-warnings]

    /** Human-readable JSON type name for warning messages. Mirrors iOS jsonTypeName. */
    private fun jsonTypeName(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is String -> "string"
        is Boolean -> "bool"
        is Number -> "number"
        is JSONArray -> "array"
        is JSONObject -> "object"
        else -> v.javaClass.simpleName
    }

    /**
     * Append collected `warnings` and `applied_extras` to a result's JSON body.
     * Both are omitted when empty (no noise on clean calls); warnings are
     * de-duplicated (the same type mismatch can be seen by more than one
     * parser on the image path). Non-JSON outputs pass through unchanged.
     * Mirrors iOS ModelUseOffloadBridge.attachCallFeedback.
     */
    private fun attachCallFeedback(
        result: NativeOffloadResult,
        warnings: List<String>,
        extras: JSONObject?,
    ): NativeOffloadResult {
        if (warnings.isEmpty() && (extras == null || extras.length() == 0)) return result
        val body = try {
            val t = result.output.trim()
            if (t.startsWith("{")) JSONObject(t) else null
        } catch (_: Throwable) { null } ?: return result
        if (warnings.isNotEmpty()) {
            val merged = LinkedHashSet<String>()
            body.optJSONArray("warnings")?.let { arr ->
                for (i in 0 until arr.length()) arr.optString(i)?.let { merged.add(it) }
            }
            merged.addAll(warnings)
            body.put("warnings", JSONArray(merged.toList()))
        }
        if (extras != null && extras.length() > 0 && !body.has("applied_extras")) {
            body.put("applied_extras", extras)
        }
        return NativeOffloadResult(result.exitCode, body.toString(2) + "\n")
    }

    // MARK: - Passthrough mode [T-android-model-use-passthrough-mode]

    /**
     * Explicit passthrough envelope: verbatim body/headers/endpoint, raw
     * (unparsed) response output. Mirrors iOS ModelUseOffloadBridge.PassthroughSpec.
     */
    private data class PassthroughSpec(
        val active: Boolean = false,
        val endpoint: String? = null,        // "/abs/path?q" (absolute) or "rel/segment"
        val method: String = "POST",
        val headers: Map<String, String> = emptyMap(),
        val body: Map<String, Any?> = emptyMap(),
        val bodyMode: String = "merge",      // "merge" | "replace"
        /** [T-model-use-passthrough-warnings] Ignored/downgraded-field feedback. */
        val warnings: List<String> = emptyList(),
    )

    /**
     * Parse the top-level `passthrough` object into a [PassthroughSpec].
     * Mirrors iOS ModelUseOffloadBridge.parsePassthroughEnvelope.
     */
    private fun parsePassthroughEnvelope(inputJson: String): PassthroughSpec {
        val obj = try {
            val t = inputJson.trim()
            if (t.startsWith("{")) JSONObject(t) else null
        } catch (_: Throwable) { null } ?: return PassthroughSpec()

        val warnings = mutableListOf<String>()
        val envRaw: Any? = obj.opt("passthrough")
        val env = envRaw as? JSONObject
        if (env == null) {
            // [T-model-use-passthrough-warnings] Exact key absent or wrong
            // type — surface the two silent failure shapes. Mirrors iOS.
            if (envRaw != null && envRaw != JSONObject.NULL) {
                warnings.add(
                    "Top-level 'passthrough' is not a JSON object (actual type: ${jsonTypeName(envRaw)}) — " +
                        "it was ignored and this call ran in STANDARD mode. " +
                        "Expected shape: {\"passthrough\":{\"endpoint\":...,\"body\":{...}}}.",
                )
                return PassthroughSpec(warnings = warnings)
            }
            val envelopeKeys = setOf("endpoint", "body", "body_mode", "headers", "method")
            for (key in obj.keys()) {
                if (key == "passthrough" || key in imageReservedKeys) continue
                val v = obj.opt(key) as? JSONObject ?: continue
                val normalized = key.lowercase().replace("_", "").replace("-", "")
                val looksLikeName = normalized in setOf("passthrough", "passthru", "passthough", "pathrough")
                val looksLikeShape = v.keys().asSequence().any { it in envelopeKeys }
                if (looksLikeName || looksLikeShape) {
                    warnings.add(
                        "Top-level key '$key' looks like a passthrough envelope, but the exact key " +
                            "must be lowercase 'passthrough' — it was ignored and this call ran in STANDARD mode.",
                    )
                }
            }
            return PassthroughSpec(warnings = warnings)
        }

        var endpoint: String? = null
        env.opt("endpoint")?.takeIf { it != JSONObject.NULL }?.let { epRaw ->
            val ep = (epRaw as? String)?.trim()
            if (!ep.isNullOrEmpty()) {
                endpoint = ep
            } else {
                warnings.add(
                    "passthrough.endpoint is not a non-empty string (actual type: ${jsonTypeName(epRaw)}) — " +
                        "ignored; the default chat/completions path was used.",
                )
            }
        }
        var method = "POST"
        env.opt("method")?.takeIf { it != JSONObject.NULL }?.let { mRaw ->
            // PATCH included — provider-native update APIs use it. Mirrors iOS.
            val allowed = setOf("GET", "POST", "PUT", "DELETE", "PATCH")
            val m = (mRaw as? String)?.uppercase()
            if (m != null && m in allowed) {
                method = m
            } else {
                val desc = (mRaw as? String) ?: jsonTypeName(mRaw)
                warnings.add("method value '$desc' is not supported (supported: GET/POST/PUT/DELETE/PATCH) — sent as POST.")
            }
        }
        val headers = LinkedHashMap<String, String>()
        env.opt("headers")?.takeIf { it != JSONObject.NULL }?.let { hRaw ->
            if (hRaw is JSONObject) {
                for (key in hRaw.keys()) {
                    val v = hRaw.opt(key)
                    if (v is String) {
                        headers[key] = v
                    } else {
                        warnings.add(
                            "passthrough.headers.$key value is not a string (actual type: ${jsonTypeName(v)}) — " +
                                "this header was ignored. Header values must be JSON strings.",
                        )
                    }
                }
            } else {
                warnings.add(
                    "passthrough.headers is not a JSON object (actual type: ${jsonTypeName(hRaw)}) — " +
                        "all custom headers were ignored.",
                )
            }
        }
        val body = LinkedHashMap<String, Any?>()
        env.opt("body")?.takeIf { it != JSONObject.NULL }?.let { bRaw ->
            if (bRaw is JSONObject) {
                for (key in bRaw.keys()) body[key] = bRaw.opt(key)
            } else {
                warnings.add(
                    "passthrough.body is not a JSON object (actual type: ${jsonTypeName(bRaw)}) — " +
                        "ignored. Wrap your body fields in an object: \"body\":{...}.",
                )
            }
        }
        var bodyMode = "merge"
        env.opt("body_mode")?.takeIf { it != JSONObject.NULL }?.let { bmRaw ->
            val bm = (bmRaw as? String)?.lowercase()
            if (bm != null && bm in setOf("merge", "replace")) {
                bodyMode = bm
            } else {
                val desc = (bmRaw as? String) ?: jsonTypeName(bmRaw)
                warnings.add("body_mode value '$desc' is invalid (only merge/replace are supported) — treated as merge.")
            }
        }
        return PassthroughSpec(true, endpoint, method, headers, bodyMode = bodyMode, body = body, warnings = warnings)
    }

    /**
     * Standard-mode chat `extra_body`: the EXPLICIT envelope only (implicit
     * unknown top-level keys stay image-path-only, per iOS). Values kept as raw
     * org.json types so JSONObject.put re-serializes them faithfully.
     */
    private fun parseChatExtraBody(inputJson: String, warnings: MutableList<String>): Map<String, Any?> {
        val obj = try {
            val t = inputJson.trim()
            if (t.startsWith("{")) JSONObject(t) else null
        } catch (_: Throwable) { null } ?: return emptyMap()
        val raw = obj.opt("extra_body")?.takeIf { it != JSONObject.NULL } ?: return emptyMap()
        val extra = raw as? JSONObject
        if (extra == null) {
            // [T-model-use-passthrough-warnings] type-mismatch feedback
            warnings.add(
                "extra_body is not a JSON object (actual type: ${jsonTypeName(raw)}) — ignored. " +
                    "Wrap your fields in an object: \"extra_body\":{...}.",
            )
            return emptyMap()
        }
        val out = LinkedHashMap<String, Any?>()
        for (key in extra.keys()) out[key] = extra.opt(key)
        return out
    }

    /** Standard-mode `extra_headers` (string map). */
    private fun parseExtraHeaders(inputJson: String, warnings: MutableList<String>): Map<String, String> {
        val obj = try {
            val t = inputJson.trim()
            if (t.startsWith("{")) JSONObject(t) else null
        } catch (_: Throwable) { null } ?: return emptyMap()
        val raw = obj.opt("extra_headers")?.takeIf { it != JSONObject.NULL } ?: return emptyMap()
        val hdrs = raw as? JSONObject
        if (hdrs == null) {
            warnings.add(
                "extra_headers is not a JSON object (actual type: ${jsonTypeName(raw)}) — " +
                    "all custom headers were ignored.",
            )
            return emptyMap()
        }
        val out = LinkedHashMap<String, String>()
        for (key in hdrs.keys()) {
            val v = hdrs.opt(key)
            if (v is String) {
                out[key] = v
            } else {
                warnings.add(
                    "extra_headers.$key value is not a string (actual type: ${jsonTypeName(v)}) — " +
                        "this header was ignored. Header values must be JSON strings.",
                )
            }
        }
        return out
    }

    /**
     * Parse a CUSTOM absolute endpoint path from top-level `endpoint` /
     * `image_endpoint` values. A value starting with "/" is an absolute path
     * replacing the entire URL path after scheme+host. Symbolic values
     * (auto/images-gen/chat) return null. Mirrors iOS parseCustomEndpointPath.
     *
     * Note: on Android these arrive inside the input JSON (not as translated
     * argv), so unlike iOS there is no rootfs-dataPath prefix to strip — the
     * value is used verbatim.
     */
    private fun parseCustomEndpointPath(inputJson: String): String? {
        val obj = try {
            val t = inputJson.trim()
            if (t.startsWith("{")) JSONObject(t) else null
        } catch (_: Throwable) { null } ?: return null
        val raw = obj.safeOptString("image_endpoint", "").ifEmpty { obj.safeOptString("endpoint", "") }
            .trim()
        return if (raw.startsWith("/")) raw else null
    }

    /**
     * Execute a raw passthrough request and build the metadata result. The
     * response is NOT parsed — full raw bytes go to --output (or, when
     * text-decodable and small, inline into the result). An unwritable --output
     * degrades to inline text + `output_error` instead of failing a call whose
     * HTTP request already completed. Mirrors iOS performRawPassthrough.
     */
    private fun performRawPassthrough(
        spec: PassthroughSpec,
        entry: ModelEntry,
        openAI: com.openminis.app.provider.openai.OpenAIProvider,
        systemPrompt: String?,
        messages: List<LLMMessage>,
        maxTokens: Int,
        outputPath: String?,
        sessionId: String?,
    ): NativeOffloadResult {
        // Baseline body for merge mode: the standard OpenAI-shape conversion
        // (messages + system + max_tokens), so callers add ONLY their extras.
        // replace mode: spec.body verbatim (model unlocked — user owns it).
        val warnings = spec.warnings.toMutableList()
        val bodyObject: JSONObject? = if (spec.bodyMode == "replace") {
            if (spec.body.isEmpty()) {
                // Legit for GET-style calls, but a common symptom of body
                // content nested at the wrong JSON level — say so. Mirrors iOS.
                warnings.add(
                    "body_mode=replace but body is empty or missing — this request was sent with NO body. " +
                        "If that is not what you intended, check that your fields are nested inside " +
                        "passthrough.body (not at the top level).",
                )
                null
            } else JSONObject().also {
                for ((k, v) in spec.body) it.put(k, v ?: JSONObject.NULL)
            }
        } else {
            val baseline = JSONObject().put("model", entry.model.id)
            if (messages.isNotEmpty()) {
                val arr = JSONArray()
                if (!systemPrompt.isNullOrEmpty()) {
                    arr.put(JSONObject().put("role", "system").put("content", systemPrompt))
                }
                for (m in messages) {
                    arr.put(JSONObject().put("role", m.role.value).put("content", m.content))
                }
                baseline.put("messages", arr)
                baseline.put("max_tokens", maxTokens)
                baseline.put("stream", false)
            }
            for ((k, v) in spec.body) baseline.put(k, v ?: JSONObject.NULL)
            baseline.put("model", entry.model.id)   // merge mode locks model
            baseline
        }

        val result = try {
            runBlocking {
                openAI.rawPassthroughRequest(
                    endpoint = spec.endpoint,
                    method = spec.method,
                    headers = spec.headers,
                    bodyObject = bodyObject,
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "raw passthrough failed: ${e.message}", e)
            return NativeOffloadResult(
                1,
                JSONObject().put("error", "passthrough_failed")
                    .put("message", e.message ?: "passthrough request failed")
                    .toString() + "\n",
            )
        }

        val out = JSONObject()
            .put("passthrough", true)
            .put("http_status", result.status)
            .put("content_type", result.contentType ?: JSONObject.NULL)
            .put("bytes", result.data.size)
            .put("endpoint_url", result.url)
            .put("body_mode", spec.bodyMode)
            .put("model_id", entry.model.id)

        if (outputPath != null) {
            val hostFile = sessionScopedHostFile(outputPath, sessionId)
                ?: PRootKernel.resolveHostPath(outputPath)
            if (hostFile == null) {
                out.put("output_error", "Could not resolve --output '$outputPath'")
                inlineTextIfPossible(result.data, out)
            } else {
                try {
                    hostFile.parentFile?.mkdirs()
                    hostFile.writeBytes(result.data)
                    logModelUseWrite(outputPath, hostFile, sessionId)
                    out.put("output_file", outputPath)
                } catch (e: Throwable) {
                    // Don't fail the whole call over an unwritable --output — the
                    // request already succeeded. Surface it + fall back to inline.
                    out.put("output_error", "Could not write --output at $outputPath: ${e.message}")
                    inlineTextIfPossible(result.data, out)
                }
            }
        } else {
            inlineTextIfPossible(result.data, out)
        }
        if (result.status < 200 || result.status >= 300) {
            out.put(
                "error_hint",
                "Provider returned HTTP ${result.status}; the raw response body (output file or inline) is unmodified.",
            )
        }
        if (warnings.isNotEmpty()) {
            out.put("warnings", JSONArray(warnings))
        }
        Log.i(TAG, "[ModelUseRoute] raw-passthrough done status=${result.status} bytes=${result.data.size} url=${result.url} warnings=${warnings.size}")
        // Non-2xx → non-zero exit code (raw body still delivered), else 0.
        val code = if (result.status in 200..299) 0 else 1
        return NativeOffloadResult(code, out.toString(2) + "\n")
    }

    /**
     * Inline a UTF-8-decodable response into the result (truncated at 64KB);
     * binary responses get a hint to re-run with --output. Mirrors iOS.
     */
    private fun inlineTextIfPossible(data: ByteArray, out: JSONObject) {
        val text = try { String(data, Charsets.UTF_8).takeIf { data.isEmpty() || it.toByteArray(Charsets.UTF_8).contentEquals(data) } } catch (_: Throwable) { null }
        if (text != null) {
            val capped = if (text.length > 65536) text.substring(0, 65536) + "…[truncated]" else text
            out.put("response_text", capped)
            if (text.length > 65536) out.put("truncated", true)
        } else {
            out.put("hint", "Response is binary (${data.size} bytes) and no --output was given — re-run with --output <path> to save it.")
        }
    }

    /**
     * [T-android-image-endpoint-mode] Heuristic: does this provider error mean
     * the /images/generations route doesn't exist on this base URL? Only then
     * does auto-mode fall back to chat completions — real errors (auth, content
     * moderation, model-not-found) propagate unchanged. Mirrors iOS
     * looksLikeEndpointMissing.
     */
    private fun looksLikeEndpointMissing(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("404") ||
            m.contains("not found") ||
            m.contains("no such endpoint") ||
            m.contains("unknown endpoint") ||
            m.contains("invalid url") ||
            m.contains("method not allowed") ||
            m.contains("unsupported route") ||
            m.contains("405")
    }

    /**
     * [T-android-image-endpoint-mode] Attempt image-output routing through
     * /v1/images/generations. Returns a completed [NativeOffloadResult] when
     * the image path produced output, or null to fall through to the normal
     * chat-completions sendMessage path (chatCompletions mode, auto-fallback,
     * or "not an image route" — e.g. non-image model / OAuth / non-OpenAI).
     * Mirrors iOS ModelUseOffloadBridge step 4.
     */
    private fun tryImageGenerationRoute(
        entry: ModelEntry,
        instance: com.openminis.app.data.model.ProviderInstance,
        provider: com.openminis.app.provider.LLMProvider,
        inputJson: String,
        fallbackPromptMessages: List<ParsedMessage>,
        inputImages: List<LLMMessage.ImagePart>,
        outputPath: String?,
        outputExt: String,
        sessionId: String?,
        callWarnings: MutableList<String> = mutableListOf(),
    ): NativeOffloadResult? {
        val outputs = entry.model.outputModalities.orEmpty()
        val wantsImageOutput = "image" in outputs
        val openAI = provider as? com.openminis.app.provider.openai.OpenAIProvider
        // Only OpenAI-compatible API-key instances with an image-output model.
        if (!wantsImageOutput || openAI == null) return null
        val pType = instance.providerType
        if (pType != ProviderType.openAI && pType != ProviderType.openRouter && pType != ProviderType.xAI) return null
        if (instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth) return null

        val cfg = parseImageGenConfig(inputJson)
        val prompt = cfg.prompt
            ?: fallbackPromptMessages.lastOrNull { it.role == "user" }?.content
            ?: ""

        // [T-android-model-use-image-passthrough GH#62] Forward arbitrary
        // provider-specific body fields, an endpoint-path override, and extra
        // headers straight through to the /images/generations request — lets
        // callers drive models our fixed schema never modeled (e.g. Volcengine
        // Seedream image-to-image via the `image` body field) with no persisted
        // config. Set on the per-call provider so every generateImage call site
        // below picks them up; nothing leaks across runs (the provider is built
        // fresh per model-use invocation).
        val passthrough = parseImagePassthrough(inputJson, callWarnings)
        openAI.imageExtraBody = passthrough.body
        openAI.imagePathOverride = passthrough.path
        openAI.imageExtraHeaders = passthrough.headers
        if (passthrough.body.isNotEmpty() || passthrough.path != null || passthrough.headers.isNotEmpty()) {
            Log.i(
                TAG,
                "[ModelUseRoute] PASSTHROUGH bodyKeys=[${passthrough.body.keys.sorted().joinToString(",")}] " +
                    "pathOverride=${passthrough.path ?: "<nil>"} " +
                    "extraHeaderKeys=[${passthrough.headers.keys.sorted().joinToString(",")}]",
            )
        }

        // A pure image generator (image_output but NOT text_output) can ONLY
        // use the Images API — never let a stale cached chatCompletions pin it
        // to the chat endpoint (which 401s "Missing scopes"). Mixed text+image
        // models keep auto/probe/fallback.
        val isPureImageGenerator = "image" in outputs && "text" !in outputs

        // [T-android-image-edit-endpoint] Input-image requests now route to
        // /v1/images/edits via editImage, matching iOS ModelUseOffloadBridge —
        // the endpoint whose absence made this return image_edit_not_supported.
        // Only a PURE image generator takes that route: a mixed text+image model
        // still declines to chat, which forwards imageParts to the model and is
        // the better channel for "look at this image and answer" requests. That
        // split is the pre-existing guard semantics, deliberately unchanged.
        if (inputImages.isNotEmpty() && !isPureImageGenerator) {
            Log.i(
                TAG,
                "[ModelUseRoute] input images present for ${entry.model.id} (text+image model) — declining images route, falling through to chat so images are forwarded",
            )
            return null
        }

        val effectiveMode = when {
            cfg.endpointOverride != null -> cfg.endpointOverride
            isPureImageGenerator -> com.openminis.app.data.model.ImageEndpointMode.imagesGenerations
            instance.imageEndpointMode == com.openminis.app.data.model.ImageEndpointMode.auto &&
                instance.imageEndpointResolved != null -> instance.imageEndpointResolved!!
            else -> instance.imageEndpointMode
        }
        Log.i(
            TAG,
            "[ModelUseRoute] image route model=${entry.model.id} mode=${instance.imageEndpointMode} " +
                "resolved=${instance.imageEndpointResolved} pure=$isPureImageGenerator override=${cfg.endpointOverride} " +
                "effective=$effectiveMode",
        )

        // chatCompletions → no image route; fall through to normal sendMessage.
        if (effectiveMode == com.openminis.app.data.model.ImageEndpointMode.chatCompletions) return null

        // imagesGenerations (forced/cached/pure) → call directly; a failure here
        // is terminal (no silent fallback) so the caller sees the real error.
        if (effectiveMode == com.openminis.app.data.model.ImageEndpointMode.imagesGenerations) {
            val response = try {
                runBlocking {
                    if (inputImages.isEmpty()) {
                        openAI.generateImage(prompt, cfg.n, cfg.size, cfg.quality)
                    } else {
                        openAI.editImage(prompt, inputImages, cfg.n, cfg.size, cfg.quality)
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "[ModelUseRoute] images/${if (inputImages.isEmpty()) "generations" else "edits"} (forced) failed: ${e.message}", e)
                val hint = imageParamHint(entry)
                val base = e.message ?: "image_generation_failed"
                return NativeOffloadResult(
                    1,
                    JSONObject().put("error", "image_generation_failed")
                        .put("message", if (hint.isEmpty()) base else "$base\n\n$hint")
                        .toString() + "\n",
                )
            }
            return writeImageResult(entry, response, outputPath, outputExt, "images_generations", sessionId)
        }

        // auto mode: probe /images/generations; cache the result; on a
        // route-missing 4xx, cache chatCompletions and fall through to chat.
        val response = try {
            runBlocking {
                if (inputImages.isEmpty()) {
                    openAI.generateImage(prompt, cfg.n, cfg.size, cfg.quality)
                } else {
                    openAI.editImage(prompt, inputImages, cfg.n, cfg.size, cfg.quality)
                }
            }.also {
                providerRepository.setImageEndpointResolved(
                    instance.id, com.openminis.app.data.model.ImageEndpointMode.imagesGenerations,
                )
            }
        } catch (e: Throwable) {
            val msg = e.message ?: ""
            val routeMissing = (e is com.openminis.app.data.model.LLMError.ProviderError ||
                e is com.openminis.app.data.model.LLMError.InvalidApiKey) && looksLikeEndpointMissing(msg)
            if (routeMissing) {
                Log.i(TAG, "[ModelUseRoute] auto probe → route missing (${msg.take(120)}) — caching chat_completions, falling back")
                providerRepository.setImageEndpointResolved(
                    instance.id, com.openminis.app.data.model.ImageEndpointMode.chatCompletions,
                )
                return null // fall through to chat path
            }
            // Real error (auth/moderation/network) — surface it, don't fall through.
            Log.w(TAG, "[ModelUseRoute] auto probe failed (non-route): ${e.message}", e)
            val hint = imageParamHint(entry)
            val base = e.message ?: "image_generation_failed"
            return NativeOffloadResult(
                1,
                JSONObject().put("error", "image_generation_failed")
                    .put("message", if (hint.isEmpty()) base else "$base\n\n$hint")
                    .toString() + "\n",
            )
        }
        return writeImageResult(entry, response, outputPath, outputExt, "images_generations", sessionId)
    }

    /**
     * [T-android-image-endpoint-mode] Write an image-generation [response] to
     * --output (media-first when the path is an image extension) and build the
     * standard model-use result JSON. Shared shape with cmdRun's media-write
     * branch.
     */
    private fun writeImageResult(
        entry: ModelEntry,
        response: com.openminis.app.data.model.LLMResponse,
        outputPath: String?,
        outputExt: String,
        endpointUsed: String,
        sessionId: String?,
    ): NativeOffloadResult {
        val mediaFiles = JSONArray()
        val firstMedia = response.mediaAttachments.firstOrNull()
        // [T-android-model-use-no-media-fails] The image endpoint exists to
        // return an image; an empty list (content filter, quota, provider
        // quirk) is a failure, never a success with a warning attached.
        if (firstMedia == null) {
            return noMediaResult(entry, response.text, outputPath, endpoint = endpointUsed)
        }
        val ts = System.currentTimeMillis() / 1000
        val modelSlug = entry.model.id.replace("/", "_")
        if (outputPath != null) {
            // [T-android-model-use-session-scoped-write] Session-scoped first,
            // global resolver as fallback. --output is absolute here (relative
            // rejected in cmdRun before the API call).
            val hostFile = sessionScopedHostFile(outputPath, sessionId)
                ?: PRootKernel.resolveHostPath(outputPath)
                ?: return NativeOffloadResult(
                    2,
                    "minis-model-use run: cannot resolve --output '$outputPath'\n",
                )
            val outputIsMedia = isImageExt(outputExt)
            if (firstMedia != null && outputIsMedia) {
                hostFile.parentFile?.mkdirs()
                hostFile.writeBytes(firstMedia.data)
                logModelUseWrite(outputPath, hostFile, sessionId)
                mediaFiles.put(JSONObject().apply {
                    put("type", firstMedia.type.value)
                    put("mime_type", firstMedia.mimeType)
                    put("path", outputPath)
                    put("size", firstMedia.data.size)
                })
            } else {
                hostFile.parentFile?.mkdirs()
                hostFile.writeText(response.text)
            }
        } else if (response.mediaAttachments.isNotEmpty()) {
            // [T-android-model-use-session-scoped-write] Auto-save to the caller
            // session's attachments dir, not the global (last-writer-wins) mount.
            val attachDir = sessionScopedHostFile("/var/minis/attachments", sessionId)
                ?: PRootKernel.resolveHostPath("/var/minis/attachments")
            if (attachDir != null) {
                attachDir.mkdirs()
                for ((idx, media) in response.mediaAttachments.withIndex()) {
                    val ext = mimeToExt(media.mimeType)
                    val fileName = "model-use-$modelSlug-$ts-$idx.$ext"
                    val hostFile = File(attachDir, fileName)
                    hostFile.writeBytes(media.data)
                    val responsePath = "/var/minis/attachments/$fileName"
                    logModelUseWrite(responsePath, hostFile, sessionId)
                    mediaFiles.put(JSONObject().apply {
                        put("type", media.type.value)
                        put("mime_type", media.mimeType)
                        put("path", responsePath)
                        put("size", media.data.size)
                    })
                }
            }
        }
        val body = JSONObject().apply {
            put("model", entry.model.id)
            put("text", response.text)
            put("image_endpoint", endpointUsed)
            if (outputPath != null) put("output_file", outputPath)
            if (mediaFiles.length() > 0) put("media_files", mediaFiles)
        }
        return NativeOffloadResult(0, body.toString(2) + "\n")
    }

    /**
     * [T-android-model-use-no-media-fails] Exit 1 with a machine-readable
     * error and the model's own text (usually the refusal / moderation
     * reason), and no file written — so neither the exit code nor the disk
     * suggests an image exists.
     */
    private fun noMediaResult(
        entry: ModelEntry,
        text: String,
        outputPath: String?,
        endpoint: String,
    ): NativeOffloadResult {
        Log.w(TAG, "[ModelUseNoMedia] model=${entry.model.id} endpoint=$endpoint output=$outputPath textLen=${text.length}")
        val body = JSONObject().apply {
            put("error", "no_media_generated")
            put(
                "message",
                "The model returned no image/media (often a content-policy refusal or quota limit). " +
                    "Nothing was saved" + (outputPath?.let { " to $it" } ?: "") +
                    ". Do not reference a generated file; tell the user it failed and why.",
            )
            put("model", entry.model.id)
            put("endpoint", endpoint)
            if (text.isNotBlank()) put("model_text", text.take(2000))
        }
        return NativeOffloadResult(1, body.toString(2) + "\n")
    }

    /**
     * [T-android-model-use-session-scoped-write] Resolve a `/var/minis/<sub>/...`
     * Linux path to the caller session's OWN host directory, bypassing the
     * global (last-writer-wins) PRootKernel.bindMounts map. That global map is
     * overwritten by ExecutionCoordinator.buildSessionBindMounts on every shell
     * build, so PRootKernel.resolveHostPath("/var/minis/attachments") returns
     * whichever session built a shell most recently — a model-use call from
     * session A could then write into session B's attachments dir while the
     * response reports the abstract `/var/minis/attachments/...` path, which A's
     * shell (mounted to A's dir) can't read. Mirrors iOS da4b6c5d, which routes
     * the write to minisAttachmentsPersistentDir(for: callerSid).
     *
     * Session-scoped subdirs are attachments/offloads/workspace/browser (see
     * buildSessionBindMounts). For those, host dir = filesDir/minis-sessions/
     * <sid>/<sub>/<rest>. Returns null when [sessionId] is null (caller then
     * falls back to the global resolveHostPath and logs the degrade) or the path
     * isn't a session-scoped `/var/minis/<sub>` path.
     */
    private fun sessionScopedHostFile(linuxPath: String, sessionId: String?): File? {
        if (sessionId == null) return null
        val m = Regex("^/var/minis/(attachments|offloads|workspace|browser)(/.*)?$").find(linuxPath)
            ?: return null
        val sub = m.groupValues[1]
        val rest = m.groupValues[2].removePrefix("/")
        val base = File(context.filesDir, "minis-sessions/$sessionId/$sub")
        return if (rest.isEmpty()) base else File(base, rest)
    }

    /** [T-android-model-use-session-scoped-write] One-line audit of a model-use
     *  write so the response path vs on-disk host path vs session scoping is
     *  greppable when diagnosing "file disappears / wrong session" reports. */
    private fun logModelUseWrite(responsePath: String, hostFile: File, sessionId: String?) {
        Log.i(
            "ModelUseImage",
            "[ModelUseWrite] path=$responsePath hostFile=${hostFile.absolutePath} " +
                "sessionId=$sessionId",
        )
    }

    /**
     * If [entry] is an image_output model, return a hint listing the params it
     * actually accepts, so a failed sendMessage tells the caller how to retry.
     * Empty string for non-image models.
     */
    private fun imageParamHint(entry: ModelEntry): String {
        if ("image" !in entry.model.outputModalities.orEmpty()) return ""
        val pType = providerRepository.instance(entry.providerInstanceId)?.providerType
        return when (pType) {
            ProviderType.gemini -> """
                Hint — ${entry.model.displayName} is a Gemini image model. Pass image params under `generation_config` in the input JSON:
                  aspect_ratio       "1:1" | "16:9" | "9:16" | "4:3" | "3:4"
                  image_size         "512px" | "1K" | "2K" | "4K"
                  number_of_images   1-4
                  person_generation  "DONT_ALLOW" | "ALLOW_ADULT"
                Example:
                  {"messages":[{"role":"user","content":"<prompt>"}],
                   "generation_config":{"aspect_ratio":"16:9","image_size":"2K"}}
            """.trimIndent()
            // [T-android-provider-type-parity] Same /v1/images/generations
            // shape as OpenAI.
            ProviderType.openAI, ProviderType.openRouter, ProviderType.openAIResponses -> """
                Hint — ${entry.model.displayName} is an OpenAI-compatible image model. Pass image params at the top level of the input JSON (matches /v1/images/generations):
                  n         integer, number of images (default 1)
                  size      "1024x1024" | "1792x1024" | "1024x1792" | etc.
                  quality   "standard" | "hd"
                  prompt    string (overrides last user message)
                Example:
                  {"prompt":"<prompt>","size":"1792x1024","quality":"hd","n":1}

                Passthrough (topics: image-to-image, extra_body, extra_headers, endpoint_path) — any field our schema doesn't model is forwarded VERBATIM into the /images/generations JSON body, so you can drive provider-specific params (e.g. Volcengine Seedream image-to-image via an `image` field, `seed`, `tools`). Two ways, both optional & additive:
                  • implicit: any unknown TOP-LEVEL key → request body
                  • explicit: `extra_body` (object, merged into body), `extra_headers` (string map, added to request headers), `endpoint_path` (string, overrides "/images/generations" for non-standard endpoints)
                Your keys win over our defaults; `model` is always forced to the resolved id. If you set `response_format`, the b64_json auto-probe is skipped.
                Image-to-image example (Seedream — image goes in the BODY, not messages):
                  {"prompt":"make the eyes blue","image":"data:image/png;base64,<...>","size":"2K"}
                Explicit-envelope example:
                  {"messages":[{"role":"user","content":"<prompt>"}],"extra_body":{"image":"<url-or-data-uri>","seed":42},"extra_headers":{"X-Custom":"1"},"endpoint_path":"/api/v3/images/generations"}
            """.trimIndent()
            // xAI (Grok) / Kimi Coding have no image-output models in the
            // current catalog — fall through to empty hint like Anthropic.
            ProviderType.anthropic, ProviderType.xAI, ProviderType.kimiCode,
            // [T-copilot-provider] Copilot exposes no image-output model.
            ProviderType.githubCopilot,
            // [T-android-provider-type-parity] No image-param hint for types
            // this build cannot drive.
            ProviderType.antigravity, ProviderType.unsupported, null -> ""
        }
    }

    private fun isImageExt(ext: String): Boolean =
        ext in setOf("png", "jpg", "jpeg", "webp", "gif", "heic")

    private fun isAudioExt(ext: String): Boolean =
        ext in setOf("wav", "mp3", "m4a", "aac", "ogg", "flac")

    private fun isVideoExt(ext: String): Boolean =
        ext in setOf("mp4", "mov", "webm", "mkv")

    private fun mimeToExt(mime: String): String = when {
        mime.contains("png") -> "png"
        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
        mime.contains("webp") -> "webp"
        mime.contains("gif") -> "gif"
        mime.contains("wav") -> "wav"
        mime.contains("mp3") || mime.contains("mpeg") -> "mp3"
        mime.contains("mp4") -> "mp4"
        mime.contains("ogg") -> "ogg"
        else -> mime.substringAfterLast("/", "bin")
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private fun resolveEntry(idOrName: String, providerFilter: String? = null): ModelEntry? {
        val all = providerRepository.resolvedAgentLoopEntries()

        // Apply --provider filter first: match against instance label (case-insensitive)
        // OR instance UUID. Restricts the lookup pool so the same model_id under
        // multiple providers can be picked unambiguously.
        val pool = if (providerFilter.isNullOrEmpty()) all else {
            val pf = providerFilter.lowercase()
            all.filter { entry ->
                val inst = providerRepository.instance(entry.providerInstanceId)
                inst != null && (inst.label.lowercase() == pf || inst.id.lowercase() == pf)
            }
        }
        if (pool.isEmpty()) return null

        // 0. Qualified `<instance_label>/<model_id>` — split on FIRST '/' only
        //    since model_id itself may contain '/' (e.g. "deepseek-ai/DeepSeek-V4").
        val slash = idOrName.indexOf('/')
        if (slash > 0 && slash < idOrName.length - 1) {
            val labelPart = idOrName.substring(0, slash).lowercase()
            val modelPart = idOrName.substring(slash + 1).lowercase()
            pool.find { entry ->
                val inst = providerRepository.instance(entry.providerInstanceId)
                inst != null &&
                    inst.label.lowercase() == labelPart &&
                    entry.model.id.lowercase() == modelPart
            }?.let { return it }
        }

        // Exact ID
        pool.find { it.model.id == idOrName }?.let { return it }
        // Exact display name
        pool.find { it.model.displayName == idOrName }?.let { return it }
        // Entry UUID
        pool.find { it.id == idOrName }?.let { return it }
        // Case-insensitive prefix / contains
        val q = idOrName.lowercase()
        pool.find { it.model.id.lowercase().startsWith(q) }?.let { return it }
        pool.find { it.model.displayName.lowercase().contains(q) }?.let { return it }
        return null
    }

    private fun providerInstanceLabel(entry: ModelEntry): String =
        providerRepository.instance(entry.providerInstanceId)?.label.orEmpty()

    private fun entryDict(entry: ModelEntry): JSONObject =
        com.openminis.app.offload.ModelUseManager.entryDict(
            entry,
            providerRepository.instance(entry.providerInstanceId),
        )

    /**
     * Modality filter: matches iOS behavior. Terms are comma-separated and
     * checked against input/output modality lists. All requested terms must
     * be satisfied for an entry to pass.
     */
    private fun matchesModality(entry: ModelEntry, filter: String): Boolean {
        val inputs = entry.model.inputModalities.orEmpty()
        val outputs = entry.model.outputModalities.orEmpty()
        val required = filter.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        for (term in required) {
            val ok = when (term) {
                "text", "text_input" -> "text" in inputs || inputs.isEmpty()
                "text_output" -> "text" in outputs || outputs.isEmpty()
                "image", "image_input" -> "image" in inputs
                "image_output" -> "image" in outputs
                "audio", "audio_input" -> "audio" in inputs
                "audio_output" -> "audio" in outputs
                "video", "video_input" -> "video" in inputs
                "video_output" -> "video" in outputs
                "pdf", "pdf_input" -> "pdf" in inputs
                else -> true
            }
            if (!ok) return false
        }
        return true
    }

    private fun readLinuxPath(linuxPath: String): String? {
        val hostFile: File = PRootKernel.resolveHostPath(linuxPath) ?: return null
        if (!hostFile.exists() || !hostFile.isFile) return null
        return try { hostFile.readText() } catch (_: Throwable) { null }
    }

    /**
     * One parsed message from the input JSON, carrying both text content
     * and any image attachments extracted from a content array. Images
     * collected here are forwarded to [LLMProvider.sendMessage]'s
     * `imageParts` parameter (which providers attach to the last user
     * message), not embedded in [LLMMessage.content] — that field stays
     * a plain string so providers don't see a stringified `[{type:...}]`
     * payload they can't parse.
     */
    private data class ParsedMessage(
        val role: String,
        val content: String,
        val images: List<LLMMessage.ImagePart>,
        /** [GH#67] input_audio blocks extracted from the content array. */
        val audios: List<LLMMessage.AudioPart> = emptyList(),
    )

    /**
     * Parse messages from stdin text or a JSON file. Preserves `system`
     * messages so the caller can merge them into systemPrompt. Image
     * parts (OpenAI-style `{"type":"image_url","image_url":{"url":...}}`
     * blocks) are extracted into [ParsedMessage.images] — previously
     * they were silently dropped during the content-array reduction and
     * the model received text-only input, which is why Jackson saw the
     * `NO_IMAGE` response (T-android-model-use-multimodal).
     *
     * Accepted shapes:
     * - `{"messages":[{"role":"user","content":"..."}]}` — OpenAI-style
     * - `[{"role":"...","content":"..."}, ...]` — array of messages
     * - Plain text → wrapped as a single user message
     */
    private fun parseMessages(text: String): List<ParsedMessage> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        try {
            if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                val arr = obj.optJSONArray("messages")
                    ?: return listOf(ParsedMessage("user", trimmed, emptyList()))
                return parseMessageArray(arr)
            }
            if (trimmed.startsWith("[")) {
                return parseMessageArray(JSONArray(trimmed))
            }
        } catch (e: ImageInputError) {
            // Deliberate hard errors from block parsing must NOT be swallowed
            // by the plain-text fallback — otherwise the whole input JSON gets
            // sent to the model as literal text (observed with a malformed
            // input_audio block during GH#67 verification).
            throw e
        } catch (e: AudioInputError) {
            throw e
        } catch (_: Throwable) {
            // Fall through to plain-text handling
        }
        return listOf(ParsedMessage("user", trimmed, emptyList()))
    }

    private fun parseMessageArray(arr: JSONArray): List<ParsedMessage> {
        val out = mutableListOf<ParsedMessage>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val role = m.optString("role", "user").lowercase()
            val textBuf = StringBuilder()
            val imgs = mutableListOf<LLMMessage.ImagePart>()
            val auds = mutableListOf<LLMMessage.AudioPart>()
            when (val c = m.opt("content")) {
                is String -> textBuf.append(c)
                is JSONArray -> {
                    for (j in 0 until c.length()) {
                        val part = c.optJSONObject(j) ?: continue
                        val type = part.optString("type")
                        if (type == "text" || type.isEmpty()) {
                            textBuf.append(part.optString("text", ""))
                        } else if (type == "image_url") {
                            val imgObj = part.optJSONObject("image_url") ?: continue
                            val url = imgObj.optString("url", "").takeIf { it.isNotEmpty() }
                                ?: continue
                            try {
                                imgs.add(resolveImageUrl(url))
                            } catch (e: ImageInputError) {
                                // Don't silently drop — surface to the agent so it
                                // can correct the URL or fall back to text.
                                throw e
                            }
                        } else if (type == "input_audio") {
                            // [GH#67] input_audio was previously skipped with no
                            // trace — the request went out text-only. Parse the
                            // official OpenAI shape; malformed blocks are a hard
                            // input error, not a silent drop. Mirrors iOS
                            // parseOpenAIMessages.
                            val audioObj = part.optJSONObject("input_audio")
                                ?: throw AudioInputError(
                                    "input_audio block is malformed. Expected " +
                                        "{\"type\":\"input_audio\",\"input_audio\":" +
                                        "{\"data\":\"<base64>\",\"format\":\"wav\"}}.",
                                )
                            val b64 = audioObj.optString("data", "")
                            if (b64.isEmpty()) {
                                throw AudioInputError("input_audio.data is missing or empty.")
                            }
                            try {
                                Base64.decode(b64, Base64.DEFAULT)
                            } catch (e: IllegalArgumentException) {
                                throw AudioInputError("input_audio.data is not valid base64: ${e.message}")
                            }
                            val format = audioObj.optString("format", "wav").lowercase()
                            auds.add(LLMMessage.AudioPart(format = format, base64Data = b64))
                        }
                    }
                }
                else -> { /* leave both buffers empty → message skipped below */ }
            }
            if (textBuf.isEmpty() && imgs.isEmpty() && auds.isEmpty()) continue
            out.add(ParsedMessage(role, textBuf.toString(), imgs, auds))
        }
        return out
    }

    /**
     * Resolve an OpenAI-style image_url string to raw bytes + MIME.
     * Mirrors iOS ModelUseOffloadBridge.resolveImageURL (subset that the
     * Android offload path actually needs):
     *
     *  - `data:<mime>;base64,<...>` → inline base64
     *  - `file:///<host-path>` → direct host read
     *  - `/var/minis/<scope>/<path>` or `/<abs/linux/path>` → via
     *    [PRootKernel.resolveHostPath] (which already handles
     *    `/var/minis/` bind mounts longest-prefix)
     *  - `http(s)://` → throw with a hint to download via shell_execute
     *    first (matches iOS — avoids egressing user content)
     *  - anything else (relative paths, unknown schemes) → throw
     *
     * Throws [ImageInputError] on any unresolvable URL so the CLI exits
     * non-zero with a descriptive message instead of silently feeding
     * the model an image-less request (the prior failure mode).
     */
    private fun resolveImageUrl(url: String): LLMMessage.ImagePart {
        // data:<mime>;base64,<base64>
        if (url.startsWith("data:")) {
            val rest = url.substring(5)
            val semi = rest.indexOf(';')
            val comma = rest.indexOf(',')
            if (semi <= 0 || comma <= semi) {
                throw ImageInputError("Malformed data: URL — expected `data:<mime>;base64,<...>`")
            }
            val mime = rest.substring(0, semi)
            val b64 = rest.substring(comma + 1)
            val bytes = try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                throw ImageInputError("data: URL base64 payload failed to decode: ${e.message}")
            }
            return LLMMessage.ImagePart(data = bytes, mimeType = mime)
        }

        // http(s):// — surface the iOS-parity message
        if (url.startsWith("http://") || url.startsWith("https://")) {
            throw ImageInputError(
                "http(s):// image URLs are not supported by minis-model-use. " +
                    "Download first with `shell_execute` (curl/wget) into " +
                    "/var/minis/workspace/, then reference the local path."
            )
        }

        // file:///abs/path → strip prefix, treat as host path; fall back
        // to resolveHostPath so bind-mounted /var/minis/* still works
        // when callers write file:///var/minis/... .
        val linuxPath = when {
            url.startsWith("file://") -> url.removePrefix("file://")
            url.startsWith("/") -> url
            else -> throw ImageInputError(
                "Unsupported image_url '$url'. Use a data: URL, file:///host/path, " +
                    "/var/minis/<scope>/<path>, or an absolute Linux path."
            )
        }
        val hostFile: File = PRootKernel.resolveHostPath(linuxPath)
            ?: File(linuxPath).takeIf { it.exists() && it.isFile }
            ?: throw ImageInputError("Image file not found at '$url'.")
        if (!hostFile.exists() || !hostFile.isFile) {
            throw ImageInputError("Image file not found at '$url' (resolved to ${hostFile.absolutePath}).")
        }
        val bytes = try {
            hostFile.readBytes()
        } catch (e: Throwable) {
            throw ImageInputError("Image file at '$url' is unreadable: ${e.message}")
        }
        return LLMMessage.ImagePart(
            data = bytes,
            mimeType = imageMimeForExtension(linuxPath),
            linuxPath = linuxPath,
        )
    }

    private fun imageMimeForExtension(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "bmp" -> "image/bmp"
            else -> "image/png"
        }
    }

    private class ImageInputError(message: String) : RuntimeException(message)

    /** [GH#67] Malformed/undecodable input_audio block — hard input error. */
    private class AudioInputError(message: String) : RuntimeException(message)

    private fun textMessage(
        role: String,
        content: String,
        audios: List<LLMMessage.AudioPart> = emptyList(),
    ): LLMMessage {
        val r = when (role.lowercase()) {
            "assistant" -> LLMMessage.Role.ASSISTANT
            else -> LLMMessage.Role.USER
        }
        return LLMMessage(role = r, content = content, audioParts = audios)
    }

    companion object {
        private const val TAG = "ModelUseOffload"
        private const val NO_MODELS_HINT =
            "No models available. Go to Settings > Model Groups to add models that the agent can use."

        /** Appended to non-empty list/search results so the agent knows how to invoke a model.
         *  Three forms are supported by `run --model`:
         *    1. Plain `model_id` (e.g. `claude-sonnet-4-6`) — works when unique.
         *    2. `instance_label/model_id` (e.g. `deepseek/deepseek-v4-flash`) — required when
         *       the same model_id is configured under multiple provider instances.
         *    3. `--model <model_id> --provider <instance_label>` — equivalent to (2),
         *       preferred when the model_id itself contains slashes.
         *  `entry_id` (UUID) also works but is opaque; prefer the human-readable forms.
         */
        private const val USAGE_HINT =
            "To invoke a model, pass `--model <model_id>` to `minis-model-use run`. " +
            "If multiple providers expose the same `model_id`, disambiguate either with " +
            "`--model <instance_label>/<model_id>` (e.g. `--model deepseek/deepseek-v4-flash`) " +
            "or with `--model <model_id> --provider <instance_label>` " +
            "(e.g. `--model deepseek-v4-flash --provider deepseek`). " +
            "The opaque `entry_id` (UUID) is also accepted. " +
            // [T-android-model-use-image-passthrough GH#62] Progressive-disclosure
            // breadcrumbs: surface capability keywords up front so the model knows
            // what's possible and which model's per-entry `hint` to read for the
            // exact param shape.
            "Capabilities by modality (run without --model, or inspect a model's `hint` field, " +
            "for the exact JSON shape): text generation, image generation, image-to-image, " +
            "image editing, audio (TTS/STT), embeddings. " +
            "Topics/params you can pass per call: messages, generation_config (size/quality/n/" +
            "aspect_ratio/image_size), image_endpoint, and — for OpenAI-compatible image models — " +
            "PASSTHROUGH via `extra_body`, `extra_headers`, `endpoint_path`, or any unknown " +
            "top-level field (forwarded verbatim; enables provider-specific params like Seedream " +
            "image-to-image). Read the target model's `hint` for concrete examples."

        private const val HELP = """minis-model-use — list, search, and invoke LLM models

Usage:
  minis-model-use list [--provider <name>] [--modality <mod>]
  minis-model-use search <query> [--provider <name>] [--modality <mod>]
  minis-model-use run --model <id_or_name> [--provider <label_or_id>]
                      [--input <path>] [--output <path>]
                      [--system <text>] [--system-file <path>]
                      [--max-tokens N] [--temperature F]

Run model selection:
  --model accepts: model_id, display name, entry_id (UUID), OR the qualified
  form `<instance_label>/<model_id>` (e.g. `deepseek/deepseek-v4-flash`) to
  disambiguate when the same model_id is configured under multiple providers.
  Equivalently, pass `--model <model_id> --provider <instance_label>`.

Input format (OpenAI Chat Completions JSON — the ONLY input format):
  Standard fields (messages, system, temperature, max_tokens,
  generation_config) are AUTOMATICALLY CONVERTED to whatever the selected
  model's provider expects on the wire. Do NOT hand-write provider-native
  bodies as the primary input.

  AUDIO INPUT (audio_input models, e.g. gpt-4o-audio / Qwen-Audio):
    Add an input_audio content block to a user message (official OpenAI
    shape; forwarded verbatim on both the Chat Completions and Responses
    API paths). The model MUST declare the audio_input modality (see
    `list --modality audio`), otherwise the call fails with an explicit
    modality error.
    {"messages":[{"role":"user","content":[
       {"type":"text","text":"Transcribe this audio"},
       {"type":"input_audio","input_audio":{"data":"<base64>",
        "format":"wav"}}]}]}
    NOTE: dedicated STT transcription endpoints (POST
    /v1/audio/transcriptions on speaches / faster-whisper / whisper.cpp
    servers) require multipart/form-data uploads, which this tool does
    NOT support yet — passthrough mode sends JSON bodies only (known
    limitation). For speech-to-text today, use a chat-protocol audio
    model as above.

  Standard-mode extras (OpenAI-compatible providers, all endpoints):
    extra_body     object — merged VERBATIM into the final request body
                   (your keys win; `model` stays locked). Use for provider
                   fields our schema doesn't model, e.g. OpenRouter web
                   search: {"extra_body":{"plugins":[{"id":"web"}]}} — these
                   are NOT converted.
    extra_headers  string map — added to request headers; same-name REPLACES
                   the default (incl. auth headers).
    endpoint       "/abs/path" starting with "/" replaces the ENTIRE path on
                   the provider's own base URL host (credentials never leave
                   the host); body is still auto-converted, response parsed.

Passthrough mode (raw escape hatch; response is NOT parsed):
  For endpoints/formats our schema doesn't model (video gen, TTS,
  provider-native APIs), add a top-level "passthrough" object:
    {"passthrough": {
       "endpoint": "/v1/audio/speech",  // "/abs/path" replaces the whole
                                         // path on the provider base host;
                                         // omit = chat path
       "method":  "POST",               // default POST
       "headers": {"X-Custom": "1"},    // same-name REPLACES defaults
       "body":    {"input": "hi", "voice": "alloy"},
       "body_mode": "replace"           // replace = body IS the whole request
                                        // body; merge (default) = layered
                                        // over the converted OpenAI baseline,
                                        // model locked
    }}
  Passthrough values are sent VERBATIM (no conversion). The response is
  returned RAW: full bytes to --output (required for binary), or inline text
  up to 64KB; the result reports http_status, content_type, bytes and the
  fully-assembled endpoint_url. OpenAI-compatible providers only for now.

Image generation fields (only for image_output models):
  Pass image params either at the top level OR under "generation_config".
  Top level matches OpenAI /v1/images/generations and takes precedence.

  OpenAI-style (DALL-E / gpt-image-1 etc.):
    n         integer, number of images (default 1)
    size      "1024x1024" | "1792x1024" | "1024x1792" | etc.
    quality   "standard" | "hd"
    prompt    string (overrides last user message)

  Gemini-style (Imagen / gemini-2.5-flash-image etc.) — under generation_config:
    aspect_ratio       "1:1" | "16:9" | "9:16" | "4:3" | "3:4"
    image_size         "512px" | "1K" | "2K" | "4K"
    number_of_images   1-4
    person_generation  "DONT_ALLOW" | "ALLOW_ADULT"
  Unknown providers silently ignore unsupported fields.

  Example (OpenAI):
    {"prompt":"a red panda astronaut","size":"1792x1024","quality":"hd","n":1}
  Example (Gemini):
    {"messages":[{"role":"user","content":"a red panda astronaut"}],
     "generation_config":{"aspect_ratio":"16:9","image_size":"2K"}}

Examples:
  minis-model-use list
  minis-model-use list --modality image_input
  minis-model-use search gemini
  minis-model-use run --model claude-sonnet-4-6 --input /var/minis/workspace/prompt.json
  minis-model-use run --model deepseek/deepseek-v4-flash --input msgs.json   # qualified form
  minis-model-use run --model deepseek-v4-flash --provider deepseek --input msgs.json   # equivalent
  echo 'What is 2+2?' | minis-model-use run --model gpt-4o
  minis-model-use run --model gemini-2.5-flash --system 'You are a poet' \
                      --input msgs.json --output /var/minis/workspace/out.txt
"""
    }
}

// ─── Merged from OffloadCallerSession.kt ──────────────────────────────────────────────────

/**
 * [T-offload-caller-session] The chat an offloaded CLI command belongs to.
 * Android port of iOS `ISHExecutionCoordinator.callerSessionId(fsContext:)`
 * as used by minis-model-use and minis-scheduled (7effb6f2f, b99e4da87).
 *
 * A native offload only learns `MINIS_CHAT_SESSION_ID`, the DISPATCH id of the
 * shell that ran it. For a sub agent (helper) that is the helper's own hidden
 * session, but its shell runs with the PARENT's `/var/minis` mounted
 * (T-p2-shared-workspace), exactly as an iOS helper runs under the parent's
 * fsSessionId, where the fs_context lookup therefore names the parent. So the
 * caller is the mounted session: files an offload writes land in the
 * directory the guest (and the parent chat) actually sees, and anything the
 * command creates on the caller's behalf belongs to the chat the user is
 * looking at. For an ordinary chat both ids are the same.
 */
internal object OffloadCallerSession {

    /** [sid] null only when the command came from no chat (interactive terminal). */
    data class Caller(val sid: String?, val source: String)

    fun resolve(
        dispatchSid: String?,
        mountedFor: (String) -> String = ExecutionCoordinator::mountedSessionIdFor,
    ): Caller {
        if (dispatchSid.isNullOrEmpty()) return Caller(null, "none (no chat session)")
        val mounted = mountedFor(dispatchSid)
        return if (mounted != dispatchSid) {
            Caller(mounted, "mounted parent (dispatch=${dispatchSid.take(8)})")
        } else {
            Caller(dispatchSid, "env")
        }
    }
}

// ─── Merged from OffloadGate.kt ──────────────────────────────────────────────────

/**
 * Tri-state agent gate for `android-*` offload CLIs. Sits at the
 * NativeOffloadHandler entry point so every shell invocation of a CLI —
 * regardless of how the LLM constructed the command — passes through
 * the same BYPASS / ASK_ONCE / NOT_ALLOWED policy stored in
 * [OffloadPermissionManager].
 *
 * Why a helper:
 *   * `NativeOffloadHandler.handle` is synchronous (the offload IPC
 *     server invokes it from a worker thread and writes the result
 *     into the guest's tmp file before replying). [checkPermission]
 *     is suspend (ASK_ONCE awaits user input on a coroutine), so we
 *     bridge with `runBlocking`. The block is fine on this thread —
 *     the IPC reply already gates further guest progress on its
 *     return.
 *   * Every handler shares the same prompt-and-cache flow, so the
 *     boilerplate lives here rather than 5+ copy-pastes.
 *
 * Session scope: T340 — prefer the chat session id forwarded by the
 * agent shell via the `MINIS_CHAT_SESSION_ID` env var (surfaced as
 * [NativeOffloadRequest.sessionId]). Falls back to
 * `OFFLOAD_GLOBAL_SESSION_ID` when the offload runs outside a chat
 * (e.g. interactive terminal). This keeps "Allow in this session"
 * grants from leaking across chat sessions.
 */
internal object OffloadGate {
    /**
     * @return true if the CLI may proceed; false → handler should
     * short-circuit with a PERMISSION_DENIED envelope pointing the
     * user to Settings → Permissions.
     */
    fun allow(toolName: String, displayName: String, sessionId: String? = null): Boolean = runBlocking {
        OffloadPermissionManager.checkPermission(
            toolName,
            displayName,
            sessionId ?: OffloadPermissionManager.OFFLOAD_GLOBAL_SESSION_ID,
        )
    }

    /** Overload that pulls the chat session id straight off the request. */
    fun allow(toolName: String, displayName: String, request: NativeOffloadRequest): Boolean =
        allow(toolName, displayName, request.sessionId)

    /**
     * Convenience: gate the call and return null when allowed, or a
     * pre-formatted PERMISSION_DENIED envelope when not. Lets a handler
     * write `enforce(...)?.let { return it }` at the top of `handle`.
     */
    fun enforce(
        toolName: String,
        displayName: String,
        args: OffloadArgs,
        request: NativeOffloadRequest? = null,
    ): NativeOffloadResult? {
        if (allow(toolName, displayName, request?.sessionId)) return null
        val body = JSONObject()
            .put("error", "permission_denied")
            .put("message",
                "Agent is not allowed to use $displayName. Open Settings → Permissions to change.")
            .toString()
        return NativeOffloadResult(126, OffloadOutput.formatBody(body, args) + "\n")
    }
}
