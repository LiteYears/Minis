package com.openminis.app.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.openminis.app.logging.AppLogger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Antigravity OAuth manager — mirrors iOS `AntigravityOAuthManager`.
 *
 * Antigravity is Google's experimental "Cloud Code Assist" proxy that routes
 * Claude / Gemini / GPT models through a Gemini-format request envelope. It
 * reuses Google's OAuth endpoints but with its own client_id + client_secret
 * and two extra scopes (`cclog`, `experimentsandconfigs`), and runs a local
 * HTTP callback on port 8086 (distinct from Gemini's 8085 so the two can
 * coexist).
 *
 * **Scaffolding only** — this file captures the OAuth constants and project
 * discovery flow so the provider layer can consume them. Actual request
 * dispatch (the `AntigravityProvider` + `AntigravityAgentProvider` pair from
 * iOS) is not wired yet; when it lands it will reuse
 * [activeBaseURL] + [gcpProjectId] persisted here.
 */
class AntigravityOAuthManager(context: Context, instanceId: String) : OAuthManager(context, instanceId) {
    companion object {
        private const val TAG = "AntigravityOAuth"
        private const val USER_AGENT = "antigravity/1.107.0 android/aarch64"

        /**
         * Candidate Cloud Code Assist base URLs, probed in order on login.
         * `daily-cloudcode-pa` is the preview / daily release channel; the
         * public `cloudcode-pa` is the fallback. Matches iOS
         * `AntigravityOAuthManager.cloudCodeBaseURLs`.
         */
        val cloudCodeBaseURLs = listOf(
            "https://daily-cloudcode-pa.sandbox.googleapis.com",
            "https://cloudcode-pa.googleapis.com",
        )
    }

    override val authURL = "https://accounts.google.com/o/oauth2/v2/auth"
    override val tokenURL = "https://oauth2.googleapis.com/token"
    override val clientId = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
    // Placeholder. Google OAuth requires a client secret alongside the
    // client id; supply your own from Google Cloud Console to use this
    // sign-in. API-key providers are unaffected.
    override val clientSecret = "GOCSPX-xxxxxxxxxxxxxxxxxxxxxxxxxxx"
    override val callbackPort = 8086
    override val redirectPath = "/oauth2callback"
    override val scopes = listOf(
        "https://www.googleapis.com/auth/cloud-platform",
        "https://www.googleapis.com/auth/userinfo.email",
        "https://www.googleapis.com/auth/userinfo.profile",
        "https://www.googleapis.com/auth/cclog",
        "https://www.googleapis.com/auth/experimentsandconfigs",
    ).joinToString(" ")

    override fun buildAuthorizationUrl(): String {
        val (_, challenge) = generateHexPKCE()
        val state = generateState()
        return "$authURL?" + listOf(
            "client_id=$clientId",
            "redirect_uri=${Uri.encode(redirectUri)}",
            "response_type=code",
            "scope=${Uri.encode(scopes)}",
            "state=$state",
            "code_challenge=$challenge",
            "code_challenge_method=S256",
            "access_type=offline",
            "prompt=consent",
        ).joinToString("&")
    }

    /** Cached user email from Google userinfo. */
    var email: String?
        get() = loadOAuthString("email")
        private set(value) { value?.let { saveOAuthString("email", it) } }

    /** GCP project ID discovered via Cloud Code Assist loadCodeAssist. */
    var gcpProjectId: String?
        get() = loadOAuthString("gcp_project")
        private set(value) { value?.let { saveOAuthString("gcp_project", it) } }

    /**
     * Cloud Code Assist base URL that successfully returned the project on
     * login. Subsequent requests pin to this URL so the provider doesn't
     * re-probe the daily/fallback pair on every call.
     */
    var activeBaseURL: String?
        get() = loadOAuthString("base_url")
        private set(value) { value?.let { saveOAuthString("base_url", it) } }

    override suspend fun onTokensReceived(json: JSONObject) {
        fetchUserEmail()
        discoverProjectIfNeeded()
    }

    private suspend fun fetchUserEmail() = withContext(Dispatchers.IO) {
        val token = validAccessToken() ?: return@withContext
        try {
            // [FEAT-261021-16] Routed by the instance's tag; unrouted it is the same HttpURLConnection request.
            com.openminis.app.network.NetworkUrlRequest.open(netTag, "https://www.googleapis.com/oauth2/v1/userinfo",
                headers = mapOf("Authorization" to "Bearer $token")).use { r ->
                if (r.code == 200) email = JSONObject(r.text()).optString("email")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch email", e)
        }
    }

    /**
     * Probe each candidate Cloud Code Assist base URL in [cloudCodeBaseURLs]
     * until one returns a project ID. Persists the winning URL in
     * [activeBaseURL] so the provider avoids re-probing.
     */
    suspend fun discoverProjectIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        if (!gcpProjectId.isNullOrEmpty() && !activeBaseURL.isNullOrEmpty()) return@withContext true
        val token = validAccessToken() ?: return@withContext false

        for (baseURL in cloudCodeBaseURLs) {
            val projectId = loadCodeAssist(token, baseURL)
            if (!projectId.isNullOrEmpty()) {
                gcpProjectId = projectId
                activeBaseURL = baseURL
                Log.i(TAG, "Antigravity project discovered via $baseURL")
                return@withContext true
            }
        }
        Log.w(TAG, "Antigravity project discovery failed on all base URLs")
        false
    }

    private fun loadCodeAssist(token: String, baseURL: String): String? {
        return try {
            val req = Request.Builder()
                .url("$baseURL/v1internal:loadCodeAssist")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $token")
                .header("User-Agent", USER_AGENT)
                .header("X-Client-Name", "antigravity")
                .header("X-Client-Version", "1.107.0")
                .build()
            val response = httpClient.newCall(req).execute()
            val body = if (response.isSuccessful) response.body?.string() else null
            response.close()
            body?.let { JSONObject(it).optString("gcpProjectId").ifEmpty { null } }
        } catch (e: Exception) {
            Log.d(TAG, "loadCodeAssist failed on $baseURL: ${e.message}")
            null
        }
    }
}

// ─── Merged from ClaudeCliMimicryHeaders.kt ──────────────────────────────────────────────────

/**
 * The Claude CLI client fingerprint, applied to Anthropic OAuth *auth* requests
 * (token exchange and refresh).
 *
 * [OpenMinis#360] Claude OAuth login and silent refresh were being served a
 * Cloudflare interstitial ("Just a moment...", HTTP 403 with an HTML body)
 * instead of JSON. Two causes, both on the auth path only:
 *
 *  1. The token endpoint had moved. `console.anthropic.com/v1/oauth/token` is
 *     the old host; the CLI now posts to `claude.ai/v1/oauth/token`.
 *  2. These requests carried no client fingerprint at all. The chat path in
 *     `AnthropicProvider` has sent the headers below since OAuth landed, so
 *     chat traffic looked like the CLI while the token requests did not —
 *     which is what Cloudflare's bot management scored as a non-official
 *     client. The failure was invisible in review because each path built its
 *     own request and nothing tied them together.
 *
 * Deliberately NOT included here: `anthropic-beta`. That header advertises
 * message-API capabilities (prompt caching, interleaved thinking, …) and is
 * meaningless on a token request; the real CLI does not send it there either,
 * so adding it would widen the gap between what we advertise and what we send
 * rather than close it.
 *
 * **Keep in lockstep with `AnthropicProvider.buildHeaders`'s `isOAuth` block**
 * — the values are one registered client identity, and a fingerprint that
 * matches on one path but not the other is the exact shape of this bug.
 * `ClaudeCliMimicryHeadersTest` fails if the two drift apart.
 *
 * Mirrors the iOS set in `OAuthHTTPClient`'s interceptor.
 */
object ClaudeCliMimicryHeaders {

    /**
     * Header name → value, in the order the chat path sends them.
     *
     * Values are pinned on purpose. `User-Agent` is version-gated by the
     * backend (below claude-cli/2.1.251 a Fable 5.1 request is refused
     * outright, and below 2.1.280 an Opus 5.5 one is; the floor only rises)
     * outright), while the `X-Stainless-*` values describe the TypeScript SDK
     * and Node runtime rather than the CLI, and move on their own cadence.
     * Bumping either speculatively is how a working fingerprint gets broken.
     */
    val ALL: List<Pair<String, String>> = listOf(
        "User-Agent" to "claude-cli/2.1.280 (external, cli)",
        "X-Stainless-Lang" to "js",
        "X-Stainless-Package-Version" to "0.106.0",
        "X-Stainless-OS" to "Linux",
        "X-Stainless-Arch" to "arm64",
        "X-Stainless-Runtime" to "node",
        "X-Stainless-Runtime-Version" to "v24.18.0",
        "X-Stainless-Retry-Count" to "0",
        "X-Stainless-Timeout" to "600",
        "X-App" to "cli",
        "Anthropic-Dangerous-Direct-Browser-Access" to "true",
    )
}

/**
 * Applies the full Claude CLI fingerprint to an OAuth token request.
 *
 * Every Anthropic auth request must go through this — see
 * [ClaudeCliMimicryHeaders] for why a partial or absent fingerprint gets the
 * request challenged by Cloudflare instead of answered.
 */
fun Request.Builder.applyClaudeCliMimicryHeaders(): Request.Builder {
    for ((name, value) in ClaudeCliMimicryHeaders.ALL) {
        header(name, value)
    }
    return this
}

// ─── Merged from CopilotDeviceFlow.kt ──────────────────────────────────────────────────

/**
 * [T-copilot-provider] GitHub Copilot auth protocol — pure request/response
 * shaping and classification. No network, no Android dependencies beyond
 * org.json, so the whole decision surface is unit-testable.
 *
 * ⚠️ UNOFFICIAL INTEGRATION. This drives Copilot through the same endpoints
 * the VS Code extension uses, with a borrowed client id and a spoofed editor
 * User-Agent. GitHub's Terms list proxying Copilot as grounds for account
 * restriction. It ships disabled by default and behind an explicit risk
 * notice — see [CopilotFeatureFlag] and `copilot_risk_notice` in strings.
 *
 * Three stages, and the middle one is what makes this different from every
 * other OAuth provider here:
 *
 *  1. RFC 8628 device flow against github.com → a LONG-LIVED account token
 *     (`gho_…`). Same shape as [KimiDeviceFlow], reused deliberately.
 *  2. Exchange that token for a SHORT-LIVED Copilot session token (~25 min)
 *     at api.github.com. This tier does not exist for Kimi; it is why the
 *     provider cannot simply hand its account token to the request layer.
 *  3. Chat Completions at api.githubcopilot.com with the session token —
 *     wire-compatible with OpenAI, which is why the existing OpenAI provider
 *     can carry it once the headers are injected.
 */
object CopilotDeviceFlow {

    // ── Stage 1: GitHub device flow ──────────────────────────────────────

    const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"

    /** Only `read:user` is needed — the Copilot token exchange derives the
     *  rest. Requesting more would be a broader grant than the feature uses. */
    const val SCOPE = "read:user"

    /**
     * Client id published by the VS Code Copilot Chat extension and used by
     * the open-source clients this integration follows (ericc-ch/copilot-api).
     * NOT ours, and not something GitHub issued to this app.
     *
     * If GitHub rotates or revokes it, stage 1 starts returning
     * `unauthorized_client` and login stops working — the failure is loud and
     * confined to this provider, which is the reason it is a single constant
     * rather than something threaded through the codebase. The alternative id
     * `Ov23li8tweQw6odWQebz` (sst/opencode) is the documented fallback.
     */
    const val CLIENT_ID = "Iv1.b507a08c87ecfe98"

    // ── Stage 2: Copilot session token exchange ──────────────────────────

    const val COPILOT_TOKEN_URL = "https://api.github.com/copilot_internal/v2/token"

    // ── Stage 3: Chat ────────────────────────────────────────────────────

    const val API_BASE = "https://api.githubcopilot.com"

    /**
     * Editor identity headers. Every one of these is a claim about being VS
     * Code, and the server rejects requests that omit them — so they are
     * version numbers we have to keep plausible, not decoration.
     *
     * Collected here (not inlined at call sites) precisely because they go
     * stale: when Copilot starts refusing requests, this block is the first
     * and only place to bump.
     */
    // [T-android-copilot-parity] Kept in step with iOS CopilotConstants: the
    // two platforms claim the same editor identity, so a version Copilot starts
    // rejecting fails (and gets bumped) on both at once rather than one
    // platform silently aging out.
    const val EDITOR_VERSION = "vscode/1.104.0"
    const val EDITOR_PLUGIN_VERSION = "copilot-chat/0.31.0"
    const val USER_AGENT = "GitHubCopilotChat/0.31.0"
    const val API_VERSION = "2025-04-01"
    const val INTEGRATION_ID = "vscode-chat"

    /**
     * Headers for the stage-2 exchange. Note the `token` scheme — NOT
     * `Bearer`. api.github.com rejects a Bearer-prefixed account token here,
     * which is an easy and silent mistake to make given stage 3 does use
     * Bearer.
     */
    fun tokenExchangeHeaders(githubToken: String): Map<String, String> = mapOf(
        "Authorization" to "token $githubToken",
        "User-Agent" to USER_AGENT,
        "Editor-Version" to EDITOR_VERSION,
        "Editor-Plugin-Version" to EDITOR_PLUGIN_VERSION,
        "X-GitHub-Api-Version" to API_VERSION,
        "Accept" to "application/json",
    )

    /**
     * Headers for stage-3 chat requests, minus Authorization (the provider
     * layer supplies that from the session token so it is always current).
     *
     * @param isAgentInitiated last message came from the assistant/tool loop
     *   rather than the user — GitHub distinguishes the two via X-Initiator.
     * @param hasImages adds the vision opt-in; sending it unconditionally
     *   would claim vision on text-only requests.
     */
    fun chatHeaders(
        isAgentInitiated: Boolean,
        hasImages: Boolean,
        requestId: String,
    ): Map<String, String> = buildMap {
        put("Copilot-Integration-Id", INTEGRATION_ID)
        put("Editor-Version", EDITOR_VERSION)
        put("Editor-Plugin-Version", EDITOR_PLUGIN_VERSION)
        put("User-Agent", USER_AGENT)
        put("Openai-Intent", "conversation-panel")
        put("X-GitHub-Api-Version", API_VERSION)
        put("X-Initiator", if (isAgentInitiated) "agent" else "user")
        put("X-Request-Id", requestId)
        if (hasImages) put("Copilot-Vision-Request", "true")
    }

    /**
     * [T-android-copilot-per-request-headers] The editor-identity headers that
     * are the SAME on every request, so they can sit on the provider once.
     *
     * Split out from [chatHeaders] because the two halves have different
     * lifetimes: this half is a constant claim about being VS Code, while
     * X-Initiator / X-Request-Id / the vision flag describe one request and are
     * wrong if pinned (see [perRequestHeaders]).
     */
    fun staticChatHeaders(): Map<String, String> = mapOf(
        "Copilot-Integration-Id" to INTEGRATION_ID,
        "Editor-Version" to EDITOR_VERSION,
        "Editor-Plugin-Version" to EDITOR_PLUGIN_VERSION,
        "User-Agent" to USER_AGENT,
        "Openai-Intent" to "conversation-panel",
        "X-GitHub-Api-Version" to API_VERSION,
    )

    /**
     * [T-android-copilot-per-request-headers] Derive the per-request headers
     * from the outgoing body — the bridge [chatHeaders] was missing.
     *
     * `chatHeaders` was correct and documented, but the only caller pinned
     * `isAgentInitiated = false, hasImages = false` when the provider was
     * BUILT, so every request claimed to be human-initiated and no request
     * ever carried the vision flag. Mislabelling agent traffic as human is the
     * specific behaviour GitHub acts on, so erring toward `agent` is the safe
     * direction. `X-Request-Id` was likewise frozen: one UUID identified an
     * instance, not a request.
     *
     * Derivation, from the only thing a request builder can see:
     * - **agent turn** — the last message has `role: "tool"`, i.e. the loop is
     *   feeding tool results back rather than a person having typed.
     * - **images** — any content part typed `image_url` (chat completions) or
     *   `input_image` (responses API). Both shapes are checked because Copilot
     *   rides whichever builder the model selects.
     *
     * Mirrors iOS `CopilotConstants.perRequestHeaders(forBody:)`.
     */
    fun perRequestHeaders(body: JSONObject): Map<String, String> {
        val messages = body.optJSONArray("messages")
            ?: body.optJSONArray("input")

        var isAgentInitiated = false
        var hasImages = false

        if (messages != null && messages.length() > 0) {
            isAgentInitiated =
                messages.optJSONObject(messages.length() - 1)?.optString("role") == "tool"

            outer@ for (i in 0 until messages.length()) {
                val parts = messages.optJSONObject(i)?.optJSONArray("content") ?: continue
                for (j in 0 until parts.length()) {
                    val type = parts.optJSONObject(j)?.optString("type")
                    if (type == "image_url" || type == "input_image") {
                        hasImages = true
                        break@outer
                    }
                }
            }
        }

        // Only the headers that actually vary. The static editor identity is
        // already on the provider's extraHeaders; re-sending identical values
        // on every request would be noise.
        return buildMap {
            put("X-Initiator", if (isAgentInitiated) "agent" else "user")
            put("X-Request-Id", java.util.UUID.randomUUID().toString())
            if (hasImages) put("Copilot-Vision-Request", "true")
        }
    }

    // ── Stage 1 parsing ──────────────────────────────────────────────────

    data class DeviceAuthorization(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresInSeconds: Long,
        val intervalSeconds: Long,
    )

    /**
     * Parse the device/code response. Returns null when a REQUIRED field is
     * missing so the caller can surface a provider error rather than starting
     * a poll loop that can never succeed.
     */
    fun parseDeviceAuthorization(json: JSONObject): DeviceAuthorization? {
        val deviceCode = json.optString("device_code", "").ifEmpty { return null }
        val userCode = json.optString("user_code", "").ifEmpty { return null }
        val uri = json.optString("verification_uri", "")
            .ifEmpty { json.optString("verification_url", "") }
            .ifEmpty { return null }
        return DeviceAuthorization(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = uri,
            expiresInSeconds = json.optLong("expires_in", 0).takeIf { it > 0 } ?: 900L,
            intervalSeconds = json.optLong("interval", 0).takeIf { it > 0 } ?: 5L,
        )
    }

    /** Classification of one access_token poll. Mirrors [KimiDeviceFlow]. */
    sealed class PollResult {
        data class Success(val accessToken: String) : PollResult()
        /** authorization_pending — keep polling at the current interval. */
        object Pending : PollResult()
        /** slow_down — keep polling, interval += 5s (RFC 8628 §3.5). */
        object SlowDown : PollResult()
        data class Denied(val description: String) : PollResult()
        data class Expired(val description: String) : PollResult()
        data class Fatal(val description: String) : PollResult()
    }

    /**
     * Classify a poll response.
     *
     * GitHub answers this endpoint with HTTP 200 even for the pending case,
     * putting the OAuth error in the body — so `httpOK` alone must never be
     * read as success. The access_token check comes first for that reason.
     */
    fun classifyPoll(json: JSONObject, httpOK: Boolean): PollResult {
        if (httpOK) {
            val access = json.optString("access_token", "")
            if (access.isNotEmpty()) return PollResult.Success(access)
        }
        val error = json.optString("error", "").lowercase().ifEmpty { "unknown_error" }
        val description = json.optString("error_description", "").ifEmpty { error }
        return when (error) {
            "authorization_pending" -> PollResult.Pending
            "slow_down" -> PollResult.SlowDown
            "access_denied" -> PollResult.Denied(description)
            "expired_token" -> PollResult.Expired(description)
            else -> PollResult.Fatal(description)
        }
    }

    /** RFC 8628 slow_down: bump the running interval by a fixed 5 seconds. */
    fun bumpedInterval(currentSeconds: Long): Long = currentSeconds + 5

    // ── Stage 2 parsing ──────────────────────────────────────────────────

    data class SessionToken(
        val token: String,
        /** Absolute unix seconds when the token stops being accepted. */
        val expiresAtEpochSeconds: Long,
    ) {
        /**
         * Refresh a minute early. The server also returns `refresh_in`, but
         * an absolute deadline survives process death and clock drift between
         * requests, which a relative countdown does not — and this is checked
         * lazily before each request rather than by a timer.
         */
        fun needsRefresh(nowEpochSeconds: Long): Boolean =
            nowEpochSeconds >= (expiresAtEpochSeconds - 60)
    }

    /**
     * Parse the session-token exchange. Returns null when the token is absent
     * OR carries no usable expiry: without a deadline we cannot tell a live
     * token from a dead one, and treating it as valid forever turns every
     * later request into a 401.
     */
    fun parseSessionToken(json: JSONObject, nowEpochSeconds: Long): SessionToken? {
        val token = json.optString("token", "").ifEmpty { return null }
        val expiresAt = json.optLong("expires_at", 0)
        if (expiresAt > 0) return SessionToken(token, expiresAt)
        // Some responses carry only `refresh_in`; derive the deadline from it.
        val refreshIn = json.optLong("refresh_in", 0)
        if (refreshIn > 0) return SessionToken(token, nowEpochSeconds + refreshIn)
        return null
    }

    /**
     * Model ids the picker should offer, from `GET /models`.
     *
     * Filters on `model_picker_enabled`: the endpoint also returns embedding
     * and internal models that chat cannot use, and offering those would put
     * entries in the picker that fail on first send. Absent flag = excluded,
     * deliberately — silently showing an unusable model is worse than missing
     * one that a later response will include.
     */
    fun parseModelIds(json: JSONObject): List<Pair<String, String>> =
        parseModels(json).map { it.id to it.displayName }

    /**
     * [T-android-copilot-model-capabilities] One model as `/models` describes
     * it, including the fields the picker and the context-window logic need.
     *
     * `parseModelIds` used to return only (id, name), discarding everything
     * else in the response. That left every Copilot model with a null context
     * window, which in turn leaves anything reasoning about context size — the
     * compaction threshold, the fallback negative guard — with no window to
     * reason about. The server already sends these numbers; dropping them was
     * pure loss.
     */
    data class CopilotModel(
        val id: String,
        val displayName: String,
        val contextWindow: Int?,
        val maxOutputTokens: Int?,
        val supportsVision: Boolean,
        val supportsReasoning: Boolean?,
        /**
         * Effort tiers this model accepts, from `supports.reasoning_effort`.
         * Null when the model takes no effort parameter.
         */
        val reasoningEffortValues: List<String>?,
    )

    /**
     * Models the account may actually use, with their capabilities.
     *
     * Two filters, both of which can legitimately empty the list — which is why
     * each drop is reported to the caller rather than silently applied:
     *
     * - `model_picker_enabled == false` — the account is not allowed to choose
     *   it. **Absent is treated as VISIBLE**, matching iOS: the field is missing
     *   on older responses, and hiding everything on an unknown shape looks like
     *   "no models" rather than the parsing gap it is. (This is a deliberate
     *   change from the previous default of excluding; excluding-on-absent is
     *   the more dangerous direction because there is no fallback list behind
     *   it — Copilot has no models.dev entry and no built-in catalog.)
     * - `capabilities.type != "chat"` — embeddings cannot serve a conversation.
     *   Reported because a free account is limited to the auto-select model, and
     *   if that entry is typed as anything else this filter would discard the
     *   only model such an account can use.
     */
    data class ParsedModels(
        val models: List<CopilotModel>,
        val hiddenByPolicy: List<String>,
        val droppedByType: List<String>,
        val totalReturned: Int,
    )

    fun parseModels(json: JSONObject): List<CopilotModel> = parseModelsDetailed(json).models

    fun parseModelsDetailed(json: JSONObject): ParsedModels {
        val data = json.optJSONArray("data")
            ?: return ParsedModels(emptyList(), emptyList(), emptyList(), 0)
        val out = mutableListOf<CopilotModel>()
        val hidden = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            val id = m.optString("id", "")
            if (id.isEmpty()) continue
            if (!m.optBoolean("model_picker_enabled", true)) {
                hidden.add(id)
                continue
            }
            val caps = m.optJSONObject("capabilities")
            val type = caps?.optString("type", "").orEmpty()
            if (type.isNotEmpty() && type != "chat") {
                dropped.add("$id:$type")
                continue
            }
            if (!seen.add(id)) continue
            val limits = caps?.optJSONObject("limits")
            val supports = caps?.optJSONObject("supports")
            out.add(
                CopilotModel(
                    id = id,
                    displayName = m.optString("name", "").ifEmpty { id },
                    contextWindow = limits?.optInt("max_context_window_tokens", 0)
                        ?.takeIf { it > 0 },
                    maxOutputTokens = limits?.optInt("max_output_tokens", 0)
                        ?.takeIf { it > 0 },
                    supportsVision = supports?.optBoolean("vision", false) ?: false,
                    // [T-android-copilot-reasoning-fields] Copilot does NOT
                    // send `supports.thinking`. A captured /models payload
                    // reports reasoning as:
                    //
                    //   "adaptive_thinking": true,
                    //   "max_thinking_budget": 32000,
                    //   "min_thinking_budget": 1024,
                    //   "reasoning_effort": ["low","medium","high","xhigh","max"]
                    //
                    // Reading `thinking` (which is what iOS does, and what this
                    // mirrored) therefore always yielded null — "unknown" — and
                    // every thinking gate in the app tests `== true`. The result:
                    // Deep Thinking was unavailable on every Copilot model, and
                    // when it was force-enabled from a stale catalogue the
                    // request went out with NO reasoning parameter at all. That
                    // is the reported "thinking is on but no thinking text".
                    //
                    // Either signal means the model reasons; absence of both is
                    // a real "no" here rather than a gap, because this response
                    // does describe the capability when it exists.
                    supportsReasoning = when {
                        supports == null -> null
                        supports.optBoolean("adaptive_thinking", false) -> true
                        supports.optJSONArray("reasoning_effort")?.length()?.let { it > 0 } == true -> true
                        // `thinking` kept as a fallback in case the shape shifts
                        // back or a proxy emulates the iOS-assumed field.
                        supports.has("thinking") -> supports.optBoolean("thinking", false)
                        else -> false
                    },
                    reasoningEffortValues = supports?.optJSONArray("reasoning_effort")
                        ?.let { arr ->
                            (0 until arr.length()).mapNotNull { i ->
                                arr.optString(i).takeIf { it.isNotEmpty() }
                            }.takeIf { it.isNotEmpty() }
                        },
                ),
            )
        }
        return ParsedModels(out, hidden, dropped, data.length())
    }
}

// ─── Merged from CopilotOAuthManager.kt ──────────────────────────────────────────────────

/**
 * [T-copilot-provider] GitHub Copilot OAuth manager.
 *
 * Two tiers, and keeping them apart is the whole job:
 *
 *  - The GitHub account token (`gho_…`) is long-lived. Won once via device
 *    flow, stored encrypted, and never sent to Copilot's chat endpoint.
 *  - The Copilot session token lasts ~25 minutes and IS what chat requests
 *    carry. It is derived from the account token on demand.
 *
 * [validAccessToken] returns the SESSION token, because that is what the
 * request layer needs; the account token stays behind this class. Refresh is
 * lazy (checked before each request) rather than timer-driven — a timer would
 * keep waking a backgrounded app to refresh a credential nothing is about to
 * use, and would still have to re-check after process death.
 *
 * ⚠️ Unofficial integration — see [CopilotDeviceFlow] for the risk note.
 */
class CopilotOAuthManager(
    context: Context,
    instanceId: String,
) : OAuthManager(context, instanceId) {

    companion object {
        private const val TAG = "CopilotOAuth"

        /** Encrypted-prefs keys, namespaced per provider instance by the base class. */
        private const val KEY_GITHUB_TOKEN = "copilot_github_token"
        private const val KEY_SESSION_TOKEN = "copilot_session_token"
        private const val KEY_SESSION_EXPIRES = "copilot_session_expires_at"

        /** Single-flight per instance: a fan-out of agent requests would
         *  otherwise all miss the cache at once and each burn an exchange. */
        private val refreshMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
        private fun mutexFor(instanceId: String): Mutex =
            refreshMutexes.getOrPut(instanceId) { Mutex() }

        /**
         * Full device-code login. Suspends until the user approves or a
         * terminal failure. [onDeviceCode] fires as soon as the code is
         * issued so the UI can show it — login is NOT complete at that point.
         */
        suspend fun login(
            context: Context,
            instanceId: String,
            onDeviceCode: (CopilotDeviceFlow.DeviceAuthorization) -> Unit,
        ): String {
            val mgr = CopilotOAuthManager(context, instanceId)
            val auth = mgr.requestDeviceAuthorization()
            onDeviceCode(auth)
            val githubToken = mgr.pollForToken(auth)
            mgr.saveOAuthString(KEY_GITHUB_TOKEN, githubToken)
            // Exchange immediately: it proves the account actually has Copilot
            // access, so the user learns at login rather than on first send.
            //
            // [T-android-copilot-login-main-thread] The device flow has already
            // SUCCEEDED at this point and the account token is saved, so a
            // failure here must not read as "authorization failed" — it means
            // the sign-in worked and Copilot access did not. Re-thrown with a
            // message that says which, because an exception with a null message
            // renders as a bare "Authentication failed" in the UI.
            try {
                mgr.exchangeSessionToken(githubToken)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "[Copilot] signed in, but the session-token exchange failed", e)
                throw Exception(
                    e.message
                        ?: "Signed in to GitHub, but could not obtain a Copilot token " +
                        "(${e.javaClass.simpleName}).",
                    e,
                )
            }
            Log.i(TAG, "Copilot login complete (instance: $instanceId)")
            return githubToken
        }
    }

    // Base-class abstract surface. Only tokenURL / clientId matter for a
    // device flow; the redirect/PKCE fields are inert placeholders, as in
    // KimiOAuthManager.
    override val authURL = CopilotDeviceFlow.DEVICE_CODE_URL
    override val tokenURL = CopilotDeviceFlow.ACCESS_TOKEN_URL
    override val clientId: String get() = CopilotDeviceFlow.CLIENT_ID
    override val clientSecret: String? get() = null
    override val callbackPort: Int get() = 0
    override val redirectPath: String get() = ""
    override val scopes = CopilotDeviceFlow.SCOPE

    /** True once a GitHub account token is stored for this instance. */
    fun hasGithubToken(): Boolean = !loadOAuthString(KEY_GITHUB_TOKEN).isNullOrEmpty()

    /**
     * [T-android-copilot-not-connected] Copilot's own answer to "is this
     * instance signed in".
     *
     * The base implementation reads `oauth_tokens_<id>` and looks for an
     * `access_token` — the shape every other provider here persists. Copilot
     * stores its two tiers under its OWN keys instead, so the inherited check
     * always returned false and every caller concluded the user had not signed
     * in: the provider list showed a grey dot and the detail screen said "Not
     * connected", immediately after a sign-in whose log reads "device flow
     * complete / session token refreshed / login complete" and which had just
     * loaded 28 models.
     *
     * Overriding here rather than teaching each screen about Copilot is what
     * fixes all of them at once — this is the question they already ask.
     *
     * The GITHUB token is the credential; the session token is derived and may
     * legitimately be absent or expired. Mirrors iOS
     * `CopilotOAuthManager.isAuthenticated(instanceId:)`, which tests exactly
     * the same field for the same reason.
     */
    override fun isAuthenticated(): Boolean = hasGithubToken()

    /**
     * [T-android-copilot-backup-token] Copilot's credential as a backup blob.
     *
     * The generic exporter reads `oauth_tokens_<id>` via
     * [exportStoredTokensJson], but Copilot never writes that key — it stores
     * the two tiers under `copilot_github_token` / `copilot_session_token`. So
     * a backup silently omitted the Copilot sign-in: the instance came back
     * after a restore, signed out, with no error anywhere. Same user-visible
     * loss iOS hit for a different reason (its switch had a `default` that
     * swallowed the case), and the login notice explicitly promises "a backup
     * you export yourself may contain it".
     *
     * The shape matches iOS `CopilotTokenStorage` exactly — camelCase keys,
     * same field names — so a package written by either platform restores on
     * the other. Only `githubToken` is load-bearing; the session token is
     * short-lived and re-minted on demand, so an expired one costs nothing.
     */
    fun exportCopilotTokensJson(): String? {
        val github = loadOAuthString(KEY_GITHUB_TOKEN)
        if (github.isNullOrEmpty()) return null
        return JSONObject().apply {
            put("githubToken", github)
            loadOAuthString(KEY_SESSION_TOKEN)?.takeIf { it.isNotEmpty() }
                ?.let { put("sessionToken", it) }
            loadOAuthString(KEY_SESSION_EXPIRES)?.toLongOrNull()
                ?.let { put("sessionExpiresAt", it) }
        }.toString()
    }

    /** Restore what [exportCopilotTokensJson] wrote. Tolerates a blob that
     *  carries only `githubToken` — the session tier re-mints itself. */
    fun importCopilotTokensJson(json: String) {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        val github = obj.optString("githubToken", "")
        if (github.isEmpty()) return
        saveOAuthString(KEY_GITHUB_TOKEN, github)
        obj.optString("sessionToken", "").takeIf { it.isNotEmpty() }
            ?.let { saveOAuthString(KEY_SESSION_TOKEN, it) }
        // `sessionExpiresAt` may be absent or 0; a missing or stale expiry simply
        // forces a refresh on the next request, which is the safe direction.
        obj.optLong("sessionExpiresAt", 0L).takeIf { it > 0 }
            ?.let { saveOAuthString(KEY_SESSION_EXPIRES, it.toString()) }
        Log.i(TAG, "[Copilot] credentials restored from backup (instance: $instanceId)")
    }

    // ── Stage 1: device flow ─────────────────────────────────────────────

    private fun postForm(url: String, fields: Map<String, String>): Pair<Int, JSONObject> {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder()
            .url(url)
            .post(form)
            // GitHub returns url-encoded bodies unless JSON is requested.
            .header("Accept", "application/json")
            .header("User-Agent", CopilotDeviceFlow.USER_AGENT)
            .build()
        val response = httpClient.newCall(request).execute()
        val code = response.code
        val body = response.body?.string().orEmpty()
        response.close()
        val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        return code to json
    }

    suspend fun requestDeviceAuthorization(): CopilotDeviceFlow.DeviceAuthorization =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Requesting device code (instance: $instanceId)")
            val (status, json) = postForm(
                CopilotDeviceFlow.DEVICE_CODE_URL,
                mapOf("client_id" to clientId, "scope" to CopilotDeviceFlow.SCOPE),
            )
            if (status !in 200..299) {
                val desc = json.optString("error_description", "")
                    .ifEmpty { json.optString("error", "device authorization failed") }
                Log.e(TAG, "device/code failed: $desc")
                throw Exception("GitHub device authorization failed: $desc")
            }
            CopilotDeviceFlow.parseDeviceAuthorization(json)
                ?: throw Exception("GitHub device authorization response missing required fields")
        }

    /** Poll until approval or terminal failure, honouring RFC 8628 backoff. */
    suspend fun pollForToken(auth: CopilotDeviceFlow.DeviceAuthorization): String =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + auth.expiresInSeconds * 1000
            var interval = auth.intervalSeconds
            // [T-android-copilot-poll-diagnostics] A device flow that sits on
            // "waiting for authorization" forever used to produce NO log line
            // at all — the loop logged nothing on any branch — so the one case
            // anyone would pull logs for was invisible, and a stuck flow could
            // not be told apart from a wrong `error` code, an unparseable body,
            // or a classifier fall-through. GitHub's `error` field is a machine
            // code (authorization_pending / access_denied / …) carrying no user
            // content, so it is safe to log verbatim; the device code and any
            // token are NOT logged.
            Log.i(
                TAG,
                "[Copilot] device flow polling START interval=${interval}s " +
                    "expiresIn=${auth.expiresInSeconds}s instance=${instanceId.take(8)}",
            )
            while (System.currentTimeMillis() < deadline) {
                // Sleep first: polling immediately can only ever return
                // authorization_pending, and risks an instant slow_down.
                delay(interval * 1000)
                val (status, json) = postForm(
                    CopilotDeviceFlow.ACCESS_TOKEN_URL,
                    mapOf(
                        "client_id" to clientId,
                        "device_code" to auth.deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    ),
                )
                val errCode = json.optString("error", "").ifEmpty {
                    if (status in 200..299) "none" else "unparsed"
                }
                Log.i(
                    TAG,
                    "[Copilot] poll status=$status error=$errCode " +
                        "keys=${json.keys().asSequence().sorted().joinToString(",")}",
                )
                when (val r = CopilotDeviceFlow.classifyPoll(json, status in 200..299)) {
                    is CopilotDeviceFlow.PollResult.Success -> {
                        Log.i(TAG, "[Copilot] device flow complete for instance ${instanceId.take(8)}")
                        return@withContext r.accessToken
                    }
                    CopilotDeviceFlow.PollResult.Pending -> Unit
                    CopilotDeviceFlow.PollResult.SlowDown -> {
                        interval = CopilotDeviceFlow.bumpedInterval(interval)
                        Log.i(TAG, "[Copilot] slow_down — interval now ${interval}s")
                    }
                    is CopilotDeviceFlow.PollResult.Denied -> {
                        Log.e(TAG, "[Copilot] device flow DENIED: ${r.description}")
                        throw Exception("GitHub sign-in was denied: ${r.description}")
                    }
                    is CopilotDeviceFlow.PollResult.Expired -> {
                        Log.e(TAG, "[Copilot] device flow EXPIRED")
                        throw Exception("The device code expired — please try again.")
                    }
                    is CopilotDeviceFlow.PollResult.Fatal -> {
                        // The classifier's catch-all: an unrecognised `error`
                        // code lands here, and that is exactly what a stuck flow
                        // would be caused by.
                        Log.e(TAG, "[Copilot] device flow FATAL: ${r.description}")
                        throw Exception("GitHub sign-in failed: ${r.description}")
                    }
                }
            }
            Log.e(
                TAG,
                "[Copilot] device flow timed out after ${auth.expiresInSeconds}s " +
                    "without a terminal answer",
            )
            throw Exception("The device code expired — please try again.")
        }

    // ── Stage 2: session token ───────────────────────────────────────────

    /** Exchange the account token for a session token and persist both parts. */
    /**
     * [T-android-copilot-login-main-thread] Stage 2, always off the main
     * thread.
     *
     * This used to be a plain blocking function. Its two callers disagreed
     * about who owned the dispatch: `validAccessToken` invokes it INSIDE
     * `withContext(Dispatchers.IO)`, so it was fine, while the `login()`
     * companion called it directly — and `login()` runs from the Add Provider
     * screen's `scope.launch`, i.e. on the main thread. The result was a
     * synchronous OkHttp call on the UI thread, which Android answers with
     * `NetworkOnMainThreadException` — an exception whose `message` is NULL, so
     * the screen fell through to its bare "Authentication failed" fallback and
     * logged nothing. The device flow itself had already succeeded, which is
     * why the user saw a completed authorization reported as a failure.
     *
     * Owning the dispatch here rather than at each call site is what makes that
     * class of mistake unavailable: `withContext(IO)` from an IO thread is a
     * no-op, so the correct caller pays nothing.
     */
    private suspend fun exchangeSessionToken(githubToken: String): String =
        withContext(Dispatchers.IO) { exchangeSessionTokenBlocking(githubToken) }

    private fun exchangeSessionTokenBlocking(githubToken: String): String {
        val builder = Request.Builder().url(CopilotDeviceFlow.COPILOT_TOKEN_URL).get()
        CopilotDeviceFlow.tokenExchangeHeaders(githubToken).forEach { (k, v) ->
            builder.header(k, v)
        }
        val response = httpClient.newCall(builder.build()).execute()
        val status = response.code
        val body = response.body?.string().orEmpty()
        response.close()
        if (status !in 200..299) {
            // 401/403 here usually means the account has no Copilot
            // subscription — distinct from a bad token, and worth saying so.
            Log.e(TAG, "Copilot token exchange failed: HTTP $status")
            throw Exception(
                if (status == 401 || status == 403) {
                    "This GitHub account does not have Copilot access (HTTP $status)."
                } else {
                    "Copilot token exchange failed (HTTP $status)."
                }
            )
        }
        val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        val now = System.currentTimeMillis() / 1000
        val session = CopilotDeviceFlow.parseSessionToken(json, now)
            ?: throw Exception("Copilot token exchange returned no usable token.")
        saveOAuthString(KEY_SESSION_TOKEN, session.token)
        saveOAuthString(KEY_SESSION_EXPIRES, session.expiresAtEpochSeconds.toString())
        Log.i(TAG, "Copilot session token refreshed (expires_at=${session.expiresAtEpochSeconds})")
        return session.token
    }

    /**
     * The SESSION token, refreshed if it is at/near expiry.
     *
     * Returns null rather than throwing when there is no account token — the
     * provider layer turns that into InvalidApiKey, which is the state a user
     * who has not signed in should see.
     */
    override suspend fun validAccessToken(): String? = withContext(Dispatchers.IO) {
        val githubToken = loadOAuthString(KEY_GITHUB_TOKEN)
        if (githubToken.isNullOrEmpty()) return@withContext null

        val cached = loadOAuthString(KEY_SESSION_TOKEN)
        val expiresAt = loadOAuthString(KEY_SESSION_EXPIRES)?.toLongOrNull()
        val now = System.currentTimeMillis() / 1000
        if (!cached.isNullOrEmpty() && expiresAt != null &&
            !CopilotDeviceFlow.SessionToken(cached, expiresAt).needsRefresh(now)
        ) {
            return@withContext cached
        }

        mutexFor(instanceId).withLock {
            // Re-read inside the lock: a request that queued behind a refresh
            // must use its result instead of starting a second exchange.
            val freshExpiry = loadOAuthString(KEY_SESSION_EXPIRES)?.toLongOrNull()
            val freshToken = loadOAuthString(KEY_SESSION_TOKEN)
            val nowInner = System.currentTimeMillis() / 1000
            if (!freshToken.isNullOrEmpty() && freshExpiry != null &&
                !CopilotDeviceFlow.SessionToken(freshToken, freshExpiry).needsRefresh(nowInner)
            ) {
                return@withLock freshToken
            }
            runCatching { exchangeSessionToken(githubToken) }
                .onFailure { Log.e(TAG, "Session refresh failed: ${it.message}") }
                .getOrNull()
        }
    }

    /**
     * Clear both tiers. The base [logout] only knows its own keys, so the
     * Copilot-specific ones are blanked here — leaving a stale GitHub token
     * behind would silently re-authenticate a user who just signed out.
     */
    fun logoutCopilot() {
        saveOAuthString(KEY_GITHUB_TOKEN, "")
        saveOAuthString(KEY_SESSION_TOKEN, "")
        saveOAuthString(KEY_SESSION_EXPIRES, "")
        logout()
        Log.i(TAG, "Copilot credentials cleared (instance: $instanceId)")
    }

    // ── Model list ───────────────────────────────────────────────────────

    /**
     * Models the server says the picker may offer, as (id, displayName).
     * Empty on any failure — the caller keeps whatever it had rather than
     * wiping the user's model list because one refresh did not answer.
     */
    suspend fun fetchModels(): List<Pair<String, String>> =
        fetchModelsDetailed().map { it.id to it.displayName }

    /**
     * [T-android-copilot-model-capabilities] Models with their capabilities,
     * and [T-android-copilot-model-diagnostics] a log line for anything the
     * filters removed.
     *
     * Both matter for the same reason: there is NO fallback behind this list.
     * Copilot ships no built-in catalog and has no models.dev entry, so an
     * empty result reaches the user as a bare "no models" with nothing to
     * distinguish a policy filter from a dead token or an unparseable body.
     * Previously every failure collapsed into `emptyList()` behind a single
     * `Log.w`. Now each outcome says which one it was.
     */
    suspend fun fetchModelsDetailed(): List<CopilotDeviceFlow.CopilotModel> =
        withContext(Dispatchers.IO) {
            val session = validAccessToken()
            if (session == null) {
                Log.w(TAG, "[Copilot] /models skipped — no valid session token")
                return@withContext emptyList()
            }
            val builder = Request.Builder()
                .url("${CopilotDeviceFlow.API_BASE}/models")
                .get()
                .header("Authorization", "Bearer $session")
            CopilotDeviceFlow.staticChatHeaders().forEach { (k, v) -> builder.header(k, v) }
            builder.header("X-Request-Id", java.util.UUID.randomUUID().toString())

            val result = runCatching {
                val response = httpClient.newCall(builder.build()).execute()
                val ok = response.isSuccessful
                val code = response.code
                val body = response.body?.string().orEmpty()
                response.close()
                if (!ok) {
                    Log.e(TAG, "[Copilot] /models returned HTTP $code")
                    return@runCatching emptyList<CopilotDeviceFlow.CopilotModel>()
                }
                val parsed = CopilotDeviceFlow.parseModelsDetailed(JSONObject(body))
                Log.i(
                    TAG,
                    "[Copilot] ${parsed.models.size} selectable models of " +
                        "${parsed.totalReturned} returned; " +
                        "ids=[${parsed.models.joinToString(",") { it.id }}]",
                )
                if (parsed.hiddenByPolicy.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "[Copilot] hidden by model_picker_enabled=false: " +
                            parsed.hiddenByPolicy.joinToString(","),
                    )
                }
                if (parsed.droppedByType.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "[Copilot] dropped by capabilities.type != chat: " +
                            parsed.droppedByType.joinToString(","),
                    )
                }
                if (parsed.models.isEmpty()) {
                    // Nothing behind this to fall back on, so say so loudly.
                    Log.e(
                        TAG,
                        "[Copilot] model list is EMPTY after filtering — " +
                            "${parsed.totalReturned} entries returned by /models",
                    )
                }
                parsed.models
            }
            result.onFailure { Log.e(TAG, "[Copilot] /models fetch failed: ${it.message}") }
            result.getOrDefault(emptyList())
        }
}

// ─── Merged from KimiDeviceFlow.kt ──────────────────────────────────────────────────

/**
 * [T-kimi-oauth] Pure RFC 8628 Device Authorization Grant logic for Kimi Code
 * — request/response parsing and poll classification, no network, no Android
 * dependencies beyond org.json. Mirrors iOS `KimiDeviceFlow.swift` so the two
 * platforms stay behavior-identical; unit-tested in KimiDeviceFlowTest.
 *
 * Wire facts verified against the live endpoint (2026-07-23, iOS):
 * - Bodies MUST be application/x-www-form-urlencoded. A JSON body makes the
 *   server report `client_id is required` (HTTP 400).
 * - Do NOT send a `scope` field — any explicit scope is rejected with
 *   `invalid_scope` for this client. Omit it entirely.
 */
object KimiDeviceFlow {

    const val AUTH_HOST = "https://auth.kimi.com"
    const val DEVICE_AUTHORIZATION_URL = "$AUTH_HOST/api/oauth/device_authorization"
    const val TOKEN_URL = "$AUTH_HOST/api/oauth/token"

    /** Default coding API base; the provider layer appends `/v1` (the real
     *  endpoints are `/coding/v1/chat/completions`, `/coding/v1/models` —
     *  `/coding/chat/completions` 404s). */
    const val CODING_API_BASE = "https://api.kimi.com/coding"

    data class DeviceAuthorization(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String?,
        /** Seconds until the device code expires. Server default fallback 900. */
        val expiresInSeconds: Long,
        /** Poll interval in seconds. Server default fallback 5. */
        val intervalSeconds: Long,
    ) {
        /** URL to open for the user — prefers the pre-filled complete URL. */
        val openUrl: String get() = verificationUriComplete ?: verificationUri
    }

    /**
     * Parse a device_authorization response. Returns null when any REQUIRED
     * field (device_code / user_code / a verification URI) is missing —
     * caller surfaces a provider error. Accepts both `verification_uri` and
     * `verification_url` spellings (iOS parity).
     */
    fun parseDeviceAuthorization(json: JSONObject): DeviceAuthorization? {
        val deviceCode = json.optString("device_code", "").ifEmpty { return null }
        val userCode = json.optString("user_code", "").ifEmpty { return null }
        val uri = json.optString("verification_uri", "")
            .ifEmpty { json.optString("verification_url", "") }
            .ifEmpty { return null }
        val uriComplete = json.optString("verification_uri_complete", "")
            .ifEmpty { json.optString("verification_url_complete", "") }
            .ifEmpty { null }
        return DeviceAuthorization(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = uri,
            verificationUriComplete = uriComplete,
            expiresInSeconds = json.optLong("expires_in", 0).takeIf { it > 0 } ?: 900L,
            intervalSeconds = json.optLong("interval", 0).takeIf { it > 0 } ?: 5L,
        )
    }

    /** Classification of one token-endpoint poll. */
    sealed class PollResult {
        data class Success(
            val accessToken: String,
            val refreshToken: String?,
            val expiresInSeconds: Long?,
        ) : PollResult()

        /** authorization_pending — keep polling at the current interval. */
        object Pending : PollResult()

        /** slow_down — keep polling, interval += 5s (RFC 8628 §3.5). */
        object SlowDown : PollResult()

        /** Terminal failures. */
        data class Denied(val description: String) : PollResult()
        data class Expired(val description: String) : PollResult()
        data class Fatal(val description: String) : PollResult()
    }

    /**
     * Classify a token-endpoint poll response. Success is 2xx with an
     * access_token; pending/slow_down arrive as OAuth errors (non-2xx) with
     * an `error` field.
     */
    fun classifyPoll(json: JSONObject, httpOK: Boolean): PollResult {
        if (httpOK) {
            val access = json.optString("access_token", "")
            if (access.isNotEmpty()) {
                return PollResult.Success(
                    accessToken = access,
                    refreshToken = json.optString("refresh_token", "").ifEmpty { null },
                    expiresInSeconds = json.optLong("expires_in", 0).takeIf { it > 0 },
                )
            }
        }
        val error = json.optString("error", "").lowercase().ifEmpty { "unknown_error" }
        val description = json.optString("error_description", "").ifEmpty { error }
        return when (error) {
            "authorization_pending" -> PollResult.Pending
            "slow_down" -> PollResult.SlowDown
            "access_denied" -> PollResult.Denied(description)
            "expired_token" -> PollResult.Expired(description)
            else -> PollResult.Fatal(description)
        }
    }

    /** RFC 8628 slow_down: bump the running interval by a fixed 5 seconds. */
    fun bumpedInterval(currentSeconds: Long): Long = currentSeconds + 5

    /**
     * [T-oauth-refresh-race] Compare-before-delete decision after a refresh
     * classified as INVALID_GRANT (iOS 36d506ca): deleting is only correct
     * when the persisted refresh token is STILL the one this failed request
     * used. If a concurrent refresh already rotated it, the "invalid grant"
     * is stale — the new credentials must be kept or the freshly-won login
     * is destroyed ("signed out ~45 min after signing in" bug class).
     *
     * @param staleRefreshToken the refresh token the FAILED request sent
     * @param currentStoredRefreshToken the refresh token persisted right now
     * @return true if credentials should be deleted, false to keep them
     */
    fun shouldDeleteAfterInvalidGrant(
        staleRefreshToken: String,
        currentStoredRefreshToken: String?,
    ): Boolean {
        val current = currentStoredRefreshToken ?: return true
        return current == staleRefreshToken
    }
}

// ─── Merged from OAuthForegroundGate.kt ──────────────────────────────────────────────────

/**
 * [T-android-oauth-foreground-exchange] Hold a loopback-OAuth token exchange
 * until Minis is back in the foreground.
 *
 * Reported: after authorizing Anthropic in the browser — which then said the
 * tab could be closed — returning to Minis showed no sign-in at all. The
 * authorization had succeeded; the token exchange had not. Logcat on a Pixel 6
 * (Android 17 beta, WireGuard up):
 *
 *     OAuthCallbackServer: GET /callback?code=…&state=…
 *     ClaudeOAuth: Callback received — code length: 48
 *     ClaudeOAuth: Token exchange: POST https://claude.ai/v1/oauth/token
 *     OAuth sign-in failed: Unable to resolve host "claude.ai": No address associated with hostname
 *
 * 10 ms from POST to failure: not a timeout, a refusal. `dumpsys netpolicy` for
 * the app's uid shows `blocked=APP_BACKGROUND, allowed=FOREGROUND|TOP|…`.
 * While the user is in the Custom Tab, Minis is a background app and the OS
 * denies it the network — DNS answers "no address" at once — but the loopback
 * callback server still works, because localhost is never blocked. So the
 * redirect lands, the browser reports success, and the exchange fires into a
 * blocked network. The same fetch from the foreground returns 200.
 *
 * OpenAI appeared to work "after a while" only because its exchange retried
 * for ~3 s ("DNS can briefly fail after Custom Tab closes" — a guess at this
 * cause); a user who took longer to come back failed the same way. Anthropic
 * had no retry and failed every time.
 *
 * The fix is to not attempt the network while it is guaranteed to be refused:
 * after the callback, wait until the process is STARTED again (the user left
 * the browser), then exchange. The code is single-use but lives for minutes, so
 * the wait costs nothing; the managers keep a short retry behind this for the
 * brief lag between foregrounding and the policy lifting.
 *
 * Also publishes the sign-in [phase] so the provider screen can show "waiting
 * for authorization" / "completing sign-in" instead of looking signed-out.
 */
object OAuthForegroundGate {

    /** Where an interactive loopback sign-in currently is. */
    enum class Phase {
        /** Browser opened; waiting for the user to authorize. */
        AWAITING_BROWSER,

        /** Callback received; exchanging the code (possibly waiting to be foreground first). */
        COMPLETING,
    }

    private val _phase = MutableStateFlow<Phase?>(null)
    val phase: StateFlow<Phase?> = _phase.asStateFlow()

    /** Called by the UI when it starts a sign-in. */
    fun begin() { _phase.value = Phase.AWAITING_BROWSER }

    /** Called by the UI when the sign-in ends, however it ends. */
    fun end() { _phase.value = null }

    /**
     * Longest we hold a received code waiting for the user to come back.
     * Beyond this we try anyway and let the failure surface — better an error
     * the user can act on than a sign-in hanging forever.
     */
    internal const val MAX_WAIT_MS = 5 * 60_000L

    /** Foreground as far as network policy cares: at least STARTED. */
    internal fun isForeground(state: Lifecycle.State): Boolean = state.isAtLeast(Lifecycle.State.STARTED)

    /**
     * Suspend until the app is in the foreground, then return. Call right
     * before the token exchange, after the callback has delivered the code.
     */
    suspend fun awaitForeground(tag: String) {
        _phase.value = Phase.COMPLETING
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val state = withContext(Dispatchers.Main.immediate) { lifecycle.currentState }
        if (isForeground(state)) return

        AppLogger.info(
            tag,
            "[OAuth] callback arrived with the app in the background ($state) — " +
                "holding the token exchange until Minis is foreground (background network is blocked)",
        )
        val started = System.currentTimeMillis()
        val arrived = withTimeoutOrNull(MAX_WAIT_MS) {
            withContext(Dispatchers.Main.immediate) { awaitStarted(lifecycle) }
        }
        val waited = System.currentTimeMillis() - started
        if (arrived == null) {
            AppLogger.warning(tag, "[OAuth] still background after ${waited}ms — attempting the exchange anyway")
        } else {
            AppLogger.info(tag, "[OAuth] foreground after ${waited}ms — exchanging")
        }
    }

    /** Must run on the main thread: Lifecycle observers are main-thread only. */
    private suspend fun awaitStarted(lifecycle: Lifecycle): Unit =
        suspendCancellableCoroutine { cont ->
            if (isForeground(lifecycle.currentState)) {
                cont.resume(Unit)
                return@suspendCancellableCoroutine
            }
            val observer = object : LifecycleEventObserver {
                override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
                    if (isForeground(lifecycle.currentState) && cont.isActive) {
                        lifecycle.removeObserver(this)
                        cont.resume(Unit)
                    }
                }
            }
            lifecycle.addObserver(observer)
            cont.invokeOnCancellation {
                // Removal must also happen on main; post it.
                android.os.Handler(android.os.Looper.getMainLooper()).post { lifecycle.removeObserver(observer) }
            }
        }
}

// ─── Merged from OAuthLogRedaction.kt ──────────────────────────────────────────────────

/**
 * [T-android-oauth-log-redact] Log-safe renderings of OAuth secrets.
 *
 * App log files are exported with bug reports, so an authorization code, PKCE
 * verifier, `state`, device/user code or access/refresh token must never reach
 * them verbatim. Callers log [secret] (length + first 4 chars, enough to tell
 * two values apart when diagnosing a mismatch) or [url] for a redirect/request
 * line whose query string carries such values.
 *
 * Pure Kotlin (no android.*) so it is unit-testable on the JVM.
 */
object OAuthLogRedaction {

    /** Query parameters whose values are credentials or one-time secrets. */
    private val SECRET_PARAMS = listOf(
        "code", "state", "code_verifier", "code_challenge",
        "access_token", "refresh_token", "id_token", "token",
        "key", "api_key", "client_secret", "user_code", "device_code",
    )

    private val PARAM_REGEX = Regex(
        "(?<=[?&#])(" + SECRET_PARAMS.joinToString("|") { Regex.escape(it) } + ")=([^&#\\s]*)",
    )

    /** Values shorter than this are shown as length only — 4 chars of a 6-char value is most of it. */
    private const val MIN_LEN_FOR_PREFIX = 8

    /** `<len=N prefix=abcd…>`; never more than the first 4 chars. */
    fun secret(value: String?): String {
        if (value == null) return "<null>"
        if (value.length < MIN_LEN_FOR_PREFIX) return "<len=${value.length}>"
        return "<len=${value.length} prefix=${value.take(4)}…>"
    }

    /**
     * [T-android-oauth-log-redact] JSON members whose values are credentials: a token
     * endpoint's response carries them, and it used to be logged verbatim.
     */
    private val SECRET_JSON = Regex(
        "(\"(?:access_token|refresh_token|id_token|token|code|code_verifier|state|client_secret|api_key|key)\"\\s*:\\s*)\"((?:[^\"\\\\]|\\\\.)*)\"",
    )

    /** [body] with every secret JSON string value replaced by its length only. */
    fun jsonBody(body: String?): String {
        if (body == null) return "<null>"
        return SECRET_JSON.replace(body) { m -> "${m.groupValues[1]}\"<len=${m.groupValues[2].length}>\"" }
    }

    /** Replaces the value of every secret-bearing query/fragment parameter in [url] with [secret]. */
    fun url(url: String?): String {
        if (url == null) return "<null>"
        return PARAM_REGEX.replace(url) { m -> "${m.groupValues[1]}=${secret(m.groupValues[2])}" }
    }
}

// ─── Merged from OAuthManager.kt ──────────────────────────────────────────────────

abstract class OAuthManager(
    protected val context: Context,
    protected val instanceId: String,
) {
    /**
     * [FEAT-261021-16] The instance's Settings > Network tag: sign-in, token
     * refresh and the account calls around them take the same route as the
     * provider's chat requests (an instance without a route of its own
     * follows the default route).
     */
    protected val netTag: String get() = com.openminis.app.network.NetworkRuntime.providerTag(instanceId)

    /** [baseHttpClient], routed by [netTag]; the same object when nothing is configured. */
    protected val httpClient: OkHttpClient get() = com.openminis.app.network.NetworkHttp.client(netTag, baseHttpClient)

    companion object {
        private const val TAG = "OAuthManager"
        private const val KEY_MANUAL_BEARER = "manual_bearer_token"

        /** OAuth `error` codes that mean the refresh token itself is dead. */
        val DEFAULT_FATAL_REFRESH_CODES = setOf(
            "invalid_grant", "invalid_token", "invalid_request",
            "unauthorized_client", "refresh_token_reused",
        )

        /** Shared OkHttp client for all OAuth HTTP requests (respects system proxy). */
        internal val baseHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        /**
         * [T-android-oauth-log-redaction] Make an HTTP body safe to log:
         * masks the VALUES of known credential fields (access_token,
         * refresh_token, id_token, api_key/key, client_secret, device_code,
         * user_code, code_verifier)
         * and truncates to a diagnostic-sized prefix. OAuth failure bodies
         * are usually just {"error":"invalid_grant"} — but some IdPs echo
         * request material, and a malformed SUCCESS body reaching an error
         * path would otherwise dump live credentials into logcat / the
         * on-device log files.
         */
        fun sanitizeBody(body: String, maxLen: Int = 300): String {
            val masked = Regex(
                // [T-android-oauth-log-redact] + user_code / code_verifier (not bare "code": error bodies use it for error codes).
                "\"(access_token|refresh_token|id_token|api_key|key|client_secret|device_code|user_code|code_verifier)\"\\s*:\\s*\"[^\"]*\"",
            ).replace(body) { m -> "\"${m.groupValues[1]}\":\"***\"" }
            return if (masked.length <= maxLen) masked else masked.take(maxLen) + "…(${masked.length} chars)"
        }

        /**
         * [T-android-group-resolve-skip-uncredentialed] Whether ANY OAuth
         * credential is stored for [instanceId] — either a token bundle from a
         * completed login or a user-pasted manual bearer.
         *
         * Static on purpose. Callers (notably provider routing) need a cheap
         * synchronous yes/no and must not depend on [forInstance], which
         * deliberately omits some provider types (gemini) and would report
         * those as uncredentialed. Token storage is keyed purely by instance
         * id, so reading the prefs directly is both correct and complete.
         *
         * Presence only — this does NOT validate or refresh the token. An
         * expired token still counts as a credential so routing keeps the
         * provider and the request path performs the refresh, matching iOS
         * `hasAnyCredential`.
         */
        fun hasStoredCredential(context: Context, instanceId: String): Boolean {
            val prefs = com.openminis.app.util.EncryptedPrefsFactory
                .safeCreate(context, "oauth_prefs")
            if (!prefs.getString("oauth_tokens_$instanceId", null).isNullOrEmpty()) return true
            return !prefs.getString("oauth_${KEY_MANUAL_BEARER}_$instanceId", null).isNullOrEmpty()
        }

        /**
         * [T-oauth-keep-credentials] Whether this instance's stored OAuth token
         * bundle is the one a refresh was rejected with. Automatic paths never
         * delete credentials any more; they set this mark instead, the UI shows
         * the instance red, and routing treats it as uncredentialed. Only an
         * explicit Sign Out deletes the bundle.
         *
         * The mark is a SHA-256 fingerprint of the rejected bundle, never the
         * credential, and it only applies while that exact bundle is stored, so
         * a new login (or a restored backup) lapses it with no explicit clear.
         * A stored manual bearer overrides it: that credential is still usable.
         * Static for the same reason as [hasStoredCredential].
         */
        fun needsReauth(context: Context, instanceId: String): Boolean {
            val prefs = com.openminis.app.util.EncryptedPrefsFactory
                .safeCreate(context, "oauth_prefs")
            val mark = prefs.getString(needsReauthKey(instanceId), null) ?: return false
            val blob = prefs.getString("oauth_tokens_$instanceId", null) ?: return false
            if (tokenFingerprint(blob) != mark) return false
            // A manual bearer never refreshes and stands in for the bundle.
            return prefs.getString("oauth_${KEY_MANUAL_BEARER}_$instanceId", null).isNullOrEmpty()
        }

        private fun needsReauthKey(instanceId: String) = "oauth_needs_reauth_$instanceId"

        private fun tokenFingerprint(blob: String): String =
            MessageDigest.getInstance("SHA-256").digest(blob.toByteArray())
                .joinToString("") { "%02x".format(it) }

        /**
         * The token bundle to save after a refresh: the [response], plus what
         * it left out that the stored bundle had. The refresh token may not
         * come back on every refresh. [T-codex-account-id-refresh] Nor may the
         * id_token, which an OpenAI sign-in made before its account id was
         * stored needs to backfill it (GH #447): saving the response alone
         * dropped it for good.
         */
        internal fun refreshedTokens(stored: JSONObject, response: JSONObject, usedRefreshToken: String): JSONObject {
            if (!response.has("refresh_token")) response.put("refresh_token", usedRefreshToken)
            val idToken = stored.optString("id_token", "")
            if (response.optString("id_token", "").isEmpty() && idToken.isNotEmpty()) response.put("id_token", idToken)
            return response
        }

        /**
         * [T-oauth-keep-credentials] Structured "the token endpoint rejected
         * this refresh token" test, iOS `OAuthRefreshErrorClassifier` parity:
         * an auth-rejection status, or an exact OAuth `error` code from the
         * JSON body. No substring matching — a body that merely mentions
         * `refresh_token` (e.g. `refresh_token_expiry_ms`) is not a rejection.
         */
        fun isRefreshRejected(status: Int, body: String, fatalErrorCodes: Set<String>): Boolean {
            if (status == 400 || status == 401 || status == 403) return true
            val code = try { JSONObject(body).optString("error", "") } catch (_: Exception) { "" }
            return code.lowercase() in fatalErrorCodes
        }

        /** Create the appropriate OAuthManager for a provider instance. */
        fun forInstance(context: Context, instance: com.openminis.app.data.model.ProviderInstance): OAuthManager? {
            return when (instance.providerType) {
                com.openminis.app.data.model.ProviderType.anthropic -> ClaudeOAuthManager(context, instance.id)
                com.openminis.app.data.model.ProviderType.openAI -> OpenAIOAuthManager(context, instance.id)
                com.openminis.app.data.model.ProviderType.xAI -> XAIOAuthManager(context, instance.id)
                com.openminis.app.data.model.ProviderType.kimiCode -> KimiOAuthManager(context, instance.id)
                // [T-copilot-provider] Device-flow OAuth like Kimi, but the
                // token it hands out is Copilot's short-lived session token.
                com.openminis.app.data.model.ProviderType.githubCopilot ->
                    CopilotOAuthManager(context, instance.id)
                else -> null
            }
        }
    }

    abstract val authURL: String
    abstract val tokenURL: String
    abstract val clientId: String
    abstract val clientSecret: String?
    abstract val callbackPort: Int
    abstract val redirectPath: String
    abstract val scopes: String

    // `open` so a provider whose server-side allow-list pins a different
    // loopback spelling can override it (xAI registered 127.0.0.1, not
    // localhost — see [XAIOAuthManager]). The default stays `localhost`,
    // which OpenAI/Codex, Gemini, Antigravity and Claude all register, so
    // this change is override-only and leaves every other provider untouched.
    open val redirectUri: String get() = "http://localhost:$callbackPort$redirectPath"

    // PKCE
    private var codeVerifier: String? = null

    protected fun generatePKCE(): Pair<String, String> = generatePKCE(byteLength = 64)

    /**
     * Generate a PKCE verifier / challenge pair from [byteLength] random bytes.
     * Anthropic Claude Code requires 96 bytes (matches iOS `ClaudeOAuthManager`);
     * other providers use the 64-byte default via the no-arg overload.
     */
    protected fun generatePKCE(byteLength: Int): Pair<String, String> {
        val bytes = ByteArray(byteLength)
        SecureRandom().nextBytes(bytes)
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        codeVerifier = verifier
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        return verifier to challenge
    }

    protected fun generateHexPKCE(): Pair<String, String> {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val verifier = bytes.joinToString("") { "%02x".format(it) }
        codeVerifier = verifier
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        return verifier to challenge
    }

    protected fun generateState(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Override to save PKCE verifier for later retrieval (e.g. for JSON token exchange). */
    open fun generateAndSavePKCE(): Pair<String, String> = generatePKCE()

    /** Override to save state for later retrieval. */
    open fun generateAndSaveState(): String = generateState()

    private var currentState: String? = null
    private var callbackServer: OAuthCallbackServer? = null

    open fun buildAuthorizationUrl(): String {
        val (_, challenge) = generateAndSavePKCE()
        currentState = generateAndSaveState()
        return "$authURL?" + listOf(
            "client_id=$clientId",
            "redirect_uri=${Uri.encode(redirectUri)}",
            "response_type=code",
            "scope=${Uri.encode(scopes)}",
            "state=$currentState",
            "code_challenge=$challenge",
            "code_challenge_method=S256",
        ).joinToString("&")
    }

    suspend fun startLogin(onComplete: (Boolean) -> Unit) {
        callbackServer?.stop()
        callbackServer = OAuthCallbackServer(
            callbackPort,
            // [T-oauth-callback-double-resume] Denied / failed: report now.
            onError = { err ->
                Log.w(TAG, "OAuth callback error: ${err.error}")
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) { onComplete(false) }
            },
        ) { code, state ->
            if (state != null && state != currentState) {
                Log.w(TAG, "State mismatch")
                onComplete(false)
                return@OAuthCallbackServer
            }
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                // [T-android-oauth-foreground-exchange] See OAuthForegroundGate.
                OAuthForegroundGate.awaitForeground(TAG)
                val success = exchangeCode(code)
                withContext(Dispatchers.Main) { onComplete(success) }
            }
        }
        callbackServer?.start()

        val url = buildAuthorizationUrl()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    suspend fun exchangeCode(code: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val params = buildTokenParams(code)
            val formBody = params.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
            val request = Request.Builder()
                .url(tokenURL)
                .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            val response = httpClient.newCall(request).execute()
            val responseCode = response.code
            val responseBody = response.body?.string() ?: ""
            response.close()

            if (responseCode !in 200..299) {
                Log.e(TAG, "Token exchange failed: $responseCode ${sanitizeBody(responseBody)}")
                return@withContext false
            }

            val json = JSONObject(responseBody)
            saveTokens(json)
            onTokensReceived(json)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange error", e)
            false
        } finally {
            callbackServer?.stop()
            callbackServer = null
        }
    }

    protected open fun buildTokenParams(code: String): Map<String, String> {
        val params = mutableMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to clientId,
            "code_verifier" to (codeVerifier ?: ""),
        )
        clientSecret?.let { params["client_secret"] = it }
        return params
    }

    open suspend fun refreshToken(): Boolean = withContext(Dispatchers.IO) {
        val stored = loadStoredTokens() ?: return@withContext false
        val refreshToken = stored.optString("refresh_token", "").ifEmpty { return@withContext false }

        try {
            val params = mutableMapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to clientId,
            )
            clientSecret?.let { params["client_secret"] = it }

            val formBody = params.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
            val request = Request.Builder()
                .url(tokenURL)
                .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            val response = httpClient.newCall(request).execute()
            val responseCode = response.code
            val responseBody = response.body?.string() ?: ""
            response.close()

            if (responseCode !in 200..299) {
                Log.e(TAG, "Token refresh failed: $responseCode")
                if (isRefreshRejected(responseCode, responseBody, DEFAULT_FATAL_REFRESH_CODES)) {
                    markNeedsReauth(refreshToken)
                }
                return@withContext false
            }

            val json = refreshedTokens(stored, JSONObject(responseBody), refreshToken)
            saveTokens(json)
            onTokensRefreshed(json)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh error", e)
            false
        }
    }

    open suspend fun validAccessToken(): String? {
        // Manual bearer token takes precedence — user wants a static token,
        // no refresh attempted. Mirrors iOS behavior for custom proxy providers.
        loadManualBearerToken()?.takeIf { it.isNotEmpty() }?.let { return it }

        val stored = loadStoredTokens() ?: return null
        val token = stored.optString("access_token", "").ifEmpty { return null }
        val expireAt = stored.optLong("expire_at", 0)
        val now = System.currentTimeMillis()

        // Refresh if expires within 4 hours
        if (expireAt > 0 && (expireAt - now) < 4 * 3600 * 1000) {
            if (refreshToken()) {
                return loadStoredTokens()?.optString("access_token")
            }
            // Refresh failed and the token is already expired: nothing usable
            // to return. [T-oauth-keep-credentials] Credentials are kept — a
            // rejected refresh was marked inside refreshToken(); a transient
            // failure (network, 5xx) must never cost the user their login.
            if (expireAt > 0 && now >= expireAt) {
                Log.w(TAG, "Token expired and refresh failed — keeping credentials")
                return null
            }
        }
        return token
    }

    /**
     * Whether this instance has a usable credential.
     *
     * [T-android-copilot-not-connected] `open` because a subclass may persist
     * its credential somewhere other than the shared `oauth_tokens_<id>` blob
     * this default inspects. Copilot does exactly that (two tiers under its own
     * keys), and while the method was final its sign-in looked like a failure
     * to every screen that asks this question.
     */
    open fun isAuthenticated(): Boolean {
        if (loadManualBearerToken()?.isNotEmpty() == true) return true
        val stored = loadStoredTokens() ?: return false
        return stored.optString("access_token", "").isNotEmpty()
    }

    fun logout() {
        getEncryptedPrefs().edit()
            .remove("oauth_tokens_$instanceId")
            .remove("oauth_${KEY_MANUAL_BEARER}_$instanceId")
            .remove(needsReauthKey(instanceId))
            .apply()
    }

    /** See the companion [needsReauth]. */
    fun needsReauth(): Boolean = needsReauth(context, instanceId)

    /**
     * [T-oauth-keep-credentials] Called instead of [logout] when the token
     * endpoint rejects [staleRefreshToken]. Compare-before-mark: when the
     * stored bundle no longer carries that refresh token, a concurrent refresh
     * already rotated it and this rejection is stale — marking would flag a
     * perfectly good fresh login.
     */
    protected fun markNeedsReauth(staleRefreshToken: String) {
        val blob = loadOAuthString("tokens") ?: return
        val current = try { JSONObject(blob).optString("refresh_token", "") } catch (_: Exception) { "" }
        if (current != staleRefreshToken) {
            Log.w(TAG, "Stale refresh rejection ignored — token already rotated; not marking")
            return
        }
        getEncryptedPrefs().edit()
            .putString(needsReauthKey(instanceId), tokenFingerprint(blob))
            .apply()
        Log.w(TAG, "Refresh token rejected — marked for re-login, credentials kept")
    }

    private fun clearNeedsReauth() {
        getEncryptedPrefs().edit().remove(needsReauthKey(instanceId)).apply()
    }

    // Token storage
    private fun saveTokens(json: JSONObject) {
        val expiresIn = json.optLong("expires_in", 0)
        if (expiresIn > 0) {
            json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
        }
        getEncryptedPrefs().edit()
            .putString("oauth_tokens_$instanceId", json.toString())
            .remove(needsReauthKey(instanceId))
            .apply()
    }

    protected fun loadStoredTokens(): JSONObject? {
        val str = getEncryptedPrefs().getString("oauth_tokens_$instanceId", null) ?: return null
        return try { JSONObject(str) } catch (_: Exception) { null }
    }

    protected fun saveOAuthString(key: String, value: String) {
        getEncryptedPrefs().edit().putString("oauth_${key}_$instanceId", value).apply()
        // A new token bundle supersedes any needs-re-login mark.
        if (key == "tokens") clearNeedsReauth()
    }

    protected fun loadOAuthString(key: String): String? {
        return getEncryptedPrefs().getString("oauth_${key}_$instanceId", null)
    }

    /**
     * User-provided static bearer token used in place of the dynamic OAuth
     * access token. Mirrors iOS `"manual-oauth-token"` keychain account.
     * When set, [validAccessToken] returns it verbatim and no refresh is
     * attempted — intended for custom proxy endpoints that don't run the
     * normal OAuth flow but still expect a Bearer credential.
     */
    fun saveManualBearerToken(token: String) {
        saveOAuthString(KEY_MANUAL_BEARER, token)
        // [T-provider-quota] A pasted token is the quota query's credential too.
        com.openminis.app.quota.QuotaStore.credentialsChanged()
    }

    fun loadManualBearerToken(): String? = loadOAuthString(KEY_MANUAL_BEARER)

    fun deleteManualBearerToken() {
        getEncryptedPrefs().edit()
            .remove("oauth_${KEY_MANUAL_BEARER}_$instanceId")
            .apply()
        com.openminis.app.quota.QuotaStore.credentialsChanged()
    }

    // [T-android-provider-export-oauth-token] Public export/import shims for the
    // provider export/import flow. The structured OAuth-login credential
    // (access_token / refresh_token / expire_at) lives as one JSON blob under
    // `oauth_tokens_<instanceId>`; the export flow previously omitted it, so an
    // OAuth-logged-in provider exported with no usable credential and imported
    // as not-authenticated. Mirrors iOS ProviderKeychainHelper.loadOAuthToken /
    // saveOAuthToken round-trip. These wrap the protected token storage without
    // exposing it broadly.

    /** The structured OAuth-login token blob as a JSON string, or null if the
     *  instance never completed an OAuth login. */
    fun exportStoredTokensJson(): String? = loadStoredTokens()?.toString()

    /** Restore a structured OAuth-login token blob from [json] (as produced by
     *  [exportStoredTokensJson]). Written verbatim so the absolute `expire_at`
     *  is preserved — unlike [saveTokens] this does NOT recompute expiry from a
     *  relative `expires_in`, which would be wrong at import time. */
    fun importStoredTokensJson(json: String) {
        val obj = try { JSONObject(json) } catch (_: Exception) { return }
        val normalized = normalizeCamelToSnake(obj)
        getEncryptedPrefs().edit()
            .putString("oauth_tokens_$instanceId", normalized.toString())
            .remove(needsReauthKey(instanceId))
            .apply()
    }

    private fun normalizeCamelToSnake(obj: JSONObject): JSONObject {
        val keyMap = mapOf(
            "accessToken" to "access_token",
            "refreshToken" to "refresh_token",
            "idToken" to "id_token",
            "expireDate" to "expire_at",
            "lastRefresh" to "last_refresh",
            "accountId" to "account_id",
            "planType" to "plan_type",
            "tokenEndpoint" to "token_endpoint",
        )
        if (keyMap.keys.none { obj.has(it) }) return obj
        val result = JSONObject()
        for (key in obj.keys()) {
            val mapped = keyMap[key] ?: key
            var value: Any = obj.get(key)
            if (mapped == "expire_at" && value is Number) {
                val v = value.toDouble()
                // iOS JSONEncoder encodes Date as seconds since 2001-01-01.
                // Values < 2e9 are reference-date offsets; convert to epoch-ms.
                val appleReferenceEpochMs = 978307200000L
                value = if (v < 2_000_000_000) {
                    (v * 1000).toLong() + appleReferenceEpochMs
                } else if (v < 2_000_000_000_000) {
                    v.toLong()
                } else {
                    v.toLong()
                }
            }
            result.put(mapped, value)
        }
        return result
    }

    /** Read an auxiliary OAuth string (e.g. Gemini's `email` / `gcp_project`)
     *  for export. */
    fun exportOAuthString(key: String): String? = loadOAuthString(key)

    /** Restore an auxiliary OAuth string (e.g. Gemini's `email` / `gcp_project`)
     *  on import. */
    fun importOAuthString(key: String, value: String) = saveOAuthString(key, value)

    // T-android-keystore-aead-fail: routed through the self-healing
    // factory; Samsung One UI / Android 16 can invalidate the master
    // key under the user, and OAuth callers can't afford a crash here
    // since some of them run in the background refresh path.
    private fun getEncryptedPrefs() =
        com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, "oauth_prefs")

    protected open suspend fun onTokensReceived(json: JSONObject) {}

    /** After [refreshToken] saved [json] (already merged by [refreshedTokens]). */
    protected open suspend fun onTokensRefreshed(json: JSONObject) {}
}
