package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.PRootKernel
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

object FileReadTool {
    const val NAME = "file_read"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read a file from the Linux filesystem, or optionally list a directory (`list_dir: true`) or search across files (`search_pattern`). Faster than shell_execute — no shell overhead. Returns file content with metadata. Rejects binary files.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Read Python script contents', 'Check system configuration file'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute Linux path to read (e.g. /var/minis/workspace/data.csv)"),
            "offset" to AgentToolParam("integer", "1-based line number to start reading from (default: 1). Ignored when direction is 'tail'. If a previous read was truncated, its header ends with next_offset=N — pass that as offset to continue from where it stopped."),
            "lines" to AgentToolParam("integer", "Maximum number of lines to return (default: all lines up to max_length)"),
            "max_length" to AgentToolParam("integer", "Maximum character length of returned content (default: 15000)"),
            "direction" to AgentToolParam("string", "Read direction: 'head' (from start, default) or 'tail' (from end of file)"),
            "search_pattern" to AgentToolParam("string", "Optional regex or substring pattern to search inside `path` (file or directory) with 1-based line numbers, without spawning a shell."),
            "list_dir" to AgentToolParam("boolean", "If true and `path` is a directory, return a structured listing of its entries with sizes instead of an error (default: false)."),
        ),
        required = listOf("tool_title", "path"),
        propertyOrdering = listOf("tool_title", "path", "offset", "lines", "direction", "max_length", "search_pattern", "list_dir"),
    )

    fun defaultMaxLengthFor(
        policy: com.openminis.app.agent.ContextInstructionPolicy = com.openminis.app.agent.ContextInstructionPolicy.STANDARD,
    ): Int = when (policy) {
        com.openminis.app.agent.ContextInstructionPolicy.STANDARD -> 15_000
        com.openminis.app.agent.ContextInstructionPolicy.AGGRESSIVE,
        com.openminis.app.agent.ContextInstructionPolicy.EXPERIMENTAL -> 40_000
    }

    fun execute(
        argsJson: String,
        sessionId: String,
        context: Context,
        policy: com.openminis.app.agent.ContextInstructionPolicy = com.openminis.app.agent.ContextInstructionPolicy.get(context),
    ): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            val toolTitle = args.optString("tool_title", NAME)
            val offset = args.optInt("offset", 1).coerceAtLeast(1)
            val MAX_LENGTH_HARD_CAP = 80_000
            val defaultMaxLen = defaultMaxLengthFor(policy)
            val maxLength = args.optInt("max_length", defaultMaxLen).coerceAtMost(MAX_LENGTH_HARD_CAP)
            val direction = args.optString("direction", "head")
            val searchPattern = args.optString("search_pattern", "").trim()
            val listDir = args.optBoolean("list_dir", false)

            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }

            // T123: per-session resolver — see FileWriteTool for rationale.
            val file = PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)

            if (!file.exists()) {
                val hint = suggestNearbyFilesHint(file, path)
                return ToolExecutionResult("Error: File not found: $path$hint", false, toolTitle = toolTitle)
            }

            if (searchPattern.isNotEmpty()) {
                return try {
                    FileToolIO.run("Searching $path") {
                        searchInPath(file, path, searchPattern, maxLength, toolTitle)
                    }
                } catch (e: FileToolIO.TimedOut) {
                    ToolExecutionResult("Error: ${e.message}", false, toolTitle = toolTitle, timedOut = true)
                } catch (e: FileToolIO.OutOfMemory) {
                    ToolExecutionResult("Error: ${e.message}", false, toolTitle = toolTitle)
                }
            }

            if (file.isDirectory) {
                if (listDir) {
                    return try {
                        FileToolIO.run("Listing $path") {
                            listDirectoryStructured(file, path, maxLength, toolTitle)
                        }
                    } catch (e: FileToolIO.TimedOut) {
                        ToolExecutionResult("Error: ${e.message}", false, toolTitle = toolTitle, timedOut = true)
                    } catch (e: FileToolIO.OutOfMemory) {
                        ToolExecutionResult("Error: ${e.message}", false, toolTitle = toolTitle)
                    }
                }
                val hint = directoryEntriesHint(file, path)
                return ToolExecutionResult("Error: Path is a directory: $path$hint", false, toolTitle = toolTitle)
            }

            val requestedLines = if (args.has("lines")) args.optInt("lines") else null
            if (requestedLines != null && requestedLines < 0) {
                return ToolExecutionResult("Error: 'lines' must not be negative", false, toolTitle = toolTitle)
            }

            // [T-port-filetool-size-guard] The read runs off the caller's
            // thread with a timeout, and streams the file (see read()).
            try {
                FileToolIO.run("Reading $path") {
                    read(file, path, offset, requestedLines, maxLength, direction, toolTitle)
                }
            } catch (e: FileToolIO.TimedOut) {
                ToolExecutionResult("Error: ${e.message}", false, toolTitle = toolTitle, timedOut = true)
            } catch (e: FileToolIO.OutOfMemory) {
                ToolExecutionResult(
                    "Error: ${e.message} Read a smaller range with offset/lines or direction='tail', " +
                        "or use the shell (head / tail / grep).",
                    false, toolTitle = toolTitle,
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult("Error reading file: ${e.message}", false)
        }
    }

    /**
     * Builds a short, actionable hint when [file] does not exist so weak models
     * immediately see case-insensitive matches or sibling files in the parent
     * directory without burning an extra `shell_execute("ls ...")` turn.
     */
    internal fun suggestNearbyFilesHint(file: File, requestedPath: String): String {
        return try {
            val targetName = file.name.lowercase()
            val parent = file.parentFile
            if (parent != null && parent.exists() && parent.isDirectory) {
                val entries = parent.listFiles()?.toList().orEmpty()
                if (entries.isEmpty()) {
                    return "\n(Parent directory '${requestedPath.substringBeforeLast('/').ifEmpty { "/" }}' exists but is empty.)"
                }
                val similar = entries.filter { entry ->
                    val n = entry.name.lowercase()
                    n == targetName || n.contains(targetName) || targetName.contains(n)
                }.sortedBy { it.name }.take(8)
                val parentLinuxPath = requestedPath.substringBeforeLast('/').ifEmpty { "/" }
                return if (similar.isNotEmpty()) {
                    val formatted = similar.joinToString(", ") {
                        if (it.isDirectory) "$parentLinuxPath/${it.name}/" else "$parentLinuxPath/${it.name}"
                    }
                    "\nDid you mean: $formatted"
                } else {
                    val sample = entries.sortedBy { it.name }.take(15).joinToString(", ") {
                        if (it.isDirectory) "${it.name}/" else it.name
                    }
                    val more = if (entries.size > 15) " (+${entries.size - 15} more)" else ""
                    "\nEntries in $parentLinuxPath: $sample$more"
                }
            }
            ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun directoryEntriesHint(dir: File, requestedPath: String): String {
        return try {
            val entries = dir.listFiles()?.sortedBy { it.name }.orEmpty()
            if (entries.isEmpty()) {
                "\n(Directory is empty.)"
            } else {
                val preview = entries.take(30).joinToString(", ") {
                    if (it.isDirectory) "${it.name}/" else it.name
                }
                val more = if (entries.size > 30) " (+${entries.size - 30} more)" else ""
                "\nDirectory contents ($requestedPath): $preview$more\nPass a file path inside this directory to file_read."
            }
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * Read [file] for file_read. Pure apart from the file access, so tests can
     * call it on a temp file.
     *
     * [T-port-filetool-size-guard] This used to be File.readLines - the
     * whole file in memory before the 80 KB output cap applied, so a read of
     * a multi-hundred-MB log or dump threw OutOfMemoryError, which no caller
     * catches, and killed the app. FileToolIO.scanLines streams the file once
     * and keeps only what the cap can return; the output (header, range,
     * truncation, next_offset) is unchanged for every file that used to fit.
     */
    internal fun read(
        file: java.io.File,
        path: String,
        offset: Int,
        requestedLines: Int?,
        maxLength: Int,
        direction: String,
        toolTitle: String,
    ): ToolExecutionResult {
        val size = file.length()

        // Binary detection: check first 8192 bytes for null bytes.
        // minOf on Long: size.toInt() overflowed for files over 2 GB.
        val isBinary = file.inputStream().use { input ->
            val buf = ByteArray(minOf(8192L, size).toInt())
            val read = input.read(buf)
            if (read > 0) buf.take(read).any { it == 0.toByte() } else false
        }

        if (isBinary) {
            return ToolExecutionResult(
                "[$path | $size bytes | binary file — cannot display contents]",
                true, toolTitle = toolTitle
            )
        }

        val window = FileToolIO.scanLines(
            file, tail = direction == "tail", offset = offset,
            lines = requestedLines, maxLength = maxLength,
        )
        val totalLines = window.totalLines
        val selectedCount = window.selectedCount

        val showStart = if (direction == "tail") {
            (totalLines - selectedCount) + 1
        } else {
            offset
        }
        val showEnd = showStart + selectedCount - 1

        var content = window.content

        // [T-fileread-truncation-header] The header used to report the line
        // range chosen BEFORE truncation, and said nothing about having
        // truncated at all — only the body gained a trailing
        // "... (truncated)". So a cut-off read still announced
        // "showing 1-1324 of 1324", which the agent took as the whole file
        // and never paged on.
        //
        // Recompute the range that actually survived and hand back the
        // offset to resume from. Confined to the truncating branch; a read
        // that fits is byte-identical to before.
        var effectiveStart = showStart
        var effectiveEnd = showEnd
        var nextOffset: Int? = null
        var wasTruncated = false
        if (content.length > maxLength) {
            wasTruncated = true
            if (direction == "tail") {
                // tail asks for the END of the file; take() returned the
                // start of the tail window instead — the opposite.
                content = content.takeLast(maxLength)
                // Drop a leading partial line so the first line is whole.
                val firstNewline = content.indexOf('\n')
                if (firstNewline in 0 until content.length - 1) {
                    content = content.substring(firstNewline + 1)
                }
                effectiveStart = effectiveEnd - content.count { it == '\n' }
                // No next_offset for tail: paging forward from the end of
                // the file is meaningless.
            } else {
                content = content.take(maxLength)
                // Back off to the last complete line, so the next page does
                // not re-read or split a line.
                val lastNewline = content.lastIndexOf('\n')
                if (lastNewline > 0) content = content.substring(0, lastNewline)
                effectiveEnd = showStart + content.count { it == '\n' }
                if (effectiveEnd < totalLines) nextOffset = effectiveEnd + 1
            }
        }

        var header = "[$path | $size bytes | $totalLines lines | " +
            "showing $effectiveStart-$effectiveEnd of $totalLines"
        if (wasTruncated) {
            header += " | truncated at $maxLength chars"
            // Named to match the tool's own `offset` parameter so the model
            // can copy it straight into the next call.
            header += if (nextOffset != null) ", next_offset=$nextOffset"
                      else ", retry with a smaller lines value"
        }
        header += "]"
        return ToolExecutionResult("$header\n$content", true, toolTitle = toolTitle)
    }

    internal fun listDirectoryStructured(
        dir: File,
        requestedPath: String,
        maxLength: Int,
        toolTitle: String,
    ): ToolExecutionResult {
        val entries = dir.listFiles()
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .orEmpty()
        val normalizedBase = requestedPath.trimEnd('/').ifEmpty { "/" }
        if (entries.isEmpty()) {
            return ToolExecutionResult("[$normalizedBase/ | 0 entries | empty directory]", true, toolTitle = toolTitle)
        }
        val sb = StringBuilder()
        var shown = 0
        for (entry in entries) {
            val line = if (entry.isDirectory) {
                val childCount = entry.listFiles()?.size ?: 0
                "dir   ${entry.name}/ ($childCount items)\n"
            } else {
                "file  ${entry.name} (${entry.length()} bytes)\n"
            }
            if (sb.length + line.length > maxLength) break
            sb.append(line)
            shown++
        }
        val truncNote = if (shown < entries.size) " | showing $shown of ${entries.size}" else ""
        val header = "[$normalizedBase/ | ${entries.size} entries$truncNote]"
        return ToolExecutionResult("$header\n${sb.toString().trimEnd()}", true, toolTitle = toolTitle)
    }

    private val SKIP_SEARCH_DIRS = setOf(".git", "node_modules", ".gradle", "build", "__pycache__", ".venv", "venv", ".cache")

    internal fun searchInPath(
        root: File,
        requestedPath: String,
        pattern: String,
        maxLength: Int,
        toolTitle: String,
    ): ToolExecutionResult {
        val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
        val lowerNeedle = pattern.lowercase()
        val sb = StringBuilder()
        var matchCount = 0
        var filesScanned = 0
        val maxMatches = 250
        val maxFileBytes = 2L * 1024 * 1024
        val normalizedBase = requestedPath.trimEnd('/')

        fun scanSingleFile(f: File, displayPath: String) {
            if (matchCount >= maxMatches || sb.length >= maxLength) return
            val len = f.length()
            if (len <= 0L || len > maxFileBytes) return
            val isBin = runCatching {
                f.inputStream().use { input ->
                    val buf = ByteArray(minOf(2048L, len).toInt())
                    val r = input.read(buf)
                    if (r > 0) buf.take(r).any { it == 0.toByte() } else false
                }
            }.getOrDefault(true)
            if (isBin) return
            filesScanned++
            runCatching {
                f.bufferedReader(Charsets.UTF_8).useLines { seq ->
                    var lineNo = 0
                    for (line in seq) {
                        lineNo++
                        val hit = if (regex != null) {
                            regex.containsMatchIn(line)
                        } else {
                            line.lowercase().contains(lowerNeedle)
                        }
                        if (hit) {
                            matchCount++
                            val snippet = if (line.length > 220) line.take(220) + "…" else line
                            val row = "$displayPath:$lineNo: $snippet\n"
                            if (sb.length + row.length <= maxLength) {
                                sb.append(row)
                            }
                            if (matchCount >= maxMatches || sb.length >= maxLength) break
                        }
                    }
                }
            }
        }

        val visitedDirs = HashSet<String>()

        fun walkDir(dir: File, relPrefix: String, depth: Int) {
            if (depth > 8 || matchCount >= maxMatches || sb.length >= maxLength) return
            val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
            if (!visitedDirs.add(canonical)) return
            val children = dir.listFiles()?.sortedBy { it.name }.orEmpty()
            for (child in children) {
                if (matchCount >= maxMatches || sb.length >= maxLength) break
                if (child.isDirectory) {
                    if (child.name in SKIP_SEARCH_DIRS) continue
                    val nextRel = if (relPrefix.isEmpty()) child.name else "$relPrefix/${child.name}"
                    walkDir(child, nextRel, depth + 1)
                } else if (child.isFile) {
                    val rel = if (relPrefix.isEmpty()) child.name else "$relPrefix/${child.name}"
                    scanSingleFile(child, "$normalizedBase/$rel")
                }
            }
        }

        if (root.isFile) {
            scanSingleFile(root, requestedPath)
        } else {
            walkDir(root, "", 0)
        }

        val header = "[Search '$pattern' in $requestedPath | $matchCount match(es) across $filesScanned file(s)]"
        val body = if (matchCount == 0) "(No matches found)" else sb.toString().trimEnd()
        return ToolExecutionResult("$header\n$body", true, toolTitle = toolTitle)
    }
}

/**
 * Per-session bounded undo ring buffer for `file_edit` and `file_write`.
 * Stores the previous content of modified text files (< 2 MB) so an accidental
 * edit or overwrite can be reverted in one call (`file_edit` with `undo: true`).
 */
object FileUndoStore {
    private const val MAX_SNAPSHOT_BYTES = 2L * 1024 * 1024
    private const val MAX_SESSION_TOTAL_BYTES = 4L * 1024 * 1024
    private const val MAX_HISTORY_PER_SESSION = 16

    private data class Snapshot(
        val path: String,
        val bytes: ByteArray,
        val timestampMs: Long,
    )

    private val snapshotsBySession = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<Snapshot>>()

    private fun normalizePath(path: String): String =
        path.trim().replace(Regex("/+"), "/")

    fun recordSnapshot(sessionId: String, path: String, file: File) {
        try {
            if (!file.exists() || !file.isFile) return
            val len = file.length()
            if (len <= 0L || len > MAX_SNAPSHOT_BYTES) return
            val bytes = file.readBytes()
            val key = sessionId.ifBlank { "__default__" }
            val normPath = normalizePath(path)
            val deque = snapshotsBySession.getOrPut(key) { ArrayDeque() }
            synchronized(deque) {
                deque.addLast(Snapshot(path = normPath, bytes = bytes, timestampMs = System.currentTimeMillis()))
                while (deque.size > MAX_HISTORY_PER_SESSION ||
                    (deque.size > 1 && deque.sumOf { it.bytes.size.toLong() } > MAX_SESSION_TOTAL_BYTES)
                ) {
                    deque.removeFirst()
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun revertLast(sessionId: String, path: String, targetFile: File): ByteArray? {
        val key = sessionId.ifBlank { "__default__" }
        val normPath = normalizePath(path)
        val deque = snapshotsBySession[key] ?: return null
        val snap = synchronized(deque) {
            val idx = deque.indexOfLast { it.path == normPath }
            if (idx < 0) null else deque.removeAt(idx)
        } ?: return null
        targetFile.parentFile?.mkdirs()
        targetFile.writeBytes(snap.bytes)
        return snap.bytes
    }
}

// ─── Merged from FileToolIO.kt ──────────────────────────────────────────────────

/**
 * [T-port-filetool-size-guard] Shared I/O plumbing for file_read / file_write /
 * file_edit, ported from iOS FileToolIO (26e6e63e9).
 *
 * - [run] does the blocking file work on a dedicated pool with a timeout. A
 *   read stuck on an unresponsive mount (FUSE / a mounted external folder)
 *   used to hold the agent loop forever, and Stop cannot interrupt a blocked
 *   FileInputStream.read. It now holds one pool thread instead, and the tool
 *   returns a timed-out result the agent can act on.
 * - [run] also turns an OutOfMemoryError into a tool error. Every catch up the
 *   stack only catches Exception, so an OOM from a huge file used to kill the
 *   app.
 * - [scanLines] streams a file once and keeps only the bytes the caller can
 *   actually return, so file_read on a multi-hundred-MB log no longer loads
 *   the whole file (File.readLines did).
 */
internal object FileToolIO {
    /** Overall time allowed for one file tool's I/O (iOS FileToolIO.ioTimeout). */
    const val IO_TIMEOUT_MS = 45_000L

    /**
     * file_edit refuses files larger than this (iOS FileToolIO.maxEditBytes):
     * the edit holds the whole file plus a rewritten copy in memory. The shell
     * (sed / a script) handles larger files.
     */
    const val MAX_EDIT_BYTES = 8L * 1024 * 1024

    /**
     * [GH #453] Most pool threads at once. The pool was unbounded: a read
     * blocked in the kernel on a mount that never answers keeps its thread
     * after the 45 s timeout, and an agent retrying file tools on that mount
     * added one stuck thread per call for as long as it ran.
     *
     * [T-fileio-cap-healthy-load] The cap can also be reached by healthy
     * work: a turn runs up to 10 tool calls at once, across several chats and
     * sub agents. So a full pool is only reported as hung mounts when at least
     * half of it is held by work whose caller already timed out ([abandoned]);
     * otherwise the call waits up to [SLOT_WAIT_MS] for a thread and, if none
     * frees, says the app is busy - never that a mount stopped answering.
     */
    const val MAX_THREADS = 32

    /** How long a call waits for a pool thread while the pool is busy with live work. */
    const val SLOT_WAIT_MS = 10_000L

    /** Per pool: tasks still running although their caller gave up on them (timed out or interrupted). */
    private val abandonedByPool = java.util.WeakHashMap<ExecutorService, AtomicInteger>()

    private fun abandoned(executor: ExecutorService): AtomicInteger =
        synchronized(abandonedByPool) { abandonedByPool.getOrPut(executor) { AtomicInteger() } }

    /** Work held by hung calls fills at least half the pool: report it as a mount that is not answering. */
    internal fun poolHeldByHungWork(abandoned: Int, max: Int): Boolean = abandoned >= maxOf(1, max / 2)

    private val threadIds = AtomicInteger()

    /** Threads up to [max], none kept idle past 60 s, no queue: over the cap, submit throws. */
    internal fun newPool(max: Int): ExecutorService =
        ThreadPoolExecutor(0, max, 60L, TimeUnit.SECONDS, SynchronousQueue()) { r ->
            // [GH #453] Named for the per-name thread count (ThreadNames).
            Thread(r, com.openminis.app.diagnostics.ThreadNames.of("fileio-${threadIds.incrementAndGet()}")).apply { isDaemon = true }
        }

    private val pool = newPool(MAX_THREADS)

    fun busyMessage(operation: String, max: Int): String =
        "$operation could not start: $max earlier file operations are still blocked (a mounted folder or " +
            "storage provider is not responding). Try again later, or use the shell for this file."

    fun saturatedMessage(operation: String, max: Int): String =
        "$operation could not start: $max file operations are already running. Try again in a moment."

    /** The work did not finish within the timeout. [message] is agent-facing. */
    class TimedOut(message: String) : Exception(message)

    /** The work ran out of memory. [message] is agent-facing. */
    class OutOfMemory(message: String) : Exception(message)

    fun timedOutMessage(operation: String, timeoutMs: Long, writes: Boolean): String =
        "$operation did not finish within ${timeoutMs / 1000}s (the file may be on a mounted folder " +
            "or storage provider that is not responding)." +
            if (writes) " The write may still complete; re-read the file before retrying." else ""

    /**
     * Run [work] off the caller's thread and wait at most [timeoutMs].
     *
     * Throws [TimedOut] on timeout (the worker is interrupted, but a read
     * blocked in the kernel keeps its pool thread until it returns) and
     * [OutOfMemory] when the work throws OutOfMemoryError. Any other exception
     * from [work] is rethrown as is.
     */
    fun <T> run(
        operation: String,
        writes: Boolean = false,
        timeoutMs: Long = IO_TIMEOUT_MS,
        work: () -> T,
    ): T = runOn(pool, MAX_THREADS, operation, writes, timeoutMs, work)

    internal fun <T> runOn(
        executor: ExecutorService,
        max: Int,
        operation: String,
        writes: Boolean,
        timeoutMs: Long,
        work: () -> T,
    ): T {
        val stuck = abandoned(executor)
        // 0 running, 1 finished, 2 abandoned by its caller (counted in [stuck]
        // until the work finally returns).
        val state = AtomicInteger(0)
        val task = java.util.concurrent.Callable<T> {
            try {
                work()
            } finally {
                if (!state.compareAndSet(0, 1)) stuck.decrementAndGet()
            }
        }
        fun abandon() {
            if (state.compareAndSet(0, 2)) stuck.incrementAndGet()
        }
        val future = submitWaiting(executor, max, stuck, operation, minOf(timeoutMs, SLOT_WAIT_MS), task)
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            abandon()
            AppLogger.warning("FileToolIO", "$operation timed out after ${timeoutMs}ms")
            throw TimedOut(timedOutMessage(operation, timeoutMs, writes))
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            if (cause is OutOfMemoryError) {
                AppLogger.warning("FileToolIO", "$operation ran out of memory")
                throw OutOfMemory("$operation ran out of memory.")
            }
            throw cause
        } catch (e: InterruptedException) {
            future.cancel(true)
            abandon()
            Thread.currentThread().interrupt()
            throw e
        }
    }

    /** Submit, waiting up to [waitMs] for a thread unless the pool is held by hung work. */
    private fun <T> submitWaiting(
        executor: ExecutorService,
        max: Int,
        stuck: AtomicInteger,
        operation: String,
        waitMs: Long,
        task: java.util.concurrent.Callable<T>,
    ): java.util.concurrent.Future<T> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
        while (true) {
            try {
                return executor.submit(task)
            } catch (e: RejectedExecutionException) {
                val hung = stuck.get()
                if (poolHeldByHungWork(hung, max)) {
                    AppLogger.warning("FileToolIO", "[GH453] $operation refused: $max pool threads busy, $hung held by hung calls")
                    throw TimedOut(busyMessage(operation, max))
                }
                if (System.nanoTime() >= deadline) {
                    AppLogger.warning("FileToolIO", "[GH453] $operation refused: $max pool threads busy with live work for ${waitMs}ms")
                    throw TimedOut(saturatedMessage(operation, max))
                }
                Thread.sleep(50)
            }
        }
    }

    /** What [scanLines] saw. */
    class LineWindow(
        /** Lines in the file, counted the way File.readLines splits them. */
        val totalLines: Int,
        /** How many lines the selection covers (selectedLines.size before). */
        val selectedCount: Int,
        /**
         * The selected lines joined with '\n' - in full when that fits, else a
         * prefix (head) or suffix (tail) that still holds more than
         * maxLength chars, so truncating it gives exactly what truncating the
         * full join would.
         */
        val content: String,
    )

    /**
     * Stream [file] once, splitting lines like File.readLines (\n, \r\n or a
     * lone \r; a trailing terminator does not start an empty line), and return
     * the selected lines joined with '\n'.
     *
     * - head: lines [offset-1, offset-1+lines) (to the end when [lines] is null);
     * - tail: the last [lines] lines (all lines when null).
     *
     * Only about 4 * (maxLength + 1) bytes of selected content are kept: every
     * UTF-8 byte sequence of at most 4 bytes decodes to at least one char, so
     * that many bytes always decode to more than maxLength chars. Line
     * terminator bytes (0x0A / 0x0D) never occur inside a multi-byte UTF-8
     * sequence, so lines are split on raw bytes and only the kept window is
     * decoded.
     */
    fun scanLines(file: File, tail: Boolean, offset: Int, lines: Int?, maxLength: Int): LineWindow =
        FileInputStream(file).use { scanLines(it, tail, offset, lines, maxLength) }

    fun scanLines(input: InputStream, tail: Boolean, offset: Int, lines: Int?, maxLength: Int): LineWindow {
        val cap = 4L * (maxLength.toLong().coerceAtLeast(0) + 1)
        return if (tail) scanTail(input, lines, cap) else scanHead(input, offset, lines, cap)
    }

    private inline fun forEachByte(input: InputStream, onByte: (Byte) -> Unit) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            for (i in 0 until n) onByte(buf[i])
        }
    }

    private const val LF: Byte = 0x0A
    private const val CR: Byte = 0x0D

    private fun scanHead(input: InputStream, offset: Int, lines: Int?, cap: Long): LineWindow {
        val start = (offset.toLong() - 1).coerceAtLeast(0)
        val end = if (lines != null) start + lines else Long.MAX_VALUE
        val window = ByteSink()
        var lineIndex = 0L
        var lineOpen = false      // a byte of the current line was seen
        var lineStarted = false   // the separator for the current selected line was written
        var selected = 0L
        var prevCR = false

        fun beginSelectedLine() {
            if (!lineStarted) {
                lineStarted = true
                if (selected > 0) window.addCapped(LF, cap)
            }
        }
        fun endLine() {
            if (lineIndex in start until end) {
                beginSelectedLine()
                selected++
            }
            lineIndex++
            lineOpen = false
            lineStarted = false
        }

        forEachByte(input) { b ->
            if (prevCR && b == LF) {
                prevCR = false
                return@forEachByte
            }
            prevCR = false
            if (b == LF || b == CR) {
                endLine()
                prevCR = b == CR
                return@forEachByte
            }
            lineOpen = true
            if (lineIndex in start until end && window.size < cap) {
                beginSelectedLine()
                window.addCapped(b, cap)
            }
        }
        if (lineOpen) endLine()
        return LineWindow(lineIndex.toIntClamped(), selected.toIntClamped(), window.decode())
    }

    private fun scanTail(input: InputStream, lines: Int?, cap: Long): LineWindow {
        val keepLines = lines?.toLong() ?: Long.MAX_VALUE
        // The kept tail lines, oldest first, each holding at most its last
        // `cap` bytes. Joined size is sum(sizes) + (count - 1) separators.
        val kept = ArrayDeque<ByteArray>()
        var keptBytes = 0L
        val current = TailLine(cap)
        var totalLines = 0L
        var lineOpen = false
        var prevCR = false

        fun endLine() {
            totalLines++
            lineOpen = false
            if (keepLines <= 0) {
                current.reset()
                return
            }
            val bytes = current.take()
            kept.addLast(bytes)
            keptBytes += bytes.size
            while (kept.size > keepLines) keptBytes -= kept.removeFirst().size
            // Drop whole lines from the front while what stays still holds
            // more than `cap` bytes: takeLast(maxLength) never reaches them.
            while (kept.size > 1) {
                val joinedWithoutFirst = keptBytes - kept.first().size + (kept.size - 2)
                if (joinedWithoutFirst < cap) break
                keptBytes -= kept.removeFirst().size
            }
        }

        forEachByte(input) { b ->
            if (prevCR && b == LF) {
                prevCR = false
                return@forEachByte
            }
            prevCR = false
            if (b == LF || b == CR) {
                endLine()
                prevCR = b == CR
                return@forEachByte
            }
            lineOpen = true
            if (keepLines > 0) current.add(b)
        }
        if (lineOpen) endLine()

        // How many lines the selection covers, independent of what was dropped.
        val selected = minOf(keepLines, totalLines)
        val out = ByteSink()
        kept.forEachIndexed { i, bytes ->
            if (i > 0) out.add(LF)
            out.addAll(bytes)
        }
        return LineWindow(totalLines.toIntClamped(), selected.toIntClamped(), out.decode())
    }

    private fun Long.toIntClamped(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** Growable byte buffer. */
    private class ByteSink {
        private var buf = ByteArray(256)
        var size = 0L
            private set

        fun add(b: Byte) {
            if (size.toInt() == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[size.toInt()] = b
            size++
        }

        fun addCapped(b: Byte, cap: Long) {
            if (size < cap) add(b)
        }

        fun addAll(bytes: ByteArray) {
            for (b in bytes) add(b)
        }

        fun decode(): String = String(buf, 0, size.toInt(), Charsets.UTF_8)
    }

    /** The current line's bytes, keeping only the last `cap` of them. */
    private class TailLine(private val cap: Long) {
        private var buf = ByteArray(256)
        private var len = 0

        fun add(b: Byte) {
            if (len == buf.size) {
                if (len >= 2 * cap) {
                    // Keep the last `cap` bytes; amortised O(1) per byte.
                    val keep = cap.toInt()
                    System.arraycopy(buf, len - keep, buf, 0, keep)
                    len = keep
                } else {
                    buf = buf.copyOf(minOf(buf.size.toLong() * 2, 2 * cap).toInt().coerceAtLeast(buf.size + 1))
                }
            }
            buf[len++] = b
        }

        fun take(): ByteArray {
            val from = (len - cap).coerceAtLeast(0).toInt()
            val out = buf.copyOfRange(from, len)
            reset()
            return out
        }

        fun reset() {
            len = 0
            if (buf.size > 4096) buf = ByteArray(256)
        }
    }
}
