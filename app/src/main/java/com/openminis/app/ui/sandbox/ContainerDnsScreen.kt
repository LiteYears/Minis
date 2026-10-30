package com.openminis.app.ui.sandbox

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openminis.app.R
import com.openminis.app.sandbox.ContainerDns
import com.openminis.app.sandbox.ContainerDns.DnsMode
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.sandbox.SessionMounts
import com.openminis.app.ui.components.SettingsRowDivider
import com.openminis.app.ui.components.SettingsSection
import com.openminis.app.ui.settings.SettingsScaffold
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [FEAT-261021-04, iOS c77be4fb6] Settings > Network > Shell Container DNS:
 * the Container Network section of the Rootfs page on a page of its own, so
 * the sandbox's DNS sits with the other network settings. One copy of the
 * DNS logic ([ContainerNetworkSection]); the Rootfs page keeps its section.
 */
@Composable
fun ContainerDnsScreen(onBack: () -> Unit) {
    SettingsScaffold(title = stringResource(R.string.network_container_dns), onBack = onBack) {
        SettingsSection(title = stringResource(R.string.rootfs_network_section)) {
            ContainerNetworkSection()
        }
    }
}

// ─── Merged from FileBrowserDeletePolicy.kt ──────────────────────────────────────────────────

/**
 * [T-filebrowser-delete-symlink-target] What deleting a row in Browse Files may
 * do, decided from the row's path in the Linux (PRoot) view, which is null when
 * the browser is not rooted in it (e.g. inside an external folder). Port of iOS
 * FileBrowserDeletePolicy (add538cd0).
 *
 * The old delete called `File.deleteRecursively()` on anything whose TARGET was
 * a directory. Its `walkBottomUp()` follows symlinks, so deleting a link to a
 * folder deleted the folder's contents, and a link nested anywhere inside a
 * deleted folder did the same to whatever it pointed at. Guest links resolve on
 * the host too: an absolute guest link such as `/sdcard/Download` names a real
 * host folder, and a relative one can climb out of the sandbox.
 */
internal object FileBrowserDeletePolicy {
    enum class Action {
        /** A regular file or folder: delete it, never following links inside it. */
        DELETE_ITEM,

        /** A symlink: delete the link only, never what it points to. */
        DELETE_LINK_ONLY,

        /**
         * A folder PRoot binds over. In a rootfs listing the row is the
         * placeholder the bind needs (PRoot skips a bind whose target is
         * missing), elsewhere it is the storage itself; neither is deleted here.
         */
        REFUSE_MOUNT_POINT,
    }

    /** Binds every PRoot launch adds (PRootKernel `-b` list). */
    private val PSEUDO_FS_MOUNT_POINTS = listOf("/dev", "/proc", "/sys")

    val FIXED_MOUNT_POINTS: Set<String> =
        (PSEUDO_FS_MOUNT_POINTS +
            SessionMounts.GLOBAL_SUBDIRS.map { "/var/minis/$it" } +
            SessionMounts.SESSION_SUBDIRS.map { "/var/minis/$it" }).toSet()

    fun action(linuxPath: String?, isSymlink: Boolean): Action {
        if (linuxPath != null && isMountPoint(linuxPath)) return Action.REFUSE_MOUNT_POINT
        return if (isSymlink) Action.DELETE_LINK_ONLY else Action.DELETE_ITEM
    }

    /** A fixed bind, or an external folder's root: /var/minis/mounts/<name>. */
    fun isMountPoint(linuxPath: String): Boolean {
        val parts = linuxPath.split('/').filter { it.isNotEmpty() }
        if ("/" + parts.joinToString("/") in FIXED_MOUNT_POINTS) return true
        return parts.size == 4 && parts[0] == "var" && parts[1] == "minis" && parts[2] == "mounts"
    }

    /** The row's Linux path: [dirLinuxPath] joined with [name], or null outside the Linux view. */
    fun rowLinuxPath(dirLinuxPath: String?, name: String): String? =
        dirLinuxPath?.let { "${it.trimEnd('/')}/$name" }

    /**
     * Carry out [action] on [file]. Returns false when something could not be
     * removed. Never call it with [Action.REFUSE_MOUNT_POINT].
     */
    fun delete(file: File, action: Action): Boolean {
        require(action != Action.REFUSE_MOUNT_POINT) { "mount points are not deleted" }
        val path = file.toPath()
        if (action == Action.DELETE_LINK_ONLY || Files.isSymbolicLink(path)) {
            // Files.delete does not follow the last component: the link goes.
            Files.deleteIfExists(path)
            return !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        }
        return deleteTreeNoFollow(path)
    }

    /**
     * Delete [root] and everything under it without following symlinks: a link
     * met on the way is unlinked as a link (the rule SessionStorage's session
     * cleanup already applies). Best effort, like deleteRecursively: keeps going
     * past failures and returns true only when nothing is left.
     */
    private fun deleteTreeNoFollow(root: Path): Boolean {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true
        try {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        // Without FOLLOW_LINKS a link to a file or a folder lands here.
                        runCatching { Files.delete(file) }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        runCatching { Files.delete(file) }
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        runCatching { Files.delete(dir) }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (_: Exception) {
            // The existence check below is the verdict.
        }
        return !Files.exists(root, LinkOption.NOFOLLOW_LINKS)
    }
}

// ─── Merged from FileBrowserViewModel.kt ──────────────────────────────────────────────────

/** Mirrors iOS FileSortKey. */
enum class FileSortKey { NAME, MODIFIED, SIZE, KIND }

/**
 * File item model — corresponds to iOS FileItem.
 */
@Immutable
data class FileItem(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val size: Long,
    /** lastModified() in epoch ms, 0 when unavailable. */
    val modifiedMs: Long = 0L,
) {
    val iconRes: String
        get() {
            if (isDirectory) return "folder"
            val ext = file.extension.lowercase()
            return when (ext) {
                "txt", "md", "json", "xml", "yaml", "yml", "conf", "cfg", "ini",
                "log", "csv" -> "text"

                "sh", "bash", "zsh", "fish" -> "terminal"
                "py", "js", "ts", "kt", "java", "c", "cpp", "h", "m", "swift",
                "rs", "go", "rb", "php", "lua", "pl" -> "code"

                "png", "jpg", "jpeg", "gif", "bmp", "svg", "webp", "ico" -> "image"
                "mp3", "wav", "aac", "flac", "ogg", "m4a" -> "audio"
                "mp4", "mov", "avi", "mkv", "webm" -> "video"
                "zip", "tar", "gz", "bz2", "xz", "7z", "rar" -> "archive"
                "pdf" -> "pdf"
                "apk", "deb", "rpm" -> "package"
                "db", "sqlite", "sqlite3" -> "database"
                "so", "dylib", "a" -> "library"
                else -> "file"
            }
        }

    val formattedSize: String
        get() = Formatter.formatFileSize(null, size)

    /** Whether this file can be previewed inline (text, image, etc.) */
    val isPreviewable: Boolean
        get() = isTextFile || isImageFile

    val isTextFile: Boolean
        get() {
            val ext = file.extension.lowercase()
            return ext in setOf(
                "txt", "md", "json", "xml", "yaml", "yml", "conf", "cfg", "ini",
                "log", "csv", "sh", "bash", "zsh", "fish",
                "py", "js", "ts", "kt", "java", "c", "cpp", "h", "m", "swift",
                "rs", "go", "rb", "php", "lua", "pl", "html", "css", "scss",
                "toml", "env", "gitignore", "dockerfile", "makefile",
            ) || ext.isEmpty() // extensionless files treated as text
        }

    val isImageFile: Boolean
        get() {
            val ext = file.extension.lowercase()
            return ext in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "ico")
        }

    val isMarkdownFile: Boolean
        get() = file.extension.lowercase() in setOf("md", "markdown", "mdown", "mkd")

    val isHtmlFile: Boolean
        get() = file.extension.lowercase() in setOf("html", "htm", "xhtml")

    val isAudioFile: Boolean
        get() = file.extension.lowercase() in setOf("mp3", "wav", "aac", "flac", "ogg", "m4a")

    val isVideoFile: Boolean
        get() = file.extension.lowercase() in setOf("mp4", "mov", "avi", "mkv", "webm")

    val isPdfFile: Boolean
        get() = file.extension.lowercase() == "pdf"

    // T144 — file-type flags driving FilePreviewScreen renderers.
    val isCsvFile: Boolean
        get() = file.extension.lowercase() in setOf("csv", "tsv")

    val isJsonFile: Boolean
        get() = file.extension.lowercase() == "json"

    /**
     * [T-android-apk-preview] An APK is technically a ZIP, but listing its 282
     * internal entries answers a question nobody asked. What someone who taps
     * an APK wants is the package it represents — and a way to install or
     * hand it to another app. Checked BEFORE [isArchiveFile] at the call site.
     */
    val isApkFile: Boolean
        get() = file.extension.lowercase() in setOf("apk", "apks", "xapk")

    val isArchiveFile: Boolean
        get() = file.extension.lowercase() in setOf("zip", "jar", "apk", "aar")

    val isOfficeFile: Boolean
        get() = file.extension.lowercase() in setOf(
            "xlsx", "xls", "docx", "doc", "pptx", "ppt", "odt", "ods", "odp",
        )

    /** Mirrors iOS FileItem.formattedDate: today=time, yesterday="Yesterday", <7d=weekday, else short date. */
    val formattedDate: String
        get() {
            if (modifiedMs <= 0L) return ""
            val date = Date(modifiedMs)
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { time = date }
            val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
            if (sameDay) return timeFormatter.format(date)
            val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            val isYesterday = yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
            if (isYesterday) return "Yesterday"
            val daysAgo = (now.timeInMillis - then.timeInMillis) / (24L * 60 * 60 * 1000)
            if (daysAgo in 0..6) return weekdayFormatter.format(date)
            return shortDateFormatter.format(date)
        }

    companion object {
        private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
        private val weekdayFormatter = SimpleDateFormat("EEEE", Locale.getDefault())
        private val shortDateFormatter = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

        fun from(file: File): FileItem? {
            if (!file.exists()) return null
            val isSymlink = Files.isSymbolicLink(file.toPath())
            val resolved = if (isSymlink) {
                try {
                    Files.readSymbolicLink(file.toPath()).toFile().let { target ->
                        if (target.isAbsolute) target else File(file.parentFile, target.path)
                    }
                } catch (_: Exception) {
                    file
                }
            } else file

            return FileItem(
                file = file,
                name = file.name,
                isDirectory = resolved.isDirectory,
                isSymlink = isSymlink,
                size = if (resolved.isDirectory) 0L else resolved.length(),
                modifiedMs = resolved.lastModified(),
            )
        }
    }
}

data class FileBrowserUiState(
    val items: List<FileItem> = emptyList(),
    val pathComponents: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val isEmpty: Boolean = false,
    val canGoBack: Boolean = false,
    val currentPath: String = "",
    val errorMessage: String? = null,
    val sortKey: FileSortKey = FileSortKey.NAME,
    val sortAscending: Boolean = true,
    val foldersFirst: Boolean = true,
    /**
     * Show files whose name starts with `.` (Unix dotfiles). Off by
     * default so the listing matches typical `ls` behaviour. Persisted
     * across app launches via SharedPreferences (see
     * [FileBrowserViewModel.PREFS_NAME]). T-hidden-files a3e7f1d0.
     */
    val showHidden: Boolean = false,
    /**
     * [T-android-file-context-copy-abs-path] Linux-side (PRoot) absolute path
     * of the directory currently shown, e.g. "/var/minis/workspace/foo". Null
     * when this browser isn't rooted under a Linux bind mount (a raw host-path
     * browser). The file context menu's "Copy Absolute Path" joins this with
     * the item name so the user copies the path the agent / shell sees
     * (/var/minis/…), not the opaque Android host path.
     */
    val currentLinuxPath: String? = null,
    /**
     * [T-filebrowser-delete-symlink-target] Name of a row whose delete was
     * refused because PRoot binds a folder there; the screen explains it.
     */
    val refusedMountPointName: String? = null,
)

class FileBrowserViewModel(
    private val rootPath: File,
    initialPath: File? = null,
    private val rootLabel: String = rootPath.name,
    // linuxRootPath: when set, directory listings route through PRootKernel
    // bind mounts so subdirs like /var/minis/{skills,memory,shared} list their
    // real content (filesDir/minis-global/*) instead of the empty placeholder
    // dirs shipped inside the Alpine rootfs tarball.
    private val linuxRootPath: String? = null,
    // T147: when set together with [appContext], `/var/minis/{attachments,
    // workspace,offloads,browser}` resolves against THIS session's per-session
    // host dir (filesDir/minis-sessions/<sessionId>/<subdir>) instead of
    // PRootKernel's last-writer-wins global bindMounts map. Lets the Chat
    // Files browser show files generated by the agent in this session even
    // when another session was the most recent shell to boot.
    private val sessionId: String? = null,
    private val appContext: android.content.Context? = null,
    // [T-android-copy-abs-path-fullpath] Linux (PRoot) path of [rootPath] used
    // ONLY to compute the "Copy Absolute Path" value, decoupled from
    // [linuxRootPath] (which also re-routes directory listings). The session
    // storage browser roots its host listing directly at the per-session dir
    // (filesDir/minis-sessions/<sid>) — that listing already resolves correctly
    // host-side, so we must NOT route it through the PRoot resolver (which would
    // redirect /var/minis to the global/empty placeholder dir). Instead this
    // prefix just lets the copy menu emit /var/minis/workspace/foo.py instead of
    // the opaque /data/user/0/.../minis-sessions/<sid>/workspace/foo.py host
    // path. When null, [linuxRootPath] (if any) drives the copy path as before.
    private val displayLinuxPrefix: String? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileBrowserUiState())
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    /** Cached unsorted listing so re-sort doesn't need to re-stat the dir. */
    private var rawItems: List<FileItem> = emptyList()

    /** Path relative to [rootPath] (e.g. "skills/skill-creator"); empty = at root. */
    private var relativePath: String = initialPath?.let { init ->
        if (init.absolutePath.startsWith(rootPath.absolutePath)) {
            init.absolutePath.removePrefix(rootPath.absolutePath).trim('/')
        } else ""
    }.orEmpty()

    /**
     * T145: snapshot of [relativePath] at construction time — the
     * directory the screen was opened at. Back navigation stops here
     * (and `goBack()` returns false to signal the caller to pop the
     * screen) instead of crawling further up toward [rootPath]. Mirrors
     * iOS FileBrowserView's behaviour where the initial directory is
     * the effective root for the back-stack of this screen.
     */
    private val initialRelativePath: String = relativePath

    /** Current resolved host-side File (accounts for PRoot bind mounts). */
    private val currentHostPath: File
        get() = resolveCurrentHostPath()

    private fun resolveCurrentHostPath(): File {
        val linuxRoot = linuxRootPath
        if (linuxRoot != null) {
            val linuxPath = if (relativePath.isEmpty()) linuxRoot
                            else "${linuxRoot.trimEnd('/')}/$relativePath"
            // T147: prefer the session-scoped resolver when we know which
            // session's view we're rendering — global bindMounts is
            // last-writer-wins and points at whichever session's PRoot
            // booted most recently.
            val sid = sessionId
            val ctx = appContext
            if (sid != null && ctx != null) {
                com.openminis.app.sandbox.PRootKernel
                    .resolveSessionHostPath(sid, linuxPath, ctx)
                    ?.let { return it }
            }
            com.openminis.app.sandbox.PRootKernel.resolveHostPath(linuxPath)?.let { return it }
        }
        return if (relativePath.isEmpty()) rootPath else File(rootPath, relativePath)
    }

    init {
        // Restore persisted showHidden preference (T-hidden-files
        // a3e7f1d0). Persisted lazily via SharedPreferences when the user
        // toggles the menu item; if [appContext] is null (e.g. test
        // harness) we fall back to the default false.
        val persisted = appContext
            ?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            ?.getBoolean(PREF_KEY_SHOW_HIDDEN, false)
            ?: false
        if (persisted) {
            _uiState.value = _uiState.value.copy(showHidden = true)
        }
        updatePathComponents()
        loadItems()
    }

    /**
     * Toggle the "show hidden files" preference and re-load the current
     * directory listing so newly-included / newly-excluded dotfiles
     * surface immediately. Persisted via SharedPreferences so the choice
     * sticks across app restarts. T-hidden-files a3e7f1d0.
     */
    fun setShowHidden(value: Boolean) {
        if (_uiState.value.showHidden == value) return
        _uiState.value = _uiState.value.copy(showHidden = value)
        appContext
            ?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(PREF_KEY_SHOW_HIDDEN, value)
            ?.apply()
        loadItems()
    }

    fun navigateTo(item: FileItem) {
        if (!item.isDirectory) return
        relativePath = if (relativePath.isEmpty()) item.name else "$relativePath/${item.name}"
        updatePathComponents()
        loadItems()
    }

    /**
     * T145: pop one directory level. Returns `true` when the navigation
     * was consumed in-place (the screen stays open), `false` when we're
     * already at the initial entry directory — caller should treat this
     * as "exit the screen" (popBackStack).
     *
     * Without the floor at [initialRelativePath] the user would crawl
     * up past the directory they entered (e.g. into `/var/`, `/`),
     * which makes Chat Files feel like a generic file manager rather
     * than a session-scoped browser.
     */
    fun goBack(): Boolean {
        if (relativePath == initialRelativePath) return false
        // We're somewhere below the initial dir. Pop one segment, but
        // don't pop past the initial dir even if the user navigated
        // sideways (e.g. via breadcrumb tap to a deeper sibling) —
        // clamp the resulting path to start with initialRelativePath.
        val parent = relativePath.substringBeforeLast('/', "")
        val initial = initialRelativePath
        relativePath = when {
            initial.isEmpty() -> parent
            parent.isEmpty() -> initial
            parent == initial || parent.startsWith("$initial/") -> parent
            else -> initial
        }
        updatePathComponents()
        loadItems()
        return true
    }

    fun navigateToPathComponent(index: Int) {
        val components = _uiState.value.pathComponents
        val candidate = if (index <= 0) "" else {
            components.drop(1).take(index).joinToString("/")
        }
        // T145: clamp breadcrumb taps to the initial entry directory
        // so the user can't escape "above" Chat Files via the path bar
        // either. Anything that would resolve above the entry dir snaps
        // back to it.
        val initial = initialRelativePath
        relativePath = when {
            initial.isEmpty() -> candidate
            candidate == initial || candidate.startsWith("$initial/") -> candidate
            else -> initial
        }
        updatePathComponents()
        loadItems()
    }

    fun deleteItem(item: FileItem) {
        // [T-filebrowser-delete-symlink-target] A link to a folder used to be
        // deleteRecursively()'d, which follows links and so deleted the
        // folder's contents. A link now loses only the link, a folder never
        // follows the links inside it, and PRoot's bind targets are refused.
        val action = FileBrowserDeletePolicy.action(
            linuxPath = FileBrowserDeletePolicy.rowLinuxPath(_uiState.value.currentLinuxPath, item.name),
            isSymlink = Files.isSymbolicLink(item.file.toPath()),
        )
        if (action == FileBrowserDeletePolicy.Action.REFUSE_MOUNT_POINT) {
            _uiState.value = _uiState.value.copy(refusedMountPointName = item.name)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                FileBrowserDeletePolicy.delete(item.file, action)
                loadItems()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = e.message)
            }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null, refusedMountPointName = null)
    }

    fun setSort(
        key: FileSortKey = _uiState.value.sortKey,
        ascending: Boolean = _uiState.value.sortAscending,
        foldersFirst: Boolean = _uiState.value.foldersFirst,
    ) {
        val newState = _uiState.value.copy(
            sortKey = key,
            sortAscending = ascending,
            foldersFirst = foldersFirst,
        )
        val sorted = applySort(rawItems, newState)
        _uiState.value = newState.copy(items = sorted, isEmpty = sorted.isEmpty())
    }

    private fun applySort(
        items: List<FileItem>,
        state: FileBrowserUiState,
    ): List<FileItem> {
        val nameAsc = Comparator<FileItem> { a, b ->
            a.name.compareTo(b.name, ignoreCase = true)
        }
        val keyComparator: Comparator<FileItem> = when (state.sortKey) {
            FileSortKey.NAME -> nameAsc
            FileSortKey.MODIFIED -> Comparator { a, b ->
                val cmp = a.modifiedMs.compareTo(b.modifiedMs)
                if (cmp != 0) cmp else nameAsc.compare(a, b)
            }
            FileSortKey.SIZE -> Comparator { a, b ->
                val cmp = a.size.compareTo(b.size)
                if (cmp != 0) cmp else nameAsc.compare(a, b)
            }
            FileSortKey.KIND -> Comparator { a, b ->
                val ea = if (a.isDirectory) "" else a.file.extension.lowercase()
                val eb = if (b.isDirectory) "" else b.file.extension.lowercase()
                val cmp = ea.compareTo(eb)
                if (cmp != 0) cmp else nameAsc.compare(a, b)
            }
        }
        val directional = if (state.sortAscending) keyComparator else keyComparator.reversed()
        val final: Comparator<FileItem> = if (state.foldersFirst) {
            // Directories first regardless of sort direction.
            Comparator<FileItem> { a, b ->
                when {
                    a.isDirectory && !b.isDirectory -> -1
                    !a.isDirectory && b.isDirectory -> 1
                    else -> 0
                }
            }.then(directional)
        } else {
            directional
        }
        return items.sortedWith(final)
    }

    private fun loadItems() {
        _uiState.value = _uiState.value.copy(isLoading = true)
        val hostPath = currentHostPath
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Resolve symlinks for listing but keep logical path for breadcrumbs
                val resolvedPath = try {
                    hostPath.toPath().toRealPath().toFile()
                } catch (_: Exception) {
                    hostPath
                }

                // Dotfile filter is gated on the user-facing "Show Hidden
                // Files" preference (T-hidden-files a3e7f1d0). Snapshot
                // showHidden here so we don't see a torn read if the
                // setter races with this background load.
                val showHidden = _uiState.value.showHidden
                val files = resolvedPath.listFiles()
                    ?.filter { showHidden || !it.name.startsWith(".") }
                    ?.mapNotNull { FileItem.from(it) }
                    ?: emptyList()

                rawItems = files
                val sorted = applySort(files, _uiState.value)
                _uiState.value = _uiState.value.copy(
                    items = sorted,
                    isLoading = false,
                    isEmpty = sorted.isEmpty(),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    items = emptyList(),
                    isLoading = false,
                    isEmpty = true,
                    errorMessage = e.message,
                )
            }
        }
    }

    private fun updatePathComponents() {
        val components = mutableListOf(rootLabel)
        if (relativePath.isNotEmpty()) {
            components += relativePath.split("/").filter { it.isNotEmpty() }
        }

        _uiState.value = _uiState.value.copy(
            pathComponents = components,
            // T145: only "can go back" when we're below the entry
            // directory; at the initial root the back button should
            // exit the screen instead of trying to ascend into the
            // wrapping rootfs.
            canGoBack = relativePath != initialRelativePath,
            currentPath = currentHostPath.absolutePath,
            // [T-android-file-context-copy-abs-path] Linux dir path for the
            // "Copy Absolute Path" menu item — derived from the VM's own
            // linuxRootPath + relativePath (the same mapping directory
            // listings route through), not host-path string-fiddling.
            // [T-android-copy-abs-path-fullpath] Prefer the dedicated
            // displayLinuxPrefix when supplied (session storage browser), else
            // fall back to linuxRootPath (Chat Files / shared folders).
            currentLinuxPath = (displayLinuxPrefix ?: linuxRootPath)?.let { root ->
                if (relativePath.isEmpty()) root.trimEnd('/')
                else "${root.trimEnd('/')}/$relativePath"
            },
        )
    }

    companion object {
        val dateFormatter = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())

        /** SharedPreferences file for FileBrowser display options. */
        const val PREFS_NAME = "file_browser_prefs"
        /** Key for the "show hidden files" toggle. Persists across app launches. */
        const val PREF_KEY_SHOW_HIDDEN = "file_browser_show_hidden"

        fun formatDate(timestamp: Long): String {
            return if (timestamp > 0) dateFormatter.format(Date(timestamp)) else "—"
        }
    }
}

// ─── Merged from MarkdownPreviewLinks.kt ──────────────────────────────────────────────────

/**
 * [T-md-preview-links] Where a link tapped inside a markdown FILE preview
 * points. FilePreviewScreen is a NavHost destination outside ChatScreen, so
 * the chat's LocalMarkdownUrlClickHandler is null there and every link in a
 * previewed document (relative, minis:// and https alike) did nothing.
 */
sealed class MarkdownPreviewLinkTarget {
    /** Same-document anchor (`#section`) or nothing to open. */
    data object Ignore : MarkdownPreviewLinkTarget()

    /** A path relative to the shown document, resolved on the host. */
    data class Relative(val file: File) : MarkdownPreviewLinkTarget()

    /** Has a scheme or is a guest absolute path: route like a chat link. */
    data class Chat(val url: String) : MarkdownPreviewLinkTarget()
}

object MarkdownPreviewLinks {
    private val schemePrefix = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    fun target(rawUrl: String, document: File): MarkdownPreviewLinkTarget {
        val url = rawUrl.trim()
        if (url.isEmpty() || url.startsWith("#")) return MarkdownPreviewLinkTarget.Ignore
        if (url.startsWith("/") || schemePrefix.containsMatchIn(url)) {
            return MarkdownPreviewLinkTarget.Chat(url)
        }
        val path = url.substringBefore('#').substringBefore('?')
        if (path.isEmpty()) return MarkdownPreviewLinkTarget.Ignore
        // `+` is a literal in a path; URLDecoder would turn it into a space.
        val decoded = runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }
            .getOrDefault(path)
        val folder = document.absoluteFile.parentFile ?: return MarkdownPreviewLinkTarget.Ignore
        return MarkdownPreviewLinkTarget.Relative(File(folder, decoded).normalize())
    }
}

// ─── Merged from ContainerNetworkSection.kt ──────────────────────────────────────────────────

/**
 * [T-container-network-dns] (OpenMinis#396) Settings > Rootfs > Container
 * Network: whether the sandbox's resolv.conf follows the system DNS (Auto,
 * the default) or lists exactly the servers the user enters (Custom).
 *
 * Mode changes apply at once; the server list applies on Save, and only when
 * every entry is a valid IPv4 / IPv6 literal, so a typo can never reach
 * resolv.conf. Custom with no saved server keeps following the system (see
 * ContainerDns.plan) — the footer line always says which one is in effect.
 * Storage is shared with minis-config (`network.dnsMode`,
 * `network.dnsCustomServers`).
 */
@Composable
internal fun ContainerNetworkSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(ContainerDns.mode(context)) }
    var savedServers by remember { mutableStateOf(ContainerDns.rawServers(context)) }
    var draft by remember { mutableStateOf(savedServers) }
    var effective by remember { mutableStateOf(ContainerDns.plan(context)) }

    val parsed = ContainerDns.parse(draft)
    val savedMsg = stringResource(R.string.rootfs_dns_saved)

    /** Persist, rewrite resolv.conf off the main thread, refresh the footer. */
    fun persist(newMode: DnsMode, servers: String, toast: Boolean) {
        ContainerDns.save(context, newMode, servers)
        mode = newMode
        savedServers = ContainerDns.rawServers(context)
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { RootfsManager.getInstance(context).refreshDns() } }
            effective = ContainerDns.plan(context)
            if (toast) Toast.makeText(context, savedMsg, Toast.LENGTH_SHORT).show()
        }
    }

    Column {
        Text(
            stringResource(R.string.rootfs_dns_mode),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        ModeRow(
            label = stringResource(R.string.rootfs_dns_auto),
            description = stringResource(R.string.rootfs_dns_auto_desc),
            selected = mode == DnsMode.AUTO,
            onClick = { if (mode != DnsMode.AUTO) persist(DnsMode.AUTO, savedServers, toast = false) },
        )
        ModeRow(
            label = stringResource(R.string.rootfs_dns_custom),
            description = stringResource(R.string.rootfs_dns_custom_desc),
            selected = mode == DnsMode.CUSTOM,
            onClick = { if (mode != DnsMode.CUSTOM) persist(DnsMode.CUSTOM, savedServers, toast = false) },
        )

        if (mode == DnsMode.CUSTOM) {
            SettingsRowDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.rootfs_dns_servers)) },
                    placeholder = { Text("223.5.5.5\n2400:3200::1", fontFamily = FontFamily.Monospace) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    minLines = 3,
                    isError = !parsed.isValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    supportingText = {
                        val msg = when {
                            !parsed.isValid -> stringResource(R.string.rootfs_dns_invalid, parsed.invalid.joinToString(", "))
                            parsed.servers.isEmpty() -> stringResource(R.string.rootfs_dns_empty)
                            parsed.servers.size > ContainerDns.RESOLVER_MAX_SERVERS ->
                                stringResource(R.string.rootfs_dns_too_many, ContainerDns.RESOLVER_MAX_SERVERS)
                            else -> stringResource(R.string.rootfs_dns_servers_hint)
                        }
                        Text(msg)
                    },
                )
                val normalizedDraft = parsed.servers.joinToString("\n")
                Button(
                    onClick = { persist(DnsMode.CUSTOM, draft, toast = true); draft = normalizedDraft },
                    enabled = parsed.isValid && normalizedDraft != savedServers,
                    modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                ) { Text(stringResource(R.string.rootfs_dns_save)) }
            }
        }

        SettingsRowDivider()
        Text(
            when (val p = effective) {
                is ContainerDns.Plan.Custom -> stringResource(R.string.rootfs_dns_effective_custom, p.servers.joinToString(", "))
                ContainerDns.Plan.System -> stringResource(R.string.rootfs_dns_effective_system)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun ModeRow(label: String, description: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onClick)
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
