package com.openminis.app.mcp.oauth

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.openminis.app.auth.OAuthCallbackServer
import com.openminis.app.logging.AppLogger
import com.openminis.app.util.EncryptedPrefsFactory
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-android-mcp-oauth] Non-secret OAuth configuration for an MCP server,
 * round-tripped inside the server entry in servers.json. The client SECRET and
 * issued tokens never live here — those go to [MCPOAuthStore]
 * (EncryptedSharedPreferences). Mirrors iOS `MCPOAuthConfig`.
 */
data class MCPOAuthConfig(
    /** "static" = user-supplied client credentials (the manual form).
     *  "dynamic" = browser sign-in: discovery + dynamic client registration
     *  fill the rest in ([MCPOAuthDiscovery], iOS #432). */
    val mode: String = MODE_STATIC,
    val clientId: String = "",
    val authorizationEndpoint: String = "",
    val tokenEndpoint: String = "",
    /** Space-separated scopes, e.g. "openid email https://.../calendar". */
    val scopes: String? = null,
    /** Custom redirect URI; defaults to the loopback callback when null. */
    val redirectUri: String? = null,
) {
    val isConfigured: Boolean
        get() = clientId.isNotBlank() &&
            authorizationEndpoint.isNotBlank() &&
            tokenEndpoint.isNotBlank()

    /** A browser sign-in entry (saved even before its first sign-in, so the
     *  form reopens in that mode - iOS #432). */
    val isDynamic: Boolean get() = mode == MODE_DYNAMIC

    /**
     * The entry's `oauth` object. [T-mcp-oauth-signin-android] Written with the
     * keys iOS and the in-guest `minis-mcp-cli add --oauth-*` use (camelCase,
     * `redirectURI`), so a servers.json carried between platforms by a backup
     * keeps its sign-in settings. The snake_case keys earlier Android builds
     * wrote ride along, so a downgrade still finds them.
     */
    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode)
        put("clientId", clientId)
        put("authorizationEndpoint", authorizationEndpoint)
        put("tokenEndpoint", tokenEndpoint)
        scopes?.takeIf { it.isNotBlank() }?.let { put("scopes", it) }
        redirectUri?.takeIf { it.isNotBlank() }?.let { put("redirectURI", it) }
        put("client_id", clientId)
        put("authorization_endpoint", authorizationEndpoint)
        put("token_endpoint", tokenEndpoint)
        redirectUri?.takeIf { it.isNotBlank() }?.let { put("redirect_uri", it) }
    }

    companion object {
        const val MODE_STATIC = "static"
        const val MODE_DYNAMIC = "dynamic"

        /** Parse from the `oauth` object inside a server entry. Returns null
         *  when the object is absent, or is a manual block with nothing in it.
         *  Reads the iOS / CLI keys first, then the snake_case ones older
         *  Android builds wrote. */
        fun fromJson(obj: JSONObject?): MCPOAuthConfig? {
            if (obj == null) return null
            fun str(camel: String, snake: String): String =
                obj.optString(camel, "").ifBlank { obj.optString(snake, "") }
            val config = MCPOAuthConfig(
                mode = obj.optString("mode", MODE_STATIC).ifBlank { MODE_STATIC },
                clientId = str("clientId", "client_id"),
                authorizationEndpoint = str("authorizationEndpoint", "authorization_endpoint"),
                tokenEndpoint = str("tokenEndpoint", "token_endpoint"),
                scopes = obj.optString("scopes", "").ifBlank { null },
                redirectUri = str("redirectURI", "redirect_uri").ifBlank { null },
            )
            // A browser-mode block is kept even when empty: it is how the entry
            // says "sign in with the browser" before the first sign-in. A manual
            // block with no client and no endpoints is noise, not configuration.
            if (config.isDynamic) return config
            return if (config.clientId.isBlank() &&
                config.authorizationEndpoint.isBlank() &&
                config.tokenEndpoint.isBlank()
            ) {
                null
            } else {
                config
            }
        }
    }
}

// ─── Merged from MCPOAuthController.kt ──────────────────────────────────────────────────

// ios-source: src/ios/Agent/Session/MCPOAuthController.swift @ 678fee08a (authorize, signIn, resetSignIn, freshness, materializeBridge)


/**
 * [T-android-mcp-oauth] Drives MCP OAuth for a remote server: the PKCE
 * Authorization Code flow (RFC 7636 + RFC 8252 loopback redirect + RFC 8707
 * resource), reusing the app's [OAuthCallbackServer] + Custom Tab pattern.
 *
 * [T-mcp-oauth-signin-android] Port of iOS #432: [signIn] is the browser
 * sign-in as ONE action (discovery + dynamic client registration + the page +
 * the token exchange), a client the server forgot is registered again once,
 * and every way the page can end (closed, denied, unanswered, client or grant
 * refused) is named instead of surfacing as a raw message. A successful
 * sign-in also writes the guest token bridge
 * (`/var/minis/mcp-servers/oauth/<server>.json`) that the in-guest
 * minis-mcp-cli reads; without it the sandbox never saw the token.
 */
class MCPOAuthController(
    private val context: Context,
    private val secrets: McpOAuthSecrets = StoreMcpOAuthSecrets(context),
    dcrCache: McpDcrCache = PrefsMcpDcrCache(context),
    private val http: OkHttpClient = defaultHttp,
    private val bridgeDir: File = defaultBridgeDir(context),
    /** Opens the authorization page; tests answer the loopback themselves. */
    private val openBrowser: (String) -> Unit = { authUrl ->
        // Do NOT set FLAG_ACTIVITY_NEW_TASK — MainActivity is singleTask
        // (matches ClaudeOAuthManager's note about IME).
        com.openminis.app.auth.OAuthTabLauncher.launch(context, Uri.parse(authUrl))
    },
    /**
     * [T-android-mcp-oauth-foreground-exchange] The loopback callback lands
     * while the user is still in the Custom Tab, i.e. with Minis in the
     * background, where Android blocks the app's network
     * (blocked=APP_BACKGROUND): the exchange failed in ~10 ms with "Unable to
     * resolve host". Same gate the provider OAuth managers use (dcf6085db) —
     * exchange only once the user is back.
     */
    private val awaitForeground: suspend () -> Unit = {
        com.openminis.app.auth.OAuthForegroundGate.awaitForeground(TAG)
    },
    private val callbackTimeoutMs: Long = CALLBACK_TIMEOUT_MS,
    /**
     * Calls back when the user has closed the authorization page, and returns
     * how to stop watching. Custom Tabs report no "dismissed", so the default
     * ([watchForReturnWithoutCallback]) treats Minis coming back to the
     * foreground with no redirect as the page being closed (xAI's
     * [T-xai-oauth-stop-resume] detector; iOS SFSafariViewController didFinish).
     */
    private val watchDismissal: (onDismissed: () -> Unit) -> (() -> Unit) = ::watchForReturnWithoutCallback,
) {

    val discovery = MCPOAuthDiscovery(secrets, dcrCache, http)

    /** How an authorization ended. Every case but [Success] leaves the tokens
     *  as they were, except [GrantRejected], which clears them. */
    sealed class Result {
        object Success : Result()

        /** The user closed the page. */
        object Cancelled : Result()

        /** The user pressed Deny on the consent page (`access_denied`). */
        object Denied : Result()

        /** The page was never answered within [CALLBACK_TIMEOUT_MS]. */
        object TimedOut : Result()

        /** `invalid_client` / `unauthorized_client`: the service does not know
         *  this client (any more). A browser sign-in registers again; a typed
         *  Client ID is the user's to correct. */
        object ClientRejected : Result()

        /** `invalid_grant`: the code was refused; the tokens are gone. */
        object GrantRejected : Result()
        data class Failed(val message: String) : Result()
    }

    /** The steps of one sign-in, for the form's status line. */
    enum class SignInPhase { DISCOVERING, REGISTERING_AGAIN, WAITING_FOR_BROWSER, FINISHING }

    sealed class SignInOutcome {
        data class SignedIn(val config: MCPOAuthConfig) : SignInOutcome()

        /** Discovery or registration did not produce a client (kind
         *  DCR_UNSUPPORTED is the documented fallback to the manual form). */
        data class DiscoveryFailed(val error: McpDiscoveryException) : SignInOutcome()

        /** The page or the token exchange ended without a sign-in. */
        data class NotSignedIn(val result: Result) : SignInOutcome()
    }

    enum class Freshness {
        /** The token is usable (or carries no expiry to judge by). */
        FRESH,

        /** It had expired and was renewed just now. */
        RENEWED,

        /** It had expired and the service refused to renew it. */
        NEEDS_SIGN_IN,

        /** Could not tell (offline, the service did not answer). */
        UNKNOWN,
    }

    @Volatile
    private var callbackServer: OAuthCallbackServer? = null

    /** What the wait for the browser's redirect produced. */
    private sealed class Callback {
        data class Code(val code: String, val state: String?) : Callback()
        object Cancelled : Callback()
    }

    /**
     * Run the authorize flow for [server] against [oauth]. [serverUrl] is the
     * server's own URL, sent as the RFC 8707 resource indicator (canonical).
     * Opens the authorization page, waits (up to 5 min) for the loopback
     * callback, verifies state, exchanges the code, stores the tokens and
     * writes the guest bridge. [onExchange] runs once the browser has answered
     * and the token request starts. Never throws — returns a [Result].
     */
    suspend fun authorize(
        server: String,
        serverUrl: String?,
        oauth: MCPOAuthConfig,
        expectedIssuer: String? = null,
        onExchange: () -> Unit = {},
    ): Result {
        // [T-mcp-oauth-bridge-name] Refuse before the page opens, not after
        // the user has signed in and the bridge write fails.
        if (!isBridgeSafeName(server)) {
            return Result.Failed("This server's name can't be used for sign-in. Rename it without '/' and try again.")
        }
        // [T-mcp-oauth-never-throws] An endpoint that is not a valid URL made
        // OkHttp's Request.Builder.url / HttpUrl throw IllegalArgumentException
        // out of here, into the form's coroutine, which has no handler - the
        // app crashed. Every such failure is a Result now; cancellation still
        // propagates.
        return try {
            authorizeUnchecked(server, serverUrl, oauth, expectedIssuer, onExchange)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.error(TAG, "[Authorize] '$server' failed: ${e.javaClass.simpleName}: ${e.message}")
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun authorizeUnchecked(
        server: String,
        serverUrl: String?,
        oauth: MCPOAuthConfig,
        expectedIssuer: String?,
        onExchange: () -> Unit,
    ): Result {
        if (!oauth.isConfigured) {
            return Result.Failed("OAuth is not fully configured (client id + endpoints required).")
        }
        val redirect = redirectUri(oauth)
        val redirectHost = runCatching { URI(redirect).host?.lowercase() }.getOrNull()
        if (redirectHost != "localhost" && redirectHost != "127.0.0.1") {
            return Result.Failed("Redirect URI must be a loopback address, e.g. $DEFAULT_REDIRECT_URI.")
        }
        val port = runCatching { URI(redirect).port.takeIf { it > 0 } }.getOrNull() ?: LOOPBACK_PORT
        val resource = McpPkce.canonicalResourceUri(serverUrl)
        val pkce = McpPkce.newPkce()
        val authUrl = McpPkce.buildAuthorizationUrl(
            authorizationEndpoint = oauth.authorizationEndpoint,
            clientId = oauth.clientId,
            redirectUri = redirect,
            pkce = pkce,
            resource = resource,
            scopes = oauth.scopes,
        )

        return withContext(Dispatchers.IO) {
            callbackServer?.stop()
            callbackServer = null

            var stopWatching: () -> Unit = {}
            val callback = try {
                withTimeoutOrNull(callbackTimeoutMs) {
                    suspendCancellableCoroutine<Callback> { cont ->
                        val srv = OAuthCallbackServer(
                            port,
                            // [T-oauth-callback-double-resume] Denied / failed on the
                            // provider's page: end the sign-in now with its reason.
                            onError = { err -> if (cont.isActive) cont.resumeWithException(err.toException(context)) },
                        ) { code, state ->
                            if (cont.isActive) cont.resume(Callback.Code(code, state))
                        }
                        callbackServer = srv
                        // If the user dismisses the Custom Tab, stop() fires this so
                        // we don't hang until the OS eventually tears the socket down.
                        srv.onExternalCancel = { if (cont.isActive) cont.resume(Callback.Cancelled) }
                        srv.start()
                        AppLogger.info(TAG, "[Authorize] '$server' callback server on :$port")

                        cont.invokeOnCancellation {
                            srv.stop()
                            callbackServer = null
                        }
                        openBrowser(authUrl)
                        AppLogger.info(TAG, "[Authorize] '$server' opened the authorization page")
                        // [T-mcp-oauth-tab-closed-android] Closing the page used
                        // to leave the form on "Waiting for you to sign in"
                        // until the 300 s timeout (A3 device test, bug 1).
                        stopWatching = watchDismissal {
                            if (callbackServer === srv) {
                                AppLogger.info(TAG, "[Authorize] '$server' Minis is back without an answer - the page was closed")
                                srv.stop()
                            }
                        }
                    }
                }
            } catch (e: com.openminis.app.auth.OAuthCallbackException) {
                // [T-mcp-oauth-signin-android] The page's own answer decides the
                // next step: Deny is a decision, a rejected client may be
                // registered again, the rest is shown as the service said it.
                AppLogger.warning(TAG, "[Authorize] '$server' callback error: ${e.callbackError.error}")
                return@withContext when (e.callbackError.error) {
                    "access_denied" -> Result.Denied
                    "invalid_client", "unauthorized_client" -> Result.ClientRejected
                    else -> Result.Failed(e.message ?: e.callbackError.error)
                }
            } finally {
                stopWatching()
                callbackServer?.stop()
                callbackServer = null
            }

            val answer = when (callback) {
                null -> {
                    AppLogger.error(TAG, "[Authorize] '$server' no answer from the sign-in page in ${callbackTimeoutMs / 1000}s")
                    return@withContext Result.TimedOut
                }
                Callback.Cancelled -> return@withContext Result.Cancelled
                is Callback.Code -> callback
            }
            if (answer.state != null && answer.state != pkce.state) {
                AppLogger.warning(TAG, "[Authorize] '$server' state mismatch")
                return@withContext Result.Failed("State mismatch in the OAuth callback.")
            }
            // [expectedIssuer] is not checked here: the shared loopback server
            // hands back the code and the state only, so there is no `iss` to
            // compare (iOS checks it on its custom-scheme path only).
            AppLogger.info(TAG, "[Authorize] '$server' answered (issuer=${expectedIssuer ?: "-"})")

            onExchange()
            awaitForeground()
            exchangeCode(server, oauth, redirect, resource, answer.code, pkce.verifier)
        }
    }

    private fun exchangeCode(
        server: String,
        oauth: MCPOAuthConfig,
        redirect: String,
        resource: String?,
        code: String,
        verifier: String,
    ): Result {
        val body = buildTokenExchangeBody(
            code = code,
            redirect = redirect,
            clientId = oauth.clientId,
            verifier = verifier,
            clientSecret = secrets.clientSecret(server),
            resource = resource,
        )
        val request = Request.Builder()
            .url(oauth.tokenEndpoint)
            .post(body.toRequestBody(FORM))
            .build()

        val (status, text) = try {
            http.newCall(request).execute().use { it.code to (it.body?.string().orEmpty()) }
        } catch (t: Throwable) {
            AppLogger.error(TAG, "[Authorize] '$server' token exchange failed: ${t.message}")
            return Result.Failed("Token exchange failed: ${t.message}")
        }
        if (status >= 400) {
            // [T-mcp-oauth-signin-android] The error code decides what happens
            // next, so it is read instead of being shown as a raw body.
            return when (val failure = MCPOAuthSignInLogic.tokenFailure(text, status)) {
                MCPOAuthSignInLogic.TokenFailure.ClientRejected -> {
                    AppLogger.error(TAG, "[Authorize] '$server' token exchange HTTP $status: the client was rejected")
                    Result.ClientRejected
                }
                MCPOAuthSignInLogic.TokenFailure.GrantRejected -> {
                    AppLogger.error(TAG, "[Authorize] '$server' token exchange HTTP $status: invalid_grant - tokens cleared")
                    signOut(server)
                    Result.GrantRejected
                }
                is MCPOAuthSignInLogic.TokenFailure.Other -> {
                    AppLogger.error(TAG, "[Authorize] '$server' token exchange HTTP $status")
                    Result.Failed("Token exchange failed: ${failure.detail}")
                }
            }
        }
        val json = runCatching { JSONObject(text) }.getOrNull()
        val access = json?.optString("access_token", "").orEmpty()
        if (access.isEmpty()) return Result.Failed("Token endpoint returned no access_token.")
        val expiresIn = json?.optLong("expires_in", 0L) ?: 0L
        val stored = MCPOAuthStore.StoredTokens(
            accessToken = access,
            refreshToken = json?.optString("refresh_token", "")?.ifBlank { null },
            expiresAtMs = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L,
        )
        secrets.setTokens(server, stored)
        materializeBridge(server, oauth, resource, stored)?.let { return Result.Failed(it) }
        AppLogger.info(TAG, "[Authorize] '$server' OK (hasRefresh=${stored.refreshToken != null}, expiresIn=${expiresIn}s)")
        return Result.Success
    }

    // -- Browser sign-in [T-mcp-oauth-signin-android] --

    /**
     * "Sign in with browser" as ONE action (GitHub #432): find the
     * authorization server from the MCP server's own 401, register this app
     * with it (or reuse the registration), open the sign-in page, exchange the
     * code and store the tokens. [persist] saves the server entry with the
     * discovered `oauth` block before the page opens - the guest reads the
     * saved entry. A client the service no longer knows is forgotten and
     * registered again, once.
     */
    suspend fun signIn(
        server: String,
        serverUrl: String,
        persist: (MCPOAuthConfig) -> Unit,
        progress: (SignInPhase) -> Unit,
    ): SignInOutcome {
        var forceRegister = false
        var attempt = 0
        while (true) {
            progress(if (forceRegister) SignInPhase.REGISTERING_AGAIN else SignInPhase.DISCOVERING)
            val outcome = try {
                discovery.discover(serverUrl, DEFAULT_REDIRECT_URI, forceRegister)
            } catch (e: McpDiscoveryException) {
                AppLogger.info(TAG, "[SignIn] '$server' discovery ended: ${e.message}")
                return SignInOutcome.DiscoveryFailed(e)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                // [T-mcp-oauth-never-throws] e.g. metadata naming an endpoint
                // that is not a URL; the form shows it instead of crashing.
                AppLogger.error(TAG, "[SignIn] '$server' discovery failed: ${e.javaClass.simpleName}: ${e.message}")
                return SignInOutcome.NotSignedIn(Result.Failed(e.message ?: e.javaClass.simpleName))
            }
            persist(outcome.config)
            // The registration's secret (usually none) is this server's secret
            // now; one left by an earlier manual setup must not ride along with
            // a client it does not belong to.
            secrets.setClientSecret(server, outcome.clientSecret)
            progress(SignInPhase.WAITING_FOR_BROWSER)
            val result = authorize(server, serverUrl, outcome.config, outcome.issuer) {
                progress(SignInPhase.FINISHING)
            }
            when {
                result is Result.Success -> return SignInOutcome.SignedIn(outcome.config)
                result is Result.ClientRejected &&
                    MCPOAuthSignInLogic.registersAgain(attempt, dynamic = true) -> {
                    AppLogger.info(TAG, "[SignIn] '$server': issuer=${outcome.issuer} no longer knows this client - registering again")
                    discovery.forgetClientId(outcome.issuer)
                    forceRegister = true
                    attempt += 1
                }
                else -> return SignInOutcome.NotSignedIn(result)
            }
        }
    }

    /** "Reset Sign-In": sign out and forget the registration the entry's
     *  `client_id` came from, so the next sign-in registers afresh. */
    fun resetSignIn(server: String, clientId: String) {
        purge(server)
        val forgotten = discovery.forgetClient(clientId)
        AppLogger.info(TAG, "[SignIn] reset for '$server' (registrations forgotten: $forgotten)")
    }

    /** Sign out: drop the tokens and the guest bridge. The client secret is
     *  configuration, not a session, and is kept. */
    fun signOut(server: String) {
        secrets.signOut(server)
        bridgeFile(server)?.delete()
    }

    /** Full cleanup when the server entry is deleted. */
    fun purge(server: String) {
        secrets.purge(server)
        bridgeFile(server)?.delete()
    }

    fun isAuthorized(server: String): Boolean = secrets.tokens(server)?.accessToken?.isNotEmpty() == true

    /**
     * Whether the saved sign-in still works, renewing an expired token. The
     * sandbox renews tokens on its own while it talks to the server; this is
     * for the settings form, which would otherwise show "Signed in" over a
     * sign-in the service has already dropped. The bridge file is read first:
     * it holds the newest tokens when the service rotates refresh tokens.
     */
    suspend fun freshness(server: String, oauth: MCPOAuthConfig, serverUrl: String?): Freshness =
        withContext(Dispatchers.IO) {
            var stored = secrets.tokens(server) ?: return@withContext Freshness.NEEDS_SIGN_IN
            var clientSecret = secrets.clientSecret(server)
            var resource = McpPkce.canonicalResourceUri(serverUrl)
            readBridge(server)?.let { b ->
                val access = b.optString("access_token", "")
                if (access.isNotEmpty()) {
                    stored = stored.copy(
                        accessToken = access,
                        refreshToken = b.optString("refresh_token", "").ifEmpty { stored.refreshToken },
                        expiresAtMs = if (b.has("expires_at")) b.optLong("expires_at") * 1000 else stored.expiresAtMs,
                    )
                    clientSecret = b.optString("client_secret", "").ifEmpty { clientSecret }
                    resource = b.optString("resource", "").ifEmpty { resource }
                }
            }
            if (stored.expiresAtMs <= 0 || System.currentTimeMillis() <= stored.expiresAtMs - 60_000) {
                return@withContext Freshness.FRESH
            }
            val refresh = stored.refreshToken
            if (refresh.isNullOrEmpty() || oauth.tokenEndpoint.isBlank() || oauth.clientId.isBlank()) {
                AppLogger.info(TAG, "[Refresh] '$server' expired and has nothing to renew it with")
                signOut(server)
                return@withContext Freshness.NEEDS_SIGN_IN
            }
            val form = linkedMapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
                "client_id" to oauth.clientId,
            )
            if (!clientSecret.isNullOrEmpty()) form["client_secret"] = clientSecret!!
            if (!resource.isNullOrEmpty()) form["resource"] = resource!!
            val request = runCatching {
                Request.Builder().url(oauth.tokenEndpoint).post(formBody(form).toRequestBody(FORM)).build()
            }.getOrNull() ?: return@withContext Freshness.UNKNOWN
            val (status, text) = try {
                http.newCall(request).execute().use { it.code to (it.body?.string().orEmpty()) }
            } catch (t: Throwable) {
                AppLogger.info(TAG, "[Refresh] '$server' could not reach the token endpoint")
                return@withContext Freshness.UNKNOWN
            }
            if (status >= 400) {
                return@withContext when (MCPOAuthSignInLogic.tokenFailure(text, status)) {
                    MCPOAuthSignInLogic.TokenFailure.ClientRejected,
                    MCPOAuthSignInLogic.TokenFailure.GrantRejected -> {
                        AppLogger.error(TAG, "[Refresh] '$server' HTTP $status: the sign-in is no longer accepted - signed out")
                        signOut(server)
                        Freshness.NEEDS_SIGN_IN
                    }
                    is MCPOAuthSignInLogic.TokenFailure.Other -> {
                        AppLogger.error(TAG, "[Refresh] '$server' HTTP $status")
                        Freshness.UNKNOWN
                    }
                }
            }
            val obj = runCatching { JSONObject(text) }.getOrNull()
            val access = obj?.optString("access_token", "").orEmpty()
            if (access.isEmpty()) {
                AppLogger.error(TAG, "[Refresh] '$server' answered without an access_token")
                return@withContext Freshness.UNKNOWN
            }
            val expiresIn = obj?.optLong("expires_in", 0L) ?: 0L
            val renewed = MCPOAuthStore.StoredTokens(
                accessToken = access,
                refreshToken = obj?.optString("refresh_token", "")?.ifEmpty { null } ?: refresh,
                expiresAtMs = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L,
            )
            secrets.setTokens(server, renewed)
            materializeBridge(server, oauth, resource, renewed)?.let {
                AppLogger.error(TAG, "[Refresh] '$server' renewed but the sandbox copy failed: $it")
            }
            AppLogger.info(TAG, "[Refresh] '$server' renewed (expiresIn=${expiresIn}s)")
            Freshness.RENEWED
        }

    /** Re-write the bridge after an edit (e.g. a new client secret or URL) so
     *  the guest's refresh path sees the current material. Best effort. */
    fun refreshBridgeIfAuthorized(server: String, oauth: MCPOAuthConfig, serverUrl: String?) {
        val t = secrets.tokens(server) ?: return
        materializeBridge(server, oauth, McpPkce.canonicalResourceUri(serverUrl), t)?.let {
            AppLogger.error(TAG, "[Bridge] refresh for '$server' failed: $it")
        }
    }

    // -- Guest bridge file --

    /**
     * The bridge file for [server], or null when the name cannot be a file in
     * [bridgeDir]. [T-mcp-oauth-bridge-name] The name comes from servers.json,
     * which the guest can write: a '/' in it would make the host write or
     * delete `.json` files outside the bridge directory. The guest reads
     * `<OAUTH_DIR>/<name>.json` verbatim, so such a name is refused rather
     * than renamed - a renamed file is one the guest would never find.
     */
    fun bridgeFile(server: String): File? {
        if (!isBridgeSafeName(server)) return null
        val file = File(bridgeDir, "$server.json")
        // Belt and braces: the file must sit directly in the bridge directory.
        val inDir = runCatching { file.canonicalFile.parentFile == bridgeDir.canonicalFile }.getOrDefault(false)
        return if (inDir) file else null
    }

    private fun readBridge(server: String): JSONObject? =
        bridgeFile(server)?.let { f -> runCatching { JSONObject(f.readText()) }.getOrNull() }

    /**
     * Write the bridge the guest transport reads (refresh material included,
     * so the daemon renews tokens without the app) and read it back. Returns
     * why it failed, or null. [issue #380] An authorization is never reported
     * as successful while the guest cannot see the token.
     */
    private fun materializeBridge(
        server: String,
        oauth: MCPOAuthConfig,
        resource: String?,
        tokens: MCPOAuthStore.StoredTokens,
    ): String? {
        val obj = JSONObject().apply {
            put("access_token", tokens.accessToken)
            put("expires_at", tokens.expiresAtMs / 1000)
            put("token_endpoint", oauth.tokenEndpoint)
            put("client_id", oauth.clientId)
            tokens.refreshToken?.let { put("refresh_token", it) }
            secrets.clientSecret(server)?.takeIf { it.isNotEmpty() }?.let { put("client_secret", it) }
            // RFC 8707: the guest's refresh grant sends the same resource.
            resource?.let { put("resource", it) }
        }
        val file = bridgeFile(server) ?: run {
            AppLogger.error(TAG, "[Bridge] refused server name '$server': not a file name in the bridge directory")
            return "This server's name can't be used for sign-in. Rename it without '/' and try again."
        }
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(obj.toString())
            tmp.setReadable(false, false)
            tmp.setReadable(true, true)
            tmp.setWritable(false, false)
            tmp.setWritable(true, true)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw java.io.IOException("could not move ${tmp.name} into place")
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "[Bridge] write failed for '$server': ${e.message}")
            return "Could not save the authorization for the sandbox to read. (${e.message})"
        }
        val readBack = readBridge(server)?.optString("access_token", "")
        if (readBack != tokens.accessToken) {
            AppLogger.error(TAG, "[Bridge] verify failed for '$server' at ${file.path}")
            return "The saved authorization could not be verified."
        }
        AppLogger.info(TAG, "[Bridge] materialized + verified for '$server' (expiresAt=${tokens.expiresAtMs / 1000})")
        return null
    }

    private fun redirectUri(oauth: MCPOAuthConfig): String =
        oauth.redirectUri?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_REDIRECT_URI

    companion object {
        private const val TAG = "MCPOAuthController"

        /** How long a redirect that is already on its way may take to land
         *  after Minis is back in front (xAI uses the same 1.5 s). */
        private const val DISMISSAL_GRACE_MS = 1_500L

        /**
         * Watches the process come back to the foreground after the page took
         * it: [onDismissed] runs [DISMISSAL_GRACE_MS] later, and the caller
         * ignores it when the redirect has arrived meanwhile. Observer
         * add/remove are main-thread only.
         */
        fun watchForReturnWithoutCallback(onDismissed: () -> Unit): () -> Unit {
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
                // The first ON_RESUME after adding the observer is replayed for
                // the activity already in front; only one that follows a
                // pause / stop is the user coming back.
                @Volatile var sawBackgrounded = false

                override fun onPause(owner: androidx.lifecycle.LifecycleOwner) { sawBackgrounded = true }
                override fun onStop(owner: androidx.lifecycle.LifecycleOwner) { sawBackgrounded = true }
                override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                    if (!sawBackgrounded) return
                    sawBackgrounded = false
                    main.postDelayed({ onDismissed() }, DISMISSAL_GRACE_MS)
                }
            }
            val lifecycle = runCatching { androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle }.getOrNull()
                ?: return {}
            main.post { lifecycle.addObserver(observer) }
            return { main.post { lifecycle.removeObserver(observer) } }
        }
        private val FORM = "application/x-www-form-urlencoded".toMediaType()

        /** How long the sign-in page may stay unanswered (iOS: 300 s). */
        const val CALLBACK_TIMEOUT_MS = 300_000L

        private val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /** A name that is one plain file name: not blank, no path separator or
         *  NUL, and not a dot entry (`.` / `..` would still be one file with
         *  the `.json` suffix, but nothing legitimate is called that). */
        fun isBridgeSafeName(server: String): Boolean =
            server.isNotBlank() && server != "." && server != ".." &&
                server.none { it == '/' || it == '\\' || it == '\u0000' }

        /** Host side of `/var/minis/mcp-servers/oauth` (PRootKernel binds
         *  `minis-global/mcp-servers` there). */
        fun defaultBridgeDir(context: Context): File =
            File(context.filesDir, "minis-global/mcp-servers/oauth")

        /**
         * Assemble the form-urlencoded token-exchange body for the
         * authorization_code grant (MCP auth spec / RFC 6749). client_secret is
         * included only when present; the RFC 8707 `resource` only when non-null.
         * Extracted for unit testing. Mirrors iOS authorize()'s token form.
         */
        internal fun buildTokenExchangeBody(
            code: String,
            redirect: String,
            clientId: String,
            verifier: String,
            clientSecret: String?,
            resource: String?,
        ): String {
            val form = linkedMapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to redirect,
                "client_id" to clientId,
                "code_verifier" to verifier,
            )
            if (!clientSecret.isNullOrEmpty()) form["client_secret"] = clientSecret
            if (!resource.isNullOrEmpty()) form["resource"] = resource
            return formBody(form)
        }

        private fun formBody(form: Map<String, String>): String =
            form.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

        /** Fixed loopback port for MCP OAuth — distinct from ClaudeOAuthManager's
         *  54545 so a concurrent login never collides. Mirrors iOS 54546. */
        const val LOOPBACK_PORT = 54546
        const val DEFAULT_REDIRECT_URI = "http://localhost:$LOOPBACK_PORT/callback"
    }
}

// ─── Merged from MCPOAuthDiscovery.kt ──────────────────────────────────────────────────

// ios-source: src/ios/Agent/Session/MCPOAuthDiscovery.swift @ 678fee08a (MCPOAuthDiscovery)


/**
 * [T-mcp-oauth-signin-android] Discovery + Dynamic Client Registration for a
 * remote MCP server - what lets a user connect WITHOUT pasting a Client ID.
 * Port of iOS `MCPOAuthDiscovery` (issue #315 / #319, #432).
 *
 * Chain (MCP authorization spec, 2025-06-18):
 *   1. unauthenticated request -> 401 + `WWW-Authenticate: Bearer
 *      resource_metadata="..."`                                 (RFC 9728 §5.1)
 *   2. GET that URL -> `authorization_servers[]`                (RFC 9728)
 *   3. GET `<AS>/.well-known/oauth-authorization-server`        (RFC 8414)
 *   4. `registration_endpoint` advertised -> POST it            (RFC 7591)
 *      else -> [McpDiscoveryException.Kind.DCR_UNSUPPORTED], and the form
 *      falls back to the manual fields.
 *
 * No token work here: the output is an [MCPOAuthConfig] the existing
 * authorize flow takes.
 */
class MCPOAuthDiscovery(
    private val secrets: McpOAuthSecrets,
    private val cache: McpDcrCache,
    private val http: OkHttpClient = defaultClient,
    private val now: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {

    /** What a discovery attempt produced. */
    data class Outcome(
        /** Ready for [MCPOAuthController.authorize]. */
        val config: MCPOAuthConfig,
        /** Authorization-server issuer: the cache key, and the RFC 9207 check. */
        val issuer: String,
        /** True when the client_id came from the cache, not a fresh POST. */
        val reusedCachedClient: Boolean,
        /** The registration's `client_secret`, when the server issued one (a
         *  public client rarely gets one). The sign-in copies it to the
         *  server's own secret, where the token requests look. */
        val clientSecret: String?,
    )

    // -- Registered-client cache --

    fun cachedClient(issuer: String, defaultRedirectUri: String): MCPOAuthSignInLogic.ClientRecord? =
        MCPOAuthSignInLogic.record(issuer, cache.v2(), cache.v1(), defaultRedirectUri)

    private fun cacheClient(record: MCPOAuthSignInLogic.ClientRecord, issuer: String) {
        val map = cache.v2() ?: JSONObject()
        map.put(issuer, record.toJson())
        cache.setV2(map)
    }

    /** Drop a cached registration - when an authorization server rejects a
     *  `client_id` it once issued, so the next attempt registers again instead
     *  of failing forever on a dead id. Both cache generations go, or the v1
     *  entry would bring the dead id back. */
    fun forgetClientId(issuer: String) {
        cache.v2()?.takeIf { it.has(issuer) }?.let { it.remove(issuer); cache.setV2(it) }
        cache.v1()?.takeIf { it.has(issuer) }?.let { it.remove(issuer); cache.setV1(it) }
        secrets.setRegistrationSecret(issuer, null)
        AppLogger.info(TAG, "[DCR] dropped cached client for issuer=$issuer")
    }

    /** "Reset Sign-In" for a server entry: forget the registration its
     *  `client_id` came from. Returns how many were forgotten. */
    fun forgetClient(clientId: String): Int {
        val issuers = MCPOAuthSignInLogic.issuers(clientId, cache.v2(), cache.v1())
        issuers.forEach { forgetClientId(it) }
        return issuers.size
    }

    // -- Public entry --

    /**
     * Run the whole chain for [serverUrl] and return a config with a
     * `client_id` filled in. Throws [McpDiscoveryException] (kind
     * DCR_UNSUPPORTED when the service has no automatic registration - the
     * caller shows the manual form). [forceRegister] skips the cached
     * registration: the sign-in passes it after the server rejected it.
     */
    suspend fun discover(serverUrl: String, redirectUri: String, forceRegister: Boolean = false): Outcome =
        withContext(Dispatchers.IO) {
            val mcpUrl = serverUrl.trim().toHttpUrlOrNull()
                ?: throw McpDiscoveryException(McpDiscoveryException.Kind.BAD_SERVER_URL)

            // 1. Unauthenticated probe. A 401 is the EXPECTED answer here.
            AppLogger.info(TAG, "[DCR] probing $mcpUrl")
            val resourceMetaUrl = probeForResourceMetadata(mcpUrl.toString())

            // 2. RFC 9728 -> authorization_servers[]
            val resourceMeta = getJson(resourceMetaUrl, "protected-resource")
            val servers = resourceMeta.optJSONArray("authorization_servers")
            val asBase = servers?.optString(0, "").orEmpty()
            if (asBase.isEmpty()) throw McpDiscoveryException(McpDiscoveryException.Kind.NO_AUTHORIZATION_SERVER)
            // Several are allowed; the first is the spec's own ordering hint.
            if ((servers?.length() ?: 0) > 1) {
                AppLogger.info(TAG, "[DCR] ${servers?.length()} authorization servers offered - using the first: $asBase")
            }

            // 3. RFC 8414, with the OIDC path as a documented fallback.
            val meta = fetchAsMetadata(asBase)
            val issuer = meta.optString("issuer", "").ifEmpty { asBase }
            val authEndpoint = meta.optString("authorization_endpoint", "")
            val tokenEndpoint = meta.optString("token_endpoint", "")
            if (authEndpoint.isEmpty() || tokenEndpoint.isEmpty()) {
                throw McpDiscoveryException(McpDiscoveryException.Kind.METADATA_INCOMPLETE)
            }
            // PKCE S256 is mandatory under OAuth 2.1: a server that lists its
            // methods without S256 cannot complete this flow, so say so now.
            meta.optJSONArray("code_challenge_methods_supported")?.let { methods ->
                if ((0 until methods.length()).none { methods.optString(it) == "S256" }) {
                    throw McpDiscoveryException(McpDiscoveryException.Kind.NO_PKCE)
                }
            }

            // 4. DCR, or the documented fallback.
            val registration = meta.optString("registration_endpoint", "")
            if (registration.isEmpty()) {
                AppLogger.info(TAG, "[DCR] issuer=$issuer advertises no registration_endpoint - manual fallback")
                throw McpDiscoveryException(McpDiscoveryException.Kind.DCR_UNSUPPORTED, issuer)
            }

            // A registration is reused only while it can still work: same
            // redirect URI, secret not expired, and not just rejected.
            val cached = cachedClient(issuer, redirectUri)
            val decision = if (forceRegister) {
                MCPOAuthSignInLogic.Reuse.RegisterAgain("the authorization server rejected the cached client")
            } else {
                MCPOAuthSignInLogic.reuse(cached, redirectUri, now())
            }
            val clientId: String
            val clientSecret: String?
            val reused: Boolean
            when (decision) {
                is MCPOAuthSignInLogic.Reuse.Cached -> {
                    AppLogger.info(TAG, "[DCR] reusing cached client for issuer=$issuer")
                    clientId = cached?.clientId.orEmpty()
                    clientSecret = secrets.registrationSecret(issuer)
                    reused = true
                    // A registration an older build made (v1: the client_id
                    // alone) is written in the new form the first time it is
                    // used again.
                    if (cached != null && MCPOAuthSignInLogic.ClientRecord.fromJson(cache.v2()?.opt(issuer)) == null) {
                        cacheClient(cached, issuer)
                        AppLogger.info(TAG, "[DCR] carried the v1 registration for issuer=$issuer over to v2")
                    }
                }
                is MCPOAuthSignInLogic.Reuse.RegisterAgain -> {
                    if (cached != null) {
                        AppLogger.info(TAG, "[DCR] not reusing the cached client for issuer=$issuer: ${decision.reason}")
                    }
                    val made = register(registration, redirectUri)
                    clientId = made.clientId
                    clientSecret = made.clientSecret
                    reused = false
                    cacheClient(
                        MCPOAuthSignInLogic.ClientRecord(made.clientId, redirectUri, now(), made.secretExpiresAt),
                        issuer,
                    )
                    secrets.setRegistrationSecret(issuer, made.clientSecret)
                    AppLogger.info(TAG, "[DCR] registered new client for issuer=$issuer (secret=${made.clientSecret != null})")
                }
            }

            val scopes = meta.optJSONArray("scopes_supported")
                ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).ifEmpty { null } } }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(" ")
            Outcome(
                config = MCPOAuthConfig(
                    mode = MCPOAuthConfig.MODE_DYNAMIC,
                    clientId = clientId,
                    authorizationEndpoint = authEndpoint,
                    tokenEndpoint = tokenEndpoint,
                    scopes = scopes,
                    redirectUri = redirectUri,
                ),
                issuer = issuer,
                reusedCachedClient = reused,
                clientSecret = clientSecret,
            )
        }

    // -- Steps --

    /** Send an unauthenticated request and read the challenge. Falls back to
     *  the well-known path when a server 401s without the header - some omit
     *  it although RFC 9728 §5.1 says MUST. */
    private fun probeForResourceMetadata(mcpUrl: String): String {
        val req = Request.Builder()
            .url(mcpUrl)
            .post(PROBE_BODY.toRequestBody(JSON))
            .header("Accept", "application/json, text/event-stream")
            .build()
        val (status, header) = try {
            routedHttp().newCall(req).execute().use { it.code to it.header("WWW-Authenticate").orEmpty() }
        } catch (e: Exception) {
            throw McpDiscoveryException(McpDiscoveryException.Kind.NETWORK, e.message ?: e.javaClass.simpleName)
        }
        if (status != 401 && status != 403) throw McpDiscoveryException(McpDiscoveryException.Kind.NOT_PROTECTED)
        resourceMetadataUrl(header)?.let { return it }
        AppLogger.info(TAG, "[DCR] $status without a usable WWW-Authenticate - trying the well-known path")
        val url = mcpUrl.toHttpUrlOrNull()
            ?: throw McpDiscoveryException(McpDiscoveryException.Kind.BAD_SERVER_URL)
        return url.newBuilder()
            .encodedPath("/.well-known/oauth-protected-resource")
            .query(null).fragment(null)
            .build().toString()
    }

    /** RFC 8414 path first, OIDC discovery second. */
    private fun fetchAsMetadata(asBase: String): JSONObject {
        val base = asBase.toHttpUrlOrNull()
            ?: throw McpDiscoveryException(McpDiscoveryException.Kind.BAD_AUTHORIZATION_SERVER_URL)
        val basePath = base.encodedPath.trimEnd('/')
        for (suffix in listOf("/.well-known/oauth-authorization-server", "/.well-known/openid-configuration")) {
            val url = base.newBuilder().encodedPath(basePath + suffix).query(null).fragment(null).build()
            try {
                return getJson(url.toString(), "as-metadata")
            } catch (e: McpDiscoveryException) {
                AppLogger.info(TAG, "[DCR] $suffix did not resolve - ${e.kind} ${e.detail.orEmpty()}")
            }
        }
        throw McpDiscoveryException(McpDiscoveryException.Kind.METADATA_UNREADABLE)
    }

    private class Registration(val clientId: String, val clientSecret: String?, val secretExpiresAt: Double?)

    /**
     * RFC 7591. `token_endpoint_auth_method: "none"` marks us a PUBLIC client -
     * a phone app cannot hold a secret; PKCE protects the exchange.
     * `application_type: "native"` (MCP 2026-07-28): an OIDC server otherwise
     * assumes a web client and refuses the loopback redirect URI.
     */
    private fun register(endpoint: String, redirectUri: String): Registration {
        val url = endpoint.toHttpUrlOrNull()
            ?: throw McpDiscoveryException(McpDiscoveryException.Kind.BAD_REGISTRATION_ENDPOINT)
        val body = JSONObject().apply {
            put("redirect_uris", JSONArray().put(redirectUri))
            put("client_name", "Minis")
            put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
            put("response_types", JSONArray().put("code"))
            put("token_endpoint_auth_method", "none")
            put("application_type", "native")
        }
        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .header("Accept", "application/json")
            .build()
        val (status, text) = try {
            routedHttp().newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
        } catch (e: Exception) {
            throw McpDiscoveryException(McpDiscoveryException.Kind.NETWORK, e.message ?: e.javaClass.simpleName)
        }
        if (status !in 200..201) {
            // The server's own error is actionable ("invalid_redirect_uri");
            // "registration failed" is not.
            val obj = runCatching { JSONObject(text) }.getOrNull()
            val detail = obj?.optString("error_description", "")?.ifEmpty { null }
                ?: obj?.optString("error", "")?.ifEmpty { null }
            throw McpDiscoveryException(McpDiscoveryException.Kind.REGISTRATION_REJECTED, detail, status)
        }
        val obj = runCatching { JSONObject(text) }.getOrNull()
        val id = obj?.optString("client_id", "").orEmpty()
        if (id.isEmpty()) throw McpDiscoveryException(McpDiscoveryException.Kind.REGISTRATION_UNREADABLE)
        return Registration(
            clientId = id,
            clientSecret = obj?.optString("client_secret", "")?.ifEmpty { null },
            secretExpiresAt = obj?.takeIf { it.has("client_secret_expires_at") }
                ?.optDouble("client_secret_expires_at")?.takeIf { !it.isNaN() },
        )
    }

    private fun getJson(url: String, label: String): JSONObject {
        val req = Request.Builder().url(url).get().header("Accept", "application/json").build()
        val (status, text) = try {
            routedHttp().newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
        } catch (e: Exception) {
            throw McpDiscoveryException(McpDiscoveryException.Kind.NETWORK, e.message ?: e.javaClass.simpleName)
        }
        if (status !in 200..299) throw McpDiscoveryException(McpDiscoveryException.Kind.HTTP_STATUS, label, status)
        return runCatching { JSONObject(text) }.getOrNull()
            ?: throw McpDiscoveryException(McpDiscoveryException.Kind.UNREADABLE, label)
    }

    /**
     * [T-net-android-phase3] MCP sign-in requests follow the "MCP" route in
     * Settings > Network; [http] itself when no route applies.
     */
    private fun routedHttp(): OkHttpClient =
        com.openminis.app.network.NetworkHttp.client(com.openminis.app.network.NetworkScope.MCP.tag, http)

    companion object {
        private const val TAG = "MCPOAuthDCR"
        private val JSON = "application/json".toMediaType()
        private const val PROBE_BODY = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Parse `resource_metadata="..."` out of a `WWW-Authenticate` header
         * (RFC 9728 §5.1). Tolerates parameter order, extra parameters, and
         * unquoted values, which real servers vary on.
         */
        fun resourceMetadataUrl(header: String): String? {
            val at = header.indexOf("resource_metadata", ignoreCase = true)
            if (at < 0) return null
            var rest = header.substring(at + "resource_metadata".length).trimStart(' ')
            if (!rest.startsWith("=")) return null
            rest = rest.substring(1).trimStart(' ')
            val value = if (rest.startsWith("\"")) {
                val end = rest.indexOf('"', 1)
                if (end < 0) return null
                rest.substring(1, end)
            } else {
                rest.takeWhile { it != ',' && it != ' ' }
            }
            return value.takeIf { it.toHttpUrlOrNull() != null }
        }
    }
}

/**
 * A discovery that did not produce a config. [kind] picks the message the form
 * shows (iOS `MCPOAuthDiscovery.DiscoveryError`); [detail] is the server's own
 * words where it gave some, [status] the HTTP status where one applies.
 */
class McpDiscoveryException(
    val kind: Kind,
    val detail: String? = null,
    val status: Int = 0,
) : Exception("${kind.name}${detail?.let { ": $it" } ?: ""}${if (status != 0) " (HTTP $status)" else ""}") {
    enum class Kind {
        /** The documented fallback: no automatic registration (detail = issuer). */
        DCR_UNSUPPORTED,
        NOT_PROTECTED,
        BAD_SERVER_URL,
        NO_AUTHORIZATION_SERVER,
        BAD_AUTHORIZATION_SERVER_URL,
        METADATA_INCOMPLETE,
        METADATA_UNREADABLE,
        NO_PKCE,
        BAD_REGISTRATION_ENDPOINT,
        REGISTRATION_REJECTED,
        REGISTRATION_UNREADABLE,
        /** detail = what was asked for ("protected-resource" / "as-metadata"). */
        HTTP_STATUS,
        UNREADABLE,
        NETWORK,
    }
}

// ─── Merged from MCPOAuthSignInLogic.kt ──────────────────────────────────────────────────

// ios-source: src/ios/Agent/Session/MCPOAuthDiscovery.swift @ 678fee08a (MCPOAuthSignInLogic)


/**
 * [T-mcp-oauth-signin-android] The decisions of the browser sign-in that need
 * no UI, network or storage, so they are unit-tested on their own. Port of iOS
 * `MCPOAuthSignInLogic` (GitHub #432: one sign-in action, a registration cache
 * that knows when it is stale, and failures that are named).
 */
object MCPOAuthSignInLogic {

    // -- Registered-client cache --

    /**
     * One dynamic registration, kept per authorization server (issuer). v1
     * kept the `client_id` alone; the redirect URI it was registered with and
     * the secret's expiry decide whether it can still be used.
     */
    data class ClientRecord(
        val clientId: String,
        val redirectUri: String,
        /** Epoch seconds; 0 for a registration carried over from v1. */
        val registeredAt: Double,
        /** RFC 7591 `client_secret_expires_at`; null or 0 = does not expire. */
        val secretExpiresAt: Double? = null,
    ) {
        /** Same keys as the iOS v2 cache entry. */
        fun toJson(): JSONObject = JSONObject().apply {
            put("clientId", clientId)
            put("redirectURI", redirectUri)
            put("registeredAt", registeredAt)
            secretExpiresAt?.let { put("secretExpiresAt", it) }
        }

        companion object {
            fun fromJson(obj: Any?): ClientRecord? {
                val o = obj as? JSONObject ?: return null
                val id = o.optString("clientId", "")
                if (id.isEmpty() || !o.has("redirectURI")) return null
                return ClientRecord(
                    clientId = id,
                    redirectUri = o.optString("redirectURI", ""),
                    registeredAt = o.optDouble("registeredAt", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                    secretExpiresAt = if (o.has("secretExpiresAt")) o.optDouble("secretExpiresAt").takeIf { !it.isNaN() } else null,
                )
            }
        }
    }

    /**
     * The registration known for [issuer]: the v2 record, else the v1
     * `client_id` read as a registration made with the default redirect URI
     * (the only one v1 builds ever registered).
     */
    fun record(issuer: String, v2: JSONObject?, v1: JSONObject?, defaultRedirectUri: String): ClientRecord? {
        ClientRecord.fromJson(v2?.opt(issuer))?.let { return it }
        val id = v1?.optString(issuer, "").orEmpty()
        if (id.isNotEmpty()) return ClientRecord(id, defaultRedirectUri, 0.0)
        return null
    }

    sealed class Reuse {
        /** The cached registration can serve this sign-in. */
        object Cached : Reuse()
        data class RegisterAgain(val reason: String) : Reuse()
    }

    /**
     * Whether a known registration can serve this sign-in. A redirect URI other
     * than the registered one is refused by the authorization server (exact
     * match), and an expired secret fails the token exchange, so both register
     * again instead of failing later.
     */
    fun reuse(record: ClientRecord?, redirectUri: String, now: Double): Reuse {
        if (record == null) return Reuse.RegisterAgain("no registration")
        if (record.redirectUri != redirectUri) return Reuse.RegisterAgain("registered for another redirect URI")
        val expires = record.secretExpiresAt
        if (expires != null && expires > 0 && expires <= now) return Reuse.RegisterAgain("client secret expired")
        return Reuse.Cached
    }

    /** The issuers whose registration is [clientId] - what "Reset Sign-In"
     *  forgets for a server entry, which names its client and not its issuer. */
    fun issuers(withClientId: String, v2: JSONObject?, v1: JSONObject?): List<String> {
        if (withClientId.isEmpty()) return emptyList()
        val out = sortedSetOf<String>()
        v2?.keys()?.forEach { k -> if (ClientRecord.fromJson(v2.opt(k))?.clientId == withClientId) out.add(k) }
        v1?.keys()?.forEach { k -> if (v1.optString(k, "") == withClientId) out.add(k) }
        return out.toList()
    }

    // -- Failures --

    /** What a failed token request means for the next step (the official
     *  TypeScript SDK's `auth()` makes the same split). */
    sealed class TokenFailure {
        /** `invalid_client` / `unauthorized_client`: the server no longer knows
         *  this client. A dynamic registration is forgotten and made again; a
         *  Client ID the user typed is theirs to correct. */
        object ClientRejected : TokenFailure()

        /** `invalid_grant`: the code or refresh token is no good. Tokens go,
         *  the registration stays, the user signs in again. */
        object GrantRejected : TokenFailure()
        data class Other(val detail: String) : TokenFailure()
    }

    fun tokenFailure(body: String, status: Int): TokenFailure {
        val obj = runCatching { JSONObject(body) }.getOrNull()
        val code = obj?.optString("error", "").orEmpty()
        val detail = obj?.optString("error_description", "").orEmpty()
        return when (code) {
            "invalid_client", "unauthorized_client" -> TokenFailure.ClientRejected
            "invalid_grant" -> TokenFailure.GrantRejected
            else -> when {
                detail.isNotEmpty() -> TokenFailure.Other(detail)
                code.isNotEmpty() -> TokenFailure.Other(code)
                else -> {
                    val text = body.take(200)
                    TokenFailure.Other(text.ifEmpty { "HTTP $status" })
                }
            }
        }
    }

    /** A rejected client is registered again once per sign-in, and only when
     *  the registration was ours to make. */
    fun registersAgain(afterClientRejectedOnAttempt: Int, dynamic: Boolean): Boolean =
        dynamic && afterClientRejectedOnAttempt == 0

    /** RFC 9207: an authorization response that names its issuer must name the
     *  one the sign-in was started with. A response without `iss` passes. */
    fun issuerMatches(expected: String?, received: String?): Boolean {
        if (received.isNullOrEmpty() || expected.isNullOrEmpty()) return true
        return expected.trimEnd('/') == received.trimEnd('/')
    }

    // -- The form --

    enum class AuthKind { NONE, BROWSER, MANUAL }

    /** Which authorization a saved entry opens as. `mode` "dynamic" is a
     *  browser sign-in; anything else - "static", a value a newer build
     *  invented, none at all - is the manual form, which shows every field. */
    fun authKind(oauth: MCPOAuthConfig?): AuthKind = when {
        oauth == null -> AuthKind.NONE
        oauth.isDynamic -> AuthKind.BROWSER
        else -> AuthKind.MANUAL
    }

    /** The `oauth` block a browser-mode entry is saved with: what the last
     *  sign-in discovered, or the bare mode before the first one. Saving the
     *  form never drops the block (#432: it did, and the entry reopened as
     *  "None" with its sign-in gone from the sandbox's view). */
    fun browserBlock(discovered: MCPOAuthConfig?): MCPOAuthConfig =
        discovered?.copy(mode = MCPOAuthConfig.MODE_DYNAMIC) ?: MCPOAuthConfig(mode = MCPOAuthConfig.MODE_DYNAMIC)

    /** The sign-in button needs a name (the key everything is stored under)
     *  and a URL, and nothing already running. */
    fun canSignIn(name: String, url: String, busy: Boolean): Boolean =
        !busy && name.isNotBlank() && url.isNotBlank()
}

// ─── Merged from MCPOAuthStorage.kt ──────────────────────────────────────────────────

/**
 * [T-mcp-oauth-signin-android] What the browser sign-in reads and writes, as
 * two seams so [MCPOAuthDiscovery] and [MCPOAuthController.signIn] run in unit
 * tests against fakes (the real stores need EncryptedSharedPreferences).
 */
interface McpOAuthSecrets {
    fun clientSecret(server: String): String?
    fun setClientSecret(server: String, secret: String?)
    fun tokens(server: String): MCPOAuthStore.StoredTokens?
    fun setTokens(server: String, tokens: MCPOAuthStore.StoredTokens)
    fun signOut(server: String)
    fun purge(server: String)
    fun registrationSecret(issuer: String): String?
    fun setRegistrationSecret(issuer: String, secret: String?)
}

/** The app's secrets: [MCPOAuthStore] (EncryptedSharedPreferences). */
class StoreMcpOAuthSecrets(private val context: Context) : McpOAuthSecrets {
    override fun clientSecret(server: String) = MCPOAuthStore.clientSecret(context, server)
    override fun setClientSecret(server: String, secret: String?) =
        MCPOAuthStore.setClientSecret(context, server, secret?.trim()?.ifEmpty { null })
    override fun tokens(server: String) = MCPOAuthStore.tokens(context, server)
    override fun setTokens(server: String, tokens: MCPOAuthStore.StoredTokens) =
        MCPOAuthStore.setTokens(context, server, tokens)
    override fun signOut(server: String) = MCPOAuthStore.signOut(context, server)
    override fun purge(server: String) = MCPOAuthStore.purge(context, server)
    override fun registrationSecret(issuer: String) = MCPOAuthStore.registrationSecret(context, issuer)
    override fun setRegistrationSecret(issuer: String, secret: String?) =
        MCPOAuthStore.setRegistrationSecret(context, issuer, secret)
}

/**
 * The dynamic-registration cache, by authorization-server issuer. A
 * `client_id` from an unauthenticated `/register` is a public identifier, not
 * a secret, so it lives in plain SharedPreferences (iOS: UserDefaults); a
 * registration's `client_secret` goes to [McpOAuthSecrets]. v1 (issuer ->
 * client_id) is still read; v2 keeps the redirect URI and the secret's expiry.
 */
interface McpDcrCache {
    fun v1(): JSONObject?
    fun v2(): JSONObject?
    fun setV1(map: JSONObject)
    fun setV2(map: JSONObject)
}

class PrefsMcpDcrCache(context: Context) : McpDcrCache {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun read(key: String): JSONObject? =
        prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    override fun v1() = read(KEY_V1)
    override fun v2() = read(KEY_V2)
    override fun setV1(map: JSONObject) = prefs.edit().putString(KEY_V1, map.toString()).apply()
    override fun setV2(map: JSONObject) = prefs.edit().putString(KEY_V2, map.toString()).apply()

    companion object {
        private const val FILE = "mcp_oauth_dcr"

        /** Same names as the iOS UserDefaults keys. */
        const val KEY_V1 = "mcp.oauth.dcr.clients.v1"
        const val KEY_V2 = "mcp.oauth.dcr.clients.v2"
    }
}

// ─── Merged from MCPOAuthStore.kt ──────────────────────────────────────────────────

/**
 * [T-android-mcp-oauth] Secret storage for MCP OAuth: the client secret and the
 * issued access / refresh tokens, per server, in EncryptedSharedPreferences —
 * the Android analog of iOS keeping these in the Keychain (distinct from the
 * non-secret [MCPOAuthConfig] that lives in servers.json). All keys are
 * namespaced by server id.
 */
object MCPOAuthStore {

    private const val TAG = "MCPOAuthStore"
    private const val FILE = "mcp_oauth_secrets"

    /** Issued tokens for a server. [expiresAtMs] is 0 when the server gave no
     *  expires_in (treated as "never proactively refresh"). */
    data class StoredTokens(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAtMs: Long,
    )

    @Volatile
    private var prefsRef: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        prefsRef ?: synchronized(this) {
            prefsRef ?: EncryptedPrefsFactory.safeCreate(context.applicationContext, FILE)
                .also { prefsRef = it }
        }

    private fun secretKey(server: String) = "secret::$server"
    private fun tokensKey(server: String) = "tokens::$server"

    // -- client secret --

    fun setClientSecret(context: Context, server: String, secret: String?) {
        val p = prefs(context)
        if (secret.isNullOrEmpty()) {
            p.edit().remove(secretKey(server)).apply()
        } else {
            p.edit().putString(secretKey(server), secret).apply()
        }
    }

    fun clientSecret(context: Context, server: String): String? =
        prefs(context).getString(secretKey(server), null)?.takeIf { it.isNotEmpty() }

    // -- tokens --

    fun setTokens(context: Context, server: String, tokens: StoredTokens) {
        val json = JSONObject().apply {
            put("access_token", tokens.accessToken)
            tokens.refreshToken?.let { put("refresh_token", it) }
            put("expires_at_ms", tokens.expiresAtMs)
        }
        prefs(context).edit().putString(tokensKey(server), json.toString()).apply()
    }

    fun tokens(context: Context, server: String): StoredTokens? {
        val raw = prefs(context).getString(tokensKey(server), null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            StoredTokens(
                accessToken = o.getString("access_token"),
                refreshToken = o.optString("refresh_token", "").ifBlank { null },
                expiresAtMs = o.optLong("expires_at_ms", 0L),
            )
        }.onFailure { AppLogger.warning(TAG, "corrupt tokens for $server: ${it.message}") }
            .getOrNull()
    }

    fun isAuthorized(context: Context, server: String): Boolean =
        tokens(context, server)?.accessToken?.isNotEmpty() == true

    /** Forget issued tokens but keep the client secret (sign out, re-auth later). */
    fun signOut(context: Context, server: String) {
        prefs(context).edit().remove(tokensKey(server)).apply()
    }

    /** Forget everything for a server — tokens AND client secret (server deleted). */
    fun purge(context: Context, server: String) {
        prefs(context).edit().remove(tokensKey(server)).remove(secretKey(server)).apply()
    }

    // -- registration secret [T-mcp-oauth-signin-android] --

    private fun registrationSecretKey(issuer: String) = "dcr::$issuer::secret"

    /** The `client_secret` a dynamic registration came with, kept per
     *  authorization server (the identity the registration belongs to) so a
     *  second server behind the same issuer gets it too. Null or blank deletes.
     *  Mirrors iOS `MCPOAuthController.setRegistrationSecret`. */
    fun setRegistrationSecret(context: Context, issuer: String, secret: String?) {
        val p = prefs(context)
        val trimmed = secret?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            p.edit().remove(registrationSecretKey(issuer)).apply()
        } else {
            p.edit().putString(registrationSecretKey(issuer), trimmed).apply()
        }
    }

    fun registrationSecret(context: Context, issuer: String): String? =
        prefs(context).getString(registrationSecretKey(issuer), null)?.takeIf { it.isNotEmpty() }
}

// ─── Merged from McpPkce.kt ──────────────────────────────────────────────────

/**
 * [T-android-mcp-oauth] PKCE (RFC 7636, S256) + RFC 8707 resource-indicator
 * helpers for MCP Static OAuth. Pure and self-contained so the parameter
 * construction can be unit-tested without a device. Mirrors iOS
 * MCPOAuthController's PKCE / canonicalResourceURI / authorize URL building.
 */
object McpPkce {

    private const val URL_SAFE =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    private val random = SecureRandom()

    /** A random URL-safe string of [length] chars (RFC 7636 unreserved set). */
    fun randomUrlSafe(length: Int): String {
        val sb = StringBuilder(length)
        repeat(length) { sb.append(URL_SAFE[random.nextInt(URL_SAFE.length)]) }
        return sb.toString()
    }

    /** base64url(SHA-256(verifier)), no padding — the S256 code_challenge. */
    fun codeChallengeS256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /** One PKCE + state triple for an authorization request. */
    data class PkceParams(val verifier: String, val challenge: String, val state: String)

    fun newPkce(): PkceParams {
        val verifier = randomUrlSafe(64)
        return PkceParams(
            verifier = verifier,
            challenge = codeChallengeS256(verifier),
            state = randomUrlSafe(24),
        )
    }

    /**
     * Canonical MCP resource URI (RFC 8707 §2): trim, drop any fragment, strip
     * trailing slashes; must have a scheme and host. Returns null when [raw]
     * isn't a usable absolute URI. Mirrors iOS canonicalResourceURI.
     */
    fun canonicalResourceUri(raw: String?): String? {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val hash = s.indexOf('#')
        if (hash >= 0) s = s.substring(0, hash)
        while (s.endsWith("/")) s = s.dropLast(1)
        if (s.isEmpty()) return null
        val uri = runCatching { URI(s) }.getOrNull() ?: return null
        if (uri.scheme.isNullOrEmpty() || uri.host.isNullOrEmpty()) return null
        return s
    }

    /**
     * Build the full authorization-endpoint URL for a PKCE code flow. Appends
     * the standard params (response_type, client_id, redirect_uri, state, S256
     * challenge, offline/consent refresh hints), plus the RFC 8707 `resource`
     * and optional scope. Query items already present on [authorizationEndpoint]
     * are preserved. Mirrors iOS authorize().
     */
    fun buildAuthorizationUrl(
        authorizationEndpoint: String,
        clientId: String,
        redirectUri: String,
        pkce: PkceParams,
        resource: String?,
        scopes: String?,
    ): String {
        val params = buildList {
            add("response_type" to "code")
            add("client_id" to clientId)
            add("redirect_uri" to redirectUri)
            add("state" to pkce.state)
            add("code_challenge" to pkce.challenge)
            add("code_challenge_method" to "S256")
            // Refresh-token hints — Google requires both; others ignore unknown
            // params (RFC 6749 §3.1).
            add("access_type" to "offline")
            add("prompt" to "consent")
            if (!resource.isNullOrEmpty()) add("resource" to resource)
            if (!scopes.isNullOrBlank()) add("scope" to scopes.trim())
        }
        val query = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        // Preserve any query already present on the endpoint (RFC 6749 §3.1
        // allows the auth endpoint to carry its own params).
        val sep = if (authorizationEndpoint.contains('?')) "&" else "?"
        return "$authorizationEndpoint$sep$query"
    }

    private fun enc(v: String): String =
        URLEncoder.encode(v, "UTF-8").replace("+", "%20")
}
