package com.openminis.app.diagnostics

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log
import android.view.Display
import android.view.FrameMetrics
import android.view.Window
import com.openminis.app.AppBuildInfo
import com.openminis.app.BuildConfig
import com.openminis.app.logging.AppLogger
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * [T-android-content-perf-diag] Summary-level content-shape diagnostics for the
 * markdown render perf path. Given a message body, computes a cheap structural
 * fingerprint (length, lines, paragraphs, table / code-block / math markers)
 * that pinpoints *which content structure* is driving a render hang — without
 * ever logging the body itself (no perf overhead, no privacy leak).
 *
 * Used by:
 *  - ChatScreen cold-open summary — one line per large message.
 *  - LargeContentGuard — at streaming degrade + stream-end markdown swap.
 *  - HangDetector — attaches the currently-rendering message's fingerprint to
 *    each JankDiag stall sample (via [currentRender]) so a Matcher/Pattern
 *    stack maps straight to "this message, this structure" from the log alone.
 */
/** Below this length a message never drives a render hang, so skip the summary. */
const val CONTENT_DIAG_MIN_CHARS = 5_000

object ContentDiag {

    data class Summary(
        val chars: Int,
        val lines: Int,
        val paragraphs: Int,
        val tableCount: Int,
        val codeBlockCount: Int,
        val mathCount: Int,
    ) {
        val hasTable: Boolean get() = tableCount > 0
        val hasCodeBlock: Boolean get() = codeBlockCount > 0
        val hasMath: Boolean get() = mathCount > 0

        /** Compact, greppable key=value fragment (no session/idx prefix). */
        fun asLogFields(): String =
            "chars=$chars lines=$lines paragraphs=$paragraphs " +
                "hasTable=$hasTable tableCount=$tableCount " +
                "hasCodeBlock=$hasCodeBlock codeBlockCount=$codeBlockCount " +
                "hasMath=$hasMath mathCount=$mathCount"
    }

    // Fenced code block opener/closer (``` or ~~~), start-of-line.
    private val FENCE = Regex("""(?m)^\s{0,3}(```|~~~)""")
    // A markdown table separator row: | --- | :---: | --- | (the load-bearing
    // line that turns pipe rows into a real table).
    private val TABLE_SEP = Regex("""(?m)^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)+\|?\s*$""")
    // Display math: $$…$$ or \[…\]. Counted as blocks (pairs → count via markers/2).
    private val DISPLAY_MATH_DOLLAR = Regex("""\$\$""")
    private val DISPLAY_MATH_BRACKET = Regex("""\\\[""")
    // Inline math: \(…\) or single-$…$ (rough — single-$ is only counted when it
    // is NOT part of a $$ run, to avoid double-counting display math).
    private val INLINE_MATH_PAREN = Regex("""\\\(""")

    /**
     * Compute the structural summary. O(n) over the text with a handful of
     * regex scans — cheap enough to call on the cold-open path, but callers
     * should still gate on a size threshold to avoid summarizing tiny bodies.
     */
    fun summarize(text: String): Summary {
        if (text.isEmpty()) return Summary(0, 0, 0, 0, 0, 0)
        val lines = text.count { it == '\n' } + 1
        // Paragraphs: runs separated by one-or-more blank lines.
        val paragraphs = text.split(Regex("\\n\\s*\\n"))
            .count { it.isNotBlank() }
            .coerceAtLeast(1)
        // Fenced code blocks = fence markers / 2 (round up an unclosed trailing fence).
        val fenceMarkers = FENCE.findAll(text).count()
        val codeBlockCount = (fenceMarkers + 1) / 2
        val tableCount = TABLE_SEP.findAll(text).count()
        val displayDollar = DISPLAY_MATH_DOLLAR.findAll(text).count() / 2
        val displayBracket = DISPLAY_MATH_BRACKET.findAll(text).count()
        val inlineParen = INLINE_MATH_PAREN.findAll(text).count()
        val mathCount = displayDollar + displayBracket + inlineParen
        return Summary(
            chars = text.length,
            lines = lines,
            paragraphs = paragraphs,
            tableCount = tableCount,
            codeBlockCount = codeBlockCount,
            mathCount = mathCount,
        )
    }

    // ─── Current-render fingerprint for HangDetector correlation ───────────────

    data class RenderMarker(val sessionId: String, val messageId: String, val summary: Summary)

    private val current = AtomicReference<RenderMarker?>(null)

    /** Called by the render path when it begins rendering a large message body. */
    fun setCurrentRender(sessionId: String, messageId: String, summary: Summary) {
        current.set(RenderMarker(sessionId, messageId, summary))
    }

    /** Cleared when the large render completes / the block leaves composition. */
    fun clearCurrentRender(messageId: String) {
        val cur = current.get()
        if (cur != null && cur.messageId == messageId) current.set(null)
    }

    /** For HangDetector: a greppable fragment for the message on-screen, or "". */
    fun currentRenderLogFields(): String {
        val cur = current.get() ?: return ""
        return " renderSession=${cur.sessionId} renderMsg=${cur.messageId} ${cur.summary.asLogFields()}"
    }
}

// ─── Merged from EnvironmentBanner.kt ──────────────────────────────────────────────────

/**
 * [T-android-mem-probe-trust] One-line device/environment banner, emitted once
 * per logging session.
 *
 * ## Why this exists
 *
 * The 2026-08-15 field log contained no device identity at all. The only clue
 * to which hardware produced it was an incidental logcat line mentioning
 * `/proc/vivo_rsc/…`. Without a banner, a triage session mistakenly compared
 * that log against a Pixel 4a reproduction and drew conclusions from the
 * mismatch — the vendor ROM was the single most load-bearing variable and it
 * was invisible.
 *
 * Every field report should answer, from the log alone: which device, which
 * ROM, how much RAM, what heap ceiling, and is the device already under
 * memory pressure. Those decide whether a repro on other hardware means
 * anything.
 *
 * Deliberately no IMEI/serial/account data — model and ROM identity only.
 */
object EnvironmentBanner {

    private const val TAG = "Env"

    fun log(context: Context) {
        try {
            val rt = Runtime.getRuntime()
            val mb = 1024L * 1024L

            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }

            // memoryClass = heap ceiling WITHOUT largeHeap;
            // largeMemoryClass = ceiling WITH android:largeHeap="true".
            // We ship largeHeap=true, so largeMemoryClass is the real limit —
            // and it varies per vendor, which is exactly what we need to know.
            val memClass = am?.memoryClass ?: -1
            val largeMemClass = am?.largeMemoryClass ?: -1

            AppLogger.info(
                TAG,
                "[Env] device=${Build.MANUFACTURER}/${Build.BRAND}/${Build.MODEL} " +
                    "device_codename=${Build.DEVICE} product=${Build.PRODUCT} " +
                    "android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} " +
                    "rom=${Build.DISPLAY} " +
                    "abi=${Build.SUPPORTED_ABIS.joinToString("|")}",
            )
            AppLogger.info(
                TAG,
                "[Env] heapCeilingMB=${rt.maxMemory() / mb} " +
                    "memoryClassMB=$memClass largeMemoryClassMB=$largeMemClass " +
                    "deviceTotalRamMB=${mi.totalMem / mb} deviceAvailRamMB=${mi.availMem / mb} " +
                    "lowMemThresholdMB=${mi.threshold / mb} deviceLowMemory=${mi.lowMemory} " +
                    "cpuCores=${rt.availableProcessors()}",
            )
            AppLogger.info(TAG, "[Env] memory ${MemorySnapshot.capture().toLogString()}")
        } catch (t: Throwable) {
            // Diagnostics must never take down a launch.
            AppLogger.warning(TAG, "[Env] banner failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}

// ─── Merged from JankDiag.kt ──────────────────────────────────────────────────

/**
 * [T-android-jankdiag] Targeted, always-on jank diagnostics for a field report
 * (120 Hz HUAWEI: chat / memory / env-var / group-list scrolling and transitions
 * at an estimated 20-40 fps, not reproducible on a 60 Hz Pixel 4a). Every line
 * starts with `[JankDiag]` at Info level, so the user only turns file logging on.
 *
 * What it answers, one suspect each:
 *  - device / refresh rate: build, device, display modes and changes, the window's
 *    preferred refresh rate, power saving, thermal status, ART compile state;
 *  - frame cost: a FrameMetrics window per 5 s, stage by stage, printed only when
 *    it has jank, tagged with the screen and whether a reply is streaming;
 *  - main thread: any message over 100 ms, with one stack per 10 s at most;
 *  - streaming: chunks in, UI publishes, parse time, newest-row recompositions;
 *  - opening a chat: click -> mount -> first layout -> first placement -> stable;
 *  - the log itself: lines per second and time spent writing on the main thread;
 *  - GC / heap / CPU / threads, in a 30 s heartbeat;
 *  - process resources (threads, memory mappings, fds - GH #453), sampled every
 *    30 s in the foreground AND the background, with a per-name thread count
 *    past a threshold and the newest sample kept on disk for the exit report;
 *  - Stop: cancelStream's own time and tap -> next frame;
 *  - transitions: every route change and the frames in the 1.5 s after it.
 *
 * Cost rules (guarded by JankDiagGuardTest): nothing is logged per frame or per
 * token; the main thread only stores timestamps and bumps counters; every log
 * line, /proc read, Display / PowerManager query and aggregation runs on the
 * "JankDiag" background thread.
 */
object JankDiag {
    private const val TAG = "JankDiag"
    private const val WINDOW_MS = 5_000L
    private const val HEARTBEAT_EVERY = 6 // windows: 30 s
    private const val STALL_MS = 100L
    private const val STACK_EVERY_MS = 10_000L
    private const val TRANSITION_MS = 1_500L
    private const val WATCH_MS = 50L
    private const val BACKGROUND_RES_EVERY = 10 // heartbeats: 5 min

    private val thread = HandlerThread("JankDiag", Process.THREAD_PRIORITY_BACKGROUND)
    private lateinit var bg: Handler
    private lateinit var app: Application
    @Volatile private var started = false

    // ── state written from the main thread (plain stores / counters only) ────
    @Volatile private var screen = "start"
    /** The navigation route's label, and a screen shown inside it (a chat in the split scaffold). */
    @Volatile private var routeLabel = "start"
    @Volatile private var overlayLabel: String? = null
    @Volatile private var streaming = false
    private val resumed = AtomicInteger(0)
    @Volatile private var msgStartNs = 0L
    private val msgSeq = AtomicLong(0)
    private val mainBusyNs = AtomicLong(0)
    @Volatile private var longestMsgNs = 0L
    private val streamChunks = AtomicInteger(0)
    private val streamPublishes = AtomicInteger(0)
    @Volatile private var streamChars = 0
    private val newestRecomposes = AtomicInteger(0)
    @Volatile private var cancelStartNs = 0L

    // ── state owned by the background thread ────────────────────────────────
    private val window = JankFrameWindow()
    private val beat = JankFrameWindow()
    private val transition = JankFrameWindow()
    private var transitionLabel: String? = null
    private var transitionEndsNs = 0L
    private val parse = JankMsSamples()
    private var windows = 0
    private var windowHadStreaming = false
    private var lastStackAtNs = 0L
    private var sampledSeq = -1L
    private val sampledStacks = HashMap<Long, String>()
    private var suppressedStalls = 0
    private var lastGc = LongArray(4)
    private var lastCpuMs = 0L
    private var pendingCancel: Pair<Long, Long>? = null
    private var displayChanges = 0
    private var lastDisplayLine = ""
    private var displayReportPending = false
    private val open = OpenTimeline()
    private val frameListeners = HashMap<Window, Window.OnFrameMetricsAvailableListener>()

    fun start(application: Application) {
        if (started) return
        started = true
        app = application
        thread.start()
        bg = Handler(thread.looper)
        installLooperMonitor()
        application.registerActivityLifecycleCallbacks(lifecycle)
        bg.post {
            banner()
            ResourceWatermark.pruneWatermarks(app.filesDir, Process.myPid())
            registerSystemListeners()
            bg.postDelayed(tick, WINDOW_MS)
            bg.postDelayed(watchdog, WATCH_MS)
        }
    }

    /** File logging was just switched on: repeat the banner so the new file has it. */
    fun onLoggingStarted() {
        if (started) bg.post { banner() }
    }

    // ── hooks (main thread; cheap) ───────────────────────────────────────────

    /** Route change (MainActivity's back-stack collector). */
    fun setScreen(route: String?) {
        routeLabel = jankScreenLabel(route)
        applyScreen()
    }

    /**
     * A screen shown without a route change of its own — on a phone a chat opens
     * inside the sessions route's split scaffold. ChatScreen calls this while it
     * is composed, so frames and transitions are tagged "chat".
     */
    fun enterScreen(label: String) {
        overlayLabel = label
        applyScreen()
    }

    fun leaveScreen(label: String) {
        if (overlayLabel == label) overlayLabel = null
        applyScreen()
    }

    private fun applyScreen() {
        val label = overlayLabel ?: routeLabel
        val from = screen
        if (label == from) return
        screen = label
        if (!started) return
        val now = System.nanoTime()
        bg.post {
            AppLogger.info(TAG, "[JankDiag] route $from -> $label streaming=$streaming")
            transition.reset()
            transitionLabel = "$from->$label"
            transitionEndsNs = now + TRANSITION_MS * 1_000_000
            bg.postDelayed({ reportTransition() }, TRANSITION_MS + 150)
        }
    }

    fun setStreaming(active: Boolean) { streaming = active }

    /** Every streamed chunk reaching the view model, before throttling. */
    fun onStreamChunk(totalChars: Int) { streamChunks.incrementAndGet(); streamChars = totalChars }

    /** A throttled publish of the streaming text to the UI. */
    fun onStreamPublish() { streamPublishes.incrementAndGet() }

    /** The chat list's newest row recomposed. */
    fun onNewestRowRecompose() { newestRecomposes.incrementAndGet() }

    /** One off-main parse tick of the live markdown tail. */
    fun onStreamParse(fragLen: Int, ms: Double) {
        if (started) bg.post { parse.add(ms); streamChars = maxOf(streamChars, fragLen) }
    }

    /** A chat row was tapped in the session list. */
    fun beginOpen(sessionId: String) {
        if (!started) return
        val t = System.nanoTime()
        val busy = mainBusyNs.get()
        longestMsgNs = 0L
        bg.post { open.begin(sessionId, t, busy) }
    }

    /** A named step of opening a chat (PerfLongCtx step names). */
    fun openStage(sessionId: String, stage: String) {
        if (!started) return
        val t = System.nanoTime()
        bg.post { open.stage(sessionId, stage, t) }
    }

    /** The newest row laid out for the first time: the chat is on screen. */
    fun openStable(sessionId: String, messages: Int, rows: Int, prewarmMs: Long) {
        if (!started) return
        val t = System.nanoTime(); val busy = mainBusyNs.get(); val longest = longestMsgNs
        bg.post { open.stable(sessionId, t, busy, longest, messages, rows, prewarmMs)?.let { AppLogger.info(TAG, it) } }
    }

    fun onCancelStreamStart() { cancelStartNs = System.nanoTime() }

    fun onCancelStreamEnd() {
        val start = cancelStartNs
        if (!started || start == 0L) return
        val end = System.nanoTime()
        bg.post { pendingCancel = start to end }
    }

    // ── main-thread message monitor ─────────────────────────────────────────

    /**
     * Looper message timing through the looper's Printer: two string checks and a
     * few stores per message. A message over [STALL_MS] is handed to the
     * background thread; the watchdog samples the stack while it is still running.
     */
    private fun installLooperMonitor() {
        Looper.getMainLooper().setMessageLogging { line ->
            if (line.startsWith(">>>>>")) {
                msgSeq.incrementAndGet()
                msgStartNs = System.nanoTime()
            } else if (line.startsWith("<<<<<")) {
                val start = msgStartNs
                msgStartNs = 0L
                if (start != 0L) {
                    val d = System.nanoTime() - start
                    mainBusyNs.addAndGet(d)
                    if (d > longestMsgNs) longestMsgNs = d
                    if (d > STALL_MS * 1_000_000) {
                        val seq = msgSeq.get()
                        val label = screen
                        val s = streaming
                        bg.post { reportStall(seq, d, label, s) }
                    }
                }
            }
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            val start = msgStartNs
            val now = System.nanoTime()
            val seq = msgSeq.get()
            if (start != 0L && now - start > STALL_MS * 1_000_000 && seq != sampledSeq &&
                now - lastStackAtNs > STACK_EVERY_MS * 1_000_000
            ) {
                sampledSeq = seq
                lastStackAtNs = now
                sampledStacks[seq] = jankStackLine(Looper.getMainLooper().thread.stackTrace)
            }
            bg.postDelayed(this, if (resumed.get() > 0) WATCH_MS else WATCH_MS * 20)
        }
    }

    private fun reportStall(seq: Long, durationNs: Long, label: String, streaming: Boolean) {
        val stack = sampledStacks.remove(seq)
        if (stack == null && durationNs < 1_000_000_000L) {
            // Stacks are sampled at most once per 10 s; unsampled stalls under a
            // second are only counted, into the heartbeat.
            suppressedStalls++
            return
        }
        AppLogger.info(
            TAG,
            "[JankDiag] mainStall ms=${durationNs / 1_000_000} screen=$label streaming=$streaming" +
                (stack?.let { " stack=$it" } ?: " stack=(not sampled)"),
        )
    }

    // ── frames ──────────────────────────────────────────────────────────────

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumed.incrementAndGet()
            val w = activity.window
            if (frameListeners.containsKey(w)) return
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ -> onFrame(metrics) }
            frameListeners[w] = listener
            runCatching { w.addOnFrameMetricsAvailableListener(listener, bg) }
            val attrs = w.attributes
            val pref = attrs.preferredRefreshRate
            val mode = attrs.preferredDisplayModeId
            val name = activity.javaClass.simpleName
            bg.post { AppLogger.info(TAG, "[JankDiag] window activity=$name preferredRefreshRate=$pref preferredDisplayModeId=$mode") }
        }

        override fun onActivityPaused(activity: Activity) {
            resumed.decrementAndGet()
            val w = activity.window
            frameListeners.remove(w)?.let { runCatching { w.removeOnFrameMetricsAvailableListener(it) } }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private val stageBuf = LongArray(JankFrameWindow.Stage.entries.size)

    /** Background thread (the listener's handler): store one frame, never log. */
    private fun onFrame(m: FrameMetrics) {
        if (m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return
        val st = JankFrameWindow.Stage.entries
        stageBuf[st.indexOf(JankFrameWindow.Stage.TOTAL)] = m.getMetric(FrameMetrics.TOTAL_DURATION)
        stageBuf[st.indexOf(JankFrameWindow.Stage.INPUT)] = m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION)
        stageBuf[st.indexOf(JankFrameWindow.Stage.ANIMATION)] = m.getMetric(FrameMetrics.ANIMATION_DURATION)
        stageBuf[st.indexOf(JankFrameWindow.Stage.LAYOUT_MEASURE)] = m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
        stageBuf[st.indexOf(JankFrameWindow.Stage.DRAW)] = m.getMetric(FrameMetrics.DRAW_DURATION)
        stageBuf[st.indexOf(JankFrameWindow.Stage.SYNC)] = m.getMetric(FrameMetrics.SYNC_DURATION)
        stageBuf[st.indexOf(JankFrameWindow.Stage.COMMAND_ISSUE)] = m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)
        stageBuf[st.indexOf(JankFrameWindow.Stage.SWAP)] = m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
        stageBuf[st.indexOf(JankFrameWindow.Stage.GPU)] =
            if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.GPU_DURATION) else 0L
        val deadline = if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.DEADLINE) else vsyncPeriodNs
        val vsync = m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        window.add(stageBuf, deadline, vsync)
        beat.add(stageBuf, deadline, vsync)
        if (transitionLabel != null && vsync in 1..transitionEndsNs) transition.add(stageBuf, deadline, vsync)
        if (streaming) windowHadStreaming = true
        pendingCancel?.let { (start, end) ->
            if (vsync >= start) {
                pendingCancel = null
                val frameDone = vsync + stageBuf[0]
                AppLogger.info(
                    TAG,
                    "[JankDiag] stop cancelStreamMs=${(end - start) / 1_000_000} tapToNextFrameMs=${(frameDone - start) / 1_000_000} screen=$screen",
                )
            }
        }
    }

    @Volatile private var vsyncPeriodNs = 16_666_666L

    private fun reportTransition() {
        val label = transitionLabel ?: return
        if (System.nanoTime() < transitionEndsNs) return
        if (transition.count > 0) AppLogger.info(TAG, "[JankDiag] transition $label ${transition.summary()}")
        transitionLabel = null
        transition.reset()
    }

    // ── periodic summary (background) ───────────────────────────────────────

    private val tick = object : Runnable {
        override fun run() {
            try {
                summarize()
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "[JankDiag] summary failed: ${t.message}")
            }
            bg.postDelayed(this, WINDOW_MS)
        }
    }

    private fun summarize() {
        windows++
        val chunks = streamChunks.getAndSet(0)
        val publishes = streamPublishes.getAndSet(0)
        val recomposes = newestRecomposes.getAndSet(0)
        val secs = WINDOW_MS / 1000.0
        val log = AppLogger.drainStats()
        if (window.worthReporting()) {
            AppLogger.info(
                TAG,
                "[JankDiag] frames screen=$screen streaming=$windowHadStreaming refresh=${currentRefresh()} ${window.summary()} " +
                    "logLines/s=${"%.0f".format(log.lines / secs)} mainLog calls=${log.mainCalls} ms=${"%.1f".format(log.mainNs / 1e6)} max=${"%.1f".format(log.mainMaxNs / 1e6)}",
            )
        }
        if (chunks > 0 || publishes > 0 || parse.total > 0) {
            AppLogger.info(
                TAG,
                "[JankDiag] stream screen=$screen chunks/s=${"%.1f".format(chunks / secs)} publishes/s=${"%.1f".format(publishes / secs)} " +
                    "chars=$streamChars parse(off-main) n=${parse.total} p50=${"%.1f".format(parse.percentile(.5))} " +
                    "p90=${"%.1f".format(parse.percentile(.9))} max=${"%.1f".format(parse.maxMs)} newestRecompose/s=${"%.1f".format(recomposes / secs)}",
            )
        }
        parse.reset()
        window.reset()
        windowHadStreaming = false
        if (displayReportPending) { displayReportPending = false; reportDisplay("changed x$displayChanges"); displayChanges = 0 }
        if (windows % HEARTBEAT_EVERY == 0) {
            val res = resources()
            if (resumed.get() > 0) heartbeat(log, res)
            // In the background the frame / GC fields mean little, but GH #453's
            // process grew for hours with nothing on screen: one line per 5 min.
            else if (windows % (HEARTBEAT_EVERY * BACKGROUND_RES_EVERY) == 0) {
                AppLogger.info(TAG, "[JankDiag] res background ${res.fields()}")
            }
        }
    }

    // ── process resources (GH #453) ─────────────────────────────────────────

    private val alertGate = ResourceWatermark.AlertGate()
    private val clock = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
    private var lastBreakdown: String? = null
    private var lastMapsBreakdown: String? = null

    /**
     * Background thread only. Samples, prints the per-name thread count when
     * [ResourceWatermark.AlertGate] says so, and replaces this process's
     * water-mark file. Never throws.
     */
    private fun resources(): ResourceWatermark.Sample {
        val s = ResourceWatermark.sample()
        val now = System.currentTimeMillis()
        try {
            if (alertGate.shouldReport(s, now)) {
                val names = ResourceWatermark.threadNames()
                val top = ResourceWatermark.breakdown(names)
                val offload = com.openminis.app.sandbox.NativeOffloadServer.inFlightSummary()
                lastBreakdown = "${clock.format(java.util.Date(now))} listed=${names.size} $top offloadInFlight=$offload"
                AppLogger.warning(
                    TAG,
                    "[JankDiag] threadsByName ${s.fields()} listed=${names.size} " +
                        "(alert at threads>${ResourceWatermark.THREAD_ALERT} or maps>${ResourceWatermark.MAPS_ALERT}) top=$top " +
                        "offloadInFlight=$offload",
                )
                // [INV-261024-05] Past the maps line, also say which mappings.
                if (s.maps > ResourceWatermark.MAPS_ALERT) {
                    val maps = ResourceWatermark.mapsBreakdown()
                    lastMapsBreakdown = "${clock.format(java.util.Date(now))} $maps"
                    AppLogger.warning(TAG, "[JankDiag] mapsByName ${s.fields()} top=$maps")
                }
            }
            val uptimeS = (android.os.SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000
            ResourceWatermark.writeWatermark(
                app.filesDir,
                Process.myPid(),
                "sampled=${clock.format(java.util.Date(now))} uptimeS=$uptimeS screen=$screen foreground=${resumed.get() > 0} ${s.fields()}\n" +
                    "threadsByName=${lastBreakdown ?: "(not taken: below threshold)"}\n" +
                    "mapsByName=${lastMapsBreakdown ?: "(not taken: below threshold)"}\n",
            )
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[JankDiag] resource sample failed: ${t.javaClass.simpleName}")
        }
        return s
    }

    private fun heartbeat(log: AppLogger.Stats, res: ResourceWatermark.Sample) {
        val gc = gcStats()
        val d = LongArray(4) { gc[it] - lastGc[it] }
        lastGc = gc
        val cpu = Process.getElapsedCpuTime()
        val cpuDelta = cpu - lastCpuMs
        lastCpuMs = cpu
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val pm = app.getSystemService(PowerManager::class.java)
        val thermal = if (Build.VERSION.SDK_INT >= 29) pm?.currentThermalStatus ?: -1 else -1
        AppLogger.info(
            TAG,
            "[JankDiag] tick ${WINDOW_MS * HEARTBEAT_EVERY / 1000}s screen=$screen ${beat.summary()} " +
                "gc+=${d[0]} gcMs+=${d[1]} blockingGc+=${d[2]} blockingGcMs+=${d[3]} heapMB=$usedMb/${rt.maxMemory() / (1024 * 1024)} " +
                "cpuMs+=$cpuDelta ${res.fields()} stallsUnsampled=$suppressedStalls " +
                "thermal=$thermal powerSave=${pm?.isPowerSaveMode} refresh=${currentRefresh()}",
        )
        suppressedStalls = 0
        beat.reset()
    }

    private fun gcStats(): LongArray = longArrayOf(
        stat("art.gc.gc-count"), stat("art.gc.gc-time"), stat("art.gc.blocking-gc-count"), stat("art.gc.blocking-gc-time"),
    )

    private fun stat(key: String): Long = runCatching { Debug.getRuntimeStat(key)?.toLong() ?: -1L }.getOrDefault(-1L)

    // ── device / display / power ────────────────────────────────────────────

    private fun display(): Display? = app.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)

    private fun currentRefresh(): String = display()?.let { "%.0f".format(it.refreshRate) } ?: "?"

    private fun banner() {
        val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        val code = if (Build.VERSION.SDK_INT >= 28) info?.longVersionCode else @Suppress("DEPRECATION") info?.versionCode?.toLong()
        val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= 30) app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName
            else @Suppress("DEPRECATION") app.packageManager.getInstallerPackageName(app.packageName)
        }.getOrNull()
        AppLogger.info(
            TAG,
            "[JankDiag] build=${AppBuildInfo.commit(app)} version=${info?.versionName}($code) built=${AppBuildInfo.buildDate(app)} " +
                "debuggable=$debuggable installer=$installer",
        )
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER}/${Build.SOC_MODEL}" else Build.HARDWARE
        AppLogger.info(
            TAG,
            "[JankDiag] device mfr=${Build.MANUFACTURER} model=${Build.MODEL} product=${Build.PRODUCT} soc=$soc " +
                "android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} rom=${Build.DISPLAY} cores=${Runtime.getRuntime().availableProcessors()}",
        )
        reportDisplay("start")
        reportPower("start")
        reportCompileState()
    }

    private fun reportDisplay(why: String) {
        val d = display() ?: return
        val mode = d.mode
        vsyncPeriodNs = (1e9 / d.refreshRate).toLong()
        val supported = d.supportedModes.joinToString(",") { "${it.modeId}:${it.physicalWidth}x${it.physicalHeight}@${"%.0f".format(it.refreshRate)}" }
        val line = "mode=${mode.modeId}:${mode.physicalWidth}x${mode.physicalHeight}@${"%.1f".format(mode.refreshRate)} " +
            "refresh=${"%.1f".format(d.refreshRate)} supported=[$supported]"
        if (why != "start" && line == lastDisplayLine) return
        lastDisplayLine = line
        AppLogger.info(TAG, "[JankDiag] display ($why) $line")
    }

    private fun reportPower(why: String) {
        val pm = app.getSystemService(PowerManager::class.java) ?: return
        val battery = app.getSystemService(BatteryManager::class.java)
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = battery?.isCharging
        val thermal = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else -1
        val headroom = if (Build.VERSION.SDK_INT >= 30) runCatching { "%.2f".format(pm.getThermalHeadroom(10)) }.getOrDefault("?") else "?"
        AppLogger.info(
            TAG,
            "[JankDiag] power ($why) saveMode=${pm.isPowerSaveMode} battery=$level% charging=$charging thermal=$thermal " +
                "headroom10s=$headroom sustainedPerfSupported=${pm.isSustainedPerformanceModeSupported} " +
                "idle=${if (Build.VERSION.SDK_INT >= 23) pm.isDeviceIdleMode else null}",
        )
    }

    private fun reportCompileState() {
        runCatching {
            // Synchronous variant (worker thread): the async one returns a Guava future.
            val status = androidx.profileinstaller.ProfileVerifier.writeProfileVerification(app)
            AppLogger.info(
                TAG,
                // Result code in the low 16 bits, an error code in the high 16 (ProfileVerifier.CompilationStatus).
                "[JankDiag] art profileResult=${status.profileInstallResultCode and 0xFFFF} profileError=${status.profileInstallResultCode shr 16} " +
                    "compiledWithProfile=${status.isCompiledWithProfile} " +
                    "profileEnqueued=${status.hasProfileEnqueuedForCompilation()}",
            )
        }.onFailure { AppLogger.info(TAG, "[JankDiag] art status unavailable: ${it.javaClass.simpleName}") }
    }

    private fun registerSystemListeners() {
        app.getSystemService(DisplayManager::class.java)?.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                // May fire often on variable-refresh panels: counted, reported once per window.
                if (displayId == Display.DEFAULT_DISPLAY) { displayChanges++; displayReportPending = true }
            }
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }, bg)
        val pm = app.getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                pm?.addThermalStatusListener(app.mainExecutor) { status ->
                    bg.post { AppLogger.info(TAG, "[JankDiag] thermal changed status=$status") }
                }
            }
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                app,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) { reportPower("changed") }
                },
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                null,
                bg,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    /** Timeline of one chat opening (background thread). */
    private class OpenTimeline {
        private var session: String? = null
        private var clickNs = 0L
        private var busyAtClick = 0L
        private val stages = LinkedHashMap<String, Long>()

        fun begin(sessionId: String, t: Long, busy: Long) {
            session = sessionId; clickNs = t; busyAtClick = busy; stages.clear()
        }

        fun stage(sessionId: String, name: String, t: Long) {
            if (session != sessionId) { session = sessionId; clickNs = 0L; stages.clear() }
            stages.putIfAbsent(name, t)
        }

        fun stable(sessionId: String, t: Long, busy: Long, longestNs: Long, messages: Int, rows: Int, prewarmMs: Long): String? {
            if (session != sessionId) return null
            val base = if (clickNs != 0L) clickNs else stages.values.firstOrNull() ?: return null
            val parts = stages.entries.joinToString(" ") { (k, v) -> "$k=+${(v - base) / 1_000_000}" }
            val line = "[JankDiag] open session=${sessionId.take(8)} click=${clickNs != 0L} msgs=$messages rows=$rows " +
                "prewarmMs=$prewarmMs $parts stable=+${(t - base) / 1_000_000} " +
                (if (clickNs != 0L) "mainBusyMs=${(busy - busyAtClick) / 1_000_000} " else "") +
                "longestMainMsgMs=${longestNs / 1_000_000}"
            session = null
            return line
        }
    }
}

// ─── Merged from JankDiagStats.kt ──────────────────────────────────────────────────

/**
 * [T-android-jankdiag] Pure aggregation behind [JankDiag]: no Android types, so
 * JankDiagStatsTest runs it on the JVM. Everything here is fed from the
 * JankDiag background thread and read back once per summary window.
 */

/** Frame durations for one window, in nanoseconds, stage by stage. */
internal class JankFrameWindow(initialCapacity: Int = 1024) {
    enum class Stage { TOTAL, INPUT, ANIMATION, LAYOUT_MEASURE, DRAW, SYNC, COMMAND_ISSUE, SWAP, GPU }

    private var size = 0
    private var data = Array(Stage.entries.size) { LongArray(initialCapacity) }
    var janky = 0
        private set
    var over100ms = 0
        private set
    var deadlineMissed = 0
        private set
    var firstVsyncNs = Long.MAX_VALUE
        private set
    var lastVsyncNs = 0L
        private set

    val count: Int get() = size

    /**
     * One frame. [deadlineNs] is the frame's budget (FrameMetrics.DEADLINE on
     * API 31+, else the display's vsync period); a frame whose total passes it
     * counts as janky.
     */
    fun add(stages: LongArray, deadlineNs: Long, intendedVsyncNs: Long) {
        if (size == data[0].size) data = Array(data.size) { data[it].copyOf(size * 2) }
        for (i in data.indices) data[i][size] = stages.getOrElse(i) { 0L }
        size++
        val total = stages[Stage.TOTAL.ordinal]
        if (deadlineNs > 0 && total > deadlineNs) { janky++; deadlineMissed++ }
        if (total > 100_000_000L) over100ms++
        if (intendedVsyncNs > 0) {
            if (intendedVsyncNs < firstVsyncNs) firstVsyncNs = intendedVsyncNs
            if (intendedVsyncNs > lastVsyncNs) lastVsyncNs = intendedVsyncNs
        }
    }

    /** The [q] quantile (0..1) of a stage, in ms; 0 when empty. */
    fun percentileMs(stage: Stage, q: Double): Double {
        if (size == 0) return 0.0
        val sorted = data[stage.ordinal].copyOf(size).also { it.sort() }
        val idx = ((size - 1) * q).toInt().coerceIn(0, size - 1)
        return sorted[idx] / 1_000_000.0
    }

    fun maxMs(stage: Stage): Double {
        var m = 0L
        for (i in 0 until size) m = max(m, data[stage.ordinal][i])
        return m / 1_000_000.0
    }

    /** Frames per second over the window's own vsync span (0 below two frames). */
    fun fps(): Double {
        if (size < 2 || lastVsyncNs <= firstVsyncNs) return 0.0
        return (size - 1) * 1e9 / (lastVsyncNs - firstVsyncNs)
    }

    /**
     * Whether the window is worth a line: a few janky frames, or one frame long
     * enough to be a visible hitch. Quiet windows print nothing.
     */
    fun worthReporting(minJanky: Int = 3): Boolean = janky >= minJanky || over100ms > 0

    fun summary(): String {
        fun f(v: Double) = "%.1f".format(v)
        val s = Stage.TOTAL
        return "frames=$size fps=${f(fps())} janky=$janky over100ms=$over100ms " +
            "total p50=${f(percentileMs(s, .5))} p90=${f(percentileMs(s, .9))} p99=${f(percentileMs(s, .99))} max=${f(maxMs(s))} | " +
            listOf(Stage.INPUT, Stage.ANIMATION, Stage.LAYOUT_MEASURE, Stage.DRAW, Stage.SYNC, Stage.COMMAND_ISSUE, Stage.SWAP, Stage.GPU)
                .joinToString(" ") { st -> "${st.name.lowercase()}=${f(percentileMs(st, .9))}/${f(maxMs(st))}" } +
            " (p90/max ms)"
    }

    fun reset() {
        size = 0; janky = 0; over100ms = 0; deadlineMissed = 0
        firstVsyncNs = Long.MAX_VALUE; lastVsyncNs = 0L
    }
}

/** A small reservoir of millisecond samples (parse times, log call times): p50/p90/max. */
internal class JankMsSamples(private val capacity: Int = 512) {
    private val values = DoubleArray(capacity)
    private var n = 0
    var total = 0
        private set
    var sumMs = 0.0
        private set
    var maxMs = 0.0
        private set

    fun add(ms: Double) {
        total++
        sumMs += ms
        if (ms > maxMs) maxMs = ms
        if (n < capacity) values[n++] = ms else values[(total - 1) % capacity] = ms
    }

    fun percentile(q: Double): Double {
        if (n == 0) return 0.0
        val sorted = values.copyOf(n).also { it.sort() }
        return sorted[((n - 1) * q).toInt().coerceIn(0, n - 1)]
    }

    fun reset() { n = 0; total = 0; sumMs = 0.0; maxMs = 0.0 }
}

/** Readable page label from a navigation route: "chat/{sessionId}" -> "chat". */
internal fun jankScreenLabel(route: String?): String {
    if (route.isNullOrBlank()) return "none"
    return route.substringBefore("/{").substringBefore("?").take(40)
}

/** "a.b.C.m:12 <- d.E.n:3 ..." — the first [depth] frames of a stack, one line. */
internal fun jankStackLine(stack: Array<StackTraceElement>, depth: Int = 18): String =
    stack.take(depth).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

// ─── Merged from LargeAllocProbe.kt ──────────────────────────────────────────────────

/**
 * [T-android-mem-probe-trust] Records large, workload-driven allocations and
 * the heap headroom around them.
 *
 * ## Why this exists
 *
 * The 2026-08-15 field log recorded `bodyLen=2342987` for the request that
 * preceded a process death, but nothing about what that cost: no before/after
 * heap, no headroom, no indication of whether the allocation nearly failed.
 * So "did serialising this body kill the app?" was unanswerable from the log
 * and had to be guessed at.
 *
 * This probe brackets such an allocation and records what actually happened.
 * A near-miss (heap crossing the pressure line, or a big jump in peak RSS)
 * is then visible in the very same log the user attaches to a report.
 *
 * ## Recording, not preventing
 *
 * This deliberately does not truncate or reject anything — a probe that
 * changes behaviour makes the next field report describe the probe rather
 * than the bug. It only observes, and shouts when the numbers are alarming.
 */
object LargeAllocProbe {

    private const val TAG = "MemProbe"

    /** Bodies at or above this are worth a line on their own. */
    const val NOTABLE_BYTES = 512 * 1024

    /**
     * Bracket a large serialisation/allocation.
     *
     * @param label what is being built (e.g. "openai.requestBody").
     * @param sizeBytes the resulting payload size, if known up front.
     * @param detail caller context — model id, message count, session id.
     * @return whatever [block] returns; exceptions propagate untouched after
     *   being logged, so an OOM here is attributable instead of anonymous.
     */
    inline fun <T> around(
        label: String,
        sizeBytes: Long,
        detail: String = "",
        block: () -> T,
    ): T {
        if (sizeBytes < NOTABLE_BYTES) return block()

        val before = MemorySnapshot.capture()
        val startNs = System.nanoTime()
        try {
            val result = block()
            report(label, sizeBytes, detail, before, startNs, failure = null)
            return result
        } catch (t: Throwable) {
            // The case we most need in a field log: the allocation that died.
            report(label, sizeBytes, detail, before, startNs, failure = t)
            throw t
        }
    }

    /** Published for the inline function above; not part of the intended API. */
    fun report(
        label: String,
        sizeBytes: Long,
        detail: String,
        before: MemorySnapshot,
        startNs: Long,
        failure: Throwable?,
    ) {
        val after = MemorySnapshot.capture()
        val ms = (System.nanoTime() - startNs) / 1_000_000
        val deltaJava = after.javaHeapMB - before.javaHeapMB
        val deltaRss = if (before.rssMB >= 0 && after.rssMB >= 0) after.rssMB - before.rssMB else -1

        val head = "[MemProbe] $label sizeBytes=$sizeBytes (${sizeBytes / 1024}KB) " +
            "tookMs=$ms deltaJavaMB=$deltaJava deltaRssMB=$deltaRss " +
            "before[${before.toLogString()}] after[${after.toLogString()}]" +
            if (detail.isEmpty()) "" else " $detail"

        when {
            failure != null -> {
                AppLogger.error(TAG, "$head FAILED=${failure.javaClass.name}: ${failure.message}")
                // Full stack: for an OOM the frame that asked for the memory is
                // the whole answer, and it is exactly what the earlier
                // investigation lacked.
                AppLogger.error(TAG, "[MemProbe] $label stack:\n${stackOf(failure)}")
            }
            after.javaHeapUsedFraction >= MemorySnapshot.HEAP_PRESSURE_FRACTION ->
                AppLogger.warning(TAG, "$head HEAP_PRESSURE")
            else -> AppLogger.info(TAG, head)
        }
    }

    fun stackOf(t: Throwable): String {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        // Cap: a deep Compose/coroutine stack can run to thousands of lines and
        // this is written into the user's log file.
        return sw.toString().lineSequence().take(60).joinToString("\n")
    }
}

// ─── Merged from LaunchCycleBeacon.kt ──────────────────────────────────────────────────

/**
 * Records when a process launch begins and when the previous launch ended
 * cleanly. Lets us distinguish "the user backgrounded and we got cleaned
 * up by MIUI / LMK" (no clean-exit marker; previous run's last tick was
 * recent, in foreground) from "we crashed" (ACRA / native handler already
 * wrote a `.log` file) or "normal launch after explicit exit" (clean-exit
 * marker present).
 *
 * The beacon is one small JSON-ish line per launch under
 * `filesDir/logs/launch-beacon.log`. We don't roll it — entries are tiny
 * and a long history helps spot MIUI-kill patterns over days.
 *
 * Bug 2 in the v1.8-dev MIUI feedback report ("app crash" with no
 * crash-*.log file): adding this beacon means the next launch can log
 * "previous run had no clean exit and no crash report — likely LMK or
 * MIUI background cleanup", which is what we want to confirm.
 */
object LaunchCycleBeacon {

    private const val FILE_NAME = "launch-beacon.log"
    private const val TAG = "LaunchBeacon"

    /**
     * [T-android-render-breaker] True when the PREVIOUS app cycle ended in
     * crash_or_stall (set once at [recordLaunch]). Consumed by the chat Resume
     * banner: in the ANR-restart loop the banner was an unguarded "continue"
     * button that re-entered the exact load that killed the previous cycle —
     * with this flag it warns and requires a confirming second tap.
     */
    @Volatile
    var lastCycleWasCrash: Boolean = false
        private set

    /**
     * [T-android-larky-longsession-followup] Snapshot of `restartCount` from
     * the most recent [recordLaunch] call (the (launches − clean_exits) tail
     * count computed in the crash_or_stall branch). Stored verbatim so the
     * launch resolver can gate on it without re-reading the beacon file.
     *
     * Only updated when the prior verdict starts with "crash_or_stall" — for
     * any other verdict (clean_exit / silent_kill / first_launch /
     * no_prior_launch) this stays at 0, which means [shouldForceHomeOnLaunch]
     * naturally returns false for users who didn't actually crash.
     *
     * Reset semantics: the field is process-scoped. It's recomputed once per
     * process at [recordLaunch] (called from MinisApp.onCreate). A single
     * cycle that does NOT end in crash_or_stall — even silent_kill, which
     * dwarfs real crashes for daily users — resets the field to 0 on the
     * very next launch, restoring auto-recovery. So a user who hits a
     * crash-loop, then closes the app cleanly (or even gets MIUI-killed in
     * the background), is back to normal launch behavior on their next tap.
     */
    @Volatile
    var lastRestartCount: Int = 0
        private set

    /**
     * [T-android-larky-longsession-followup] Threshold for the
     * "skip auto-recovery" breaker. When the previous launch ended in
     * crash_or_stall AND [lastRestartCount] exceeds this value (strictly
     * greater than), the launch resolver lands on the session list instead
     * of auto-recovering the previous chat — protects against being thrown
     * straight back into a session that's killing the process.
     *
     * The "> 3" wording in the spec means a fourth consecutive bad cycle
     * triggers it; choosing 3 as the strict-greater-than threshold matches
     * that interpretation while leaving the user 3 free retries (which can
     * be normal if e.g. they hit a transient OOM that won't repeat).
     */
    const val RESTART_COUNT_FORCE_HOME_THRESHOLD: Int = 3

    /**
     * [T-android-larky-longsession-followup] True when the previous cycle
     * ended in crash_or_stall AND the rolling restart-count exceeds
     * [RESTART_COUNT_FORCE_HOME_THRESHOLD]. Joined with the existing
     * HangDetector / CrashFrequencyDetector breakers in AppNavigation's
     * launch resolver — any one of them flips the launch to mode 3 (home),
     * the others stay untouched.
     *
     * Always false for users whose previous cycle was clean_exit, silent_kill,
     * first_launch, or no_prior_launch — those reset [lastRestartCount] to 0
     * (it's only written in the crash_or_stall branch of [recordLaunch]).
     */
    fun shouldForceHomeOnLaunch(): Boolean =
        lastCycleWasCrash && lastRestartCount > RESTART_COUNT_FORCE_HOME_THRESHOLD

    fun recordLaunch(context: Context) {
        val file = beaconFile(context)
        val previousTail = readTail(file)
        val now = System.currentTimeMillis()
        val nowIso = isoLocal(now)

        // Inspect previous record to classify the prior cycle.
        val previousVerdict = classifyPrevious(previousTail, context, now)
        lastCycleWasCrash = previousVerdict.startsWith("crash_or_stall")
        AppLogger.info(TAG, "launch verdict for previous cycle: $previousVerdict")

        // [T-android-perf-logging] When the previous cycle ended in
        // crash_or_stall, surface a structured Perf line so a low-memory
        // ANR repro can be correlated against a recovery loop. restartCount
        // = how many of the recent launch records are themselves preceded by
        // crash artefacts (i.e. consecutive bad cycles) — a climbing count is
        // the signature of "recovery keeps re-crashing on the same session".
        if (previousVerdict.startsWith("crash_or_stall")) {
            val restartCount = countRecentCrashLaunches(previousTail, context)
            // [T-android-larky-longsession-followup] Snapshot the count so
            // shouldForceHomeOnLaunch() can gate on it without re-reading
            // the beacon. Cleared by the path above whenever the previous
            // cycle is anything other than crash_or_stall (the var stays at
            // its initialization value of 0 unless this branch overrides).
            lastRestartCount = restartCount
            val lastSessionId = readLastSessionId(context)
            AppLogger.warning(
                TAG,
                "[Perf][LongCtx] step=launchBeacon.crashOrStall " +
                    "reason=${previousVerdict.substringBefore(' ')} " +
                    "detail=${previousVerdict.substringAfter('(', "").substringBefore(')')} " +
                    "restartCount=$restartCount lastSessionId=$lastSessionId",
            )
        } else {
            // Explicit reset for clarity: any verdict that's not
            // crash_or_stall resets the gate count. Important when a user
            // who previously hit the breaker has a single non-crash cycle
            // (clean_exit / silent_kill) — they should be back to normal
            // auto-recovery on the very next launch.
            lastRestartCount = 0
        }

        appendLine(file, "[$nowIso] launch pid=${android.os.Process.myPid()}")
    }

    /**
     * [T-android-perf-logging] Count launch records in the beacon tail that
     * sit between crash/stall artefacts — a rough "consecutive bad cycles"
     * gauge. Cheap heuristic: number of `launch` lines minus the number
     * followed by a `clean_exit`. Not exact, but a rising value across
     * launches is what flags a recovery loop.
     */
    private fun countRecentCrashLaunches(tail: String, context: Context): Int {
        val lines = tail.split('\n').filter { it.isNotBlank() }
        val launches = lines.count { it.contains(" launch ") }
        val cleanExits = lines.count { it.contains(" clean_exit ") }
        return (launches - cleanExits).coerceAtLeast(0)
    }

    /**
     * [T-android-perf-logging] Best-effort last-opened session id, read from
     * the most recent stall-*.log header if present (the HangDetector writes
     * the session id into its stall report). Returns "unknown" when no stall
     * artefact carries one — keeps the log line populated without throwing.
     */
    private fun readLastSessionId(context: Context): String {
        return try {
            val logsDir = File(context.filesDir, "logs")
            val stall = logsDir.listFiles()
                ?.filter { it.name.startsWith("stall-") }
                ?.maxByOrNull { it.lastModified() }
                ?: return "unknown"
            val head = stall.bufferedReader().use { it.readText().take(2000) }
            Regex("session[=:]\\s*([0-9a-fA-F-]{8,})").find(head)?.groupValues?.get(1) ?: "unknown"
        } catch (_: Throwable) {
            "unknown"
        }
    }

    fun recordCleanExit(context: Context) {
        val now = System.currentTimeMillis()
        appendLine(beaconFile(context), "[${isoLocal(now)}] clean_exit pid=${android.os.Process.myPid()}")
    }

    private fun beaconFile(context: Context): File =
        File(File(context.filesDir, "logs").also { it.mkdirs() }, FILE_NAME)

    /** Last ~16 KB of the beacon log — enough to find the previous launch line. */
    private fun readTail(file: File): String {
        if (!file.exists() || file.length() == 0L) return ""
        return try {
            val len = file.length()
            val start = (len - 16 * 1024).coerceAtLeast(0)
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val bytes = ByteArray((len - start).toInt())
                raf.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "tail read failed: ${t.message}")
            ""
        }
    }

    private fun classifyPrevious(tail: String, context: Context, now: Long): String {
        if (tail.isBlank()) return "first_launch"
        val lines = tail.split('\n').filter { it.isNotBlank() }
        val lastLaunch = lines.lastOrNull { it.contains(" launch ") } ?: return "no_prior_launch"
        val lastCleanExit = lines.lastOrNull { it.contains(" clean_exit ") }
        // Order matters: if a clean_exit line follows the most recent launch,
        // the previous cycle ended cleanly.
        val cleanExitAfterLaunch = lastCleanExit != null &&
            lines.indexOf(lastCleanExit) > lines.indexOf(lastLaunch)
        if (cleanExitAfterLaunch) return "clean_exit"

        // Look for crash artefacts produced between previous launch and now.
        val logsDir = File(context.filesDir, "logs")
        val previousLaunchMs = parseTs(lastLaunch) ?: return "ambiguous_no_timestamp"
        val crashLogs = logsDir.listFiles()?.filter { f ->
            val name = f.name
            (name.startsWith("crash-") || name.startsWith("native-crash-") || name.startsWith("stall-")) &&
                f.lastModified() in previousLaunchMs..now
        }.orEmpty()
        if (crashLogs.isNotEmpty()) {
            return "crash_or_stall (${crashLogs.joinToString { it.name }})"
        }
        // No crash, no clean exit — likely OOM / LMK / MIUI background cleanup.
        val ageMs = now - previousLaunchMs
        return "silent_kill (uptime_was=${ageMs}ms)"
    }

    private fun parseTs(line: String): Long? {
        // Lines look like "[2026-05-13T01:23:45.678] launch pid=…"
        val open = line.indexOf('[')
        val close = line.indexOf(']')
        if (open != 0 || close <= 0) return null
        val iso = line.substring(1, close)
        return try {
            val fmt = java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                java.util.Locale.US,
            ).apply { timeZone = java.util.TimeZone.getDefault() }
            fmt.parse(iso)?.time
        } catch (_: Throwable) {
            null
        }
    }

    private fun isoLocal(ms: Long): String {
        val fmt = java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            java.util.Locale.US,
        ).apply { timeZone = java.util.TimeZone.getDefault() }
        return fmt.format(java.util.Date(ms))
    }

    private fun appendLine(file: File, line: String) {
        try {
            file.appendText(line + "\n")
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "append failed: ${t.message}")
        }
    }
}

// ─── Merged from MemorySnapshot.kt ──────────────────────────────────────────────────

/**
 * [T-android-mem-probe-trust] Trustworthy process-memory readings.
 *
 * ## Why this exists
 *
 * The 2026-08-15 field log reported `nativeHeapMB=9744` — 9.7 GB — from
 * `Debug.getNativeHeapAllocatedSize()`, on a device with 6 GB of RAM and a
 * 512 MB heap ceiling. Both a 17-message session (23 KB of text) and a
 * 1528-message one produced multi-GB readings, so the number did not track
 * workload at all. That reading sent a whole investigation down the wrong
 * path: it was read as "native heap exploded → OOM kill", and a request-body
 * serialisation fix was scoped off the back of it.
 *
 * `Debug.getNativeHeapAllocatedSize()` delegates to the platform allocator and
 * some vendor ROMs (the field device was vivo — `/proc/vivo_rsc/…` appears in
 * its logcat tail) return an accumulated or address-space figure rather than
 * live bytes. A Pixel 4a under a deliberately larger workload reported 23 MB
 * from the same call, i.e. the API is not comparable across devices and must
 * not be the only number in the log.
 *
 * ## What this reports instead
 *
 * `/proc/self/status` is the kernel's own accounting and is consistent across
 * vendors: [rssKb] is resident memory and is the number to reason about when
 * asking "was this process big enough for the LMK to kill it". The Java heap
 * comes from `Runtime`, which is reliable everywhere. The legacy native figure
 * is still emitted, but explicitly labelled so nobody treats it as truth again.
 *
 * All reads are cheap: two `Runtime` calls plus one small proc file, no
 * allocation beyond the parsed longs. Safe to sample per timeline step.
 */
data class MemorySnapshot(
    /** Live Java heap in MB (`totalMemory - freeMemory`). Reliable. */
    val javaHeapMB: Long,
    /** Java heap ceiling in MB (`maxMemory`). The OOM line for Java allocations. */
    val javaHeapMaxMB: Long,
    /** Resident set size in MB from /proc/self/status. Kernel truth; -1 if unreadable. */
    val rssMB: Long,
    /** Peak RSS in MB (VmHWM) — survives a dip, so a spike is still visible. */
    val rssPeakMB: Long,
    /** Virtual size in MB (VmSize). Large values are normal, not a leak signal. */
    val vmSizeMB: Long,
    /** `Debug.getNativeHeapAllocatedSize()`. UNTRUSTED — see class docs. */
    val nativeHeapRawMB: Long,
) {
    /** Fraction of the Java heap ceiling in use, 0..1. The real OOM predictor. */
    val javaHeapUsedFraction: Double
        get() = if (javaHeapMaxMB > 0) javaHeapMB.toDouble() / javaHeapMaxMB else 0.0

    /**
     * Compact log form. `nativeHeapRawMB` is deliberately named `nativeRaw`
     * rather than `nativeHeapMB` so old greps do not silently pick it up and
     * repeat the original misreading.
     */
    fun toLogString(): String =
        "javaHeapMB=$javaHeapMB/$javaHeapMaxMB (${(javaHeapUsedFraction * 100).toInt()}%) " +
            "rssMB=$rssMB peakRssMB=$rssPeakMB vmSizeMB=$vmSizeMB nativeRawMB=$nativeHeapRawMB"

    companion object {
        /** True once the Java heap is close enough to its ceiling to matter. */
        const val HEAP_PRESSURE_FRACTION = 0.80

        fun capture(): MemorySnapshot {
            val rt = Runtime.getRuntime()
            val mb = 1024L * 1024L
            var rssKb = -1L
            var peakKb = -1L
            var vmKb = -1L
            try {
                // Single pass; stop as soon as all three are found. VmHWM and
                // VmRSS sit adjacent near the top of the file, so this reads
                // only a handful of lines in practice.
                File("/proc/self/status").useLines { lines ->
                    for (line in lines) {
                        when {
                            line.startsWith("VmRSS:") -> rssKb = parseKb(line)
                            line.startsWith("VmHWM:") -> peakKb = parseKb(line)
                            line.startsWith("VmSize:") -> vmKb = parseKb(line)
                        }
                        if (rssKb >= 0 && peakKb >= 0 && vmKb >= 0) break
                    }
                }
            } catch (_: Throwable) {
                // Some hardened ROMs restrict /proc/self. Fall through with -1
                // rather than losing the Java-heap numbers too.
            }
            return MemorySnapshot(
                javaHeapMB = (rt.totalMemory() - rt.freeMemory()) / mb,
                javaHeapMaxMB = rt.maxMemory() / mb,
                rssMB = if (rssKb >= 0) rssKb / 1024 else -1,
                rssPeakMB = if (peakKb >= 0) peakKb / 1024 else -1,
                vmSizeMB = if (vmKb >= 0) vmKb / 1024 else -1,
                nativeHeapRawMB = Debug.getNativeHeapAllocatedSize() / mb,
            )
        }

        /** "VmRSS:\t  123456 kB" → 123456. */
        private fun parseKb(line: String): Long {
            val digits = line.filter { it.isDigit() }
            return digits.toLongOrNull() ?: -1L
        }
    }
}

// ─── Merged from PerfLongCtx.kt ──────────────────────────────────────────────────

/**
 * T-android-long-ctx-reentry-perf: dedicated breadcrumb stream for the
 * "tap session → ChatScreen first frame" reentry path. Distinct from the
 * existing `[T-HANG-DIAG]` markers so a single grep on
 * `[Perf][LongCtx]` returns just the reentry timeline.
 *
 * Each call emits a line like:
 *   [Perf][LongCtx] step=loadSession.enter session=abc elapsedMs=12 sinceClickMs=180 javaHeapMB=148 nativeHeapMB=64 extra=...
 *
 * - `elapsedMs` is measured from the most recent step on the same session.
 * - `sinceClickMs` is measured from `click()` on the same session, which
 *   is the user-perceived start of the reentry.
 * - Heap numbers are cheap O(1) reads and intentionally sampled at every
 *   step so a GC-storm regime is obvious in the trace without a separate
 *   tracer.
 *
 * Thread-safety: the click + last timestamps are kept in `AtomicLong`s
 * keyed by sessionId. Concurrent loadSession of *different* sessions
 * won't clobber each other; concurrent loadSession of the *same*
 * sessionId would race on `lastNs` but that is not a real scenario
 * (loadSession runs once on entry).
 */
object PerfLongCtx {

    private const val CATEGORY = "Perf"

    private val clickNsBySession = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val lastNsBySession = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val seq = AtomicLong(0)

    /**
     * Row-compose accumulator for the LazyColumn rentry path. Counts each
     * [FlatChatItem] that enters composition during a reentry; emits a
     * one-line summary when the 10th and 50th rows arrive so a dense
     * tool-use session shows up as a single "took 800 ms to compose
     * 50 rows" line instead of 50 individual log entries.
     *
     * The counter is keyed by sessionId, so a navigation away + back
     * within the same process accumulates new counts (the previous
     * milestones already fired, this just races to 10/50 again).
     */
    private val rowComposeCount = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
    private val rowComposeStartNs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val rowComposeTypes = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, AtomicLong>>()
    private val ROW_MILESTONES = longArrayOf(10, 50, 200)

    /**
     * User tapped the session card. Resets the per-session timeline.
     * Called from the row's gesture handler so we capture the very first
     * timestamp the user is waiting on.
     */
    fun click(sessionId: String) {
        JankDiag.beginOpen(sessionId)
        val now = System.nanoTime()
        clickNsBySession[sessionId] = now
        lastNsBySession[sessionId] = now
        emit(sessionId, "click", elapsedMs = 0, extra = "seq=${seq.incrementAndGet()}")
    }

    /**
     * Generic timeline breadcrumb. `extra` is appended verbatim so
     * callsites can pass `count=405 totalChars=1234567` etc.
     */
    fun step(sessionId: String, name: String, extra: String = "") {
        // [T-android-jankdiag] The chat-open stages, timed in the jank log too.
        if (name in JANK_OPEN_STAGES) JankDiag.openStage(sessionId, name)
        val now = System.nanoTime()
        val last = lastNsBySession[sessionId] ?: clickNsBySession[sessionId] ?: now
        val elapsedMs = (now - last) / 1_000_000
        lastNsBySession[sessionId] = now
        emit(sessionId, name, elapsedMs, extra)
    }

    /**
     * End of the reentry timeline (typically the loadSession EXIT or
     * the last-message onPlaced). Leaves the click timestamp in place
     * so any late-arriving steps still report a sensible
     * `sinceClickMs`; subsequent click() calls reset normally.
     */
    fun end(sessionId: String, name: String = "end", extra: String = "") {
        step(sessionId, name, extra)
        lastNsBySession.remove(sessionId)
    }

    /**
     * Call from each LazyColumn item's compose lambda. Cheap O(1) — only
     * emits a log line at the 10th / 50th / 200th row per session.
     * Tracks per-class counts (e.g. `AssistantToolUse=18 UserBubble=4
     * AssistantText=8`) so a session blown out by tool cards is
     * immediately distinguishable from one blown out by markdown
     * chunks.
     */
    fun maybeReportRowComposed(sessionId: String, itemClassName: String) {
        val counter = rowComposeCount.computeIfAbsent(sessionId) { AtomicLong(0) }
        val types = rowComposeTypes.computeIfAbsent(sessionId) { java.util.concurrent.ConcurrentHashMap() }
        types.computeIfAbsent(itemClassName) { AtomicLong(0) }.incrementAndGet()
        val now = counter.incrementAndGet()
        if (now == 1L) {
            rowComposeStartNs[sessionId] = System.nanoTime()
            return
        }
        var isMilestone = false
        for (m in ROW_MILESTONES) {
            if (now == m) { isMilestone = true; break }
        }
        if (isMilestone) {
            val startNs = rowComposeStartNs[sessionId] ?: return
            val ms = (System.nanoTime() - startNs) / 1_000_000
            val byType = types.entries.joinToString(",") { "${it.key}=${it.value.get()}" }
            step(
                sessionId,
                "rowsCompose.milestone",
                "rows=$now sinceFirstRowMs=$ms byType=$byType",
            )
        }
    }

    private fun emit(sessionId: String, name: String, elapsedMs: Long, extra: String) {
        val clickNs = clickNsBySession[sessionId]
        val sinceClickMs = if (clickNs != null) {
            (System.nanoTime() - clickNs) / 1_000_000
        } else {
            -1L
        }
        // [T-android-mem-probe-trust] Was `javaHeapMB=… nativeHeapMB=…`, where
        // the native figure came straight from Debug.getNativeHeapAllocatedSize().
        // On the 2026-08-15 field device (vivo) that call reported 9744 MB on a
        // 6 GB phone, for a 17-message session — it tracked nothing, and it was
        // read as an OOM smoking gun. MemorySnapshot reports kernel RSS as the
        // primary number and keeps the legacy value under `nativeRawMB` so it
        // can still be compared across reports without being mistaken for truth.
        val mem = MemorySnapshot.capture()
        val extraPart = if (extra.isEmpty()) "" else " $extra"
        AppLogger.info(
            CATEGORY,
            "[Perf][LongCtx] step=$name session=$sessionId elapsedMs=$elapsedMs " +
                "sinceClickMs=$sinceClickMs ${mem.toLogString()}$extraPart",
        )
    }

    private val JANK_OPEN_STAGES = setOf(
        "chatScreen.mount", "buildFlatChatItems.firstBuild", "lazyColumn.firstLayout",
        "lazyColumn.firstItem.compose", "lazyColumn.firstItem.placed", "loadSession.exit",
    )
}

// ─── Merged from ResourceWatermark.kt ──────────────────────────────────────────────────

/**
 * [GH #453] Process resource water marks: thread count, memory-mapping count,
 * open fds, VmSize / VmRSS, and, past a threshold, how many threads share each
 * name.
 *
 * Why: a 1.15-beta.4 field report died after hours (once after 13.6 h in the
 * background) with `pthread_create (1040KB stack) failed: Try again` and
 * `Scudo ERROR: internal map failure` alternating - a process running out of
 * threads or of memory mappings (the kernel's vm.max_map_count, 65530 by
 * default; every thread costs a stack mapping plus a guard). Nothing in the
 * logs said which, or who was creating them. JankDiag's 30 s heartbeat already
 * printed `threads=`, but only while an Activity was resumed, so a process that
 * grew in the background printed nothing.
 *
 * Everything here is plain file reads on whatever thread calls it (JankDiag's
 * background HandlerThread). Nothing is held between calls except the
 * reporting gate's two numbers, nothing allocates per mapping, and every read
 * swallows its own failure and reports -1: a diagnostic must keep working in a
 * process that is already failing to allocate, and must never be the thing
 * that throws.
 */
object ResourceWatermark {

    /**
     * Thread count above which the per-name breakdown is printed. The
     * `[JankDiag] tick` lines in our own long-session logs (Pixel 4a / 6,
     * chats, shells, sub-agents, browser) ranged 40-121 threads; 400 is over
     * three times that, and still far below where pthread_create starts
     * failing, so the breakdown is on disk long before the crash.
     */
    const val THREAD_ALERT = 400

    /**
     * Mapping count above which the breakdown is printed. The kernel default
     * limit is 65530; a healthy 64-bit ART app sits at a few thousand (shared
     * libraries, dex/oat/vdex, JIT, one stack + guard per thread). 30000 is
     * under half the limit and several times the normal level, and leaves
     * room for the logs to show the growth before the limit. We have no
     * measured field baseline for `maps=` yet - this line is what will give
     * us one.
     */
    const val MAPS_ALERT = 30_000

    /** At most one breakdown per this interval, however fast the numbers move. */
    const val ALERT_MIN_INTERVAL_MS = 5 * 60_000L

    /** Past the first breakdown, the next needs this much growth (1.25 = +25 %). */
    const val ALERT_GROWTH = 1.25

    /** Below this fraction of the thresholds the gate re-arms. */
    const val ALERT_REARM = 0.8

    /**
     * Stop counting `/proc/self/maps` past this many lines. Above the kernel's
     * default limit, so any real count is exact; a larger value only means the
     * limit was raised, and "over 70000" answers the question as well.
     */
    const val MAPS_COUNT_CAP = 70_000

    /** Stop listing threads past this many (bounds the breakdown's cost). */
    const val TASK_SCAN_CAP = 8_192

    /** Names shown in a breakdown. */
    const val TOP_N = 15

    data class Sample(
        val threads: Int,
        val maps: Int,
        val fds: Int,
        val vmSizeKb: Long,
        val vmRssKb: Long,
    ) {
        /** `threads=… maps=… fds=… vmSizeMB=… rssMB=…`, the same fields everywhere. */
        fun fields(): String =
            "threads=$threads maps=${if (maps >= MAPS_COUNT_CAP) ">=$MAPS_COUNT_CAP" else "$maps"} fds=$fds " +
                "vmSizeMB=${mb(vmSizeKb)} rssMB=${mb(vmRssKb)}"

        private fun mb(kb: Long) = if (kb < 0) "-1" else "${kb / 1024}"
    }

    // ── reads ────────────────────────────────────────────────────────────────

    fun sample(proc: File = File("/proc/self")): Sample {
        val status = readStatus(File(proc, "status"))
        return Sample(
            threads = status[0].toInt(),
            maps = countMaps(File(proc, "maps")),
            fds = countFds(File(proc, "fd")),
            vmSizeKb = status[1],
            vmRssKb = status[2],
        )
    }

    /** [Threads, VmSize kB, VmRSS kB] from `/proc/self/status`; -1 for anything missing. */
    internal fun readStatus(file: File): LongArray {
        val out = longArrayOf(-1, -1, -1)
        runCatching {
            file.useLines { lines ->
                for (line in lines) {
                    val slot = when {
                        line.startsWith("Threads:") -> 0
                        line.startsWith("VmSize:") -> 1
                        line.startsWith("VmRSS:") -> 2
                        else -> continue
                    }
                    out[slot] = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: -1
                }
            }
        }
        return out
    }

    internal fun countMaps(file: File): Int =
        runCatching { FileInputStream(file).use { countLines(it, MAPS_COUNT_CAP) } }.getOrDefault(-1)

    /**
     * Newlines in [input], stopping at [cap]. One 16 KB buffer for the whole
     * read: no String per line, so a 65k-line maps file costs one buffer.
     */
    internal fun countLines(input: InputStream, cap: Int): Int {
        val buf = ByteArray(16 * 1024)
        var lines = 0
        while (lines < cap) {
            val n = input.read(buf)
            if (n <= 0) break
            for (i in 0 until n) if (buf[i] == '\n'.code.toByte()) lines++
        }
        return minOf(lines, cap)
    }

    internal fun countFds(dir: File): Int = runCatching { dir.list()?.size ?: -1 }.getOrDefault(-1)

    // ── per-name breakdown ───────────────────────────────────────────────────

    /**
     * Thread names from `/proc/self/task/<tid>/comm`, not
     * `Thread.getAllStackTraces()`: comm covers native threads too (proot
     * helpers, codec/audio, Go runtimes) and costs one small read per thread
     * instead of suspending every thread to walk its stack.
     *
     * comm holds at most 15 bytes. ART keeps a dotted name's LAST 15 characters
     * and anything else's first 15, which is why our own pool names are short
     * and dot-free (see [ThreadNames]).
     */
    fun threadNames(taskDir: File = File("/proc/self/task")): List<String> {
        val tids = runCatching { taskDir.list() }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>(minOf(tids.size, TASK_SCAN_CAP))
        for (tid in tids) {
            if (out.size >= TASK_SCAN_CAP) break
            // A thread that exited between list() and read is simply skipped.
            val name = runCatching { File(taskDir, "$tid/comm").readText().trim() }.getOrNull() ?: continue
            out.add(name)
        }
        return out
    }

    /**
     * Group names by their stable part: every run of digits becomes `#`, so
     * `pool-3-thread-17`, `DefaultDispatch`, `mn-fileio-12` and `binder:1234_5`
     * each collapse to one key.
     */
    fun nameKey(name: String): String = name.trim().replace(DIGITS, "#").ifEmpty { "(empty)" }

    private val DIGITS = Regex("[0-9]+")

    /** `key:count` for the [topN] largest groups, largest first, then the rest as `other:N`. */
    fun breakdown(names: List<String>, topN: Int = TOP_N): String {
        if (names.isEmpty()) return "(unavailable)"
        val counts = HashMap<String, Int>()
        for (n in names) { val k = nameKey(n); counts[k] = (counts[k] ?: 0) + 1 }
        val sorted = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val shown = sorted.take(topN).joinToString(",") { "${it.key}:${it.value}" }
        val rest = sorted.drop(topN).sumOf { it.value }
        return if (rest > 0) "$shown,other:$rest" else shown
    }

    /**
     * [INV-261024-05] What the mappings are: `/proc/self/maps` grouped by name,
     * the [topN] largest groups. A count alone (all GH #453 and OpenMinis#426
     * had) says the process is running out of mappings, not who is taking
     * them; the name does - `[anon:scudo:secondary]` is large native
     * allocations, `[anon:stack_and_tls:#]` thread stacks, a `.so` / `.apk`
     * path a file mapped over and over, `/dev/ashmem/…` shared memory.
     *
     * Only read when [AlertGate] fires (at most every few minutes, and only
     * past [MAPS_ALERT] or [THREAD_ALERT]), and stops at [MAPS_COUNT_CAP]
     * lines. One String per line is fine at that rate.
     */
    fun mapsBreakdown(file: File = File("/proc/self/maps"), topN: Int = TOP_N): String =
        runCatching {
            val names = ArrayList<String>()
            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (names.size >= MAPS_COUNT_CAP) break
                    names.add(mapName(line))
                }
            }
            breakdown(names, topN)
        }.getOrDefault("(unavailable)")

    /**
     * The name column of one maps line (`[anon]` when it has none), the same
     * digit folding as thread names so numbered stacks and ashmem regions
     * group. A mapping's path is app or system code, never user data - the
     * only user files Minis maps are under its own data dir, and those paths
     * are folded to the directory below.
     */
    internal fun mapName(line: String): String {
        // address perms offset dev inode [name]
        var field = 0
        var i = 0
        while (i < line.length && field < 5) {
            while (i < line.length && line[i] != ' ') i++
            while (i < line.length && line[i] == ' ') i++
            field++
        }
        val name = if (field < 5 || i >= line.length) "[anon]" else line.substring(i).trim()
        val dataDir = name.indexOf("/com.openminis.app/")
        val short = if (dataDir >= 0 && name.startsWith("/data/")) {
            // Keep the app-relative top directory (files, cache, databases...) only.
            val rest = name.substring(dataDir + "/com.openminis.app/".length)
            "<app>/" + rest.substringBefore('/')
        } else name
        return nameKey(short)
    }

    // ── when to print the breakdown ──────────────────────────────────────────

    /**
     * Decides when a sample earns a breakdown. Fires on crossing a threshold;
     * after that, only when threads or maps have grown by [ALERT_GROWTH] over
     * the last breakdown and [ALERT_MIN_INTERVAL_MS] has passed, so a process
     * that stays high prints once and a process that keeps growing prints a
     * growth curve without flooding the log. Re-arms once both fall below
     * [ALERT_REARM] of their thresholds. Not thread-safe: one caller (JankDiag's
     * background thread).
     */
    class AlertGate(
        private val threadAlert: Int = THREAD_ALERT,
        private val mapsAlert: Int = MAPS_ALERT,
        private val minIntervalMs: Long = ALERT_MIN_INTERVAL_MS,
    ) {
        private var armed = true
        private var lastAtMs = 0L
        private var lastThreads = 0
        private var lastMaps = 0

        fun shouldReport(s: Sample, nowMs: Long): Boolean {
            val over = s.threads > threadAlert || s.maps > mapsAlert
            if (!over) {
                if (s.threads < threadAlert * ALERT_REARM && s.maps < mapsAlert * ALERT_REARM) armed = true
                return false
            }
            val report = when {
                armed -> true
                nowMs - lastAtMs < minIntervalMs -> false
                else -> s.threads >= lastThreads * ALERT_GROWTH || s.maps >= lastMaps * ALERT_GROWTH
            }
            if (report) { armed = false; lastAtMs = nowMs; lastThreads = s.threads; lastMaps = s.maps }
            return report
        }
    }

    // ── the last sample, for the next launch ─────────────────────────────────

    /**
     * The newest sample, kept on disk so the NEXT launch can attach it to the
     * exit report ([com.openminis.app.crash.ExitInfoCollector]): a process that
     * dies of exhaustion cannot describe itself. One small file per pid, so the
     * collector matches the record's own pid and never races the new process
     * writing its first sample.
     */
    const val WATERMARK_DIR = "diag"
    private const val WATERMARK_PREFIX = "res-"
    private const val WATERMARK_KEEP = 8

    fun watermarkFile(filesDir: File, pid: Int): File = File(File(filesDir, WATERMARK_DIR), "$WATERMARK_PREFIX$pid.txt")

    /** Replace this pid's file (write + rename: a crash mid-write leaves the previous one). */
    fun writeWatermark(filesDir: File, pid: Int, text: String) {
        runCatching {
            val f = watermarkFile(filesDir, pid)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    /** The file the process with [pid] left behind, or null. */
    fun readWatermark(filesDir: File, pid: Int): Pair<String, Long>? = runCatching {
        val f = watermarkFile(filesDir, pid)
        if (!f.isFile) null else f.readText() to f.lastModified()
    }.getOrNull()

    /** Keep the newest [keep] files (other processes'), plus [ownPid]'s. */
    fun pruneWatermarks(filesDir: File, ownPid: Int, keep: Int = WATERMARK_KEEP) {
        runCatching {
            val own = watermarkFile(filesDir, ownPid).name
            File(filesDir, WATERMARK_DIR).listFiles { f -> f.name.startsWith(WATERMARK_PREFIX) && f.name != own }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(keep)
                ?.forEach { it.delete() }
        }
    }
}

/**
 * [GH #453] Names for threads Minis creates, so a per-name count shows which
 * component a pile of threads belongs to. Each name is dot-free and at most
 * 15 characters before any per-thread number, because the kernel's comm (what
 * `/proc`, tombstones and [ResourceWatermark.threadNames] see) keeps only 15
 * bytes. Names carry no user data.
 */
object ThreadNames {
    const val PREFIX = "mn-"
    fun of(component: String): String = PREFIX + component
}

// ─── Merged from StreamJitterProbe.kt ──────────────────────────────────────────────────

/**
 * Debug-only timeline markers for the streaming-jitter harness
 * (tools/perf-baseline + the scratchpad `jitter_analyze.py`). Each mark logs
 * a `mono=` System.nanoTime() stamp so it can be joined against
 * `dumpsys gfxinfo framestats` (same CLOCK_MONOTONIC) to classify frames as
 * "within N ms of a publish / flatten / programmatic scroll" versus quiet.
 *
 * Lives here, permanently, because the previous incarnation was five loose
 * `Log.d("JitterProbe", …)` lines in ChatViewModel/ChatScreen that another
 * session's commit swept into HEAD by accident. A gated helper costs one
 * boolean check in release and cannot leak into a shipped log.
 *
 * `experiment()` reads `debug.minis.<key>` system properties so a rendering
 * A/B (e.g. `adb shell setprop debug.minis.glide snap`) can be flipped on a
 * debug device without a rebuild. Never consulted in release builds.
 */
object StreamJitterProbe {
    private const val TAG = "JitterProbe"

    @JvmStatic
    fun publish(kind: String, len: Int) {
        if (!BuildConfig.DEV_TOOLS) return
        Log.d(TAG, "$kind len=$len mono=${System.nanoTime()}")
    }

    @JvmStatic
    fun scroll(source: String, idx: Int) {
        if (!BuildConfig.DEV_TOOLS) return
        Log.d(TAG, "scroll src=$source idx=$idx mono=${System.nanoTime()}")
    }

    @JvmStatic
    fun flatten(ms: Long, frozenReused: Boolean, frozen: Int, live: Int, streamEmpty: Boolean) {
        if (!BuildConfig.DEV_TOOLS) return
        Log.d(
            TAG,
            "flatten ms=$ms frozenReused=$frozenReused frozen=$frozen live=$live " +
                "streamEmpty=$streamEmpty mono=${System.nanoTime()}",
        )
    }

    private val expCache = HashMap<String, Pair<Long, String>>()

    /** Value of `debug.minis.<key>` (cached 1 s), "" when unset or in release. */
    @JvmStatic
    fun experiment(key: String): String {
        if (!BuildConfig.DEV_TOOLS) return ""
        val now = System.nanoTime()
        synchronized(expCache) {
            expCache[key]?.let { (t, v) -> if (now - t < 1_000_000_000L) return v }
            val v = runCatching {
                val cls = Class.forName("android.os.SystemProperties")
                cls.getMethod("get", String::class.java, String::class.java)
                    .invoke(null, "debug.minis.$key", "") as String
            }.getOrDefault("")
            expCache[key] = now to v
            return v
        }
    }
}

// ─── Merged from StreamPerfMonitor.kt ──────────────────────────────────────────────────

/**
 * [T-android-stream-pipeline-incremental] Per-streaming-turn render-pipeline
 * aggregator. Exists to quantify the frozen/live incremental flatten rollout
 * against the ANR baseline (minis-2026-06-10 crash loop: GC freeing
 * 130–180MB/s, buildFlatChatItems up to 100s, main-thread regex hang).
 *
 * Zero-overhead contract (the monitor must never become its own perf bug):
 *  - Hot path ([tick]) does primitive field math only — no allocation, no
 *    string building, no logging, no locks (single-writer: the pipeline's
 *    collect loop).
 *  - Turn boundaries ([turnStart]/[turnEnd]) each do two
 *    [Debug.getRuntimeStat] reads and at most ONE AppLogger line. Runtime
 *    stats are cheap counters maintained by ART; reading them twice per turn
 *    is negligible.
 *  - When no turn is active every call no-ops on a boolean check.
 *
 * The single summary line per turn gives the before/after comparison:
 *   [StreamPerf] sid=… ticks=N flattenAvgUs=… flattenMaxMs=… frozenHits=N-1/N
 *   rowsLast=total(frozen+live) gcCount=+x gcFreedMB=+y.z turnS=…
 * Baseline equivalents come from the existing PerfLongCtx slow-build lines and
 * logcat GC lines, so old logs remain comparable.
 */
object StreamPerfMonitor {

    private const val TAG = "StreamPerf"

    private var active = false
    private var sessionId: String = ""
    private var turnStartNs = 0L
    private var tickCount = 0L
    private var flattenTotalNs = 0L
    private var flattenMaxNs = 0L
    private var frozenHitTicks = 0L
    private var lastFrozenRows = 0
    private var lastLiveRows = 0
    private var gcCountStart = 0L
    private var gcFreedStart = 0L

    /** ART runtime stat, or 0 when unavailable (older devices / stat renamed). */
    private fun runtimeStat(key: String): Long =
        Debug.getRuntimeStat(key)?.toLongOrNull() ?: 0L

    /** Begin aggregating a streaming turn. Idempotent for the same session;
     *  a different session means the previous turn's collector was cancelled
     *  mid-stream (session switch) — flush its summary before starting. */
    fun turnStart(sid: String) {
        if (active && sid == sessionId) return
        if (active) turnEnd()
        active = true
        sessionId = sid
        turnStartNs = System.nanoTime()
        tickCount = 0
        flattenTotalNs = 0
        flattenMaxNs = 0
        frozenHitTicks = 0
        lastFrozenRows = 0
        lastLiveRows = 0
        gcCountStart = runtimeStat("art.gc.gc-count")
        gcFreedStart = runtimeStat("art.gc.bytes-freed")
    }

    /**
     * One pipeline tick (one sampled flatten+publish). [flattenNanos] covers
     * the off-main flatten work for this tick; [frozenReused] is true when the
     * frozen-prefix cache was reused as-is (the expected steady state).
     */
    fun tick(flattenNanos: Long, frozenReused: Boolean, frozenRows: Int, liveRows: Int) {
        if (!active) return
        tickCount++
        flattenTotalNs += flattenNanos
        if (flattenNanos > flattenMaxNs) flattenMaxNs = flattenNanos
        if (frozenReused) frozenHitTicks++
        lastFrozenRows = frozenRows
        lastLiveRows = liveRows
    }

    /** End the turn and emit the one-line summary. No-op when not active. */
    fun turnEnd() {
        if (!active) return
        active = false
        val turnMs = (System.nanoTime() - turnStartNs) / 1_000_000
        val gcCountDelta = runtimeStat("art.gc.gc-count") - gcCountStart
        val gcFreedDeltaMb = (runtimeStat("art.gc.bytes-freed") - gcFreedStart) / 1_000_000.0
        val avgUs = if (tickCount > 0) flattenTotalNs / tickCount / 1_000 else 0
        AppLogger.info(
            TAG,
            "turn sid=$sessionId ticks=$tickCount flattenAvgUs=$avgUs " +
                "flattenMaxMs=${flattenMaxNs / 1_000_000} frozenHits=$frozenHitTicks/$tickCount " +
                "rowsLast=${lastFrozenRows + lastLiveRows}(frozen=$lastFrozenRows,live=$lastLiveRows) " +
                "gcCount=+$gcCountDelta gcFreedMB=+${String.format(java.util.Locale.US, "%.1f", gcFreedDeltaMb)} " +
                "turnS=${turnMs / 1000}",
        )
    }
}
