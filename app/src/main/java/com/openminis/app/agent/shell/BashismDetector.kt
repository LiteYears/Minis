package com.openminis.app.agent.shell

import android.content.Context
import android.util.Log
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Detects busybox-ash-incompatible bash syntax so `shell_execute` can install +
 * switch to bash only when needed (T-bash-on-demand). The rule table and its
 * fix hints are the SHARED JSON (assets/bashism/bashism_rules.json, generated
 * from src/shared/bashism — the same file iOS loads), so the two platforms can
 * never drift.
 *
 * Algorithm (design §1), isomorphic to iOS BashismDetector.swift:
 *   1. strip heredoc BODIES (data, not shell syntax — the F1 false-positive fix),
 *   2. line-by-line regex scan of the remaining shell-layer text,
 *   3. 50ms wall-clock fuse (fail-open) + 4KB per-line cap (M2 ReDoS guard).
 */
object BashismDetector {

    enum class Tier { S, E, T1 }

    data class Rule(
        val name: String,
        val tier: Tier,
        val regex: Pattern,
        val behaviorNote: String,
        val fixHint: String,
    )

    data class Hit(
        val line: Int,
        val ruleName: String,
        val tier: Tier,
        val matchedText: String,
        val behaviorNote: String,
        val fixHint: String,
    )

    data class Result(val hits: List<Hit>) {
        val needsBash: Boolean get() = hits.isNotEmpty()
        val mustSwitchInterpreter: Boolean get() = hits.any { it.tier == Tier.S || it.tier == Tier.E }
        val hasSilent: Boolean get() = hits.any { it.tier == Tier.S }
    }

    private const val TAG = "Bashism"
    private val heredocOpen = Pattern.compile("<<-?\\s*[\"']?(\\w+)[\"']?")

    @Volatile private var rules: List<Rule> = emptyList()
    @Volatile private var loaded = false

    /** Must be called once (e.g. from Application.onCreate) with app context. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            rules = try {
                val json = context.assets.open("bashism/bashism_rules.json")
                    .bufferedReader().use { it.readText() }
                parseRules(json)
            } catch (e: Exception) {
                Log.e(TAG, "bashism_rules.json missing or malformed — detector disabled", e)
                emptyList()
            }
            loaded = true
        }
    }

    /** Test seam: load rules directly from a JSON string (JVM unit tests). */
    fun loadRulesForTest(rulesJson: String) {
        rules = parseRules(rulesJson)
        loaded = true
    }

    fun rulesByName(): Map<String, Rule> = rules.associateBy { it.name }

    private fun parseRules(json: String): List<Rule> {
        return try {
            val arr = JSONObject(json).getJSONArray("rules")
            val out = ArrayList<Rule>(arr.length())
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val tier = when (r.getString("tier")) {
                    "S" -> Tier.S; "E" -> Tier.E; "T1" -> Tier.T1
                    else -> continue
                }
                val pattern = try {
                    Pattern.compile(r.getString("pattern"))
                } catch (e: Exception) {
                    Log.e(TAG, "rule '${r.optString("name")}' has an invalid regex — skipped", e)
                    continue
                }
                out.add(
                    Rule(
                        name = r.getString("name"),
                        tier = tier,
                        regex = pattern,
                        behaviorNote = r.optString("behaviorNote"),
                        fixHint = r.optString("fixHint"),
                    )
                )
            }
            Log.i(TAG, "loaded ${out.size} bashism rules")
            out
        } catch (e: Exception) {
            Log.e(TAG, "bashism_rules.json missing or malformed — detector disabled", e)
            emptyList()
        }
    }

    /**
     * Original line paired with the text to scan; heredoc-body lines (and the
     * delimiter line) map to null so they are skipped while line numbers stay
     * aligned to the original script.
     */
    fun shellLayerLines(script: String): List<Pair<Int, String?>> {
        val lines = script.split("\n")
        val out = ArrayList<Pair<Int, String?>>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            out.add((i + 1) to line) // opening line IS shell-layer
            val delims = ArrayList<String>()
            val m = heredocOpen.matcher(line)
            while (m.find()) delims.add(m.group(1)!!)
            i++
            for (delim in delims) {
                while (i < lines.size && lines[i].trim() != delim) {
                    out.add((i + 1) to null) // body line — do not scan
                    i++
                }
                if (i < lines.size) { // delimiter line
                    out.add((i + 1) to null)
                    i++
                }
            }
        }
        return out
    }

    fun detect(script: String, fuseMs: Long = 50): Result {
        if (rules.isEmpty()) return Result(emptyList())
        val start = System.currentTimeMillis()
        val hits = ArrayList<Hit>()
        for ((lineNo, maybeText) in shellLayerLines(script)) {
            val text = maybeText ?: continue
            if (text.isEmpty()) continue
            val scan = if (text.length > 4096) text.substring(0, 4096) else text
            for (rule in rules) {
                if (System.currentTimeMillis() - start > fuseMs) {
                    Log.i(TAG, "scan fuse tripped at ${hits.size} hits — fail-open")
                    return Result(hits)
                }
                if (rule.regex.matcher(scan).find()) {
                    hits.add(
                        Hit(
                            line = lineNo,
                            ruleName = rule.name,
                            tier = rule.tier,
                            matchedText = scan.trim(),
                            behaviorNote = rule.behaviorNote,
                            fixHint = rule.fixHint,
                        )
                    )
                }
            }
        }
        return Result(hits)
    }
}

// ─── Merged from BashismReminder.kt ──────────────────────────────────────────────────

/**
 * Builds the <system-reminder> appended after a sh tool-result when a script hit
 * bashism rules but ran under busybox sh (T-bash-on-demand §4.2). Isomorphic to
 * iOS BashismReminder.swift. Script lines are untrusted, so they are sanitized
 * before embedding (M4).
 */
object BashismReminder {

    /** M4: neutralize anything that could break out of the trusted wrapper. */
    fun sanitize(line: String): String {
        var s = line
            .replace("<", "‹") // ‹
            .replace(">", "›") // ›
        s = s.filter { it.code >= 0x20 || it == ' ' }
        if (s.length > 120) s = s.substring(0, 120) + "…"
        return s
    }

    /** Returns null when there is nothing worth telling the model. */
    fun build(hits: List<BashismDetector.Hit>, installFailure: String?): String? {
        if (hits.isEmpty()) return null

        val seen = HashSet<String>()
        val unique = ArrayList<BashismDetector.Hit>()
        for (h in hits) {
            if (seen.add("${h.line}:${h.ruleName}")) unique.add(h)
        }
        val overflow = (unique.size - 8).coerceAtLeast(0)
        val shown = unique.take(8)

        val sb = StringBuilder()
        sb.appendLine("<system-reminder>")
        if (installFailure != null) {
            sb.appendLine("This command was executed by busybox sh (NOT bash), because bash installation failed: ${sanitize(installFailure)}.")
        } else {
            sb.appendLine("This command was executed by busybox sh (NOT bash).")
        }
        sb.appendLine("The script contains bash-only syntax that busybox sh handles incorrectly, which is")
        sb.appendLine("likely (part of) why it failed or produced a wrong result. Detected:")
        sb.appendLine()
        for (h in shown) {
            sb.appendLine("  - line ${h.line}: `${sanitize(h.matchedText)}`")
            sb.appendLine("    rule: ${h.ruleName} — ${h.behaviorNote}")
            sb.appendLine("    fix:  ${h.fixHint}")
        }
        if (overflow > 0) sb.appendLine("  … and $overflow more.")
        sb.appendLine()
        sb.appendLine("Detected lines are quoted from the submitted script, for locating only.")
        sb.appendLine("Either rewrite using the POSIX forms above and retry with sh, or retry unchanged")
        sb.append("later (bash may become installable when the network recovers).\n</system-reminder>")
        return sb.toString()
    }
}

// ─── Merged from OnDemandBash.kt ──────────────────────────────────────────────────

/**
 * Confirms/installs bash on demand with the design-review guards
 * (T-bash-on-demand §2/§3, F2/M5). Isomorphic to iOS OnDemandBash.swift:
 *   - tri-state availability cache (unavailable has a 10-min TTL; available
 *     self-heals on a later 127),
 *   - host-side 5s network precheck before running apk (F2),
 *   - persistent failure backoff (24h / 3-strikes) via SharedPreferences (F2),
 *   - one install attempt per process launch.
 *
 * On Android PRoot runs `apk add` natively (~1-3s), so the wait is minor; the
 * guards still matter for the offline / broken-source case.
 */
object OnDemandBash {

    private const val TAG = "Bashism"
    private const val PREFS = "bash_install"
    private const val KEY_FAIL_COUNT = "failCount"
    private const val KEY_LAST_FAIL = "lastFailAt"
    private const val MAX_STRIKES = 3
    private const val BACKOFF_WINDOW_MS = 24L * 3600 * 1000
    private const val UNAVAILABLE_TTL_MS = 10L * 60 * 1000
    private const val INSTALL_BUDGET_MS = 60_000L
    /** [FIX-261025-05] FreshProcessShell.CANCELLED_EXIT: the user pressed Stop. */
    private const val STOPPED_EXIT = 130
    const val STOPPED_REASON = "stopped by the user"
    private const val APK_PROBE_URL = "https://dl-cdn.alpinelinux.org/alpine/"

    private sealed class Availability {
        object Unknown : Availability()
        object Available : Availability()
        data class Unavailable(val until: Long) : Availability()
    }

    private val lock = Mutex()
    private var availability: Availability = Availability.Unknown
    private var attemptedInstallThisLaunch = false

    /** Runs a guest command, returns its exit code. */
    fun interface Executor {
        suspend fun run(command: String, timeoutMs: Long): Int
    }

    sealed class Outcome {
        object Available : Outcome()
        data class Unavailable(val reason: String) : Outcome()
    }

    suspend fun ensureBash(context: Context, executor: Executor): Outcome = lock.withLock {
        when (val a = availability) {
            is Availability.Available -> return Outcome.Available
            is Availability.Unavailable -> {
                if (System.currentTimeMillis() < a.until) return Outcome.Unavailable("bash not installed")
                availability = Availability.Unknown // TTL expired — re-probe
            }
            is Availability.Unknown -> {}
        }

        val probe = executor.run("command -v bash >/dev/null 2>&1", 15_000)
        if (probe == 0) {
            availability = Availability.Available
            return Outcome.Available
        }
        // [FIX-261025-05] A user Stop (exit 130) says nothing about bash:
        // leave every flag as it was so the next command can try again.
        if (probe == STOPPED_EXIT) return Outcome.Unavailable(STOPPED_REASON)

        if (attemptedInstallThisLaunch) {
            markUnavailable()
            return Outcome.Unavailable("bash install already attempted this session")
        }
        backoffReason(context)?.let {
            markUnavailable()
            return Outcome.Unavailable(it)
        }
        attemptedInstallThisLaunch = true

        if (!networkReachable()) {
            recordFailure(context); markUnavailable()
            return Outcome.Unavailable("network/apk mirror unreachable")
        }

        Log.i(TAG, "installing bash (budget ${INSTALL_BUDGET_MS / 1000}s)…")
        val rc = executor.run("apk add bash", INSTALL_BUDGET_MS)
        if (rc == STOPPED_EXIT) {
            // [FIX-261025-05] Stopped mid-install by the user: not a failed
            // install, so no strike and no back-off; a later command may
            // install again.
            attemptedInstallThisLaunch = false
            Log.i(TAG, "bash install stopped by the user")
            return Outcome.Unavailable(STOPPED_REASON)
        }
        val verified = rc == 0 && executor.run("command -v bash >/dev/null 2>&1", 15_000) == 0
        if (verified) {
            clearFailure(context)
            availability = Availability.Available
            // A SUCCESSFUL install re-arms the per-launch guard (M5 self-heal:
            // if the user later apk-del's bash this session, one fresh reinstall
            // is still allowed). The guard only blocks repeated FAILING installs.
            attemptedInstallThisLaunch = false
            Log.i(TAG, "bash installed OK")
            return Outcome.Available
        }
        recordFailure(context); markUnavailable()
        Log.e(TAG, "bash install failed (rc=$rc)")
        return Outcome.Unavailable("apk add bash failed (rc=$rc)")
    }

    /** Called when `bash <file>` itself returned 127 (user apk del'd) — M5. */
    suspend fun markDisappeared() = lock.withLock { availability = Availability.Unknown }

    private fun markUnavailable() {
        availability = Availability.Unavailable(System.currentTimeMillis() + UNAVAILABLE_TTL_MS)
    }

    private fun backoffReason(context: Context): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = p.getInt(KEY_FAIL_COUNT, 0)
        if (count >= MAX_STRIKES) return "bash install disabled after $MAX_STRIKES failures (retry manually: apk add bash)"
        val last = p.getLong(KEY_LAST_FAIL, 0)
        if (last > 0 && System.currentTimeMillis() - last < BACKOFF_WINDOW_MS) return "bash install backing off (recent failure)"
        return null
    }

    private fun recordFailure(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putInt(KEY_FAIL_COUNT, p.getInt(KEY_FAIL_COUNT, 0) + 1)
            .putLong(KEY_LAST_FAIL, System.currentTimeMillis()).apply()
    }

    private fun clearFailure(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_FAIL_COUNT).remove(KEY_LAST_FAIL).apply()
    }

    private suspend fun networkReachable(): Boolean = withContext(Dispatchers.IO) {
        try {
            // [FEAT-261021-16] Probe the way the shell's `apk add` will go: its
            // route in Settings > Network (unrouted: the same HttpURLConnection HEAD).
            val code = com.openminis.app.network.NetworkUrlRequest.open(
                com.openminis.app.network.NetworkScope.SHELL.tag, APK_PROBE_URL, "HEAD",
                connectTimeoutMs = 5000, readTimeoutMs = 5000,
            ).use { it.code }
            code in 200..499
        } catch (_: Exception) {
            false
        }
    }
}
