package com.openminis.app.browser

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.browser.customtabs.CustomTabsIntent
import com.openminis.app.logging.AppLogger
import java.io.File
import java.lang.ref.WeakReference
import java.net.URI
import java.nio.ByteBuffer
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * All browser actions available to the agent, mirroring iOS BrowserAction.
 */
enum class BrowserAction(val value: String) {
    NAVIGATE("navigate"),
    SCREENSHOT("screenshot"),
    CLICK("click"),
    TYPE("type"),
    GET_TEXT("get_text"),
    SCROLL("scroll"),
    GET_PAGE_INFO("get_page_info"),
    EXECUTE_JS("execute_js"),
    FIND_ELEMENTS("find_elements"),
    HOVER("hover"),
    GET_READABLE("get_readable"),
    SET_USER_AGENT("set_user_agent"),
    SET_VIEWPORT("set_viewport"),
    GET_BACKBONE("get_backbone"),
    FETCH("fetch"),
    NEW_TAB("new_tab"),
    CLOSE_TAB("close_tab"),
    LIST_TABS("list_tabs"),
    GET_COOKIES("get_cookies"),
    SET_COOKIES("set_cookies"),
    SCROLL_AND_COLLECT("scroll_and_collect"),
    WAIT_FOR_DOM_STABLE("wait_for_dom_stable");

    /**
     * [T-browser-readaction-follow-tab-and-yolo-android] True when this action
     * opens / replaces the page (navigate) and may therefore fan out to
     * a fresh tab under the implicit-tab grace window. False for
     * operate-current-page actions (execute_js, get_text, click, …) which must
     * follow selectedTabId.
     */
    val opensNewPage: Boolean get() = this in opensNewPageActions

    companion object {
        private val map = entries.associateBy { it.value }
        fun fromString(s: String): BrowserAction? = map[s]

        /** Actions that visually change the page and warrant an auto-snapshot. */
        val visualChangeActions: Set<BrowserAction> = setOf(
            NAVIGATE, CLICK, SCROLL, HOVER, TYPE
        )

        /**
         * [T-browser-readaction-follow-tab-and-yolo-android] Actions that open
         * or replace the page in a tab — these are the ONLY actions that should
         * fan out to a fresh tab when an implicit (tab_id-less) call lands while
         * the previously-used tab is still inside its inUse grace window. The
         * grace-based fan-out exists so N back-to-back *navigates* open N
         * distinct tabs instead of trampling tab 0.
         *
         * Every other action operates on the CURRENT page (read DOM, click,
         * scroll, screenshot, …). For those, fanning out to a fresh/other tab
         * is exactly the #612/#614 bug: `navigate A → execute_js` ran the JS on
         * a different (blank or stale) tab because navigate's tab was still in
         * grace. Those actions must follow `selectedTabId` (the most-recently
         * navigated tab), never fan out. See [opensNewPage].
         *
         * NEW_TAB / CLOSE_TAB / LIST_TABS / SET_VIEWPORT are handled at the pool
         * level before tab acquisition, so they're intentionally absent here.
         */
        val opensNewPageActions: Set<BrowserAction> = setOf(
            NAVIGATE
        )

        val allValues: List<String> = entries.map { it.value }
    }
}

enum class ScrollDirection(val value: String) {
    UP("up"), DOWN("down");
    companion object {
        fun fromString(s: String): ScrollDirection? = entries.find { it.value == s }
    }
}

enum class UserAgentProfile(val value: String) {
    MOBILE_CHROME("mobile_chrome"),
    DESKTOP_CHROME("desktop_chrome"),
    CUSTOM("custom");

    val userAgentString: String?
        get() = when (this) {
            MOBILE_CHROME -> "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"
            DESKTOP_CHROME -> "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
            CUSTOM -> null
        }

    val displayName: String
        get() = when (this) {
            MOBILE_CHROME -> "Mobile Chrome"
            DESKTOP_CHROME -> "Desktop Chrome"
            CUSTOM -> "Custom"
        }

    val viewportSize: Pair<Int, Int>
        get() = when (this) {
            DESKTOP_CHROME -> 1280 to 800
            MOBILE_CHROME, CUSTOM -> 412 to 915
        }

    companion object {
        fun fromString(s: String): UserAgentProfile? = entries.find { it.value == s }

        /**
         * [ISS-033] A user_agent argument: case-insensitive, "-" read as "_",
         * and "desktop" / "mobile" accepted as aliases. Null when unknown - it
         * used to fall back to mobile silently, so "desktop" switched to MOBILE.
         */
        fun parse(raw: String?): UserAgentProfile? {
            val s = raw?.trim()?.lowercase()?.replace('-', '_') ?: return null
            return when (s) {
                "desktop" -> DESKTOP_CHROME
                "mobile" -> MOBILE_CHROME
                else -> fromString(s)
            }
        }
    }
}

// ─── Merged from BrowserActionGuard.kt ──────────────────────────────────────────────────

/**
 * Thrown when a tab's WebView can no longer answer: its renderer process died,
 * or a JS evaluation got no callback within [BrowserActionGuard.JS_EVAL_TIMEOUT_MS].
 * The manager marks itself unusable before throwing, so the pool rebuilds the
 * tab on the next acquire even when an intermediate caller swallows this.
 */
class WebViewWedgedException(message: String) : IllegalStateException(message)

/**
 * (GH#245) Timing rules for one browser action, kept free of WebView so they
 * can be unit-tested with plain coroutines.
 *
 * Background: a renderer that HANGS (page JS spinning, compositor stuck) never
 * fires `onRenderProcessGone`, and `WebView.evaluateJavascript` then never
 * calls back. Before this, nothing bounded that wait inside the pool, nothing
 * marked the tab bad, and the CLI's single-tab driver kept routing every
 * command back to the same frozen tab — each one burning 60s and returning a
 * misleading "tab busy" error, forever. iOS had the sibling bug (the CLI gave
 * up after 90s but the native side held its serial slot for 300s); on Android
 * structured concurrency already propagates the CLI's cancellation into the
 * pool and `Mutex` releases on it, so what was missing is the deadline and the
 * rebuild, not a cancel bridge.
 */
internal object BrowserActionGuard {
    /** Budget for one `evaluateJavascript` callback. Healthy pages answer in ms. */
    const val JS_EVAL_TIMEOUT_MS = 15_000L

    /**
     * Hard ceiling on one page operation once its tab is held. Longer than the
     * longest legitimate action (fetch awaits up to 60s; wait_for_dom_stable
     * caps at 60s) and shorter than the CLI's 90s, so the pool trips first,
     * reports the real cause and marks the tab for rebuild.
     */
    const val ACTION_DEAD_TIMEOUT_MS = 75_000L

    /**
     * A cancellation that arrives after the action has run this long is taken
     * as "the page stopped answering" (the CLI's 90s timeout, or the deadline
     * above). A shorter one is a user pressing Stop on a healthy page, which
     * must not throw that page away.
     */
    const val WEDGE_SUSPECT_MS = 30_000L

    /**
     * How long a call waits for ANOTHER call's operation on the same explicit
     * tab id to release its lock. Purely the lock wait — the operation itself
     * is bounded by [ACTION_DEAD_TIMEOUT_MS].
     */
    const val TAB_SERIAL_WAIT_TIMEOUT_MS = 60_000L

    sealed class Outcome<out T> {
        data class Done<T>(val value: T) : Outcome<T>()
        /** The operation outlived [ACTION_DEAD_TIMEOUT_MS]; the tab was flagged. */
        data class DeadlineExceeded(val elapsedMs: Long) : Outcome<Nothing>()
        /** The WebView reported itself unusable mid-action. */
        data class Wedged(val message: String) : Outcome<Nothing>()
    }

    /**
     * Wait at most [waitMs] for [mutex]. Returns true when the caller now owns
     * it (and must unlock it in a finally), false when another holder kept it
     * past the window. A lock wait cancelled mid-way never leaves the mutex
     * owned — `Mutex.lock` gives prompt-cancellation guarantees.
     */
    suspend fun lockWithin(mutex: Mutex, waitMs: Long): Boolean =
        withTimeoutOrNull(waitMs) { mutex.lock() } != null

    /**
     * Run one page operation under [deadlineMs].
     *
     * [onSuspect] is called with a reason whenever the tab should be treated
     * as frozen: the deadline fired, or the caller was cancelled after at
     * least [suspectMs]. A cancellation is ALWAYS rethrown — swallowing it
     * would break the caller's structured concurrency.
     */
    suspend fun <T : Any> runGuarded(
        deadlineMs: Long = ACTION_DEAD_TIMEOUT_MS,
        suspectMs: Long = WEDGE_SUSPECT_MS,
        clock: () -> Long = { System.nanoTime() / 1_000_000L },
        onSuspect: (String) -> Unit,
        block: suspend () -> T,
    ): Outcome<T> {
        val startedAt = clock()
        return try {
            val value = withTimeoutOrNull(deadlineMs) { block() }
            if (value != null) {
                Outcome.Done(value)
            } else {
                val elapsed = clock() - startedAt
                onSuspect("action exceeded ${deadlineMs / 1000}s deadline")
                Outcome.DeadlineExceeded(elapsed)
            }
        } catch (e: WebViewWedgedException) {
            Outcome.Wedged(e.message ?: "browser tab stopped responding")
        } catch (e: CancellationException) {
            val elapsed = clock() - startedAt
            if (elapsed >= suspectMs) onSuspect("cancelled after ${elapsed}ms")
            throw e
        }
    }
}

// ─── Merged from BrowserActionInput.kt ──────────────────────────────────────────────────

/**
 * Parsed input for a browser_use tool call, mirroring iOS BrowserActionInput.
 */
data class BrowserActionInput(
    val action: BrowserAction,
    val url: String? = null,
    val selector: String? = null,
    val text: String? = null,
    val coordinateX: Int? = null,
    val coordinateY: Int? = null,
    val direction: ScrollDirection? = null,
    val amount: Int? = null,
    val script: String? = null,
    val userAgent: UserAgentProfile? = null,
    /** [ISS-033] The user_agent argument as given, for the error text when it is unknown. */
    val userAgentRaw: String? = null,
    val maxDepth: Int? = null,
    val tabId: Int? = null,
    /** Viewport width override (set_viewport action). */
    val viewportWidth: Int? = null,
    /** Viewport height override (set_viewport action). */
    val viewportHeight: Int? = null,
    /** When true on set_viewport, drop the session-level viewport override. */
    val reset: Boolean = false,
    /** Cookie-name / scroll_and_collect keyword filter. */
    val keywords: List<String>? = null,
    /** Fuzzy-match cookie names for get_cookies. */
    val fuzzy: Boolean = false,
    /** Item selector for scroll_and_collect (each matching element is captured). */
    val itemSelector: String? = null,
    /** Scroll iterations for scroll_and_collect. */
    val scrollCount: Int? = null,
    /** Milliseconds to wait (wait_for_dom_stable). */
    val timeoutMs: Int? = null,
    /** When true on screenshot, stretch viewport to full document.scrollHeight before capture. */
    val fullPage: Boolean = false,
    /**
     * Cookies to write (set_cookies). Each entry has keys name + value
     * (required) and optional domain, path, secure, http_only, expires
     * (Unix seconds). Values are loosely typed (String / Boolean / Number).
     */
    val cookies: List<Map<String, Any?>>? = null,
) {
    companion object {
        fun parse(json: String): BrowserActionInput? {
            return try {
                val obj = JSONObject(json)
                val actionStr = obj.optString("action", "")
                val action = BrowserAction.fromString(actionStr) ?: return null

                BrowserActionInput(
                    action = action,
                    url = obj.optString("url").ifEmpty { null },
                    selector = obj.optString("selector").ifEmpty { null },
                    text = obj.optString("text").ifEmpty { null },
                    coordinateX = if (obj.has("coordinate_x")) obj.optInt("coordinate_x") else null,
                    coordinateY = if (obj.has("coordinate_y")) obj.optInt("coordinate_y") else null,
                    direction = obj.optString("direction").ifEmpty { null }?.let { ScrollDirection.fromString(it) },
                    amount = if (obj.has("amount")) obj.optInt("amount") else null,
                    script = obj.optString("script").ifEmpty { null },
                    userAgent = UserAgentProfile.parse(obj.optString("user_agent").ifEmpty { null }),
                    userAgentRaw = obj.optString("user_agent").ifEmpty { null },
                    maxDepth = if (obj.has("max_depth")) obj.optInt("max_depth") else null,
                    tabId = if (obj.has("tab_id")) obj.optInt("tab_id") else null,
                    viewportWidth = if (obj.has("viewport_width")) obj.optInt("viewport_width") else null,
                    viewportHeight = if (obj.has("viewport_height")) obj.optInt("viewport_height") else null,
                    reset = obj.optBoolean("reset", false),
                    keywords = obj.optJSONArray("keywords")?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                            .takeIf { it.isNotEmpty() }
                    },
                    fuzzy = obj.optBoolean("fuzzy", false),
                    itemSelector = obj.optString("item_selector").ifEmpty { null },
                    scrollCount = if (obj.has("scroll_count")) obj.optInt("scroll_count") else null,
                    timeoutMs = if (obj.has("timeout")) obj.optInt("timeout") else null,
                    fullPage = obj.optBoolean("full_page", false),
                    cookies = parseCookies(obj),
                )
            } catch (_: Exception) {
                null
            }
        }

        /**
         * `cookies` may arrive either as a real JSON array or — because the tool
         * schema can only type it as a string — as a JSON-encoded STRING like
         * `"[{\"name\":...}]"`. Accept both so a schema-faithful model (which
         * emits the string form) isn't silently dropped to empty. Returns null
         * when absent / unparseable / empty.
         */
        private fun parseCookies(obj: JSONObject): List<Map<String, Any?>>? {
            val arr: JSONArray? = obj.optJSONArray("cookies")
                ?: obj.optString("cookies").takeIf { it.isNotBlank() }
                    ?.let { runCatching { JSONArray(it) }.getOrNull() }
            arr ?: return null
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { co ->
                    co.keys().asSequence().associateWith { k -> co.get(k) }
                }
            }.takeIf { it.isNotEmpty() }
        }
    }
}

// ─── Merged from BrowserActionResult.kt ──────────────────────────────────────────────────

/**
 * Result of a browser action execution, mirroring iOS BrowserActionResult.
 */
data class BrowserActionResult(
    val text: String,
    val success: Boolean = true,
    /** Base64-encoded JPEG for the vision API. */
    val base64Image: String? = null,
    /** Local file path where the screenshot was saved to disk. */
    val imageFilePath: String? = null,
    /** Raw downloaded file data (for fetch action). */
    val fetchedFileData: ByteArray? = null,
    /** Determined filename for the fetched file. */
    val fetchedFileName: String? = null,
    /** The page URL at the time this action was executed. */
    val pageURL: String? = null,
    /**
     * [T-android-browser-result-tab-id] The tab id this action actually ran on.
     * Stamped by [com.openminis.app.browser.BrowserTabPool] at the point of
     * dispatch using the acquired tab's id (the VERIFIED target), NOT the global
     * selectedTabId — which the fan-out branch overwrites when it spawns a fresh
     * tab for a concurrent navigate. Without this, the agent had to guess tab_id
     * for its follow-up reads/scrolls and routinely picked the wrong one,
     * sometimes surfacing as empty get_text results.
     */
    val tabId: Int? = null,
) {
    companion object {
        fun error(message: String) = BrowserActionResult(
            text = "Error: $message",
            success = false,
        )
    }
}

// ─── Merged from BrowserTabPoolRegistry.kt ──────────────────────────────────────────────────

/**
 * [T-android-browser-global-tab-cap] Process-wide cap on live WebView tabs
 * across every [BrowserTabPool].
 *
 * Why this exists: [T-android-browser-tab-ownership] raised a pool's ceiling
 * from 3 to [BrowserTabPool.MAX_TABS_WITH_AGENTS] = 6 so concurrent sub agents
 * would stop closing each other's tabs. But an Android pool is NOT a singleton
 * the way iOS's effectively is — one is built per chat ViewModel
 * (`ChatViewModel.kt`), one is resident for `minis-browser-use`
 * (`MinisApp.kt`), and the session list builds one of its own
 * (`SessionListScreen.kt`). So the raised ceiling multiplies by the number of
 * live pools: two chats each running two sub agents is a theoretical peak of
 * 2 x 6 = 12 WebViews. Each Android WebView carries its own out-of-process
 * renderer (tens of MB resident, plus a process record the LMK counts), which
 * is heavier than iOS's WKWebView — that peak is a reclaim storm, not a
 * capacity plan.
 *
 * The fix is a cross-pool gate rather than lowering the per-pool ceiling,
 * because the per-pool number is doing a different and still-correct job:
 * bounding how many tabs ONE conversation's agents may fan out to. Lowering it
 * would re-break the incident that raised it (agents closing each other's
 * tabs) on a single-chat device, where nothing is actually over budget. What
 * is over budget is the process total, and only something above the pools can
 * see that.
 *
 * Arbitration is ported verbatim from iOS `BrowserTabPoolRegistry.requestSlot`
 * (`src/ios/Agent/BrowserUse/BrowserTabPool.swift`) — the semantics are
 * already settled there and are deliberately not re-litigated:
 *
 *  1. under [GLOBAL_TAB_CAP] -> admit
 *  2. otherwise evict the least-recently-active IDLE tab in a SIBLING pool
 *  3. otherwise preempt the least-recently-active BUSY tab in a sibling pool
 *  4. no sibling tabs at all -> deny
 *
 * The requester's own pool is excluded from every pass: a pool must never
 * solve its own admission by cannibalising itself, or an agent's two tabs
 * would take turns destroying each other.
 *
 * ## Concurrency
 *
 * **The registry is main-thread-confined and takes no locks of its own.**
 *
 * That is the whole concurrency strategy, and it is what makes the documented
 * hazard — "never hold pool A's lock while reaching for pool B's" —
 * structurally impossible rather than merely avoided by care:
 *
 *  - Every tab-mutating path in [BrowserTabPool] already runs inside
 *    `withContext(Dispatchers.Main)` (`acquireTab`, `newTab`, `closeTab`,
 *    `createTab`'s callers, `evictIdleTabs`). The registry is only ever
 *    entered from those paths, so [pools] and every victim pool's tab list are
 *    touched on one thread. No mutex is needed to protect them, so there is no
 *    registry lock that could participate in a cycle.
 *  - `BrowserTabPool.executeSerialized` DOES hold a per-tab [kotlinx.coroutines.sync.Mutex]
 *    while the call stack below it reaches `createTab` and therefore this
 *    registry. That is the inversion risk, and it is defused by the registry
 *    never acquiring a `tabLocks` mutex — not the requester's and not the
 *    victim's. Eviction reaches the victim pool through
 *    [RegistryPool.evictTabForRegistry] / [RegistryPool.preemptTabForRegistry],
 *    which mutate `_tabs` and tear the WebView down directly. A victim tab
 *    whose serial mutex is held by some other coroutine is preempted anyway;
 *    that coroutine discovers it on its next call and gets a retry hint (see
 *    [RegistryPool.preemptTabForRegistry]). Waiting for that mutex is exactly
 *    the deadlock we are refusing to build.
 *  - Consequently the registry's methods must stay synchronous and non-
 *    suspending. A `suspend` here would invite someone to `withLock` inside it
 *    later, which is the failure this note exists to prevent.
 *
 * ## Single-pool devices see nothing
 *
 * With one chat open and no agents running, `totalLiveTabs()` sits at 1-3
 * against a cap of [GLOBAL_TAB_CAP], so [requestSlot] returns true on its
 * first line without scanning anything. Step 4 also means a lone pool at cap
 * is DENIED rather than made to eat its own tabs — but a lone pool can never
 * reach the global cap in the first place, since its own ceiling (6) is below
 * it. Covered by `BrowserTabPoolRegistryTest`.
 */
object BrowserTabPoolRegistry {

    private const val TAG = "BrowserTabPoolRegistry"

    /**
     * Hard cap on live WebView tabs across the whole process.
     *
     * iOS uses 8. Android keeps 8 as well, deliberately, even though an
     * Android WebView is the heavier of the two: the number has to sit ABOVE a
     * single pool's own ceiling ([BrowserTabPool.MAX_TABS_WITH_AGENTS] = 6) or
     * the registry would start arbitrating the ordinary single-chat case that
     * per-pool ownership already handles correctly — a user with one chat and
     * two sub agents would suddenly see tabs evicted for no reason visible to
     * them. That leaves 7 or 8 as the only real candidates, and 8 gives one
     * fully-loaded agent chat (6) room to coexist with the resident
     * `minis-browser-use` pool (1-2) without either preempting the other,
     * which is the exact pair that is always live. The peak this replaces is
     * 12+, so the reduction is already the bulk of the win; shaving to 7 would
     * buy one WebView at the cost of making that common pair contend.
     */
    const val GLOBAL_TAB_CAP = 8

    /**
     * The slice of [BrowserTabPool] the registry drives. Extracted as an
     * interface so the arbitration above is unit-testable: the real pool needs
     * a `Context` and constructs real `WebView`s, neither of which exists in a
     * JVM unit test, and this logic is precisely the part worth testing.
     */
    interface RegistryPool {
        /** Snapshot of this pool's tabs, oldest-activity ordering not assumed. */
        fun registrySnapshot(): List<TabInfo>

        /**
         * Destroy an idle tab on behalf of the registry. The caller has
         * already established `inUse == false`; implementations re-check.
         * Must actually release the WebView, not merely drop the list entry.
         */
        fun evictTabForRegistry(id: Int)

        /**
         * Destroy an in-flight tab on behalf of the registry. The agent
         * driving it must get an intelligible, retryable error rather than a
         * bare exception.
         */
        fun preemptTabForRegistry(id: Int)

        /** Destroy every idle tab; returns how many went. Used by onTrimMemory. */
        fun evictAllIdleForMemoryPressure(): Int
    }

    /** The two fields arbitration needs, lifted out of `BrowserTabPool.Tab`. */
    data class TabInfo(val id: Int, val inUse: Boolean, val lastActivityDate: Date)

    /**
     * Weakly held so a closed chat's pool does not keep its WebViews counted
     * (or alive) after the ViewModel is cleared. Mirrors iOS's `WeakPool`.
     * Identity-keyed: pools are compared by reference, never by equals.
     */
    private val pools = mutableListOf<WeakReference<RegistryPool>>()

    fun register(pool: RegistryPool) {
        compactPools()
        if (pools.none { it.get() === pool }) {
            pools.add(WeakReference(pool))
            Log.i(TAG, "Registered pool (${pools.size} live)")
        }
    }

    fun unregister(pool: RegistryPool) {
        pools.removeAll { it.get() === pool || it.get() == null }
    }

    /** Drop weak refs whose pool has been collected. */
    private fun compactPools() {
        pools.removeAll { it.get() == null }
    }

    /** Live pools, strongly held for the duration of one call. */
    private fun livePools(): List<RegistryPool> {
        compactPools()
        return pools.mapNotNull { it.get() }
    }

    /** Total live tabs across every registered pool. */
    fun totalLiveTabs(): Int = livePools().sumOf { it.registrySnapshot().size }

    /**
     * Ask for room to create one more tab in [requester]. See the class note
     * for the four-step ladder. Returns false only when the cap is reached and
     * no OTHER pool has a tab to give up.
     *
     * Synchronous and main-thread-confined by contract — see **Concurrency**.
     */
    fun requestSlot(requester: RegistryPool): Boolean {
        val live = livePools()
        if (live.sumOf { it.registrySnapshot().size } < GLOBAL_TAB_CAP) return true

        val now = System.currentTimeMillis()
        var idleVictim: Victim? = null
        var busyVictim: Victim? = null

        // One pass collects both candidate classes, as iOS does, so a cap-hit
        // does not walk every pool twice.
        for (pool in live) {
            if (pool === requester) continue
            for (tab in pool.registrySnapshot()) {
                val idleFor = now - tab.lastActivityDate.time
                if (!tab.inUse) {
                    if (idleVictim == null || idleFor > idleVictim.idleForMs) {
                        idleVictim = Victim(pool, tab.id, idleFor)
                    }
                } else {
                    if (busyVictim == null || idleFor > busyVictim.idleForMs) {
                        busyVictim = Victim(pool, tab.id, idleFor)
                    }
                }
            }
        }

        idleVictim?.let { v ->
            Log.i(
                TAG,
                "Global cap ($GLOBAL_TAB_CAP) reached — evicting idle tab ${v.tabId} " +
                    "from a sibling pool (idle ${v.idleForMs / 1000}s)",
            )
            v.pool.evictTabForRegistry(v.tabId)
            return true
        }
        busyVictim?.let { v ->
            Log.i(
                TAG,
                "Global cap ($GLOBAL_TAB_CAP) reached with no idle siblings — preempting " +
                    "in-use tab ${v.tabId} (idle ${v.idleForMs / 1000}s)",
            )
            v.pool.preemptTabForRegistry(v.tabId)
            return true
        }
        Log.i(TAG, "Global cap ($GLOBAL_TAB_CAP) reached and no sibling tabs to reclaim — denying")
        return false
    }

    private data class Victim(val pool: RegistryPool, val tabId: Int, val idleForMs: Long)

    /**
     * Release every idle tab across all pools. Tabs an agent is actively
     * driving are left alone so an in-flight action does not fail halfway —
     * memory pressure is not worth corrupting a running task over, and those
     * tabs are the ones a subsequent [requestSlot] can still preempt if the
     * pressure turns out to be real.
     *
     * Called from `MinisApp.onTrimMemory`, i.e. already on the main thread.
     */
    fun handleMemoryPressure(): Int {
        val pools = livePools()
        val evicted = pools.sumOf { it.evictAllIdleForMemoryPressure() }
        if (evicted > 0) {
            Log.i(TAG, "Memory pressure — evicted $evicted idle tab(s) across ${pools.size} pool(s)")
        }
        return evicted
    }

    /** Test hook: drop all registrations. Not used in production code. */
    internal fun resetForTests() {
        pools.clear()
    }
}

// ─── Merged from GoogleAuthRouter.kt ──────────────────────────────────────────────────

/**
 * Routes navigation to Google login / OAuth domains out of our in-app
 * WebView and into a Chrome Custom Tab. Google permanently disallows
 * WebView-based sign-in (multi-signal detection: UA, X-Requested-With,
 * Sec-CH-UA, JS surface, TLS ClientHello), so any in-app WebView attempt
 * to reach these domains hits 403 disallowed_useragent. Custom Tabs run
 * in the user's real Chrome process — they share Chrome's cookie jar
 * and Google policy explicitly permits them.
 *
 * After the user finishes auth in the Custom Tab, cookies stay in
 * Chrome (not in our WebView). For the user's own browsing this is a
 * one-time flow; for `browser_use` agent calls the LLM should be told
 * the page can't be agentically traversed and to ask the user to copy
 * results back into chat — see ChatViewModel.buildSystemPrompt.
 */
object GoogleAuthRouter {
    private const val TAG = "GoogleAuthRouter"

    /**
     * Domains that, when navigated to from any in-app WebView, must be
     * handed off to Chrome Custom Tab. Subdomains match (suffix check).
     */
    private val GOOGLE_AUTH_HOSTS = listOf(
        "accounts.google.com",
        "signin.google.com",
        "myaccount.google.com",
        "oauth2.googleapis.com",
        "accounts.youtube.com",
    )

    /** Returns true if [url] should be opened externally (Custom Tab). */
    fun shouldRouteExternally(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return GOOGLE_AUTH_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * Launch [url] in a Chrome Custom Tab. Falls back to a plain
     * ACTION_VIEW Intent if Custom Tabs is unavailable on the device
     * (e.g. no Chrome / WebView-only ROM).
     */
    fun openInCustomTab(context: Context, url: String) {
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(false)
                .build()
                .launchUrl(context, Uri.parse(url))
            AppLogger.info(TAG, "opened in Custom Tab: $url")
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "Custom Tab launch failed, falling back to ACTION_VIEW: ${t.message}")
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }
}

// ─── Merged from HeadlessRenderHost.kt ──────────────────────────────────────────────────

/**
 * [T-android-browser-gpu-capture] Renders a headless (window-less) agent
 * WebView through the real GPU compositor so a screenshot contains what a
 * user would see — including WebGL, `<video>` and other GPU-only layers.
 *
 * Why this exists: the pool's WebViews are never attached to a window while
 * the browser sheet is closed, and the old capture drew them with
 * `WebView.draw(softwareCanvas)`. Chromium answers a software draw by
 * re-rasterising the page on the CPU, which has no access to GPU surfaces, so
 * a WebGL canvas came out as an empty box (reproduced on a Pixel 4a: DOM text
 * and a 2D canvas captured, the WebGL canvas beside them blank white, while
 * the same page on screen showed the rendered scene). A detached View has no
 * window surface, so reading screen pixels (PixelCopy) is not an option
 * either — there is nothing on screen to read.
 *
 * The fix gives the WebView a real surface only for the duration of a
 * capture: a PRIVATE virtual display (visible to nothing but this app)
 * backed by an [ImageReader], with a [Presentation] window on it. The WebView
 * is parented into that window, drawn by the normal hardware pipeline, one
 * composited frame is read back from the ImageReader, and the WebView is
 * detached again. This is how an offscreen browser is given a GPU on Android.
 *
 * Cost control, deliberately:
 *  - Nothing exists until the first capture, and the display, window and
 *    buffers are released [IDLE_RELEASE_MS] after the last one, so an idle
 *    app pays no rendering, memory or battery cost. The WebView is only in
 *    the window during a capture, so pages do not start animating in the
 *    background as a permanently attached view would.
 *  - Captures are serialised; the host is sized to the viewport being
 *    captured and never larger than [MAX_EDGE_PX] on either edge — a
 *    full-page capture (tens of thousands of px tall) stays on the software
 *    path rather than allocating a display that size.
 *  - Any failure returns null and the caller falls back to the software
 *    draw, so the worst case is the previous behaviour. Repeated failures to
 *    create the host trip [disabled] for the rest of the process, so a device
 *    that cannot do this does not pay the attempt on every screenshot.
 *  - The window is NOT_FOCUSABLE / NOT_TOUCHABLE: a focused input inside the
 *    page must never raise the IME on the phone's real screen.
 */
internal object HeadlessRenderHost {
    private const val TAG = "HeadlessRenderHost"

    const val IDLE_RELEASE_MS = 15_000L
    const val MAX_EDGE_PX = 4096
    private const val VISUAL_STATE_TIMEOUT_MS = 1_500L
    /**
     * Upper bound on waiting for the page to finish drawing. Only a page that
     * never stops changing (a running animation) waits this long; re-raster
     * of a detached page measured ~100 ms (Wikipedia, Pixel 4a), so this
     * leaves wide margin without making every animated page cost 1.5 s.
     */
    private const val SETTLE_TIMEOUT_MS = 700L
    /**
     * A frame counts as final once no newer one has arrived for this long.
     *
     * Why a quiet window and not a fixed frame count: Chromium drops a
     * page's rasterised tiles while the WebView is detached, so on re-attach
     * it presents what it has (white where tiles are missing) and keeps
     * presenting as raster completes. Two frames was enough for a tiny page
     * and gave a mostly-white Wikipedia article (measured on a Pixel 4a).
     * A finished static page stops producing frames, which is the signal.
     */
    private const val QUIET_MS = 150L
    /** At least this many frames after the content is ready. */
    private const val MIN_FRAMES = 2
    private const val MAX_HOST_FAILURES = 3

    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())

    // All state below is touched on the main thread only.
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var container: FrameLayout? = null
    private var hostW = 0
    private var hostH = 0
    private var latest: Image? = null
    private var frameCount = 0L
    private var lastFrameAt = 0L
    private var hostFailures = 0
    private var visualStateRequest = 0L

    /** Non-null once the GPU path has been given up on for this process. */
    @Volatile
    var disabled: String? = null
        private set

    private val releaseTask = Runnable { release("idle") }

    /**
     * Capture [webView] at [w]×[h] physical px through the GPU compositor, or
     * null when this path does not apply (the WebView is on screen in the
     * browser sheet, the size is out of range, the host could not be built)
     * or fails — callers then use the software draw.
     */
    suspend fun capture(webView: WebView, w: Int, h: Int): Bitmap? {
        if (disabled != null) return null
        return mutex.withLock { withContext(Dispatchers.Main) { captureOnMain(webView, w, h) } }
    }

    private suspend fun captureOnMain(webView: WebView, w: Int, h: Int): Bitmap? {
        // Shown in the browser sheet: it is not ours to move, and taking it
        // would blank the sheet for the length of the capture.
        if (webView.parent != null || webView.isAttachedToWindow) return null
        if (w <= 0 || h <= 0 || w > MAX_EDGE_PX || h > MAX_EDGE_PX) return null

        main.removeCallbacks(releaseTask)
        val t0 = SystemClock.elapsedRealtime()
        // Main-thread time actually spent blocking (the waits in between
        // suspend and let the UI run) — the number that matters for jank.
        var mainBlockMs = 0L
        fun <T> onMain(block: () -> T): T {
            val s = SystemClock.elapsedRealtime()
            try { return block() } finally { mainBlockMs += SystemClock.elapsedRealtime() - s }
        }
        var image: Image? = null
        var scrollbars: Pair<Boolean, Boolean>? = null
        try {
            val host = onMain { ensureHost(webView.context, w, h) } ?: return null
            val tHost = SystemClock.elapsedRealtime()
            onMain {
                // Scrollbars fade in on attach and would be baked into the
                // image; the software path never drew them.
                scrollbars = webView.isVerticalScrollBarEnabled to webView.isHorizontalScrollBarEnabled
                webView.isVerticalScrollBarEnabled = false
                webView.isHorizontalScrollBarEnabled = false
                host.addView(webView, FrameLayout.LayoutParams(w, h))
            }
            val ready = awaitVisualState(webView)
            val tReady = SystemClock.elapsedRealtime()
            val start = frameCount
            onMain { webView.invalidate() }
            val settled = withTimeoutOrNull(SETTLE_TIMEOUT_MS) {
                while (frameCount < start + MIN_FRAMES ||
                    SystemClock.elapsedRealtime() - lastFrameAt < QUIET_MS
                ) delay(8)
                true
            } == true
            // Timing out is normal for an animated page (it never goes
            // quiet); any frame of it is a true picture. Only NO frame fails.
            val gotFrames = frameCount >= start + 1
            val tFrame = SystemClock.elapsedRealtime()
            // The sheet can open mid-capture and re-parent the WebView
            // (BrowserWebView detaches it from whatever holds it); the frame
            // we have would then be of an empty window.
            if (webView.parent !== host) {
                Log.w(TAG, "WebView left the host during capture; falling back")
                return null
            }
            if (!gotFrames || latest == null) {
                Log.w(TAG, "No composited frame (ready=$ready frames=${frameCount - start}); falling back")
                return null
            }
            // Take ownership so the listener cannot close it under us, and
            // hand the WebView back before the copy: the copy is ~w*h*4
            // bytes and runs off the main thread.
            image = latest
            latest = null
            onMain { host.removeView(webView) }
            val owned = image!!
            val bitmap = withContext(Dispatchers.Default) { toBitmap(owned, w, h) }
            Log.i(
                TAG,
                "gpu capture ${w}x$h total=${SystemClock.elapsedRealtime() - t0}ms " +
                    "host=${tHost - t0}ms ready=${tReady - tHost}ms($ready) " +
                    "frame=${tFrame - tReady}ms(frames=${frameCount - start} settled=$settled) " +
                    "mainBlock=${mainBlockMs}ms",
            )
            return bitmap
        } catch (e: Exception) {
            Log.w(TAG, "gpu capture failed: ${e.javaClass.simpleName}: ${e.message}")
            return null
        } finally {
            image?.close()
            scrollbars?.let { (v, hz) ->
                webView.isVerticalScrollBarEnabled = v
                webView.isHorizontalScrollBarEnabled = hz
            }
            val host = container
            if (host != null && webView.parent === host) host.removeView(webView)
            main.postDelayed(releaseTask, IDLE_RELEASE_MS)
        }
    }

    /**
     * Resolves once the WebView's current content will be in the next frame
     * it draws — the documented way to know a (re)attached WebView is not
     * about to present a stale or empty frame. Bounded: a wedged renderer
     * must not wedge the screenshot.
     */
    private suspend fun awaitVisualState(webView: WebView): Boolean {
        val done = CompletableDeferred<Unit>()
        val id = ++visualStateRequest
        webView.postVisualStateCallback(
            id,
            object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (requestId == id) done.complete(Unit)
                }
            },
        )
        return withTimeoutOrNull(VISUAL_STATE_TIMEOUT_MS) { done.await() } != null
    }

    private fun ensureHost(context: Context, w: Int, h: Int): FrameLayout? {
        val existing = container
        if (existing != null && presentation != null && hostW == w && hostH == h) return existing
        release(if (existing == null) "create" else "resize")

        val app = context.applicationContext
        var r: ImageReader? = null
        var vd: VirtualDisplay? = null
        try {
            val dm = app.getSystemService(DisplayManager::class.java) ?: return fail("no DisplayManager")
            r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
            // Keep only the newest frame: an ImageReader whose images are
            // never acquired stops receiving once its queue is full.
            r.setOnImageAvailableListener({ rr ->
                val img = runCatching { rr.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                latest?.close()
                latest = img
                frameCount++
                lastFrameAt = SystemClock.elapsedRealtime()
            }, main)
            // No PUBLIC flag: a private display only this app can put
            // content on, needing no permission and invisible to the user.
            vd = dm.createVirtualDisplay(
                "minis-headless-browser", w, h, app.resources.displayMetrics.densityDpi,
                r.surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            ) ?: return fail("createVirtualDisplay returned null").also { r.close() }

            val p = Presentation(app, vd.display)
            p.window?.apply {
                addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                )
                setBackgroundDrawable(ColorDrawable(Color.WHITE))
            }
            val c = FrameLayout(p.context)
            p.setContentView(c)
            p.show()

            reader = r
            display = vd
            presentation = p
            container = c
            hostW = w
            hostH = h
            hostFailures = 0
            Log.i(TAG, "host created ${w}x$h dpi=${app.resources.displayMetrics.densityDpi}")
            return c
        } catch (e: Exception) {
            runCatching { vd?.release() }
            runCatching { r?.close() }
            return fail("host setup failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun fail(reason: String): FrameLayout? {
        hostFailures++
        Log.w(TAG, "$reason (failure $hostFailures/$MAX_HOST_FAILURES)")
        if (hostFailures >= MAX_HOST_FAILURES) {
            disabled = reason
            Log.w(TAG, "GPU capture disabled for this process: $reason")
        }
        return null
    }

    /** Tear everything down; the next capture rebuilds on demand. Main thread. */
    private fun release(why: String) {
        if (presentation == null && display == null && reader == null) return
        main.removeCallbacks(releaseTask)
        container?.removeAllViews()
        runCatching { presentation?.dismiss() }
        runCatching { display?.release() }
        latest?.close()
        latest = null
        runCatching { reader?.close() }
        presentation = null
        display = null
        reader = null
        container = null
        Log.i(TAG, "host released ($why) ${hostW}x$hostH")
        hostW = 0
        hostH = 0
    }

    /**
     * Copy an RGBA_8888 [image] into a [w]×[h] ARGB_8888 bitmap. The plane's
     * rows may be padded (rowStride > w*4), and the last row is not padded, so
     * the buffer is re-packed row by row rather than bulk-copied.
     */
    private fun toBitmap(image: Image, w: Int, h: Int): Bitmap {
        val plane = image.planes[0]
        val src = plane.buffer.duplicate().apply { rewind() }
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowBytes = w * pixelStride
        val packed = ByteBuffer.allocateDirect(rowBytes * h)
        val row = ByteArray(rowBytes)
        for (y in 0 until h) {
            src.position(y * rowStride)
            src.get(row, 0, rowBytes)
            packed.put(row)
        }
        packed.rewind()
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(packed) }
    }
}

// ─── Merged from InterceptedDialogQueue.kt ──────────────────────────────────────────────────

/**
 * [T-android-js-dialogs-256] Bounded record of JS dialogs the agent browser
 * answered with a default instead of showing. Android port of the
 * `pendingDialogEvents` / `recordInterceptedDialog` / `drainInterceptedDialogReport`
 * trio on iOS `BrowserUseManager` (BrowserUseManager.swift L67-90, L2627-2720).
 *
 * Split out of [BrowserUseManager] rather than inlined there because that class
 * requires a live `WebView` to construct, which a JVM unit test cannot provide —
 * the eviction and drain-once semantics are the parts worth pinning, and this
 * way they are testable without an instrumentation run.
 *
 * Thread-safe: WebChromeClient callbacks arrive on the UI thread while drains
 * happen from the action coroutine, so record/drain genuinely race. iOS gets
 * this for free via @MainActor; here it costs a lock.
 */
class InterceptedDialogQueue(
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
) {

    companion object {
        /**
         * Cap on retained records. A page can call alert() in a loop and this
         * queue only drains when a tool result is produced, so without a bound a
         * buggy (or adversarial) page could grow it without limit. Once full the
         * OLDEST are dropped and the drained report says how many, keeping the
         * most recent dialogs — the ones the agent is likely acting on.
         */
        const val DEFAULT_MAX_EVENTS = 20
    }

    /** One alert()/confirm()/prompt() answered with a default. */
    data class Event(
        val kind: String,
        val message: String,
        val defaultText: String?,
        val pageURL: String?,
        /** The value handed back to the page, rendered for the model. */
        val defaultResponse: String,
    )

    private val events = mutableListOf<Event>()
    private var dropped = 0
    private val lock = Any()

    /** Append, evicting the oldest once [maxEvents] is reached. */
    fun record(
        kind: String,
        message: String,
        defaultText: String?,
        pageURL: String?,
        defaultResponse: String,
    ) {
        synchronized(lock) {
            if (events.size >= maxEvents) {
                events.removeAt(0)
                dropped++
            }
            events.add(Event(kind, message, defaultText, pageURL, defaultResponse))
        }
    }

    /** Test/diagnostic view of the current depth. */
    val size: Int get() = synchronized(lock) { events.size }

    /**
     * Drain into a block of text for the model, or null when empty. Draining
     * clears the queue, so each dialog is reported exactly once.
     *
     * Known limitation (same as iOS): this only runs when a tool result is
     * produced for the tab. If the agent triggers a dialog and then makes no
     * further browser call, the records die with the tab — deliberately not
     * defended against, since the memory is bounded and every interception is
     * already logged at record time.
     */
    fun drainReport(): String? {
        val drainedEvents: List<Event>
        val drainedDropped: Int
        synchronized(lock) {
            if (events.isEmpty()) return null
            drainedEvents = events.toList()
            drainedDropped = dropped
            events.clear()
            dropped = 0
        }

        val lines = drainedEvents.map { e ->
            buildString {
                append("- ${e.kind}(): \"${e.message}\"")
                if (!e.defaultText.isNullOrEmpty()) append(" [default text: \"${e.defaultText}\"]")
                if (e.pageURL != null) append(" on ${e.pageURL}")
                append(" — page received `${e.defaultResponse}`")
            }
        }.toMutableList()
        if (drainedDropped > 0) {
            lines.add(
                "- ($drainedDropped earlier dialog${if (drainedDropped == 1) "" else "s"} " +
                    "dropped; only the most recent $maxEvents are kept)"
            )
        }

        val plural = if (drainedEvents.size == 1) "a dialog" else "${drainedEvents.size} dialogs"
        return "[Browser Dialog Intercepted] The page tried to show $plural, which was " +
            "NOT shown to the user — this is headless automation and cannot wait for " +
            "human input. The page was given the default response shown below and " +
            "continued running.\n" +
            lines.joinToString("\n") + "\n" +
            "If you need a different outcome, use execute_js to drive the page's " +
            "DOM/JS state directly instead of relying on native dialogs (for example " +
            "override window.confirm before triggering the action).\n\n" +
            "[Original tool result below]\n\n"
    }
}

// ─── Merged from SiteCookieCleaner.kt ──────────────────────────────────────────────────

/**
 * [ISS-040] Delete one site's cookies from the WebView cookie store.
 *
 * `CookieManager` has no "remove cookies for a domain" call, and the settings
 * sheet used to call `setCookie(domain, "")` — a bare host where a URL is
 * required, and an empty cookie — which WebView ignores, so the delete icon
 * did nothing. The only way to remove a single cookie is to overwrite it with
 * an already-expired one that has the same name, Domain and Path.
 *
 * `getCookie(url)` returns names and values only, not each cookie's Domain or
 * Path, so every plausible combination is written:
 *  - host-only (no Domain attribute) and Domain= the host and each parent
 *    domain (a cookie set by `www.example.com` for `.example.com` is visible
 *    from www and needs `Domain=example.com` to match);
 *  - with and without `Secure`, through https (an insecure write cannot
 *    replace a Secure cookie, and `__Host-` / `__Secure-` names require it);
 *  - Path=/ only. The root URL only reports cookies whose Path is "/", so a
 *    cookie scoped to a deeper path is neither visible nor removable here —
 *    the read-back below then reports it instead of claiming success.
 *
 * Writes that match nothing (or that WebView rejects, such as a Domain that is
 * a public suffix) are no-ops. Afterwards the cookies are read back; anything
 * still present is returned so the caller never fails silently.
 *
 * Blocking: call off the main thread.
 */
object SiteCookieCleaner {

    private const val TAG = "SiteCookieCleaner"
    private const val EXPIRED = "Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0"

    data class Result(val host: String, val found: Set<String>, val remaining: Set<String>) {
        val cleared: Boolean get() = remaining.isEmpty()
    }

    /** Cookie names in a `getCookie` header (`"a=1; b=2; flag"`). Pure. */
    internal fun cookieNames(header: String?): Set<String> {
        if (header.isNullOrBlank()) return emptySet()
        return header.split(';')
            .map { it.trim().substringBefore('=').trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * The hosts a delete for [domain] covers: the host itself, plus the bare
     * host for a `www.` entry (the history records the host the page loaded
     * from, but the site's login cookies often sit on the bare domain). Pure.
     */
    internal fun hostsFor(domain: String): List<String> {
        val host = domain.trim().trimEnd('.').lowercase()
        if (host.isEmpty()) return emptyList()
        val bare = host.removePrefix("www.")
        return if (bare != host && bare.contains('.')) listOf(host, bare) else listOf(host)
    }

    /** `Domain=` values that can cover [host]: itself and every parent with at least two labels. Pure. */
    internal fun domainAttributes(host: String): List<String> {
        val labels = host.split('.')
        if (labels.size < 2 || host.all { it.isDigit() || it == '.' }) return listOf(host)
        return (0..labels.size - 2).map { labels.drop(it).joinToString(".") }
    }

    /** Every expired `Set-Cookie` value written for [name] on [host]. Pure. */
    internal fun expiryCookies(name: String, host: String): List<String> {
        val scopes = listOf("") + domainAttributes(host).map { "; Domain=$it" }
        return scopes.flatMap { scope ->
            listOf(
                "$name=; $EXPIRED; Path=/$scope",
                "$name=; $EXPIRED; Path=/$scope; Secure",
            )
        }
    }

    private fun namesAt(cm: CookieManager, host: String): Set<String> =
        cookieNames(cm.getCookie("https://$host")) + cookieNames(cm.getCookie("http://$host"))

    /** Names of the cookies the root of [domain] (and its bare host) currently sends. */
    fun cookieNamesFor(domain: String, cm: CookieManager = CookieManager.getInstance()): Set<String> =
        hostsFor(domain).flatMap { namesAt(cm, it) }.toSet()

    fun hasCookiesFor(domain: String, cm: CookieManager = CookieManager.getInstance()): Boolean =
        cookieNamesFor(domain, cm).isNotEmpty()

    /**
     * The settings sheet's cookie section: [historyDomains] (the browsing
     * history, still the source of the list) narrowed to the sites that hold
     * cookies right now, so a delete or Clear All visibly removes rows. Pure
     * given [probe].
     */
    internal fun sitesWithCookies(historyDomains: List<String>, probe: (String) -> Boolean): List<String> =
        historyDomains.filter(probe)

    /** What the cookie section shows; read off the main thread. */
    data class SectionState(val hasCookies: Boolean, val domains: List<String>)

    fun loadSection(historyDomains: List<String>, cm: CookieManager = CookieManager.getInstance()): SectionState {
        val hasAny = cm.hasCookies()
        // Nothing stored at all: skip the per-site probes.
        val domains = if (hasAny) sitesWithCookies(historyDomains) { hasCookiesFor(it, cm) } else emptyList()
        return SectionState(hasAny, domains)
    }

    fun clear(domain: String, cm: CookieManager = CookieManager.getInstance()): Result {
        val hosts = hostsFor(domain)
        val found = linkedSetOf<String>()
        for (host in hosts) {
            val names = namesAt(cm, host)
            found += names
            for (name in names) {
                for (value in expiryCookies(name, host)) {
                    cm.setCookie("https://$host", value)
                    cm.setCookie("http://$host", value)
                }
            }
        }
        cm.flush()
        val remaining = cookieNamesFor(domain, cm)
        AppLogger.info(TAG, "clear $domain hosts=$hosts found=${found.size} remaining=${remaining.size} $remaining")
        return Result(domain, found, remaining)
    }
}

// ─── Merged from BrowserHistory.kt ──────────────────────────────────────────────────

/**
 * Tracks browser history with 7-day retention, mirroring iOS BrowserHistoryStore.
 */
class BrowserHistoryStore private constructor(private val context: Context) {

    companion object {
        private const val TAG = "BrowserHistory"
        private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L // 7 days
        private const val FILENAME = "browser_history.json"

        @Volatile
        private var instance: BrowserHistoryStore? = null

        fun getInstance(context: Context): BrowserHistoryStore {
            return instance ?: synchronized(this) {
                instance ?: BrowserHistoryStore(context.applicationContext).also { instance = it }
            }
        }
    }

    data class Entry(
        val id: String = UUID.randomUUID().toString(),
        val url: String,
        val title: String,
        val timestamp: Long = System.currentTimeMillis(),
        val domain: String = extractDomain(url),
    )

    private val entries = mutableListOf<Entry>()

    init {
        load()
    }

    fun record(url: String, title: String) {
        if (url.isEmpty()) return
        // Deduplicate consecutive visits to same URL
        if (entries.lastOrNull()?.url == url) return

        entries.add(Entry(url = url, title = title))
        pruneOld()
        save()
    }

    fun getEntries(): List<Entry> = entries.sortedByDescending { it.timestamp }

    fun search(query: String): List<Entry> {
        if (query.isBlank()) return getEntries()
        val q = query.lowercase()
        return entries.filter {
            it.title.lowercase().contains(q) || it.url.lowercase().contains(q)
        }.sortedByDescending { it.timestamp }
    }

    fun groupedByDay(): Map<String, List<Entry>> {
        val cal = java.util.Calendar.getInstance()
        val today = java.util.Calendar.getInstance()
        val yesterday = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }

        return getEntries().groupBy { entry ->
            cal.timeInMillis = entry.timestamp
            when {
                cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
                    cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR) -> "Today"
                cal.get(java.util.Calendar.YEAR) == yesterday.get(java.util.Calendar.YEAR) &&
                    cal.get(java.util.Calendar.DAY_OF_YEAR) == yesterday.get(java.util.Calendar.DAY_OF_YEAR) -> "Yesterday"
                else -> {
                    val month = cal.get(java.util.Calendar.MONTH) + 1
                    val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
                    "$month/${day}"
                }
            }
        }
    }

    /** Get unique domains from history (for cookie domain listing). */
    fun uniqueDomains(): List<String> {
        return entries.map { it.domain }.filter { it.isNotEmpty() }.distinct().sorted()
    }

    fun clear() {
        entries.clear()
        save()
    }

    private fun pruneOld() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        entries.removeAll { it.timestamp < cutoff }
    }

    private fun save() {
        try {
            val file = File(context.filesDir, FILENAME)
            val array = JSONArray()
            for (entry in entries) {
                val obj = JSONObject()
                obj.put("id", entry.id)
                obj.put("url", entry.url)
                obj.put("title", entry.title)
                obj.put("timestamp", entry.timestamp)
                obj.put("domain", entry.domain)
                array.put(obj)
            }
            file.writeText(array.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save history: ${e.message}")
        }
    }

    private fun load() {
        try {
            val file = File(context.filesDir, FILENAME)
            if (!file.exists()) return
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                entries.add(Entry(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    url = obj.optString("url", ""),
                    title = obj.optString("title", ""),
                    timestamp = obj.optLong("timestamp", 0),
                    domain = obj.optString("domain", ""),
                ))
            }
            pruneOld()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load history: ${e.message}")
        }
    }
}

private fun extractDomain(url: String): String {
    return try {
        URI(url).host ?: ""
    } catch (_: Exception) {
        ""
    }
}
