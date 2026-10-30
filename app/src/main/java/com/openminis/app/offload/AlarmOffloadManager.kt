package com.openminis.app.offload

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.PRootKernel
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import rikka.sui.Sui

enum class RepeatMode { ONCE, DAILY, WEEKDAYS }

/**
 * Manages scheduling and cancelling alarms/timers using Android AlarmManager.
 * Alarm metadata is persisted in SharedPreferences as a JSON array so it
 * survives process restarts.
 */
class AlarmOffloadManager(private val context: Context) {

    companion object {
        private const val TAG = "AlarmOffloadManager"
        private const val PREFS_NAME = "minis_alarms_prefs"
        private const val KEY_ALARMS = "alarms_json"
        const val CHANNEL_ID = "minis_alarms"
        private const val CHANNEL_NAME = "Minis Alarms & Timers"

        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_ALARM_LABEL = "alarm_label"
    }

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alarms and timers scheduled by the Minis agent"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    // ── Scheduling ──────────────────────────────────────────────────────

    /**
     * Schedule an alarm at a specific hour:minute.
     * Returns a human-readable confirmation string.
     */
    fun scheduleAlarm(label: String, hour: Int, minute: Int, repeatMode: RepeatMode): String {
        val id = UUID.randomUUID().toString()
        val requestCode = id.hashCode() and 0x7FFFFFFF // positive int

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // If the time is already past today, push to tomorrow
            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val pendingIntent = buildPendingIntent(id, requestCode, label)

        try {
            when (repeatMode) {
                RepeatMode.ONCE -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent,
                    )
                }
                RepeatMode.DAILY -> {
                    alarmManager.setRepeating(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        AlarmManager.INTERVAL_DAY,
                        pendingIntent,
                    )
                }
                RepeatMode.WEEKDAYS -> {
                    // For weekdays we schedule the next weekday occurrence and
                    // re-schedule from the receiver to keep the chain going.
                    skipToNextWeekday(calendar)
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent,
                    )
                }
            }
        } catch (e: SecurityException) {
            AppLogger.error(TAG, "Cannot schedule exact alarm – missing SCHEDULE_EXACT_ALARM permission: ${e.message}")
            return "Error: exact alarm permission not granted. Please allow exact alarms in system settings."
        }

        val entry = JSONObject().apply {
            put("id", id)
            put("requestCode", requestCode)
            put("label", label)
            put("hour", hour)
            put("minute", minute)
            put("repeatMode", repeatMode.name)
            put("type", "alarm")
            put("triggerAtMs", calendar.timeInMillis)
        }
        persistAlarm(entry)

        val timeStr = "%02d:%02d".format(hour, minute)
        return "Alarm scheduled: \"$label\" at $timeStr ($repeatMode) [id=$id]"
    }

    /**
     * Schedule a countdown timer that fires after [durationSec] seconds.
     */
    fun scheduleTimer(label: String, durationSec: Int): String {
        val id = UUID.randomUUID().toString()
        val requestCode = id.hashCode() and 0x7FFFFFFF
        val triggerAt = System.currentTimeMillis() + durationSec * 1000L

        val pendingIntent = buildPendingIntent(id, requestCode, label)

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent,
            )
        } catch (e: SecurityException) {
            AppLogger.error(TAG, "Cannot schedule exact alarm: ${e.message}")
            return "Error: exact alarm permission not granted."
        }

        val entry = JSONObject().apply {
            put("id", id)
            put("requestCode", requestCode)
            put("label", label)
            put("durationSec", durationSec)
            put("type", "timer")
            put("triggerAtMs", triggerAt)
        }
        persistAlarm(entry)

        return "Timer scheduled: \"$label\" in ${durationSec}s [id=$id]"
    }

    // ── Listing / Cancelling ────────────────────────────────────────────

    fun listAlarms(): String {
        // First sweep: drop ONCE alarms / timers whose triggerAtMs has passed.
        // The receiver also cleans up on fire, but if it was killed by an OEM
        // power-saver between schedule and trigger the prefs entry would
        // otherwise stick around forever.
        sweepExpiredOnce()

        val alarms = loadAlarms()
        if (alarms.length() == 0) return "No active alarms or timers."

        val now = System.currentTimeMillis()
        val sb = StringBuilder("Active alarms/timers (${alarms.length()}):\n")
        for (i in 0 until alarms.length()) {
            val a = alarms.getJSONObject(i)
            sb.append("  - [${a.getString("type")}] \"${a.getString("label")}\" id=${a.getString("id")}")
            if (a.has("hour")) {
                sb.append(" at %02d:%02d".format(a.getInt("hour"), a.getInt("minute")))
                sb.append(" (${a.optString("repeatMode", "ONCE")})")
            } else if (a.has("durationSec")) {
                sb.append(" duration=${a.getInt("durationSec")}s")
            }
            // DAILY/WEEKDAYS may have a triggerAtMs from the *first* fire
            // that's now in the past — that's normal (the OS keeps repeating
            // them). Only mark non-repeating entries as [expired]; repeating
            // entries we leave unannotated.
            val triggerAt = a.optLong("triggerAtMs", 0L)
            val mode = a.optString("repeatMode", "ONCE")
            val nonRepeating = a.getString("type") == "timer" || mode == "ONCE"
            if (nonRepeating && triggerAt in 1L..now) {
                sb.append(" [expired]")
            }
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }

    /**
     * Remove ONCE alarms / timers whose triggerAtMs has already passed.
     * Called from [listAlarms] as a cheap GC and from [AlarmReceiver] right
     * after the alarm fires. Idempotent and safe to call repeatedly.
     */
    fun sweepExpiredOnce(): Int {
        val alarms = loadAlarms()
        if (alarms.length() == 0) return 0
        val now = System.currentTimeMillis()
        val kept = JSONArray()
        var removed = 0
        for (i in 0 until alarms.length()) {
            val a = alarms.getJSONObject(i)
            val mode = a.optString("repeatMode", "ONCE")
            val nonRepeating = a.optString("type") == "timer" || mode == "ONCE"
            val triggerAt = a.optLong("triggerAtMs", 0L)
            if (nonRepeating && triggerAt in 1L..now) {
                removed++
                continue
            }
            kept.put(a)
        }
        if (removed > 0) {
            prefs.edit().putString(KEY_ALARMS, kept.toString()).apply()
            AppLogger.info(TAG, "swept $removed expired ONCE alarm(s)/timer(s)")
        }
        return removed
    }

    /**
     * Called from [AlarmReceiver] right after an alarm fires. Looks up the
     * entry in prefs and drops it iff it's a ONCE alarm or a timer; for
     * DAILY/WEEKDAYS the OS re-fires on its own and we keep the entry.
     */
    fun onAlarmFired(alarmId: String) {
        val alarms = loadAlarms()
        for (i in 0 until alarms.length()) {
            val a = alarms.getJSONObject(i)
            if (a.optString("id") != alarmId) continue
            val mode = a.optString("repeatMode", "ONCE")
            val nonRepeating = a.optString("type") == "timer" || mode == "ONCE"
            if (!nonRepeating) {
                AppLogger.info(TAG, "alarm id=$alarmId mode=$mode fired — keeping entry (repeats)")
                return
            }
            alarms.remove(i)
            prefs.edit().putString(KEY_ALARMS, alarms.toString()).apply()
            AppLogger.info(TAG, "alarm id=$alarmId mode=$mode fired — removed from prefs")
            return
        }
        AppLogger.warning(TAG, "alarm id=$alarmId fired but no matching prefs entry")
    }

    fun cancelAlarm(alarmId: String): String {
        val alarms = loadAlarms()
        var found = false

        for (i in 0 until alarms.length()) {
            val a = alarms.getJSONObject(i)
            if (a.getString("id") == alarmId) {
                val requestCode = a.getInt("requestCode")
                val label = a.getString("label")
                val pi = buildPendingIntent(alarmId, requestCode, label)
                alarmManager.cancel(pi)
                pi.cancel()
                alarms.remove(i)
                found = true
                break
            }
        }

        if (!found) return "Error: no alarm found with id=$alarmId"

        prefs.edit().putString(KEY_ALARMS, alarms.toString()).apply()
        return "Alarm cancelled: id=$alarmId"
    }

    /**
     * Cancel every persisted alarm/timer. Mirrors apple-alarm `cancel --all`.
     * Returns the count cancelled (0 is a valid no-op). Each entry's
     * PendingIntent is cancelled individually so the OS-side scheduling is
     * cleared even if prefs become orphaned somehow.
     */
    fun cancelAllAlarms(): Int {
        val alarms = loadAlarms()
        val n = alarms.length()
        for (i in 0 until n) {
            val a = alarms.getJSONObject(i)
            val id = a.optString("id")
            val requestCode = a.optInt("requestCode")
            val label = a.optString("label", "Alarm")
            if (id.isEmpty() || requestCode == 0) continue
            val pi = buildPendingIntent(id, requestCode, label)
            alarmManager.cancel(pi)
            pi.cancel()
        }
        prefs.edit().remove(KEY_ALARMS).apply()
        return n
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun buildPendingIntent(id: String, requestCode: Int, label: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, id)
            putExtra(EXTRA_ALARM_LABEL, label)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun skipToNextWeekday(cal: Calendar) {
        while (true) {
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            if (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY) break
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
    }

    private fun loadAlarms(): JSONArray {
        val raw = prefs.getString(KEY_ALARMS, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun persistAlarm(entry: JSONObject) {
        val alarms = loadAlarms()
        alarms.put(entry)
        prefs.edit().putString(KEY_ALARMS, alarms.toString()).apply()
    }
}

// ─── Merged from CalendarManager.kt ──────────────────────────────────────────────────

/**
 * Calendar read/write bridge using CalendarProvider.
 * Requires READ_CALENDAR and WRITE_CALENDAR permissions.
 */
object CalendarManager {

    fun hasPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun hasWritePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * List upcoming events within the given time range (millis).
     */
    fun listEvents(context: Context, startMs: Long, endMs: Long, maxResults: Int = 50): String {
        if (!hasPermission(context)) return "Error: READ_CALENDAR permission not granted"

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.ALL_DAY,
        )

        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(startMs.toString(), endMs.toString())
        val sortOrder = "${CalendarContract.Events.DTSTART} ASC"

        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection, selection, selectionArgs, sortOrder
        ) ?: return "Error: failed to query calendar"

        val events = JSONArray()
        cursor.use {
            var count = 0
            while (it.moveToNext() && count < maxResults) {
                val event = JSONObject()
                event.put("id", it.getLong(0))
                event.put("title", it.getString(1) ?: "")
                event.put("start_ms", it.getLong(2))
                event.put("end_ms", it.getLong(3))
                event.put("location", it.getString(4) ?: "")
                event.put("description", it.getString(5) ?: "")
                event.put("all_day", it.getInt(6) == 1)
                events.put(event)
                count++
            }
        }

        return if (events.length() == 0) "No events found in the given time range."
        else events.toString(2)
    }

    /**
     * Create a calendar event.
     */
    fun createEvent(
        context: Context,
        title: String,
        startMs: Long,
        endMs: Long,
        description: String? = null,
        location: String? = null,
        allDay: Boolean = false,
    ): String {
        if (!hasWritePermission(context)) return "Error: WRITE_CALENDAR permission not granted"

        // Get the primary calendar ID
        val calendarId = getPrimaryCalendarId(context)
            ?: return "Error: no calendar found on device"

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (description != null) put(CalendarContract.Events.DESCRIPTION, description)
            if (location != null) put(CalendarContract.Events.EVENT_LOCATION, location)
            if (allDay) put(CalendarContract.Events.ALL_DAY, 1)
        }

        return try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) "Created event '$title' (id: ${uri.lastPathSegment})"
            else "Error: failed to create event"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun getPrimaryCalendarId(context: Context): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.IS_PRIMARY} = 1"
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection, selection, null, null
        )
        cursor?.use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        // Fallback: first writable calendar
        val fallbackCursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection, null, null, null
        )
        fallbackCursor?.use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        return null
    }
}

// ─── Merged from HealthManager.kt ──────────────────────────────────────────────────

/**
 * Health data bridge using Android Health Connect API (Android 14+).
 * Provides read access to steps, heart rate, sleep, and exercise data.
 *
 * NOTE: Health Connect SDK dependency and runtime permission checks required.
 * This is a stub implementation — full integration requires adding
 * `androidx.health.connect:connect-client` dependency and requesting
 * `android.permission.health.READ_STEPS` etc.
 */
object HealthManager {

    fun isAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= 34 // Health Connect available on Android 14+
    }

    /**
     * Check if Health Connect is available and permissions are granted.
     */
    fun status(context: Context): String {
        if (!isAvailable()) {
            return "Health Connect requires Android 14 or later. Current: API ${Build.VERSION.SDK_INT}"
        }
        return "Health Connect available. Use the Health Connect app to grant permissions."
    }

    /**
     * Read step count for a date range.
     * Stub — requires Health Connect SDK integration.
     */
    fun readSteps(context: Context, startMs: Long, endMs: Long): String {
        if (!isAvailable()) return "Error: Health Connect not available"
        return "Health Connect integration pending. Add 'androidx.health.connect:connect-client' " +
            "dependency and implement HealthConnectClient usage."
    }

    /**
     * Read heart rate samples for a date range.
     * Stub — requires Health Connect SDK integration.
     */
    fun readHeartRate(context: Context, startMs: Long, endMs: Long): String {
        if (!isAvailable()) return "Error: Health Connect not available"
        return "Health Connect integration pending."
    }
}

// ─── Merged from MediaPlayerManager.kt ──────────────────────────────────────────────────

/**
 * Manages multiple concurrent MediaPlayer sessions keyed by session ID.
 * Supports audio playback with play/pause/resume/seek/stop/status operations.
 * Paths are resolved through PRootKernel.resolveHostPath().
 */
object MediaPlayerManager {

    private const val TAG = "MediaPlayerManager"

    private data class Session(
        val player: MediaPlayer,
        val filePath: String,
        val mediaType: String, // "audio" or "video"
        var state: PlayerState = PlayerState.IDLE,
    )

    enum class PlayerState { IDLE, PLAYING, PAUSED, STOPPED }

    private val sessions = mutableMapOf<String, Session>()

    private val audioExtensions = setOf(
        "mp3", "wav", "ogg", "flac", "aac", "m4a", "wma", "opus", "amr", "mid", "midi",
    )
    private val videoExtensions = setOf(
        "mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "wmv", "ts", "m4v",
    )

    /**
     * Start playback of a file in a new or existing session.
     * If the session already exists, the previous player is released first.
     */
    fun play(sessionId: String, filePath: String): String {
        // Stop existing session if any
        sessions[sessionId]?.let { existing ->
            releaseSession(existing)
        }

        val hostFile = PRootKernel.resolveHostPath(filePath)
            ?: return "Error: cannot resolve path '$filePath'"

        if (!hostFile.exists()) {
            return "Error: file not found at '$filePath' (resolved: ${hostFile.absolutePath})"
        }

        val ext = hostFile.extension.lowercase()
        val mediaType = when {
            audioExtensions.contains(ext) -> "audio"
            videoExtensions.contains(ext) -> "video"
            else -> "audio" // default to audio for unknown extensions
        }

        if (mediaType == "video") {
            return "Error: video playback requires a SurfaceView and is not supported in headless mode. " +
                "Only audio files can be played."
        }

        return try {
            val player = MediaPlayer().apply {
                setDataSource(hostFile.absolutePath)
                prepare()
                start()
            }

            val session = Session(
                player = player,
                filePath = filePath,
                mediaType = mediaType,
                state = PlayerState.PLAYING,
            )

            player.setOnCompletionListener {
                Log.d(TAG, "Playback completed: session=$sessionId")
                session.state = PlayerState.STOPPED
            }

            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: session=$sessionId what=$what extra=$extra")
                session.state = PlayerState.STOPPED
                true
            }

            sessions[sessionId] = session

            val durationMs = player.duration
            val durationStr = formatDuration(durationMs)
            "Playing '$filePath' (session=$sessionId, type=$mediaType, duration=$durationStr)"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play $filePath", e)
            "Error: failed to play '$filePath': ${e.message}"
        }
    }

    fun pause(sessionId: String): String {
        val session = sessions[sessionId]
            ?: return "Error: no session '$sessionId'"

        if (session.state != PlayerState.PLAYING) {
            return "Error: session '$sessionId' is not playing (state=${session.state})"
        }

        return try {
            session.player.pause()
            session.state = PlayerState.PAUSED
            val pos = formatDuration(session.player.currentPosition)
            "Paused session '$sessionId' at $pos"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun resume(sessionId: String): String {
        val session = sessions[sessionId]
            ?: return "Error: no session '$sessionId'"

        if (session.state != PlayerState.PAUSED) {
            return "Error: session '$sessionId' is not paused (state=${session.state})"
        }

        return try {
            session.player.start()
            session.state = PlayerState.PLAYING
            val pos = formatDuration(session.player.currentPosition)
            "Resumed session '$sessionId' from $pos"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun seek(sessionId: String, positionMs: Int): String {
        val session = sessions[sessionId]
            ?: return "Error: no session '$sessionId'"

        if (session.state == PlayerState.STOPPED || session.state == PlayerState.IDLE) {
            return "Error: session '$sessionId' is ${session.state} – cannot seek"
        }

        return try {
            session.player.seekTo(positionMs)
            val pos = formatDuration(positionMs)
            "Seeked session '$sessionId' to $pos"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun stop(sessionId: String): String {
        val session = sessions[sessionId]
            ?: return "Error: no session '$sessionId'"

        releaseSession(session)
        sessions.remove(sessionId)
        return "Stopped and released session '$sessionId'"
    }

    fun status(sessionId: String): String {
        val session = sessions[sessionId]
            ?: return "Error: no session '$sessionId'"

        return try {
            val pos = if (session.state == PlayerState.STOPPED || session.state == PlayerState.IDLE) {
                "N/A"
            } else {
                formatDuration(session.player.currentPosition)
            }
            val dur = if (session.state == PlayerState.STOPPED || session.state == PlayerState.IDLE) {
                "N/A"
            } else {
                formatDuration(session.player.duration)
            }
            "Session '$sessionId': state=${session.state}, file=${session.filePath}, " +
                "type=${session.mediaType}, position=$pos, duration=$dur"
        } catch (e: Exception) {
            "Session '$sessionId': state=${session.state}, file=${session.filePath} (player error: ${e.message})"
        }
    }

    fun listSessions(): String {
        if (sessions.isEmpty()) return "No active media sessions."

        val sb = StringBuilder("Active media sessions (${sessions.size}):\n")
        for ((id, session) in sessions) {
            sb.append("  - $id: state=${session.state}, file=${session.filePath}, type=${session.mediaType}\n")
        }
        return sb.toString().trimEnd()
    }

    // ── Internal ────────────────────────────────────────────────────────

    private fun releaseSession(session: Session) {
        try {
            if (session.state == PlayerState.PLAYING || session.state == PlayerState.PAUSED) {
                session.player.stop()
            }
            session.player.release()
            session.state = PlayerState.STOPPED
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing player: ${e.message}")
        }
    }

    private fun formatDuration(ms: Int): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }
}

// ─── Merged from MinisNotificationListenerService.kt ──────────────────────────────────────────────────

/**
 * Listens for active status-bar notifications so the agent can read them.
 *
 * Requires the user to manually grant "Notification access" in system
 * settings (Android enforces this — it can't be requested via the normal
 * runtime permission flow). [isEnabled] / [openSettings] help the caller
 * guide the user through that one-time grant.
 */
class MinisNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        connected = true
    }

    override fun onListenerDisconnected() {
        instance = null
        connected = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // The system fans posts to bound listeners on its own thread, so a
        // notification we just sent via `NotificationManager.notify` won't
        // appear in `getActiveNotifications()` synchronously. `handleSend`
        // registers a latch keyed by (package, id) here so it can block
        // until the listener has actually observed its own post.
        val key = postKey(sbn.packageName, sbn.id)
        postedLatches.remove(key)?.countDown()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    companion object {
        private const val TAG = "NotifListener"

        @Volatile private var connected: Boolean = false
        @Volatile private var instance: MinisNotificationListenerService? = null

        /** Latches awaiting a specific (package, id) post. */
        private val postedLatches = ConcurrentHashMap<String, CountDownLatch>()

        private fun postKey(pkg: String, id: Int): String = "$pkg#$id"

        /** True once the system has bound our listener (implies access granted). */
        fun isConnected(): Boolean = connected

        /**
         * Block (up to [timeoutMs]) until [onNotificationPosted] fires for
         * `(pkg, id)`. Returns true if observed, false on timeout. If the
         * post has *already* arrived (visible in `activeNotifications`),
         * returns true immediately. Used by `android-notification send` so
         * a follow-up `list` always sees the freshly-posted notification.
         */
        fun awaitPosted(pkg: String, id: Int, timeoutMs: Long = 2_000L): Boolean {
            val svc = instance ?: run {
                AppLogger.warning(TAG, "awaitPosted($pkg#$id) called but listener not connected")
                return false
            }
            // Already there? `activeNotifications` is a snapshot of what the
            // system has fanned out to us, so a hit here means the post has
            // already propagated and no waiting is needed.
            try {
                if (svc.activeNotifications.any { it.packageName == pkg && it.id == id }) {
                    return true
                }
            } catch (_: SecurityException) {
                // Listener bound but not yet authorized — fall through to wait.
            }
            val key = postKey(pkg, id)
            val latch = postedLatches.computeIfAbsent(key) { CountDownLatch(1) }
            // Re-check after registering the latch — closes the race where the
            // post lands between our snapshot read and the latch insertion.
            try {
                if (svc.activeNotifications.any { it.packageName == pkg && it.id == id }) {
                    postedLatches.remove(key)
                    return true
                }
            } catch (_: SecurityException) {}
            val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            postedLatches.remove(key)
            if (!ok) AppLogger.warning(TAG, "awaitPosted($key) timed out after ${timeoutMs}ms")
            return ok
        }

        /**
         * Returns the live list of active status-bar notifications across all
         * apps, or null if the listener isn't bound yet. Unlike
         * `NotificationManager.activeNotifications` (which only returns this
         * app's own notifications), this reads via `NotificationListenerService`
         * which has access to everyone else's.
         */
        fun getActiveNotifications(): Array<StatusBarNotification>? {
            return try {
                instance?.activeNotifications
            } catch (_: SecurityException) {
                null
            }
        }

        /** True if the user has granted Notification Access for this app. */
        fun isEnabled(context: Context): Boolean {
            val pkg = context.packageName
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            return flat.split(':').any { entry ->
                val cn = ComponentName.unflattenFromString(entry) ?: return@any false
                cn.packageName == pkg
            }
        }

        /** Intent action for opening the Notification Access settings page. */
        const val SETTINGS_ACTION: String = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
    }
}

// ─── Merged from ModelUseManager.kt ──────────────────────────────────────────────────

/**
 * Provides model listing and search capabilities for the model_use tool.
 * Only surfaces the Agent-Loop-visible subset (mirrors iOS
 * ProviderConfigStore.resolvedAgentLoopEntries). Models not exposed in
 * Settings > Model Groups > "Available Models in Agent Loop" are hidden.
 *
 * Output shape mirrors iOS `ModelUseOffloadBridge.entryDict` so the agent
 * sees the same fields on both platforms — most importantly `modalities`,
 * which it needs to pick an image-generation / TTS model.
 */
object ModelUseManager {

    private const val NO_MODELS_HINT =
        "No models available. Go to Settings > Model Groups > Available Models in Agent Loop to expose models to the agent."

    /**
     * List the agent-loop-visible models, optionally filtered by a free-text
     * query that matches against model id, display name, or provider.
     */
    fun listModels(
        entries: List<ModelEntry>,
        instances: Map<String, ProviderInstance> = emptyMap(),
        filter: String? = null,
    ): String {
        val filtered = if (filter.isNullOrBlank()) entries else {
            val q = filter.lowercase()
            entries.filter {
                it.model.id.lowercase().contains(q) ||
                    it.model.displayName.lowercase().contains(q) ||
                    it.model.provider.lowercase().contains(q)
            }
        }

        if (filtered.isEmpty()) {
            return if (filter != null) "No models matching '$filter'."
            else NO_MODELS_HINT
        }

        val result = JSONArray()
        for (entry in filtered) {
            result.put(entryDict(entry, instances[entry.providerInstanceId]))
        }
        return result.toString(2)
    }

    /**
     * Emit the per-entry JSON. Field names match iOS exactly:
     * entry_id, model_id, display_name, provider, instance_label,
     * provider_type, modalities, context_window.
     */
    fun entryDict(entry: ModelEntry, instance: ProviderInstance?): JSONObject {
        // Enrich at read time — entries persisted before the modalities field
        // existed won't have inputModalities/outputModalities populated, so
        // look them up from the bundled models.dev registry on the fly.
        val enrichedModel = com.openminis.app.provider.ModelsDevApi.enrichModel(entry.model)
        val modalities = JSONArray()
        val inputs = enrichedModel.inputModalities.orEmpty()
        val outputs = enrichedModel.outputModalities.orEmpty()
        // Fallback when neither models.dev nor pattern inference populated them —
        // every LLM supports text in/out.
        val inputSet = if (inputs.isEmpty() && outputs.isEmpty()) listOf("text") else inputs
        val outputSet = if (inputs.isEmpty() && outputs.isEmpty()) listOf("text") else outputs
        if ("text" in inputSet) modalities.put("text_input")
        if ("text" in outputSet) modalities.put("text_output")
        if ("image" in inputSet) modalities.put("image_input")
        if ("image" in outputSet) modalities.put("image_output")
        if ("audio" in inputSet) modalities.put("audio_input")
        if ("audio" in outputSet) modalities.put("audio_output")
        if ("video" in inputSet) modalities.put("video_input")
        if ("video" in outputSet) modalities.put("video_output")
        if ("pdf" in inputSet) modalities.put("pdf_input")

        return JSONObject().apply {
            put("entry_id", entry.id)
            put("model_id", entry.model.id)
            put("display_name", entry.model.displayName)
            put("provider", entry.model.provider)
            put("instance_label", instance?.label ?: "unknown")
            put("provider_type", instance?.providerType?.displayName ?: "unknown")
            put("modalities", modalities)
            // [OpenMinis#438] The entry's own value first (the user's override,
            // or what the endpoint reported); the catalog only fills a gap.
            (entry.model.contextWindow ?: enrichedModel.contextWindow)?.let { put("context_window", it) }
        }
    }
}

// ─── Merged from ScheduledNotificationStore.kt ──────────────────────────────────────────────────

/**
 * Lightweight SharedPreferences-backed registry of currently-scheduled
 * `android-notification schedule` notifications.
 *
 * Used so that:
 *   - `android-notification pending` can list what's still queued
 *   - `android-notification cancel --id` can remove a specific entry
 *   - the receiver can drop entries on fire so they don't linger
 *
 * The app already maintains a similar prefs registry for alarms in
 * AlarmOffloadManager; we deliberately keep this separate so the two
 * surfaces don't collide on key names or fire semantics.
 */
internal class ScheduledNotificationStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun add(entry: JSONObject) {
        val list = loadAll()
        list.put(entry)
        prefs.edit().putString(KEY, list.toString()).apply()
    }

    fun remove(id: String): Boolean {
        val list = loadAll()
        for (i in 0 until list.length()) {
            if (list.getJSONObject(i).optString("id") == id) {
                list.remove(i)
                prefs.edit().putString(KEY, list.toString()).apply()
                return true
            }
        }
        return false
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    fun get(id: String): JSONObject? {
        val list = loadAll()
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (o.optString("id") == id) return o
        }
        return null
    }

    fun loadAll(): JSONArray {
        val raw = prefs.getString(KEY, null) ?: return JSONArray()
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    /**
     * Drop entries whose triggerAtMs has already passed. Receiver also
     * cleans up on fire, but if it was killed by the OS between schedule
     * and trigger the prefs entry would otherwise stick around.
     */
    fun sweepExpired() {
        val list = loadAll()
        val now = System.currentTimeMillis()
        val kept = JSONArray()
        var dropped = false
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (o.optLong("trigger_at_ms", Long.MAX_VALUE) > now) {
                kept.put(o)
            } else {
                dropped = true
            }
        }
        if (dropped) prefs.edit().putString(KEY, kept.toString()).apply()
    }

    companion object {
        private const val PREFS = "minis_scheduled_notifications"
        private const val KEY = "scheduled"
    }
}

// ─── Merged from ShizukuBackend.kt ──────────────────────────────────────────────────

/**
 * [T-android-privileged-backend] Shizuku-protocol privileged-execution backend.
 *
 * Three providers speak the SAME Shizuku binder protocol and push their binder
 * into the SAME client-side [rikka.shizuku.ShizukuProvider]:
 *   • official Shizuku manager (`moe.shizuku.privileged.api`, RikkaApps) — APK
 *   • AXManager (`frb.axeron.manager`, Axeron) — APK
 *   • Sui (RikkaApps) — a Magisk/KernelSU **module**, usually NO APK at all
 *
 * The `rikka.shizuku.Shizuku` singleton is therefore a single slot, owned by
 * whichever provider the user authorized — the SDK and this class are unaware
 * of (and indifferent to) which one is running. The earlier two-backend split
 * confused detection because both `ShizukuBackend` and `AxeronBackend`
 * reported state off the same shared singleton; that abstraction has been
 * removed and must NOT come back for Sui either.
 *
 * [T-android-sui-support] (GH#110 / GH#97) Detection is **binder-first**: a
 * live binder means we are usable, full stop. The installed-package scan is
 * only consulted when the binder is DOWN, to explain why and pick the right
 * install guidance. The previous package-first order hard-blocked Sui: Sui
 * ships no APK, so the whitelist scan returned "not installed" and
 * short-circuited before `pingBinder()` was ever called — a live, authorized
 * Sui rendered as "Manager Not Installed".
 *
 * No Sui-specific dependency or init is needed: `dev.rikka.shizuku:provider`
 * calls `Sui.init(packageName)` automatically from `ShizukuProvider.onCreate`
 * (verified in the 13.1.5 bytecode), and `rikka.sui.Sui` ships inside
 * `dev.rikka.shizuku:api`. [Sui.isSui] is used for LABELS ONLY — never for
 * state decisions (there is no supported way to identify the binder's sender;
 * see the reverted probe in 09f12761).
 */
class ShizukuBackend(private val appContext: Context) {

    @Volatile private var onStateChanged: (() -> Unit)? = null
    private var listenersRegistered = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        AppLogger.info(TAG, "binder received")
        onStateChanged?.invoke()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        AppLogger.warning(TAG, "binder died")
        onStateChanged?.invoke()
    }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            AppLogger.info(
                TAG,
                "permission result code=$requestCode grant=${grantResult == PackageManager.PERMISSION_GRANTED}",
            )
            onStateChanged?.invoke()
        }

    /**
     * Any known Shizuku-protocol **manager APK** installed?
     *
     * [T-android-sui-support] NOTE: this is NOT "is a privileged backend
     * available" — Sui provides the binder with no APK to find. Never gate
     * binder work on this; use [snapshot] / [isPermissionGranted] instead.
     * It survives only to drive install guidance and the "open manager app"
     * affordance.
     */
    fun isInstalled(): Boolean = installedManagerPackage() != null

    /**
     * [T-android-sui-support] True when the live binder is provided by Sui
     * (root module) rather than a manager APK. Display-only: it picks labels
     * and hides the meaningless "Open Manager App" row for Sui users. It must
     * never influence state resolution — see [decideState].
     */
    fun isSui(): Boolean = runCatching { Sui.isSui() }.getOrDefault(false)

    /**
     * The first installed Shizuku-protocol manager package (in preference
     * order: official Shizuku, then AXManager), or null if none installed.
     * Used to drive "open the manager app" actions.
     */
    fun installedManagerPackage(): String? = SHIZUKU_COMPATIBLE_PACKAGES.firstOrNull { pkg ->
        runCatching { appContext.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    }

    fun isBinderAlive(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isPermissionGranted(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun snapshot(): ShizukuManager.Snapshot {
        val binderAlive = isBinderAlive()
        val state = decideState(
            binderAlive = binderAlive,
            permissionGranted = binderAlive && isPermissionGranted(),
            managerApkInstalled = isInstalled(),
        )
        return if (state == ShizukuManager.State.READY) {
            ShizukuManager.Snapshot(state, versionOrUnknown(), uidOrUnknown(), isSui())
        } else {
            ShizukuManager.Snapshot(state, isSui = binderAlive && isSui())
        }
    }

    fun registerListeners(onStateChanged: () -> Unit) {
        this.onStateChanged = onStateChanged
        if (listenersRegistered) return
        listenersRegistered = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        }.onFailure { AppLogger.warning(TAG, "init listeners failed: ${it.message}") }
    }

    fun refresh() {
        onStateChanged?.invoke()
    }

    /**
     * Launch the installed manager app (Shizuku or AXManager — whichever is
     * present). If none is installed, falls back to an install page; the
     * multi-option install UI lives in the Settings screen, not here.
     *
     * [T-android-sui-support] Sui has no launcher activity, so a Sui user has
     * nothing to open. The UI hides this row for them; if it is somehow
     * reached anyway, send them to Sui's page rather than to Shizuku's
     * releases (which is what the old unconditional fallback did).
     */
    fun openManagerApp(context: Context) {
        val pkg = installedManagerPackage()
        if (pkg != null) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
                    .onFailure { AppLogger.warning(TAG, "open $pkg failed: ${it.message}") }
                return
            }
        }
        openInstallPage(context, if (isSui()) SUI_GITHUB_URL else SHIZUKU_GITHUB_URL)
    }

    fun openInstallPage(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { AppLogger.warning(TAG, "openInstallPage($url) failed: ${it.message}") }
    }

    fun requestPermission() {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            AppLogger.warning(TAG, "permission permanently denied — user must enable in the manager app")
            return
        }
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
            .onFailure { AppLogger.warning(TAG, "requestPermission failed: ${it.message}") }
    }

    private fun versionOrUnknown(): Int = runCatching {
        if (Shizuku.pingBinder()) Shizuku.getVersion() else -1
    }.getOrDefault(-1)

    private fun uidOrUnknown(): Int = runCatching {
        if (Shizuku.pingBinder()) Shizuku.getUid() else -1
    }.getOrDefault(-1)

    /**
     * Shizuku-SDK process path (reflection-based `Shizuku.newProcess`).
     */
    fun runProcess(
        argv: Array<String>,
        env: Array<String>?,
        cwd: String?,
        timeoutMs: Long,
    ): ShizukuManager.ProcessResult {
        if (!isPermissionGranted()) {
            return ShizukuManager.ProcessResult(
                exitCode = 126,
                stdout = "",
                stderr = "shizuku not ready (state=${snapshot().state})",
            )
        }
        // Shizuku.newProcess is hidden API — invoke via reflection so the
        // SDK upgrade doesn't break our build. The returned object is a
        // ShizukuRemoteProcess which extends java.lang.Process, so once we
        // have it, treat it as a normal Process and use the standard
        // waitFor(timeout, unit) API rather than reflecting for a
        // nonexistent `waitForTimeout` method (T343).
        val procAny = runCatching {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            m.isAccessible = true
            m.invoke(null, argv, env, cwd)
        }.getOrElse {
            AppLogger.warning(TAG, "newProcess reflection failed: ${it.message}")
            return ShizukuManager.ProcessResult(1, "", "shizuku.newProcess unavailable: ${it.message}")
        } ?: return ShizukuManager.ProcessResult(1, "", "shizuku.newProcess returned null")

        val proc = procAny as? Process ?: return ShizukuManager.ProcessResult(
            1, "",
            "shizuku.newProcess returned ${procAny.javaClass.name}, not java.lang.Process",
        )

        val out = StringBuilder()
        val err = StringBuilder()
        val outThread = Thread {
            runCatching {
                proc.inputStream?.use { s ->
                    s.bufferedReader().forEachLine { l -> synchronized(out) { out.append(l).append('\n') } }
                }
            }
        }
        val errThread = Thread {
            runCatching {
                proc.errorStream?.use { s ->
                    s.bufferedReader().forEachLine { l -> synchronized(err) { err.append(l).append('\n') } }
                }
            }
        }
        outThread.isDaemon = true; errThread.isDaemon = true
        // GH #453: per-name thread counts.
        outThread.name = com.openminis.app.diagnostics.ThreadNames.of("shizuku-out"); errThread.name = com.openminis.app.diagnostics.ThreadNames.of("shizuku-err")
        outThread.start(); errThread.start()

        val exited: Boolean = try {
            proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: IllegalArgumentException) {
            // Shizuku v13 RemoteProcess.waitFor(long, TimeUnit) throws IllegalArgumentException
            // ("process hasn't exited") instead of blocking. Fall back to polling exitValue().
            if (polledFallbackLogged.compareAndSet(false, true)) {
                AppLogger.info(TAG, "Using polling fallback for Shizuku RemoteProcess.waitFor(timeout) (SDK bug — throws IllegalArgumentException)")
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            var done = false
            while (System.currentTimeMillis() < deadline) {
                try {
                    proc.exitValue()
                    done = true
                    break
                } catch (e: RuntimeException) {
                    if (e is IllegalThreadStateException || e is IllegalArgumentException) {
                        Thread.sleep(50)
                    } else {
                        throw e
                    }
                }
            }
            done
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "Shizuku waitFor failed: type=${t::class.java.name} msg=${t.message}")
            runCatching { proc.destroy() }
            runCatching { outThread.join(2000) }
            runCatching { errThread.join(2000) }
            return ShizukuManager.ProcessResult(1, out.toString().trimEnd('\n'), (err.toString().trimEnd('\n') + "\nwaitFor failed: ${t.message}").trim())
        }

        if (!exited) {
            runCatching { proc.destroy() }
            runCatching { outThread.join(2000) }
            runCatching { errThread.join(2000) }
            // Linux convention: 124 = command timed out (coreutils `timeout`).
            return ShizukuManager.ProcessResult(124, out.toString().trimEnd('\n'), err.toString().trimEnd('\n'))
        }

        runCatching { outThread.join(2000) }
        runCatching { errThread.join(2000) }
        val rc = runCatching { proc.exitValue() }.getOrDefault(-1)
        return ShizukuManager.ProcessResult(rc, out.toString().trimEnd('\n'), err.toString().trimEnd('\n'))
    }

    companion object {
        private const val TAG = "ShizukuBackend"

        // Known Shizuku-protocol manager packages. Order = UI install-preference.
        // Sui is deliberately ABSENT: it is a root module, not an APK, and
        // adding a package name for it would re-create the very whitelist trap
        // that hid it (GH#110). Sui is detected purely by its live binder.
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val AXMANAGER_PACKAGE = "frb.axeron.manager"
        val SHIZUKU_COMPATIBLE_PACKAGES = listOf(SHIZUKU_PACKAGE, AXMANAGER_PACKAGE)

        const val SHIZUKU_GITHUB_URL = "https://github.com/RikkaApps/Shizuku/releases"
        const val AXMANAGER_GITHUB_URL = "https://github.com/fahrez182/AxManager/releases"
        const val SUI_GITHUB_URL = "https://github.com/RikkaApps/Sui"

        const val PERMISSION_REQUEST_CODE = 0xC1A4D

        private val polledFallbackLogged = AtomicBoolean(false)

        /**
         * [T-android-sui-support] The binder-first decision, extracted as a
         * PURE function so every branch is unit-testable without a device, a
         * root environment, or the Shizuku SDK's static singleton
         * (ShizukuBackendStateTest).
         *
         * Order matters and is the whole fix:
         *   1. binder alive?  → READY / NEED_PERMISSION. Whoever supplied it
         *      (Shizuku APK, AXManager, or Sui module) is irrelevant — this is
         *      what makes Sui work with zero Sui-specific plumbing.
         *   2. binder down, manager APK present → NOT_RUNNING ("start it").
         *   3. binder down, nothing installed → NOT_INSTALLED ("install one").
         *
         * NOT_INSTALLED keeps its name for call-site compatibility but now
         * means "no binder AND no manager APK" — with Sui in the picture it
         * reads as "no privileged backend available", which is what the UI
         * copy says.
         */
        fun decideState(
            binderAlive: Boolean,
            permissionGranted: Boolean,
            managerApkInstalled: Boolean,
        ): ShizukuManager.State = when {
            binderAlive && permissionGranted -> ShizukuManager.State.READY
            binderAlive -> ShizukuManager.State.NEED_PERMISSION
            managerApkInstalled -> ShizukuManager.State.NOT_RUNNING
            else -> ShizukuManager.State.NOT_INSTALLED
        }
    }
}

// ─── Merged from ShizukuManager.kt ──────────────────────────────────────────────────

/**
 * T322 / [T-android-privileged-backend]: lifecycle + permission gateway for
 * Shizuku-protocol privileged execution.
 *
 * Thin façade around a single [ShizukuBackend], which recognises ALL THREE
 * Shizuku-protocol providers — the official Shizuku manager, AXManager, and
 * Sui (root module) — because they share the same client-side binder slot, so
 * there is no "active backend" choice to make at this layer. Public API
 * ([snapshot], [isReady], [runProcess], [openShizukuApp], [openInstallPage],
 * [requestPermission]) is unchanged from earlier callers.
 *
 * State machine ([T-android-sui-support]: binder-first — a live binder wins
 * before any package check, which is what lets APK-less Sui be detected):
 *   READY              Binder up + permission held — calls work.
 *   NEED_PERMISSION    Binder up but Minis not authorized.
 *   NOT_RUNNING        No binder, but a manager APK is installed — start it.
 *   NOT_INSTALLED      No binder and no manager APK — nothing available.
 */
object ShizukuManager {
    private const val TAG = "ShizukuManager"

    enum class State { NOT_INSTALLED, NOT_RUNNING, NEED_PERMISSION, READY }

    data class Snapshot(
        val state: State,
        val version: Int = -1,
        val uid: Int = -1,
        /**
         * [T-android-sui-support] True when the live binder came from Sui (a
         * root module) rather than a manager APK. DISPLAY ONLY — drives the
         * "Sui (root)" label and hides the "Open Manager App" row that Sui
         * has no activity for. Never gate behaviour on it.
         */
        val isSui: Boolean = false,
    )

    data class ProcessResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val combined: String get() = if (stderr.isEmpty()) stdout else "$stdout\n$stderr".trimEnd()
    }

    @Volatile private var initialized = false
    @Volatile private var appContext: Context? = null
    private var backend: ShizukuBackend? = null

    private val _snapshot = MutableStateFlow(Snapshot(State.NOT_INSTALLED))
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    fun init(app: Application) {
        if (initialized) return
        initialized = true
        appContext = app.applicationContext
        backend = ShizukuBackend(app.applicationContext).also { b ->
            runCatching { b.registerListeners { recompute("listener") } }
                .onFailure { AppLogger.warning(TAG, "registerListeners failed: ${it.message}") }
        }
        recompute("init")
    }

    fun refresh() = recompute("manual-refresh")

    fun isReady(): Boolean = _snapshot.value.state == State.READY

    /**
     * Whether a manager **APK** is installed. [T-android-sui-support] This is
     * NOT an availability check — Sui provides the binder with no APK. Use
     * [isReady] / [snapshot] for "can we actually run privileged commands".
     */
    fun isInstalled(): Boolean = backend?.isInstalled() == true

    /** [T-android-sui-support] Display-only: is the live binder Sui's? */
    fun isSui(): Boolean = _snapshot.value.isSui

    /** First installed Shizuku-protocol manager package, or null. */
    fun installedManagerPackage(): String? = backend?.installedManagerPackage()

    fun requestPermission(): Boolean {
        val b = backend ?: return false
        if (!b.isBinderAlive()) return false
        b.requestPermission()
        return true
    }

    /** Launch the installed manager app (Shizuku or AXManager). */
    fun openShizukuApp(context: Context) {
        backend?.openManagerApp(context)
    }

    /**
     * Open the official Shizuku install page. The "AXManager" alternative is
     * surfaced as a separate row in the Settings UI; this method preserves
     * the original single-CTA behaviour for callers that don't show a picker.
     */
    fun openInstallPage(context: Context) {
        backend?.openInstallPage(context, ShizukuBackend.SHIZUKU_GITHUB_URL)
    }

    /** Open a specific install page (Shizuku or AXManager). */
    fun openInstallPage(context: Context, url: String) {
        backend?.openInstallPage(context, url)
    }

    fun runProcess(
        argv: Array<String>,
        env: Array<String>? = null,
        cwd: String? = null,
        timeoutMs: Long = 5_000L,
    ): ProcessResult {
        if (!isReady()) {
            return ProcessResult(
                exitCode = 126,
                stdout = "",
                stderr = "shizuku not ready (state=${_snapshot.value.state})",
            )
        }
        return backend!!.runProcess(argv, env, cwd, timeoutMs)
    }

    fun deviceSdk(): Int = Build.VERSION.SDK_INT

    private fun recompute(reason: String) {
        val b = backend ?: run {
            _snapshot.value = Snapshot(State.NOT_INSTALLED)
            return
        }
        val snap = b.snapshot()
        val prev = _snapshot.value
        _snapshot.value = snap
        // Only log on real transitions — refresh() is fired by every settings
        // screen entry + every listener callback, so logging unconditionally
        // floods logcat with NOT_RUNNING repeats.
        if (snap.state != prev.state || snap.version != prev.version ||
            snap.uid != prev.uid || snap.isSui != prev.isSui
        ) {
            // [T-android-sui-support] provider= is the field to ask GH#110 /
            // GH#97 reporters for: it distinguishes a Sui-supplied binder from
            // a manager APK without needing any (unsupported) sender probe.
            val provider = when {
                snap.isSui -> "sui"
                else -> b.installedManagerPackage() ?: "none"
            }
            AppLogger.info(
                TAG,
                "state=${snap.state} ver=${snap.version} uid=${snap.uid} provider=$provider ($reason)",
            )
        }
    }
}

// ─── Merged from WeatherManager.kt ──────────────────────────────────────────────────

/**
 * Fetches weather data from the Open-Meteo public API (no API key required).
 * Returns current conditions, 48-hour hourly forecast, and 10-day daily forecast
 * as a formatted human-readable string.
 */
object WeatherManager {

    private const val TAG = "WeatherManager"
    private const val BASE_URL = "https://api.open-meteo.com/v1/forecast"

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(okhttp3.ConnectionPool(5, 30, TimeUnit.SECONDS))
        .build()

    // [FEAT-261021-16] Follows the default route in Settings > Network; the
    // same client object when nothing is configured.
    private val client: OkHttpClient get() = com.openminis.app.network.NetworkHttp.client(com.openminis.app.network.NetworkHttp.DEFAULT_TAG, baseClient)

    /**
     * Fetch weather for the given coordinates.
     * Returns a formatted string with current, hourly, and daily weather.
     */
    fun fetchWeather(latitude: Double, longitude: Double): String {
        val json = fetchWeatherJson(latitude, longitude)
            ?: return "Error fetching weather: $lastError"
        return formatWeatherResponse(json, latitude, longitude)
    }

    /**
     * Fetch raw Open-Meteo JSON. Returns null on transport error and stores
     * a user-readable reason in [lastError] so the calling handler can
     * surface it via a structured envelope. T63 added — the
     * subcommand-based android-weather handler needs raw JSON to slice
     * into current/hourly/daily.
     */
    @Volatile var lastError: String = ""
        private set

    fun fetchWeatherJson(latitude: Double, longitude: Double): JSONObject? {
        val url = "$BASE_URL?" +
            "latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation," +
            "weather_code,wind_speed_10m,wind_direction_10m" +
            "&hourly=temperature_2m,precipitation_probability,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum," +
            "precipitation_probability_max,sunrise,sunset" +
            "&forecast_hours=48" +
            "&forecast_days=10" +
            "&timezone=auto"

        val request = Request.Builder().url(url).get().build()
        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) {
                lastError = "Open-Meteo API returned ${response.code}: ${body ?: "no body"}"
                null
            } else {
                lastError = ""
                JSONObject(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Weather fetch failed", e)
            lastError = e.message ?: "unknown network error"
            null
        }
    }

    private fun formatWeatherResponse(json: JSONObject, lat: Double, lon: Double): String {
        val sb = StringBuilder()
        val timezone = json.optString("timezone", "unknown")

        sb.appendLine("=== Weather for ($lat, $lon) | Timezone: $timezone ===")
        sb.appendLine()

        // Current conditions
        val current = json.optJSONObject("current")
        if (current != null) {
            val units = json.optJSONObject("current_units")
            val tempUnit = units?.optString("temperature_2m", "°C") ?: "°C"
            val windUnit = units?.optString("wind_speed_10m", "km/h") ?: "km/h"

            sb.appendLine("── Current Conditions ──")
            sb.appendLine("  Temperature: ${current.optDouble("temperature_2m")}$tempUnit")
            sb.appendLine("  Feels like:  ${current.optDouble("apparent_temperature")}$tempUnit")
            sb.appendLine("  Humidity:    ${current.optInt("relative_humidity_2m")}%")
            sb.appendLine("  Precipitation: ${current.optDouble("precipitation")} mm")
            sb.appendLine("  Wind:        ${current.optDouble("wind_speed_10m")} $windUnit " +
                "from ${current.optInt("wind_direction_10m")}°")
            sb.appendLine("  Conditions:  ${weatherCodeToDescription(current.optInt("weather_code"))}")
            sb.appendLine()
        }

        // Hourly forecast (show every 3 hours to keep it readable)
        val hourly = json.optJSONObject("hourly")
        if (hourly != null) {
            val times = hourly.optJSONArray("time")
            val temps = hourly.optJSONArray("temperature_2m")
            val precips = hourly.optJSONArray("precipitation_probability")
            val codes = hourly.optJSONArray("weather_code")
            val winds = hourly.optJSONArray("wind_speed_10m")

            if (times != null && times.length() > 0) {
                sb.appendLine("── Hourly Forecast (every 3h) ──")
                for (i in 0 until times.length() step 3) {
                    val time = times.optString(i, "").replace("T", " ")
                    val temp = temps?.optDouble(i) ?: 0.0
                    val precip = precips?.optInt(i) ?: 0
                    val code = codes?.optInt(i) ?: 0
                    val wind = winds?.optDouble(i) ?: 0.0
                    sb.appendLine("  $time | ${temp}° | ${weatherCodeToDescription(code)} | " +
                        "rain ${precip}% | wind ${wind} km/h")
                }
                sb.appendLine()
            }
        }

        // Daily forecast
        val daily = json.optJSONObject("daily")
        if (daily != null) {
            val times = daily.optJSONArray("time")
            val maxTemps = daily.optJSONArray("temperature_2m_max")
            val minTemps = daily.optJSONArray("temperature_2m_min")
            val codes = daily.optJSONArray("weather_code")
            val precipSum = daily.optJSONArray("precipitation_sum")
            val precipProb = daily.optJSONArray("precipitation_probability_max")
            val sunrises = daily.optJSONArray("sunrise")
            val sunsets = daily.optJSONArray("sunset")

            if (times != null && times.length() > 0) {
                sb.appendLine("── 10-Day Forecast ──")
                for (i in 0 until times.length()) {
                    val date = times.optString(i, "")
                    val hi = maxTemps?.optDouble(i) ?: 0.0
                    val lo = minTemps?.optDouble(i) ?: 0.0
                    val code = codes?.optInt(i) ?: 0
                    val rain = precipSum?.optDouble(i) ?: 0.0
                    val rainProb = precipProb?.optInt(i) ?: 0
                    val sunrise = sunrises?.optString(i, "")?.substringAfter("T") ?: ""
                    val sunset = sunsets?.optString(i, "")?.substringAfter("T") ?: ""

                    sb.appendLine("  $date | ${lo}°–${hi}° | ${weatherCodeToDescription(code)} | " +
                        "rain ${rain}mm (${rainProb}%) | sunrise $sunrise sunset $sunset")
                }
            }
        }

        return sb.toString().trimEnd()
    }

    /**
     * WMO Weather interpretation codes to human-readable descriptions.
     * https://open-meteo.com/en/docs#weathervariables
     */
    private fun weatherCodeToDescription(code: Int): String = when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45 -> "Foggy"
        48 -> "Depositing rime fog"
        51 -> "Light drizzle"
        53 -> "Moderate drizzle"
        55 -> "Dense drizzle"
        56 -> "Light freezing drizzle"
        57 -> "Dense freezing drizzle"
        61 -> "Slight rain"
        63 -> "Moderate rain"
        65 -> "Heavy rain"
        66 -> "Light freezing rain"
        67 -> "Heavy freezing rain"
        71 -> "Slight snow"
        73 -> "Moderate snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80 -> "Slight rain showers"
        81 -> "Moderate rain showers"
        82 -> "Violent rain showers"
        85 -> "Slight snow showers"
        86 -> "Heavy snow showers"
        95 -> "Thunderstorm"
        96 -> "Thunderstorm with slight hail"
        99 -> "Thunderstorm with heavy hail"
        else -> "Unknown (code $code)"
    }
}
