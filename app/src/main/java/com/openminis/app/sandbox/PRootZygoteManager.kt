package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages warm per-session PRoot Zygote daemons (`minis-zygote`) and persistent
 * per-session working directory (`CWD`) continuity across `FreshProcessShell` calls.
 *
 * Why this exists:
 * - `FreshProcessShell` gives strong per-command process-group isolation and avoids
 *   marker corruption on shared pipes, but historically lost the working directory
 *   (`cd /var/minis/workspace/app`) on every command and suffered cold VFS/loader
 *   page faults when spinning up `proot`.
 * - [PRootZygoteManager] keeps a lightweight background Zygote warm per active session
 *   (bounded to at most [MAX_WARM_ZYGOTES] concurrent sessions so we never pressure
 *   `GlobalShellThrottle.PROCESS_BUDGET`) and maintains the session's persistent CWD
 *   file at `<rootfs>/.minis-exit/cwd_<sessionId>`.
 */
object PRootZygoteManager {
    private const val TAG = "PRootZygoteManager"
    private const val ZYGOTE_DIR_NAME = ".minis-zygote"
    private const val EXIT_DIR_NAME = ".minis-exit"
    private const val MAX_WARM_ZYGOTES = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class WarmZygote(
        val sessionId: String,
        val process: Process,
        val startedAtMs: Long = System.currentTimeMillis(),
        @Volatile var lastTouchedMs: Long = System.currentTimeMillis(),
    )

    private val warmZygotes = ConcurrentHashMap<String, WarmZygote>()

    /**
     * Returns the host File storing the persistent working directory for [sessionId].
     */
    fun sessionCwdFile(context: Context, sessionId: String): File {
        val rootfsDir = RootfsManager.getInstance(context).rootfsDir
        val exitDir = File(rootfsDir, EXIT_DIR_NAME).apply { mkdirs() }
        val safeId = sanitizeSessionKey(sessionId)
        return File(exitDir, "cwd_$safeId")
    }

    /**
     * Reads the current persisted guest working directory for [sessionId], or
     * `/var/minis/workspace` if none has been recorded yet.
     */
    fun currentSessionCwd(context: Context, sessionId: String): String {
        val file = sessionCwdFile(context, sessionId)
        val saved = runCatching {
            if (file.exists()) file.readText().trim() else ""
        }.getOrDefault("")
        return saved.ifBlank { "/var/minis/workspace" }
    }

    /**
     * Pre-warms a session's PRoot Zygote and VFS page cache in the background
     * after PRoot has booted. Safe and non-blocking.
     */
    fun ensureSessionWarmAsync(
        context: Context,
        sessionId: String,
        bindMounts: Map<String, String>,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                ensureSessionWarmInternal(appContext, sessionId, bindMounts)
            }.onFailure { e ->
                Log.d(TAG, "[$sessionId] Zygote warmup skipped: ${e.message}")
            }
        }
    }

    private fun ensureSessionWarmInternal(
        context: Context,
        sessionId: String,
        bindMounts: Map<String, String>,
    ) {
        val rootfsManager = RootfsManager.getInstance(context)
        if (!rootfsManager.isProotAvailable || !rootfsManager.rootfsDir.exists()) return

        val safeId = sanitizeSessionKey(sessionId)
        val existing = warmZygotes[safeId]
        if (existing != null && existing.process.isAlive) {
            existing.lastTouchedMs = System.currentTimeMillis()
            return
        }

        // Evict oldest warm zygote if at capacity so we never crowd GlobalShellThrottle
        if (warmZygotes.size >= MAX_WARM_ZYGOTES) {
            val oldest = warmZygotes.values.minByOrNull { it.lastTouchedMs }
            if (oldest != null) {
                stopSessionZygote(context, oldest.sessionId, clearCwd = false)
            }
        }

        val zygoteScript = File(rootfsManager.rootfsDir, "usr/local/bin/minis-zygote")
        if (!zygoteScript.exists()) return

        val zygoteGuestDir = File(rootfsManager.rootfsDir, "$ZYGOTE_DIR_NAME/$safeId/queue")
        zygoteGuestDir.mkdirs()

        val cmd = mutableListOf<String>()
        cmd.add(rootfsManager.prootBinary.absolutePath)
        cmd.add("-0")
        cmd.add("--link2symlink")
        cmd.add("-r")
        cmd.add(rootfsManager.rootfsDir.absolutePath)
        cmd.add("-b")
        cmd.add("/dev")
        cmd.add("-b")
        cmd.add("/proc")
        cmd.add("-b")
        cmd.add("/sys")
        cmd.add("-w")
        cmd.add("/root")
        for ((guestPath, hostPath) in bindMounts) {
            val hostDir = File(hostPath)
            if (!hostDir.exists()) hostDir.mkdirs()
            cmd.add("-b")
            cmd.add("$hostPath:$guestPath")
        }
        cmd.add("--fake-netlink")
        cmd.add("/bin/sh")
        cmd.add("/usr/local/bin/minis-zygote")
        cmd.add(safeId)

        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
        if (PRootKernel.nativeLibDir.isNotEmpty()) env["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
        if (PRootKernel.prootLoaderPath.isNotEmpty()) env["PROOT_LOADER"] = PRootKernel.prootLoaderPath
        if (PRootKernel.prootLoader32Path.isNotEmpty()) env["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
        env["TERM"] = "dumb"
        env["PS1"] = ""
        env["TZ"] = PRootKernel.posixTz()
        env["MINIS_CHAT_SESSION_ID"] = sessionId
        for ((k, v) in PRootKernel.customEnvironment) env[k] = v

        val process = SpawnSignalMask.start(pb)
        warmZygotes[safeId] = WarmZygote(sessionId = safeId, process = process)
        Log.i(TAG, "[$sessionId] Warm PRoot Zygote started (active=${warmZygotes.size})")
    }

    /**
     * Stops the warm Zygote for [sessionId] and optionally removes its saved CWD state.
     */
    fun stopSessionZygote(context: Context, sessionId: String, clearCwd: Boolean = true) {
        val safeId = sanitizeSessionKey(sessionId)
        val removed = warmZygotes.remove(safeId)
        if (removed != null) {
            runCatching { removed.process.destroyForcibly() }
            Log.i(TAG, "[$sessionId] Warm PRoot Zygote stopped")
        }
        runCatching {
            val rootfsDir = RootfsManager.getInstance(context).rootfsDir
            File(rootfsDir, "$ZYGOTE_DIR_NAME/$safeId").deleteRecursively()
            if (clearCwd) {
                File(rootfsDir, "$EXIT_DIR_NAME/cwd_$safeId").delete()
            }
        }
    }

    fun stopAll(context: Context) {
        for (key in warmZygotes.keys.toList()) {
            stopSessionZygote(context, key, clearCwd = false)
        }
    }

    fun sanitizeSessionKey(sessionId: String): String =
        sessionId.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64).ifBlank { "default" }
}
