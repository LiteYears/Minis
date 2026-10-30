package com.openminis.app.provider.openai

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.normalizeModalities
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.ProviderModelsCache
import com.openminis.app.provider.applyUserAgentOverride
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-android-chat-tool-adjacency] (#382 / #352) Last gate before a Chat
 * Completions request goes out: every assistant `tool_calls` block is followed
 * at once by exactly one `role:"tool"` reply per call, and no `tool` message
 * stands anywhere else. Port of iOS `sanitizeToolCallAdjacency`
 * (OpenAIAgentProvider.swift), the counterpart of [ResponsesToolPairing].
 *
 * Why it is needed: the three earlier layers all pair by ID, none by POSITION.
 *  - ChatViewModel.sanitizeAgentHistory checks only "the next message answers
 *    this call": when it does not, it injects an "interrupted" result right
 *    after the call, and keeps the real result wherever it was, because its id
 *    still exists somewhere in the history;
 *  - RequestContextBuilder.dropOrphanedToolParts pairs over id sets;
 *  - globallyDedupeToolCallIds only renames repeated ids.
 * So a history whose ids all match but whose results are not adjacent, or are
 * duplicated, went out as built. DeepSeek / OpenAI answer
 *     tool calls and tool results do not match … / No tool output found …
 * and, because the request is rebuilt from the same history every time, the
 * 400 repeats on every retry until the chat is cleared. Seen in synced /
 * imported sessions: a user message stored between the calls and their
 * results, or two consecutive tool-call turns with the first one's result
 * after the second.
 *
 * Repair, as iOS does it (two passes, by id):
 *  1. take every `tool` reply out of the array, grouped by `tool_call_id`;
 *  2. re-emit the other messages in their order, and right after each
 *     assistant `tool_calls` block put one reply per call, in call order:
 *     - the reply found for that id (it MOVES there, wherever it was);
 *     - of several replies for one id, the first REAL one wins. A repair
 *       placeholder ([SYNTHETIC_REPLIES]) loses to a real result: the history
 *       repair above puts its "interrupted" result BEFORE the real one, and
 *       first-wins alone would send the fake and drop the tool's output;
 *     - no reply at all: a placeholder ([PLACEHOLDER_REPLY]);
 *  3. replies left over are dropped: duplicates, and replies no call claims.
 *
 * Nothing else moves: user / assistant / system messages, and the image
 * carriers that follow a tool run, keep their order.
 *
 * An array that is already well formed is returned as is (same object, same
 * bytes), so healthy chats keep their prompt-cache prefix.
 *
 * Runs after globallyDedupeToolCallIds, which makes every non-empty call id
 * unique in the request, so "the reply for this id" is unambiguous.
 */
internal object ChatCompletionsToolPairing {

    /** The same wording as iOS and the Responses path; one constant for both. */
    const val PLACEHOLDER_REPLY = ResponsesToolPairing.PLACEHOLDER_OUTPUT

    /**
     * What ChatViewModel's history repair writes for a call it finds
     * unanswered. Kept in step with it by ChatCompletionsToolPairingTest.
     */
    const val HISTORY_REPAIR_REPLY = "Tool execution was interrupted by an unexpected error."

    /** Replies that stand in for a missing result; a real result beats them. */
    val SYNTHETIC_REPLIES: Set<String> = setOf(PLACEHOLDER_REPLY, HISTORY_REPAIR_REPLY)

    class Result(
        val messages: JSONArray,
        /** Call ids whose reply was not right after its call and was moved there. */
        val movedReplies: List<String>,
        /** Ids of extra replies for a call that already had one. */
        val droppedDuplicateReplies: List<String>,
        /** Ids of replies no assistant tool_calls claims. */
        val droppedOrphanReplies: List<String>,
        /** Call ids that got a placeholder reply. */
        val placeholderCalls: List<String>,
    ) {
        val changed: Boolean
            get() = movedReplies.isNotEmpty() || droppedDuplicateReplies.isNotEmpty() ||
                droppedOrphanReplies.isNotEmpty() || placeholderCalls.isNotEmpty()
    }

    fun sanitize(messages: JSONArray): Result {
        if (isWellFormed(messages)) return Result(messages, emptyList(), emptyList(), emptyList(), emptyList())

        // Pass 1: every tool reply, by id, in the order it stands in.
        val replies = LinkedHashMap<String, ArrayList<Pair<Int, JSONObject>>>()
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            if (msg.optString("role") != "tool") continue
            replies.getOrPut(msg.optString("tool_call_id")) { ArrayList() }.add(i to msg)
        }

        val out = JSONArray()
        val moved = mutableListOf<String>()
        val placeholders = mutableListOf<String>()
        val claimedIds = HashSet<String>()
        // Pass 2: the rest in order, each tool_calls block followed by its replies.
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i)
            if (msg == null) {
                out.put(messages.get(i))
                continue
            }
            if (msg.optString("role") == "tool") continue
            out.put(msg)
            val calls = toolCallIds(msg) ?: continue
            val adjacent = adjacentReplyRange(messages, i)
            for (id in calls) {
                claimedIds.add(id)
                val candidates = replies[id]
                val pick = candidates?.firstOrNull { it.second.optString("content") !in SYNTHETIC_REPLIES }
                    ?: candidates?.firstOrNull()
                if (pick == null) {
                    placeholders += id
                    out.put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", id)
                        put("content", PLACEHOLDER_REPLY)
                    })
                    continue
                }
                candidates!!.remove(pick)
                if (pick.first !in adjacent) moved += id
                out.put(pick.second)
            }
        }

        val duplicates = mutableListOf<String>()
        val orphans = mutableListOf<String>()
        for ((id, left) in replies) {
            for (unused in left) if (id in claimedIds) duplicates += id else orphans += id
        }
        return Result(out, moved, duplicates, orphans, placeholders)
    }

    /** The ids of a message's tool calls, or null when it is not an assistant tool_calls block. */
    private fun toolCallIds(msg: JSONObject): List<String>? {
        if (msg.optString("role") != "assistant") return null
        val calls = msg.optJSONArray("tool_calls") ?: return null
        if (calls.length() == 0) return null
        return (0 until calls.length()).map { calls.optJSONObject(it)?.optString("id") ?: "" }
    }

    /** Indices of the run of `tool` messages right after the message at [index]. */
    private fun adjacentReplyRange(messages: JSONArray, index: Int): IntRange {
        var end = index
        while (end + 1 < messages.length() && messages.optJSONObject(end + 1)?.optString("role") == "tool") end++
        return (index + 1)..end
    }

    /**
     * Every tool_calls block is followed at once by exactly its replies (one
     * per call, any order), and there is no other `tool` message.
     */
    private fun isWellFormed(messages: JSONArray): Boolean {
        var i = 0
        while (i < messages.length()) {
            val msg = messages.optJSONObject(i)
            if (msg == null) { i++; continue }
            if (msg.optString("role") == "tool") return false
            val calls = toolCallIds(msg)
            if (calls == null) { i++; continue }
            val run = adjacentReplyRange(messages, i)
            val replyIds = run.map { messages.optJSONObject(it)?.optString("tool_call_id") ?: "" }
            if (replyIds.size != calls.size || replyIds.sorted() != calls.sorted()) return false
            i = run.last + 1
        }
        return true
    }
}

// ─── Merged from CodexModelsApi.kt ──────────────────────────────────────────────────

/**
 * [T-codex-dynamic-discovery GH#319] Live model discovery for the Codex OAuth
 * path.
 *
 * ## Why this exists
 *
 * [OpenAIModelsApi.fetchModelsOAuth] returns a list compiled into the app. Its
 * comment ("OAuth tokens can't call /v1/models") is true and remains true —
 * but it was read as "therefore no discovery is possible", which is not. The
 * Codex backend has its own discovery route, `/backend-api/codex/models`, that
 * the OAuth token DOES authenticate against. Consequence of the gap: a model
 * enabled on the user's own ChatGPT account was invisible until someone shipped
 * a new build with the id typed into that literal, and pressing Refresh could
 * not help because Refresh ran the same literal.
 *
 * Verified independently before this was written (2026-09-11):
 *   * `GET https://chatgpt.com/backend-api/codex/models?client_version=…`
 *     with a bogus bearer answers **401** + `{"detail":"Could not parse your
 *     authentication token…"}` — a real auth challenge on a real route.
 *   * `GET https://chatgpt.com/backend-api/models?client_version=…` answers
 *     **403 text/html** (a Cloudflare-style block page). That is NOT the
 *     discovery route; it is deliberately not in the path list below.
 *
 * ## Identity on the wire
 *
 * The client identity matches [OpenAIProvider]'s inference request — same
 * `Originator: codex_cli_rs`, same `codex_cli_rs/<v> (Android; arm64)` UA, same
 * client version (one constant). This is deliberate and load-bearing in two
 * directions: OpenAI gates model availability on the advertised client version,
 * so discovery must not advertise a different one than inference (a model
 * listed under one version and refused under another is the worst outcome), and
 * the account must not see two different client identities from one app.
 * [T-codex-discovery-client-version] The Responses-only headers (`Version`,
 * `Openai-Beta`) are no longer sent here; see [discoveryRequest].
 *
 * The upstream reference implementation this was checked against uses its own
 * `originator: omp`; that is *its* identity, not ours, and is not copied.
 *
 * ## What this is NOT
 *
 * Nothing here touches the API-key path, the user-configured OpenAI-compatible
 * bearer path, or `OpenAIModelsApi.fetchModels`. Those keep calling
 * `/v1/models` exactly as before.
 */
object CodexModelsApi {
    private const val TAG = "CodexModelsApi"

    /**
     * Discovery route. Single-element on purpose — see the class comment: the
     * bare `/backend-api/models` sibling returns a 403 HTML block page, so
     * probing it would only add a wasted round trip and a misleading log line.
     */
    private const val MODELS_URL = "https://chatgpt.com/backend-api/codex/models"

    /**
     * Separate from the shared OkHttp default so a hung discovery call can
     * never stall a Refresh tap for minutes. Discovery is strictly optional —
     * every failure path here has a fallback — so it gets a short leash.
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Namespaced away from `"openai"` so a Codex discovery result and an
     * API-key `/v1/models` result for the same account can never collide.
     */
    private val cache = ProviderModelsCache("codex-oauth")

    /** `visibility` values that keep a discovered model out of the picker (same set as iOS). */
    internal val HIDDEN_VISIBILITIES = setOf("hide", "hidden", "internal", "none")

    /** Outcome of one discovery attempt. */
    sealed interface Result {
        /** The backend answered and we parsed at least one visible model. */
        data class Success(val models: List<LLMModel>) : Result

        /**
          * The backend rejected the CREDENTIAL (401/403 JSON). Distinct from
          * [Failed] because the honest user-facing message differs: this one
          * means "sign in again", and per GH#319 must NOT be laundered into a
          * silent success that redisplays the previous list as if it were
          * fresh.
          */
        data class AuthFailed(val status: Int) : Result

        /**
         * Transport error, non-auth HTTP error, unparseable body, or a parsed
         * body with no visible models. All of these mean "we learned nothing",
         * which is the caller's cue to fall back — never to erase.
         */
        data class Failed(val reason: String) : Result
    }

    /**
     * Fetch the account's live model list.
     *
     * @param accessToken a CURRENT access token — refreshing is the OAuth
     *   manager's job and is done by the caller before we get here.
     * @param accountId `chatgpt-account-id`. Optional on the wire (its
     *   absence does not 401) and it is also the preferred cache partition —
     *   see [cacheKey] for what happens when it is missing.
     * @param forceRefresh true for a user-initiated Refresh: skips the cache
     *   READ entirely. GH#319 calls this out explicitly — a Refresh button that
     *   returns a cached list is not a refresh.
     */
    suspend fun fetchModels(
        accessToken: String,
        accountId: String?,
        clientVersion: String,
        context: Context? = null,
        forceRefresh: Boolean = false,
        // [FEAT-261021-16] The instance's Settings > Network tag (prov:<id>);
        // null follows the default route.
        netTag: String? = null,
    ): Result = withContext(Dispatchers.IO) {
        val key = cacheKey(accountId, accessToken, clientVersion)
        // hasAccountId, never the id itself. It gates BOTH the
        // chatgpt-account-id header and the cache partition, so when a refresh
        // mysteriously never caches this one line says why (see [cacheKey]).
        AppLogger.info(
            TAG,
            "discovery start: clientVersion=$clientVersion hasAccountId=${!accountId.isNullOrBlank()} force=$forceRefresh",
        )
        if (!forceRefresh && context != null) {
            cache.load(context, key)?.let {
                // Ids only (never the token or account), so "why is model X
                // missing" can be answered from the log.
                AppLogger.info(TAG, "discovery cache hit (${it.size} models): ${it.joinToString { m -> m.id }}")
                recordUltrafast(context, it)
                return@withContext Result.Success(it)
            }
        }

        val builder = discoveryRequest(accessToken, accountId, clientVersion)

        val (status, body) = try {
            com.openminis.app.network.NetworkHttp.client(netTag, client).newCall(builder.build()).execute().use { resp ->
                resp.code to (resp.body?.string() ?: "")
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "discovery transport error: ${e.javaClass.simpleName}: ${e.message}")
            return@withContext Result.Failed("network: ${e.javaClass.simpleName}")
        }

        if (status == 401 || status == 403) {
            // Drop the cached catalog for this account: it was fetched under a
            // credential the backend now refuses, so continuing to serve it
            // would be exactly the "stale list presented as success" that
            // GH#319 asks us to avoid.
            if (context != null) cache.invalidate(context, key)
            AppLogger.warning(TAG, "discovery rejected credential: HTTP $status")
            return@withContext Result.AuthFailed(status)
        }
        if (status !in 200..299) {
            AppLogger.warning(TAG, "discovery HTTP $status: ${body.take(200)}")
            return@withContext Result.Failed("http $status")
        }

        val parsed = try {
            parseModels(body)
        } catch (e: Exception) {
            AppLogger.warning(TAG, "discovery parse error: ${e.javaClass.simpleName}: ${e.message}")
            return@withContext Result.Failed("parse: ${e.javaClass.simpleName}")
        }

        if (parsed.isEmpty()) {
            AppLogger.warning(TAG, "discovery returned no visible models")
            return@withContext Result.Failed("empty")
        }

        val enriched = enrichPreservingFresh(parsed)
        AppLogger.info(
            TAG,
            "discovery OK (${enriched.size} models): ${enriched.joinToString { it.id }}",
        )
        if (context != null) cache.save(context, key, enriched)
        recordUltrafast(context, enriched)
        Result.Success(enriched)
    }

    /**
     * [T-android-ultrafast-align] Records which models this account's catalogue
     * declares Ultrafast for (SpeedTiers.catalogDeclaresUltrafast; iOS
     * recordCatalogUltrafast), and logs it in iOS's words, so "why is Ultrafast
     * offered / sent" can be read from the log.
     */
    private fun recordUltrafast(context: Context?, models: List<LLMModel>) {
        val ids = models.filter { m -> m.serviceTiers?.contains("ultrafast") == true }.map { it.id.lowercase() }
        com.openminis.app.data.FastModePrefs.recordCatalogUltrafast(context, ids)
        AppLogger.info(
            TAG,
            "Codex discovery: catalogue declares ultrafast for ${ids.size} model(s)" +
                if (ids.isEmpty()) "" else ": " + ids.joinToString(","),
        )
    }

    /**
     * [T-codex-discovery-client-version] The discovery request, built exactly
     * as CLIProxyAPI's fetch_codex_models does (dev branch, verified with gh):
     * `client_version` query, `Accept`, `Authorization`, `Originator:
     * codex_cli_rs`, `User-Agent` and, when known, `Chatgpt-Account-Id`.
     *
     * It no longer sends `Version` or `Openai-Beta: responses=experimental`.
     * Those belong to the Responses inference request; CLIProxyAPI's
     * discovery, which does list gpt-6.1-sol, sends neither. Dropping them
     * removes two differences from a known-working request. Whether either
     * header affected the catalog was not verified. The client version
     * itself stays shared with inference (same constant), so the two can
     * still never gate differently.
     */
    internal fun discoveryRequest(accessToken: String, accountId: String?, clientVersion: String): Request.Builder {
        val builder = Request.Builder()
            .url("$MODELS_URL?client_version=$clientVersion")
            .get()
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $accessToken")
            .header("Originator", "codex_cli_rs")
            .header("User-Agent", "codex_cli_rs/$clientVersion (Android; arm64)")
        accountId?.takeIf { it.isNotBlank() }?.let { builder.header("Chatgpt-Account-Id", it) }
        return builder
    }

    /** Drop this account's cached catalog (e.g. on sign-out). */
    fun invalidate(context: Context, accountId: String?, accessToken: String, clientVersion: String) {
        cache.invalidate(context, cacheKey(accountId, accessToken, clientVersion))
    }

    /**
     * Cache partition. **Both** components matter:
     *   * account id — GH#319 requires that switching accounts must not reuse
     *     the other account's catalog, and the catalog is genuinely per-account
     *     (tier gates which models are returned);
     *   * client version — the backend filters by it, so a version bump must
     *     re-fetch rather than serve a list computed under the old gate.
     *
     * Null when there is no account id: an unpartitionable result is not
     * cached at all. Losing a cache entry costs one HTTP call; mixing two
     * accounts' catalogs costs the user's trust in the picker.
     */
    internal fun cacheKey(accountId: String?, accessToken: String, clientVersion: String): String {
        // Prefer the account id: it is stable across token refreshes, so the
        // cache survives them. Fall back to the token itself — verified
        // necessary, not hypothetical: on a real signed-in account here the
        // id_token carried no `chatgpt_account_id`, so an account-only key
        // would have disabled caching outright for that user.
        //
        // Either way the value is one-way hashed by ProviderModelsCache before
        // it becomes a filename, so no credential reaches disk. The token
        // fallback re-fetches once per refresh cycle, which is the correct
        // cost: two accounts can never collide, which is the property that
        // matters.
        val identity = accountId?.takeIf { it.isNotBlank() } ?: "tok:$accessToken"
        return "$identity|$clientVersion"
    }

    /**
     * Parse the discovery payload.
     *
     * Shape (confirmed against the upstream Codex catalog reference): a top
     * level `models` array, with `data` accepted as an alias since the same
     * backend family serves both spellings. Each entry:
     *
     * ```
     * { "slug"|"id", "display_name", "context_window", "max_context_window",
     *   "default_reasoning_level", "supported_reasoning_levels":[{"effort":…}],
     *   "input_modalities":[…], "visibility": "…", "priority": n }
     * ```
     *
     * Every field except the id is optional and defaulted — a discovery route
     * that adds or drops a field must degrade to a usable model, never to a
     * dropped one.
     */
    internal fun parseModels(body: String): List<LLMModel> {
        val root = JSONObject(body)
        val array = root.optJSONArray("models") ?: root.optJSONArray("data") ?: return emptyList()

        // priority ascending (the backend's own ranking), id as the tiebreak so
        // the picker order is stable across refreshes rather than reshuffling
        // whenever the server emits equal priorities in a different order.
        val ranked = mutableListOf<Pair<Int, LLMModel>>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val model = parseEntry(obj) ?: continue
            ranked.add(obj.optInt("priority", Int.MAX_VALUE) to model)
        }
        return ranked
            .sortedWith(compareBy({ it.first }, { it.second.id }))
            .map { it.second }
    }

    private fun parseEntry(obj: JSONObject): LLMModel? {
        val id = obj.optString("slug").ifBlank { obj.optString("id") }.trim()
        if (id.isEmpty()) return null

        // GH#319: "隐藏/不可见的模型条目保持隐藏". The backend marks internal /
        // withdrawn SKUs here; surfacing one puts a model in the picker that
        // errors on first send.
        //
        // [T-codex-discovery-client-version] The live values are `list` and
        // `hide`. The set is shared with iOS parseCodexModels so both
        // platforms hide the same entries (gpt-reserve, codex-auto-review…).
        val visibility = obj.optString("visibility").trim().lowercase()
        if (visibility in HIDDEN_VISIBILITIES) return null

        val contextWindow = obj.optInt("context_window", 0).takeIf { it > 0 }
        val maxOutput = obj.optInt("max_output_tokens", 0).takeIf { it > 0 }

        return LLMModel(
            id = id,
            displayName = obj.optString("display_name").trim().ifEmpty { id },
            provider = "OpenAI",
            contextWindow = contextWindow,
            maxOutputTokens = maxOutput,
            supportsReasoning = parseReasoning(obj),
            inputModalities = obj.optJSONArray("input_modalities")
                ?.toStringList()
                .normalizeModalities(),
            // Deliberately NOT derived. The route describes inputs only; every
            // Codex chat model outputs text, and the one Codex model that
            // outputs images (gpt-image-2*) does not come from this route at
            // all — it is appended by OpenAIModelsApi with its own modalities.
            outputModalities = null,
            reasoningEffortValues = parseEffortTiers(obj),
            serviceTiers = parseServiceTiers(obj),
        )
    }

    /**
     * [T-ultrafast-catalog-tiers] The service tiers this model declares: ids
     * from `service_tiers` ([{id, name, description}], the official field)
     * plus the deprecated `additional_speed_tiers` (plain strings). null when
     * the entry has neither field; an empty list when it has one but it is
     * empty. Lowercased, blanks dropped, first-seen order.
     */
    internal fun parseServiceTiers(obj: JSONObject): List<String>? {
        if (!obj.has("service_tiers") && !obj.has("additional_speed_tiers")) return null
        val out = LinkedHashSet<String>()
        obj.optJSONArray("service_tiers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val id = when (val raw = arr.opt(i)) {
                    is JSONObject -> raw.optString("id")
                    is String -> raw
                    else -> ""
                }.trim().lowercase()
                if (id.isNotEmpty()) out.add(id)
            }
        }
        obj.optJSONArray("additional_speed_tiers")?.toStringList()?.forEach { t ->
            val id = t.trim().lowercase()
            if (id.isNotEmpty()) out.add(id)
        }
        return out.toList()
    }

    /**
     * A model reasons if it declares a default level other than "none", or if
     * any supported level offers a non-"none" effort. Checking BOTH matters:
     * a model can ship with reasoning off by default while still accepting it.
     *
     * Returns null rather than false when the payload says nothing, so
     * [enrichPreservingFresh] can let models.dev answer instead of pinning a
     * fabricated "does not reason" onto a model that does.
     */
    private fun parseReasoning(obj: JSONObject): Boolean? {
        val default = obj.optString("default_reasoning_level").trim().lowercase()
        if (default.isNotEmpty() && default != "none") return true

        val levels = obj.optJSONArray("supported_reasoning_levels")
        var sawLevels = false
        if (levels != null) {
            for (i in 0 until levels.length()) {
                val effort = when (val raw = levels.opt(i)) {
                    is JSONObject -> raw.optString("effort").trim().lowercase()
                    is String -> raw.trim().lowercase()
                    else -> ""
                }
                if (effort.isEmpty()) continue
                sawLevels = true
                if (effort != "none") return true
            }
        }
        // Only an affirmative "the backend listed levels and every one was
        // none" justifies false; silence stays unknown.
        return if (default == "none" || sawLevels) false else null
    }

    /**
     * The effort tiers the picker's Thinking control may offer, in the order
     * the backend listed them. Null when the payload declares none, so
     * enrichment can fill them in from models.dev.
     */
    private fun parseEffortTiers(obj: JSONObject): List<String>? {
        val levels = obj.optJSONArray("supported_reasoning_levels") ?: return null
        val out = LinkedHashSet<String>()
        for (i in 0 until levels.length()) {
            val effort = when (val raw = levels.opt(i)) {
                is JSONObject -> raw.optString("effort").trim().lowercase()
                is String -> raw.trim().lowercase()
                else -> ""
            }
            if (effort.isNotEmpty() && effort != "none") out.add(effort)
        }
        return out.toList().takeIf { it.isNotEmpty() }
    }

    /**
     * Fill gaps from models.dev WITHOUT letting it overwrite what the
     * authoritative route just told us.
     *
     * This is GH#319 requirement 3 ("不要用过期的 enrichment 数据覆盖来自权威端点
     * 的新鲜元数据") and it cannot be met by calling [ModelsDevApi.enrichModels]
     * directly: `applyDevData` resolves every field as `devModel.x ?: model.x`,
     * i.e. models.dev WINS wherever it has an opinion. That is the right rule
     * for a `/v1/models` response, which carries almost no metadata — but here
     * the response is the vendor's own registry for this very account, and a
     * community catalog lagging behind it would silently re-cap a freshly
     * widened context window (the 5.6 family's 272k→1M change is exactly this
     * shape of edit).
     *
     * So: enrich, then restore every field the route actually populated.
     * Fields the route left null keep the enriched value, which is the whole
     * point of still enriching.
     */
    internal fun enrichPreservingFresh(fresh: List<LLMModel>): List<LLMModel> =
        restoreAuthoritative(fresh, ModelsDevApi.enrichModels(fresh))

    /**
     * The restore half of [enrichPreservingFresh], split out so it can be
     * tested for real.
     *
     * Keeping it inline would have made the guard effectively untestable on
     * the JVM: `ModelsDevApi` reads its registry through an Android `Context`,
     * so in a unit test enrichment is a no-op and "authoritative values
     * survived" passes whether or not the guard exists. Taking the enriched
     * list as a parameter lets a test supply an enrichment that genuinely
     * disagrees — which is the only version of this check worth having.
     */
    internal fun restoreAuthoritative(
        fresh: List<LLMModel>,
        enriched: List<LLMModel>,
    ): List<LLMModel> {
        return fresh.zip(enriched) { authoritative, filled ->
            filled.copy(
                // NOT a plain "authoritative wins" for the two capacity
                // numbers — see [maxCapacity]. Everything else is.
                contextWindow = maxCapacity(authoritative.contextWindow, filled.contextWindow),
                maxOutputTokens = maxCapacity(authoritative.maxOutputTokens, filled.maxOutputTokens),
                supportsReasoning = authoritative.supportsReasoning ?: filled.supportsReasoning,
                inputModalities = authoritative.inputModalities ?: filled.inputModalities,
                reasoningEffortValues = authoritative.reasoningEffortValues
                    ?: filled.reasoningEffortValues,
                // [T-ultrafast-catalog-tiers] Only the Codex catalog knows this.
                serviceTiers = authoritative.serviceTiers ?: filled.serviceTiers,
                // displayName: see [preferredName]. Compared against our
                // hand-written label for this id, NOT against `filled` —
                // models.dev enrichment never touches displayName, so
                // `filled.displayName` is just the route's own string and
                // comparing the two would always be a no-op.
                displayName = preferredName(authoritative.id, authoritative.displayName),
            )
        }
    }

    /**
     * Capacity fields take the LARGER of the two, not the authoritative one.
     *
     * Found on-device, not reasoned about: refreshing a real ChatGPT account
     * moved every 5.6/6-family model from a 1,050,000 context window down to
     * 272,000. The backend registry genuinely reports the stale 272,000 —
     * OpenAI widened these SKUs to 1M without updating that field, which the
     * upstream Codex catalog documents and works around the same way (it
     * floors luna/sol/terra at 1M). So "the vendor's own endpoint is
     * authoritative" is true about WHICH models exist and false about how
     * large they are.
     *
     * Max, rather than a hardcoded per-slug floor, because the rule should
     * hold for the next family too: an under-report costs the user usable
     * context, while an over-report is bounded by the server rejecting the
     * request. Neither source is trusted to shrink a number the other one
     * vouches for.
     */
    private fun maxCapacity(fromRoute: Int?, fromCatalog: Int?): Int? = when {
        fromRoute == null -> fromCatalog
        fromCatalog == null -> fromRoute
        else -> maxOf(fromRoute, fromCatalog)
    }

    /**
     * Keep our own display name when the route's differs only in punctuation.
     *
     * Also found on-device: a successful discovery renamed "GPT-6 Astra" to
     * "GPT-6-Astra" and "GPT-5.6 Sol" to "GPT-5.6-Sol". The backend's
     * `display_name` is slug-derived, so for every model we already ship it is
     * strictly worse-looking than the hand-written label — and it silently
     * renamed models the user has been reading for months, in exchange for
     * nothing.
     *
     * The comparison strips separators and case, so this can ONLY suppress a
     * cosmetic difference. A genuinely different name still comes through, and
     * a NEW model — the whole point of this feature, and therefore absent from
     * [builtInNames] — keeps the route's name untouched.
     */
    private fun preferredName(id: String, fromRoute: String): String {
        val builtIn = builtInNames[id] ?: return fromRoute
        return if (cosmeticKey(fromRoute) == cosmeticKey(builtIn)) builtIn else fromRoute
    }

    /**
     * Our hand-written labels, by id. Read from the same list the fallback
     * tier serves, so the two tiers can never disagree about what a model is
     * called — a refresh must not rename anything just because it succeeded.
     */
    private val builtInNames: Map<String, String> by lazy {
        OpenAIModelsApi.fetchModelsOAuth().associate { it.id to it.displayName }
    }

    private fun cosmeticKey(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    private fun JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val s = optString(i, "").trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }
}

// ─── Merged from OpenAIModelsApi.kt ──────────────────────────────────────────────────

object OpenAIModelsApi {
    private const val TAG = "OpenAIModelsApi"
    private val client = OkHttpClient()
    private val cache = ProviderModelsCache("openai")

    /**
     * Static model list for Codex OAuth (tokens can't call /v1/models).
     * Matches iOS `LLMModel.allOpenAICodexOAuth` (Providers/LLMTypes.swift).
     * Order is preserved so the model picker shows the same default rank.
     */
    // T119: every GPT-5.x model on Codex OAuth supports reasoning_effort
    // (`/v1/responses` requires the `reasoning` object on this auth path),
    // so set supportsReasoning = true up front. Without it the Thinking
    // pill in chat is disabled and the user can't pick low/medium/high.
    fun fetchModelsOAuth(): List<LLMModel> = listOf(
        // [T-android-thinking-level-arch] GPT-5.6 family — Codex OAuth only
        // (not in LLMModel.allOpenAI, matching iOS). sol/terra reach ULTRA,
        // luna reaches MAX (see ThinkingLevelCatalog).
        // [T-gpt6-astra] GPT-6 Astra — Codex OAuth only, the current flagship,
        // so it leads the list. Reaches MAX like sol/terra (see
        // ThinkingLevelCatalog); no effort tiers declared here, same pattern
        // as the 5.6 family, so the catalog rule sets the ceiling.
        // [T-gpt6-sol-luna] GPT-6 Sol / Luna — Codex OAuth only, available on
        // every plan (including free). `supportsReasoning` is EXPLICIT and load
        // bearing: neither id is in the bundled models.dev snapshot, so nothing
        // enriches the flag later, and the Responses builder's "Codex OAuth
        // needs a reasoning object" fallback hard-codes effort "low" — the
        // exact data gap GPT6AstraReasoningTest exists to pin.
        LLMModel("gpt-6-sol", "GPT-6 Sol", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-6-luna", "GPT-6 Luna", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-6-astra", "GPT-6 Astra", "OpenAI", supportsReasoning = true),
        // [T-gpt61-sol] GPT-6.1 Sol (OpenAI DevDay 2026-09-29) — Codex OAuth
        // only. Listed right after Astra, the same place iOS puts it
        // (LLMModel.allOpenAICodexOAuth). Data from CLIProxyAPI
        // PR #6207 (its Codex catalog): 272K context, 128K max output, effort
        // tiers low/medium/high/xhigh/max (ceiling set by ThinkingLevelCatalog,
        // not declared here, same pattern as its siblings), Team/Plus/Pro — not
        // assumed on Free. OpenAIProvider.CODEX_CLIENT_VERSION (0.159.0)
        // clears its minimal_client_version of 0.155.0.
        // supportsReasoning is explicit for the same reason as gpt-6-sol: the id
        // is not in the bundled models.dev snapshot. The window is explicit
        // because nothing else supplies one when live discovery is unavailable.
        LLMModel(
            "gpt-6.1-sol", "GPT-6.1 Sol", "OpenAI",
            contextWindow = 272_000,
            maxOutputTokens = 128_000,
            supportsReasoning = true,
        ),
        LLMModel("gpt-5.6-sol", "GPT-5.6 Sol", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.6-terra", "GPT-5.6 Terra", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.6-luna", "GPT-5.6 Luna", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.5", "GPT-5.5", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.4", "GPT-5.4", "OpenAI", supportsReasoning = true),
        // [T-codex-oauth-model-prune] Verified callable on a live
        // ChatGPT-account token (2026-08-01); we had never listed it.
        LLMModel("gpt-5.4-mini", "GPT-5.4 Mini", "OpenAI", supportsReasoning = true),
        // [T-codex-oauth-model-prune] gpt-5.3-codex, gpt-5.3-codex-spark,
        // gpt-5-codex-mini, gpt-5.3, gpt-5.2 and gpt-5 were removed here.
        // The Codex backend answers each with
        //   HTTP 400 {"detail":"The '<id>' model is not supported when using
        //   Codex with a ChatGPT account."}
        // and that error renders as an EMPTY assistant turn, so leaving them in
        // the picker reads to the user as "tool calls are broken" rather than
        // "wrong model". Verified on-device 2026-08-01 against a real Codex
        // OAuth token; the survivors match the set CLIProxyAPI ships for its
        // Codex client (internal/registry/models/codex_client_models.json).
        // Availability is tier-dependent — re-probe before restoring any id,
        // do not add one back from documentation alone. Mirrors iOS
        // LLMModel.allOpenAICodexOAuth.
    ).let {
        AppLogger.info(TAG, "Codex OAuth model list (${it.size} models): ${it.joinToString { m -> m.id }}")
        ModelsDevApi.enrichModels(it)
    } + codexImageModels()

    /**
     * The Codex-OAuth image-generation models.
     *
     * [T-codex-dynamic-discovery GH#319] Extracted from the inline tail of
     * [fetchModelsOAuth] so the live-discovery path can append the same set.
     * Discovery returns CHAT SKUs only — these are not in it — so without a
     * shared accessor a successful discovery would drop three models that do
     * work on this auth path. Same list, one definition, two callers.
     */
    fun codexImageModels(): List<LLMModel> = listOf(
        // [T-codex-gpt-image2-oauth-android] Special image-generation model on
        // the Codex OAuth path. Appended AFTER enrichModels so its declared
        // image input/output modalities survive (models.dev doesn't know it).
        // It does NOT take the normal Chat Completions / Responses path — the
        // gpt-image-2 branch in OpenAIProvider routes it through the Codex
        // image_generation tool. Additive only: the GPT-5.x entries above and
        // their existing OAuth flow are unchanged.
        LLMModel(
            id = "gpt-image-2",
            displayName = "GPT Image 2",
            provider = "OpenAI",
            inputModalities = listOf("text", "image"),
            outputModalities = listOf("image"),
        ),
        // [T-codex-gpt-image25-android] The 2.5 variants. Same Codex OAuth path
        // as gpt-image-2 above and the same reason for sitting after
        // enrichModels — models.dev does not carry them either.
        //
        // What differs is on the wire: gpt-image-2 is whatever the backend
        // picks by default for a bare {type:image_generation} tool, while these
        // two name themselves in that tool object. See
        // OpenAIProvider.buildCodexImageBody.
        LLMModel(
            id = "gpt-image-2.5-sunburst",
            displayName = "GPT Image 2.5 Sunburst",
            provider = "OpenAI",
            inputModalities = listOf("text", "image"),
            outputModalities = listOf("image"),
        ),
        LLMModel(
            id = "gpt-image-2.5-flare",
            displayName = "GPT Image 2.5 Flare",
            provider = "OpenAI",
            inputModalities = listOf("text", "image"),
            outputModalities = listOf("image"),
        ),
    )

    // Chat-capable model prefixes (matching iOS)
    private val chatPrefixes = listOf("gpt-", "o1", "o3", "o4-", "codex-", "chatgpt-")

    // Suffixes to exclude (matching iOS)
    private val excludeSuffixes = listOf(
        "-instruct", "-realtime", "-audio", "-transcribe", "-tts", "-embedding"
    )

    /**
     * [T-android-model-refresh-visible] A model-list fetch and, when the
     * vendor's own list could not be used, why. [models] is what [fetchModels]
     * returns (the built-in list on the official endpoint, EMPTY for a custom
     * base so the caller keeps the user's entries); [error] is set for an HTTP
     * error, a body that is not a model list, a list with no models, or a
     * network failure - each of which used to be silent.
     */
    data class FetchResult(val models: List<LLMModel>, val error: String?)

    suspend fun fetchModels(
        apiKey: String,
        baseURL: String? = null,
        context: Context? = null,
        forceRefresh: Boolean = false,
        // [T-provider-custom-user-agent] Per-provider UA override; null/blank
        // keeps the default UA. Threaded from ProviderRepository.refreshModels.
        customUserAgent: String? = null,
        // [FEAT-261021-16] The instance's Settings > Network tag (prov:<id>);
        // null follows the default route.
        netTag: String? = null,
    ): List<LLMModel> = fetchModelsDetailed(apiKey, baseURL, context, forceRefresh, customUserAgent, netTag).models

    suspend fun fetchModelsDetailed(
        apiKey: String,
        baseURL: String? = null,
        context: Context? = null,
        forceRefresh: Boolean = false,
        customUserAgent: String? = null,
        netTag: String? = null,
    ): FetchResult = withContext(Dispatchers.IO) {
        val isCustomBase = baseURL != null && !isOfficialOpenAI(baseURL)
        // For third-party endpoints (vLLM, Ollama, etc.), return empty on failure
        // so the caller preserves existing models instead of replacing with built-in GPT list.
        val fallback = if (isCustomBase) emptyList() else LLMModel.allOpenAI
        fun fail(why: String) = FetchResult(fallback, why)

        val cacheKey = (baseURL ?: "") + "|" + apiKey
        if (context != null && !forceRefresh) {
            cache.load(context, cacheKey)?.let { return@withContext FetchResult(it, null) }
        }

        val url = buildURL(baseURL)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            // [T-provider-custom-user-agent] models-list UA override.
            .applyUserAgentOverride(customUserAgent)
            .build()

        // A network failure still THROWS, as before: the caller's catch reports
        // it, and on the official endpoint it must not turn into the built-in
        // list replacing the user's entries.
        val (code, body) = com.openminis.app.network.NetworkHttp.client(netTag, client).newCall(request).execute().use { it.code to (it.body?.string() ?: "") }

        if (code !in 200..299) {
            if (context != null && (code == 401 || code == 403)) {
                cache.invalidate(context, cacheKey)
            }
            return@withContext fail("HTTP $code: ${errorSnippet(body)}")
        }

        val data = modelArray(body)
            ?: return@withContext fail("the response is not a model list: ${errorSnippet(body)}")
        val models = try {
            val parsed = mutableListOf<LLMModel>()
            for (i in 0 until data.length()) {
                // An entry may be an object or a bare id string; the id may sit
                // under `id`, `model`, `model_id` or `name` (gateways differ).
                val raw = data.opt(i)
                val obj = raw as? JSONObject ?: JSONObject().put("id", raw?.toString() ?: "")
                val id = listOf("id", "model", "model_id", "name")
                    .firstNotNullOfOrNull { k -> obj.optString(k, "").trim().takeIf { it.isNotEmpty() } }
                    ?: continue

                // Only filter by chat prefixes for official OpenAI endpoints;
                // custom endpoints (vLLM, Ollama) may serve any model ID.
                if (!isCustomBase) {
                    if (!chatPrefixes.any { id.startsWith(it) }) continue
                    if (excludeSuffixes.any { id.contains(it) }) continue
                    if (id.contains(":ft-")) continue
                }

                val displayName = (if (obj.optString("name", "") == id) "" else obj.optString("name", "")).ifBlank { LLMModel.modelDisplayName(id) }
                // Third-party gateways (vLLM, OpenRouter-compat proxies) often
                // report per-model modalities under `architecture.{input,output}_modalities`
                // the same way OpenRouter does — pick them up so vision/audio
                // models are routable without waiting for models.dev enrichment.
                val arch = obj.optJSONObject("architecture")
                // OpenAI / OpenRouter return modalities as `image_input` / `text_output` with
                // suffixes; the rest of the codebase (models.dev, capability fragments,
                // ModelEntryDetailScreen toggles) uses the bare form. Normalize at the parse
                // boundary so persisted overrides round-trip correctly through the toggles.
                val inputModalities = arch?.optJSONArray("input_modalities")?.toStringList().normalizeModalities()
                val outputModalities = arch?.optJSONArray("output_modalities")?.toStringList().normalizeModalities()

                // T119: known reasoning families (GPT-5.x, o-series, Codex
                // Mini) get supportsReasoning pre-set to true so the
                // Thinking pill enables before models.dev enrichment lands
                // — for brand-new ids (e.g. gpt-5.5) the catalog rarely has
                // the `reasoning` flag yet, and without this the pill
                // stays disabled.
                //
                // `gpt-6` is anchored alongside `gpt-5` for exactly the reason
                // 9938d0686 anchored gpt-5 in the first place, one generation
                // later: the Codex OAuth path stamps gpt-6-astra from its static
                // list, but an API-key instance discovering it through
                // /v1/models had no Thinking pill until models.dev catalogued
                // the id.
                val idLower = id.lowercase()
                val knownReasoning = idLower.startsWith("gpt-5") ||
                    idLower.startsWith("gpt-6") ||
                    idLower.startsWith("o1") ||
                    idLower.startsWith("o3") ||
                    idLower.startsWith("o4") ||
                    idLower.contains("codex")

                // [OpenMinis#438] A gateway that reports its own limits is the
                // authority on them; enrichModels below (and any later
                // re-enrichment) only fills what it left out.
                val (reportedContext, reportedOutput) = reportedLimits(obj)
                parsed.add(
                    LLMModel(
                        id = id,
                        displayName = displayName,
                        provider = if (isCustomBase) "Custom" else "OpenAI",
                        contextWindow = reportedContext,
                        maxOutputTokens = reportedOutput,
                        limitsReportedByEndpoint = if (reportedContext != null || reportedOutput != null) true else null,
                        inputModalities = inputModalities,
                        outputModalities = outputModalities,
                        supportsReasoning = if (knownReasoning) true else null,
                    )
                )
            }
            if (parsed.isEmpty()) return@withContext fail("the provider listed no models")
            ModelsDevApi.enrichModels(parsed)
        } catch (e: Exception) {
            return@withContext fail("could not read the model list: ${e.message ?: e.javaClass.simpleName}")
        }

        if (context != null) cache.save(context, cacheKey, models)
        FetchResult(models, null)
    }

    /**
     * [OpenMinis#438] The context window and output limit a /models entry
     * reports about itself, each null when absent. Read in order: top-level
     * `context_length` / `context_window` / `contextWindow` /
     * `max_context_length`, then `limit.context`, then OpenRouter-style
     * `top_provider.context_length`; output likewise from `max_output_tokens`
     * / `maxOutputTokens` / `max_completion_tokens`, `limit.output`,
     * `top_provider.max_completion_tokens`. Numbers and numeric strings are
     * accepted; zero, negative, non-numeric or boolean values count as absent.
     * Same rule as iOS ModelListParsing.reportedLimits.
     */
    internal fun reportedLimits(obj: JSONObject): Pair<Int?, Int?> {
        val limit = obj.optJSONObject("limit")
        val top = obj.optJSONObject("top_provider")
        val context = listOf("context_length", "context_window", "contextWindow", "max_context_length")
            .firstNotNullOfOrNull { positiveInt(obj.opt(it)) }
            ?: positiveInt(limit?.opt("context"))
            ?: positiveInt(top?.opt("context_length"))
        val output = listOf("max_output_tokens", "maxOutputTokens", "max_completion_tokens")
            .firstNotNullOfOrNull { positiveInt(obj.opt(it)) }
            ?: positiveInt(limit?.opt("output"))
            ?: positiveInt(top?.opt("max_completion_tokens"))
        return context to output
    }

    private fun positiveInt(raw: Any?): Int? {
        val value: Long? = when (raw) {
            is Boolean -> null
            is Number -> raw.toDouble().takeIf { it.isFinite() && it >= 1 && it < Int.MAX_VALUE.toDouble() }?.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        }
        return value?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
    }

    /**
     * [T-android-model-refresh-visible] The model array of a /models body, in
     * the shapes OpenAI-compatible gateways actually send: `{"data":[...]}`
     * (standard), `{"models":[...]}`, `{"items"|"results"|"result":[...]}`,
     * `{"data":{"models"|"data":[...]}}`, or a bare `[...]`. Null when the body
     * holds none of them (an error object in a 200, HTML, empty).
     */
    internal fun modelArray(body: String): JSONArray? {
        val text = body.trim()
        if (text.startsWith("[")) return runCatching { JSONArray(text) }.getOrNull()
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val keys = listOf("data", "models", "items", "results", "result")
        for (k in keys) json.optJSONArray(k)?.let { return it }
        for (k in keys) {
            val inner = json.optJSONObject(k) ?: continue
            for (k2 in keys) inner.optJSONArray(k2)?.let { return it }
        }
        return null
    }

    /** First 200 characters of an error body, single line, for a visible message. */
    internal fun errorSnippet(body: String): String {
        val json = runCatching { JSONObject(body.trim()) }.getOrNull()
        val message = json?.optJSONObject("error")?.optString("message", "")?.takeIf { it.isNotBlank() }
            ?: json?.optString("message", "")?.takeIf { it.isNotBlank() }
            ?: json?.optString("error", "")?.takeIf { it.isNotBlank() && !it.startsWith("{") }
        return (message ?: body).replace(Regex("\\s+"), " ").trim().take(200).ifEmpty { "(empty body)" }
    }

    private fun JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val s = optString(i, "")
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    /** Check if a base URL points to official OpenAI endpoints. */
    private fun isOfficialOpenAI(baseURL: String): Boolean {
        val lower = baseURL.lowercase()
        return lower.contains("api.openai.com") || lower.contains("chatgpt.com")
    }

    private fun buildURL(baseURL: String?): String {
        if (baseURL == null) return "https://api.openai.com/v1/models"
        // baseURL is ProviderConfig.effectiveBaseURL — it already has /v1 iff the
        // appendV1Suffix toggle is on. Only append /models; never force /v1, or the
        // toggle is overridden and services without /v1 return 404.
        val base = baseURL.trimEnd('/')
        return "$base/models"
    }
}

// ─── Merged from ReasoningDetails.kt ──────────────────────────────────────────────────

/**
 * [T-android-openrouter-reasoning-details] Reads OpenRouter's structured
 * `delta.reasoning_details` array (iOS 8d17198d6, GH#263).
 *
 * The chat-completions parser only read STRING reasoning fields
 * (`reasoning_content`, `reasoning`, `reasoning_text`). OpenRouter also sends
 *
 *   "reasoning_details":[{"type":"reasoning.text","text":"…"},
 *                        {"type":"reasoning.summary","summary":"…"},
 *                        {"type":"reasoning.encrypted","data":"…"}]
 *
 * and for some models ONLY that. A model that spent its budget reasoning this
 * way ended the turn with text empty AND reasoning empty, which the agent loop
 * reports as "Model returned an empty response".
 */
internal object ReasoningDetails {

    /** What one chunk's `reasoning_details` contributed. */
    data class Parsed(
        /** Concatenated text of the `reasoning.text` / `reasoning.summary` items, in order. */
        val text: String,
        /** Opaque `reasoning.encrypted` items: no text, but the model did reason. */
        val encryptedCount: Int,
        /** True when at least one known item was present (even empty or encrypted). */
        val sawReasoning: Boolean,
    )

    private val STRING_FIELDS = listOf("reasoning_content", "reasoning", "reasoning_text")

    /**
     * Parses [delta]'s `reasoning_details`, or returns null when there is
     * nothing to read.
     *
     * Also null when the same chunk already carried NON-EMPTY string
     * reasoning: OpenRouter then sends the same text twice (string + details),
     * and reading both would double every sentence. An empty string field does
     * not count, so it cannot suppress real details.
     */
    fun parse(delta: JSONObject): Parsed? {
        val details = delta.optJSONArray("reasoning_details") ?: return null
        val chunkHadStringReasoning = STRING_FIELDS.any {
            (delta.opt(it) as? String).orEmpty().isNotEmpty()
        }
        if (chunkHadStringReasoning) return null

        val text = StringBuilder()
        var encrypted = 0
        var saw = false
        for (i in 0 until details.length()) {
            val item = details.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "reasoning.text" -> { saw = true; text.append(stringOrEmpty(item, "text")) }
                "reasoning.summary" -> { saw = true; text.append(stringOrEmpty(item, "summary")) }
                "reasoning.encrypted" -> { saw = true; encrypted++ }
                // Unknown future types are ignored rather than guessed at.
            }
        }
        if (!saw) return null
        return Parsed(text.toString(), encrypted, saw)
    }

    // optString would turn a JSON null into the literal "null".
    private fun stringOrEmpty(o: JSONObject, key: String): String = (o.opt(key) as? String).orEmpty()
}

// ─── Merged from ResponsesToolPairing.kt ──────────────────────────────────────────────────

/**
 * [T-android-responses-orphan-tool-output] Last gate before a Responses
 * request goes out: every `function_call_output` must answer a
 * `function_call` with the same `call_id` in the array, at most once, and every
 * `function_call` except the trailing run must be answered. Port of iOS
 * `sanitizeResponsesToolPairing` (bc142f750, first-output-wins from ffbed35ca).
 *
 * Why it is needed: the Responses builder emitted `function_call_output`
 * unconditionally. An upstream answers a stranded one with
 *     No tool call found for function call output with call_id call_… .
 * and, because the request is rebuilt from the same history every time, the
 * 400 repeated on every retry and every fallback model until the chat was
 * cleared (the iOS field report). ChatViewModel's history repair catches most
 * orphans one layer up, but it works on stored ids while this sees exactly the
 * ids the request carries (split on "|" and capped), and it cannot see every
 * source of a stranded or duplicated result (a retry, resume or restore that
 * left an extra result row).
 *
 * Repair, same rules as iOS:
 *  - an output whose call is not in the request is DROPPED - nothing can
 *    reconstruct the call;
 *  - a second output for the same call_id is DROPPED - the first one wins, as
 *    in the Chat Completions path;
 *  - an unanswered call gets a placeholder output instead of being deleted,
 *    which would silently discard the assistant's own turn. The TRAILING run of
 *    calls is exempt: a request ending on function_call is what the API expects
 *    mid-round, and a placeholder there would tell the model a tool it is about
 *    to run has already failed.
 *
 * Placeholders are buffered until the run of function_call items closes and
 * land before the first following item, so parallel calls stay
 * `function_call ×N, function_call_output ×N` - an output in the middle of the
 * call run, or a tool-image carrier between outputs, is itself a 400
 * ([T-android-responses-toolresult-image-split]). Order is otherwise kept as
 * built; no items are moved.
 */
internal object ResponsesToolPairing {

    const val PLACEHOLDER_OUTPUT =
        "Tool execution result is unavailable (history was truncated or interrupted)."

    class Result(
        val items: JSONArray,
        val droppedOrphanOutputs: List<String>,
        val droppedDuplicateOutputs: List<String>,
        val placeholderCalls: List<String>,
    ) {
        val changed: Boolean
            get() = droppedOrphanOutputs.isNotEmpty() || droppedDuplicateOutputs.isNotEmpty() ||
                placeholderCalls.isNotEmpty()
    }

    fun sanitize(items: JSONArray): Result {
        val callIds = HashSet<String>()
        val outputCounts = HashMap<String, Int>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "function_call" -> callIds.add(item.optString("call_id"))
                "function_call_output" -> {
                    val id = item.optString("call_id")
                    outputCounts[id] = (outputCounts[id] ?: 0) + 1
                }
            }
        }

        // Calls at the very end are waiting for their results, not orphaned.
        val trailing = HashSet<String>()
        for (i in items.length() - 1 downTo 0) {
            val item = items.optJSONObject(i) ?: break
            when (item.optString("type")) {
                "function_call" -> trailing.add(item.optString("call_id"))
                "reasoning" -> {}
                else -> break
            }
        }

        val orphanOutputs = outputCounts.keys - callIds
        val duplicated = outputCounts.filter { (id, n) -> n > 1 && id in callIds }.keys
        val unanswered = callIds - outputCounts.keys - trailing
        if (orphanOutputs.isEmpty() && duplicated.isEmpty() && unanswered.isEmpty()) {
            return Result(items, emptyList(), emptyList(), emptyList())
        }

        val out = JSONArray()
        val seenOutputs = HashSet<String>()
        val droppedOrphans = mutableListOf<String>()
        val droppedDuplicates = mutableListOf<String>()
        val placeholders = mutableListOf<String>()
        val pending = mutableListOf<JSONObject>()
        fun flush() {
            for (p in pending) out.put(p)
            pending.clear()
        }
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i)
            if (item == null) {
                out.put(items.get(i))
                continue
            }
            val type = item.optString("type")
            if (type == "function_call_output") {
                val id = item.optString("call_id")
                if (id in orphanOutputs) { droppedOrphans += id; continue }
                if (!seenOutputs.add(id)) { droppedDuplicates += id; continue }
            }
            // A real output closes the call run just as a placeholder does, so
            // pending placeholders go BEFORE it: one contiguous output block.
            if (type != "function_call") flush()
            out.put(item)
            if (type == "function_call") {
                val id = item.optString("call_id")
                if (id in unanswered) {
                    placeholders += id
                    pending += JSONObject().apply {
                        put("type", "function_call_output")
                        put("call_id", id)
                        put("output", PLACEHOLDER_OUTPUT)
                    }
                }
            }
        }
        flush()
        return Result(out, droppedOrphans, droppedDuplicates, placeholders)
    }
}

// ─── Merged from ThinkPrefixStreamParser.kt ──────────────────────────────────────────────────

/**
 * [T-android-think-prefix-stream] Splits a streamed `content` field into live
 * thinking deltas and visible body text, for models that embed reasoning as a
 * `<think>…</think>` PREFIX of `content` instead of using the
 * `reasoning_content` field (MiniMax M3, some Qwen/DeepSeek deployments).
 *
 * Port of iOS `ThinkPrefixStreamParser` (22ca4285). Replaces the previous
 * `extractThinkTags` scanner, which had two defects reproduced on Android:
 *
 *  1. **Leading whitespace leaked into the body.** M3 always emits "\n\n" after
 *     `</think>`; the old scanner passed it straight through, so every such
 *     message body began with a blank line. A think-only tool turn could also
 *     leave a whitespace-only text block, which renders as a blank band because
 *     every empty-block guard checks `isEmpty()` and "\n\n" passes that.
 *  2. **Mid-text tags were stripped anywhere.** The old scanner searched for
 *     `<think>` at ANY offset, so a message merely *explaining* the tag —
 *     "Use the `<think>` tag to mark reasoning" — had its prose silently
 *     swallowed into the thinking bubble. Verified against the old
 *     implementation: `"Use the <think> tag to mark reasoning."` produced
 *     visible `"Use the "` and thinking `" tag to mark re"`.
 *
 * Design: an explicit `UNDECIDED → THINKING → BODY` state machine.
 *  - Only a `<think>` at the very START of a turn (leading whitespace tolerated
 *    and dropped) enters THINKING. Anything else commits the turn to BODY, and
 *    from then on tags are passed through verbatim.
 *  - After `</think>`, leading whitespace is dropped, and a trailing-whitespace
 *    run is withheld until proven interior — so a think-only turn ends with an
 *    empty body rather than "\n\n".
 *
 * Not thread-safe; one instance per streaming turn (the provider creates one
 * per request).
 */
internal class ThinkPrefixStreamParser {

    private enum class State { UNDECIDED, THINKING, BODY }

    /** One chunk's worth of split output. Either side may be empty. */
    data class Output(val visible: String, val thinking: String)

    private var state = State.UNDECIDED

    /**
     * Holds bytes we cannot classify yet: a partial `<think>`/`</think>` tag
     * split across chunks, or leading whitespace before we know whether a
     * `<think>` follows.
     */
    private val pending = StringBuilder()

    /**
     * Trailing whitespace in BODY state, withheld until we see a non-space
     * character after it (proving it is interior). Dropped at [finishTurn].
     */
    private val heldWhitespace = StringBuilder()

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }

    /** Feed one streamed `content` delta. */
    fun feed(text: String): Output {
        if (text.isEmpty()) return Output("", "")
        val visible = StringBuilder()
        val thinking = StringBuilder()
        pending.append(text)

        loop@ while (pending.isNotEmpty()) {
            when (state) {
                State.UNDECIDED -> {
                    // Tolerate (and drop) leading whitespace before a <think>.
                    val firstNonSpace = pending.indexOfFirst { !it.isWhitespace() }
                    if (firstNonSpace < 0) {
                        // All whitespace so far — can't decide yet. Keep it: if a
                        // <think> follows we drop it, otherwise it belongs to the body.
                        break@loop
                    }
                    val rest = pending.substring(firstNonSpace)
                    if (OPEN.startsWith(rest.take(OPEN.length))) {
                        // Could still become "<think>" once more bytes arrive.
                        if (rest.length < OPEN.length) break@loop
                    }
                    if (rest.startsWith(OPEN)) {
                        state = State.THINKING
                        // Drop the tolerated leading whitespace AND the tag.
                        pending.delete(0, firstNonSpace + OPEN.length)
                    } else {
                        // No think prefix — the whole turn is body, tags and all.
                        state = State.BODY
                        // Leading whitespace here is genuine body content.
                    }
                }

                State.THINKING -> {
                    val closeIdx = pending.indexOf(CLOSE)
                    if (closeIdx < 0) {
                        // Emit everything except a possible partial closing tag.
                        val safe = safeEmitLength(pending, CLOSE)
                        if (safe <= 0) break@loop
                        thinking.append(pending, 0, safe)
                        pending.delete(0, safe)
                        break@loop
                    }
                    thinking.append(pending, 0, closeIdx)
                    pending.delete(0, closeIdx + CLOSE.length)
                    state = State.BODY
                    // Drop the whitespace M3 emits right after </think>.
                    while (pending.isNotEmpty() && pending[0].isWhitespace()) pending.deleteCharAt(0)
                }

                State.BODY -> {
                    // Withhold a trailing whitespace run until proven interior, so
                    // a think-only turn doesn't end with a whitespace-only block.
                    var cut = pending.length
                    while (cut > 0 && pending[cut - 1].isWhitespace()) cut--
                    if (cut > 0) {
                        // Anything held from earlier is now interior — release it.
                        visible.append(heldWhitespace)
                        heldWhitespace.setLength(0)
                        visible.append(pending, 0, cut)
                    }
                    heldWhitespace.append(pending, cut, pending.length)
                    pending.setLength(0)
                    break@loop
                }
            }
        }
        return Output(visible.toString(), thinking.toString())
    }

    /**
     * Flush whatever is still buffered at end of turn. Idempotent — the caller
     * may invoke it on both `finish_reason` and `[DONE]`.
     *
     * Withheld trailing whitespace is intentionally DROPPED, not emitted: that
     * is the whole point of holding it.
     */
    fun finishTurn(): Output {
        val visible = StringBuilder()
        val thinking = StringBuilder()
        if (pending.isNotEmpty()) {
            when (state) {
                // An unterminated <think> — treat the remainder as thinking
                // rather than dumping raw reasoning into the body.
                State.THINKING -> thinking.append(pending)
                // Undecided at end of turn means no think prefix ever arrived,
                // so the buffered bytes are ordinary body text.
                State.UNDECIDED, State.BODY -> visible.append(pending)
            }
            pending.setLength(0)
        }
        heldWhitespace.setLength(0)
        return Output(visible.toString(), thinking.toString())
    }

    /**
     * Flush a short UNDECIDED buffer before a tool boundary, so the ViewModel's
     * pre-tool snapshot isn't missing text. Cross-chunk tag tails and withheld
     * whitespace stay buffered. Mirrors iOS `resolveAtToolBoundary()`.
     */
    fun resolveAtToolBoundary(): Output {
        if (state != State.UNDECIDED || pending.isEmpty()) return Output("", "")
        // If the buffer could still grow into "<think>", leave it alone.
        val rest = pending.trimStart()
        if (rest.isNotEmpty() && OPEN.startsWith(rest.take(OPEN.length)) && rest.length < OPEN.length) {
            return Output("", "")
        }
        state = State.BODY
        val out = pending.toString()
        pending.setLength(0)
        return Output(out, "")
    }

    /**
     * How many chars are safe to emit without splitting a potential [tag]
     * occurrence. Keeps the longest suffix of [buf] that is a proper prefix of
     * [tag] buffered.
     */
    private fun safeEmitLength(buf: StringBuilder, tag: String): Int {
        val maxKeep = minOf(tag.length - 1, buf.length)
        for (keep in maxKeep downTo 1) {
            val suffixStart = buf.length - keep
            var matches = true
            for (k in 0 until keep) {
                if (buf[suffixStart + k] != tag[k]) { matches = false; break }
            }
            if (matches) return suffixStart
        }
        return buf.length
    }
}

private inline fun CharSequence.indexOfFirst(predicate: (Char) -> Boolean): Int {
    for (i in indices) if (predicate(this[i])) return i
    return -1
}
