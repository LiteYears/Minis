package com.openminis.app.sandbox

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import java.io.BufferedWriter
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStreamWriter
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Host-side endpoint of the proot `native_offload` extension.
 *
 * The guest issues `execve("<handler-name>", argv, envp)`; the proot
 * extension sends argv/env/cwd over an abstract unix socket to this
 * server. The server dispatches to the registered [NativeOffloadHandler],
 * writes the handler's combined output into a tmpfile inside the guest's
 * /tmp, and replies with `(exit_code, guest_tmpfile_path)`. The extension
 * then rewrites the execve into `/bin/cat <tmpfile>` so the guest sees
 * the handler output as plain stdout.
 *
 * Mirrors iOS `native_offload_add_handler` + `native_offload_exec`.
 */
data class NativeOffloadRequest(
    val pid: Int,
    val argv: List<String>,
    val env: Map<String, String>,
    val cwd: String,
    /**
     * T340: chat session id forwarded by the agent shell via the
     * `MINIS_CHAT_SESSION_ID` env var. Lets [OffloadPermissionManager]
     * scope ASK_ONCE grants/denials per-chat-session instead of using
     * a single process-wide bucket. Null when the offload originates
     * outside a chat (e.g. interactive terminal) — handlers fall back
     * to `OFFLOAD_GLOBAL_SESSION_ID` in that case.
     */
    val sessionId: String? = null,
)

data class NativeOffloadResult(
    val exitCode: Int,
    val output: String,
)

fun interface NativeOffloadHandler {
    fun handle(request: NativeOffloadRequest): NativeOffloadResult
}

/** [T-native-offload-peer-uid] Only this app's own uid may use the offload socket. */
internal object PeerCheck {
    fun allowed(peerUid: Int, myUid: Int): Boolean = peerUid >= 0 && peerUid == myUid
}

/** [T-offload-cloexec-api29] Os.fcntlInt is public SDK API from 30 (R) on. */
internal fun canSetCloseOnExec(sdkInt: Int): Boolean = sdkInt >= 30

object NativeOffloadServer {
    private const val TAG = "NativeOffloadServer"
    /**
     * [T-native-offload-bind] Prefix of the abstract socket name; the full
     * name is [socketName]. It is never used on its own: the bare
     * "native-offload" is what every earlier build bound.
     */
    private const val SOCKET_NAME_PREFIX = "native-offload"
    private const val MAGIC_REQ = 0x46464F4E  // 'N' 'O' 'F' 'F' little-endian
    private const val MAGIC_RSP = 0x52464F4E  // 'N' 'O' 'F' 'R'
    private const val VERSION = 1

    /** [T-android-offload-tmp-leak] Filename prefix of a handler reply file. */
    private const val REPLY_PREFIX = ".native-offload-"

    /**
     * How long a reply file may live before the in-session sweep may remove it.
     *
     * The real gap between writing the file and the rewritten `/bin/cat` reading
     * it is sub-millisecond, so 10 minutes is enormously conservative — it
     * exists only so a stopped/slow tracee can never lose its output.
     */
    private const val REPLY_TTL_MS = 10 * 60 * 1000L

    /** Run the opportunistic sweep every N replies, not on every single one. */
    private const val SWEEP_EVERY_N_REPLIES = 50L

    /**
     * [T-native-offload-bind] The abstract socket this app instance listens
     * on: `native-offload-<uid>-<pid>`, e.g. `native-offload-10379-4242`.
     *
     * Abstract unix names are global to the network namespace, not to the
     * app. A fixed "native-offload" therefore collided with anything else on
     * the device holding that name - a second Minis in an app-clone / work
     * profile / secondary user (same package, different uid), a fork built
     * from this code, or a leftover process. The bind then failed and, since
     * it threw from Application.onCreate, the app could never start again.
     * The uid differs for every such instance (userId * 100000 + appId).
     *
     * [GH-440] The pid makes the name unique per process as well, so it no
     * longer relies on exactly one process per uid calling [start]: today
     * that holds only because the `:acra` reporter returns from
     * MinisApp.onCreate first, and any process added later would have
     * silently lost offload to whichever bound first. It also means a
     * previous process of this app (or, below API 30 where the socket is not
     * close-on-exec, a guest process that inherited it) can no longer hold
     * the name this one needs. Suggested in the GH #440 patch.
     *
     * proot gets the name only through `--native-offload=<name>:<handlers>`
     * from the four launchers, which all read it here, so the name is not
     * persisted anywhere and the proot extension needs no change.
     */
    val socketName: String by lazy {
        socketNameFor(android.os.Process.myUid(), android.os.Process.myPid())
    }

    internal fun socketNameFor(uid: Int, pid: Int): String = "$SOCKET_NAME_PREFIX-$uid-$pid"

    /** First background retry delays, then [RETRY_STEADY_MS] until it binds. */
    private val RETRY_DELAYS_MS = longArrayOf(50L, 100L, 200L, 400L, 800L, 2_000L, 5_000L, 10_000L)
    private const val RETRY_STEADY_MS = 30_000L

    /** How long a shell launcher waits for a bind that is being retried. */
    const val AWAIT_LISTENING_MS = 2_000L

    /**
     * Launchers wait only within this window after the first bind failed.
     * It covers the usual cause - the previous process of this app still
     * releasing the name for a moment after a restart. Past it, the name is
     * held for real and waiting would only add [AWAIT_LISTENING_MS] to every
     * shell command while degraded.
     */
    private const val AWAIT_WINDOW_MS = 5_000L

    private val handlers = ConcurrentHashMap<String, NativeOffloadHandler>()
    private val counter = AtomicLong(0)

    /**
     * [GH #453] Most offload calls served at once; one more is answered
     * "busy" (exit 75) instead of getting a thread. Each call holds a worker
     * thread (about 1 MB of stack) until its handler returns, and the protocol
     * has no cancel message, so a handler that never returns used to leak its
     * thread for the life of the process. The known never-returning waits are
     * now bounded (OffloadPermissionManager.DECISION_TIMEOUT_MS), but the long
     * legitimate ones remain - `minis-scheduled run` waits for a whole agent
     * run (up to ~90 min), `model_use` for a full model reply, and each
     * sub-agent's shell can have one in flight. 128 is far above what those
     * need together and far below where thread creation fails, so this only
     * ever turns an unknown leak into a visible, logged error instead of a
     * crash. Rejecting rather than queueing: a queue behind stuck handlers
     * would hang every later command.
     */
    internal const val MAX_CONCURRENT_CALLS = 128

    /** [GH #453] Exit code of a call refused at [MAX_CONCURRENT_CALLS] (EX_TEMPFAIL). */
    internal const val EXIT_BUSY = 75

    internal val inFlight = OffloadInFlight()
    private val lastBusyLogMs = AtomicLong(0)
    @Volatile
    private var serverSocket: LocalServerSocket? = null
    private var acceptThread: Thread? = null

    /** [T-native-offload-bind] Non-null while a background bind retry runs. */
    @Volatile
    private var retryThread: Thread? = null

    /** When the first bind failed (uptime ms), 0 when not degraded. */
    @Volatile
    private var degradedSinceMs = 0L

    /** True once the server is bound and accepting. */
    val isListening: Boolean get() = serverSocket != null

    @Volatile
    private var rootfsTmpDir: File? = null

    val registeredHandlers: Set<String> get() = handlers.keys.toSet()

    fun register(name: String, handler: NativeOffloadHandler) {
        require(name.isNotEmpty())
        handlers[name] = handler
        Log.d(TAG, "register '$name' (total=${handlers.size})")
    }

    /**
     * Bind the server. Never throws and never sleeps on the caller's thread.
     *
     * [T-native-offload-bind] One synchronous attempt, as before, so on the
     * normal path the socket is listening before onCreate returns and before
     * any shell can start. If that attempt fails (the name is held by
     * someone else), the app keeps running without native offload: the
     * offload CLIs report themselves unavailable (see [awaitListening] and
     * the handler stubs) and a daemon thread retries the bind in the
     * background until it succeeds. Previously this threw from
     * Application.onCreate after ~1.55 s of sleeps on the main thread, and a
     * name held for longer than that put the app into an endless crash loop.
     */
    @Synchronized
    fun start(rootfsDir: File) {
        rootfsTmpDir = File(rootfsDir, "tmp")
        if (serverSocket != null || retryThread != null) return

        val s = tryBind(attempt = 1)
        if (s != null) {
            onBound(s)
            return
        }
        degradedSinceMs = android.os.SystemClock.uptimeMillis()
        Log.w(TAG, "native offload unavailable: '$socketName' is held by another process; " +
            "offload CLIs report unavailable, retrying the bind in the background")
        retryThread = thread(name = "native-offload-rebind", isDaemon = true) { retryBindLoop() }
    }

    /**
     * Wait up to [timeoutMs] for the server to be listening. Returns at once
     * when it already is (the normal case), when no bind is pending, on the
     * main thread, or once [AWAIT_WINDOW_MS] have passed since the first
     * bind failed. Shell launchers pass `--native-offload` only when this
     * returns true: a proot pointed at a name nobody of ours serves either
     * fails each offload silently or, when another process holds the name
     * without accepting, blocks in connect/read forever.
     */
    fun awaitListening(timeoutMs: Long = AWAIT_LISTENING_MS): Boolean {
        if (serverSocket != null) return true
        if (retryThread == null) return false
        if (android.os.Looper.getMainLooper()?.isCurrentThread == true) return false
        val sinceFail = android.os.SystemClock.uptimeMillis() - degradedSinceMs
        if (sinceFail > AWAIT_WINDOW_MS) return false
        val deadline = System.currentTimeMillis() + minOf(timeoutMs, AWAIT_WINDOW_MS - sinceFail)
        while (serverSocket == null && retryThread != null && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50) } catch (_: InterruptedException) { break }
        }
        return serverSocket != null
    }

    private fun tryBind(attempt: Int): LocalServerSocket? =
        try {
            LocalServerSocket(socketName)
        } catch (e: java.io.IOException) {
            if (attempt <= RETRY_DELAYS_MS.size + 1 || attempt % 10 == 0) {
                Log.w(TAG, "bind '$socketName' attempt $attempt failed: ${e.message}")
            }
            null
        }

    /**
     * [T-native-offload-cloexec] Keep the listening socket out of every
     * process we exec. Without FD_CLOEXEC the terminal's proot and every
     * guest process under it (fork+execve in pty_bridge.c) held the bound
     * name, so one of them outliving the app would keep the name taken and
     * every later launch would find it in use. Nothing needs to inherit it:
     * proot connects by name.
     */
    private fun setCloseOnExec(s: LocalServerSocket) {
        // [T-offload-cloexec-api29] Os.fcntlInt is public only from API 30. On
        // 26-29 it is a greylisted hidden method: linking it throws
        // NoSuchMethodError - an Error, not an Exception - which killed the
        // process from the bind-retry thread the first time a shell started
        // (Android 10, 1.15-beta.3). Below 30 the socket stays inheritable, as
        // it was before f61d6f472; a stale holder of the name is already
        // handled by the bind-retry loop.
        if (!canSetCloseOnExec(android.os.Build.VERSION.SDK_INT)) {
            Log.i(TAG, "FD_CLOEXEC on the offload socket skipped below API 30")
            return
        }
        try {
            android.system.Os.fcntlInt(s.fileDescriptor, android.system.OsConstants.F_SETFD, android.system.OsConstants.FD_CLOEXEC)
        } catch (t: Throwable) {
            Log.w(TAG, "FD_CLOEXEC on the offload socket failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun retryBindLoop() {
        var attempt = 1
        while (true) {
            val delay = RETRY_DELAYS_MS.getOrElse(attempt - 1) { RETRY_STEADY_MS }
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                return
            }
            attempt++
            val s = tryBind(attempt) ?: continue
            synchronized(this) {
                retryThread = null
                degradedSinceMs = 0L
                onBound(s)
            }
            Log.i(TAG, "native offload recovered after $attempt bind attempts")
            return
        }
    }

    private fun onBound(s: LocalServerSocket) {
        setCloseOnExec(s)
        serverSocket = s
        acceptThread = thread(name = "native-offload-accept", isDaemon = true) {
            runAcceptLoop(s)
        }
        Log.i(TAG, "listening on abstract socket '$socketName' " +
            "handlers=${handlers.keys.sorted()} tmpDir=${rootfsTmpDir?.absolutePath}")

        // [T-android-offload-tmp-leak] Sweep reply files orphaned by earlier
        // app processes. See sweepStaleReplies for why this is safe here and
        // why the mechanism leaks in the first place.
        sweepStaleReplies(all = true)
    }

    /**
     * [T-android-offload-tmp-leak] Delete `.native-offload-*` reply files.
     *
     * WHY THESE LEAK. Each offload call writes the handler's combined output to
     * `<rootfs>/tmp/.native-offload-<pid>-<seq>` and returns the GUEST path;
     * proot's native_offload extension then rewrites the tracee's execve into
     * `/bin/cat <tmpfile>`. So the host cannot delete the file at reply time —
     * `cat` has not run yet, and deleting it would turn every offload call into
     * "No such file or directory". Nothing else ever removed them either
     * (`delete`/`cleanup` were zero occurrences in this file), so one file
     * accumulated per offload call, forever: measured on a dev device, 35 files
     * spanning 12 days and surviving many app restarts, growing +1 per call.
     * On a heavy user's device this reached thousands of files / GBs, showing
     * up as an inflated "Shell container" figure on the storage screen.
     *
     * WHY DELETING IS SAFE HERE.
     *  - [all] = true is used at server start. Any file present then belongs to
     *    a PREVIOUS app process: its tracee died with that process, so no `cat`
     *    can still be pending on it.
     *  - [all] = false keeps files younger than [REPLY_TTL_MS] and is used
     *    opportunistically while serving. The TTL is what makes it safe: it only
     *    ever removes files far older than the microseconds between writing the
     *    reply and the rewritten `cat` reading it. A pathologically stopped
     *    tracee (SIGSTOP between execve-enter and the read) is the sole way to
     *    exceed it, and that already means the caller is not reading its output.
     *
     * Failures are logged and swallowed: a leaked temp file must never break an
     * offload call.
     */
    private fun sweepStaleReplies(all: Boolean) {
        val dir = rootfsTmpDir ?: return
        try {
            val cutoff = System.currentTimeMillis() - REPLY_TTL_MS
            var removed = 0
            var bytes = 0L
            dir.listFiles { f -> f.isFile && f.name.startsWith(REPLY_PREFIX) }?.forEach { f ->
                if (!all && f.lastModified() > cutoff) return@forEach
                val len = f.length()
                if (f.delete()) {
                    removed++
                    bytes += len
                }
            }
            if (removed > 0) {
                Log.i(TAG, "swept $removed stale reply file(s), freed ${bytes / 1024}KB (all=$all)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "sweepStaleReplies failed: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        retryThread?.interrupt()
        retryThread = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread = null
    }

    private fun runAcceptLoop(s: LocalServerSocket) {
        while (true) {
            val client = try {
                s.accept()
            } catch (e: Exception) {
                Log.i(TAG, "accept loop terminated: ${e.message}")
                return
            }
            Log.d(TAG, "accepted client from proot extension")
            if (!inFlight.tryAdmit(MAX_CONCURRENT_CALLS)) {
                rejectBusy(client)
                continue
            }
            try {
                thread(name = OffloadInFlight.WORKER_THREAD_NAME, isDaemon = true) {
                    try {
                        handleClient(client)
                    } catch (e: Exception) {
                        Log.w(TAG, "worker error: ${e.message}", e)
                    } finally {
                        inFlight.release()
                        try { client.close() } catch (_: Exception) {}
                    }
                }
            } catch (t: Throwable) {
                // pthread_create failed (GH #453's OutOfMemoryError): refuse
                // this call and keep accepting rather than lose the server.
                inFlight.release()
                Log.w(TAG, "could not start an offload worker: $t")
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * [GH #453] Answer a call over [MAX_CONCURRENT_CALLS] on the accept thread:
     * read its request (proot sends it at once; 2 s read timeout), reply busy
     * with the commands currently holding the slots.
     */
    private fun rejectBusy(client: LocalSocket) {
        val busy = inFlight.summary()
        val now = System.currentTimeMillis()
        val last = lastBusyLogMs.get()
        if (now - last > 60_000 && lastBusyLogMs.compareAndSet(last, now)) {
            Log.w(TAG, "[GH453] refusing offload call: $MAX_CONCURRENT_CALLS calls in flight ($busy)")
        }
        try {
            client.soTimeout = 2_000
            val peerUid = try { client.peerCredentials.uid } catch (e: Exception) { -1 }
            if (!PeerCheck.allowed(peerUid, android.os.Process.myUid())) return
            val request = readRequest(DataInputStream(client.inputStream)) ?: return
            writeReply(
                DataOutputStream(client.outputStream),
                request.pid,
                NativeOffloadResult(
                    exitCode = EXIT_BUSY,
                    output = "native_offload: busy - $MAX_CONCURRENT_CALLS offload calls are still running ($busy). " +
                        "Try again when some finish.\n",
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "busy reply failed: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /** [GH #453] `command:count(oldest Ns)` for the calls in flight, for logs. */
    fun inFlightSummary(): String = inFlight.summary()

    /** [T-native-offload-peer-uid] First accepted peer uid, logged once. */
    @Volatile
    private var loggedPeerUid = false

    private fun handleClient(client: LocalSocket) {
        // [T-native-offload-peer-uid] Abstract sockets have no filesystem
        // permissions: any app on the device could connect and drive these
        // handlers (contacts, photos, model calls with the user's keys). The
        // only legitimate client is proot - it connects from the tracer
        // process itself, which always runs as this app's uid (`-0` fakes
        // root only inside the guest). Refuse every other uid.
        val peerUid = try { client.peerCredentials.uid } catch (e: Exception) { -1 }
        if (!PeerCheck.allowed(peerUid, android.os.Process.myUid())) {
            Log.w(TAG, "refused offload client uid=$peerUid (expected ${android.os.Process.myUid()})")
            return
        }
        if (!loggedPeerUid) {
            loggedPeerUid = true
            Log.i(TAG, "offload client uid=$peerUid accepted (app uid)")
        }
        val request = readRequest(DataInputStream(client.inputStream)) ?: return
        val output = DataOutputStream(client.outputStream)
        val pid = request.pid
        val argv = request.argv
        val env = request.env
        val cwd = request.cwd

        val name = argv.firstOrNull().orEmpty().substringAfterLast('/')
        Log.d(TAG, "recv pid=$pid name='$name' argc=${argv.size} argv=$argv cwd=$cwd envc=${env.size}")

        val t0 = System.nanoTime()
        val handler = handlers[name]
        // [GH #453] Name the thread after its command, so a thread count by
        // name (JankDiag, tombstone summary) says which command holds them.
        if (handler != null) {
            runCatching { Thread.currentThread().name = OffloadInFlight.workerThreadName(name) }
            inFlight.begin(name)
        }
        val result = try {
            if (handler == null) {
                Log.w(TAG, "no handler registered for '$name' (known=${handlers.keys})")
                NativeOffloadResult(exitCode = 127, output = "native_offload: no handler for '$name'\n")
            } else {
                try {
                    handler.handle(NativeOffloadRequest(
                        pid = pid,
                        argv = argv,
                        env = env,
                        cwd = cwd,
                        sessionId = env["MINIS_CHAT_SESSION_ID"]?.takeIf { it.isNotEmpty() },
                    ))
                } catch (e: Exception) {
                    Log.w(TAG, "handler '$name' threw: ${e.message}", e)
                    NativeOffloadResult(exitCode = 1, output = "native_offload: ${e.message}\n")
                }
            }
        } finally {
            if (handler != null) inFlight.end()
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        val tmpGuest = writeReply(output, pid, result)
        Log.d(TAG, "reply name='$name' exit=${result.exitCode} outBytes=${result.output.length} " +
            "tmpGuest=$tmpGuest elapsed=${elapsedMs}ms")
    }

    private class WireRequest(val pid: Int, val argv: List<String>, val env: Map<String, String>, val cwd: String)

    /** The request frame, or null (logged) for a bad magic / version. */
    private fun readRequest(input: DataInputStream): WireRequest? {
        val magic = input.readLEInt()
        if (magic != MAGIC_REQ) {
            Log.w(TAG, "bad magic: 0x${magic.toUInt().toString(16)}")
            return null
        }
        val version = input.readLEInt()
        if (version != VERSION) {
            Log.w(TAG, "unsupported version $version")
            return null
        }

        val pid = input.readLEInt()
        val argc = input.readLEInt()
        if (argc < 0 || argc > 256) throw IllegalStateException("bad argc=$argc")
        val argv = ArrayList<String>(argc)
        repeat(argc) { argv.add(input.readLEString()) }

        val envc = input.readLEInt()
        if (envc < 0 || envc > 4096) throw IllegalStateException("bad envc=$envc")
        val env = LinkedHashMap<String, String>(envc)
        repeat(envc) {
            val s = input.readLEString()
            val eq = s.indexOf('=')
            if (eq >= 0) env[s.substring(0, eq)] = s.substring(eq + 1) else env[s] = ""
        }
        val cwd = input.readLEString()
        return WireRequest(pid, argv, env, cwd)
    }

    /** Write [result]'s output to its reply file and send the response frame; returns the guest path. */
    private fun writeReply(output: DataOutputStream, pid: Int, result: NativeOffloadResult): String {
        val tmpDir = rootfsTmpDir ?: throw IllegalStateException("server not started")
        tmpDir.mkdirs()
        val seq = counter.incrementAndGet()
        val tmpHost = File(tmpDir, "$REPLY_PREFIX$pid-$seq")
        tmpHost.writeText(result.output)
        val tmpGuest = "/tmp/${tmpHost.name}"

        // [T-android-offload-tmp-leak] Bound growth WITHIN a long-running
        // process. The start-time sweep only catches files from previous
        // processes, but a single session can issue thousands of offload calls
        // (agent loops shelling out repeatedly), so without this the leak simply
        // moves from "across restarts" to "within one run". Age-gated, so the
        // file just written — and any other still awaiting its `cat` — is never
        // touched. Sampled rather than run per reply to keep the hot path cheap.
        if (seq % SWEEP_EVERY_N_REPLIES == 0L) sweepStaleReplies(all = false)

        output.writeLEInt(MAGIC_RSP)
        output.writeLEInt(result.exitCode)
        output.writeLEString(tmpGuest)
        output.flush()
        return tmpGuest
    }

    // ---- little-endian helpers ----

    private fun DataInputStream.readLEInt(): Int {
        val buf = ByteArray(4)
        readFully(buf)
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun DataInputStream.readLEString(): String {
        val len = readLEInt()
        if (len < 0 || len > 1 shl 20) throw IllegalStateException("bad string len $len")
        if (len == 0) return ""
        val buf = ByteArray(len)
        readFully(buf)
        return String(buf, Charsets.UTF_8)
    }

    private fun DataOutputStream.writeLEInt(v: Int) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        write(buf)
    }

    private fun DataOutputStream.writeLEString(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeLEInt(bytes.size)
        if (bytes.isNotEmpty()) write(bytes)
    }
}

// ─── Merged from PersistentShell.kt ──────────────────────────────────────────────────

/**
 * A long-running PRoot shell process that persists across commands.
 * Agent commands are sent via stdin and output is captured using unique
 * end-of-command markers, similar to iOS ISHKernel.executeCommandAndWait.
 *
 * This ensures that environment variables, working directory, installed
 * packages, and running services all persist between commands within the
 * same session.
 */
class PersistentShell(
    private val context: Context,
    private val sessionId: String,
    private val sessionBindMounts: Map<String, String>,  // linuxPath -> hostPath
) {

    companion object {
        private const val TAG = "PersistentShell"

        /**
         * [T-android-shell-marker-chunk-split] Bytes held back between reads
         * so a completion marker split across two `read()` calls is still
         * matched. Comfortably longer than the longest marker the wrapper can
         * emit (`__MINIS_PGID_<8>_<pgid>__` / `__MINIS_DONE_<8>_EXIT_<n>__`).
         */
        private const val MARKER_CARRY = 96

        /**
         * [T-android-shell-death-diagnosability] Death-capture windows. The
         * head must comfortably hold proot's error line(s) printed BEFORE the
         * multi-KB talloc leak dump; the tail shows how the output ended.
         */
        private const val OUTPUT_HEAD_MAX = 1024
        private const val OUTPUT_TAIL_MAX = 2048
    }

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stdinWriter: BufferedWriter? = null

    private val isStarting = AtomicBoolean(false)

    /** Pending command callback — only one command at a time. */
    @Volatile
    private var pendingCallback: CommandCallback? = null

    /**
     * [T-android-shell-marker-chunk-split] Tail of the previous read held back
     * so a marker split across two `read()` calls is still found. Only ever
     * touched by the single reader thread.
     */
    private var carryOver: String = ""

    /**
     * [T-android-shell-timeout-pgid] Whether the guest has `setsid`. Resolved
     * once per shell; when absent the wrapper is skipped and a timeout falls
     * back to recycling the shell without a targeted kill.
     */
    @Volatile
    private var setsidAvailable: Boolean = true

    /**
     * Drop our own control markers from text handed to the caller, so the
     * `__MINIS_PGID_…__` line the wrapper prints never reaches the agent or
     * the user as command output.
     */
    private fun stripControlMarkers(text: String, marker: String): String {
        val prefix = ForegroundCommandGroup.pgidMarkerPrefix(marker)
        if (!text.contains(prefix)) return text
        return text.lineSequence()
            .filterNot { it.contains(prefix) }
            .joinToString("\n")
    }

    val isAlive: Boolean
        get() = process?.isAlive == true

    /** [diag] Read back the mount this shell was started with (frozen at boot). */
    fun debugBindMount(linuxPath: String): String? = sessionBindMounts[linuxPath]

    private class CommandCallback(
        val marker: String,
        val output: StringBuilder = StringBuilder(),
        val lineCallback: ((String) -> Unit)?,
        var onComplete: ((String, Int) -> Unit)? = null,
        /**
         * [T-android-shell-timeout-pgid] Process group of this command's
         * `setsid` wrapper, learned from the `__MINIS_PGID_…__` line the
         * wrapper prints before the command runs. Null until it arrives (or
         * forever, if setsid is unavailable) — and null must mean "do not
         * kill", never "kill something else".
         */
        @Volatile var pgid: Int? = null,
    )

    /**
     * Ensure the persistent shell process is running.
     * Idempotent — returns immediately if already alive.
     */
    suspend fun ensureStarted() {
        if (isAlive) return
        if (!isStarting.compareAndSet(false, true)) {
            // Another coroutine is already starting — wait for it
            while (isStarting.get() && !isAlive) {
                kotlinx.coroutines.delay(50)
            }
            return
        }

        try {
            withContext(Dispatchers.IO) {
                // [T-android-seccomp-selfheal / GH#186] The guest /bin/sh is
                // itself a dynamically linked binary, so on an affected kernel
                // it can die during dynamic linking before it ever reads a
                // command — the shell simply never comes up, which surfaces to
                // the user as "[Shell not running] (exit code: -1)". Give it
                // one retry with PROOT_NO_SECCOMP=1 under the same narrow
                // conditions used for one-shot commands.
                //
                // Timed around startProcess() (which sleeps 200ms before
                // returning): if the shell is already dead when it returns, it
                // died during that window, i.e. essentially instantly.
                val spawnedAt = System.currentTimeMillis()
                startProcess()
                val aliveMs = System.currentTimeMillis() - spawnedAt

                if (!useNoSeccomp && !isAlive) {
                    val exit = runCatching { process?.exitValue() }.getOrNull() ?: lastExitCode
                    if (SeccompFallbackPolicy.shouldRetryWithoutSeccomp(
                            exitCode = exit,
                            durationMs = aliveMs,
                            producedOutput = false,
                            alreadyRetried = false,
                        )
                    ) {
                        com.openminis.app.logging.AppLogger.warning(
                            TAG,
                            SeccompFallbackPolicy.retryLogLine(exit, aliveMs, "persistent shell startup"),
                        )
                        // Sticky for this shell's lifetime: every later respawn
                        // keeps the workaround instead of rediscovering it.
                        useNoSeccomp = true
                        startProcess()
                        if (isAlive) {
                            com.openminis.app.logging.AppLogger.warning(
                                TAG,
                                "[proot-retry] persistent shell came up with " +
                                    "${SeccompFallbackPolicy.NO_SECCOMP_ENV}=1 — this device " +
                                    "needs the seccomp workaround (GH#186)",
                            )
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            // [T-android-shell-spawn-outcome] Previously swallowed: the only
            // handler here was `finally { isStarting.set(false) }`, so a throw
            // out of startProcess() (ProcessBuilder.start() IOException —
            // ENOENT on the proot binary, EACCES, EAGAIN under memory
            // pressure) unwound with NOTHING written anywhere. The caller then
            // saw isAlive == false and reported the generic "[Shell not
            // running]", which is exactly the dead end the field reports keep
            // hitting. Record it and rethrow — swallowing the cause is what
            // made this class of failure undiagnosable.
            com.openminis.app.logging.AppLogger.error(
                TAG,
                "spawn threw: ${t.javaClass.simpleName}: ${t.message}",
            )
            throw t
        } finally {
            isStarting.set(false)
        }
        // [T-android-env-first-turn] A shell that just came up has an empty
        // environment, whether this was the first spawn or a respawn after a
        // death. Replay whatever snapshot the coordinator last handed us, so
        // the env cannot be lost by a process swap the caller never sees.
        // Outside the finally: only worth doing when we actually started.
        replayEnvironmentIfAny()
    }

    /**
     * [T-android-seccomp-selfheal / GH#186] Once the seccomp workaround proves
     * necessary on this device, keep it for every subsequent respawn of this
     * shell. Without stickiness the shell would crash-and-retry on every
     * restart, doubling startup latency for exactly the users already hitting
     * the bug.
     */
    @Volatile
    private var useNoSeccomp = false

    private fun startProcess() {
        Log.i(TAG, "Starting persistent shell process")

        val rootfsManager = RootfsManager.getInstance(context)

        // [T-android-shell-death-diagnosability] One-line spawn context in the
        // FILE log. Field reports of "[Shell not running] (exit code: -1)" have
        // had two distinct causes already (missing proot ELF loaders after
        // bc2566b2, and at least one custom-ROM death we couldn't classify),
        // and a user log that only says "process exited" cannot tell them
        // apart. Loader presence is the first thing to rule out: without
        // PROOT_LOADER, proot bare-execve's rootfs busybox and SELinux kills
        // it on Android 10+ (see a25d93f7).
        com.openminis.app.logging.AppLogger.info(
            TAG,
            "spawn ctx: proot=${rootfsManager.prootBinary.exists()} " +
                "rootfs=${File(rootfsManager.rootfsDir, "bin/busybox").exists()} " +
                "loader64=${PRootKernel.prootLoaderPath.isNotEmpty()} " +
                "loader32=${PRootKernel.prootLoader32Path.isNotEmpty()}",
        )
        // Fresh capture per process incarnation — a respawned shell must not
        // report its predecessor's dying output as its own.
        synchronized(outputTail) {
            outputHead.setLength(0)
            outputTail.setLength(0)
            outputTotal = 0
        }
        lastExitCode = null

        val cmd = mutableListOf<String>()
        cmd.add(rootfsManager.prootBinary.absolutePath)
        cmd.add("-0")
        // T141: see PRootKernel.buildProotCommand for rationale — translates
        // hardlinks to symlinks so apk install of binutils/gcc works.
        cmd.add("--link2symlink")
        cmd.add("-r")
        cmd.add(rootfsManager.rootfsDir.absolutePath)
        cmd.add("-b"); cmd.add("/dev")
        cmd.add("-b"); cmd.add("/proc")
        cmd.add("-b"); cmd.add("/sys")
        cmd.add("-w"); cmd.add("/root")

        // Apply this session's bind mounts (session-specific, not global)
        for ((linuxPath, hostPath) in sessionBindMounts) {
            cmd.add("-b")
            cmd.add("$hostPath:$linuxPath")
        }

        val handlers = NativeOffloadServer.registeredHandlers
        // [T-native-offload-bind] Only when our server is listening: otherwise
        // the handler stubs run and report the CLI unavailable, instead of
        // proot connecting to a name another process holds (and hanging).
        if (handlers.isNotEmpty() && NativeOffloadServer.awaitListening()) {
            cmd.add("--native-offload=${NativeOffloadServer.socketName}:${handlers.joinToString(",")}")
        }
        // [FEAT-261021-11] Shell transparent forwarding (see PRootKernel).
        cmd.addAll(com.openminis.app.network.ShellTransparentNet.prootArgs(rootfsManager.prootBinary))

        cmd.add("/bin/sh")

        val debugOffload = com.openminis.app.BuildConfig.DEV_TOOLS

        val processBuilder = ProcessBuilder(cmd)
        // In debug builds we want proot's native_offload stderr logs in
        // logcat, not merged into shell stdout (which would break the
        // __MINIS_DONE__ marker detection). Release keeps the original
        // merged behavior so no stderr output is lost silently.
        processBuilder.redirectErrorStream(!debugOffload)

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
        env["TERM"] = "dumb"
        env["PS1"] = ""  // Suppress prompt to avoid polluting output
        // Timezone: customEnvironment["TZ"] is seeded at PRootKernel.boot(),
        // but refresh it here in case the system timezone changed between boot
        // and now.
        env["TZ"] = PRootKernel.posixTz()
        if (debugOffload) env["MINIS_NOFF_DEBUG"] = "1"
        // T340: forward the chat session id to native_offload handlers via
        // proot env. NativeOffloadServer reads this off `request.env` and
        // hands it to OffloadPermissionManager so ASK_ONCE grants/denials
        // are scoped per chat session, not globally.
        env["MINIS_CHAT_SESSION_ID"] = sessionId

        for ((key, value) in PRootKernel.customEnvironment) {
            env[key] = value
        }
        // [T-net-android-phase3] Settings > Network "Shell" route: proxy
        // variables for the local entry (empty = direct, unchanged). Before
        // the caller's / user's own variables, which may override them.
        env.putAll(com.openminis.app.network.NetworkShellEnvironment.proxyVariables())

        // [T-android-seccomp-selfheal / GH#186] Applied last so nothing above
        // can clobber it once this device is known to need it.
        if (useNoSeccomp) {
            env[SeccompFallbackPolicy.NO_SECCOMP_ENV] = SeccompFallbackPolicy.NO_SECCOMP_VALUE
        }

        // [T-shell-stall-yes-head] SIGPIPE unblocked in the child; see SpawnSignalMask.
        val p = SpawnSignalMask.start(processBuilder)
        process = p
        stdinWriter = BufferedWriter(OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8))

        // Start background reader thread
        Thread({
            readLoop(p)
        }, "PersistentShell-reader").apply {
            isDaemon = true
            start()
        }

        // In debug, drain stderr separately into logcat.
        if (debugOffload) {
            Thread({
                val br = p.errorStream.bufferedReader(StandardCharsets.UTF_8)
                try {
                    for (line in br.lineSequence()) Log.d("PRootStderr", line)
                } catch (_: Exception) {}
            }, "PersistentShell-stderr").apply {
                isDaemon = true
                start()
            }
        }

        // Wait briefly for shell to initialize
        try {
            Thread.sleep(200)
        } catch (_: InterruptedException) {}

        // [T-android-shell-spawn-outcome] Record the OUTCOME of the spawn, not
        // just that one was attempted. "Starting persistent shell process"
        // followed by silence is the signature every field report has, and it
        // cannot distinguish "proot is running fine" from "proot exited during
        // the 200ms window" — the two cases that lead to completely different
        // investigations. Logged to the FILE log (AppLogger, not Log.i) because
        // that is what a user can actually send us; logcat is gone by then.
        val alive = isAlive
        val exit = runCatching { process?.exitValue() }.getOrNull()
        val early = synchronized(outputTail) { outputHead.toString().take(300) }
        com.openminis.app.logging.AppLogger.info(
            TAG,
            "spawn outcome: alive=$alive" +
                (if (exit != null) " exit=$exit" else "") +
                " loader=${PRootKernel.prootLoaderPath.isNotEmpty()}" +
                " noSeccomp=$useNoSeccomp" +
                (if (early.isNotEmpty()) " early=${early.replace('\n', '|')}" else ""),
        )
        Log.i(TAG, "Persistent shell started")
    }

    /**
     * [T-android-shell-death-diagnosability] Captured shell output for
     * premature-death reporting, including chunks that arrive with no pending
     * callback — which readLoop previously discarded outright. In release
     * builds stderr is merged into stdout (redirectErrorStream), so proot's
     * dying words land exactly in that discarded window.
     *
     * HEAD + rolling TAIL, not tail-only. The first crDroid field log proved
     * tail-only insufficient: proot prints its one-line cause FIRST ("proot
     * error: …") and then talloc_enable_leak_report() dumps a multi-KB
     * allocation tree at exit — which evicted the cause and left us staring at
     * HandlerEntry leak rows. The head is where the answer lives; the tail
     * still shows how it ended.
     */
    private val outputHead = StringBuilder()
    private val outputTail = StringBuilder()
    private var outputTotal = 0

    private fun appendTail(text: String) {
        synchronized(outputTail) {
            outputTotal += text.length
            if (outputHead.length < OUTPUT_HEAD_MAX) {
                outputHead.append(text.take(OUTPUT_HEAD_MAX - outputHead.length))
            }
            outputTail.append(text)
            val over = outputTail.length - OUTPUT_TAIL_MAX
            if (over > 0) outputTail.delete(0, over)
        }
    }

    /**
     * Captured output (trimmed) for premature-death reporting: the head (where
     * proot's error line lives), plus the tail when output outgrew the head
     * window, with the elided middle marked.
     */
    fun deathTail(): String = synchronized(outputTail) {
        val head = outputHead.toString().trim()
        if (outputTotal <= OUTPUT_HEAD_MAX) return head // head holds everything
        // Tail buffer starts at byte (outputTotal - tail.length); the head
        // covers [0, OUTPUT_HEAD_MAX). Drop any overlap from the tail so the
        // two windows concatenate without duplication.
        val overlap = OUTPUT_HEAD_MAX - (outputTotal - outputTail.length)
        val tail = outputTail.substring(overlap.coerceIn(0, outputTail.length)).trim()
        if (tail.isEmpty()) return head
        val elided = (-overlap).coerceAtLeast(0)
        val sep = if (elided > 0) "\n…[$elided bytes elided]…\n" else "\n"
        "$head$sep$tail"
    }

    /** Exit code of the dead shell process, when known. */
    @Volatile
    var lastExitCode: Int? = null
        private set

    private fun readLoop(p: Process) {
        try {
            val buffer = ByteArray(4096)
            val stream = p.inputStream
            // [T-android-pshell-utf8-split] A read boundary can fall inside a
            // multi-byte character; decoding each read on its own turned both
            // halves into U+FFFD. The decoder keeps the unfinished bytes for
            // the next read. Markers are matched on the decoded text below and
            // are ASCII, so this does not change how they are found.
            val decoder = Utf8StreamDecoder()
            while (true) {
                val n = stream.read(buffer)
                // End of stream: what is left of a cut character goes through
                // the same path as any other text, once.
                val text = if (n < 0) decoder.finish() else decoder.decode(buffer, 0, n)
                if (text.isEmpty()) {
                    if (n < 0) break
                    continue
                }
                appendTail(text)

                val cb = pendingCallback
                if (cb != null) {
                    // [T-android-shell-marker-chunk-split] Match against the
                    // carry-over tail PLUS this chunk, not the chunk alone.
                    // `read()` returns arbitrary boundaries, so a completion
                    // marker straddling two reads used to be missed entirely
                    // and the command hung until its timeout — a second,
                    // independent source of the #358 symptom, and one that
                    // high-output commands hit most often.
                    val scan = if (carryOver.isEmpty()) text else carryOver + text
                    val alreadyEmitted = carryOver.length

                    // Learn the process group as soon as the wrapper reports
                    // it, so a timeout has something precise to kill.
                    if (cb.pgid == null) {
                        ForegroundCommandGroup.parsePgid(scan, cb.marker)?.let { cb.pgid = it }
                    }

                    val markerExitPattern = "__MINIS_DONE_${cb.marker}_EXIT_"
                    val markerIdx = scan.indexOf(markerExitPattern)

                    if (markerIdx >= 0) {
                        carryOver = ""
                        // Everything before the marker that has not already
                        // been handed to the caller.
                        val beforeMarker = scan.substring(alreadyEmitted.coerceAtMost(markerIdx), markerIdx)
                        val visible = stripControlMarkers(beforeMarker, cb.marker)
                        cb.output.append(visible)
                        if (cb.lineCallback != null) {
                            feedLines(visible, cb.lineCallback)
                        }

                        // Extract exit code from marker line
                        val afterMarker = scan.substring(markerIdx)
                        val exitCode = parseExitCode(afterMarker, cb.marker)

                        // Signal completion
                        cb.onComplete?.invoke(cb.output.toString(), exitCode)
                        pendingCallback = null
                    } else {
                        // Emit only what is new, and hold back a tail long
                        // enough to contain a split marker.
                        val emitEnd = (scan.length - MARKER_CARRY).coerceAtLeast(alreadyEmitted)
                        val fresh = scan.substring(alreadyEmitted, emitEnd)
                        carryOver = scan.substring(emitEnd)
                        if (fresh.isNotEmpty()) {
                            val visible = stripControlMarkers(fresh, cb.marker)
                            cb.output.append(visible)
                            if (cb.lineCallback != null) {
                                feedLines(visible, cb.lineCallback)
                            }
                        }
                    }
                } else {
                    carryOver = ""
                }
                // If no pending callback, discard (shell prompt noise etc.)
                if (n < 0) break
            }
        } catch (e: Exception) {
            Log.d(TAG, "Reader loop ended: ${e.message}")
        }

        // Process exited
        val cb = pendingCallback
        if (cb != null) {
            cb.onComplete?.invoke(cb.output.toString(), -1)
            pendingCallback = null
        }

        // [T-android-shell-death-diagnosability] Name the cause in the FILE
        // log. exitValue distinguishes death classes (signal deaths are
        // 128+n: 132=SIGILL, 139=SIGSEGV, 159=SIGSYS/seccomp), and the tail
        // carries proot's own error line in release builds. Without these,
        // a field log reads "started → exited" 25ms apart and is
        // unactionable — precisely the shape of the crDroid report.
        val exit = runCatching { p.waitFor() }.getOrNull()
        lastExitCode = exit
        val tail = deathTail()
        com.openminis.app.logging.AppLogger.error(
            TAG,
            "shell process exited code=$exit" +
                (if (tail.isNotEmpty()) " capture=${tail.take(1200)}" else " capture=(no output)"),
        )

        process = null
        stdinWriter = null
        Log.i(TAG, "Persistent shell process exited")
    }

    private fun feedLines(text: String, callback: (String) -> Unit) {
        val lines = text.split('\n')
        for (i in lines.indices) {
            val line = lines[i].replace("\r", "")
            if (line.isNotEmpty() && (i < lines.size - 1 || text.endsWith('\n'))) {
                callback(line)
            } else if (line.isNotEmpty() && i == lines.size - 1) {
                // Partial line — still feed it for real-time updates
                callback(line)
            }
        }
    }

    private fun parseExitCode(text: String, marker: String): Int {
        // Pattern: __MINIS_DONE_{marker}_EXIT_{code}__
        val regex = Regex("__MINIS_DONE_${Regex.escape(marker)}_EXIT_(\\d+)__")
        val match = regex.find(text)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    /**
     * Execute a command in the persistent shell and wait for completion.
     *
     * Wraps the command with a unique marker to detect output boundaries:
     *   {command}; echo "__MINIS_DONE_{marker}_EXIT_$?__"
     *
     * @return Pair of (output, exitCode)
     */
    suspend fun executeCommand(
        command: String,
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null,
    ): Pair<String, Int> {
        ensureStarted()

        val writer = stdinWriter
        if (writer == null || !isAlive) {
            // [T-android-shell-death-diagnosability] Surface WHY the shell is
            // down, not just that it is. The exit code and proot's own last
            // words (captured by outputTail; stderr is merged into stdout in
            // release) turn "[Shell not running]" from a dead end into a
            // self-describing report — both for the agent reading the tool
            // result and for the screenshot a user sends us.
            val exit = lastExitCode
            val tail = deathTail()
            val detail = buildString {
                append("[Shell not running]")
                if (exit != null) append(" proot exit=$exit")
                if (tail.isNotEmpty()) append("\n${tail.take(300)}")
            }
            return Pair(detail, -1)
        }

        val marker = UUID.randomUUID().toString().take(8)
        // [T-android-shell-timeout-pgid] Run the command in its own process
        // group so a timeout has something precise to kill. See
        // ForegroundCommandGroup for why this is a pgid and not a search for
        // "&" in the command text.
        val wrappedCommand = ForegroundCommandGroup.wrap(command, marker, setsidAvailable)

        return withContext(Dispatchers.IO) {
            val result = withTimeoutOrNull(timeout) {
                suspendCancellableCoroutine { cont ->
                    val cb = CommandCallback(
                        marker = marker,
                        lineCallback = lineCallback,
                    )
                    cb.onComplete = { output, exitCode ->
                        if (cont.isActive) {
                            cont.resume(Pair(output, exitCode))
                        }
                    }
                    pendingCallback = cb

                    cont.invokeOnCancellation {
                        pendingCallback = null
                    }

                    try {
                        writer.write(wrappedCommand)
                        writer.flush()
                    } catch (e: Exception) {
                        pendingCallback = null
                        if (cont.isActive) {
                            cont.resume(Pair("[Write error: ${e.message}]", -1))
                        }
                    }
                }
            }

            if (result == null) {
                // [T-android-shell-timeout-pgid] Issue #358. The old code
                // here did nothing but drop the callback — "cancel pending,
                // but don't kill the shell" — and the runaway kept running in
                // this REUSED shell, still writing to stdout. /bin/sh reads
                // stdin serially, so every later command in the session just
                // queued behind it and burned its own timeout, `echo alive`
                // included. That is the reported permanent wedge.
                val cb = pendingCallback
                pendingCallback = null

                // Whatever the command managed to produce before the deadline
                // is worth keeping — it usually says where it got stuck.
                // Mirrors iOS T-ish-shell-timeout-preserve-output.
                val partial = cb?.output?.toString().orEmpty()

                // Kill the command's OWN process group. A `&` job started by
                // the command shares that group and goes with it, which is
                // correct: it belongs to the command the user is stopping.
                // Work meant to outlive the command detaches into its own
                // session (setsid/nohup) and is untouched — verified on
                // device, see ForegroundCommandGroup.
                cb?.pgid?.let { pgid ->
                    runCatching {
                        stdinWriter?.let { w ->
                            w.write(ForegroundCommandGroup.killGroupCommand(pgid))
                            w.flush()
                        }
                    }.onFailure {
                        Log.w(TAG, "timeout kill for pgid=$pgid failed: ${it.message}")
                    }
                    Log.i(TAG, "[timeout] killed foreground process group $pgid")
                }

                // Recycle regardless of whether the kill landed: the shell's
                // stdout may still hold bytes the runaway wrote before dying,
                // and those would be misattributed to whichever command reads
                // next. ensureStarted() replays the environment snapshot, so
                // nothing the user configured is lost.
                if (!ForegroundCommandGroup.shellIsReusableAfterTimeout()) {
                    runCatching { stop() }
                        .onFailure { Log.w(TAG, "timeout recycle failed: ${it.message}") }
                }

                val notice = "[Command timed out after ${timeout / 1000}s]"
                val text = if (partial.isBlank()) notice else "$partial\n$notice"
                Pair(text, 124)
            } else {
                result
            }
        }
    }

    /**
     * Apply environment variables to the running shell.
     *
     * The shell is long-lived and reused across commands, so a stale `export`
     * from a previous turn lingers until something overwrites it. Caller can
     * supply [previousKeys] — the set of variable names injected on the prior
     * call — so any name absent from the new [envVars] is `unset` first. That
     * gives whole-snapshot semantics matching the per-command isolation iOS
     * gets for free with one `/bin/sh` process per command.
     *
     * Empty default for [previousKeys] preserves the original behaviour for
     * system broadcasts (TZ, proxy) that are only meant to overlay specific
     * keys, never wipe the user's env-var snapshot.
     */
    suspend fun applyEnvironment(
        envVars: Map<String, String>,
        previousKeys: Set<String> = emptySet(),
    ) {
        // [T-android-env-first-turn] Remember the snapshot BEFORE the liveness
        // check, and remember it even if the write below fails.
        //
        // This is the first-turn bug. The old code opened with
        // `if (!isAlive) return` and told the caller nothing, so on a cold
        // start the sequence was:
        //
        //   1. the lease spawns shell #1 while PROOT_LOADER is still unset,
        //      and SELinux kills it (`execve("/bin/sh"): Permission denied`);
        //   2. the coordinator exports the user's env into that corpse — this
        //      method returns silently, and the coordinator nonetheless records
        //      the keys in lastInjectedKeys as though they had landed;
        //   3. executeCommand() calls ensureStarted(), which quietly spawns
        //      shell #2 — a brand new process that never saw an `export`.
        //
        // The command then ran with no env at all, which is exactly the
        // reported "first tool call has no environment variables, retrying
        // works". Retrying worked because by then a live shell existed and
        // step 2 actually wrote to it.
        //
        // Holding the snapshot on the shell lets startProcess() replay it into
        // whatever process comes up next, so a respawn can no longer silently
        // drop the user's environment.
        pendingEnv = envVars
        pendingEnvPreviousKeys = previousKeys
        if (!isAlive) return
        writeEnvironment(envVars, previousKeys)
    }

    /**
     * [T-android-env-first-turn] The snapshot last handed to
     * [applyEnvironment], replayed by [startProcess] into every subsequent
     * shell incarnation.
     */
    private var pendingEnv: Map<String, String> = emptyMap()
    private var pendingEnvPreviousKeys: Set<String> = emptySet()

    /** Actually write the exports. Assumes the shell is up. */
    private suspend fun writeEnvironment(
        envVars: Map<String, String>,
        previousKeys: Set<String>,
    ) {
        val writer = stdinWriter ?: return
        withContext(Dispatchers.IO) {
            try {
                for (key in previousKeys - envVars.keys) {
                    writer.write("unset $key\n")
                }
                for ((key, value) in envVars) {
                    // Escape single quotes in values
                    val escaped = value.replace("'", "'\\''")
                    writer.write("export $key='$escaped'\n")
                }
                writer.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply env vars: ${e.message}")
            }
        }
    }

    /**
     * [T-android-env-first-turn] Re-export the remembered snapshot into a
     * freshly spawned process. A respawn is a NEW `/bin/sh` with an empty
     * environment, so without this the exports written to the previous
     * incarnation are simply gone.
     *
     * `previousKeys` is deliberately empty: the new process has nothing to
     * unset, and replaying the old unset list would be a no-op at best.
     */
    private suspend fun replayEnvironmentIfAny() {
        if (pendingEnv.isEmpty()) return
        if (!isAlive) return
        writeEnvironment(pendingEnv, previousKeys = emptySet())
    }

    /**
     * [T-stop-choice] "Stop Current Tool Only": kill the command running now
     * and nothing else - the shell stays, and so does any work detached with
     * setsid/nohup (it left this command's group; see ForegroundCommandGroup).
     *
     * The signal goes from the host: proot does not virtualise pids, so the
     * group id the wrapper reported is the host's. Writing a kill into stdin,
     * as the timeout path does, cannot work here because `/bin/sh` reads its
     * next line only after the foreground command returns. Once the group is
     * dead the shell prints the done marker as usual and the call returns with
     * whatever the command had written.
     *
     * Without a group id (no setsid in the guest, or the wrapper has not
     * reported yet) this one shell is recycled instead.
     *
     * @return whether a command was running.
     */
    fun interruptForeground(): Boolean {
        val cb = pendingCallback ?: return false
        val pgid = cb.pgid
        if (pgid != null && pgid > 1) {
            val sent = runCatching {
                android.system.Os.kill(-pgid, android.system.OsConstants.SIGKILL)
            }.onFailure { Log.w(TAG, "interrupt: kill -$pgid failed: ${it.message}") }.isSuccess
            if (sent) {
                Log.i(TAG, "[stop-tool] killed foreground process group $pgid")
                return true
            }
        }
        Log.i(TAG, "[stop-tool] no process group to kill (pgid=$pgid) - recycling this shell")
        stop()
        return true
    }

    /**
     * Stop the persistent shell.
     */
    fun stop() {
        try { stdinWriter?.close() } catch (_: Exception) {}
        stdinWriter = null
        process?.destroyForcibly()
        process = null
        pendingCallback?.let {
            it.onComplete?.invoke(it.output.toString(), -1)
        }
        pendingCallback = null
        Log.i(TAG, "Persistent shell stopped")
    }
}

// ─── Merged from TerminalSession.kt ──────────────────────────────────────────────────

/**
 * Interactive PTY shell session — mirrors iOS ISHKernel + TerminalSession.
 *
 * Uses [PtyBridge.forkExec] to spawn `proot /bin/sh -l -i` attached to a real
 * PTY (via bionic forkpty). The shell sees `isatty(0)==true`, emits its PS1
 * prompt, honours termios echo/canonical/ISIG modes, and responds to signals,
 * arrow keys, Tab completion, and interactive programs (vi, top, gh auth login)
 * identically to iSH on iOS.
 *
 * Output is delivered as raw bytes through [outputBytes]; a TerminalEmulator
 * should consume these and parse ANSI sequences.
 */
class TerminalSession(private val context: Context) {

    companion object {
        /**
         * [T-envvar-disable] A terminal's environment: the kernel's own
         * variables, then the user's ENABLED variables ([user] comes from
         * EnvVarRepository.allAsDict()). Pure, and it never writes back into
         * [system], so turning every variable off leaves a new terminal with
         * none of them.
         */
        internal fun terminalEnvironment(system: Map<String, String>, user: Map<String, String>): Map<String, String> =
            LinkedHashMap(system).apply { putAll(user) }

        private const val TAG = "TerminalSession"
        private const val READ_BUFFER_SIZE = 8192
        /** Max bytes per PTY write; larger pastes are chunked so nothing is dropped. */
        private const val CHUNK_SIZE = 2048
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24

        /**
         * Weak-reference registry of live sessions so the Application can push
         * environment updates (e.g. ACTION_TIMEZONE_CHANGED) into already-running
         * interactive shells. Stale weak refs are cleaned on each iteration.
         */
        private val liveSessions = CopyOnWriteArrayList<WeakReference<TerminalSession>>()

        /** Broadcast `export TZ=<value>` to every live interactive shell. */
        fun broadcastTimezone(tz: String) {
            val dead = mutableListOf<WeakReference<TerminalSession>>()
            for (ref in liveSessions) {
                val s = ref.get()
                if (s == null) { dead.add(ref); continue }
                if (s.isRunning) s.applyTimezone(tz)
            }
            liveSessions.removeAll(dead)
        }

        /**
         * Broadcast the HTTP-proxy env block to every live interactive shell.
         * Sends `export KEY=value` for each pair — empty values clear a
         * previously-set proxy in one round trip.
         */
        fun broadcastProxy(env: Map<String, String>) {
            val dead = mutableListOf<WeakReference<TerminalSession>>()
            for (ref in liveSessions) {
                val s = ref.get()
                if (s == null) { dead.add(ref); continue }
                if (s.isRunning) s.applyEnvMap(env)
            }
            liveSessions.removeAll(dead)
        }
    }

    enum class State { IDLE, BOOTING, RUNNING, STOPPED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Raw PTY output bytes (not sanitized — feed directly into TerminalEmulator). */
    private val _outputBytes = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val outputBytes: SharedFlow<ByteArray> = _outputBytes.asSharedFlow()

    /** Counter bumped on every reset() / clearOutput() — lets UI wipe emulator state. */
    private val _clearVersion = MutableStateFlow(0)
    val clearVersion: StateFlow<Int> = _clearVersion.asStateFlow()

    private var masterFd: Int = -1
    private var childPid: Int = 0
    private var readerJob: Job? = null

    /**
     * [T-android-seccomp-selfheal / GH#186] Set once the interactive shell has
     * been restarted with PROOT_NO_SECCOMP=1; also the one-shot guard that
     * stops a crash-restart loop if the retry dies the same way.
     */
    @Volatile
    private var useNoSeccomp = false

    /**
     * Whether this PTY ever emitted output. A terminal that printed something
     * has begun a real session, so a later crash is the guest program's, not
     * the loader's — and restarting would wipe visible scrollback.
     */
    @Volatile
    private var sawOutput = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var cols: Int = DEFAULT_COLS
    @Volatile private var rows: Int = DEFAULT_ROWS

    val isRunning: Boolean get() = _state.value == State.RUNNING

    /**
     * Start the PTY-backed shell. Call [setWindowSize] first if you know the
     * target geometry — otherwise boots at 80×24 and expects a resize callback.
     */
    fun start(sessionId: String? = null, initialCols: Int = DEFAULT_COLS, initialRows: Int = DEFAULT_ROWS) {
        if (_state.value == State.RUNNING) return
        _state.value = State.BOOTING
        cols = initialCols
        rows = initialRows
        // Fresh per PTY incarnation, so a retry is judged on its own output
        // rather than inheriting the dead attempt's.
        sawOutput = false

        scope.launch {
            try {
                PRootKernel.boot(context)

                // [T-envvar-disable] The user's variables used to be putAll()-ed
                // into the process-wide PRootKernel.customEnvironment here. That
                // map is never cleared, and every shell copies it (FreshProcessShell,
                // PersistentShell, ShellExecutor), so once a terminal had been
                // opened a deleted - or disabled - variable kept reaching every
                // later command until the app restarted. They now go only into
                // this terminal's own environment, below.

                val cmdList = buildInteractiveCommand(sessionId)
                val cmd = cmdList.first()
                val argv = cmdList.toTypedArray()

                // Environment in KEY=VALUE form
                val envMap = LinkedHashMap<String, String>()
                envMap["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
                if (PRootKernel.nativeLibDir.isNotEmpty())
                    envMap["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
                if (PRootKernel.prootLoaderPath.isNotEmpty())
                    envMap["PROOT_LOADER"] = PRootKernel.prootLoaderPath
                if (PRootKernel.prootLoader32Path.isNotEmpty())
                    envMap["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
                envMap["TERM"] = "xterm-256color"
                envMap["LANG"] = "C.UTF-8"
                envMap["LC_ALL"] = "C.UTF-8"
                // Timezone: PRootKernel.boot() already seeded customEnvironment["TZ"],
                // but set a fresh value here too in case the kernel was booted earlier
                // and the system timezone changed since.
                envMap["TZ"] = PRootKernel.posixTz()
                // [T-net-android-phase3] Settings > Network "Shell" route (empty
                // = direct, unchanged); the user's own variables below win.
                envMap.putAll(com.openminis.app.network.NetworkShellEnvironment.proxyVariables())
                envMap.putAll(
                    terminalEnvironment(
                        PRootKernel.customEnvironment,
                        ExecutionCoordinator.envVarRepository?.allAsDict().orEmpty(),
                    )
                )

                // [T-android-seccomp-selfheal / GH#186] Applied last so nothing
                // above can clobber it once this device is known to need it.
                if (useNoSeccomp) {
                    envMap[SeccompFallbackPolicy.NO_SECCOMP_ENV] = SeccompFallbackPolicy.NO_SECCOMP_VALUE
                }

                val envp = envMap.map { (k, v) -> "$k=$v" }.toTypedArray()
                val cwd = File(cmd).parentFile?.absolutePath

                val outPid = IntArray(1)
                val fd = PtyBridge.forkExec(cmd, argv, envp, cwd, cols, rows, outPid)
                if (fd < 0) {
                    Log.e(TAG, "forkExec failed: errno=${-fd}")
                    _outputBytes.emit("Failed to start PTY: errno=${-fd}\r\n".toByteArray())
                    _state.value = State.STOPPED
                    return@launch
                }
                masterFd = fd
                childPid = outPid[0]
                _state.value = State.RUNNING
                liveSessions.add(WeakReference(this@TerminalSession))
                Log.i(TAG, "PTY started: fd=$fd pid=$childPid cols=$cols rows=$rows")

                if (sessionId != null) {
                    kotlinx.coroutines.delay(300)
                    writeInput("cd /var/minis && clear\n".toByteArray())
                }
                // [T-android-session-private-mounts] Say which files this
                // terminal sees. Written to the screen, not typed into the
                // shell, so it never lands in history. Delayed past the
                // `clear` above so it is not wiped.
                scope.launch {
                    kotlinx.coroutines.delay(if (sessionId != null) 600 else 300)
                    _outputBytes.emit(banner(sessionId).toByteArray())
                }

                readerJob = scope.launch { readLoop() }

                // Wait for child exit in a sibling coroutine.
                val spawnedAt = System.currentTimeMillis()
                scope.launch {
                    val status = PtyBridge.waitFor(childPid)
                    Log.i(TAG, "Child exited: status=$status")

                    // [T-android-seccomp-selfheal / GH#186] PtyBridge reports a
                    // signal death as -(128+signal) (pty_bridge.c:234), so
                    // negate before consulting the policy. If the interactive
                    // shell died on an early fatal signal, restart it once with
                    // PROOT_NO_SECCOMP=1 rather than leaving the user staring
                    // at a dead terminal they cannot fix from the UI.
                    val aliveMs = System.currentTimeMillis() - spawnedAt
                    if (SeccompFallbackPolicy.shouldRetryWithoutSeccomp(
                            exitCode = if (status < 0) -status else null,
                            durationMs = aliveMs,
                            producedOutput = sawOutput,
                            alreadyRetried = useNoSeccomp,
                        )
                    ) {
                        com.openminis.app.logging.AppLogger.warning(
                            TAG,
                            SeccompFallbackPolicy.retryLogLine(-status, aliveMs, "interactive terminal"),
                        )
                        useNoSeccomp = true
                        masterFd = -1
                        childPid = 0
                        readerJob?.cancel()
                        readerJob = null
                        start(sessionId, cols, rows)
                        return@launch
                    }

                    _outputBytes.emit("\r\n[Process exited: $status]\r\n".toByteArray())
                    _state.value = State.STOPPED
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start PTY session", t)
                _outputBytes.emit("Error: ${t.message}\r\n".toByteArray())
                _state.value = State.STOPPED
            }
        }
    }

    private suspend fun readLoop() {
        val fd = masterFd
        val buf = ByteArray(READ_BUFFER_SIZE)
        while (_state.value == State.RUNNING) {
            val n = PtyBridge.readBytes(fd, buf, 0, buf.size)
            if (n <= 0) {
                // EOF or error → child exited / fd closed
                if (n < 0) Log.d(TAG, "readBytes returned errno=${-n}")
                break
            }
            sawOutput = true
            _outputBytes.emit(buf.copyOf(n))
        }
    }

    /** Send raw bytes to PTY master. Suitable for keystrokes, control codes, etc. */
    fun sendRawBytes(bytes: ByteArray) {
        if (bytes.isEmpty() || masterFd < 0) return
        scope.launch { writeInput(bytes) }
    }

    /**
     * Send a UTF-8 string to the PTY, mirroring iOS TerminalInputView.insertText
     * semantics while covering the cases that iOS handles implicitly through UIKit:
     *
     *  - Unicode (CJK, emoji, combining chars): UTF-8 encoded verbatim.
     *  - Line endings: `\r\n` and bare `\n` both collapse to `\r`. A TTY expects
     *    CR for Enter; the kernel's ICRNL termios flag translates CR → NL on the
     *    shell's read side, so the shell sees a newline as usual.
     *  - Large pastes: chunked into ≤2048-byte writes and yielded between chunks
     *    so the PTY's input buffer has time to drain and bytes aren't dropped.
     *
     * Ctrl sequences, arrow keys, and function keys come through TerminalInputView
     * (hardware keys / accessory bar) as already-translated byte arrays via
     * [sendRawBytes]. They deliberately do NOT go through [sendText] — that
     * mirrors the iOS split where insertText handles printable text and
     * pressesBegan handles function/arrow keys.
     */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        val normalized = normalizeLineEndings(text)
        sendRawBytes(normalized.toByteArray(Charsets.UTF_8))
    }

    /** Collapse CRLF / LF to CR so a real TTY treats them as Enter. */
    private fun normalizeLineEndings(text: String): String {
        // Fast path: no newlines to rewrite.
        if ('\n' !in text && '\r' !in text) return text
        val sb = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when (c) {
                '\r' -> {
                    sb.append('\r')
                    // Swallow a following \n so \r\n becomes a single \r.
                    if (i + 1 < n && text[i + 1] == '\n') i++
                }
                '\n' -> sb.append('\r')
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** Legacy API — same as [sendText] but appends a newline. Kept for the deprecated line-input UI. */
    @Deprecated("Use sendText / sendRawBytes instead — real TTY doesn't line-buffer.")
    fun sendInput(text: String) {
        // Use CR, not LF — a real TTY treats CR as Enter (termios ICRNL handles the rest).
        sendRawBytes((normalizeLineEndings(text) + "\r").toByteArray(Charsets.UTF_8))
    }

    /** Send SIGINT (Ctrl+C handled by kernel thanks to termios ISIG). */
    fun sendInterrupt() {
        // With a real PTY, writing 0x03 to the master triggers SIGINT via
        // termios VINTR — which is the correct, portable path.
        sendRawBytes(byteArrayOf(0x03))
    }

    /** Resize PTY window — emits SIGWINCH so the shell redraws its prompt. */
    fun setWindowSize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        cols = newCols
        rows = newRows
        if (masterFd >= 0) {
            val rc = PtyBridge.setWindowSize(masterFd, newCols, newRows)
            if (rc < 0) Log.w(TAG, "setWindowSize failed: errno=${-rc}")
        }
    }

    fun stop() {
        readerJob?.cancel()
        readerJob = null
        val fd = masterFd
        val pid = childPid
        masterFd = -1
        childPid = 0
        if (fd >= 0) PtyBridge.closeFd(fd)
        if (pid > 0) PtyBridge.sendSignal(pid, 15) // SIGTERM
        if (_state.value != State.STOPPED) _state.value = State.STOPPED
        liveSessions.removeAll { it.get() === this || it.get() == null }
    }

    /**
     * Push a new TZ value into the running interactive shell via stdin.
     * Uses CR as the line terminator (termios ICRNL maps it to NL for the shell).
     */
    private fun applyTimezone(tz: String) {
        if (masterFd < 0) return
        val line = "export TZ='${tz.replace("'", "'\\''")}'\r"
        sendRawBytes(line.toByteArray(Charsets.UTF_8))
    }

    /**
     * Push a block of env vars into the running interactive shell via stdin.
     * Each key is `export`ed on its own line with POSIX single-quote escaping.
     * Empty values are exported verbatim — this lets a proxy-unset transition
     * clear previously-set proxy vars in one round trip.
     */
    private fun applyEnvMap(env: Map<String, String>) {
        if (masterFd < 0 || env.isEmpty()) return
        val sb = StringBuilder()
        for ((k, v) in env) {
            val escaped = v.replace("'", "'\\''")
            sb.append("export ").append(k).append("='").append(escaped).append("'\r")
        }
        sendRawBytes(sb.toString().toByteArray(Charsets.UTF_8))
    }

    /** Signal UI to wipe the emulator. No PTY bytes are written. */
    fun clearOutput() {
        _clearVersion.value = _clearVersion.value + 1
    }

    private suspend fun writeInput(bytes: ByteArray) {
        val fd = masterFd
        if (fd < 0) return
        // PTY master writes may return a short count at PIPE_BUF (~4096) boundaries
        // or under backpressure. Loop until all bytes are written; cap per-call size
        // to CHUNK so large pastes don't monopolize the PTY or block the caller.
        var off = 0
        while (off < bytes.size) {
            val take = minOf(CHUNK_SIZE, bytes.size - off)
            val rc = PtyBridge.writeBytes(fd, bytes, off, take)
            if (rc < 0) {
                Log.w(TAG, "writeBytes failed: errno=${-rc}")
                return
            }
            if (rc == 0) {
                // Shouldn't happen on a healthy fd; bail rather than spin.
                Log.w(TAG, "writeBytes returned 0 at off=$off size=${bytes.size}")
                return
            }
            off += rc
            // Yield after each chunk so the PTY reader side has time to drain.
            if (off < bytes.size) kotlinx.coroutines.yield()
        }
    }

    /**
     * One-line notice of what `/var/minis` means in this terminal. A chat's
     * terminal shares that chat's workspace with its agent; the global one
     * belongs to no chat, and saying so up front prevents "I saved it in the
     * terminal and the agent can't see it".
     */
    private fun banner(sessionId: String?): String =
        if (sessionId != null) {
            "\u001b[2m[Minis] /var/minis/{workspace,attachments,offloads,browser} are this chat's " +
                "(session ${sessionId.take(8)}) — the agent in that chat sees the same files.\u001b[0m\r\n"
        } else {
            "\u001b[2m[Minis] Global terminal — not tied to any chat. /var/minis/workspace here is NOT " +
                "any chat's workspace; open the terminal from a chat's menu to work in its files. " +
                "Shared: /var/minis/{shared,memory,skills}.\u001b[0m\r\n"
        }

    private fun buildInteractiveCommand(sessionId: String?): List<String> {
        check(PRootKernel.isBooted) { "PRootKernel must be booted" }
        val rootfsManager = RootfsManager.getInstance(context)
        val cmd = mutableListOf<String>()
        cmd.add(rootfsManager.prootBinary.absolutePath)
        cmd.add("-0")
        // T141: see PRootKernel.buildProotCommand for rationale — translates
        // hardlinks to symlinks so apk install of binutils/gcc works.
        cmd.add("--link2symlink")
        cmd.add("-r"); cmd.add(rootfsManager.rootfsDir.absolutePath)
        cmd.add("-b"); cmd.add("/dev")
        cmd.add("-b"); cmd.add("/proc")
        cmd.add("-b"); cmd.add("/sys")
        cmd.add("-w"); cmd.add("/root")

        // [T-android-session-private-mounts] The same mount set the agent's
        // shell for [sessionId] gets (SessionMounts is the single builder), so
        // a file saved here is the file the agent sees. This used to replay
        // the global PRootKernel.bindMounts, whose per-session entries were
        // whichever chat last built a shell — a random chat's workspace.
        // No session (global entry): global dirs and external mounts only.
        cmd.addAll(SessionMounts.toProotArgs(SessionMounts.forContext(context, sessionId).mounts))

        val handlers = NativeOffloadServer.registeredHandlers
        // [T-native-offload-bind] Only when our server is listening: otherwise
        // the handler stubs run and report the CLI unavailable, instead of
        // proot connecting to a name another process holds (and hanging).
        if (handlers.isNotEmpty() && NativeOffloadServer.awaitListening()) {
            cmd.add("--native-offload=${NativeOffloadServer.socketName}:${handlers.joinToString(",")}")
        }

        // [T-android-fake-netlink-tty-parity] Same rtnetlink emulation the
        // agent's shell gets — see PRootKernel.buildProotCommand, which is the
        // authoritative copy of this rationale.
        //
        // c9318f42f added `--fake-netlink` to that builder only, so the two
        // ways into the sandbox disagreed: a tool run by the agent saw an
        // (empty) interface list, while the SAME tool typed into this terminal
        // hit the real AF_NETLINK refusal from Android's untrusted_app SELinux
        // domain. A sandbox that behaves differently depending on which screen
        // you launched from is a debugging trap regardless of which behaviour
        // is the better one, so the flag is mirrored here.
        //
        // The ⚠️ on the original still stands, and now covers this call site
        // too: an empty interface list is a deliberate lie that unblocks
        // callers which only need enumeration to succeed, at the cost of
        // moving the failure later for callers that then bind a local address.
        // Whatever exposure decision that flag gets, both builders take it
        // together.
        cmd.add("--fake-netlink")
        // [FEAT-261021-11] Shell transparent forwarding (see PRootKernel).
        cmd.addAll(com.openminis.app.network.ShellTransparentNet.prootArgs(rootfsManager.prootBinary))

        // Login + interactive so /etc/profile is sourced (readline, history, color aliases).
        cmd.add("/bin/sh")
        cmd.add("-l")
        cmd.add("-i")
        return cmd
    }
}
