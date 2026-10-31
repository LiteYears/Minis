package com.openminis.app.providers

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Exposes `<filesDir>/minis-global/{memory,skills,shared}` to the system
 * Files app as a storage root. Mirrors iOS `FileProviderExtension`
 * (spec_FileMount §1). Runs in the main app process — no IPC / no
 * independent extension target needed on Android.
 *
 * Capabilities match iOS:
 *   - `memory/` and `skills/` are read-only (tools own their content)
 *   - `shared/` accepts new subitems and in-place edits
 *   - root itself is read-only (can't create a new top-level folder)
 */
class MinisDocumentsProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = "com.openminis.minis.documents"
        private const val ROOT_ID = "minis-root"
        private const val ROOT_DOC_ID = ""        // empty = providerRoot
        private val TOP_LEVEL = listOf("memory", "skills", "shared")
        private val READ_ONLY_TOP = setOf("memory", "skills")
        /** Never expose internal metadata that happens to sit in filesDir. */
        private val METADATA_BLACKLIST = setOf(
            "mounted-folders.json",
            "logs",
            ".",
            "..",
        )

        private val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_MIME_TYPES,
        )

        private val DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
        )
    }

    override fun onCreate(): Boolean = true

    private fun providerRoot(): File {
        val ctx = context ?: throw IllegalStateException("Provider has no context")
        return File(ctx.filesDir, "minis-global").apply {
            mkdirs()
            for (top in TOP_LEVEL) {
                File(this, top).mkdirs()
            }
        }
    }

    private fun resolveDoc(documentId: String): File {
        val root = providerRoot()
        val clean = documentId.trim().trim('/')
        if (clean.isEmpty()) return root
        val segments = clean.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw FileNotFoundException("Invalid path: $documentId")
        }
        if (segments.first() !in TOP_LEVEL) {
            throw FileNotFoundException("Unknown top-level directory: $documentId")
        }
        val target = File(root, clean)
        val rootCanonical = root.canonicalPath
        val targetCanonical = target.canonicalPath
        if (targetCanonical != rootCanonical && !targetCanonical.startsWith("$rootCanonical/")) {
            throw FileNotFoundException("Path escapes provider root: $documentId")
        }
        return target
    }

    private fun documentIdFor(file: File): String {
        val rootPath = providerRoot().canonicalPath
        val abs = file.canonicalPath
        if (abs == rootPath) return ROOT_DOC_ID
        if (!abs.startsWith("$rootPath/")) throw FileNotFoundException("Outside provider root: $abs")
        return abs.substring(rootPath.length + 1)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return try {
            val parent = resolveDoc(parentDocumentId).canonicalPath
            val child = resolveDoc(documentId).canonicalPath
            child == parent || child.startsWith("$parent/")
        } catch (_: Throwable) {
            false
        }
    }

    private fun flagsFor(documentId: String, file: File): Int {
        val clean = documentId.trim().trim('/')
        if (clean.isEmpty()) return 0 // root is immutable through SAF
        val topLevel = clean.substringBefore('/')
        val isTop = !clean.contains('/')
        if (topLevel in READ_ONLY_TOP) {
            return if (isTop && file.isDirectory) Document.FLAG_DIR_PREFERS_LAST_MODIFIED else 0
        }
        // `shared/` tree is writable, but the top-level `shared` folder itself
        // cannot be deleted or renamed.
        var flags = 0
        if (file.isDirectory) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        else flags = flags or Document.FLAG_SUPPORTS_WRITE
        if (!isTop) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        return flags
    }

    private fun mimeFor(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    override fun queryRoots(projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_PROJECTION)
        cursor.newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT_ID)
            .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
            .add(Root.COLUMN_TITLE, "Minis")
            .add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
            .add(Root.COLUMN_MIME_TYPES, "*/*")
            .add(Root.COLUMN_ICON, com.openminis.app.R.mipmap.ic_launcher)
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        val file = resolveDoc(documentId)
        if (!file.exists()) {
            throw FileNotFoundException("Document not found: $documentId")
        }
        val displayName = if (documentId.isEmpty()) "Minis" else file.name
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, documentId)
            .add(Document.COLUMN_DISPLAY_NAME, displayName)
            .add(Document.COLUMN_MIME_TYPE, mimeFor(file))
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(Document.COLUMN_FLAGS, flagsFor(documentId, file))
            .add(Document.COLUMN_SIZE, if (file.isFile) file.length() else 0L)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        val cleanParent = parentDocumentId.trim().trim('/')
        val parent = resolveDoc(cleanParent)
        if (!parent.exists() || !parent.isDirectory) {
            throw FileNotFoundException("Directory not found: $parentDocumentId")
        }
        val isRoot = cleanParent.isEmpty()
        val children = parent.listFiles()?.toList() ?: emptyList()
        for (child in children) {
            val name = child.name
            if (name.startsWith(".")) continue
            if (name in METADATA_BLACKLIST) continue
            if (isRoot && name !in TOP_LEVEL) continue
            val docId = if (cleanParent.isEmpty()) name else "$cleanParent/$name"
            cursor.newRow()
                .add(Document.COLUMN_DOCUMENT_ID, docId)
                .add(Document.COLUMN_DISPLAY_NAME, name)
                .add(Document.COLUMN_MIME_TYPE, mimeFor(child))
                .add(Document.COLUMN_LAST_MODIFIED, child.lastModified())
                .add(Document.COLUMN_FLAGS, flagsFor(docId, child))
                .add(Document.COLUMN_SIZE, if (child.isFile) child.length() else 0L)
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val clean = documentId.trim().trim('/')
        val file = resolveDoc(clean)
        if (!file.exists()) {
            throw FileNotFoundException("Document not found: $documentId")
        }
        val parcelMode = ParcelFileDescriptor.parseMode(mode)
        // Reject writes against the read-only subtrees even if the client
        // ignored the flags cursor — belt + suspenders for AI skills.
        if (parcelMode and ParcelFileDescriptor.MODE_WRITE_ONLY != 0 ||
            parcelMode and ParcelFileDescriptor.MODE_READ_WRITE != 0
        ) {
            val top = clean.substringBefore('/')
            if (clean.isEmpty() || top in READ_ONLY_TOP) {
                throw UnsupportedOperationException("$top is read-only")
            }
        }
        return ParcelFileDescriptor.open(file, parcelMode)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val cleanParent = parentDocumentId.trim().trim('/')
        if (cleanParent.isEmpty()) {
            throw UnsupportedOperationException("Root directory is read-only")
        }
        val parent = resolveDoc(cleanParent)
        if (!parent.exists() || !parent.isDirectory) {
            throw FileNotFoundException("Parent directory not found: $parentDocumentId")
        }
        val top = cleanParent.substringBefore('/')
        if (top in READ_ONLY_TOP) throw UnsupportedOperationException("$top is read-only")
        val safeName = sanitizeDisplayName(displayName)
        val created = allocateUniqueTarget(parent, safeName, isDir = mimeType == Document.MIME_TYPE_DIR)
        val ok = if (mimeType == Document.MIME_TYPE_DIR) created.mkdirs() else created.createNewFile()
        if (!ok && !created.exists()) throw FileNotFoundException("Failed to create ${created.name}")
        return documentIdFor(created)
    }

    override fun deleteDocument(documentId: String) {
        val clean = documentId.trim().trim('/')
        if (clean.isEmpty() || !clean.contains('/')) {
            throw UnsupportedOperationException("Top-level directory cannot be deleted")
        }
        val file = resolveDoc(clean)
        val top = clean.substringBefore('/')
        if (top in READ_ONLY_TOP) throw UnsupportedOperationException("$top is read-only")
        if (!file.exists() || !file.deleteRecursively()) throw FileNotFoundException("Failed to delete $documentId")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val clean = documentId.trim().trim('/')
        if (clean.isEmpty() || !clean.contains('/')) {
            throw UnsupportedOperationException("Top-level directory cannot be renamed")
        }
        val file = resolveDoc(clean)
        if (!file.exists()) throw FileNotFoundException("Document not found: $documentId")
        val top = clean.substringBefore('/')
        if (top in READ_ONLY_TOP) throw UnsupportedOperationException("$top is read-only")
        val safe = sanitizeDisplayName(displayName)
        val parentDir = file.parentFile ?: throw FileNotFoundException("No parent directory for $documentId")
        val target = if (safe == file.name) {
            return documentIdFor(file)
        } else {
            allocateUniqueTarget(parentDir, safe, isDir = file.isDirectory)
        }
        if (!file.renameTo(target)) throw FileNotFoundException("Failed to rename $documentId")
        return documentIdFor(target)
    }

    private fun sanitizeDisplayName(raw: String): String {
        val cleaned = raw.replace("/", "_").replace("\\", "_").replace("\u0000", "").trim()
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "_" else cleaned
    }

    private fun allocateUniqueTarget(parent: File, baseName: String, isDir: Boolean): File {
        var candidate = File(parent, baseName)
        if (!candidate.exists()) return candidate
        val dotIdx = if (!isDir) baseName.lastIndexOf('.') else -1
        val stem = if (dotIdx > 0) baseName.substring(0, dotIdx) else baseName
        val ext = if (dotIdx > 0) baseName.substring(dotIdx) else ""
        for (i in 1..999) {
            candidate = File(parent, "$stem ($i)$ext")
            if (!candidate.exists()) return candidate
        }
        return File(parent, "${stem}_${System.currentTimeMillis()}$ext")
    }
}
