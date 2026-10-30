package com.openminis.app.sandbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * [T-android-global-shell-throttle] Admits agent shell commands against a
 * budget of Minis child processes, across ALL sessions. A command that does
 * not fit waits in a FIFO queue; it is never refused.
 *
 * Why: every running command is a PRoot process tree (libproot + /bin/sh +
 * the command's children), and Android 12+ kills phantom processes beyond
 * `max_phantom_processes` (32 by default). Main chat, helper agents,
 * sub-agents and scheduled tasks can each fan out several shell calls at
 * once; nothing bounded the total, so a burst could cross that budget and
 * have processes SIGKILLed mid-command.
 *
 * [T-android-shell-process-budget] Why a process budget and not a command
 * count: a fixed "3 commands" was never measured and was both too strict (a
 * `sleep` is 4 processes, so three of them use 12 of 32) and blind to what
 * actually costs - a 4-stage pipeline is 7 processes, a leftover daemon
 * holds processes after its command returned. The app cannot read the
 * system limit or other apps' processes (no DUMP permission, /proc of other
 * uids is denied), but it can count its own descendants in /proc, so the
 * gate admits a command while that live count stays within [PROCESS_BUDGET],
 * half the default 32, leaving the rest for other apps (IMEs and chat apps
 * keep a few children of their own).
 *
 * Scope and limits, deliberately:
 *  - Only command execution is gated. The interactive terminal (a
 *    user-owned PTY) is not; queueing a person's keystrokes behind agent
 *    work would be the wrong trade. Its processes still count toward the
 *    live total, so agent commands give way to it.
 *  - A command is always admitted when no other gated command is running,
 *    so processes nobody is waiting on (a leaked daemon) can slow commands
 *    to one at a time but never stall them.
 *  - Queue time does not come out of the command's own timeout: a queued
 *    command runs with its full budget. The queue has its own safety cap,
 *    [MAX_QUEUE_WAIT_MS], so an unforeseen wait-cycle cannot hang forever.
 *  - A command whose process could not even be started (the caller's
 *    [run] `isStartFailure`) ran nothing, so it goes back into the queue and
 *    is retried after a backoff. A command that started and then failed or
 *    was killed is never retried: it may have had side effects.
 *  - Waiting is cancellable (Stop, turn cancel) and holds no reservation.
 */
object GlobalShellThrottle {
    private const val TAG = "GlobalShellThrottle"

    /** Live Minis child processes the gate admits up to. */
    const val PROCESS_BUDGET = 16

    /**
     * [T-loadtest-limits] The budget admission uses: [PROCESS_BUDGET] unless a
     * DEV_TOOLS build overrides it for a load test. In memory only.
     */
    @Volatile
    var processBudget: Int = PROCESS_BUDGET
        private set

    /** [T-loadtest-limits] DEV_TOOLS only: set the budget (null = default), 4..64. */
    fun debugSetProcessBudget(n: Int?): Int {
        if (!com.openminis.app.BuildConfig.DEV_TOOLS) return processBudget
        processBudget = (n ?: PROCESS_BUDGET).coerceIn(PROCESSES_PER_COMMAND, 64)
        Log.i(TAG, "[T-loadtest-limits] processBudget=$processBudget")
        return processBudget
    }

    /**
     * What a command is assumed to cost before its processes show up in
     * /proc: a fresh-process `sleep` is 4 (libproot, its tracee loader, sh,
     * the command), measured on a Pixel 4a. Also the headroom a new command
     * needs, so 4 simple commands fill the budget.
     */
    const val PROCESSES_PER_COMMAND = 4

    /** Safety cap on time spent queued (not on the command's run time). */
    const val MAX_QUEUE_WAIT_MS = 10 * 60_000L

    /**
     * How long an admitted command is charged [PROCESSES_PER_COMMAND] on top
     * of the live count. Spawning takes well under this; afterwards the
     * command's real processes are in the live count. Growth in the live count
     * since the reservation was made is netted off, so a command is not
     * charged twice once it shows up; any error is toward waiting.
     */
    private const val RESERVATION_MS = 2_000L

    private const val POLL_MS = 250L
    private const val MAX_RETRY_BACKOFF_MS = 5_000L

    /** Counts this app's live child processes. Swapped out in tests. */
    @Volatile
    internal var processCounter: () -> Int = { countDescendants(File("/proc"), android.os.Process.myPid()) }

    /** Clock in ms. Swapped out in tests (virtual time). */
    @Volatile
    internal var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    /** FIFO: kotlinx [Mutex] hands the lock to waiters in arrival order. */
    private val admission = Mutex()
    private val running = AtomicInteger(0)
    private val waiting = AtomicInteger(0)
    private class Reservation(val atMs: Long, val liveAtAdmission: Int)
    private val reservations = java.util.concurrent.ConcurrentHashMap<Any, Reservation>()

    /** Commands currently admitted and running (diagnostics / tests). */
    val runningCount: Int get() = running.get()

    /** Commands currently queued (diagnostics / tests). */
    val waitingCount: Int get() = waiting.get()

    sealed class Outcome<out T> {
        /**
         * [block] ran to completion. [queuedMs] is the total time spent
         * waiting (including start-failure backoffs); [queued] is whether it
         * waited at all; [startRetries] how many starts failed before it ran.
         */
        data class Ran<T>(
            val value: T,
            val queuedMs: Long,
            val queued: Boolean,
            val startRetries: Int,
        ) : Outcome<T>()

        data class QueueTimedOut(val waitedMs: Long, val lastLiveCount: Int) : Outcome<Nothing>()
    }

    /**
     * Run [block] once the process budget has room. [isStartFailure] tells
     * whether a result means nothing was started; such a run is retried
     * after a backoff, within [MAX_QUEUE_WAIT_MS].
     */
    suspend fun <T> run(
        tag: String,
        maxQueueWaitMs: Long = MAX_QUEUE_WAIT_MS,
        isStartFailure: (T) -> Boolean = { false },
        block: suspend () -> T,
    ): Outcome<T> {
        var queued = false
        var retries = 0
        var lastLive = 0
        // Time spent waiting only: the queue and start-failure backoffs, not
        // the (failed) start attempts themselves.
        var queuedMs = 0L
        while (true) {
            val remaining = maxQueueWaitMs - queuedMs
            val admitStart = clock()
            val liveAtAdmission = if (remaining <= 0) null else withTimeoutOrNull(remaining) {
                admit(tag) { live -> queued = true; lastLive = live }
            }
            queuedMs += clock() - admitStart
            if (liveAtAdmission == null) {
                Log.w(TAG, "[$tag] gave up after ${queuedMs}ms in the queue (live=$lastLive budget=$processBudget)")
                return Outcome.QueueTimedOut(queuedMs, lastLive)
            }
            val token = Any()
            reservations[token] = Reservation(clock(), liveAtAdmission)
            running.incrementAndGet()
            val value = try {
                block()
            } finally {
                reservations.remove(token)
                running.decrementAndGet()
            }
            if (!isStartFailure(value)) {
                if (queued) Log.i(TAG, "[$tag] finished after queueing ${queuedMs}ms (start retries=$retries)")
                return Outcome.Ran(value, if (queued) queuedMs else 0L, queued, retries)
            }
            // Nothing started, so nothing ran: queue again. Back off first,
            // or a start that fails at once would spin through the gate.
            retries++
            queued = true
            val backoff = (1_000L shl (retries - 1).coerceAtMost(3)).coerceAtMost(MAX_RETRY_BACKOFF_MS)
            if (maxQueueWaitMs - queuedMs <= backoff) {
                Log.w(TAG, "[$tag] gave up after ${queuedMs}ms: the command could not be started ($retries attempts)")
                return Outcome.QueueTimedOut(queuedMs, safeCount())
            }
            Log.w(TAG, "[$tag] start failed (attempt $retries), re-queueing in ${backoff}ms (live=${safeCount()})")
            val backoffStart = clock()
            delay(backoff)
            queuedMs += clock() - backoffStart
        }
    }

    /**
     * Wait at the head of the FIFO until the budget has room; returns the
     * live count at admission. [onQueued] fires once, with the live count,
     * if the command has to wait.
     */
    private suspend fun admit(tag: String, onQueued: (live: Int) -> Unit): Int {
        var announced = false
        fun announce(live: Int, why: String) {
            if (announced) return
            announced = true
            onQueued(live)
            Log.i(TAG, "[$tag] queued: $why (live=$live running=${running.get()} waiting=${waiting.get()} budget=$processBudget)")
        }
        waiting.incrementAndGet()
        try {
            val lockStart = clock()
            return admission.withLock {
                // Waiting behind commands queued earlier is queueing too. A
                // lock handed over within a poll is a burst arriving at
                // once, not a wait, and stays unannounced.
                if (clock() - lockStart >= POLL_MS) announce(safeCount(), "behind earlier queued commands")
                val live = awaitRoom(::announce)
                if (announced) Log.i(TAG, "[$tag] dequeued after ${clock() - lockStart}ms (live=$live)")
                live
            }
        } finally {
            waiting.decrementAndGet()
        }
    }

    /** Poll until the budget has room; returns the live count then. */
    private suspend fun awaitRoom(announce: (live: Int, why: String) -> Unit): Int {
        while (true) {
            val live = safeCount()
            val reserved = reservedProcesses(live)
            // An idle gate always admits: see the class comment.
            if (running.get() == 0 || live + reserved + PROCESSES_PER_COMMAND <= processBudget) return live
            announce(live, "process budget full")
            delay(POLL_MS)
        }
    }

    /** Processes charged for recently admitted commands not yet seen in [live]. */
    private fun reservedProcesses(live: Int): Int {
        val now = clock()
        val young = reservations.values.filter { now - it.atMs < RESERVATION_MS }
        if (young.isEmpty()) return 0
        val grown = (live - young.minOf { it.liveAtAdmission }).coerceAtLeast(0)
        return (young.size * PROCESSES_PER_COMMAND - grown).coerceAtLeast(0)
    }

    /** An unreadable /proc must not block commands; count 0 and let them run. */
    private fun safeCount(): Int = runCatching { processCounter() }.getOrElse {
        Log.w(TAG, "process count failed: ${it.message}")
        0
    }

    /**
     * Number of live descendants of [rootPid] under [procRoot], excluding
     * [rootPid] itself. Entries this app cannot read (other uids) are skipped;
     * a process that exits mid-walk is simply missed.
     */
    internal fun countDescendants(procRoot: File, rootPid: Int): Int {
        val parentOf = HashMap<Int, Int>()
        procRoot.listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return@forEach
            parseParentPid(stat)?.let { parentOf[pid] = it }
        }
        val children = HashMap<Int, MutableList<Int>>()
        for ((pid, ppid) in parentOf) children.getOrPut(ppid) { ArrayList() }.add(pid)
        var count = 0
        val stack = ArrayDeque(children[rootPid].orEmpty())
        val seen = HashSet<Int>()
        while (stack.isNotEmpty()) {
            val pid = stack.removeLast()
            if (!seen.add(pid)) continue
            count++
            children[pid]?.let { stack.addAll(it) }
        }
        return count
    }

    /**
     * `/proc/<pid>/stat` is "pid (comm) state ppid ...". comm may contain
     * spaces and parentheses, so fields are read after the LAST ')'.
     */
    internal fun parseParentPid(stat: String): Int? {
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        val fields = stat.substring(close + 1).trim().split(' ')
        return fields.getOrNull(1)?.toIntOrNull()
    }
}

// ─── Merged from RootfsManager.kt ──────────────────────────────────────────────────

/**
 * Observable state for rootfs installation.
 */
sealed class RootfsInstallState {
    object Idle : RootfsInstallState()
    object Preparing : RootfsInstallState()
    /** progress in 0f..1f during network download. */
    data class Downloading(val progress: Float) : RootfsInstallState()
    /** progress in 0f..1f, based on compressed asset bytes consumed. */
    data class Extracting(val progress: Float) : RootfsInstallState()
    object Finalizing : RootfsInstallState()
    object Installed : RootfsInstallState()
    data class Failed(val error: String) : RootfsInstallState()
}

/**
 * Manages Alpine Linux rootfs installation and PRoot binary extraction.
 * Corresponds to iOS RootfsManager.swift.
 */
class RootfsManager private constructor(private val context: Context) {

    val rootfsDir: File = File(context.filesDir, "alpine-rootfs")
    val prootBinary: File = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
    val isProotAvailable: Boolean
        get() = prootBinary.exists() && prootBinary.canExecute()

    private val archFile: File get() = File(rootfsDir, ".arch")

    val isInstalled: Boolean
        get() = rootfsDir.exists() && archFile.exists() &&
                archFile.readText().trim().isNotEmpty()

    /**
     * Observable install progress. UI layers (OnboardingScreen,
     * RootfsManagementScreen) bind this and render a progress bar during
     * `installIfNeeded()` / `reset()`. Emits `Installed` on success and
     * `Failed` on error so callers can surface retry affordances.
     */
    private val _installState = MutableStateFlow<RootfsInstallState>(
        if (isInstalled) RootfsInstallState.Installed else RootfsInstallState.Idle
    )
    val installState: StateFlow<RootfsInstallState> = _installState.asStateFlow()

    /**
     * Install Alpine rootfs from assets if not already present.
     * Extracts alpine-minirootfs.tar.gz using manual POSIX tar parsing.
     * Progress is published to [installState] (Preparing → Extracting(f) →
     * Finalizing → Installed / Failed).
     */
    suspend fun installIfNeeded() = withContext(Dispatchers.IO) {
        if (isInstalled) {
            Log.d(TAG, "Rootfs already installed at $rootfsDir")
            _installState.value = RootfsInstallState.Installed
            return@withContext
        }

        try {
            _installState.value = RootfsInstallState.Preparing
            Log.i(TAG, "Installing Alpine rootfs...")

            // Clean up any partial install
            if (rootfsDir.exists()) {
                rootfsDir.deleteRecursively()
            }
            rootfsDir.mkdirs()

            val currentArch = detectDeviceArch()
            Log.i(TAG, "Device architecture detected as: $currentArch")

            // 1. Check for bundled assets matching current architecture or generic names
            val assetCandidates = listOf(
                "alpine-minirootfs-$currentArch.tar.gz",
                ROOTFS_ASSET,
                "alpine-minirootfs-$currentArch.tar",
                ROOTFS_ASSET_TAR,
                "alpine-minirootfs-aarch64.tar.gz",
                "alpine-minirootfs-x86_64.tar.gz"
            )

            var foundAsset: String? = null
            for (cand in assetCandidates) {
                try {
                    context.assets.open(cand).close()
                    foundAsset = cand
                    break
                } catch (_: Exception) { }
            }

            val cachedFile = File(context.cacheDir, "alpine-minirootfs-$currentArch.tar.gz")

            if (foundAsset != null) {
                Log.i(TAG, "Extracting rootfs from bundled asset: $foundAsset")
                val assetTotal: Long = try {
                    context.assets.openFd(foundAsset).use { it.length }
                } catch (_: Exception) { 0L }

                _installState.value = RootfsInstallState.Extracting(0f)
                context.assets.open(foundAsset).use { rawAsset ->
                    val progressStream = ProgressInputStream(rawAsset, assetTotal) { fraction ->
                        _installState.value = RootfsInstallState.Extracting(fraction)
                    }
                    if (foundAsset.endsWith(".gz")) {
                        GZIPInputStream(progressStream).use { gzipStream ->
                            extractTar(gzipStream, rootfsDir)
                        }
                    } else {
                        extractTar(progressStream, rootfsDir)
                    }
                }
            } else {
                // If not bundled in assets, use cached file or download from mirrors
                if (!cachedFile.exists() || cachedFile.length() < 500_000) {
                    Log.i(TAG, "No bundled asset found; downloading rootfs for $currentArch from mirror...")
                    _installState.value = RootfsInstallState.Downloading(0f)
                    downloadRootfsFromMirrors(currentArch, cachedFile)
                }

                Log.i(TAG, "Extracting rootfs from file: ${cachedFile.absolutePath}")
                _installState.value = RootfsInstallState.Extracting(0f)
                val fileTotal = cachedFile.length()
                FileInputStream(cachedFile).use { rawStream ->
                    val progressStream = ProgressInputStream(rawStream, fileTotal) { fraction ->
                        _installState.value = RootfsInstallState.Extracting(fraction)
                    }
                    GZIPInputStream(progressStream).use { gzipStream ->
                        extractTar(gzipStream, rootfsDir)
                    }
                }
            }

            _installState.value = RootfsInstallState.Finalizing

            // Write arch marker
            archFile.writeText(currentArch)

            // Pre-create /var/minis directories
            val minisSubdirs = listOf("attachments", "offloads", "workspace", "skills", "memory", "shared", "mounts")
            for (subdir in minisSubdirs) {
                File(rootfsDir, "var/minis/$subdir").mkdirs()
            }

            // Pre-create /opt/bin
            File(rootfsDir, "opt/bin").mkdirs()

            // Write resolv.conf from system DNS (fallback to 8.8.8.8)
            refreshDns()

            // Configure /etc/apk/repositories with the active Alpine mirror
            try {
                val mirrorUrl = getActiveAlpineMirror()
                val apkDir = File(rootfsDir, "etc/apk")
                apkDir.mkdirs()
                val reposFile = File(apkDir, "repositories")
                reposFile.writeText("${mirrorUrl}v3.21/main\n${mirrorUrl}v3.21/community\n")
                Log.i(TAG, "Configured apk repositories using mirror: $mirrorUrl")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write initial apk repositories: ${e.message}")
            }

            // Flag a fresh install so MirrorSpeedTestViewModel picks fastest mirror
            context.getSharedPreferences("mirror_settings", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("rootfs.freshInstall", true)
                .apply()

            Log.i(TAG, "Rootfs installation complete")
            _installState.value = RootfsInstallState.Installed
        } catch (t: Throwable) {
            Log.e(TAG, "Rootfs installation failed", t)
            _installState.value = RootfsInstallState.Failed(t.message ?: t.javaClass.simpleName)
            throw t
        }
    }

    fun detectDeviceArch(): String {
        val abis = Build.SUPPORTED_ABIS ?: emptyArray()
        return when {
            abis.any { it.startsWith("arm64") } -> "aarch64"
            abis.any { it.contains("x86_64") } -> "x86_64"
            abis.any { it.startsWith("armeabi") } -> "armv7"
            else -> "aarch64"
        }
    }

    fun getActiveAlpineMirror(): String {
        val prefs = context.getSharedPreferences("mirror_settings", Context.MODE_PRIVATE)
        val customMirror = prefs.getString("custom_mirror_alpine", null)?.trim()
        val useCustom = prefs.getBoolean("use_custom_alpine", false)
        if (useCustom && !customMirror.isNullOrEmpty()) {
            return if (customMirror.endsWith("/")) customMirror else "$customMirror/"
        }
        val selectedId = prefs.getString("selected_mirror_alpine", null)
        if (!selectedId.isNullOrEmpty()) {
            val entry = com.openminis.app.ui.sandbox.MirrorCatalog.alpineMirrors.firstOrNull { it.id == selectedId }
            if (entry != null) return entry.baseURL
        }
        return "https://dl-cdn.alpinelinux.org/alpine/"
    }

    private fun downloadRootfsFromMirrors(arch: String, destFile: File) {
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        val activeMirror = getActiveAlpineMirror()
        val mirrorsToTry = buildList {
            add(activeMirror)
            for (m in com.openminis.app.ui.sandbox.MirrorCatalog.alpineMirrors) {
                if (m.baseURL != activeMirror) add(m.baseURL)
            }
        }.distinct()

        var lastError: Exception? = null
        val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")
        if (tempFile.exists()) tempFile.delete()

        for (mirror in mirrorsToTry) {
            val candidateUrls = listOf(
                "${mirror}v3.21/releases/$arch/alpine-minirootfs-3.21.9-$arch.tar.gz",
                "${mirror}latest-stable/releases/$arch/alpine-minirootfs-3.21.9-$arch.tar.gz",
                "${mirror}v3.21/releases/$arch/alpine-minirootfs-3.21.0-$arch.tar.gz"
            )

            for (url in candidateUrls) {
                try {
                    Log.i(TAG, "Attempting rootfs download from: $url")
                    val request = Request.Builder().url(url).build()
                    val response = httpClient.newCall(request).execute()
                    if (!response.isSuccessful) {
                        response.close()
                        continue
                    }
                    val body = response.body
                    if (body == null) {
                        response.close()
                        continue
                    }
                    val totalLength = body.contentLength()
                    var bytesDownloaded = 0L
                    var lastReported = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buf = ByteArray(16384)
                            var len: Int
                            while (input.read(buf).also { len = it } != -1) {
                                output.write(buf, 0, len)
                                bytesDownloaded += len
                                val now = System.currentTimeMillis()
                                if (now - lastReported > 80 && totalLength > 0) {
                                    val prog = (bytesDownloaded.toFloat() / totalLength).coerceIn(0f, 1f)
                                    _installState.value = RootfsInstallState.Downloading(prog)
                                    lastReported = now
                                }
                            }
                        }
                    }

                    if (tempFile.length() > 500_000) {
                        if (destFile.exists()) destFile.delete()
                        if (tempFile.renameTo(destFile)) {
                            Log.i(TAG, "Rootfs downloaded successfully (${destFile.length()} bytes) from $url")
                            return
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Download failed from $url: ${e.message}")
                    lastError = e
                    if (tempFile.exists()) tempFile.delete()
                }
            }
        }
        throw lastError ?: IOException("Failed to download Alpine rootfs from any configured mirror")
    }

    /** Directory containing extracted native libraries (read-only, executable). */
    val nativeLibDir: File = File(context.applicationInfo.nativeLibraryDir)

    /**
     * Verify PRoot binary is available in the native library directory.
     */
    suspend fun installProotIfNeeded() = withContext(Dispatchers.IO) {
        if (!isProotAvailable) {
            Log.w(
                TAG,
                "PRoot binary not found at $prootBinary (arch=${detectDeviceArch()}). " +
                    "Falling back to host /system/bin/sh execution."
            )
            return@withContext
        }

        Log.d(TAG, "PRoot binary available at $prootBinary")
    }

    /**
     * Reset rootfs. Optionally keeps user data (/root).
     * Returns the backup directory if keepUserData is true, null otherwise.
     */
    suspend fun reset(keepUserData: Boolean = false): File? = withContext(Dispatchers.IO) {
        var backupDir: File? = null

        if (keepUserData) {
            val rootHome = File(rootfsDir, "root")
            if (rootHome.exists()) {
                backupDir = File(context.cacheDir, "rootfs-backup-root")
                backupDir.deleteRecursively()
                rootHome.copyRecursively(backupDir, overwrite = true)
            }
        }

        rootfsDir.deleteRecursively()
        installIfNeeded()

        // Restore user data
        if (backupDir != null && backupDir.exists()) {
            val rootHome = File(rootfsDir, "root")
            backupDir.copyRecursively(rootHome, overwrite = true)
            backupDir.deleteRecursively()
        }

        backupDir
    }

    /**
     * Calculate the total size of the rootfs directory in bytes.
     */
    suspend fun getRootfsSize(): Long = withContext(Dispatchers.IO) {
        if (!rootfsDir.exists()) return@withContext 0L
        calculateDirSize(rootfsDir)
    }

    /**
     * Restore user data from a backup directory into /root.
     */
    suspend fun restoreUserData(backupDir: File) = withContext(Dispatchers.IO) {
        if (!backupDir.exists()) return@withContext
        val rootHome = File(rootfsDir, "root")
        rootHome.mkdirs()
        backupDir.copyRecursively(rootHome, overwrite = true)
        backupDir.deleteRecursively()
        Log.i(TAG, "User data restored from $backupDir")
    }

    /**
     * [T-android-storage-symlink-inflation] Third copy of the sizing walk —
     * routed through the shared one instead of keeping a following variant
     * alive here.
     *
     * This was hand-rolled recursion over `listFiles()`, and both `isDirectory`
     * and `length()` RESOLVE symlinks: a link to a directory was descended into
     * and its whole subtree counted again (once per link pointing at it), and a
     * link to a file was counted as a second copy of the target. That is the
     * same defect 754e65eac fixed in `SessionStorage.directorySize` and
     * `DebugRPCHandler.dirSize`, and missing it here is why the "Rootfs 管理"
     * screen still read 40.25 GB after the Storage screen had been corrected —
     * two screens sizing the same directory and disagreeing by an order of
     * magnitude.
     *
     * It was also the only one of the three with no cycle protection: a
     * `a/loop -> a` symlink recursed until StackOverflowError rather than
     * merely over-counting.
     */
    private fun calculateDirSize(dir: File): Long =
        com.openminis.app.data.session.SessionStorage.directorySize(dir)

    /**
     * Ensure session-specific directories exist on the host filesystem.
     */
    fun ensureSessionDirs(sessionId: String) {
        val sessionBase = File(context.filesDir, "minis-sessions/$sessionId")
        val subdirs = listOf("attachments", "offloads", "workspace", "browser")
        for (subdir in subdirs) {
            File(sessionBase, subdir).mkdirs()
        }
    }

    /**
     * Read system DNS servers and search domains from ConnectivityManager,
     * then write resolv.conf into the Alpine rootfs.
     * Mirrors iOS ISHKernel.configureDns / refreshDns.
     * Falls back to 8.8.8.8 / 8.8.4.4 if no system DNS available.
     */
    fun refreshDns() {
        if (!rootfsDir.exists()) return

        // [T-container-network-dns] (OpenMinis#396) Custom mode: the user's
        // servers, verbatim, on every call — install, boot and each network
        // change all land here, so a network switch can no longer wash them
        // out. Custom with no usable server resolves to System (see
        // ContainerDns.plan), so this can never write a resolv.conf with no
        // nameserver. Auto (the default) is the code below, unchanged.
        val plan = ContainerDns.plan(context)
        if (plan is ContainerDns.Plan.Custom) {
            Log.i(TAG, "[DNS] custom mode: ${plan.servers.joinToString(", ")}")
            writeResolvConf(ContainerDns.customResolvConf(plan.servers))
            return
        }

        val resolvConf = StringBuilder()

        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val linkProps: LinkProperties? = if (network != null) cm.getLinkProperties(network) else null

            if (linkProps != null) {
                // Search domains
                val domains = linkProps.domains
                if (!domains.isNullOrBlank()) {
                    resolvConf.append("search $domains\n")
                    Log.i(TAG, "[DNS] search domains: $domains")
                }

                // Nameservers
                val dnsServers = linkProps.dnsServers
                if (dnsServers.isNotEmpty()) {
                    for (server in dnsServers) {
                        val addr = server.hostAddress ?: continue
                        resolvConf.append("nameserver $addr\n")
                        Log.i(TAG, "[DNS] system server: $addr")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[DNS] Failed to read system DNS: ${e.message}")
        }

        // Fallback to public DNS if no system servers were found
        if (!resolvConf.contains("nameserver")) {
            Log.i(TAG, "[DNS] no system DNS — using fallback: 8.8.8.8, 8.8.4.4")
            resolvConf.append("nameserver 8.8.8.8\n")
            resolvConf.append("nameserver 8.8.4.4\n")
        }

        writeResolvConf(resolvConf.toString())
    }

    private fun writeResolvConf(content: String) {
        try {
            val file = File(rootfsDir, "etc/resolv.conf")
            file.parentFile?.mkdirs()
            file.writeText(content)
            Log.i(TAG, "[DNS] resolv.conf updated:\n$content")
        } catch (e: Exception) {
            Log.e(TAG, "[DNS] Failed to write resolv.conf: ${e.message}")
        }
    }

    /**
     * Copy every file under `assets/default_mount/` into the rootfs, preserving
     * the directory layout. Mirrors iOS RootfsManager.applyDefaultMountOverlay
     * (src/ios/iSH/RootfsManager.swift:153-225) — runs on every boot so shipping
     * updated profile scripts / URL wrappers with an app release just works.
     *
     * Files under `bin` / `sbin` paths get the execute bit set. Existing files
     * are overwritten so users see the latest shipped version even if they
     * previously edited the file — matching iOS behavior.
     *
     * No-op when the asset dir is missing or the rootfs hasn't been extracted.
     */
    suspend fun applyDefaultMountOverlay() = withContext(Dispatchers.IO) {
        if (!rootfsDir.exists()) {
            Log.d(TAG, "[DefaultMount] rootfs missing, skipping overlay")
            return@withContext
        }

        // [T-git-createobject-rename] #409: before any shell can run git,
        // repair the invalid core.createObject an older minis.sh wrote.
        migrateSystemGitConfig()

        // AssetManager.list() returns an empty array for both "leaf file" and
        // "missing path", so probe for children before recursing — otherwise
        // a missing overlay dir would be mistaken for a single leaf file.
        val rootEntries = try {
            context.assets.list(DEFAULT_MOUNT_ASSET)
        } catch (t: Throwable) {
            null
        }
        if (rootEntries.isNullOrEmpty()) {
            Log.d(TAG, "[DefaultMount] no default_mount assets found, skipping overlay")
            return@withContext
        }

        val startNs = System.nanoTime()
        var fileCount = 0
        try {
            fileCount = copyAssetDir(DEFAULT_MOUNT_ASSET, rootfsDir)
        } catch (t: Throwable) {
            Log.w(TAG, "[DefaultMount] overlay failed: ${t.message}", t)
            return@withContext
        }

        // Mirror iOS removeExternallyManagedMarker() — drop PEP 668 marker so
        // `pip install` Just Works inside this embedded Alpine rootfs even
        // when the shipped pip.conf isn't being read (e.g. pip invoked with
        // --isolated or via a venv). Safe: this is a single-tenant sandbox.
        val markerRemoved = removeExternallyManagedMarker()

        // [T-mcp-cli-readonly-android] Make the shipped minis-mcp-cli Python lib
        // read-only inside the guest so a user can't `vi`-tamper the bundled
        // scripts (mirrors iOS #707). Scoped to /usr/local/lib/minis-mcp-cli/
        // ONLY — the wrapper at /usr/local/bin/minis-mcp-cli stays executable +
        // writable (app-managed). Re-applied on every boot AFTER the copy; the
        // copyAssetDir leaf-copy above re-opens read-only files writable first,
        // so the next app-upgrade overlay still overwrites cleanly.
        val lockedCount = lockMcpCliLibReadOnly()

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        Log.i(TAG, "[DefaultMount] Done. $fileCount file(s) overlaid, $markerRemoved EXTERNALLY-MANAGED marker(s) removed, $lockedCount minis-mcp-cli lib path(s) locked read-only in %.1fms".format(elapsedMs))
    }

    /**
     * [T-mcp-cli-readonly-android] Set the `/usr/local/lib/minis-mcp-cli/`
     * subtree read-only for the guest: directories 0555 (read+execute, no
     * write), files 0444 (read-only). Java's File API has no octal chmod, so
     * we use setWritable(false, false) + setReadable(true, false)
     * (+ setExecutable(true, false) on dirs) — the `false` ownerOnly arg makes
     * the permission apply to all users, matching the setReadable style used
     * for libtalloc above. Returns the number of paths adjusted; 0 if the lib
     * dir is absent. Idempotent — the pre-copy unlock in copyAssetDir keeps the
     * upgrade path working across boots.
     */
    private fun lockMcpCliLibReadOnly(): Int {
        val libDir = File(rootfsDir, "usr/local/lib/minis-mcp-cli")
        if (!libDir.isDirectory) return 0
        var count = 0
        // walkBottomUp so child files are locked before their parent dir loses
        // write — dir mode doesn't gate chmod of already-visited children, but
        // bottom-up keeps the intent clear and avoids any traversal surprise.
        for (f in libDir.walkBottomUp()) {
            f.setWritable(false, false)
            f.setReadable(true, false)
            if (f.isDirectory) f.setExecutable(true, false)
            count++
        }
        return count
    }

    /**
     * Remove the PEP 668 EXTERNALLY-MANAGED marker file from every
     * `usr/lib/python3*` directory in the rootfs. Mirrors iOS
     * RootfsManager.removeExternallyManagedMarker (src/ios/iSH/RootfsManager.swift:228-239).
     * Returns the number of markers removed.
     */
    /**
     * [T-git-createobject-rename] openminis/openminis#409: older builds ran
     * `git config --system core.createObject copy` from minis.sh. `copy` is
     * not a valid value, so every git command in the sandbox died with
     * "invalid mode for object creation". Removes that line from the guest's
     * /etc/gitconfig on the host side (no shell needed). Everything else in
     * the file is kept byte for byte; running it again changes nothing.
     * Returns the number of lines removed.
     */
    private fun migrateSystemGitConfig(): Int = migrateGitConfigFile(File(rootfsDir, "etc/gitconfig"))

    private fun removeExternallyManagedMarker(): Int {
        val usrLib = File(rootfsDir, "usr/lib")
        val children = usrLib.listFiles() ?: return 0
        var removed = 0
        for (entry in children) {
            if (!entry.name.startsWith("python3")) continue
            val marker = File(entry, "EXTERNALLY-MANAGED")
            if (marker.exists() && marker.delete()) {
                Log.i(TAG, "[DefaultMount] Removed EXTERNALLY-MANAGED from ${entry.name}")
                removed++
            }
        }
        return removed
    }

    /**
     * Recursively copy an assets path into [targetBase]. Returns the number of
     * regular files written. Files under /bin/, /sbin/, /usr/bin/, /usr/sbin/,
     * /usr/local/bin/, /usr/local/sbin/ get the execute bit set.
     */
    private fun copyAssetDir(assetPath: String, targetBase: File, prefix: String = ""): Int {
        val entries = context.assets.list(assetPath) ?: return 0
        if (entries.isEmpty()) {
            // Leaf: asset is a file. Copy it.
            val dest = File(targetBase, prefix)
            dest.parentFile?.mkdirs()
            // [T-mcp-cli-readonly-android] A prior boot may have set this file
            // (and its dir) read-only — the minis-mcp-cli lib subtree, locked
            // below. Re-open both writable before overwriting, otherwise an app
            // upgrade can't replace the shipped file: truncating an existing
            // file needs write on the FILE, and creating a new one needs write
            // on the PARENT DIR. No-op when already writable / not yet present.
            dest.parentFile?.setWritable(true, true)
            if (dest.exists()) dest.setWritable(true, true)
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            if (isUnderBinDir(prefix)) {
                dest.setExecutable(true, false)
            }
            return 1
        }
        var count = 0
        for (name in entries) {
            val childAsset = "$assetPath/$name"
            val childPrefix = if (prefix.isEmpty()) name else "$prefix/$name"
            count += copyAssetDir(childAsset, targetBase, childPrefix)
        }
        return count
    }

    private fun isUnderBinDir(relativePath: String): Boolean {
        val normalized = "/$relativePath"
        return BIN_DIR_PREFIXES.any { normalized.startsWith(it) }
    }

    // --- POSIX tar extraction ---

    /**
     * Extract a POSIX tar stream into the target directory.
     * Handles regular files, directories, and symlinks.
     * Does not depend on any external library.
     */
    internal fun extractTar(input: InputStream, targetDir: File) {
        val header = ByteArray(512)

        while (true) {
            val bytesRead = readFully(input, header)
            if (bytesRead < 512) break

            // Check for end-of-archive (two consecutive zero blocks)
            if (header.all { it == 0.toByte() }) break

            val name = extractString(header, 0, 100)
            val modeOctal = extractString(header, 100, 8)
            val sizeOctal = extractString(header, 124, 12)
            val typeFlag = header[156].toInt().toChar()
            val linkName = extractString(header, 157, 100)
            val mode = modeOctal.trim().toIntOrNull(8) ?: 0

            // Handle GNU/POSIX long names via prefix field (bytes 345-500)
            val prefix = extractString(header, 345, 155)
            val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name

            if (fullName.isEmpty()) break

            val size = sizeOctal.trim().toLongOrNull(8) ?: 0L
            val outFile = File(targetDir, fullName)

            when (typeFlag) {
                '5', 'D' -> {
                    // Directory
                    outFile.mkdirs()
                }
                '2' -> {
                    // Symbolic link
                    outFile.parentFile?.mkdirs()
                    try {
                        java.nio.file.Files.createSymbolicLink(
                            outFile.toPath(),
                            java.nio.file.Paths.get(linkName)
                        )
                    } catch (_: Exception) {
                        // Symlinks may fail on some Android versions; skip
                        Log.w(TAG, "Failed to create symlink: $fullName -> $linkName")
                    }
                }
                '0', '\u0000' -> {
                    // Regular file (type '0' or null/legacy)
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output ->
                        var remaining = size
                        val buf = ByteArray(8192)
                        while (remaining > 0) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val n = input.read(buf, 0, toRead)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                    // Preserve executable permission from tar header
                    if (mode and 0b001_001_001 != 0) {
                        outFile.setExecutable(true, false)
                    }
                    // Skip padding to next 512-byte boundary
                    val remainder = (size % 512).toInt()
                    if (remainder != 0) {
                        skipFully(input, (512 - remainder).toLong())
                    }
                    continue // Already consumed data + padding
                }
                '1' -> {
                    // Hard link — create a copy
                    outFile.parentFile?.mkdirs()
                    val linkTarget = File(targetDir, linkName)
                    if (linkTarget.exists()) {
                        linkTarget.copyTo(outFile, overwrite = true)
                    }
                }
                else -> {
                    // Unknown type, skip data
                }
            }

            // Skip data blocks for non-file entries that we didn't consume above
            if (typeFlag != '0' && typeFlag != '\u0000' && size > 0) {
                val blocks = (size + 511) / 512 * 512
                skipFully(input, blocks)
            }
        }
    }

    internal fun extractString(header: ByteArray, offset: Int, length: Int): String {
        val end = minOf(offset + length, header.size)
        var actualEnd = offset
        for (i in offset until end) {
            if (header[i] == 0.toByte()) break
            actualEnd = i + 1
        }
        return String(header, offset, actualEnd - offset, Charset.forName("UTF-8"))
    }

    internal fun readFully(input: InputStream, buf: ByteArray): Int {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n < 0) return offset
            offset += n
        }
        return offset
    }

    internal fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val n = input.read(buf, 0, toRead)
            if (n < 0) break
            remaining -= n
        }
    }

    /**
     * InputStream wrapper that reports cumulative bytes read as a 0..1 fraction.
     * Throttles updates so each +1% bump emits at most once. Designed to wrap
     * the asset stream (not the gzip stream) so progress is monotonic against
     * a size we can cheaply know up front.
     */
    private class ProgressInputStream(
        inner: InputStream,
        private val total: Long,
        private val onProgress: (Float) -> Unit,
    ) : FilterInputStream(inner) {
        private var read: Long = 0
        private var lastReportedPercent: Int = -1

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) { read += 1; publish() }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) { read += n; publish() }
            return n
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(n)
            if (skipped > 0) { read += skipped; publish() }
            return skipped
        }

        private fun publish() {
            if (total <= 0) return
            val pct = ((read * 100) / total).toInt().coerceIn(0, 99)
            if (pct != lastReportedPercent) {
                lastReportedPercent = pct
                onProgress(pct / 100f)
            }
        }
    }

    companion object {
        private const val TAG = "RootfsManager"

        /** [T-git-createobject-rename] Apply [migrateGitConfigText] to [file] in place; 0 when it is absent. */
        internal fun migrateGitConfigFile(file: File): Int {
            if (!file.isFile) return 0
            return try {
                val (migrated, removed) = migrateGitConfigText(file.readText())
                if (removed > 0) {
                    val tmp = File(file.parentFile, "${file.name}.minis-tmp")
                    tmp.writeText(migrated)
                    if (!tmp.renameTo(file)) {
                        file.writeText(migrated)
                        tmp.delete()
                    }
                    Log.i(TAG, "[GitConfigMigration] removed $removed invalid core.createObject line(s) from /etc/gitconfig")
                }
                removed
            } catch (t: Throwable) {
                Log.w(TAG, "[GitConfigMigration] failed: ${t.message}")
                0
            }
        }

        /**
         * [T-git-createobject-rename] The /etc/gitconfig text with every
         * invalid `createObject` entry of a `[core]` section removed, and how
         * many lines went.
         *
         * - The section name and the key are matched case-insensitively, as
         *   Git does.
         * - The value is valid only if it is exactly `link` or `rename`: Git
         *   compares the value case-sensitively (anything else, a missing
         *   value included, is fatal).
         * - Quotes and trailing `#` / `;` comments are ignored when reading
         *   the value.
         * - Other sections, other keys, comments, blank lines and the line
         *   endings are kept as they are. An emptied `[core]` header is kept
         *   too (harmless).
         */
        internal fun migrateGitConfigText(text: String): Pair<String, Int> {
            val lines = text.split("\n")
            val out = ArrayList<String>(lines.size)
            var inCore = false
            var removed = 0
            val header = Regex("""^\s*\[\s*([A-Za-z0-9.-]+)(\s+"[^"]*")?\s*]\s*([#;].*)?$""")
            val entry = Regex("""^\s*createobject\s*(=(.*))?$""", RegexOption.IGNORE_CASE)
            for (line in lines) {
                val bare = line.removeSuffix("\r")
                val h = header.find(bare)
                if (h != null) {
                    inCore = h.groupValues[1].equals("core", ignoreCase = true) && h.groupValues[2].isEmpty()
                    out += line
                    continue
                }
                if (inCore) {
                    val e = entry.find(bare)
                    if (e != null && gitConfigValue(e.groupValues[2], e.groupValues[1].isNotEmpty()) !in VALID_CREATE_OBJECT) {
                        removed++
                        continue
                    }
                }
                out += line
            }
            return (if (removed == 0) text else out.joinToString("\n")) to removed
        }

        private val VALID_CREATE_OBJECT = setOf("link", "rename")

        /** A git-config value without quotes or a trailing comment; null when the key has no `=`. */
        private fun gitConfigValue(raw: String, hasEquals: Boolean): String? {
            if (!hasEquals) return null
            val sb = StringBuilder()
            var quoted = false
            for (c in raw) {
                when {
                    c == '"' -> quoted = !quoted
                    !quoted && (c == '#' || c == ';') -> break
                    else -> sb.append(c)
                }
            }
            return sb.toString().trim()
        }
        private const val ARCH = "aarch64"
        private const val ROOTFS_ASSET = "alpine-minirootfs.tar.gz"
        private const val ROOTFS_ASSET_TAR = "alpine-minirootfs.tar"
        private const val PROOT_ASSET = "proot-aarch64"
        private const val DEFAULT_MOUNT_ASSET = "default_mount"

        /**
         * Rootfs paths whose contents must be executable. Matches iOS
         * RootfsManager.swift:199 byte-for-byte so the same overlay tree
         * produces identical file modes on both platforms.
         */
        private val BIN_DIR_PREFIXES = listOf(
            "/bin/",
            "/sbin/",
            "/usr/bin/",
            "/usr/sbin/",
            "/usr/local/bin/",
            "/usr/local/sbin/",
        )

        @Volatile
        private var instance: RootfsManager? = null

        fun getInstance(context: Context): RootfsManager {
            return instance ?: synchronized(this) {
                instance ?: RootfsManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}

// ─── Merged from ShellExecutor.kt ──────────────────────────────────────────────────

/**
 * Executes shell commands inside the PRoot sandbox via ProcessBuilder.
 * Corresponds to iOS ISHShellExecutor.
 */
object ShellExecutor {

    private const val TAG = "ShellExecutor"
    private const val DEFAULT_TIMEOUT_MS = 600_000L // 10 minutes

    data class ShellResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long
    )

    /** The currently running process, if any. Can be destroyed to stop execution. */
    @Volatile
    var currentProcess: Process? = null
        private set

    /**
     * Execute a command inside the PRoot sandbox.
     *
     * @param context Android context for resolving PROOT_TMP_DIR
     * @param command Shell command string to execute
     * @param timeout Timeout in milliseconds (default 10 minutes)
     * @param environment Additional environment variables
     * @param lineCallback Called for each line of output (on IO dispatcher)
     * @return ShellResult with combined stdout+stderr, exit code, and duration
     */
    suspend fun execute(
        context: Context,
        command: String,
        timeout: Long = DEFAULT_TIMEOUT_MS,
        environment: Map<String, String> = emptyMap(),
        lineCallback: ((String) -> Unit)? = null
    ): ShellResult = withContext(Dispatchers.IO) {
        check(PRootKernel.isBooted) { "PRootKernel must be booted before executing commands" }

        val first = runOnce(context, command, timeout, environment, lineCallback, noSeccomp = false)

        // [T-android-seccomp-selfheal / GH#186] If the child died on an early
        // fatal signal with no output at all, the host kernel's seccomp fast
        // path is the prime suspect — retry once without it. Gated hard so a
        // normal failing command is never re-run; see SeccompFallbackPolicy.
        if (!SeccompFallbackPolicy.shouldRetryWithoutSeccomp(
                exitCode = first.exitCode,
                durationMs = first.durationMs,
                producedOutput = first.output.isNotEmpty(),
                alreadyRetried = false,
            )
        ) {
            return@withContext first
        }

        com.openminis.app.logging.AppLogger.warning(
            TAG,
            SeccompFallbackPolicy.retryLogLine(first.exitCode, first.durationMs, "shell command"),
        )
        // The retry deliberately reuses the caller's lineCallback: the first
        // attempt produced no output (that is a precondition of retrying), so
        // there is nothing to duplicate.
        val retried = runOnce(context, command, timeout, environment, lineCallback, noSeccomp = true)
        if (retried.exitCode == 0) {
            com.openminis.app.logging.AppLogger.warning(
                TAG,
                "[proot-retry] succeeded with ${SeccompFallbackPolicy.NO_SECCOMP_ENV}=1 — " +
                    "this device needs the seccomp workaround (GH#186)",
            )
        }
        // Whatever the retry returned is what the caller sees: if it failed
        // too, the error surfaces normally rather than being swallowed.
        retried
    }

    /**
     * One proot invocation. Split out of [execute] so the GH#186 seccomp
     * fallback can re-run the identical command instead of duplicating the
     * spawn/plumbing logic.
     */
    private suspend fun runOnce(
        context: Context,
        command: String,
        timeout: Long,
        environment: Map<String, String>,
        lineCallback: ((String) -> Unit)?,
        noSeccomp: Boolean,
    ): ShellResult = withContext(Dispatchers.IO) {
        val prootCommand = PRootKernel.buildProotCommand(command)

        Log.d(TAG, "Executing: $command")

        val startTime = System.currentTimeMillis()

        val processBuilder = ProcessBuilder(prootCommand)
        processBuilder.redirectErrorStream(true)

        // Set required environment for PRoot
        val env = processBuilder.environment()
        env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
        if (PRootKernel.nativeLibDir.isNotEmpty()) {
            env["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
        }
        if (PRootKernel.prootLoaderPath.isNotEmpty()) {
            env["PROOT_LOADER"] = PRootKernel.prootLoaderPath
        }
        if (PRootKernel.prootLoader32Path.isNotEmpty()) {
            env["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
        }

        // Apply custom environment from PRootKernel
        for ((key, value) in PRootKernel.customEnvironment) {
            env[key] = value
        }
        // [T-net-android-phase3] Settings > Network "Shell" route: proxy
        // variables for the local entry (empty = direct, unchanged). Before
        // the caller's / user's own variables, which may override them.
        env.putAll(com.openminis.app.network.NetworkShellEnvironment.proxyVariables())

        // Apply per-call environment overrides
        for ((key, value) in environment) {
            env[key] = value
        }

        // [T-android-seccomp-selfheal / GH#186] Applied last so nothing above
        // can clobber it on the retry attempt.
        if (noSeccomp) {
            env[SeccompFallbackPolicy.NO_SECCOMP_ENV] = SeccompFallbackPolicy.NO_SECCOMP_VALUE
        }

        val output = StringBuilder()
        var exitCode = -1

        try {
            withTimeout(timeout) {
                // [T-shell-stall-yes-head] SIGPIPE unblocked in the child; see SpawnSignalMask.
                val process = SpawnSignalMask.start(processBuilder)
                currentProcess = process

                try {
                    // Read raw chars to preserve \r for TerminalSanitizer CR-folding.
                    // readLine() would consume \r as line terminator, losing progress overwrites.
                    InputStreamReader(process.inputStream, StandardCharsets.UTF_8).use { reader ->
                        val buf = CharArray(4096)
                        var lastLineForCallback = StringBuilder()
                        var n: Int
                        while (reader.read(buf).also { n = it } != -1) {
                            output.append(buf, 0, n)
                            // Feed lines to callback for UI updates
                            if (lineCallback != null) {
                                for (i in 0 until n) {
                                    val c = buf[i]
                                    if (c == '\n') {
                                        lineCallback.invoke(lastLineForCallback.toString())
                                        lastLineForCallback.clear()
                                    } else if (c != '\r') {
                                        lastLineForCallback.append(c)
                                    }
                                }
                            }
                        }
                        if (lineCallback != null && lastLineForCallback.isNotEmpty()) {
                            lineCallback.invoke(lastLineForCallback.toString())
                        }
                    }

                    exitCode = process.waitFor()
                } finally {
                    currentProcess = null
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "Command timed out after ${timeout}ms: $command")
            currentProcess?.destroyForcibly()
            currentProcess = null
            output.appendLine("\n[Command timed out after ${timeout / 1000}s]")
            exitCode = 124 // Standard timeout exit code
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: $command", e)
            currentProcess?.destroyForcibly()
            currentProcess = null
            output.appendLine("\n[Error: ${e.message}]")
            exitCode = -1
        }

        val durationMs = System.currentTimeMillis() - startTime
        Log.d(TAG, "Command completed in ${durationMs}ms with exit code $exitCode")

        ShellResult(
            output = output.toString().trimEnd(),
            exitCode = exitCode,
            durationMs = durationMs
        )
    }

    /**
     * Forcibly destroy the currently running process.
     */
    fun destroyCurrent() {
        currentProcess?.let { process ->
            Log.i(TAG, "Destroying current process")
            process.destroyForcibly()
            currentProcess = null
        }
    }
}
