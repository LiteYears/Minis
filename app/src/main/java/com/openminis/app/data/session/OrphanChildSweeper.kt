package com.openminis.app.data.session

import android.content.Context
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.SessionBadgeStore
import com.openminis.app.ui.chat.ChatViewModelStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-android-zombie-child-sweep] Cold-start cleanup of hidden sub agent
 * sessions whose parent no longer exists.
 *
 * A sub agent runs in a child session that is deliberately invisible: every
 * picker filters rows whose `parent_session_id` is set. That is fine while the
 * parent is around — deleting the parent cascades through SessionDeleter. It
 * is not fine when the process is killed mid-run, or when a parent disappears
 * by some path that did not cascade: the child then sits in the table forever,
 * holding its messages and its workspace, with no user-reachable way to remove
 * it. They accumulate.
 *
 * This deletes user data, so the predicate is deliberately narrow and each
 * deletion is justified in the log rather than merely counted.
 */
object OrphanChildSweeper {
    private const val TAG = "OrphanSweep"

    /**
     * Rows touched more recently than this are never candidates.
     *
     * Two things hide behind that. The obvious one is a run in flight. The
     * subtler one is a backup restore: BackupImporter streams sessions row by
     * row rather than in one transaction, so there is a window where a child is
     * present and its parent is not yet — and because the importer preserves
     * the backup's own `updatedAt`, such a child can already be older than any
     * plausible grace period. The age floor does NOT cover that case; the
     * re-check in [sweep] does. The floor's job is the ordinary one of not
     * racing live writes.
     */
    private const val GRACE_MS = 24L * 60 * 60 * 1000

    data class Report(val examined: Int, val deleted: Int, val spared: Int, val bytesFreed: Long)

    /**
     * Runs once at cold start. Safe to call when there is nothing to do — it
     * logs a baseline either way, so "the sweeper ran and found nothing" is
     * distinguishable in a log from "the sweeper never ran".
     */
    suspend fun sweep(context: Context, repo: ChatRepository): Report {
        val cutoff = System.currentTimeMillis() - GRACE_MS
        val totalChildren = runCatching { repo.dao.childSessionCount() }.getOrDefault(-1)
        val candidates = runCatching { repo.dao.orphanChildSessionIds(cutoff) }.getOrElse {
            AppLogger.warning(TAG, "sweep aborted — candidate query failed: ${it.message}")
            return Report(0, 0, 0, 0)
        }
        AppLogger.info(
            TAG,
            "sweep start: childSessions=$totalChildren candidates=${candidates.size} graceHours=${GRACE_MS / 3_600_000}",
        )
        if (candidates.isEmpty()) return Report(0, 0, 0, 0)

        var deleted = 0
        var spared = 0
        var bytes = 0L
        for (id in candidates) {
            val row = runCatching { repo.getSession(id) }.getOrNull()
            if (row == null) {
                // Someone else removed it between the query and now.
                spared++
                AppLogger.info(TAG, "SPARED child=${id.take(8)} — row gone before delete")
                continue
            }
            val parentId = row.parentSessionId
            if (parentId.isNullOrEmpty()) {
                spared++
                AppLogger.warning(TAG, "SPARED child=${id.take(8)} — no parent id on re-read")
                continue
            }
            // Re-check by id immediately before deleting. This is what protects
            // an in-flight restore: the parent may have been imported in the
            // moments since the candidate query ran, and a child whose parent
            // now exists is simply not an orphan.
            val parent = runCatching { repo.getSession(parentId) }.getOrNull()
            if (parent != null) {
                spared++
                AppLogger.warning(
                    TAG,
                    "SPARED child=${id.take(8)} — parent ${parentId.take(8)} exists after all",
                )
                continue
            }
            val ageDays = (System.currentTimeMillis() - row.updatedAt) / 86_400_000
            AppLogger.info(
                TAG,
                "DELETE child=${id.take(8)} parent=${parentId.take(8)} (missing) " +
                    "age=${ageDays}d title='${row.title?.take(40).orEmpty()}'",
            )
            // Through the normal funnel: a zombie can itself have children, and
            // only this path also removes the workspace, media, jobs and badges.
            val report = runCatching {
                SessionDeleter.deleteTree(context, repo, id, "orphan-sweep")
            }
            val done = report.getOrNull()
            if (done == null) {
                spared++
                AppLogger.warning(
                    TAG,
                    "SPARED child=${id.take(8)} — delete failed: ${report.exceptionOrNull()?.message}",
                )
            } else {
                deleted += done.deletedIds.size
                bytes += done.bytesFreed
            }
        }
        AppLogger.info(TAG, "sweep done: deleted=$deleted spared=$spared freed=${bytes}B")
        return Report(candidates.size, deleted, spared, bytes)
    }
}

// ─── Merged from OrphanedSessionFiles.kt ──────────────────────────────────────────────────

/**
 * [T-storage-orphan-cleanup] Session folders that no conversation owns, and
 * the rules for removing them. Port of iOS SessionStorageCleanup (20e1825f6).
 *
 * An orphan is a `minis-sessions/<id>/` or `media/<y>/<m>/<d>/<id>/` folder
 * whose session row is gone - left by deletes that removed the row but not
 * all the files (builds before the storage delete fixes, an interrupted
 * delete, a crash mid-delete). The storage list is built from the database,
 * so these were in no row and could not be cleared without clearing data.
 *
 * What a clean-up may remove, and nothing else:
 * - only real directories (not symlinks) whose name is a UUID, at exactly
 *   those two places - nothing else under filesDir is ever listed;
 * - only when no session row has that id (compared without case);
 * - never a session the app is using right now (streaming, open, held by a
 *   scheduled task, processing), row or no row;
 * - never one with anything modified within [MIN_AGE_MS]: a new chat's files
 *   can be written before its row, and for that window the folder looks
 *   exactly like an orphan. Real leftovers are old;
 * - a folder whose dates cannot be read is kept: "do not know" never means
 *   "delete".
 * The folders are listed BEFORE the session ids are read, so a session
 * created in between is in the id set.
 */
class OrphanedSessionFiles(private val storage: SessionStorage) {

    /** Every on-disk folder of one session id. */
    data class Folder(val id: String, val dirs: List<File>, val size: Long, val newestModifiedMs: Long?)

    data class Scan(val removable: List<Folder>, val kept: List<Folder>) {
        val removableBytes: Long get() = removable.sumOf { it.size }
        val keptBytes: Long get() = kept.sumOf { it.size }
        val totalBytes: Long get() = removableBytes + keptBytes
        val isEmpty: Boolean get() = removable.isEmpty() && kept.isEmpty()
    }

    data class Result(val removedCount: Int, val freedBytes: Long, val keptBytes: Long, val failedPaths: List<String>)

    /** Step 1: every UUID-named session folder on disk, grouped by id. */
    fun listFolders(): Map<String, List<File>> {
        val out = LinkedHashMap<String, MutableList<File>>()
        storage.sessionsRoot.listFiles()?.forEach { d ->
            if (isUuid(d.name) && isRealDir(d)) out.getOrPut(d.name) { mutableListOf() }.add(d)
        }
        // media/<yyyy>/<MM>/<dd>/<sessionId>: exactly four levels down.
        storage.mediaRoot.listFiles()?.filter { isRealDir(it) }?.forEach { y ->
            y.listFiles()?.filter { isRealDir(it) }?.forEach { m ->
                m.listFiles()?.filter { isRealDir(it) }?.forEach { day ->
                    day.listFiles()?.forEach { d ->
                        if (isUuid(d.name) && isRealDir(d)) out.getOrPut(d.name) { mutableListOf() }.add(d)
                    }
                }
            }
        }
        return out
    }

    /** Step 2, with the session ids read AFTER [listed]. */
    fun scan(listed: Map<String, List<File>>, knownIds: Set<String>, inUse: Set<String>, nowMs: Long): Scan {
        val known = knownIds.mapTo(HashSet()) { it.lowercase() }
        val busy = inUse.mapTo(HashSet()) { it.lowercase() }
        val removable = ArrayList<Folder>()
        val kept = ArrayList<Folder>()
        for ((id, dirs) in listed) {
            if (id.lowercase() in known || id.lowercase() in busy) continue
            val present = dirs.filter { it.exists() }
            if (present.isEmpty()) continue
            val newest = newestModified(present)
            val folder = Folder(id, present, present.sumOf { SessionStorage.directorySize(it) }, newest)
            if (newest == null || nowMs - newest < MIN_AGE_MS) kept.add(folder) else removable.add(folder)
        }
        return Scan(removable, kept)
    }

    /** Remove [scan]'s removable folders only, through the session delete path. */
    fun delete(scan: Scan): Result {
        var freed = 0L
        var removed = 0
        val failed = ArrayList<String>()
        for (f in scan.removable) {
            val r = storage.deleteFilesDetailed(f.id)
            freed += r.freed
            if (r.isComplete) removed++ else failed += r.failedPaths
        }
        return Result(removed, freed, scan.keptBytes, failed)
    }

    companion object {
        const val MIN_AGE_MS = 60 * 60 * 1000L

        private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        fun isUuid(name: String): Boolean = UUID_RE.matches(name)

        private fun isRealDir(f: File): Boolean = try {
            java.nio.file.Files.isDirectory(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) {
            false
        }

        /** Newest modification time anywhere in [dirs] (symlinks not followed); null if any is unreadable. */
        fun newestModified(dirs: List<File>): Long? {
            var newest = 0L
            for (d in dirs) {
                val seq = d.walkTopDown().onEnter { it == d || !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                for (f in seq) {
                    val t = try {
                        java.nio.file.Files.getLastModifiedTime(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS).toMillis()
                    } catch (_: Exception) {
                        return null
                    }
                    if (t <= 0L) return null
                    if (t > newest) newest = t
                }
            }
            // Nothing could be read at all: unknown, so keep.
            return newest.takeIf { it > 0L }
        }
    }
}

// ─── Merged from SessionDeleter.kt ──────────────────────────────────────────────────

/**
 * [T-android-child-session-delete-storage] THE session-delete funnel.
 *
 * Before this, four entry points (home list single/multi delete, delete-
 * folder-with-sessions, empty-session cleanup on exit, the debug RPC) each
 * did `chatRepository.deleteSession` plus whatever cache clears that call
 * site remembered — and none of them touched the session's files, so every
 * deleted session leaked its `minis-sessions/<id>` workspace and dated media
 * forever. With helper child sessions the leak multiplied: a parent delete
 * dropped the children's DB rows but left their directories, their cached
 * ViewModels and their badges behind.
 *
 * One call now does the whole tree, in the only safe order:
 *   1. resolve self + every descendant (deepest first);
 *   2. per session, deepest first: cancel its running helper job, stop any
 *      live stream and drop its ViewModel, delete messages + row, delete its
 *      files, drop its badge entries;
 *   3. the root last.
 *
 * Lives in `data/session` and takes a Context only for `filesDir` — the
 * repository stays Context-free, and the UI/service singletons it touches
 * (ViewModel store, badge store, headless runner) are process-wide objects,
 * not screen state. Every delete entry point MUST route through here.
 */
object SessionDeleter {
    private const val TAG = "SessionDeleter"

    data class Report(val deletedIds: List<String>, val bytesFreed: Long, val jobsCancelled: Int)

    suspend fun deleteTree(
        context: Context,
        repo: ChatRepository,
        rootId: String,
        reason: String,
    ): Report = withContext(Dispatchers.IO) {
        val storage = SessionStorage(context.applicationContext.filesDir)
        val ids = SessionTree.subtreeDeepestFirst(rootId) { repo.dao.childSessionIds(it) }
        var freed = 0L
        var jobs = 0
        for (id in ids) {
            // 1. Stop work first. A helper still running in a child would keep
            //    writing rows/files into what we are about to delete.
            //    [T-agent-port-round2] Drop `then` first: a cancelled child's
            //    completion must not post an <agent_callback> into a session
            //    that is being deleted — the headless submit would re-create
            //    rows for it (a resurrected parent).
            AgentJobRegistry.jobForSession(id)?.let {
                if (it.isActive) {
                    AgentJobRegistry.setThen(it.id, com.openminis.app.agent.jobs.AgentJobThen.None)
                    AgentJobRegistry.cancel(it.id, "session-deleted:$reason"); jobs++
                }
            }
            for (child in AgentJobRegistry.activeChildren(id)) {
                AgentJobRegistry.setThen(child.id, com.openminis.app.agent.jobs.AgentJobThen.None)
            }
            // [T-sub-agents-queue] Drop the backlog too — a queued task
            // would otherwise start into a session that no longer exists.
            AgentJobRegistry.dropQueuedDelegations(id, "parent-deleted:$reason")
            AgentJobRegistry.cancelAll(id, "parent-deleted:$reason")
            // [T-scheduled-active-session-android] Turn off the scheduled tasks
            // this chat created (iOS cancels them in both delete funnels).
            // Left on, a loop would keep firing into a deleted chat, and its
            // follow-up hold would keep that chat "processing" - and the
            // foreground service up - forever.
            runCatching {
                com.openminis.app.scheduled.ScheduledTaskManager(context.applicationContext)
                    .disableAllCreatedBy(id, "session-deleted:$reason")
            }
            runCatching { HeadlessChatRunner.cancel(context, id) }
            // 2. Drop the live ViewModel (cancels its scope) — on Main, it owns
            //    a ViewModelStore.
            withContext(Dispatchers.Main) { ChatViewModelStore.release(id) }
            HeadlessChatRunner.forget(id)
            // 3. DB rows. deleteSessionOnly is the non-cascading primitive; the
            //    cascade is this loop, which already walked the subtree.
            repo.deleteSessionOnly(id)
            // 4. Files, then badges.
            freed += storage.deleteFiles(id)
            SessionBadgeStore.clear(id)
            // [T-android-browser-cli-own-pool] A pool minis-browser-use made
            // for this session while it had no live view model.
            com.openminis.app.sandbox.offload.BrowserCliPools.release(id)
        }
        AppLogger.info(
            TAG,
            "deleteTree root=${rootId.take(8)} reason=$reason sessions=${ids.size} " +
                "jobsCancelled=$jobs freed=${freed}B",
        )
        Report(ids, freed, jobs)
    }

    /**
     * Clear a session tree's FILES only (storage screen "clear files"): every
     * descendant's workspace and media go too, the DB rows stay.
     */
    suspend fun clearTreeFiles(context: Context, repo: ChatRepository, rootId: String): Long =
        withContext(Dispatchers.IO) {
            val storage = SessionStorage(context.applicationContext.filesDir)
            val ids = SessionTree.subtreeDeepestFirst(rootId) { repo.dao.childSessionIds(it) }
            ids.sumOf { storage.deleteFiles(it) }
        }
}

// ─── Merged from SessionStorage.kt ──────────────────────────────────────────────────

/**
 * [T-android-child-session-delete-storage] The on-disk footprint of a session,
 * in one place. Two roots under `filesDir`:
 *
 *   minis-sessions/<sessionId>/…        workspace, attachments, offloads, browser
 *   media/<yyyy>/<MM>/<dd>/<sessionId>/… dated media (images, audio) — one
 *                                         session can have dirs under many dates
 *
 * Takes `filesDir` as a [File] rather than a Context so it is usable from a
 * plain JVM test with a temp directory. Every caller that sizes or clears a
 * session's files goes through here; the storage screen used to carry its own
 * copies of these walks and the delete paths had none at all.
 */
class SessionStorage(private val filesDir: File) {

    val sessionsRoot: File get() = File(filesDir, SESSIONS_DIR)
    val mediaRoot: File get() = File(filesDir, MEDIA_DIR)

    fun sessionDir(sessionId: String): File = File(sessionsRoot, sessionId)

    /** Every dated media directory belonging to [sessionId]. */
    fun mediaDirs(sessionId: String): List<File> {
        if (!mediaRoot.exists()) return emptyList()
        return mediaRoot.walkTopDown()
            .filter { it.isDirectory && it.name == sessionId && it != mediaRoot }
            .toList()
    }

    fun minisSize(sessionId: String): Long = directorySize(sessionDir(sessionId))

    fun mediaSize(sessionId: String): Long = mediaDirs(sessionId).sumOf { directorySize(it) }

    /**
     * Media bytes per session for [sessionIds], from ONE walk of the media
     * tree (the storage list sizes every session at once; walking per id
     * would be quadratic in files).
     */
    fun mediaSizesBySession(sessionIds: Set<String>): Map<String, Long> {
        if (!mediaRoot.exists()) return emptyMap()
        val sizes = mutableMapOf<String, Long>()
        mediaRoot.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val sid = file.parentFile?.name ?: return@forEach
            if (sid in sessionIds) sizes[sid] = (sizes[sid] ?: 0L) + file.length()
        }
        return sizes
    }

    /**
     * Outcome of a delete: bytes freed, plus whatever could NOT be removed.
     *
     * [T-android-storage-delete-feedback] `deleteRecursively()` returns a
     * boolean that every caller here used to drop on the floor, so a partial
     * failure looked exactly like a success — the user tapped "delete" and
     * nothing happened, with no error. openminis/openminis#375 is the report:
     * Git leaves `.git/objects/pack/` at mode 0555, the walk cannot unlink
     * inside it, and the screen stayed silent about it.
     */
    data class DeleteResult(val freed: Long, val failedPaths: List<String>) {
        val isComplete: Boolean get() = failedPaths.isEmpty()
    }

    /** Delete every file this session owns. Returns bytes freed (best effort). */
    fun deleteFiles(sessionId: String): Long = deleteFilesDetailed(sessionId).freed

    /**
     * As [deleteFiles], but reports what survived so the caller can tell the
     * user instead of failing silently.
     */
    fun deleteFilesDetailed(sessionId: String): DeleteResult {
        var freed = 0L
        val failed = mutableListOf<String>()

        fun wipe(target: File) {
            if (!target.exists()) return
            val size = directorySize(target)
            // Restore write permission first. A directory needs +w for its
            // entries to be unlinkable at all, and Git deliberately drops it
            // on pack dirs. Without this the delete fails on exactly the
            // files #375 is about. Best effort: a chmod that fails just
            // means the delete below reports the path.
            restoreWritable(target)
            if (deleteTreeNoFollow(target) && !target.exists()) {
                freed += size
            } else {
                failed += target.absolutePath
                // Some of it may still have gone; count only what really left.
                freed += size - directorySize(target)
            }
        }

        wipe(sessionDir(sessionId))
        for (m in mediaDirs(sessionId)) wipe(m)
        return DeleteResult(freed, failed)
    }

    /**
     * Give every entry under [root] its owner-write bit back, deepest first.
     *
     * Directories are what actually matter — unlinking a file is governed by
     * its PARENT's permissions, not its own — but read-only regular files are
     * restored too so a later re-scan does not trip over them.
     */
    private fun restoreWritable(root: File) {
        // [T-android-session-delete-nofollow] Never follow symlinks. The old
        // `walkBottomUp()` descended into a symlinked directory, so a link in
        // the workspace (the sandbox can create one; a relative guest link
        // climbing out of /var/minis/workspace resolves on the host into a
        // neighbouring directory) got a directory OUTSIDE this session
        // chmod'ed. walkFileTree without FOLLOW_LINKS reports a link as a
        // plain entry, and File.setWritable on a link would change its
        // target, so links are skipped outright.
        try {
            java.nio.file.Files.walkFileTree(
                root.toPath(),
                object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun preVisitDirectory(
                        dir: java.nio.file.Path,
                        attrs: java.nio.file.attribute.BasicFileAttributes,
                    ): java.nio.file.FileVisitResult {
                        val f = dir.toFile()
                        if (!f.canWrite()) runCatching { f.setWritable(true, true) }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: java.nio.file.Path,
                        attrs: java.nio.file.attribute.BasicFileAttributes,
                    ): java.nio.file.FileVisitResult {
                        if (!attrs.isSymbolicLink) {
                            val f = file.toFile()
                            if (!f.canWrite()) runCatching { f.setWritable(true, true) }
                        }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: java.nio.file.Path,
                        exc: java.io.IOException,
                    ): java.nio.file.FileVisitResult = java.nio.file.FileVisitResult.CONTINUE
                },
            )
        } catch (_: Exception) {
            // A walk failure here is not fatal: the delete below still runs
            // and will report whatever it could not remove.
        }
    }

    /**
     * [T-android-session-delete-nofollow] Delete [root] and everything under
     * it WITHOUT following symlinks: a link is unlinked as a link and its
     * target is left alone.
     *
     * `File.deleteRecursively()` walks with `walkBottomUp()`, which descends
     * into a symlinked directory and deletes the TARGET's contents — so
     * "Clear files" on session A, whose workspace held a link to session B's
     * workspace, wiped B's files. That is also the rule [directorySize]
     * already applies when measuring (a link's target is someone else's
     * bytes), so the freed figure and the deletion now agree.
     *
     * Best effort, like deleteRecursively: keeps going past failures and
     * returns true only when nothing is left.
     */
    private fun deleteTreeNoFollow(root: File): Boolean {
        val rootPath = root.toPath()
        if (!java.nio.file.Files.exists(rootPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true
        try {
            java.nio.file.Files.walkFileTree(
                rootPath,
                object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun visitFile(
                        file: java.nio.file.Path,
                        attrs: java.nio.file.attribute.BasicFileAttributes,
                    ): java.nio.file.FileVisitResult {
                        // Without FOLLOW_LINKS a link (to a file OR a
                        // directory) arrives here; Files.delete removes the
                        // link itself.
                        runCatching { java.nio.file.Files.delete(file) }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: java.nio.file.Path,
                        exc: java.io.IOException,
                    ): java.nio.file.FileVisitResult {
                        runCatching { java.nio.file.Files.delete(file) }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: java.nio.file.Path,
                        exc: java.io.IOException?,
                    ): java.nio.file.FileVisitResult {
                        runCatching { java.nio.file.Files.delete(dir) }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (_: Exception) {
            // Fall through: the existence check below is the verdict.
        }
        return !java.nio.file.Files.exists(rootPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
    }

    fun hasFiles(sessionId: String): Boolean =
        sessionDir(sessionId).exists() || mediaDirs(sessionId).isNotEmpty()

    companion object {
        const val SESSIONS_DIR = "minis-sessions"
        const val MEDIA_DIR = "media"

        /**
         * Bytes really stored under [dir], counting each file once and never
         * leaving the subtree.
         *
         * [T-android-storage-symlink-inflation] This used to be a plain
         * `walkTopDown()` summing `length()` of everything `isFile` said yes
         * to — and every one of those three calls resolves symlinks. So the
         * walk behaved like `du -L`, not `du`:
         *
         *   - a symlink to a FILE was counted as a second full copy of the
         *     target's bytes (the Alpine rootfs ships ~335 of these — busybox
         *     applets — and a user's apk installs add more);
         *   - a symlink to a DIRECTORY was DESCENDED INTO, so the whole target
         *     subtree was counted again, once per link pointing at it. This is
         *     what turns a few GB of real files into tens of GB on the storage
         *     screen: `lib -> usr/lib`, `.venv/lib64 -> lib`, node_modules and
         *     apk layouts all create them, and the inflation multiplies rather
         *     than adds.
         *
         * A user reported "Shell 容器 43.89 GB" while Android's own app-storage
         * page said 9.60 GB of data for the same install. Android counts real
         * blocks; we counted symlink targets repeatedly. That is the whole gap
         * — nothing was double-counted from bind mounts, which a host-side walk
         * cannot even see (PRoot's `-b` exists only inside the PRoot process,
         * and `var/minis/mounts/<name>` on disk is an empty placeholder dir).
         *
         * A symlink contributes nothing here on purpose: its target is either
         * inside the subtree (already counted at its real path) or outside it
         * (someone else's bytes, which deleting this directory would not free).
         * That also makes the number honest for [deleteFiles], which reports it
         * as "bytes freed".
         *
         * Still an apparent-size sum, not `du`'s block count: sparse files read
         * high and small files read low against a 4 KiB block. That difference
         * is small and stable, unlike the symlink multiplier.
         */
        fun directorySize(dir: File): Long {
            if (!dir.exists()) return 0L
            var total = 0L
            // walkTopDown()'s own traversal follows directory symlinks, so the
            // filter below cannot prevent descending into one — the link has to
            // be pruned via onEnter before the walk steps through it.
            dir.walkTopDown()
                .onEnter { !isSymlink(it) }
                .forEach { f ->
                    if (!isSymlink(f) && f.isFile) total += f.length()
                }
            return total
        }

        /**
         * True when [f] is itself a symbolic link.
         *
         * `Files.isSymbolicLink` does not follow the final component, which is
         * exactly the question being asked. It is also cheap — one lstat — and
         * total: a broken link (target deleted) answers true rather than
         * throwing, and an unreadable path answers false and is then simply
         * counted as the zero-length entry it appears to be.
         */
        private fun isSymlink(f: File): Boolean =
            try {
                java.nio.file.Files.isSymbolicLink(f.toPath())
            } catch (_: Exception) {
                // InvalidPathException / SecurityException — treat as a normal
                // entry rather than letting a sizing call take down the screen.
                false
            }
    }
}

// ─── Merged from SessionTree.kt ──────────────────────────────────────────────────

/**
 * [T-android-child-session-delete-storage] Parent/child session tree helpers.
 *
 * Helper (delegate_task) sessions are hidden children of the session that
 * spawned them (`ChatSessionEntity.parentSessionId`). Every operation that
 * treats a session as a unit — delete, storage accounting, cache release —
 * has to cover the whole subtree, not one level: a child can itself have
 * children in a later design, and hardcoding one hop is exactly the bug
 * class this exists to prevent. These are pure functions over an id graph so
 * the walk is unit-testable without Room.
 */
object SessionTree {

    /**
     * Ids of every descendant of [rootId], deepest first, root excluded.
     * Deepest-first is the delete order: a child row must go before its
     * parent so a crash mid-way never leaves an orphan pointing at a
     * missing parent. Cycle-safe (a corrupt parent link cannot loop).
     */
    suspend fun descendantsDeepestFirst(
        rootId: String,
        childrenOf: suspend (String) -> List<String>,
    ): List<String> {
        val seen = mutableSetOf(rootId)
        val order = mutableListOf<String>()
        suspend fun walk(id: String) {
            for (child in childrenOf(id)) {
                if (!seen.add(child)) continue
                walk(child)
                order += child
            }
        }
        walk(rootId)
        return order
    }

    /** [rootId] plus all descendants, deepest first (root last). */
    suspend fun subtreeDeepestFirst(
        rootId: String,
        childrenOf: suspend (String) -> List<String>,
    ): List<String> = descendantsDeepestFirst(rootId, childrenOf) + rootId

    /**
     * Resolve each session to its top-level ancestor given a parent map
     * (`id → parentId?`). A dangling parent (id not in the map) is treated as
     * the root so a child whose parent row was lost still surfaces somewhere
     * rather than vanishing from accounting.
     */
    fun rootOf(id: String, parentOf: Map<String, String?>): String {
        var cur = id
        val seen = mutableSetOf(cur)
        while (true) {
            val p = parentOf[cur] ?: return cur
            if (p !in parentOf || !seen.add(p)) return cur
            cur = p
        }
    }

    /**
     * Aggregate per-session sizes onto their root ancestor. Result contains
     * ONLY roots (sessions with no parent, or an unresolvable one); every
     * size is counted exactly once. This is what the storage screen shows.
     */
    fun aggregateToRoots(
        parentOf: Map<String, String?>,
        sizes: Map<String, Long>,
    ): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        for (id in parentOf.keys) {
            val root = rootOf(id, parentOf)
            out[root] = (out[root] ?: 0L) + (sizes[id] ?: 0L)
        }
        return out
    }

    /** Ids in [parentOf] whose root is [rootId] (root included). */
    fun membersOf(rootId: String, parentOf: Map<String, String?>): List<String> =
        parentOf.keys.filter { rootOf(it, parentOf) == rootId }
}
