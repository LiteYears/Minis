package com.openminis.app.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Streaming chat export — T-export-optimize (b443b54d).
 *
 * Previously [SessionListScreen.exportSession] loaded every [MessageEntity]
 * for a session at once, built the full JSON / TXT payload as a single
 * [String] in memory, and handed that to [Intent.EXTRA_TEXT]. Hundreds of
 * messages caused jank, ghosting and OOM crashes.
 *
 * This exporter:
 *   - paginates the session via [ChatRepository.loadMessagePageRaw] in
 *     batches of [BATCH_SIZE], releasing each batch after it's written;
 *   - streams the serialized output into `cacheDir/export-staging/<uuid>/`
 *     using a [BufferedWriter] so peak memory stays bounded;
 *   - wraps the staged transcript + a `session.json` metadata sidecar in
 *     a single [ZipOutputStream]-built archive;
 *   - moves the final `.zip` into `cacheDir/shared/` (already declared in
 *     `file_provider_paths.xml`), where it can be handed out via
 *     [FileProvider];
 *   - cleans the staging directory on success and failure.
 *
 * Runs on [Dispatchers.IO]; [progress] is a [StateFlow] so a future UI
 * (progress overlay) can subscribe without re-architecting the call site.
 */
object ChatExporter {

    private const val BATCH_SIZE = 50
    private const val LOG_CATEGORY = "ChatExporter"

    sealed interface Progress {
        data object Idle : Progress
        data class Running(val done: Int, val total: Int) : Progress
        data class Done(val zipUri: Uri, val summary: Summary) : Progress
        data class Failed(val throwable: Throwable) : Progress
    }

    /**
     * Lightweight summary of a finished export. Populated as we stream so
     * the multi-select / "ready to share" UI can render a key-value preview
     * without re-reading the payload.
     */
    data class Summary(
        val format: String,              // "json" | "text"
        val messageCount: Int,
        val firstCreatedAt: Long?,       // ms, or null if empty
        val lastCreatedAt: Long?,
        val imageAttachments: Int,
        val videoAttachments: Int,
        val estimatedBytes: Long,
    )

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /**
     * Stream-export [session] in [format] (`"json"` | `"text"`) and return
     * a content [Uri] pointing at the zipped archive, suitable for
     * [Intent.ACTION_SEND]. Throws on failure; caller's coroutine scope
     * decides how to surface it. [Progress] is published to [progress] as
     * batches are written.
     */
    suspend fun exportToZip(
        context: Context,
        session: ChatSessionEntity,
        repository: ChatRepository,
        format: String,
    ): Pair<Uri, Summary> = withContext(Dispatchers.IO) {
        val isJson = format == "json"
        val ext = if (isJson) "json" else "txt"
        val stagingRoot = File(context.cacheDir, "export-staging")
        val workDir = File(stagingRoot, UUID.randomUUID().toString())
        if (!workDir.mkdirs() && !workDir.isDirectory) {
            throw IllegalStateException("export-staging mkdir failed: ${workDir.absolutePath}")
        }

        try {
            val transcriptFile = File(workDir, "messages.$ext")
            val summary = streamTranscript(repository, session, isJson, transcriptFile)

            val metaFile = File(workDir, "session.json")
            writeSessionMeta(metaFile, session, summary)

            // Build zip under shared/ so FileProvider can hand it out.
            val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
            val safeTitle = (session.title ?: "conversation")
                .replace(Regex("[^A-Za-z0-9_-]+"), "_")
                .take(64)
                .ifEmpty { "conversation" }
            val zipFile = File(sharedDir, "${safeTitle}-${session.id.take(8)}.zip")
            if (zipFile.exists()) zipFile.delete()

            ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zos ->
                zipFileEntry(zos, "messages.$ext", transcriptFile)
                zipFileEntry(zos, "session.json", metaFile)
            }

            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, zipFile)
            _progress.value = Progress.Done(uri, summary)
            AppLogger.info(LOG_CATEGORY, "exportToZip ok: ${zipFile.absolutePath} (${zipFile.length()} bytes, ${summary.messageCount} msgs)")
            uri to summary
        } catch (t: Throwable) {
            _progress.value = Progress.Failed(t)
            AppLogger.error(LOG_CATEGORY, "exportToZip failed: ${t.message}")
            throw t
        } finally {
            // Always clean staging — the zip itself lives under shared/.
            runCatching { workDir.deleteRecursively() }
        }
    }

    private suspend fun streamTranscript(
        repository: ChatRepository,
        session: ChatSessionEntity,
        isJson: Boolean,
        out: File,
    ): Summary {
        val total = repository.messageCount(session.id)
        var done = 0
        var first: Long? = null
        var last: Long? = null
        var images = 0
        var videos = 0
        var bytes = 0L

        _progress.value = Progress.Running(0, total)

        BufferedWriter(OutputStreamWriter(FileOutputStream(out), Charsets.UTF_8)).use { writer ->
            if (isJson) {
                // Stream a hand-rolled JSON array — `[ {…}, {…}, … ]` —
                // so we never materialize the whole list at once.
                writer.write("[")
                var firstEntry = true
                forEachBatch(repository, session.id, total) { batch ->
                    for (msg in batch) {
                        if (!firstEntry) writer.write(",")
                        firstEntry = false
                        val obj = JSONObject().apply {
                            put("id", msg.id)
                            put("role", msg.role)
                            put("content", msg.partsJson)
                            put("created_at", msg.createdAt)
                        }
                        val rendered = obj.toString()
                        writer.write(rendered)
                        bytes += rendered.length.toLong()
                        if (first == null) first = msg.createdAt
                        last = msg.createdAt
                        val (img, vid) = countAttachments(msg.partsJson)
                        images += img
                        videos += vid
                        done += 1
                    }
                    writer.flush()
                    _progress.value = Progress.Running(done, total)
                }
                writer.write("]")
            } else {
                writer.write(session.title ?: "Conversation")
                writer.write("\n\n")
                forEachBatch(repository, session.id, total) { batch ->
                    for (msg in batch) {
                        val role = if (msg.role == "user") "You" else "Assistant"
                        val text = extractPlainText(msg.partsJson)
                        writer.write(role)
                        writer.write(": ")
                        writer.write(text)
                        writer.write("\n\n")
                        bytes += text.length.toLong() + role.length + 4
                        if (first == null) first = msg.createdAt
                        last = msg.createdAt
                        val (img, vid) = countAttachments(msg.partsJson)
                        images += img
                        videos += vid
                        done += 1
                    }
                    writer.flush()
                    _progress.value = Progress.Running(done, total)
                }
            }
        }

        return Summary(
            format = if (isJson) "json" else "text",
            messageCount = done,
            firstCreatedAt = first,
            lastCreatedAt = last,
            imageAttachments = images,
            videoAttachments = videos,
            estimatedBytes = bytes,
        )
    }

    private suspend inline fun forEachBatch(
        repository: ChatRepository,
        sessionId: String,
        total: Int,
        block: (List<MessageEntity>) -> Unit,
    ) {
        if (total <= 0) return
        var offset = 0
        while (offset < total) {
            val batch = repository.loadMessagePageRaw(sessionId, offset, BATCH_SIZE)
            if (batch.isEmpty()) break
            block(batch)
            offset += batch.size
            if (batch.size < BATCH_SIZE) break
        }
    }

    private fun writeSessionMeta(file: File, session: ChatSessionEntity, summary: Summary) {
        val meta = JSONObject().apply {
            put("id", session.id)
            put("title", session.title ?: "")
            put("model_id", session.modelId)
            put("created_at", session.createdAt)
            put("message_count", summary.messageCount)
            summary.firstCreatedAt?.let { put("first_created_at", it) }
            summary.lastCreatedAt?.let { put("last_created_at", it) }
            put("image_attachments", summary.imageAttachments)
            put("video_attachments", summary.videoAttachments)
            put("format", summary.format)
        }
        file.writeText(meta.toString(2), Charsets.UTF_8)
    }

    private fun zipFileEntry(zos: ZipOutputStream, name: String, file: File) {
        zos.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { it.copyTo(zos, bufferSize = 16 * 1024) }
        zos.closeEntry()
    }

    private fun extractPlainText(partsJson: String): String = try {
        val arr = JSONArray(partsJson)
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("type") == "text") {
                // [T-android-retry-attachment-loss] Strip the persisted
                // <user-attached-files> XML inventory from the human-readable
                // text export — it's model-facing metadata, not chat content.
                // (The JSON export above keeps full-fidelity parts_json.)
                var value = obj.optString("value")
                val start = value.indexOf("<user-attached-files>")
                if (start >= 0) {
                    val endTag = "</user-attached-files>"
                    val end = value.indexOf(endTag, start)
                    value = if (end >= 0) {
                        value.substring(0, start) + value.substring(end + endTag.length)
                    } else {
                        value.substring(0, start)
                    }.trim()
                }
                if (value.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(value)
                }
            }
        }
        sb.toString()
    } catch (_: Throwable) {
        partsJson
    }

    /** Best-effort `(images, videos)` count by walking parts_json. */
    private fun countAttachments(partsJson: String): Pair<Int, Int> = try {
        val arr = JSONArray(partsJson)
        var img = 0
        var vid = 0
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            when (obj.optString("type")) {
                "image", "image_url" -> img += 1
                "video", "video_url" -> vid += 1
            }
        }
        img to vid
    } catch (_: Throwable) {
        0 to 0
    }
}

// ─── Merged from PendingShare.kt ──────────────────────────────────────────────────

/**
 * Mirrors iOS Shared/PendingShare.swift wire format so Android and iOS
 * share extensions emit/consume the same on-disk JSON. Future-proofs a
 * cross-platform sync if it ever lands.
 *
 * Wire shape: `{items: [{kind, value}], timestamp}`
 *   - kind ∈ {"inlineText", "attachment"}
 *   - value: for inlineText = the text; for attachment = filename in
 *            the share staging dir (filesDir/share_extension/).
 */
data class PendingShare(val items: List<Item>, val timestampMs: Long) {
    data class Item(val kind: Kind, val value: String) {
        enum class Kind(val wire: String) {
            INLINE_TEXT("inlineText"),
            ATTACHMENT("attachment"),
        }
    }

    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (item in items) {
            arr.put(JSONObject().put("kind", item.kind.wire).put("value", item.value))
        }
        return JSONObject().put("items", arr).put("timestamp", timestampMs)
    }

    companion object {
        fun fromJson(json: JSONObject): PendingShare? {
            val arr = json.optJSONArray("items") ?: return null
            val items = mutableListOf<Item>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val kindStr = o.optString("kind", "")
                if (kindStr.isEmpty()) continue
                val kind = Item.Kind.entries.firstOrNull { it.wire == kindStr } ?: continue
                val value = o.optString("value", "")
                if (value.isEmpty()) continue
                items += Item(kind, value)
            }
            if (items.isEmpty()) return null
            return PendingShare(items, json.optLong("timestamp", System.currentTimeMillis()))
        }
    }
}

// ─── Merged from SessionExporter.kt ──────────────────────────────────────────────────

/**
 * [T-android-session-multi-export] Exports several sessions into ONE file, the
 * way the session list's multi-select "Export" does on iOS
 * (ContentView.exportSessions / streamExportAsJSON / streamExportAsPlainText).
 *
 * Same output as iOS, so an export reads the same whichever device made it:
 *  - JSON: an array of sessions, each `{id, modelId, createdAt, updatedAt,
 *    title?, category?, messages: [...]}` with ISO-8601 dates; a message is
 *    `{role, parts, createdAt, tokenUsage?, reasoning?}` and its parts are
 *    cleaned (`text`, `tool_use{name, description?, input?}`,
 *    `tool_result{tool, success, output?}` with output capped at 2000 chars,
 *    `media` as a note).
 *  - Plain text: per session a `# title / Model / Created` header, then
 *    `[User]|[Minis] HH:mm` blocks separated by `---`; sessions separated by a
 *    line of 60 `=`.
 *  - Both start each session at its first user message, skip rows that are
 *    only tool results, and skip a session with no user message at all.
 *  - The payload is zipped (a single entry); if zipping fails the raw
 *    payload is shared instead, as iOS falls back.
 *  - File name: the session title (60 chars, `/` and `:` replaced) for one
 *    session, `minis-sessions-<n>` for several.
 *
 * Not ported: iOS writes a tool result's `snapshot` text/duration; Android's
 * ToolResult has no snapshot.
 *
 * Messages are read in pages of [BATCH_SIZE] and written as they arrive, so
 * memory stays bounded however long the sessions are (the T-export-optimize
 * contract of [ChatExporter]).
 */
object SessionExporter {

    const val BATCH_SIZE = 50
    private const val TOOL_OUTPUT_MAX = 2000
    private const val LOG_CATEGORY = "SessionExporter"

    enum class Format(val ext: String) { JSON("json"), PLAIN_TEXT("txt") }

    /** iOS ExportSummary, plus the image/video split Android's summary strings show. */
    data class Summary(
        val totalMessages: Int = 0,
        val images: Int = 0,
        val videos: Int = 0,
        val otherAttachments: Int = 0,
        val earliest: Long? = null,
        val latest: Long? = null,
    ) {
        val attachmentCount: Int get() = images + videos + otherAttachments
    }

    data class Result(
        val uri: Uri,
        val summary: Summary,
        val format: Format,
        val sessionCount: Int,
        val fileSizeBytes: Long,
        val fileName: String,
    )

    /** One page of a session's rows, oldest first; empty when exhausted. */
    fun interface PageLoader {
        suspend fun load(sessionId: String, offset: Int, limit: Int): List<MessageEntity>
    }

    /** iOS: the session title for one session, `minis-sessions-<n>` for several. */
    fun baseName(sessions: List<ChatSessionEntity>): String {
        val single = sessions.singleOrNull()
        if (single != null) {
            val title = single.title?.take(60)?.replace("/", "-")?.replace(":", "-")
            if (!title.isNullOrBlank()) return title
        }
        return "minis-sessions-${sessions.size}"
    }

    suspend fun exportToZip(
        context: Context,
        sessions: List<ChatSessionEntity>,
        repository: ChatRepository,
        format: Format,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        require(sessions.isNotEmpty()) { "nothing to export" }
        val workRoot = File(File(context.cacheDir, "export-staging"), "multi-${UUID.randomUUID()}")
        val payloadDir = File(workRoot, "payload")
        if (!payloadDir.mkdirs() && !payloadDir.isDirectory) {
            throw IllegalStateException("export-staging mkdir failed: ${payloadDir.absolutePath}")
        }
        try {
            val base = baseName(sessions)
            val payloadName = "$base.${format.ext}"
            val payload = File(payloadDir, payloadName)
            val loader = PageLoader { sid, offset, limit -> repository.loadMessagePageRaw(sid, offset, limit) }
            // Total up front (a count query per session), so progress reads
            // "n / total" rather than growing with every page as on iOS.
            val total = sessions.sumOf { repository.messageCount(it.id) }
            onProgress(0, total)
            val summary = BufferedWriter(OutputStreamWriter(FileOutputStream(payload), Charsets.UTF_8)).use { w ->
                when (format) {
                    Format.JSON -> writeJson(sessions, loader, w, total, onProgress)
                    Format.PLAIN_TEXT -> writePlainText(sessions, loader, w, total, onProgress)
                }
            }

            // Hand out from cacheDir/shared (declared in file_provider_paths).
            val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
            val zip = File(sharedDir, "$base.zip")
            val out = try {
                if (zip.exists()) zip.delete()
                ZipOutputStream(FileOutputStream(zip).buffered()).use { zos ->
                    zos.putNextEntry(ZipEntry(payloadName))
                    FileInputStream(payload).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                zip
            } catch (t: Throwable) {
                // iOS falls back to the raw payload when zipping fails.
                AppLogger.warning(LOG_CATEGORY, "zip failed, sharing the raw payload: ${t.message}")
                runCatching { zip.delete() }
                File(sharedDir, payloadName).also { raw ->
                    if (raw.exists()) raw.delete()
                    payload.copyTo(raw)
                }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
            AppLogger.info(
                LOG_CATEGORY,
                "export ok: ${out.name} sessions=${sessions.size} messages=${summary.totalMessages} bytes=${out.length()}",
            )
            Result(uri, summary, format, sessions.size, out.length(), out.name)
        } finally {
            runCatching { workRoot.deleteRecursively() }
        }
    }

    // ── Writers (pure: a loader in, text out; unit-tested) ───────────────────

    /** Rows of [sessionId] from its first user message on, page by page. */
    private suspend fun forEachRowFromFirstUser(
        sessionId: String,
        loader: PageLoader,
        block: suspend (MessageEntity) -> Unit,
    ) {
        var offset = 0
        var started = false
        while (true) {
            val page = loader.load(sessionId, offset, BATCH_SIZE)
            if (page.isEmpty()) break
            for (row in page) {
                if (!started && row.role != "user") continue
                started = true
                block(row)
            }
            offset += page.size
            if (page.size < BATCH_SIZE) break
        }
    }

    /** Whether a session has a user message at all (iOS skips it otherwise). */
    private suspend fun hasUserMessage(sessionId: String, loader: PageLoader): Boolean {
        var offset = 0
        while (true) {
            val page = loader.load(sessionId, offset, BATCH_SIZE)
            if (page.any { it.role == "user" }) return true
            if (page.size < BATCH_SIZE) return false
            offset += page.size
        }
    }

    suspend fun writeJson(
        sessions: List<ChatSessionEntity>,
        loader: PageLoader,
        out: Writer,
        total: Int = 0,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Summary {
        var summary = Summary()
        var done = 0
        out.write("[\n")
        var firstSession = true
        for (session in sessions) {
            if (!hasUserMessage(session.id, loader)) continue
            if (!firstSession) out.write(",\n")
            firstSession = false
            val header = JSONObject().apply {
                put("id", session.id)
                put("modelId", session.modelId)
                put("createdAt", iso(session.createdAt))
                put("updatedAt", iso(session.updatedAt))
                session.title?.let { put("title", it) }
                session.category?.let { put("category", it) }
            }
            // Open the session object, then stream its messages array.
            out.write(header.toString(2).trimEnd().removeSuffix("}"))
            out.write(",\n  \"messages\": [\n")
            var firstMsg = true
            var sinceProgress = 0
            forEachRowFromFirstUser(session.id, loader) { row ->
                done += 1
                val parts = parseParts(row.partsJson)
                if (isToolResultOnly(parts)) return@forEachRowFromFirstUser
                val clean = JSONArray()
                for (p in parts) {
                    val v = p.optJSONObject("value")
                    when (p.optString("type")) {
                        "text" -> clean.put(JSONObject().put("type", "text").put("text", p.optString("value")))
                        "toolUse" -> clean.put(
                            JSONObject().put("type", "tool_use").put("name", v?.optString("name").orEmpty()).apply {
                                v?.optString("description")?.takeIf { it.isNotEmpty() }?.let { put("description", it) }
                                v?.optString("input")?.takeIf { it.isNotEmpty() }?.let { put("input", it) }
                            },
                        )
                        "toolResult" -> clean.put(
                            JSONObject().put("type", "tool_result")
                                .put("tool", v?.optString("toolUseId").orEmpty())
                                .put("success", v?.optBoolean("success", true) ?: true)
                                .apply {
                                    val output = v?.optString("output").orEmpty()
                                    if (output.isNotEmpty()) {
                                        put(
                                            "output",
                                            if (output.length > TOOL_OUTPUT_MAX) output.take(TOOL_OUTPUT_MAX) + "\n…[truncated]" else output,
                                        )
                                    }
                                },
                        )
                        "mediaRef" -> {
                            clean.put(JSONObject().put("type", "media").put("note", "image/file attachment"))
                            summary = summary.countMedia(v?.optString("mimeType").orEmpty())
                        }
                    }
                }
                if (clean.length() == 0) return@forEachRowFromFirstUser
                val msg = JSONObject().apply {
                    put("role", row.role)
                    put("parts", clean)
                    put("createdAt", iso(row.createdAt))
                    row.tokenUsage?.let { raw -> runCatching { JSONObject(raw) }.getOrNull()?.let { put("tokenUsage", it) } }
                    row.reasoningContent?.takeIf { it.isNotEmpty() }?.let { put("reasoning", it) }
                }
                if (!firstMsg) out.write(",\n")
                firstMsg = false
                out.write(msg.toString(2))
                summary = summary.countMessage(row.createdAt)
                if (++sinceProgress >= BATCH_SIZE) {
                    sinceProgress = 0
                    out.flush()
                    onProgress(done, maxOf(total, done))
                }
            }
            out.write("\n  ]\n}")
            onProgress(done, maxOf(total, done))
        }
        out.write("\n]\n")
        return summary
    }

    suspend fun writePlainText(
        sessions: List<ChatSessionEntity>,
        loader: PageLoader,
        out: Writer,
        total: Int = 0,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Summary {
        val dateFmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        var summary = Summary()
        var done = 0
        sessions.forEachIndexed { i, session ->
            if (i > 0) out.write("\n\n" + "=".repeat(60) + "\n\n")
            out.write("# ${session.title ?: "Untitled"}\n")
            out.write("Model: ${session.modelId}\n")
            out.write("Created: ${dateFmt.format(Date(session.createdAt))}\n")
            out.write("-".repeat(40) + "\n")
            var sinceProgress = 0
            forEachRowFromFirstUser(session.id, loader) { row ->
                done += 1
                val parts = parseParts(row.partsJson)
                if (isToolResultOnly(parts)) return@forEachRowFromFirstUser
                val lines = mutableListOf<String>()
                for (p in parts) {
                    val v = p.optJSONObject("value")
                    when (p.optString("type")) {
                        "text" -> p.optString("value").takeIf { it.isNotEmpty() }?.let { lines.add(it) }
                        "toolUse" -> lines.add(
                            "[Tool: ${v?.optString("description")?.takeIf { it.isNotEmpty() } ?: v?.optString("name").orEmpty()}]",
                        )
                        // Android keeps no result snapshot, so this is iOS's no-snapshot branch.
                        "toolResult" -> lines.add("[Result: ${if (v?.optBoolean("success", true) != false) "ok" else "failed"}]")
                        "mediaRef" -> {
                            lines.add("[Attachment]")
                            summary = summary.countMedia(v?.optString("mimeType").orEmpty())
                        }
                    }
                }
                val body = lines.joinToString("\n")
                if (body.isNotEmpty()) {
                    val role = if (row.role == "user") "User" else "Minis"
                    out.write("\n[$role] ${timeFmt.format(Date(row.createdAt))}\n$body\n---\n")
                    summary = summary.countMessage(row.createdAt)
                }
                if (++sinceProgress >= BATCH_SIZE) {
                    sinceProgress = 0
                    out.flush()
                    onProgress(done, maxOf(total, done))
                }
            }
            onProgress(done, maxOf(total, done))
        }
        return summary
    }

    private fun parseParts(partsJson: String): List<JSONObject> = try {
        val arr = JSONArray(partsJson)
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    } catch (_: Exception) {
        // A row that is not a parts array is treated as plain text, as the
        // rest of the app does.
        listOf(JSONObject().put("type", "text").put("value", partsJson))
    }

    /** iOS `isToolResultOnly`: every part is a tool result. */
    private fun isToolResultOnly(parts: List<JSONObject>): Boolean =
        parts.isNotEmpty() && parts.all { it.optString("type") == "toolResult" }

    private fun Summary.countMessage(at: Long) = copy(
        totalMessages = totalMessages + 1,
        earliest = if (earliest == null || at < earliest) at else earliest,
        latest = if (latest == null || at > latest) at else latest,
    )

    private fun Summary.countMedia(mime: String) = when {
        mime.startsWith("image/") -> copy(images = images + 1)
        mime.startsWith("video/") -> copy(videos = videos + 1)
        else -> copy(otherAttachments = otherAttachments + 1)
    }

    private fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(ms))
}

// ─── Merged from ShareCoordinator.kt ──────────────────────────────────────────────────

/**
 * Mirrors iOS Shared/ShareCoordinator.swift. Singleton bridge between
 * ShareReceiverActivity (producer, lives in a different Activity instance
 * before re-launching MainActivity) and ChatScreen (consumer, lives inside
 * MainActivity). Holds the [PendingShare] in memory with a 30s TTL —
 * long enough for the launch flow to land in a chat session, short enough
 * that a stale share doesn't surprise the user weeks later.
 */
object ShareCoordinator {
    private const val TAG = "ShareCoordinator"
    // [T-android-share-buffer-merge] 30s → 300s, aligned with
    // LAUNCH_MAX_AGE_MS below and iOS a51e7255. The old 30s window expired
    // whenever the user lingered on the session list after sharing, so a
    // perfectly good share was discarded before they ever opened a chat.
    private const val BUFFER_TTL_MS = 300_000L
    private const val LAUNCH_MAX_AGE_MS = 5L * 60 * 1000

    /**
     * [T-android-share-routing] [targetSessionId] is the chat this share is
     * addressed to (mirrors iOS PendingShareBuffer.targetSessionId). Null
     * until the UI routes it ([addressTo]); an unaddressed share can be
     * consumed by nobody. It used to be drained by whichever chat composed
     * next - an unrelated or busy one included - and when the list was
     * showing nothing drained it at all.
     */
    private data class Buffered(
        val share: PendingShare,
        val bufferedAtMs: Long,
        val targetSessionId: String? = null,
    )

    @Volatile private var buffer: Buffered? = null

    private val _bufferVersion = MutableStateFlow(0)
    /**
     * Increments every time a new share is buffered. ChatScreen observes
     * this via collectAsState so a warm-start (user already inside a
     * session) injects too. Cold starts pick up the buffer on first
     * composition since the version is non-zero by then.
     */
    val bufferVersion: StateFlow<Int> = _bufferVersion.asStateFlow()

    /**
     * Called from MainActivity.onCreate / onNewIntent when the share
     * receiver re-launched MainActivity with the `shared_content` extra.
     * Loads from prefs, drops if older than [LAUNCH_MAX_AGE_MS] (5 min,
     * mirrors iOS launch check), stores in the in-memory buffer, clears
     * prefs.
     */
    @Synchronized
    fun processPendingShare(context: Context) {
        val pending = SharedShareStore.loadPendingShare(context) ?: run {
            AppLogger.info(TAG, "[Share] processPendingShare: no pending share")
            return
        }
        val ageMs = System.currentTimeMillis() - pending.timestampMs
        if (ageMs > LAUNCH_MAX_AGE_MS) {
            AppLogger.info(TAG, "[Share] processPendingShare: stale (age=${ageMs}ms), discarding")
            SharedShareStore.clearPendingShare(context)
            // [T-android-share-buffer-merge] Preserve files still referenced by a
            // live in-memory buffer. Discarding THIS stale record must not delete
            // an earlier share's attachments that are still waiting to be
            // injected — with a 300s buffer TTL that overlap is entirely
            // reachable.
            SharedShareStore.cleanSharedFiles(context, keep = liveBufferFileNames())
            return
        }
        SharedShareStore.clearPendingShare(context)
        // [T-android-share-buffer-merge] Append into an unconsumed buffer
        // instead of replacing it (mirrors iOS a51e7255). The old
        // single-slot `buffer = Buffered(...)` silently destroyed a share
        // that had been loaded but not yet injected into the chat — the
        // exact "shared two screenshots, only the second arrived" report.
        val now = System.currentTimeMillis()
        val existing = buffer
        val mergedShare = if (existing != null && now - existing.bufferedAtMs <= BUFFER_TTL_MS) {
            val seen = LinkedHashMap<Pair<PendingShare.Item.Kind, String>, PendingShare.Item>()
            for (item in existing.share.items + pending.items) {
                seen[item.kind to item.value] = item
            }
            AppLogger.info(
                TAG,
                "[Share] processPendingShare: merging ${existing.share.items.size} + " +
                    "${pending.items.size} -> ${seen.size} item(s)",
            )
            PendingShare(seen.values.toList(), pending.timestampMs)
        } else {
            AppLogger.info(TAG, "[Share] processPendingShare: buffering ${pending.items.size} item(s)")
            pending
        }
        // bufferedAt renews on merge so the combined buffer gets a full TTL.
        // [T-android-share-routing] Every new share is routed afresh (target
        // cleared), merged items included: the chat an earlier share was
        // addressed to may have been closed before it took it, and the user
        // is now looking at something else.
        buffer = Buffered(mergedShare, now)
        _bufferVersion.value = _bufferVersion.value + 1
        // [XSessionDiag] Hypothesis 2: this buffer is process-global, is NOT
        // addressed to any session, and MERGES rather than replaces — dedup is by
        // exact (kind, value), so near-duplicate fragments of the same text all
        // survive and concatenate. That is the shape of the reported corrupted
        // input (same sentence restarted, interleaved with its own translation).
        // Log every item's digest so a later report can be matched character-for-
        // character against what the model received. Fires once per share, not in
        // a loop over anything hot.
        logBufferState("buffered", mergedShare, now, bufferedAtMs = now)
    }

    /**
     * [XSessionDiag] Dump the buffer's contents with per-item digests.
     *
     * Truncates each value to [DIAG_VALUE_PREVIEW] chars: a share can carry a
     * whole article, and the diagnostic question is "which fragments merged
     * together", which the head of each item answers. Length is logged in full so
     * truncation never hides the real size.
     */
    private fun logBufferState(
        phase: String,
        share: PendingShare,
        nowMs: Long,
        bufferedAtMs: Long,
    ) {
        val items = share.items.mapIndexed { i, item ->
            val v = item.value
            val preview = v.take(DIAG_VALUE_PREVIEW).replace("\n", "\\n")
            "#$i(${item.kind.name} len=${v.length} \"$preview\"" +
                if (v.length > DIAG_VALUE_PREVIEW) "…\")" else "\")"
        }
        // ttlRemaining is measured from bufferedAtMs, matching consumeBuffer's own
        // expiry test — using share.timestampMs here would report a different
        // number than the one that actually governs the drop.
        AppLogger.info(
            TAG,
            "[XSessionDiag] share/$phase: items=${share.items.size} " +
                "shareTimestampMs=${share.timestampMs} bufferedAtMs=$bufferedAtMs now=$nowMs " +
                "ageMs=${nowMs - bufferedAtMs} ttlRemainingMs=${BUFFER_TTL_MS - (nowMs - bufferedAtMs)} " +
                "-> ${items.joinToString(" | ")}",
        )
    }

    /** Head-of-value length used by [logBufferState]; diagnostic only. */
    private const val DIAG_VALUE_PREVIEW = 100

    /**
     * Attachment file names referenced by the live in-memory buffer, if any.
     * Used to protect them from a directory-wide cleanup triggered by an
     * unrelated stale record. Empty when nothing is buffered.
     */
    private fun liveBufferFileNames(): Set<String> =
        buffer?.share?.items
            ?.filter { it.kind == PendingShare.Item.Kind.ATTACHMENT }
            ?.map { it.value }
            ?.toSet()
            ?: emptySet()

    /**
     * Consume the buffer (one-shot). Returns null if empty or expired.
     *
     * [T-android-share-buffer-merge] An expired buffer used to vanish with
     * only a log line — the user shared something, opened the app, and
     * nothing happened with no explanation. Now the discard surfaces a
     * toast. Mirrors iOS a51e7255's ShareFeedbackToast.
     */
    /**
     * [T-android-share-routing] True while a share is buffered but not yet
     * addressed to a chat - the UI's cue to route it ([addressTo]).
     */
    fun hasUnaddressedShare(): Boolean = buffer?.let { it.targetSessionId == null } == true

    /**
     * [T-android-share-routing] Address the buffered share to [sessionId] (the
     * visible chat, or a new draft opened for it). Only an unaddressed share
     * is addressed, so two routers racing (both chat routes composed during a
     * transition) cannot re-target it. Bumps [bufferVersion] so the target's
     * ChatScreen re-checks. Returns whether this call addressed it.
     */
    @Synchronized
    fun addressTo(sessionId: String): Boolean {
        val buf = buffer ?: return false
        if (buf.targetSessionId != null) return false
        buffer = buf.copy(targetSessionId = sessionId)
        AppLogger.info(TAG, "[Share] addressed ${buf.share.items.size} item(s) to session=${sessionId.take(16)}")
        _bufferVersion.value = _bufferVersion.value + 1
        return true
    }

    /**
     * [T-android-share-routing] Consume the share if it is addressed to
     * [sessionId]. Anything else - no share, unaddressed, or addressed to
     * another chat - returns null and leaves the buffer untouched.
     */
    @Synchronized
    fun consumeBuffer(context: Context, sessionId: String): PendingShare? {
        val buf = buffer ?: return null
        if (buf.targetSessionId != sessionId) return null
        val diagSessionId: String? = sessionId
        buffer = null
        val nowMs = System.currentTimeMillis()
        val ageMs = nowMs - buf.bufferedAtMs
        if (ageMs > BUFFER_TTL_MS) {
            AppLogger.info(TAG, "[Share] consumeBuffer: expired (age=${ageMs}ms)")
            AppLogger.info(
                TAG,
                "[XSessionDiag] share/consume-expired: " +
                    "consumer=${diagSessionId?.take(8) ?: "unknown"} " +
                    "items=${buf.share.items.size} ageMs=$ageMs ttlMs=$BUFFER_TTL_MS",
            )
            SharedShareStore.cleanSharedFiles(context)
            notifyExpired(context)
            return null
        }
        AppLogger.info(TAG, "[Share] consumeBuffer: ${buf.share.items.size} item(s)")
        // [XSessionDiag] The consumer is now always the addressed session
        // ([T-android-share-routing]); logged to keep the repro trail.
        logBufferState("consume", buf.share, nowMs, buf.bufferedAtMs)
        AppLogger.info(
            TAG,
            "[XSessionDiag] share/consumed-by: consumer=${diagSessionId?.take(8) ?: "unknown"} " +
                "items=${buf.share.items.size} ageMs=$ageMs",
        )
        return buf.share
    }

    /**
     * Surface the expiry to the user. Posted on the main looper because
     * consumeBuffer is reachable from a composition/IO context, and Toast
     * requires a Looper-backed thread.
     */
    private fun notifyExpired(context: Context) {
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    android.widget.Toast.makeText(
                        context.applicationContext,
                        context.getString(com.openminis.app.R.string.share_expired_toast),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }
}

// ─── Merged from ShareHandoffPolicy.kt ──────────────────────────────────────────────────

/**
 * [T-android-share-launch-crash] The retry ladder [ShareReceiverActivity] uses
 * to hand off to MainActivity, extracted so it can be unit-tested.
 *
 * ## Why this exists
 *
 * Field report (vivo V2352A / Android 14, Minis 0.20-preview): sharing into
 * Minis crashed the app immediately and repeatedly (HangDetector recorded
 * restartCount=50). The crash is raised inside `startActivity`:
 *
 *     RuntimeException: Unable to start activity …ShareReceiverActivity
 *     Caused by: NullPointerException … String.equals(Object) on null
 *       at android.os.Parcel.createExceptionOrNull(Parcel.java:3077)
 *       at IActivityTaskManager$Stub$Proxy.startActivity(…)
 *     Caused by: RemoteException: Remote stack trace:
 *       at VivoActivityStarterImpl.generateLaunchFreeFormOption(:2195)
 *
 * The fault is entirely in the OEM's freeform-window logic in system_server: it
 * NPEs, and the exception it marshals back has a null message, so
 * `Parcel.readException` NPEs again rebuilding it. Both land on our main thread,
 * so an unguarded call kills the app.
 *
 * The real Activity work (building Intents, calling `startActivity`) needs a
 * live Context and cannot run on the JVM. What CAN be tested is the decision
 * layer: how many attempts are made, in what order, and what happens when each
 * one throws. That is what [handOff] models — the caller supplies the attempts
 * as lambdas, so a test can make any of them throw on demand and assert the
 * outcome without a device.
 */
object ShareHandoffPolicy {

    /** What ultimately happened, for logging and for the caller's UI decision. */
    enum class Outcome {
        /** The primary attempt (NEW_TASK | CLEAR_TOP) succeeded. */
        LAUNCHED,

        /** The primary attempt threw; the NEW_TASK-only retry succeeded. */
        LAUNCHED_VIA_FALLBACK,

        /**
         * Every attempt threw. The caller must not crash: the share is already
         * persisted, so it tells the user to open the app manually.
         */
        FAILED,
    }

    /**
     * Run [primary], then [fallback] if the first throws, and report which
     * (if either) succeeded.
     *
     * Catches [Throwable] rather than [Exception] on purpose: the observed
     * top-level failure is a RuntimeException, but the Binder unwrap that
     * produces it can surface other Errors, and there is no failure mode in a
     * best-effort hand-off worth crashing the process over. This function
     * therefore never throws — that guarantee is the entire point, since it is
     * called from `onCreate`, where anything escaping becomes "Unable to start
     * activity" and takes the app down.
     *
     * @param onError invoked with each failure so the caller can log it; its
     *   own throws are swallowed too, so a broken logger cannot defeat the guard.
     */
    fun handOff(
        primary: () -> Unit,
        fallback: () -> Unit,
        onError: (stage: String, error: Throwable) -> Unit = { _, _ -> },
    ): Outcome {
        try {
            primary()
            return Outcome.LAUNCHED
        } catch (t: Throwable) {
            report(onError, "primary", t)
        }

        try {
            fallback()
            return Outcome.LAUNCHED_VIA_FALLBACK
        } catch (t: Throwable) {
            report(onError, "fallback", t)
        }

        return Outcome.FAILED
    }

    private fun report(onError: (String, Throwable) -> Unit, stage: String, t: Throwable) {
        try {
            onError(stage, t)
        } catch (_: Throwable) {
            // A logging failure must never turn into the crash we are preventing.
        }
    }
}

// ─── Merged from SharedShareStore.kt ──────────────────────────────────────────────────

/**
 * Mirrors iOS Shared/SharedContainerStore.swift. SharedPreferences-backed
 * read/write of [PendingShare]; staged binary attachments live under
 * `filesDir/share_extension/` so the producer (ShareReceiverActivity)
 * can copy ContentResolver streams into a path the consumer can read
 * after the producer activity finishes.
 */
object SharedShareStore {
    private const val TAG = "SharedShareStore"
    private const val PREFS_NAME = "share_prefs"
    private const val KEY_PENDING_SHARE = "pending_share"
    private const val SHARE_DIR = "share_extension"

    /**
     * [T-android-share-buffer-merge] Window during which a newly saved share
     * merges into the existing unconsumed record rather than replacing it.
     * 300s matches iOS a51e7255 and ShareCoordinator's launch staleness cutoff.
     */
    private const val MERGE_MAX_AGE_MS = 300_000L

    /**
     * [T-android-share-buffer-merge] Upper bound on a merged item list. Merging
     * is unbounded by nature — a user (or a misbehaving sender) repeatedly
     * sharing inside the 300s window would otherwise grow one record without
     * limit and dump all of it into the composer at once. Keep the NEWEST
     * items on overflow: the most recent share is what the user is acting on.
     */
    private const val MERGE_MAX_ITEMS = 50

    fun sharedFileDirectory(context: Context): File =
        File(context.filesDir, SHARE_DIR).apply { mkdirs() }

    /**
     * [T-android-share-buffer-merge] Persist a share, MERGING with an
     * unconsumed record instead of replacing it.
     *
     * Mirrors iOS a51e7255. Previously this replaced the record wholesale:
     * sharing two screenshots before the main app ran processPendingShare
     * silently destroyed the first one. Attachment file names are
     * UUID-suffixed, so appending across shares is collision-free on disk.
     *
     * Only merges while the existing record is younger than
     * [MERGE_MAX_AGE_MS]; an older record is treated as abandoned and
     * replaced (its staged files are cleaned so they don't leak).
     */
    fun savePendingShare(context: Context, share: PendingShare) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = loadPendingShare(context)
        val now = System.currentTimeMillis()
        val merged = if (existing != null && now - existing.timestampMs <= MERGE_MAX_AGE_MS) {
            // De-dupe by (kind, value) so a double-delivered intent doesn't
            // inject the same attachment twice.
            val seen = LinkedHashMap<Pair<PendingShare.Item.Kind, String>, PendingShare.Item>()
            for (item in existing.items + share.items) {
                seen[item.kind to item.value] = item
            }
            // Cap the list, keeping the NEWEST items (takeLast) — the incoming
            // share is what the user just acted on.
            val all = seen.values.toList()
            val capped = if (all.size > MERGE_MAX_ITEMS) all.takeLast(MERGE_MAX_ITEMS) else all
            AppLogger.info(
                TAG,
                "merging pending share: ${existing.items.size} + ${share.items.size} -> ${capped.size} item(s)" +
                    if (capped.size < all.size) " (capped from ${all.size})" else "",
            )
            // Renew the timestamp so the merged record gets a full window.
            PendingShare(capped, now)
        } else {
            share
        }
        prefs.edit().putString(KEY_PENDING_SHARE, merged.toJson().toString()).apply()
        AppLogger.info(TAG, "saved pending share with ${merged.items.size} item(s)")
    }

    fun loadPendingShare(context: Context): PendingShare? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PENDING_SHARE, null) ?: return null
        return try {
            PendingShare.fromJson(JSONObject(raw))
        } catch (e: Exception) {
            AppLogger.warning(TAG, "failed to decode pending share: ${e.message}")
            null
        }
    }

    fun clearPendingShare(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_PENDING_SHARE).apply()
    }

    /**
     * Remove staged binary attachments. Call after consumption or on stale buffer.
     *
     * [keep] names any files that must survive — pass the item values of a share
     * that is still live in memory.
     *
     * [T-android-share-buffer-merge] Why `keep` exists: this used to wipe the
     * whole directory unconditionally. Raising the in-memory buffer TTL from
     * 30s to 300s widened a window where share A can still be buffered
     * (holding file references) while a NEWLY ARRIVING stale prefs record
     * takes the discard path and deletes A's files out from under it, leaving
     * broken attachments. Callers that know about a live buffer now exclude it.
     */
    fun cleanSharedFiles(context: Context, keep: Set<String> = emptySet()) {
        val dir = sharedFileDirectory(context)
        dir.listFiles()?.forEach { f ->
            if (f.name !in keep) runCatching { f.delete() }
        }
    }
}
