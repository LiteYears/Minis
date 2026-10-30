package com.openminis.app.sandbox.offload

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.openminis.app.accessibility.AccessibilityRecoveryManager
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.accessibility.NodeRegistry
import com.openminis.app.accessibility.RestrictedSettingsManager
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.RepeatMode
import com.openminis.app.offload.WeatherManager
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.speech.RecognizerErrorCodes
import com.openminis.app.speech.RecognizerFallback
import com.openminis.app.speech.TextToSpeechManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/**
 * `android-a11y-cli` — UI-layer automation surface backed by
 * [MinisAccessibilityService]. Mirrors the design doc
 * `docs/.../android-accessibility-cli-design.md`.
 *
 * Output envelope `{ ok, data }` / `{ ok:false, error:{code,message} }`
 * matches the rest of `android-*` so `--quiet` strips it cleanly.
 */
class AccessibilityOffloadHandler(private val context: Context) : NativeOffloadHandler {

    companion object {
        private const val TAG = "A11yOffload"
        private const val TOOL = "android-a11y-cli"

        /**
         * [T-android-a11y-screenshot-mount] Where `ui screenshot` writes its PNG.
         *
         * It must be a path PRoot bind-mounts into the guest, or the agent
         * cannot read back what it just captured. The previous location,
         * `getExternalFilesDir("a11y_screenshots")`, is mounted nowhere:
         * `ExecutionCoordinator.buildSessionBindMounts` only exposes
         * `/var/minis/{attachments,offloads,workspace,browser}` (per session)
         * and `/var/minis/{memory,skills,shared,mcp-servers}` (global), so the
         * returned host path resolved through `resolveHostPath`'s rootfs
         * fallback to a file that does not exist and `read_image` answered
         * "File not found".
         *
         * `attachments` is the right namespace rather than `workspace`: it is
         * session-scoped, it is what the system prompt already documents for
         * media the model may render inline, and it is where the sibling
         * producers write (`ImageBudget` spillover, user uploads). The
         * `screenshots/` subdir keeps captures separate from those.
         */
        private const val VAR_MINIS_ATTACHMENTS = "/var/minis/attachments"
        private const val A11Y_SHOT_SUBDIR = "screenshots"

        private const val TOP_HELP = """android-a11y-cli — UI-layer automation via Android AccessibilityService.

Usage:
  android-a11y-cli <group> <subcommand> [flags]

Groups:
  ui        dump | find | info | node | screenshot
  tap       node | xy | text | id
  input     text | clear | key
  scroll    node | xy | to-text
  gesture   swipe | pinch | path
  wait      appear | disappear | stable | activity
  event     watch | once
  notify    watch | once
  dialog    detect | dismiss
  extract   text | list | form
  service   status | ping

Output: JSON envelope { ok, data | error: { code, message } }.
Use --compact to emit on a single line; --quiet to strip the envelope.

First-run: enable "Minis" under Settings → Accessibility, then `service ping`.
"""
        private const val SERVICE_HELP = "service status | ping\n"
        private const val UI_HELP = """ui dump | find | info | node | screenshot

  ui screenshot                Capture a system-wide screenshot via
                               AccessibilityService.takeScreenshot (API 30+).
    --scale <float>            Scale factor applied to the captured bitmap
                               (default 0.5; e.g. 1.0 = native, 0.25 = quarter).
    --display <int>            Display id to capture (default 0 = default).
    --inline | -b              Return base64 PNG inline instead of writing
                               to a file. Otherwise the PNG is written to
                               /var/minis/attachments/screenshots/a11y_<ts>.png
                               and the reply carries `path` (that Linux path,
                               ready for read_image), `minis_url`
                               (minis://attachments/screenshots/<file>) and
                               `host_path` (the Android-side path, for
                               debugging only — tools cannot read it).
"""
        private const val TAP_HELP = "tap node <id> | tap xy <x> <y> | tap text <s> | tap id <res>\n"
        private const val INPUT_HELP = "input text <s> [--node id] [--clear|--append] | input clear | input key BACK|HOME|RECENTS|NOTIFICATIONS\n"
        private const val SCROLL_HELP = "scroll node <id> [--direction up|down|left|right] [--times n] | scroll xy <x> <y> | scroll to-text <s>\n"
        private const val GESTURE_HELP = "gesture swipe <x1> <y1> <x2> <y2> | gesture pinch <cx> <cy> [--scale f] | gesture path \"x,y:x,y\"\n"
        private const val WAIT_HELP = "wait appear | disappear | stable | activity\n"
        private const val EVENT_HELP = "event watch [--type T] [--package P] [--duration ms] | event once [--type T] [--timeout ms]\n"
        private const val NOTIFY_HELP = "notify watch [--package P] [--text-contains S] [--duration ms] | notify once [--timeout ms]\n"
        private const val DIALOG_HELP = "dialog detect | dialog dismiss [--confirm|--deny|--button TEXT]\n"
        private const val EXTRACT_HELP = "extract text | list [--auto-scroll] | form\n"
    }

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(
            request.argv.drop(1),
            booleanFlags = setOf(
                "help", "h", "long", "double", "clear", "append",
                "annotate", "compact", "inline", "b",
                "clickable", "editable", "scrollable", "checked", "enabled",
                "visible-only", "auto-scroll", "auto-dismiss", "auto-confirm",
                "confirm", "deny", "children", "ancestors",
            ),
        )
        if (args.hasFlag("h", "help") || args.positional.isEmpty()) {
            return NativeOffloadResult(if (args.positional.isEmpty()) 2 else 0, TOP_HELP)
        }
        val sub = args.positional[0]
        // T330: tri-state agent gate via OffloadPermissionManager. `service`
        // and `--version` are diagnostic and pass through so the agent (or
        // a curious developer) can still verify the service runs even when
        // the agent surface is blocked.
        if (sub != "service" && sub != "--version") {
            if (!OffloadGate.allow("a11y_cli", "android-a11y-cli", request)) {
                return err(
                    args,
                    "PERMISSION_DENIED",
                    "Agent is not allowed to use android-a11y-cli. Open Settings → Permissions → Integrations to change.",
                )
            }
        }
        // [T-android-a11y-force-stop-recovery] A force-stop (system Settings'
        // "Force stop", or an OEM "clear" button) makes the framework strip our
        // component out of Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES. It
        // never self-heals, so without this every subsequent a11y call just
        // returns SERVICE_NOT_RUNNING and the user has no idea the grant is the
        // problem. Detect it here — the single point every real operation
        // passes through — and offer a repair (one-tap via Shizuku when
        // available, otherwise a trip to Settings).
        //
        // `service` / `--version` are exempt for the same reason they bypass
        // the gate above: they are how you DIAGNOSE this state, and prompting
        // from `service status` would be circular.
        //
        // runBlocking matches the established pattern for permission gates in
        // the sibling handlers (Calendar / Contacts / BrowserUse) — offload
        // handlers are invoked off the main thread on a sandbox worker, and
        // `handle` is not a suspend fun. The prompt self-cancels after
        // AccessibilityRecoveryManager.PROMPT_TIMEOUT_MS so this can never
        // block the worker indefinitely.
        if (sub != "service" && sub != "--version") {
            val usable = kotlinx.coroutines.runBlocking {
                AccessibilityRecoveryManager.ensureGrantOrPrompt(context)
            }
            if (!usable) {
                // [T-android-restricted-settings] Distinguish the two reasons
                // the grant can be missing. If the OS has flagged this install
                // (sideloaded from a downloaded APK), telling the user to
                // "re-enable it under Settings → Accessibility" sends them to a
                // toggle that is greyed out and cannot be moved — the actual
                // blocker is the restricted-settings gate, so name it.
                val restricted = RestrictedSettingsManager.isRestricted(context)
                return err(
                    args,
                    "SERVICE_NOT_RUNNING",
                    if (restricted) {
                        "Android is blocking the accessibility toggle for this install " +
                            "(\"Restricted setting\" — applies to apps installed from a " +
                            "downloaded APK). Allow it via App info → ⋮ → Allow restricted " +
                            "settings, then enable Minis under Settings → Accessibility. " +
                            "Settings → Permissions → System Permissions has a one-tap fix " +
                            "when Shizuku is available."
                    } else {
                        "Accessibility permission was revoked (this happens after a force-stop). " +
                            "Re-enable Minis under Settings → Accessibility, or use Settings → " +
                            "Permissions → Integrations to repair it with Shizuku."
                    },
                    exit = 77,
                )
            }
        }
        // [T-bg-overlay phase 2 fix] Surface a11y sub-action progress to
        // SessionActivityTracker.currentToolStatus so the background
        // overlay capsule + FGS notification show what the agent is
        // actually doing — "tap by-text Login" instead of the static
        // "Running: a11y_cli". Computed before dispatch so even fast
        // sub-commands (status / ping) get a one-line update before they
        // return. Does NOT touch the per-message tool name / running
        // flag — those stay owned by ChatViewModel so the overlay's
        // tool-icon and indeterminate progress are not affected.
        com.openminis.app.service.SessionActivityTracker.updateToolStatus(
            statusForSubAction(sub, args.positional.getOrNull(1), args),
        )
        return try {
            when (sub) {
                // [T-android-a11y-helper-mount] Resolve screenshots against the
                // session whose attachments dir the guest has MOUNTED: for a
                // helper that is its parent (ExecutionCoordinator binds
                // `fsSessionId ?: sessionId`), not the MINIS_CHAT_SESSION_ID it
                // exports. Otherwise the PNG lands where neither the helper's
                // shell nor read_image can find it.
                "ui"        -> uiSub(
                    args,
                    request.sessionId?.let {
                        com.openminis.app.sandbox.ExecutionCoordinator.mountedSessionIdFor(it)
                    },
                )
                "tap"       -> tapSub(args)
                "input"     -> inputSub(args)
                "scroll"    -> scrollSub(args)
                "gesture"   -> gestureSub(args)
                "wait"      -> waitSub(args)
                "event"     -> eventSub(args)
                "notify"    -> notifySub(args)
                "dialog"    -> dialogSub(args)
                "extract"   -> extractSub(args)
                "service"   -> serviceSub(args)
                "--version" -> NativeOffloadResult(0, "android-a11y-cli 0.1\n")
                else        -> NativeOffloadResult(2, "$TOOL: unknown subcommand '$sub'\n$TOP_HELP")
            }
        } catch (e: NotRunning) {
            err(args, "SERVICE_NOT_RUNNING",
                e.message ?: "Accessibility service is not running. Enable Minis under Settings → Accessibility.",
                exit = 77)
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${e.javaClass.simpleName} ${e.message}")
            err(args, "INTERNAL", "${e.javaClass.simpleName}: ${e.message ?: ""}")
        }
    }

    // ── service ──────────────────────────────────────────────────────────

    private fun serviceSub(args: OffloadArgs): NativeOffloadResult {
        return when (args.positional.getOrNull(1)) {
            null -> NativeOffloadResult(2, SERVICE_HELP)
            "status" -> {
                val svc = MinisAccessibilityService.getInstance()
                val data = JSONObject()
                    .put("running", svc != null)
                    .put("serviceName", MinisAccessibilityService.SERVICE_ID)
                    .put("capabilities", JSONArray().apply {
                        put("retrieveWindowContent"); put("performGestures"); put("watchEvents")
                    })
                    .put("androidVersion", android.os.Build.VERSION.SDK_INT)
                    // [T-android-restricted-settings] `service status` is the
                    // documented way to diagnose why the service won't start,
                    // so report the OS-level block explicitly — otherwise the
                    // only visible fact is running=false, which looks
                    // identical to "the user never enabled it".
                    .put("restrictedSettings", RestrictedSettingsManager.isRestricted(context))
                ok(args, data)
            }
            "ping" -> {
                if (MinisAccessibilityService.getInstance() != null)
                    NativeOffloadResult(0, "✓ Accessibility service is running\n")
                else if (RestrictedSettingsManager.isRestricted(context))
                    NativeOffloadResult(
                        77,
                        "✗ Accessibility service is not running — Android has flagged this " +
                            "install as \"Restricted setting\" and the toggle is greyed out. " +
                            "Allow it via App info → ⋮ → Allow restricted settings first.\n",
                    )
                else
                    NativeOffloadResult(77, "✗ Accessibility service is not running — go to Settings → Accessibility → Minis to enable\n")
            }
            else -> NativeOffloadResult(2, "$TOOL service: unknown action\n$SERVICE_HELP")
        }
    }

    // ── ui ───────────────────────────────────────────────────────────────

    /**
     * [T-android-a11y-screenshot-mount] `sessionId` is threaded in only for
     * `screenshot`, which must resolve the calling chat's own
     * `/var/minis/attachments` rather than whichever session wrote the global
     * bind-mount map last. Null when the call did not come from an agent shell
     * (e.g. the interactive terminal).
     */
    private fun uiSub(args: OffloadArgs, sessionId: String?): NativeOffloadResult {
        return when (args.positional.getOrNull(1)) {
            null         -> NativeOffloadResult(2, UI_HELP)
            "dump"       -> uiDump(args)
            "find"       -> uiFind(args)
            "info"       -> uiInfo(args)
            "node"       -> uiNode(args)
            "screenshot" -> uiScreenshot(args, sessionId)
            else         -> NativeOffloadResult(2, "$TOOL ui: unknown action\n$UI_HELP")
        }
    }

    private fun uiDump(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val maxDepth = args.getInt("depth") ?: 10
        val compact = args.hasFlag("compact")
        val visibleOnly = args.getBool("visible-only") ?: true
        val arr = JSONArray()
        for (root in svc.rootNodes()) {
            walkNode(svc.nodeRegistry, root, 0, maxDepth, visibleOnly, compact, arr)
        }
        return ok(args, JSONObject().put("count", arr.length()).put("nodes", arr))
    }

    private fun walkNode(
        registry: NodeRegistry, node: AccessibilityNodeInfo?,
        depth: Int, maxDepth: Int, visibleOnly: Boolean, compact: Boolean, out: JSONArray,
    ) {
        if (node == null || depth > maxDepth) return
        if (!visibleOnly || node.isVisibleToUser) out.put(nodeToJson(registry, node, depth, compact))
        for (i in 0 until node.childCount) {
            walkNode(registry, node.getChild(i), depth + 1, maxDepth, visibleOnly, compact, out)
        }
    }

    private fun nodeToJson(registry: NodeRegistry, n: AccessibilityNodeInfo, depth: Int, compact: Boolean): JSONObject {
        val id = registry.put(n)
        val rect = Rect(); n.getBoundsInScreen(rect)
        val text = n.text?.toString()
        val desc = n.contentDescription?.toString()
        val obj = JSONObject().put("nodeId", id)
        if (compact) {
            if (!text.isNullOrEmpty()) obj.put("text", text)
            if (!desc.isNullOrEmpty()) obj.put("contentDesc", desc)
            obj.put("clickable", n.isClickable)
            obj.put("center", JSONObject().put("x", rect.centerX()).put("y", rect.centerY()))
        } else {
            obj.put("className", n.className?.toString() ?: "")
            obj.put("text", text ?: "")
            obj.put("contentDesc", desc ?: "")
            obj.put("resourceId", n.viewIdResourceName ?: "")
            obj.put("packageName", n.packageName?.toString() ?: "")
            obj.put("bounds", JSONObject()
                .put("left", rect.left).put("top", rect.top)
                .put("right", rect.right).put("bottom", rect.bottom))
            obj.put("center", JSONObject().put("x", rect.centerX()).put("y", rect.centerY()))
            obj.put("clickable", n.isClickable)
            obj.put("longClickable", n.isLongClickable)
            obj.put("scrollable", n.isScrollable)
            obj.put("editable", n.isEditable)
            obj.put("checkable", n.isCheckable)
            obj.put("checked", n.isChecked)
            obj.put("focusable", n.isFocusable)
            obj.put("focused", n.isFocused)
            obj.put("selected", n.isSelected)
            obj.put("enabled", n.isEnabled)
            obj.put("visible", n.isVisibleToUser)
            obj.put("depth", depth)
            obj.put("childCount", n.childCount)
        }
        return obj
    }

    private fun uiFind(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val limit = args.getInt("limit") ?: 50
        val depth = args.getInt("depth") ?: 30
        val matches = ArrayList<AccessibilityNodeInfo>()
        for (root in svc.rootNodes()) {
            findMatching(root, args, depth, 0, matches, limit)
            if (matches.size >= limit) break
        }
        val index = args.getInt("index")
        val arr = JSONArray()
        if (index != null) {
            matches.getOrNull(index)?.let { arr.put(nodeToJson(svc.nodeRegistry, it, 0, compact = false)) }
        } else {
            for (m in matches) arr.put(nodeToJson(svc.nodeRegistry, m, 0, compact = false))
        }
        return ok(args, JSONObject().put("count", arr.length()).put("data", arr))
    }

    private fun findMatching(
        node: AccessibilityNodeInfo?, args: OffloadArgs,
        maxDepth: Int, depth: Int,
        out: MutableList<AccessibilityNodeInfo>, limit: Int,
    ) {
        if (node == null || depth > maxDepth || out.size >= limit) return
        if (matchesPredicate(node, args)) out.add(node)
        for (i in 0 until node.childCount) {
            findMatching(node.getChild(i), args, maxDepth, depth + 1, out, limit)
        }
    }

    private fun matchesPredicate(n: AccessibilityNodeInfo, args: OffloadArgs): Boolean {
        args.get("text")?.let { if (n.text?.toString() != it) return false }
        args.get("text-contains")?.let { if (n.text?.toString()?.contains(it) != true) return false }
        args.get("desc")?.let { if (n.contentDescription?.toString() != it) return false }
        args.get("desc-contains")?.let { if (n.contentDescription?.toString()?.contains(it) != true) return false }
        args.get("id")?.let { if (n.viewIdResourceName != it) return false }
        args.get("class")?.let { if (n.className?.toString() != it) return false }
        args.get("package")?.let { if (n.packageName?.toString() != it) return false }
        if (args.hasFlag("clickable") && !n.isClickable) return false
        if (args.hasFlag("editable") && !n.isEditable) return false
        if (args.hasFlag("scrollable") && !n.isScrollable) return false
        if (args.hasFlag("checked") && !n.isChecked) return false
        if (args.hasFlag("enabled") && !n.isEnabled) return false
        return true
    }

    private fun uiInfo(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val (pkg, cls) = svc.foregroundPackage()
        val windows = svc.windowInfos()
        val winArr = JSONArray()
        for (w in windows) {
            winArr.put(JSONObject().apply { for ((k, v) in w) put(k, v ?: JSONObject.NULL) })
        }
        return ok(args, JSONObject()
            .put("packageName", pkg ?: "")
            .put("activityName", cls ?: "")
            .put("windowCount", windows.size)
            .put("windows", winArr))
    }

    private fun uiNode(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val nodeId = args.positional.getOrNull(2)
            ?: return NativeOffloadResult(2, "$TOOL ui node: missing <nodeId>\n")
        val n = svc.nodeRegistry.get(nodeId)
            ?: return err(args, "NODE_NOT_FOUND", "no live node with id=$nodeId; re-run `ui dump`")
        val data = nodeToJson(svc.nodeRegistry, n, 0, compact = false)
        if (args.hasFlag("children")) {
            val ch = JSONArray()
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { ch.put(nodeToJson(svc.nodeRegistry, it, 1, compact = false)) }
            }
            data.put("children", ch)
        }
        if (args.hasFlag("ancestors")) {
            val anc = JSONArray()
            var cur = n.parent
            while (cur != null) { anc.put(nodeToJson(svc.nodeRegistry, cur, 0, compact = true)); cur = cur.parent }
            data.put("ancestors", anc)
        }
        return ok(args, data)
    }

    private fun uiScreenshot(args: OffloadArgs, sessionId: String?): NativeOffloadResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return err(args, "NOT_SUPPORTED",
                "ui screenshot requires Android 11 (API 30); use `android-shizuku-cli exec screencap` instead.")
        }
        val svc = svcOrThrow()
        val scale = (args.getDouble("scale") ?: 0.5).toFloat().coerceIn(0.05f, 1.0f)
        val displayId = args.getInt("display") ?: Display.DEFAULT_DISPLAY
        val inline = args.hasFlag("inline", "b")

        val shot = svc.captureScreenshot(displayId)
        val raw = shot.bitmap
            ?: return err(args, shot.errorCode ?: "TAKE_SCREENSHOT_FAILED",
                shot.errorMessage ?: "takeScreenshot failed")

        val origW = raw.width
        val origH = raw.height
        val scaled: Bitmap = if (scale == 1.0f) raw else {
            val w = (origW * scale).toInt().coerceAtLeast(1)
            val h = (origH * scale).toInt().coerceAtLeast(1)
            val s = Bitmap.createScaledBitmap(raw, w, h, true)
            if (s !== raw) raw.recycle()
            s
        }

        val baos = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val outW = scaled.width
        val outH = scaled.height
        scaled.recycle()
        val pngBytes = baos.toByteArray()

        val data = JSONObject()
            .put("displayId", displayId)
            .put("originalWidth", origW)
            .put("originalHeight", origH)
            .put("scaledWidth", outW)
            .put("scaledHeight", outH)
            .put("scale", scale.toDouble())
            .put("sizeBytes", pngBytes.size)

        if (inline) {
            data.put("encoding", "png+base64")
            data.put("base64", android.util.Base64.encodeToString(pngBytes, android.util.Base64.NO_WRAP))
            return ok(args, data)
        }

        // [T-android-a11y-screenshot-mount] Resolve the SESSION's attachments
        // directory, not the global bind-mount map's current answer.
        //
        // PRootKernel.bindMounts is process-global mutable state that
        // ExecutionCoordinator.buildSessionBindMounts overwrites every time ANY
        // session boots a shell, so resolveHostPath is last-writer-wins. Using
        // it alone would drop this chat's screenshot into a different chat's
        // attachments directory whenever two sessions are active — the capture
        // would then be unreadable here and would leak into an unrelated
        // conversation. resolveSessionHostPath bypasses that map for the
        // per-session subdirs and is the API the chat link resolver and
        // read_image already use for exactly this reason.
        //
        // The global map remains the fallback for a call with no session (the
        // interactive terminal), where last-writer-wins is the only answer
        // available and nothing is being attributed to a chat.
        val attachmentsHostDir: java.io.File? = sessionId
            ?.let { PRootKernel.resolveSessionHostPath(it, VAR_MINIS_ATTACHMENTS, context) }
            ?: PRootKernel.resolveHostPath(VAR_MINIS_ATTACHMENTS)

        if (attachmentsHostDir == null) {
            // Do NOT silently fall back to a private directory the way the old
            // `?: cacheDir` chain did: that produced a path the agent cannot
            // read and reported success, which is the whole bug. Fail loudly
            // and name the one flag that still works without a mount.
            return err(args, "NO_ATTACHMENTS_MOUNT",
                "cannot resolve $VAR_MINIS_ATTACHMENTS; run from an agent shell, or pass --inline for base64")
        }

        val dir = java.io.File(attachmentsHostDir, A11Y_SHOT_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        val filename = "a11y_${System.currentTimeMillis()}.png"
        val file = java.io.File(dir, filename)
        return try {
            java.io.FileOutputStream(file).use { it.write(pngBytes) }
            val linuxPath = "$VAR_MINIS_ATTACHMENTS/$A11Y_SHOT_SUBDIR/$filename"
            // `path` is the guest path so it can be handed straight to
            // read_image / the shell. This replaces the host absolute path the
            // old code returned, which no agent-side tool could open.
            data.put("path", linuxPath)
            data.put("minis_url", "minis://attachments/$A11Y_SHOT_SUBDIR/$filename")
            // Kept for debugging and for any caller that genuinely wants the
            // Android-side location; it is NOT readable from the sandbox.
            data.put("host_path", file.absolutePath)
            ok(args, data)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "screenshot write failed: ${t.message}")
            err(args, "WRITE_FAILED", "failed to write PNG: ${t.javaClass.simpleName}: ${t.message ?: ""}")
        }
    }

    // ── tap ──────────────────────────────────────────────────────────────

    private fun tapSub(args: OffloadArgs): NativeOffloadResult {
        return when (args.positional.getOrNull(1)) {
            null   -> NativeOffloadResult(2, TAP_HELP)
            "node" -> tapNode(args)
            "xy"   -> tapXY(args)
            "text" -> tapText(args)
            "id"   -> tapId(args)
            else   -> NativeOffloadResult(2, "$TOOL tap: unknown action\n$TAP_HELP")
        }
    }

    private fun tapNode(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val nodeId = args.positional.getOrNull(2)
            ?: return NativeOffloadResult(2, "$TOOL tap node: missing <nodeId>\n")
        val n = svc.nodeRegistry.get(nodeId)
            ?: return err(args, "NODE_NOT_FOUND", "no live node with id=$nodeId")
        val action = if (args.hasFlag("long")) AccessibilityNodeInfo.ACTION_LONG_CLICK
                     else AccessibilityNodeInfo.ACTION_CLICK
        if (!n.isClickable && !args.hasFlag("long")) {
            val r = Rect(); n.getBoundsInScreen(r)
            return tapXYRaw(svc, r.centerX(), r.centerY(), args)
        }
        return if (n.performAction(action))
            ok(args, JSONObject().put("nodeId", nodeId).put("action", "click"))
        else err(args, "ACTION_FAILED", "performAction($action) returned false")
    }

    private fun tapXY(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val x = args.positional.getOrNull(2)?.toIntOrNull()
            ?: return NativeOffloadResult(2, "$TOOL tap xy: missing <x>\n")
        val y = args.positional.getOrNull(3)?.toIntOrNull()
            ?: return NativeOffloadResult(2, "$TOOL tap xy: missing <y>\n")
        return tapXYRaw(svc, x, y, args)
    }

    private fun tapXYRaw(svc: MinisAccessibilityService, x: Int, y: Int, args: OffloadArgs): NativeOffloadResult {
        val duration = args.getLong("duration") ?: if (args.hasFlag("long")) 1000L else 50L
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x.toFloat() + 0.1f, y.toFloat() + 0.1f)
        }
        val gestureOk = svc.dispatchSimpleGesture(path, 0L, duration)
        if (args.hasFlag("double")) {
            Thread.sleep(80)
            svc.dispatchSimpleGesture(path, 0L, duration)
        }
        return if (gestureOk) ok(args, JSONObject().put("x", x).put("y", y).put("action", "tap"))
        else err(args, "GESTURE_FAILED", "dispatchGesture returned cancelled / timed out")
    }

    private fun tapText(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val text = args.positional.getOrNull(2)
            ?: return NativeOffloadResult(2, "$TOOL tap text: missing <text>\n")
        val contains = args.hasFlag("contains")
        val index = args.getInt("index") ?: 0
        val matches = ArrayList<AccessibilityNodeInfo>()
        for (root in svc.rootNodes()) findByTextOrDesc(root, text, contains, 30, 0, matches)
        val n = matches.getOrNull(index)
            ?: return err(args, "NODE_NOT_FOUND",
                "no node with text${if (contains) " containing " else "="}\"$text\" (matches: ${matches.size})")
        if (n.isClickable) n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        else {
            val r = Rect(); n.getBoundsInScreen(r)
            tapXYRaw(svc, r.centerX(), r.centerY(), args)
        }
        return ok(args, JSONObject().put("text", text).put("action", "click"))
    }

    private fun findByTextOrDesc(
        node: AccessibilityNodeInfo?, target: String, contains: Boolean,
        maxDepth: Int, depth: Int, out: MutableList<AccessibilityNodeInfo>,
    ) {
        if (node == null || depth > maxDepth) return
        val t = node.text?.toString()
        val d = node.contentDescription?.toString()
        val match = if (contains)
            (t != null && t.contains(target)) || (d != null && d.contains(target))
        else
            t == target || d == target
        if (match) out.add(node)
        for (i in 0 until node.childCount) findByTextOrDesc(node.getChild(i), target, contains, maxDepth, depth + 1, out)
    }

    private fun tapId(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val rid = args.positional.getOrNull(2)
            ?: return NativeOffloadResult(2, "$TOOL tap id: missing <resourceId>\n")
        val matches = ArrayList<AccessibilityNodeInfo>()
        for (root in svc.rootNodes()) findByResourceId(root, rid, 30, 0, matches)
        val n = matches.firstOrNull()
            ?: return err(args, "NODE_NOT_FOUND", "no node with resource-id=$rid")
        return if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            ok(args, JSONObject().put("resourceId", rid).put("action", "click"))
        else err(args, "ACTION_FAILED", "performAction(CLICK) returned false")
    }

    private fun findByResourceId(
        node: AccessibilityNodeInfo?, rid: String,
        maxDepth: Int, depth: Int, out: MutableList<AccessibilityNodeInfo>,
    ) {
        if (node == null || depth > maxDepth) return
        if (node.viewIdResourceName == rid) out.add(node)
        for (i in 0 until node.childCount) findByResourceId(node.getChild(i), rid, maxDepth, depth + 1, out)
    }

    // ── input ────────────────────────────────────────────────────────────

    private fun inputSub(args: OffloadArgs): NativeOffloadResult {
        return when (args.positional.getOrNull(1)) {
            null    -> NativeOffloadResult(2, INPUT_HELP)
            "text"  -> inputText(args)
            "clear" -> inputClear(args)
            "key"   -> inputKey(args)
            else    -> NativeOffloadResult(2, "$TOOL input: unknown action\n$INPUT_HELP")
        }
    }

    private fun inputText(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val text = args.positional.getOrNull(2)
            ?: return NativeOffloadResult(2, "$TOOL input text: missing <text>\n")
        val node = resolveTargetEditable(svc, args)
            ?: return err(args, "NODE_NOT_FOUND", "no editable focus and no --node specified")
        val finalText = when {
            args.hasFlag("clear")  -> text
            args.hasFlag("append") -> (node.text?.toString() ?: "") + text
            else                   -> text
        }
        return if (svc.setNodeText(node, finalText))
            ok(args, JSONObject().put("text", finalText).put("action", "setText"))
        else err(args, "ACTION_FAILED", "ACTION_SET_TEXT failed (node may not be editable)")
    }

    private fun inputClear(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val node = resolveTargetEditable(svc, args)
            ?: return err(args, "NODE_NOT_FOUND", "no editable focus and no --node specified")
        return if (svc.setNodeText(node, ""))
            ok(args, JSONObject().put("action", "clear"))
        else err(args, "ACTION_FAILED", "ACTION_SET_TEXT('') failed")
    }

    private fun inputKey(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val keyName = args.positional.getOrNull(2)
            ?: return NativeOffloadResult(2, "$TOOL input key: missing <keycode>\n")
        return when (keyName.uppercase()) {
            "BACK" -> { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); ok(args, JSONObject().put("key", "BACK")) }
            "HOME" -> { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); ok(args, JSONObject().put("key", "HOME")) }
            "RECENTS" -> { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS); ok(args, JSONObject().put("key", "RECENTS")) }
            "NOTIFICATIONS" -> { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); ok(args, JSONObject().put("key", "NOTIFICATIONS")) }
            else -> err(args, "INVALID_ARGS",
                "key '$keyName' not supported via accessibility (try BACK/HOME/RECENTS/NOTIFICATIONS; for ENTER/DPAD use shizuku-cli `input keyevent`)")
        }
    }

    private fun resolveTargetEditable(svc: MinisAccessibilityService, args: OffloadArgs): AccessibilityNodeInfo? {
        args.get("node")?.let { id -> return svc.nodeRegistry.get(id) }
        for (root in svc.rootNodes()) {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) return focused
        }
        return null
    }

    // ── scroll ───────────────────────────────────────────────────────────

    private fun scrollSub(args: OffloadArgs): NativeOffloadResult {
        return when (args.positional.getOrNull(1)) {
            null      -> NativeOffloadResult(2, SCROLL_HELP)
            "node"    -> scrollNode(args)
            "xy"      -> scrollXY(args)
            "to-text" -> scrollToText(args)
            else      -> NativeOffloadResult(2, "$TOOL scroll: unknown action\n$SCROLL_HELP")
        }
    }

    private fun scrollNode(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val nodeId = args.positional.getOrNull(2)
            ?: return NativeOffloadResult(2, "$TOOL scroll node: missing <nodeId>\n")
        val n = svc.nodeRegistry.get(nodeId)
            ?: return err(args, "NODE_NOT_FOUND", "no live node with id=$nodeId")
        val direction = args.get("direction") ?: "down"
        val times = args.getInt("times") ?: 1
        val action = when (direction) {
            "down", "right" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "up", "left"    -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> return err(args, "INVALID_ARGS", "--direction must be up|down|left|right")
        }
        var done = 0
        repeat(times) { if (n.performAction(action)) done++ }
        return ok(args, JSONObject().put("scrolled", done).put("direction", direction))
    }

    private fun scrollXY(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val x = args.positional.getOrNull(2)?.toIntOrNull()
            ?: return NativeOffloadResult(2, "$TOOL scroll xy: missing <x>\n")
        val y = args.positional.getOrNull(3)?.toIntOrNull()
            ?: return NativeOffloadResult(2, "$TOOL scroll xy: missing <y>\n")
        val direction = args.get("direction") ?: "down"
        val distance = args.getInt("distance") ?: 500
        val duration = args.getLong("duration") ?: 300L
        val (dx, dy) = when (direction) {
            "down"  -> 0 to -distance
            "up"    -> 0 to distance
            "left"  -> distance to 0
            "right" -> -distance to 0
            else -> return err(args, "INVALID_ARGS", "--direction must be up|down|left|right")
        }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo((x + dx).toFloat(), (y + dy).toFloat()) }
        return if (svc.dispatchSimpleGesture(path, 0L, duration))
            ok(args, JSONObject().put("direction", direction).put("distance", distance))
        else err(args, "GESTURE_FAILED", "scroll gesture cancelled")
    }

    private fun scrollToText(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val text = args.positional.getOrNull(2)
            ?: return NativeOffloadResult(2, "$TOOL scroll to-text: missing <text>\n")
        val maxScrolls = args.getInt("max-scrolls") ?: 10
        val direction = args.get("direction") ?: "down"
        val containerId = args.get("container")
        val container: AccessibilityNodeInfo? = containerId?.let { svc.nodeRegistry.get(it) }
        val action = when (direction) {
            "down", "right" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "up", "left"    -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> return err(args, "INVALID_ARGS", "--direction must be up|down|left|right")
        }
        repeat(maxScrolls) {
            val matches = ArrayList<AccessibilityNodeInfo>()
            for (root in svc.rootNodes()) findByTextOrDesc(root, text, contains = true, 30, 0, matches)
            val hit = matches.firstOrNull()
            if (hit != null) return ok(args, JSONObject()
                .put("found", true)
                .put("node", nodeToJson(svc.nodeRegistry, hit, 0, compact = true)))
            val scrolled = container?.performAction(action) ?: run {
                var any = false
                for (root in svc.rootNodes()) {
                    val s = firstScrollable(root, 30, 0)
                    if (s != null) { any = s.performAction(action); break }
                }
                any
            }
            if (!scrolled) return ok(args, JSONObject().put("found", false).put("reason", "scroll_action_rejected"))
            Thread.sleep(400)
        }
        return ok(args, JSONObject().put("found", false))
    }

    private fun firstScrollable(node: AccessibilityNodeInfo?, maxDepth: Int, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > maxDepth) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) firstScrollable(node.getChild(i), maxDepth, depth + 1)?.let { return it }
        return null
    }

    // ── gesture ──────────────────────────────────────────────────────────

    private fun gestureSub(args: OffloadArgs): NativeOffloadResult {
        return when (args.positional.getOrNull(1)) {
            null    -> NativeOffloadResult(2, GESTURE_HELP)
            "swipe" -> gestureSwipe(args)
            "pinch" -> gesturePinch(args)
            "path"  -> gesturePath(args)
            else    -> NativeOffloadResult(2, "$TOOL gesture: unknown action\n$GESTURE_HELP")
        }
    }

    private fun gestureSwipe(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val x1 = args.positional.getOrNull(2)?.toIntOrNull() ?: return NativeOffloadResult(2, "$TOOL gesture swipe: missing <x1>\n")
        val y1 = args.positional.getOrNull(3)?.toIntOrNull() ?: return NativeOffloadResult(2, "$TOOL gesture swipe: missing <y1>\n")
        val x2 = args.positional.getOrNull(4)?.toIntOrNull() ?: return NativeOffloadResult(2, "$TOOL gesture swipe: missing <x2>\n")
        val y2 = args.positional.getOrNull(5)?.toIntOrNull() ?: return NativeOffloadResult(2, "$TOOL gesture swipe: missing <y2>\n")
        val duration = args.getLong("duration") ?: 300L
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        return if (svc.dispatchSimpleGesture(path, 0L, duration))
            ok(args, JSONObject().put("from", JSONArray().put(x1).put(y1)).put("to", JSONArray().put(x2).put(y2)))
        else err(args, "GESTURE_FAILED", "swipe cancelled")
    }

    private fun gesturePinch(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val cx = args.positional.getOrNull(2)?.toFloatOrNull() ?: return NativeOffloadResult(2, "$TOOL gesture pinch: missing <cx>\n")
        val cy = args.positional.getOrNull(3)?.toFloatOrNull() ?: return NativeOffloadResult(2, "$TOOL gesture pinch: missing <cy>\n")
        val scale = args.getDouble("scale")?.toFloat() ?: 0.5f
        val duration = args.getLong("duration") ?: 300L
        return if (svc.dispatchPinch(cx, cy, scale, duration))
            ok(args, JSONObject().put("center", JSONArray().put(cx).put(cy)).put("scale", scale))
        else err(args, "GESTURE_FAILED", "pinch cancelled")
    }

    private fun gesturePath(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val pointsStr = args.positional.getOrNull(2)
            ?: return NativeOffloadResult(2, "$TOOL gesture path: missing <points>\n")
        val pts = pointsStr.split(":").map {
            val xy = it.split(","); if (xy.size != 2) return err(args, "INVALID_ARGS", "bad points: $it")
            xy[0].toFloat() to xy[1].toFloat()
        }
        if (pts.size < 2) return err(args, "INVALID_ARGS", "need >= 2 points")
        val path = Path().apply {
            moveTo(pts[0].first, pts[0].second)
            for (i in 1 until pts.size) lineTo(pts[i].first, pts[i].second)
        }
        val duration = args.getLong("duration") ?: (pts.size * 100L)
        return if (svc.dispatchSimpleGesture(path, 0L, duration))
            ok(args, JSONObject().put("points", pts.size))
        else err(args, "GESTURE_FAILED", "path cancelled")
    }

    // ── wait ─────────────────────────────────────────────────────────────

    private fun waitSub(args: OffloadArgs): NativeOffloadResult {
        return when (args.positional.getOrNull(1)) {
            null         -> NativeOffloadResult(2, WAIT_HELP)
            "appear"     -> waitAppear(args, present = true)
            "disappear"  -> waitAppear(args, present = false)
            "stable"     -> waitStable(args)
            "activity"   -> waitActivity(args)
            else         -> NativeOffloadResult(2, "$TOOL wait: unknown action\n$WAIT_HELP")
        }
    }

    private fun waitAppear(args: OffloadArgs, present: Boolean): NativeOffloadResult {
        val svc = svcOrThrow()
        val timeout = args.getLong("timeout") ?: 10_000L
        val deadline = System.currentTimeMillis() + timeout
        val targetText = args.get("text") ?: args.get("text-contains")
        val containsOnly = args.get("text-contains") != null
        val targetId = args.get("id")
        val targetClass = args.get("class")
        while (System.currentTimeMillis() < deadline) {
            for (root in svc.rootNodes()) {
                val matches = ArrayList<AccessibilityNodeInfo>()
                if (targetText != null) findByTextOrDesc(root, targetText, contains = containsOnly, 30, 0, matches)
                if (targetId != null) findByResourceId(root, targetId, 30, 0, matches)
                if (targetClass != null) findByClassName(root, targetClass, 30, 0, matches)
                val found = matches.isNotEmpty()
                if (found == present) {
                    return if (present) ok(args, JSONObject().put("found", true)
                        .put("node", nodeToJson(svc.nodeRegistry, matches.first(), 0, compact = true)))
                    else ok(args, JSONObject().put("disappeared", true))
                }
            }
            Thread.sleep(200)
        }
        return ok(args, JSONObject().put("found", false).put("timedOut", true))
    }

    private fun findByClassName(node: AccessibilityNodeInfo?, cls: String, maxDepth: Int, depth: Int, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null || depth > maxDepth) return
        if (node.className?.toString() == cls) out.add(node)
        for (i in 0 until node.childCount) findByClassName(node.getChild(i), cls, maxDepth, depth + 1, out)
    }

    private fun waitStable(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val interval = args.getLong("interval") ?: 200L
        val stableDuration = args.getLong("duration") ?: 500L
        val timeout = args.getLong("timeout") ?: 8000L
        val start = System.currentTimeMillis()
        val deadline = start + timeout
        var lastSig = treeSignature(svc)
        var stableSince = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval)
            val sig = treeSignature(svc)
            if (sig != lastSig) { lastSig = sig; stableSince = System.currentTimeMillis() }
            else if (System.currentTimeMillis() - stableSince >= stableDuration) {
                return ok(args, JSONObject().put("stable", true).put("waitedMs", System.currentTimeMillis() - start))
            }
        }
        return ok(args, JSONObject().put("stable", false).put("timedOut", true))
    }

    private fun treeSignature(svc: MinisAccessibilityService): Int {
        var h = 0
        for (root in svc.rootNodes()) {
            h = h * 31 + (root.text?.hashCode() ?: 0)
            h = h * 31 + root.childCount
            h = h * 31 + root.windowId
        }
        val (pkg, cls) = svc.foregroundPackage()
        h = h * 31 + (pkg?.hashCode() ?: 0)
        h = h * 31 + (cls?.hashCode() ?: 0)
        return h
    }

    private fun waitActivity(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val timeout = args.getLong("timeout") ?: 10_000L
        val pkg = args.get("package")
        val act = args.get("activity")
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            val (p, c) = svc.foregroundPackage()
            val pkgOk = pkg == null || p == pkg
            val actOk = act == null || c == act || (act.startsWith(".") && (c?.endsWith(act) == true))
            if (pkgOk && actOk) return ok(args, JSONObject().put("packageName", p ?: "").put("activityName", c ?: ""))
            Thread.sleep(200)
        }
        return ok(args, JSONObject().put("timedOut", true))
    }

    // ── event / notify / dialog ──────────────────────────────────────────

    private fun eventSub(args: OffloadArgs): NativeOffloadResult {
        return when (args.positional.getOrNull(1)) {
            null    -> NativeOffloadResult(2, EVENT_HELP)
            "watch" -> eventWatch(args, once = false)
            "once"  -> eventWatch(args, once = true)
            else    -> NativeOffloadResult(2, "$TOOL event: unknown action\n$EVENT_HELP")
        }
    }

    private fun eventWatch(args: OffloadArgs, once: Boolean): NativeOffloadResult {
        val svc = svcOrThrow()
        val duration = if (once) (args.getLong("timeout") ?: 30_000L)
                       else (args.getLong("duration") ?: 30_000L)
        val typeFilter = args.get("type")?.split(",")?.map(String::trim)?.toSet()
        val pkgFilter = args.get("package")
        val textContains = args.get("text-contains")
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + duration.coerceAtMost(120_000L)
        val collected = java.util.concurrent.ConcurrentLinkedQueue<MinisAccessibilityService.RecordedEvent>()
        val listener: (MinisAccessibilityService.RecordedEvent) -> Unit = { collected.offer(it) }
        svc.addEventListener(listener)
        try {
            while (System.currentTimeMillis() < deadline) {
                while (true) {
                    val ev = collected.poll() ?: break
                    val typeName = ev.type.lowercase().removePrefix("typeview").removePrefix("type")
                    if (typeFilter != null && typeFilter.none { typeName.contains(it.lowercase()) }) continue
                    if (pkgFilter != null && ev.packageName != pkgFilter) continue
                    if (textContains != null && (ev.text?.contains(textContains) != true)) continue
                    val obj = JSONObject()
                        .put("type", typeName)
                        .put("packageName", ev.packageName ?: "")
                        .put("className", ev.className ?: "")
                        .put("text", ev.text ?: "")
                        .put("timestamp", ev.timestamp)
                    sb.append(obj.toString()).append('\n')
                    if (once) return NativeOffloadResult(0, sb.toString())
                }
                Thread.sleep(50)
            }
        } finally {
            svc.removeEventListener(listener)
        }
        return NativeOffloadResult(0, sb.toString())
    }

    private fun notifySub(args: OffloadArgs): NativeOffloadResult {
        val sub = args.positional.getOrNull(1)
        if (sub != "watch" && sub != "once") {
            return NativeOffloadResult(2, "$TOOL notify: unknown action\n$NOTIFY_HELP")
        }
        // Reuse eventWatch with forced type filter.
        val newArgv = mutableListOf("event", sub)
        args.get("package")?.let { newArgv += listOf("--package", it) }
        args.get("text-contains")?.let { newArgv += listOf("--text-contains", it) }
        args.get("duration")?.let { newArgv += listOf("--duration", it) }
        args.get("timeout")?.let { newArgv += listOf("--timeout", it) }
        newArgv += listOf("--type", "notification_state_changed")
        if (args.hasFlag("compact")) newArgv += "--compact"
        return eventWatch(OffloadArgs(newArgv), once = sub == "once")
    }

    private fun dialogSub(args: OffloadArgs): NativeOffloadResult {
        return when (args.positional.getOrNull(1)) {
            null      -> NativeOffloadResult(2, DIALOG_HELP)
            "detect"  -> dialogDetect(args)
            "dismiss" -> dialogDismiss(args)
            "watch"   -> err(args, "NOT_IMPLEMENTED", "dialog watch: not implemented in v1; poll `dialog detect` instead")
            else      -> NativeOffloadResult(2, "$TOOL dialog: unknown action\n$DIALOG_HELP")
        }
    }

    private fun dialogDetect(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        var dialog: AccessibilityNodeInfo? = null
        for (root in svc.rootNodes()) {
            dialog = findFirstByClassNameContains(root, "Dialog", 30, 0)
            if (dialog != null) break
        }
        if (dialog == null) return ok(args, JSONObject().put("hasDialog", false))
        val buttons = JSONArray()
        collectClickableTexts(dialog, 10, 0, buttons, svc.nodeRegistry)
        val msg = StringBuilder()
        collectText(dialog, 5, 0, msg)
        return ok(args, JSONObject()
            .put("hasDialog", true)
            .put("type", "app_dialog")
            .put("title", "")
            .put("message", msg.toString().trim())
            .put("buttons", buttons))
    }

    private fun findFirstByClassNameContains(node: AccessibilityNodeInfo?, frag: String, maxDepth: Int, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > maxDepth) return null
        if (node.className?.toString()?.contains(frag) == true) return node
        for (i in 0 until node.childCount) findFirstByClassNameContains(node.getChild(i), frag, maxDepth, depth + 1)?.let { return it }
        return null
    }

    private fun collectClickableTexts(node: AccessibilityNodeInfo?, maxDepth: Int, depth: Int, out: JSONArray, registry: NodeRegistry) {
        if (node == null || depth > maxDepth) return
        val t = node.text?.toString()
        if (node.isClickable && !t.isNullOrEmpty()) {
            out.put(JSONObject().put("text", t).put("nodeId", registry.put(node)))
        }
        for (i in 0 until node.childCount) collectClickableTexts(node.getChild(i), maxDepth, depth + 1, out, registry)
    }

    private fun collectText(node: AccessibilityNodeInfo?, maxDepth: Int, depth: Int, out: StringBuilder) {
        if (node == null || depth > maxDepth) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.append(it).append(' ') }
        for (i in 0 until node.childCount) collectText(node.getChild(i), maxDepth, depth + 1, out)
    }

    private fun dialogDismiss(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val confirm = args.hasFlag("confirm")
        val deny = args.hasFlag("deny")
        val explicit = args.get("button")
        val candidates = when {
            explicit != null -> listOf(explicit)
            confirm -> listOf("确认", "确定", "允许", "好", "是", "OK", "Allow", "Confirm", "Yes")
            deny    -> listOf("拒绝", "取消", "否", "不允许", "Cancel", "Deny", "No")
            else    -> listOf("取消", "关闭", "否", "Cancel", "Close")
        }
        for (label in candidates) {
            val matches = ArrayList<AccessibilityNodeInfo>()
            for (root in svc.rootNodes()) findByTextOrDesc(root, label, contains = false, 30, 0, matches)
            val btn = matches.firstOrNull { it.isClickable } ?: matches.firstOrNull()
            if (btn != null) {
                val clicked = if (btn.isClickable) btn.performAction(AccessibilityNodeInfo.ACTION_CLICK) else {
                    val r = Rect(); btn.getBoundsInScreen(r)
                    svc.dispatchSimpleGesture(
                        Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()); lineTo(r.exactCenterX() + 0.1f, r.exactCenterY() + 0.1f) },
                        0L, 50L
                    )
                }
                if (clicked) return ok(args, JSONObject().put("dismissed", true).put("button", label))
            }
        }
        return err(args, "NODE_NOT_FOUND", "no matching dismiss button found (looked for: ${candidates.joinToString()})")
    }

    // ── extract ──────────────────────────────────────────────────────────

    private fun extractSub(args: OffloadArgs): NativeOffloadResult {
        return when (args.positional.getOrNull(1)) {
            null    -> NativeOffloadResult(2, EXTRACT_HELP)
            "text"  -> extractText(args)
            "list"  -> extractList(args)
            "table" -> err(args, "NOT_IMPLEMENTED", "extract table: not implemented in v1")
            "form"  -> extractForm(args)
            else    -> NativeOffloadResult(2, "$TOOL extract: unknown action\n$EXTRACT_HELP")
        }
    }

    private fun extractText(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val nodeId = args.get("node")
        val sep = args.get("separator") ?: "\n"
        val roots = if (nodeId != null) listOfNotNull(svc.nodeRegistry.get(nodeId)) else svc.rootNodes()
        if (roots.isEmpty()) return err(args, "NODE_NOT_FOUND", "no roots / node=$nodeId")
        val arr = JSONArray()
        val sb = StringBuilder()
        for (root in roots) collectVisibleText(root, 50, 0, arr, sb, sep)
        return if (args.get("format") == "json")
            ok(args, JSONObject().put("count", arr.length()).put("data", arr))
        else NativeOffloadResult(0, sb.toString())
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo?, maxDepth: Int, depth: Int, arr: JSONArray, sb: StringBuilder, sep: String) {
        if (node == null || depth > maxDepth) return
        if (node.isVisibleToUser) {
            val t = node.text?.toString()
            if (!t.isNullOrBlank()) {
                val r = Rect(); node.getBoundsInScreen(r)
                arr.put(JSONObject().put("text", t).put("bounds", JSONObject()
                    .put("left", r.left).put("top", r.top).put("right", r.right).put("bottom", r.bottom)))
                sb.append(t).append(sep)
            }
        }
        for (i in 0 until node.childCount) collectVisibleText(node.getChild(i), maxDepth, depth + 1, arr, sb, sep)
    }

    private fun extractList(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val nodeId = args.get("node")
        val maxItems = args.getInt("max-items") ?: 100
        val autoScroll = args.hasFlag("auto-scroll")
        val container: AccessibilityNodeInfo = (nodeId?.let { svc.nodeRegistry.get(it) }) ?: run {
            var found: AccessibilityNodeInfo? = null
            for (root in svc.rootNodes()) { found = firstScrollable(root, 30, 0); if (found != null) break }
            found ?: return err(args, "NODE_NOT_FOUND", "no scrollable container; pass --node")
        }
        val items = LinkedHashMap<String, JSONObject>()
        var iter = 0
        do {
            for (i in 0 until container.childCount) {
                val child = container.getChild(i) ?: continue
                val sb = StringBuilder()
                collectText(child, 5, 0, sb)
                val text = sb.toString().trim()
                if (text.isNotEmpty()) {
                    items.putIfAbsent(text, JSONObject().put("text", text).put("nodeId", svc.nodeRegistry.put(child)))
                }
                if (items.size >= maxItems) break
            }
            if (!autoScroll || items.size >= maxItems) break
            val scrolled = container.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            if (!scrolled) break
            Thread.sleep(400)
            iter++
        } while (iter < 30)
        val arr = JSONArray()
        for (v in items.values) arr.put(v)
        return ok(args, JSONObject().put("count", arr.length()).put("data", arr))
    }

    private fun extractForm(args: OffloadArgs): NativeOffloadResult {
        val svc = svcOrThrow()
        val nodeId = args.get("node")
        val roots = if (nodeId != null) listOfNotNull(svc.nodeRegistry.get(nodeId)) else svc.rootNodes()
        val arr = JSONArray()
        for (root in roots) collectFormFields(root, 30, 0, arr, svc.nodeRegistry)
        return ok(args, JSONObject().put("count", arr.length()).put("data", arr))
    }

    private fun collectFormFields(node: AccessibilityNodeInfo?, maxDepth: Int, depth: Int, arr: JSONArray, registry: NodeRegistry) {
        if (node == null || depth > maxDepth) return
        if (node.isEditable || node.isCheckable) {
            val obj = JSONObject().put("nodeId", registry.put(node))
                .put("type", node.className?.toString() ?: "")
                .put("label", node.contentDescription?.toString() ?: node.hintText?.toString() ?: "")
            if (node.isEditable) obj.put("value", node.text?.toString() ?: "")
            if (node.isCheckable) obj.put("checked", node.isChecked)
            arr.put(obj)
        }
        for (i in 0 until node.childCount) collectFormFields(node.getChild(i), maxDepth, depth + 1, arr, registry)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private class NotRunning(msg: String) : RuntimeException(msg)

    private fun svcOrThrow(): MinisAccessibilityService =
        MinisAccessibilityService.getInstance()
            ?: throw NotRunning("Accessibility service is not running. Enable Minis under Settings → Accessibility.")

    private fun ok(args: OffloadArgs, data: Any): NativeOffloadResult {
        val body = JSONObject().put("ok", true).put("data", data).toString()
        return NativeOffloadResult(0, OffloadOutput.formatBody(body, args) + "\n")
    }

    private fun err(args: OffloadArgs, code: String, message: String, exit: Int = 1): NativeOffloadResult {
        val body = JSONObject().put("ok", false).put("error",
            JSONObject().put("code", code).put("message", message)).toString()
        return NativeOffloadResult(exit, OffloadOutput.formatBody(body, args) + "\n")
    }

    /**
     * [T-bg-overlay phase 2 fix] Human-readable sub-action status surfaced
     * to the background overlay + FGS notification while the a11y CLI is
     * running. Format mirrors the CLI's own subcommand vocabulary so a
     * developer reading logs sees the same thing the agent ran.
     *
     * Examples:
     *   ui dump           → "a11y_cli: dumping UI tree"
     *   tap by-text Login → "a11y_cli: tap \"Login\""
     *   input set --text X → "a11y_cli: input text"
     *   scroll forward    → "a11y_cli: scroll forward"
     *   event watch       → "a11y_cli: watching events"
     */
    private fun statusForSubAction(sub: String, verb: String?, args: OffloadArgs): String {
        val v = verb ?: ""
        val target = args.positional.getOrNull(2)?.takeIf { it.isNotBlank() }
        return when (sub) {
            "ui" -> when (v) {
                "dump"       -> "a11y_cli: dumping UI tree"
                "find"       -> "a11y_cli: finding ${target ?: "element"}"
                "info"       -> "a11y_cli: reading UI info"
                "node"       -> "a11y_cli: reading node ${target ?: ""}".trim()
                "screenshot" -> "a11y_cli: capturing screenshot"
                else         -> "a11y_cli: ui"
            }
            "tap" -> when (v) {
                "by-text"     -> "a11y_cli: tap \"${target ?: ""}\""
                "by-id"       -> "a11y_cli: tap #${target ?: ""}"
                "by-node"     -> "a11y_cli: tap node ${target ?: ""}"
                "at"          -> "a11y_cli: tap at ($target)"
                else          -> "a11y_cli: tap"
            }
            "input" -> when (v) {
                "set"   -> "a11y_cli: input text"
                "clear" -> "a11y_cli: clearing input"
                else    -> "a11y_cli: input"
            }
            "scroll" -> when (v) {
                "forward", "backward", "up", "down" -> "a11y_cli: scroll $v"
                "to-text"                            -> "a11y_cli: scrolling to \"${target ?: ""}\""
                else                                 -> "a11y_cli: scroll"
            }
            "gesture"  -> "a11y_cli: gesture${v.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""}"
            "wait"     -> "a11y_cli: waiting for ${v.ifBlank { "condition" }}"
            "event"    -> if (v == "watch") "a11y_cli: watching events" else "a11y_cli: event $v"
            "notify"   -> if (v == "watch") "a11y_cli: watching notifications" else "a11y_cli: notify $v"
            "dialog"   -> "a11y_cli: dialog $v"
            "extract"  -> "a11y_cli: extracting ${v.ifBlank { "text" }}"
            "service"  -> "a11y_cli: service $v"
            else       -> "Running: a11y_cli $sub"
        }
    }

}

// ─── Merged from AlarmOffloadHandler.kt ──────────────────────────────────────────────────

/**
 * android-alarm — schedule alarms and timers, list, or cancel them.
 *
 * Mirrors apple-alarm (NativeOffloads/AlarmOffload.m) — same set/timer/
 * cancel CLI surface so prompts and the agent loop are platform-agnostic:
 *   android-alarm set --time HH:MM|ISO --label L [--repeat ONCE|DAILY|WEEKDAYS]
 *   android-alarm timer --duration <seconds|5m|1h> [--label L]
 *   android-alarm list
 *   android-alarm cancel --id <alarm_id>
 *   android-alarm cancel --all
 *
 * Legacy positional forms are kept as aliases so older prompts and
 * scripts continue to work without changes:
 *   android-alarm schedule <HH:MM> [...]
 *   android-alarm timer <seconds> [...]
 *   android-alarm cancel <alarm_id>
 */
class AlarmOffloadHandler(private val context: Context) : NativeOffloadHandler {
    // T268: AlarmOffloadManager dependency dropped — this handler is now a
    // pure intent-dispatch surface (system Clock's SET_ALARM / SET_TIMER /
    // SHOW_ALARMS). The manager + its SharedPreferences are still around so
    // the one-shot ghost migration in MinisApp.onCreate can flush any
    // pre-T266 entries; the manager will be deleted once that bake is over.

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help") || args.positional.isEmpty()) {
            return NativeOffloadResult(if (args.positional.isEmpty()) 2 else 0, HELP)
        }

        return try {
            when (val sub = args.positional[0]) {
                "set", "schedule" -> handleSet(args)
                "timer" -> handleTimer(args)
                "open", "list", "cancel" -> handleOpen(args)
                else -> NativeOffloadResult(2, "android-alarm: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: SecurityException) {
            AppLogger.warning(TAG, "SecurityException: ${e.message}")
            val body = JSONObject().put("error", "exact_alarm_denied")
                .put("message", "Exact alarms are blocked. On Android 14+ the user must grant 'Alarms & reminders' in Settings; on Xiaomi/Huawei/Oppo/OnePlus/Vivo, also enable autostart and disable battery optimization for Minis. Underlying: ${e.message}")
                .toString()
            NativeOffloadResult(77, OffloadOutput.formatBody(body, args) + "\n")
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            val body = JSONObject().put("error", "alarm_failed")
                .put("message", e.message ?: "unknown").toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    // ── Subcommand handlers ─────────────────────────────────────────────

    private fun handleSet(args: OffloadArgs): NativeOffloadResult {
        // Time can come from --time (preferred, apple-alarm parity) or as a
        // legacy positional second arg (`schedule HH:MM`).
        val timeStr = args.get("time") ?: args.positional.getOrNull(1)
            ?: return NativeOffloadResult(2, "android-alarm set: --time <HH:MM|ISO> is required\n")
        val (hour, minute) = parseTimeArg(timeStr)
            ?: return NativeOffloadResult(
                2,
                "android-alarm: invalid time '$timeStr' (expected HH:MM or ISO 8601)\n",
            )
        val label = args.get("label") ?: "Alarm"
        val repeat = args.get("repeat")?.uppercase() ?: "ONCE"
        val mode = runCatching { RepeatMode.valueOf(repeat) }.getOrNull()
            ?: return NativeOffloadResult(
                2,
                "android-alarm: invalid --repeat '$repeat' (use ONCE, DAILY, WEEKDAYS)\n",
            )
        // T266: single-source schedule via the system Clock app. The internal
        // AlarmOffloadManager.scheduleAlarm + SharedPreferences path is gone:
        // dual-writing produced ghost reminders that fired only inside Minis
        // and confused users who expected to manage everything in their phone
        // Clock UI. Any OEM that refuses ACTION_SET_ALARM (no Clock app, or a
        // permission-stripped fork) is now a hard error — no silent fallback.
        val systemErr = scheduleViaSystemClock(label, hour, minute, mode)
        if (systemErr != null) {
            val body = JSONObject().put("error", "system_clock_unavailable")
                .put("message", systemErr)
                .put("hint", "Re-enable or install a Clock app (e.g. Google Clock) and grant the SET_ALARM permission.")
                .toString()
            AppLogger.warning(TAG, "set: system Clock dispatch failed — $systemErr")
            return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
        val data = buildSetEnvelope(label, hour, minute, mode)
            .put("system_alarm", "ok")
        AppLogger.info(TAG, "set: time=$hour:$minute repeat=$mode label='$label' → system Clock")
        return emitEnvelope("set", data, args)
    }

    /**
     * T266: single-source schedule via the system Clock app
     * ([AlarmClock.ACTION_SET_ALARM] + `EXTRA_SKIP_UI=true`). Returns null
     * on success, or a human-readable error string on failure.
     *
     * Pixel / AOSP Clock honors SKIP_UI and silently inserts the alarm;
     * OEM forks may force a confirmation dialog (see [emitEnvelope]
     * oem_hint). On Android 11+ resolveActivity returns non-null because
     * AndroidManifest declares <queries> for SET_ALARM; the
     * com.android.alarm.permission.SET_ALARM permission is required (T262-1).
     *
     * Was previously called trySystemSchedule and returned
     * "ok" | "fallback_internal_only" so the caller could degrade to a
     * Minis-internal AlarmManager copy. T266 retired that fallback —
     * dual-writing produced ghost alarms that fired only inside Minis
     * and confused users who managed everything in the phone Clock UI.
     */
    private fun scheduleViaSystemClock(
        label: String,
        hour: Int,
        minute: Int,
        mode: RepeatMode,
    ): String? {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            // EXTRA_DAYS expects ArrayList<Int> of Calendar.SUNDAY..SATURDAY.
            // ONCE → omit; the system Clock interprets absence as a one-shot
            // alarm scheduled for the next matching HH:MM (today or tomorrow).
            when (mode) {
                RepeatMode.DAILY -> putExtra(
                    AlarmClock.EXTRA_DAYS,
                    arrayListOf(
                        Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY,
                        Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY,
                        Calendar.SATURDAY,
                    ),
                )
                RepeatMode.WEEKDAYS -> putExtra(
                    AlarmClock.EXTRA_DAYS,
                    arrayListOf(
                        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                        Calendar.THURSDAY, Calendar.FRIDAY,
                    ),
                )
                RepeatMode.ONCE -> Unit
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            null
        } catch (e: ActivityNotFoundException) {
            "No system Clock app handles ACTION_SET_ALARM. Install or re-enable a Clock app."
        } catch (e: SecurityException) {
            // T262-1: deskclock's HandleSetApiCalls is permission-protected
            // (com.android.alarm.permission.SET_ALARM, normal protection).
            // If this fires the manifest declaration regressed.
            "System Clock refused the alarm — SET_ALARM permission missing or revoked: ${e.message}"
        } catch (e: Throwable) {
            "System Clock dispatch failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun handleTimer(args: OffloadArgs): NativeOffloadResult {
        // Duration can come from --duration (apple-alarm parity, accepts
        // shorthand) or as a legacy positional second arg (`timer 300`).
        val durationStr = args.get("duration") ?: args.positional.getOrNull(1)
            ?: return NativeOffloadResult(
                2,
                "android-alarm timer: --duration <seconds|5m|1h> is required\n",
            )
        val secs = parseDuration(durationStr)
            ?: return NativeOffloadResult(
                2,
                "android-alarm: invalid duration '$durationStr' (use seconds or shorthand 30s, 5m, 1h, 2d)\n",
            )
        if (secs <= 0) return NativeOffloadResult(2, "android-alarm: duration must be positive\n")
        val label = args.get("label") ?: "Timer"
        // T266b: single-source timer via the system Clock app
        // (AlarmClock.ACTION_SET_TIMER, API 19+). Same rationale as T266 set:
        // the previous internal AlarmManager.setExactAndAllowWhileIdle path
        // produced timers that fired only inside Minis with no Clock UI to
        // pause/resume/dismiss. Pixel/Google Clock honors EXTRA_SKIP_UI for
        // SET_TIMER too — verified via pm resolve-activity returning
        // com.google.android.deskclock/.HandleSetApiCalls on Pixel 4a + 6.
        val systemErr = startSystemTimer(label, secs)
        if (systemErr != null) {
            val body = JSONObject().put("error", "system_clock_unavailable")
                .put("message", systemErr)
                .put("hint", "Re-enable or install a Clock app (e.g. Google Clock) and grant the SET_ALARM permission.")
                .toString()
            AppLogger.warning(TAG, "timer: system Clock dispatch failed — $systemErr")
            return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
        val data = buildTimerEnvelope(label, secs)
            .put("system_alarm", "ok")
        AppLogger.info(TAG, "timer: duration=${secs}s label='$label' → system Clock")
        return emitEnvelope("timer", data, args)
    }

    /**
     * T266b: dispatch [AlarmClock.ACTION_SET_TIMER] with EXTRA_LENGTH +
     * EXTRA_MESSAGE + EXTRA_SKIP_UI=true. Returns null on success, or a
     * human-readable error string on failure. Mirrors
     * [scheduleViaSystemClock] for alarms.
     */
    private fun startSystemTimer(label: String, durationSec: Int): String? {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, durationSec)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            null
        } catch (e: ActivityNotFoundException) {
            "No system Clock app handles ACTION_SET_TIMER. Install or re-enable a Clock app."
        } catch (e: SecurityException) {
            "System Clock refused the timer — SET_ALARM permission missing or revoked: ${e.message}"
        } catch (e: Throwable) {
            "System Clock dispatch failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * T268: list / cancel / open all converge on the same action — launch
     * the system Clock app's alarm view via [AlarmClock.ACTION_SHOW_ALARMS].
     * The system Clock does not expose a stable query API for third-party
     * inspection (`AlarmClock.ACTION_DISMISS_ALARM` only targets the
     * currently-ringing alarm), so the agent cannot enumerate or
     * selectively cancel; the user manages everything from the Clock UI.
     * `list` and `cancel` are kept as aliases of `open` purely so older
     * prompts and shell scripts don't break — they print the same body.
     */
    private fun handleOpen(args: OffloadArgs): NativeOffloadResult {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            AppLogger.info(TAG, "open: launched system Clock SHOW_ALARMS")
            val data = JSONObject()
                .put("opened", true)
                .put("hint", "System Clock launched. Tell the user to view, edit, or cancel alarms in the Clock app's Alarms tab (or Timers tab for timers). Minis cannot enumerate or cancel alarms programmatically — Android's Clock API is fire-and-forget.")
            emitEnvelope("open", data, args)
        } catch (e: ActivityNotFoundException) {
            val body = JSONObject().put("error", "no_clock_app")
                .put("message", "No Clock app handles ACTION_SHOW_ALARMS. Install or re-enable a Clock app to manage alarms.")
                .toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        } catch (e: Throwable) {
            val body = JSONObject().put("error", "open_failed")
                .put("message", e.message ?: e.javaClass.simpleName)
                .toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    private fun parseHHMM(s: String): Pair<Int, Int>? {
        val parts = s.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h to m
    }

    /**
     * Accept HH:MM (parsed against today's calendar) or ISO 8601 like
     * "2026-02-25T14:00", "2026-02-25T14:00:00Z". Both forms collapse to the
     * (hour, minute) pair the manager expects — manager will roll forward to
     * the next day if HH:MM is already past. Mirrors apple-alarm parseTime.
     */
    private fun parseTimeArg(s: String): Pair<Int, Int>? {
        parseHHMM(s)?.let { return it }
        // Try a few common ISO 8601 forms.
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
        )
        for (pat in patterns) {
            val date = runCatching {
                val sdf = SimpleDateFormat(pat, Locale.US)
                if (pat.endsWith("'Z'")) sdf.timeZone = TimeZone.getTimeZone("UTC")
                sdf.parse(s)
            }.getOrNull() ?: continue
            val cal = Calendar.getInstance().apply { time = date }
            return cal.get(Calendar.HOUR_OF_DAY) to cal.get(Calendar.MINUTE)
        }
        return null
    }

    /**
     * Mirror apple-alarm `parseDuration`: accept raw seconds or shorthand
     * 30s / 5m / 1h / 2d. Returns total seconds, or null on bad input.
     */
    private fun parseDuration(s: String): Int? {
        val trimmed = s.trim().lowercase()
        if (trimmed.isEmpty()) return null
        trimmed.toIntOrNull()?.let { return it }
        if (trimmed.length < 2) return null
        val unit = trimmed.last()
        val num = trimmed.dropLast(1).toIntOrNull() ?: return null
        return when (unit) {
            's' -> num
            'm' -> num * 60
            'h' -> num * 3600
            'd' -> num * 86400
            else -> null
        }
    }

    /**
     * Format a Date as ISO 8601 `yyyy-MM-dd'T'HH:mm:ssXXX` to match
     * apple-alarm `noff_format_date`. Stable across locales.
     */
    private fun formatIso(date: Date): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        return sdf.format(date)
    }

    private fun buildSetEnvelope(
        label: String,
        hour: Int,
        minute: Int,
        mode: RepeatMode,
    ): JSONObject {
        // T266: id no longer threaded through — system Clock owns the
        // identity and there's no Minis-side equivalent to surface. Compute
        // the actual fire time (today vs tomorrow roll-forward) so the
        // model sees a real timestamp not just HH:MM.
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_YEAR, 1)
        }
        return JSONObject().apply {
            put("label", label)
            put("time", formatIso(cal.time))
            put("hour", hour)
            put("minute", minute)
            put("repeat", mode.name)
            put("view_url", VIEW_URL)
            put("hint", "Alarm saved to the system Clock app. Open the Clock app, or $VIEW_URL inside Minis, to view or cancel.")
        }
    }

    private fun buildTimerEnvelope(label: String, durationSec: Int): JSONObject {
        // T266b: id no longer threaded through — system Clock owns the
        // identity. fires_at remains useful so the agent can describe
        // when the timer will go off.
        val firesAt = Date(System.currentTimeMillis() + durationSec * 1000L)
        return JSONObject().apply {
            put("label", label)
            put("duration_seconds", durationSec)
            put("fires_at", formatIso(firesAt))
            put("view_url", VIEW_URL)
            put("hint", "Timer started in the system Clock app. Open the Clock app's Timer tab, or $VIEW_URL inside Minis, to view or stop.")
        }
    }

    /**
     * Emit an apple-alarm-style envelope: `{ok:true, tool, action, data}`.
     * formatBody handles --compact / -q so nothing extra to do here.
     * OEM hint is appended as a note string on `data` so it survives quiet
     * extraction (which keeps only `data`).
     */
    private fun emitEnvelope(action: String, data: JSONObject, args: OffloadArgs): NativeOffloadResult {
        val needsHint = OsCompat.isXiaomi || OsCompat.isHuawei ||
            Build.MANUFACTURER.equals("OPPO", ignoreCase = true) ||
            Build.MANUFACTURER.equals("OnePlus", ignoreCase = true) ||
            Build.MANUFACTURER.equals("Vivo", ignoreCase = true)
        if (needsHint && (action == "set" || action == "timer")) {
            data.put(
                "oem_hint",
                "${OsCompat.oemLabel()} aggressively kills background alarms. Please enable Autostart and disable battery optimization for Minis in system settings to keep this alarm reliable.",
            )
        }
        val envelope = JSONObject()
            .put("ok", true)
            .put("tool", "android-alarm")
            .put("action", action)
            .put("data", data)
        return NativeOffloadResult(0, OffloadOutput.formatBody(envelope.toString(2), args) + "\n")
    }

    private fun wrap(text: String, args: OffloadArgs): NativeOffloadResult {
        val err = text.startsWith("Error")
        val formatted = OffloadOutput.formatBody(text.trimEnd('\n'), args)
        return NativeOffloadResult(if (err) 1 else 0, "$formatted\n")
    }

    companion object {
        private const val TAG = "AlarmOffload"
        private const val VIEW_URL = "minis://views/alarm"
        private const val HELP = """android-alarm — schedule alarms and timers (mirrors apple-alarm)

Usage:
  android-alarm set --time <HH:MM|ISO> [--label L] [--repeat ONCE|DAILY|WEEKDAYS]
  android-alarm timer --duration <seconds|5m|1h> [--label L]
  android-alarm open

T266+T268: alarms and timers are written into the user's Android
system Clock app (single source of truth). The agent cannot
enumerate or selectively cancel alarms because the system Clock
does not expose a query API — instruct the user to view/edit/cancel
in the Clock app's Alarms or Timers tab. `android-alarm open`
launches Clock to that screen; `list` and `cancel` remain as aliases
that do the same thing.

Legacy aliases:
  android-alarm schedule <HH:MM> [--label L] [--repeat ...]
  android-alarm timer <seconds> [--label L]
  android-alarm list             (alias of open)
  android-alarm cancel ...       (alias of open)

Examples:
  android-alarm set --time 07:30 --label "Wake up" --repeat DAILY
  android-alarm set --time 2026-02-25T14:00
  android-alarm timer --duration 5m --label "Tea"
  android-alarm open

set/timer return JSON with `view_url: minis://views/alarm` and
`system_alarm: "ok"` on success.
"""
    }
}

// ─── Merged from ClipboardOffloadHandler.kt ──────────────────────────────────────────────────

/**
 * android-clipboard — get, set, clear, or query the device clipboard.
 *
 * T65: re-aligned with iOS apple-clipboard (NativeOffloads/ClipboardOffload.m).
 * Adds:
 *   - `status` subcommand mirroring iOS — returns `has_content`, `types`,
 *     `item_count` so the model can probe before reading.
 *   - `--text <value>` named flag on `set` — preferred over the legacy
 *     positional form (`android-clipboard set Hello world`) so multi-word
 *     text without explicit quoting works without surprising tokenisation.
 *     The legacy positional form still works for back-compat.
 *
 * Android 10+ restricts clipboard reads from background — `primaryClip`
 * returns null unless the app has window focus or is the default IME. We
 * detect that case and return a specific error so the caller knows the
 * failure is recoverable by foregrounding the app rather than a permission
 * problem.
 *
 * Usage:
 *   android-clipboard get
 *   android-clipboard set --text "Hello world"
 *   android-clipboard set Hello world         # legacy positional, still works
 *   android-clipboard clear
 *   android-clipboard status
 */
class ClipboardOffloadHandler(private val context: Context) : NativeOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        // [T-offload-defaults-batch-android] Help only on explicit
        // --help/-h; no subcommand defaults to `get`.
        if (args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, HELP)
        }
        // T330: tri-state agent gate before any work.
        OffloadGate.enforce("clipboard", "android-clipboard", args, request)?.let { return it }

        val cm = try {
            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        } catch (_: Throwable) { null }
            ?: run {
                val body = JSONObject().put("error", "service_unavailable")
                    .put("message", "ClipboardManager not available on this device.")
                    .toString()
                return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
            }

        return try {
            when (val sub = args.positional.firstOrNull() ?: "get") {
                "get" -> doGet(cm, args)
                "set" -> doSet(cm, args)
                "clear" -> doClear(cm, args)
                "status" -> doStatus(cm, args)
                else -> NativeOffloadResult(2, "android-clipboard: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException: ${e.message}")
            val body = JSONObject().put("error", "clipboard_denied")
                .put("message", "Clipboard access was denied by the system. On MIUI/HyperOS the user may have revoked clipboard permission in the privacy panel.")
                .toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        } catch (e: Throwable) {
            Log.w(TAG, "uncaught: ${e.message}", e)
            val body = JSONObject().put("error", "internal").put("message", e.message ?: "unknown").toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    // ── Subcommands ──────────────────────────────────────────────────────────

    private fun doGet(cm: ClipboardManager, args: OffloadArgs): NativeOffloadResult {
        if (!isAppForeground() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return backgroundError(args)
        }
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) {
            return NativeOffloadResult(0, "")
        }
        // Backwards-compat: get's primary output is still the raw clipboard
        // text (lots of pipe consumers depend on it). The iOS version emits
        // structured JSON; we only switch to JSON when --json is requested
        // so existing prompts (`text=$(android-clipboard get)`) keep working.
        val text = try { clip.getItemAt(0).coerceToText(context).toString() } catch (_: Throwable) { "" }
        val body = OffloadOutput.formatBody(text.trimEnd('\n'), args)
        return NativeOffloadResult(0, "$body\n")
    }

    private fun doSet(cm: ClipboardManager, args: OffloadArgs): NativeOffloadResult {
        // T65: --text is the preferred form; falls back to the legacy
        // positional form for back-compat. Whichever path delivers
        // non-blank text wins.
        val text = args.get("text") ?: args.positional.drop(1).joinToString(" ")
        if (text.isBlank()) {
            return NativeOffloadResult(
                2,
                "android-clipboard set: missing <text> (use --text \"...\" or pass positional args)\n",
            )
        }
        if (!isAppForeground() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return backgroundError(args)
        }
        val label = args.get("label") ?: "Minis"
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        return NativeOffloadResult(
            0,
            OffloadOutput.formatBody("Copied ${text.length} characters to clipboard.", args) + "\n",
        )
    }

    private fun doClear(cm: ClipboardManager, args: OffloadArgs): NativeOffloadResult {
        if (!isAppForeground()) return backgroundError(args)
        cm.setPrimaryClip(ClipData.newPlainText("", ""))
        return NativeOffloadResult(0, OffloadOutput.formatBody("Clipboard cleared.", args) + "\n")
    }

    /**
     * iOS apple-clipboard `status` returns:
     *   {has_content, types, item_count, change_count}
     *
     * Android port:
     *   - `has_content` — true iff there's a primary clip with at least 1 item
     *   - `types` — list of MIME types from the first item's clip description
     *     (Android exposes MIME types directly, more precise than iOS's
     *     coarse "strings/urls/images" buckets — we still bucket the
     *     iOS-style values into a parallel `categories` array so prompts
     *     learned for iOS keep working without modification)
     *   - `item_count` — `primaryClip.itemCount`
     *   - `change_count` — Android doesn't expose a clipboard change counter,
     *     omitted (the field is simply not in the JSON body, not zero,
     *     so prompts can detect platform difference)
     */
    private fun doStatus(cm: ClipboardManager, args: OffloadArgs): NativeOffloadResult {
        if (!isAppForeground() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return backgroundError(args)
        }
        val clip = cm.primaryClip
        val description = cm.primaryClipDescription
        val itemCount = clip?.itemCount ?: 0
        val mimeTypes = JSONArray()
        val categories = JSONArray()
        var hasStrings = false
        var hasUrls = false
        var hasImages = false
        if (description != null) {
            for (i in 0 until description.mimeTypeCount) {
                val mime = description.getMimeType(i)
                mimeTypes.put(mime)
                when {
                    mime.startsWith("text/") || mime == ClipDescription_MIMETYPE_TEXT_PLAIN -> hasStrings = true
                    mime == ClipDescription_MIMETYPE_TEXT_URILIST -> hasUrls = true
                    mime.startsWith("image/") -> hasImages = true
                }
            }
        }
        if (hasStrings) categories.put("strings")
        if (hasUrls) categories.put("urls")
        if (hasImages) categories.put("images")
        val hasContent = itemCount > 0 && (hasStrings || hasUrls || hasImages || mimeTypes.length() > 0)
        val body = JSONObject()
            .put("has_content", hasContent)
            .put("has_strings", hasStrings)
            .put("has_urls", hasUrls)
            .put("has_images", hasImages)
            .put("types", categories)
            .put("mime_types", mimeTypes)
            .put("item_count", itemCount)
            .toString()
        return NativeOffloadResult(0, OffloadOutput.formatBody(body, args) + "\n")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun isAppForeground(): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return true  // can't tell — assume OK, let the actual call surface errors
            val procs = am.runningAppProcesses.orEmpty()
            val pid = android.os.Process.myPid()
            procs.any { it.pid == pid && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
        } catch (_: Throwable) {
            true
        }
    }

    private fun backgroundError(args: OffloadArgs) = NativeOffloadResult(
        1,
        OffloadOutput.formatBody(
            JSONObject().put("error", "clipboard_requires_foreground")
                .put("message", "Android 10+ blocks clipboard access when the app is not in the foreground. Ask the user to bring Minis to the foreground and retry.")
                .toString(),
            args,
        ) + "\n",
    )

    companion object {
        private const val TAG = "ClipboardOffload"
        // Local copies of android.content.ClipDescription constants so the
        // status switch reads cleanly without an extra import.
        private const val ClipDescription_MIMETYPE_TEXT_PLAIN = "text/plain"
        private const val ClipDescription_MIMETYPE_TEXT_URILIST = "text/uri-list"
        private const val HELP = """android-clipboard — access the device clipboard

Usage:
  android-clipboard <command> [options]

Running without a subcommand is the same as `get` (default subcommand).

COMMANDS:
  get                  Print current clipboard text
  set --text "<text>"  Copy text to clipboard (preferred form)
  set <text...>        Copy text to clipboard (legacy positional form)
  clear                Empty the clipboard
  status               Print clipboard content types + item count

OPTIONS:
  --text <value>       Text to write (for `set`)
  --label <name>       Optional ClipData label (default: Minis)
  --help, -h           Show this help message
  --compact            Minimize JSON output
  -q, --quiet          Output only data field

EXAMPLES:
  android-clipboard get
  android-clipboard set --text "Hello world"
  android-clipboard set Hello world
  android-clipboard clear
  android-clipboard status

Android 10+ blocks clipboard access in the background — errors include
{"error":"clipboard_requires_foreground"} when the app is not focused.
"""
    }
}

// ─── Merged from OffloadArgs.kt ──────────────────────────────────────────────────

/**
 * Tiny long-option parser shared by android-* offload handlers.
 *
 * Supports:
 *   --flag               boolean flag (returned by [hasFlag])
 *   --key value          value read by [get]
 *   --key=value          value read by [get]
 *   positional args      [positional]
 *
 * Short flags (`-h`) are matched literally by [hasFlag].
 */
internal class OffloadArgs(argv: List<String>, booleanFlags: Set<String> = emptySet()) {
    val positional: List<String>
    private val flags: Set<String>
    private val values: Map<String, String>

    init {
        // T54: every android-* command supports --compact / -q / --quiet for
        // output shaping (see OffloadOutput). Always declare these as boolean
        // flags so e.g. `--compact list` parses as flag + positional rather
        // than `--compact=list` greedy consumption. Recognized at parser level
        // so handlers that haven't been wired through OffloadOutput.formatBody
        // yet still reject the flag's value as a no-op rather than as a
        // missing-positional error.
        val effectiveBooleanFlags = booleanFlags + OffloadOutput.OUTPUT_FLAGS
        val pos = mutableListOf<String>()
        val fs = mutableSetOf<String>()
        val vs = mutableMapOf<String, String>()
        var i = 0
        while (i < argv.size) {
            val a = argv[i]
            when {
                a.startsWith("--") && a.contains('=') -> {
                    val eq = a.indexOf('=')
                    vs[a.substring(2, eq)] = a.substring(eq + 1)
                }
                a.startsWith("--") -> {
                    val key = a.substring(2)
                    val next = argv.getOrNull(i + 1)
                    // A flag is treated as boolean when (a) the caller declared
                    // it as such, or (b) the next token is missing / itself a
                    // flag. Without the declared-boolean set, `--compact foo`
                    // would greedily consume `foo` as the value of `--compact`
                    // and swallow the positional.
                    if (key in effectiveBooleanFlags || next == null || next.startsWith("-")) {
                        fs.add(key)
                    } else {
                        vs[key] = next
                        i++
                    }
                }
                a.startsWith("-") && a.length > 1 -> fs.add(a.substring(1))
                else -> pos.add(a)
            }
            i++
        }
        positional = pos
        flags = fs
        values = vs
    }

    fun hasFlag(vararg names: String): Boolean = names.any { it in flags }
    fun get(vararg names: String): String? = names.firstNotNullOfOrNull { values[it] }
    fun getInt(vararg names: String): Int? = get(*names)?.toIntOrNull()
    fun getLong(vararg names: String): Long? = get(*names)?.toLongOrNull()
    fun getDouble(vararg names: String): Double? = get(*names)?.toDoubleOrNull()
    fun getBool(vararg names: String): Boolean? =
        get(*names)?.lowercase()?.let { it == "true" || it == "1" || it == "yes" }
}

// ─── Merged from OffloadOutput.kt ──────────────────────────────────────────────────

/**
 * Centralized output filter for `android-*` offload commands.
 *
 * Mirrors iOS `noff_emit_json` (NativeOffloadUtils.m:159) — the same
 * `--compact` / `-q` / `--quiet` flags work uniformly across every
 * command, so an agent loop can rely on consistent shaping no matter
 * which tool it invoked.
 *
 * Behavior contract:
 *   - Default (no flags): pass [body] through unchanged. Existing agent
 *     flows that scrape free-form output keep working.
 *   - `--compact`: if [body] parses as JSON (object or array), re-emit
 *     it as a single line with no indent. Plain-text bodies pass
 *     through.
 *   - `-q` / `--quiet`: if [body] parses as a JSONObject with the iOS
 *     envelope shape `{ok, data, error, ...}`, return only the `data`
 *     field (success) or the `error` field (failure). For raw JSON
 *     without an envelope, or plain-text bodies, pass through.
 *   - Both flags compose: extract the data/error field first, then
 *     compact-serialize the result.
 *
 * Handlers feed `body` without a trailing newline; the formatter never
 * adds one — the caller appends `"\n"` when wrapping in
 * [com.openminis.app.sandbox.NativeOffloadResult], matching the
 * existing pattern.
 */
internal object OffloadOutput {
    private const val TAG = "OffloadOutput"

    /**
     * Boolean-flag names recognized by every android-* command. [OffloadArgs]
     * always merges this set into its [booleanFlags] argument so e.g.
     * `--compact list` parses as flag + positional rather than swallowing
     * `list` as the value of `--compact`.
     */
    val OUTPUT_FLAGS: Set<String> = setOf("compact", "quiet", "q")

    /**
     * Apply the active output flags to a finished body. Caller passes the
     * full string it would otherwise emit (sans trailing newline); the
     * return is the string to emit instead.
     */
    fun formatBody(body: String, args: OffloadArgs): String {
        val compact = args.hasFlag("compact")
        val quiet = args.hasFlag("q", "quiet")
        if (!compact && !quiet) return body
        val trimmed = body.trimEnd('\n')

        // Try JSONObject first, then JSONArray. Plain text falls through.
        val obj = parseObjectOrNull(trimmed)
        if (obj != null) {
            val payload: Any = if (quiet) extractEnvelopeField(obj) ?: obj else obj
            return serialize(payload, compact)
        }
        val arr = parseArrayOrNull(trimmed)
        if (arr != null) {
            // Quiet on an array is a no-op (no envelope to strip).
            return serialize(arr, compact)
        }
        // Plain text — neither flag applies.
        return body
    }

    /** iOS envelope: `{ok:true, data: ...}` or `{ok:false, error: ...}`.
     *  We accept either field independently — older / partial envelopes
     *  may carry just `data` or just `error`. */
    private fun extractEnvelopeField(obj: JSONObject): Any? {
        if (obj.has("error")) return obj.opt("error")
        if (obj.has("data")) return obj.opt("data")
        return null
    }

    private fun parseObjectOrNull(s: String): JSONObject? {
        val first = s.firstOrNull { !it.isWhitespace() } ?: return null
        if (first != '{') return null
        return try { JSONObject(s) } catch (_: Exception) { null }
    }

    private fun parseArrayOrNull(s: String): JSONArray? {
        val first = s.firstOrNull { !it.isWhitespace() } ?: return null
        if (first != '[') return null
        return try { JSONArray(s) } catch (_: Exception) { null }
    }

    private fun serialize(payload: Any, compact: Boolean): String = try {
        when (payload) {
            is JSONObject -> if (compact) payload.toString() else payload.toString(2)
            is JSONArray  -> if (compact) payload.toString() else payload.toString(2)
            is String     -> payload
            else          -> payload.toString()
        }
    } catch (e: Exception) {
        AppLogger.warning(TAG, "serialize failed: ${e.message}")
        payload.toString()
    }
}

// ─── Merged from OpenOffloadHandler.kt ──────────────────────────────────────────────────

/**
 * Host-side implementation of `android-open <url>` — launches any URL or
 * URI the Android system knows how to resolve (https, tel, mailto, geo,
 * market, intent, etc.).
 *
 * Includes a few OEM-aware fallbacks:
 *   • `market:` URIs on Huawei / no-GMS devices → redirect to AppGallery.
 *   • Any `ActivityNotFoundException` → structured error naming the
 *     missing handler instead of crashing.
 *
 * Usage:
 *   android-open <url>
 *   android-open --help
 */
class OpenOffloadHandler(private val context: Context) : NativeOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) return NativeOffloadResult(0, HELP)

        val url = args.positional.firstOrNull()
        if (url.isNullOrBlank()) {
            return NativeOffloadResult(2, "android-open: missing <url>\n$HELP")
        }

        return tryOpen(url, args)
    }

    private fun tryOpen(url: String, args: OffloadArgs): NativeOffloadResult {
        return try {
            // T259: intent: / android-app: URIs encode action + categories +
            // extras + flags inline (e.g. ACTION_SET_ALARM with HOUR/MINUTES
            // extras). Wrapping them in ACTION_VIEW threw all of that away,
            // so resolveActivity returned null and the call fell through to
            // the no_handler error branch. Use Intent.parseUri to honor the
            // full URI semantics; ACTION_VIEW path stays for plain http/tel
            // /mailto/geo/market URLs.
            val intent: Intent = if (url.startsWith("intent:") || url.startsWith("android-app:")) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    // Browsers inject a selector to bind the URI to a
                    // specific package; we already trust the model-supplied
                    // intent, so let the system-wide chooser apply.
                    selector = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val resolved = intent.resolveActivity(context.packageManager)
            Log.d(TAG, "startActivity url='$url' action=${intent.action} resolved=${resolved?.flattenToShortString()}")
            context.startActivity(intent)
            NativeOffloadResult(0, OffloadOutput.formatBody("Opened: $url", args) + "\n")
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no handler for '$url'")
            maybeFallback(url, e, args)
        } catch (e: SecurityException) {
            val body = JSONObject().put("error", "open_denied")
                .put("message", "The system refused to open this URL: ${e.message}")
                .put("url", url)
                .toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        } catch (e: java.net.URISyntaxException) {
            // T259: parseUri rejected a malformed intent: URI before any
            // resolveActivity call — surface as bad input, not no_handler,
            // so the model knows to fix the URI shape.
            Log.w(TAG, "parseUri failed for '$url': ${e.message}")
            val body = JSONObject().put("error", "bad_intent_uri")
                .put("message", "Malformed intent: URI: ${e.message}")
                .put("url", url)
                .toString()
            NativeOffloadResult(2, OffloadOutput.formatBody(body, args) + "\n")
        } catch (e: Throwable) {
            Log.w(TAG, "uncaught: ${e.message}", e)
            val body = JSONObject().put("error", "open_failed")
                .put("message", e.message ?: "Failed to open URL.")
                .put("url", url)
                .toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    /**
     * Fallback rules for URLs that couldn't be resolved. Returns a friendly
     * error message with a hint the model can relay to the user.
     */
    private fun maybeFallback(url: String, original: ActivityNotFoundException, args: OffloadArgs): NativeOffloadResult {
        // market: → Huawei AppGallery on HMS-only devices
        if (url.startsWith("market://") && OsCompat.isHuawei) {
            val pkg = Uri.parse(url).getQueryParameter("id")
                ?: url.substringAfter("details?id=").substringBefore('&')
            if (pkg.isNotBlank()) {
                val ag = "https://appgallery.huawei.com/app/$pkg"
                Log.d(TAG, "market: redirect → AppGallery $ag")
                return tryOpen(ag, args)
            }
        }

        val schemeHint = when {
            url.startsWith("mailto:") -> "no email app installed"
            url.startsWith("tel:") -> "no phone/dialer app installed"
            url.startsWith("sms:") -> "no SMS app installed"
            url.startsWith("geo:") -> "no maps app installed"
            url.startsWith("market:") -> "no app store installed"
            url.startsWith("http:") || url.startsWith("https:") -> "no browser installed"
            else -> "no app can handle this URI scheme"
        }
        val body = JSONObject().put("error", "no_handler")
            .put("message", "Cannot open '$url': $schemeHint. Ask the user to install a compatible app.")
            .put("url", url)
            .put("detail", original.message ?: "ActivityNotFoundException")
            .toString()
        return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
    }

    companion object {
        private const val TAG = "OpenOffload"
        private const val HELP = """android-open — open a URL or URI with the system handler

Usage:
  android-open <url>
  android-open --help

Supports https://, tel:, mailto:, geo:, market:, intent:, and any other
scheme a device app can handle. On Huawei devices without Play Store,
market:// URLs automatically fall back to AppGallery.
Errors are returned as JSON: {"error":"no_handler","message":"...","url":"..."}.
"""
    }
}

// ─── Merged from OsCompat.kt ──────────────────────────────────────────────────

/**
 * Runtime OS-compatibility helpers shared by android-* offload handlers.
 *
 * Focused on detecting environments where an offload cannot work at all
 * so the handler can fail fast with a clear message instead of crashing.
 */
internal object OsCompat {

    /**
     * Detect HarmonyOS NEXT (aka HarmonyOS 5 / "Pure Blood Harmony").
     * NEXT drops the AOSP compatibility layer, so normal Android APIs
     * don't exist — but reflection-based SystemProperties lookup still
     * works from within the APK runtime shim on pre-5 devices.
     *
     * Heuristic: package manager reports Harmony features or the
     * ro.build.version.harmony / hw_sc.build.os.enable props look recent.
     * Conservative — we only return true when multiple signals align.
     */
    fun isHarmonyOsNext(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val hasHarmony = pm.hasSystemFeature("com.huawei.software.features.ohos") ||
                pm.hasSystemFeature("ohos.system.harmony")
            if (!hasHarmony) return false
            val harmonyVer = systemProp("hw_sc.build.os.enable")
                .ifEmpty { systemProp("ro.build.version.harmony") }
            // NEXT identifies as 5.x; EMUI/Harmony ≤4 still have AOSP.
            harmonyVer.startsWith("5") || harmonyVer.startsWith("6")
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Read an Android system property via reflection. Returns "" on any failure.
     */
    fun systemProp(key: String): String {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val m = cls.getMethod("get", String::class.java)
            (m.invoke(null, key) as? String).orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    /** Is this a Xiaomi MIUI / HyperOS device? */
    val isXiaomi: Boolean
        get() = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
            Build.MANUFACTURER.equals("Redmi", ignoreCase = true)

    /** Is this a Huawei / Honor device? */
    val isHuawei: Boolean
        get() = Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true) ||
            Build.MANUFACTURER.equals("HONOR", ignoreCase = true)

    /** Build a short label like "Xiaomi MIUI" / "Samsung One UI" for error messages. */
    fun oemLabel(): String {
        val mfr = Build.MANUFACTURER.ifBlank { "Android" }
        return mfr.replaceFirstChar { it.titlecase() }
    }
}

// ─── Merged from SessionsUpdateRules.kt ──────────────────────────────────────────────────

/**
 * [T-sessions-cli-update] The `minis-sessions-cli update / groups / list
 * --group` contract, ported one-to-one from iOS `SessionsUpdateRules`
 * (SessionsOffloadBridge.swift), so the two platforms validate, match
 * groups and report changes identically.
 */
object SessionsUpdateRules {

    /**
     * The session icon keys: the session's category, the 16 tiles of the Edit
     * Session sheet (SessionListScreen.allCategories, iOS
     * SessionEditSheet.categories). "" = the default icon. Matched after
     * trimming and case-insensitively (src/shared/cli/sessions-update.md 2.1).
     */
    val ICON_NAMES: List<String> = listOf(
        "code", "writing", "research", "analysis", "creative", "chat",
        "math", "translation", "health", "finance", "travel", "education",
        "design", "productivity", "support", "other",
    )

    const val INVALID_ARGUMENTS = "invalid_arguments"
    const val INVALID_ICON = "invalid_icon"
    const val SESSION_NOT_FOUND = "session_not_found"
    const val NOT_ALLOWED = "not_allowed"
    const val TITLE_AUTO_FAILED = "title_auto_failed"

    data class Request(
        val title: String? = null,
        val titleAuto: Boolean = false,
        val group: String? = null,
        val icon: String? = null,
    )

    data class Failure(val code: String, val message: String)

    /**
     * Argument errors, checked before the session is looked up, in this
     * order: no field, --title with --title-auto, empty --title, bad --icon.
     */
    fun validate(r: Request): Failure? {
        if (r.title == null && !r.titleAuto && r.group == null && r.icon == null) {
            return Failure(INVALID_ARGUMENTS, "update needs at least one of --title, --title-auto, --group, --icon.")
        }
        if (r.title != null && r.titleAuto) {
            return Failure(INVALID_ARGUMENTS, "--title and --title-auto cannot be used together.")
        }
        if (r.title != null && normalizedTitle(r.title).isEmpty()) {
            return Failure(INVALID_ARGUMENTS, "--title cannot be empty. Use --title-auto to regenerate the title automatically.")
        }
        if (r.icon != null && normalizedIcon(r.icon) == null) {
            return Failure(INVALID_ICON, "Unknown icon '${r.icon}'. Valid icons: ${ICON_NAMES.joinToString(", ")}, or \"\" for the default icon.")
        }
        return null
    }

    /**
     * The icon key for an `--icon` value: "" for blank (the default icon), the
     * key for a known one in any case and with surrounding spaces, null when
     * it is not an icon key.
     */
    fun normalizedIcon(raw: String): String? {
        val v = raw.trim().lowercase()
        return if (v.isEmpty() || v in ICON_NAMES) v else null
    }

    /** Titles and group names are trimmed, as the edit sheet and the group picker do. */
    fun normalizedTitle(s: String): String = s.trim()
    fun normalizedGroup(s: String): String = s.trim()

    /** Group names match case-sensitively and exactly (after trimming). */
    fun groupNameMatches(stored: String, wanted: String): Boolean = normalizedGroup(stored) == wanted

    data class FolderRef(val id: String, val name: String, val createdAt: Long)

    /**
     * The group [name] refers to, or null when none has that name. Names may
     * repeat by design; the one with the newest member session wins, then any
     * group with members beats an empty one, then the oldest group.
     * [lastActivity]: group id -> newest member's updated_at (absent / null =
     * no members).
     */
    fun pickFolder(name: String, among: List<FolderRef>, lastActivity: Map<String, Long?>): String? {
        val matches = among.filter { groupNameMatches(it.name, name) }
        if (matches.size <= 1) return matches.firstOrNull()?.id
        return matches.sortedWith(
            compareByDescending<FolderRef> { lastActivity[it.id] != null }
                .thenByDescending { lastActivity[it.id] ?: Long.MIN_VALUE }
                .thenBy { it.createdAt },
        ).first().id
    }

    data class Snapshot(val title: String, val group: String, val icon: String)

    /** Fields whose value differs, in the fixed order title, group, icon. */
    fun changedFields(before: Snapshot, after: Snapshot): List<String> = buildList {
        if (before.title != after.title) add("title")
        if (before.group != after.group) add("group")
        if (before.icon != after.icon) add("icon")
    }

    sealed class Outcome {
        data class Done(val after: Snapshot, val changed: List<String>) : Outcome()
        data class Failed(val code: String, val message: String) : Outcome()
    }
}

// ─── Merged from SpeakOffloadHandler.kt ──────────────────────────────────────────────────

/**
 * android-speak — speak text aloud via Android TTS.
 *
 * T60: re-aligned with iOS `apple-speak` (NativeOffloads/SpeakOffload.m).
 * Subcommand model:
 *
 *   android-speak speak <text> [--voice tag] [--rate F] [--pitch F] [--volume F]
 *   android-speak stop
 *   android-speak voices [--language prefix]
 *
 * Back-compat: the legacy positional form (`android-speak Hello world`) still
 * works because the dispatcher falls through to `speak` when the first
 * positional isn't a known subcommand keyword. The legacy `--stop` flag is
 * also still honoured so any agent prompt that learned the old form before
 * this commit doesn't regress.
 *
 * Init order: TTS initialisation is asynchronous, so the first `speak` call
 * after boot can race and silently no-op on slower devices. We wait up to
 * [TextToSpeechManager.INIT_TIMEOUT_MS] (the manager's own budget) for init,
 * and if still unready return a structured error so the model knows
 * speech didn't actually play.
 *
 * Voice resolution: iOS treats `--voice` as a BCP-47 language tag (passed to
 * `AVSpeechSynthesisVoice voiceWithLanguage:`). Android's TTS API exposes
 * voices by identifier AND by Locale; we accept the iOS-style language tag
 * via `Locale.forLanguageTag(...)` for cross-platform parity, and additionally
 * try to match an exact voice identifier when `voices` enumeration finds one.
 */
class SpeakOffloadHandler(private val context: Context) : NativeOffloadHandler {
    private val tts = TextToSpeechManager().also { it.init(context) }

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) return NativeOffloadResult(0, HELP)

        // Legacy `--stop` flag — pre-T60 callers used it as a synonym for
        // the `stop` subcommand. Honour it before the subcommand dispatch
        // so old prompts keep working.
        if (args.hasFlag("stop")) return cmdStop(args)
        // Legacy `--status` is dropped from the iOS spec but kept on
        // Android as a debug aid (no iOS analogue). Same back-compat.
        if (args.hasFlag("status")) return buildStatus(args)

        val sub = args.positional.firstOrNull()
        return try {
            when (sub) {
                "speak" -> cmdSpeak(args, dropFirstPositional = true)
                "stop" -> cmdStop(args)
                "voices" -> cmdVoices(args)
                null -> NativeOffloadResult(2, "android-speak: missing command\n$HELP")
                else -> {
                    // Backwards-compat: treat the whole positional list as
                    // free-form text. Mirrors the pre-T60 invocation
                    // `android-speak Hello world`.
                    cmdSpeak(args, dropFirstPositional = false)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "uncaught: ${e.message}", e)
            val body = JSONObject()
                .put("error", "internal")
                .put("message", e.message ?: "unknown")
                .toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    // ── Subcommands ──────────────────────────────────────────────────────────

    private fun cmdSpeak(args: OffloadArgs, dropFirstPositional: Boolean): NativeOffloadResult {
        val textParts = if (dropFirstPositional) args.positional.drop(1) else args.positional
        val text = (args.get("text") ?: textParts.joinToString(" ")).trim()
        if (text.isBlank()) {
            return NativeOffloadResult(2, "android-speak: missing <text>\n$HELP")
        }
        // [T-android-tts-init-timeout] Wait as long as the manager itself does:
        // 2 s gave up before TextToSpeechManager's own 4 s budget, so a slow
        // engine cold start (OPPO ColorOS) was reported as "no engine".
        if (!waitForInit(TextToSpeechManager.INIT_TIMEOUT_MS)) {
            val body = JSONObject()
                .put("error", "tts_unavailable")
                .put(
                    "message",
                    "No usable text-to-speech engine is installed on this device " +
                        "(common on Huawei HMS-only devices and some stripped China ROMs). " +
                        "Ask the user to install a TTS engine — on most devices " +
                        "'Google Text-to-speech' from the Play Store or an OEM equivalent works.",
                )
                .put("available_engines", JSONArray(probeEngineNames()))
                .toString()
            return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }

        // Voice / language: --voice takes precedence and is treated as a
        // BCP-47 language tag (matches iOS). Empty / invalid tags fall
        // through to the auto-detect path inside speak().
        val voiceTag = args.get("voice")
        if (!voiceTag.isNullOrBlank()) {
            tts.setLanguage(Locale.forLanguageTag(voiceTag))
        }

        args.getDouble("rate")?.let { tts.speechRate = it.toFloat() }
        args.getDouble("pitch")?.let { tts.speechPitch = it.toFloat() }
        args.getDouble("volume")?.let { tts.speechVolume = it.toFloat() }

        tts.speak(text)
        val preview = if (text.length > 100) text.take(100) + "..." else text
        val body = JSONObject()
            .put("text", preview)
            .put("voice", voiceTag ?: "auto")
            .put("rate", tts.speechRate.toDouble())
            .put("pitch", tts.speechPitch.toDouble())
            .put("characters", text.length)
            .put("status", "speaking")
            .toString()
        return NativeOffloadResult(0, OffloadOutput.formatBody(body, args) + "\n")
    }

    private fun cmdStop(args: OffloadArgs): NativeOffloadResult {
        tts.stop()
        val body = JSONObject().put("status", "stopped").toString()
        return NativeOffloadResult(0, OffloadOutput.formatBody(body, args) + "\n")
    }

    /**
     * List installed voices. iOS returns id/name/language/quality/gender for
     * each `AVSpeechSynthesisVoice`. Android `TextToSpeech.voices` (API 21+)
     * exposes equivalent fields:
     *   - `Voice.name`            → both id+name (Android doesn't separate)
     *   - `Voice.locale.toLanguageTag()` → language
     *   - `Voice.quality`         → mapped to default/enhanced/premium
     *   - `Voice.features`        → no gender field, marked "unspecified"
     */
    private fun cmdVoices(args: OffloadArgs): NativeOffloadResult {
        if (!waitForInit(TextToSpeechManager.INIT_TIMEOUT_MS)) {
            val body = JSONObject()
                .put("error", "tts_unavailable")
                .put("voices", JSONArray())
                .put("count", 0)
                .toString()
            return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
        val languagePrefix = args.get("language")
        val items = JSONArray()
        // Pull voices through a fresh probe — the long-lived
        // [TextToSpeechManager] doesn't expose them, and a probe is cheap.
        val probe = TextToSpeech(context.applicationContext, null)
        try {
            // Voices are enumerable as soon as the engine is installed; a
            // 200ms post-construction settle is enough on every device I've
            // tested. waitForInit above already established this engine is
            // alive globally, so the probe constructor inheriting the same
            // engine binding shouldn't need a long wait.
            Thread.sleep(200)
            val voices: Set<Voice>? = probe.voices
            voices?.forEach { v ->
                val tag = v.locale?.toLanguageTag() ?: ""
                if (!languagePrefix.isNullOrBlank() && !tag.startsWith(languagePrefix)) {
                    return@forEach
                }
                val quality = when (v.quality) {
                    Voice.QUALITY_VERY_HIGH, Voice.QUALITY_HIGH -> "premium"
                    Voice.QUALITY_NORMAL -> "enhanced"
                    else -> "default"
                }
                items.put(
                    JSONObject()
                        .put("id", v.name ?: "")
                        .put("name", v.name ?: "")
                        .put("language", tag)
                        .put("quality", quality)
                        .put("gender", "unspecified"),
                )
            }
        } finally {
            try { probe.shutdown() } catch (_: Throwable) {}
        }
        val body = JSONObject()
            .put("voices", items)
            .put("count", items.length())
            .toString()
        return NativeOffloadResult(0, OffloadOutput.formatBody(body, args) + "\n")
    }

    // ── Diagnostics (Android-only, no iOS analogue) ──────────────────────────

    private fun buildStatus(args: OffloadArgs): NativeOffloadResult {
        val engines = probeEngineNames()
        val ready = waitForInit(500)
        val body = JSONObject()
            .put("ready", ready)
            .put("speaking", tts.isSpeaking.value)
            .put("rate", tts.speechRate.toDouble())
            .put("pitch", tts.speechPitch.toDouble())
            .put("available_engines", JSONArray(engines))
        return NativeOffloadResult(0, OffloadOutput.formatBody(body.toString(2), args) + "\n")
    }

    private fun waitForInit(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!tts.isInitialized && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50) } catch (_: InterruptedException) { break }
        }
        return tts.isInitialized
    }

    /**
     * Installed TTS engines (package names), best effort.
     *
     * [T-android-tts-engine-visibility] A direct PackageManager query for
     * TTS_SERVICE — the same query TextToSpeech.getEngines() makes, without
     * constructing a throw-away TextToSpeech that starts (and then abandons)
     * an engine binding just to read a list. Either way the result is subject
     * to package visibility: it is only complete because the manifest now
     * declares a TTS_SERVICE <queries> entry. Before that, a user-installed
     * engine was filtered out and this reported `available_engines: []` on a
     * device that had one.
     */
    private fun probeEngineNames(): List<String> = try {
        val pm = context.packageManager
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, 0)
        }
        resolved.mapNotNull { it.serviceInfo?.packageName }.distinct()
    } catch (_: Throwable) {
        emptyList()
    }

    @Suppress("unused")
    private val androidApiLevel: Int = Build.VERSION.SDK_INT

    companion object {
        private const val TAG = "SpeakOffload"
        private const val HELP = """android-speak — speak text aloud via Android TTS

Usage:
  android-speak <command> [options]

COMMANDS:
  speak       Synthesize speech from text
  voices      List available voices
  stop        Stop current speech

COMMON OPTIONS:
  --help, -h           Show this help message
  --compact            Minimize JSON output
  -q, --quiet          Output only data field

SPEAK OPTIONS:
  --text <string>      Text to speak (positional also accepted)
  --voice <tag>        BCP-47 language tag (e.g. en-US, zh-CN)
  --rate <0.1-3.0>     Speech rate (default: 1.0)
  --pitch <0.5-2.0>    Pitch multiplier (default: 1.0)
  --volume <0.0-1.0>   Volume (default: 1.0)

VOICES OPTIONS:
  --language <prefix>  Filter by language prefix (e.g. "en")

EXAMPLES:
  android-speak speak "Hello world"
  android-speak speak --text "Bonjour" --voice fr-FR --rate 0.5
  android-speak voices --language en
  android-speak stop

Returns structured JSON on errors:
  {"error":"tts_unavailable","message":"...","available_engines":[...]}
"""
    }
}

// ─── Merged from SpeechCliEngine.kt ──────────────────────────────────────────────────

/**
 * [T-431-engine] Pure rules for `android-speech transcribe --engine
 * system|provider|auto` (GH #431 feature request 1/2, from the issue author):
 * transcribe with the speech-to-text model configured in the Voice Input
 * group, from the microphone or from an audio file.
 *
 * Data destination is the point of these rules. The system engine keeps audio
 * with Android's recognizer; the provider engine uploads it to a third-party
 * service the user configured, which may charge for it. So:
 *  - the default stays `system` - no silent change of where audio goes;
 *  - `provider` must be asked for;
 *  - `auto` uses the provider only when the system recognizer is known not
 *    to work or fails to connect, and every provider result states where the
 *    audio went (`audio_sent_to`, `notice`).
 */
internal object SpeechEngines {
    enum class Engine(val label: String) {
        SYSTEM("system"),
        PROVIDER("provider"),
        AUTO("auto"),
    }

    /** Null [raw] is the default (system); an unknown name is null. */
    fun parse(raw: String?): Engine? {
        val s = raw?.trim()?.lowercase() ?: return Engine.SYSTEM
        return Engine.entries.firstOrNull { it.label == s }
    }

    /** A Voice Input group member, without credentials. */
    data class VoiceModel(val providerLabel: String, val modelId: String, val host: String)

    fun destination(m: VoiceModel): String = "provider \"${m.providerLabel}\" (model ${m.modelId}, ${m.host})"

    fun sentNotice(m: VoiceModel): String = "Audio was sent to ${destination(m)}."

    /**
     * `auto`, before capturing: go straight to the provider when the system
     * recognizer is known not to work here (no service, default hidden, last
     * attempt ERROR_CLIENT) - recording first would make the user speak twice.
     */
    fun autoStartsWithProvider(servicesListed: Boolean, systemUsable: Boolean, haveModel: Boolean): Boolean =
        haveModel && (!servicesListed || !systemUsable)

    /**
     * `auto`, after the system engine answered: fall back to the provider for
     * the failures that mean "cannot reach a recognizer" - the same set the
     * system engine itself switches recognizers on - and a recognizer that
     * could not be created. Silence, a timeout or a network error are not
     * reasons to send audio elsewhere.
     */
    fun autoFallsBack(outcome: RecognizeOutcome): Boolean = when (outcome) {
        is RecognizeOutcome.Failure -> RecognizerFallback.shouldSwitch(outcome.code)
        is RecognizeOutcome.CreationFailed -> true
        else -> false
    }

    fun noModelMessage(): String =
        "No voice-input model is configured. Add a speech-to-text model (e.g. whisper-1 or " +
            "gpt-4o-transcribe) from a provider to Settings > Model Groups > Voice Input, and make sure " +
            "voice input is not set to System. Then run `android-speech transcribe --engine provider`."

    /** The `engine` block of `status`. Names and hosts only, never keys. */
    fun status(models: List<VoiceModel>, servicesListed: Boolean, systemUsable: Boolean): JSONObject {
        val first = models.firstOrNull()
        val auto = if (autoStartsWithProvider(servicesListed, systemUsable, models.isNotEmpty())) {
            "provider"
        } else if (models.isNotEmpty()) {
            "system, then provider if the system recognizer cannot be reached"
        } else {
            "system"
        }
        return JSONObject()
            .put("default", Engine.SYSTEM.label)
            .put("auto_would_use", auto)
            .put(
                "voice_input_model",
                JSONObject()
                    .put("configured", first != null)
                    .put("provider_label", first?.providerLabel ?: JSONObject.NULL)
                    .put("model", first?.modelId ?: JSONObject.NULL)
                    .put("host", first?.host ?: JSONObject.NULL)
                    .put("candidates", models.size),
            )
    }

    fun providerErrors(errors: List<Pair<VoiceModel, String>>): JSONArray = JSONArray().apply {
        errors.forEach { (m, msg) ->
            put(
                JSONObject().put("provider_label", m.providerLabel).put("model", m.modelId)
                    .put("host", m.host).put("message", msg),
            )
        }
    }
}

/**
 * [T-431-engine] Audio handling for the provider engine. Voice providers take
 * one WAV (the request is built as `voice.wav`, `audio/wav`), so the CLI sends
 * WAV: a WAV file small enough goes as-is, anything else Android can decode
 * (MP3, M4A/AAC, FLAC, OGG/Opus, AMR, WebM, large or multi-channel WAV) is
 * decoded and re-encoded as 16 kHz mono PCM WAV on the device first.
 */
internal object SpeechAudio {
    const val SAMPLE_RATE = 16_000

    /** Refuse bigger input files before reading them. */
    const val MAX_FILE_BYTES = 100L * 1024 * 1024

    /** OpenAI's /v1/audio/transcriptions limit; most compatible services match it. */
    const val MAX_UPLOAD_BYTES = 25L * 1024 * 1024

    /** Decoded audio cap: 10 min of 16 kHz mono is 19.2 MB, under [MAX_UPLOAD_BYTES]. */
    const val MAX_FILE_SECONDS = 600

    /** Microphone capture cap, as the chat voice input and iOS apple-speech. */
    const val MAX_MIC_SECONDS = 60

    enum class Format(val label: String) {
        WAV("wav"), MP3("mp3"), MP4("m4a/mp4"), FLAC("flac"), OGG("ogg"), AMR("amr"),
        WEBM("webm"), AAC("aac"), UNKNOWN("unknown"),
    }

    /** Identify an audio container from its first bytes. */
    fun sniff(head: ByteArray): Format {
        fun at(i: Int, s: String) = head.size >= i + s.length &&
            s.indices.all { head[i + it] == s[it].code.toByte() }
        return when {
            at(0, "RIFF") && at(8, "WAVE") -> Format.WAV
            at(0, "ID3") -> Format.MP3
            head.size >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xF6) == 0xF0 -> Format.AAC
            head.size >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0 -> Format.MP3
            at(4, "ftyp") -> Format.MP4
            at(0, "fLaC") -> Format.FLAC
            at(0, "OggS") -> Format.OGG
            at(0, "#!AMR") -> Format.AMR
            head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
                head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() -> Format.WEBM
            else -> Format.UNKNOWN
        }
    }

    /**
     * Length of a WAV sent unchanged, from its header's byte rate (offset 28)
     * and the file size; null when the header is not a usable PCM WAV.
     */
    fun wavSeconds(head: ByteArray, sizeBytes: Long): Double? {
        if (sniff(head) != Format.WAV || head.size < 44) return null
        val byteRate = (head[28].toInt() and 0xFF) or ((head[29].toInt() and 0xFF) shl 8) or
            ((head[30].toInt() and 0xFF) shl 16) or ((head[31].toInt() and 0xFF) shl 24)
        if (byteRate <= 0) return null
        return (sizeBytes - 44).coerceAtLeast(0) / byteRate.toDouble()
    }

    /** A WAV small enough to upload unchanged. */
    fun passThrough(format: Format, sizeBytes: Long): Boolean =
        format == Format.WAV && sizeBytes <= MAX_UPLOAD_BYTES

    /** Average interleaved channels into mono. */
    fun downmix(interleaved: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        return ShortArray(frames) { f ->
            var sum = 0
            for (c in 0 until channels) sum += interleaved[f * channels + c]
            (sum / channels).toShort()
        }
    }

    /** Linear-interpolation resampling; speech-recognition quality is enough. */
    fun resample(mono: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || mono.isEmpty()) return mono
        val outLen = ((mono.size.toLong() * toRate) / fromRate).toInt()
        return ShortArray(outLen) { i ->
            val pos = i.toDouble() * fromRate / toRate
            val i0 = pos.toInt().coerceAtMost(mono.size - 1)
            val i1 = (i0 + 1).coerceAtMost(mono.size - 1)
            val frac = pos - i0
            (mono[i0] + (mono[i1] - mono[i0]) * frac).toInt().toShort()
        }
    }

    /**
     * [T-speech-decode-streaming] [downmix] + [resample] fed one decoder buffer
     * at a time, so a long file never sits in memory at its source rate and
     * channel count (10 min of 48 kHz stereo was ~115 MB of shorts, copied
     * again before the downmix). Same output as the batch pair: the
     * interpolation positions are computed with the same expression, and an
     * output sample is emitted as soon as both input samples it reads exist.
     * The output is capped at [maxOut] samples (null = no cap).
     */
    class StreamingResampler(
        private val channels: Int,
        private val fromRate: Int,
        private val toRate: Int,
        private val maxOut: Int? = null,
    ) {
        private var out = ShortArray(minOf(maxOut ?: 1 shl 16, 1 shl 16).coerceAtLeast(1))
        private var outLen = 0
        private var inCount = 0L
        private var prev: Short = 0
        private var nextOut = 0L

        /** Output samples so far. */
        val size: Int get() = outLen

        private fun emit(v: Short) {
            if (maxOut != null && outLen >= maxOut) return
            if (outLen == out.size) out = out.copyOf(maxOf(out.size * 2, 1).let { if (maxOut != null) minOf(it, maxOut) else it })
            out[outLen++] = v
        }

        fun push(interleaved: ShortArray, length: Int = interleaved.size) {
            val ch = maxOf(channels, 1)
            val frames = length / ch
            for (f in 0 until frames) {
                val s: Short = if (ch == 1) interleaved[f] else {
                    var sum = 0
                    for (c in 0 until ch) sum += interleaved[f * ch + c]
                    (sum / ch).toShort()
                }
                val g = inCount
                if (fromRate == toRate) {
                    emit(s)
                } else if (g > 0) {
                    // Every output whose left input sample is g-1.
                    while (true) {
                        val pos = nextOut.toDouble() * fromRate / toRate
                        val i0 = pos.toLong()
                        if (i0 > g - 1) break
                        val frac = pos - i0
                        emit((prev + (s - prev) * frac).toInt().toShort())
                        nextOut++
                    }
                }
                prev = s
                inCount++
            }
        }

        /** The 16-bit mono result; call once, after the last [push]. */
        fun finish(): ShortArray {
            if (fromRate != toRate && inCount > 0) {
                val total = (inCount * toRate) / fromRate
                // Downsampling can compute an output just past the batch
                // length before the stream's end is known: drop it.
                if (outLen > total) outLen = total.toInt()
                // The tail [resample] clamps to the last sample.
                while (nextOut < total) {
                    emit(prev)
                    nextOut++
                }
            }
            return out.copyOf(outLen)
        }
    }

    fun toLittleEndianBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}

// ─── Merged from SpeechCliModel.kt ──────────────────────────────────────────────────

/**
 * [T-431] Pure model of the `android-speech` CLI: the output envelope and exit
 * codes, the `status` and `languages` results, and the wording of recognizer
 * errors. Nothing here talks to the system, so all of it is unit-tested; the
 * handler feeds it what [SpeechSystem] observed.
 *
 * Background (GH #431, analysis by the issue author): on a vivo OriginOS phone
 * `status` said `available: true`, `languages` returned an empty list and
 * `transcribe` failed with the two words "Client error" and exit code 0. Each
 * of those was the CLI not saying what it knew.
 */
internal object SpeechCli {
    const val TOOL = "android-speech"

    // Exit codes follow iOS `apple-speech` (NOFF_EXIT_*), except permission
    // denials keep 77, which every android-* command uses.
    const val EXIT_OK = 0
    const val EXIT_ERROR = 1
    const val EXIT_INVALID_ARGS = 2
    const val EXIT_NOT_AVAILABLE = 4
    const val EXIT_PERMISSION = 77

    /** [T-431-cancel] The caller exited mid-run (128 + SIGINT, as a shell reports it). */
    const val EXIT_CANCELLED = 130

    // ── Envelope ─────────────────────────────────────────────────────────

    /**
     * iOS envelope `{ok, tool, action, data, timestamp}`. The data fields are
     * also copied to the top level, so prompts that learned the flat pre-#431
     * shape (`.available`, `.text`, `.locales`) keep reading them.
     */
    fun ok(action: String, data: JSONObject, now: Date = Date()): JSONObject {
        val out = JSONObject()
            .put("ok", true)
            .put("tool", TOOL)
            .put("action", action)
            .put("data", data)
            .put("timestamp", iso(now))
        for (key in data.keys()) {
            if (!out.has(key)) out.put(key, data.get(key))
        }
        return out
    }

    /**
     * iOS error envelope `{ok:false, tool, action, error:{code, message, ...}}`.
     * The message is repeated at the top level for the pre-#431 readers of
     * `.message`. `.error` changes from a sentence to an object: presence
     * checks keep working, string comparisons on it do not.
     */
    fun error(
        action: String,
        code: String,
        message: String,
        extra: JSONObject? = null,
        now: Date = Date(),
    ): JSONObject {
        val err = JSONObject().put("code", code).put("message", message)
        if (extra != null) for (key in extra.keys()) err.put(key, extra.get(key))
        return JSONObject()
            .put("ok", false)
            .put("tool", TOOL)
            .put("action", action)
            .put("error", err)
            .put("message", message)
            .put("timestamp", iso(now))
    }

    private fun iso(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(date)

    // ── status ───────────────────────────────────────────────────────────

    /** What the system reported, gathered by [SpeechSystem.environment]. */
    data class Environment(
        /** `queryIntentServices(RecognitionService)` as "package/class". */
        val services: List<String>,
        /** `Settings.Secure.voice_recognition_service`, or null when unset or unreadable. */
        val defaultService: String?,
        /** `isOnDeviceRecognitionAvailable`, null below API 31. */
        val onDeviceAvailable: Boolean?,
        val locale: String,
        val hasMicPermission: Boolean,
        val sdkInt: Int,
        /** The last system recognition failure the CLI saw, if any. */
        val lastError: LastError? = null,
    )

    data class LastError(val code: Int, val atMillis: Long, val recognizer: String?)

    /**
     * A recognizer the default service names but the package manager does not
     * list is a recognizer Minis cannot bind - the ROM hides it, or it is
     * disabled. Matching is on the component, ignoring the short-class form.
     */
    fun defaultVisible(env: Environment): Boolean {
        val def = env.defaultService?.trim().orEmpty()
        if (def.isEmpty()) return false
        val norm = normalizeComponent(def)
        return env.services.any { normalizeComponent(it) == norm }
    }

    /** "pkg/.Cls" and "pkg/pkg.Cls" name the same component. */
    fun normalizeComponent(c: String): String {
        val slash = c.indexOf('/')
        if (slash <= 0) return c
        val pkg = c.substring(0, slash)
        val cls = c.substring(slash + 1)
        return "$pkg/" + if (cls.startsWith(".")) pkg + cls else cls
    }

    /**
     * Whether system recognition can be expected to work, and why not. The
     * first answer that applies wins; null reason means nothing is known to
     * be wrong. `available` (pre-#431) only ever meant "a service is listed".
     */
    fun assess(env: Environment): Pair<Boolean, String?> {
        if (env.services.isEmpty()) {
            return false to "No speech recognition service is installed or visible to Minis, " +
                "so the system recognizer cannot work on this device."
        }
        val def = env.defaultService?.trim().orEmpty()
        if (def.isEmpty()) {
            return false to "No default voice recognition service is set " +
                "(Settings.Secure voice_recognition_service is empty), so Android rejects " +
                "recognition requests with ERROR_CLIENT."
        }
        if (!defaultVisible(env)) {
            return false to "The default recognition service ($def) is not among the services " +
                "Minis can see; the ROM hides it from third-party apps or it is disabled, " +
                "so Android cannot connect Minis to it."
        }
        val last = env.lastError
        if (last != null && last.code == SpeechRecognizer.ERROR_CLIENT) {
            return false to "The last recognition attempt failed with ERROR_CLIENT: Android could " +
                "not connect Minis to the recognition service ($def). On some ROMs (e.g. vivo " +
                "OriginOS) the default recognizer does not serve third-party apps."
        }
        if (!env.hasMicPermission) {
            return true to "Microphone permission is not granted yet; transcribe will ask for it."
        }
        return true to null
    }

    fun status(env: Environment, probe: JSONObject? = null): JSONObject {
        val (isAvailable, reason) = assess(env)
        val data = JSONObject()
            // Pre-#431 fields, unchanged in meaning.
            .put("available", env.services.isNotEmpty())
            .put("has_record_audio_permission", env.hasMicPermission)
            // iOS apple-speech names.
            .put("is_available", isAvailable)
            .put("authorization_status", if (env.hasMicPermission) "authorized" else "not_authorized")
            .put("locale", env.locale)
            .put("supports_on_device", env.onDeviceAvailable ?: JSONObject.NULL)
            // Android detail.
            .put("recognition_services", JSONArray(env.services))
            .put("default_service", env.defaultService ?: JSONObject.NULL)
            .put("default_service_visible", defaultVisible(env))
            .put("sdk_int", env.sdkInt)
            .put("reason", reason ?: JSONObject.NULL)
        env.lastError?.let {
            data.put(
                "last_error",
                JSONObject()
                    .put("code", it.code)
                    .put("name", RecognizerErrorCodes.name(it.code))
                    .put("recognizer", it.recognizer ?: JSONObject.NULL)
                    .put("at", iso(Date(it.atMillis))),
            )
        }
        if (probe != null) data.put("probe", probe)
        return data
    }

    // ── languages ────────────────────────────────────────────────────────

    /**
     * Answer of `RecognizerIntent.getVoiceDetailsIntent` sent as an explicit
     * broadcast: null tags = nothing came back; [note] says why.
     */
    data class VoiceDetails(val tags: List<String>?, val note: String)

    /**
     * API 33 `checkRecognitionSupport`, merged over both recognizers. The
     * `*Answered` flags say which recognizer actually replied: a recognizer
     * that did not answer must not turn into "false" for every locale.
     */
    data class Support(
        val online: List<String>,
        val installedOnDevice: List<String>,
        val supportedOnDevice: List<String>,
        val note: String,
        val regularAnswered: Boolean = true,
        val onDeviceAnswered: Boolean = true,
    ) {
        val isEmpty get() = online.isEmpty() && installedOnDevice.isEmpty() && supportedOnDevice.isEmpty()
    }

    sealed class LanguagesResult {
        abstract val body: JSONObject
        abstract val exitCode: Int

        class Found(override val body: JSONObject) : LanguagesResult() {
            override val exitCode = EXIT_OK
        }

        class Unavailable(override val body: JSONObject) : LanguagesResult() {
            override val exitCode = EXIT_NOT_AVAILABLE
        }
    }

    /**
     * Pre-#431 `languages` sent an implicit broadcast no recognizer receives
     * on Android 8+, and printed the empty answer as `{"locales":[],"count":0}`
     * with exit 0 - on every device, not only vivo (confirmed on a Pixel 4a).
     * An empty list is no longer printed as a result: when the system answers
     * nothing the call fails with `unavailable`, and the chat voice input's
     * built-in list is attached only as a labelled fallback.
     */
    fun languages(
        details: VoiceDetails,
        support: Support?,
        builtin: List<String>,
        prefix: String?,
        now: Date = Date(),
    ): LanguagesResult {
        val detailTags = details.tags.orEmpty()
        val sources = JSONArray()
        if (detailTags.isNotEmpty()) sources.put("voice_details")
        if (support != null && !support.isEmpty) sources.put("recognition_support")

        val all = linkedSetOf<String>()
        all.addAll(detailTags)
        if (support != null) {
            all.addAll(support.online)
            all.addAll(support.installedOnDevice)
            all.addAll(support.supportedOnDevice)
        }
        val tags = all.map { canonicalTag(it) }.distinct().filter { matches(it, prefix) }.sorted()

        if (sources.length() == 0) {
            val fallback = JSONArray()
            builtin.map { canonicalTag(it) }.distinct().filter { matches(it, prefix) }.forEach {
                fallback.put(localeItem(it))
            }
            val extra = JSONObject()
                .put("voice_details", details.note)
                .put("recognition_support", support?.note ?: "not available below Android 13")
                .put(
                    "fallback",
                    JSONObject()
                        .put("source", "builtin")
                        .put(
                            "note",
                            "Minis' built-in list (the one the chat voice input offers). It is NOT what " +
                                "this device's recognizer reported and may include languages it cannot " +
                                "recognize.",
                        )
                        .put("locales", fallback)
                        .put("count", fallback.length()),
                )
            return LanguagesResult.Unavailable(
                error(
                    "languages",
                    "unavailable",
                    "The system did not report which languages its speech recognizer supports. " +
                        "This says nothing about whether recognition works; try `android-speech " +
                        "transcribe --language <tag>` directly.",
                    extra,
                    now,
                ),
            )
        }

        val items = JSONArray()
        for (tag in tags) {
            val item = localeItem(tag)
            if (support != null && !support.isEmpty) {
                if (support.regularAnswered) item.put("online", support.online.any { canonicalTag(it) == tag })
                if (support.onDeviceAnswered) {
                    item.put("on_device_installed", support.installedOnDevice.any { canonicalTag(it) == tag })
                    item.put("on_device_supported", support.supportedOnDevice.any { canonicalTag(it) == tag })
                }
            }
            items.put(item)
        }
        val data = JSONObject()
            .put("locales", items)
            .put("count", items.length())
            .put("sources", sources)
            .put(
                "notes",
                JSONObject()
                    .put("voice_details", details.note)
                    .put("recognition_support", support?.note ?: "not available below Android 13")
                    .put(
                        "flags",
                        "online / on_device_* are what each recognizer reported about itself and are " +
                            "omitted for a recognizer that did not answer.",
                    ),
            )
        return LanguagesResult.Found(ok("languages", data, now))
    }

    private fun matches(tag: String, prefix: String?): Boolean =
        prefix.isNullOrBlank() || tag.lowercase().startsWith(canonicalTag(prefix).lowercase())

    /** Recognizers mix "en_US" and "en-US"; print BCP-47. */
    fun canonicalTag(tag: String): String = tag.trim().replace('_', '-')

    private fun localeItem(tag: String): JSONObject {
        val name = Locale.forLanguageTag(tag).displayName
        return JSONObject().put("locale", tag).put("display_name", name.ifEmpty { tag })
    }

    // ── transcribe errors ────────────────────────────────────────────────

    /** Stable snake-case code for a recognizer error, for scripts. */
    fun errorCode(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_CLIENT -> "client_error"
        SpeechRecognizer.ERROR_AUDIO -> "audio_error"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network_error"
        SpeechRecognizer.ERROR_SERVER -> "server_error"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer_busy"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission_denied"
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no_speech"
        RecognizerErrorCodes.TOO_MANY_REQUESTS -> "too_many_requests"
        RecognizerErrorCodes.SERVER_DISCONNECTED -> "service_disconnected"
        RecognizerErrorCodes.LANGUAGE_NOT_SUPPORTED -> "language_not_supported"
        RecognizerErrorCodes.LANGUAGE_UNAVAILABLE -> "language_unavailable"
        else -> "recognizer_error"
    }

    /**
     * An actionable message for a recognizer failure. [hints] lists what the
     * caller can try instead; it grows as the CLI gains alternatives.
     */
    fun recognizerErrorMessage(
        code: Int,
        language: String?,
        defaultService: String?,
        hints: List<String>,
    ): String {
        val base = when (code) {
            SpeechRecognizer.ERROR_CLIENT ->
                "Android could not connect Minis to a speech recognition service (ERROR_CLIENT). " +
                    "Default service: ${defaultService ?: "none set"}. On some ROMs (e.g. vivo " +
                    "OriginOS and other China ROMs) the default recognizer does not serve " +
                    "third-party apps; that is a system restriction Minis cannot change."
            RecognizerErrorCodes.LANGUAGE_NOT_SUPPORTED, RecognizerErrorCodes.LANGUAGE_UNAVAILABLE ->
                RecognizerErrorCodes.describe(code) + " (requested: ${language ?: "device default"})."
            else -> RecognizerErrorCodes.describe(code) + " (${RecognizerErrorCodes.name(code)})."
        }
        val tips = hints + "Run `android-speech status` for the services on this device."
        return base + " Try: " + tips.joinToString(" ")
    }
}

// ─── Merged from WeatherOffloadHandler.kt ──────────────────────────────────────────────────

/**
 * android-weather — fetch weather from Open-Meteo (no API key needed).
 *
 * Mirrors apple-weather (NativeOffloads/WeatherOffload.m): same five
 * subcommands and same flag names so prompts and the agent loop are
 * platform-agnostic.
 *
 * Usage:
 *   android-weather current   --lat L --lon N
 *   android-weather hourly    --lat L --lon N [--hours N]
 *   android-weather daily     --lat L --lon N [--days N]
 *   android-weather alerts    --lat L --lon N
 *   android-weather report    --lat L --lon N [--hours N] [--days N]
 *
 * Backwards compatibility:
 *   - Legacy positional `android-weather <lat> <lon>` still works and
 *     emits the pre-T63 plain-text report.
 *   - `--lng` is accepted as an alias for `--lon` (apple-weather naming).
 *
 * Open-Meteo hosts in the EU — mainland-China users on China Mobile/
 * Telecom see higher latency and occasional timeouts. The handler returns
 * a structured error in that case so the model can explain the situation.
 */
class WeatherOffloadHandler(private val context: Context) : NativeOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) return NativeOffloadResult(0, HELP)

        // Subcommand model (T63). When the first positional is one of the
        // five known subcommands, route through the typed JSON path.
        // Otherwise fall back to the legacy `<lat> <lon>` plain-text form.
        val sub = args.positional.firstOrNull()
        if (sub in SUBCOMMANDS) {
            return handleSubcommand(sub!!, args)
        }
        return handleLegacy(args)
    }

    // ── Subcommand path (T63) ────────────────────────────────────────────

    private fun handleSubcommand(sub: String, args: OffloadArgs): NativeOffloadResult {
        // Coordinates — flag form preferred; --lng accepted as iOS alias.
        val lat = args.getDouble("lat")
            ?: return NativeOffloadResult(2, "android-weather $sub: --lat <latitude> is required\n")
        val lon = args.getDouble("lon", "lng")
            ?: return NativeOffloadResult(2, "android-weather $sub: --lon <longitude> is required\n")
        if (lat !in -90.0..90.0) return NativeOffloadResult(2, "android-weather: latitude out of range\n")
        if (lon !in -180.0..180.0) return NativeOffloadResult(2, "android-weather: longitude out of range\n")

        val maxHours = (args.getInt("hours") ?: 24).coerceIn(1, 48)
        val maxDays = (args.getInt("days") ?: 7).coerceIn(1, 10)

        val json = try {
            WeatherManager.fetchWeatherJson(lat, lon)
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "fetchWeatherJson uncaught: ${e.message}")
            null
        } ?: return NativeOffloadResult(1, augmentedError(WeatherManager.lastError, args))

        AppLogger.info(TAG, "$sub: lat=$lat lon=$lon hours=$maxHours days=$maxDays")
        val data: JSONObject = when (sub) {
            "current" -> currentBlock(json)
            "hourly"  -> hourlyBlock(json, maxHours)
            "daily"   -> dailyBlock(json, maxDays)
            "alerts"  -> alertsBlock()
            "report"  -> reportBlock(json, maxHours, maxDays, lat, lon)
            else -> JSONObject() // unreachable — guarded by SUBCOMMANDS
        }
        return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
    }

    /** Pluck `current` from Open-Meteo response, normalize numeric fields,
     *  and mirror apple-weather's per-field shape (temperature, feels_like,
     *  humidity, precipitation, wind, conditions). */
    private fun currentBlock(json: JSONObject): JSONObject {
        val current = json.optJSONObject("current") ?: return JSONObject()
        val units = json.optJSONObject("current_units")
        val tempUnit = units?.optString("temperature_2m", "°C") ?: "°C"
        val windUnit = units?.optString("wind_speed_10m", "km/h") ?: "km/h"
        return JSONObject()
            .put("time", current.optString("time", ""))
            .put("temperature", current.optDouble("temperature_2m"))
            .put("temperature_unit", tempUnit)
            .put("feels_like", current.optDouble("apparent_temperature"))
            .put("humidity", current.optInt("relative_humidity_2m"))
            .put("precipitation_mm", current.optDouble("precipitation"))
            .put("wind_speed", current.optDouble("wind_speed_10m"))
            .put("wind_speed_unit", windUnit)
            .put("wind_direction", current.optInt("wind_direction_10m"))
            .put("weather_code", current.optInt("weather_code"))
            .put("conditions", weatherCodeToDescription(current.optInt("weather_code")))
    }

    /** Slice the hourly arrays into a list of per-hour records, capped at
     *  [maxHours] (mirrors apple-weather hourly subarray capping). */
    private fun hourlyBlock(json: JSONObject, maxHours: Int): JSONObject {
        val hourly = json.optJSONObject("hourly") ?: return JSONObject().put("hourly", JSONArray()).put("count", 0)
        val times = hourly.optJSONArray("time")
        val temps = hourly.optJSONArray("temperature_2m")
        val precips = hourly.optJSONArray("precipitation_probability")
        val codes = hourly.optJSONArray("weather_code")
        val winds = hourly.optJSONArray("wind_speed_10m")
        val arr = JSONArray()
        val n = minOf(times?.length() ?: 0, maxHours)
        for (i in 0 until n) {
            val item = JSONObject()
                .put("time", times?.optString(i, "") ?: "")
                .put("temperature", temps?.optDouble(i, 0.0) ?: 0.0)
                .put("precipitation_probability", precips?.optInt(i, 0) ?: 0)
                .put("wind_speed", winds?.optDouble(i, 0.0) ?: 0.0)
                .put("weather_code", codes?.optInt(i, 0) ?: 0)
                .put("conditions", weatherCodeToDescription(codes?.optInt(i, 0) ?: 0))
            arr.put(item)
        }
        return JSONObject().put("hourly", arr).put("count", arr.length())
    }

    /** Slice daily arrays similarly. apple-weather caps to maxDays. */
    private fun dailyBlock(json: JSONObject, maxDays: Int): JSONObject {
        val daily = json.optJSONObject("daily") ?: return JSONObject().put("daily", JSONArray()).put("count", 0)
        val times = daily.optJSONArray("time")
        val highs = daily.optJSONArray("temperature_2m_max")
        val lows = daily.optJSONArray("temperature_2m_min")
        val codes = daily.optJSONArray("weather_code")
        val precSum = daily.optJSONArray("precipitation_sum")
        val precProb = daily.optJSONArray("precipitation_probability_max")
        val sunrises = daily.optJSONArray("sunrise")
        val sunsets = daily.optJSONArray("sunset")
        val arr = JSONArray()
        val n = minOf(times?.length() ?: 0, maxDays)
        for (i in 0 until n) {
            val item = JSONObject()
                .put("date", times?.optString(i, "") ?: "")
                .put("temperature_max", highs?.optDouble(i, 0.0) ?: 0.0)
                .put("temperature_min", lows?.optDouble(i, 0.0) ?: 0.0)
                .put("precipitation_sum_mm", precSum?.optDouble(i, 0.0) ?: 0.0)
                .put("precipitation_probability_max", precProb?.optInt(i, 0) ?: 0)
                .put("sunrise", sunrises?.optString(i, "") ?: "")
                .put("sunset", sunsets?.optString(i, "") ?: "")
                .put("weather_code", codes?.optInt(i, 0) ?: 0)
                .put("conditions", weatherCodeToDescription(codes?.optInt(i, 0) ?: 0))
            arr.put(item)
        }
        return JSONObject().put("daily", arr).put("count", arr.length())
    }

    /**
     * Open-Meteo doesn't expose government weather alerts (NWS-style).
     * Mirror apple-weather's empty-alerts shape and surface a structured
     * note so the model knows alerts aren't supported on this backend
     * rather than silently returning an empty list.
     */
    private fun alertsBlock(): JSONObject =
        JSONObject()
            .put("alerts", JSONArray())
            .put("count", 0)
            .put("note", "Open-Meteo does not provide weather alerts. For severe-weather alerts use a region-specific service (e.g. NWS in the US, JMA in Japan, NMC in China).")

    /** Combined dump matching apple-weather report (current + hourly +
     *  daily + alerts + location). Sliced sub-blocks share the same shape
     *  as the individual subcommands. */
    private fun reportBlock(json: JSONObject, maxHours: Int, maxDays: Int, lat: Double, lon: Double): JSONObject {
        val timezone = json.optString("timezone", "unknown")
        return JSONObject()
            .put("current", currentBlock(json))
            .put("hourly", hourlyBlock(json, maxHours).optJSONArray("hourly") ?: JSONArray())
            .put("daily", dailyBlock(json, maxDays).optJSONArray("daily") ?: JSONArray())
            .put("alerts", alertsBlock().optJSONArray("alerts") ?: JSONArray())
            .put("location", JSONObject().put("latitude", lat).put("longitude", lon).put("timezone", timezone))
    }

    // ── Legacy plain-text path (pre-T63) ─────────────────────────────────

    private fun handleLegacy(args: OffloadArgs): NativeOffloadResult {
        if (args.positional.size < 2) {
            return NativeOffloadResult(2, HELP)
        }
        val lat = args.positional[0].toDoubleOrNull()
        val lon = args.positional[1].toDoubleOrNull()
        if (lat == null || lon == null) {
            return NativeOffloadResult(2, "android-weather: <latitude> and <longitude> must be numeric\n")
        }
        if (lat !in -90.0..90.0) return NativeOffloadResult(2, "android-weather: latitude out of range\n")
        if (lon !in -180.0..180.0) return NativeOffloadResult(2, "android-weather: longitude out of range\n")
        return try {
            val text = WeatherManager.fetchWeather(lat, lon)
            val err = text.startsWith("Error")
            if (err) {
                NativeOffloadResult(1, augmentedError(text, args))
            } else {
                NativeOffloadResult(0, OffloadOutput.formatBody(text.trimEnd('\n'), args) + "\n")
            }
        } catch (e: Throwable) {
            NativeOffloadResult(1, augmentedError("Error: ${e.message}", args))
        }
    }

    // ── Errors / helpers ─────────────────────────────────────────────────

    /**
     * When Open-Meteo fails, add a CN-specific hint so users on Chinese
     * networks understand why the EU-hosted endpoint might be slow.
     */
    private fun augmentedError(text: String, args: OffloadArgs): String {
        val msg = text.trimEnd().removePrefix("Error:").trim().ifEmpty { "unknown network error" }
        val inChina = looksChina()
        val obj = JSONObject()
            .put("error", "weather_fetch_failed")
            .put("message", msg)
            .put("endpoint", "api.open-meteo.com")
        if (inChina) {
            obj.put(
                "hint",
                "api.open-meteo.com is hosted in Europe and may time out from Chinese mobile networks. Ask the user to retry on Wi-Fi, or use a CN-resident weather service (e.g. QWeather / 和风天气) when available.",
            )
        }
        return OffloadOutput.formatBody(obj.toString(), args) + "\n"
    }

    private fun looksChina(): Boolean {
        if (Locale.getDefault().country.equals("CN", ignoreCase = true)) return true
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val mcc = tm?.simOperator?.take(3) ?: tm?.networkOperator?.take(3)
            mcc == "460"
        } catch (_: Throwable) { false }
    }

    /**
     * WMO Weather interpretation codes to human-readable descriptions.
     * Duplicated from WeatherManager so the JSON path doesn't need to
     * round-trip through plain-text formatting just to get descriptions.
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

    companion object {
        private const val TAG = "WeatherOffload"
        private val SUBCOMMANDS = setOf("current", "hourly", "daily", "alerts", "report")
        private const val HELP = """android-weather — current, hourly, daily forecast, alerts, combined report
                              (mirrors apple-weather, Open-Meteo backend, no API key)

Usage:
  android-weather current   --lat L --lon N
  android-weather hourly    --lat L --lon N [--hours N (default 24, max 48)]
  android-weather daily     --lat L --lon N [--days N (default 7, max 10)]
  android-weather alerts    --lat L --lon N
  android-weather report    --lat L --lon N [--hours N] [--days N]

Legacy form (kept for backwards compatibility):
  android-weather <lat> <lon>           Plain-text combined report

Aliases:
  --lng                                 Apple-weather naming alias for --lon

Notes:
  - Coordinates are decimal degrees (latitude -90..90, longitude -180..180).
  - alerts always returns an empty list — Open-Meteo doesn't expose
    government weather alerts. The structured note in `data` points the
    model at region-specific services for severe-weather alerts.
  - On mainland-China cellular networks api.open-meteo.com may time out;
    the error envelope adds a CN-specific hint when the SIM/locale matches.

Errors return JSON: {"error":"weather_fetch_failed","message":"...","endpoint":"..."}.
"""
    }
}
