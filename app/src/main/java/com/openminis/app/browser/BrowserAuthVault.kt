package com.openminis.app.browser

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent Named Auth Profiles ("Authenticated Session Vault") for the browser agent and UI.
 *
 * Allows the user to log into any site once in the Browser Sheet (including 2FA / passkey / SSO
 * flows) and save the authenticated state—cookies (including HttpOnly cookies accessible via
 * [CookieManager]), `localStorage`, `sessionStorage`, and user-agent profile—under a named
 * profile (e.g. `"github"`, `"aws-prod"`, `"chase-bank"`).
 *
 * Both the user (via the Session Vault sheet in [com.openminis.app.ui.browser.BrowserSheet])
 * and the AI agent (via `browser_use` actions `save_auth_profile`, `load_auth_profile`,
 * `list_auth_profiles`, `delete_auth_profile`) can inspect, switch, or restore profiles.
 */
object BrowserAuthVault {

    private const val TAG = "BrowserAuthVault"
    private const val VAULT_FILENAME = "browser_auth_vault.json"

    data class SavedCookie(
        val originUrl: String,
        val name: String,
        val value: String,
        val domain: String,
    )

    data class AuthProfile(
        val name: String,
        val primaryDomain: String,
        val savedUrl: String,
        val pageTitle: String,
        val userAgentProfile: String,
        val cookies: List<SavedCookie>,
        val localStorage: Map<String, String>,
        val sessionStorage: Map<String, String>,
        val updatedAtMs: Long,
    ) {
        val cookieCount: Int get() = cookies.size
        val storageKeyCount: Int get() = localStorage.size + sessionStorage.size
        val domains: List<String>
            get() = cookies.map { it.domain }.filter { it.isNotBlank() }.distinct()
                .ifEmpty { listOfNotNull(primaryDomain.takeIf { it.isNotBlank() }) }
    }

    private val lock = Any()
    private var initialized = false
    private val _profiles = MutableStateFlow<List<AuthProfile>>(emptyList())
    val profiles: StateFlow<List<AuthProfile>> = _profiles.asStateFlow()

    fun ensureLoaded(context: Context): List<AuthProfile> {
        synchronized(lock) {
            if (!initialized) {
                _profiles.value = readFromDisk(context.applicationContext)
                initialized = true
            }
            return _profiles.value
        }
    }

    /**
     * Suggest a clean profile name based on the current page URL (e.g. `"github.com"` -> `"github"`).
     */
    fun suggestProfileName(url: String?): String {
        val host = url?.let { runCatching { URI(it).host }.getOrNull() }
            ?.lowercase()
            ?.removePrefix("www.")
            ?.removePrefix("m.")
            ?.removePrefix("app.")
            ?.removePrefix("auth.")
            ?.removePrefix("login.")
            .orEmpty()
        if (host.isBlank()) return "default"
        val parts = host.split('.').filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> parts[parts.size - 2]
            parts.isNotEmpty() -> parts.first()
            else -> "default"
        }
    }

    fun normalizeProfileName(raw: String?, fallbackUrl: String? = null): String {
        val cleaned = raw?.trim()
            ?.replace(Regex("[^a-zA-Z0-9._\\- ]"), "")
            ?.trim()
            ?.replace(Regex("\\s+"), "-")
            ?.lowercase()
            .orEmpty()
        return cleaned.ifEmpty { suggestProfileName(fallbackUrl) }
    }

    /**
     * Capture the active tab's cookies + `localStorage` + `sessionStorage` into a named profile.
     */
    suspend fun saveFromManager(
        context: Context,
        manager: BrowserUseManager,
        rawProfileName: String?,
    ): BrowserActionResult {
        ensureLoaded(context)
        val currentUrl = manager.currentURL.value.takeIf { it.isNotBlank() && it != "about:blank" }
            ?: return BrowserActionResult.error(
                "save_auth_profile: no active web page is loaded. Navigate and log in first.",
            )

        val profileName = normalizeProfileName(rawProfileName, currentUrl)
        val uri = runCatching { URI(currentUrl) }.getOrNull()
        val host = uri?.host?.lowercase().orEmpty()
        val rootDomain = extractRootDomain(host)

        val cookieMgr = runCatching { CookieManager.getInstance() }.getOrNull()
            ?: return BrowserActionResult.error("save_auth_profile: CookieManager unavailable")
        withContext(Dispatchers.Main) {
            runCatching { cookieMgr.flush() }
        }

        // Probe candidate origins so SSO / subdomain cookies (e.g.https://github.com,
        // https://api.github.com, https://accounts.google.com) are captured together.
        val candidateUrls = linkedSetOf<String>()
        candidateUrls.add(currentUrl)
        if (host.isNotEmpty()) {
            candidateUrls.add("https://$host/")
            candidateUrls.add("http://$host/")
        }
        if (rootDomain.isNotEmpty() && rootDomain != host) {
            candidateUrls.add("https://$rootDomain/")
            candidateUrls.add("https://www.$rootDomain/")
            candidateUrls.add("https://app.$rootDomain/")
            candidateUrls.add("https://api.$rootDomain/")
            candidateUrls.add("https://auth.$rootDomain/")
            candidateUrls.add("https://login.$rootDomain/")
            candidateUrls.add("https://id.$rootDomain/")
        }
        // Include recent history URLs matching the same root domain
        runCatching {
            val historyEntries = BrowserHistoryStore.getInstance(context).getEntries()
            for (entry in historyEntries) {
                val hHost = runCatching { URI(entry.url).host?.lowercase() }.getOrNull() ?: continue
                if (rootDomain.isNotEmpty() && (hHost == rootDomain || hHost.endsWith(".$rootDomain"))) {
                    candidateUrls.add(entry.url)
                }
            }
        }

        val collectedCookies = LinkedHashMap<String, SavedCookie>()
        for (probeUrl in candidateUrls) {
            val rawCookieHeader = runCatching { cookieMgr.getCookie(probeUrl) }.getOrNull().orEmpty()
            if (rawCookieHeader.isBlank()) continue
            val probeHost = runCatching { URI(probeUrl).host?.lowercase() }.getOrNull() ?: host
            val probeOrigin = runCatching {
                val u = URI(probeUrl)
                "${u.scheme ?: "https"}://${u.host}"
            }.getOrDefault(probeUrl)

            for (segment in rawCookieHeader.split(";")) {
                val trimmed = segment.trim()
                val eq = trimmed.indexOf('=')
                if (eq <= 0) continue
                val cName = trimmed.substring(0, eq).trim()
                val cValue = trimmed.substring(eq + 1).trim()
                if (cName.isEmpty()) continue
                val dedupKey = "$probeHost|$cName"
                collectedCookies[dedupKey] = SavedCookie(
                    originUrl = probeOrigin,
                    name = cName,
                    value = cValue,
                    domain = probeHost.ifEmpty { rootDomain },
                )
            }
        }

        // Capture localStorage and sessionStorage from the live WebView
        val (localMap, sessionMap) = manager.dumpCurrentWebStorage()

        val profile = AuthProfile(
            name = profileName,
            primaryDomain = rootDomain.ifEmpty { host },
            savedUrl = currentUrl,
            pageTitle = manager.pageTitle.value,
            userAgentProfile = manager.currentProfile.value,
            cookies = collectedCookies.values.toList(),
            localStorage = localMap,
            sessionStorage = sessionMap,
            updatedAtMs = System.currentTimeMillis(),
        )

        synchronized(lock) {
            val existing = _profiles.value.filterNot { it.name.equals(profileName, ignoreCase = true) }
            val updated = listOf(profile) + existing
            _profiles.value = updated
            writeToDisk(context.applicationContext, updated)
        }

        val summary = buildString {
            appendLine("Saved Auth Profile '$profileName' to Authenticated Session Vault")
            appendLine("  Domain: ${profile.primaryDomain.ifEmpty { "(unknown)" }}")
            appendLine("  URL: ${profile.savedUrl}")
            appendLine("  Cookies captured: ${profile.cookieCount}")
            appendLine("  Web Storage keys: ${profile.localStorage.size} localStorage, ${profile.sessionStorage.size} sessionStorage")
            append("  User-Agent profile: ${profile.userAgentProfile}")
        }
        Log.i(TAG, "Saved auth profile '$profileName': ${profile.cookieCount} cookies, ${profile.storageKeyCount} storage keys")
        return BrowserActionResult(text = summary)
    }

    /**
     * Restore a named auth profile into [manager]'s WebView + [CookieManager].
     */
    suspend fun loadIntoManager(
        context: Context,
        manager: BrowserUseManager,
        rawProfileName: String?,
        navigateToSavedUrl: Boolean = true,
    ): BrowserActionResult {
        val all = ensureLoaded(context)
        if (all.isEmpty()) {
            return BrowserActionResult.error(
                "load_auth_profile: no saved profiles in the Authenticated Session Vault. " +
                    "Log into a site in the Browser Sheet and call save_auth_profile first.",
            )
        }
        val query = rawProfileName?.trim().orEmpty()
        val profile = if (query.isEmpty()) {
            // Match current tab domain if possible, otherwise most recent profile
            val curHost = runCatching { URI(manager.currentURL.value).host?.lowercase() }.getOrNull().orEmpty()
            val curRoot = extractRootDomain(curHost)
            all.firstOrNull { curRoot.isNotEmpty() && it.primaryDomain.equals(curRoot, ignoreCase = true) }
                ?: all.first()
        } else {
            all.firstOrNull { it.name.equals(query, ignoreCase = true) }
                ?: all.firstOrNull { it.primaryDomain.equals(query, ignoreCase = true) }
                ?: all.firstOrNull {
                    it.name.contains(query, ignoreCase = true) ||
                        it.primaryDomain.contains(query, ignoreCase = true)
                }
        } ?: return BrowserActionResult.error(
            "load_auth_profile: profile '$query' not found. Available profiles: ${all.joinToString(", ") { "'${it.name}' (${it.primaryDomain})" }}",
        )

        val cookieMgr = runCatching { CookieManager.getInstance() }.getOrNull()
            ?: return BrowserActionResult.error("load_auth_profile: CookieManager unavailable")

        var restoredCookies = 0
        withContext(Dispatchers.Main) {
            cookieMgr.setAcceptCookie(true)
            for (c in profile.cookies) {
                val targetOrigin = c.originUrl.ifBlank { "https://${c.domain}" }
                val isHttps = targetOrigin.startsWith("https://", ignoreCase = true)
                val isHostPrefix = c.name.startsWith("__Host-")
                val isSecurePrefix = isHostPrefix || c.name.startsWith("__Secure-")
                val secureAttr = if (isHttps || isSecurePrefix) "; Secure" else ""

                // RFC 6265bis: __Host- cookies MUST have Secure, Path=/, and NO Domain attribute.
                val cookiePair = "${c.name}=${c.value}; Path=/$secureAttr"
                cookieMgr.setCookie(targetOrigin, cookiePair)

                if (!isHostPrefix && c.domain.isNotBlank() && !c.domain.startsWith("localhost")) {
                    val root = extractRootDomain(c.domain)
                    if (root.isNotBlank()) {
                        cookieMgr.setCookie(
                            "https://$root",
                            "${c.name}=${c.value}; Domain=.$root; Path=/$secureAttr",
                        )
                    }
                }
                restoredCookies++
            }
            cookieMgr.flush()
        }

        // Restore saved User-Agent profile if it differs from the tab's current profile
        if (profile.userAgentProfile.isNotBlank() &&
            !profile.userAgentProfile.equals(manager.currentProfile.value, ignoreCase = true)
        ) {
            manager.applyUserAgentForVault(profile.userAgentProfile)
        }

        // Navigate or reload so cookies & storage take effect on the target origin
        val currentUrl = manager.currentURL.value
        val curHost = runCatching { URI(currentUrl).host?.lowercase() }.getOrNull().orEmpty()
        val sameDomain = curHost.isNotEmpty() &&
            (curHost == profile.primaryDomain || curHost.endsWith(".${profile.primaryDomain}"))

        if (navigateToSavedUrl && !sameDomain && profile.savedUrl.isNotBlank()) {
            manager.loadURLAndWaitForVault(profile.savedUrl)
        }

        // Restore localStorage & sessionStorage now that the WebView is on the matching origin
        val restoredStorage = manager.restoreCurrentWebStorage(
            localStorage = profile.localStorage,
            sessionStorage = profile.sessionStorage,
        )

        // If we injected storage keys (or were already on the same domain), reload and wait
        // so SPA startup scripts read the restored localStorage/sessionStorage and cookies.
        if ((sameDomain || restoredStorage > 0) && profile.savedUrl.isNotBlank()) {
            withContext(Dispatchers.Main) {
                manager.reloadAndWait()
            }
        }

        val summary = buildString {
            appendLine("Loaded Auth Profile '${profile.name}' from Authenticated Session Vault")
            appendLine("  Domain: ${profile.primaryDomain}")
            appendLine("  Restored cookies: $restoredCookies")
            appendLine("  Restored storage keys: $restoredStorage (${profile.localStorage.size} local, ${profile.sessionStorage.size} session)")
            append("  Active URL: ${manager.currentURL.value.ifEmpty { profile.savedUrl }}")
        }
        Log.i(TAG, "Loaded auth profile '${profile.name}': $restoredCookies cookies, $restoredStorage storage keys")
        return BrowserActionResult(text = summary)
    }

    fun listProfilesResult(context: Context): BrowserActionResult {
        val list = ensureLoaded(context)
        if (list.isEmpty()) {
            return BrowserActionResult(
                text = "Authenticated Session Vault is empty (0 saved profiles).\n" +
                    "Tip: Log into any site in the Browser Sheet or via browser_use, then call " +
                    "save_auth_profile with profile_name='<name>' to persist cookies and web storage.",
            )
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val text = buildString {
            appendLine("Authenticated Session Vault (${list.size} profile(s)):")
            for ((idx, p) in list.withIndex()) {
                val updated = fmt.format(Date(p.updatedAtMs))
                appendLine(
                    "  [${idx + 1}] '${p.name}' — domain: ${p.primaryDomain.ifEmpty { "-" }}, " +
                        "cookies: ${p.cookieCount}, storage: ${p.storageKeyCount} keys, " +
                        "updated: $updated",
                )
                if (p.savedUrl.isNotBlank()) {
                    appendLine("      url: ${p.savedUrl}")
                }
            }
        }.trimEnd()
        return BrowserActionResult(text = text)
    }

    fun deleteProfileResult(context: Context, rawProfileName: String?): BrowserActionResult {
        val name = rawProfileName?.trim().orEmpty()
        if (name.isEmpty()) {
            return BrowserActionResult.error("delete_auth_profile requires 'profile_name'")
        }
        val removed = deleteProfile(context, name)
        return if (removed) {
            BrowserActionResult(text = "Deleted Auth Profile '$name' from Authenticated Session Vault.")
        } else {
            BrowserActionResult.error("Auth Profile '$name' not found in Authenticated Session Vault.")
        }
    }

    fun deleteProfile(context: Context, name: String): Boolean {
        ensureLoaded(context)
        synchronized(lock) {
            val before = _profiles.value
            val after = before.filterNot { it.name.equals(name.trim(), ignoreCase = true) }
            if (after.size == before.size) return false
            _profiles.value = after
            writeToDisk(context.applicationContext, after)
            return true
        }
    }

    private fun extractRootDomain(host: String): String {
        val clean = host.trim().lowercase().removePrefix("www.")
        val parts = clean.split('.').filter { it.isNotBlank() }
        if (parts.size <= 2) return clean
        val secondLast = parts[parts.size - 2]
        val last = parts.last()
        // Handle multi-part TLDs like .co.uk, .com.au
        return if (secondLast.length <= 3 && last.length == 2 && parts.size >= 3) {
            parts.takeLast(3).joinToString(".")
        } else {
            parts.takeLast(2).joinToString(".")
        }
    }

    private fun vaultFile(context: Context): File = File(context.filesDir, VAULT_FILENAME)

    private fun readFromDisk(context: Context): List<AuthProfile> {
        val file = vaultFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("profiles") ?: return emptyList()
            val out = ArrayList<AuthProfile>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val cookiesArr = obj.optJSONArray("cookies") ?: JSONArray()
                val cookies = ArrayList<SavedCookie>(cookiesArr.length())
                for (j in 0 until cookiesArr.length()) {
                    val c = cookiesArr.optJSONObject(j) ?: continue
                    val cName = c.optString("name", "")
                    if (cName.isEmpty()) continue
                    cookies.add(
                        SavedCookie(
                            originUrl = c.optString("originUrl", ""),
                            name = cName,
                            value = c.optString("value", ""),
                            domain = c.optString("domain", ""),
                        ),
                    )
                }
                val localObj = obj.optJSONObject("localStorage")
                val localMap = LinkedHashMap<String, String>()
                if (localObj != null) {
                    val keys = localObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        localMap[k] = localObj.optString(k, "")
                    }
                }
                val sessionObj = obj.optJSONObject("sessionStorage")
                val sessionMap = LinkedHashMap<String, String>()
                if (sessionObj != null) {
                    val keys = sessionObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        sessionMap[k] = sessionObj.optString(k, "")
                    }
                }
                out.add(
                    AuthProfile(
                        name = obj.optString("name", "default"),
                        primaryDomain = obj.optString("primaryDomain", ""),
                        savedUrl = obj.optString("savedUrl", ""),
                        pageTitle = obj.optString("pageTitle", ""),
                        userAgentProfile = obj.optString("userAgentProfile", UserAgentProfile.MOBILE_CHROME.value),
                        cookies = cookies,
                        localStorage = localMap,
                        sessionStorage = sessionMap,
                        updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis()),
                    ),
                )
            }
            out.sortedByDescending { it.updatedAtMs }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read auth vault: ${e.message}")
            emptyList()
        }
    }

    private fun writeToDisk(context: Context, profiles: List<AuthProfile>) {
        try {
            val arr = JSONArray()
            for (p in profiles) {
                val obj = JSONObject()
                obj.put("name", p.name)
                obj.put("primaryDomain", p.primaryDomain)
                obj.put("savedUrl", p.savedUrl)
                obj.put("pageTitle", p.pageTitle)
                obj.put("userAgentProfile", p.userAgentProfile)
                obj.put("updatedAtMs", p.updatedAtMs)

                val cArr = JSONArray()
                for (c in p.cookies) {
                    cArr.put(
                        JSONObject().apply {
                            put("originUrl", c.originUrl)
                            put("name", c.name)
                            put("value", c.value)
                            put("domain", c.domain)
                        },
                    )
                }
                obj.put("cookies", cArr)

                val lObj = JSONObject()
                for ((k, v) in p.localStorage) lObj.put(k, v)
                obj.put("localStorage", lObj)

                val sObj = JSONObject()
                for ((k, v) in p.sessionStorage) sObj.put(k, v)
                obj.put("sessionStorage", sObj)

                arr.put(obj)
            }
            val root = JSONObject().apply {
                put("version", 1)
                put("profiles", arr)
            }
            val target = vaultFile(context)
            val tmp = File(context.filesDir, "$VAULT_FILENAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(root.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write auth vault: ${e.message}")
        }
    }
}
