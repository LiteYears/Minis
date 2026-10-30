package com.openminis.app.ui.chat

import android.content.Context
import android.util.Log
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Process-level cache of ChatViewModels keyed by sessionId. Mirrors iOS
 * `ViewModelCache` — a session's agent loop keeps running even if the user
 * leaves the chat screen, and the row in the sessions list shows a spinning
 * indicator while streaming is in flight.
 *
 * Without this, scoping the ChatViewModel to a NavBackStackEntry means
 * `popBackStack()` would cancel `viewModelScope` and kill the streaming job.
 */
object ChatViewModelStore {

    private const val TAG = "ChatVMStore"

    /**
     * One ViewModelStore per canonical sessionId. Each store contains at most
     * one ChatViewModel (the one created by our factory). When we want to drop
     * a session's VM, we call `clear()` on its store which triggers
     * `onCleared`.
     */
    private val stores = LinkedHashMap<String, ViewModelStore>()

    /**
     * [T-android-vm-store-leak] How many chat sessions keep a live
     * ChatViewModel at once.
     *
     * This cache had no bound and only one eviction path — [release], called
     * solely from SessionDeleter, i.e. when the user DELETES a session. Merely
     * navigating away never dropped anything, so every session opened in a
     * process lifetime stayed resident with its agent history and rendered
     * rows. A device log over ~3 hours showed exactly that: ten allocations,
     * ZERO releases, and a session last used at 00:5x still emitting frames at
     * 03:0x. Heap went 29MB (5%) -> 436MB (85%) of a 512MB cap and RSS 386MB ->
     * 3.3GB, after which nearly every allocation hit a blocking GC — ~230 GCs a
     * minute, 1.4s stalls, 149-frame drops, and the phone running hot.
     *
     * 4 keeps the warm-resume behaviour this cache exists for (the previous
     * chat, and the couple before it, still resume instantly) while bounding
     * the worst case. Anything actively streaming is never evicted regardless
     * of this number, so raising or lowering it cannot break a running agent.
     *
     * [T-android-vm-store-dual-pool] This bound now lives in
     * [MAX_CACHED_NORMAL] and [MAX_CACHED_CHILD], one per pool.
     */

    /**
     * [T-android-vm-store-dual-pool] Which cache pool a session belongs to.
     *
     * Sub agent sessions and the user's own chats compete for very different
     * reasons, and sharing one LRU made them evict each other. Measured on a
     * Pixel 4a with the sub agent cap (then a global 3) saturated: three children
     * allocated inside 55ms, and the third evicted the FIRST one — a child that
     * had existed for 55 milliseconds — while also pushing the user's own
     * sessions out of the cache:
     *
     *     allocate child 709994c9 (total=5)
     *     evict d655a80b (lru, remaining=4)     <- a normal session
     *     allocate child 1663bdae (total=5)
     *     evict e0358fc1 (lru, remaining=4)     <- another normal session
     *     allocate child 44a70eb6 (total=5)
     *     evict 709994c9 (lru, remaining=4)     <- the child from 55ms ago
     *
     * Separate pools with separate caps remove the interference in both
     * directions. Mirrors iOS `ViewModelCache` (ChatLifecycleSupport.swift).
     */
    enum class PoolKind { NORMAL, CHILD }

    /**
     * Pool membership, STICKY: a session's pool is decided when its store is
     * first created and never changes afterwards.
     *
     * Stickiness is what makes the classification trustworthy, because on
     * Android the same entry point serves two very different callers.
     * `HeadlessChatRunner.viewModelFor` both CREATES a child's view model
     * (ChatViewModel.createChildViewModel) and re-acquires it for READ-ONLY
     * display (AgentTranscriptScreen). Without stickiness, opening a finished
     * sub agent's transcript would re-register it, and any later change to how
     * that screen asks for the VM could silently reclassify a live child as a
     * normal session — putting it back in the pool this change exists to keep
     * it out of.
     *
     * [T-android-vm-store-child-tag-survives-evict] Entries are removed on
     * [release], but a CHILD tag deliberately OUTLIVES eviction (see
     * [dropStoreKeepingChildTag]). Stickiness used to last only as long as the
     * store: LRU trim / memory pressure removed the tag with it, and every
     * read-only re-acquire of a finished sub agent (AgentTranscriptScreen, the
     * delegate sheet's image poll, the Stop glyph) then re-created it in the
     * NORMAL pool and evicted one of the user's own warm chats. Only CHILD
     * tags are retained (NORMAL is the default anyway), so the map is bounded
     * by the number of sub agents run in this process — a few bytes each.
     */
    private val poolKinds = mutableMapOf<String, PoolKind>()

    /**
     * Cap for the normal pool.
     *
     * [T-new-limits] Was 4 — already below the 5 session slots it sat next to.
     * Now 8: the 6 agent loops SessionConcurrencyManager admits
     * (MAX_CONCURRENT) plus the chat on screen and the one the user just left.
     * Running sessions are never evicted, so with 4 every idle chat — the one
     * the user was reading included — was thrown out the moment a fifth loop
     * started, and switching between two chats while six agents ran reloaded
     * each from the database every time.
     *
     * Not higher: an idle cached view model keeps its whole transcript and
     * agent history in the Java heap. A text chat is a few MB, but a chat with
     * image history holds the encoded images too (75.8 MB for 100 images in
     * the Pixel 4a load test, Java peak 221–327 MB of a 512 MB heap), and only
     * the idle entries above the running ones are what this number buys.
     * handleMemoryPressure still releases every idle entry on onTrimMemory.
     */
    internal const val MAX_CACHED_NORMAL = 8

    /**
     * Cap for the child pool.
     *
     * Sized from Android's own numbers rather than copied from iOS (which uses
     * 10): when this was set the sub agent cap was a global 3, so 6 held two
     * full generations of concurrent jobs plus their read-back, at the ~1MB per
     * child session measured previously ~6MB. Larger would mostly cache
     * transcripts nobody reopens.
     *
     * [T-new-limits] The cap is now 3 PER PARENT conversation
     * (AgentJobRegistry.MAX_CONCURRENT_CHILD_JOBS_PER_PARENT), so up to 6
     * parents × 3 = 18 children can be live at once. Left at 6 on purpose: it
     * is a SOFT cap — a child whose job is still active is never evicted
     * (ChatViewModel.hasWorkInFlight counts its own PENDING/RUNNING job, which
     * also covers the start-up gap before its first send), and neither is a
     * transcript the user is reading ([holdWhileViewed]). So live children all
     * stay and the pool only runs over until they finish; the bound limits
     * cached FINISHED transcripts, which are cheap to reload from the database.
     */
    internal const val MAX_CACHED_CHILD = 6

    /**
     * Draft → canonical mapping. When a draft ("__new__...") session is
     * persisted, we add `draftKey -> realId` here so lookups via the old key
     * (from a ChatScreen whose `sessionId` parameter is still the draft)
     * continue to hit the same live store.
     */
    private val aliases = mutableMapOf<String, String>()

    /**
     * [T-android-split-draft-highlight] Bumped every time [aliases] changes.
     *
     * [aliases] is a plain map, so writing it schedules no recomposition — and
     * the two-pane list resolves its highlight THROUGH that map. When a draft
     * was promoted on first send the pane key deliberately stayed
     * `__new__<uuid>` (see [rename]: aliasing rather than re-keying is what
     * keeps the streaming ViewModel alive), so the highlight went on resolving
     * to the draft id, matched no persisted row, and the running session sat
     * unhighlighted in the list even after it had a title and a group.
     *
     * Exposing the generation as observable state gives Compose something to
     * subscribe to: readers key on it, the write invalidates them, and the
     * lookup re-runs against the now-populated alias.
     */
    private val aliasGeneration = androidx.compose.runtime.mutableIntStateOf(0)

    private fun resolveKey(sessionId: String): String =
        aliases[sessionId] ?: sessionId

    /**
     * [T-android-tablet-split] Public view of [resolveKey]: the PERSISTED id a
     * (possibly draft) session id now stands for.
     *
     * The two-pane list highlights whichever session the detail pane is
     * showing. When the detail holds a draft, the pane's key stays
     * `__new__<uuid>` even after the first send promotes it — [rename] aliases
     * the key rather than renaming it, precisely so the running screen and its
     * ViewModel are not disturbed mid-stream. Without this lookup the list
     * would go on highlighting nothing after the draft became a real row,
     * because no persisted row ever matches a `__new__` id.
     *
     * Returns the input unchanged when there is no alias, so a plain persisted
     * id and an un-promoted draft both behave sensibly.
     */
    @Synchronized
    fun resolvePersistedId(sessionId: String): String = resolveKey(sessionId)

    /**
     * [T-sub-agents-v1] The already-created ChatViewModel for [sessionId], or
     * null — never constructs one.
     *
     * For callers off the main thread that only want to observe a session that
     * is already live. `ownerFor` + ViewModelProvider would CREATE one, and
     * doing that off the main thread can produce a second instance for the same
     * session under a race.
     */
    @Synchronized
    fun existing(sessionId: String): ChatViewModel? {
        // Object monitor, not a block lock on the map: every writer (ownerFor,
        // trim, release, rename) holds the object monitor, and ownerFor
        // re-inserts on EVERY call, so a reader on a different lock could walk
        // the LinkedHashMap mid-mutation.
        val store = stores[resolveKey(sessionId)] ?: return null
        return viewModelIn(store)
    }

    /**
     * The ChatViewModel already living in [store], or null — never constructs
     * one. ViewModelStore has no public getter; ask a provider with a factory
     * that refuses to build, so a miss returns null instead of a new VM.
     */
    private fun viewModelIn(store: ViewModelStore): ChatViewModel? = runCatching {
        androidx.lifecycle.ViewModelProvider(
            store,
            object : androidx.lifecycle.ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    throw IllegalStateException("no existing view model")
            },
        )[ChatViewModel::class.java]
    }.getOrNull()

    /**
     * [T-android-vm-evict-orphan] Whether [store] is still the live store for
     * [sessionId].
     *
     * `HeadlessChatRunner` caches a `ViewModelProvider` per session, bound to
     * the store this object handed out. Before the cache had a bound, that
     * store was only ever cleared on session delete, which also told the
     * runner to forget it. LRU trim and memory-pressure release clear stores
     * without any such notice, so the runner's provider went on pointing at a
     * cleared store: its next lookup built a NEW ChatViewModel inside a store
     * this cache no longer tracks — never evicted, invisible to [existing],
     * and a second instance next to the one `ChatScreen` gets from [ownerFor].
     * That is the split-state bug the shared store was introduced to fix. The
     * runner now asks this before trusting its cache.
     */
    @Synchronized
    fun isLiveStore(sessionId: String, store: ViewModelStore): Boolean =
        stores[resolveKey(sessionId)] === store

    /**
     * [T-android-vm-evict-busy] Every cached session that eviction must leave
     * alone because clearing it would destroy work in flight.
     *
     * Two sources, unioned:
     *  - `SessionActivityTracker.activeSessions` — the original signal, set by
     *    the stream job once it holds a concurrency slot.
     *  - the view model's own [ChatViewModel.hasWorkInFlight] — covers the gap
     *    before the slot is acquired (the send is queued behind other
     *    sessions, `viewModelScope` is alive, the tracker says idle) and a
     *    parent whose background sub agents are still running or queued.
     */
    private fun busyKeys(): Set<String> {
        val tracked = runCatching {
            com.openminis.app.service.SessionActivityTracker.activeSessions.value
                .map { resolveKey(it) }
        }.getOrDefault(emptyList())
        val inFlight = stores.entries
            .filter { (_, store) -> runCatching { viewModelIn(store)?.hasWorkInFlight() == true }.getOrDefault(false) }
            .map { it.key }
        return (tracked + inFlight + pendingSends.keys + viewers.keys).toSet()
    }

    /**
     * [T-new-limits] Sessions a screen other than ChatScreen is showing, keyed
     * by store key, refcounted. ChatScreen claims [setActiveSession]; the sub
     * agent transcript screen does not (it must not become the "current
     * session" config reads target), so without this a finished child the
     * user was reading could be evicted by any other child starting — with
     * 6 parents × 3 children that is a routine event, not an edge case.
     */
    private val viewers = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Keep [sessionId]'s store from eviction until the returned call runs. */
    fun holdWhileViewed(sessionId: String): () -> Unit {
        val key = resolveKey(sessionId)
        viewers.merge(key, 1, Int::plus)
        var released = false
        return {
            if (!released) {
                released = true
                viewers.computeIfPresent(key) { _, n -> if (n <= 1) null else n - 1 }
            }
        }
    }

    /**
     * [T-android-vm-evict-pending-send] Sessions a caller has taken a view
     * model for and is about to send into, keyed by store key, refcounted.
     *
     * Between creating a view model and its first send there is a gap where it
     * looks idle to every other busy signal: HeadlessChatRunner waits there
     * for the model binding to resolve, and the view model is not streaming
     * yet. Starting 8 sessions at once through that path let each new one
     * evict the previous (the NORMAL pool holds 4); the evicted view models'
     * load was cancelled, no model ever resolved, the prompt was never sent,
     * and the list kept 4 empty "New Chat" rows.
     */
    private val pendingSends = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Keep [sessionId]'s store from eviction while [block] prepares a send. */
    suspend fun <T> holdingForSend(sessionId: String, block: suspend () -> T): T {
        val key = resolveKey(sessionId)
        pendingSends.merge(key, 1, Int::plus)
        try {
            return block()
        } finally {
            pendingSends.computeIfPresent(key) { _, n -> if (n <= 1) null else n - 1 }
        }
    }

    /**
     * [T-android-split-draft-highlight] Compose-aware [resolvePersistedId].
     *
     * Reading [aliasGeneration] inside a composition subscribes the caller to
     * alias changes, so a draft that gets promoted mid-stream re-resolves to
     * its real id and the list highlight follows it. Call this from
     * composables; [resolvePersistedId] remains for non-Compose callers.
     */
    @androidx.compose.runtime.Composable
    fun rememberPersistedId(sessionId: String): String {
        val generation = aliasGeneration.intValue
        return androidx.compose.runtime.remember(sessionId, generation) {
            resolveKey(sessionId)
        }
    }

    /**
     * [T-android-vm-store-dual-pool] [kind] classifies a session on FIRST
     * creation only; see [poolKinds] for why that is sticky.
     *
     * Defaulting to NORMAL keeps every existing call site correct without
     * changes: `ChatScreen` (the user's own chat) and the two debug RPC
     * entry points all open normal sessions. Only
     * `HeadlessChatRunner.viewModelFor` passes CHILD, and it is the single
     * path a sub agent's view model is ever built through.
     */
    @Synchronized
    @JvmOverloads
    fun ownerFor(sessionId: String, kind: PoolKind = PoolKind.NORMAL): ViewModelStoreOwner {
        val key = resolveKey(sessionId)
        val existing = stores.remove(key)
        val store = if (existing != null) {
            // Re-insert so LinkedHashMap's iteration order is least-recently-
            // USED, not merely insertion order: a long-lived session the user
            // keeps returning to must not be evicted ahead of one they opened
            // once and abandoned.
            existing
        } else {
            // First sight of this session: this is the only moment its pool is
            // decided. putIfAbsent rather than put, so a later call with a
            // different `kind` cannot reclassify a live session.
            poolKinds.putIfAbsent(key, kind)
            Log.d(
                TAG,
                "allocate store for $key kind=${poolKinds[key]} " +
                    "(total=${stores.size + 1}, pool=${poolSize(poolKinds[key]) + 1})",
            )
            ViewModelStore()
        }
        stores[key] = store
        trimToCapacity(poolKinds[key] ?: PoolKind.NORMAL)
        return object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
    }

    /**
     * [T-android-vm-store-leak] Evict least-recently-used sessions past
     * [MAX_CACHED_NORMAL] / [MAX_CACHED_CHILD].
     *
     * Two categories are never evicted, and both matter:
     *
     *  - anything in `SessionActivityTracker.activeSessions` — a session whose
     *    agent loop is still running. Clearing its store cancels
     *    `viewModelScope` and kills the stream mid-flight, which is the very
     *    thing this cache was built to prevent. This is why the cap is a
     *    soft one: if the user genuinely has five agents in flight, all five
     *    stay, and the cache simply runs over until they finish.
     *  - the session currently on screen, which would otherwise be torn down
     *    underneath the composition that is reading it.
     */
    /** Number of cached stores currently belonging to [kind]. */
    private fun poolSize(kind: PoolKind?): Int {
        if (kind == null) return 0
        return stores.keys.count { (poolKinds[it] ?: PoolKind.NORMAL) == kind }
    }

    /**
     * [T-android-vm-store-dual-pool] Trim ONE pool, independently of the other.
     *
     * Only the pool that just grew is considered, and only its own members are
     * eligible for eviction, so a burst of sub agents can no longer push the
     * user's chats out of the cache (or each other — a running child is never
     * evicted, so even 6 parents × 3 children over MAX_CACHED_CHILD = 6 only
     * makes the pool run over until they finish).
     *
     * Both carve-outs from the single-pool version still apply, in both pools,
     * for unchanged reasons: a session whose agent loop is running would have
     * its `viewModelScope` cancelled and its stream killed, and the on-screen
     * session would be torn down underneath the composition reading it. This is
     * still therefore a SOFT cap — if every member of a pool is busy, the pool
     * runs over rather than breaking a live run.
     */
    private fun trimToCapacity(kind: PoolKind) {
        val cap = when (kind) {
            PoolKind.NORMAL -> MAX_CACHED_NORMAL
            PoolKind.CHILD -> MAX_CACHED_CHILD
        }
        val inPool = stores.keys.filter { (poolKinds[it] ?: PoolKind.NORMAL) == kind }
        if (inPool.size <= cap) return
        val busy = busyKeys()
        val onScreen = activeSessionIdInternal?.let { resolveKey(it) }
        // [T-android-vm-evict-busy] The store this call just inserted is the
        // LAST key, and its ChatViewModel does not exist yet — `ownerFor`
        // returns the owner and the caller builds the VM afterwards. So it
        // looks idle to every busy signal there is, and being the oldest
        // untouched entry it was the first thing evicted: measured on a
        // Pixel 4a, a 6th session opened while five slots were held allocated
        // its store and cleared it 2ms later, killing the send. Never evict
        // the key this insertion is for; the next call trims it normally once
        // it has a view model to answer for itself.
        val justInserted = inPool.lastOrNull()
        // Oldest first; LinkedHashMap keeps that order for us.
        val evictable = inPool.filter { it != onScreen && it != justInserted && it !in busy }
        var overflow = inPool.size - cap
        for (key in evictable) {
            if (overflow <= 0) break
            dropStoreKeepingChildTag(key)
            overflow--
            Log.d(
                TAG,
                "evict store for $key kind=$kind (lru, remaining=${stores.size}, " +
                    "pool=${poolSize(kind)})",
            )
        }
    }

    /**
     * [T-android-trimmemory-vmstore] Release every cached session the system
     * can safely take back, in response to real memory pressure.
     *
     * [trimToCapacity] is a SOFT cap keyed on count: it only ever evicts down
     * to each pool's cap, and only when a new session is added. Neither
     * condition holds when the pressure comes from outside this cache — the
     * user can sit on four legitimately-cached heavy sessions while the device
     * runs out of memory, and nothing here would give anything back. This is
     * the last line of defence for that case, driven by `onTrimMemory`.
     *
     * The two exemptions from [trimToCapacity] are preserved exactly, and for
     * the same reasons — releasing either would be worse than the memory it
     * reclaims:
     *   - a session whose agent loop is running ([SessionActivityTracker]):
     *     clearing it cancels `viewModelScope` and kills the stream mid-flight;
     *   - the session currently on screen: it would be torn down underneath
     *     the composition reading it.
     *
     * Returns the number of stores actually released, so the caller can log
     * whether the signal did anything.
     */
    @Synchronized
    fun handleMemoryPressure(): Int {
        if (stores.isEmpty()) return 0
        val busy = busyKeys()
        val onScreen = activeSessionIdInternal?.let { resolveKey(it) }
        val evictable = stores.keys.filter { it != onScreen && it !in busy }
        for (key in evictable) {
            dropStoreKeepingChildTag(key)
        }
        if (evictable.isNotEmpty()) {
            Log.d(
                TAG,
                "memory pressure: released ${evictable.size} store(s), " +
                    "remaining=${stores.size} (kept onScreen=${onScreen != null} busy=${busy.size})",
            )
        }
        return evictable.size
    }

    /**
     * [T-android-vm-store-child-tag-survives-evict] Evict [key]'s store (LRU
     * trim or memory pressure) without forgetting that it is a sub agent.
     * A NORMAL tag is dropped because NORMAL is the default; a CHILD tag is
     * kept so that re-opening the evicted child — through any entry point,
     * including ones that pass the NORMAL default — lands it back in the
     * CHILD pool instead of evicting a user chat. [release] is still the one
     * place a tag is forgotten for good.
     */
    private fun dropStoreKeepingChildTag(key: String) {
        stores.remove(key)?.clear()
        if (poolKinds[key] != PoolKind.CHILD) poolKinds.remove(key)
        // [OpenMinis#433] A parked draft whose view model is gone has nothing
        // left to hand back.
        forgetDraft(key)
    }

    /**
     * Drop the cached VM for this session (cancels `viewModelScope`, triggers
     * `ChatViewModel.onCleared`). Call when the session is deleted. Also
     * clears any draft alias pointing at this canonical id.
     */
    @Synchronized
    fun release(sessionId: String) {
        val key = resolveKey(sessionId)
        aliases.entries.removeAll { it.value == key }
        aliasGeneration.intValue++
        // [T-android-vm-store-child-tag-survives-evict] Forget the tag even
        // when the store was already evicted: a CHILD tag outlives eviction.
        poolKinds.remove(key)
        forgetDraft(key)
        stores.remove(key)?.let {
            it.clear()
            Log.d(TAG, "release store for $key (remaining=${stores.size})")
        }
    }

    /**
     * Mark `fromSessionId` (a draft key) as an alias for `toSessionId` (the
     * real, persisted id). The live store stays under the real id; future
     * `ownerFor(draftKey)` lookups resolve to the same store so a ChatScreen
     * still rendering with the draft route continues to see the running VM.
     */
    /**
     * T311: id of the chat the user has on screen right now. Set by
     * `ChatScreen`'s lifecycle hook on enter, cleared on dispose.
     * `minis-config session.*` reads this so reads/writes target the
     * "current session" the same way iOS `AIChatViewModel.activeSessionId`
     * does. `null` = no chat is foregrounded → reads return empty / writes
     * throw `No active session`. Resolves through `aliases` so a draft id
     * still maps to the persisted row.
     */
    @Volatile
    private var activeSessionIdInternal: String? = null

    val activeSessionId: String?
        get() = activeSessionIdInternal?.let { resolveKey(it) }

    @Synchronized
    fun setActiveSession(sessionId: String?) {
        activeSessionIdInternal = sessionId
    }

    /**
     * [T-android-active-session-cas] Clear the on-screen id only if it still
     * belongs to [sessionId]. A screen being disposed must not wipe the id a
     * newer screen already claimed (CHAT -> CHAT navigation runs the incoming
     * screen's enter before the outgoing screen's dispose). Compared through
     * the alias map so a promoted draft still recognises its own claim.
     */
    @Synchronized
    fun clearActiveSession(sessionId: String) {
        val current = activeSessionIdInternal ?: return
        if (resolveKey(current) != resolveKey(sessionId)) return
        activeSessionIdInternal = null
    }

    @Synchronized
    fun rename(fromSessionId: String, toSessionId: String) {
        if (fromSessionId == toSessionId) return
        val store = stores.remove(fromSessionId)
        if (store != null) {
            stores[toSessionId] = store
        }
        // [T-android-vm-store-dual-pool] Carry pool membership across the
        // re-key. Without this a renamed session loses its tag and silently
        // falls back to NORMAL on the next lookup — which for a promoted draft
        // that happens to be a child would put it in the pool this change
        // exists to keep it out of.
        poolKinds.remove(fromSessionId)?.let { poolKinds[toSessionId] = it }
        // [OpenMinis#433] Sent: no longer a draft that New Chat may reuse.
        forgetDraft(fromSessionId)
        aliases[fromSessionId] = toSessionId
        // Invalidate anything resolving through the alias map — see
        // [aliasGeneration].
        aliasGeneration.intValue++
        Log.d(TAG, "rename store $fromSessionId -> $toSessionId (alias kept)")
    }

    // ─── Unsent new chats [OpenMinis#433] ───────────────────────────────

    /**
     * [OpenMinis#433] How long a new chat left with unsent text stays
     * available to the next New Chat, counted from the moment it was left.
     * Same value as iOS `ParkedDrafts.lifetime`.
     */
    internal const val PARKED_DRAFT_LIFETIME_MS = 15 * 60 * 1000L

    /** The clock parking reads. Replaced in tests; nothing ever waits on it. */
    @Volatile
    internal var parkedDraftClock: () -> Long = { System.currentTimeMillis() }

    private data class ParkedDraft(val draftId: String, val parkedAtMs: Long)

    /**
     * [OpenMinis#433] New chats the user left with something typed or attached
     * but never sent, one per slot, keyed by [draftSlotKey].
     *
     * Text typed in a new chat was lost on Back + New Chat: the draft's view
     * model stayed in [stores] with the text in it, but every New Chat minted a
     * fresh `__new__<uuid>`, so nothing could reach it again until LRU threw it
     * out. Parking records which draft id to hand back; the view model itself
     * never moves. Memory only, and deliberately not a session row (a row per
     * abandoned draft would fill the list with empty chats). Mirrors iOS
     * `ViewModelCache.parkedNewChats` (d96f8c71d, 885696d42).
     *
     * No timer: expired slots are dropped by whichever park / take / unpark
     * call comes next.
     */
    private val parkedDrafts = mutableMapOf<String, ParkedDraft>()

    /**
     * Draft ids minted by [newChatDraftId], i.e. by a New Chat button. Only
     * these are ever parked. Drafts opened by a share, a quick action, a deep
     * link, the launch-mode preference or the Move-to sheet carry content or
     * intent of their own: they must not pick up an old draft (they never call
     * [newChatDraftId]) and leaving them must neither park nor clear a slot.
     */
    private val newChatDraftIds = mutableSetOf<String>()

    /**
     * The slot a draft id parks in: its model group AND its folder, both read
     * from the id the same way ChatViewModel reads them.
     *
     * iOS keys by model group only, so text typed in "New Chat in Group X"
     * reappears in the next plain New Chat and is filed outside X once sent.
     * Android reuses the draft id itself, and the folder is encoded in it, so
     * a group-only key would carry X into the plain New Chat instead — either
     * way the chat would land somewhere the user did not open it. Matching on
     * both keeps each draft where it was started.
     */
    private fun draftSlotKey(groupId: String?, folderId: String?): String =
        "grp=${groupId.orEmpty()}|fld=${folderId.orEmpty()}"

    private fun draftSlotKey(draftId: String): String = draftSlotKey(
        draftId.substringAfter("__grp__", "").substringBefore("__fld__"),
        draftId.substringAfter("__fld__", "").substringBefore("__grp__"),
    )

    /**
     * [OpenMinis#433] What counts as an unsent draft worth keeping: a draft
     * that has no session row yet and is not running, holding non-blank text
     * (a `[Pasted#N]` placeholder counts — its buffer lives on the same view
     * model) or an attachment. Same rule as iOS `hasUnsentDraft` +
     * `parkNewChatIfUnsent`; Android has no remote sessions to exclude.
     */
    internal fun isUnsentDraft(
        isDraft: Boolean,
        realSessionId: String,
        text: String,
        hasAttachments: Boolean,
        busy: Boolean,
    ): Boolean = isDraft && realSessionId.isEmpty() && !busy && (text.isNotBlank() || hasAttachments)

    /**
     * [OpenMinis#433] The draft id a New Chat button should open: the unsent
     * draft parked for this model group and folder if there is one, otherwise
     * a fresh `__new__<uuid>[__grp__<g>][__fld__<f>]`.
     *
     * A parked draft is handed out once (its slot is emptied), and only while
     * its view model is still cached, never promoted, and still holding
     * something; anything else falls through to a fresh id.
     */
    @Synchronized
    fun newChatDraftId(groupId: String? = null, folderId: String? = null): String {
        val key = draftSlotKey(groupId, folderId)
        purgeExpiredDrafts(parkedDraftClock())
        parkedDrafts.remove(key)?.let { parked ->
            if (isResumableDraft(parked.draftId)) {
                Log.d(TAG, "newChatDraftId: resuming parked draft ${parked.draftId} slot=$key")
                return parked.draftId
            }
        }
        var id = "__new__${java.util.UUID.randomUUID()}"
        if (groupId != null) id += "__grp__$groupId"
        if (folderId != null) id += "__fld__$folderId"
        newChatDraftIds += id
        return id
    }

    private fun isResumableDraft(draftId: String): Boolean {
        if (aliases.containsKey(draftId)) return false
        val store = stores[draftId] ?: return false
        // A view model that has since lost its text (or started running) has
        // nothing to resume. Without one (only in JVM tests: a parked draft
        // was reported by its own view model) trust the verdict at parking.
        return viewModelIn(store)?.hasUnsentDraft ?: true
    }

    /**
     * [OpenMinis#433] ChatScreen is leaving [sessionId]. Park it when it is a
     * New Chat draft with unsent content; otherwise empty its slot if the slot
     * holds this very draft, so text the user cleared before leaving does not
     * come back — and another chat's parked draft is left alone.
     */
    @Synchronized
    fun parkDraftIfUnsent(sessionId: String, hasUnsentDraft: Boolean) {
        if (sessionId !in newChatDraftIds) return
        val now = parkedDraftClock()
        purgeExpiredDrafts(now)
        val key = draftSlotKey(sessionId)
        val keep = hasUnsentDraft && stores.containsKey(sessionId) && !aliases.containsKey(sessionId)
        if (keep) {
            parkedDrafts[key] = ParkedDraft(sessionId, now)
            Log.d(TAG, "parkDraft $sessionId slot=$key keptFor=${PARKED_DRAFT_LIFETIME_MS / 1000}s")
        } else if (parkedDrafts[key]?.draftId == sessionId) {
            parkedDrafts.remove(key)
        }
    }

    /** [OpenMinis#433] [sessionId] is on screen again: no New Chat may take it. */
    @Synchronized
    fun unparkDraft(sessionId: String) {
        purgeExpiredDrafts(parkedDraftClock())
        parkedDrafts.entries.removeAll { it.value.draftId == sessionId }
    }

    /**
     * Drop every slot parked longer than [PARKED_DRAFT_LIFETIME_MS] ago, and
     * release its view model (with its attachments) unless something still
     * needs it, the way iOS lets the parked VM go.
     */
    private fun purgeExpiredDrafts(now: Long) {
        val expired = parkedDrafts.filterValues { now - it.parkedAtMs > PARKED_DRAFT_LIFETIME_MS }
        if (expired.isEmpty()) return
        expired.keys.forEach { parkedDrafts.remove(it) }
        val busy = busyKeys()
        val onScreen = activeSessionIdInternal?.let { resolveKey(it) }
        for (draft in expired.values) {
            val id = draft.draftId
            if (id != onScreen && id !in busy && !aliases.containsKey(id)) dropStoreKeepingChildTag(id)
        }
        Log.d(TAG, "purgeExpiredDrafts: dropped ${expired.size} parked draft(s)")
    }

    /** Forget [draftId] as a parkable New Chat draft (evicted, deleted or sent). */
    private fun forgetDraft(draftId: String) {
        if (newChatDraftIds.remove(draftId)) {
            parkedDrafts.entries.removeAll { it.value.draftId == draftId }
        }
    }

    @Synchronized
    internal fun clearParkedDraftsForTest() {
        parkedDrafts.clear()
        newChatDraftIds.clear()
    }

    /**
     * One-shot stash for the "Move to…" capsule flow. The source session
     * writes (inputText + attachments) here, navigates to the target,
     * and the target's ChatScreen drains it via [consumePendingTransfer].
     * Mirrors iOS `ViewModelCache.pendingTransfer`. Volatile + simple
     * read/write — only ever touched from the main thread.
     */
    data class PendingTransfer(
        val inputText: String,
        val attachments: List<InputAttachment>,
        /**
         * [T-android-moveto-stash-binding] Session this content was moved TO.
         * Only that session may drain the stash. Previously absent, so
         * whichever ChatScreen composed first ate the content — if the
         * navigation to the target didn't land (or the user backed out and
         * opened something else), the moved text/attachments surfaced in an
         * unrelated session. Mirrors iOS 6c3093c8 (GH OpenMinis#120).
         */
        val targetId: String,
        /** Wall-clock stash time; drives the [STASH_TTL_MS] staleness drop. */
        val stashedAtMs: Long = System.currentTimeMillis(),
    )

    /**
     * [T-android-moveto-stash-binding] A stash older than this is considered
     * abandoned and dropped rather than injected. Without it an unclaimed
     * stash sat forever and could ambush a session opened much later.
     */
    private const val STASH_TTL_MS = 300_000L

    @Volatile
    private var pendingTransfer: PendingTransfer? = null

    fun stashPendingTransfer(transfer: PendingTransfer) {
        pendingTransfer = transfer
        Log.d(
            TAG,
            "stashPendingTransfer: target=${transfer.targetId} " +
                "text=${transfer.inputText.length}ch attachments=${transfer.attachments.size}",
        )
    }

    /**
     * Drain the pending-transfer slot exactly once, and only for the session
     * it was addressed to.
     *
     * [sessionId] is the draining screen's session. A mismatch leaves the
     * stash in place so the real target can still claim it when it opens.
     * An expired stash is dropped outright.
     */
    fun consumePendingTransfer(sessionId: String): PendingTransfer? {
        val t = pendingTransfer ?: return null
        if (System.currentTimeMillis() - t.stashedAtMs > STASH_TTL_MS) {
            pendingTransfer = null
            Log.d(TAG, "consumePendingTransfer: dropping stale stash (target=${t.targetId})")
            return null
        }
        // Compare through the draft→canonical alias map, the same way ownerFor /
        // release / activeSessionId do. Today MoveToSessionSheet only offers
        // PERSISTED sessions and rename() only fires for drafts, so raw ids
        // would already match — but resolving makes this correct by
        // construction instead of relying on that invariant, so a future
        // "move into a new chat" target can't strand the transfer.
        if (resolveKey(t.targetId) != resolveKey(sessionId)) {
            // Not ours — leave it for the intended target.
            Log.d(
                TAG,
                "consumePendingTransfer: session=$sessionId is not target=${t.targetId}, leaving stash",
            )
            return null
        }
        pendingTransfer = null
        Log.d(
            TAG,
            "consumePendingTransfer: target=${t.targetId} " +
                "text=${t.inputText.length}ch attachments=${t.attachments.size}",
        )
        return t
    }
}

// ─── Merged from TableColumnPlanner.kt ──────────────────────────────────────────────────

/**
 * [T-android-table-width-by-content] Decides markdown table column widths from
 * how much text each column holds. Port of iOS `TableColumnPlanner` as of
 * b3ec29cee (T-table-width-by-content); widths are in dp, the iOS constants
 * are points, so the numbers match one to one.
 *
 * History: every column at its single-line width (up to 5× the viewport) made
 * prose tables several screens wide; the next plan guaranteed three columns,
 * then two plus a peek — both capped every visible column at the SAME width,
 * so a column with three times the text came out as narrow as its neighbour.
 * Now widths follow the content ratio and a wordier column is never narrower.
 *
 * Per column (all widths include the cell's horizontal padding):
 *   n = natural width: widest cell on one line, never below FLOOR_WIDTH
 *   h = hard minimum: min(n, max(floor, min(longest Latin word, scrollCap), hardMin))
 *   c = comfortable width: min(n, max(h, COMFORT_FRACTION · W))
 * Modes:
 *   1. Σn ≤ W  → NATURAL: every column grows in proportion to n to fill W.
 *   2. Σc ≤ W  → WRAPPED: max(h, min(n, C + (n − C) · t)) with C = COMFORT · W
 *                and one t ∈ [0, 1] chosen so the table exactly fills W.
 *   3. else    → SCROLLS: clamp(n, c, max(c, scrollCap)); the column crossing
 *                the right edge shows at least PEEK_MIN.
 * Invariant (tested): n_i ≥ n_j ⇒ width_i ≥ width_j, except where a long word
 * or an inline formula forces a minimum.
 *
 * Android-only: [Column.hardMin]. iOS fits an over-wide cell formula to the
 * cell (wrap at relations / scale); Android's inline formula is a fixed-size
 * Placeholder sized before layout, so the column must hold it instead.
 *
 * Pure (no Android types) so `TableColumnPlannerTest` runs it on the JVM.
 */
internal object TableColumnPlanner {

    /**
     * @param natural widest cell of the column on a single line, padding included
     * @param minUnbreakable widest run that should not be split (longest Latin word), padding included
     * @param hardMin width the column can never go below, padding included: the
     *   widest inline-formula slot, which cannot wrap. 0 when the column has none.
     */
    data class Column(val natural: Float, val minUnbreakable: Float, val hardMin: Float = 0f)

    enum class Mode { NATURAL, WRAPPED, SCROLLS }

    data class Plan(val widths: FloatArray, val tableWidth: Float, val mode: Mode) {
        override fun equals(other: Any?): Boolean = other is Plan && widths.contentEquals(other.widths) &&
            tableWidth == other.tableWidth && mode == other.mode

        override fun hashCode(): Int = widths.contentHashCode()
    }

    /** Narrowest column (the long-standing minimum). */
    const val FLOOR_WIDTH = 80f
    /** A prose column gets at least this share of the viewport before the table scrolls instead. */
    const val COMFORT_FRACTION = 0.35f
    /** No column (and no unbreakable word) is wider than this share when the table scrolls. */
    const val SCROLL_CAP_FRACTION = 0.7f
    /** Least visible width of the column crossing the right edge, the cue that the table scrolls. */
    const val PEEK_MIN = 24f

    fun plan(columns: List<Column>, availableWidth: Float): Plan {
        val n = columns.size
        val w = max(availableWidth, 0f)
        if (n == 0 || w <= 0f) return Plan(FloatArray(0), w, Mode.NATURAL)
        val floorW = min(FLOOR_WIDTH, w)
        val scrollCap = floor(w * SCROLL_CAP_FRACTION)
        val comfortW = floor(w * COMFORT_FRACTION)
        val natural = FloatArray(n) { max(floorW, ceil(columns[it].natural)) }
        val hard = FloatArray(n) { i ->
            min(natural[i], maxOf(floorW, min(ceil(columns[i].minUnbreakable), scrollCap), ceil(columns[i].hardMin)))
        }
        val comfort = FloatArray(n) { i -> min(natural[i], max(hard[i], comfortW)) }
        val sumNatural = natural.sum()
        val sumComfort = comfort.sum()

        if (sumNatural <= w) {
            // Everything fits on one line: spread the spare width in proportion
            // to content, so no column ends up wider than a wordier one.
            return Plan(FloatArray(n) { natural[it] * w / sumNatural }, w, Mode.NATURAL)
        }
        if (sumComfort <= w) {
            // One growth factor for every column, so width stays a function of
            // content alone; a long word only ever raises its own column to
            // that word, never gives it a bigger share of the spare width.
            fun widths(t: Float) = FloatArray(n) {
                max(hard[it], min(natural[it], comfortW + (natural[it] - comfortW) * t))
            }
            var lo = 0f
            var hi = 1f
            repeat(40) {
                val mid = (lo + hi) / 2
                if (widths(mid).sum() < w) lo = mid else hi = mid
            }
            val result = widths(hi)
            // Bisection lands within a hair of w; the widest column takes the rest.
            val widest = natural.indices.maxByOrNull { natural[it] }!!
            result[widest] += w - result.sum()
            return Plan(result, w, Mode.WRAPPED)
        }
        val widths = FloatArray(n) { max(comfort[it], min(natural[it], scrollCap)) }
        applyPeek(widths, natural, comfort, w)
        return Plan(widths, max(widths.sum(), w), Mode.SCROLLS)
    }

    /**
     * If a column boundary lands just inside the right edge, the next column
     * shows as a sliver (or not at all) and the table reads as finished.
     * Narrow the column before it — never below its comfortable width and never
     * below a column with no more text — so at least PEEK_MIN shows.
     */
    fun applyPeek(widths: FloatArray, natural: FloatArray, comfort: FloatArray, viewport: Float) {
        var x = 0f
        for (k in widths.indices) {
            if (x + widths[k] > viewport) {
                val visible = viewport - x
                if (k < 1 || visible >= PEEK_MIN) return
                val j = k - 1
                val lessText = widths.indices.filter { it != j && natural[it] <= natural[j] }
                    .maxOfOrNull { widths[it] } ?: 0f
                val give = min(PEEK_MIN - visible, widths[j] - max(comfort[j], lessText))
                if (give > 0f) widths[j] -= give
                return
            }
            x += widths[k]
        }
    }

    /**
     * Streaming stability: column widths only grow while a table streams in (a
     * later row can't make an earlier column jump narrower). A table the plan
     * fits in the viewport stays within it: any overshoot from the lift is
     * taken back from the most-lifted columns, never below the plan. A
     * scrolling table keeps its lifted widths.
     */
    fun applyingStreamingFloor(plan: Plan, previous: FloatArray?, availableWidth: Float): Plan {
        if (previous == null || previous.size != plan.widths.size || plan.widths.isEmpty()) return plan
        val widths = FloatArray(plan.widths.size) { max(plan.widths[it], previous[it]) }
        if (plan.mode != Mode.SCROLLS) {
            var over = widths.sum() - max(availableWidth, plan.widths.sum())
            while (over >= 0.5f) {
                val i = widths.indices.maxByOrNull { widths[it] - plan.widths[it] } ?: break
                val lifted = widths[i] - plan.widths[i]
                if (lifted <= 0f) break
                val take = min(over, lifted)
                widths[i] -= take
                over -= take
            }
        }
        return Plan(widths, max(widths.sum(), availableWidth), plan.mode)
    }

    /**
     * Longest run of the text that should not be split across lines: a
     * whitespace-delimited run with CJK counted as a break (CJK wraps per
     * character). Null when there is no run longer than one character.
     */
    fun longestUnbreakableRun(text: String): String? {
        var best: String? = null
        var start = -1
        var i = 0
        fun close(end: Int) {
            if (start < 0) return
            val run = text.substring(start, end)
            if (run.codePointCount(0, run.length) > (best?.let { it.codePointCount(0, it.length) } ?: 0)) best = run
            start = -1
        }
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val breaks = Character.isWhitespace(cp) || isCjk(cp)
            if (breaks) close(i) else if (start < 0) start = i
            i += Character.charCount(cp)
        }
        close(text.length)
        val b = best ?: return null
        return if (b.codePointCount(0, b.length) > 1) b else null
    }

    private fun isCjk(cp: Int): Boolean = cp in 0x1100..0x11FF || cp in 0x2E80..0x2FDF ||
        cp in 0x3000..0x303F || cp in 0x3040..0x30FF || cp in 0x3100..0x31FF ||
        cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xAC00..0xD7AF ||
        cp in 0xF900..0xFAFF || cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFFEF ||
        cp in 0x20000..0x2FA1F
}

// ─── Merged from TitleCandidates.kt ──────────────────────────────────────────────────

/**
 * [T-titlegen-group-order] Which models title generation tries, and in what
 * order. iOS parity: `regenerateTitleCandidates` + `expandSourceForSubTasks`
 * (987f1f70d, 26ccf9cb4).
 *
 * A title call used to reach ONE model. The auto path resolved the title
 * group's first member (or fell back to the chat's model) and called it once
 * per turn; manual Regenerate took the title group's first member, the
 * session's model id, then every other entry. So a rate-limited title model
 * left the chat on "New Chat" until the first-message fallback, and the other
 * members of the group the user had ordered precisely for this — the point of
 * a fallback group — were never asked.
 *
 * Order, deduped by entry id, every candidate available (provider enabled +
 * credentialed + entry visible) and title-eligible:
 *   1  title sub group (`defaultSubGroupId`) — the WHOLE group, router order
 *   2  the session's primary source — its whole group, or its pinned entry
 *   2b only if nothing so far: the session row's model id
 *   2c the default primary group — whole group, router order
 *   3  every other available eligible entry
 *
 * "Router order" is the order the agent loop walks a group on provider
 * failure: the member it would pick first (the preferred/last-used member
 * when still available, else the strategy's pick — first available for
 * `fallback`, session-id hash for `loadBalance`), then the members after it,
 * wrapping around. See ChatViewModel.resolveProviderFromGroup +
 * buildFallbackProviders.
 */
internal object TitleCandidates {

    /**
     * Cap on models tried per title request: enough to exhaust a typical
     * 6-member title group before the first-message fallback, without letting
     * a systemic outage spray every configured model. iOS auto path: 6.
     */
    const val MAX_TRIES = 6

    /** Pause between auto-title tries: rate limits recover in seconds. */
    const val AUTO_PAUSE_MS = 1_500L

    /** Where the session's primary model comes from (ChatSessionEntity.modelBinding). */
    sealed class PrimarySource {
        data class Group(val groupId: String, val preferredEntryId: String?) : PrimarySource()
        data class Entry(val entryId: String) : PrimarySource()

        companion object {
            /** Parse the persisted binding JSON; null for none / unreadable. */
            fun parse(bindingJson: String?): PrimarySource? {
                if (bindingJson.isNullOrBlank()) return null
                return try {
                    val obj = org.json.JSONObject(bindingJson)
                    when (obj.optString("type")) {
                        "group" -> obj.optString("groupId").takeIf { it.isNotEmpty() }
                            ?.let { Group(it, obj.optString("lastEntryId").takeIf { e -> e.isNotEmpty() }) }
                        "entry" -> obj.optString("entryId").takeIf { it.isNotEmpty() }?.let { Entry(it) }
                        else -> null
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    /**
     * T334 title-eligibility, shared by both paths: the model must output
     * text (no declared modalities counts as text) and its id must not name a
     * non-chat capability — those endpoints reject the chat schema or stream
     * nothing useful.
     */
    fun isTitleEligible(entry: ModelEntry): Boolean {
        val outs = entry.model.outputModalities
        val outputsText = outs == null || outs.isEmpty() || outs.contains("text")
        val idLower = entry.model.id.lowercase()
        val nonChatId = listOf("tts", "voiceclone", "voicedesign", "embedding", "embed-", "whisper", "image", "video")
            .any { idLower.contains(it) }
        return outputsText && !nonChatId
    }

    /**
     * [group]'s [available] members (already filtered, in member order) in
     * router order: the first pick, then the rest wrapping around.
     */
    fun expandGroup(
        group: ModelGroup,
        available: List<ModelEntry>,
        sessionId: String,
        preferredEntryId: String? = null,
    ): List<ModelEntry> {
        if (available.isEmpty()) return emptyList()
        val start = available.indexOfFirst { it.id == preferredEntryId }.takeIf { it >= 0 }
            ?: when (group.strategy) {
                RoutingStrategy.loadBalance -> Math.floorMod(sessionId.hashCode(), available.size)
                RoutingStrategy.fallback -> 0
            }
        return List(available.size) { available[(start + it) % available.size] }
    }

    /**
     * Assemble the ordered list from the tiers (see the class doc). Pure: the
     * caller supplies lookups, so the ordering is unit-tested without a
     * repository.
     */
    fun order(
        sessionId: String,
        subGroup: ModelGroup?,
        primary: PrimarySource?,
        sessionModelId: String?,
        defaultPrimaryGroup: ModelGroup?,
        pool: List<ModelEntry>,
        group: (String) -> ModelGroup?,
        entry: (String) -> ModelEntry?,
        availableMembers: (ModelGroup) -> List<ModelEntry>,
        isAvailable: (ModelEntry) -> Boolean,
    ): List<ModelEntry> {
        val ordered = mutableListOf<ModelEntry>()
        val seen = HashSet<String>()
        fun push(e: ModelEntry?) {
            if (e == null || !seen.add(e.id)) return
            if (!isAvailable(e) || !isTitleEligible(e)) return
            ordered += e
        }
        fun pushGroup(g: ModelGroup?, preferred: String? = null) {
            if (g == null) return
            expandGroup(g, availableMembers(g), sessionId, preferred).forEach(::push)
        }

        pushGroup(subGroup)
        when (primary) {
            is PrimarySource.Group -> pushGroup(group(primary.groupId), primary.preferredEntryId)
            is PrimarySource.Entry -> push(entry(primary.entryId))
            null -> Unit
        }
        if (ordered.isEmpty() && !sessionModelId.isNullOrEmpty()) {
            push(pool.firstOrNull { it.model.id == sessionModelId && isAvailable(it) })
        }
        pushGroup(defaultPrimaryGroup)
        pool.forEach(::push)
        return ordered
    }

    /** [order] against the live provider configuration. */
    fun forSession(
        repo: ProviderRepository,
        sessionId: String,
        primary: PrimarySource?,
        sessionModelId: String?,
    ): List<ModelEntry> {
        val config = repo.config.value
        // One credential check per INSTANCE, not per entry: hasAnyCredential
        // reads the (encrypted) key store, and a large catalog has hundreds of
        // entries on a handful of instances — measured 12.5 s for 901 entries
        // on a Pixel 4a before this, on the thread finishing the agent turn.
        val instanceUsable = HashMap<String, Boolean>()
        fun isAvailable(e: ModelEntry): Boolean {
            if (e.isHidden) return false
            return instanceUsable.getOrPut(e.providerInstanceId) {
                val inst = config.instances.find { it.id == e.providerInstanceId }
                inst != null && inst.isEnabled && repo.hasAnyCredential(inst)
            }
        }
        val entriesById = config.modelEntries.associateBy { it.id }
        return order(
            sessionId = sessionId,
            subGroup = repo.defaultSubGroupId?.let { repo.group(it) },
            primary = primary,
            sessionModelId = sessionModelId,
            defaultPrimaryGroup = repo.defaultPrimaryGroupId?.let { repo.group(it) },
            pool = repo.allVisibleEntries(),
            group = { repo.group(it) },
            entry = { id -> entriesById[id] },
            // Same filter as ProviderRepository.availableMemberEntries (hidden /
            // disabled / uncredentialed dropped, member order kept), through
            // the memoized check.
            availableMembers = { g -> g.memberEntryIds.mapNotNull { entriesById[it] }.filter(::isAvailable) },
            isAvailable = ::isAvailable,
        )
    }

    /**
     * Build a provider for one candidate: usable key (keyless self-hosted
     * endpoints and OAuth instances included), OAuth token refreshed first so
     * an expired cached token does not 401 the title call, tagged with the
     * chat id (OpenCode Go needs `x-opencode-session`). Null when the entry
     * cannot be driven at all — the walk then moves on.
     */
    suspend fun providerFor(
        repo: ProviderRepository,
        context: Context,
        entry: ModelEntry,
        sessionId: String,
    ): LLMProvider? {
        val instance = repo.instance(entry.providerInstanceId) ?: return null
        val apiKey = repo.requestCredential(instance) ?: return null
        return try {
            ProviderFactory.create(instance, apiKey, entry.model, context, sessionId = sessionId, overrides = entry.overrides)
        } catch (e: Exception) {
            Log.w("TitleGen", "provider creation failed for ${entry.model.id}: ${e.message}")
            null
        }
    }

    /**
     * [T-android-titlegen-stream-error-walk] Whether [e] is the caller being
     * cancelled, rather than one model failing.
     *
     * Providers report a mid-stream failure (an inline SSE `error` frame such
     * as OpenRouter's 429, `response.failed`, a stream that closed empty) by
     * cancelling their producer with the real LLMError as the cause, so the
     * collector sees a CancellationException that is NOT a cancellation of
     * this coroutine. Rethrowing every CancellationException ended the whole
     * walk on the first such error and never tried the next title model — the
     * exact report 0047dc76c set out to fix. Same rule as the agent loop
     * (`e is CancellationException && e.cause == null`), plus the coroutine's
     * own state, so a real cancellation that happens to carry a cause still
     * propagates.
     */
    suspend fun isRealCancellation(e: kotlinx.coroutines.CancellationException): Boolean =
        e.cause == null || !kotlinx.coroutines.currentCoroutineContext().isActive

    /**
     * Walk [candidates] (at most [maxTries]) until [tryOne] returns non-null.
     * A throw or a null result is a failure and moves on to the next model —
     * an empty or unparseable title counts too. [shouldStop] is checked before
     * each try (e.g. the user renamed the chat meanwhile). [pauseMs] separates
     * tries. Returns the first success, or null when every try failed.
     */
    suspend fun <T : Any> walk(
        candidates: List<ModelEntry>,
        origin: String,
        maxTries: Int = MAX_TRIES,
        pauseMs: Long = 0L,
        shouldStop: suspend () -> Boolean = { false },
        tryOne: suspend (ModelEntry) -> T?,
    ): T? {
        val tries = candidates.take(maxTries)
        for ((idx, entry) in tries.withIndex()) {
            if (shouldStop()) {
                AppLogger.info("TitleGen", "walk origin=$origin stopped before candidate ${idx + 1}/${tries.size}")
                return null
            }
            AppLogger.info("TitleGen", "walk origin=$origin candidate ${idx + 1}/${tries.size} model=${entry.model.id}")
            val result = try {
                tryOne(entry)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException && isRealCancellation(e)) throw e
                AppLogger.warning(
                    "TitleGen",
                    "walk origin=$origin FAILED ${idx + 1}/${tries.size} model=${entry.model.id} " +
                        "${e.javaClass.simpleName}: ${e.message?.take(200)}",
                )
                null
            }
            if (result != null) {
                if (idx > 0) AppLogger.info("TitleGen", "walk origin=$origin succeeded on fallback candidate ${idx + 1} (${entry.model.id})")
                return result
            }
            if (pauseMs > 0 && idx + 1 < tries.size) delay(pauseMs)
        }
        AppLogger.warning("TitleGen", "walk origin=$origin all ${tries.size} candidate(s) failed (of ${candidates.size})")
        return null
    }
}

// ─── Merged from ToggleAnchor.kt ──────────────────────────────────────────────────

/**
 * [T-user-ask-resolved-expand] Pure geometry for "a card the user opens or
 * shuts keeps its top in place" (iOS e563158e2 ToggleAnchorMath).
 *
 * The chat list is reverseLayout: an item's BOTTOM is pinned (to the item
 * below it, or to the viewport bottom), so a card that grows pushes its top
 * up and one that shrinks pulls it down. Scrolling by the height change
 * undoes exactly that. In LazyListState units under reverseLayout, a positive
 * scroll moves content down and a negative one moves it up, toward the newest
 * message (see ChatScreen's jump-to-bottom glide: "Negative = toward
 * newest/bottom").
 */
internal object ToggleAnchorMath {
    /** The scroll that keeps the card's top fixed when its height goes [oldHeight] -> [newHeight]. */
    fun keepTopScroll(oldHeight: Int, newHeight: Int): Float = (newHeight - oldHeight).toFloat()

    /**
     * After an EXPAND only: how far (px, >= 0) to move the content up so the
     * opened card's bottom is visible, never pushing its top above the
     * visible top. A collapse never scrolls back (iOS revealShift).
     */
    fun revealShift(cardTop: Float, cardBottom: Float, visibleTop: Float, visibleBottom: Float): Float {
        val overflow = cardBottom - visibleBottom
        if (overflow <= 0f) return 0f
        return minOf(overflow, cardTop - visibleTop).coerceAtLeast(0f)
    }

    /** Ease-in-out over [0, 1], the curve animateContentSize's default spring roughly follows. */
    fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).let { it * it * it } / 2
    }

    /** Height shown at progress [t] of a resize from [from] to [to]. */
    fun heightAt(from: Int, to: Int, t: Float): Int = from + Math.round((to - from) * ease(t))

    /**
     * The LazyColumn index of flat row [flatPos]: the list renders
     * `flatItems.asReversed()` after [leading] conditional items (the same
     * relationship as ChatScreen's previous-turn jump).
     */
    fun lazyIndex(flatPos: Int, flatSize: Int, leading: Int): Int = (flatSize - 1 - flatPos) + leading

    /** [leading] measured from one visible row: its lazy index and flat position. */
    fun leadingCount(lazyIndex: Int, flatPos: Int, flatSize: Int): Int = lazyIndex - (flatSize - 1 - flatPos)

    /**
     * The scroll offset that puts a row back where it was. LazyListItemInfo
     * offsets and scroll offsets share one axis (an item at scroll position
     * (i, s) has offset -s), so a row that sat at [offset] is requested at
     * -offset. Negative is fine: the next measure fills in the newer rows
     * below it.
     */
    fun restoreScrollOffset(offset: Int): Int = -offset
}

/**
 * The chat list's side of a top-anchored toggle. Provided to list items by
 * [LocalChatToggleAnchor]; null outside the chat list (a card then just
 * animates, as before).
 */
internal class ChatToggleAnchor(private val listState: LazyListState) {
    /** The LazyColumn's coordinates and the px hidden under its bottom reserve (composer / toolbar). */
    var listCoords: () -> LayoutCoordinates? = { null }
    var bottomInsetPx: Float = 0f
    var scope: CoroutineScope? = null

    /**
     * [T-toggle-anchor-snap-yield] True while a user toggle is moving the list
     * (and briefly after): ChatScreen's bottom drift snap stands down, or it
     * would undo each compensation step a frame later when pinned to the bottom.
     */
    @Volatile private var busyUntilMs = 0L
    val isBusy: Boolean get() = android.os.SystemClock.uptimeMillis() < busyUntilMs
    fun markBusy(forMs: Long = BUSY_TAIL_MS) {
        busyUntilMs = maxOf(busyUntilMs, android.os.SystemClock.uptimeMillis() + forMs)
    }

    /** A tapped fold row waiting for the list that opens / shuts it. */
    private class PendingRow(val key: String, val offset: Int, val leading: Int, val size: Int)
    private var pendingRow: PendingRow? = null

    /**
     * A folded tool row was tapped: remember where it is. Expanding inserts its
     * calls as separate rows BELOW it, and the reverseLayout list keeps the
     * row under them in place, so without this the tapped row is pushed up.
     */
    fun captureRow(key: String, rows: List<com.openminis.app.ui.chat.FlatChatItem>) {
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val pos = rows.indexOfFirst { it.key == key }
        if (pos < 0) return
        pendingRow = PendingRow(key, info.offset, ToggleAnchorMath.leadingCount(info.index, pos, rows.size), rows.size)
    }

    /** The folded rows were rebuilt: request the tapped row's old place for the same remeasure. */
    fun restoreRow(rows: List<com.openminis.app.ui.chat.FlatChatItem>) {
        val p = pendingRow ?: return
        pendingRow = null
        val pos = rows.indexOfFirst { it.key == p.key }
        if (pos < 0) return
        val index = ToggleAnchorMath.lazyIndex(pos, rows.size, p.leading)
        if (index < 0) return
        markBusy()
        listState.requestScrollToItem(index, ToggleAnchorMath.restoreScrollOffset(p.offset))
        val added = rows.size - p.size
        if (added > 0) scope?.launch { revealRows(p.key, index, added) }
    }

    /** After a fold row opened: bring its last call into view, keeping the row itself visible. */
    private suspend fun revealRows(key: String, index: Int, added: Int) {
        withFrameNanos { }
        withFrameNanos { }
        val info = listState.layoutInfo
        val head = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val last = info.visibleItemsInfo.firstOrNull { it.index == index - added }
        // Axis: offsets grow from the list's bottom edge upward (reverseLayout),
        // so "below the visible bottom" is a negative offset.
        val blockBottom = last?.offset ?: Int.MIN_VALUE / 2
        val shift = ToggleAnchorMath.revealShift(
            cardTop = -(head.offset + head.size).toFloat(),
            cardBottom = -blockBottom.toFloat(),
            visibleTop = -(info.viewportEndOffset - info.afterContentPadding).toFloat(),
            visibleBottom = 0f,
        )
        if (shift > 0f) { markBusy(); listState.animateScrollBy(-shift) }
    }

    /** Undo the bottom pin for one height change. Called in a frame callback, before that frame's measure. */
    fun keepTop(oldHeight: Int, newHeight: Int) {
        markBusy()
        val d = ToggleAnchorMath.keepTopScroll(oldHeight, newHeight)
        if (d != 0f) listState.dispatchRawDelta(d)
    }

    /** One short scroll bringing an opened card's bottom into view (top stays visible). */
    suspend fun reveal(card: LayoutCoordinates?) {
        val list = listCoords() ?: return
        if (card == null || !card.isAttached || !list.isAttached) return
        val box = list.localBoundingBoxOf(card, clipBounds = false)
        val shift = ToggleAnchorMath.revealShift(box.top, box.bottom, 0f, list.size.height - bottomInsetPx)
        if (shift > 0f) { markBusy(); listState.animateScrollBy(-shift) }
    }
}

internal val LocalChatToggleAnchor = staticCompositionLocalOf<ChatToggleAnchor?> { null }

/** How long after its last step a toggle keeps the drift snap away. */
private const val BUSY_TAIL_MS = 450L

/**
 * A card's own open / shut animation with its top held in place.
 *
 * animateContentSize cannot do this: it resizes during measure, and the list's
 * compensating scroll would land a frame later, so the top would wobble. Here
 * one frame callback sets both the shown height and the compensating scroll,
 * and the frame's measure applies them together.
 *
 * Apply [modifier] to the card's outermost layout. Its content is measured at
 * its natural height; while [shown] is pinned, the card reports that height
 * instead and clips.
 */
internal class TopAnchoredResize(private val durationMs: Long = 260) {
    private var natural = -1
    private var shown: Int? by mutableStateOf(null)
    private var job: Job? = null
    /** The card's coordinates, for the reveal. */
    var coords: LayoutCoordinates? = null

    val modifier: Modifier = Modifier
        .clipToBounds()
        .layout { measurable, constraints ->
            val p = measurable.measure(constraints)
            natural = p.height
            val h = shown ?: p.height
            layout(p.width, h) { p.place(0, 0) }
        }

    /** Flip the card's state with [flip] and animate the height change, top fixed. */
    fun toggle(scope: CoroutineScope, anchor: ChatToggleAnchor?, flip: () -> Unit) {
        val from = shown ?: natural
        job?.cancel()
        if (from < 0) { flip(); return }
        shown = from
        anchor?.markBusy(durationMs + 450)
        flip()
        // A newer toggle cancels this one and pins its own starting height, so
        // a cancelled run leaves [shown] alone.
        job = scope.launch {
            withFrameNanos { }   // this frame measures the new content (height still pinned)
            withFrameNanos { }   // its natural height is known from here on
            val to = natural
            var current = from
            val start = withFrameNanos { it }
            while (true) {
                val done = withFrameNanos { now ->
                    val t = ((now - start) / 1_000_000f) / durationMs
                    val h = if (t >= 1f) to else ToggleAnchorMath.heightAt(from, to, t)
                    // Same frame callback: the scroll and the height reach this frame's measure together.
                    anchor?.keepTop(current, h)
                    current = h
                    shown = h
                    t >= 1f
                }
                if (done) break
            }
            shown = null
            if (to > from) {
                withFrameNanos { }
                anchor?.reveal(coords)
            }
        }
    }
}
