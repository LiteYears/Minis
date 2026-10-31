package com.openminis.app.webapp

import android.content.Context
import com.openminis.app.data.db.WebAppShortcutEntity
import com.openminis.app.data.repository.WebAppShortcutRepository
import com.openminis.app.sandbox.PRootKernel
import java.io.File

/**
 * T-pwa-1 (renamed Pwa → WebApp): resolve the stored
 * ([WebAppShortcutEntity.pathScope], [WebAppShortcutEntity.scopeContext],
 * [WebAppShortcutEntity.htmlPath]) triple back to a host File. Returns
 * null if the file no longer exists — caller should surface a "source
 * missing" UI instead of crashing.
 *
 *   - session_attachment → `<filesDir>/sessions/<sessionId>/attachments/<htmlPath>`
 *     (htmlPath relative to the session's attachments dir; absolute paths
 *      under /var/minis/<sub>/ are also accepted via resolveSessionHostPath)
 *   - shared             → resolved via [PRootKernel.resolveHostPath]
 *   - mount              → resolved via [PRootKernel.resolveHostPath]
 *                          (longest-prefix match against bindMounts)
 */
object WebAppPathResolver {

    fun resolve(context: Context, shortcut: WebAppShortcutEntity): File? {
        val file = when (shortcut.pathScope) {
            WebAppShortcutRepository.SCOPE_SESSION_ATTACHMENT -> resolveSession(context, shortcut)
            WebAppShortcutRepository.SCOPE_SHARED -> resolveScopedGlobal(
                rawLinuxPath = shortcut.htmlPath,
                expectedPrefix = "/var/minis/shared",
            )
            WebAppShortcutRepository.SCOPE_MOUNT -> {
                val mountName = shortcut.scopeContext?.takeIf { it.isNotBlank() && !it.contains('/') && it != "." && it != ".." }
                val prefix = if (mountName != null) "/var/minis/mounts/$mountName" else "/var/minis/mounts"
                resolveScopedGlobal(rawLinuxPath = shortcut.htmlPath, expectedPrefix = prefix)
            }
            else -> null
        }
        return file?.takeIf { it.exists() && it.isFile }
    }

    private fun resolveScopedGlobal(rawLinuxPath: String, expectedPrefix: String): File? {
        val clean = rawLinuxPath.trim()
        if (clean != expectedPrefix && !clean.startsWith("$expectedPrefix/")) return null
        val target = PRootKernel.resolveHostPath(clean) ?: return null
        val rootDir = PRootKernel.resolveHostPath(expectedPrefix) ?: return null
        return verifyCanonicalContainment(rootDir, target)
    }

    /**
     * T-pwa-3: reverse-resolve a host file path to a `(pathScope,
     * scopeContext, linuxPath)` triple suitable for
     * [WebAppShortcutRepository.create]. Walks the
     * [PRootKernel.bindMounts] map looking for an entry whose host
     * directory is a prefix of [hostFile], and also checks per-session
     * `minis-sessions/<sessionId>/<subdir>` directories; returns null if
     * none matches (caller should hide the "Add to Home Screen" menu item).
     *
     * Mapping rules:
     *  - `/var/minis/shared` bind  → `pathScope = "shared"`,  `scopeContext = null`
     *  - `/var/minis/mounts/<n>`   → `pathScope = "mount"`,   `scopeContext = "<n>"`
     *  - `minis-sessions/<sid>/<subdir>/...` → `pathScope = "session_attachment"`, `scopeContext = "<sid>"`
     *  - everything else (incl. memory/skills, rootfs) → null
     */
    fun inferScope(hostFile: File): Triple<String, String?, String>? {
        val hostAbs = runCatching { hostFile.canonicalPath }.getOrDefault(hostFile.absolutePath)
        // Longest host-prefix wins, mirroring resolveHostPath's longest-key match.
        val sorted = com.openminis.app.sandbox.PRootKernel
            .bindMounts.entries.sortedByDescending { it.value.length }
        for ((linuxPrefix, hostBase) in sorted) {
            val baseNorm = runCatching { File(hostBase).canonicalPath }.getOrDefault(hostBase).trimEnd('/')
            if (hostAbs == baseNorm || hostAbs.startsWith("$baseNorm/")) {
                val tail = hostAbs.removePrefix(baseNorm).removePrefix("/")
                val linuxPath = if (tail.isEmpty()) linuxPrefix else "$linuxPrefix/$tail"
                return when {
                    linuxPrefix == "/var/minis/shared" ->
                        Triple(WebAppShortcutRepository.SCOPE_SHARED, null, linuxPath)
                    linuxPrefix.startsWith("/var/minis/mounts/") -> {
                        val mountName = linuxPrefix.removePrefix("/var/minis/mounts/")
                            .substringBefore('/')
                        Triple(WebAppShortcutRepository.SCOPE_MOUNT, mountName, linuxPath)
                    }
                    else -> null  // memory/skills/etc — no WebApp support
                }
            }
        }

        // Check per-session directories: .../minis-sessions/<sid>/<subdir>/<rel>
        val minisSessionsMarker = "/minis-sessions/"
        val markerIdx = hostAbs.indexOf(minisSessionsMarker)
        if (markerIdx >= 0) {
            val afterMarker = hostAbs.substring(markerIdx + minisSessionsMarker.length)
            val parts = afterMarker.split('/', limit = 3)
            if (parts.size == 3) {
                val sid = parts[0]
                val subdir = parts[1]
                val rel = parts[2]
                if (sid.isNotBlank() && subdir in setOf("workspace", "attachments", "offloads", "browser") && rel.isNotBlank()) {
                    return Triple(
                        WebAppShortcutRepository.SCOPE_SESSION_ATTACHMENT,
                        sid,
                        "/var/minis/$subdir/$rel",
                    )
                }
            }
        }
        return null
    }

    private fun resolveSession(context: Context, shortcut: WebAppShortcutEntity): File? {
        val sessionId = shortcut.scopeContext
            ?.takeIf { it.isNotBlank() && !it.contains('/') && !it.contains('\\') && it != "." && it != ".." }
            ?: return null

        // Absolute /var/minis/<perSession>/... — go through PRootKernel which
        // knows how to map per-session subdirs to <filesDir>/minis-sessions/<id>/<sub>.
        if (shortcut.htmlPath.startsWith("/var/minis/")) {
            val sessionRoot = File(context.filesDir, "minis-sessions/$sessionId")
            val resolved = PRootKernel.resolveSessionHostPath(sessionId, shortcut.htmlPath, context) ?: return null
            return verifyCanonicalContainment(sessionRoot, resolved)
        }

        // Relative path — check current minis-sessions/<id>/attachments first,
        // then fall back to legacy sessions/<id>/attachments.
        val primaryDir = File(context.filesDir, "minis-sessions/$sessionId/attachments")
        val primaryCandidate = verifyCanonicalContainment(primaryDir, File(primaryDir, shortcut.htmlPath))
        if (primaryCandidate != null && primaryCandidate.exists()) {
            return primaryCandidate
        }
        val legacyDir = File(context.filesDir, "sessions/$sessionId/attachments")
        return verifyCanonicalContainment(legacyDir, File(legacyDir, shortcut.htmlPath))
    }

    private fun verifyCanonicalContainment(rootDir: File, target: File): File? {
        return try {
            val rootCanonical = rootDir.canonicalPath
            val targetCanonical = target.canonicalPath
            if (targetCanonical == rootCanonical || targetCanonical.startsWith("$rootCanonical/")) {
                target
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
