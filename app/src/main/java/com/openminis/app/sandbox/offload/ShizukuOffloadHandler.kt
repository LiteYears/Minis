package com.openminis.app.sandbox.offload

import android.content.Context
import android.speech.SpeechRecognizer
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.speech.RecognizerErrorCodes
import com.openminis.app.speech.RecognizerFallback
import com.openminis.app.speech.RecognizerLocales
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * T322: `android-shizuku-cli` — full CLI surface from the design doc.
 *
 * Routes argv through [ShizukuManager.runProcess], which executes
 * privileged shell tools (`pm`, `am`, `cmd`, `settings`, `dumpsys`,
 * `input`, `wm`, `appops`) inside the Shizuku service's process (uid
 * 2000 with adb-startup, uid 0 with root-startup). This is exactly the
 * same surface area `adb shell` provides — so any subcommand a developer
 * could run from a USB-tethered laptop is now reachable from inside the
 * agent's sandboxed shell, without launching the device's settings UI.
 *
 * Subcommand groups (12, per design doc):
 *   package      list / info / install / uninstall / enable / disable / clear / path
 *   permission   list / grant / revoke / appops
 *   activity     start / force-stop / kill / broadcast / top
 *   display      list / set / reset
 *   settings     get / set / delete / list
 *   user         list / create / remove / switch / start / stop
 *   network      restrict / allow / stats
 *   input        tap / swipe / key / text
 *   notification list / dismiss / channel
 *   file         ls / pull / push / rm
 *   device       info / battery / usage
 *   service      status / ping
 *
 * All subcommands honor the project-wide `--compact` / `-q` / `--quiet`
 * flags via [OffloadOutput.formatBody], and `--format json|text|csv`
 * (text is the default; csv is wired only on `package list`).
 */
class ShizukuOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val argv = request.argv.drop(1)
        // Top-level help / version short-circuits — no Shizuku dependency.
        if (argv.isEmpty() || argv[0] == "--help" || argv[0] == "-h" || argv[0] == "help") {
            return ok(HELP)
        }
        if (argv[0] == "--version") return ok("android-shizuku-cli 1.0 (T322)")

        val group = argv[0]
        val rest = argv.drop(1)
        val args = OffloadArgs(rest)

        // `service ping/status` reports state without requiring READY.
        if (group == "service") return handleService(rest, args)

        // T330: tri-state agent gate via OffloadPermissionManager. ASK_ONCE
        // pops a UI dialog and resumes when the user responds; "Always
        // Allow" upgrades the stored level to BYPASS for next time. See
        // OffloadGate for the sync→suspend rationale (handler interface
        // is sync, manager API is suspend).
        if (!OffloadGate.allow("shizuku_cli", "android-shizuku-cli", request)) {
            return errEnvelope(
                "PERMISSION_DENIED",
                "Agent is not allowed to use android-shizuku-cli. Open Settings → Permissions → Integrations to change.",
                args,
            )
        }

        // Every other group needs binder + permission.
        ShizukuManager.refresh()
        when (val st = ShizukuManager.snapshot.value.state) {
            ShizukuManager.State.NOT_INSTALLED ->
                return errEnvelope("SERVICE_NOT_RUNNING", "Shizuku is not installed. Open Settings → Permissions → Shizuku.", args)
            ShizukuManager.State.NOT_RUNNING ->
                return errEnvelope("SERVICE_NOT_RUNNING", "Shizuku service is not running. Start it via adb or root, then retry.", args)
            ShizukuManager.State.NEED_PERMISSION ->
                return errEnvelope("PERMISSION_DENIED", "Minis is not authorized for Shizuku. Grant permission in Settings → Permissions → Shizuku.", args)
            ShizukuManager.State.READY -> { /* fall through */ }
        }

        return try {
            when (group) {
                "package" -> handlePackage(rest, args)
                "permission" -> handlePermission(rest, args)
                "activity" -> handleActivity(rest, args)
                "display" -> handleDisplay(rest, args)
                "settings" -> handleSettings(rest, args)
                "user" -> handleUser(rest, args)
                "network" -> handleNetwork(rest, args)
                "input" -> handleInput(rest, args)
                "notification" -> handleNotification(rest, args)
                "file" -> handleFile(rest, args)
                "device" -> handleDevice(rest, args)
                "exec" -> handleExec(rest, args)
                else -> NativeOffloadResult(
                    2,
                    "android-shizuku-cli: unknown subcommand '$group'.\n" +
                        "  - Try `android-shizuku-cli exec $group ${rest.joinToString(" ")}`".trimEnd() + " to run as a raw shell command.\n" +
                        "  - Run `android-shizuku-cli` with no args to see available subcommands.\n",
                )
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "group=$group failed: ${t.message}")
            errEnvelope("OPERATION_FAILED", t.message ?: "unknown", args)
        }
    }

    // ─── Group: service ────────────────────────────────────────────────────
    private fun handleService(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = rest.firstOrNull() ?: "status"
        val snap = ShizukuManager.snapshot.value
        return when (sub) {
            "status" -> {
                val data = JSONObject()
                    .put("state", snap.state.name)
                    .put("running", snap.state == ShizukuManager.State.READY ||
                        snap.state == ShizukuManager.State.NEED_PERMISSION)
                    .put("authorized", snap.state == ShizukuManager.State.READY)
                    .put("version", snap.version)
                    .put("uid", snap.uid)
                    .put("startup_type", when (snap.uid) { 0 -> "root"; 2000 -> "adb"; else -> "unknown" })
                okEnvelope(data, args)
            }
            "ping" -> {
                if (snap.state == ShizukuManager.State.READY) {
                    ok("OK Shizuku service is running (uid=${snap.uid}, version=${snap.version})\n")
                } else {
                    NativeOffloadResult(1, "FAIL Shizuku service is not READY (state=${snap.state})\n")
                }
            }
            "help", "--help", "-h" -> ok(SERVICE_HELP)
            else -> NativeOffloadResult(2, "service: unknown subcommand '$sub'\n$SERVICE_HELP")
        }
    }

    // ─── Group: package ────────────────────────────────────────────────────
    private fun handlePackage(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = rest.firstOrNull() ?: return ok(PACKAGE_HELP)
        return when (sub) {
            "list" -> packageList(args)
            "info" -> packageInfo(args.positional.getOrNull(1), args)
            "install" -> packageInstall(args.positional.getOrNull(1), args)
            "uninstall" -> packageUninstall(args.positional.getOrNull(1), args)
            "enable" -> packageEnableDisable(args.positional.getOrNull(1), args, enable = true)
            "disable" -> packageEnableDisable(args.positional.getOrNull(1), args, enable = false)
            "clear" -> packageClear(args.positional.getOrNull(1), args)
            "path" -> packagePath(args.positional.getOrNull(1), args)
            "help", "--help", "-h" -> ok(PACKAGE_HELP)
            else -> NativeOffloadResult(2, "package: unknown subcommand '$sub'\n$PACKAGE_HELP")
        }
    }

    private fun packageList(args: OffloadArgs): NativeOffloadResult {
        val cmd = mutableListOf("pm", "list", "packages")
        if (args.hasFlag("system")) cmd.add("-s")
        if (args.hasFlag("third-party", "3")) cmd.add("-3")
        if (args.hasFlag("disabled")) cmd.add("-d")
        args.get("user")?.let { cmd.addAll(listOf("--user", it)) }
        args.get("filter")?.let { cmd.add(it) }
        val r = ShizukuManager.runProcess(cmd.toTypedArray())
        if (r.exitCode != 0) return errEnvelope("OPERATION_FAILED", failMessage(r), args)
        val pkgs = r.stdout.lineSequence()
            .map { it.removePrefix("package:") }
            .filter { it.isNotBlank() }
            .toList()
        if (args.get("format") == "json") {
            val arr = JSONArray()
            for (p in pkgs) arr.put(JSONObject().put("packageName", p))
            return okEnvelope(arr, args)
        }
        if (args.get("format") == "csv") {
            val csv = "packageName\n" + pkgs.joinToString("\n")
            return ok(csv + "\n")
        }
        return ok(pkgs.joinToString("\n") + if (pkgs.isEmpty()) "" else "\n")
    }

    private fun packageInfo(pkg: String?, args: OffloadArgs): NativeOffloadResult {
        if (pkg.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "package info <packageName>", args)
        val cmd = mutableListOf("dumpsys", "package", pkg)
        val r = ShizukuManager.runProcess(cmd.toTypedArray(), timeoutMs = 10_000)
        if (r.exitCode != 0) return errEnvelope("PACKAGE_NOT_FOUND", r.combined, args)
        val d = parseDumpsysPackage(r.stdout, pkg)
        return okEnvelope(d, args)
    }

    private fun packageInstall(apk: String?, args: OffloadArgs): NativeOffloadResult {
        if (apk.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "package install <apkPath>", args)
        val cmd = mutableListOf("pm", "install")
        if (args.hasFlag("replace", "r")) cmd.add("-r") else cmd.add("-r")  // default -r
        if (args.hasFlag("downgrade")) cmd.add("-d")
        if (args.hasFlag("grant-permissions")) cmd.add("-g")
        args.get("user")?.let { cmd.addAll(listOf("--user", it)) }
        cmd.add(apk)
        val r = ShizukuManager.runProcess(cmd.toTypedArray(), timeoutMs = 60_000)
        return if (r.exitCode == 0 && r.stdout.contains("Success")) {
            okEnvelope(JSONObject().put("installed", apk), args)
        } else {
            errEnvelope("OPERATION_FAILED", r.combined.ifBlank { "install failed" }, args)
        }
    }

    private fun packageUninstall(pkg: String?, args: OffloadArgs): NativeOffloadResult {
        if (pkg.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "package uninstall <packageName>", args)
        val cmd = mutableListOf("pm", "uninstall")
        if (args.hasFlag("keep-data", "k")) cmd.add("-k")
        args.get("user")?.let { cmd.addAll(listOf("--user", it)) }
        cmd.add(pkg)
        val r = ShizukuManager.runProcess(cmd.toTypedArray(), timeoutMs = 30_000)
        return if (r.exitCode == 0 && r.stdout.contains("Success")) {
            okEnvelope(JSONObject().put("uninstalled", pkg), args)
        } else {
            errEnvelope("OPERATION_FAILED", r.combined.ifBlank { "uninstall failed" }, args)
        }
    }

    private fun packageEnableDisable(pkg: String?, args: OffloadArgs, enable: Boolean): NativeOffloadResult {
        if (pkg.isNullOrBlank()) {
            return errEnvelope("INVALID_ARGS", "package ${if (enable) "enable" else "disable"} <packageName>", args)
        }
        val verb = if (enable) "enable" else "disable-user"
        val cmd = mutableListOf("pm", verb)
        args.get("user")?.let { cmd.addAll(listOf("--user", it)) }
        cmd.add(pkg)
        val r = ShizukuManager.runProcess(cmd.toTypedArray())
        return if (r.exitCode == 0) {
            okEnvelope(JSONObject().put("package", pkg).put("state", if (enable) "enabled" else "disabled"), args)
        } else {
            errEnvelope("OPERATION_FAILED", failMessage(r), args)
        }
    }

    private fun packageClear(pkg: String?, args: OffloadArgs): NativeOffloadResult {
        if (pkg.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "package clear <packageName>", args)
        val cmd: MutableList<String> = if (args.hasFlag("cache-only")) {
            mutableListOf("pm", "trim-caches", "1G")
        } else {
            mutableListOf("pm", "clear").apply {
                args.get("user")?.let { addAll(listOf("--user", it)) }
                add(pkg)
            }
        }
        val r = ShizukuManager.runProcess(cmd.toTypedArray(), timeoutMs = 15_000)
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("cleared", pkg), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    private fun packagePath(pkg: String?, args: OffloadArgs): NativeOffloadResult {
        if (pkg.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "package path <packageName>", args)
        val r = ShizukuManager.runProcess(arrayOf("pm", "path", pkg))
        if (r.exitCode != 0) return errEnvelope("PACKAGE_NOT_FOUND", r.combined, args)
        val paths = r.stdout.lineSequence()
            .map { it.removePrefix("package:") }
            .filter { it.isNotBlank() }
            .toList()
        return if (args.get("format") == "json") okEnvelope(JSONArray(paths), args)
        else ok(paths.joinToString("\n") + "\n")
    }

    // ─── Group: permission ────────────────────────────────────────────────
    private fun handlePermission(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = rest.firstOrNull() ?: return ok(PERMISSION_HELP)
        return when (sub) {
            "list" -> permissionList(args.positional.getOrNull(1), args)
            "grant" -> permissionGrantRevoke(args.positional.getOrNull(1), args.positional.getOrNull(2), args, grant = true)
            "revoke" -> permissionGrantRevoke(args.positional.getOrNull(1), args.positional.getOrNull(2), args, grant = false)
            "appops" -> permissionAppops(args.positional.drop(1), args)
            "help", "--help", "-h" -> ok(PERMISSION_HELP)
            else -> NativeOffloadResult(2, "permission: unknown subcommand '$sub'\n$PERMISSION_HELP")
        }
    }

    private fun permissionList(pkg: String?, args: OffloadArgs): NativeOffloadResult {
        if (pkg.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "permission list <packageName>", args)
        val cmd = mutableListOf("dumpsys", "package", pkg)
        val r = ShizukuManager.runProcess(cmd.toTypedArray(), timeoutMs = 10_000)
        if (r.exitCode != 0) return errEnvelope("PACKAGE_NOT_FOUND", r.combined, args)
        val perms = parseDumpsysPermissions(r.stdout)
        val onlyGranted = args.hasFlag("granted")
        val onlyDenied = args.hasFlag("denied")
        val onlyDangerous = args.hasFlag("dangerous")
        val filtered = perms.filter { p ->
            (!onlyGranted || p.granted) &&
                (!onlyDenied || !p.granted) &&
                (!onlyDangerous || p.dangerous)
        }
        if (args.get("format") == "json") {
            val arr = JSONArray()
            for (p in filtered) {
                arr.put(JSONObject()
                    .put("name", p.name)
                    .put("granted", p.granted)
                    .put("dangerous", p.dangerous))
            }
            return okEnvelope(arr, args)
        }
        val text = filtered.joinToString("\n") { "${if (it.granted) "✓" else "✗"} ${it.name}" }
        return ok(text + if (text.isNotEmpty()) "\n" else "")
    }

    private fun permissionGrantRevoke(pkg: String?, perm: String?, args: OffloadArgs, grant: Boolean): NativeOffloadResult {
        if (pkg.isNullOrBlank() || perm.isNullOrBlank()) {
            return errEnvelope("INVALID_ARGS", "permission ${if (grant) "grant" else "revoke"} <pkg> <permission>", args)
        }
        val cmd = mutableListOf("pm", if (grant) "grant" else "revoke")
        args.get("user")?.let { cmd.addAll(listOf("--user", it)) }
        cmd.add(pkg); cmd.add(perm)
        val r = ShizukuManager.runProcess(cmd.toTypedArray())
        return if (r.exitCode == 0) {
            okEnvelope(JSONObject().put("package", pkg).put("permission", perm).put("granted", grant), args)
        } else {
            errEnvelope("OPERATION_FAILED", failMessage(r), args)
        }
    }

    private fun permissionAppops(pos: List<String>, args: OffloadArgs): NativeOffloadResult {
        if (pos.size < 3) return errEnvelope("INVALID_ARGS", "permission appops <pkg> <op> <mode>", args)
        val (pkg, op, mode) = Triple(pos[0], pos[1], pos[2])
        val r = ShizukuManager.runProcess(arrayOf("appops", "set", pkg, op, mode))
        return if (r.exitCode == 0) {
            okEnvelope(JSONObject().put("package", pkg).put("op", op).put("mode", mode), args)
        } else {
            errEnvelope("OPERATION_FAILED", failMessage(r), args)
        }
    }

    // ─── Group: activity ──────────────────────────────────────────────────
    private fun handleActivity(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = rest.firstOrNull() ?: return ok(ACTIVITY_HELP)
        return when (sub) {
            "start" -> activityStart(args)
            "force-stop" -> activityForceStop(args.positional.getOrNull(1), args)
            "kill" -> activityKill(args.positional.getOrNull(1), args)
            "broadcast" -> activityBroadcast(args.positional.getOrNull(1), args)
            "top" -> activityTop(args)
            "help", "--help", "-h" -> ok(ACTIVITY_HELP)
            else -> NativeOffloadResult(2, "activity: unknown subcommand '$sub'\n$ACTIVITY_HELP")
        }
    }

    private fun activityStart(args: OffloadArgs): NativeOffloadResult {
        val cmd = mutableListOf("am", "start", "-W")
        args.get("action", "a")?.let { cmd.addAll(listOf("-a", it)) }
        args.get("data", "d")?.let { cmd.addAll(listOf("-d", it)) }
        args.get("package", "p")?.let { cmd.addAll(listOf("-p", it)) }
        args.get("component", "c")?.let { cmd.addAll(listOf("-n", it)) }
        args.get("user")?.let { cmd.addAll(listOf("--user", it)) }
        // Extras: --extra-string key=value (parser surfaces as values)
        for ((k, v) in extras(args, "extra-string")) {
            cmd.addAll(listOf("--es", k, v))
        }
        for ((k, v) in extras(args, "extra-int")) {
            cmd.addAll(listOf("--ei", k, v))
        }
        for ((k, v) in extras(args, "extra-bool")) {
            cmd.addAll(listOf("--ez", k, v))
        }
        val r = ShizukuManager.runProcess(cmd.toTypedArray(), timeoutMs = 15_000)
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("status", "started").put("output", r.stdout), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    private fun activityForceStop(pkg: String?, args: OffloadArgs): NativeOffloadResult {
        if (pkg.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "activity force-stop <pkg>", args)
        val cmd = mutableListOf("am", "force-stop")
        args.get("user")?.let { cmd.addAll(listOf("--user", it)) }
        cmd.add(pkg)
        val r = ShizukuManager.runProcess(cmd.toTypedArray())
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("stopped", pkg), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    private fun activityKill(pkg: String?, args: OffloadArgs): NativeOffloadResult {
        if (pkg.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "activity kill <pkg>", args)
        val r = ShizukuManager.runProcess(arrayOf("am", "kill", pkg))
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("killed", pkg), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    private fun activityBroadcast(action: String?, args: OffloadArgs): NativeOffloadResult {
        if (action.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "activity broadcast <action>", args)
        val cmd = mutableListOf("am", "broadcast", "-a", action)
        args.get("package")?.let { cmd.addAll(listOf("-p", it)) }
        args.get("component")?.let { cmd.addAll(listOf("-n", it)) }
        args.get("user")?.let { cmd.addAll(listOf("--user", it)) }
        for ((k, v) in extras(args, "extra-string")) cmd.addAll(listOf("--es", k, v))
        for ((k, v) in extras(args, "extra-int")) cmd.addAll(listOf("--ei", k, v))
        val r = ShizukuManager.runProcess(cmd.toTypedArray())
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("broadcast", action).put("output", r.stdout), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    private fun activityTop(args: OffloadArgs): NativeOffloadResult {
        val r = ShizukuManager.runProcess(arrayOf("dumpsys", "activity", "activities"), timeoutMs = 6_000)
        if (r.exitCode != 0) return errEnvelope("OPERATION_FAILED", failMessage(r), args)
        // Look for "topResumedActivity=ActivityRecord{... pkg/cls ...}"
        val regex = Regex("""topResumedActivity=ActivityRecord\{[^ ]+ \d+ ([^/]+)/([^ ]+)""")
        val m = regex.find(r.stdout)
        val obj = if (m != null) {
            JSONObject().put("packageName", m.groupValues[1]).put("activityName", m.groupValues[2])
        } else {
            JSONObject().put("packageName", "unknown").put("activityName", "unknown")
        }
        return okEnvelope(obj, args)
    }

    // ─── Group: display ───────────────────────────────────────────────────
    private fun handleDisplay(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = rest.firstOrNull() ?: return ok(DISPLAY_HELP)
        return when (sub) {
            "list" -> displayList(args)
            "set" -> displaySet(args)
            "reset" -> displayReset(args)
            "help", "--help", "-h" -> ok(DISPLAY_HELP)
            else -> NativeOffloadResult(2, "display: unknown subcommand '$sub'\n$DISPLAY_HELP")
        }
    }

    private fun displayList(args: OffloadArgs): NativeOffloadResult {
        val r = ShizukuManager.runProcess(arrayOf("dumpsys", "display"), timeoutMs = 6_000)
        if (r.exitCode != 0) return errEnvelope("OPERATION_FAILED", failMessage(r), args)
        // Parse "DisplayDeviceInfo" blocks; each contains "<id>" + width/height/density/refresh.
        val displays = JSONArray()
        val blockRegex = Regex("""DisplayDeviceInfo\{"[^"]*"[^}]*\}""")
        for (block in blockRegex.findAll(r.stdout)) {
            val text = block.value
            val w = Regex("""(\d+) x (\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            val h = Regex("""(\d+) x (\d+)""").find(text)?.groupValues?.get(2)?.toIntOrNull()
            val dpi = Regex("""density (\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            val rate = Regex("""(\d+(?:\.\d+)?) fps""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
            val rotation = Regex("""rotation (\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            displays.put(JSONObject()
                .put("width", w ?: -1)
                .put("height", h ?: -1)
                .put("density", dpi ?: -1)
                .put("refreshRate", rate ?: -1.0)
                .put("rotation", rotation ?: 0))
        }
        return okEnvelope(displays, args)
    }

    private fun displaySet(args: OffloadArgs): NativeOffloadResult {
        var ok = true
        val results = JSONObject()
        args.getInt("density")?.let {
            val r = ShizukuManager.runProcess(arrayOf("wm", "density", it.toString()))
            results.put("density", r.exitCode == 0)
            ok = ok && r.exitCode == 0
        }
        val w = args.getInt("width"); val h = args.getInt("height")
        if (w != null && h != null) {
            val r = ShizukuManager.runProcess(arrayOf("wm", "size", "${w}x${h}"))
            results.put("size", r.exitCode == 0)
            ok = ok && r.exitCode == 0
        }
        return if (ok) okEnvelope(results, args)
        else errEnvelope("OPERATION_FAILED", "one or more `wm` calls failed: $results", args)
    }

    private fun displayReset(args: OffloadArgs): NativeOffloadResult {
        val r1 = ShizukuManager.runProcess(arrayOf("wm", "size", "reset"))
        val r2 = ShizukuManager.runProcess(arrayOf("wm", "density", "reset"))
        return if (r1.exitCode == 0 && r2.exitCode == 0) okEnvelope(JSONObject().put("reset", true), args)
        else errEnvelope("OPERATION_FAILED", "size:${r1.combined} density:${r2.combined}", args)
    }

    // ─── Group: settings ──────────────────────────────────────────────────
    private fun handleSettings(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = rest.firstOrNull() ?: return ok(SETTINGS_HELP)
        val ns = args.positional.getOrNull(1)
        return when (sub) {
            "get" -> settingsGet(ns, args.positional.getOrNull(2), args)
            "set" -> settingsSet(ns, args.positional.getOrNull(2), args.positional.getOrNull(3), args)
            "delete" -> settingsDelete(ns, args.positional.getOrNull(2), args)
            "list" -> settingsList(ns, args)
            "help", "--help", "-h" -> ok(SETTINGS_HELP)
            else -> NativeOffloadResult(2, "settings: unknown subcommand '$sub'\n$SETTINGS_HELP")
        }
    }

    private fun validNs(ns: String?): Boolean = ns in listOf("global", "secure", "system")

    private fun settingsGet(ns: String?, key: String?, args: OffloadArgs): NativeOffloadResult {
        if (!validNs(ns) || key.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "settings get <global|secure|system> <key>", args)
        val r = ShizukuManager.runProcess(arrayOf("settings", "get", ns!!, key))
        // T339: `settings get` returns exitCode=1 with empty stdout/stderr when the key
        // is missing from the namespace database. Don't pass an empty message through —
        // synthesize a useful one so callers see what went wrong.
        if (r.exitCode == 0) return okEnvelope(JSONObject().put("key", key).put("value", r.stdout.trim()), args)
        // T343: branch the synthesized hint by exitCode — `settings` returns 1
        // for missing keys, 124 for our local timeout, 143 when something
        // SIGTERM'd the child, anything else is a generic privileged-shell
        // failure.
        val hint = r.combined.ifBlank {
            when (r.exitCode) {
                1 -> "settings $ns key='$key' does not exist or is not readable (exitCode=1). " +
                    "Try `android-shizuku-cli settings list $ns` to see available keys."
                124 -> "settings get $ns $key timed out (exitCode=124); raise --timeout-ms or check Shizuku service health."
                143 -> "settings get $ns $key was killed mid-flight (SIGTERM/exitCode=143); " +
                    "the privileged process was destroyed before it could finish."
                else -> "settings get $ns $key failed (exitCode=${r.exitCode})"
            }
        }
        return errEnvelope("OPERATION_FAILED", hint, args)
    }

    private fun settingsSet(ns: String?, key: String?, value: String?, args: OffloadArgs): NativeOffloadResult {
        if (!validNs(ns) || key.isNullOrBlank() || value == null) {
            return errEnvelope("INVALID_ARGS", "settings set <ns> <key> <value>", args)
        }
        val r = ShizukuManager.runProcess(arrayOf("settings", "put", ns!!, key, value))
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("key", key).put("value", value), args)
        else errEnvelope(
            "OPERATION_FAILED",
            r.combined.ifBlank { "settings put $ns $key failed (exitCode=${r.exitCode})" },
            args,
        )
    }

    private fun settingsDelete(ns: String?, key: String?, args: OffloadArgs): NativeOffloadResult {
        if (!validNs(ns) || key.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "settings delete <ns> <key>", args)
        val r = ShizukuManager.runProcess(arrayOf("settings", "delete", ns!!, key))
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("deleted", key), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r, "settings delete $ns $key"), args)
    }

    private fun settingsList(ns: String?, args: OffloadArgs): NativeOffloadResult {
        if (!validNs(ns)) return errEnvelope("INVALID_ARGS", "settings list <global|secure|system>", args)
        val r = ShizukuManager.runProcess(arrayOf("settings", "list", ns!!), timeoutMs = 8_000)
        if (r.exitCode != 0) return errEnvelope("OPERATION_FAILED", failMessage(r, "settings list $ns"), args)
        val filter = args.get("filter")
        val obj = JSONObject()
        for (line in r.stdout.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val k = line.substring(0, eq); val v = line.substring(eq + 1)
            if (filter != null && !k.contains(filter)) continue
            obj.put(k, v)
        }
        return okEnvelope(obj, args)
    }

    // ─── Group: user ──────────────────────────────────────────────────────
    private fun handleUser(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = rest.firstOrNull() ?: return ok(USER_HELP)
        return when (sub) {
            "list" -> userList(args)
            "create" -> userCreate(args.positional.getOrNull(1), args)
            "remove" -> userRemove(args.positional.getOrNull(1), args)
            "switch" -> userSwitch(args.positional.getOrNull(1), args)
            "start" -> userStartStop(args.positional.getOrNull(1), args, start = true)
            "stop" -> userStartStop(args.positional.getOrNull(1), args, start = false)
            "help", "--help", "-h" -> ok(USER_HELP)
            else -> NativeOffloadResult(2, "user: unknown subcommand '$sub'\n$USER_HELP")
        }
    }

    private fun userList(args: OffloadArgs): NativeOffloadResult {
        val r = ShizukuManager.runProcess(arrayOf("pm", "list", "users"))
        if (r.exitCode != 0) return errEnvelope("OPERATION_FAILED", failMessage(r), args)
        val users = JSONArray()
        // Format: "UserInfo{<id>:<name>:<flags>} running"
        val regex = Regex("""UserInfo\{(\d+):([^:}]*):([\da-fA-Fx]+)\}\s*(running)?""")
        for (m in regex.findAll(r.stdout)) {
            users.put(JSONObject()
                .put("userId", m.groupValues[1].toIntOrNull() ?: -1)
                .put("name", m.groupValues[2])
                .put("flags", m.groupValues[3])
                .put("isRunning", m.groupValues[4] == "running"))
        }
        return okEnvelope(users, args)
    }

    private fun userCreate(name: String?, args: OffloadArgs): NativeOffloadResult {
        if (name.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "user create <name>", args)
        val cmd = mutableListOf("pm", "create-user")
        if (args.hasFlag("managed-profile")) cmd.add("--profileOf")
        if (args.hasFlag("guest")) cmd.add("--guest")
        cmd.add(name)
        val r = ShizukuManager.runProcess(cmd.toTypedArray())
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("created", r.stdout.trim()), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    private fun userRemove(id: String?, args: OffloadArgs): NativeOffloadResult {
        if (id.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "user remove <userId>", args)
        val r = ShizukuManager.runProcess(arrayOf("pm", "remove-user", id))
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("removed", id), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    private fun userSwitch(id: String?, args: OffloadArgs): NativeOffloadResult {
        if (id.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "user switch <userId>", args)
        val r = ShizukuManager.runProcess(arrayOf("am", "switch-user", id))
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("switched", id), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    private fun userStartStop(id: String?, args: OffloadArgs, start: Boolean): NativeOffloadResult {
        if (id.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "user ${if (start) "start" else "stop"} <userId>", args)
        val r = ShizukuManager.runProcess(arrayOf("am", if (start) "start-user" else "stop-user", id))
        return if (r.exitCode == 0) okEnvelope(JSONObject().put(if (start) "started" else "stopped", id), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    // ─── Group: network ───────────────────────────────────────────────────
    private fun handleNetwork(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = rest.firstOrNull() ?: return ok(NETWORK_HELP)
        return when (sub) {
            "restrict" -> networkRestrict(args.positional.getOrNull(1), args, restrict = true)
            "allow" -> networkRestrict(args.positional.getOrNull(1), args, restrict = false)
            "stats" -> networkStats(args.positional.getOrNull(1), args)
            "help", "--help", "-h" -> ok(NETWORK_HELP)
            else -> NativeOffloadResult(2, "network: unknown subcommand '$sub'\n$NETWORK_HELP")
        }
    }

    private fun networkRestrict(pkg: String?, args: OffloadArgs, restrict: Boolean): NativeOffloadResult {
        if (pkg.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "network ${if (restrict) "restrict" else "allow"} <pkg>", args)
        val results = JSONObject()
        val all = args.hasFlag("all")
        if (args.hasFlag("background") || all) {
            // RUN_ANY_IN_BACKGROUND appop
            val mode = if (restrict) "ignore" else "allow"
            val r = ShizukuManager.runProcess(arrayOf("appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", mode))
            results.put("background", r.exitCode == 0)
        }
        if (args.hasFlag("wifi") || all) {
            // cmd netpolicy is the modern surface; older devices use `cmd netpolicy set restrict-background`
            val r = ShizukuManager.runProcess(arrayOf("cmd", "netpolicy",
                if (restrict) "add" else "remove", "restrict-background-whitelist", pkgUid(pkg)))
            results.put("wifi", r.exitCode == 0)
        }
        if (args.hasFlag("data") || all) {
            val r = ShizukuManager.runProcess(arrayOf("cmd", "netpolicy",
                if (restrict) "add" else "remove", "restrict-background-blacklist", pkgUid(pkg)))
            results.put("data", r.exitCode == 0)
        }
        return okEnvelope(results, args)
    }

    private fun networkStats(pkg: String?, args: OffloadArgs): NativeOffloadResult {
        if (pkg.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "network stats <pkg>", args)
        // dumpsys netstats detail; surface the per-uid block.
        val r = ShizukuManager.runProcess(arrayOf("dumpsys", "netstats", "detail"), timeoutMs = 10_000)
        if (r.exitCode != 0) return errEnvelope("OPERATION_FAILED", failMessage(r), args)
        val uid = pkgUid(pkg).toIntOrNull() ?: -1
        val (rx, tx) = parseNetstats(r.stdout, uid)
        val obj = JSONObject().put("package", pkg).put("uid", uid).put("rx_bytes", rx).put("tx_bytes", tx)
        return okEnvelope(obj, args)
    }

    private fun pkgUid(pkg: String): String {
        val r = ShizukuManager.runProcess(arrayOf("dumpsys", "package", pkg), timeoutMs = 6_000)
        val m = Regex("""userId=(\d+)""").find(r.stdout)
        return m?.groupValues?.get(1) ?: "0"
    }

    // ─── Group: input ─────────────────────────────────────────────────────
    private fun handleInput(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = rest.firstOrNull() ?: return ok(INPUT_HELP)
        return when (sub) {
            "tap" -> inputTap(args.positional.getOrNull(1), args.positional.getOrNull(2), args)
            "swipe" -> inputSwipe(args.positional.drop(1), args)
            "key" -> inputKey(args.positional.getOrNull(1), args)
            "text" -> inputText(args.positional.drop(1).joinToString(" "), args)
            "help", "--help", "-h" -> ok(INPUT_HELP)
            else -> NativeOffloadResult(2, "input: unknown subcommand '$sub'\n$INPUT_HELP")
        }
    }

    private fun inputTap(x: String?, y: String?, args: OffloadArgs): NativeOffloadResult {
        if (x.isNullOrBlank() || y.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "input tap <x> <y>", args)
        val r = ShizukuManager.runProcess(arrayOf("input", "tap", x, y))
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("tap", "$x,$y"), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    private fun inputSwipe(coords: List<String>, args: OffloadArgs): NativeOffloadResult {
        if (coords.size < 4) return errEnvelope("INVALID_ARGS", "input swipe <x1> <y1> <x2> <y2>", args)
        val cmd = mutableListOf("input", "swipe", coords[0], coords[1], coords[2], coords[3])
        args.get("duration")?.let { cmd.add(it) }
        val r = ShizukuManager.runProcess(cmd.toTypedArray())
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("swipe", coords.take(4)), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    private fun inputKey(key: String?, args: OffloadArgs): NativeOffloadResult {
        if (key.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "input key <keycode>", args)
        val cmd = mutableListOf("input", "keyevent")
        if (args.hasFlag("long-press")) cmd.add("--longpress")
        cmd.add(key)
        val r = ShizukuManager.runProcess(cmd.toTypedArray())
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("key", key), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    private fun inputText(text: String, args: OffloadArgs): NativeOffloadResult {
        if (text.isBlank()) return errEnvelope("INVALID_ARGS", "input text <text>", args)
        // `input text` doesn't accept spaces directly — replace with %s
        val safe = text.replace(' ', '%').replace("'", "")
        val r = ShizukuManager.runProcess(arrayOf("input", "text", safe))
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("text", text), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    // ─── Group: notification ──────────────────────────────────────────────
    private fun handleNotification(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = rest.firstOrNull() ?: return ok(NOTIFICATION_HELP)
        return when (sub) {
            "list" -> notificationList(args)
            "dismiss" -> notificationDismiss(args)
            "channel" -> notificationChannel(rest.drop(1), args)
            "help", "--help", "-h" -> ok(NOTIFICATION_HELP)
            else -> NativeOffloadResult(2, "notification: unknown subcommand '$sub'\n$NOTIFICATION_HELP")
        }
    }

    private fun notificationList(args: OffloadArgs): NativeOffloadResult {
        val r = ShizukuManager.runProcess(arrayOf("dumpsys", "notification", "--noredact"), timeoutMs = 8_000)
        if (r.exitCode != 0) return errEnvelope("OPERATION_FAILED", failMessage(r), args)
        val arr = JSONArray()
        val pkgFilter = args.get("package")
        // Each NotificationRecord block starts with "NotificationRecord(...)"
        val regex = Regex("""NotificationRecord\(.*?pkg=([^\s]+).*?id=(\d+).*?tag=([^\s]+)?""")
        for (m in regex.findAll(r.stdout)) {
            val pkg = m.groupValues[1]
            if (pkgFilter != null && pkg != pkgFilter) continue
            arr.put(JSONObject()
                .put("packageName", pkg)
                .put("id", m.groupValues[2].toIntOrNull() ?: -1)
                .put("tag", m.groupValues[3].ifBlank { null }))
        }
        return okEnvelope(arr, args)
    }

    private fun notificationDismiss(args: OffloadArgs): NativeOffloadResult {
        if (args.hasFlag("all")) {
            val r = ShizukuManager.runProcess(arrayOf("cmd", "notification", "cancel_all"))
            return if (r.exitCode == 0) okEnvelope(JSONObject().put("cancelled", "all"), args)
            else errEnvelope("OPERATION_FAILED", failMessage(r), args)
        }
        val pkg = args.get("package")
        val id = args.get("id")
        return when {
            pkg != null -> {
                val r = ShizukuManager.runProcess(arrayOf("cmd", "notification", "cancel", pkg))
                if (r.exitCode == 0) okEnvelope(JSONObject().put("cancelled", pkg), args)
                else errEnvelope("OPERATION_FAILED", failMessage(r), args)
            }
            id != null -> {
                val r = ShizukuManager.runProcess(arrayOf("cmd", "notification", "cancel", id))
                if (r.exitCode == 0) okEnvelope(JSONObject().put("cancelled_id", id), args)
                else errEnvelope("OPERATION_FAILED", failMessage(r), args)
            }
            else -> errEnvelope("INVALID_ARGS", "notification dismiss --all | --package <pkg> | --id <id>", args)
        }
    }

    private fun notificationChannel(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = rest.firstOrNull() ?: return errEnvelope("INVALID_ARGS", "notification channel list <pkg> | set <pkg> <channelId> [--block|--unblock|--importance N]", args)
        return when (sub) {
            "list" -> {
                val pkg = rest.getOrNull(1) ?: return errEnvelope("INVALID_ARGS", "notification channel list <pkg>", args)
                val r = ShizukuManager.runProcess(arrayOf("dumpsys", "notification"), timeoutMs = 8_000)
                if (r.exitCode != 0) return errEnvelope("OPERATION_FAILED", failMessage(r), args)
                val arr = JSONArray()
                val regex = Regex("""NotificationChannel\{.*?id=([^,]+).*?name=([^,]+).*?importance=(\d+)""")
                for (m in regex.findAll(r.stdout)) {
                    arr.put(JSONObject()
                        .put("id", m.groupValues[1])
                        .put("name", m.groupValues[2])
                        .put("importance", m.groupValues[3].toIntOrNull() ?: -1))
                }
                okEnvelope(arr, args)
            }
            "set" -> {
                val pkg = rest.getOrNull(1)
                val ch = rest.getOrNull(2)
                if (pkg == null || ch == null) return errEnvelope("INVALID_ARGS", "notification channel set <pkg> <channelId>", args)
                val cmd = mutableListOf("cmd", "notification")
                when {
                    args.hasFlag("block") -> { cmd.addAll(listOf("set_bubbles", pkg, "0")) }  // best-effort
                    args.hasFlag("unblock") -> { cmd.addAll(listOf("set_bubbles", pkg, "1")) }
                    else -> {
                        val imp = args.get("importance") ?: return errEnvelope("INVALID_ARGS", "channel set requires --block/--unblock/--importance", args)
                        cmd.addAll(listOf("set_channel_importance", pkg, ch, imp))
                    }
                }
                val r = ShizukuManager.runProcess(cmd.toTypedArray())
                if (r.exitCode == 0) okEnvelope(JSONObject().put("channel", ch).put("package", pkg), args)
                else errEnvelope("OPERATION_FAILED", failMessage(r), args)
            }
            else -> errEnvelope("INVALID_ARGS", "notification channel list|set", args)
        }
    }

    // ─── Group: file ──────────────────────────────────────────────────────
    private fun handleFile(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = rest.firstOrNull() ?: return ok(FILE_HELP)
        return when (sub) {
            "ls" -> fileLs(args.positional.getOrNull(1), args)
            "pull" -> filePullPush(args.positional.getOrNull(1), args.positional.getOrNull(2), args, pull = true)
            "push" -> filePullPush(args.positional.getOrNull(1), args.positional.getOrNull(2), args, pull = false)
            "rm" -> fileRm(args.positional.getOrNull(1), args)
            "help", "--help", "-h" -> ok(FILE_HELP)
            else -> NativeOffloadResult(2, "file: unknown subcommand '$sub'\n$FILE_HELP")
        }
    }

    private fun fileLs(path: String?, args: OffloadArgs): NativeOffloadResult {
        if (path.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "file ls <path>", args)
        val cmd = mutableListOf("ls")
        if (args.hasFlag("long", "l")) cmd.add("-la") else cmd.add("-1")
        if (args.hasFlag("recursive", "r")) cmd.add("-R")
        cmd.add(path)
        val r = ShizukuManager.runProcess(cmd.toTypedArray(), timeoutMs = 8_000)
        return if (r.exitCode == 0) ok(r.stdout + "\n")
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    private fun filePullPush(src: String?, dst: String?, args: OffloadArgs, pull: Boolean): NativeOffloadResult {
        if (src.isNullOrBlank() || dst.isNullOrBlank()) {
            return errEnvelope("INVALID_ARGS", "file ${if (pull) "pull" else "push"} <src> <dst>", args)
        }
        // Use cp via Shizuku — works for both directions because the
        // Shizuku service has full FS access, while our own uid usually
        // does not for /Android/data/* paths.
        val r = ShizukuManager.runProcess(arrayOf("cp", "-r", src, dst), timeoutMs = 30_000)
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("src", src).put("dst", dst), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    private fun fileRm(path: String?, args: OffloadArgs): NativeOffloadResult {
        if (path.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "file rm <path>", args)
        val cmd = mutableListOf("rm")
        if (args.hasFlag("recursive", "r")) cmd.add("-rf") else cmd.add("-f")
        cmd.add(path)
        val r = ShizukuManager.runProcess(cmd.toTypedArray())
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("removed", path), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    // ─── Group: device ────────────────────────────────────────────────────
    private fun handleDevice(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = rest.firstOrNull() ?: return ok(DEVICE_HELP)
        return when (sub) {
            "info" -> deviceInfo(args)
            "battery" -> deviceBattery(args)
            "usage" -> deviceUsage(args)
            "help", "--help", "-h" -> ok(DEVICE_HELP)
            else -> NativeOffloadResult(2, "device: unknown subcommand '$sub'\n$DEVICE_HELP")
        }
    }

    private fun deviceInfo(args: OffloadArgs): NativeOffloadResult {
        val obj = JSONObject()
            .put("brand", android.os.Build.BRAND)
            .put("model", android.os.Build.MODEL)
            .put("manufacturer", android.os.Build.MANUFACTURER)
            .put("androidVersion", android.os.Build.VERSION.RELEASE)
            .put("sdkInt", android.os.Build.VERSION.SDK_INT)
            .put("buildId", android.os.Build.ID)
        return okEnvelope(obj, args)
    }

    private fun deviceBattery(args: OffloadArgs): NativeOffloadResult {
        val r = ShizukuManager.runProcess(arrayOf("dumpsys", "battery"), timeoutMs = 5_000)
        if (r.exitCode != 0) return errEnvelope("OPERATION_FAILED", failMessage(r), args)
        val obj = JSONObject()
        for (line in r.stdout.lineSequence()) {
            val t = line.trim()
            val colon = t.indexOf(':')
            if (colon <= 0) continue
            val k = t.substring(0, colon).trim()
            val v = t.substring(colon + 1).trim()
            obj.put(k.replace(' ', '_').lowercase(), v)
        }
        return okEnvelope(obj, args)
    }

    private fun deviceUsage(args: OffloadArgs): NativeOffloadResult {
        val pkg = args.get("package")
        val cmd = mutableListOf("dumpsys", "usagestats")
        val r = ShizukuManager.runProcess(cmd.toTypedArray(), timeoutMs = 10_000)
        if (r.exitCode != 0) return errEnvelope("OPERATION_FAILED", failMessage(r), args)
        // Surface raw usagestats text — full structured parsing would dwarf this handler.
        val filtered = if (pkg != null) {
            r.stdout.lineSequence().filter { it.contains(pkg) }.joinToString("\n")
        } else {
            r.stdout
        }
        val top = args.getInt("top")
        val limited = if (top != null) filtered.lineSequence().take(top).joinToString("\n") else filtered
        return ok(limited + "\n")
    }

    // ─── Group: exec (raw shell passthrough) ───────────────────────────────
    /**
     * T341: wildcard fallback. Curated subcommands cover the well-known
     * surface, but LLM priors include a long tail of intuitive shell calls
     * (`pm list packages -f`, `cmd statusbar expand-notifications`, …) that
     * don't fit any group. `exec` joins remaining argv into a single shell
     * command string and runs it under Shizuku via `sh -c` so metacharacters
     * (`;`, `|`, `>`, backticks) keep their shell meaning. Caller is
     * responsible for quoting.
     */
    private fun handleExec(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        if (rest.isEmpty() || rest.firstOrNull() in listOf("help", "--help", "-h")) return ok(EXEC_HELP)
        val cmd = rest.joinToString(" ")
        val timeout = args.getInt("timeout-ms")?.toLong() ?: 30_000L
        val r = ShizukuManager.runProcess(arrayOf("sh", "-c", cmd), timeoutMs = timeout)
        val data = JSONObject()
            .put("command", cmd)
            .put("exitCode", r.exitCode)
            .put("stdout", r.stdout)
            .put("stderr", r.stderr)
            .put("combined", r.combined)
        return if (r.exitCode == 0) {
            okEnvelope(data, args)
        } else {
            // Surface non-zero exit with the same envelope shape so callers
            // get stdout/stderr even on failure — many shell tools (e.g.
            // `settings get` for an unknown key) exit non-zero with content.
            val obj = JSONObject().put("ok", false)
                .put("error", JSONObject()
                    .put("code", "OPERATION_FAILED")
                    .put("message", failMessage(r, "exec `$cmd`")))
                .put("data", data)
            val body = OffloadOutput.formatBody(obj.toString(2), args)
            NativeOffloadResult(r.exitCode, body + "\n")
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private data class PermInfo(val name: String, val granted: Boolean, val dangerous: Boolean)

    private fun parseDumpsysPermissions(s: String): List<PermInfo> {
        val out = mutableListOf<PermInfo>()
        val lines = s.lineSequence()
        var inSection = false
        var dangerous = false
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("requested permissions:")) { inSection = true; dangerous = false; continue }
            if (line.startsWith("install permissions:")) { inSection = true; dangerous = false; continue }
            if (line.startsWith("runtime permissions:")) { inSection = true; dangerous = true; continue }
            if (line.isEmpty()) { inSection = false; continue }
            if (!inSection) continue
            // `android.permission.X: granted=true`
            val colon = line.indexOf(':')
            val name = if (colon > 0) line.substring(0, colon) else line
            val granted = line.contains("granted=true")
            // Plain requested permissions section has no granted= marker;
            // skip those (they're shadowed by install/runtime entries).
            if (line.contains("granted=") || dangerous) {
                out.add(PermInfo(name.trim(), granted, dangerous))
            }
        }
        return out.distinctBy { it.name }
    }

    private fun parseDumpsysPackage(s: String, pkg: String): JSONObject {
        val obj = JSONObject().put("packageName", pkg)
        val regexes = mapOf(
            "versionName" to Regex("""versionName=([^\s]+)"""),
            "versionCode" to Regex("""versionCode=(\d+)"""),
            "targetSdk" to Regex("""targetSdk=(\d+)"""),
            "minSdk" to Regex("""minSdk=(\d+)"""),
            "firstInstallTime" to Regex("""firstInstallTime=([^\n]+)"""),
            "lastUpdateTime" to Regex("""lastUpdateTime=([^\n]+)"""),
            "installerPackageName" to Regex("""installerPackageName=([^\s]+)"""),
            "dataDir" to Regex("""dataDir=([^\s]+)"""),
            "codePath" to Regex("""codePath=([^\s]+)"""),
        )
        for ((k, r) in regexes) {
            r.find(s)?.groupValues?.get(1)?.let { obj.put(k, it) }
        }
        return obj
    }

    private fun parseNetstats(dump: String, uid: Int): Pair<Long, Long> {
        if (uid < 0) return 0L to 0L
        var rx = 0L; var tx = 0L
        // dumpsys netstats detail prints per-uid blocks: "uid=10042 ... rb=12345 tb=6789"
        val regex = Regex("""uid=$uid\D[^\n]*rb=(\d+)[^\n]*tb=(\d+)""")
        for (m in regex.findAll(dump)) {
            rx += m.groupValues[1].toLongOrNull() ?: 0L
            tx += m.groupValues[2].toLongOrNull() ?: 0L
        }
        return rx to tx
    }

    /** Pull all `--<flag-prefix> key=value` repeated args. */
    private fun extras(@Suppress("UNUSED_PARAMETER") args: OffloadArgs, prefix: String): List<Pair<String, String>> {
        // OffloadArgs collapses repeated --key value into a single map entry.
        // For multiplexed extras, callers pass `--extra-string` once with a
        // semicolon-separated list: --extra-string foo=bar;baz=qux.
        val raw = args.get(prefix) ?: return emptyList()
        return raw.split(';').mapNotNull {
            val eq = it.indexOf('='); if (eq <= 0) null else it.substring(0, eq) to it.substring(eq + 1)
        }
    }

    private fun ok(body: String) = NativeOffloadResult(0, body)

    private fun okEnvelope(payload: Any, args: OffloadArgs): NativeOffloadResult {
        val obj = JSONObject().put("ok", true).put("data", payload)
        val body = OffloadOutput.formatBody(obj.toString(2), args)
        return NativeOffloadResult(0, body + "\n")
    }

    /**
     * T339: many shell commands invoked via Shizuku exit non-zero with empty
     * stdout/stderr (e.g. `settings get` for an unknown key, `pm clear` on a
     * non-existent package). Wrap [r] so callers never surface a blank message
     * — the synthesized fallback keeps the original command context in view.
     */
    private fun failMessage(r: ShizukuManager.ProcessResult, context: String): String =
        r.combined.ifBlank { "$context failed (exitCode=${r.exitCode})" }

    /**
     * T343 overload: when no specific command context is available, still
     * synthesize a non-blank message keyed on exitCode so callers don't see
     * empty `OPERATION_FAILED` envelopes (the SIGTERM-143 + empty-output
     * combo was the surface symptom of the runProcess reflection bug).
     */
    private fun failMessage(r: ShizukuManager.ProcessResult): String =
        r.combined.ifBlank {
            when (r.exitCode) {
                124 -> "command timed out (exitCode=124); raise --timeout-ms or check Shizuku service health."
                143 -> "command was killed (SIGTERM/exitCode=143); privileged process destroyed mid-flight."
                else -> "command failed (exitCode=${r.exitCode})"
            }
        }

    private fun errEnvelope(code: String, message: String, args: OffloadArgs): NativeOffloadResult {
        val obj = JSONObject().put("ok", false).put(
            "error",
            JSONObject().put("code", code).put("message", message),
        )
        val body = OffloadOutput.formatBody(obj.toString(2), args)
        AppLogger.warning(TAG, "$code: $message")
        return NativeOffloadResult(1, body + "\n")
    }

    companion object {
        private const val TAG = "ShizukuOffload"

        private const val HELP = """android-shizuku-cli — privileged Android system control via Shizuku.

Usage:
  android-shizuku-cli <group> <subcommand> [flags]
  android-shizuku-cli exec <any shell command>      ← fallback passthrough

Fallback:
  exec         Run an arbitrary shell command with Shizuku privilege.
               Use this when no curated subcommand fits — `exec` accepts
               any `adb shell`-style invocation (pm, am, cmd, dumpsys,
               settings, wm, ime, appops, input, …) and returns
               {stdout, stderr, exitCode}. Curated groups below are
               convenience wrappers around `exec` that return structured
               JSON for common operations.

Groups:
  package      list / info / install / uninstall / enable / disable / clear / path
  permission   list / grant / revoke / appops
  activity     start / force-stop / kill / broadcast / top
  display      list / set / reset
  settings     get / set / delete / list
  user         list / create / remove / switch / start / stop
  network      restrict / allow / stats
  input        tap / swipe / key / text
  notification list / dismiss / channel
  file         ls / pull / push / rm
  device       info / battery / usage
  service      status / ping

Common flags:
  --format json|text|csv     output format (default text)
  --compact                  single-line JSON
  -q, --quiet                strip envelope (data only on success, error on failure)
  --user <id>                target user ID
  --help                     group / subcommand help

Run `android-shizuku-cli <group> --help` for group-specific flags.
"""

        private const val EXEC_HELP = """exec — raw shell passthrough (T341).

Usage:
  android-shizuku-cli exec <shell command...>     [--timeout-ms N]

Runs the joined argv via `sh -c` under the Shizuku service uid (same
privilege as `adb shell`). Returns {ok, data:{command, exitCode, stdout,
stderr, combined}}. On non-zero exit the stdout/stderr are still
included in `data` so callers can diagnose without re-running.

Examples:
  android-shizuku-cli exec pm list packages -f
  android-shizuku-cli exec dumpsys battery
  android-shizuku-cli exec "settings get global airplane_mode_on"
  android-shizuku-cli exec "logcat -d -t 200 | grep MyTag"

Notes:
  - Caller owns quoting/escaping. Metacharacters (; | > backticks) work.
  - Default timeout 30s; override via --timeout-ms.
  - Prefer curated subcommands when they exist — they emit structured JSON.
"""

        private const val SERVICE_HELP = """service — Shizuku runtime status.

Usage:
  android-shizuku-cli service status        State + version + uid
  android-shizuku-cli service ping          Quick connection check
"""

        private const val PACKAGE_HELP = """package — installed-app management.

Usage:
  android-shizuku-cli package list [--system|-3|--disabled] [--filter X]
  android-shizuku-cli package info <pkg>
  android-shizuku-cli package install <apkPath> [--grant-permissions] [--downgrade]
  android-shizuku-cli package uninstall <pkg> [--keep-data]
  android-shizuku-cli package enable <pkg>
  android-shizuku-cli package disable <pkg>
  android-shizuku-cli package clear <pkg> [--cache-only]
  android-shizuku-cli package path <pkg>
"""

        private const val PERMISSION_HELP = """permission — runtime + AppOps permissions.

Usage:
  android-shizuku-cli permission list <pkg> [--granted|--denied|--dangerous]
  android-shizuku-cli permission grant <pkg> <permission>
  android-shizuku-cli permission revoke <pkg> <permission>
  android-shizuku-cli permission appops <pkg> <op> <allow|deny|ignore|default>
"""

        private const val ACTIVITY_HELP = """activity — Activity / process management.

Usage:
  android-shizuku-cli activity start [-p pkg] [-c component] [-a action] [-d uri]
                               [--extra-string k=v;k=v] [--extra-int k=v]
  android-shizuku-cli activity force-stop <pkg>
  android-shizuku-cli activity kill <pkg>
  android-shizuku-cli activity broadcast <action> [--package pkg]
  android-shizuku-cli activity top
"""

        private const val DISPLAY_HELP = """display — display & resolution.

Usage:
  android-shizuku-cli display list
  android-shizuku-cli display set [--width N] [--height N] [--density DPI]
  android-shizuku-cli display reset
"""

        private const val SETTINGS_HELP = """settings — system Settings DB.

Usage:
  android-shizuku-cli settings get <global|secure|system> <key>
  android-shizuku-cli settings set <ns> <key> <value>
  android-shizuku-cli settings delete <ns> <key>
  android-shizuku-cli settings list <ns> [--filter X]
"""

        private const val USER_HELP = """user — multi-user management.

Usage:
  android-shizuku-cli user list
  android-shizuku-cli user create <name> [--managed-profile|--guest]
  android-shizuku-cli user remove <userId>
  android-shizuku-cli user switch <userId>
  android-shizuku-cli user start <userId>
  android-shizuku-cli user stop <userId>
"""

        private const val NETWORK_HELP = """network — net policy & stats.

Usage:
  android-shizuku-cli network restrict <pkg> [--background|--wifi|--data|--all]
  android-shizuku-cli network allow <pkg> [--background|--wifi|--data|--all]
  android-shizuku-cli network stats <pkg>
"""

        private const val INPUT_HELP = """input — input simulation.

Usage:
  android-shizuku-cli input tap <x> <y>
  android-shizuku-cli input swipe <x1> <y1> <x2> <y2> [--duration MS]
  android-shizuku-cli input key <KEYCODE> [--long-press]
  android-shizuku-cli input text <text>
"""

        private const val NOTIFICATION_HELP = """notification — system notifications.

Usage:
  android-shizuku-cli notification list [--package pkg]
  android-shizuku-cli notification dismiss [--all|--package pkg|--id N]
  android-shizuku-cli notification channel list <pkg>
  android-shizuku-cli notification channel set <pkg> <channelId>
                                         [--block|--unblock|--importance N]
"""

        private const val FILE_HELP = """file — privileged file access.

Usage:
  android-shizuku-cli file ls <path> [-l] [-r]
  android-shizuku-cli file pull <remote> <local>
  android-shizuku-cli file push <local> <remote>
  android-shizuku-cli file rm <path> [-r]
"""

        private const val DEVICE_HELP = """device — device state.

Usage:
  android-shizuku-cli device info
  android-shizuku-cli device battery
  android-shizuku-cli device usage [--package pkg] [--top N]
"""
    }
}

// ─── Merged from SpeechOffloadHandler.kt ──────────────────────────────────────────────────

/**
 * android-speech — speech recognition (audio → text).
 *
 * T61: re-aligned with iOS `apple-speech` (NativeOffloads/SpeechOffload.m).
 *
 *   android-speech transcribe [--engine system|provider|auto] [--source mic|<path>]
 *                             [--language tag] [--duration N] [--on-device]
 *   android-speech listen [...]              # legacy alias for transcribe
 *   android-speech languages [--language prefix]
 *   android-speech status [--probe]
 *
 * [T-431] (GH #431; the analysis that found these is the issue author's):
 *   - Output uses the iOS envelope `{ok, tool, action, data|error, timestamp}`;
 *     success data is also copied to the top level for prompts that learned
 *     the flat pre-#431 shape. Failures exit non-zero (transcribe used to
 *     print `{"error": ...}` with exit 0).
 *   - `status` says why recognition may not work instead of only whether a
 *     service is listed.
 *   - `languages` asks the recognizer the documented way and fails with
 *     `unavailable` instead of printing an empty list.
 *   - Recognizer errors are actionable ("Client error" → which service, why,
 *     what to try).
 *   - [T-431-fallback] The regular recognizer is the default; `--on-device`
 *     prefers the on-device one. A client / disconnect / language failure
 *     tries the other kind once (rule shared with the chat voice input), and
 *     the result names the recognizer that answered.
 *
 *   - [T-431-engine] `--engine provider|auto` transcribes with the Voice Input
 *     group's speech-to-text model (the chat voice input's provider path),
 *     from the mic or from an audio file. Default stays `system`, so audio
 *     does not silently start going to a third-party service; every provider
 *     result says where it went (rules in [SpeechEngines]).
 *
 * The system recognizer takes the microphone only: Android's [SpeechRecognizer]
 * accepts no audio files on the vendor implementations we ship to, so
 * `--source <path>` needs `--engine provider` (or `auto`).
 *
 * Permission flow: when a transcribe attempt needs RECORD_AUDIO and the user
 * has already permanently denied it, we route through the same 3-stage flow
 * the chat composer's mic button uses (T67).
 */
class SpeechOffloadHandler internal constructor(
    private val system: SpeechSystem,
) : NativeOffloadHandler {

    constructor(context: Context) : this(AndroidSpeechSystem(context))

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1), BOOLEAN_FLAGS)
        if (args.hasFlag("h", "help") || args.positional.isEmpty()) {
            return NativeOffloadResult(if (args.positional.isEmpty()) SpeechCli.EXIT_INVALID_ARGS else 0, HELP)
        }

        return try {
            when (val sub = args.positional[0]) {
                "status" -> cmdStatus(args)
                "languages" -> cmdLanguages(args)
                "transcribe", "listen" -> cmdTranscribe(args, request.pid)
                else -> emit(
                    args,
                    SpeechCli.EXIT_INVALID_ARGS,
                    SpeechCli.error(
                        sub,
                        "invalid_args",
                        "Unknown command '$sub'. Valid commands: transcribe, languages, status. Use --help for details.",
                    ),
                )
            }
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            emit(args, SpeechCli.EXIT_ERROR, SpeechCli.error("internal", "internal", e.message ?: "unknown"))
        }
    }

    // ── Subcommands ──────────────────────────────────────────────────────────

    private fun cmdStatus(args: OffloadArgs): NativeOffloadResult {
        val probe = if (args.hasFlag("probe")) system.probe(PROBE_TIMEOUT_MS) else null
        val env = system.environment()
        val data = SpeechCli.status(env, probe)
            .put("recognizer_order", JSONArray(plan(false, env).map { it.label }))
            .put("recognizer_order_on_device", JSONArray(plan(true, env).map { it.label }))
            .put("engine", SpeechEngines.status(system.voiceInputModels(), env.services.isNotEmpty(), SpeechCli.assess(env).first))
        return emit(args, SpeechCli.EXIT_OK, SpeechCli.ok("status", data))
    }

    private fun plan(preferOnDevice: Boolean, env: SpeechCli.Environment) =
        RecognizerFallback.plan(preferOnDevice, env.sdkInt, env.onDeviceAvailable)

    private fun cmdLanguages(args: OffloadArgs): NativeOffloadResult {
        val details = system.voiceDetails(LANGUAGES_TIMEOUT_MS)
        val support = system.recognitionSupport(LANGUAGES_TIMEOUT_MS)
        val result = SpeechCli.languages(details, support, RecognizerLocales.BUILTIN_TAGS, args.get("language"))
        return emit(args, result.exitCode, result.body)
    }

    private fun cmdTranscribe(args: OffloadArgs, callerPid: Int): NativeOffloadResult {
        // [T-431-cancel] Checked while recording and before any upload.
        val callerGone = { !system.callerAlive(callerPid) }
        val engine = SpeechEngines.parse(args.get("engine")) ?: return emit(
            args,
            SpeechCli.EXIT_INVALID_ARGS,
            SpeechCli.error("transcribe", "invalid_args", "--engine must be system, provider or auto."),
        )
        val env = system.environment()
        // iOS-style flag names: --language, --duration. Pre-T61 callers also
        // passed --max / --timeout; preserve those.
        val language = args.get("language")?.takeIf { it.isNotBlank() }?.let { RecognizerLocales.recognizerTag(it) }
        val maxResults = args.getInt("max") ?: 3
        val durationSec = (args.getInt("duration") ?: args.getInt("timeout") ?: DEFAULT_DURATION_SEC)
            .coerceIn(1, MAX_DURATION_SEC)
        val onDeviceRequested = args.hasFlag("on-device")
        val models = system.voiceInputModels()

        // --source: default mic, accept "mic" / "system-mic" / any /var/minis/... path.
        val source = args.get("source")
        if (source != null && !sourceIsMic(source)) return transcribeFile(args, engine, source, language, models, callerGone)

        if (engine == SpeechEngines.Engine.PROVIDER && models.isEmpty()) return noModel(args)
        val providerFirst = engine == SpeechEngines.Engine.PROVIDER ||
            (engine == SpeechEngines.Engine.AUTO &&
                SpeechEngines.autoStartsWithProvider(env.services.isNotEmpty(), SpeechCli.assess(env).first, models.isNotEmpty()))
        if (!providerFirst && env.services.isEmpty()) {
            return emit(
                args,
                SpeechCli.EXIT_NOT_AVAILABLE,
                SpeechCli.error(
                    "transcribe",
                    "recognizer_unavailable",
                    "No speech recognition service is registered on this device. This is common on " +
                        "devices without Google Mobile Services (Huawei HMS-only, some stripped China " +
                        "ROMs). Ask the user to install a speech recognition engine (e.g. Google, or " +
                        "an OEM-provided alternative). " + providerHint(models),
                ),
            )
        }

        permissionGate(args)?.let { return it }

        return withMic(args) {
            if (providerFirst) {
                val from = if (engine == SpeechEngines.Engine.AUTO) {
                    JSONObject().put("engine", "system").put("reason", SpeechCli.assess(env).second ?: "system recognizer not usable")
                } else {
                    null
                }
                return@withMic providerFromMic(args, engine, durationSec, language, from, callerGone)
            }
            val order = plan(onDeviceRequested, env)
            val failed = mutableListOf<RecognizeOutcome.Failure>()
            var outcome: RecognizeOutcome
            var i = 0
            while (true) {
                val kind = order[i]
                outcome = system.recognize(
                    RecognizeRequest(
                        language, maxResults, durationSec.toLong(),
                        kind == RecognizerFallback.Kind.ON_DEVICE,
                        isCancelled = callerGone,
                    ),
                )
                val next = (outcome as? RecognizeOutcome.Failure)?.let { RecognizerFallback.next(order, i, it.code) }
                if (next == null) break
                AppLogger.info(TAG, "transcribe: ${kind.label} failed (${RecognizerErrorCodes.name((outcome as RecognizeOutcome.Failure).code)}), trying ${next.label}")
                failed += outcome
                i++
            }
            val attempts = Attempts(order, failed, onDeviceRequested)
            if (engine == SpeechEngines.Engine.AUTO && models.isNotEmpty() && SpeechEngines.autoFallsBack(outcome)) {
                // [T-431-engine] The system recognizer could not be reached:
                // record it for `status`, then capture again for the provider.
                val from = JSONObject().put("engine", "system").put("failed_attempts", attempts.json())
                (outcome as? RecognizeOutcome.Failure)?.let {
                    system.recordLastError(it.code, it.recognizer)
                    from.put("error_code", it.code).put("error_name", RecognizerErrorCodes.name(it.code))
                        .put("recognizer", it.recognizer)
                }
                return@withMic providerFromMic(args, engine, durationSec, language, from, callerGone)
            }
            transcribeResult(args, outcome, language, durationSec, env, attempts, listOf(providerHint(models)))
        }
    }

    // ── [T-431-engine] provider engine ───────────────────────────────────────

    /** What the system engine's errors suggest about the provider engine. */
    private fun providerHint(models: List<SpeechEngines.VoiceModel>): String {
        val m = models.firstOrNull()
            ?: return "To transcribe with a model service instead, add a speech-to-text model to " +
                "Settings > Model Groups > Voice Input and use `--engine provider`."
        return "`--engine provider` (or `--engine auto`) transcribes with the Voice Input model instead; " +
            "the audio is then uploaded to ${SpeechEngines.destination(m)}."
    }

    /**
     * [T-431-cancel] The caller is gone, so nobody reads this; it is for the
     * log. The point is what did NOT happen: no further capture, no upload.
     */
    private fun cancelled(args: OffloadArgs, uploaded: Boolean): NativeOffloadResult {
        AppLogger.info(TAG, "transcribe: caller exited - stopped${if (uploaded) "" else ", nothing uploaded"}")
        return emit(
            args,
            SpeechCli.EXIT_CANCELLED,
            SpeechCli.error(
                "transcribe",
                "cancelled",
                "The caller exited; recording stopped" + if (uploaded) "." else " and no audio was uploaded.",
            ),
        )
    }

    private fun noModel(args: OffloadArgs) = emit(
        args,
        SpeechCli.EXIT_NOT_AVAILABLE,
        SpeechCli.error("transcribe", "no_voice_input_model", SpeechEngines.noModelMessage()),
    )

    private fun providerFromMic(
        args: OffloadArgs,
        engine: SpeechEngines.Engine,
        durationSec: Int,
        language: String?,
        fallbackFrom: JSONObject?,
        callerGone: () -> Boolean,
    ): NativeOffloadResult = when (val capture = system.recordMic(durationSec, callerGone)) {
        is CaptureOutcome.Failed -> emit(
            args,
            SpeechCli.EXIT_ERROR,
            SpeechCli.error("transcribe", capture.code, capture.message, JSONObject().put("engine_used", "provider")),
        )
        CaptureOutcome.Cancelled -> cancelled(args, uploaded = false)
        is CaptureOutcome.Ok -> if (callerGone()) cancelled(args, uploaded = false) else providerResult(
            args,
            system.providerTranscribe(capture.wav, language),
            engine,
            language,
            JSONObject().put("source", "mic").put("source_type", "mic").put("duration_seconds", durationSec),
            fallbackFrom,
        )
    }

    private fun transcribeFile(
        args: OffloadArgs,
        engine: SpeechEngines.Engine,
        source: String,
        language: String?,
        models: List<SpeechEngines.VoiceModel>,
        callerGone: () -> Boolean,
    ): NativeOffloadResult {
        val resolved = system.resolveSourcePath(source)
        val file = resolved?.takeIf { it.isFile }
        val exists = file != null
        AppLogger.info(TAG, "transcribe --source=$source engine=${engine.label} exists=$exists")
        val pathInfo = JSONObject()
            .put("requested_path", source)
            .put("resolved_host_path", resolved?.absolutePath ?: "")
            .put("file_exists", exists)
        if (engine == SpeechEngines.Engine.SYSTEM) {
            return emit(
                args,
                SpeechCli.EXIT_INVALID_ARGS,
                SpeechCli.error(
                    "transcribe",
                    "not_supported",
                    "The Android system recognizer only takes the microphone, not audio files. " +
                        "Use `--engine provider` to transcribe the file with the Voice Input model " +
                        "(the audio is uploaded to that service)." +
                        (models.firstOrNull()?.let { " Configured: ${SpeechEngines.destination(it)}." }
                            ?: " No Voice Input model is configured yet: add one in Settings > Model Groups > Voice Input."),
                    pathInfo,
                ),
            )
        }
        if (models.isEmpty()) return noModel(args)
        if (file == null) {
            return emit(
                args,
                SpeechCli.EXIT_INVALID_ARGS,
                SpeechCli.error("transcribe", "invalid_args", "Audio file not found: $source", pathInfo),
            )
        }
        // [T-speech-decode-streaming] The decode stops as soon as the caller is gone.
        val decoded = system.decodeAudioFile(file, callerGone)
        if (callerGone()) return cancelled(args, uploaded = false)
        return when (decoded) {
            is DecodeOutcome.Failed -> emit(
                args,
                if (decoded.code == "file_too_large" || decoded.code == "audio_too_long") SpeechCli.EXIT_INVALID_ARGS else SpeechCli.EXIT_ERROR,
                SpeechCli.error(
                    "transcribe",
                    decoded.code,
                    decoded.message + " Supported: WAV up to 25 MB as-is; MP3, M4A/AAC, FLAC, OGG/Opus, AMR, " +
                        "WebM and larger WAV are decoded on the device (max ${SpeechAudio.MAX_FILE_SECONDS / 60} min, " +
                        "${SpeechAudio.MAX_FILE_BYTES / (1024 * 1024)} MB).",
                    pathInfo,
                ),
            )
            is DecodeOutcome.Ok -> if (callerGone()) cancelled(args, uploaded = false) else providerResult(
                args,
                system.providerTranscribe(decoded.wav, language),
                engine,
                language,
                JSONObject()
                    .put("source", source)
                    .put("source_type", "audio_file")
                    .put("duration_seconds", decoded.seconds ?: JSONObject.NULL)
                    .put("file_format", decoded.format.label)
                    .put("decoded_on_device", decoded.decoded),
                null,
            )
        }
    }

    private fun providerResult(
        args: OffloadArgs,
        outcome: ProviderOutcome,
        engine: SpeechEngines.Engine,
        language: String?,
        sourceInfo: JSONObject,
        fallbackFrom: JSONObject?,
    ): NativeOffloadResult = when (outcome) {
        ProviderOutcome.NoModel -> noModel(args)
        is ProviderOutcome.Success -> {
            val data = JSONObject()
                .put("text", outcome.text)
                .put("engine_used", "provider")
                .put("engine_requested", engine.label)
                .put("provider_label", outcome.model.providerLabel)
                .put("model", outcome.model.modelId)
                .put("audio_sent_to", SpeechEngines.destination(outcome.model))
                .put("notice", SpeechEngines.sentNotice(outcome.model))
                .put("locale", language ?: JSONObject.NULL)
            for (key in sourceInfo.keys()) data.put(key, sourceInfo.get(key))
            if (outcome.text.isEmpty()) data.put("no_speech", true)
            if (fallbackFrom != null) data.put("fallback_from", fallbackFrom)
            emit(args, SpeechCli.EXIT_OK, SpeechCli.ok("transcribe", data))
        }
        is ProviderOutcome.Failed -> {
            val sentTo = outcome.errors.map { SpeechEngines.destination(it.first) }
            val extra = JSONObject()
                .put("engine_used", "provider")
                .put("engine_requested", engine.label)
                .put("providers", SpeechEngines.providerErrors(outcome.errors))
                .put("audio_sent_to", JSONArray(sentTo))
            if (fallbackFrom != null) extra.put("fallback_from", fallbackFrom)
            emit(
                args,
                SpeechCli.EXIT_ERROR,
                SpeechCli.error(
                    "transcribe",
                    "provider_error",
                    "Every Voice Input model failed. Audio was sent to: ${sentTo.joinToString("; ")}. " +
                        "Last error: ${outcome.errors.lastOrNull()?.second ?: "unknown"}",
                    extra,
                ),
            )
        }
    }

    /** What the recognizer fallback did, for the result. */
    private data class Attempts(
        val order: List<RecognizerFallback.Kind>,
        val failed: List<RecognizeOutcome.Failure>,
        val onDeviceRequested: Boolean,
    ) {
        fun json(): JSONArray = JSONArray().apply {
            failed.forEach {
                put(
                    JSONObject().put("recognizer", it.recognizer)
                        .put("error_code", it.code)
                        .put("error_name", RecognizerErrorCodes.name(it.code)),
                )
            }
        }

        fun annotate(data: JSONObject, env: SpeechCli.Environment): JSONObject {
            data.put("fallback_used", failed.isNotEmpty())
            data.put("on_device_requested", onDeviceRequested)
            if (failed.isNotEmpty()) data.put("failed_attempts", json())
            if (onDeviceRequested && order.first() != RecognizerFallback.Kind.ON_DEVICE) {
                data.put(
                    "note",
                    if (env.sdkInt < 31) {
                        "On-device recognition needs Android 12; used the regular recognizer."
                    } else {
                        "No on-device recognizer is available on this device; used the regular recognizer."
                    },
                )
            }
            return data
        }

        /** What else the caller can try, after everything was tried. */
        fun hints(env: SpeechCli.Environment): List<String> {
            val tried = order.take(failed.size + 1)
            return when {
                tried.size > 1 -> listOf("Both the regular and the on-device recognizer failed.")
                env.onDeviceAvailable != true -> listOf("This device has no on-device recognizer to fall back to.")
                else -> emptyList()
            }
        }
    }

    /**
     * One microphone capture at a time, process-wide: two recognizer sessions
     * fight over the mic and the second one fails or records nothing. The chat
     * voice input owns the mic while it records, as it does for its own taps.
     */
    private fun withMic(args: OffloadArgs, body: () -> NativeOffloadResult): NativeOffloadResult {
        system.micBusyReason()?.let { return busy(args, it) }
        if (!micInUse.compareAndSet(false, true)) {
            return busy(args, "Another android-speech transcribe is already using the microphone.")
        }
        try {
            return body()
        } finally {
            micInUse.set(false)
        }
    }

    private fun busy(args: OffloadArgs, why: String) = emit(
        args,
        SpeechCli.EXIT_ERROR,
        SpeechCli.error("transcribe", "busy", "$why Try again when it has finished."),
    )

    private fun transcribeResult(
        args: OffloadArgs,
        outcome: RecognizeOutcome,
        language: String?,
        durationSec: Int,
        env: SpeechCli.Environment,
        attempts: Attempts,
        extraHints: List<String> = emptyList(),
    ): NativeOffloadResult {
        val locale = language ?: env.locale
        return when (outcome) {
            is RecognizeOutcome.Success -> {
                system.recordLastError(null, null)
                val data = attempts.annotate(successData(outcome, locale, durationSec), env)
                emit(args, SpeechCli.EXIT_OK, SpeechCli.ok("transcribe", data))
            }
            is RecognizeOutcome.Failure -> {
                if (outcome.code == SpeechRecognizer.ERROR_NO_MATCH || outcome.code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    // The recognizer worked and heard nothing: an empty
                    // result, not a failure (iOS returns its empty text too).
                    system.recordLastError(null, null)
                    val data = attempts.annotate(baseData(locale, durationSec, outcome.recognizer), env)
                        .put("text", "")
                        .put("no_speech", true)
                    return emit(args, SpeechCli.EXIT_OK, SpeechCli.ok("transcribe", data))
                }
                system.recordLastError(outcome.code, outcome.recognizer)
                emit(
                    args,
                    SpeechCli.EXIT_ERROR,
                    SpeechCli.error(
                        "transcribe",
                        SpeechCli.errorCode(outcome.code),
                        SpeechCli.recognizerErrorMessage(outcome.code, language, env.defaultService, attempts.hints(env) + extraHints),
                        JSONObject()
                            .put("android_error", outcome.code)
                            .put("android_error_name", RecognizerErrorCodes.name(outcome.code))
                            .put("recognizer", outcome.recognizer)
                            .put("default_service", env.defaultService ?: JSONObject.NULL)
                            .put("failed_attempts", attempts.json()),
                    ),
                )
            }
            is RecognizeOutcome.Timeout -> emit(
                args,
                SpeechCli.EXIT_ERROR,
                SpeechCli.error(
                    "transcribe",
                    "timeout",
                    "Speech recognition timed out after ${outcome.seconds}s without a result.",
                    JSONObject().put("recognizer", outcome.recognizer).put("failed_attempts", attempts.json()),
                ),
            )
            is RecognizeOutcome.Cancelled -> cancelled(args, uploaded = false)
            is RecognizeOutcome.CreationFailed -> emit(
                args,
                SpeechCli.EXIT_ERROR,
                SpeechCli.error(
                    "transcribe",
                    "recognizer_creation_failed",
                    outcome.message,
                    JSONObject().put("recognizer", outcome.recognizer),
                ),
            )
        }
    }

    private fun baseData(locale: String, durationSec: Int, recognizer: String): JSONObject = JSONObject()
        .put("locale", locale)
        .put("source", "mic")
        .put("source_type", "mic")
        .put("duration_seconds", durationSec)
        .put("engine_used", "system")
        .put("recognizer", recognizer)
        .put("on_device", recognizer == Recognizers.ON_DEVICE)

    private fun successData(s: RecognizeOutcome.Success, locale: String, durationSec: Int): JSONObject {
        val data = baseData(locale, durationSec, s.recognizer).put("text", s.texts.firstOrNull() ?: "")
        if (s.texts.size > 1) {
            val alts = JSONArray()
            s.texts.forEachIndexed { i, t ->
                val a = JSONObject().put("text", t)
                s.confidences?.getOrNull(i)?.let { a.put("confidence", String.format(java.util.Locale.US, "%.2f", it)) }
                alts.put(a)
            }
            data.put("alternatives", alts)
        }
        return data
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun permissionGate(args: OffloadArgs): NativeOffloadResult? {
        if (system.hasMicPermission()) return null
        AppLogger.warning(TAG, "RECORD_AUDIO not granted — routing through permission flow")
        return when (system.requestMicPermission()) {
            OffloadPermissionManager.AndroidPermissionResult.GRANTED -> null
            OffloadPermissionManager.AndroidPermissionResult.DENIED -> emit(
                args,
                SpeechCli.EXIT_PERMISSION,
                SpeechCli.error("transcribe", "permission_denied", "The user declined the microphone permission."),
            )
            OffloadPermissionManager.AndroidPermissionResult.TIMEOUT -> emit(
                args,
                SpeechCli.EXIT_PERMISSION,
                SpeechCli.error(
                    "transcribe",
                    "timeout",
                    "Timed out waiting for the user to grant the microphone permission.",
                ),
            )
        }
    }

    private fun emit(args: OffloadArgs, exit: Int, body: JSONObject): NativeOffloadResult =
        NativeOffloadResult(exit, OffloadOutput.formatBody(body.toString(2), args) + "\n")

    private fun sourceIsMic(source: String): Boolean {
        val s = source.trim().lowercase()
        return s.isEmpty() || s == "mic" || s == "system-mic" || s == "system_mic" || s == "microphone"
    }

    companion object {
        private const val TAG = "SpeechOffload"
        private const val DEFAULT_DURATION_SEC = 30
        private const val MAX_DURATION_SEC = 60
        private const val PROBE_TIMEOUT_MS = 3_000L
        private const val LANGUAGES_TIMEOUT_MS = 3_000L
        private val BOOLEAN_FLAGS = setOf("h", "help", "probe", "on-device")

        /** Process-wide: one android-speech microphone capture at a time. */
        private val micInUse = AtomicBoolean(false)

        private const val HELP = """android-speech — speech recognition (audio → text)

Usage:
  android-speech <command> [options]

COMMANDS:
  transcribe   Transcribe speech from the microphone or an audio file
  listen       Alias for transcribe (legacy)
  languages    List the recognition locales the system reports
  status       Check speech recognition availability (and why it may fail)

OPTIONS:
  --help, -h           Show this help message
  --compact            Minimize JSON output
  -q, --quiet          Output only the data (or error) field
  --engine <name>      system (default): Android's speech recognizer; audio
                       stays with the system recognition service.
                       provider: the speech-to-text model in Settings > Model
                       Groups > Voice Input. Audio is UPLOADED to that service,
                       which may charge per minute.
                       auto: system, but the provider when the system
                       recognizer cannot be reached; the result then says
                       "Audio was sent to provider ..." (audio_sent_to).
  --source <mic|path>  Source: system mic (default) or an audio file path
                       (needs --engine provider or auto). WAV up to 25 MB is
                       sent as-is; MP3, M4A/AAC, FLAC, OGG/Opus, AMR, WebM and
                       larger WAV are decoded to 16 kHz WAV on the device
                       first. Limits: 100 MB file, 10 min audio.
  --duration <sec>     Recording duration for mic source (default: 30, max: 60)
  --language <locale>  Recognition locale (e.g. en-US, zh-CN)
  --max <N>            Maximum number of result candidates (default: 3)
  --on-device          Prefer the on-device recognizer (Android 12+). Default
                       is the regular system recognizer. Either one falls back
                       to the other once on a connection or language error;
                       the result says which one answered (recognizer,
                       fallback_used, failed_attempts).
  --probe              (status) Open and cancel one recognizer session to test
                       the connection. Uses the microphone briefly.

OUTPUT:
  JSON envelope {ok, tool, action, data|error, timestamp}. Success data fields
  are also repeated at the top level. Errors have error.code / error.message.
  Exit codes: 0 ok, 1 error, 2 invalid arguments, 4 not available,
  77 permission denied.

EXAMPLES:
  android-speech transcribe --duration 5
  android-speech transcribe --source mic --language zh-CN --duration 15
  android-speech transcribe --on-device --language en-US
  android-speech transcribe --engine provider --duration 10
  android-speech transcribe --engine provider --source /var/minis/attachments/meeting.m4a
  android-speech transcribe --engine auto --language zh-CN
  android-speech languages
  android-speech languages --language en
  android-speech status
  android-speech status --probe

Notes:
  - Requires RECORD_AUDIO permission for mic transcription.
  - `status` shows the Voice Input model (name and host, never the key) and
    what `--engine auto` would use.
  - `languages` fails with error.code "unavailable" when the system does not
    report its languages; error.fallback then lists Minis' built-in locales,
    which are not a report from this device.
"""
    }
}
