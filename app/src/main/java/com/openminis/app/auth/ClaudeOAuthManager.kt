package com.openminis.app.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.openminis.app.BuildConfig
import com.openminis.app.data.repository.ProviderRepository
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * OAuth manager for Anthropic (Claude) OAuth flow.
 *
 * Flow (mirrors OpenRouterOAuthManager pattern):
 * 1. Generate PKCE verifier + challenge, state
 * 2. Start local callback server on port 54545
 * 3. Open claude.ai/oauth/authorize in Chrome Custom Tab (in-app browser)
 * 4. User authorizes → Anthropic redirects to localhost:54545/callback
 * 5. Callback server captures code + state
 * 6. Exchange code for tokens via POST JSON to claude.ai/v1/oauth/token
 * 7. Store access_token as API key via ProviderRepository
 *
 * Key differences from OpenRouter:
 * - Token exchange uses application/json (NOT form-urlencoded)
 * - `state` parameter required in token exchange body
 * - API calls need `anthropic-beta: oauth-2025-04-20` header
 * - System prompt must start with Claude Code prefix
 */
class ClaudeOAuthManager(context: Context, instanceId: String) : OAuthManager(context, instanceId) {

    companion object {
        private const val TAG = "ClaudeOAuth"

        /**
         * System prompt prefix required by Anthropic for Claude Code OAuth
         * credentials.
         *
         * The value is not hardcoded: it is supplied at build time via the
         * `ANTHROPIC_OAUTH_IDENTIFIER_PROMPT` BuildConfig field, populated from
         * `app/provider-customization.properties`. That file is tracked in the private
         * repo (filled in) but excluded from the public mirror, which ships
         * only `provider-customization.properties.example` with an empty value. Reading
         * it without a configured value throws — by design — the first time an
         * OAuth (Claude Code) request needs the prompt.
         */
        val ANTHROPIC_OAUTH_IDENTIFIER_PROMPT: String
            get() = BuildConfig.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT.ifEmpty {
                throw IllegalStateException(
                    "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT is not configured. Copy "
                        + "app/provider-customization.properties.example to "
                        + "app/provider-customization.properties and set "
                        + "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT before using Claude Code OAuth."
                )
            }

        /**
         * How long before expiry we proactively refresh. Mirrors iOS
         * `ClaudeOAuthManager.refreshBuffer` (5 minutes) — significantly tighter
         * than OAuthManager's 4-hour default, which burns refresh cycles.
         */
        private const val REFRESH_BUFFER_MS = 5 * 60 * 1000L

        private var callbackServer: OAuthCallbackServer? = null

        /**
         * Per-instance refresh mutex so concurrent callers (e.g. parallel
         * streaming requests that all discover an expired token at once)
         * don't each kick off an HTTP refresh. Only one waits on the network;
         * the rest fall through to read the fresh stored token. Mirrors iOS
         * `actor` serialization. Keyed by instanceId because each OAuth
         * account has its own token state.
         */
        private val refreshMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

        private fun mutexFor(instanceId: String): Mutex =
            refreshMutexes.getOrPut(instanceId) { Mutex() }

        /**
         * Static helper: perform full login flow and save the API key.
         * Returns the access token.
         */
        suspend fun login(context: Context, instanceId: String, providerRepository: ProviderRepository): String {
            val manager = ClaudeOAuthManager(context, instanceId)
            val token = manager.performLogin(context)
            providerRepository.saveApiKey(instanceId, token)
            return token
        }
    }

    /**
     * Classifies the reason `refreshToken()` completed. UI layers dispatch on
     * this to decide between "retry the request" vs "prompt the user to sign
     * in again". Mirrors iOS `isRefreshTokenInvalid` + the surrounding try/catch.
     */
    enum class RefreshOutcome {
        /** New access token is in storage. */
        SUCCESS,
        /** Refresh token is invalid/revoked (HTTP 400/401/403 or invalid_grant). Instance marked for re-login, credentials kept; user must log in again. */
        INVALID_GRANT,
        /** Transient network/5xx error. Existing token kept; caller may retry later. */
        TRANSIENT,
        /** No refresh token available (never logged in, or lost). */
        NO_TOKEN,
    }

    override val authURL = "https://claude.ai/oauth/authorize"
    /**
     * [OpenMinis#360] `claude.ai`, not the older `console.anthropic.com`. The
     * old host answers the OAuth token POST with a Cloudflare challenge page
     * (HTTP 403, "Just a moment...") rather than JSON, so both login and
     * silent refresh fail. Paired with [applyClaudeCliMimicryHeaders] on every
     * request to this URL — the endpoint alone is not enough.
     */
    override val tokenURL = "https://claude.ai/v1/oauth/token"
    override val clientId = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    override val clientSecret: String? = null
    override val callbackPort = 54545
    override val redirectPath = "/callback"
    override val scopes = "org:create_api_key user:profile user:inference"

    /**
     * Perform the full OAuth login flow (mirrors OpenRouterOAuthManager.login pattern).
     * Returns the access token. Throws on failure.
     */
    suspend fun performLogin(context: Context): String {
        Log.i(TAG, "=== Anthropic OAuth login started (instance: $instanceId) ===")

        callbackServer?.stop()
        callbackServer = null

        // Generate PKCE and state (saved for later token exchange)
        val authUrl = buildAuthorizationUrl()

        val accessToken = withContext(Dispatchers.IO) {
            // Step 1: Wait for callback code
            val (code, state) = suspendCancellableCoroutine<Pair<String, String?>> { cont ->
                val server = OAuthCallbackServer(
                    callbackPort,
                    // [T-oauth-callback-double-resume] Denied / failed on the
                    // provider's page: end the sign-in now with its reason.
                    onError = { err -> if (cont.isActive) cont.resumeWithException(err.toException(context)) },
                ) { receivedCode, receivedState ->
                    if (cont.isActive) {
                        cont.resume(receivedCode to receivedState)
                    }
                }
                callbackServer = server
                server.start()
                Log.i(TAG, "Callback server started on port $callbackPort")

                cont.invokeOnCancellation {
                    server.stop()
                    callbackServer = null
                }

                // Open auth URL in Chrome Custom Tab (in-app browser). Do NOT set
                // FLAG_ACTIVITY_NEW_TASK: MainActivity is singleTask, and launching Custom
                // Tab in a new task causes the IME to be dismissed whenever the authorization
                // page focuses an input.
                OAuthTabLauncher.launch(context, Uri.parse(authUrl))
                Log.i(TAG, "Opened Custom Tab for Anthropic authorization")
            }

            callbackServer?.stop()
            callbackServer = null
            Log.i(TAG, "Callback received — code length: ${code.length}")

            // Step 2: Validate state (CSRF protection)
            val savedState = loadOAuthString("state")
            if (state != null && state != savedState) {
                throw Exception("OAuth state mismatch — possible CSRF attack")
            }

            // Step 3: Exchange code for tokens (JSON body, NOT form-urlencoded)
            // [T-android-oauth-foreground-exchange] The callback lands while the
            // user is still in the Custom Tab, i.e. with Minis in the background,
            // where the OS blocks this app's network (DNS answers "no address").
            // Exchange only once the user is back.
            OAuthForegroundGate.awaitForeground(TAG)
            exchangeCodeJson(code)
        }

        Log.i(TAG, "=== Anthropic OAuth login complete (instance: $instanceId) ===")
        return accessToken
    }

    /**
     * Exchange authorization code for tokens using JSON body.
     * Anthropic requires application/json, NOT form-urlencoded.
     */
    private fun exchangeCodeJson(code: String): String {
        val body = JSONObject().apply {
            put("grant_type", "authorization_code")
            put("client_id", clientId)
            put("code", code)
            put("redirect_uri", redirectUri)
            put("code_verifier", loadOAuthString("verifier") ?: "")
            put("state", loadOAuthString("state") ?: "")
        }

        Log.d(TAG, "Token exchange: POST $tokenURL")

        val request = okhttp3.Request.Builder()
            .url(tokenURL)
            .applyClaudeCliMimicryHeaders()
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        // [T-android-oauth-foreground-exchange] OAuthForegroundGate has already
        // waited for the app to be foreground; this short retry only covers
        // the lag between foregrounding and the OS lifting the background
        // network block. A network failure that outlasts it is surfaced as
        // OAuthNetworkUnreachableException so the UI can say "check your
        // network" rather than print a raw UnknownHostException.
        var response: okhttp3.Response? = null
        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                response = httpClient.newCall(request).execute()
                lastError = null
                break
            } catch (e: java.io.IOException) {
                Log.w(TAG, "Token exchange attempt $attempt failed: ${e.javaClass.simpleName}: ${e.message}")
                lastError = e
                if (attempt < 3) Thread.sleep(1000L * attempt)
            }
        }
        if (response == null) {
            val e = lastError ?: Exception("Token exchange failed")
            throw if (e is java.net.UnknownHostException || e is java.net.SocketTimeoutException) {
                OAuthNetworkUnreachableException(e)
            } else {
                e
            }
        }
        val responseCode = response.code
        val responseBody = response.body?.string() ?: ""
        response.close()

        if (responseCode !in 200..299) {
            Log.e(TAG, "Token exchange failed: $responseCode ${OAuthManager.sanitizeBody(responseBody)}")
            throw Exception("Token exchange failed ($responseCode): $responseBody")
        }

        val json = JSONObject(responseBody)
        val accessToken = json.optString("access_token", "")
        if (accessToken.isEmpty()) {
            throw Exception("No access_token in response")
        }

        // Save full token data (for refresh later)
        val expiresIn = json.optLong("expires_in", 0)
        if (expiresIn > 0) {
            json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
        }
        saveOAuthString("tokens", json.toString())

        Log.i(TAG, "Token exchange successful. Expires in ${expiresIn}s, has refresh: ${json.has("refresh_token")}")
        return accessToken
    }

    /**
     * Boolean wrapper over [refreshTokenClassified] for compatibility with the
     * base `OAuthManager.refreshToken()` signature. Returns true on SUCCESS.
     * Prefer [refreshTokenClassified] at new call sites — it tells you
     * whether a failure requires re-login vs a simple retry.
     */
    override suspend fun refreshToken(): Boolean =
        refreshTokenClassified() == RefreshOutcome.SUCCESS

    /**
     * Refresh with structured error classification and concurrency protection.
     * Mirrors iOS `ClaudeOAuthManager.validAccessToken` error handling:
     *
     *  - `INVALID_GRANT` — HTTP 400/401/403 or an exact fatal `error` code:
     *    the instance is marked for re-login and its credentials are KEPT
     *    (iOS parity: `markOAuthNeedsReauth`). User must re-run the OAuth
     *    login flow; only Sign Out deletes the bundle.
     *  - `TRANSIENT` — network/timeout/5xx: credentials kept intact; caller
     *    may retry. The still-valid access token (if any) remains usable.
     *  - `NO_TOKEN` — no refresh token stored (not logged in).
     *  - `SUCCESS` — new access token persisted.
     *
     * Concurrency: guarded by a per-instance [Mutex]. If another coroutine is
     * already refreshing, we wait for it to finish and then check whether the
     * stored token was updated in the meantime. This matches iOS's actor-
     * serialized refresh path.
     */
    suspend fun refreshTokenClassified(): RefreshOutcome = withContext(Dispatchers.IO) {
        val mutex = mutexFor(instanceId)
        mutex.withLock {
            val stored = loadStoredTokens() ?: return@withLock RefreshOutcome.NO_TOKEN
            val refreshTokenValue = stored.optString("refresh_token", "")
                .ifEmpty { return@withLock RefreshOutcome.NO_TOKEN }

            // Coalesce concurrent refreshers: if the token was refreshed while
            // we were blocked on the mutex, return SUCCESS without hitting the
            // network again. Detected by comparing expire_at to what we saw
            // entering the lock — a different value means another coroutine
            // already refreshed.
            val priorExpireAt = stored.optLong("expire_at", 0)
            val freshCheck = loadStoredTokens()?.optLong("expire_at", 0) ?: 0
            if (freshCheck > priorExpireAt && freshCheck > System.currentTimeMillis()) {
                Log.d(TAG, "Refresh skipped — fresher token already present (concurrent refresh coalesced)")
                return@withLock RefreshOutcome.SUCCESS
            }

            try {
                val body = JSONObject().apply {
                    put("grant_type", "refresh_token")
                    put("client_id", clientId)
                    put("refresh_token", refreshTokenValue)
                }

                Log.d(TAG, "Refreshing OAuth token via JSON POST to $tokenURL")
                val request = okhttp3.Request.Builder()
                    .url(tokenURL)
                    .applyClaudeCliMimicryHeaders()
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val response = httpClient.newCall(request).execute()
                val responseCode = response.code
                val responseBody = response.body?.string() ?: ""
                response.close()

                if (responseCode in 200..299) {
                    val json = JSONObject(responseBody)
                    if (!json.has("refresh_token")) {
                        json.put("refresh_token", refreshTokenValue)
                    }
                    val expiresIn = json.optLong("expires_in", 0)
                    if (expiresIn > 0) {
                        json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
                    }
                    saveOAuthString("tokens", json.toString())
                    Log.i(TAG, "Token refresh successful. Expires in ${expiresIn}s")
                    return@withLock RefreshOutcome.SUCCESS
                }

                // Classify failure. [T-oauth-keep-credentials] Structured
                // (status + exact `error` code), iOS parity: the old bare
                // `refresh_token` substring also fired on benign bodies.
                val isInvalidGrant = OAuthManager.isRefreshRejected(
                    responseCode, responseBody,
                    setOf("invalid_grant", "invalid_token", "invalid_request", "unauthorized_client"),
                )
                if (isInvalidGrant) {
                    // Never delete: mark for re-login and keep the bundle.
                    Log.e(TAG, "Refresh token invalid ($responseCode): ${OAuthManager.sanitizeBody(responseBody)} — marking for re-login")
                    markNeedsReauth(refreshTokenValue)
                    return@withLock RefreshOutcome.INVALID_GRANT
                }
                // 5xx or unexpected code — keep credentials, let caller retry.
                Log.w(TAG, "Token refresh transient failure ($responseCode): ${OAuthManager.sanitizeBody(responseBody)} — keeping token")
                return@withLock RefreshOutcome.TRANSIENT
            } catch (e: Exception) {
                // Network failure (IOException / SocketTimeout / etc). Keep credentials.
                Log.w(TAG, "Token refresh transient error — keeping token", e)
                return@withLock RefreshOutcome.TRANSIENT
            }
        }
    }

    /**
     * Check the stored token and refresh it if within the 5-minute expiry
     * window (iOS parity: `refreshBuffer = 5 * 60`). Returns the currently
     * valid access token string, or null if not logged in / refresh failed
     * and the old token is also expired. Mirrors iOS `validAccessToken`.
     *
     * Use this from network call sites that want a fresh token without
     * caring about the refresh outcome details.
     */
    override suspend fun validAccessToken(): String? = withContext(Dispatchers.IO) {
        val stored = loadStoredTokens() ?: return@withContext null
        val token = stored.optString("access_token", "").ifEmpty { return@withContext null }
        val expireAt = stored.optLong("expire_at", 0)
        val now = System.currentTimeMillis()

        val needsRefresh = expireAt > 0 && (expireAt - now) <= REFRESH_BUFFER_MS
        if (!needsRefresh) return@withContext token

        when (refreshTokenClassified()) {
            RefreshOutcome.SUCCESS ->
                loadStoredTokens()?.optString("access_token", "")?.ifEmpty { null }
            RefreshOutcome.INVALID_GRANT -> {
                // Marked for re-login inside refreshTokenClassified (credentials kept).
                null
            }
            RefreshOutcome.TRANSIENT -> {
                // Old token still usable if not actually expired. If it IS
                // expired, surface null so the caller can bubble up auth error.
                if (expireAt > 0 && now >= expireAt) null else token
            }
            RefreshOutcome.NO_TOKEN -> null
        }
    }

    /**
     * Save PKCE verifier for JSON token exchange. Uses 96 random bytes to match
     * iOS `ClaudeOAuthManager` exactly — Anthropic accepts 43–128-char verifiers,
     * the common tooling default is 96 bytes base64url-encoded (128 chars).
     */
    override fun generateAndSavePKCE(): Pair<String, String> {
        val (verifier, challenge) = generatePKCE(byteLength = 96)
        saveOAuthString("verifier", verifier)
        return verifier to challenge
    }

    /** Save state for JSON token exchange. */
    override fun generateAndSaveState(): String {
        val state = super.generateAndSaveState()
        saveOAuthString("state", state)
        return state
    }
}

// ─── Merged from GeminiOAuthManager.kt ──────────────────────────────────────────────────

class GeminiOAuthManager(context: Context, instanceId: String) : OAuthManager(context, instanceId) {
    companion object {
        private const val TAG = "GeminiOAuth"
        private const val USER_AGENT = "GeminiCLI/0.30.0 (android; aarch64)"

        /**
         * Proactive refresh window — mirrors [ClaudeOAuthManager] for consistent
         * behavior across Anthropic + Google OAuth. iOS Gemini uses a 0-second
         * buffer (only refreshes when actually expired); keeping 5 minutes here
         * avoids a race on Google's 1-hour access tokens.
         */
        private const val REFRESH_BUFFER_MS = 5 * 60 * 1000L

        /** Per-instance refresh mutex — serializes concurrent refresh calls. */
        private val refreshMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

        private fun mutexFor(instanceId: String): Mutex =
            refreshMutexes.getOrPut(instanceId) { Mutex() }
    }

    /**
     * Refresh outcome classification. Mirrors [ClaudeOAuthManager.RefreshOutcome]
     * for uniform dispatch at call sites that work with either provider.
     */
    enum class RefreshOutcome { SUCCESS, INVALID_GRANT, TRANSIENT, NO_TOKEN }

    override val authURL = "https://accounts.google.com/o/oauth2/v2/auth"
    override val tokenURL = "https://oauth2.googleapis.com/token"
    override val clientId = "681255809395-oo8ft2oprdrnp9e3aqf6av3hmdib135j.apps.googleusercontent.com"
    // Placeholder. Google OAuth requires a client secret alongside the
    // client id; supply your own from Google Cloud Console to use this
    // sign-in. API-key providers are unaffected.
    override val clientSecret = "GOCSPX-xxxxxxxxxxxxxxxxxxxxxxxxxxx"
    override val callbackPort = 8085
    override val redirectPath = "/oauth2callback"
    override val scopes = "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile"

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

    var email: String?
        get() = loadOAuthString("email")
        private set(value) { value?.let { saveOAuthString("email", it) } }

    var gcpProjectId: String?
        get() = loadOAuthString("gcp_project")
        private set(value) { value?.let { saveOAuthString("gcp_project", it) } }

    override suspend fun onTokensReceived(json: JSONObject) {
        fetchUserEmail()
    }

    private suspend fun fetchUserEmail() {
        val token = validAccessToken() ?: return
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

    suspend fun discoverProjectIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        if (!gcpProjectId.isNullOrEmpty()) return@withContext true
        val token = validAccessToken() ?: return@withContext false

        // Try loadCodeAssist first
        val projectId = loadCodeAssist(token) ?: onboardUser(token)
        if (projectId != null) {
            gcpProjectId = projectId
            return@withContext true
        }
        false
    }

    /** The headers the Cloud Code calls have always sent, in the same order. */
    private fun cloudCodeHeaders(token: String, json: Boolean): Map<String, String> = buildMap {
        put("Authorization", "Bearer $token")
        put("User-Agent", USER_AGENT)
        if (json) put("Content-Type", "application/json")
    }

    private fun loadCodeAssist(token: String): String? {
        return try {
            val response = com.openminis.app.network.NetworkUrlRequest.open(netTag, "https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist", "POST",
                cloudCodeHeaders(token, json = true), "{}".toByteArray()).use { r -> if (r.code == 200) r.text() else null }
            response?.let { JSONObject(it).optString("gcpProjectId").ifEmpty { null } }
        } catch (e: Exception) {
            Log.d(TAG, "loadCodeAssist failed: ${e.message}")
            null
        }
    }

    private suspend fun onboardUser(token: String): String? {
        return try {
            val body = com.openminis.app.network.NetworkUrlRequest.open(netTag, "https://cloudcode-pa.googleapis.com/v1internal:onboardUser", "POST",
                cloudCodeHeaders(token, json = true),
                """{"setupMetadata":{"ideType":"NEOVIM","platforms":["LINUX"]}}""".toByteArray(),
            ).use { r -> if (r.code == 200) r.text() else null }

            if (body != null) {
                val json = JSONObject(body)
                val opName = json.optString("name")
                if (opName.isNotEmpty()) {
                    pollOperation(token, opName)
                } else {
                    json.optString("gcpProjectId").ifEmpty { null }
                }
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "onboardUser failed", e)
            null
        }
    }

    private suspend fun pollOperation(token: String, operationName: String): String? {
        for (i in 0 until 24) {
            delay(5000)
            try {
                val body = com.openminis.app.network.NetworkUrlRequest.open(netTag, "https://cloudcode-pa.googleapis.com/v1internal/$operationName",
                    headers = cloudCodeHeaders(token, json = false)).use { r -> if (r.code == 200) r.text() else null }
                if (body != null) {
                    val json = JSONObject(body)
                    if (json.optBoolean("done")) {
                        return json.optJSONObject("response")?.optString("gcpProjectId")?.ifEmpty { null }
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    // ── Token refresh with classification + concurrency protection ──
    // Mirrors ClaudeOAuthManager's refresh machinery; see RefreshOutcome for
    // the error taxonomy.

    /** Boolean compatibility wrapper over [refreshTokenClassified]. */
    override suspend fun refreshToken(): Boolean =
        refreshTokenClassified() == RefreshOutcome.SUCCESS

    /**
     * Refresh with structured error classification. Serialized per-instance
     * so concurrent callers share a single network round-trip. Mirrors iOS
     * `GeminiOAuthManager.validAccessToken` error handling:
     *
     *  - `INVALID_GRANT` — Google returned 400/401/403 or `invalid_grant`/
     *    `invalid_token`: refresh token is revoked/expired. Stored credentials
     *    are kept and the instance is marked for re-login
     *    (`markNeedsReauth`); user must re-run OAuth.
     *  - `TRANSIENT` — network/5xx: credentials kept; caller may retry.
     *  - `NO_TOKEN` — no stored refresh token (not logged in).
     *  - `SUCCESS` — new access token persisted.
     */
    suspend fun refreshTokenClassified(): RefreshOutcome = withContext(Dispatchers.IO) {
        val mutex = mutexFor(instanceId)
        mutex.withLock {
            val stored = loadStoredTokens() ?: return@withLock RefreshOutcome.NO_TOKEN
            val refreshTokenValue = stored.optString("refresh_token", "")
                .ifEmpty { return@withLock RefreshOutcome.NO_TOKEN }

            // Coalesce concurrent refreshers: if another coroutine refreshed
            // while we were queued on the mutex, short-circuit with SUCCESS.
            val priorExpireAt = stored.optLong("expire_at", 0)
            val freshCheck = loadStoredTokens()?.optLong("expire_at", 0) ?: 0
            if (freshCheck > priorExpireAt && freshCheck > System.currentTimeMillis()) {
                Log.d(TAG, "Refresh skipped — fresher token already present (concurrent refresh coalesced)")
                return@withLock RefreshOutcome.SUCCESS
            }

            try {
                // Google's token endpoint accepts form-urlencoded (NOT JSON like Anthropic).
                val params = mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshTokenValue,
                    "client_id" to clientId,
                    "client_secret" to (clientSecret ?: ""),
                )
                val formBody = params.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
                val request = okhttp3.Request.Builder()
                    .url(tokenURL)
                    .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                    .build()
                val response = httpClient.newCall(request).execute()
                val responseCode = response.code
                val responseBody = response.body?.string() ?: ""
                response.close()

                if (responseCode in 200..299) {
                    val json = JSONObject(responseBody)
                    // Google may rotate or drop the refresh_token; preserve if absent.
                    if (!json.has("refresh_token")) {
                        json.put("refresh_token", refreshTokenValue)
                    }
                    val expiresIn = json.optLong("expires_in", 0)
                    if (expiresIn > 0) {
                        json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
                    }
                    saveOAuthString("tokens", json.toString())
                    Log.i(TAG, "Gemini token refresh successful. Expires in ${expiresIn}s")
                    return@withLock RefreshOutcome.SUCCESS
                }

                // Classify non-2xx response. Google's token endpoint returns 400
                // with `error=invalid_grant` for revoked/expired refresh tokens,
                // and `error=invalid_token` for truly malformed tokens. Both are
                // non-recoverable; user must re-auth.
                val bodyLower = responseBody.lowercase()
                val isInvalidGrant = responseCode == 400 || responseCode == 401 || responseCode == 403 ||
                    bodyLower.contains("invalid_grant") ||
                    bodyLower.contains("invalid_token")
                if (isInvalidGrant) {
                    // [T-oauth-keep-credentials] Never delete: mark for re-login.
                    Log.e(TAG, "Refresh token invalid ($responseCode): ${OAuthManager.sanitizeBody(responseBody)} — marking for re-login")
                    markNeedsReauth(refreshTokenValue)
                    return@withLock RefreshOutcome.INVALID_GRANT
                }
                Log.w(TAG, "Gemini token refresh transient failure ($responseCode): ${OAuthManager.sanitizeBody(responseBody)} — keeping token")
                return@withLock RefreshOutcome.TRANSIENT
            } catch (e: Exception) {
                Log.w(TAG, "Gemini token refresh transient error — keeping token", e)
                return@withLock RefreshOutcome.TRANSIENT
            }
        }
    }

    /**
     * Return a valid access token, refreshing within the 5-minute pre-expiry
     * window. Returns null if credentials are missing or invalid. Mirrors iOS
     * `GeminiOAuthManager.validAccessToken` behavior (with Android's wider
     * pre-refresh window — iOS Gemini refreshes only on hard expiry).
     */
    override suspend fun validAccessToken(): String? = withContext(Dispatchers.IO) {
        val stored = loadStoredTokens() ?: return@withContext null
        val token = stored.optString("access_token", "").ifEmpty { return@withContext null }
        val expireAt = stored.optLong("expire_at", 0)
        val now = System.currentTimeMillis()

        val needsRefresh = expireAt > 0 && (expireAt - now) <= REFRESH_BUFFER_MS
        if (!needsRefresh) return@withContext token

        when (refreshTokenClassified()) {
            RefreshOutcome.SUCCESS ->
                loadStoredTokens()?.optString("access_token", "")?.ifEmpty { null }
            RefreshOutcome.INVALID_GRANT -> null
            RefreshOutcome.TRANSIENT ->
                if (expireAt > 0 && now >= expireAt) null else token
            RefreshOutcome.NO_TOKEN -> null
        }
    }
}

// ─── Merged from OAuthTabLauncher.kt ──────────────────────────────────────────────────

/**
 * Opens a sign-in's authorize page in a Custom Tab (title shown) - the one
 * launch every browser sign-in shares (OpenAI, Claude, xAI, OpenRouter, MCP).
 *
 * Launched from the caller's context WITHOUT FLAG_ACTIVITY_NEW_TASK, so from
 * an Activity the tab sits above singleTask MainActivity in MainActivity's own
 * task. OAuthRedirectActivity relies on that (T-oauth-return-to-app): when the
 * browser hands the redirect to the app it brings MainActivity forward with
 * CLEAR_TOP, which closes the tab only because the tab is in that task.
 * [newTask] is for callers that may hold a non-Activity context.
 *
 * No CustomTabsSession / bindCustomTabsService here on purpose: a session was
 * only ever used to observe navigation events for diagnostics, and binding it
 * delayed opening the tab by up to a second.
 */
object OAuthTabLauncher {
    fun launch(context: Context, uri: Uri, newTask: Boolean = false) {
        val tabs = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        if (newTask) tabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        tabs.launchUrl(context, uri)
    }
}
