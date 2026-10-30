package com.openminis.app.sandbox

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.openminis.app.data.MountedFoldersStore
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.diagnostics.ThreadNames
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [T-container-network-dns] (OpenMinis#396) Which DNS servers the sandbox's
 * `/etc/resolv.conf` lists.
 *
 * By default the sandbox follows the system: [RootfsManager.refreshDns]
 * copies the active network's nameservers on install, on every boot and on
 * every network change. That is right for most users, and it is the ONLY
 * correct choice under a proxy running Fake-IP / TUN mode (Clash, Surge,
 * sing-box): public resolvers there leak queries and hand back real IPs that
 * bypass the proxy. But some networks (a LAN, Tailscale, a private VPN)
 * advertise resolvers that cannot resolve public names, and then every
 * `apk add` / `pip install` / `git clone` in the sandbox times out.
 *
 * So the choice is the user's: [DnsMode.AUTO] (default, unchanged
 * behaviour) or [DnsMode.CUSTOM], where resolv.conf lists exactly the servers
 * they entered and a network change no longer rewrites it. Everything below
 * except the prefs accessors is pure, so it is unit-tested directly.
 *
 * Storage: SharedPreferences [PREFS]; the keys are the minis-config paths
 * (`network.dnsMode`, `network.dnsCustomServers`), registered in
 * ConfigBuiltins so the Settings UI and the agent share one source of truth.
 */
object ContainerDns {

    const val PREFS = "container_network"
    const val KEY_MODE = "network.dnsMode"
    const val KEY_SERVERS = "network.dnsCustomServers"

    enum class DnsMode(val wire: String) {
        AUTO("auto"),
        CUSTOM("custom"),
        ;

        companion object {
            /** Unknown or missing → AUTO, the behaviour before this setting existed. */
            fun fromWire(raw: String?): DnsMode = values().firstOrNull { it.wire == raw?.trim()?.lowercase() } ?: AUTO
        }
    }

    /**
     * musl (Alpine) reads at most this many `nameserver` lines (MAXNS); the
     * rest are ignored. Extra entries are still written — they do no harm —
     * but the editor says so.
     */
    const val RESOLVER_MAX_SERVERS = 3

    data class ParseResult(
        /** Valid addresses, in the order given, without duplicates. */
        val servers: List<String>,
        /** Entries that are neither IPv4 nor IPv6, verbatim. */
        val invalid: List<String>,
    ) {
        val isValid: Boolean get() = invalid.isEmpty()
    }

    /**
     * Split a user-entered list on newlines, commas, semicolons or spaces.
     * A pasted `nameserver 1.1.1.1` line works too: the keyword is dropped.
     * An IPv6 address may be written in brackets (`[2606:4700::1111]`).
     */
    fun parse(raw: String): ParseResult {
        val servers = LinkedHashSet<String>()
        val invalid = mutableListOf<String>()
        for (token in raw.split(Regex("""[\s,;]+"""))) {
            val t = token.trim()
            if (t.isEmpty() || t.equals("nameserver", ignoreCase = true)) continue
            val addr = t.removePrefix("[").removeSuffix("]")
            if (isValidServer(addr)) servers += addr else invalid += t
        }
        return ParseResult(servers.toList(), invalid)
    }

    fun isValidServer(s: String): Boolean = isValidIpv4(s) || isValidIpv6(s)

    /** Dotted quad, each octet 0-255, no leading zeros (`01` is ambiguous). */
    fun isValidIpv4(s: String): Boolean {
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it in '0'..'9' } &&
                (p.length == 1 || p[0] != '0') && p.toInt() <= 255
        }
    }

    /**
     * RFC 4291 text form: eight 16-bit hex groups, one `::` run of zeros at
     * most, an optional trailing dotted-quad (`::ffff:192.0.2.1`), and an
     * optional `%zone` for link-local addresses, which resolv.conf accepts
     * (`fe80::1%wlan0`). Parsed by hand so nothing ever goes near
     * InetAddress, which would try to RESOLVE a string that is not a literal.
     */
    fun isValidIpv6(s: String): Boolean {
        if (s.isEmpty()) return false
        val pct = s.indexOf('%')
        val addr = if (pct >= 0) {
            val zone = s.substring(pct + 1)
            if (zone.isEmpty() || !zone.all { it.isLetterOrDigit() || it in "._-" }) return false
            s.substring(0, pct)
        } else {
            s
        }
        if (!addr.contains(':')) return false
        val doubleColons = Regex("::").findAll(addr).count()
        if (doubleColons > 1 || addr.contains(":::")) return false
        val compressed = doubleColons == 1
        // A lone leading or trailing ':' (not part of '::') is malformed.
        if (addr.startsWith(":") && !addr.startsWith("::")) return false
        if (addr.endsWith(":") && !addr.endsWith("::")) return false

        val halves = if (compressed) addr.split("::") else listOf(addr)
        val groups = halves.flatMap { h -> if (h.isEmpty()) emptyList() else h.split(':') }
        var count = 0
        for ((i, g) in groups.withIndex()) {
            val last = i == groups.lastIndex
            if (last && g.contains('.')) {
                if (!isValidIpv4(g)) return false
                count += 2
                continue
            }
            if (g.isEmpty() || g.length > 4 || !g.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return false
            count += 1
        }
        return if (compressed) count <= 7 else count == 8
    }

    /** What [RootfsManager.refreshDns] should do. */
    sealed class Plan {
        /** Follow the system (the pre-existing behaviour). */
        object System : Plan()

        /** Write exactly these servers, whatever the network says. */
        data class Custom(val servers: List<String>) : Plan()
    }

    /**
     * Custom mode with no usable server falls back to the system rather than
     * writing a resolv.conf with no nameserver, which would break every
     * lookup — a mode switch saved before the list was filled in must not
     * take the sandbox offline.
     */
    fun plan(mode: DnsMode, rawServers: String?): Plan {
        if (mode != DnsMode.CUSTOM) return Plan.System
        val servers = parse(rawServers.orEmpty()).servers
        return if (servers.isEmpty()) Plan.System else Plan.Custom(servers)
    }

    /**
     * resolv.conf for custom mode. No `search` line: custom means the user's
     * servers only, and a search domain from whichever network happens to be
     * active would reintroduce the system's influence.
     */
    fun customResolvConf(servers: List<String>): String = buildString {
        append("# Written by Minis: Container Network DNS is set to Custom (Settings > Rootfs).\n")
        for (s in servers) append("nameserver ").append(s).append('\n')
    }

    // ── storage ─────────────────────────────────────────────────────────────

    /**
     * Where the two settings live. The prefs-backed [PrefsStore] is the real
     * one; the interface exists so the minis-config fields
     * (config/fields/ContainerDnsFields) can be exercised in a JVM test.
     */
    interface Store {
        fun mode(): DnsMode
        fun rawServers(): String
        /** Persist both. [rawServers] must already be valid. */
        fun save(mode: DnsMode, rawServers: String)
    }

    class PrefsStore(private val context: Context) : Store {
        override fun mode(): DnsMode = mode(context)
        override fun rawServers(): String = rawServers(context)
        override fun save(mode: DnsMode, rawServers: String) = save(context, mode, rawServers)
    }

    /** One address per line: the stored form. */
    fun normalize(raw: String): String = parse(raw).servers.joinToString("\n")

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(context: Context): DnsMode = DnsMode.fromWire(prefs(context).getString(KEY_MODE, null))

    fun rawServers(context: Context): String = prefs(context).getString(KEY_SERVERS, null).orEmpty()

    fun plan(context: Context): Plan = plan(mode(context), rawServers(context))

    /**
     * Save both settings together. The server list is stored normalised (one
     * address per line) and must be valid — callers validate first and show
     * the error; this refuses rather than persist something unusable.
     */
    fun save(context: Context, mode: DnsMode, rawServers: String) {
        val parsed = parse(rawServers)
        require(parsed.isValid) { "Invalid DNS server(s): ${parsed.invalid.joinToString(", ")}" }
        prefs(context).edit()
            .putString(KEY_MODE, mode.wire)
            .putString(KEY_SERVERS, normalize(rawServers))
            .apply()
    }
}

// ─── Merged from ExecutionCoordinator.kt ──────────────────────────────────────────────────

/**
 * Manages per-session persistent shell processes.
 *
 * Architecture:
 * - PRootKernel (rootfs + proot binary): global singleton, booted once
 * - PersistentShell: one per sessionId, owns its bind mounts and /bin/sh process
 * - Per-session Mutex: different sessions can run commands concurrently
 * - ConcurrentHashMap: thread-safe shell/mutex registry
 *
 * Concurrency guarantees:
 * - Same session: commands are serialized by the per-session Mutex
 * - Different sessions: run concurrently (each has its own Mutex)
 * - Shell creation: protected by globalLock to prevent duplicate shells
 * - Shell death: detected on next command, shell is recreated with same bind mounts
 */
object ExecutionCoordinator {

    private const val TAG = "ExecutionCoordinator"

    data class CommandResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long,
        /**
         * [T-android-shell-process-budget] A <system-reminder> for the model
         * when the command waited in [GlobalShellThrottle]'s queue; null when
         * it started at once. Kept apart from [output] so the caller can put
         * it after everything else it appends (exit status, redaction).
         */
        val queueNote: String? = null,
    )

    /**
     * [T-android-shell-fresh-process] Which mechanism runs commands.
     *
     * Defaults to the warm shell so step 1-3 change nothing for users; step 4
     * flips it once the comparison harness shows the two paths agree. Kept
     * mutable so a debug build can switch at runtime without a reinstall,
     * which is how the step-3 comparison is driven.
     */
    @Volatile
    internal var executionStrategy: ShellExecutionStrategy = ShellExecutionStrategy.DEFAULT

    /**
     * [T-android-shell-fresh-process] One PRoot process per command.
     *
     * No lease, no pool slot, no mutex: a command that owns its own process
     * and pipes cannot be interfered with by another, so the serialisation the
     * pooled path needs does not apply. That also means commands in one
     * session now run genuinely concurrently rather than queueing — the
     * behaviour the tool contract already promises ("Each invocation spawns a
     * fresh process — there is no shared terminal session").
     */
    private suspend fun executeFresh(
        sessionId: String,
        command: String,
        timeout: Long,
        lineCallback: ((String) -> Unit)?,
        fsSessionId: String?,
        stopEpoch: Long,
        spawned: java.util.concurrent.atomic.AtomicBoolean,
    ): CommandResult {
        val startTime = System.currentTimeMillis()
        val bindMounts = buildSessionBindMounts(fsSessionId ?: sessionId)
        val envVars = envVarRepository?.allAsDict() ?: emptyMap()

        suspend fun attempt(noSeccomp: Boolean): Pair<String, Int> {
            val shell = FreshProcessShell(
                context = appContext,
                sessionId = sessionId,
                sessionBindMounts = bindMounts,
                useNoSeccomp = noSeccomp,
            )
            // [T-android-shell-fresh-process] Step 5. Register before running so
            // stopCurrentCommand() can reach this command. A fresh shell is owned
            // by this call frame, unlike a pooled PersistentShell, so without the
            // registry the Stop button would have nothing to kill and a running
            // command would ignore it.
            // [T-android-fresh-shells-concurrent] The inner set is a concurrent
            // key set: it is mutated here and in the finally below under the
            // map's bin lock, but iterated by stopFreshShells() from the Stop /
            // terminate thread WITHOUT that lock, so a plain LinkedHashSet
            // could throw ConcurrentModificationException mid-Stop.
            freshShells.compute(sessionId) { _, existing ->
                (existing ?: newFreshShellSet()).also { it.add(shell) }
            }
            // [FIX-261025-05] Registered first, then checked: a Stop from here
            // on finds this shell and kills it (stop() before spawn makes it
            // return at once), and a Stop that came while the command was still
            // waiting (queue, boot, start-retry backoff, the seccomp retry gap)
            // moved the epoch, so it is not started at all.
            if (stoppedSince(sessionId, stopEpoch)) {
                freshShells.computeIfPresent(sessionId) { _, set ->
                    set.remove(shell)
                    if (set.isEmpty()) null else set
                }
                return Pair(NOT_STARTED_OUTPUT, FreshProcessShell.CANCELLED_EXIT)
            }
            spawned.set(true)
            return try {
                shell.execute(
                    command = command,
                    timeout = timeout,
                    envVars = envVars,
                    lineCallback = lineCallback,
                )
            } finally {
                freshShells.computeIfPresent(sessionId) { _, set ->
                    set.remove(shell)
                    if (set.isEmpty()) null else set
                }
            }
        }

        var (rawOutput, exitCode) = attempt(noSeccomp = false)
        // [T-android-fresh-seccomp-selfheal / GH#186] The warm path
        // (PersistentShell), ShellExecutor and TerminalSession all retry once
        // with PROOT_NO_SECCOMP=1 when the child dies on an early fatal signal
        // with no output. The fresh path became the default without that
        // self-heal, so on a GH#186 device every shell_execute died with
        // 135/139. Same narrow gate as the others (SeccompFallbackPolicy):
        // a normal failure, a timeout (124) or a user Stop (130) never
        // qualifies, and it fires at most once per command.
        val firstDurationMs = System.currentTimeMillis() - startTime
        if (SeccompFallbackPolicy.shouldRetryWithoutSeccomp(
                exitCode = exitCode,
                durationMs = firstDurationMs,
                producedOutput = rawOutput.isNotEmpty(),
                alreadyRetried = false,
            )
        ) {
            com.openminis.app.logging.AppLogger.warning(
                TAG,
                SeccompFallbackPolicy.retryLogLine(exitCode, firstDurationMs, "fresh shell command"),
            )
            // No output came out of the first attempt (a precondition of the
            // retry), so reusing lineCallback cannot duplicate anything.
            val retried = attempt(noSeccomp = true)
            rawOutput = retried.first
            exitCode = retried.second
        }
        val durationMs = System.currentTimeMillis() - startTime
        val sanitized = TerminalSanitizer.sanitize(rawOutput)
        val truncated = TerminalSanitizer.truncateIfNeeded(sanitized)
        val output = if (exitCode != 0 && exitCode != 124) {
            ShellExitCode.ensureSuffix(truncated, exitCode)
        } else {
            truncated
        }
        return CommandResult(output = output, exitCode = exitCode, durationMs = durationMs)
    }

    private lateinit var appContext: Context
    var envVarRepository: EnvVarRepository? = null

    /** Thread-safe per-session shell registry. */
    private val shells = ConcurrentHashMap<String, PersistentShell>()

    /**
     * [T-android-shell-fresh-process] Live fresh-process commands per session.
     *
     * A set rather than a single entry: a session runs several commands
     * concurrently (the same reason the warm path holds a pool), and Stop must
     * mean "stop everything this conversation is running", not just the most
     * recent one. Entries are removed in a finally block, so a crashing
     * command cannot leak one.
     */
    private val freshShells = ConcurrentHashMap<String, MutableSet<FreshProcessShell>>()

    /** [T-android-fresh-shells-concurrent] Thread-safe per-session set for [freshShells]. */
    private fun newFreshShellSet(): MutableSet<FreshProcessShell> = ConcurrentHashMap.newKeySet()

    /** Thread-safe per-session mutex registry. */
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * [T-android-a11y-helper-mount] dispatch session id -> the session whose
     * `/var/minis` directories its shell binds (fsSessionId), recorded only
     * when they differ (a helper / sub agent sharing its parent's workspace,
     * T-p2-shared-workspace). Dropped in [sessionDidTerminate].
     */
    private val mountedSessionIds = ConcurrentHashMap<String, String>()

    /**
     * [T-android-a11y-helper-mount] The session id whose per-session dirs are
     * mounted at `/var/minis/...` for a command dispatched as [sessionId].
     *
     * The shell exports `MINIS_CHAT_SESSION_ID=<dispatch id>`, but for a
     * helper the bind mounts come from `fsSessionId ?: sessionId` (the
     * PARENT). A native offload that resolves `/var/minis/attachments` with
     * the env id therefore writes into the helper's own directory, which
     * neither the helper's shell nor read_image (called with the parent id)
     * can see. Offloads map the env id through here first — the same
     * `fsSessionId ?: sessionId` rule [executeFresh] mounts with.
     */
    fun mountedSessionIdFor(sessionId: String): String = mountedSessionIds[sessionId] ?: sessionId

    /**
     * Per-session snapshot of the env-var keys injected on the previous
     * `applyEnvironment` call. Used to issue `unset` for keys the user has
     * since deleted from EnvVarRepository. Long-lived shells would otherwise
     * keep the stale value around indefinitely.
     */
    private val lastInjectedKeys = ConcurrentHashMap<String, Set<String>>()

    /**
     * Global lock used only for shell creation to prevent duplicate shells
     * when the same session's first command arrives concurrently.
     */
    private val globalLock = Mutex()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Execute a command in the session's persistent shell.
     *
     * Flow:
     * 1. Get or create per-session Mutex (thread-safe via ConcurrentHashMap)
     * 2. Acquire per-session Mutex (serializes commands within same session)
     * 3. Get or create PersistentShell (protected by globalLock on creation)
     * 4. Execute command
     */
    suspend fun execute(
        sessionId: String,
        command: String,
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null,
        /** [T-p2-shared-workspace] Session whose `/var/minis` dirs the shell
         *  binds (a helper's PARENT). The shell process itself stays keyed by
         *  [sessionId], so stop / cleanup never reach the parent's shell. */
        fsSessionId: String? = null,
        /**
         * [FIX-261025-05] The session's [stopEpoch] when the CALLER's tool call
         * began. A tool that waits before running its command (a `delay`
         * countdown, a bash install) passes the value it took at its start,
         * so a Stop during that wait still keeps the command from starting.
         * Null = taken here.
         */
        stopEpoch: Long? = null,
    ): CommandResult {
        val epoch = stopEpoch ?: stopEpoch(sessionId)
        if (stoppedSince(sessionId, epoch)) return notStarted(sessionId, "before it was queued")
        // [T-android-concurrent-shell] Pick a FREE shell from this session's
        // pool instead of queueing behind its one shell.
        //
        // Why a pool and not "drop the lock": the lock is load-bearing. A
        // PersistentShell writes the command to one long-lived /bin/sh's stdin
        // and reads that process's stdout until an end marker, through a SINGLE
        // `pendingCallback` slot. Two commands on one shell would interleave on
        // the same pipe and race that slot — the output of one would be
        // attributed to the other. So the serialization stays PER SHELL; what
        // changes is that a session may have more than one.
        //
        // Why not iOS's model: iOS forks a fresh /bin/sh per call and has no
        // lock at all (ISHExecutionCoordinator's comment records deleting its
        // FIFO in 2026-05-25 for exactly the reason we hit here — it "silently
        // serialize[d] at the coordinator, defeating the upper-layer
        // parallelism"). Android cannot copy that directly: a proot shell costs
        // a process plus every bind mount re-applied at spawn, which is far
        // heavier than iSH's fork, so spawning one per call would trade this
        // latency for a worse one. Pooling keeps the warm-shell benefit and
        // still lets N commands run at once.
        //
        // The documented contract is unaffected: the tool schema already tells
        // the model "Each invocation spawns a fresh process — there is no shared
        // terminal session", so nothing may depend on cwd or shell variables
        // surviving between calls. Env vars are re-applied per command below.
        // The lease arrives with its slot's lock ALREADY HELD (claimed
        // atomically inside acquireShellLease), so this is try/finally rather
        // than withLock — re-locking here would deadlock on a non-reentrant
        // Mutex.
        // [T-android-env-first-turn] Boot BEFORE taking the lease, not after.
        //
        // This ordering is the first-turn env bug. acquireShellLease() spawns
        // the shell, and it used to run while PRootKernel had not booted yet —
        // so PROOT_LOADER was still empty, proot bare-execve'd the rootfs
        // busybox, and SELinux killed it on Android 10+:
        //
        //   spawn ctx: proot=true rootfs=true loader64=false loader32=false
        //   spawn outcome: alive=false        <- execve("/bin/sh"): Permission denied
        //   Auto-booting PRootKernel          <- boot only starts HERE
        //   PRoot kernel booted ... loader=.../libproot-loader.so
        //   spawn ctx: ... loader64=true loader32=true
        //   spawn outcome: alive=true
        //
        // The user's env was then exported into that dead first shell and lost
        // when executeCommand() silently respawned, so the first tool call of
        // every cold start ran with no environment and a retry "fixed" it.
        // Booting first means the very first spawn already has its loader.
        if (!PRootKernel.isBooted) {
            Log.i(TAG, "[$sessionId] Auto-booting PRootKernel")
            PRootKernel.boot(appContext)
        }

        // [T-android-a11y-helper-mount] Remember whose /var/minis dirs this
        // dispatch id is mounted with, so native offloads (which only learn
        // the MINIS_CHAT_SESSION_ID = dispatch id) resolve guest paths
        // against the directory the guest actually sees. See
        // [mountedSessionIdFor].
        if (fsSessionId != null && fsSessionId != sessionId) {
            mountedSessionIds[sessionId] = fsSessionId
        }

        // [T-android-shell-fresh-process] Step 3: route through the strategy.
        // FRESH_PROCESS needs no lease, no pool slot and no mutex — a command
        // that owns its own process cannot collide with another one, so the
        // serialisation the warm path requires is simply absent here.
        // [T-android-global-shell-throttle] Commands are admitted against a
        // budget of live Minis child processes across every session; the rest
        // queue. Taken BEFORE the pool lease, so a queued command holds no
        // shell slot.
        // [T-android-shell-process-budget] The queue does not eat the
        // command's timeout: once admitted it runs with its full budget. A
        // fresh spawn that failed ran nothing, so it re-queues and retries.
        // [FIX-261025-05] "Stop Current Tool Only" does not cancel a shell
        // call (it kills the call's process group so the call returns its
        // partial output). Before a process exists there is nothing to kill,
        // so a command waiting in the throttle's queue or start-retry backoff
        // used to wait out the Stop and then run anyway. The watcher cancels
        // that wait as soon as this session is stopped, as long as nothing
        // has been spawned yet; once it has, the ordinary group kill applies.
        val spawned = java.util.concurrent.atomic.AtomicBoolean(false)
        val outcome = try {
            kotlinx.coroutines.coroutineScope {
                val work = async {
                    GlobalShellThrottle.run(
                        tag = sessionId,
                        isStartFailure = ::isStartFailure,
                    ) {
                        if (stoppedSince(sessionId, epoch)) {
                            CommandResult(output = NOT_STARTED_OUTPUT, exitCode = FreshProcessShell.CANCELLED_EXIT, durationMs = 0L)
                        } else if (executionStrategy == ShellExecutionStrategy.FRESH_PROCESS) {
                            executeFresh(sessionId, command, timeout, lineCallback, fsSessionId, epoch, spawned)
                        } else {
                            spawned.set(true)
                            executeWarm(sessionId, command, timeout, lineCallback, fsSessionId)
                        }
                    }
                }
                val watcher = launch {
                    while (isActive && !spawned.get()) {
                        // Re-read spawned: it may have been set since the loop
                        // check, and a spawned command is the group kill's job -
                        // cancelling it here would report "nothing was run"
                        // for a command that already started.
                        if (stoppedSince(sessionId, epoch) && !spawned.get()) {
                            work.cancel(StoppedBeforeStart())
                            break
                        }
                        kotlinx.coroutines.delay(STOP_WATCH_MS)
                    }
                }
                try { work.await() } finally { watcher.cancel() }
            }
        } catch (e: StoppedBeforeStart) {
            return notStarted(sessionId, "while waiting to start")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Only our own watcher's cancel becomes a result; a cancellation
            // from above (the whole turn was stopped) propagates.
            if (e.cause is StoppedBeforeStart && kotlinx.coroutines.currentCoroutineContext().isActive) {
                return notStarted(sessionId, "while waiting to start")
            }
            throw e
        }
        if (outcome is GlobalShellThrottle.Outcome.Ran && outcome.value.output == NOT_STARTED_OUTPUT) {
            return notStarted(sessionId, "when it was admitted")
        }
        return when (outcome) {
            is GlobalShellThrottle.Outcome.Ran -> {
                if (!outcome.queued) outcome.value
                else outcome.value.copy(queueNote = queueNote(outcome.queuedMs, outcome.startRetries))
            }
            is GlobalShellThrottle.Outcome.QueueTimedOut -> CommandResult(
                output = "Not run: waited ${outcome.waitedMs / 1000}s for Android's per-app process limit to " +
                    "free up (${outcome.lastLiveCount} Minis processes running, budget " +
                    "${GlobalShellThrottle.PROCESS_BUDGET}), or for the shell to start. Nothing was executed; " +
                    "retry once other commands or background processes have finished.",
                exitCode = 124,
                durationMs = outcome.waitedMs,
            )
        }
    }

    /**
     * A fresh process that could not be spawned (see FreshProcessShell's
     * spawn catch) ran nothing and is safe to retry. Everything else - a
     * warm shell that died, a command killed mid-run - may have had effects.
     */
    internal fun isStartFailure(result: CommandResult): Boolean =
        result.exitCode == -1 && result.output.startsWith(FreshProcessShell.SPAWN_FAILED_PREFIX)

    /**
     * [T-android-shell-process-budget] Tell the model a command sat in the
     * queue, so a slow result is not read as a slow command.
     */
    internal fun queueNote(queuedMs: Long, startRetries: Int): String {
        val secs = String.format(java.util.Locale.US, "%.1f", queuedMs / 1000.0)
        val retried = if (startRetries > 0) " Its process failed to start $startRetries time(s) and was retried." else ""
        return "<system-reminder>This command was queued for ${secs}s before it started, because Android " +
            "limits how many processes an app may run at once.$retried It then ran to completion and its output " +
            "is complete, but its measured duration may be inaccurate.</system-reminder>"
    }

    /** The pooled warm-shell path (ShellExecutionStrategy.WARM_SHELL). */
    private suspend fun executeWarm(
        sessionId: String,
        command: String,
        timeout: Long,
        lineCallback: ((String) -> Unit)?,
        fsSessionId: String?,
    ): CommandResult {
        val lease = acquireShellLease(sessionId, fsSessionId ?: sessionId)

        return try {
            val startTime = System.currentTimeMillis()

            // [diag] trace the sessionId that shell_execute is dispatched with —
            // suspected source of the Chinese-emoji filename vanishing bug
            Log.w(TAG, "[diag] execute sessionId=$sessionId cmd=${command.take(120).replace('\n', ' ')}")

            // The lease already resolved (and started) this slot's shell.
            val shell = lease.shell

            // Inject user-defined environment variables as a *full snapshot*
            // (T124a). Pass the previously-injected key set so applyEnvironment
            // can `unset` anything the user has since removed from settings;
            // otherwise the long-lived shell would keep stale values.
            // [T-android-concurrent-shell] Keyed by SLOT, not by session: each
            // pooled shell is its own process with its own environment, so a
            // session-wide snapshot would tell slot 2 that keys slot 1 already
            // exported are present when slot 2 has never seen them.
            // [T-android-env-first-turn] applyEnvironment now also REMEMBERS
            // this snapshot on the shell, so that if the process turns out to
            // be dead (or dies and is respawned by executeCommand below), the
            // exports are replayed into the incarnation that actually runs the
            // command. Before that, a cold-start shell death between here and
            // executeCommand silently dropped the whole environment and the
            // user's first tool call ran bare.
            val envVars = envVarRepository?.allAsDict() ?: emptyMap()
            val previousKeys = lastInjectedKeys[lease.key] ?: emptySet()
            if (envVars.isNotEmpty() || previousKeys.isNotEmpty()) {
                shell.applyEnvironment(envVars, previousKeys = previousKeys)
                lastInjectedKeys[lease.key] = envVars.keys.toSet()
            }

            val (rawOutput, exitCode) = shell.executeCommand(
                command = command,
                timeout = timeout,
                lineCallback = lineCallback,
            )

            val durationMs = System.currentTimeMillis() - startTime
            val sanitized = TerminalSanitizer.sanitize(rawOutput)
            val truncated = TerminalSanitizer.truncateIfNeeded(sanitized)
            val output = if (exitCode != 0 && exitCode != 124) {
                ShellExitCode.ensureSuffix(truncated, exitCode)
            } else {
                truncated
            }

            CommandResult(output = output, exitCode = exitCode, durationMs = durationMs)
        } finally {
            lease.mutex.unlock()
        }
    }

    /**
     * Get the existing shell for this session, or create a new one.
     * Uses globalLock to prevent two coroutines from simultaneously creating
     * a shell for the same session (e.g. if the old shell just died).
     */
    /**
     * [T-android-concurrent-shell] Upper bound on shells per session.
     *
     * Each is a proot process holding the session's bind mounts, so this is
     * real memory and real PIDs on a phone — not a free knob. Four covers the
     * observed fan-out (this device's history: of 88,839 tool-carrying turns,
     * the widest were a handful of 5-call turns, and shell_execute appears in
     * at most a few of those at once) while staying well under
     * ChatViewModel.MAX_CONCURRENT_TOOLS, which bounds the whole turn anyway.
     * A 5th concurrent shell command simply waits for a slot — the pre-change
     * behaviour, applied at a much higher threshold.
     */
    private const val MAX_SHELLS_PER_SESSION = 4

    /** One pooled shell plus the lock that serializes ITS pipe. */
    private data class ShellLease(
        /** "<sessionId>#<slot>" — the key for [shells], [mutexes], [lastInjectedKeys]. */
        val key: String,
        val shell: PersistentShell,
        val mutex: Mutex,
    )

    /**
     * [T-android-concurrent-shell] Find a shell for this session whose lock is
     * free, creating one if the pool has room.
     *
     * Deliberately best-effort rather than a strict allocator: `tryLock` is
     * only a hint (the winner takes it for real inside `withLock`), so two
     * callers can pick the same slot and one will wait. That is correct and
     * cheap — the alternative, holding a global allocation lock across the
     * whole command, would reintroduce the serialization this exists to remove.
     *
     * Falls back to slot 0 when every slot is busy and the pool is full, so a
     * 5th concurrent command queues instead of failing.
     */
    private suspend fun acquireShellLease(sessionId: String, fsSessionId: String): ShellLease {
        // Claim a slot ATOMICALLY, before doing anything slow.
        //
        // The first cut here scanned for a slot whose Mutex was not `isLocked`
        // and returned it. That is a race, and the device proved it: three
        // concurrent commands all reached the scan before ANY of them had
        // entered `withLock`, so every one saw slot 0 free, all three picked
        // it, and they queued exactly as before — the logs even show slot 0
        // being created twice. `isLocked` answers about the past, not about
        // what the caller is going to do next.
        //
        // `tryLock` is the fix: it CLAIMS the slot in the same operation that
        // tests it, so exactly one caller can win a given slot. The lock is
        // held from here until the command finishes and is released in the
        // caller's `finally` — see [ShellLease.release].
        for (slot in 0 until MAX_SHELLS_PER_SESSION) {
            val key = "$sessionId#$slot"
            val mutex = mutexes.getOrPut(key) { Mutex() }
            if (!mutex.tryLock()) continue
            // Won this slot. Reuse its shell when warm; otherwise spawn one,
            // still holding the claim so no one else spawns into it too.
            val existing = shells[key]
            val shell = if (existing != null && existing.isAlive) existing
            else getOrCreateShell(key, fsSessionId, chatSessionId = sessionId)
            return ShellLease(key, shell, mutex)
        }
        // Pool exhausted: every slot is genuinely busy. Wait for slot 0 — the
        // pre-pooling behaviour, now at a 4x higher threshold.
        val key = "$sessionId#0"
        val mutex = mutexes.getOrPut(key) { Mutex() }
        mutex.lock()
        val existing = shells[key]
        val shell = if (existing != null && existing.isAlive) existing
        else getOrCreateShell(key, fsSessionId, chatSessionId = sessionId)
        return ShellLease(key, shell, mutex)
    }

    /**
     * @param sessionId the POOL KEY ("<chatSessionId>#<slot>"), which is what
     *   [shells] is registered under.
     * @param chatSessionId the real chat session id. Kept separate because it
     *   is exported into the shell as MINIS_CHAT_SESSION_ID, which the native
     *   offload layer reads to scope ASK_ONCE permission grants per chat.
     *   Handing it the slotted key would make every slot a different "session"
     *   to the permission manager, so a user who granted a permission once
     *   would be prompted again on the next slot. Defaults to the pool key so
     *   the non-pooled callers below are unchanged.
     */
    private suspend fun getOrCreateShell(
        sessionId: String,
        fsSessionId: String = sessionId,
        chatSessionId: String = sessionId,
    ): PersistentShell {
        // Fast path: existing alive shell
        val existing = shells[sessionId]
        if (existing != null && existing.isAlive) {
            Log.w(TAG, "[diag] reuse existing shell for sessionId=$sessionId attachmentsMount=${existing.debugBindMount("/var/minis/attachments")}")
            return existing
        }

        // Slow path: need to create (or recreate after crash)
        return globalLock.withLock {
            // Double-check after acquiring lock
            val recheck = shells[sessionId]
            if (recheck != null && recheck.isAlive) {
                Log.w(TAG, "[diag] reuse existing shell (post-lock) for sessionId=$sessionId attachmentsMount=${recheck.debugBindMount("/var/minis/attachments")}")
                return@withLock recheck
            }

            // Shell is dead or missing — clean up and create fresh
            if (recheck != null) {
                Log.w(TAG, "[$sessionId] Shell died unexpectedly, recreating")
                recheck.stop()
            }

            val bindMounts = buildSessionBindMounts(fsSessionId)
            val shell = PersistentShell(appContext, chatSessionId, bindMounts)
            shells[sessionId] = shell
            shell.ensureStarted()
            Log.i(TAG, "[$sessionId] Shell created with ${bindMounts.size} bind mounts")
            Log.w(TAG, "[diag] new shell created sessionId=$sessionId attachmentsMount=${bindMounts["/var/minis/attachments"]}")
            shell
        }
    }

    /**
     * [T-android-session-private-mounts] Bind mounts for a shell of
     * [sessionId]: its own workspace / attachments / offloads / browser, the
     * global dirs, and the user's external folders — see [SessionMounts].
     *
     * The result goes ONLY into that shell's PRoot argv. This used to also
     * write the per-session dirs into the process-global
     * PRootKernel.bindMounts, where the last session to build a shell decided
     * what every other reader (the terminal, the image loader) saw.
     */
    private fun buildSessionBindMounts(sessionId: String): Map<String, String> {
        val built = SessionMounts.forContext(appContext, sessionId)
        // Legacy readers that resolve `/var/minis/<per-session>` WITHOUT a
        // session id keep their old answer (the most recent session) through
        // this explicit hint instead of through the shared mount table.
        PRootKernel.noteLegacySession(appContext, sessionId)
        Log.i(TAG, "[$sessionId] private bind mounts=${built.mounts.size} skipped=${built.skipped}")
        return built.mounts
    }

    /**
     * [T-android-concurrent-shell] Tear down every pooled shell belonging to
     * [sessionId] and drop its per-slot bookkeeping.
     *
     * Stops each one here rather than returning them, so no caller can forget a
     * slot; the return value is only the FIRST shell removed, kept so the two
     * existing callers' "did anything exist?" logging still works.
     *
     * Matching is on the "<sessionId>#<slot>" key prefix, anchored with the
     * separator so a session id that is a prefix of another cannot sweep its
     * neighbour's shells.
     */
    private fun removeSessionShells(sessionId: String): PersistentShell? {
        val prefix = "$sessionId#"
        var first: PersistentShell? = null
        for (key in shells.keys.toList()) {
            if (key != sessionId && !key.startsWith(prefix)) continue
            val shell = shells.remove(key) ?: continue
            // T124a: the env snapshot belongs to the now-dead shell.
            lastInjectedKeys.remove(key)
            // [T-android-concurrent-shell] The Mutex is deliberately NOT removed.
            // A command may be running on this slot right now and holding it;
            // dropping it from the map would let the next command create a FRESH
            // Mutex for the same key and run concurrently with the in-flight one
            // on a shell being torn down. Leaving it means the in-flight holder
            // still unlocks the object it locked, and the next caller waits
            // correctly. The map is bounded by (sessions x 4), not a real leak.
            if (first == null) first = shell else shell.stop()
        }
        // Legacy un-slotted key, from a build before pooling.
        lastInjectedKeys.remove(sessionId)
        return first
    }

    /**
     * Called when a session is closed. Stops and removes the shell.
     */
    fun sessionDidTerminate(sessionId: String) {
        bumpStopEpoch(sessionId)
        val shell = removeSessionShells(sessionId)
        mutexes.remove(sessionId)
        // T124a: drop the snapshot too — a future shell for the same id
        // restarts from a clean baseline, so the next applyEnvironment
        // shouldn't try to `unset` keys that don't exist in the new shell.
        lastInjectedKeys.remove(sessionId)
        mountedSessionIds.remove(sessionId)
        shell?.stop()
        if (shell != null) Log.i(TAG, "[$sessionId] Shell terminated")
        // [T-android-fresh-session-terminate] Fresh-process commands are not in
        // the pooled `shells` map, so the lines above never reached them: a
        // closed session's running command kept going (and holding its
        // bind-mounted workspace) until it finished or timed out. Same path as
        // the Stop button — FreshProcessShell.stop() kills the whole process
        // group — and keyed on this exact session id, so other sessions'
        // commands are untouched.
        val stoppedFresh = stopFreshShells(sessionId)
        if (stoppedFresh > 0) Log.i(TAG, "[$sessionId] $stoppedFresh fresh-process command(s) killed on terminate")
    }

    /**
     * Stop the shell for a specific session (e.g. user tapped cancel).
     * The shell process is killed; next command will recreate it.
     */
    fun stopCurrentCommand(sessionId: String? = null) {
        // [FIX-261025-05] Only a Stop aimed at a session moves its epoch. The
        // session-less form is the shell restart after a mount change
        // (MinisApp), not a user Stop: moving every epoch there told the
        // commands still waiting in every chat "stopped by the user".
        if (sessionId != null) bumpStopEpoch(sessionId)
        if (sessionId != null) {
            // [T-android-concurrent-shell] Stop EVERY shell in the session's
            // pool, not one. A session now holds several, and Stop must mean
            // "stop what this conversation is running" — killing slot 0 while
            // slots 1-3 kept working would leave commands the user believes
            // they cancelled still executing, with no way to reach them.
            val shell = removeSessionShells(sessionId)
            shell?.stop()
            val fresh = stopFreshShells(sessionId)
            // [T-android-stop-late-write] Through AppLogger, so a field log
            // shows that Stop reached the shell and what it killed; Log.i never
            // reaches the exported log file.
            com.openminis.app.logging.AppLogger.info(
                TAG,
                "[StopLate] [$sessionId] stopped by user, command killed: " +
                    "persistentShell=${shell != null} freshCommands=$fresh",
            )
        } else {
            // Stop all sessions (legacy/fallback)
            shells.values.forEach { it.stop() }
            shells.clear()
            // [T-android-fresh-shells-concurrent] Snapshot via ArrayList, see stopFreshShells.
            ArrayList(freshShells.keys).forEach { stopFreshShells(it) }
            lastInjectedKeys.clear()
            ShellExecutor.destroyCurrent()
        }
    }

    /**
     * [T-stop-choice] "Stop Current Tool Only": end the commands [sessionId] is
     * running right now, and only those. Unlike [stopCurrentCommand] the pool
     * is kept, so background processes the agent started (`setsid`/`nohup`
     * servers, watchers) survive; each pooled shell kills its foreground
     * process group ([PersistentShell.interruptForeground]), and a
     * fresh-process command is stopped the way it always is - by its group,
     * leaving proot alive for anything it detached.
     *
     * @return how many commands were signalled.
     */
    fun interruptForegroundCommands(sessionId: String): Int {
        bumpStopEpoch(sessionId)
        val prefix = "$sessionId#"
        var pooled = 0
        for ((key, shell) in shells.entries.toList()) {
            if (key != sessionId && !key.startsWith(prefix)) continue
            if (shell.interruptForeground()) pooled++
        }
        val fresh = stopFreshShells(sessionId)
        com.openminis.app.logging.AppLogger.info(
            TAG,
            "[stop-tool] [$sessionId] foreground commands interrupted, pool kept: " +
                "pooled=$pooled freshCommands=$fresh",
        )
        return pooled + fresh
    }

    /**
     * [FIX-261025-05] Bumped by every Stop that reaches [sessionId]'s commands
     * ([stopCurrentCommand] with a session, [interruptForegroundCommands],
     * [sessionDidTerminate]). A command compares it with the value its tool
     * call began with, so a Stop that came while it was still waiting - a
     * `delay` countdown, the process-budget queue, PRoot boot, a bash install,
     * a start retry - keeps it from starting, even though no process existed
     * for the Stop to kill.
     */
    private val stopEpochs = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    fun stopEpoch(sessionId: String): Long = stopEpochs[sessionId]?.get() ?: 0L

    fun stoppedSince(sessionId: String, epoch: Long): Boolean = stopEpoch(sessionId) != epoch

    private fun bumpStopEpoch(sessionId: String) {
        stopEpochs.computeIfAbsent(sessionId) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
    }

    private class StoppedBeforeStart : kotlinx.coroutines.CancellationException("stopped by the user before the command started")

    private fun notStarted(sessionId: String, phase: String): CommandResult {
        com.openminis.app.logging.AppLogger.info(TAG, "[stop-tool] [$sessionId] command stopped $phase - not run")
        return CommandResult(output = NOT_STARTED_OUTPUT, exitCode = FreshProcessShell.CANCELLED_EXIT, durationMs = 0L)
    }

    /** What a command stopped before it started answers. Read by the model. */
    const val NOT_STARTED_OUTPUT = "[Cancelled] Stopped by the user before the command started; nothing was run."

    private const val STOP_WATCH_MS = 200L

    /**
     * Stop every fresh-process command running for [sessionId].
     *
     * The entries are NOT removed here — each execute() call removes its own
     * in a finally block. Removing them here too would race that cleanup and
     * could drop a set another command had just re-created.
     */
    private fun stopFreshShells(sessionId: String): Int {
        // [T-android-fresh-shells-concurrent] Iterate a snapshot: a command
        // finishing concurrently removes itself from the live set. ArrayList(c)
        // copies via toArray(), which is weakly consistent on a concurrent set;
        // Kotlin's toList() is NOT — for a size-1 collection it calls
        // iterator().next() after reading size, and throws
        // NoSuchElementException if the element was removed in between.
        val snapshot = freshShells[sessionId]?.let { ArrayList(it) } ?: return 0
        snapshot.forEach { runCatching { it.stop() } }
        return snapshot.size
    }

    /** Legacy overload for callers without sessionId. */
    fun stopCurrentCommand() = stopCurrentCommand(sessionId = null)

    /**
     * Propagate a system-timezone change to every live shell.
     *
     * - Updates [PRootKernel.customEnvironment]["TZ"] so future shells inherit
     *   the new value at spawn time.
     * - Exports the new TZ into every already-running [PersistentShell] via
     *   `export TZ=...` on stdin.
     * - Asks [TerminalSession] to do the same for every live interactive PTY.
     *
     * Safe to call before PRoot has booted — it's a no-op in that case.
     */
    suspend fun broadcastTimezoneChange() {
        if (!PRootKernel.isBooted) return
        val tz = PRootKernel.updateTimezone()
        val tzMap = mapOf("TZ" to tz)
        for ((_, shell) in shells) {
            if (shell.isAlive) shell.applyEnvironment(tzMap)
        }
        TerminalSession.broadcastTimezone(tz)
    }

    /**
     * Propagate a system-proxy change to every live shell. Exports all six
     * proxy keys as a block — empty strings when no proxy is configured, so
     * a disable transition clears the old values in-place without needing
     * a separate `unset`.
     *
     * Safe to call before PRoot has booted — it's a no-op in that case.
     */
    suspend fun broadcastProxyChange(cleared: Set<String> = emptySet()) {
        if (!PRootKernel.isBooted) return
        // [T-net-android-phase3] A Settings > Network "Shell" route wins over
        // the system proxy, as it does when a shell is spawned; without one
        // (the default) the block is exactly the system proxy's, as before.
        // [FEAT-261021-10] [cleared] names keys the Network module set before
        // and no longer does (ALL_PROXY is not in the system block): a shell
        // route turned back to direct must not leave the entry's SOCKS URL
        // behind in a running shell. Cleared keys go first so the system
        // proxy's own HTTP(S)_PROXY / NO_PROXY, when set, take their place.
        val env = cleared.associateWith { "" } +
            PRootKernel.updateProxy(appContext) +
            com.openminis.app.network.NetworkShellEnvironment.proxyVariables()
        for ((_, shell) in shells) {
            if (shell.isAlive) shell.applyEnvironment(env)
        }
        TerminalSession.broadcastProxy(env)
    }
}

// ─── Merged from ForegroundCommandGroup.kt ──────────────────────────────────────────────────

/**
 * [T-android-shell-timeout-pgid] How a foreground command is isolated so a
 * timeout can kill it — and only it.
 *
 * Issue #358: one command that outran its timeout wedged its whole session.
 * Every later `shell_execute` in that session timed out too, `echo alive`
 * included, while other sessions and the interactive Terminal stayed fine.
 *
 * Cause: the timeout path did exactly nothing to the runaway.
 *
 *     // Timeout — cancel pending, but don't kill the shell
 *     pendingCallback = null
 *
 * The command kept running inside the session's long-lived, REUSED shell,
 * still writing to stdout. `/bin/sh` reads stdin serially, so the next
 * command merely queued behind it and could not start until the runaway
 * finished — which is why even `echo alive` burned its full timeout.
 *
 * ## Why a process group, and not a string search for `&`
 *
 * The obvious-looking fix — "if the command contains `&`, treat it as a
 * background job and leave it alone" — is wrong in a way that is hard to see
 * and easy to ship. `&` is ordinary text in `curl 'a?x=1&y=2'`, in
 * `grep 'a & b'`, in `awk '{print $1 & $2}'`, and inside any heredoc. A
 * matcher cannot tell those from a real job-control `&` without parsing the
 * whole shell grammar, quoting and all. Getting it wrong either kills a
 * daemon the user deliberately started, or spares a runaway and leaves #358
 * unfixed.
 *
 * The kernel already tracks exactly this, so ask it instead. Each foreground
 * command runs under `setsid`, which puts it in a brand-new session and
 * process group whose pgid equals its pid. Verified in this project's own
 * Alpine guest (`/proc/<pid>/stat` field 5):
 *
 *     shell           pid=23997  pgid=946     <- the persistent shell
 *     setsid child    pid=24027  pgid=24027   <- its own group
 *     plain child     pid=24029  pgid=946     <- inherits the shell's
 *
 * Timeout then sends `kill -9 -<pgid>`, which reaches that command and its
 * descendants and nothing else.
 *
 * ## Protecting real background work
 *
 * A `&` job started inside the wrapper still inherits the wrapper's group —
 * measured directly: `bg_pgid=24041` against `fg_pgid=24041` — so a plain
 * `sleep 100 &` WOULD be swept up. That is the correct call: an unadorned `&`
 * job belongs to the command that spawned it, and the user asked for that
 * command to stop.
 *
 * Work that is meant to outlive the command says so, by detaching into its
 * own session with `setsid` or `nohup`. Measured on device, killing the
 * foreground group:
 *
 *     plain `… &` daemon   : frozen at 2 lines  (swept, as intended)
 *     `setsid …` daemon    : 6 -> 9 lines       (survived)
 *
 * So the rule the user gets is the standard POSIX one, enforced by the
 * kernel rather than by a regex over their command text.
 */
internal object ForegroundCommandGroup {

    /** Guest path to `setsid`, present in the shipped Alpine rootfs. */
    const val SETSID = "/usr/bin/setsid"

    /**
     * Wrap [command] so it runs in its own process group and reports the
     * group id before it starts.
     *
     * The wrapper prints `__MINIS_PGID_<marker>_<pgid>__` first, so the host
     * learns which group to kill without having to hunt for the pid. It is
     * emitted by the subshell itself — reading `/proc/self/stat` field 5 —
     * because busybox `ps` has no `-o pgid=`.
     *
     * `exec` replaces the subshell with the user's command where possible, so
     * there is no extra shell sitting between the group leader and the work.
     * The command still runs under a shell (not exec'd directly) because it
     * may be a pipeline, a list, or contain redirections.
     *
     * Falls back to running the command unwrapped when [setsidAvailable] is
     * false: an unkillable-but-working command beats a broken one.
     */
    fun wrap(command: String, marker: String, setsidAvailable: Boolean = true): String {
        if (!setsidAvailable) {
            return "$command\necho \"__MINIS_DONE_${marker}_EXIT_\$?__\"\n"
        }
        // Single-quote the payload for the inner sh -c, escaping any quote in
        // it the POSIX way ('\'' closes, escapes, reopens). The command text
        // is never interpreted by the outer shell, so `&`, quotes and URLs
        // pass through untouched — the whole point of not parsing it.
        val quoted = command.replace("'", "'\\''")
        return buildString {
            append(SETSID)
            append(" sh -c 'echo \"__MINIS_PGID_")
            append(marker)
            append("_$(awk \"{print \\$5}\" /proc/self/stat)__\"; ")
            append(quoted)
            append("'\n")
            append("echo \"__MINIS_DONE_${marker}_EXIT_\$?__\"\n")
        }
    }

    /**
     * [T-android-shell-fresh-process] Step 4: the same process-group
     * isolation, for a path that has no marker protocol.
     *
     * The fresh-process path already learns completion from `waitFor()`, so it
     * needs none of [wrap]'s marker machinery — only the property that makes a
     * timeout killable: the command must lead its own process group.
     *
     * Why it is needed there at all. `Process.destroyForcibly()` SIGKILLs the
     * proot process it spawned, but not the guest descendants proot is
     * tracing. Measured on a Pixel 6: proot died on schedule at 5s
     * (`timeout after 5s, process killed` in logcat) while a `libproot.so`
     * child survived holding the pipe's write end, so the host's blocking
     * read never saw EOF and the call outlived its deadline. Killing the
     * GROUP reaches those descendants, the last writer closes, and the read
     * ends naturally.
     *
     * `exec` matters here: without it the wrapping `sh` would remain as an
     * extra process between the group leader and the work, and would be the
     * one to die while the real command kept the pipe open. With it the
     * command IS the group leader.
     *
     * [T-android-fresh-exit-status-file] The group leader is now a small
     * runner ([FRESH_RUNNER]) rather than the command itself, because process
     * exit stopped being a usable completion signal: a command that leaves a
     * detached daemon behind (every cold `minis-mcp-cli` call) keeps proot
     * alive — proot must keep tracing the daemon for its paths to work — and
     * proot's own stdout is the host pipe, so the read never reaches EOF. The
     * runner records its pid (= the group id, since setsid made it the leader)
     * in `<statusPath>.pid`, runs the command in a child shell, then writes
     * the command's exit status to `<statusPath>` and exits with it. The
     * status file is the completion signal; the pid file lets a timeout or
     * Stop kill exactly this command's group and nothing it deliberately
     * detached.
     *
     * The command and the path stay separate argv entries — `$1` and `$2` of
     * the runner — so nothing in either is ever re-parsed by a shell.
     */
    fun wrapForFreshProcess(command: String, statusPath: String): List<String> =
        listOf(SETSID, "/bin/sh", "-c", FRESH_RUNNER, "sh", command, statusPath)

    /**
     * [T-android-fresh-exit-status-file] See [wrapForFreshProcess]. `$1` is the
     * command, `$2` the status path. The inner `/bin/sh -c "$1"` means an
     * `exit N` in the command ends only the command, and the status is still
     * written; a command killed by a signal reports 128+N, as a shell would.
     */
    const val FRESH_RUNNER =
        "printf '%s' \"\$\$\" > \"\$2.pid\"; /bin/sh -c \"\$1\"; rc=\$?; printf '%s' \"\$rc\" > \"\$2\"; exit \$rc"

    /** Marker the wrapper prints so the host can learn the group id. */
    fun pgidMarkerPrefix(marker: String): String = "__MINIS_PGID_${marker}_"

    /**
     * Parse the pgid out of a chunk containing [pgidMarkerPrefix], or null.
     *
     * Returns null for a malformed or absent marker rather than guessing: a
     * wrong pgid would kill an unrelated group, so "don't know" must mean
     * "don't kill".
     */
    fun parsePgid(text: String, marker: String): Int? {
        val prefix = pgidMarkerPrefix(marker)
        val start = text.indexOf(prefix)
        if (start < 0) return null
        val from = start + prefix.length
        val end = text.indexOf("__", from)
        if (end < 0) return null
        val pgid = text.substring(from, end).trim().toIntOrNull() ?: return null
        // pgid 0 / 1 would mean "this whole process group" or init — never
        // send a kill there.
        return if (pgid > 1) pgid else null
    }

    /**
     * The guest-side kill for a timed-out foreground group.
     *
     * SIGTERM first so a well-behaved tree can flush and exit, then SIGKILL
     * for anything that ignored it — the escalation iOS already uses
     * (`ISHShellExecutor.killProcessGroup`). Negative pid targets the group.
     *
     * Runs detached via [SETSID] itself: the shell that must execute this is
     * the very shell whose foreground is wedged, so the kill has to come from
     * something the kill will not also destroy.
     */
    fun killGroupCommand(pgid: Int): String =
        "$SETSID sh -c 'kill -TERM -$pgid 2>/dev/null; sleep 1; kill -KILL -$pgid 2>/dev/null' >/dev/null 2>&1 &\n"

    /**
     * Whether a shell that just had a command timed out can keep being used.
     *
     * Always false. Even after the group is killed, the shell's stdout may
     * still hold bytes the runaway wrote before dying, and those would be
     * attributed to whichever command reads next. Recycling is the only way
     * to guarantee a clean channel; `ensureStarted()` replays the environment
     * snapshot, so nothing the user configured is lost.
     */
    fun shellIsReusableAfterTimeout(): Boolean = false
}

// ─── Merged from FreshProcessShell.kt ──────────────────────────────────────────────────

/**
 * [T-android-shell-fresh-process] Runs one command in its own PRoot process
 * and reads the exit code from the process itself.
 *
 * Step 2 of the migration away from in-band completion markers. See
 * [ShellExecutionStrategy] for the measurements and the two rejected
 * alternatives (FD 3, FIFO).
 *
 * What disappears here, compared with [PersistentShell]:
 *
 *  - **No `__MINIS_DONE__` marker.** Nothing is appended to the command's
 *    output and nothing is scanned for in it, so no command can forge
 *    completion by echoing a string. Completion is the command's own exit,
 *    reported out of band by the runner's status file (see
 *    [ForegroundCommandGroup.wrapForFreshProcess]) — proot's exit alone is
 *    not enough, because proot outlives any command that leaves a detached
 *    daemon behind.
 *  - **No carry-over buffer.** The marker was the only reason to hold bytes
 *    back between reads, and holding bytes back is what swallowed every short
 *    output in the regression this replaces.
 *  - **No cross-command contamination.** Each command owns its pipes. A
 *    runaway cannot write into the stream a later command reads, because that
 *    stream dies with it.
 *  - **No shell recycling after a timeout.** `destroyForcibly()` takes the
 *    process and its pipes together.
 *
 * Environment handling mirrors [PersistentShell.startProcess] exactly — the
 * same PROOT_* variables, TZ refresh, session id for native_offload, and the
 * seccomp fallback — so a command sees the identical environment either way.
 * That is what makes the two paths comparable in step 3.
 */
internal class FreshProcessShell(
    private val context: Context,
    private val sessionId: String,
    private val sessionBindMounts: Map<String, String>,
    private val useNoSeccomp: Boolean = false,
) {

    /**
     * Run [command] to completion.
     *
     * @return (output, exitCode). Exit 124 on timeout, matching the warm-shell
     *   contract and `timeout(1)`.
     */
    suspend fun execute(
        command: String,
        timeout: Long,
        envVars: Map<String, String> = emptyMap(),
        lineCallback: ((String) -> Unit)? = null,
    ): Pair<String, Int> = withContext(Dispatchers.IO) {
        // [T-android-fresh-exit-status-file] Where the runner reports the
        // command's exit (see ForegroundCommandGroup.wrapForFreshProcess).
        // The rootfs is the guest's "/", so the same file is visible on both
        // sides without a bind mount.
        val exitDir = File(RootfsManager.getInstance(context).rootfsDir, EXIT_DIR_NAME)
        runCatching { exitDir.mkdirs(); pruneStaleExitFiles(exitDir) }
        val token = java.util.UUID.randomUUID().toString()
        val statusFile = File(exitDir, token)
        statusHandle = statusFile
        val process = try {
            spawn(command, envVars, "/$EXIT_DIR_NAME/$token")
        } catch (e: Exception) {
            Log.e(TAG, "[$sessionId] spawn failed: ${e.message}")
            return@withContext Pair("$SPAWN_FAILED_PREFIX ${e.message}]", -1)
        }
        // [T-android-shell-fresh-process] Step 5. Publish the process so
        // [stop] can reach it. The warm path's Stop button works by killing
        // the session's long-lived shell; a fresh process is owned by this
        // call frame and would otherwise be unreachable, so tapping Stop
        // would leave the command running with no way to cancel it.
        live = process
        if (stopped) {
            // Stop arrived between the check and the spawn. Honour it rather
            // than running a command the user already cancelled.
            runCatching { process.destroyForcibly() }
            live = null
            return@withContext Pair("[Cancelled]", CANCELLED_EXIT)
        }

        val output = StringBuilder()

        // [T-android-fresh-stderr-drain] Debug builds keep stderr on its own
        // pipe (see spawn); it MUST be read, or a command that writes 64 KB to
        // stderr blocks forever. Release merges stderr and needs no drain.
        val stderrDrain = if (SEPARATE_STDERR) {
            StderrDrain(process.errorStream) { Log.d("PRootStderr", it) }.start()
        } else {
            null
        }

        // The read below is a BLOCKING InputStream.read(). Coroutine
        // cancellation cannot interrupt it — withTimeoutOrNull would fire its
        // timer and then wait for the read anyway, so the timeout would not
        // take effect until the command finished on its own (measured: a 5s
        // timeout on `sleep 120` did not return). The only thing that unblocks
        // a pipe read is the writer going away, so the watchdog kills the
        // process and lets EOF end the loop naturally.
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val readEof = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(timeout)
            // [T-android-mcp-detached-daemon-hang] Do NOT gate this on
            // `process.isAlive`.
            //
            // A command may leave behind a deliberately detached daemon:
            // `minis-mcp-cli` forks one (setsid + its own session, fds 0/1/2
            // reopened on /dev/null) so later calls reuse warm MCP
            // connections. The daemon holds no pipe of its own — verified on
            // device — but proot stays ptrace-attached to it, and a tracer
            // cannot exit while its tracee lives. So proot remains alive
            // holding the host pipe's write end long after the command
            // printed its result and exited, and the blocking read below never
            // sees EOF.
            //
            // Measured on a Pixel 6 / Android 17: `minis-mcp-cli list` wrote
            // its complete output, yet the shell call was still running after
            // 90s under a 30s guest `timeout`, with proot showing
            // `TracerPid` = the surviving daemon.
            //
            // `process.isAlive` is TRUE in that state, so the old gate did let
            // this path run — but it is also the case that a dead process
            // still needs the stream closed, and closing is harmless either
            // way. Run the unblock path unconditionally once the deadline
            // passes rather than depending on which of the two shapes occurred.
            //
            // [T-android-fresh-exit-status-file] That completed command is now
            // finished by the completion monitor below, not by this deadline;
            // the watchdog only bounds a command that is genuinely still running.
            run {
                timedOut.set(true)
                // [T-android-fresh-exit-status-file] Kill the command's own
                // process group, not proot: proot may also be the tracer of a
                // daemon an earlier command detached, and killing it would
                // leave that daemon running untraced and broken. See
                // [killCommand].
                killCommand(process, readEof)
                Log.i(TAG, "[$sessionId] timeout after ${timeout / 1000}s, command killed")
            }
        }

        // [T-android-fresh-exit-status-file] Completion monitor. The runner
        // writes the status file once the command has exited; from then on
        // everything the command wrote is already in the pipe. If proot then
        // exits too, the read reaches EOF by itself and this does nothing. If
        // proot stays — it is still tracing a detached daemon, and its own
        // stdout IS this pipe — EOF never comes, so once the output has gone
        // quiet the read end is closed here and the call completes, leaving
        // proot (and the daemon) alone.
        val lastReadAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val closedOnStatus = java.util.concurrent.atomic.AtomicBoolean(false)
        val monitor = kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            while (!statusFile.exists()) {
                if (readEof.get()) return@launch
                kotlinx.coroutines.delay(STATUS_POLL_MS)
            }
            val seenAt = System.currentTimeMillis()
            while (!readEof.get()) {
                val now = System.currentTimeMillis()
                if (outputSettled(now, seenAt, lastReadAt.get())) {
                    closedOnStatus.set(true)
                    runCatching { process.inputStream.close() }
                    Log.i(TAG, "[$sessionId] command exited; proot still tracing a detached process, left running")
                    return@launch
                }
                kotlinx.coroutines.delay(STATUS_POLL_MS)
            }
        }

        val exitCode = try {
            // [T-android-fresh-utf8-stream] Decode through a STATEFUL reader,
            // not per read(): an 8 KB read ends at an arbitrary byte offset, so
            // decoding each chunk on its own split any multibyte character on
            // the boundary into U+FFFD (a long CJK / emoji output came back
            // with replacement characters sprinkled through it). The reader's
            // decoder carries the incomplete tail into the next read. Closing
            // process.inputStream (watchdog / Stop) still unblocks it, since
            // the reader's read blocks in that same stream.
            val buf = CharArray(8192)
            val stream = java.io.InputStreamReader(process.inputStream, StandardCharsets.UTF_8)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                lastReadAt.set(System.currentTimeMillis())
                val text = String(buf, 0, n)
                output.append(text)
                lineCallback?.let { feedLines(text, it) }
            }
            readEof.set(true)
            resolveExitCode(process, statusFile, closedOnStatus.get())
        } catch (e: Exception) {
            // A read that fails because the watchdog, Stop or the completion
            // monitor closed the stream is the expected path, not an error.
            readEof.set(true)
            if (!timedOut.get() && !closedOnStatus.get() && !stopped) {
                Log.w(TAG, "[$sessionId] read failed: ${e.message}")
            }
            resolveExitCode(process, statusFile, closedOnStatus.get())
        } finally {
            watchdog.cancel()
            monitor.cancel()
        }

        // Stderr goes after stdout: separate pipes cannot be re-interleaved.
        // When proot was left running its stderr never reaches EOF either, so
        // do not spend the full wait on it.
        val stderrWait = if (closedOnStatus.get()) STDERR_SETTLE_MS else STDERR_DRAIN_WAIT_MS
        if (closedOnStatus.get()) runCatching { process.outputStream.close() }
        stderrDrain?.finish(stderrWait)?.takeIf { it.isNotEmpty() }?.let { err ->
            if (output.isNotEmpty() && output.last() != '\n') output.append('\n')
            output.append(err)
        }

        live = null
        statusHandle = null
        runCatching { statusFile.delete() }
        runCatching { File(statusFile.path + ".pid").delete() }

        if (stopped) {
            // A user-initiated Stop is not a timeout: it must not be reported
            // as 124, which the agent loop treats as "the command exceeded its
            // budget" and may retry.
            val partial = output.toString()
            val notice = "[Cancelled]"
            return@withContext Pair(
                if (partial.isBlank()) notice else "$partial\n$notice",
                CANCELLED_EXIT,
            )
        }

        if (timedOut.get()) {
            val partial = output.toString()
            val notice = "[Command timed out after ${timeout / 1000}s]"
            return@withContext Pair(
                if (partial.isBlank()) notice else "$partial\n$notice",
                124,
            )
        }

        Pair(output.toString(), exitCode)
    }

    /** Build the argv and environment, mirroring PersistentShell.startProcess. */
    private fun spawn(command: String, envVars: Map<String, String>, statusGuestPath: String): Process {
        val rootfsManager = RootfsManager.getInstance(context)
        val cmd = mutableListOf<String>()
        if (rootfsManager.isProotAvailable) {
            cmd.add(rootfsManager.prootBinary.absolutePath)
            cmd.add("-0")
            cmd.add("--link2symlink")
            cmd.add("-r"); cmd.add(rootfsManager.rootfsDir.absolutePath)
            cmd.add("-b"); cmd.add("/dev")
            cmd.add("-b"); cmd.add("/proc")
            cmd.add("-b"); cmd.add("/sys")
            cmd.add("-w"); cmd.add("/root")
            for ((linuxPath, hostPath) in sessionBindMounts) {
                cmd.add("-b"); cmd.add("$hostPath:$linuxPath")
            }
            val handlers = NativeOffloadServer.registeredHandlers
            // [T-native-offload-bind] Only when our server is listening: otherwise
            // the handler stubs run and report the CLI unavailable, instead of
            // proot connecting to a name another process holds (and hanging).
            if (handlers.isNotEmpty() && NativeOffloadServer.awaitListening()) {
                cmd.add("--native-offload=${NativeOffloadServer.socketName}:${handlers.joinToString(",")}")
            }
            // [T-android-fake-netlink] Same rtnetlink emulation the pooled shell
            // gets; the two paths must not disagree about whether the sandbox has
            // network interfaces.
            cmd.add("--fake-netlink")
            // [FEAT-261021-11] Shell transparent forwarding (see PRootKernel).
            cmd.addAll(com.openminis.app.network.ShellTransparentNet.prootArgs(rootfsManager.prootBinary))
            // [T-android-shell-fresh-process] Step 4: run the command as its own
            // process-group leader.
            cmd.addAll(ForegroundCommandGroup.wrapForFreshProcess(command, statusGuestPath))
        } else {
            val hostStatusPath = File(rootfsManager.rootfsDir, statusGuestPath.removePrefix("/")).absolutePath
            val wrapped = ForegroundCommandGroup.wrapForFreshProcess(command, hostStatusPath)
            cmd.add("/system/bin/sh")
            cmd.addAll(wrapped.drop(1))
        }

        val debugOffload = com.openminis.app.BuildConfig.DEV_TOOLS
        val pb = ProcessBuilder(cmd)
        if (!rootfsManager.isProotAvailable) {
            pb.directory(File(rootfsManager.rootfsDir, "root").apply { mkdirs() })
        }
        configureStdin(pb)
        // Separate in debug so proot's [native_offload] lines reach logcat
        // instead of the output — which is why execute() starts a StderrDrain
        // whenever SEPARATE_STDERR is set.
        pb.redirectErrorStream(!SEPARATE_STDERR)

        val env = pb.environment()
        env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
        if (PRootKernel.nativeLibDir.isNotEmpty()) env["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
        if (PRootKernel.prootLoaderPath.isNotEmpty()) env["PROOT_LOADER"] = PRootKernel.prootLoaderPath
        if (PRootKernel.prootLoader32Path.isNotEmpty()) env["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
        env["TERM"] = "dumb"
        env["PS1"] = ""
        env["TZ"] = PRootKernel.posixTz()
        if (debugOffload) env["MINIS_NOFF_DEBUG"] = "1"
        env["MINIS_CHAT_SESSION_ID"] = sessionId
        for ((k, v) in PRootKernel.customEnvironment) env[k] = v
        // [T-net-android-phase3] Settings > Network "Shell" route: proxy
        // variables for the local entry (empty = direct, unchanged). Before
        // the caller's / user's own variables, which may override them.
        env.putAll(com.openminis.app.network.NetworkShellEnvironment.proxyVariables())
        // User-configured variables go in as real environment entries rather
        // than `export` lines written to a shared stdin — one more thing the
        // warm path had to do that this one does not.
        for ((k, v) in envVars) env[k] = v
        if (useNoSeccomp) {
            env[SeccompFallbackPolicy.NO_SECCOMP_ENV] = SeccompFallbackPolicy.NO_SECCOMP_VALUE
        }
        // [T-shell-stall-yes-head] SIGPIPE unblocked in the child; see SpawnSignalMask.
        return SpawnSignalMask.start(pb)
    }

    /**
     * [T-android-shell-fresh-process] Step 5: cancel the running command.
     *
     * This is what the Stop button reaches. It reuses the same whole-tree kill
     * as the timeout path, because the failure it prevents is identical: a
     * SIGKILL to the proot process alone leaves traced guest descendants
     * running and holding the pipe open.
     *
     * Safe to call when nothing is running, and safe to call before [execute]
     * has spawned — [stopped] is checked immediately after the spawn so a
     * Stop racing a starting command still takes effect.
     */
    fun stop() {
        stopped = true
        val p = live ?: return
        // No isAlive gate: proot stays alive while it traces a detached
        // daemon, so "alive" says nothing about whether the command is.
        //
        // [T-android-stop-button-freeze] Stop is reached from the chat's Stop
        // button, i.e. the main thread (ChatViewModel.cancelStream ->
        // ExecutionCoordinator.stopCurrentCommand). killCommand waits up to
        // KILL_SETTLE_MS for proot to exit before closing the streams, and with
        // no read flag to watch that wait is always the full second - per
        // running command. A field log showed 1.02 s between "cancelStream
        // invoked" and the next line of the same call; with two commands
        // running users saw ~2 s. The kill and its settle now run off the
        // caller's thread; the command's read loop still ends the same way.
        kotlin.concurrent.thread(name = "FreshShellStop", isDaemon = true) {
            killCommand(p, readEof = null)
            Log.i(TAG, "[$sessionId] stopped by user, command killed")
        }
    }

    /** The process currently running, or null between commands. */
    @Volatile private var live: Process? = null

    /** The running command's status file (its `.pid` sibling names the group). */
    @Volatile private var statusHandle: File? = null

    /** Set once [stop] is called; this instance runs one command only. */
    @Volatile private var stopped: Boolean = false

    /**
     * [T-android-fresh-exit-status-file] The command's exit code, once the
     * read has ended.
     *
     * The status file is authoritative: it is the COMMAND's status, written
     * by the runner after the command exited. proot's own exit value is only
     * the fallback for a command that never got that far (spawn failure, a
     * seccomp crash of the guest shell — whose 135/139 the seccomp fallback
     * keys on — or a group killed by a timeout or Stop).
     *
     * [T-android-mcp-detached-daemon-hang] Never an unbounded wait. When the
     * completion monitor closed the stream, proot is deliberately still
     * running — it is the tracer of a detached daemon, which breaks if its
     * tracer dies — so it is neither waited on nor killed.
     */
    private fun resolveExitCode(process: Process, statusFile: File, closedOnStatus: Boolean): Int {
        if (closedOnStatus) return readStatus(statusFile) ?: 0
        val exited = runCatching {
            process.waitFor(REAP_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        val processExit = if (exited) runCatching { process.exitValue() }.getOrNull() else null
        return readStatus(statusFile) ?: processExit ?: 0
    }

    /** The runner's recorded exit status, or null when it never wrote one. */
    private fun readStatus(statusFile: File): Int? {
        // The runner's printf may still be mid-write when the file first
        // appears; a short retry covers that without ever blocking for long.
        repeat(3) {
            val v = runCatching { statusFile.readText().trim().toIntOrNull() }.getOrNull()
            if (v != null) return v
            if (!statusFile.exists()) return null
            Thread.sleep(10)
        }
        return null
    }

    /**
     * [T-android-fresh-exit-status-file] Kill the running command — its whole
     * process group — for a timeout or a Stop, and make sure the read ends.
     *
     * The group is named by the runner's pid file: setsid made the runner the
     * group leader, and proot does not virtualise pids, so the guest pid is
     * the host pid. `Os.kill` takes a negative pid as "this group", unlike
     * `Process.sendSignal`, which silently drops any pid <= 0. Everything the
     * command started in its own group dies with it; what it deliberately
     * detached into another session (a daemon) does not — and neither does
     * proot, which may still be that daemon's tracer. Killing proot here is
     * what used to leave `minis-mcp-cli`'s daemon running untraced, with every
     * later MCP call failing against it.
     *
     * If proot had nothing else to trace it exits on its own and the read
     * reaches EOF; otherwise the stream is closed after a short grace so the
     * call returns on time either way. Only when the group is unknown — Stop
     * or a timeout landing before the runner wrote its pid — does this fall
     * back to the old whole-process kill.
     */
    private fun killCommand(process: Process, readEof: java.util.concurrent.atomic.AtomicBoolean?) {
        val pgid = statusHandle?.let { readPgid(File(it.path + ".pid")) }
        if (pgid == null) {
            killProcessTree(process)
            runCatching { process.destroyForcibly() }
        } else {
            runCatching { android.system.Os.kill(-pgid, android.system.OsConstants.SIGKILL) }
            val deadline = System.currentTimeMillis() + KILL_SETTLE_MS
            while (readEof?.get() != true && System.currentTimeMillis() < deadline) {
                runCatching { Thread.sleep(STATUS_POLL_MS) }
            }
        }
        // Belt and braces: if anything still holds the write end, the read
        // must not wait on it. Closing makes a blocked read fail at once.
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }

    /** The runner's group id, or null if absent or implausible. */
    private fun readPgid(pidFile: File): Int? {
        val v = runCatching { pidFile.readText().trim().toIntOrNull() }.getOrNull() ?: return null
        // 0 / 1 would mean "our own group" or init: never kill those.
        return if (v > 1) v else null
    }

    /** Drop status files a killed app left behind; they are tiny but unbounded. */
    private fun pruneStaleExitFiles(dir: File) {
        val cutoff = System.currentTimeMillis() - STALE_EXIT_FILE_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    /**
     * [T-android-shell-fresh-process] Kill [process] and every descendant it
     * left behind in the guest.
     *
     * `Process.pid()` is the host pid of proot. Its guest children are
     * ordinary host processes too (proot traces rather than containerises), so
     * they are reachable by walking /proc for anything whose parent is that
     * pid, and recursing. That walk is the reliable part; a negative-pgid
     * signal is attempted first because it reaches the whole group in one call
     * when the platform honours it.
     *
     * SIGKILL rather than SIGTERM: this runs only after the command has
     * already missed its deadline, and a tree that ignored the clock is not
     * owed a chance to ignore a polite signal too. Work deliberately detached
     * with its own `setsid`/`nohup` has left this group and is untouched —
     * verified on device for the warm path, and the mechanism is identical.
     */
    private fun killProcessTree(process: Process) {
        // `Process.pid()` is Java 9+ and is NOT on Android's API 26 baseline,
        // so read the pid the way Android has always exposed it: the private
        // `pid` field on the platform's ProcessImpl. Defensive throughout — a
        // failure to identify the pid must degrade to "kill only the process
        // we hold", never mask the timeout.
        val pid: Int = runCatching {
            val f = process.javaClass.getDeclaredField("pid")
            f.isAccessible = true
            f.getInt(process)
        }.getOrNull() ?: return
        // One shot at the whole group. Harmless if unsupported.
        //
        // [T-android-mcp-detached-daemon-hang] On Android 17 this is the ONLY
        // part that can work. /proc is mounted `hidepid=invisible` there (it
        // was `hidepid=2` on Android 13), so an app cannot see even its own
        // descendants' /proc entries: the walk below returns an empty list and
        // the whole sweep silently does nothing. Verified on a Pixel 6 —
        // logcat printed "process tree killed" while proot and every guest
        // child were still running 6 minutes later.
        //
        // The group kill does not depend on /proc being readable, so it stays
        // the primary mechanism; the walk is a best-effort supplement for
        // platforms that still expose the tree.
        runCatching { android.os.Process.sendSignal(-pid, 9) }
        // Then the walk, which does not depend on pgid semantics holding.
        for (child in descendantsOf(pid)) {
            runCatching { android.os.Process.sendSignal(child, 9) }
        }
    }

    /** Host pids whose ancestry reaches [root], nearest-last. */
    private fun descendantsOf(root: Int, depth: Int = 0): List<Int> {
        if (depth > MAX_TREE_DEPTH) return emptyList()
        val out = mutableListOf<Int>()
        val procDir = File("/proc")
        val entries = procDir.listFiles() ?: return out
        for (e in entries) {
            val childPid = e.name.toIntOrNull() ?: continue
            val ppid = runCatching {
                // /proc/<pid>/stat field 4 is the parent pid. The comm field
                // can contain spaces and parentheses, so split after the last
                // ')' rather than tokenising the whole line.
                val raw = File(e, "stat").readText()
                raw.substring(raw.lastIndexOf(')') + 2).split(' ')[1].toInt()
            }.getOrNull() ?: continue
            if (ppid == root) {
                out += descendantsOf(childPid, depth + 1)
                out += childPid
            }
        }
        return out
    }

    private var lineBuffer = StringBuilder()

    /** Emit whole lines to [callback], holding a partial trailing line. */
    private fun feedLines(text: String, callback: (String) -> Unit) {
        lineBuffer.append(text)
        var idx = lineBuffer.indexOf("\n")
        while (idx >= 0) {
            callback(lineBuffer.substring(0, idx))
            lineBuffer.delete(0, idx + 1)
            idx = lineBuffer.indexOf("\n")
        }
    }

    companion object {
        /**
         * [T-android-shell-process-budget] Leads the output when the process
         * could not be spawned at all - nothing ran, so GlobalShellThrottle
         * may queue the command and retry it.
         */
        const val SPAWN_FAILED_PREFIX = "[Failed to start shell:"

        /**
         * [T-android-parity-fixes] stdin reads /dev/null, so a command that
         * reads input gets EOF at once instead of waiting forever.
         *
         * ProcessBuilder's default stdin is a pipe to this process, and
         * nothing ever writes to it or closes it: `cat`, `read`, a bare
         * `python3` or an interactive prompt sat blocked until the timeout
         * (15 min by default), with no output to explain why. Unattended runs
         * (a scheduled task's prefilled command) had no one to notice at all.
         * iOS gives the command an immediate EOF; this matches it. Set on the
         * proot process itself, so every guest process inherits it.
         */
        internal fun configureStdin(pb: ProcessBuilder): ProcessBuilder =
            pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))

        private const val TAG = "FreshProcessShell"

        /** Debug builds keep stderr on its own pipe (drained by [StderrDrain]). */
        private val SEPARATE_STDERR = com.openminis.app.BuildConfig.DEV_TOOLS

        /** How long to let the stderr drain reach EOF after stdout has. */
        private const val STDERR_DRAIN_WAIT_MS = 2_000L

        /**
         * Depth limit for the descendant walk. A guest tree this deep is
         * pathological, and an unbounded recursion over /proc during a
         * timeout is a worse failure than missing a great-great-grandchild.
         */
        private const val MAX_TREE_DEPTH = 8

        /**
         * [T-android-mcp-detached-daemon-hang] How long to wait for proot to
         * exit after its output stream reached EOF (the pipe closing and the
         * process exiting are microseconds apart), so a wait is never unbounded.
         */
        private const val REAP_GRACE_MS = 2_000L

        /**
         * [T-android-fresh-exit-status-file] Directory under the rootfs (the
         * guest's "/") where the runner writes each command's status and pid.
         */
        const val EXIT_DIR_NAME = ".minis-exit"

        /** How often the completion monitor looks for the status file. */
        const val STATUS_POLL_MS = 25L

        /**
         * How long the output must have been silent — measured from both the
         * status file appearing and the last byte read — before the stream is
         * closed on a proot that stays alive. The command has already exited
         * when the status appears, so its output is already in the pipe; this
         * only lets the reader thread drain it.
         */
        const val OUTPUT_QUIET_MS = 150L

        /** Hard cap on that drain, for a detached process that keeps writing. */
        const val OUTPUT_DRAIN_CAP_MS = 2_000L

        /** Stderr wait when proot was left running (its EOF never comes). */
        private const val STDERR_SETTLE_MS = 200L

        /** After a group kill, how long to let proot exit before closing streams. */
        private const val KILL_SETTLE_MS = 1_000L

        /** Status files older than this belong to a process that died mid-command. */
        private const val STALE_EXIT_FILE_MS = 60 * 60_000L

        /**
         * [T-android-fresh-exit-status-file] Whether the stream may be closed
         * on a command whose status file appeared at [statusSeenAt]: its
         * output has been quiet for [OUTPUT_QUIET_MS], or [OUTPUT_DRAIN_CAP_MS]
         * has passed since the status appeared, whichever comes first.
         */
        fun outputSettled(now: Long, statusSeenAt: Long, lastReadAt: Long): Boolean {
            if (now - statusSeenAt >= OUTPUT_DRAIN_CAP_MS) return true
            return now - statusSeenAt >= OUTPUT_QUIET_MS && now - lastReadAt >= OUTPUT_QUIET_MS
        }

        /**
         * Exit code for a user-initiated Stop. Distinct from 124 (timeout) so
         * the agent loop can tell "you cancelled this" from "this ran too
         * long", which it retries differently.
         */
        const val CANCELLED_EXIT = 130
    }
}

// ─── Merged from HandlerStub.kt ──────────────────────────────────────────────────

/**
 * [T-native-offload-bind] The `/usr/local/bin/<handler>` stub every offload CLI
 * resolves to on PATH.
 *
 * On the normal path the stub never runs: proot's native_offload extension
 * intercepts the execve and serves the command from the app. It runs only
 * when that did not happen - the app could not bind its offload socket (the
 * launcher then leaves `--native-offload` off), or the offload call failed.
 * It used to be `exit 0`, so a broken offload looked like a successful
 * command with no output; now it says what is wrong and exits non-zero.
 */
internal object HandlerStub {
    /** What every earlier build wrote; upgraded in place on boot. */
    const val LEGACY_CONTENT = "#!/bin/sh\nexit 0\n"

    /** sysexits EX_UNAVAILABLE. */
    const val EXIT_UNAVAILABLE = 69

    fun content(name: String): String =
        "#!/bin/sh\n" +
            "echo \"$name: unavailable - Minis could not start its native offload service. " +
            "Restart the Minis app and try again.\" >&2\n" +
            "exit $EXIT_UNAVAILABLE\n"
}

// ─── Merged from MountedFolderCoordinator.kt ──────────────────────────────────────────────────

/**
 * Bridges the user-facing [MountedFoldersStore] to the parts of the app
 * that enforce read-only-mount semantics and feed bind specs into PRoot.
 * Mirrors iOS `MountedFolderCoordinator` but only the methods we actually
 * need on Android — there is no NSFileCoordinator equivalent here because
 * once Option A resolves a SAF tree URI to a real POSIX path, the kernel
 * (not the app) handles cross-process consistency.
 *
 * Consumers (per T219 spec):
 *  - T219-3 PRootKernel: [bindMountSpecs] to seed `proot -b` flags.
 *  - T219-3 FileWriteTool / FileEditTool: [requireWritable] /
 *    [isLinuxPathUnderReadOnlyMount] to short-circuit writes against
 *    locked mounts before touching disk.
 */
object MountedFolderCoordinator {

    /** Linux prefix under which all bind-mounted external folders live. */
    private const val MOUNTS_PREFIX = "/var/minis/mounts/"

    /**
     * Throws [ReadOnlyMountException] when [linuxPath] is inside an
     * effectively read-only mount. Paths outside `/var/minis/mounts/` and
     * paths inside writable mounts pass through silently.
     */
    fun requireWritable(linuxPath: String, store: MountedFoldersStore) {
        if (isLinuxPathUnderReadOnlyMount(linuxPath, store)) {
            throw ReadOnlyMountException(linuxPath)
        }
    }

    /**
     * True when [linuxPath] is `/var/minis/mounts/<name>/...` AND the
     * matching entry in [store] is currently locked (either OS-level
     * non-writable or user-toggled off via `Allow writes`).
     *
     * Returns false for paths outside the mounts tree, for unknown mount
     * names (defensive: an unknown name means there's no enforceable
     * read-only contract — let other layers decide), and for fully
     * writable mounts.
     */
    fun isLinuxPathUnderReadOnlyMount(linuxPath: String, store: MountedFoldersStore): Boolean {
        if (!linuxPath.startsWith(MOUNTS_PREFIX)) return false
        val tail = linuxPath.substring(MOUNTS_PREFIX.length)
        // Match `<name>` or `<name>/...` exactly — don't treat
        // `/var/minis/mounts/foobar` as inside a mount named `foo`.
        val name = tail.substringBefore('/')
        if (name.isEmpty()) return false
        val entry = store.entries.value.firstOrNull { it.name == name } ?: return false
        return !entry.effectiveWritable
    }

    /**
     * Snapshot of bind-mount specs PRoot should inject. Only entries with a
     * resolved POSIX path are returned — entries whose URI resolution
     * failed (e.g. cloud provider, removable volume unmounted) silently
     * drop out of the snapshot until their state changes.
     *
     * Returns `(linuxDir, hostPath)` pairs.
     */
    fun bindMountSpecs(store: MountedFoldersStore): List<Pair<String, String>> =
        store.entries.value
            .mapNotNull { e ->
                val host = e.resolvedHostPath ?: return@mapNotNull null
                "$MOUNTS_PREFIX${e.name}" to host
            }
}

/**
 * Thrown when a write tool is asked to modify a path inside a locked
 * external mount. The message is intentionally user-facing and matches
 * the wording surfaced in iOS so chat transcripts read the same way
 * across platforms.
 */
class ReadOnlyMountException(val linuxPath: String) : Exception(
    "$linuxPath is inside a read-only mounted folder and cannot be modified. " +
        "Toggle writability in Settings → Mount External Folders if this is a mistake.",
)

// ─── Merged from OffloadInFlight.kt ──────────────────────────────────────────────────

/**
 * [GH #453] The offload calls being served: how many hold a worker thread
 * (the admission count behind [NativeOffloadServer.MAX_CONCURRENT_CALLS]) and
 * which command each is running, so a pile of stuck workers names its cause
 * in the logs.
 */
internal class OffloadInFlight(private val clock: () -> Long = System::currentTimeMillis) {
    private val admitted = AtomicInteger(0)
    private val running = ConcurrentHashMap<Long, Pair<String, Long>>() // worker thread id -> (command, start ms)

    val count: Int get() = admitted.get()

    /** Take a slot, or false when [max] are taken. */
    fun tryAdmit(max: Int): Boolean {
        while (true) {
            val n = admitted.get()
            if (n >= max) return false
            if (admitted.compareAndSet(n, n + 1)) return true
        }
    }

    fun release() { admitted.decrementAndGet() }

    /** The calling worker is now running [command]. */
    fun begin(command: String) { running[Thread.currentThread().id] = command to clock() }

    fun end() { running.remove(Thread.currentThread().id) }

    /** `command:count(oldest Ns)`, most first; `none` when idle. */
    fun summary(): String {
        val now = clock()
        val groups = running.values.groupBy({ it.first }, { it.second })
        if (groups.isEmpty()) return "none"
        return groups.entries
            .sortedWith(compareByDescending<Map.Entry<String, List<Long>>> { it.value.size }.thenBy { it.key })
            .joinToString(",") { (cmd, starts) -> "$cmd:${starts.size}(oldest ${(now - starts.min()) / 1000}s)" }
    }

    companion object {
        /** Before the command is known. */
        val WORKER_THREAD_NAME = ThreadNames.of("offload-wk")

        /**
         * `mn-o-<command>`: the command without its `android-` / `minis-`
         * prefix, cut to the kernel's 15-byte comm, dots replaced (a dotted
         * name would keep its last 15 characters instead of its first).
         */
        fun workerThreadName(command: String): String {
            val short = command.removePrefix("android-").removePrefix("minis-").replace('.', '_')
            return ThreadNames.of("o-$short").take(15)
        }
    }
}

// ─── Merged from SeccompFallbackPolicy.kt ──────────────────────────────────────────────────

/**
 * [T-android-seccomp-selfheal / GH#186] Decide when a proot child's death looks
 * like the host kernel's seccomp fast path miscompiling our syscall filter,
 * and should be retried once with `PROOT_NO_SECCOMP=1`.
 *
 * ## Why this exists
 *
 * GH#186: on one reporter's ARM64 device, `/bin/true` and `uname` run fine
 * inside the Alpine rootfs but `apk --version` dies instantly with SIGBUS and
 * `apk update` with SIGSEGV. We could not reproduce it: on a Pixel 6 the very
 * same bytes (rootfs binaries verified byte-identical to the shipped tarball)
 * with the very same argv run `apk update` to completion. So the fault is not
 * in what we ship — it is in how the host kernel handles proot's
 * ptrace+seccomp interaction, which is exactly the class of bug proot itself
 * documents a workaround for: `PROOT_NO_SECCOMP=1` (see
 * deps/proot/src/tracee/event.c:97 and deps/proot/src/cli/cli.c:140, and
 * upstream's "Add PR_epoll_wait SIGSYS handler" for a kernel that mis-fires the
 * same way).
 *
 * The symptom distribution is the tell: `apk` is the **only dynamically linked
 * program** in the minirootfs — `/bin/true` and `uname` are busybox symlinks.
 * That points at the dynamic-loading/mmap path under ptrace, not at file
 * content, and it means any dynamically linked guest binary can be hit, not
 * just apk. Hence this is applied at the sandbox launch layer rather than
 * special-cased for apk.
 *
 * ## Why gate on "early" rather than retrying every signal death
 *
 * A retry is only safe when the crash cannot have had side effects the user
 * would not want repeated. A guest program that runs for a while and *then*
 * segfaults may already have written files, mutated the rootfs, or sent
 * network traffic; re-running it could double those effects, and its crash is
 * far more likely to be a genuine bug in that program than a host-kernel
 * quirk. The GH#186 signature is the opposite: death is effectively immediate,
 * during dynamic linking, before `main()` can do anything observable.
 *
 * So the trigger is deliberately narrow — **fatal signal AND died early AND
 * produced no output**. Normal non-zero exits (a failing build, `grep` finding
 * nothing, a user's own buggy program) never qualify, which is the safety
 * boundary the fix requires: this must not silently re-run arbitrary user
 * commands.
 */
object SeccompFallbackPolicy {

    /** Env var proot reads to skip installing its seccomp filter. */
    const val NO_SECCOMP_ENV = "PROOT_NO_SECCOMP"
    const val NO_SECCOMP_VALUE = "1"

    /**
     * Upper bound on "died during startup". Generous on purpose: a cold
     * dynamic link of apk pulling in libcrypto/libssl on a slow device is
     * still far under a second, while any program that ran long enough to do
     * real work is well past it. Raising this trades safety (re-running a
     * command that had side effects) for coverage, so it stays conservative.
     */
    const val EARLY_DEATH_MS = 1_500L

    /**
     * Shell/waitpid convention: a process killed by signal N reports 128+N.
     *
     * - 135 = 128+7  SIGBUS  — the reported `apk --version` failure
     * - 139 = 128+11 SIGSEGV — the reported `apk update` / `apk add` failure
     * - 132 = 128+4  SIGILL  — same family: a mis-mapped/mis-restarted text
     *   segment lands on garbage instructions just as easily as on a bad
     *   address, so it is treated identically.
     * - 159 = 128+31 SIGSYS  — the seccomp filter itself killing the child.
     *   This is the most direct evidence of the suspected cause.
     *
     * Deliberately NOT included: SIGKILL (137) and SIGTERM (143), which are
     * how *we* stop a shell (timeouts, cancellation, [ShellExecutor.destroyCurrent],
     * Android low-memory kills). Retrying those would fight the caller's own
     * cancellation and re-run work the user just aborted. SIGABRT (134) is also
     * excluded: that is a program calling abort() on itself — a real bug in the
     * guest program, not a syscall-translation failure.
     */
    val RETRYABLE_EXIT_CODES = setOf(132, 135, 139, 159)

    /**
     * True when [exitCode] indicates death by one of the [RETRYABLE_EXIT_CODES]
     * signals, the process lived less than [EARLY_DEATH_MS], and it emitted no
     * output.
     *
     * The no-output condition matters as much as the timing: a program that
     * already printed something has begun executing its own logic, so its
     * crash is attributable to the program rather than to the loader. It also
     * keeps the retry from duplicating any output the caller already surfaced.
     *
     * @param exitCode process exit status, or null if it could not be observed
     *   (never retryable — we cannot tell what happened).
     * @param durationMs wall-clock time from spawn to exit.
     * @param producedOutput whether the child wrote anything to stdout/stderr.
     * @param alreadyRetried set once a retry has been spent, so this can only
     *   fire once per command (no retry loops).
     */
    fun shouldRetryWithoutSeccomp(
        exitCode: Int?,
        durationMs: Long,
        producedOutput: Boolean,
        alreadyRetried: Boolean,
    ): Boolean {
        if (alreadyRetried) return false
        if (producedOutput) return false
        if (exitCode == null || exitCode !in RETRYABLE_EXIT_CODES) return false
        return durationMs < EARLY_DEATH_MS
    }

    /** Human-readable signal name for diagnostics; null when not a tracked code. */
    fun signalName(exitCode: Int?): String? = when (exitCode) {
        132 -> "SIGILL"
        135 -> "SIGBUS"
        139 -> "SIGSEGV"
        159 -> "SIGSYS"
        else -> null
    }

    /**
     * One-line diagnostic emitted when the fallback fires. Greppable on
     * purpose: when a GH#186 reporter sends a log back, the presence of
     * `[proot-retry]` is what confirms this path was taken, and the absence of
     * it rules the seccomp hypothesis out rather than leaving it open.
     */
    fun retryLogLine(exitCode: Int?, durationMs: Long, what: String): String {
        val sig = signalName(exitCode) ?: "signal"
        return "[proot-retry] detected early $sig (exit=$exitCode) after ${durationMs}ms " +
            "in $what — retrying once with $NO_SECCOMP_ENV=$NO_SECCOMP_VALUE (GH#186)"
    }
}

// ─── Merged from SessionMounts.kt ──────────────────────────────────────────────────

/**
 * [T-android-session-private-mounts] The ONE place a shell's `/var/minis`
 * bind mounts are decided — for the agent's shells (ExecutionCoordinator →
 * PersistentShell / FreshProcessShell) and the interactive terminal alike.
 *
 * Why this exists: each chat session has its own `workspace`, `attachments`,
 * `offloads` and `browser` directories, but they used to be ALSO written into
 * the process-global [PRootKernel.bindMounts] every time a shell was built.
 * That map is last-writer-wins, so whichever session built a shell most
 * recently decided what `/var/minis/workspace` meant for every reader of the
 * global map — including the interactive terminal, which replayed it
 * verbatim and so showed a random session's files. The per-session mounts
 * now live only in the argv of the shell that owns them; the global map keeps
 * only what is genuinely global (`memory`, `skills`, `shared`, `mcp-servers`,
 * `mounts/<name>`).
 *
 * A directory that cannot be created is left OUT of the mounts, with an error
 * log, rather than passed through. PRoot does not fail on a missing `-b` host
 * path — it prints one WARNING and silently drops the binding — so the guest
 * would see the empty rootfs placeholder and "successfully" write files that
 * no reader will ever find. Deciding here makes the loss observable.
 */
object SessionMounts {
    private const val TAG = "SessionMounts"

    /** Subdirs under `minis-sessions/<sessionId>/`, one set per chat session. */
    val SESSION_SUBDIRS = listOf("attachments", "offloads", "workspace", "browser")

    /** Subdirs under `minis-global/`, shared by every session. */
    val GLOBAL_SUBDIRS = listOf("memory", "skills", "shared", "mcp-servers")

    /**
     * Soft ceiling on the bytes the `-b` arguments add to PRoot's argv. The
     * kernel limit (ARG_MAX, shared with envp) is far higher; this only
     * warns early if external mounts ever grow to where it could matter.
     */
    const val ARGV_WARN_BYTES = 64 * 1024

    data class Built(
        /** Linux path → host path, in bind order. */
        val mounts: Map<String, String>,
        /** Linux paths left out because their host directory is unusable. */
        val skipped: List<String>,
    )

    fun sessionDir(filesDir: File, sessionId: String, subdir: String): File =
        File(filesDir, "minis-sessions/$sessionId/$subdir")

    /**
     * Mounts for a shell. [sessionId] null means a shell tied to no chat (the
     * global terminal): it gets the global dirs and external mounts only, so
     * `/var/minis/workspace` there is the rootfs placeholder rather than some
     * other session's workspace.
     *
     * All directories are prepared and checked first and the map is built
     * after, so a failure report names every unusable directory at once.
     */
    fun build(
        filesDir: File,
        sessionId: String?,
        externalMounts: List<Pair<String, String>> = emptyList(),
    ): Built {
        val candidates = linkedMapOf<String, File>()
        if (sessionId != null) {
            for (sub in SESSION_SUBDIRS) candidates["/var/minis/$sub"] = sessionDir(filesDir, sessionId, sub)
        }
        val globalBase = File(filesDir, "minis-global")
        for (sub in GLOBAL_SUBDIRS) candidates["/var/minis/$sub"] = File(globalBase, sub)

        val mounts = linkedMapOf<String, String>()
        val skipped = mutableListOf<String>()
        for ((linux, dir) in candidates) {
            if (ensureDir(dir)) mounts[linux] = dir.absolutePath else skipped += linux
        }
        // External (SAF-picked) folders are the user's, not ours to create,
        // and are passed through as before: under scoped storage a Java
        // `isDirectory` can say false for a tree the process can still reach,
        // so dropping on that answer would take working mounts away. Only log.
        for ((linux, host) in externalMounts) {
            mounts[linux] = host
            if (!File(host).isDirectory) Log.w(TAG, "external mount $linux -> $host is not visible as a directory")
        }
        if (skipped.isNotEmpty()) {
            Log.e(
                TAG,
                "session=${sessionId ?: "<global>"} skipped ${skipped.size} bind mount(s) whose host dir " +
                    "is missing or cannot be created: $skipped — the guest will see the rootfs placeholder there",
            )
        }
        return Built(mounts, skipped)
    }

    /** [build] with the app's files dir and the current external mounts. */
    fun forContext(context: Context, sessionId: String?): Built =
        build(context.filesDir, sessionId, externalMountsSnapshot())

    /** `-b host:linux` pairs for [mounts], with a soft size warning. */
    fun toProotArgs(mounts: Map<String, String>): List<String> {
        val args = ArrayList<String>(mounts.size * 2)
        var bytes = 0
        for ((linux, host) in mounts) {
            val spec = "$host:$linux"
            args += "-b"
            args += spec
            bytes += spec.length + 4 // "-b" + two NULs
        }
        if (bytes > ARGV_WARN_BYTES) {
            Log.w(TAG, "bind-mount argv is $bytes bytes (> $ARGV_WARN_BYTES) across ${mounts.size} mounts")
        }
        return args
    }

    /**
     * True when [dir] is a usable directory after trying to create it.
     * `mkdirs()` alone is not the answer: it returns false both on failure
     * and when the directory already exists (e.g. a concurrent creator won).
     */
    internal fun ensureDir(dir: File): Boolean {
        if (dir.isDirectory) return true
        dir.mkdirs()
        return dir.isDirectory
    }

    private fun externalMountsSnapshot(): List<Pair<String, String>> =
        PRootKernel.mountedFoldersStore?.entries?.value.orEmpty().mapNotNull { entry ->
            val host = entry.resolvedHostPath ?: return@mapNotNull null
            "/var/minis/mounts/${entry.name}" to host
        }
}

// ─── Merged from ShellExecutionStrategy.kt ──────────────────────────────────────────────────

/**
 * [T-android-shell-fresh-process] Which mechanism runs a `shell_execute`.
 *
 * ## Why this switch exists
 *
 * Command completion has always been detected by an IN-BAND marker: the host
 * appends `echo "__MINIS_DONE_<uuid>_EXIT_$?__"` to every command and the
 * reader scans stdout for it. That was not a shortcut — with a REUSED shell,
 * `java.lang.Process` offers no other option. `waitFor()` waits for the shell
 * itself, stdout is an undelimited byte stream, and `$?` lives only inside the
 * guest. Telling the host "this command ended, with this code" had to travel
 * through the one channel that existed.
 *
 * In-band signalling then failed the way in-band signalling always does, three
 * times in five months:
 *
 *  - a marker split across two `read()` calls was never matched, so the
 *    command hung until its timeout;
 *  - the fix for that (a hold-back buffer) swallowed every output shorter than
 *    the window — measured on a Pixel 6, EVERY command returned empty;
 *  - a timed-out command kept writing into the shared stdout, so its bytes
 *    were attributed to whichever command ran next (Issue #358).
 *
 * Each patch introduced the next edge case, because the defect is structural:
 * control and data share a channel that the command itself can write to.
 *
 * ## Why a fresh process is the way out, and why it is affordable
 *
 * With one process per command, `Process.waitFor()` returns the exit code
 * directly. There is no marker to split, no buffer to swallow bytes, no shared
 * stream to pollute, and nothing for a command to forge. The whole class of
 * bug stops being expressible.
 *
 * The original objection was cold-start cost. Measured on a Pixel 6, inside
 * this project's own PRoot Alpine guest, 20 runs each:
 *
 *     sh -c "echo hi"          4.2 ms
 *     sh -c "true"             5.7 ms
 *     setsid sh -c "echo hi"   8.5 ms
 *     FIFO control round-trip  24.3 ms   (the rejected out-of-band option)
 *
 * Against a per-tool-call host overhead of 65-90 ms, 4.2 ms is under 6%. A
 * `python3` interpreter start alone costs 68 ms. The premise that warm-shell
 * reuse pays for its complexity does not survive measurement.
 *
 * Two alternatives were tested on device and rejected:
 *
 *  - **FD 3 passthrough** — PRoot's ptrace layer does not carry fd 3 across
 *    `execve`. Both `pass_fds` and an explicit `dup2` fail with
 *    `/bin/sh: dup2(3,1): Bad file descriptor`.
 *  - **FIFO control pipe** — works in isolation, but a background process
 *    inheriting the write end holds the reader open forever. Reproduced twice:
 *    the read blocked and never returned. That is Issue #358's failure mode
 *    moved to a new channel, and 5.8x slower besides.
 *
 * ## Rollout
 *
 * The migration is staged so each step is verifiable on its own, and
 * [WARM_SHELL] stays available as a fallback for a release cycle. Only when
 * fresh-process has run as the default without incident do the marker,
 * carry-over buffer and single-slot callback come out.
 */
internal enum class ShellExecutionStrategy {
    /**
     * The long-lived pooled shell with in-band `__MINIS_DONE__` markers.
     * Retained as the rollback path; carries every defect described above.
     */
    WARM_SHELL,

    /**
     * One `/bin/sh -c` per command, completion read from the process exit
     * status. Matches iOS, which has always worked this way
     * (`ISHExecutionCoordinator`).
     */
    FRESH_PROCESS,

    ;

    companion object {
        /**
         * Step 5: [FRESH_PROCESS] is now what users get.
         *
         * Steps 1-4 built the path, matched its sandbox configuration to the
         * warm shell flag for flag, and made a timeout kill the whole guest
         * process tree. Verified on a Pixel 6: a timed-out command returns 124
         * on schedule, the next command in the same session runs in 0.12s
         * (Issue #358's wedge cannot occur — there is no shared stdin to queue
         * behind), the tree is fully reaped, and deliberately detached work
         * survives.
         *
         * [WARM_SHELL] stays reachable for a release cycle as the rollback,
         * and the marker/carry-over machinery it needs stays with it. Deleting
         * that in the same change that flips the default would leave no way
         * back if the new path misbehaves on a device we cannot test.
         */
        val DEFAULT: ShellExecutionStrategy = FRESH_PROCESS

        /**
         * Whether a strategy needs the in-band completion marker.
         *
         * The marker exists only because a reused shell cannot report a
         * command boundary any other way. A fresh process gets the exit code
         * from `waitFor()`, so appending a marker would be pure risk — one
         * more thing to split, swallow or forge.
         */
        fun needsInBandMarker(strategy: ShellExecutionStrategy): Boolean =
            strategy == WARM_SHELL

        /**
         * Whether a timeout has to recycle the shell afterwards.
         *
         * WARM_SHELL must: the runaway keeps writing into a stdout that later
         * commands read, so even a successful kill leaves bytes that would be
         * misattributed. A fresh process owns its pipes exclusively — when it
         * dies, they die, and the next command starts from nothing.
         */
        fun recyclesShellOnTimeout(strategy: ShellExecutionStrategy): Boolean =
            strategy == WARM_SHELL
    }
}

// ─── Merged from ShellExitCode.kt ──────────────────────────────────────────────────

/**
 * [T-android-parity-fixes] The single place a shell tool result is annotated
 * with its exit status.
 *
 * Two layers used to each append their own: the coordinator wrote
 * `\n(exit code: N)` and the chat layer then added ` (exit code N)`, so the
 * model read `…\n(exit code: 1) (exit code 1)` on every failure. The
 * coordinator keeps annotating (it knows 124 is reported by its own timeout
 * notice), and the chat layer only fills in a status the coordinator left
 * out, in the same format.
 */
internal object ShellExitCode {

    fun suffix(code: Int): String = "(exit code: $code)"

    /** [output] with its exit status appended, unless it already ends with it. */
    fun ensureSuffix(output: String, code: Int): String =
        if (output.trimEnd().endsWith(suffix(code))) output else "$output\n${suffix(code)}"
}

// ─── Merged from ShellTimeoutPolicy.kt ──────────────────────────────────────────────────

/**
 * Choose a shell-execution timeout based on the **command prefix**, matching
 * the tiered strategy iOS uses in `ISHExecutionCoordinator`. Agent calls tend
 * to cluster around a few command families with wildly different expected
 * durations:
 *
 *   - quick shell utilities (`ls`, `cat`, `grep`, `sed`, `awk`) finish in
 *     under a second and should fail fast if they don't
 *   - package installs (`apk add`, `pip install`, `npm install`) legitimately
 *     take 2–5 minutes and should not hit a tight timeout
 *   - build / compile commands (`make`, `cargo build`, `go build`) can run 10+
 *     minutes on non-trivial projects
 *   - long-running services (daemons, watch mode) are either cancelled by the
 *     user or preempted by the coordinator's wait-queue
 *
 * **Not wired yet** — this file publishes the policy so the next pass can
 * adopt it at the call site without churning the dirty [ExecutionCoordinator]
 * further. Callers pick a timeout by running the raw command line through
 * [ShellTimeoutPolicy.forCommand] and passing the result down.
 */
object ShellTimeoutPolicy {
    /** Default (baseline) timeout matching the existing call-site default. */
    const val DEFAULT_TIMEOUT_MS = 600_000L      // 10 min

    /** Quick tier — shell builtins, pure text utilities, metadata queries. */
    const val QUICK_TIMEOUT_MS = 60_000L         // 1 min

    /** Interactive / networked queries (ping, dig, curl one-shots). */
    const val NETWORK_TIMEOUT_MS = 180_000L      // 3 min

    /** Package installs — apk / pip / npm / gem / cargo install. */
    const val INSTALL_TIMEOUT_MS = 600_000L      // 10 min

    /** Build / compile — make, cargo build, go build, gradle, ninja. */
    const val BUILD_TIMEOUT_MS = 1_200_000L      // 20 min

    /** Long-running services / daemons — always allowed the full budget. */
    const val LONG_RUNNING_TIMEOUT_MS = 1_800_000L // 30 min

    private val quickPrefixes = setOf(
        "ls", "cat", "head", "tail", "wc", "grep", "rg", "egrep", "fgrep",
        "sed", "awk", "tr", "cut", "sort", "uniq", "find",
        "echo", "printf", "pwd", "which", "whoami", "id", "env", "date",
        "basename", "dirname", "file", "stat", "du", "df",
        "mkdir", "rmdir", "touch", "chmod", "chown", "cp", "mv", "rm", "ln",
    )

    private val networkPrefixes = setOf(
        "ping", "dig", "nslookup", "host", "traceroute",
        "curl", "wget", "http",
        "ssh", "scp", "rsync",
    )

    /** Install commands — any of these (possibly with subcommand) triggers. */
    private val installTokens = setOf(
        "apk", "apt", "apt-get", "yum", "dnf", "pacman", "brew",
        "pip", "pip3", "pipx",
        "npm", "pnpm", "yarn", "bun",
        "gem", "bundle",
        "go get", "go install",
    )

    /** Build / compile drivers. */
    private val buildPrefixes = setOf(
        "make", "gmake", "ninja", "cmake",
        "cargo", "rustc",
        "go build", "go test",
        "gradle", "gradlew", "mvn",
        "webpack", "vite", "rollup", "esbuild",
        "tsc", "swc", "babel",
    )

    /** Long-running / daemon drivers. */
    private val longRunningPrefixes = setOf(
        "watch",
        "node --watch", "npm run dev", "npm run start", "npm start",
        "python -m http.server",
        "serve", "http-server",
    )

    /**
     * Return a recommended timeout (ms) for [command]. Takes the first
     * non-whitespace token and consults the classifier tables above. Unknown
     * commands fall through to [DEFAULT_TIMEOUT_MS] so behavior stays
     * conservative for anything unrecognized.
     */
    fun forCommand(command: String): Long {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return DEFAULT_TIMEOUT_MS
        val lower = trimmed.lowercase()

        // Pipelines / shell-chains — take the first sub-command for classification.
        val firstSegment = lower.substringBefore("&&").substringBefore("||").substringBefore(";").trim()
        val firstToken = firstSegment.substringBefore(' ')

        // Check longest-match phrases first (go build vs go).
        if (longRunningPrefixes.any { firstSegment.startsWith(it) }) return LONG_RUNNING_TIMEOUT_MS
        if (buildPrefixes.any { firstSegment.startsWith(it) }) return BUILD_TIMEOUT_MS
        if (installTokens.any { firstSegment.startsWith(it) && firstSegment.containsInstallSubcommand() }) {
            return INSTALL_TIMEOUT_MS
        }

        if (firstToken in networkPrefixes) return NETWORK_TIMEOUT_MS
        if (firstToken in quickPrefixes) return QUICK_TIMEOUT_MS

        return DEFAULT_TIMEOUT_MS
    }

    /** Heuristic: treat bare `apk`, `pip` etc. without an install subcommand as neutral. */
    private fun String.containsInstallSubcommand(): Boolean {
        val installVerbs = listOf("install", "add", "get", "update", "upgrade")
        return installVerbs.any { " $it" in this } || this.startsWith("apk ") || this.startsWith("pip install")
    }
}

// ─── Merged from SpawnSignalMask.kt ──────────────────────────────────────────────────

/**
 * [T-shell-stall-yes-head] Starts agent shell processes with SIGPIPE unblocked.
 *
 * ART blocks SIGPIPE in every app thread (Runtime::BlockSignals), and
 * ProcessBuilder's child keeps the spawning thread's signal mask across
 * execve, through proot, into every guest process. Nothing on the way unblocks
 * it (busybox sh does not), so a writer whose reader had exited got EPIPE
 * instead of being killed. Most tools then exit on the error, but busybox
 * `yes STRING | head -c N` never notices it and spins forever: head exits,
 * yes keeps holding the merged stderr pipe open, and the shell_execute call
 * hangs until its timeout (15 min by default). Seen on a Pixel 4a as
 * "yes ... | head -c 50000 stuck for 6+ minutes"; reproduced on stock
 * Alpine 3.21 busybox by blocking SIGPIPE in the parent.
 *
 * The interactive terminal never had this problem: PtyBridge.forkExec clears
 * the mask in its own child. ProcessBuilder offers no such hook, so the mask
 * is cleared on the SPAWNING thread for the duration of start() and restored
 * right after. start() does not suspend, so the whole window stays on one
 * thread; the parent side only reads the exec-failure pipe, so no write of
 * this thread can raise SIGPIPE in the window. SIGQUIT / SIGUSR1 stay blocked
 * on purpose: they are process-directed, and an unblocked thread could steal
 * one meant for ART's signal catcher.
 */
internal object SpawnSignalMask {
    private const val TAG = "SpawnSignalMask"

    /** Bit set returned by [Native.unblockSigpipe]. */
    const val WAS_BLOCKED = 1
    const val DISPOSITION_IGNORED = 2

    internal interface Native {
        /** Bit set ([WAS_BLOCKED], [DISPOSITION_IGNORED]) or -errno. */
        fun unblockSigpipe(): Int

        /** 0 or -errno. */
        fun blockSigpipe(): Int
    }

    private object PtyNative : Native {
        override fun unblockSigpipe(): Int = PtyBridge.unblockSigpipe()
        override fun blockSigpipe(): Int = PtyBridge.blockSigpipe()
    }

    /** Swapped out in tests; the real one needs libpty_bridge. */
    @Volatile
    internal var native: Native = PtyNative

    @Volatile
    private var ignoredLogged = false

    /** Start [pb] with SIGPIPE unblocked in the child. */
    fun start(pb: ProcessBuilder): Process = withSigpipeUnblocked { pb.start() }

    /**
     * Run [block] with SIGPIPE unblocked on this thread, then restore the
     * previous mask. A native failure never stops the spawn: the command
     * then runs as it always did.
     */
    fun <T> withSigpipeUnblocked(block: () -> T): T {
        val state = try {
            native.unblockSigpipe()
        } catch (t: Throwable) {
            // UnsatisfiedLinkError / ExceptionInInitializerError: no native lib.
            Log.w(TAG, "SIGPIPE unblock unavailable: ${t.javaClass.simpleName}: ${t.message}")
            -1
        }
        if (state < 0) Log.w(TAG, "SIGPIPE unblock failed ($state); spawning with the inherited mask")
        if (state >= 0 && state and DISPOSITION_IGNORED != 0 && !ignoredLogged) {
            ignoredLogged = true
            Log.w(TAG, "SIGPIPE is SIG_IGN in this process; children inherit it and `yes | head` can still hang")
        }
        try {
            return block()
        } finally {
            if (state >= 0 && state and WAS_BLOCKED != 0) {
                val rc = runCatching { native.blockSigpipe() }.getOrElse { -1 }
                if (rc != 0) Log.e(TAG, "SIGPIPE re-block failed ($rc)")
            }
        }
    }
}

// ─── Merged from StderrDrain.kt ──────────────────────────────────────────────────

/**
 * [T-android-fresh-stderr-drain] Keeps a command's separate stderr pipe
 * flowing, and decides where each line goes.
 *
 * Debug builds (BuildConfig.DEV_TOOLS) spawn the fresh-process shell with
 * stderr NOT merged into stdout, so proot's `[native_offload]` debug lines
 * can go to logcat instead of into the command's output. The fresh path
 * copied that split from PersistentShell but not PersistentShell's stderr
 * reader thread, so nothing read the pipe at all. Once a command wrote 64 KB
 * to stderr — wget's progress meter on a ~40 MB download, pip, npm, a
 * compiler — the pipe buffer filled and the command blocked in write()
 * forever, with the download stalled mid-file. Observed on a Pixel 6: wget in
 * `pipe_write` for 10+ minutes, zero bytes read or written over a 10 s
 * sample, the .part file frozen at 42.9 MB. Release builds merge stderr and
 * were never affected.
 *
 * Lines are routed the way a release build would show them: our proot
 * extensions' debug lines (`[native_offload] `, `[fake_netlink] `) go to [log]; every other line is kept and
 * returned by [finish] so the caller can append it to the command output —
 * the model sees the command's errors in debug builds too, not just in
 * release. Stderr is appended after stdout rather than interleaved; the order
 * between the two streams is not recoverable once they are separate pipes.
 */
internal class StderrDrain(
    private val stream: InputStream,
    private val log: (String) -> Unit,
) {
    private val kept = StringBuilder()
    private val thread = Thread({ drain() }, "FreshProcessShell-stderr").apply { isDaemon = true }

    fun start(): StderrDrain = apply { thread.start() }

    private fun drain() {
        try {
            stream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                if (isProotDebugLine(line)) log(line) else synchronized(kept) { kept.append(line).append('\n') }
            }
        } catch (_: Exception) {
            // Closed by timeout / Stop / finish(): whatever was read is kept.
        }
    }

    /**
     * Wait at most [waitMs] for the drain to reach EOF, then close the stream
     * (a detached daemon may still hold the write end) and return the kept
     * text. Bounded so a lingering writer can never hang the shell call.
     */
    fun finish(waitMs: Long): String {
        runCatching { thread.join(waitMs) }
        runCatching { stream.close() }
        return synchronized(kept) { kept.toString() }
    }

    companion object {
        /**
         * Debug-only lines of our own proot extensions, printed when
         * MINIS_NOFF_DEBUG is set (DEV_TOOLS builds). `[fake_netlink] ` joined
         * when its startup line moved from note(INFO) to the debug switch;
         * `[xnet] ` is the transparent forwarding extension (FEAT-261021-11).
         */
        private val PROOT_DEBUG_PREFIXES = listOf("[native_offload] ", "[fake_netlink] ", "[xnet] ")

        fun isProotDebugLine(line: String): Boolean = PROOT_DEBUG_PREFIXES.any { line.startsWith(it) }
    }
}

// ─── Merged from TerminalSanitizer.kt ──────────────────────────────────────────────────

/**
 * Strips ANSI escape sequences and handles CR-based line overwrites
 * from terminal output. Corresponds to iOS AIChatViewModel.sanitizeTerminalOutput().
 */
object TerminalSanitizer {

    // Matches ANSI/VT escape sequences:
    //   ESC [ ... final_byte (CSI sequences)
    //   ESC ] ... ST (OSC sequences terminated by BEL or ESC\)
    //   ESC followed by single character (simple escapes)
    private val ANSI_REGEX = Regex(
        """\x1B(?:\[[0-9;]*[A-Za-z]|\][^\x07]*(?:\x07|\x1B\\)|\[[0-9;]*m|[()][0-2AB]|[A-Za-z])"""
    )

    /**
     * [T-android-longsession-sanitize-pretrim / GH#326] Ceiling applied to
     * [sanitize]'s INPUT, before its multi-pass rewrite.
     *
     * Sized at 8x the 50 000-char output cap rather than close to it, because
     * the trim happens before escape-stripping: the margin has to absorb
     * whatever the passes are about to delete, so that a result which would
     * have fitted under the final cap after cleaning is never shortened by
     * this. 400k chars is ~800 KB — bounded work for the passes, while still
     * far more text than the 50k that ultimately survives.
     */
    private const val PRE_SANITIZE_MAX_CHARS = 400_000

    /**
     * Sanitize terminal output in two passes:
     * 1. CR folding — simulate carriage return overwriting
     * 2. Strip remaining ANSI/VT escape sequences
     */
    fun sanitize(raw: String): String {
        if (raw.isEmpty()) return raw

        // [T-android-longsession-sanitize-pretrim / GH#326] Cut the input down
        // BEFORE the five full-copy passes below.
        //
        // Those passes each materialise another string the size of their input
        // (foldCarriageReturns builds a StringBuilder, then two Regex.replace,
        // a .filter, and a .lines()/joinToString), so peak transient memory is
        // several times the raw output — while the caller throws all but
        // [truncateIfNeeded]'s 50k away one line later. A command that dumps a
        // large file or a runaway build log therefore allocates hundreds of MB
        // of char[] for a result that was always going to be discarded, and
        // those large contiguous allocations are served by the allocator's
        // secondary (mmap) path — the documented shape of the
        // `Scudo ERROR: internal map failure` self-abort seen in the field.
        //
        // Pre-trimming keeps a generous margin over the final cap so the
        // visible result is unchanged in every realistic case: sanitising can
        // only ever SHRINK the text (it deletes escapes and control bytes), so
        // any input already under the margin is untouched, and for a larger one
        // the head/tail this keeps still bracket far more than the 50k that
        // survives. Only the discarded middle of an enormous dump is affected,
        // and that middle was never going to be shown.
        val pre = truncateIfNeeded(raw, PRE_SANITIZE_MAX_CHARS)

        // Pass 1: CR folding
        val crFolded = foldCarriageReturns(pre)

        // Pass 2: Strip ANSI sequences
        val stripped = ANSI_REGEX.replace(crFolded, "")

        // Pass 3: Remove null bytes and non-printable control chars (except \n \t)
        val cleaned = stripped.filter { it == '\n' || it == '\t' || it.code >= 0x20 }

        // Pass 4: Remove "null" artifacts from PRoot/pipe issues
        // - Lines that are entirely "null"
        // - Runs of repeated "null" (e.g., "nullnullnull" → "")
        // - Lines that are just "null" appended to a prefix (e.g., "file:nullnullnull")
        val noNullLines = cleaned.lines()
            .filter { it.trim() != "null" }
            .joinToString("\n")
            .replace(Regex("(?:null){2,}"), "") // Remove runs of 2+ consecutive "null"

        // Pass 5: Collapse excessive blank lines (3+ consecutive → 2)
        return noNullLines.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /**
     * Truncate output if it exceeds maxChars, keeping head and tail.
     */
    fun truncateIfNeeded(output: String, maxChars: Int = 50_000): String {
        if (output.length <= maxChars) return output

        val keepEach = maxChars / 2
        val head = output.substring(0, keepEach)
        val tail = output.substring(output.length - keepEach)
        val omitted = output.length - maxChars
        return "$head\n\n[... $omitted characters omitted ...]\n\n$tail"
    }

    /**
     * Simulate CR (\r) behavior: when a line contains \r (without \n),
     * the text after \r overwrites from the beginning of the line.
     *
     * Real terminal semantics, not "last segment wins": each \r moves the
     * cursor to column 0 and the next segment overwrites character by
     * character, so whatever the previous content had BEYOND the new
     * segment's length stays visible — `"AAAA\rBB"` renders as `"BBAA"`,
     * and a progress line that shrinks (`"  50%[=====>    ]\r100%"`) keeps
     * its old tail. Keeping only the last non-empty segment (the behavior
     * this replaced) silently dropped that tail whenever a later segment
     * was shorter. Empty segments (a trailing \r, or \r\r) move the cursor
     * without writing, so they are no-ops.
     *
     * [T-android-sanitize-cr-escapes] Escape sequences are ZERO-width here.
     * The fold used to run on the raw text, before escape stripping, so the
     * invisible bytes counted as screen columns: `Downloading 50%\r\e[KDone`
     * (pip / npm / apk / git progress) rendered as "Doneding 50%" because
     * ESC[K was treated as 3 printable chars instead of "erase to end of
     * line", and a coloured segment overwritten by a plain one kept the old
     * visible text while the overwrite landed on the colour codes. Lines with
     * a \r are now simulated cursor-wise: escapes are consumed without
     * advancing the cursor, EL (ESC[K / ESC[1K / ESC[2K) is interpreted, and
     * other control bytes (which pass 3 deletes anyway) take no column.
     * Lines without \r are untouched; pass 2 strips their escapes as before.
     *
     * O(line length): every char is visited once.
     */
    private fun foldCarriageReturns(text: String): String {
        val lines = text.split('\n')
        val result = StringBuilder()
        val matcher = ANSI_REGEX.toPattern().matcher("")

        for ((index, line) in lines.withIndex()) {
            if (index > 0) result.append('\n')

            if ('\r' !in line) {
                result.append(line)
                continue
            }

            matcher.reset(line)
            val buffer = StringBuilder(line.length)
            var cursor = 0
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (c == '\r') {
                    cursor = 0
                    i++
                    continue
                }
                if (c == '\u001B') {
                    matcher.region(i, line.length)
                    if (matcher.lookingAt()) {
                        val seq = matcher.group()
                        if (seq.length >= 3 && seq[1] == '[' && seq.last() == 'K') {
                            eraseInLine(buffer, cursor, seq.substring(2, seq.length - 1))
                        }
                        i = matcher.end()
                    } else {
                        i++ // lone ESC: zero-width, removed by pass 3
                    }
                    continue
                }
                if (c != '\t' && c.code < 0x20) {
                    i++ // non-printable: zero-width, removed by pass 3
                    continue
                }
                if (cursor < buffer.length) buffer.setCharAt(cursor, c) else buffer.append(c)
                cursor++
                i++
            }
            result.append(buffer)
        }

        return result.toString()
    }

    /** EL — `ESC[<param>K`: 0/empty = cursor to end, 1 = start to cursor, 2 = whole line. */
    private fun eraseInLine(buffer: StringBuilder, cursor: Int, param: String) {
        when (param) {
            "", "0" -> if (cursor < buffer.length) buffer.setLength(cursor)
            "1" -> for (k in 0..minOf(cursor, buffer.length - 1)) buffer.setCharAt(k, ' ')
            "2" -> {
                val keep = minOf(cursor, buffer.length)
                buffer.setLength(keep)
                for (k in 0 until keep) buffer.setCharAt(k, ' ')
            }
        }
    }
}

// ─── Merged from Utf8StreamDecoder.kt ──────────────────────────────────────────────────

/**
 * [T-android-pshell-utf8-split] Decodes a UTF-8 byte stream that arrives in
 * arbitrary chunks.
 *
 * PersistentShell reads the shell's output up to 4096 bytes at a time and used
 * to decode every read on its own (`String(buffer, 0, n, UTF_8)`). `read()`
 * boundaries fall anywhere, so a multi-byte character (3 bytes for CJK, 4 for
 * an emoji) cut by one came out as U+FFFD on both sides and the character was
 * lost. iOS fixed the same class of bug in bfdebc44b (GH #428).
 *
 * Here the bytes of an unfinished character at the end of a chunk are kept and
 * decoded together with the next chunk. Bytes that are invalid wherever the
 * cut falls still become U+FFFD, as before. [finish] reports what is left
 * when the stream ends in the middle of a character.
 *
 * Not thread-safe: one instance per stream, used from its reader thread.
 */
internal class Utf8StreamDecoder {

    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    /** The first bytes of a character whose rest has not been read yet (at most 3). */
    private var pending = ByteArray(0)

    /** The text complete so far; may be empty when [length] bytes only continue a character. */
    fun decode(bytes: ByteArray, offset: Int, length: Int): String {
        val input = ByteBuffer.allocate(pending.size + length)
        input.put(pending).put(bytes, offset, length).flip()
        // UTF-8 never yields more chars than bytes (a replacement is one char
        // for at least one byte).
        val out = CharBuffer.allocate(input.remaining())
        decoder.decode(input, out, false)
        pending = ByteArray(input.remaining()).also { input.get(it) }
        return out.flip().toString()
    }

    /** End of stream: an unfinished character becomes U+FFFD. Empty when nothing was pending. */
    fun finish(): String {
        val input = ByteBuffer.wrap(pending)
        val out = CharBuffer.allocate(pending.size + 1)
        decoder.decode(input, out, true)
        decoder.flush(out)
        decoder.reset()
        pending = ByteArray(0)
        return out.flip().toString()
    }
}
