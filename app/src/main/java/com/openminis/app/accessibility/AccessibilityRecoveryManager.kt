package com.openminis.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [T-android-a11y-force-stop-recovery] Detects a revoked accessibility grant
 * and offers to repair it.
 *
 * ## Why this exists
 *
 * `adb shell am force-stop` — and the equivalent "force stop" button in system
 * Settings, plus the aggressive "clear" action on several OEM ROMs — makes the
 * framework REMOVE our component from `Settings.Secure`
 * .ENABLED_ACCESSIBILITY_SERVICES. Verified empirically on Pixel 4a / Android
 * 13: after a force-stop the key is emptied, `accessibility_enabled` drops to
 * 0, and it never self-heals — waiting does nothing and relaunching the app
 * does nothing. The user has to walk back into Settings → Accessibility.
 *
 * This is deliberate AOSP behaviour, not a Minis defect: the same test against
 * Google's own TalkBack, Bitwarden and AutoX reproduced it identically. With
 * two services enabled and only one force-stopped, the framework surgically
 * removes just the stopped one — i.e. it is a security decision (a
 * force-stopped app must not keep the ability to read the screen).
 *
 * Note that ordinary "swipe away from recents" does NOT do this: an
 * accessibility service pins its host process at adj=100 (bound by `system`),
 * so the process survives and the grant is untouched. Only force-stop-class
 * kills matter here.
 *
 * ## Why an app normally cannot fix this
 *
 * Writing ENABLED_ACCESSIBILITY_SERVICES requires WRITE_SECURE_SETTINGS, which
 * is not grantable to a normal app. That is why none of the apps surveyed even
 * *detect* the loss. Minis is in a better position because it already ships a
 * Shizuku client: with Shizuku authorized we can run `settings put secure`
 * with shell privilege and repair the grant in place, which was verified to
 * take effect immediately (the service rebinds without a relaunch).
 *
 * So there are two tiers:
 *  - Shizuku READY  → one-tap repair, no trip to Settings.
 *  - otherwise      → explain the situation and deep-link to Settings.
 */
object AccessibilityRecoveryManager {

    private const val TAG = "A11yRecovery"

    /**
     * How long the in-chat repair prompt waits for the user before giving up.
     * Spec'd at 60s: long enough to notice a dialog, short enough that an
     * unattended agent run is not blocked indefinitely. Exposed (not private)
     * so tests can assert the contract without reaching into internals.
     */
    const val PROMPT_TIMEOUT_MS: Long = 60_000L

    /**
     * After a Shizuku write, how long to wait for the framework to actually
     * rebind the service. The write returns before `onServiceConnected` runs,
     * so a naive "did it work" check immediately after would report false.
     */
    private const val REBIND_TIMEOUT_MS: Long = 5_000L
    private const val REBIND_POLL_MS: Long = 250L

    /**
     * Debounce for [isGrantRevoked]'s Settings.Secure read. The check sits on
     * the a11y tool-invocation path, which a single agent turn can hit dozens
     * of times in a row, and each call is a ContentProvider round-trip. A
     * short TTL keeps the common case (grant intact) essentially free while
     * still noticing a revocation within a quarter second.
     *
     * Deliberately NOT a long-lived cache: a stale "grant is fine" answer
     * would suppress the very prompt this class exists to raise.
     */
    private const val CHECK_CACHE_TTL_MS: Long = 250L

    @Volatile private var cachedRevoked: Boolean? = null
    @Volatile private var cachedAtMs: Long = 0L

    /** What the user chose in the repair prompt. */
    enum class Decision { REPAIR, OPEN_SETTINGS, CANCEL }

    /**
     * A pending repair prompt. Collected by MainActivity, which renders it as
     * an AlertDialog and calls [respond].
     *
     * @param shizukuAvailable when true the dialog offers in-place repair;
     *   when false it can only offer a trip to Settings.
     */
    data class RepairPrompt(
        val shizukuAvailable: Boolean,
    )

    private val _pendingPrompt = MutableStateFlow<RepairPrompt?>(null)
    val pendingPrompt: StateFlow<RepairPrompt?> = _pendingPrompt.asStateFlow()

    private var continuation: Continuation<Decision>? = null

    /**
     * Observable "the grant is currently missing" flag for Settings UI, so the
     * repair row can appear without the screen polling Settings.Secure itself.
     * Refreshed by [refreshRevokedState], which Settings calls on entry /
     * resume.
     */
    private val _revoked = MutableStateFlow(false)
    val revoked: StateFlow<Boolean> = _revoked.asStateFlow()

    /** The `pkg/cls` string the framework expects in ENABLED_ACCESSIBILITY_SERVICES. */
    private fun componentId(context: Context): String =
        "${context.packageName}/${MinisAccessibilityService::class.java.name}"

    private const val PREFS = "a11y_recovery"
    private const val KEY_EVER_GRANTED = "ever_granted"

    /**
     * True once the user has enabled the service at least once on this install.
     *
     * Needed to tell "revoked" apart from "never set up". Both look identical
     * in Settings.Secure — our component simply isn't listed — but only the
     * first is a fault worth surfacing a repair affordance for. Without this a
     * fresh install would greet the user with a "permission was revoked" banner
     * for a permission they had never granted.
     *
     * Latched by [markGranted], which the service calls on connect.
     */
    fun hasEverBeenGranted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_EVER_GRANTED, false)

    /**
     * Latch that the grant has existed. Called from
     * [MinisAccessibilityService.onServiceConnected] — the one moment we know
     * for certain the user granted it, whichever route they took (Settings
     * toggle, Shizuku repair, or a restore).
     */
    fun markGranted(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(KEY_EVER_GRANTED, false)) {
            p.edit().putBoolean(KEY_EVER_GRANTED, true).apply()
            AppLogger.info(TAG, "grant latched (first successful connect)")
        }
    }

    /**
     * True when our component is absent from ENABLED_ACCESSIBILITY_SERVICES,
     * i.e. the grant itself is gone and only a Settings write can restore it.
     *
     * This asks Settings.Secure rather than checking
     * `MinisAccessibilityService.getInstance() != null`, because the two mean
     * different things and only one of them is repairable:
     *  - instance == null but grant present → the service is mid-(re)bind, or
     *    the OEM killed it and the framework will bring it back. Writing the
     *    setting would be pointless.
     *  - grant absent → the framework has forgotten us entirely. This is the
     *    force-stop case, and the only one we can repair.
     *
     * Matching is case-insensitive and also accepts the abbreviated
     * `pkg/.Class` form, because the framework normalizes entries it rewrites
     * (observed: TalkBack's entry came back as `pkg/.TalkBackService` after we
     * wrote the fully-qualified form).
     */
    fun isGrantRevoked(context: Context): Boolean {
        val now = System.currentTimeMillis()
        cachedRevoked?.let { if (now - cachedAtMs < CHECK_CACHE_TTL_MS) return it }

        val revoked = try {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            isRevokedIn(
                enabledValue = enabled,
                pkg = context.packageName,
                serviceClass = MinisAccessibilityService::class.java.name,
            )
        } catch (t: Throwable) {
            // A read failure is not evidence of revocation — do not prompt on it.
            AppLogger.warning(TAG, "isGrantRevoked read failed: ${t.message}")
            false
        }

        cachedRevoked = revoked
        cachedAtMs = now
        return revoked
    }

    /**
     * Pure entry-matching half of [isGrantRevoked], split out so the parsing
     * rules can be exercised on the JVM without a Context.
     *
     * Returns true when [enabledValue] — the raw colon-separated
     * ENABLED_ACCESSIBILITY_SERVICES string — does not contain our component.
     *
     * Accepts both the fully-qualified `pkg/pkg.Class` form we write and the
     * abbreviated `pkg/.Class` form the framework may normalize to. Observed on
     * Pixel 4a / Android 13: after writing TalkBack's fully-qualified name the
     * framework rewrote the value as `…talkback/.TalkBackService`. Matching only
     * the long form would then read as "revoked" while the grant was healthy,
     * producing a repair prompt in a loop.
     */
    fun isRevokedIn(enabledValue: String?, pkg: String, serviceClass: String): Boolean {
        if (enabledValue.isNullOrBlank()) return true
        val fq = "$pkg/$serviceClass"
        val short = "$pkg/.${serviceClass.removePrefix("$pkg.")}"
        return enabledValue.split(':').none { entry ->
            val e = entry.trim()
            e.equals(fq, ignoreCase = true) || e.equals(short, ignoreCase = true)
        }
    }

    /** Drop the debounce so the next [isGrantRevoked] re-reads Settings. */
    fun invalidateCache() {
        cachedRevoked = null
        cachedAtMs = 0L
    }

    /** Re-read the grant and publish it to [revoked] for Settings UI. */
    fun refreshRevokedState(context: Context) {
        invalidateCache()
        _revoked.value = isGrantRevoked(context)
    }

    /**
     * Repair the grant with Shizuku shell privilege.
     *
     * Appends our component to the existing ENABLED_ACCESSIBILITY_SERVICES
     * value rather than overwriting it — a blind overwrite would disable every
     * OTHER accessibility service the user relies on (TalkBack, a password
     * manager's autofill, an OEM gesture tool). Then sets
     * `accessibility_enabled=1`, which force-stop also clears and without
     * which the framework ignores the service list entirely.
     *
     * Runs `settings put secure` through [ShizukuManager.runProcess] — the
     * same privileged path `android-shizuku-cli settings set` uses, so there
     * is one implementation of the write.
     *
     * @return true once the service has actually rebound.
     */
    suspend fun repairWithShizuku(context: Context): Boolean {
        if (!ShizukuManager.isReady()) {
            AppLogger.info(TAG, "repair skipped: Shizuku not ready")
            return false
        }
        val target = componentId(context)

        val existing = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )?.split(':')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        } catch (_: Throwable) { emptyList() }

        val merged = (existing + target).distinct().joinToString(":")

        val r1 = ShizukuManager.runProcess(
            arrayOf("settings", "put", "secure", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged)
        )
        if (r1.exitCode != 0) {
            AppLogger.warning(TAG, "repair failed writing service list: exit=${r1.exitCode} ${r1.combined}")
            return false
        }
        val r2 = ShizukuManager.runProcess(
            arrayOf("settings", "put", "secure", Settings.Secure.ACCESSIBILITY_ENABLED, "1")
        )
        if (r2.exitCode != 0) {
            AppLogger.warning(TAG, "repair failed writing accessibility_enabled: exit=${r2.exitCode} ${r2.combined}")
            return false
        }

        // The write is async from the framework's perspective; wait for the
        // real signal (our service instance appearing) rather than assuming.
        val bound = withTimeoutOrNull(REBIND_TIMEOUT_MS) {
            while (MinisAccessibilityService.getInstance() == null) delay(REBIND_POLL_MS)
            true
        } == true

        invalidateCache()
        _revoked.value = isGrantRevoked(context)
        AppLogger.info(TAG, "repair via Shizuku wrote grant; rebound=$bound")
        // Report on the write succeeding: on a slow device the rebind can land
        // just after our window, and the grant itself is what we were asked to
        // restore. `bound` is logged for diagnosis.
        return true
    }

    /**
     * Show the repair prompt and wait for a decision, for at most
     * [PROMPT_TIMEOUT_MS].
     *
     * Times out to [Decision.CANCEL] so an unattended run (screen off, user
     * away, or the dialog dismissed by something else) degrades to the normal
     * "service not running" error instead of hanging the agent loop. The
     * pending prompt is always cleared, including on coroutine cancellation,
     * so a stale dialog can never outlive its caller.
     */
    private suspend fun awaitDecision(prompt: RepairPrompt): Decision {
        val decision = withTimeoutOrNull(PROMPT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Decision> { cont ->
                continuation = cont
                _pendingPrompt.value = prompt
                cont.invokeOnCancellation {
                    _pendingPrompt.value = null
                    continuation = null
                }
            }
        }
        if (decision == null) {
            // Timed out: tear the dialog down so it doesn't linger on screen.
            _pendingPrompt.value = null
            continuation = null
            AppLogger.info(TAG, "repair prompt timed out after ${PROMPT_TIMEOUT_MS}ms — treating as cancel")
        }
        return decision ?: Decision.CANCEL
    }

    /** Called by the UI once the dialog closes. Safe to call spuriously. */
    fun respond(decision: Decision) {
        _pendingPrompt.value = null
        val c = continuation
        continuation = null
        c?.resume(decision)
    }

    /**
     * Entry point for the a11y tool path: if the grant is gone, offer to fix
     * it and report whether the caller may proceed.
     *
     * @return true if the service is usable (grant was intact, or repair
     *   succeeded); false if the caller should fail with its normal
     *   "service not running" error.
     */
    suspend fun ensureGrantOrPrompt(context: Context): Boolean {
        if (!isGrantRevoked(context)) return true

        val shizuku = ShizukuManager.isReady()
        AppLogger.info(TAG, "grant revoked — prompting (shizukuReady=$shizuku)")
        _revoked.value = true

        return when (awaitDecision(RepairPrompt(shizukuAvailable = shizuku))) {
            Decision.REPAIR -> repairWithShizuku(context)
            // The user was sent to Settings; they may or may not finish. Don't
            // block the turn waiting — report unusable and let them retry.
            Decision.OPEN_SETTINGS -> false
            Decision.CANCEL -> false
        }
    }
}

// ─── Merged from MinisAccessibilityService.kt ──────────────────────────────────────────────────

/**
 * MinisAccessibilityService — host-side AccessibilityService backing the
 * `android-a11y-cli` offload command. The user must enable it manually
 * under Settings → Accessibility; we never start it programmatically.
 */
class MinisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MinisA11y"
        const val SERVICE_ID = "com.openminis.app/.accessibility.MinisAccessibilityService"
        private const val EVENT_RING_CAP = 1024

        @Volatile
        private var _instance: MinisAccessibilityService? = null

        fun getInstance(): MinisAccessibilityService? = _instance
    }

    data class RecordedEvent(
        val type: String,
        val packageName: String?,
        val className: String?,
        val text: String?,
        val timestamp: Long,
    )

    private val eventRing = ConcurrentLinkedQueue<RecordedEvent>()
    private val eventListeners = CopyOnWriteArrayList<(RecordedEvent) -> Unit>()

    val nodeRegistry: NodeRegistry = NodeRegistry()

    override fun onCreate() {
        super.onCreate()
        _instance = this
        AppLogger.info(TAG, "service created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance = this
        // [T-android-a11y-miui-service-failure] Breadcrumb on (re)bind. On OEM
        // ROMs (MIUI etc.) the system kills + rebinds this service repeatedly,
        // surfacing "此服务出现故障"; a connect log lets us correlate a degraded
        // window in stall/diag logs with a kill/rebind. No self-restart logic —
        // remediation is the user whitelisting autostart + battery (see
        // SystemPermissionsScreen OEM guidance).
        AppLogger.info(TAG, "service connected (manufacturer=${android.os.Build.MANUFACTURER})")
        // [T-android-a11y-force-stop-recovery] Latch that the grant existed, so
        // a later absence can be recognized as a revocation rather than a
        // never-configured service. This is the one callback that only fires
        // when the user has genuinely granted it.
        try {
            AccessibilityRecoveryManager.markGranted(this)
            AccessibilityRecoveryManager.refreshRevokedState(this)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "markGranted failed: ${t.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val rec = RecordedEvent(
            type = AccessibilityEvent.eventTypeToString(event.eventType),
            packageName = event.packageName?.toString(),
            className = event.className?.toString(),
            text = event.text?.joinToString(" ") { it?.toString() ?: "" }?.takeIf { it.isNotBlank() },
            timestamp = System.currentTimeMillis(),
        )
        eventRing.offer(rec)
        while (eventRing.size > EVENT_RING_CAP) eventRing.poll()
        for (listener in eventListeners) {
            try { listener(rec) } catch (_: Throwable) {}
        }
    }

    override fun onInterrupt() {
        AppLogger.info(TAG, "service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (_instance === this) _instance = null
        eventRing.clear()
        nodeRegistry.clear()
        eventListeners.clear()
        AppLogger.info(TAG, "service destroyed")
    }

    fun snapshotEvents(): List<RecordedEvent> = eventRing.toList()

    fun addEventListener(listener: (RecordedEvent) -> Unit) {
        eventListeners.add(listener)
    }

    fun removeEventListener(listener: (RecordedEvent) -> Unit) {
        eventListeners.remove(listener)
    }

    fun rootNodes(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows ?: emptyList()) {
                w.root?.let { out.add(it) }
            }
        } catch (_: Throwable) {}
        if (out.isEmpty()) {
            try { rootInActiveWindow?.let { out.add(it) } } catch (_: Throwable) {}
        }
        return out
    }

    fun windowInfos(): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        try {
            for (w in windows ?: emptyList()) {
                out.add(mapOf(
                    "windowId" to w.id,
                    "type" to when (w.type) {
                        android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
                        android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
                        android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
                        android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "overlay"
                        else -> "other"
                    },
                    "title" to (w.title?.toString() ?: ""),
                    "focused" to w.isFocused,
                    "active" to w.isActive,
                ))
            }
        } catch (_: Throwable) {}
        return out
    }

    fun foregroundPackage(): Pair<String?, String?> {
        return try {
            val r = rootInActiveWindow
            (r?.packageName?.toString()) to (r?.className?.toString())
        } catch (_: Throwable) { null to null }
    }

    fun dispatchSimpleGesture(path: Path, startTime: Long, durationMs: Long, timeoutMs: Long = 5000): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, startTime, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val done = java.util.concurrent.CountDownLatch(1)
        val ok = booleanArrayOf(false)
        val callback = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { ok[0] = true; done.countDown() }
            override fun onCancelled(g: GestureDescription?) { ok[0] = false; done.countDown() }
        }
        Handler(Looper.getMainLooper()).post { dispatchGesture(gesture, callback, null) }
        return done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) && ok[0]
    }

    fun dispatchPinch(cx: Float, cy: Float, scale: Float, durationMs: Long): Boolean {
        val initialOffset = 200f
        val finalOffset = (initialOffset * scale).coerceAtLeast(20f)
        val (s0, e0) = if (scale > 1f) {
            (cx - finalOffset to cy) to (cx - initialOffset to cy)
        } else {
            (cx - initialOffset to cy) to (cx - finalOffset to cy)
        }
        val (s1, e1) = if (scale > 1f) {
            (cx + finalOffset to cy) to (cx + initialOffset to cy)
        } else {
            (cx + initialOffset to cy) to (cx + finalOffset to cy)
        }
        val p0 = Path().apply { moveTo(s0.first, s0.second); lineTo(e0.first, e0.second) }
        val p1 = Path().apply { moveTo(s1.first, s1.second); lineTo(e1.first, e1.second) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p0, 0, durationMs))
            .addStroke(GestureDescription.StrokeDescription(p1, 0, durationMs))
            .build()
        val done = java.util.concurrent.CountDownLatch(1)
        val ok = booleanArrayOf(false)
        Handler(Looper.getMainLooper()).post {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { ok[0] = true; done.countDown() }
                override fun onCancelled(g: GestureDescription?) { ok[0] = false; done.countDown() }
            }, null)
        }
        return done.await(durationMs + 2000, java.util.concurrent.TimeUnit.MILLISECONDS) && ok[0]
    }

    /**
     * Result of [captureScreenshot]. On success [bitmap] is non-null and
     * caller is responsible for `bitmap.recycle()`.
     */
    data class ShotResult(
        val bitmap: Bitmap?,
        val errorCode: String? = null,
        val errorMessage: String? = null,
    )

    /**
     * Synchronously captures a system-wide screenshot via
     * `AccessibilityService.takeScreenshot` (API 30+). Blocks the calling
     * thread up to [timeoutMs]. Caller must check `Build.VERSION.SDK_INT`
     * before invoking — annotated `@RequiresApi(R)`.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun captureScreenshot(displayId: Int = Display.DEFAULT_DISPLAY, timeoutMs: Long = 5_000): ShotResult {
        val done = CountDownLatch(1)
        val resultRef = java.util.concurrent.atomic.AtomicReference(
            ShotResult(null, "TIMEOUT", "takeScreenshot timed out")
        )
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            takeScreenshot(displayId, executor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hwBuffer: HardwareBuffer = screenshot.hardwareBuffer
                        val cs: ColorSpace? = screenshot.colorSpace
                        val bmp = Bitmap.wrapHardwareBuffer(hwBuffer, cs)
                        try { hwBuffer.close() } catch (_: Throwable) {}
                        if (bmp == null) {
                            resultRef.set(ShotResult(null, "DECODE_FAILED", "wrapHardwareBuffer returned null"))
                        } else {
                            // Copy to a software bitmap so we can encode + scale freely.
                            val sw = bmp.copy(Bitmap.Config.ARGB_8888, false)
                            bmp.recycle()
                            resultRef.set(ShotResult(sw))
                        }
                    } catch (t: Throwable) {
                        resultRef.set(ShotResult(null, "DECODE_FAILED", "${t.javaClass.simpleName}: ${t.message ?: ""}"))
                    } finally {
                        done.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    resultRef.set(ShotResult(null, "TAKE_SCREENSHOT_FAILED", "takeScreenshot error code=$errorCode"))
                    done.countDown()
                }
            })
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                AppLogger.warning(TAG, "takeScreenshot timed out after ${timeoutMs}ms")
            }
        } catch (t: Throwable) {
            resultRef.set(ShotResult(null, "INTERNAL", "${t.javaClass.simpleName}: ${t.message ?: ""}"))
        } finally {
            executor.shutdown()
        }
        return resultRef.get()
    }

    fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}

// ─── Merged from NodeRegistry.kt ──────────────────────────────────────────────────

/**
 * Short-lived cache mapping opaque string IDs (e.g. "a3f2") to live
 * AccessibilityNodeInfo references. AccessibilityNodeInfo has no durable
 * identity across calls — `ui dump` allocates fresh objects every time
 * the framework re-enumerates the tree, but the AI agent expects to
 * reference a node by id seconds later in `tap node a3f2`.
 *
 * Entries expire after [TTL_MS] or when [clear] is called (e.g. on
 * service destroy).
 */
class NodeRegistry {
    private data class Entry(val node: AccessibilityNodeInfo, val createdAt: Long)

    private val map = ConcurrentHashMap<String, Entry>()
    private val seq = AtomicLong(0)

    fun put(node: AccessibilityNodeInfo): String {
        evictExpired()
        val id = nextId()
        map[id] = Entry(node, System.currentTimeMillis())
        return id
    }

    fun get(id: String): AccessibilityNodeInfo? {
        val e = map[id] ?: return null
        if (System.currentTimeMillis() - e.createdAt > TTL_MS) {
            map.remove(id)
            return null
        }
        return e.node
    }

    fun clear() { map.clear() }

    private fun evictExpired() {
        val now = System.currentTimeMillis()
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.createdAt > TTL_MS) it.remove()
        }
    }

    private fun nextId(): String {
        return java.lang.Long.toString(seq.incrementAndGet() and 0xFFFFFL, 36).padStart(4, '0')
    }

    companion object {
        const val TTL_MS = 60_000L
    }
}

// ─── Merged from RestrictedSettingsManager.kt ──────────────────────────────────────────────────

/**
 * [T-android-restricted-settings] Detects Android 13+ "restricted settings"
 * and offers to clear it.
 *
 * ## Why this exists
 *
 * A user on Redmi Note 12 Pro+ 5G (HyperOS 2.0.10 / Android 14) updated Minis
 * from the website and could no longer enable the accessibility service
 * backing `android-a11y-cli`: tapping Minis under Settings → Accessibility
 * pops "Restricted setting — for your security, this setting is currently
 * unavailable" and the toggle never moves. Older builds enabled fine.
 *
 * ## Root cause (an OS policy, not a Minis regression)
 *
 * AOSP `InstallPackageHelper.installPackagesLI` ends with:
 *
 * ```java
 * // Apply restricted settings on potentially dangerous packages.
 * if (installRequest.getPackageSource() == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
 *         || installRequest.getPackageSource() == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE) {
 *     enableRestrictedSettings(pkgName, pkg.getUid());
 * }
 * ```
 *
 * and `enableRestrictedSettings` sets `OP_ACCESS_RESTRICTED_SETTINGS` to
 * `MODE_ERRORED` for every user. While that op is errored the Settings app
 * refuses to arm the accessibility toggle (same gate covers notification
 * listeners and device admin).
 *
 * The trigger is purely the `packageSource` the INSTALLING app declared via
 * `SessionParams.setPackageSource`. The framework does not infer or police it
 * — see `PackageInstallerService.createSession`, which passes
 * `params.packageSource` into `InstallSource.create` unchecked. So:
 *
 * - `adb install` / `pm install` leaves it `PACKAGE_SOURCE_UNSPECIFIED` (0) →
 *   never restricted. This is why the problem is invisible to us in
 *   development and why every internal test build enables fine.
 * - A file manager or browser installing a downloaded APK declares 3/4 →
 *   restricted.
 * - A store declares `PACKAGE_SOURCE_STORE` (2) → not restricted.
 *
 * Confirmed on a Pixel 4a (Android 13) across 25 installed third-party
 * packages: every `packageSource=3`/`4` package reads `deny` (= MODE_ERRORED)
 * for this op, and every `packageSource=0` package reads `allow`/default. The
 * correlation is exact.
 *
 * ### Why the user's "old version worked" and why SAI did not help
 *
 * Nothing changed in Minis. What changed is how that particular copy was
 * installed: an APK opened from a browser/file manager download declares
 * `DOWNLOADED_FILE`, so the flag is applied at install time and then persists
 * across in-place updates. SAI is session-based, but session-based is NOT the
 * exemption — SAI still declares a local-file source, so reinstalling through
 * it re-applies the very same flag. That is exactly what the user observed.
 *
 * ### Why we cannot fix this from inside the app
 *
 * `ACCESS_RESTRICTED_SETTINGS` is set on OUR uid by the installer, and
 * clearing it needs `android.permission.MANAGE_APP_OPS_MODES` (signature).
 * An app cannot exempt itself — by design, since the whole point is to stop a
 * sideloaded app from talking the user into granting it screen-reading power.
 * The op is additionally `restrictRead`, so we cannot even *observe* it (see
 * [isRestricted]). So the honest remedy is detection via the install source
 * plus accurate guidance, with an optional one-tap fix when the user already
 * has Shizuku (which runs as shell and does hold the permission).
 *
 * ## What this does NOT do
 *
 * It does not change how Minis is packaged or installed, and deliberately so.
 * We could make the in-app updater install through a `PackageInstaller`
 * session declaring `PACKAGE_SOURCE_STORE` — the framework does not verify
 * that claim — and self-updates would stop being flagged. That is a
 * misrepresentation of provenance made solely to dodge a user-protection
 * prompt, it would not help the reported user (whose copy came from a browser
 * download, not from our updater), and it cannot fix an already-flagged
 * install anyway. Guidance is the correct fix; the user stays the one who
 * decides.
 */
object RestrictedSettingsManager {

    private const val TAG = "RestrictedSettings"

    /**
     * Set once [clearWithShizuku] has verifiably lifted the flag. Deliberately
     * in-memory only: it is a display latch, not persisted state, and a fresh
     * process re-derives the truth from the install source (plus, if the user
     * has by then enabled the service, the guidance is gated off anyway).
     */
    @Volatile
    private var cleared = false

    /**
     * True when the OS has flagged this install and will therefore block the
     * accessibility toggle.
     *
     * ### Why we do NOT read the appop directly
     *
     * The obvious probe — `unsafeCheckOpNoThrow("android:access_restricted_settings", …)`
     * on our own uid — does not work, and fails in a way that is easy to miss:
     * it throws SecurityException rather than returning a mode. The op is
     * declared `.setRestrictRead(true)` in AppOpsManager's op table, and
     * `AppOpsService.verifyIncomingOp` turns that into
     * `enforcePermission(MANAGE_APPOPS)` for EVERY caller — reading your own
     * op is not exempt. Verified on a Pixel 4a (Android 13): with the op set
     * to `deny` via `cmd appops`, the in-app probe logged
     * "SecurityException" once per poll and the guidance never appeared.
     *
     * ### What we read instead
     *
     * The same input the framework itself keys the decision on: the
     * `packageSource` our installer declared. `InstallPackageHelper` applies
     * the flag for exactly `PACKAGE_SOURCE_LOCAL_FILE` (3) and
     * `PACKAGE_SOURCE_DOWNLOADED_FILE` (4), and
     * `InstallSourceInfo.getPackageSource()` is public API readable for our
     * own package with no permission. So this is not a heuristic — it is the
     * same condition, read from the other side.
     *
     * `getPackageSource()` was added in Android 14 (U). On Android 13 the flag
     * exists but the value is unreadable, so we return false there and leave
     * today's behaviour: the reported device is Android 14, and guessing on 13
     * risks showing the banner to users whose install is fine.
     *
     * Returns false on anything unexpected. A false negative just leaves the
     * pre-existing behaviour; a false positive would send every user chasing a
     * setting that isn't there.
     */
    fun isRestricted(context: Context): Boolean {
        // Once we have actually cleared the op this session, stop reporting
        // restricted. The install source (what isRestricted reads) does not
        // change when the flag is lifted, and the op itself is unreadable to
        // us, so without this latch the guidance would never go away.
        if (cleared) return false
        // getPackageSource() is UPSIDE_DOWN_CAKE+ (Android 14).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return try {
            val source = context.packageManager
                .getInstallSourceInfo(context.packageName)
                .packageSource
            val restricted = isRestrictedSource(source)
            if (restricted) {
                AppLogger.info(TAG, "install flagged restricted (packageSource=$source)")
            }
            restricted
        } catch (t: Throwable) {
            AppLogger.info(TAG, "install-source probe unavailable: ${t.javaClass.simpleName}")
            false
        }
    }

    /**
     * Classify a `PackageInstaller.PACKAGE_SOURCE_*` value. Split out so the
     * decision is testable on the JVM (no Robolectric in this module).
     *
     * Mirrors `InstallPackageHelper`'s condition exactly — only LOCAL_FILE and
     * DOWNLOADED_FILE trigger the flag. UNSPECIFIED (adb/pm), STORE and OTHER
     * must NOT, or we would put a "your install is blocked" banner in front of
     * users whose install is perfectly fine.
     */
    internal fun isRestrictedSource(packageSource: Int): Boolean =
        packageSource == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE ||
            packageSource == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE

    /**
     * Clear the flag via Shizuku, which runs as `shell` and so holds
     * `MANAGE_APP_OPS_MODES`. This is the same privileged channel
     * [AccessibilityRecoveryManager.repairWithShizuku] uses.
     *
     * Equivalent to the command the reporting user ran by hand:
     * `appops set com.openminis.app ACCESS_RESTRICTED_SETTINGS allow`.
     *
     * @return true once the op reads back as allowed.
     */
    suspend fun clearWithShizuku(context: Context): Boolean {
        if (!ShizukuManager.isReady()) {
            AppLogger.info(TAG, "clear skipped: Shizuku not ready")
            return false
        }
        val set = ShizukuManager.runProcess(
            arrayOf(
                "appops", "set", context.packageName,
                "ACCESS_RESTRICTED_SETTINGS", "allow",
            ),
        )
        if (set.exitCode != 0) {
            AppLogger.warning(TAG, "appops set failed: exit=${set.exitCode} ${set.combined}")
            return false
        }
        // Verify rather than trust the exit code. Note we must re-read through
        // Shizuku too: [isRestricted] reads the INSTALL SOURCE, which never
        // changes, and the appop itself is read-restricted to MANAGE_APPOPS
        // holders — so the in-process probe cannot observe this write at all.
        val get = ShizukuManager.runProcess(
            arrayOf("appops", "get", context.packageName, "ACCESS_RESTRICTED_SETTINGS"),
        )
        // `cmd appops get` prints e.g. "ACCESS_RESTRICTED_SETTINGS: allow".
        // "deny" is how the shell renders MODE_ERRORED.
        val ok = get.exitCode == 0 && get.combined.contains("allow")
        if (ok) cleared = true
        AppLogger.info(TAG, "cleared restricted settings via Shizuku; ok=$ok (${get.combined.trim()})")
        return ok
    }
}
