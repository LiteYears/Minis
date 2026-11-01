package com.openminis.app.agent.jobs

import org.json.JSONArray
import org.json.JSONObject

/**
 * Sub-Agent Swarm & Parallel Sandboxed Workers (`spawn_subagent`).
 *
 * Allows the primary orchestrator model to act as a Staff Engineer / Research Lead
 * and dispatch 2-5 (flexibly 1-5) parallel sandboxed worker agents simultaneously,
 * each running with:
 * 1. An isolated working directory under `/var/minis/workspace/swarm_<swarmId>/<workerId>`
 * 2. An isolated browser tab from `BrowserTabPool` (with optional initial `url` pre-opened)
 * 3. A cheaper/faster model from `ModelGroups` (`SubAgentModelChoice.SUB_MODEL` by default,
 *    with automatic fallback to the primary model if no Sub group is configured)
 * 4. Structured parallel fan-in into a single clean context-window result.
 */
object SubAgentSwarm {
    const val TOOL_NAME = "spawn_subagent"
    const val MIN_WORKERS = 1
    const val MAX_WORKERS = 5
    const val DEFAULT_MINUTES = 5
    const val MAX_MINUTES = 30

    data class SwarmWorkerSpec(
        val id: String,
        val slug: String,
        val title: String,
        val task: String,
        val url: String?,
        val agentName: String?,
        val modelChoice: SubAgentModelChoice,
    )

    data class SwarmArgs(
        val title: String,
        val workers: List<SwarmWorkerSpec>,
        val sharedContext: String,
        val defaultModelChoice: SubAgentModelChoice,
        val minutes: Int,
    )

    data class SwarmWorkerStatus(
        val workerId: String,
        val slug: String,
        val title: String,
        val childSessionId: String,
        val jobId: String,
        val status: String,
        val modelUsed: String,
        val tierUsed: String,
        val modelOrigin: String?,
        val modelGroupName: String?,
        val workdir: String,
        val tabId: Int?,
        val initialUrl: String?,
        val currentTool: String?,
        val currentActivity: String?,
        val turns: Int,
        val elapsedMs: Long,
        val summary: String,
        val result: String,
        val errorDetail: String? = null,
    ) {
        fun toJsonObject(): JSONObject = JSONObject().apply {
            put("worker_id", workerId)
            put("slug", slug)
            put("title", title)
            if (childSessionId.isNotEmpty()) put("child_session_id", childSessionId)
            if (jobId.isNotEmpty()) put("job_id", jobId)
            put("status", status)
            if (modelUsed.isNotEmpty()) put("model_used", modelUsed)
            if (tierUsed.isNotEmpty()) put("tier_used", tierUsed)
            if (!modelOrigin.isNullOrEmpty()) put("model_origin", modelOrigin)
            if (!modelGroupName.isNullOrEmpty()) put("model_group_name", modelGroupName)
            put("workdir", workdir)
            if (tabId != null && tabId >= 0) put("tab_id", tabId)
            if (!initialUrl.isNullOrEmpty()) put("url", initialUrl)
            if (!currentTool.isNullOrEmpty()) put("tool", currentTool)
            if (!currentActivity.isNullOrEmpty()) put("activity", currentActivity.take(90))
            put("turns", turns)
            put("elapsed_s", (elapsedMs / 1000L).coerceAtLeast(0L))
            if (summary.isNotEmpty()) put("summary", summary)
            if (result.isNotEmpty()) put("result", result)
            if (!errorDetail.isNullOrEmpty()) put("error_detail", errorDetail)
        }
    }

    fun sanitizeSlug(raw: String, fallbackIndex: Int): String {
        val cleaned = raw.trim().lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "_")
            .trim('_')
            .take(28)
        return if (cleaned.isNotEmpty()) cleaned else "worker_${fallbackIndex + 1}"
    }

    /**
     * Parses `spawn_subagent` arguments with high resilience for both frontier
     * and smaller models (accepting `workers` as a JSON array, a JSON-encoded
     * string array, `tasks`/`prompts` arrays, or an object map).
     */
    fun parseArgs(argsJson: String): SwarmArgs {
        val o = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val title = o.optString("tool_title", "").trim().ifEmpty {
            o.optString("title", "").trim().ifEmpty { "Sub-Agent Swarm" }
        }
        val sharedContext = o.optString("shared_context", "").trim().ifEmpty {
            o.optString("context", "").trim()
        }
        // Default to SUB_MODEL (cheaper/faster model from ModelGroups) for swarm workers
        val defaultChoiceRaw = o.optString("model_choice", "sub_model").trim()
        val defaultChoice = if (defaultChoiceRaw.isEmpty()) {
            SubAgentModelChoice.SUB_MODEL
        } else {
            SubAgentModelChoice.parse(defaultChoiceRaw)
        }
        val minutesRaw = if (o.has("max_minutes")) {
            o.optDouble("max_minutes", DEFAULT_MINUTES.toDouble()).toInt()
        } else {
            DEFAULT_MINUTES
        }
        val minutes = minutesRaw.coerceIn(1, MAX_MINUTES)

        val rawArray: JSONArray? = when {
            o.optJSONArray("workers") != null -> o.optJSONArray("workers")
            o.optString("workers", "").trim().startsWith("[") ->
                runCatching { JSONArray(o.optString("workers").trim()) }.getOrNull()
            o.optJSONArray("tasks") != null -> o.optJSONArray("tasks")
            o.optString("tasks", "").trim().startsWith("[") ->
                runCatching { JSONArray(o.optString("tasks").trim()) }.getOrNull()
            o.optJSONArray("prompts") != null -> o.optJSONArray("prompts")
            o.optJSONObject("workers") != null -> {
                val mapObj = o.getJSONObject("workers")
                val arr = JSONArray()
                val keys = mapObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = mapObj.opt(k)
                    when (v) {
                        is JSONObject -> {
                            if (!v.has("id")) v.put("id", k)
                            arr.put(v)
                        }
                        is String -> {
                            arr.put(JSONObject().put("id", k).put("title", k).put("task", v))
                        }
                    }
                }
                arr
            }
            else -> null
        }

        val parsedWorkers = mutableListOf<SwarmWorkerSpec>()
        val usedSlugs = HashSet<String>()
        if (rawArray != null) {
            val limit = minOf(rawArray.length(), MAX_WORKERS)
            for (i in 0 until limit) {
                val item = rawArray.opt(i)
                when (item) {
                    is JSONObject -> {
                        val rawId = item.optString("id", "")
                            .ifEmpty { item.optString("worker_id", "") }
                            .ifEmpty { item.optString("name", "") }
                            .ifEmpty { "worker_${i + 1}" }
                        var slug = sanitizeSlug(rawId, i)
                        if (!usedSlugs.add(slug)) {
                            slug = "${slug}_${i + 1}"
                            usedSlugs.add(slug)
                        }
                        val taskText = item.optString("task", "").trim()
                            .ifEmpty { item.optString("prompt", "").trim() }
                            .ifEmpty { item.optString("instructions", "").trim() }
                            .ifEmpty { item.optString("description", "").trim() }
                        if (taskText.isEmpty()) continue
                        val workerTitle = item.optString("title", "").trim()
                            .ifEmpty { item.optString("name", "").trim() }
                            .ifEmpty { taskText.lineSequence().firstOrNull()?.take(44)?.trim().orEmpty() }
                            .ifEmpty { "Worker ${i + 1}" }
                        val url = item.optString("url", "").trim()
                            .ifEmpty { item.optString("initial_url", "").trim() }
                            .ifEmpty { null }
                        val agentName = item.optString("agent", "").trim().ifEmpty { null }
                        val workerChoiceStr = item.optString("model_choice", "").trim()
                        val workerChoice = if (workerChoiceStr.isEmpty()) {
                            defaultChoice
                        } else {
                            SubAgentModelChoice.parse(workerChoiceStr)
                        }
                        parsedWorkers.add(
                            SwarmWorkerSpec(
                                id = rawId,
                                slug = slug,
                                title = workerTitle,
                                task = taskText,
                                url = url,
                                agentName = agentName,
                                modelChoice = workerChoice,
                            )
                        )
                    }
                    is String -> {
                        val taskText = item.trim()
                        if (taskText.isEmpty()) continue
                        val slug = "worker_${i + 1}"
                        usedSlugs.add(slug)
                        parsedWorkers.add(
                            SwarmWorkerSpec(
                                id = slug,
                                slug = slug,
                                title = taskText.lineSequence().firstOrNull()?.take(44)?.trim() ?: "Worker ${i + 1}",
                                task = taskText,
                                url = null,
                                agentName = null,
                                modelChoice = defaultChoice,
                            )
                        )
                    }
                }
            }
        }

        return SwarmArgs(
            title = title,
            workers = parsedWorkers,
            sharedContext = sharedContext,
            defaultModelChoice = defaultChoice,
            minutes = minutes,
        )
    }

    /**
     * Build the prompt sent to an individual swarm worker, including its
     * isolated working directory, dedicated browser tab ID, optional pre-opened
     * URL, and any shared context from the orchestrator.
     */
    fun buildWorkerPrompt(
        spec: SwarmWorkerSpec,
        sharedContext: String,
        workdir: String,
        tabId: Int?,
        totalWorkers: Int,
        workerIndex: Int,
    ): String {
        val sb = StringBuilder()
        sb.append("[Swarm Worker ${workerIndex + 1} of $totalWorkers — ID: ${spec.id} (\"${spec.title}\")]\n")
        sb.append("- Isolated Working Directory: `$workdir` (All `shell_execute` commands start here automatically, and `\$MINIS_WORKER_DIR` is set to `$workdir`. Write all scripts, intermediate files, and outputs inside `$workdir` so you never collide with sibling workers.)\n")
        if (tabId != null && tabId >= 0) {
            if (!spec.url.isNullOrEmpty()) {
                sb.append("- Dedicated Browser Tab: `tab_id: $tabId` (Pre-allocated for you and already navigating to `${spec.url}`. Pass `tab_id: $tabId` on your `browser_use` calls to inspect or interact with it immediately.)\n")
            } else {
                sb.append("- Dedicated Browser Tab: `tab_id: $tabId` (Pre-allocated exclusively for you. Pass `tab_id: $tabId` on your `browser_use` calls so you never collide with sibling workers.)\n")
            }
        }
        sb.append("\n### Assigned Task\n")
        sb.append(spec.task.trim())
        if (sharedContext.isNotBlank()) {
            sb.append("\n\n--- Shared Swarm Context ---\n")
            sb.append(sharedContext.trim())
        }
        return sb.toString()
    }

    /**
     * Live progress JSON written to the parent tool block while parallel workers run.
     * Compatible with `parseHelperBlock` so the inline card, floating thumbnail,
     * and detail sheet all update smoothly in real time.
     */
    fun progressJson(
        title: String,
        swarmRootDir: String,
        workers: List<SwarmWorkerStatus>,
        elapsedMs: Long,
    ): String {
        val total = workers.size
        val completed = workers.count { it.status == "completed" }
        val finished = workers.count { it.status != "running" && it.status != "starting" }
        val activeWorkers = workers.filter { it.status == "running" || it.status == "starting" }
        val activeTool = activeWorkers.firstNotNullOfOrNull { it.currentTool?.takeIf(String::isNotEmpty) } ?: ""
        val activeSummary = buildString {
            append("$finished/$total done")
            val runningLabels = activeWorkers.mapNotNull { w ->
                val t = w.currentTool?.takeIf { it.isNotEmpty() }
                if (t != null) "${w.workerId}:$t" else w.workerId
            }
            if (runningLabels.isNotEmpty()) {
                append(" · ")
                append(runningLabels.joinToString(", "))
            }
        }
        val primaryChildId = activeWorkers.firstOrNull()?.childSessionId?.takeIf { it.isNotEmpty() }
            ?: workers.firstOrNull()?.childSessionId.orEmpty()
        val primaryJobId = activeWorkers.firstOrNull()?.jobId?.takeIf { it.isNotEmpty() }
            ?: workers.firstOrNull()?.jobId.orEmpty()
        val arr = JSONArray()
        workers.forEach { arr.put(it.toJsonObject()) }

        return JSONObject()
            .put("swarm", true)
            .put("status", "running")
            .put("title", title)
            .put("swarm_dir", swarmRootDir)
            .put("worker_count", total)
            .put("completed_count", completed)
            .put("finished_count", finished)
            .put("child_session_id", primaryChildId)
            .put("job_id", primaryJobId)
            .put("tool", activeTool)
            .put("activity", activeSummary.take(100))
            .put("elapsed_s", (elapsedMs / 1000L).coerceAtLeast(0L))
            .put("workers", arr)
            .toString()
    }

    /**
     * Final fan-in JSON returned to the parent orchestrator model when all
     * parallel swarm workers complete.
     */
    fun resultJson(
        title: String,
        swarmRootDir: String,
        workers: List<SwarmWorkerStatus>,
        elapsedMs: Long,
    ): String {
        val total = workers.size
        val completed = workers.count { it.status == "completed" }
        val failed = workers.count { it.status == "failed" || it.status == "rejected" }
        val timedOut = workers.count { it.status == "timeout" }
        val cancelled = workers.count { it.status == "cancelled" }

        val overallStatus = when {
            completed > 0 -> "completed"
            cancelled == total -> "cancelled"
            timedOut > 0 -> "timeout"
            failed > 0 -> "failed"
            else -> "completed"
        }

        val modelsDistinct = workers.mapNotNull { it.modelUsed.takeIf(String::isNotEmpty) }.distinct()
        val modelSummary = when {
            modelsDistinct.isEmpty() -> ""
            modelsDistinct.size == 1 -> "${modelsDistinct.first()} (×$total)"
            else -> modelsDistinct.joinToString(", ")
        }

        val totalTurns = workers.sumOf { it.turns }
        val tabsAllocated = workers.mapNotNull { it.tabId }.distinct()

        // Build clean structured Markdown synthesis for `result` so both the
        // orchestrator LLM and the user detail sheet can read all worker outputs
        // in one scan without context pollution.
        val synthesisMd = buildString {
            append("## Swarm Fan-In Summary: ").append(title).append("\n")
            append("- **Workers**: ").append("$completed/$total completed")
            if (timedOut > 0) append(" ($timedOut timed out)")
            if (failed > 0) append(" ($failed failed)")
            append(" in ").append(elapsedMs / 1000L).append("s\n")
            append("- **Swarm Workspace**: `").append(swarmRootDir).append("`\n")
            if (tabsAllocated.isNotEmpty()) {
                append("- **Isolated Browser Tabs Used**: ").append(tabsAllocated.joinToString(", ") { "#$it" }).append(" (released)\n")
            }
            if (modelSummary.isNotEmpty()) {
                append("- **Worker Model(s)**: ").append(modelSummary).append("\n")
            }
            append("\n---\n\n")
            for ((idx, w) in workers.withIndex()) {
                val badge = when (w.status) {
                    "completed" -> "COMPLETED"
                    "timeout" -> "TIMEOUT"
                    "cancelled" -> "CANCELLED"
                    else -> w.status.uppercase()
                }
                append("### ").append(idx + 1).append(". [").append(badge).append("] ")
                append(w.title).append(" (`").append(w.workerId).append("`)\n")
                append("- **Workdir**: `").append(w.workdir).append("`")
                if (w.tabId != null && w.tabId >= 0) {
                    append(" · **Tab**: `#").append(w.tabId).append("`")
                }
                if (w.modelUsed.isNotEmpty()) {
                    append(" · **Model**: `").append(w.modelUsed).append("`")
                }
                append(" · **Turns**: ").append(w.turns)
                append(" · **Elapsed**: ").append(w.elapsedMs / 1000L).append("s\n\n")
                val body = w.result.trim().ifEmpty {
                    w.errorDetail?.let { "Error: $it" } ?: "(No deliverable text returned)"
                }
                append(body).append("\n\n")
                if (idx < workers.lastIndex) {
                    append("---\n\n")
                }
            }
        }.trim()

        val summaryLine = "Summary: swarm $completed/$total done · turns $totalTurns · dir $swarmRootDir"
        val primaryChildId = workers.firstOrNull()?.childSessionId.orEmpty()
        val primaryTier = workers.firstOrNull()?.tierUsed ?: "sub"
        val primaryOrigin = workers.firstOrNull()?.modelOrigin
        val primaryGroup = workers.firstOrNull()?.modelGroupName

        val arr = JSONArray()
        workers.forEach { arr.put(it.toJsonObject()) }

        return JSONObject()
            .put("ok", completed > 0)
            .put("swarm", true)
            .put("status", overallStatus)
            .put("title", title)
            .put("swarm_dir", swarmRootDir)
            .put("worker_count", total)
            .put("completed_count", completed)
            .put("model_used", modelSummary)
            .put("tier_used", primaryTier)
            .also { j ->
                if (!primaryOrigin.isNullOrEmpty()) j.put("model_origin", primaryOrigin)
                if (!primaryGroup.isNullOrEmpty()) j.put("model_group_name", primaryGroup)
            }
            .put("turns", totalTurns)
            .put("elapsed_s", (elapsedMs / 1000L).coerceAtLeast(0L))
            .put("child_session_id", primaryChildId)
            .put("summary", summaryLine)
            .put("result", synthesisMd)
            .put("workers", arr)
            .toString()
    }
}
