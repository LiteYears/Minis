package com.openminis.app.debug

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.openminis.app.BuildConfig
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.PRootKernel
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Static catalogue of every JSON-RPC method exposed by the Android debug server.
 *
 * Returned by `rpc.discover` so clients can introspect the API at runtime
 * instead of relying on external documentation. Keep this in sync with the
 * dispatch `when` in [DebugRPCHandler.dispatch].
 */
object DebugMethodRegistry {

    data class ParamSpec(
        val name: String,
        val type: String,
        val required: Boolean,
        val default: Any? = null,
        val description: String,
    )

    data class MethodSpec(
        val name: String,
        val description: String,
        val params: List<ParamSpec>,
        val returns: String,
        val example: JSONObject,
    )

    private fun ex(vararg pairs: Pair<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in pairs) o.put(k, v)
        return o
    }

    val methods: List<MethodSpec> by lazy {
        buildList {
            addAll(BASE_METHODS)
            addAll(NetDebugMethods.specs)
            if (BuildConfig.DEV_TOOLS) addAll(DEBUG_ONLY_METHODS)
        }
    }

    private val DEBUG_ONLY_METHODS: List<MethodSpec> = listOf(
        MethodSpec(
            name = "debug.chatShare.openPicker",
            description = "[T-multi-select-share] Open the Share Chat picker on the session shown in the app, exactly as long-pressing a message and choosing \"Share Chat…\" would (its round preselected). Anchors on the last user message unless messageId is given. The session must be on screen. DEBUG only.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "The session currently shown in the app."),
                ParamSpec("messageId", "string", required = false, description = "Message whose round is preselected; default the last user message."),
            ),
            returns = "{ok, messageId}",
            example = ex("sessionId" to "…"),
        ),
        MethodSpec(
            name = "debug.chatExport.render",
            description = "[T-multi-select-share] Render the Share Chat export headlessly for the session shown in the app (long image or PDF) and report the file. The session must be on screen. DEBUG only.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "The session currently shown in the app."),
                ParamSpec("format", "string", required = false, default = "image", description = "image | pdf"),
                ParamSpec("unitIndexes", "[int]", required = false, description = "Units (questions, replies, callback cards) to include by index; default all."),
                ParamSpec("lastUnits", "int", required = false, description = "Include only the last N units."),
                ParamSpec("includeToolCalls", "bool", required = false, default = false, description = "Include tool-call cells."),
                ParamSpec("includeThinking", "bool", required = false, default = false, description = "Include thinking cells."),
                ParamSpec("wideLayout", "bool", required = false, default = false, description = "Tablet (1024dp) width."),
                ParamSpec("adaptiveTables", "bool", required = false, default = false, description = "Tables at their natural width."),
                ParamSpec("dark", "bool", required = false, default = false, description = "Dark appearance."),
                ParamSpec("title", "string", required = false, default = "Debug Export", description = "Header / footer title."),
                ParamSpec("inline", "bool", required = false, default = false, description = "Also return the file as base64 (up to 8 MB)."),
            ),
            returns = "{path, bytes, width, height, scale, pages, units, totalUnits, cells, ms, base64?}",
            example = ex("sessionId" to "…", "format" to "pdf", "lastUnits" to 2),
        ),
        MethodSpec(
            name = "debug.shizuku.exec",
            description = "DEBUG-only: invoke ShizukuOffloadHandler directly with the given argv (T344). Bypasses the agent loop for e2e harness verification of android-shizuku-cli.",
            params = listOf(
                ParamSpec("args", "[string]", required = false, description = "argv past `android-shizuku-cli` (e.g. [\"exec\", \"id\"]). Either this or 'command' is required."),
                ParamSpec("command", "string", required = false, description = "Whitespace-separated alternative to args, e.g. \"exec id\"."),
            ),
            returns = "{exitCode, output, argv}",
            example = ex("args" to JSONArray().apply { put("exec"); put("id") }),
        ),
        MethodSpec(
            name = "debug.modelUse.exec",
            description = "DEBUG-only: invoke ModelUseOffloadHandler directly with the given argv. Parallels debug.shizuku.exec — lets harnesses trigger `minis-model-use run/list/search` without an in-shell prompt.",
            params = listOf(
                ParamSpec("args", "[string]", required = false, description = "argv past `minis-model-use` (e.g. [\"run\", \"--model\", \"gpt-5.3-codex\"])."),
                ParamSpec("command", "string", required = false, description = "Whitespace-separated alternative to args."),
                ParamSpec("input", "string", required = false, description = "Raw JSON/text fed to the handler as the --input file contents (written to a temp file under /tmp)."),
            ),
            returns = "{exitCode, output, argv}",
            example = ex(
                "args" to JSONArray().apply { put("run"); put("--model"); put("gpt-5.3-codex") },
                "input" to "{\"messages\":[{\"role\":\"user\",\"content\":\"Reply with the single word OK.\"}]}",
            ),
        ),
        MethodSpec(
            name = "debug.sessions.exec",
            description = "DEBUG-only: invoke SessionsOffloadHandler directly with the given argv. Parallels debug.modelUse.exec — lets harnesses trigger `minis-sessions-cli list/search/messages` (incl. --full) without an in-shell prompt.",
            params = listOf(
                ParamSpec("args", "[string]", required = false, description = "argv past `minis-sessions-cli` (e.g. [\"messages\", \"--id\", \"<session_id>\", \"--full\"])."),
                ParamSpec("command", "string", required = false, description = "Whitespace-separated alternative to args."),
            ),
            returns = "{exitCode, output, argv}",
            example = ex(
                "args" to JSONArray().apply { put("messages"); put("--id"); put("<session_id>"); put("--full") },
            ),
        ),
        MethodSpec(
            name = "debug.calendar.exec",
            description = "DEBUG-only: invoke CalendarOffloadHandler directly with the given argv (android-calendar list/create/update/delete). Writes hit the device's real calendars.",
            params = listOf(
                ParamSpec("args", "[string]", required = false, description = "argv past `android-calendar` (e.g. [\"update\", \"--id\", \"42\", \"--all-day\"])."),
                ParamSpec("command", "string", required = false, description = "Whitespace-separated alternative to args."),
            ),
            returns = "{exitCode, output, argv}",
            example = ex(
                "args" to JSONArray().apply { put("update"); put("--id"); put("42"); put("--all-day") },
            ),
        ),
        MethodSpec(
            name = "debug.minisConfig.exec",
            description = "DEBUG-only: drive minis-config through the REAL ConfigBridge (same code path as the in-shell CLI), so a harness can exercise every collection, the confirmation gate and the audit log without an in-shell prompt. Subcommands: set, get, topics, topic-help, audit-list.",
            params = listOf(
                ParamSpec("subcommand", "string", required = true, description = "One of: set, get, topics, topic-help, audit-list."),
                ParamSpec("path", "string", required = false, description = "Config path, for get and single-path set (e.g. \"thinkingrules.<inst>:<rule>.label\")."),
                ParamSpec("value_json", "string", required = false, description = "JSON-encoded new value, for single-path set."),
                ParamSpec("items", "[{path,value_json}]", required = false, description = "Multi-path set applied as ONE write batch; alternative to path+value_json."),
                ParamSpec("skipConfirmation", "bool", required = false, description = "set only. Default true (unattended). Pass false to drive the real on-device confirmation sheet."),
                ParamSpec("filter", "string", required = false, description = "get only. Space-separated terms; filters JSON array values."),
                ParamSpec("page", "int", required = false, description = "get only. 1-BASED page number (page=1 and page=0 both mean the first page). Omit together with pageSize for no pagination."),
                ParamSpec("pageSize", "int", required = false, description = "get only. 0 = return everything."),
                ParamSpec("topic", "string", required = false, description = "topic-help only. Topic name from subcommand=topics."),
                ParamSpec("limit", "int", required = false, description = "audit-list only. 1..1000, default 100."),
                ParamSpec("scope", "string", required = false, description = "audit-list only. Restrict the audit trail to one scope."),
            ),
            returns = "get/set -> the bridge's {ok,…} envelope; topics -> {ok, topics:[string]}; topic-help -> {ok, topic, empty, fields:[{path,display_name,description,schema,access,risk,revertable}]} (empty=true means a registered collection with no children yet, e.g. thinkingrules before any rule is authored); audit-list -> {ok, count, capacity, total_used, entries}",
            example = ex(
                "subcommand" to "get",
                "path" to "thinkingrules",
            ),
        ),
    )

    private val BASE_METHODS: List<MethodSpec> = listOf(
        MethodSpec(
            name = "rpc.discover",
            description = "Return the full list of supported methods with parameter schemas and examples.",
            params = emptyList(),
            returns = "{platform, version, build, methodCount, methods:[{name, description, params, returns, example}]}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.appInfo",
            description = "Return app metadata, device info, and disk usage.",
            params = emptyList(),
            returns = "{platform, sdkVersion, device, androidVersion, prootBooted, filesDir, logFiles, totalLogSize, diskUsage:{filesDir, sessions, global}}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.screenshot",
            description = "Capture a live screenshot of the foreground Activity as PNG.",
            params = listOf(
                ParamSpec("scale", "number", required = false, default = 1.0, description = "Render scale (0 < scale <= 1)."),
            ),
            returns = "{base64, size, encoding: 'png'}",
            example = ex("scale" to 0.5),
        ),
        MethodSpec(
            name = "debug.ls",
            description = "List directory contents inside the proot-mounted Linux filesystem.",
            params = listOf(
                ParamSpec("path", "string", required = false, default = "/", description = "Linux path under the proot rootfs."),
                ParamSpec("recursive", "bool", required = false, default = false, description = "Recurse into subdirectories."),
                ParamSpec("maxDepth", "int", required = false, default = 3, description = "Max recursion depth when recursive=true."),
            ),
            returns = "Array of {name, type, size, modified}; when recursive=true, directories include a 'children' array.",
            example = ex("path" to "/root", "recursive" to true, "maxDepth" to 2),
        ),
        MethodSpec(
            name = "debug.readFile",
            description = "Read a file from the proot rootfs with optional offset/limit slicing.",
            params = listOf(
                ParamSpec("path", "string", required = true, description = "Linux path under the proot rootfs."),
                ParamSpec("offset", "long", required = false, default = 0, description = "Byte offset to start reading from."),
                ParamSpec("limit", "int", required = false, default = 524288, description = "Maximum bytes to read (default 512KB)."),
                ParamSpec("base64", "bool", required = false, default = false, description = "Force base64 encoding (otherwise detected from content)."),
            ),
            returns = "{size, content, encoding, bytesRead, truncated?}",
            example = ex("path" to "/etc/os-release", "limit" to 4096),
        ),
        // [T-android-backup-subagents]
        MethodSpec(
            name = "debug.backup.export",
            description = "Run the real BackupExporter into filesDir/debug-backup (no destination upload). Credentials excluded unless includeCredentials=true.",
            params = listOf(
                ParamSpec("categories", "array", required = false, description = "Category keys, default [\"providers\"]."),
                ParamSpec("includeCredentials", "boolean", required = false, description = "Default false."),
            ),
            returns = "{path, bytes, backupId, members:[...]}",
            example = JSONObject().put("categories", org.json.JSONArray().put("providers")),
        ),
        MethodSpec(
            name = "debug.backup.restore",
            description = "Extract a .minisbak on the device and run the real BackupImporter (merge; nothing deleted).",
            params = listOf(
                ParamSpec("path", "string", required = true, description = "Absolute path of the package on the device."),
                ParamSpec("categories", "array", required = false, description = "Category keys; default = all in the package."),
            ),
            returns = "{backupId, sourcePlatform, integrityChecked, integrityFailed, warnings, categories:[{category, imported, updated, skipped, unreadable, failed}]}",
            example = JSONObject().put("path", "/data/user/0/com.openminis.app/files/debug-backup/x.minisbak"),
        ),
        MethodSpec(
            name = "debug.backup.upload",
            description = "Upload a package to ONE named destination with the real RcloneChunkedUpload. cancelAfterMs simulates Stop.",
            params = listOf(
                ParamSpec("path", "string", required = true, description = "Package on the device."),
                ParamSpec("remote", "string", required = true, description = "Destination name."),
                ParamSpec("cancelAfterMs", "integer", required = false, description = "Flip the cancel flag after this long."),
            ),
            returns = "{ok, elapsedMs, cancelRequested, error}",
            example = JSONObject().put("path", "...").put("remote", "SlowDavTest"),
        ),
        MethodSpec(
            name = "debug.backup.remotes.addWebdav",
            description = "Add a WebDAV destination, DISABLED so real backups never deliver to it.",
            params = listOf(
                ParamSpec("name", "string", required = true, description = "Destination name."),
                ParamSpec("url", "string", required = true, description = "WebDAV URL."),
            ),
            returns = "{added, enabled:false}",
            example = JSONObject().put("name", "SlowDavTest").put("url", "http://127.0.0.1:8099"),
        ),
        MethodSpec(
            name = "debug.backup.remotes.remove",
            description = "Remove a destination and its stored credential.",
            params = listOf(ParamSpec("name", "string", required = true, description = "Destination name.")),
            returns = "{removed}",
            example = JSONObject().put("name", "SlowDavTest"),
        ),
        MethodSpec(
            name = "debug.limits.get",
            description = "[T-loadtest-limits] The concurrency caps admission uses now, with their defaults: agent loop sessions (global), sub agent jobs (per parent conversation, childJobsScope), shell process budget. DEV_TOOLS builds only.",
            params = emptyList(),
            returns = "{sessions, sessionsDefault, childJobs, childJobsDefault, childJobsScope, queuedDelegationsPerParent, shellProcessBudget, shellProcessBudgetDefault}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.limits.set",
            description = "[T-loadtest-limits] Override a concurrency cap in memory for a load test (absent = unchanged, null or \"default\" = reset). A process restart resets all. DEV_TOOLS builds only.",
            params = listOf(
                ParamSpec("sessions", "integer", required = false, description = "Agent loop sessions, global, 1..32 (default 6). Raising it admits queued turns. Sub agents do not use these slots."),
                ParamSpec("childJobs", "integer", required = false, description = "Sub agent jobs PER PARENT conversation, 1..32 (default 3). Raising it drains the queues."),
                ParamSpec("childJobsPerParent", "integer", required = false, description = "Alias of childJobs (wins when both are given)."),
                ParamSpec("shellProcessBudget", "integer", required = false, description = "Live Minis child processes the shell gate admits up to, 4..64 (default 16)."),
            ),
            returns = "the caps in effect (as debug.limits.get)",
            example = ex("sessions" to 10, "childJobs" to 10, "shellProcessBudget" to 32),
        ),
        MethodSpec(
            name = "debug.loadtest.seedSession",
            description = "[T-loadtest-seed] Create a titled loadtest-* session (no title request to the title sub-model), optionally with synthetic text / shell-tool history. DEV_TOOLS builds only.",
            params = listOf(
                ParamSpec("title", "string", required = true, description = "Must start with 'loadtest-'."),
                ParamSpec("modelId", "string", required = true, description = "Model id the session row is bound to."),
                ParamSpec("turns", "integer", required = false, description = "User + assistant pairs (0..5000, default 0)."),
                ParamSpec("chars", "integer", required = false, description = "Characters per text message (default 400)."),
                ParamSpec("toolEvery", "integer", required = false, description = "Every Nth turn also gets a shell tool call + result (0 = never)."),
                ParamSpec("toolOutputChars", "integer", required = false, description = "Characters per synthetic tool result (default 4000)."),
            ),
            returns = "{sessionId, title, rows}",
            example = ex("title" to "loadtest-medium-1", "modelId" to "loadtest-mock", "turns" to 150, "toolEvery" to 5),
        ),
        MethodSpec(
            name = "debug.loadtest.snapshot",
            description = "[T-loadtest-limits] One sample of the app process for a load test: Java / native heap, PSS, threads, availMem / threshold / lowMemory, onTrimMemory counts, slot and queue counters. DEV_TOOLS builds only.",
            params = emptyList(),
            returns = "{tMs, javaHeapUsedKb, javaHeapMaxKb, nativeHeapAllocKb, totalPssKb, dalvikPssKb, nativePssKb, otherPssKb, threads, availMemKb, thresholdKb, lowMemory, trims, runningSessions, suspendedSessions, childJobsActive, limits}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.chat.usageHint",
            description = "[T-port-composer-hint-debug] Show the composer's context-usage line with chosen figures in the chat on screen (empty composer only), to test the context / quota carousel (two pages, 5 s x 3 rounds) without filling 70% of a context window. Each call is a new generation. DEV_TOOLS builds only.",
            params = listOf(
                ParamSpec("sessionId", "string", required = false, description = "Chat to show it in; defaults to the chat on screen."),
                ParamSpec("percent", "integer", required = false, default = 75, description = "Context used, 1-100. <70 normal, 70-79 warning (amber), 80+ critical (red)."),
                ParamSpec("windowTokens", "integer", required = false, default = 200000, description = "Context window size."),
                ParamSpec("quota", "string", required = false, default = "sample", description = "\"sample\" (fake quota page), \"live\" (the model's real quota, if any), or \"none\" (context page only)."),
                ParamSpec("quotaText", "string", required = false, description = "Custom quota page text (overrides quota)."),
                ParamSpec("quotaHighlight", "string", required = false, description = "Substring of quotaText to tint."),
                ParamSpec("quotaTier", "string", required = false, default = "normal", description = "normal | warning | critical: the quota figure's tint."),
            ),
            returns = "{ok, sessionId, generation, tier, highlights, quotaText, quotaTier, pages:[CONTEXT, QUOTA?], note}",
            example = ex("percent" to 85, "quota" to "sample", "quotaTier" to "warning"),
        ),
        MethodSpec(
            name = "debug.subAgents.list",
            description = "List the sub agent roster (built-in + custom).",
            params = emptyList(),
            returns = "{subAgents:[{id, name, description, instructions, modelGroupId, thinkingLevelOverride, isBuiltIn, sortOrder, updatedAt}]}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.subAgents.upsert",
            description = "Create or update a custom sub agent.",
            params = listOf(
                ParamSpec("name", "string", required = true, description = "Wire name."),
                ParamSpec("description", "string", required = false, description = "What the main model reads."),
                ParamSpec("instructions", "string", required = false, description = "Appended to the child brief."),
                ParamSpec("thinkingLevelOverride", "string", required = false, description = "off|low|medium|high|xhigh|max|ultra."),
                ParamSpec("id", "string", required = false, description = "Update this definition instead of creating one."),
            ),
            returns = "{id, subAgents:[...]}",
            example = JSONObject().put("name", "demo-agent").put("description", "demo"),
        ),
        MethodSpec(
            name = "debug.subAgents.delete",
            description = "Delete a custom sub agent (the built-in cannot be deleted).",
            params = listOf(ParamSpec("id", "string", required = true, description = "Definition id.")),
            returns = "{deleted, remaining}",
            example = JSONObject().put("id", "..."),
        ),
        MethodSpec(
            name = "debug.logs.list",
            description = "List AppLogger log files on disk.",
            params = emptyList(),
            returns = "{totalSize, files:[{name, size, modified}]}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.logs.read",
            description = "Read a log file by filename (no path separators allowed).",
            params = listOf(
                ParamSpec("name", "string", required = true, description = "Log filename (no '/' or '..')."),
                ParamSpec("offset", "int", required = false, default = 0, description = "Character offset to start reading from."),
                ParamSpec("limit", "int", required = false, default = 524288, description = "Maximum characters to read (default 512KB)."),
            ),
            returns = "{name, size, content, bytesRead, truncated?}",
            example = ex("name" to "2026-04-17.log", "limit" to 16384),
        ),
        MethodSpec(
            name = "provider.quickTest",
            description = "Run Quick Test on a model entry (same code path as the UI Quick Test sheet). " +
                "Tests applicable modalities (text, speechOut, transcription, imageGen) and returns " +
                "per-kind status. Audio/image results report byte counts, not payloads. " +
                "Android counterpart of iOS debug.providers.quickTest.",
            params = listOf(
                ParamSpec("entryId", "string", required = true, description = "Model entry id from provider.models.list."),
                ParamSpec("kinds", "string[]", required = false, description = "Subset of text/speechOut/transcription/imageGen; omit to auto-detect."),
            ),
            returns = "{entryId, modelId, displayName, providerLabel, results:[{kind, status, elapsedMs, detail, bytes?}]}",
            example = ex("entryId" to "pi_xyz/mimo-v2.5-tts"),
        ),
        MethodSpec(
            name = "debug.crash.list",
            description = "List on-device crash reports (ACRA Java/Kotlin + native), newest first, " +
                "each with a one-line summary of the exception and first app frame.",
            params = listOf(
                ParamSpec("limit", "int", required = false, default = 20, description = "Max reports to return (1-200)."),
            ),
            returns = "{count, returned, crashes:[{name, size, modified, native, summary}]}",
            example = ex("limit" to 5),
        ),
        MethodSpec(
            name = "debug.crash.read",
            description = "Read a crash report. Omit 'name' to get the NEWEST crash — the usual " +
                "\"why did it just die?\" call. Returns the stack section only by default; " +
                "pass stackOnly=false for the full file including the embedded logcat tail.",
            params = listOf(
                ParamSpec("name", "string", required = false, description = "Report filename from debug.crash.list; omit for newest."),
                ParamSpec("stackOnly", "bool", required = false, default = true, description = "Trim the trailing logcat/Build dump."),
                ParamSpec("limit", "int", required = false, default = 262144, description = "Max characters to return."),
            ),
            returns = "{name, modified, native, fileSize, stackOnly, content, truncated?}",
            example = ex("stackOnly" to true),
        ),
        MethodSpec(
            name = "debug.tap",
            description = "Dispatch a tap gesture at the given Activity-space coordinates.",
            params = listOf(
                ParamSpec("x", "number", required = true, description = "Screen x in pixels."),
                ParamSpec("y", "number", required = true, description = "Screen y in pixels."),
            ),
            returns = "{ok, point:{x,y}}",
            example = ex("x" to 300, "y" to 600),
        ),
        MethodSpec(
            name = "debug.scroll",
            description = "Simulate a scroll gesture from a point by deltaX/deltaY.",
            params = listOf(
                ParamSpec("x", "number", required = true, description = "Start x in pixels."),
                ParamSpec("y", "number", required = true, description = "Start y in pixels."),
                ParamSpec("deltaX", "number", required = false, default = 0, description = "Horizontal delta."),
                ParamSpec("deltaY", "number", required = false, default = 0, description = "Vertical delta. At least one of deltaX/deltaY must be non-zero."),
            ),
            returns = "{ok, start:{x,y}, delta:{x,y}}",
            example = ex("x" to 300, "y" to 600, "deltaY" to -400),
        ),
        MethodSpec(
            name = "debug.inputText",
            description = "Type text into the currently focused field.",
            params = listOf(
                ParamSpec("text", "string", required = true, description = "Text to input."),
            ),
            returns = "{ok, length}",
            example = ex("text" to "hello world"),
        ),
        MethodSpec(
            name = "debug.voice.injectFailedAudio",
            description = "DEBUG-only: queue a 16 kHz mono PCM16 WAV as a failed voice utterance, as the provider " +
                "engine does when a transcription fails. The voice panel shows its Retry prompt; Retry runs the " +
                "real ASR fail-over (stall offer / Switch Model / System fallback) on that audio.",
            params = listOf(
                ParamSpec("wavBase64", "string", required = true, description = "Base64 of the WAV file."),
                ParamSpec("engineId", "string", required = false, default = "provider", description = "Engine that retries it."),
            ),
            returns = "{ok, bytes, seconds}",
            example = ex("wavBase64" to "<base64>"),
        ),
        MethodSpec(
            name = "debug.setClipboard",
            description = "Put text on the system clipboard from inside the app process, " +
                "so `input keyevent PASTE` can trigger a REAL single-shot paste. " +
                "debug.inputText types per character and therefore cannot exercise " +
                "paste-detection logic.",
            params = listOf(
                ParamSpec("text", "string", required = true, description = "Text to place on the clipboard."),
                ParamSpec("label", "string", required = false, default = "minis-debug", description = "ClipData label."),
            ),
            returns = "{ok, length, clipboardLength}",
            example = ex("text" to "a long block of text"),
        ),
        MethodSpec(
            name = "debug.llmRequests",
            description = "Return captured LLM provider requests/responses for debugging.",
            params = listOf(
                ParamSpec("last", "int", required = false, description = "Limit to the N most recent entries."),
                ParamSpec("formatted", "bool", required = false, default = false, description = "If true, return a single 'text' string (same shape as iOS Copy Requests output) instead of structured JSON."),
                ParamSpec("fullLast", "bool", required = false, default = false, description = "If true, return only the newest request body UNTRUNCATED (the ring caps bodies at 64 KB)."),
            ),
            returns = "{count, requests:[...]}; {count, text} when formatted=true; {timestamp, length, requestBody} when fullLast=true",
            example = ex("last" to 5),
        ),
        MethodSpec(
            name = "debug.llmRequests.clear",
            description = "Clear the captured LLM request log.",
            params = emptyList(),
            returns = "{cleared: true}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.agentTrace",
            description = "Get agent request traces (alias of debug.llmRequests, iOS-compatible shape).",
            params = listOf(
                ParamSpec("last", "bool", required = false, default = false, description = "If true, return only the most recent trace object instead of {traces:[...]}."),
            ),
            returns = "{traces:[...]} or a single trace object when last=true",
            example = ex("last" to true),
        ),
        MethodSpec(
            name = "debug.fetch",
            description = "Diagnostic HTTP probe: runs DNS, HttpURLConnection, OkHttp, and proxy-selector checks against a URL.",
            params = listOf(
                ParamSpec("url", "string", required = true, description = "Absolute URL to probe."),
            ),
            returns = "{dns?, dns_error?, httpurlconn_status?, httpurlconn_error?, okhttp_status?, okhttp_error?, proxies?, proxy_error?}",
            example = ex("url" to "https://example.com"),
        ),
        MethodSpec(
            name = "debug.logs.setEnabled",
            description = "Enable or disable AppLogger file capture at runtime.",
            params = listOf(
                ParamSpec("enabled", "bool", required = true, description = "true to start capturing, false to stop."),
            ),
            returns = "{enabled}",
            example = ex("enabled" to true),
        ),
        MethodSpec(
            name = "debug.viewTree",
            description = "Return the foreground Activity's View hierarchy with embedded Compose semantics nodes (shallow). Addresses are good only until the next viewTree/search call.",
            params = listOf(
                ParamSpec("maxDepth", "int", required = false, default = 50, description = "Maximum tree depth to traverse."),
            ),
            returns = "Array of root nodes; each node has {address, type, bounds, text?, description?, children?, compose?}.",
            example = ex("maxDepth" to 20),
        ),
        MethodSpec(
            name = "debug.search",
            description = "Search the View + Compose semantics tree for nodes matching a keyword (case-insensitive substring).",
            params = listOf(
                ParamSpec("keyword", "string", required = true, description = "Substring matched against text, contentDescription, type, or testTag."),
                ParamSpec("scope", "string", required = false, default = "all", description = "'all', 'text', or 'type'."),
            ),
            returns = "Array of matching nodes (shallow describe shape).",
            example = ex("keyword" to "Login", "scope" to "text"),
        ),
        MethodSpec(
            name = "debug.inspect",
            description = "Return full properties of the node at the given address from a prior viewTree/search call.",
            params = listOf(
                ParamSpec("address", "string", required = true, description = "Address returned by viewTree/search."),
            ),
            returns = "Node object with extra detail (alpha, class, idName, actions…), or {error} if not found.",
            example = ex("address" to "0x12abcd"),
        ),
        MethodSpec(
            name = "debug.highlight",
            description = "Flash a colored overlay on a specific node for visual verification.",
            params = listOf(
                ParamSpec("address", "string", required = true, description = "Address returned by viewTree/search."),
                ParamSpec("color", "string", required = false, default = "red", description = "Named color: red, green, blue, yellow, purple, orange — or any #RRGGBB."),
                ParamSpec("duration", "number", required = false, default = 2.0, description = "Seconds to keep the overlay visible (0.1..10)."),
            ),
            returns = "{ok: bool}",
            example = ex("address" to "0x12abcd", "color" to "green", "duration" to 1.5),
        ),
        MethodSpec(
            name = "debug.cloudSync",
            description = "Return cloud sync status. Android does not implement iCloud sync — this method is a stub returning {enabled: false, status: 'unsupported'} for iOS API compatibility.",
            params = emptyList(),
            returns = "{enabled, status, devices, deviceCount}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.writeFile",
            description = "Write a file into the app's filesDir-rooted Linux-path namespace (resolved through PRoot bind mounts). Intended for staging test fixtures before debug.shellExecute.",
            params = listOf(
                ParamSpec("path", "string", required = true, description = "Linux path under the proot rootfs (e.g. /tmp/test.sh, /var/minis/skills/example.md)."),
                ParamSpec("content", "string", required = true, description = "File content. Encoded per the 'encoding' field."),
                ParamSpec("encoding", "string", required = false, default = "utf8", description = "'utf8' or 'base64'."),
                ParamSpec("overwrite", "bool", required = false, default = true, description = "Whether to overwrite an existing file."),
                ParamSpec("mode", "string", required = false, default = "0644", description = "Octal file mode, e.g. '0644' or '0755'. Setting mode is best-effort on Android."),
            ),
            returns = "{ok, path, hostPath, size}",
            example = ex("path" to "/tmp/hello.sh", "content" to "IyEvYmluL3NoCmVjaG8gaGVsbG8K", "encoding" to "base64", "mode" to "0755"),
        ),
        MethodSpec(
            name = "debug.screenshot.capture",
            description = "Capture a screenshot into the in-memory ring buffer with an optional label.",
            params = listOf(
                ParamSpec("label", "string", required = false, default = "", description = "Free-form label."),
                ParamSpec("scale", "number", required = false, default = 0.5, description = "Render scale (0 < scale <= 1)."),
            ),
            returns = "{id, label, timestamp, size}",
            example = ex("label" to "before-tap"),
        ),
        MethodSpec(
            name = "debug.screenshot.list",
            description = "List entries in the screenshot ring buffer (newest last).",
            params = emptyList(),
            returns = "{count, entries:[{id, label, timestamp, size}]}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.screenshot.get",
            description = "Return a single screenshot from the ring buffer as base64 PNG.",
            params = listOf(
                ParamSpec("id", "int", required = false, description = "Entry id; defaults to the latest entry."),
            ),
            returns = "{id, label, timestamp, base64, size, encoding: 'png'}",
            example = ex("id" to 3),
        ),
        MethodSpec(
            name = "debug.screenshot.clear",
            description = "Clear the screenshot ring buffer.",
            params = emptyList(),
            returns = "{cleared: int}",
            example = JSONObject(),
        ),

        // --- Browser ---
        MethodSpec(
            name = "debug.browser.listTabs",
            description = "List all open browser tabs in the application-scoped pool.",
            params = emptyList(),
            returns = "{tabs:[{id, url, title, selected, inUse}]}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.browser.pageInfo",
            description = "Get URL/title/loading-state/viewport for a tab.",
            params = listOf(
                ParamSpec("tabId", "int", required = false, description = "Target tab id; defaults to selected tab."),
            ),
            returns = "{tabId, url, title, isLoading, canGoBack, canGoForward, viewport, text}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.browser.cookies",
            description = "DEV_TOOLS only. WebView cookie store, names only (never values): get a site's cookie names, " +
                "set one raw Set-Cookie value, or clear a site exactly as the browser settings delete icon does.",
            params = listOf(
                ParamSpec("action", "string", required = true, description = "get | set | clear"),
                ParamSpec("domain", "string", required = false, description = "Site host, for get / clear (e.g. www.example.com)."),
                ParamSpec("url", "string", required = false, description = "For set: the URL the cookie is set from."),
                ParamSpec("cookie", "string", required = false, description = "For set: a Set-Cookie value, e.g. \"a=1; Path=/; Secure\"."),
            ),
            returns = "get {domain, names, hasCookies} · set {ok} · clear {domain, cleared, found, remaining}",
            example = ex("action" to "get", "domain" to "www.example.com"),
        ),
        MethodSpec(
            name = "debug.browser.executeJS",
            description = "Run arbitrary JavaScript in a tab's WebView and return the evaluated result.",
            params = listOf(
                ParamSpec("script", "string", required = true, description = "JS source. The expression's value is stringified and returned."),
                ParamSpec("tabId", "int", required = false, description = "Target tab id; defaults to selected tab."),
            ),
            returns = "{result}",
            example = ex("script" to "document.title"),
        ),
        MethodSpec(
            name = "debug.browser.getReadable",
            description = "Run the Readability-style content extraction on a tab.",
            params = listOf(
                ParamSpec("tabId", "int", required = false, description = "Target tab id; defaults to selected tab."),
            ),
            returns = "{tabId, text, length}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "debug.browser.getText",
            description = "Extract text content from the page, optionally scoped to a CSS selector.",
            params = listOf(
                ParamSpec("selector", "string", required = false, description = "CSS selector to scope the extraction."),
                ParamSpec("tabId", "int", required = false, description = "Target tab id; defaults to selected tab."),
            ),
            returns = "{tabId, text, length}",
            example = ex("selector" to "article"),
        ),
        MethodSpec(
            name = "debug.browser.screenshot",
            description = "Capture a JPEG screenshot of a tab's WebView.",
            params = listOf(
                ParamSpec("tabId", "int", required = false, description = "Target tab id; defaults to selected tab."),
            ),
            returns = "{base64, size, encoding: 'jpeg', tabId}",
            example = JSONObject(),
        ),

        // --- Provider (read-only — Phase 1) ---
        MethodSpec(
            name = "provider.types",
            description = "Return the schema of every supported provider type, including built-in model ids and OAuth flows.",
            params = emptyList(),
            returns = "{types:[{id, displayName, supportedCredentials, oauthFlow, defaultBaseURL, customBaseURLSupported, appendV1SuffixConfigurable, builtInModelIds}]}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "provider.instances.list",
            description = "List all configured provider instances (write-only credential material excluded).",
            params = listOf(
                ParamSpec("includeDisabled", "bool", required = false, default = true, description = "If false, omit instances where isEnabled=false."),
            ),
            returns = "{count, instances:[{id, label, providerType, credentialType, isEnabled, customBaseURL, appendV1Suffix, useResponsesAPI, createdAt, hasCredential, modelEntryCount}]}",
            example = JSONObject(),
        ),
        MethodSpec(
            name = "provider.models.list",
            description = "List model entries for a specific provider instance.",
            params = listOf(
                ParamSpec("instanceId", "string", required = true, description = "Target instance UUID."),
                ParamSpec("includeHidden", "bool", required = false, default = false, description = "Include user-hidden entries."),
            ),
            returns = "{instanceId, instanceLabel, count, entries:[...]}",
            example = ex("instanceId" to "pi_xyz"),
        ),
        MethodSpec(
            name = "provider.export",
            description = "Export a provider instance (config + models + stored API key) as a portable JSON envelope. Mirrors the in-app Share/Export button. The API key is base64-wrapped to survive JSON string escaping; this is NOT a security boundary — callers of the 5321 RPC are trusted.",
            params = listOf(
                ParamSpec("instanceId", "string", required = true, description = "Target instance UUID."),
            ),
            returns = "{version, exportedAt, platform, instanceId, instanceLabel, config:{providerType, label, credentialType, apiKey?, customBaseURL?, models:[...]}}",
            example = ex("instanceId" to "pi_xyz"),
        ),
        MethodSpec(
            name = "provider.import",
            description = "Import a provider instance from a JSON document produced by `provider.export` or the in-app Share button. Accepts either the wrapped envelope or the raw legacy config JSON for cross-platform compatibility. Always assigns a new instance UUID; duplicate labels are auto-suffixed.",
            params = listOf(
                ParamSpec("configJson", "string", required = true, description = "Either the {version,config:{...}} envelope or the raw legacy config JSON."),
            ),
            returns = "{instanceId, instanceLabel, modelEntryCount, success}",
            example = ex("configJson" to "{\"version\":1,\"config\":{...}}"),
        ),
        MethodSpec(
            name = "provider.groups.list",
            description = "List all configured model groups.",
            params = listOf(
                ParamSpec("includeMembers", "bool", required = false, default = true, description = "Include resolved member summaries in each group."),
            ),
            returns = "{defaultGroupId, count, groups:[{id, name, strategy, fallbackStrategy, isDefault, inAgentLoop, memberEntryIds, members?}]}",
            example = JSONObject(),
        ),

        // --- Chat (read-only — Phase 1) ---
        MethodSpec(
            name = "chat.sessions.list",
            description = "List recent chat sessions sorted by updated_at desc.",
            params = listOf(
                ParamSpec("limit", "int", required = false, default = 50, description = "Max sessions to return (1..500)."),
                ParamSpec("includeEmpty", "bool", required = false, default = false, description = "If true, include sessions with no messages."),
            ),
            returns = "{count, sessions:[...]}",
            example = ex("limit" to 20),
        ),
        MethodSpec(
            name = "chat.sessions.get",
            description = "Fetch a single session's metadata.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
            ),
            returns = "{id, title, modelId, modelName, source, isRunning (processing: own loop, sub agents or own scheduled task due), isStreaming, memoryEnabled, category, messageCount, createdAt, updatedAt}",
            example = ex("sessionId" to "6D0F…"),
        ),
        MethodSpec(
            name = "chat.sessions.usage",
            description = "Aggregate token usage for a session, optionally broken down per assistant turn.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("perTurn", "bool", required = false, default = false, description = "If true, include a turns:[] breakdown."),
            ),
            returns = "{sessionId, totals:{...}, turns?}",
            example = ex("sessionId" to "6D0F…", "perTurn" to true),
        ),
        MethodSpec(
            name = "chat.messages.list",
            description = "List messages in a session in chronological order, with optional filtering.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("limit", "int", required = false, default = 200, description = "Max messages (1..1000)."),
                ParamSpec("offset", "int", required = false, default = 0, description = "Skip the first N matched messages."),
                ParamSpec("roles", "[string]", required = false, description = "Filter by role: user/assistant/system/tool."),
                ParamSpec("includeTools", "bool", required = false, default = true, description = "Include tool-call/tool-result blocks."),
                ParamSpec("includeReasoning", "bool", required = false, default = false, description = "Include captured reasoning_content."),
            ),
            returns = "{sessionId, totalCount, count, messages:[...]}",
            example = ex("sessionId" to "6D0F…", "limit" to 50),
        ),
        MethodSpec(
            name = "chat.models.list",
            description = "Return the candidate models and groups visible to the chat picker.",
            params = listOf(
                ParamSpec("includeHidden", "bool", required = false, default = false, description = "Include user-hidden entries."),
                ParamSpec("includeDisabled", "bool", required = false, default = false, description = "Include entries from disabled provider instances."),
            ),
            returns = "{defaultGroupId, groupCount, entryCount, groups:[...], entries:[...]}",
            example = JSONObject(),
        ),

        // --- Provider mutate ---
        MethodSpec(
            name = "provider.instances.create",
            description = "Create a new provider instance. apiKey/oauthToken are write-only.",
            params = listOf(
                ParamSpec("providerType", "string", required = true, description = "anthropic / gemini / openAI / openRouter"),
                ParamSpec("label", "string", required = true, description = "User-visible name."),
                ParamSpec("credentialType", "string", required = false, default = "apiKey", description = "apiKey or oauth"),
                ParamSpec("apiKey", "string", required = false, description = "Stored encrypted; never returned by any read."),
                ParamSpec("oauthToken", "string", required = false, description = "Manual OAuth token seed."),
                ParamSpec("customBaseURL", "string", required = false, description = "openAI only."),
                ParamSpec("appendV1Suffix", "bool", required = false, default = true, description = "Whether to append /v1 to customBaseURL."),
                ParamSpec("useResponsesAPI", "bool", required = false, default = false, description = "openAI only — route via /v1/responses."),
                ParamSpec("customUserAgent", "string", required = false, description = "Override outbound User-Agent. apiKey providers only."),
                ParamSpec("isEnabled", "bool", required = false, default = true, description = "Create in enabled state."),
                ParamSpec("seedBuiltInModels", "bool", required = false, default = true, description = "If false, create instance with no model entries."),
            ),
            returns = "{instance:{...}}",
            example = ex("providerType" to "openAI", "label" to "DeepSeek", "apiKey" to "sk-…", "customBaseURL" to "https://api.deepseek.com", "seedBuiltInModels" to false),
        ),
        MethodSpec(
            name = "provider.instances.update",
            description = "Patch fields on an existing instance. Omitted fields are untouched. Pass apiKey=\"\" to clear.",
            params = listOf(
                ParamSpec("instanceId", "string", required = true, description = "Target instance UUID."),
                ParamSpec("label", "string", required = false, description = "New display name."),
                ParamSpec("apiKey", "string", required = false, description = "Replace stored API key. \"\" clears."),
                ParamSpec("oauthToken", "string", required = false, description = "Replace stored OAuth token."),
                ParamSpec("customBaseURL", "string", required = false, description = "null reverts to default."),
                ParamSpec("appendV1Suffix", "bool", required = false, description = "Update suffix behavior."),
                ParamSpec("useResponsesAPI", "bool", required = false, description = "openAI only."),
                ParamSpec("customUserAgent", "string", required = false, description = "Override outbound User-Agent (null/\"\" reverts to default). apiKey providers only."),
                ParamSpec("isEnabled", "bool", required = false, description = "Enable / disable."),
                ParamSpec("imageEndpointMode", "string", required = false, description = "auto | images_generations | chat_completions. Forcing non-auto clears the cached probe result (GH#68)."),
            ),
            returns = "{instance:{...}}",
            example = ex("instanceId" to "pi_xyz", "isEnabled" to false),
        ),
        MethodSpec(
            name = "provider.instances.delete",
            description = "Remove a provider instance, all its model entries, and its stored credential.",
            params = listOf(
                ParamSpec("instanceId", "string", required = true, description = "Target instance UUID."),
                ParamSpec("confirm", "bool", required = true, default = false, description = "Must be true."),
            ),
            returns = "{instanceId, deletedModelEntries, deleted}",
            example = ex("instanceId" to "pi_xyz", "confirm" to true),
        ),
        MethodSpec(
            name = "provider.instances.test",
            description = "Probe an instance with the stored credential to verify reachability.",
            params = listOf(
                ParamSpec("instanceId", "string", required = true, description = "Target instance UUID."),
                ParamSpec("timeoutMs", "int", required = false, default = 10000, description = "Clamped to [1000, 30000]."),
            ),
            returns = "{ok, httpStatus, latencyMs, reachableModelCount?, error?}",
            example = ex("instanceId" to "pi_xyz"),
        ),
        MethodSpec(
            name = "provider.models.add",
            description = "Add a custom model entry to an instance.",
            params = listOf(
                ParamSpec("instanceId", "string", required = true, description = "Target instance UUID."),
                ParamSpec("modelId", "string", required = true, description = "API model id."),
                ParamSpec("displayName", "string", required = false, description = "Defaults to a prettified modelId."),
                ParamSpec("contextWindow", "int", required = false, description = "Tokens."),
                ParamSpec("maxOutputTokens", "int", required = false, description = "Tokens."),
                ParamSpec("supportsReasoning", "bool", required = false, description = "Tri-state — pass null to leave unknown."),
                ParamSpec("supportsImageInput", "bool", required = false, description = "Modality flag."),
                ParamSpec("supportsAudioInput", "bool", required = false, description = "Modality flag."),
                ParamSpec("supportsVideoInput", "bool", required = false, description = "Modality flag."),
                ParamSpec("supportsPDFInput", "bool", required = false, description = "Modality flag."),
                ParamSpec("supportsImageOutput", "bool", required = false, description = "Modality flag."),
            ),
            returns = "Entry shape (same as provider.models.list[].entries[i]).",
            example = ex("instanceId" to "pi_xyz", "modelId" to "deepseek-v4-pro"),
        ),
        MethodSpec(
            name = "provider.models.update",
            description = "Patch a model entry. Omitted fields are untouched; pass null to clear an override; modelId only mutable on custom entries.",
            params = listOf(
                ParamSpec("entryId", "string", required = true, description = "Target entry UUID."),
                ParamSpec("displayName", "string", required = false, description = "null clears the override."),
                ParamSpec("maxOutputTokens", "int", required = false, description = "null clears the override."),
                ParamSpec("contextWindow", "int", required = false, description = "null clears the override."),
                ParamSpec("isHidden", "bool", required = false, description = "Toggle picker visibility."),
                ParamSpec("modelId", "string", required = false, description = "Custom entries only."),
            ),
            returns = "Updated entry.",
            example = ex("entryId" to "entry_123", "isHidden" to true),
        ),
        MethodSpec(
            name = "provider.models.delete",
            description = "Remove a custom model entry. Built-in entries can't be deleted — hide them via update isHidden=true.",
            params = listOf(
                ParamSpec("entryId", "string", required = true, description = "Target entry UUID."),
                ParamSpec("confirm", "bool", required = true, default = false, description = "Must be true."),
            ),
            returns = "{entryId, deleted}",
            example = ex("entryId" to "entry_123", "confirm" to true),
        ),
        MethodSpec(
            name = "provider.models.refresh",
            description = "Re-query the provider's model list for an instance and update the entry list.",
            params = listOf(
                ParamSpec("instanceId", "string", required = true, description = "Target instance UUID."),
                ParamSpec(
                    "forceRefresh", "bool", required = false, default = false,
                    description = "Bypass the discovery cache, as the in-app Refresh button does. " +
                        "Currently affects the Codex OAuth path only.",
                ),
            ),
            returns = "{instanceId, added, disappeared, total, durationMs}",
            example = ex("instanceId" to "pi_xyz"),
        ),
        MethodSpec(
            name = "provider.models.setAgentLoop",
            description = "Toggle whether a model entry is exposed to the in-shell minis-model-use agent.",
            params = listOf(
                ParamSpec("entryId", "string", required = true, description = "Target entry UUID."),
                ParamSpec("inLoop", "bool", required = true, description = "true to add, false to remove."),
            ),
            returns = "{entryId, inLoop}",
            example = ex("entryId" to "entry_123", "inLoop" to true),
        ),
        MethodSpec(
            name = "provider.groups.create",
            description = "Create a new model group.",
            params = listOf(
                ParamSpec("name", "string", required = true, description = "Display name."),
                ParamSpec("memberEntryIds", "[string]", required = false, description = "Initial member entries (order significant for fallback)."),
                ParamSpec("strategy", "string", required = false, default = "fallback", description = "fallback / loadBalance"),
                ParamSpec("fallbackStrategy", "string", required = false, default = "default", description = "default / always"),
                ParamSpec("contextLimitTokens", "int", required = false, description = "Context cap for sessions bound to the group; omit / null / 0 for no cap."),
            ),
            returns = "{group:{...}}",
            example = ex("name" to "Coding", "memberEntryIds" to JSONArray().apply { put("entry_a") }),
        ),
        MethodSpec(
            name = "provider.groups.update",
            description = "Patch fields on an existing group.",
            params = listOf(
                ParamSpec("groupId", "string", required = true, description = "Target group UUID."),
                ParamSpec("name", "string", required = false, description = "Must be non-empty if supplied."),
                ParamSpec("memberEntryIds", "[string]", required = false, description = "Replace members entirely; order preserved."),
                ParamSpec("strategy", "string", required = false, description = "fallback / loadBalance"),
                ParamSpec("fallbackStrategy", "string", required = false, description = "default / always"),
                ParamSpec("contextLimitTokens", "int", required = false, description = "Set the group's context cap; null / 0 removes it. Omitted = unchanged."),
            ),
            returns = "{group:{...}}",
            example = ex("groupId" to "grp_xyz"),
        ),
        MethodSpec(
            name = "provider.groups.delete",
            description = "Remove a group. Sessions bound to it fall back to the default group.",
            params = listOf(
                ParamSpec("groupId", "string", required = true, description = "Target group UUID."),
                ParamSpec("confirm", "bool", required = true, default = false, description = "Must be true."),
            ),
            returns = "{groupId, deleted, wasDefault}",
            example = ex("groupId" to "grp_xyz", "confirm" to true),
        ),
        MethodSpec(
            name = "provider.groups.setDefault",
            description = "Set or clear the global default model group.",
            params = listOf(
                ParamSpec("groupId", "string", required = true, description = "Target group UUID, or null to clear."),
            ),
            returns = "{defaultGroupId}",
            example = ex("groupId" to "grp_xyz"),
        ),
        MethodSpec(
            name = "provider.groups.setAgentLoop",
            description = "Toggle whether a model group is exposed to the in-shell minis-model-use agent.",
            params = listOf(
                ParamSpec("groupId", "string", required = true, description = "Target group UUID."),
                ParamSpec("inLoop", "bool", required = true, description = "true to add, false to remove."),
            ),
            returns = "{groupId, inLoop}",
            example = ex("groupId" to "grp_xyz", "inLoop" to true),
        ),

        // --- Chat mutate ---
        MethodSpec(
            name = "chat.prompt",
            description = "Send a prompt to a new or existing chat session. wait=true blocks until completion.",
            params = listOf(
                ParamSpec("prompt", "string", required = true, description = "User message text."),
                ParamSpec("sessionId", "string", required = false, description = "Existing session id; omit to create a new one (source=debug)."),
                ParamSpec("attachments", "[object]", required = false, description = "Array of {name, data, mime?}; data is base64."),
                ParamSpec("modelEntryId", "string", required = false, description = "Pin to a specific model entry (mutually exclusive with modelGroupId)."),
                ParamSpec("modelGroupId", "string", required = false, description = "Pin to a model group."),
                ParamSpec("thinkingLevel", "string", required = false, description = "off / low / medium / high / xhigh / max / ultra — applies before send; leaves the VM setting alone when omitted."),
                ParamSpec("wait", "bool", required = false, default = false, description = "Block until completion."),
                ParamSpec("waitTimeout", "int", required = false, default = 600, description = "Seconds; clamped to [1, 1800]."),
            ),
            returns = "{sessionId, isNewSession, modelName, status, prompt, responseText, userMessageId, timedOut?}",
            example = ex("prompt" to "Hello", "wait" to true),
        ),
        MethodSpec(
            name = "chat.retry",
            description = "Retry from a specific user message in a session.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("messageId", "string", required = false, description = "User message id; omit to retry from the most recent user message."),
                ParamSpec("modelEntryId", "string", required = false, description = "Pin retry to a specific entry."),
                ParamSpec("modelGroupId", "string", required = false, description = "Pin retry to a group."),
                ParamSpec("wait", "bool", required = false, default = false, description = "Block until completion."),
                ParamSpec("waitTimeout", "int", required = false, default = 600, description = "Seconds; clamped to [1, 1800]."),
            ),
            returns = "{sessionId, status, retriedMessageId, deletedMessageCount, modelName, responseText, timedOut?}",
            example = ex("sessionId" to "6D0F…", "wait" to true),
        ),
        MethodSpec(
            name = "chat.rerunFromToolBlock",
            description = "Block-boundary re-run: cut at a specific tool_use block (keep earlier blocks in its turn, drop it + everything after) and regenerate. In-app this is the tool-bubble long-press 'Re-run From Here'.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("assistantMessageId", "string", required = true, description = "UI assistant bubble id owning the tool block."),
                ParamSpec("blockId", "string", required = true, description = "Tool block id (equals its tool_use id)."),
                ParamSpec("wait", "bool", required = false, default = false, description = "Block until completion."),
                ParamSpec("waitTimeout", "int", required = false, default = 600, description = "Seconds; clamped to [1, 1800]."),
            ),
            returns = "{sessionId, status, deletedMessageCount, responseText, timedOut?}",
            example = ex("sessionId" to "6D0F…", "assistantMessageId" to "assistant_…", "blockId" to "call_…", "wait" to true),
        ),
        MethodSpec(
            name = "chat.session.status",
            description = "Poll a session's live status (after async chat.prompt / chat.retry).",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
            ),
            returns = "{sessionId, title, modelName, isRunning (processing), isStreaming, messageCount, lastMessageRole, updatedAt}",
            example = ex("sessionId" to "6D0F…"),
        ),
        MethodSpec(
            name = "chat.session.cancel",
            description = "Cancel an in-progress agent run. No-op if the session isn't running.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
            ),
            returns = "{sessionId, wasRunning, cancelled}",
            example = ex("sessionId" to "6D0F…"),
        ),
        MethodSpec(
            name = "chat.session.selectModel",
            description = "Switch the live session's bound model mid-session (same as tapping a model in the in-app picker: ChatViewModel.selectEntry). Unlike chat.prompt's modelEntryId (which only rewrites the DB binding and is ignored by the loaded VM), this re-resolves the live model/provider. The thinking level is preserved across the switch; the response echoes it back.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("modelEntryId", "string", required = true, description = "Model entry id to bind (from chat.models.list)."),
            ),
            returns = "{sessionId, modelEntryId, modelName, thinkingLevel}",
            example = ex("sessionId" to "6D0F…", "modelEntryId" to "…/gpt-5.6-sol"),
        ),
        MethodSpec(
            name = "chat.compact.markers.list",
            description = "List all compact markers on a session, oldest → newest. Includes v2 fields (anchorMessageId, version, summaryLength, summaryPreview).",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("includeFullSummary", "bool", required = false, default = false, description = "Include the full summary string in each marker (default: 120-char preview only)."),
            ),
            returns = "{sessionId, count, markers:[{id, version, anchorMessageId, firstKeptMessageId, summaryLength, summaryPreview, compactedCount, createdAt}]}",
            example = ex("sessionId" to "6D0F…"),
        ),
        MethodSpec(
            name = "chat.compact.before",
            description = "Trigger compaction on a session. includesBoundary=true → compactAll semantics (the in-app /compact slash command, anchor = last active message). includesBoundary=false → 'compact up through messageId' (long-press equivalent, anchor = caller-supplied DB message id). In v2 both modes write the same shape of marker — includesBoundary is preserved for ABI compat with iOS.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("messageId", "string", required = false, description = "DB message id to use as the anchor. Required when includesBoundary=false."),
                ParamSpec("includesBoundary", "bool", required = false, default = false, description = "true → compactAll mode (auto-anchor on last active message)."),
                ParamSpec("waitTimeout", "int", required = false, default = 120, description = "Seconds to wait for compaction LLM call. Clamped [1,1800]."),
            ),
            returns = "{sessionId, messageId, includesBoundary, beforeMarkerCount, afterMarkerCount, wrote, status, timedOut?, error?, latestMarker:{id, version, anchorMessageId, summaryLength, compactedCount, createdAt}}",
            example = ex("sessionId" to "6D0F…", "includesBoundary" to true),
        ),
        MethodSpec(
            name = "chat.compact.revert",
            description = "Revert the most recent compact on a session. Drops the latest marker; cachedLatestMarker falls back to the previous one (or null), then the UI rebuilds.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
            ),
            returns = "{sessionId, beforeMarkerCount, afterMarkerCount, removedMarkerId, newLatestMarkerId}",
            example = ex("sessionId" to "6D0F…"),
        ),
        MethodSpec(
            name = "scheduled.runAgentChild",
            description = "[T-agent-port-round3] Create a scheduled task with target mode CHILD_OF (agent task in a chat) and fire it now — the callback CARD path (no delegate block). Debug/e2e only.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Parent chat the agent child belongs to."),
                ParamSpec("prompt", "string", required = true, description = "The agent's task."),
                ParamSpec("label", "string", required = false, description = "Task label (becomes the agent title)."),
            ),
            returns = "{taskId, childSessionId}",
            example = ex("sessionId" to "6D0F…", "prompt" to "echo hi"),
        ),
        MethodSpec(
            name = "chat.session.delete",
            description = "Permanently delete a session and its messages. Cancels any in-flight run first.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("confirm", "bool", required = true, default = false, description = "Must be true."),
            ),
            returns = "{sessionId, deleted}",
            example = ex("sessionId" to "6D0F…", "confirm" to true),
        ),
    )

    private fun encode(method: MethodSpec): JSONObject {
        val params = JSONArray()
        for (p in method.params) {
            val obj = JSONObject().apply {
                put("name", p.name)
                put("type", p.type)
                put("required", p.required)
                put("description", p.description)
            }
            if (p.default != null) obj.put("default", p.default)
            params.put(obj)
        }
        return JSONObject().apply {
            put("name", method.name)
            put("description", method.description)
            put("params", params)
            put("returns", method.returns)
            put("example", method.example)
        }
    }

    fun discover(): JSONObject {
        val arr = JSONArray()
        for (m in methods) arr.put(encode(m))
        return JSONObject().apply {
            put("platform", "android")
            put("version", BuildConfig.VERSION_NAME)
            put("build", BuildConfig.VERSION_CODE)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("methodCount", methods.size)
            put("methods", arr)
        }
    }

    val methodNames: List<String>
        get() = methods.map { it.name }
}

// ─── Merged from DebugRPCHandler.kt ──────────────────────────────────────────────────

/**
 * JSON-RPC 2.0 method dispatcher for the debug server.
 * Mirrors iOS DebugServer methods for cross-platform parity.
 *
 * Call `rpc.discover` to retrieve the full method catalogue with parameter
 * schemas and examples. See [DebugMethodRegistry] for the source of truth.
 */
class DebugRPCHandler(private val context: Context) {

    companion object {
        /** Set this from MainActivity to enable screenshot capture. */
        var currentActivity: WeakReference<Activity>? = null

        /** Both crash writers' filenames: ACRA's and NativeCrashHandler's. */
        private val CRASH_NAME = Regex("""^(native-)?crash-.*\.log$""")
    }

    suspend fun handle(json: String): String {
        val parsed = try {
            JSONObject(json)
        } catch (_: Exception) {
            return errorResponse(-32700, "Parse error", null)
        }

        val id = parsed.opt("id")
        val jsonrpc = parsed.optString("jsonrpc")
        if (jsonrpc != "2.0") {
            return errorResponse(-32600, "Invalid Request: missing jsonrpc 2.0", id)
        }

        val method = parsed.optString("method")
        if (method.isEmpty()) {
            return errorResponse(-32600, "Invalid Request: missing method", id)
        }

        val params = parsed.optJSONObject("params") ?: JSONObject()

        return try {
            val result = dispatch(method, params)
            successResponse(result, id)
        } catch (e: RPCException) {
            errorResponse(e.code, e.message ?: "Unknown error", id)
        } catch (e: Exception) {
            errorResponse(-32000, e.message ?: "Internal error", id)
        }
    }

    private suspend fun dispatch(method: String, params: JSONObject): Any {
        return when (method) {
            "rpc.discover" -> DebugMethodRegistry.discover()
            "debug.appInfo" -> handleAppInfo()
            // [T-loadtest-limits] Load-test caps and process sampling (DEV_TOOLS).
            "debug.limits.get" -> LoadTestDebugMethods.limitsGet()
            "debug.limits.set" -> LoadTestDebugMethods.limitsSet(params)
            "debug.loadtest.snapshot" -> LoadTestDebugMethods.snapshot(context)
            "debug.loadtest.seedSession" -> LoadTestDebugMethods.seedSession(context, params)
            "debug.screenshot" -> handleScreenshot(params)
            "debug.ls" -> handleLS(params)
            "debug.rawLs" -> handleRawLS(params)
            "debug.readFile" -> handleReadFile(params)
            "debug.logs.list" -> handleLogsList()
            // [T-android-backup-subagents] Backup round-trip + sub agent roster drivers.
            "debug.backup.export" -> BackupDebugMethods.backupExport(context, params)
            "debug.backup.restore" -> BackupDebugMethods.backupRestore(context, params)
            "debug.backup.upload" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { BackupDebugMethods.backupUpload(context, params) }
            "debug.backup.remotes.addWebdav" -> BackupDebugMethods.remotesAddWebdav(context, params)
            "debug.backup.remotes.remove" -> BackupDebugMethods.remotesRemove(context, params)
            // [T-net-android-phase0] Embedded Tailscale node.
            in NetDebugMethods.names -> NetDebugMethods.dispatch(context, method, params)
            "debug.subAgents.list" -> BackupDebugMethods.subAgentsList(context)
            "debug.subAgents.upsert" -> BackupDebugMethods.subAgentsUpsert(context, params)
            "debug.subAgents.delete" -> BackupDebugMethods.subAgentsDelete(context, params)
            "debug.logs.read" -> handleLogsRead(params)
            "debug.logs.setEnabled" -> handleLogsSetEnabled(params)
            "debug.crash.list" -> handleCrashList(params)
            "debug.crash.read" -> handleCrashRead(params)
            "debug.tap" -> handleTap(params)
            "debug.scroll" -> handleScroll(params)
            "debug.inputText" -> handleInputText(params)
            "debug.setClipboard" -> handleSetClipboard(params)
            "debug.voice.injectFailedAudio" -> {
                if (!BuildConfig.DEV_TOOLS) {
                    throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
                }
                handleVoiceInjectFailedAudio(params)
            }
            "debug.llmRequests" -> handleLLMRequests(params)
            "debug.llmRequests.clear" -> { LLMRequestLog.clear(); JSONObject().put("cleared", true) }
            "debug.agentTrace" -> handleAgentTrace(params)
            "debug.fetch" -> handleFetch(params)
            "debug.shellExecute" -> handleShellExecute(params)
            "debug.update.check" -> handleUpdateCheck()
            "debug.update.download" -> handleUpdateDownload(params)
            "debug.update.install" -> handleUpdateInstall(params)
            "debug.permissions.list" -> handlePermissionsList()

            // UI introspection (Layer A parity with iOS)
            "debug.viewTree" -> handleViewTree(params)
            "debug.search" -> handleSearch(params)
            "debug.inspect" -> handleInspect(params)
            "debug.highlight" -> handleHighlight(params)
            "debug.cloudSync" -> handleCloudSync()
            "debug.writeFile" -> handleWriteFile(params)
            "debug.screenshot.capture" -> handleScreenshotCapture(params)
            "debug.screenshot.list" -> DebugScreenshotRing.listJson()
            "debug.screenshot.get" -> handleScreenshotGet(params)
            "debug.screenshot.clear" -> JSONObject().put("cleared", DebugScreenshotRing.clearAll())

            // Browser
            "debug.browser.listTabs" -> BrowserDebugMethods.listTabs(context)
            "debug.browser.pageInfo" -> BrowserDebugMethods.pageInfo(context, params)
            "debug.browser.executeJS" -> BrowserDebugMethods.executeJS(context, params)
            "debug.browser.getReadable" -> BrowserDebugMethods.getReadable(context, params)
            "debug.browser.getText" -> BrowserDebugMethods.getText(context, params)
            "debug.browser.screenshot" -> BrowserDebugMethods.screenshot(context, params)
            "debug.browser.cookies" -> BrowserDebugMethods.cookies(params)

            // Provider (read)
            "provider.types" -> ProviderDebugMethods.types()
            "provider.instances.list" -> ProviderDebugMethods.instancesList(context, params)
            "provider.models.list" -> ProviderDebugMethods.modelsList(context, params)
            "provider.groups.list" -> ProviderDebugMethods.groupsList(context, params)
            "provider.quickTest" -> ProviderDebugMethods.quickTest(context, params)
            "provider.export" -> ProviderDebugMethods.export(context, params)
            "provider.import" -> ProviderDebugMethods.import(context, params)

            // Provider (mutate)
            "provider.instances.create" -> ProviderMutationMethods.instancesCreate(context, params)
            "provider.instances.update" -> ProviderMutationMethods.instancesUpdate(context, params)
            "provider.instances.delete" -> ProviderMutationMethods.instancesDelete(context, params)
            "provider.instances.test" -> ProviderMutationMethods.instancesTest(context, params)
            "provider.models.add" -> ProviderMutationMethods.modelsAdd(context, params)
            "provider.models.update" -> ProviderMutationMethods.modelsUpdate(context, params)
            "provider.models.delete" -> ProviderMutationMethods.modelsDelete(context, params)
            "provider.models.refresh" -> ProviderMutationMethods.modelsRefresh(context, params)
            "provider.models.setAgentLoop" -> ProviderMutationMethods.modelsSetAgentLoop(context, params)
            "provider.groups.create" -> ProviderMutationMethods.groupsCreate(context, params)
            "provider.groups.update" -> ProviderMutationMethods.groupsUpdate(context, params)
            "provider.groups.delete" -> ProviderMutationMethods.groupsDelete(context, params)
            "provider.groups.setDefault" -> ProviderMutationMethods.groupsSetDefault(context, params)
            "provider.groups.setAgentLoop" -> ProviderMutationMethods.groupsSetAgentLoop(context, params)

            // Chat (read)
            "chat.sessions.list" -> ChatDebugMethods.sessionsList(context, params)
            "chat.sessions.get" -> ChatDebugMethods.sessionsGet(context, params)
            "chat.sessions.usage" -> ChatDebugMethods.sessionsUsage(context, params)
            "chat.messages.list" -> ChatDebugMethods.messagesList(context, params)
            "chat.models.list" -> ChatDebugMethods.modelsList(context, params)

            // Chat (mutate)
            "chat.prompt" -> ChatMutationMethods.prompt(context, params)
            "chat.uiPrompt" -> ChatMutationMethods.uiPrompt(context, params)
            // [T-port-composer-hint-debug] Composer usage line with chosen figures.
            "debug.chat.usageHint" -> UsageHintDebugMethods.show(context, params)
            "chat.retry" -> ChatMutationMethods.retry(context, params)
            "chat.rerunFromToolBlock" -> ChatMutationMethods.rerunFromToolBlock(context, params)
            "chat.session.status" -> ChatMutationMethods.status(context, params)
            "chat.session.cancel" -> ChatMutationMethods.cancel(context, params)
            "chat.session.selectModel" -> ChatMutationMethods.selectModel(context, params)
            "chat.session.delete" -> ChatMutationMethods.delete(context, params)
            "scheduled.runAgentChild" -> ChatMutationMethods.runScheduledAgentChild(context, params)

            // Chat compact (mirrors iOS chat.compact.* namespace).
            "chat.compact.before" -> ChatMutationMethods.compactBefore(context, params)
            "chat.compact.markers.list" -> ChatMutationMethods.compactMarkersList(context, params)
            "chat.compact.revert" -> ChatMutationMethods.compactRevert(context, params)

            // Debug-only: direct CLI / offload-handler invocation (T344).
            // Registered solely on DEBUG builds so release APKs cannot expose it.
            "debug.shizuku.exec" -> {
                if (!BuildConfig.DEV_TOOLS) {
                    throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
                }
                handleShizukuExec(params)
            }
            // [T-multi-select-share] Share Chat picker / export, served by the
            // ChatScreen showing the session (iOS parity, DEBUG only).
            "debug.chatShare.openPicker" -> {
                if (!BuildConfig.DEV_TOOLS) {
                    throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
                }
                com.openminis.app.ui.chat.share.ChatShareDebugRpc.openPicker(params)
            }
            "debug.chatExport.render" -> {
                if (!BuildConfig.DEV_TOOLS) {
                    throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
                }
                com.openminis.app.ui.chat.share.ChatShareDebugRpc.render(params)
            }
            "debug.modelUse.exec" -> {
                if (!BuildConfig.DEV_TOOLS) {
                    throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
                }
                handleModelUseExec(params)
            }
            // [T-android-sessions-cli-full] DEBUG-only invocation of the
            // SessionsOffloadHandler — parallels debug.modelUse.exec so test
            // harnesses can verify minis-sessions-cli (list / search /
            // messages, incl. --full) end-to-end without an in-shell prompt.
            "debug.sessions.exec" -> {
                if (!BuildConfig.DEV_TOOLS) {
                    throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
                }
                handleSessionsExec(params)
            }
            // [T-android-calendar-update-all-day] DEBUG-only direct
            // invocation of android-calendar, so all-day create/update can be
            // verified against the device's real calendar provider.
            "debug.calendar.exec" -> {
                if (!BuildConfig.DEV_TOOLS) {
                    throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
                }
                handleCalendarExec(params)
            }
            // [T-minis-config-provider-add] DEBUG-only invocation of the
            // ConfigOffloadHandler — parallels debug.modelUse.exec so test
            // harnesses can exercise minis-config (get / set / set-batch /
            // audit-*) without driving an in-shell prompt. The handler
            // re-uses the production ConfigBridge code path; we override
            // skipConfirmation under the hood via a dedicated arg the
            // production CLI never exposes.
            "debug.minisConfig.exec" -> {
                if (!BuildConfig.DEV_TOOLS) {
                    throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
                }
                handleMinisConfigExec(params)
            }

            else -> throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
        }
    }

    // ── App Info ─────────────────────────────────────────────────────────────

    private fun handleAppInfo(): JSONObject {
        val filesDir = context.filesDir
        return JSONObject().apply {
            put("platform", "android")
            put("sdkVersion", Build.VERSION.SDK_INT)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("androidVersion", Build.VERSION.RELEASE)
            put("prootBooted", PRootKernel.isBooted)
            put("filesDir", filesDir.absolutePath)
            put("logFiles", AppLogger.listLogFiles().size)
            put("totalLogSize", AppLogger.totalSize())
            put("diskUsage", JSONObject().apply {
                put("filesDir", dirSize(filesDir))
                put("sessions", dirSize(File(filesDir, "minis-sessions")))
                put("global", dirSize(File(filesDir, "minis-global")))
            })
        }
    }

    // [T-android-storage-symlink-inflation] Second copy of the sizing walk —
    // route it through the shared one rather than keeping a following variant
    // alive here. `walkTopDown().filter { isFile }.sumOf { length() }` resolves
    // symlinks at every step, so it descends into directory links and counts
    // their subtrees again; that is what reported a 9.6 GB install as tens of
    // GB. Diagnostics that disagree with the storage screen are worse than no
    // diagnostics.
    private fun dirSize(dir: File): Long =
        com.openminis.app.data.session.SessionStorage.directorySize(dir)

    // ── Screenshot ──────────────────────────────────────────────────────────

    private suspend fun handleScreenshot(params: JSONObject): JSONObject {
        val scale = params.optDouble("scale", 1.0).toFloat()

        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity for screenshot")

        val bitmap = withContext(Dispatchers.Main) {
            captureActivityScreenshot(activity, scale)
        }

        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        bitmap.recycle()
        val pngBytes = baos.toByteArray()
        val base64 = android.util.Base64.encodeToString(pngBytes, android.util.Base64.NO_WRAP)

        return JSONObject().apply {
            put("base64", base64)
            put("size", pngBytes.size)
            put("encoding", "png")
        }
    }

    private suspend fun captureActivityScreenshot(activity: Activity, scale: Float): Bitmap =
        suspendCancellableCoroutine { cont ->
            val rootView = activity.window.decorView.rootView
            // Use PixelCopy for accurate capture on API 26+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val width = (rootView.width * scale).toInt().coerceAtLeast(1)
                val height = (rootView.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                android.view.PixelCopy.request(
                    activity.window, bitmap,
                    { result ->
                        if (result == android.view.PixelCopy.SUCCESS) {
                            cont.resume(bitmap)
                        } else {
                            // Fallback to canvas draw
                            cont.resume(canvasCapture(rootView, scale))
                        }
                    },
                    Handler(Looper.getMainLooper()),
                )
            } else {
                cont.resume(canvasCapture(rootView, scale))
            }
        }

    private fun canvasCapture(view: android.view.View, scale: Float): Bitmap {
        val width = (view.width * scale).toInt().coerceAtLeast(1)
        val height = (view.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        if (scale != 1f) canvas.scale(scale, scale)
        view.draw(canvas)
        return bitmap
    }

    // ── File System ─────────────────────────────────────────────────────────

    private fun handleLS(params: JSONObject): Any {
        val path = params.optString("path", "/")
        if (path.contains("..")) throw RPCException(-32602, "Invalid path: '..' not allowed")

        val recursive = params.optBoolean("recursive", false)
        val maxDepth = params.optInt("maxDepth", 3)

        val hostFile = PRootKernel.resolveHostPath(path)
            ?: throw RPCException(-32602, "Cannot resolve path: $path")

        if (!hostFile.isDirectory) throw RPCException(-32602, "Not a directory: $path")

        return if (recursive) {
            listRecursive(hostFile, path, maxDepth, 0)
        } else {
            listFlat(hostFile)
        }
    }

    /**
     * [diag] Raw list of a host-filesystem directory, bypassing the PRoot
     * bindMounts/rootfs resolver. Constrained to filesDir to avoid poking
     * at arbitrary paths. Use `minis-sessions` (default) to enumerate every
     * session's attachments/workspace/... directories and find files that
     * were written into a session we no longer have mounted.
     */
    private fun handleRawLS(params: JSONObject): Any {
        val subPath = params.optString("path", "minis-sessions")
        if (subPath.contains("..")) throw RPCException(-32602, "Invalid path: '..' not allowed")
        val recursive = params.optBoolean("recursive", true)
        val maxDepth = params.optInt("maxDepth", 4)

        val base = context.filesDir
        val target = if (subPath.isEmpty() || subPath == "/") base else File(base, subPath)
        if (!target.exists()) throw RPCException(-32602, "Not found: ${target.absolutePath}")
        if (!target.isDirectory) throw RPCException(-32602, "Not a directory: ${target.absolutePath}")

        val root = JSONObject()
        root.put("root", target.absolutePath)
        root.put("entries",
            if (recursive) listRecursive(target, target.absolutePath, maxDepth, 0)
            else listFlat(target)
        )
        return root
    }

    private fun listFlat(dir: File): JSONArray {
        val files = dir.listFiles() ?: return JSONArray()
        val array = JSONArray()
        for (file in files.sortedBy { it.name }) {
            array.put(JSONObject().apply {
                put("name", file.name)
                put("type", if (file.isDirectory) "directory" else "file")
                put("size", file.length())
                put("modified", file.lastModified())
            })
        }
        return array
    }

    private fun listRecursive(dir: File, virtualPath: String, maxDepth: Int, depth: Int): JSONArray {
        val array = JSONArray()
        val files = dir.listFiles()?.sortedBy { it.name } ?: return array
        for (file in files) {
            val childPath = if (virtualPath == "/") "/${file.name}" else "$virtualPath/${file.name}"
            val entry = JSONObject().apply {
                put("name", file.name)
                put("path", childPath)
                put("type", if (file.isDirectory) "directory" else "file")
                put("size", file.length())
                put("modified", file.lastModified())
            }
            if (file.isDirectory && depth < maxDepth) {
                entry.put("children", listRecursive(file, childPath, maxDepth, depth + 1))
            }
            array.put(entry)
        }
        return array
    }

    private fun handleReadFile(params: JSONObject): JSONObject {
        val path = params.optString("path")
        if (path.isEmpty()) throw RPCException(-32602, "Invalid params: 'path' is required")
        if (path.contains("..")) throw RPCException(-32602, "Invalid path: '..' not allowed")

        val hostFile = PRootKernel.resolveHostPath(path)
            ?: throw RPCException(-32602, "Cannot resolve path: $path")

        if (!hostFile.exists()) throw RPCException(-32602, "File not found: $path")

        val fileSize = hostFile.length()
        val offset = params.optLong("offset", 0)
        val limit = params.optInt("limit", 524_288)
        val forceBase64 = params.optBoolean("base64", false)

        val bytes = hostFile.inputStream().use { stream ->
            if (offset > 0) stream.skip(offset)
            stream.readNBytes(limit)
        }

        val isText = !forceBase64 && bytes.all {
            it in 0x09..0x0D || it in 0x20..0x7E || it.toInt() and 0xFF > 0x7F
        }

        return JSONObject().apply {
            put("size", fileSize)
            if (isText) {
                put("content", String(bytes, Charsets.UTF_8))
                put("encoding", "utf8")
            } else {
                put("content", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                put("encoding", "base64")
            }
            put("bytesRead", bytes.size)
            if (offset + bytes.size < fileSize) put("truncated", true)
        }
    }

    // ── Logging ──────────────────────────────────────────────────────────────

    private fun handleLogsList(): JSONObject {
        val files = AppLogger.listLogFiles()
        val array = JSONArray()
        for (file in files) {
            array.put(JSONObject().apply {
                put("name", file.name)
                put("size", file.length())
                put("modified", file.lastModified())
            })
        }
        return JSONObject().apply {
            put("totalSize", AppLogger.totalSize())
            put("files", array)
        }
    }

    private fun handleLogsRead(params: JSONObject): JSONObject {
        val name = params.optString("name")
        if (name.isEmpty()) throw RPCException(-32602, "Invalid params: 'name' is required")
        if (name.contains("/") || name.contains("..")) {
            throw RPCException(-32602, "Invalid params: 'name' must be a filename")
        }

        val content = AppLogger.readLog(name)
            ?: throw RPCException(-32602, "Log file not found: $name")

        val offset = params.optInt("offset", 0)
        val limit = params.optInt("limit", 524_288)
        val sliced = content.substring(
            offset.coerceAtMost(content.length),
            (offset + limit).coerceAtMost(content.length),
        )

        return JSONObject().apply {
            put("name", name)
            put("size", content.length)
            put("content", sliced)
            put("bytesRead", sliced.length)
            if (sliced.length < content.length - offset) put("truncated", true)
        }
    }

    // ── Crash reports ───────────────────────────────────────────────────────
    //
    // [T-android-debug-crashes] Crash triage over the debug server. Both crash
    // writers already drop files into filesDir/logs — ACRA's CrashFileSender
    // writes "crash-<stamp>.log" (Java/Kotlin) and NativeCrashHandler writes
    // "native-crash-<stamp>.log" — but reaching them previously meant knowing
    // that convention and shelling out through `adb run-as`, which is
    // unavailable to any client that isn't on a USB cable. A crashed app also
    // stops answering RPC, so the crash is exactly when the debug server is
    // least able to explain itself; these methods make the post-restart
    // "what just died?" question answerable in one call.

    private fun crashFiles(): List<File> {
        val dir = File(context.filesDir, "logs")
        if (!dir.isDirectory) return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && CRASH_NAME.matches(it.name) }
            .sortedByDescending { it.lastModified() }
    }

    private fun handleCrashList(params: JSONObject): JSONObject {
        val limit = params.optInt("limit", 20).coerceIn(1, 200)
        val files = crashFiles()
        val array = JSONArray()
        for (f in files.take(limit)) {
            array.put(JSONObject().apply {
                put("name", f.name)
                put("size", f.length())
                put("modified", f.lastModified())
                put("native", f.name.startsWith("native-crash-"))
                // First stack line is what triage keys on, so surface it here
                // and save a read call when scanning a list of crashes.
                put("summary", crashSummary(f))
            })
        }
        return JSONObject().apply {
            put("count", files.size)
            put("returned", array.length())
            put("crashes", array)
        }
    }

    /** Exception type + first app frame — enough to tell crashes apart. */
    private fun crashSummary(f: File): String = try {
        val lines = f.bufferedReader().useLines { seq -> seq.take(400).toList() }
        val i = lines.indexOfFirst { it.startsWith("--- Stack Trace ---") }
        val body = if (i >= 0) lines.drop(i + 1) else lines
        val head = body.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val frame = body.firstOrNull { it.trimStart().startsWith("at com.openminis") }
            ?.trim()?.removePrefix("at ").orEmpty()
        listOf(head, frame).filter { it.isNotEmpty() }.joinToString("  |  ")
    } catch (e: Exception) {
        "(unreadable: ${e.message})"
    }

    private fun handleCrashRead(params: JSONObject): JSONObject {
        val files = crashFiles()
        if (files.isEmpty()) throw RPCException(-32000, "No crash reports on device")
        // No name → newest, which is what "why did it just die?" wants.
        val name = params.optString("name").takeIf { it.isNotEmpty() }
        if (name != null && (name.contains("/") || name.contains(".."))) {
            throw RPCException(-32602, "Invalid params: 'name' must be a filename")
        }
        val file = if (name == null) files.first()
        else files.firstOrNull { it.name == name }
            ?: throw RPCException(-32602, "Crash report not found: $name")

        val full = file.readText()
        // Default to the stack only: the ACRA reports embed 200 logcat lines
        // plus a Build dump, so a whole file is ~27KB of which the useful part
        // is the first ~40 lines.
        val stackOnly = params.optBoolean("stackOnly", true)
        val content = if (!stackOnly) full else {
            val start = full.indexOf("--- Stack Trace ---")
            val end = full.indexOf("--- Logcat")
            when {
                start < 0 -> full
                end > start -> full.substring(0, end).trimEnd()
                else -> full.substring(0, minOf(full.length, start + 4000))
            }
        }
        val limit = params.optInt("limit", 262_144)
        val sliced = content.take(limit)
        return JSONObject().apply {
            put("name", file.name)
            put("modified", file.lastModified())
            put("native", file.name.startsWith("native-crash-"))
            put("fileSize", file.length())
            put("stackOnly", stackOnly)
            put("content", sliced)
            if (sliced.length < content.length) put("truncated", true)
        }
    }

    // ── Touch & Input Simulation ────────────────────────────────────────────

    private suspend fun handleTap(params: JSONObject): JSONObject {
        val x = params.optDouble("x", -1.0).toFloat()
        val y = params.optDouble("y", -1.0).toFloat()
        if (x < 0 || y < 0) throw RPCException(-32602, "Invalid params: 'x' and 'y' are required")

        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity")

        withContext(Dispatchers.Main) {
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            activity.dispatchTouchEvent(down)
            down.recycle()

            val up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, x, y, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            activity.dispatchTouchEvent(up)
            up.recycle()
        }

        return JSONObject().apply {
            put("ok", true)
            put("point", JSONObject().put("x", x).put("y", y))
        }
    }

    private suspend fun handleScroll(params: JSONObject): JSONObject {
        val x = params.optDouble("x", -1.0).toFloat()
        val y = params.optDouble("y", -1.0).toFloat()
        val deltaX = params.optDouble("deltaX", 0.0).toFloat()
        val deltaY = params.optDouble("deltaY", 0.0).toFloat()

        if (x < 0 || y < 0) throw RPCException(-32602, "Invalid params: 'x' and 'y' are required")
        if (deltaX == 0f && deltaY == 0f) throw RPCException(-32602, "At least one of deltaX/deltaY must be non-zero")

        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity")

        withContext(Dispatchers.Main) {
            val downTime = SystemClock.uptimeMillis()
            val steps = 10
            val stepDuration = 16L // ~60fps

            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            activity.dispatchTouchEvent(down)
            down.recycle()

            for (i in 1..steps) {
                val frac = i.toFloat() / steps
                val moveX = x + deltaX * frac
                val moveY = y + deltaY * frac
                val move = MotionEvent.obtain(downTime, downTime + i * stepDuration, MotionEvent.ACTION_MOVE, moveX, moveY, 0)
                move.source = InputDevice.SOURCE_TOUCHSCREEN
                activity.dispatchTouchEvent(move)
                move.recycle()
            }

            val up = MotionEvent.obtain(downTime, downTime + (steps + 1) * stepDuration, MotionEvent.ACTION_UP, x + deltaX, y + deltaY, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            activity.dispatchTouchEvent(up)
            up.recycle()
        }

        return JSONObject().apply {
            put("ok", true)
            put("start", JSONObject().put("x", x).put("y", y))
            put("delta", JSONObject().put("x", deltaX).put("y", deltaY))
        }
    }

    private suspend fun handleInputText(params: JSONObject): JSONObject {
        val text = params.optString("text")
        if (text.isEmpty()) throw RPCException(-32602, "Invalid params: 'text' is required")

        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity")

        // [T-debug-inputtext-mainthread] Instrumentation.sendStringSync throws
        // "This method can not be called from the main application thread" —
        // it BLOCKS waiting for the injected key events to be processed by the
        // main thread, so it must be dispatched off-main. Note it maps chars
        // through KeyCharacterMap, so only ASCII-typable text lands; CJK input
        // needs the IME path and is out of scope for this harness hook.
        withContext(Dispatchers.IO) {
            val instrumentation = android.app.Instrumentation()
            instrumentation.sendStringSync(text)
        }

        return JSONObject().apply {
            put("ok", true)
            put("length", text.length)
        }
    }

    /**
     * [T-android-debug-setclipboard] Put [text] on the system clipboard, from
     * inside the app's own process.
     *
     * Exists because there is no way to stage a clipboard from outside on a
     * modern device: API 33 has no `cmd clipboard`, `service call clipboard`
     * needs an undocumented parcel layout, and since Android 10 only the
     * foreground app may write the clipboard at all — which is precisely what
     * this handler is.
     *
     * It is the missing half of a real paste test. [handleInputText] types
     * character by character, so it can never exercise a paste: the composer's
     * fold logic keys on ONE large insertion, and per-character input looks
     * exactly like ordinary typing. With the clipboard staged here, a plain
     * `input keyevent PASTE` produces a genuine single-shot insertion — the
     * same event a user's long-press → Paste generates.
     *
     * Debug-only by construction: DebugServer is started under
     * `if (BuildConfig.DEBUG)` in MinisApp, so no release build carries this.
     */
    /**
     * [T-android-voice-asr-stall-skip] DEBUG-only: queue a WAV as a failed
     * voice utterance, exactly as the provider engine does when a take's
     * transcription fails. The voice panel then shows its Retry prompt, and
     * Retry runs the real provider fail-over (stall offer, Switch Model, System
     * fallback) on that audio — the only way to exercise it on a device with no
     * one to speak into the microphone.
     */
    private fun handleVoiceInjectFailedAudio(params: JSONObject): JSONObject {
        val b64 = params.optString("wavBase64", "").ifEmpty { throw RPCException(-32602, "Missing 'wavBase64' param") }
        val wav = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        val seconds = ((wav.size - 44).coerceAtLeast(0) / 32000.0)
        com.openminis.app.ui.chat.voice.VoiceSendGate.recordFailure(
            com.openminis.app.speech.SpeechRecognitionManager.FailedAudio(
                wav = wav,
                seconds = seconds,
                error = com.openminis.app.speech.RecognitionError.NETWORK,
                message = "injected by debug.voice.injectFailedAudio",
                engineId = params.optString("engineId", "provider"),
            ),
            com.openminis.app.ui.chat.voice.VoiceSendGate.generation,
        )
        return JSONObject().put("ok", true).put("bytes", wav.size).put("seconds", seconds)
    }

    private suspend fun handleSetClipboard(params: JSONObject): JSONObject {
        val text = params.optString("text")
        if (text.isEmpty()) throw RPCException(-32602, "Invalid params: 'text' is required")
        val label = params.optString("label", "minis-debug")

        // ClipboardManager.setPrimaryClip must run on a Looper thread, and the
        // write is only honoured while this app holds focus.
        withContext(Dispatchers.Main) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        }

        // Read it straight back: a silent no-op here (focus lost, OEM policy)
        // would otherwise show up much later as a paste that pasted nothing.
        val readBack = withContext(Dispatchers.Main) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.primaryClip?.getItemAt(0)?.text?.length ?: -1
        }

        return JSONObject().apply {
            put("ok", readBack == text.length)
            put("length", text.length)
            put("clipboardLength", readBack)
        }
    }

    // ── LLM Request Tracking ──────────────────────────────────────────────

    private fun handleLLMRequests(params: JSONObject): JSONObject {
        if (params.optBoolean("fullLast", false)) {
            return LLMRequestLog.lastFullJSON() ?: JSONObject().put("error", "no request captured yet")
        }
        val last = if (params.has("last") && !params.isNull("last")) params.optInt("last", -1).takeIf { it > 0 } else null
        val raw = LLMRequestLog.toJSON(last)
        if (!params.optBoolean("formatted", false)) return raw
        return JSONObject().apply {
            put("count", raw.optInt("count", 0))
            put("text", formatLLMRequestsText(raw))
        }
    }

    private fun formatLLMRequestsText(raw: JSONObject): String {
        val arr = raw.optJSONArray("requests") ?: return ""
        val sb = StringBuilder()
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val provider = e.optString("provider")
            val timestamp = e.optLong("timestamp", 0)
            val durMs = e.optLong("durationMs", 0)
            val status = e.optInt("responseStatusCode", 0)
            val usage = e.optJSONObject("usage")
            val usageStr = if (usage != null) {
                buildString {
                    append("in:").append(usage.optInt("inputTokens", usage.optInt("input_tokens", 0)))
                    append(" out:").append(usage.optInt("outputTokens", usage.optInt("output_tokens", 0)))
                    val cr = usage.optInt("cacheReadTokens", usage.optInt("cache_read_tokens", 0))
                    if (cr > 0) append(" cache_read:").append(cr)
                }
            } else ""
            sb.append("// --- #").append(i + 1).append(' ')
                .append(provider).append(' ')
                .append(if (timestamp > 0) ts.format(java.util.Date(timestamp)) else "")
                .append(' ').append(durMs).append("ms")
                .append(" HTTP ").append(status)
                .append(if (usageStr.isNotEmpty()) " | $usageStr" else "")
                .append(" ---\n")
            sb.append("// ").append(e.optString("requestMethod"))
                .append(' ').append(e.optString("requestURL")).append('\n')
            val body = e.opt("requestBody")
            if (body != null) sb.append(body.toString()).append('\n')
            val resp = e.optString("responseBody", "")
            if (resp.isNotEmpty()) {
                sb.append("// --- Response (").append(resp.length).append(" chars) ---\n")
                sb.append(resp).append('\n')
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * Aggregate "agent trace" view — alias for `debug.llmRequests` exposed
     * under the iOS-compatible name. `last=true` returns just the most recent
     * trace object instead of an array (mirrors iOS docs).
     */
    private fun handleAgentTrace(params: JSONObject): JSONObject {
        val onlyLast = params.optBoolean("last", false)
        if (onlyLast) {
            val tail = LLMRequestLog.getLast(1)
            if (tail.isEmpty()) return JSONObject().put("traces", org.json.JSONArray())
            // Reuse the same JSON shape `debug.llmRequests` produces so callers
            // get a consistent envelope (the underlying Entry → JSON mapping
            // lives in LLMRequestLog).
            val full = LLMRequestLog.toJSON(1)
            val arr = full.optJSONArray("requests")
            return arr?.optJSONObject(0) ?: JSONObject().put("traces", org.json.JSONArray())
        }
        val full = LLMRequestLog.toJSON(null)
        return JSONObject().put("traces", full.optJSONArray("requests") ?: org.json.JSONArray())
    }

    private fun handleLogsSetEnabled(params: JSONObject): JSONObject {
        if (!params.has("enabled")) throw RPCException(-32602, "Missing 'enabled' param")
        val enabled = params.optBoolean("enabled", false)
        AppLogger.setEnabled(context, enabled)
        return JSONObject().put("enabled", AppLogger.isEnabled(context))
    }

    // ── Response helpers ────────────────────────────────────────────────────

    private fun metaObject(): JSONObject {
        return JSONObject().apply {
            put("app", "MinisApp")
            put("version", BuildConfig.VERSION_NAME)
            put("build", BuildConfig.VERSION_CODE)
            // [T-android-about-build-date] versionCode is static across
            // rebuilds; this is what tells builds apart (iOS `buildDate`).
            put("buildDate", com.openminis.app.AppBuildInfo.buildDate(context))
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("platform", "android")
        }
    }

    private fun successResponse(result: Any, id: Any?): String {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id ?: JSONObject.NULL)
            put("result", result)
            put("_meta", metaObject())
        }.toString()
    }

    fun errorJSON(code: Int, message: String): String {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", JSONObject.NULL)
            put("error", JSONObject().apply {
                put("code", code)
                put("message", message)
            })
            put("_meta", metaObject())
        }.toString()
    }

    private fun errorResponse(code: Int, message: String, id: Any?): String {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id ?: JSONObject.NULL)
            put("error", JSONObject().apply {
                put("code", code)
                put("message", message)
            })
            put("_meta", metaObject())
        }.toString()
    }

    private fun handleFetch(params: JSONObject): JSONObject {
        val url = params.optString("url").ifEmpty { throw RPCException(-32602, "Missing 'url' param") }
        val result = JSONObject()

        // Test 1: DNS
        try {
            val host = java.net.URI(url).host
            val addrs = java.net.InetAddress.getAllByName(host)
            result.put("dns", addrs.joinToString(", ") { it.hostAddress })
        } catch (e: Exception) {
            result.put("dns_error", "${e.javaClass.simpleName}: ${e.message}")
        }

        // Test 2: HttpURLConnection
        try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.requestMethod = "GET"
            result.put("httpurlconn_status", conn.responseCode)
            conn.disconnect()
        } catch (e: Exception) {
            result.put("httpurlconn_error", "${e.javaClass.simpleName}: ${e.message}")
        }

        // Test 3: OkHttp
        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val req = okhttp3.Request.Builder().url(url).get().build()
            val resp = client.newCall(req).execute()
            result.put("okhttp_status", resp.code)
            resp.close()
        } catch (e: Exception) {
            result.put("okhttp_error", "${e.javaClass.simpleName}: ${e.message}")
        }

        // Test 4: Proxy info
        try {
            val selector = java.net.ProxySelector.getDefault()
            val proxies = selector.select(java.net.URI(url))
            result.put("proxies", proxies.joinToString(", ") { it.toString() })
        } catch (e: Exception) {
            result.put("proxy_error", e.message)
        }

        return result
    }

    // ── Shell Execute (PRoot sandbox) ────────────────────────────────────────

    /**
     * Run a command inside the PRoot sandbox for the given session and return
     * `{ output, exit_code }`. Mirrors iOS `debug.shellExecute`. Debug-only;
     * meant for integration-test harnesses that need to drive shell tools
     * (`minis-browser-use`, `minis-open`, …) without going through the agent.
     *
     * Params:
     *   command  (string, required) — command line to run under /bin/sh -c.
     *   session  (string)           — session id. Defaults to "debug-rpc" so
     *                                 callers don't accidentally mutate a
     *                                 real chat's shell state.
     *   timeout  (int)              — timeout in seconds. Default 60.
     *   strategy (string)           — [T-android-shell-fresh-process] step 3:
     *                                 "warm" | "fresh". Runs this ONE command
     *                                 under the named execution path, leaving
     *                                 the global default untouched, so a
     *                                 harness can drive the same command down
     *                                 both and diff the results. Omitted =
     *                                 whatever the app is configured to use.
     */
    private suspend fun handleShellExecute(params: JSONObject): JSONObject {
        val command = params.optString("command").ifEmpty {
            throw RPCException(-32602, "Missing 'command' param")
        }
        val session = params.optString("session", "debug-rpc").ifEmpty { "debug-rpc" }
        val timeoutSec = params.optInt("timeout", 60).coerceIn(1, 900)
        // [T-android-shell-fresh-process] Per-call strategy override. Restored
        // in `finally` below so a comparison run cannot leave the app on the
        // wrong path if the command throws.
        val requested = params.optString("strategy", "").lowercase()
        val previousStrategy = ExecutionCoordinator.executionStrategy
        when (requested) {
            "fresh" -> ExecutionCoordinator.executionStrategy =
                com.openminis.app.sandbox.ShellExecutionStrategy.FRESH_PROCESS
            "warm" -> ExecutionCoordinator.executionStrategy =
                com.openminis.app.sandbox.ShellExecutionStrategy.WARM_SHELL
            "" -> Unit
            else -> throw RPCException(-32602, "strategy must be 'warm' or 'fresh'")
        }

        // Mirror ChatViewModel's terminal lineCallback: scan raw lines for
        // OSC MinisOpenURL markers before TerminalSanitizer strips them and
        // hand captured URLs to the broker so test harnesses driving
        // `minis-open` via this RPC trigger the same in-app preview flow as
        // real chat shell output.
        val capturedUrls = mutableListOf<String>()
        val result = try {
            ExecutionCoordinator.execute(
                sessionId = session,
                command = command,
                timeout = timeoutSec * 1000L,
                lineCallback = { rawLine ->
                    val (_, urls) = com.openminis.app.terminal.MinisUrlMarker.extract(rawLine)
                    capturedUrls.addAll(urls)
                },
            )
        } catch (e: Exception) {
            throw RPCException(-32000, "Shell execute failed: ${e.message}")
        } finally {
            // Always put the global strategy back, success or failure.
            ExecutionCoordinator.executionStrategy = previousStrategy
        }
        for (raw in capturedUrls) {
            com.openminis.app.terminal.MinisOpenUrlBroker.offer(raw)
        }
        return JSONObject()
            .put("output", result.output)
            .put("exit_code", result.exitCode)
            .put("session", session)
            .put("strategy", requested.ifEmpty { previousStrategy.name.lowercase() })
            .put("duration_ms", result.durationMs)
            .put("queue_note", result.queueNote ?: JSONObject.NULL)
    }

    // ── Update checker (T33) — exposed only via DebugRPC so the e2e flow can
    // be exercised before the production UI entry point lands.
    private suspend fun handleUpdateCheck(): JSONObject {
        // [T-update-channels] A manual check: bypasses the throttle, writes the cache.
        return when (val r = com.openminis.app.data.UpdateChecker.check(context, reason = "rpc")) {
            is com.openminis.app.data.UpdateChecker.CheckResult.UpdateAvailable -> JSONObject()
                .put("status", "update_available")
                .put("tag_name", r.tagName)
                .put("version_name", r.versionName)
                .put("version_code", r.versionCode ?: JSONObject.NULL)
                .put("sha256", r.sha256 ?: JSONObject.NULL)
                .put("release_name", r.releaseName)
                .put("apk_url", r.apkUrl)
                .put("apk_size", r.apkSizeBytes)
                .put("changelog", r.changelog)
            com.openminis.app.data.UpdateChecker.CheckResult.UpToDate ->
                JSONObject().put("status", "up_to_date")
            com.openminis.app.data.UpdateChecker.CheckResult.NoReleaseAvailable ->
                JSONObject().put("status", "no_release")
            is com.openminis.app.data.UpdateChecker.CheckResult.NoApkAsset ->
                JSONObject().put("status", "no_apk_asset").put("tag_name", r.tagName)
            com.openminis.app.data.UpdateChecker.CheckResult.Forbidden ->
                JSONObject().put("status", "forbidden")
            com.openminis.app.data.UpdateChecker.CheckResult.NetworkUnreachable ->
                JSONObject().put("status", "network_unreachable")
            is com.openminis.app.data.UpdateChecker.CheckResult.Error ->
                JSONObject().put("status", "error").put("message", r.message)
        }
    }

    private suspend fun handleUpdateDownload(params: JSONObject): JSONObject {
        val url = params.optString("url").ifEmpty {
            throw RPCException(-32602, "Missing 'url' parameter")
        }
        return when (val r = com.openminis.app.data.UpdateChecker.download(context, url)) {
            is com.openminis.app.data.UpdateChecker.DownloadResult.Success -> JSONObject()
                .put("status", "ok")
                .put("path", r.file.absolutePath)
                .put("size", r.file.length())
            is com.openminis.app.data.UpdateChecker.DownloadResult.Error -> JSONObject()
                .put("status", "error")
                .put("message", r.message)
        }
    }

    private fun handleUpdateInstall(params: JSONObject): JSONObject {
        val path = params.optString("path").ifEmpty {
            throw RPCException(-32602, "Missing 'path' parameter")
        }
        val file = java.io.File(path)
        if (!file.exists()) throw RPCException(-32000, "APK not found: $path")
        val canInstall = com.openminis.app.data.UpdateChecker.canInstall(context)
        return try {
            com.openminis.app.data.UpdateChecker.installApk(context, file)
            JSONObject().put("status", "launched").put("can_install", canInstall)
        } catch (e: Exception) {
            JSONObject()
                .put("status", "error")
                .put("can_install", canInstall)
                .put("message", e.message ?: e.javaClass.simpleName)
        }
    }

    // ── UI introspection (Layer A) ───────────────────────────────────────────

    private suspend fun handleViewTree(params: JSONObject): Any {
        val maxDepth = params.optInt("maxDepth", 50).coerceIn(1, 200)
        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity")
        val trees = DebugInspectorMethods.viewTree(activity, maxDepth)
        return if (trees.length() == 1) trees.get(0) else trees
    }

    private suspend fun handleSearch(params: JSONObject): JSONArray {
        val keyword = params.optString("keyword").ifEmpty {
            throw RPCException(-32602, "Invalid params: 'keyword' is required")
        }
        val scope = params.optString("scope", "all").ifEmpty { "all" }
        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity")
        return DebugInspectorMethods.search(activity, keyword, scope)
    }

    private suspend fun handleInspect(params: JSONObject): JSONObject {
        val address = params.optString("address").ifEmpty {
            throw RPCException(-32602, "Invalid params: 'address' is required")
        }
        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity")
        return DebugInspectorMethods.inspect(activity, address)
            ?: JSONObject().put("error", "Node not found at address $address (call viewTree/search first)")
    }

    private suspend fun handleHighlight(params: JSONObject): JSONObject {
        val address = params.optString("address").ifEmpty {
            throw RPCException(-32602, "Invalid params: 'address' is required")
        }
        val color = params.optString("color", "red").ifEmpty { "red" }
        val duration = params.optDouble("duration", 2.0)
        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity")
        val ok = DebugInspectorMethods.highlight(activity, address, color, duration)
        return JSONObject().put("ok", ok)
    }

    /**
     * Stub for parity with iOS `debug.cloudSync`. Android does not have an
     * iCloud-equivalent built into MinisApp, so we report disabled and an
     * empty device list rather than fail the call. Lets cross-platform
     * harnesses skip the check uniformly.
     */
    private fun handleCloudSync(): JSONObject {
        return JSONObject().apply {
            put("enabled", false)
            put("status", "unsupported")
            put("reason", "Android build does not include iCloud sync")
            put("devices", JSONArray())
            put("deviceCount", 0)
        }
    }

    /**
     * Write a file into the proot-mounted Linux namespace. Resolves the
     * Linux path through PRootKernel.resolveHostPath, creates parent dirs,
     * writes the bytes, and best-effort applies the requested mode bits.
     * No fakefs registration is needed — proot reads the host directly,
     * so the guest sees the file the moment it lands on disk.
     */
    private fun handleWriteFile(params: JSONObject): JSONObject {
        val path = params.optString("path").ifEmpty {
            throw RPCException(-32602, "Invalid params: 'path' is required")
        }
        if (path.contains("..")) throw RPCException(-32602, "Invalid path: '..' not allowed")
        if (!params.has("content")) throw RPCException(-32602, "Invalid params: 'content' is required")
        val content = params.optString("content")
        val encoding = params.optString("encoding", "utf8").lowercase().ifEmpty { "utf8" }
        val overwrite = params.optBoolean("overwrite", true)
        val modeStr = params.optString("mode", "0644")

        // Pre-boot fallback: resolveHostPath returns null until proot boots
        // (it lazy-initializes its RootfsManager). Test harnesses commonly
        // want to stage files BEFORE booting, so route through the rootfs
        // dir directly when the kernel isn't ready yet.
        val hostFile = PRootKernel.resolveHostPath(path) ?: run {
            val rootfsDir = com.openminis.app.sandbox.RootfsManager.getInstance(context).rootfsDir
            File(rootfsDir, path.removePrefix("/"))
        }

        if (hostFile.exists() && !overwrite) {
            throw RPCException(-32000, "File exists and overwrite=false: $path")
        }

        val bytes = when (encoding) {
            "utf8" -> content.toByteArray(Charsets.UTF_8)
            "base64" -> try {
                android.util.Base64.decode(content, android.util.Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                throw RPCException(-32602, "Invalid base64 content: ${e.message}")
            }
            else -> throw RPCException(-32602, "Invalid encoding: '$encoding' (must be utf8 or base64)")
        }

        hostFile.parentFile?.mkdirs()
        hostFile.writeBytes(bytes)

        // Best-effort mode application — on most Android filesystems we can
        // only set readable/writable/executable via java.io.File flags.
        runCatching {
            val modeOct = if (modeStr.startsWith("0")) modeStr.toInt(8) else modeStr.toInt(10)
            val ownerExec = (modeOct and 0b001_000_000) != 0
            val ownerWrite = (modeOct and 0b010_000_000) != 0
            val ownerRead = (modeOct and 0b100_000_000) != 0
            hostFile.setReadable(ownerRead, true)
            hostFile.setWritable(ownerWrite, true)
            hostFile.setExecutable(ownerExec, true)
        }

        return JSONObject().apply {
            put("ok", true)
            put("path", path)
            put("hostPath", hostFile.absolutePath)
            put("size", bytes.size)
        }
    }

    private suspend fun handleScreenshotCapture(params: JSONObject): JSONObject {
        val label = params.optString("label", "")
        val scale = params.optDouble("scale", 0.5).toFloat().coerceIn(0.05f, 1.0f)
        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity")
        val entry = DebugScreenshotRing.capture(activity, label, scale)
        return DebugScreenshotRing.summary(entry)
    }

    private fun handleScreenshotGet(params: JSONObject): JSONObject {
        val id = if (params.has("id") && !params.isNull("id")) params.optInt("id") else null
        val entry = DebugScreenshotRing.get(id)
            ?: throw RPCException(-32602, "Screenshot not found${if (id != null) " for id=$id" else " (buffer empty)"}")
        return JSONObject().apply {
            put("id", entry.id)
            put("label", entry.label)
            put("timestamp", entry.timestamp)
            put("base64", android.util.Base64.encodeToString(entry.pngBytes, android.util.Base64.NO_WRAP))
            put("size", entry.pngBytes.size)
            put("encoding", "png")
        }
    }

    /**
     * Dump the live OffloadPermissionManager registry — one entry per tool with
     * the resolved level (default + persisted overrides applied). Used to
     * verify T34/T35 alignment without driving the Settings UI.
     */
    private fun handlePermissionsList(): JSONObject {
        val arr = org.json.JSONArray()
        for (tool in com.openminis.app.offload.OffloadPermissionManager.toolRegistry) {
            arr.put(JSONObject()
                .put("toolName", tool.toolName)
                .put("displayName", tool.displayName)
                .put("category", tool.category.name)
                .put("defaultLevel", tool.defaultLevel.name)
                .put("currentLevel", com.openminis.app.offload.OffloadPermissionManager.getLevel(tool.toolName).name)
                .put("showInSettings", tool.showInSettings))
        }
        return JSONObject().put("tools", arr).put("count", arr.length())
    }

    /**
     * T344: Direct invocation of [com.openminis.app.sandbox.offload.ShizukuOffloadHandler]
     * for e2e harnesses. Bypasses the agent loop so debug clients can verify Shizuku
     * CLI behavior without driving a chat. DEBUG-build only — gated in [dispatch].
     *
     * Params (one of):
     *   - {"args": ["exec", "id"]}            — argv past `android-shizuku-cli`
     *   - {"command": "exec id"}              — single string, whitespace-split
     */
    private fun handleShizukuExec(params: JSONObject): JSONObject {
        val argvTail: List<String> = when {
            params.has("args") -> {
                val arr = params.optJSONArray("args")
                    ?: throw RPCException(-32602, "args must be an array of strings")
                List(arr.length()) { arr.optString(it) }
            }
            params.has("command") -> {
                params.optString("command").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            }
            else -> throw RPCException(-32602, "Missing 'args' (array) or 'command' (string)")
        }
        AppLogger.info("DebugRPC", "debug.shizuku.exec argv=${argvTail.joinToString(" ")}")
        val handler = com.openminis.app.sandbox.offload.ShizukuOffloadHandler(context)
        // ShizukuOffloadHandler.handle() drops argv[0]; prepend the CLI name so the
        // tail aligns with what `android-shizuku-cli` would receive in-shell.
        val request = com.openminis.app.sandbox.NativeOffloadRequest(
            pid = -1,
            argv = listOf("android-shizuku-cli") + argvTail,
            env = emptyMap(),
            cwd = "/",
            sessionId = null,
        )
        val result = handler.handle(request)
        return JSONObject().apply {
            put("exitCode", result.exitCode)
            put("output", result.output)
            put("argv", JSONArray(argvTail))
        }
    }

    /**
     * Direct invocation of [com.openminis.app.sandbox.offload.ModelUseOffloadHandler]
     * for e2e harnesses. Mirrors [handleShizukuExec]; lets callers exercise the
     * `minis-model-use` CLI without going through a real Alpine shell prompt.
     * DEBUG-only.
     */
    private fun handleModelUseExec(params: JSONObject): JSONObject {
        val argvTail: List<String> = when {
            params.has("args") -> {
                val arr = params.optJSONArray("args")
                    ?: throw RPCException(-32602, "args must be an array of strings")
                List(arr.length()) { arr.optString(it) }
            }
            params.has("command") -> {
                params.optString("command").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            }
            else -> throw RPCException(-32602, "Missing 'args' (array) or 'command' (string)")
        }

        // Optional `input` blob: write to host /tmp and inject `--input <linuxPath>`
        // so the handler reads it via readLinuxPath() exactly like a real shell
        // invocation would. We resolve the host path via PRootKernel to honour
        // the same rootfs layout the offload server uses.
        val finalArgv: List<String> = if (params.has("input")) {
            val inputBlob = params.optString("input", "")
            val linuxPath = "/tmp/.debug-modeluse-input-${System.currentTimeMillis()}.json"
            val hostFile = com.openminis.app.sandbox.PRootKernel.resolveHostPath(linuxPath)
                ?: throw RPCException(-32603, "cannot resolve $linuxPath under rootfs")
            hostFile.parentFile?.mkdirs()
            hostFile.writeText(inputBlob)
            argvTail + listOf("--input", linuxPath)
        } else argvTail

        AppLogger.info("DebugRPC", "debug.modelUse.exec argv=${finalArgv.joinToString(" ")}")
        val app = context.applicationContext as com.openminis.app.MinisApp
        val handler = com.openminis.app.sandbox.offload.ModelUseOffloadHandler(context, app.providerRepository)
        val request = com.openminis.app.sandbox.NativeOffloadRequest(
            pid = -1,
            argv = listOf("minis-model-use") + finalArgv,
            env = emptyMap(),
            cwd = "/",
            sessionId = null,
        )
        val result = handler.handle(request)
        return JSONObject().apply {
            put("exitCode", result.exitCode)
            put("output", result.output)
            put("argv", JSONArray(finalArgv))
        }
    }

    /**
     * [T-android-calendar-update-all-day] Direct invocation of
     * [com.openminis.app.sandbox.offload.CalendarOffloadHandler] for e2e
     * harnesses, like [handleSessionsExec]. DEBUG-only. Writes go to the
     * device's real calendars — callers delete what they create.
     */
    private fun handleCalendarExec(params: JSONObject): JSONObject {
        val argvTail: List<String> = when {
            params.has("args") -> {
                val arr = params.optJSONArray("args")
                    ?: throw RPCException(-32602, "args must be an array of strings")
                List(arr.length()) { arr.optString(it) }
            }
            params.has("command") -> {
                params.optString("command").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            }
            else -> throw RPCException(-32602, "Missing 'args' (array) or 'command' (string)")
        }
        AppLogger.info("DebugRPC", "debug.calendar.exec argv=${argvTail.joinToString(" ")}")
        if (argvTail.firstOrNull() == "debug-local") return calendarDebugLocal(argvTail.drop(1))
        val handler = com.openminis.app.sandbox.offload.CalendarOffloadHandler(context.applicationContext)
        val request = com.openminis.app.sandbox.NativeOffloadRequest(
            pid = -1,
            argv = listOf("android-calendar") + argvTail,
            env = emptyMap(),
            cwd = "/",
            sessionId = null,
        )
        val result = handler.handle(request)
        return JSONObject().apply {
            put("exitCode", result.exitCode)
            put("output", result.output)
            put("argv", JSONArray(argvTail))
        }
    }

    /**
     * Test fixtures for [handleCalendarExec], never reachable from the CLI:
     *  - `create`  — add a device-only calendar (ACCOUNT_TYPE_LOCAL, never
     *    syncs) named "Minis Debug (local)"; returns its id.
     *  - `delete`  — remove it; the provider deletes its events with it.
     *  - `raw <event_id>` — the stored Events row (DTSTART / DTEND / ALL_DAY /
     *    EVENT_TIMEZONE / RRULE), to check what the provider actually kept.
     *  - `legacy-allday <calendar_id> <YYYY-MM-DD>` — insert an all-day event
     *    the way create did before T-android-calendar-update-all-day (LOCAL
     *    midnight .. 23:59:59 with EVENT_TIMEZONE=UTC), to show where the
     *    provider puts it.
     */
    private fun calendarDebugLocal(argv: List<String>): JSONObject {
        val cr = context.contentResolver
        val account = "minis-debug"
        val syncUri = android.provider.CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(android.provider.CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(android.provider.CalendarContract.Calendars.ACCOUNT_NAME, account)
            .appendQueryParameter(android.provider.CalendarContract.Calendars.ACCOUNT_TYPE, android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val out = JSONObject().put("argv", JSONArray(argv))
        when (argv.firstOrNull()) {
            "create" -> {
                val v = android.content.ContentValues().apply {
                    put(android.provider.CalendarContract.Calendars.ACCOUNT_NAME, account)
                    put(android.provider.CalendarContract.Calendars.ACCOUNT_TYPE, android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL)
                    put(android.provider.CalendarContract.Calendars.NAME, "minis_debug_local")
                    put(android.provider.CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Minis Debug (local)")
                    put(android.provider.CalendarContract.Calendars.CALENDAR_COLOR, 0xFF888888.toInt())
                    put(android.provider.CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, android.provider.CalendarContract.Calendars.CAL_ACCESS_OWNER)
                    put(android.provider.CalendarContract.Calendars.OWNER_ACCOUNT, account)
                    put(android.provider.CalendarContract.Calendars.VISIBLE, 1)
                    put(android.provider.CalendarContract.Calendars.SYNC_EVENTS, 1)
                    put(android.provider.CalendarContract.Calendars.CALENDAR_TIME_ZONE, java.util.TimeZone.getDefault().id)
                }
                val uri = cr.insert(syncUri, v)
                out.put("exitCode", if (uri != null) 0 else 1).put("calendar_id", uri?.lastPathSegment ?: JSONObject.NULL)
            }
            "delete" -> {
                val n = cr.delete(
                    syncUri,
                    "${android.provider.CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${android.provider.CalendarContract.Calendars.ACCOUNT_TYPE}=?",
                    arrayOf(account, android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL),
                )
                out.put("exitCode", 0).put("deleted_calendars", n)
            }
            "raw" -> {
                val id = argv.getOrNull(1)?.toLongOrNull() ?: throw RPCException(-32602, "raw <event_id>")
                cr.query(
                    android.provider.CalendarContract.Events.CONTENT_URI.buildUpon().appendPath(id.toString()).build(),
                    arrayOf(
                        android.provider.CalendarContract.Events.DTSTART,
                        android.provider.CalendarContract.Events.DTEND,
                        android.provider.CalendarContract.Events.ALL_DAY,
                        android.provider.CalendarContract.Events.EVENT_TIMEZONE,
                        android.provider.CalendarContract.Events.RRULE,
                        android.provider.CalendarContract.Events.TITLE,
                        android.provider.CalendarContract.Events.CALENDAR_ID,
                    ),
                    null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val utc = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                        out.put("exitCode", 0)
                            .put("dtstart", c.getLong(0)).put("dtstart_utc", utc.format(java.util.Date(c.getLong(0))))
                            .put("dtend", if (c.isNull(1)) JSONObject.NULL else c.getLong(1))
                            .put("dtend_utc", if (c.isNull(1)) JSONObject.NULL else utc.format(java.util.Date(c.getLong(1))))
                            .put("all_day", c.getInt(2)).put("event_timezone", c.getString(3) ?: JSONObject.NULL)
                            .put("rrule", c.getString(4) ?: JSONObject.NULL).put("title", c.getString(5) ?: "")
                            .put("calendar_id", c.getLong(6))
                    } else out.put("exitCode", 1).put("error", "not found")
                }
            }
            "legacy-allday" -> {
                val calId = argv.getOrNull(1)?.toLongOrNull() ?: throw RPCException(-32602, "legacy-allday <calendar_id> <YYYY-MM-DD>")
                val day = com.openminis.app.sandbox.offload.CalendarOffloadHandler.parseDate(argv.getOrNull(2) ?: "")
                    ?: throw RPCException(-32602, "legacy-allday <calendar_id> <YYYY-MM-DD>")
                val (s0, e0) = com.openminis.app.sandbox.offload.CalendarOffloadHandler.allDayBounds(day, day)
                val v = android.content.ContentValues().apply {
                    put(android.provider.CalendarContract.Events.CALENDAR_ID, calId)
                    put(android.provider.CalendarContract.Events.TITLE, "Minis legacy all-day probe")
                    put(android.provider.CalendarContract.Events.DTSTART, s0)
                    put(android.provider.CalendarContract.Events.DTEND, e0)
                    put(android.provider.CalendarContract.Events.ALL_DAY, 1)
                    put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                }
                val uri = cr.insert(android.provider.CalendarContract.Events.CONTENT_URI, v)
                out.put("exitCode", if (uri != null) 0 else 1).put("event_id", uri?.lastPathSegment ?: JSONObject.NULL)
            }
            else -> throw RPCException(-32602, "debug-local create | delete | raw <id> | legacy-allday <cal> <date>")
        }
        return out
    }

    /**
     * [T-android-sessions-cli-full] Direct invocation of
     * [com.openminis.app.sandbox.offload.SessionsOffloadHandler] for e2e
     * harnesses. Mirrors [handleModelUseExec]; lets callers exercise the
     * `minis-sessions-cli` CLI (list / search / messages, incl. --full)
     * without going through a real Alpine shell prompt. DEBUG-only.
     */
    private fun handleSessionsExec(params: JSONObject): JSONObject {
        val argvTail: List<String> = when {
            params.has("args") -> {
                val arr = params.optJSONArray("args")
                    ?: throw RPCException(-32602, "args must be an array of strings")
                List(arr.length()) { arr.optString(it) }
            }
            params.has("command") -> {
                params.optString("command").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            }
            else -> throw RPCException(-32602, "Missing 'args' (array) or 'command' (string)")
        }

        AppLogger.info("DebugRPC", "debug.sessions.exec argv=${argvTail.joinToString(" ")}")
        val app = context.applicationContext as com.openminis.app.MinisApp
        val handler = com.openminis.app.sandbox.offload.SessionsOffloadHandler(app.chatRepository)
        val request = com.openminis.app.sandbox.NativeOffloadRequest(
            pid = -1,
            argv = listOf("minis-sessions-cli") + argvTail,
            env = emptyMap(),
            cwd = "/",
            sessionId = null,
        )
        val result = handler.handle(request)
        return JSONObject().apply {
            put("exitCode", result.exitCode)
            put("output", result.output)
            put("argv", JSONArray(argvTail))
        }
    }

    /**
     * [T-minis-config-provider-add] DEBUG-only minis-config invocation
     * that BYPASSES the user-confirmation gate. Targets the same code
     * path the offload CLI hits (ConfigBridge.performWriteBatch /
     * readField / auditList), so harnesses can verify add / set / get
     * end-to-end without driving the in-app confirm dialog.
     *
     * Params:
     *   - `subcommand` (required): "set" | "get" | "audit-list"
     *   - subcommand=set:    `path` (string) + `value_json` (string)
     *   - subcommand=get:    `path` (string)
     *   - subcommand=audit-list: optional `limit` (int)
     */
    private fun handleMinisConfigExec(params: JSONObject): JSONObject {
        val sub = params.optString("subcommand", "").takeIf { it.isNotEmpty() }
            ?: throw RPCException(
                -32602,
                "Missing 'subcommand' — one of: set, get, topics, topic-help, audit-list",
            )

        return when (sub) {
            "set" -> {
                // Accepts EITHER a single `path` + `value_json`, or `items:
                // [{path, value_json}, …]` for a multi-path batch. The batch form
                // matters because performWriteBatch applies its items as ONE unit
                // — writing two paths as two calls cannot exercise that.
                val items = params.optJSONArray("items")?.also { arr ->
                    if (arr.length() == 0) {
                        throw RPCException(-32602, "'items' must not be empty for subcommand=set")
                    }
                    for (i in 0 until arr.length()) {
                        val it = arr.optJSONObject(i)
                            ?: throw RPCException(-32602, "items[$i] must be an object")
                        if (it.optString("path", "").isEmpty()) {
                            throw RPCException(-32602, "Missing 'path' in items[$i]")
                        }
                        if (it.optString("value_json", "").isEmpty()) {
                            throw RPCException(-32602, "Missing 'value_json' in items[$i]")
                        }
                    }
                } ?: run {
                    val path = params.optString("path", "").takeIf { it.isNotEmpty() }
                        ?: throw RPCException(
                            -32602,
                            "Missing 'path' (or 'items') for subcommand=set",
                        )
                    val valueJson = params.optString("value_json", "").takeIf { it.isNotEmpty() }
                        ?: throw RPCException(-32602, "Missing 'value_json' for subcommand=set")
                    JSONArray().put(
                        JSONObject().apply {
                            put("path", path)
                            put("value_json", valueJson)
                        },
                    )
                }
                // Default stays TRUE for backward compatibility: existing
                // harnesses call this unattended and would hang on a sheet that
                // nobody can tap. Passing `skipConfirmation:false` is what lets a
                // test drive the REAL confirmation gate — without it that path is
                // permanently unreachable from automation, which is precisely
                // what iOS DebugRPCConfig calls out (it defaults the other way,
                // preferring fidelity over convenience).
                val skip = params.optBoolean("skipConfirmation", true)
                AppLogger.info(
                    "DebugRPC",
                    "debug.minisConfig.exec set items=${items.length()} skipConfirmation=$skip",
                )
                // Hop to the main thread because performWriteBatch is a
                // suspend fun that uses Dispatchers.Main internally.
                kotlinx.coroutines.runBlocking {
                    com.openminis.app.config.ConfigBridge.performWriteBatch(
                        items = items,
                        caption = "debug.minisConfig.exec",
                        actorRaw = "debug-rpc",
                        sessionId = null,
                        skipConfirmation = skip,
                    )
                }
            }
            "get" -> {
                val path = params.optString("path", "").takeIf { it.isNotEmpty() }
                    ?: throw RPCException(-32602, "Missing 'path' for subcommand=get")
                // filter/page/pageSize were hardcoded to null/0/0, which made
                // large collections (models, sessions) unreadable from
                // automation — readField supports all three, so forward them.
                // 0/0 preserves the previous "no pagination" behaviour.
                // NOTE: readField's page is 1-BASED (it maps page<=0 to page 1),
                // so page=0 and page=1 both return the first page.
                val filter = params.optString("filter", "").takeIf { it.isNotEmpty() }
                val page = params.optInt("page", 0)
                val pageSize = params.optInt("pageSize", 0)
                AppLogger.info("DebugRPC", "debug.minisConfig.exec get path=$path")
                com.openminis.app.config.ConfigBridge.readField(
                    path = path,
                    filter = filter,
                    page = page,
                    pageSize = pageSize,
                )
            }
            // Discovery. Without these a caller has to know a collection's
            // writable paths in advance; `topics` is `minis-config --help`'s
            // index and `topic-help` is `minis-config <topic> --help`.
            "topics" -> JSONObject().apply {
                put("ok", true)
                put("topics", com.openminis.app.config.ConfigBridge.allTopics())
            }
            "topic-help" -> {
                val topic = params.optString("topic", "").takeIf { it.isNotEmpty() }
                    ?: throw RPCException(-32602, "Missing 'topic' for subcommand=topic-help")
                // "No fields" has TWO causes that must not be conflated: an
                // unregistered topic (caller typo — an error), and a registered
                // COLLECTION that currently has no children, e.g. `thinkingrules`
                // before the user authors a rule. The latter is a legitimate
                // empty result; reporting it as "unknown topic" sends the caller
                // hunting for a name that is in fact correct.
                //
                // allTopics() is the authority because it includes every
                // registered collection basePath regardless of child count.
                val known = com.openminis.app.config.ConfigBridge.allTopics()
                    .let { arr -> (0 until arr.length()).any { arr.optString(it) == topic } }
                if (!known) {
                    throw RPCException(-32602, "Unknown topic '$topic' — call subcommand=topics")
                }
                val fields = com.openminis.app.config.ConfigBridge.fieldsForTopic(topic)
                JSONObject().apply {
                    put("ok", true)
                    put("topic", topic)
                    // Explicit, so an empty list is unambiguously "registered but
                    // has no children right now" rather than a silent oddity.
                    put("empty", fields.length() == 0)
                    put("fields", fields)
                }
            }
            "audit-list" -> {
                val limit = params.optInt("limit", 100).coerceIn(1, 1000)
                val scope = params.optString("scope", "").takeIf { it.isNotEmpty() }
                com.openminis.app.config.ConfigBridge.auditList(limit, scope)
            }
            else -> throw RPCException(
                -32602,
                "Unknown subcommand '$sub' — one of: set, get, topics, topic-help, audit-list",
            )
        }
    }
}

internal class RPCException(val code: Int, override val message: String) : Exception(message)

// ─── Merged from LLMRequestLog.kt ──────────────────────────────────────────────────

/**
 * Ring buffer that captures recent LLM API requests and responses for debugging.
 * Accessible via debug.llmRequests JSON-RPC method.
 */
object LLMRequestLog {

    private const val MAX_ENTRIES = 20

    /**
     * T302: cap each retained `requestBody` at this many characters. Real
     * agent-loop request bodies can run 30+ MB (full message history with
     * heavy tool outputs); pinning 20 of those in a ring buffer was the
     * direct cause of an OOM crash on a HONOR PTP-AN00 — the JVM heap
     * blew past target footprint trying to hold ~600 MB of transient
     * debug strings. The truncated body is still useful for diagnosing
     * cache-prefix stability and small request shape.
     */
    private const val MAX_BODY_CHARS = 64 * 1024

    data class Entry(
        val provider: String,
        val timestamp: Long = System.currentTimeMillis(),
        val requestURL: String,
        val requestMethod: String = "POST",
        val requestHeaders: Map<String, String>,
        val requestBody: String,      // JSON string — capped at MAX_BODY_CHARS by add()
        val durationMs: Long = 0,
        val responseStatusCode: Int = 0,
        val responseBody: String = "", // first 2000 chars
        val usage: JSONObject? = null,
    )

    private val entries = mutableListOf<Entry>()

    /**
     * The newest request body, untruncated - one slot only, so the T302 heap
     * bound stays at one body. Checking what a request really replays (the
     * reasoning and tool calls of every past turn) needs the tail, which the
     * 64 KB cap cuts off. Served by debug.llmRequests {fullLast:true}.
     */
    private var lastFullBody: String? = null
    private var lastFullTimestamp: Long = 0

    @Synchronized
    fun add(entry: Entry) {
        // T302: belt-and-suspenders. Release builds short-circuit the whole
        // log because the only consumer is the debug.llmRequests JSON-RPC
        // surface, which release users never reach — keeping a 20-deep ring
        // buffer of multi-MB request bodies hot for nobody is exactly the
        // OOM the HONOR PTP-AN00 user hit. Debug builds still record, with
        // each entry truncated to MAX_BODY_CHARS so even a single retained
        // entry can't push a memory-tight device over the line.
        if (!BuildConfig.DEV_TOOLS) return
        // [T-android-llmlog-redact] Never retain credentials: the ring is
        // served by debug.llmRequests, which a LAN client reaches with only
        // the debug-server token, and callers hand over the raw request
        // header map (Authorization: Bearer <key>, x-api-key). Redact here,
        // once, so every current and future caller is covered.
        val redacted = entry.copy(
            requestHeaders = redactHeaders(entry.requestHeaders),
            requestURL = redactUrl(entry.requestURL),
        )
        lastFullBody = redacted.requestBody
        lastFullTimestamp = redacted.timestamp
        val safeEntry = if (redacted.requestBody.length > MAX_BODY_CHARS) {
            redacted.copy(
                requestBody = redacted.requestBody.take(MAX_BODY_CHARS) +
                    "\n…[truncated ${redacted.requestBody.length - MAX_BODY_CHARS} chars]",
            )
        } else redacted
        entries.add(safeEntry)
        if (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
    }

    /** Header names whose value is a credential (compared case-insensitively). */
    private val SECRET_HEADERS = setOf(
        "authorization", "proxy-authorization", "x-api-key", "api-key",
        "x-goog-api-key", "cookie", "set-cookie",
    )

    /** Query parameters that carry an API key (e.g. Gemini's `?key=`). */
    private val SECRET_QUERY_PARAMS = setOf("key", "api_key", "apikey", "access_token", "token")

    /**
     * Credential headers become `***`; an `Authorization` value keeps its
     * scheme (`Bearer ***`, `Basic ***`) like iOS, so the shape of the request
     * stays visible. Every other header, including `x-opencode-session`, is
     * kept as is.
     */
    fun redactHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (name, value) ->
            val lower = name.lowercase()
            val trimmed = value.trim()
            when {
                lower !in SECRET_HEADERS -> value
                (lower == "authorization" || lower == "proxy-authorization") && ' ' in trimmed ->
                    "${trimmed.substringBefore(' ')} ***"
                else -> "***"
            }
        }

    /** Replaces the value of key-carrying query parameters with `***`. */
    fun redactUrl(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val fragmentAt = url.indexOf('#', q).let { if (it < 0) url.length else it }
        val query = url.substring(q + 1, fragmentAt).split('&').joinToString("&") { pair ->
            val name = pair.substringBefore('=')
            if ('=' in pair && name.lowercase() in SECRET_QUERY_PARAMS) "$name=***" else pair
        }
        return url.substring(0, q + 1) + query + url.substring(fragmentAt)
    }

    @Synchronized
    fun getAll(): List<Entry> = entries.toList()

    @Synchronized
    fun getLast(n: Int): List<Entry> = entries.takeLast(n)

    @Synchronized
    fun clear() {
        entries.clear()
        lastFullBody = null
    }

    /** {timestamp, length, requestBody} of the newest request, untruncated; null when none. */
    @Synchronized
    fun lastFullJSON(): JSONObject? {
        val body = lastFullBody ?: return null
        return JSONObject().apply {
            put("timestamp", lastFullTimestamp)
            put("length", body.length)
            put("requestBody", body)
        }
    }

    fun toJSON(last: Int? = null): JSONObject {
        val list = if (last != null) getLast(last) else getAll()
        val array = JSONArray()
        for (entry in list) {
            array.put(JSONObject().apply {
                put("provider", entry.provider)
                put("timestamp", entry.timestamp)
                put("requestURL", entry.requestURL)
                put("requestMethod", entry.requestMethod)
                put("requestHeaders", JSONObject(entry.requestHeaders))
                put("requestBody", try { JSONObject(entry.requestBody) } catch (_: Exception) { entry.requestBody })
                put("durationMs", entry.durationMs)
                put("responseStatusCode", entry.responseStatusCode)
                if (entry.responseBody.isNotEmpty()) {
                    put("responseBody", entry.responseBody)
                }
                if (entry.usage != null) {
                    put("usage", entry.usage)
                }
            })
        }
        return JSONObject().apply {
            put("count", list.size)
            put("requests", array)
        }
    }
}
