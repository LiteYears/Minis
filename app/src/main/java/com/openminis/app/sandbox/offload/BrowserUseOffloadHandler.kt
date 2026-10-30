package com.openminis.app.sandbox.offload

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.browser.BrowserAction
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserActionResult
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.MinisNotificationListenerService
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.offload.ScheduledNotificationReceiver
import com.openminis.app.offload.ScheduledNotificationStore
import com.openminis.app.provider.voice.VoiceInputRequest
import com.openminis.app.provider.voice.VoiceProvider
import com.openminis.app.provider.voice.VoiceProviderFactory
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.speech.MicInUse
import com.openminis.app.speech.RecognizerErrorCodes
import com.openminis.app.speech.RecognizerLocales
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * minis-browser-use — expose the agent's browser_use tool as a CLI inside the
 * PRoot sandbox.
 *
 * Output contract mirrors iOS `BrowserUseOffload.m` / `NativeOffloadUtils.m`:
 * every invocation emits a JSON envelope
 *   `{ ok, tool, action, data | error, timestamp }`
 * (pretty-printed by default; minified with `--compact`; data-only with `-q`).
 * Screenshots are persisted under `/var/minis/browser/` and referenced via
 * `image_path` + `minis_url` — raw base64 is only included when the caller
 * explicitly passes `--with-base64`, matching iOS.
 *
 * [T-android-browser-cli-own-pool] Runs on the CALLING chat's tab pool (the one
 * its `browser_use` tool drives), resolved by [BrowserCliPools] from the
 * shell's `MINIS_CHAT_SESSION_ID`; mirrors iOS BrowserUseOffloadBridge. It used
 * to run every chat's calls on one process-wide pool, so concurrent chats
 * navigated each other's page. Screenshots and downloads go to the caller's
 * own `/var/minis/browser/`.
 *
 * Hard 90-second timeout via `withTimeout` so a hanging navigation can't lock
 * the shell indefinitely. Exits 0 for success, 1 for runtime errors, 2 for
 * bad arguments. Bare invocation prints help and exits 0 (matches iOS).
 */
class BrowserUseOffloadHandler(private val app: MinisApp) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        // Declared-boolean flags: without this set, the shared OffloadArgs
        // parser would greedily consume the next positional token (typically
        // the action name) as a value, e.g. `--compact navigate` would store
        // `compact=navigate` and drop `navigate` from positional args.
        val args = OffloadArgs(
            request.argv.drop(1),
            booleanFlags = BOOLEAN_FLAGS,
        )
        val compact = args.hasFlag("compact")
        val quiet = args.hasFlag("q", "quiet")
        val withBase64 = args.hasFlag("with-base64", "with_base64")

        // Bare invocation / --help: exit 0 with help (matches iOS). iOS writes
        // help to stderr; stdout vs stderr is not separable across this native
        // offload boundary, so we return help in `output` with exit 0.
        if (request.argv.size <= 1 || args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, HELP)
        }

        // [T-tools-granular-switches] The CLI is a second door to the same
        // capability: a shell_execute call can reach minis-browser-use even
        // when browser_use was removed from the tool schema. Gate it here too,
        // or turning the tool off would only hide it. Mirrors iOS
        // BrowserUseOffloadBridge's AgentToolSwitch.browser guard. Checked
        // AFTER --help so documentation stays readable while it is off.
        if (!com.openminis.app.tools.AgentToolSwitch.BROWSER.isEnabled(app)) {
            return emitError(
                action = "execute", code = ERR_DISABLED,
                message = "The browser tool is turned off in Settings > Tools.",
                compact = compact, quiet = quiet, exit = 2,
            )
        }

        // Parse args into the browser_use input shape.
        val inputJson = try {
            buildInputJson(args)
        } catch (e: IllegalArgumentException) {
            return emitError(action = "execute", code = ERR_INVALID_ARGS,
                message = e.message ?: "invalid arguments",
                compact = compact, quiet = quiet, exit = 2)
        }

        val input = BrowserActionInput.parse(inputJson.toString())
            ?: return emitError(action = "execute", code = ERR_INVALID_ARGS,
                message = "Invalid browser_use input. Required: 'action' (one of ${BrowserAction.allValues.joinToString(", ")})",
                compact = compact, quiet = quiet, exit = 2)

        val actionName = input.action.value

        // [T-android-browser-cli-own-pool] The caller's pool, not a shared one.
        val pool = when (val r = BrowserCliPools.resolve(app, request.sessionId)) {
            is BrowserCliPools.Resolution.Found -> {
                Log.i(TAG, "action=$actionName sid=${request.sessionId?.take(8)} pool=${r.via}")
                r.pool
            }
            is BrowserCliPools.Resolution.SessionGone -> return emitError(
                action = actionName, code = ERR_INTERNAL,
                message = "session ${r.sessionId} no longer exists; cannot run browser_use.",
                compact = compact, quiet = quiet, exit = 1,
            )
        }
        // The session whose /var/minis is mounted for this shell (the parent,
        // for a sub agent), so files land where the caller can read them.
        val filesSid = request.sessionId?.let {
            com.openminis.app.sandbox.ExecutionCoordinator.mountedSessionIdFor(it)
        }

        // [T-bg-overlay phase 2 fix] Surface browser sub-action progress
        // to SessionActivityTracker.currentToolStatus so the background
        // overlay capsule + FGS notification show what the agent is
        // actually doing — "browser_use: navigate https://example.com"
        // instead of the static "Running: browser_use".
        com.openminis.app.service.SessionActivityTracker.updateToolStatus(
            statusForBrowserAction(actionName, inputJson),
        )

        // Execute under a 90s hard timeout. A hanging navigation in a tab
        // pool shouldn't be able to block the entire shell forever.
        val result: BrowserActionResult = try {
            runBlocking {
                withTimeout(EXECUTE_TIMEOUT_MS) {
                    // [T-browser-readaction-follow-tab-and-yolo-android] The CLI
                    // (`minis-browser-use`) is a serial human/script driver:
                    // navigate → execute_js / get_text / … all expect the page
                    // just navigated to, not a fanned-out grace tab. Drive in
                    // YOLO single-tab mode so every tab-less action sticks to the
                    // selected tab. Explicit --tab-id still routes normally.
                    // Mirrors iOS BrowserUseOffloadBridge passing singleTab=true.
                    // No owner, as on iOS: the chat drives its own pool with
                    // full access.
                    pool.execute(input, singleTab = true)
                }
            }
        } catch (_: TimeoutCancellationException) {
            return emitError(action = actionName, code = ERR_INTERNAL,
                message = "browser action timed out after ${EXECUTE_TIMEOUT_MS / 1000}s",
                compact = compact, quiet = quiet, exit = 1)
        } catch (e: Throwable) {
            Log.w(TAG, "execute failed: ${e.message}", e)
            return emitError(action = actionName, code = ERR_INTERNAL,
                message = e.message ?: "browser action failed",
                compact = compact, quiet = quiet, exit = 1)
        }

        if (!result.success) {
            return emitError(action = actionName, code = ERR_INTERNAL,
                message = result.text.ifEmpty { "browser action failed" },
                compact = compact, quiet = quiet, exit = 1)
        }

        val data = encodeData(result, withBase64 = withBase64, filesSid = filesSid)
        val envelope = envelope(actionName, data)
        return NativeOffloadResult(0, emit(envelope, data, compact = compact, quiet = quiet))
    }

    // ── Input parsing ────────────────────────────────────────────────────

    private fun buildInputJson(args: OffloadArgs): JSONObject {
        // Explicit --json wins; action/flags are ignored in that case.
        args.get("json")?.let { raw ->
            return try {
                JSONObject(raw)
            } catch (e: Exception) {
                throw IllegalArgumentException("--json is not valid JSON: ${e.message}")
            }
        }

        val action = args.positional.firstOrNull()
            ?: throw IllegalArgumentException("missing <action>. Use --help for usage.")

        val obj = JSONObject().put("action", action)
        args.get("url")?.let { obj.put("url", it) }
        args.get("selector")?.let { obj.put("selector", it) }
        args.get("text")?.let { obj.put("text", it) }
        args.getInt("coordinate-x", "coordinate_x", "x")?.let { obj.put("coordinate_x", it) }
        args.getInt("coordinate-y", "coordinate_y", "y")?.let { obj.put("coordinate_y", it) }
        args.get("direction")?.let { obj.put("direction", it) }
        args.getInt("amount")?.let { obj.put("amount", it) }
        args.get("script")?.let { obj.put("script", it) }
        args.get("user-agent", "user_agent", "ua")?.let { obj.put("user_agent", it) }
        args.getInt("max-depth", "max_depth")?.let { obj.put("max_depth", it) }
        args.getInt("tab-id", "tab_id")?.let { obj.put("tab_id", it) }
        // set_viewport parameters
        args.getInt("width")?.let { obj.put("viewport_width", it) }
        args.getInt("height")?.let { obj.put("viewport_height", it) }
        if (args.hasFlag("reset")) obj.put("reset", true)
        // get_cookies / scroll_and_collect parameters
        args.get("item-selector", "item_selector")?.let { obj.put("item_selector", it) }
        args.getInt("scroll-count", "scroll_count")?.let { obj.put("scroll_count", it) }
        args.getInt("timeout")?.let { obj.put("timeout", it) }
        if (args.hasFlag("fuzzy")) obj.put("fuzzy", true)
        args.get("keywords")?.let { raw ->
            // Comma- or whitespace-separated list.
            val tokens = raw.split(Regex("[,\\s]+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (tokens.isNotEmpty()) obj.put("keywords", JSONArray(tokens))
        }
        // set_cookies: a JSON array of cookie objects. Either inline via
        // --cookies '<json>' or, to dodge busybox-ash shell mangling of the
        // JSON's quotes / braces / colons, from a file via --cookies-file <path>
        // (mirrors `minis-config set --file`). Parse failures throw so the
        // handler surfaces an explicit invalid_args error instead of silently
        // dropping the array (which used to reach set_cookies as empty).
        val cookiesFile = args.get("cookies-file", "cookies_file")
        val cookiesRaw: String? = if (cookiesFile != null) {
            val host = PRootKernel.resolveHostPath(cookiesFile)
                ?: throw IllegalArgumentException("--cookies-file: cannot resolve path '$cookiesFile'")
            runCatching { host.readText() }.getOrNull()
                ?: throw IllegalArgumentException("--cookies-file: could not read '$cookiesFile'")
        } else {
            args.get("cookies")
        }
        cookiesRaw?.let { raw ->
            // JSON array first; otherwise accept a Netscape cookies.txt export.
            val arr = runCatching { JSONArray(raw) }.getOrNull()
                ?: parseNetscapeCookies(raw)
                ?: throw IllegalArgumentException(
                    "--cookies (or --cookies-file content) must be a JSON ARRAY of cookie " +
                        "objects, or a Netscape cookies.txt file. JSON object fields: name + " +
                        "value (required); optional domain (defaults to current page host), " +
                        "path (defaults to \"/\"), secure (bool), http_only (bool, alias " +
                        "httpOnly), expires (unix seconds, alias expirationDate), sameSite. " +
                        "e.g. '[{\"name\":\"sid\",\"value\":\"abc\",\"domain\":\".x.com\",\"secure\":true}]'. " +
                        "If the shell is mangling the quotes, write the JSON array to a file " +
                        "and pass --cookies-file <path>.",
                )
            obj.put("cookies", arr)
        }
        return obj
    }

    /**
     * Parse a Netscape-format cookie file (curl/wget/browser export) into the
     * JSON array `set_cookies` consumes. Returns null if it doesn't look like
     * Netscape, so the caller falls through to the JSON error. Format: 7
     * TAB-separated fields per line — domain, includeSubdomains, path, secure,
     * expires, name, value. `#` lines are comments, except curl's `#HttpOnly_`
     * domain prefix which marks an HttpOnly cookie.
     */
    private fun parseNetscapeCookies(text: String): JSONArray? {
        val out = JSONArray()
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            var httpOnly = false
            var work = t
            when {
                work.startsWith("#HttpOnly_") -> { httpOnly = true; work = work.removePrefix("#HttpOnly_") }
                work.startsWith("#") -> continue
            }
            val f = work.split("\t")
            if (f.size < 7) continue
            val name = f[5]
            if (name.isEmpty()) continue
            val c = JSONObject().apply {
                put("name", name)
                put("value", f[6])
                put("domain", f[0])
                put("path", f[2])
                if (f[3].equals("TRUE", ignoreCase = true)) put("secure", true)
                if (httpOnly) put("http_only", true)
                f[4].toLongOrNull()?.takeIf { it > 0 }?.let { put("expires", it) }
            }
            out.put(c)
        }
        return if (out.length() > 0) out else null
    }

    // ── Data encoding (iOS BrowserUseOffloadBridge.encode counterpart) ────

    private fun encodeData(r: BrowserActionResult, withBase64: Boolean, filesSid: String?): JSONObject {
        val out = JSONObject()
        out.put("text", r.text)
        out.put("success", r.success)
        r.pageURL?.takeIf { it.isNotEmpty() }?.let { out.put("page_url", it) }

        // Persist screenshot bytes under /var/minis/browser/ so shells can
        // reference the JPEG via image_path + minis_url instead of piping
        // base64 through stdout.
        // [T-android-browser-cli-own-pool] The caller's own browser dir
        // (minis-sessions/<sid>/browser is what /var/minis/browser mounts for
        // that chat). The process-wide mount table only knows the last shell
        // that set it up, so another chat's folder could get the file.
        val browserHostDir: File? = (
            filesSid?.let { com.openminis.app.sandbox.SessionMounts.sessionDir(app.filesDir, it, "browser") }
                ?: PRootKernel.resolveHostPath(VAR_MINIS_BROWSER)
            )?.also {
            try { it.mkdirs() } catch (_: Throwable) { /* non-fatal — write will fail below */ }
        }

        var persistedImagePath: String? = null
        val base64 = r.base64Image
        if (!base64.isNullOrEmpty() && browserHostDir != null) {
            val bytes = try { android.util.Base64.decode(base64, android.util.Base64.DEFAULT) }
                catch (_: Exception) { null }
            if (bytes != null) {
                val filename = "screenshot_${System.currentTimeMillis()}.jpg"
                val dest = File(browserHostDir, filename)
                if (runCatching { dest.writeBytes(bytes) }.isSuccess) {
                    val linuxPath = "$VAR_MINIS_BROWSER/$filename"
                    persistedImagePath = linuxPath
                    out.put("image_path", linuxPath)
                    out.put("minis_url", "minis://browser/$filename")
                } else {
                    Log.w(TAG, "Failed to persist screenshot to ${dest.absolutePath}")
                }
            }
        } else if (!base64.isNullOrEmpty()) {
            Log.w(TAG, "No /var/minis/browser mount — falling back to base64-only output")
        }

        // Fall back to the in-memory host path only when we couldn't persist.
        if (persistedImagePath == null) {
            r.imageFilePath?.takeIf { it.isNotEmpty() }?.let { out.put("image_path", it) }
        }

        if (withBase64 && !base64.isNullOrEmpty()) {
            out.put("image_base64", base64)
        }

        // Fetched file bytes (fetch action)
        val fname = r.fetchedFileName
        if (!fname.isNullOrEmpty()) {
            out.put("fetched_file", fname)
            val data = r.fetchedFileData
            if (data != null) {
                out.put("fetched_bytes", data.size)
                if (browserHostDir != null) {
                    val dest = File(browserHostDir, fname)
                    if (runCatching { dest.writeBytes(data) }.isSuccess) {
                        out.put("fetched_path", "$VAR_MINIS_BROWSER/$fname")
                        out.put("fetched_minis_url", "minis://browser/$fname")
                    } else {
                        Log.w(TAG, "Failed to persist fetched file to ${dest.absolutePath}")
                    }
                }
            }
        }

        return out
    }

    // ── JSON envelope helpers (mirror NativeOffloadUtils.m) ──────────────

    private fun envelope(action: String, data: JSONObject): JSONObject = JSONObject()
        .put("ok", true)
        .put("tool", TOOL_NAME)
        .put("action", action)
        .put("data", data)
        .put("timestamp", isoTimestamp())

    private fun errorEnvelope(action: String, code: String, message: String): JSONObject {
        val err = JSONObject().put("code", code).put("message", message)
        return JSONObject()
            .put("ok", false)
            .put("tool", TOOL_NAME)
            .put("action", action)
            .put("error", err)
            .put("timestamp", isoTimestamp())
    }

    private fun emitError(
        action: String,
        code: String,
        message: String,
        compact: Boolean,
        quiet: Boolean,
        exit: Int,
    ): NativeOffloadResult {
        val env = errorEnvelope(action, code, message)
        // In quiet mode, error emits just the `error` sub-object.
        val payload = if (quiet) env.optJSONObject("error") ?: env else env
        return NativeOffloadResult(exit, serialize(payload, compact) + "\n")
    }

    private fun emit(envelope: JSONObject, data: JSONObject, compact: Boolean, quiet: Boolean): String {
        val payload: Any = if (quiet) data else envelope
        return serialize(payload, compact) + "\n"
    }

    private fun serialize(payload: Any, compact: Boolean): String {
        if (payload !is JSONObject && payload !is JSONArray) return payload.toString()
        return if (compact) payload.toString() else (payload as? JSONObject)?.toString(2)
            ?: (payload as JSONArray).toString(2)
    }

    private fun isoTimestamp(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(Date())
    }

    /**
     * [T-bg-overlay phase 2 fix] Human-readable browser sub-action status
     * surfaced to the background overlay + FGS notification. Falls back to
     * a bare action name when no rich detail is available — keeps the
     * overlay text useful for any future BrowserAction values too.
     */
    private fun statusForBrowserAction(action: String, input: org.json.JSONObject): String {
        val url = input.optString("url", "").takeIf { it.isNotBlank() }
        val sel = input.optString("selector", "").takeIf { it.isNotBlank() }
        val txt = input.optString("text", "").takeIf { it.isNotBlank() }
        val dir = input.optString("direction", "").takeIf { it.isNotBlank() }
        return when (action) {
            "navigate"           -> "browser: navigate ${url ?: ""}".trim()
            "screenshot"         -> "browser: screenshot"
            "click"              -> "browser: click ${sel ?: ""}".trim()
            "type"               -> "browser: type"
            "scroll"             -> "browser: scroll${dir?.let { " $it" } ?: ""}"
            "scroll_and_collect" -> "browser: scroll & collect"
            "get_text"           -> "browser: read text"
            "get_readable"       -> "browser: extract readable"
            "get_backbone"       -> "browser: extract structure"
            "fetch"              -> "browser: fetch ${url ?: ""}".trim()
            "execute_js"         -> "browser: run JS"
            "set_viewport"       -> "browser: set viewport"
            "get_cookies"        -> "browser: read cookies"
            "set_cookies"        -> "browser: write cookies"
            "go_back", "back"    -> "browser: go back"
            "reload"             -> "browser: reload"
            "find"               -> "browser: find ${txt ?: sel ?: ""}".trim()
            else                 -> "browser: $action"
        }
    }

    companion object {
        private const val TAG = "BrowserUseOffload"
        private const val TOOL_NAME = "minis-browser-use"
        private const val VAR_MINIS_BROWSER = "/var/minis/browser"
        private const val EXECUTE_TIMEOUT_MS = 90_000L

        /**
         * Long-option flags this handler treats as booleans (no value expected).
         * Passed to [OffloadArgs] so the shared parser doesn't greedily consume
         * the next token — e.g. `--compact navigate` would otherwise record
         * `compact=navigate`.
         */
        private val BOOLEAN_FLAGS = setOf(
            "compact", "quiet", "with-base64", "with_base64",
            "reset", "fuzzy", "help",
        )

        // Error codes — match iOS NOFF_ERR_* constants.
        private const val ERR_INVALID_ARGS = "invalid_args"
        /** [T-tools-granular-switches] The user turned browser_use off. */
        private const val ERR_DISABLED = "tool_disabled"
        private const val ERR_INTERNAL = "internal_error"

        private val HELP = """
minis-browser-use - Drive the in-app WebView from the shell

USAGE:
  minis-browser-use <action> [options]
  minis-browser-use --json '<json>'
  minis-browser-use --help

ACTIONS:
  navigate        --url <url>
  screenshot
  click           --selector <css> | --coordinate-x <n> --coordinate-y <n>
  type            --selector <css> --text <text>
  get_text        --selector <css>
  scroll          [--selector <css>] [--direction up|down] [--amount <px>]
  get_page_info
  execute_js      --script <js>
  find_elements   --selector <css>
  hover           --selector <css>
  get_readable
  set_user_agent  --user-agent mobile_chrome|desktop_chrome   (this tab only; aliases mobile, desktop)
  set_viewport    --width <n> --height <n> | --reset
  get_backbone    [--max-depth <n>]
  fetch           --url <url>
  new_tab         [--url <url>]
  close_tab       [--tab-id <n>]
  list_tabs
  get_cookies     --keywords <list> [--fuzzy]
  set_cookies     --cookies <json-array> | --cookies-file <path>
                  Content (for both: the file holds the SAME JSON array):
                    [{"name":"n","value":"v",            // required
                      "domain":".x.com","path":"/",       // optional
                      "secure":true,"http_only":true,     // optional
                      "expires":1893456000}]              // optional unix secs
                  domain defaults to the current page host, path to "/".
                  Aliases accepted: httpOnly, expirationDate, sameSite.
                  --cookies-file also accepts a Netscape cookies.txt
                  (curl/wget/browser export, TAB-separated).
                  Prefer --cookies-file: the JSON's quotes/braces survive
                  the shell intact — write the array to a temp file first.
  scroll_and_collect --scroll-count <n> --item-selector <css> [--keywords <list>]
  wait_for_dom_stable [--timeout <ms>]

COMMON OPTIONS:
  --tab-id <n>     Route the action to a specific tab (default: active tab)
  --json '<s>'     Pass the full input object as JSON (matches browser_use schema)
  --with-base64    Also include image_base64 in the screenshot output. Off by
                   default — screenshots are persisted to /var/minis/browser/
                   and referenced via image_path + minis_url.
  --compact        Minimize JSON output
  -q, --quiet      Output only the data field
  -h, --help       Show this help message

OUTPUT:
  JSON envelope with a `data` object containing:
    text              Human-readable result the agent would see
    success           true / false
    page_url          URL after the action (when applicable)
    image_path        Linux path of the persisted JPEG under /var/minis/browser/
    minis_url         minis://browser/<filename> — stable reference for
                      read_image / downstream tools
    image_base64      Base64 JPEG (only when --with-base64 is set)
    fetched_file      Filename of the downloaded resource (fetch action)
    fetched_path      Linux path of the persisted download under /var/minis/browser/
    fetched_minis_url minis://browser/<filename> for the download

EXAMPLES:
  minis-browser-use navigate --url https://example.com
  minis-browser-use screenshot
  minis-browser-use click --selector '.btn-primary'
  minis-browser-use type --selector 'input[name=q]' --text 'hello'
  minis-browser-use execute_js --script 'return document.title'
  minis-browser-use --json '{"action":"navigate","url":"https://x.com"}'
""".trimIndent() + "\n"
    }
}

// ─── Merged from NotificationOffloadHandler.kt ──────────────────────────────────────────────────

/**
 * android-notification — post, schedule, cancel, or read device notifications.
 *
 * Mirrors apple-notification (NativeOffloads/NotificationOffload.m): same
 * subcommands and same flag names so prompts and the agent loop are
 * platform-agnostic. Legacy aliases are preserved (`send` → `schedule`,
 * `list` for active notifications still works alongside `pending` for
 * not-yet-fired ones).
 *
 * Usage:
 *   android-notification schedule --title T --body B [--after SECS | --at ISO]
 *                                 (alias: send)
 *   android-notification list                  Active (already-delivered)
 *   android-notification pending               Scheduled but not yet fired
 *   android-notification cancel --id <id> | --all
 *   android-notification clear                 Clear all delivered (legacy)
 *   android-notification settings              Authorization status
 */
class NotificationOffloadHandler(private val context: Context) : NativeOffloadHandler {
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val store = ScheduledNotificationStore(context)

    init { ensureChannel() }

    private fun ensureChannel() {
        if (channelCreated) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        channelCreated = true
    }

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1), booleanFlags = setOf("all"))
        // [T-offload-defaults-batch-android] Help only on explicit
        // --help/-h; no subcommand defaults to `list`.
        if (args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, HELP)
        }

        return try {
            when (val sub = args.positional.firstOrNull() ?: "list") {
                "send", "schedule" -> handleSend(args)
                "clear" -> {
                    nm.cancelAll()
                    NativeOffloadResult(0, OffloadOutput.formatBody("All notifications cleared.", args) + "\n")
                }
                "list" -> handleList(args)
                "pending" -> handlePending(args)
                "cancel" -> handleCancel(args)
                "settings" -> handleSettings(args)
                else -> NativeOffloadResult(2, "android-notification: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: SecurityException) {
            val body = JSONObject().put("error", "notification_denied")
                .put("message", "Notification post denied: ${e.message}")
                .toString()
            NativeOffloadResult(77, OffloadOutput.formatBody(body, args) + "\n")
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            val body = JSONObject().put("error", "notification_failed")
                .put("message", e.message ?: "unknown").toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    // ── send ────────────────────────────────────────────────────────────────

    private fun handleSend(args: OffloadArgs): NativeOffloadResult {
        val title = args.get("title")
            ?: return NativeOffloadResult(2, "android-notification send: --title is required\n")

        // Android 13+: POST_NOTIFICATIONS is a runtime permission. First try
        // the system dialog; if the user has permanently denied it the dialog
        // won't re-appear, so fall back to an in-app "go to settings" gate
        // that polls for the grant.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val result = runBlocking {
                var r = OffloadPermissionManager.requestAndroidPermission(
                    listOf(Manifest.permission.POST_NOTIFICATIONS)
                )
                val hasNotifPerm = {
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                }
                if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED &&
                    OffloadPermissionManager.pollForPermissionGrant(hasNotifPerm)
                ) {
                    AppLogger.info(TAG, "Notification permission granted during post-DENY poll")
                    r = OffloadPermissionManager.AndroidPermissionResult.GRANTED
                }
                if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED) {
                    // Dialog either won't re-appear or the user just tapped
                    // "Don't allow". Offer the in-app path to Settings.
                    r = OffloadPermissionManager.requestSettingsGate(
                        OffloadPermissionManager.SettingsGateRequest(
                            id = Manifest.permission.POST_NOTIFICATIONS,
                            title = "Notifications are off",
                            message = "Minis needs notification permission to send notifications. Open Settings to allow it.",
                            settingsAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            requiresPackageUri = true,
                            positiveLabel = "Open Settings",
                        ),
                        check = {
                            ContextCompat.checkSelfPermission(
                                context, Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED
                        },
                    )
                }
                r
            }
            when (result) {
                OffloadPermissionManager.AndroidPermissionResult.GRANTED -> {} // continue
                OffloadPermissionManager.AndroidPermissionResult.DENIED -> {
                    return NativeOffloadResult(
                        77,
                        OffloadOutput.formatBody(
                            JSONObject().put("error", "permission_denied")
                                .put("message", "The user declined the notification permission.")
                                .toString(),
                            args,
                        ) + "\n",
                    )
                }
                OffloadPermissionManager.AndroidPermissionResult.TIMEOUT -> {
                    return NativeOffloadResult(
                        77,
                        OffloadOutput.formatBody(
                            JSONObject().put("error", "timeout")
                                .put("message", "Timed out waiting for the user to respond to the notification permission prompt.")
                                .toString(),
                            args,
                        ) + "\n",
                    )
                }
            }
        }

        val body = args.get("body") ?: ""

        // Deferred path: --after <seconds> or --at <ISO> schedules the
        // notification via AlarmManager + ScheduledNotificationReceiver.
        // Mirrors apple-notification's UNTimeIntervalNotificationTrigger
        // (after) and UNCalendarNotificationTrigger (at) semantics.
        val afterSec = args.getInt("after")
        val atStr = args.get("at")
        if (afterSec != null || atStr != null) {
            return scheduleDeferred(title, body, afterSec, atStr, args)
        }

        // Immediate path (apple-notification has no equivalent — UN
        // schedules with trigger=nil deliver immediately, which is what
        // our pre-T58 `send` already does).
        val id = UUID.randomUUID().toString()
        val notifId = id.hashCode() and 0x7FFFFFFF
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            // [GH#116] Without this the notification is inert: tapping it did
            // nothing and setAutoCancel merely dismissed it. The deferred
            // (--after/--at) path has always set one; this immediate path
            // never did, so plain `send` — the most common invocation — was
            // the one broken shape. Same helper both sides, see its KDoc.
            .setContentIntent(ScheduledNotificationReceiver.contentIntentFor(context, notifId))
            .build()
        nm.notify(notifId, n)
        // The system fans the post to bound listeners on its own thread, so
        // an immediate follow-up `list` may miss this notification. Block
        // until our listener observes it (best-effort: 2s, only when the
        // listener is connected — otherwise `list` would have failed anyway).
        if (MinisNotificationListenerService.isEnabled(context) &&
            MinisNotificationListenerService.isConnected()
        ) {
            val seen = MinisNotificationListenerService.awaitPosted(context.packageName, notifId)
            if (!seen) {
                AppLogger.warning(TAG, "listener did not observe posted id=$notifId within timeout — list may not see it immediately")
            }
        }
        AppLogger.info(TAG, "send (immediate): id=$id title='$title'")
        val oemHint = if (OsCompat.isHuawei || OsCompat.isXiaomi) {
            "On ${OsCompat.oemLabel()}, banner notifications may be disabled by default — the user can enable them in Settings → Notifications for Minis."
        } else null
        val data = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("body", body)
            .put("scheduled", "immediate")
        if (oemHint != null) data.put("oem_hint", oemHint)
        return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
    }

    // ── schedule (deferred) ─────────────────────────────────────────────

    /**
     * Schedule a notification to fire after [afterSec] seconds, or at
     * [atStr] (ISO 8601). Uses AlarmManager.setExactAndAllowWhileIdle so
     * the notification fires even in Doze; mirrors what AlarmOffloadManager
     * does for alarms. Persists the entry so `pending` and `cancel --id`
     * can find it.
     */
    private fun scheduleDeferred(
        title: String,
        body: String,
        afterSec: Int?,
        atStr: String?,
        args: OffloadArgs,
    ): NativeOffloadResult {
        val triggerAtMs: Long = when {
            afterSec != null -> {
                if (afterSec <= 0) return NativeOffloadResult(2, "android-notification: --after must be positive\n")
                System.currentTimeMillis() + afterSec * 1000L
            }
            atStr != null -> parseIso(atStr)
                ?: return NativeOffloadResult(2, "android-notification: invalid --at '$atStr' (use ISO 8601)\n")
            else -> return NativeOffloadResult(2, "android-notification: --after or --at is required\n")
        }
        if (triggerAtMs <= System.currentTimeMillis()) {
            return NativeOffloadResult(2, "android-notification: trigger time is in the past\n")
        }

        val id = args.get("id") ?: UUID.randomUUID().toString()
        val requestCode = id.hashCode() and 0x7FFFFFFF

        val intent = Intent(context, ScheduledNotificationReceiver::class.java).apply {
            putExtra(ScheduledNotificationReceiver.EXTRA_ID, id)
            putExtra(ScheduledNotificationReceiver.EXTRA_TITLE, title)
            putExtra(ScheduledNotificationReceiver.EXTRA_BODY, body)
        }
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        } catch (e: SecurityException) {
            val errBody = JSONObject().put("error", "exact_alarm_denied")
                .put("message", "Exact alarms blocked. On Android 14+ grant 'Alarms & reminders' in Settings; on Xiaomi/Huawei/Oppo/OnePlus/Vivo also enable autostart and disable battery optimization. Underlying: ${e.message}")
                .toString()
            return NativeOffloadResult(77, OffloadOutput.formatBody(errBody, args) + "\n")
        }

        val entry = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("body", body)
            .put("trigger_at_ms", triggerAtMs)
            .put("trigger_at", formatIso(triggerAtMs))
            .put("request_code", requestCode)
        store.add(entry)

        AppLogger.info(TAG, "schedule: id=$id title='$title' trigger=${formatIso(triggerAtMs)}")
        val data = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("body", body)
            .put("scheduled", formatIso(triggerAtMs))
        return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
    }

    // ── cancel ──────────────────────────────────────────────────────────

    /**
     * Cancel pending (scheduled-but-not-yet-fired) notifications. Mirrors
     * apple-notification cancel: --id removes one, --all removes all.
     * If the id matches an already-delivered notification (very common —
     * the model can't tell pending vs delivered), fall through to nm.cancel
     * on the integer hash so the user-visible notification disappears too.
     */
    private fun handleCancel(args: OffloadArgs): NativeOffloadResult {
        if (args.hasFlag("all")) {
            // Cancel pending: walk store, drop alarms, clear prefs.
            val all = store.loadAll()
            var n = 0
            for (i in 0 until all.length()) {
                val o = all.getJSONObject(i)
                cancelAlarmFor(o.optString("id"), o.optInt("request_code"))
                n++
            }
            store.clear()
            // Also clear delivered notifications (matches apple-notification
            // cancel --all semantics: nothing the user sees from us remains).
            nm.cancelAll()
            AppLogger.info(TAG, "cancel --all: pending=$n")
            val data = JSONObject().put("cancelled", "all").put("pending_cleared", n)
            return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
        }
        val id = args.get("id")
            ?: return NativeOffloadResult(2, "android-notification cancel: --id <id> or --all is required\n")

        val entry = store.get(id)
        if (entry != null) {
            cancelAlarmFor(id, entry.optInt("request_code"))
            store.remove(id)
        }
        // Also drop any delivered notification posted with the same id —
        // notifId = id.hashCode() & 0x7FFFFFFF, same convention as both
        // immediate-send and scheduled-fire.
        val notifId = id.hashCode() and 0x7FFFFFFF
        nm.cancel(notifId)

        AppLogger.info(TAG, "cancel: id=$id pending=${entry != null}")
        val data = JSONObject().put("cancelled", id).put("was_pending", entry != null)
        return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
    }

    private fun cancelAlarmFor(id: String, requestCode: Int) {
        val intent = Intent(context, ScheduledNotificationReceiver::class.java).apply {
            putExtra(ScheduledNotificationReceiver.EXTRA_ID, id)
        }
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pi)
        pi.cancel()
    }

    // ── pending ─────────────────────────────────────────────────────────

    /** List scheduled-but-not-yet-fired notifications. iOS equivalent is
     *  UNUserNotificationCenter.getPendingNotificationRequests. */
    private fun handlePending(args: OffloadArgs): NativeOffloadResult {
        store.sweepExpired()
        val all = store.loadAll()
        val out = JSONObject()
            .put("notifications", all)
            .put("count", all.length())
        return NativeOffloadResult(0, OffloadOutput.formatBody(out.toString(2), args) + "\n")
    }

    // ── settings ────────────────────────────────────────────────────────

    /**
     * Return notification authorization + per-feature state. Mirrors
     * apple-notification settings — same field names where possible
     * (authorization_status, sound_enabled, badge_enabled, alert_enabled,
     * lock_screen_enabled). Android-only adds: can_post,
     * channel_importance, schedule_exact_allowed, listener_access.
     */
    private fun handleSettings(args: OffloadArgs): NativeOffloadResult {
        val canPost = NotificationManagerCompat.from(context).areNotificationsEnabled()
        // No iOS-style "ephemeral / provisional" on Android — collapse to
        // authorized vs denied, matching what users see in settings.
        val authStatus = if (canPost) "authorized" else "denied"
        val data = JSONObject()
            .put("authorization_status", authStatus)
            .put("can_post", canPost)
            .put("alert_enabled", canPost)
            .put("sound_enabled", canPost)
            .put("badge_enabled", canPost)
            .put("lock_screen_enabled", canPost)
            .put("notification_center_enabled", canPost)
            .put("critical_alert_enabled", false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = nm.getNotificationChannel(CHANNEL_ID)
            data.put("channel_id", CHANNEL_ID)
            data.put("channel_importance", channel?.importance ?: -1)
            data.put("channel_enabled", channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            val canSchedule = try { am?.canScheduleExactAlarms() == true } catch (_: Throwable) { false }
            data.put("schedule_exact_allowed", canSchedule)
        }
        data.put("listener_access", MinisNotificationListenerService.isEnabled(context))
        AppLogger.info(TAG, "settings: can_post=$canPost listener=${MinisNotificationListenerService.isEnabled(context)}")
        return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** ISO 8601 with offset, matches noff_format_date on iOS. */
    private fun formatIso(ms: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        return sdf.format(Date(ms))
    }

    /** Parse ISO 8601 into ms epoch. Accepts the same set of forms as the
     *  alarm and calendar handlers so prompts can use any convention. */
    private fun parseIso(s: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
        )
        for (pat in patterns) {
            try {
                val sdf = SimpleDateFormat(pat, Locale.US).apply {
                    timeZone = if (pat.endsWith("'Z'")) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
                }
                return sdf.parse(s)?.time
            } catch (_: Throwable) {}
        }
        return null
    }

    // ── list ────────────────────────────────────────────────────────────────

    private fun handleList(args: OffloadArgs): NativeOffloadResult {
        if (!MinisNotificationListenerService.isEnabled(context)) {
            // Notification Access can only be granted from the system settings
            // page — requestPermissions is not an option. Use the in-app
            // "settings gate" which shows a dialog, opens the settings page,
            // and polls for the user to complete the grant.
            val result = runBlocking {
                OffloadPermissionManager.requestSettingsGate(
                    OffloadPermissionManager.SettingsGateRequest(
                        id = "notification_access",
                        title = "Notification access needed",
                        message = "Minis needs Notification access to read the status-bar notifications. Open Settings and enable \"Minis\" under Notification access.",
                        settingsAction = MinisNotificationListenerService.SETTINGS_ACTION,
                        requiresPackageUri = false,
                        positiveLabel = "Open Settings",
                    ),
                    check = { MinisNotificationListenerService.isEnabled(context) },
                )
            }
            when (result) {
                OffloadPermissionManager.AndroidPermissionResult.GRANTED -> {} // continue
                OffloadPermissionManager.AndroidPermissionResult.DENIED -> {
                    return NativeOffloadResult(
                        77,
                        OffloadOutput.formatBody(
                            JSONObject()
                                .put("error", "notification_access_not_granted")
                                .put("message", "The user declined to grant Notification access.")
                                .toString(),
                            args,
                        ) + "\n",
                    )
                }
                OffloadPermissionManager.AndroidPermissionResult.TIMEOUT -> {
                    return NativeOffloadResult(
                        77,
                        OffloadOutput.formatBody(
                            JSONObject()
                                .put("error", "timeout")
                                .put("message", "Timed out waiting for the user to enable Notification access.")
                                .toString(),
                            args,
                        ) + "\n",
                    )
                }
            }
        }

        // `NotificationManager.activeNotifications` only exposes this app's
        // own notifications. The cross-app list comes from our bound
        // `NotificationListenerService`.
        val active: Array<StatusBarNotification> =
            MinisNotificationListenerService.getActiveNotifications()
                ?: return NativeOffloadResult(
                    77,
                    OffloadOutput.formatBody(
                        JSONObject()
                            .put("error", "listener_not_connected")
                            .put("message", "Notification listener is authorized but not yet connected. Please try again in a moment.")
                            .toString(),
                        args,
                    ) + "\n",
                )

        val max = args.get("max")?.toIntOrNull()?.coerceAtLeast(1) ?: 50
        val pm = context.packageManager
        val appNameCache = mutableMapOf<String, String>()

        val arr = JSONArray()
        var i = 0
        for (sbn in active.sortedByDescending { it.postTime }) {
            if (i >= max) break
            val extras = sbn.notification.extras
            val title = extras.getCharSequence("android.title")?.toString() ?: ""
            val text = extras.getCharSequence("android.text")?.toString()
                ?: extras.getCharSequence("android.bigText")?.toString()
                ?: ""
            val subText = extras.getCharSequence("android.subText")?.toString()
            val pkg = sbn.packageName ?: ""
            val appName = appNameCache.getOrPut(pkg) {
                try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Throwable) { pkg }
            }
            val obj = JSONObject()
                .put("package", pkg)
                .put("app", appName)
                .put("title", title)
                .put("body", text)
                .put("post_time", sbn.postTime)
            if (!subText.isNullOrEmpty()) obj.put("subtitle", subText)
            if (sbn.isOngoing) obj.put("ongoing", true)
            arr.put(obj)
            i++
        }

        val out = JSONObject()
            .put("count", arr.length())
            .put("total_active", active.size)
            .put("notifications", arr)
            .toString(2)
        return NativeOffloadResult(0, OffloadOutput.formatBody(out, args) + "\n")
    }

    companion object {
        private const val TAG = "NotificationOffload"
        private const val CHANNEL_ID = "minis_agent_notifications"
        private const val CHANNEL_NAME = "Agent Notifications"
        private var channelCreated = false

        private const val HELP = """android-notification — send / schedule / list / cancel notifications
                              (mirrors apple-notification)

Usage:
  android-notification schedule --title T --body B [--after SECS | --at ISO]
                                       (alias: send — same flags)
  android-notification                  Same as `list` (default subcommand)
  android-notification list [--max N]   Active (already-delivered)
  android-notification pending          Scheduled but not yet fired
  android-notification cancel --id <id>
  android-notification cancel --all
  android-notification clear            Cancel all delivered (legacy)
  android-notification settings         Authorization + channel state

Examples:
  android-notification schedule --title "Reminder" --body "Standup" --after 300
  android-notification schedule --title "Meeting" --body "..." --at 2026-04-26T15:00:00
  android-notification cancel --id <id-from-pending>

Notes:
  - schedule/send triggers a system permission prompt on Android 13+
    if POST_NOTIFICATIONS has not been granted yet.
  - --after / --at use AlarmManager.setExactAndAllowWhileIdle so they
    fire reliably even in Doze. Requires SCHEDULE_EXACT_ALARM on
    Android 14+ (settings reports schedule_exact_allowed).
  - list needs Notification access (Settings → Apps → Special app
    access → Notification access → Minis). First list call opens it.
"""
    }
}

// ─── Merged from SessionsOffloadHandler.kt ──────────────────────────────────────────────────

/**
 * T188 — `minis-sessions-cli` offload handler. Lets the in-shell agent
 * query historical chat sessions and messages without round-tripping
 * back through the LLM. Four subcommands:
 *
 *   list       List recent sessions, optionally filtered by id, keyword,
 *              and date range. Default if no subcommand is given.
 *   search     Search message content across sessions; --keywords
 *              required.
 *   messages   Read a paginated transcript of one session; --id required.
 *   status     [T-subagent-termination] Current state of one session; --id
 *              required. Same fields as iOS `status`, including how a sub
 *              agent run ended (termination_reason, last_tool_status, …).
 *   update     [T-sessions-cli-update] Change a session's title, group and/or
 *              icon through the same repository calls the session list's
 *              Edit sheet and group menu use (the UI refreshes from Room).
 *   groups     [T-sessions-cli-update] Every group with its session count.
 *
 * Mirrors iOS canonical CLI surface: argv shape, JSON envelope, exit
 * codes, error messages all line up. The on-device db (Room) is the
 * same shape as iOS (sqlite tables `sessions`/`messages` with matching
 * column names), so query semantics translate one-to-one.
 *
 * Output flags `--compact` / `-q` / `--quiet` are honored uniformly via
 * [OffloadOutput.formatBody], same as every other android-* / minis-*
 * tool.
 */
class SessionsOffloadHandler(
    private val repo: ChatRepository,
    /** The authoritative is-running signal (iOS: SessionActivityTracker.isActive). */
    private val isRunning: (String) -> Boolean = { com.openminis.app.service.SessionActivityTracker.isActive(it) },
    /** A live sub agent job still runs in this session (registry), for `interrupted` resolution. */
    private val hasLiveJob: (String) -> Boolean = { sid ->
        com.openminis.app.agent.jobs.AgentJobRegistry.list().any { it.runSessionId == sid && it.isActive }
    },
    /**
     * [T-sessions-cli-update] `update --title-auto`: the session list's
     * Regenerate Title (SessionTitleRegenerator); true when a title was written.
     */
    private val titleRegenerator: (suspend (String) -> Boolean)? = null,
) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        // argv[0] is the program name ("minis-sessions-cli"); subcommand
        // and options follow. Drop argv[0] before parsing so positional[0]
        // is the subcommand name. `full` is declared boolean so
        // `--full <token>` never greedily consumes the next token as a value
        // (e.g. `--full` placed before the subcommand). Same for `--tools`.
        val args = OffloadArgs(request.argv.drop(1), booleanFlags = setOf("full", "tools", "title-auto"))
        if (args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, OffloadOutput.formatBody(HELP_TEXT, args) + "\n")
        }

        val sub = args.positional.firstOrNull() ?: "list"
        return try {
            when (sub) {
                "list" -> cmdList(args)
                "search" -> cmdSearch(args)
                "messages" -> cmdMessages(args)
                "status" -> cmdStatus(args)
                "update" -> cmdUpdate(args)
                "groups" -> cmdGroups(args)
                else -> {
                    val err = errorEnvelope(
                        sub,
                        "INVALID_ARGS",
                        "Unknown command '$sub'. Valid: list, search, messages, status, update, groups. " +
                            "Use --help for details.",
                    )
                    NativeOffloadResult(
                        EXIT_INVALID_ARGS,
                        OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
                    )
                }
            }
        } catch (e: Throwable) {
            // Last-ditch: any unexpected exception (sql parse error, OOM,
            // …) becomes an INTERNAL envelope rather than a crash so the
            // shell stays alive and the agent can read the failure.
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            val err = errorEnvelope(sub, "INTERNAL", e.message ?: "unknown error")
            NativeOffloadResult(
                1,
                OffloadOutput.formatBody(err.toString(2), args) + "\n",
            )
        }
    }

    private fun cmdList(args: OffloadArgs): NativeOffloadResult {
        val ids = parseIds(args)
        val kws = parseKeywords(args)
        val startMs = parseDate(args.get("start"))
        val endMs = parseEndDate(args.get("end"))
        val limit = parseLimit(args)
        // [T-sessions-cli-update] --group <name>: exact, case-sensitive name
        // (every group with that name - names are not unique); "" = ungrouped.
        val groupFilter = args.get("group")?.let(SessionsUpdateRules::normalizedGroup)

        val (metas, folderNames) = runBlocking {
            val folders = repo.listFolders()
            val folderIds = groupFilter?.let { g ->
                if (g.isEmpty()) emptyList() else folders.filter { SessionsUpdateRules.groupNameMatches(it.name, g) }.map { it.id }
            }
            val rows = if (folderIds != null && groupFilter!!.isNotEmpty() && folderIds.isEmpty()) emptyList()
            else repo.querySessionsMeta(ids, kws, limit, startMs, endMs, folderIds)
            rows to folders.associate { it.id to it.name }
        }

        val sessions = JSONArray()
        for (m in metas) {
            val s = JSONObject()
                .put("session_id", m.id)
                .put("started_at", DATE_FMT.format(Date(m.startedAt)))
                .put("last_active", DATE_FMT.format(Date(m.lastActive)))
                .put("message_count", m.messageCount)
            // Optional fields — only emit when non-null so the JSON
            // matches iOS's `(optional)` shape rather than carrying
            // null literals through to the agent prompt.
            // [T-sessions-cli-update] title / group / icon are always present
            // ("" = untitled / ungrouped / default icon).
            s.put("title", m.title.orEmpty())
            s.put("group", m.folderId?.let { folderNames[it] }.orEmpty())
            s.put("icon", m.category.orEmpty())
            m.preview?.let { if (it.isNotBlank()) s.put("preview", it) }
            m.source?.let { if (it.isNotBlank()) s.put("source", it) }
            sessions.put(s)
        }
        val data = JSONObject()
            .put("count", sessions.length())
            .put("sessions", sessions)
        return emit("list", data, args)
    }

    private fun cmdSearch(args: OffloadArgs): NativeOffloadResult {
        val kws = parseKeywords(args).orEmpty()
        if (kws.isEmpty()) {
            val err = errorEnvelope(
                "search",
                "INVALID_ARGS",
                "--keywords is required for search. " +
                    "Example: minis-sessions-cli search --keywords \"API error\"",
            )
            return NativeOffloadResult(
                EXIT_INVALID_ARGS,
                OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
            )
        }
        val ids = parseIds(args)
        val startMs = parseDate(args.get("start"))
        val endMs = parseEndDate(args.get("end"))
        val limit = parseLimit(args)

        val matches = runBlocking { repo.searchMessages(ids, kws, limit, startMs, endMs) }

        val msgs = JSONArray()
        for (m in matches) {
            msgs.put(
                JSONObject()
                    .put("session_id", m.sessionId)
                    .put("message_id", m.messageId)
                    .put("role", m.role)
                    .put("created_at", DATE_FMT.format(Date(m.createdAt)))
                    .put("snippet", m.snippet),
            )
        }
        val data = JSONObject()
            .put("count", msgs.length())
            .put("messages", msgs)
        return emit("search", data, args)
    }

    private fun cmdMessages(args: OffloadArgs): NativeOffloadResult {
        val sessionId = args.get("id")?.takeIf { it.isNotBlank() }
            ?: run {
                val err = errorEnvelope(
                    "messages",
                    "INVALID_ARGS",
                    "--id <session_id> is required. Use 'list' first to find session IDs.",
                )
                return NativeOffloadResult(
                    EXIT_INVALID_ARGS,
                    OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
                )
            }
        val limit = parseLimit(args)
        val offset = (args.getInt("offset") ?: 0).coerceAtLeast(0)
        // [T-android-sessions-cli-full] --full lifts the per-message text cap
        // from the default 600 to 50000 chars (iOS parity). Previously the flag
        // was silently ignored — every message stayed truncated at 600 no
        // matter what the caller passed, gutting transcript exports.
        val full = args.hasFlag("full")
        val maxChars = if (full) ChatRepository.MESSAGE_TEXT_MAX_FULL else ChatRepository.MESSAGE_TEXT_MAX
        // [T-android-sessions-cli-messages-daterange] GH#200 (iOS 8f3189a73).
        // HELP_TEXT documents --start / --end and `list` / `search` honour
        // them, but `messages` parsed neither — the CLI accepted the flags and
        // silently returned the whole session, which reads as the filter
        // working and matching everything.
        val startMs = parseDate(args.get("start"))
        val endMs = parseEndDate(args.get("end"))
        // [T-android-sessions-cli-tool-calls] Opt-in, so the default output is
        // unchanged (iOS 7af22b59f).
        val tools = args.hasFlag("tools")

        val (page, total) = runBlocking {
            // Single coroutine block so the two reads see a consistent
            // snapshot — ordering matters less than ensuring we don't
            // surface a `total` that disagrees with the slice we
            // returned (e.g. another session interleaving inserts mid-
            // call). Room serializes via the suspending dispatcher so
            // these run sequentially in the same coroutine.
            // Both reads take the SAME date range on purpose: an unfiltered
            // count next to a filtered page would make `total` describe the
            // whole session while the slice covers only the matches, so
            // `hasMore` would lie. iOS 8f3189a73 calls this out explicitly.
            repo.loadMessagePage(sessionId, offset, limit, maxChars, startMs, endMs, includeTools = tools) to
                repo.messageCountInRange(sessionId, startMs, endMs)
        }

        val msgs = JSONArray()
        for (m in page) {
            val obj = JSONObject()
                .put("message_id", m.messageId)
                .put("role", m.role)
                .put("created_at", DATE_FMT.format(Date(m.createdAt)))
                .put("text", m.text)
            // Only present when the stored text exceeded the cap, so normal
            // messages serialize byte-identically to before (iOS parity).
            if (m.truncated) obj.put("truncated", true)
            if (tools) {
                obj.put("tool_calls", toolCallsJson(m.toolUses, maxChars))
                obj.put("tool_results", toolResultsJson(m.toolResults, maxChars))
            }
            msgs.put(obj)
        }
        val data = JSONObject()
            .put("session_id", sessionId)
            .put("offset", offset)
            .put("limit", limit)
            .put("full", full)
            .put("max_chars", maxChars)
            .put("total", total)
            .put("count", msgs.length())
            .put("messages", msgs)
        // Only when on, so the default envelope keeps exactly its old keys.
        if (tools) data.put("tools", true)
        return emit("messages", data, args)
    }

    /**
     * [T-subagent-termination] `status --id <session_id>`: is it running, what
     * did it last say and do, and — for a sub agent session — how did its run
     * end. Port of iOS `getSessionStatus` with its 2026-10-04 fixes:
     * last_message / last_tool come from the stored transcript (iOS returned ""
     * for any session whose view model was not cached), and the termination
     * record is merged in. A record still `running` with nothing alive behind
     * it is reported (and stored) as `interrupted`.
     */
    private fun cmdStatus(args: OffloadArgs): NativeOffloadResult {
        val sessionId = (args.get("id") ?: args.positional.getOrNull(1))?.takeIf { it.isNotBlank() }
            ?: run {
                val err = errorEnvelope("status", "INVALID_ARGS", "--id <session_id> is required for status.")
                return NativeOffloadResult(
                    EXIT_INVALID_ARGS,
                    OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
                )
            }
        val (session, rows) = runBlocking { repo.getSession(sessionId) to repo.loadMessages(sessionId) }
        if (session == null) {
            val err = errorEnvelope("status", "session_not_found", "No session with id $sessionId.")
            err.getJSONObject("data").put("session_id", sessionId)
            return NativeOffloadResult(1, OffloadOutput.formatBody(err.toString(2), args) + "\n")
        }
        val running = isRunning(sessionId)
        val termination = com.openminis.app.agent.jobs.SubAgentTerminationStore.shared
            .resolved(sessionId, isLive = running || hasLiveJob(sessionId))
        val group = runBlocking { session.folderId?.let { repo.getFolder(it)?.name } }.orEmpty()
        return emit("status", statusData(session, rows, running, termination, group), args)
    }

    /**
     * [T-sessions-cli-update] `update --id <id> [--title t | --title-auto] [--group g] [--icon i]`.
     * Same contract and order of steps as iOS SessionsOffloadBridge.updateSession:
     *
     *  1. Argument errors ([SessionsUpdateRules.validate]) before any lookup.
     *  2. session_not_found / not_allowed (a sub agent's hidden session).
     *  3. --title-auto first, through the list's own Regenerate Title
     *     ([titleRegenerator]); if it fails nothing else is written.
     *  4. Title / icon in one write - the Edit sheet's own repository call -
     *     skipped when nothing differs, so a no-op does not re-sort the list.
     *  5. Group: exact (trimmed) name, created when missing, "" ungroups -
     *     the group picker's createFolder / setFolderForSessions.
     *  6. `changed`: the fields whose value differs, in the order title, group, icon.
     *
     * Every write goes through ChatRepository, so the list and an open chat
     * refresh from Room as after an edit in the app.
     */
    private fun cmdUpdate(args: OffloadArgs): NativeOffloadResult {
        for (f in listOf("id", "title", "group", "icon")) {
            if (args.hasFlag(f) && args.get(f) == null) return updateFail(args, SessionsUpdateRules.INVALID_ARGUMENTS, "--$f needs a value.")
        }
        val id = args.get("id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return updateFail(args, SessionsUpdateRules.INVALID_ARGUMENTS,
                "--id <session_id> is required for update. Use 'list' first to find a session ID.")
        val req = SessionsUpdateRules.Request(
            title = args.get("title"),
            titleAuto = args.hasFlag("title-auto"),
            group = args.get("group"),
            icon = args.get("icon"),
        )
        SessionsUpdateRules.validate(req)?.let { return updateFail(args, it.code, it.message) }

        val outcome = runBlocking { performUpdate(id, req) }
        return when (outcome) {
            is SessionsUpdateRules.Outcome.Failed -> updateFail(args, outcome.code, outcome.message)
            is SessionsUpdateRules.Outcome.Done -> {
                AppLogger.info(TAG, "[SessionsCLI] update ${id.take(8)} changed=${outcome.changed}")
                emit("update", JSONObject()
                    .put("id", id)
                    .put("title", outcome.after.title)
                    .put("group", outcome.after.group)
                    .put("icon", outcome.after.icon)
                    .put("changed", JSONArray(outcome.changed)), args, timestamp = true)
            }
        }
    }

    private suspend fun snapshot(session: com.openminis.app.data.db.ChatSessionEntity) = SessionsUpdateRules.Snapshot(
        title = session.title.orEmpty(),
        group = session.folderId?.let { repo.getFolder(it)?.name }.orEmpty(),
        icon = session.category.orEmpty(),
    )

    private suspend fun performUpdate(id: String, req: SessionsUpdateRules.Request): SessionsUpdateRules.Outcome {
        val fail = SessionsUpdateRules.Outcome::Failed
        val session = repo.getSession(id)
            ?: return fail(SessionsUpdateRules.SESSION_NOT_FOUND, "No session with id $id.")
        if (session.isChild) {
            return fail(SessionsUpdateRules.NOT_ALLOWED,
                "$id is a sub agent's hidden session; edit its parent ${session.parentSessionId.orEmpty()} instead.")
        }
        val before = snapshot(session)

        // 1. --title-auto first: on failure nothing else has been written.
        if (req.titleAuto) {
            val regen = titleRegenerator
                ?: return fail(SessionsUpdateRules.TITLE_AUTO_FAILED, "Title regeneration is not available.")
            val ok = runCatching { kotlinx.coroutines.withTimeoutOrNull(TITLE_AUTO_TIMEOUT_MS) { regen(id) } }.getOrElse {
                return fail(SessionsUpdateRules.TITLE_AUTO_FAILED, it.message ?: it.javaClass.simpleName)
            } ?: return fail(SessionsUpdateRules.TITLE_AUTO_FAILED, "Title regeneration timed out after ${TITLE_AUTO_TIMEOUT_MS / 1000} s.")
            if (!ok || repo.getSession(id)?.title.isNullOrBlank()) {
                return fail(SessionsUpdateRules.TITLE_AUTO_FAILED,
                    "No title was generated: the session needs at least one user message and one reply.")
            }
        }

        // 2. Title / icon: one write, skipped when nothing differs.
        if (req.title != null || req.icon != null) {
            val current = repo.getSession(id) ?: return fail(SessionsUpdateRules.SESSION_NOT_FOUND, "Session $id was deleted during the update.")
            val newTitle = req.title?.let(SessionsUpdateRules::normalizedTitle) ?: current.title
            val newCategory = if (req.icon != null) SessionsUpdateRules.normalizedIcon(req.icon)!!.ifEmpty { null } else current.category
            if (newTitle != current.title || newCategory != current.category) {
                when {
                    newTitle != null && newCategory != null -> repo.updateSessionTitleAndCategory(id, newTitle, newCategory)
                    newTitle != null && newTitle != current.title -> {
                        repo.updateSessionTitleAndCategory(id, newTitle, null)
                        if (newCategory != current.category) repo.updateSessionCategory(id, newCategory)
                    }
                    else -> repo.updateSessionCategory(id, newCategory)
                }
            }
        }

        // 3. Group: exact name, created when missing, "" ungroups.
        if (req.group != null) {
            val name = SessionsUpdateRules.normalizedGroup(req.group)
            val folderId = if (name.isEmpty()) null else {
                val folders = repo.listFolders()
                val matches = folders.filter { SessionsUpdateRules.groupNameMatches(it.name, name) }
                val activity = if (matches.size > 1) matches.associate { f ->
                    f.id to repo.sessionIdsInFolder(f.id).mapNotNull { repo.getSession(it)?.updatedAt }.maxOrNull()
                } else emptyMap()
                SessionsUpdateRules.pickFolder(name, folders.map { SessionsUpdateRules.FolderRef(it.id, it.name, it.createdAt) }, activity)
                    ?: repo.createFolder(name).id.also { AppLogger.info(TAG, "[SessionsCLI] update ${id.take(8)} created group") }
            }
            val current = repo.getSession(id)
            if (current != null && current.folderId != folderId) repo.setFolderForSessions(folderId, listOf(id))
        }

        val final = repo.getSession(id) ?: return fail(SessionsUpdateRules.SESSION_NOT_FOUND, "Session $id was deleted during the update.")
        val after = snapshot(final)
        return SessionsUpdateRules.Outcome.Done(after, SessionsUpdateRules.changedFields(before, after))
    }

    /**
     * iOS's error envelope (noff_json_error): `{ok:false, tool, action,
     * error:{code, message}}`; `-q` prints just `error`. Usage errors
     * (invalid_arguments, invalid_icon) exit 2, errors about the session 1.
     */
    private fun updateFail(args: OffloadArgs, code: String, msg: String): NativeOffloadResult {
        val error = JSONObject().put("code", code).put("message", msg)
        // An extra key the shared spec allows: the keys an agent can retry with.
        if (code == SessionsUpdateRules.INVALID_ICON) error.put("valid_icons", JSONArray(SessionsUpdateRules.ICON_NAMES))
        val err = JSONObject()
            .put("ok", false)
            .put("tool", TOOL_NAME)
            .put("action", "update")
            .put("error", error)
            .put("timestamp", isoNow())
        val exit = if (code == SessionsUpdateRules.INVALID_ARGUMENTS || code == SessionsUpdateRules.INVALID_ICON) EXIT_INVALID_ARGS else 1
        return NativeOffloadResult(exit, OffloadOutput.formatBody(err.toString(2), args) + "\n")
    }

    /**
     * [T-sessions-cli-update] `groups`: every group as `{name, session_count}`,
     * same-named groups separately. Order as the home list shows them:
     * pinned groups first, then groups by their newest member's activity,
     * then empty groups. `session_count` counts home-list sessions only (no
     * hidden child sessions).
     */
    private fun cmdGroups(args: OffloadArgs): NativeOffloadResult {
        val rows = runBlocking {
            repo.listFolders().map { f ->
                val members = repo.sessionIdsInFolder(f.id).mapNotNull { repo.getSession(it) }.filter { !it.isChild }
                Triple(f, members.size, members.maxOfOrNull { it.updatedAt })
            }
        }.sortedWith(
            compareByDescending<Triple<com.openminis.app.data.db.FolderEntity, Int, Long?>> { it.first.pinnedAt != null }
                .thenByDescending { it.first.pinnedAt ?: 0L }
                .thenByDescending { it.third != null }
                .thenByDescending { it.third ?: 0L },
        )
        val arr = JSONArray()
        for ((f, count, _) in rows) arr.put(JSONObject().put("name", f.name).put("session_count", count))
        return emit("groups", JSONObject().put("count", arr.length()).put("groups", arr), args, timestamp = true)
    }

    // ─── arg parsing helpers ──────────────────────────────────────────

    private fun parseIds(args: OffloadArgs): List<String>? {
        val raw = args.get("ids") ?: return null
        val parts = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return parts.takeIf { it.isNotEmpty() }
    }

    private fun parseKeywords(args: OffloadArgs): List<String>? {
        val raw = args.get("keywords") ?: return null
        // Split on any whitespace (the user may quote multi-word keywords
        // at the shell, in which case we get one big token containing a
        // space — collapse runs of whitespace into a single delimiter).
        val parts = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return parts.takeIf { it.isNotEmpty() }
    }

    private fun parseDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getDefault()
            sdf.isLenient = false
            sdf.parse(raw)?.time
        }.getOrNull()
    }

    /**
     * Inclusive end-of-day: `--end 2025-03-31` includes everything up to
     * and including 2025-03-31 23:59:59.999. Adding (1 day - 1 ms) is
     * safer than (24h - 1ms) across DST transitions on devices that
     * don't run UTC. Mirrors iOS SessionsOffload.m L141-145.
     */
    private fun parseEndDate(raw: String?): Long? {
        val ms = parseDate(raw) ?: return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = ms
            add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis - 1
    }

    private fun parseLimit(args: OffloadArgs): Int {
        val raw = args.getInt("limit") ?: return DEFAULT_LIMIT
        return raw.coerceIn(1, MAX_LIMIT)
    }

    // ─── envelope helpers ─────────────────────────────────────────────

    /**
     * [T-sessions-cli-update] `timestamp` (ISO 8601, local offset, as iOS
     * noff_format_date) is set for the commands specified together with iOS
     * (update, groups); the older commands keep their exact previous keys.
     */
    private fun emit(action: String, data: JSONObject, args: OffloadArgs, timestamp: Boolean = false): NativeOffloadResult {
        val envelope = JSONObject()
            .put("ok", true)
            .put("tool", TOOL_NAME)
            .put("action", action)
            .put("data", data)
        if (timestamp) envelope.put("timestamp", isoNow())
        return NativeOffloadResult(
            0,
            OffloadOutput.formatBody(envelope.toString(2), args) + "\n",
        )
    }

    private fun errorEnvelope(action: String, code: String, msg: String): JSONObject {
        return JSONObject()
            .put("ok", false)
            .put("tool", TOOL_NAME)
            .put("action", action)
            .put("data", JSONObject().put("error", code).put("message", msg))
    }

    companion object {
        private const val TAG = "SessionsOffload"
        private const val TOOL_NAME = "minis-sessions-cli"
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 100

        // iOS NOFF_EXIT_INVALID_ARGS — the shell convention is exit 2
        // for invalid CLI args, distinct from exit 1 for runtime errors.
        private const val EXIT_INVALID_ARGS = 2

        /** [T-sessions-cli-update] `--title-auto` gives up after this (shared spec 2.2). */
        private const val TITLE_AUTO_TIMEOUT_MS = 60_000L

        private fun isoNow(): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())

        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }

        /**
         * [T-subagent-termination] The `status` data object, pure for tests.
         * Keys match iOS: session_id, title, model_name, is_running,
         * last_message, last_tool, updated_at, plus — when known —
         * last_tool_status, last_tool_result, parent_session_id and the
         * termination fields (termination_reason, termination_detail,
         * last_tool_name, partial_output_available, closing_attempted,
         * delivery_state, delivery_detail, ended_at).
         *
         * last_message is the last assistant text of the stored transcript;
         * when there is none (a run that ended on a tool result) it falls back
         * to the session's recorded last activity (the list preview), and
         * last_tool_result carries what the last tool returned.
         */
        internal fun statusData(
            session: com.openminis.app.data.db.ChatSessionEntity,
            rows: List<com.openminis.app.data.db.MessageEntity>,
            isRunning: Boolean,
            termination: com.openminis.app.agent.jobs.SubAgentTermination?,
            /** [T-sessions-cli-update] Name of the session's group, "" = ungrouped. */
            group: String = "",
        ): JSONObject {
            val (lastAssistant, tool) = com.openminis.app.agent.jobs.SubAgentTerminationRules.lastActivity(rows)
            val lastMessage = lastAssistant.ifEmpty { session.lastMessage.orEmpty() }
            val data = JSONObject()
                .put("session_id", session.id)
                .put("title", session.title.orEmpty())
                .put("model_name", session.modelId)
                .put("is_running", isRunning)
                .put("last_message", lastMessage)
                .put("last_tool", tool?.name.orEmpty())
                .put("updated_at", DATE_FMT.format(Date(session.updatedAt)))
                .put("group", group)
                .put("icon", session.category.orEmpty())
            tool?.let {
                if (it.status.isNotEmpty()) data.put("last_tool_status", it.status)
                if (it.summary.isNotEmpty()) data.put("last_tool_result", it.summary)
            }
            session.parentSessionId?.let { data.put("parent_session_id", it) }
            termination?.putPayload(data)
            return data
        }

        /**
         * [T-android-sessions-cli-tool-calls] `tool_calls` for one message:
         * `{tool_use_id, name, input}`, input being the stored input JSON as a
         * string (so the field has one type even when cut mid-JSON), capped at
         * the same [maxChars] as `text` with `input_truncated` when cut.
         */
        internal fun toolCallsJson(uses: List<com.openminis.app.data.repository.OffloadToolUse>, maxChars: Int): JSONArray {
            val out = JSONArray()
            for (tu in uses) {
                val call = JSONObject()
                    .put("tool_use_id", tu.toolUseId)
                    .put("name", tu.name)
                    .put("input", tu.input.take(maxChars))
                if (tu.input.length > maxChars) call.put("input_truncated", true)
                out.put(call)
            }
            return out
        }

        /** [T-android-sessions-cli-tool-calls] `tool_results`: `{tool_use_id, success, output}`, same cap. */
        internal fun toolResultsJson(results: List<com.openminis.app.data.repository.OffloadToolResult>, maxChars: Int): JSONArray {
            val out = JSONArray()
            for (tr in results) {
                val res = JSONObject()
                    .put("tool_use_id", tr.toolUseId)
                    .put("success", tr.success)
                    .put("output", tr.output.take(maxChars))
                if (tr.output.length > maxChars) res.put("output_truncated", true)
                out.put(res)
            }
            return out
        }

        private const val HELP_TEXT = """minis-sessions-cli - Query historical chat sessions and messages

USAGE:
  minis-sessions-cli <command> [options]

COMMANDS:
  list      List recent sessions (default: 50, max: 100)
  search    Search message content across sessions (requires --keywords)
  messages  Read messages from a specific session (requires --id)
  status    Get current status of a session (requires --id; sub agents:
            termination_reason, last_tool_status, last_tool_result,
            delivery_state, ...)
  update    Change a session's title, group and/or icon (requires --id)
  groups    List every group: [{name, session_count}]

OPTIONS:
  --keywords <words>    Space-separated keywords (AND logic, required for search)
  --ids <id1,id2,...>   Filter by comma-separated session IDs (list/search)
  --id <session_id>     Session ID to read messages from (messages/status)
  --full                (messages only) Return full message text up to 50000 chars
  --tools               (messages only) Also return each message's tool calls:
                        tool_calls [{tool_use_id, name, input}] with the full
                        input JSON (e.g. the exact shell command), and
                        tool_results [{tool_use_id, success, output}].
                        Same per-field cap as text (600, or 50000 with --full).
  --offset <n>          Skip first n messages, 0-based (default: 0)
  --start <YYYY-MM-DD>  Filter results after this date (inclusive)
  --end <YYYY-MM-DD>    Filter results before this date (inclusive, end of day)
  --limit <n>           Max results (default: 50, max: 100)
  --group <name>        (list only) Only sessions in this group (exact name);
                        "" = only ungrouped sessions
  --help, -h            Show this help message
  --compact             Minimize JSON output
  -q, --quiet           Output only data field

UPDATE OPTIONS:
  --id <session_id>     Session to change (required)
  --title <text>        New title (must not be empty)
  --title-auto          Regenerate the title automatically, like the app's
                        "Regenerate Title" (may also set the icon). Not with --title.
  --group <name>        Move into the group with exactly this name
                        (case-sensitive); created if missing. "" = ungroup.
  --icon <icon>         One of: code writing research analysis creative chat
                        math translation health finance travel education
                        design productivity support other. "" = default icon.
  At least one of --title / --title-auto / --group / --icon is required;
  fields not given are left unchanged. data.changed lists the fields whose
  value actually changed.
  Errors: invalid_arguments, invalid_icon, session_not_found,
          not_allowed (sub agent sessions), title_auto_failed.

OUTPUT (update):
  id, title, group, icon, changed (the fields whose value changed).

OUTPUT (groups):
  count, groups [{name, session_count}].

OUTPUT (list):
  Each session includes: session_id, title, group ("" = ungrouped), icon
  ("" = default), preview (first user message, 60 chars), source,
  started_at, last_active, message_count.

OUTPUT (search):
  Each message includes: session_id, message_id, role, created_at,
  snippet (up to 600 chars centered on keyword match).

OUTPUT (status):
  session_id, title, model_name, is_running, last_message, last_tool,
  updated_at, group, icon; when known: last_tool_status (success|failed|cancelled|running),
  last_tool_result (one-line summary), parent_session_id. Sub agent sessions
  also carry termination_reason (running|completed|timeout|tool_error|
  cancelled|interrupted|model_error), termination_detail, last_tool_name,
  partial_output_available, closing_attempted, delivery_state
  (inline|pending|delivered|queued|failed), delivery_detail, ended_at.

OUTPUT (messages):
  Each message includes: message_id, role, created_at, text (up to 600 chars
  by default; up to 50000 with --full). Messages longer than the cap carry
  "truncated": true. Response also includes: session_id, offset, limit, full,
  max_chars, total (total message count). With --tools each message also
  has tool_calls / tool_results (input_truncated / output_truncated when cut)
  and the response carries "tools": true.

WORKFLOW:
  1. Use 'list' or 'list --keywords <topic>' to find relevant sessions
  2. Use 'search --keywords <terms>' to find specific messages
  3. Use 'messages --id <session_id>' to read a conversation
  4. Use --offset to paginate through long conversations

EXAMPLES:
  minis-sessions-cli list
  minis-sessions-cli list --limit 10
  minis-sessions-cli list --keywords python flask
  minis-sessions-cli list --start 2025-01-01 --end 2025-03-31
  minis-sessions-cli search --keywords "API error" --limit 20
  minis-sessions-cli search --keywords deploy --ids abc123,def456
  minis-sessions-cli messages --id <session_id>
  minis-sessions-cli messages --id <session_id> --full
  minis-sessions-cli messages --id <session_id> --tools --full
  minis-sessions-cli messages --id <session_id> --offset 20 --limit 10
  minis-sessions-cli status --id <session_id>
  minis-sessions-cli list --group "Project A"
  minis-sessions-cli groups
  minis-sessions-cli update --id <session_id> --title "新标题" --group "项目A" --icon "code"
  minis-sessions-cli update --id <session_id> --group ""
  minis-sessions-cli update --id <session_id> --icon ""
"""
    }
}

// ─── Merged from SpeechProviderBridge.kt ──────────────────────────────────────────────────

/**
 * [T-431-engine] The provider engine's system side: the Voice Input group's
 * models, a fixed-length microphone capture, audio-file decoding and the
 * upload - through the same [VoiceProviderFactory] / [VoiceProvider] the chat
 * voice input ([com.openminis.app.speech.ProviderSpeechRecognitionEngine])
 * uses. API keys stay inside the repository and the provider; nothing here
 * returns or logs them.
 */
internal class SpeechProviderBridge(private val context: Context) {

    private fun repository() =
        (context.applicationContext as? MinisApp)?.takeIf { it.subsystemsReady() }?.providerRepository

    private fun candidates(): List<Pair<ProviderInstance, ModelEntry>> =
        repository()?.resolveVoiceInputCandidates(
            loadBalanceSeed = kotlin.random.Random.nextInt(Int.MAX_VALUE),
        ).orEmpty()

    private fun describe(instance: ProviderInstance, entry: ModelEntry): SpeechEngines.VoiceModel {
        val base = runCatching { VoiceProviderFactory.make(instance, null)?.baseURL }.getOrNull()
            ?: instance.customBaseURL
        val host = base?.let { runCatching { java.net.URI(it).host }.getOrNull() ?: it } ?: "unknown host"
        return SpeechEngines.VoiceModel(instance.label, entry.baseModel.id, host)
    }

    fun models(): List<SpeechEngines.VoiceModel> = candidates().map { (i, e) -> describe(i, e) }

    /** Record [seconds] of 16 kHz mono PCM16 from the microphone, as WAV. */
    @SuppressLint("MissingPermission") // the handler's permission gate runs first
    fun record(seconds: Int, isCancelled: () -> Boolean = { false }): CaptureOutcome {
        val rate = SpeechAudio.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return CaptureOutcome.Failed("audio_error", "AudioRecord reports no usable buffer size.")
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        } catch (e: Exception) {
            return CaptureOutcome.Failed(micCode(), "The microphone could not be opened: ${e.message}")
        }
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                return CaptureOutcome.Failed(micCode(), "The microphone could not be opened (AudioRecord not initialized).")
            }
            val pcm = ByteArrayOutputStream()
            val buf = ByteArray(minBuf)
            val wantBytes = rate * 2 * seconds.coerceIn(1, SpeechAudio.MAX_MIC_SECONDS)
            val deadline = System.currentTimeMillis() + seconds * 1000L + 2_000L
            recorder.startRecording()
            var nextSilenceCheck = System.currentTimeMillis() + 400L
            var nextCancelCheck = System.currentTimeMillis() + 250L
            while (pcm.size() < wantBytes && System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                if (now >= nextCancelCheck) {
                    nextCancelCheck = now + 250L
                    // [T-431-cancel] The caller is gone: stop recording; the
                    // handler then skips the upload.
                    if (isCancelled()) return CaptureOutcome.Cancelled
                }
                if (now >= nextSilenceCheck) {
                    nextSilenceCheck = now + 1_000L
                    // [T-voice-mic-preempted] A call or another recorder owns the mic.
                    if (MicInUse.isSilenced(context, recorder.audioSessionId)) {
                        return CaptureOutcome.Failed(
                            "mic_in_use",
                            "Another app or a call is using the microphone; the recording was silenced.",
                        )
                    }
                }
                val n = recorder.read(buf, 0, minOf(buf.size, wantBytes - pcm.size()))
                if (n > 0) pcm.write(buf, 0, n)
            }
            val bytes = pcm.toByteArray()
            return CaptureOutcome.Ok(VoiceProvider.wrapPcm16InWav(bytes, rate), bytes.size / (rate * 2.0))
        } catch (e: Exception) {
            return CaptureOutcome.Failed(micCode(), "Recording failed: ${e.message}")
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    private fun micCode(): String =
        if (MicInUse.captureFailure(context) == com.openminis.app.speech.RecognitionError.MIC_IN_USE) "mic_in_use" else "audio_error"

    /** A WAV within the upload limit as-is; anything else decoded to 16 kHz mono WAV. */
    fun decode(file: File, isCancelled: () -> Boolean = { false }): DecodeOutcome {
        val size = file.length()
        if (size > SpeechAudio.MAX_FILE_BYTES) {
            return DecodeOutcome.Failed(
                "file_too_large",
                "The file is ${size / (1024 * 1024)} MB; the limit is ${SpeechAudio.MAX_FILE_BYTES / (1024 * 1024)} MB.",
            )
        }
        val head = ByteArray(44)
        val read = runCatching { file.inputStream().use { it.read(head) } }.getOrElse {
            return DecodeOutcome.Failed("file_unreadable", "The file could not be read: ${it.message}")
        }
        val format = SpeechAudio.sniff(head.copyOf(maxOf(read, 0)))
        if (SpeechAudio.passThrough(format, size)) {
            val h = head.copyOf(maxOf(read, 0))
            return DecodeOutcome.Ok(file.readBytes(), SpeechAudio.wavSeconds(h, size), format, decoded = false)
        }
        return decodeWithMediaCodec(file, format, isCancelled)
    }

    private fun decodeWithMediaCodec(file: File, format: SpeechAudio.Format, isCancelled: () -> Boolean): DecodeOutcome {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return DecodeOutcome.Failed(
                "unsupported_format",
                "No audio track Android can read was found (detected: ${format.label}).",
            )
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            var rate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inFormat, null, null, 0)
            codec.start()

            // [T-speech-decode-streaming] Each output buffer goes straight to
            // 16 kHz mono; only that result (at most 10 min = 9.6M samples,
            // 19.2 MB) is kept. A reused scratch array holds one buffer.
            var resampler: SpeechAudio.StreamingResampler? = null
            var scratch = ShortArray(0)
            var totalFrames = 0L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val maxOut = SpeechAudio.SAMPLE_RATE * SpeechAudio.MAX_FILE_SECONDS
            while (!outputDone) {
                if (isCancelled()) return DecodeOutcome.Failed("cancelled", "The caller stopped before the audio was decoded.")
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    outIdx >= 0 -> {
                        val out = codec.getOutputBuffer(outIdx)!!
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val count = info.size / 2
                        if (scratch.size < count) scratch = ShortArray(count)
                        out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(scratch, 0, count)
                        val r = resampler ?: SpeechAudio.StreamingResampler(channels, rate, SpeechAudio.SAMPLE_RATE, maxOut)
                            .also { resampler = it }
                        r.push(scratch, count)
                        totalFrames += count / maxOf(channels, 1)
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (totalFrames > rate.toLong() * SpeechAudio.MAX_FILE_SECONDS) {
                            return DecodeOutcome.Failed(
                                "audio_too_long",
                                "The audio is longer than ${SpeechAudio.MAX_FILE_SECONDS / 60} minutes; trim it or split it.",
                            )
                        }
                    }
                }
            }
            val mono = resampler?.finish() ?: ShortArray(0)
            val wav = VoiceProvider.wrapPcm16InWav(SpeechAudio.toLittleEndianBytes(mono), SpeechAudio.SAMPLE_RATE)
            return DecodeOutcome.Ok(wav, mono.size / SpeechAudio.SAMPLE_RATE.toDouble(), format, decoded = true)
        } catch (e: OutOfMemoryError) {
            // [T-speech-decode-streaming] Never take the app down for one file.
            AppLogger.warning(TAG, "decode ran out of memory")
            return DecodeOutcome.Failed("unsupported_format", "Android ran out of memory decoding this file; trim or split it.")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "decode failed: ${e.message}")
            return DecodeOutcome.Failed(
                "unsupported_format",
                "Android could not decode this file (detected: ${format.label}): ${e.message}",
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** Try the Voice Input candidates in order; the first transcript wins. */
    fun transcribe(wav: ByteArray, language: String?): ProviderOutcome {
        val repo = repository() ?: return ProviderOutcome.NoModel
        val list = candidates()
        if (list.isEmpty()) return ProviderOutcome.NoModel
        val errors = mutableListOf<Pair<SpeechEngines.VoiceModel, String>>()
        for ((instance, entry) in list) {
            val model = describe(instance, entry)
            val key = repo.loadApiKey(instance.id)
            val provider = VoiceProviderFactory.make(instance, key)
            if (provider == null) {
                errors += model to "This provider type cannot serve voice input."
                continue
            }
            try {
                val response = runBlocking {
                    provider.transcribe(
                        VoiceInputRequest(
                            audioData = wav,
                            model = entry.baseModel.id,
                            language = language?.let { Locale.forLanguageTag(it).toLanguageTag() },
                            resolvedModel = entry.model,
                        ),
                    )
                }
                return ProviderOutcome.Success(response.text.trim(), model)
            } catch (e: Exception) {
                // Messages carry the HTTP status / body excerpt, not the key.
                AppLogger.warning(TAG, "provider ${instance.label} failed: ${e.javaClass.simpleName}")
                errors += model to SpeechAudioRedact.redact(e.message ?: e.javaClass.simpleName, key)
            }
        }
        return ProviderOutcome.Failed(errors)
    }

    companion object {
        private const val TAG = "SpeechOffload"
    }
}

/**
 * Some services echo the (partial) key in a 401 body ("Incorrect API key
 * provided: sk-...abcd"). The CLI prints provider errors, so the key and any
 * sk-style token are masked first.
 */
internal object SpeechAudioRedact {
    private val TOKEN = Regex("""(sk|pk|ak|key)[-_][A-Za-z0-9_\-*.]{6,}""", RegexOption.IGNORE_CASE)

    fun redact(message: String, key: String?): String {
        var out = message
        if (!key.isNullOrBlank()) out = out.replace(key, "<redacted>")
        return TOKEN.replace(out, "<redacted>")
    }
}

internal sealed class CaptureOutcome {
    class Ok(val wav: ByteArray, val seconds: Double) : CaptureOutcome()
    class Failed(val code: String, val message: String) : CaptureOutcome()
    object Cancelled : CaptureOutcome()
}

internal sealed class DecodeOutcome {
    class Ok(val wav: ByteArray, val seconds: Double?, val format: SpeechAudio.Format, val decoded: Boolean) : DecodeOutcome()
    class Failed(val code: String, val message: String) : DecodeOutcome()
}

internal sealed class ProviderOutcome {
    class Success(val text: String, val model: SpeechEngines.VoiceModel) : ProviderOutcome()
    class Failed(val errors: List<Pair<SpeechEngines.VoiceModel, String>>) : ProviderOutcome()
    object NoModel : ProviderOutcome()
}

// ─── Merged from SpeechSystem.kt ──────────────────────────────────────────────────

/**
 * [T-431] Everything `android-speech` asks of the system, behind one seam so
 * the handler's decisions (envelope, exit codes, wording) are unit-tested
 * against a fake.
 */
internal interface SpeechSystem {
    fun environment(): SpeechCli.Environment
    fun voiceDetails(timeoutMs: Long): SpeechCli.VoiceDetails
    fun recognitionSupport(timeoutMs: Long): SpeechCli.Support?
    fun hasMicPermission(): Boolean
    fun requestMicPermission(): OffloadPermissionManager.AndroidPermissionResult
    fun recognize(request: RecognizeRequest): RecognizeOutcome
    /** Open and cancel one session, to see whether Android connects at all. */
    fun probe(timeoutMs: Long): JSONObject
    fun resolveSourcePath(source: String): File?
    /** Non-null while the chat voice input is capturing (it owns the microphone). */
    fun micBusyReason(): String?

    /**
     * [T-431-cancel] Whether the guest process that invoked the CLI is still
     * running. The offload protocol has no cancel message: when the caller is
     * killed (Ctrl-C, the agent's stop) the host handler keeps going unless it
     * looks. Offload callers are host processes of this app's uid, so
     * `/proc/<pid>` answers it; pid <= 0 (debug RPC) counts as alive.
     */
    fun callerAlive(pid: Int): Boolean

    // [T-431-engine] Provider engine: the Voice Input group's models.
    fun voiceInputModels(): List<SpeechEngines.VoiceModel>
    fun recordMic(seconds: Int, isCancelled: () -> Boolean = { false }): CaptureOutcome
    fun decodeAudioFile(file: File, isCancelled: () -> Boolean = { false }): DecodeOutcome
    fun providerTranscribe(wav: ByteArray, language: String?): ProviderOutcome
    /** Remember (or, with null, forget) the last recognizer failure for `status`. */
    fun recordLastError(code: Int?, recognizer: String?)
}

internal data class RecognizeRequest(
    val language: String?,
    val maxResults: Int,
    val durationSec: Long,
    /** Which system recognizer this one session uses; fallback is the caller's. */
    val onDevice: Boolean = false,
    /** [T-431-cancel] Polled while waiting; true ends the session at once. */
    val isCancelled: () -> Boolean = { false },
)

internal sealed class RecognizeOutcome {
    abstract val recognizer: String

    data class Success(
        val texts: List<String>,
        val confidences: List<Float>?,
        override val recognizer: String,
    ) : RecognizeOutcome()

    data class Failure(val code: Int, override val recognizer: String) : RecognizeOutcome()

    data class Timeout(val seconds: Long, override val recognizer: String) : RecognizeOutcome()

    data class CreationFailed(val message: String, override val recognizer: String) : RecognizeOutcome()

    /** [T-431-cancel] The caller went away; the session was cancelled. */
    data class Cancelled(override val recognizer: String) : RecognizeOutcome()
}

/**
 * [T-431-cancel] Reads a caller's `/proc/<pid>/status`. The caller is a proot
 * tracee: during the offload call it sits in a ptrace stop, so a SIGTERM or
 * SIGINT does not end it until proot gets back to it - which is after the host
 * replies. The process therefore stays in /proc; what changes at once is the
 * pending-signal mask (checked on a Pixel 4a: ShdPnd 0x4000 after SIGTERM).
 * Minis' own stop sends SIGTERM then SIGKILL to the group, a terminal Ctrl-C
 * SIGINT; a SIGKILLed process becomes a zombie.
 */
internal object CallerStatus {
    /** SIGHUP(1), SIGINT(2), SIGKILL(9), SIGTERM(15) as bits of a signal mask. */
    private const val STOP_SIGNALS: Long = (1L shl 0) or (1L shl 1) or (1L shl 8) or (1L shl 14)

    fun alive(status: String?): Boolean {
        if (status == null) return false
        val state = Regex("^State:\\s*(\\S)", RegexOption.MULTILINE).find(status)?.groupValues?.get(1)
        if (state == "Z" || state == "X") return false
        return (mask(status, "SigPnd") or mask(status, "ShdPnd")) and STOP_SIGNALS == 0L
    }

    private fun mask(status: String, field: String): Long =
        Regex("^$field:\\s*([0-9a-fA-F]+)", RegexOption.MULTILINE).find(status)?.groupValues?.get(1)
            ?.toULongOrNull(16)?.toLong() ?: 0L
}

internal object Recognizers {
    const val REGULAR = "regular"
    const val ON_DEVICE = "on_device"
}

internal class AndroidSpeechSystem(private val context: Context) : SpeechSystem {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val provider by lazy { SpeechProviderBridge(context) }

    override fun voiceInputModels() = runCatching { provider.models() }.getOrElse {
        AppLogger.warning(TAG, "voice input models: ${it.message}")
        emptyList()
    }

    override fun recordMic(seconds: Int, isCancelled: () -> Boolean) = provider.record(seconds, isCancelled)

    override fun callerAlive(pid: Int): Boolean =
        pid <= 0 || CallerStatus.alive(runCatching { File("/proc/$pid/status").readText() }.getOrNull())
    override fun decodeAudioFile(file: File, isCancelled: () -> Boolean) = provider.decode(file, isCancelled)
    override fun providerTranscribe(wav: ByteArray, language: String?) = provider.transcribe(wav, language)
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun environment(): SpeechCli.Environment {
        val services = runCatching {
            context.packageManager
                .queryIntentServices(Intent(RECOGNITION_SERVICE_ACTION), 0)
                .mapNotNull { info -> info.serviceInfo?.let { "${it.packageName}/${it.name}" } }
        }.getOrElse {
            AppLogger.warning(TAG, "queryIntentServices failed: ${it.message}")
            emptyList()
        }
        // Readable by ordinary apps: SpeechRecognizer reads it in-process.
        val def = runCatching {
            Settings.Secure.getString(context.contentResolver, VOICE_RECOGNITION_SERVICE)
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val onDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        } else {
            null
        }
        return SpeechCli.Environment(
            services = services,
            defaultService = def,
            onDeviceAvailable = onDevice,
            locale = RecognizerLocales.recognizerTag(Locale.getDefault()),
            hasMicPermission = hasMicPermission(),
            sdkInt = Build.VERSION.SDK_INT,
            lastError = lastError(),
        )
    }

    /**
     * The documented way to ask a recognizer for its languages: resolve the
     * voice-search activity, read its `android.speech.DETAILS` receiver and
     * send it an EXPLICIT ordered broadcast. The pre-#431 implicit broadcast
     * reaches no manifest receiver on Android 8+.
     */
    override fun voiceDetails(timeoutMs: Long): SpeechCli.VoiceDetails {
        val intent = runCatching { RecognizerIntent.getVoiceDetailsIntent(context) }.getOrNull()
            ?: return SpeechCli.VoiceDetails(
                null,
                "no voice-search app exposes a language-details receiver (getVoiceDetailsIntent returned null)",
            )
        val latch = CountDownLatch(1)
        val tags = AtomicReference<List<String>?>(null)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                @Suppress("DEPRECATION")
                tags.set(getResultExtras(true)?.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES))
                latch.countDown()
            }
        }
        try {
            context.sendOrderedBroadcast(intent, null, receiver, mainHandler, 0, null, null)
        } catch (e: Throwable) {
            return SpeechCli.VoiceDetails(null, "broadcast to ${intent.component?.flattenToShortString()} failed: ${e.message}")
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            return SpeechCli.VoiceDetails(null, "${intent.component?.flattenToShortString()} did not answer within ${timeoutMs}ms")
        }
        val got = tags.get()
        val target = intent.component?.flattenToShortString()
        return when {
            got == null -> SpeechCli.VoiceDetails(null, "$target answered without a language list")
            got.isEmpty() -> SpeechCli.VoiceDetails(emptyList(), "$target answered with an empty language list")
            else -> SpeechCli.VoiceDetails(got, "$target")
        }
    }

    /** API 33+: ask the regular and the on-device recognizer directly. */
    override fun recognitionSupport(timeoutMs: Long): SpeechCli.Support? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val online = linkedSetOf<String>()
        val installed = linkedSetOf<String>()
        val supported = linkedSetOf<String>()
        val notes = mutableListOf<String>()
        var regularAnswered = false
        var onDeviceAnswered = false
        for (onDevice in listOf(false, true)) {
            val label = if (onDevice) Recognizers.ON_DEVICE else Recognizers.REGULAR
            if (onDevice && runCatching { !SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(true)) {
                notes += "$label: not available"
                continue
            }
            val r = querySupport(onDevice, timeoutMs)
            when (r) {
                is SupportAnswer.Ok -> {
                    online += r.online
                    installed += r.installed
                    supported += r.supported
                    if (onDevice) onDeviceAnswered = true else regularAnswered = true
                    notes += "$label: ok"
                }
                is SupportAnswer.Error -> notes += "$label: ${RecognizerErrorCodes.name(r.code)}"
                SupportAnswer.Timeout -> notes += "$label: no answer within ${timeoutMs}ms"
                is SupportAnswer.Threw -> notes += "$label: ${r.message}"
            }
        }
        return SpeechCli.Support(
            online.toList(), installed.toList(), supported.toList(), notes.joinToString("; "),
            regularAnswered = regularAnswered,
            onDeviceAnswered = onDeviceAnswered,
        )
    }

    private sealed class SupportAnswer {
        class Ok(val online: List<String>, val installed: List<String>, val supported: List<String>) : SupportAnswer()
        class Error(val code: Int) : SupportAnswer()
        object Timeout : SupportAnswer()
        class Threw(val message: String) : SupportAnswer()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun querySupport(onDevice: Boolean, timeoutMs: Long): SupportAnswer {
        val latch = CountDownLatch(1)
        val answer = AtomicReference<SupportAnswer>(SupportAnswer.Timeout)
        val recognizerRef = AtomicReference<SpeechRecognizer?>(null)
        val executor = Executor { mainHandler.post(it) }
        mainHandler.post {
            try {
                val r = if (onDevice) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
                recognizerRef.set(r)
                r.checkRecognitionSupport(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
                    executor,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                            answer.set(
                                SupportAnswer.Ok(
                                    recognitionSupport.onlineLanguages,
                                    recognitionSupport.installedOnDeviceLanguages,
                                    recognitionSupport.supportedOnDeviceLanguages,
                                ),
                            )
                            latch.countDown()
                        }

                        override fun onError(error: Int) {
                            answer.set(SupportAnswer.Error(error))
                            latch.countDown()
                        }
                    },
                )
            } catch (e: Throwable) {
                answer.set(SupportAnswer.Threw(e.message ?: e.javaClass.simpleName))
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        mainHandler.post { runCatching { recognizerRef.getAndSet(null)?.destroy() } }
        return answer.get()
    }

    override fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** The 3-stage flow the chat composer's mic button uses (T67). */
    override fun requestMicPermission(): OffloadPermissionManager.AndroidPermissionResult = runBlocking {
        var r = OffloadPermissionManager.requestAndroidPermission(listOf(Manifest.permission.RECORD_AUDIO))
        if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED &&
            OffloadPermissionManager.pollForPermissionGrant({ hasMicPermission() })
        ) {
            AppLogger.info(TAG, "Mic permission granted during post-DENY poll")
            r = OffloadPermissionManager.AndroidPermissionResult.GRANTED
        }
        if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED) {
            r = OffloadPermissionManager.requestSettingsGate(
                OffloadPermissionManager.SettingsGateRequest(
                    id = Manifest.permission.RECORD_AUDIO,
                    title = "Microphone permission needed",
                    message = "Minis needs microphone permission to transcribe speech. Open Settings to allow it.",
                    settingsAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    requiresPackageUri = true,
                    positiveLabel = "Open Settings",
                ),
                check = { hasMicPermission() },
            )
        }
        r
    }

    /**
     * One recognition session on the recognizer the request names.
     * [T-431-fallback] Pre-#431 the CLI always took the on-device recognizer on
     * S+ and never fell back; the handler now plans the order.
     */
    override fun recognize(request: RecognizeRequest): RecognizeOutcome =
        session(request, request.onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

    override fun micBusyReason(): String? {
        val state = com.openminis.app.speech.SpeechRecognitionManager.state.value
        return when (state) {
            com.openminis.app.speech.RecognitionState.STARTING,
            com.openminis.app.speech.RecognitionState.RECORDING,
            -> "The chat voice input is using the microphone (state: $state)."
            else -> null
        }
    }

    /**
     * [T-android-speech-error10] Every exit path destroys the recognizer
     * exactly once, on the main looper that created it - an orphaned session
     * exhausts the system recognizer service (ERROR_TOO_MANY_REQUESTS, 10).
     */
    private fun session(request: RecognizeRequest, onDevice: Boolean): RecognizeOutcome {
        val label = if (onDevice) Recognizers.ON_DEVICE else Recognizers.REGULAR
        val latch = CountDownLatch(1)
        val outcome = AtomicReference<RecognizeOutcome?>(null)
        val recognizerRef = AtomicReference<SpeechRecognizer?>(null)
        val destroyed = AtomicBoolean(false)
        fun destroyOnce() {
            if (!destroyed.compareAndSet(false, true)) return
            val r = recognizerRef.getAndSet(null) ?: return
            try {
                r.destroy()
            } catch (e: Throwable) {
                AppLogger.warning(TAG, "recognizer destroy threw: ${e.message}")
            }
        }
        fun finish(o: RecognizeOutcome) {
            if (outcome.compareAndSet(null, o)) latch.countDown()
            destroyOnce()
        }

        mainHandler.post {
            val recognizer = try {
                if (onDevice) {
                    try {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    } catch (_: Throwable) {
                        SpeechRecognizer.createSpeechRecognizer(context)
                    }
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            } catch (e: Throwable) {
                finish(RecognizeOutcome.CreationFailed("Could not create SpeechRecognizer: ${e.message}", label))
                return@post
            }
            recognizerRef.set(recognizer)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, request.maxResults)
                request.language?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val confs = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.toList()
                    finish(RecognizeOutcome.Success(matches, confs, label))
                }

                override fun onError(error: Int) = finish(RecognizeOutcome.Failure(error, label))
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            try {
                recognizer.startListening(intent)
            } catch (e: Throwable) {
                finish(RecognizeOutcome.CreationFailed("startListening threw: ${e.message}", label))
            }
        }

        // [T-431-cancel] A caller that went away cancels the session now,
        // freeing the microphone, instead of at the end of --duration.
        fun cancelled(): RecognizeOutcome {
            mainHandler.post {
                runCatching { recognizerRef.get()?.cancel() }
                finish(RecognizeOutcome.Cancelled(label))
            }
            return RecognizeOutcome.Cancelled(label)
        }

        // [T-431] --duration is the recording length, as on iOS: stop
        // listening when it is up and give the recognizer a grace period to
        // deliver the final result. Pre-#431 the duration was only a timeout,
        // so a quiet room always ended as "timed out".
        when (awaitOrCancel(latch, request.durationSec * 1000, request.isCancelled)) {
            Wait.DONE -> Unit
            Wait.CANCELLED -> return cancelled()
            Wait.TIME_UP -> {
                mainHandler.post {
                    if (!destroyed.get()) runCatching { recognizerRef.get()?.stopListening() }
                }
                when (awaitOrCancel(latch, RESULT_GRACE_SEC * 1000, request.isCancelled)) {
                    Wait.DONE -> Unit
                    Wait.CANCELLED -> return cancelled()
                    Wait.TIME_UP -> {
                        // [T-android-speech-error10] Never leave the session behind.
                        mainHandler.post {
                            runCatching { recognizerRef.get()?.cancel() }
                            finish(RecognizeOutcome.Timeout(request.durationSec + RESULT_GRACE_SEC, label))
                        }
                        return RecognizeOutcome.Timeout(request.durationSec + RESULT_GRACE_SEC, label)
                    }
                }
            }
        }
        return outcome.get() ?: RecognizeOutcome.Timeout(request.durationSec, label)
    }

    private enum class Wait { DONE, TIME_UP, CANCELLED }

    /** Wait up to [millis] for [latch], checking [isCancelled] every 250 ms. */
    private fun awaitOrCancel(latch: CountDownLatch, millis: Long, isCancelled: () -> Boolean): Wait {
        val end = System.currentTimeMillis() + millis
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) return Wait.TIME_UP
            if (latch.await(minOf(left, 250L), TimeUnit.MILLISECONDS)) return Wait.DONE
            if (isCancelled()) return Wait.CANCELLED
        }
    }

    override fun probe(timeoutMs: Long): JSONObject {
        val started = System.currentTimeMillis()
        val ready = CountDownLatch(1)
        val result = AtomicReference(JSONObject().put("ok", false).put("result", "no_callback"))
        val recognizerRef = AtomicReference<SpeechRecognizer?>(null)
        mainHandler.post {
            try {
                val r = SpeechRecognizer.createSpeechRecognizer(context)
                recognizerRef.set(r)
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        result.set(JSONObject().put("ok", true).put("result", "ready_for_speech"))
                        ready.countDown()
                    }

                    override fun onError(error: Int) {
                        result.set(
                            JSONObject().put("ok", false).put("result", "error")
                                .put("error_code", error).put("error_name", RecognizerErrorCodes.name(error)),
                        )
                        ready.countDown()
                    }

                    override fun onResults(results: Bundle?) = ready.countDown()
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH))
            } catch (e: Throwable) {
                result.set(JSONObject().put("ok", false).put("result", "threw").put("message", e.message ?: ""))
                ready.countDown()
            }
        }
        if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            result.set(JSONObject().put("ok", false).put("result", "no_callback_within_${timeoutMs}ms"))
        }
        mainHandler.post {
            recognizerRef.getAndSet(null)?.let {
                runCatching { it.cancel() }
                runCatching { it.destroy() }
            }
        }
        return result.get().put("recognizer", Recognizers.REGULAR).put("ms", System.currentTimeMillis() - started)
    }

    /**
     * Resolve a `/var/minis/...`-style Linux path to a host File via
     * [PRootKernel]'s global bind-mount table.
     */
    override fun resolveSourcePath(source: String): File? {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return null
        return try {
            if (trimmed.startsWith("/")) PRootKernel.resolveHostPath(trimmed) ?: File(trimmed) else File(trimmed)
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "resolveSourcePath('$trimmed') failed: ${e.message}")
            null
        }
    }

    override fun recordLastError(code: Int?, recognizer: String?) {
        val edit = prefs.edit()
        if (code == null) {
            edit.remove(KEY_LAST_CODE).remove(KEY_LAST_AT).remove(KEY_LAST_RECOGNIZER)
        } else {
            edit.putInt(KEY_LAST_CODE, code).putLong(KEY_LAST_AT, System.currentTimeMillis())
                .putString(KEY_LAST_RECOGNIZER, recognizer)
        }
        edit.apply()
    }

    private fun lastError(): SpeechCli.LastError? {
        if (!prefs.contains(KEY_LAST_CODE)) return null
        return SpeechCli.LastError(
            prefs.getInt(KEY_LAST_CODE, 0),
            prefs.getLong(KEY_LAST_AT, 0L),
            prefs.getString(KEY_LAST_RECOGNIZER, null),
        )
    }

    companion object {
        private const val TAG = "SpeechOffload"
        private const val PREFS = "android_speech_cli"
        private const val KEY_LAST_CODE = "last_error_code"
        private const val KEY_LAST_AT = "last_error_at"
        private const val KEY_LAST_RECOGNIZER = "last_error_recognizer"
        private const val RECOGNITION_SERVICE_ACTION = "android.speech.RecognitionService"

        /** After stopListening, how long the recognizer may take to answer. */
        private const val RESULT_GRACE_SEC = 8L

        /** `Settings.Secure.VOICE_RECOGNITION_SERVICE` is @hide; the key is not. */
        private const val VOICE_RECOGNITION_SERVICE = "voice_recognition_service"
    }
}
