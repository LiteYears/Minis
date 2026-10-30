package com.openminis.app.backup.remote

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import com.openminis.app.logging.AppLogger
import com.openminis.app.util.EncryptedPrefsFactory
import com.openminis.rclone.gomobile.Gomobile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * [T-android-backup-local-folder] Delivers a finished `.minisbak` package to a
 * folder the user picked on this device, through SAF.
 *
 * This is the local-folder peer of [RcloneChunkedUpload]: same job, same
 * contract (throw on failure, report progress), but a plain ContentResolver
 * stream copy instead of an rclone transfer — a destination that is a
 * directory on the phone has no server to talk to.
 *
 * Built on raw [DocumentsContract] rather than `androidx.documentfile`:
 * DocumentFile would be the more convenient API, but it is not a dependency of
 * this module and the rest of the app (PRootKernel, MountedFoldersScreen)
 * already talks to SAF at this level. Adding a library for one class, when the
 * three calls needed are one-liners, is not worth the extra dependency.
 *
 * The write goes to a `.partial` scratch name and is renamed only after the
 * bytes are flushed, for the same reason the rclone path does it: an
 * interrupted write (process death, storage full, an SD card pulled) must not
 * leave a truncated file under a name the restore picker accepts. The restore
 * list matches `.minisbak` exactly, so a leftover `.partial` reads as scratch
 * rather than as a corrupt backup.
 */
class LocalFolderDelivery(private val context: Context) {

    class DeliveryException(message: String) : Exception(message)

    /**
     * Copy [packageFile] into the tree [treeUri] points at.
     *
     * [onProgress] receives bytes-written / total as the copy runs, so a large
     * package on slow storage (an SD card, a USB drive over OTG) still shows
     * movement rather than a frozen status line.
     */
    fun deliver(
        packageFile: File,
        treeUri: String,
        // [T-android-backup-webdav-deadline] Polled per buffer, so Stop ends a
        // copy to a slow folder (a network-backed provider) mid-file.
        isCancelled: () -> Boolean = { false },
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ) {
        if (!packageFile.exists()) throw DeliveryException("Couldn't read the backup file.")

        val tree = runCatching { Uri.parse(treeUri) }.getOrNull()
            ?: throw DeliveryException("That folder is no longer available.")
        // The tree URI addresses the tree; writes need the DOCUMENT uri for
        // the same node. Passing the tree uri to createDocument throws.
        val parentDoc = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
        }.getOrNull() ?: throw DeliveryException("That folder is no longer available.")

        val name = packageFile.name
        val partialName = "$name.$PARTIAL_SUFFIX"

        // Re-delivering the same backup id, or retrying after a failure, must
        // not accumulate "name (1).minisbak" copies: SAF's createDocument
        // de-duplicates names silently rather than overwriting.
        deleteChild(parentDoc, partialName)

        val partial = runCatching {
            DocumentsContract.createDocument(
                context.contentResolver, parentDoc, MIME_TYPE, partialName,
            )
        }.getOrNull() ?: throw DeliveryException(
            // A persisted permission outlives the folder: an SD card removed, a
            // USB drive unplugged, the directory deleted in a file manager. All
            // of them surface here, so say the folder is unreachable rather
            // than leaking a provider-specific exception.
            "Couldn't create a file in that folder — it may have been moved or removed.",
        )

        val total = packageFile.length()
        try {
            context.contentResolver.openOutputStream(partial, "w")?.use { out ->
                packageFile.inputStream().use { input ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    var sent = 0L
                    while (true) {
                        if (isCancelled()) throw DeliveryException("Delivery stopped.")
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        sent += read
                        onProgress?.invoke(sent, total)
                    }
                    // The provider buffers; without this the rename below can
                    // promote a file whose tail is still in flight, producing a
                    // `.minisbak` that fails to unzip.
                    out.flush()
                }
            } ?: throw DeliveryException("Couldn't open that folder for writing.")
        } catch (e: Exception) {
            // Leave nothing half-written behind under any name.
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, partial) }
            throw if (e is DeliveryException) e
            else DeliveryException(e.message ?: "Couldn't write to that folder.")
        }

        // Replacing an existing package of the same name is deliberate: the
        // name carries the backup id, so a same-name file IS this backup being
        // re-delivered. SAF will not rename onto an existing name.
        deleteChild(parentDoc, name)
        val renamed = runCatching {
            DocumentsContract.renameDocument(context.contentResolver, partial, name)
        }.getOrNull()
        if (renamed == null) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, partial) }
            throw DeliveryException("Couldn't finish writing to that folder.")
        }

        AppLogger.info(TAG, "[LocalDest] wrote $name ($total bytes) to $treeUri")
    }

    /**
     * Delete a direct child of [parentDoc] by display name, if it exists.
     *
     * SAF has no "delete by name" call, so the children have to be listed.
     * Failures are swallowed on purpose: this only clears the way for a write
     * that reports its own errors, and a folder we cannot enumerate should
     * surface as "couldn't create the file", not as a delete failure.
     */
    /**
     * [T-android-backup-local-folder-delete] Delete the backup package named
     * [name] from the folder [treeUri], reporting failure.
     *
     * Issue #367: "Delete Record and Files" could never succeed for a local
     * folder. The delete path routed every destination through rclone, and a
     * local folder is a SAF `content://` tree that is never registered as an
     * rclone remote — so the delete silently did nothing while the UI reported
     * success and dropped the history record, orphaning the file on disk.
     *
     * Idempotent by design: a file that is already gone is a success, because
     * the caller's goal (no such package in this folder) is satisfied. Anything
     * that leaves the file in place — an unreadable tree, a refused delete, a
     * revoked permission — throws, so the caller can keep the record and tell
     * the user which destination failed.
     *
     * Deliberately NOT [deleteChild]: that one swallows every error on purpose
     * because it only clears the way for a write that reports its own failures
     * (see its doc). Reusing it here would reintroduce exactly the silent
     * success this fixes.
     *
     * @throws IllegalStateException when the folder cannot be enumerated or the
     *   provider refuses the delete.
     * @throws SecurityException when the persisted tree permission is gone.
     */
    fun delete(treeUri: String, name: String) {
        val tree = Uri.parse(treeUri)
        val parentDoc = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            parentDoc,
            DocumentsContract.getDocumentId(parentDoc),
        )
        // A null cursor means the tree could not be read at all — never treat
        // that as "the file isn't there", or a revoked grant reads as success.
        val cursor = context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null, null, null,
        ) ?: throw IllegalStateException("Could not open the folder to delete '$name'.")

        var target: Uri? = null
        cursor.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) != name) continue
                target = DocumentsContract.buildDocumentUriUsingTree(parentDoc, c.getString(0))
                break
            }
        }
        // Already absent: the postcondition holds, so this is a success.
        val doc = target ?: return
        val deleted = DocumentsContract.deleteDocument(context.contentResolver, doc)
        if (!deleted) throw IllegalStateException("The folder refused to delete '$name'.")
    }

    private fun deleteChild(parentDoc: Uri, displayName: String) {
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                parentDoc,
                DocumentsContract.getDocumentId(parentDoc),
            )
        }.getOrNull() ?: return

        runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) != displayName) continue
                    val doc = DocumentsContract.buildDocumentUriUsingTree(parentDoc, c.getString(0))
                    DocumentsContract.deleteDocument(context.contentResolver, doc)
                    return
                }
            }
        }
    }

    companion object {
        private const val TAG = "Backup"
        private const val PARTIAL_SUFFIX = "partial"

        /**
         * `.minisbak` has no registered MIME type. octet-stream keeps SAF from
         * appending an extension of its own choosing to the name we pass —
         * which is what it does with a type it recognises.
         */
        private const val MIME_TYPE = "application/octet-stream"
    }
}

// ─── Merged from RcloneBackendCatalog.kt ──────────────────────────────────────────────────

/**
 * The remote types offered in "Add Server", and the fields each one needs.
 * Mirrors `src/ios/Agent/Backup/Remote/RcloneBackendCatalog.swift` — same
 * types, same rclone parameter keys, same order, so a user who configures a
 * NAS on one platform recognises the form on the other.
 *
 * rclone accepts dozens of options per backend; asking for all of them would
 * make this unusable. Each entry lists only what is actually required to
 * connect, with everything else left at rclone's defaults — a user adding a
 * NAS should answer four questions, not forty.
 *
 * Adding a type here does NOT add the code for it: the backend must also be
 * imported in `deps/rclone-mobile/backends/backends.go` and the .aar rebuilt,
 * or rclone rejects the config at runtime with "didn't find backend called …".
 */
object RcloneBackendCatalog {

    data class Field(
        /** rclone parameter name. */
        val key: String,
        val label: String,
        val placeholder: String,
        /** Routed to the encrypted store instead of plain prefs. */
        val isSecret: Boolean = false,
        val isOptional: Boolean = false,
        val keyboard: KeyboardKind = KeyboardKind.DEFAULT,
    )

    enum class KeyboardKind { DEFAULT, URL, NUMERIC, EMAIL }

    data class Backend(
        /** rclone backend type — must match a directory under rclone's backend/. */
        val type: String,
        val title: String,
        val subtitle: String,
        val fields: List<Field>,
    )

    /**
     * Whether this backend treats a leading `/` in the destination path as a
     * FILESYSTEM-ABSOLUTE path that must be preserved. Mirrors
     * `RcloneBackendCatalog.usesAbsolutePaths` on iOS.
     *
     * Only SFTP. For sftp, `remote:` resolves to the login user's home and
     * `remote:/etc` is a genuinely different, valid location, so stripping the
     * slash silently rewrites such a path into a home-relative one.
     *
     * Every other backend here is URL/prefix-based, where a leading `/`
     * resolves against the SERVER root and escapes the folder baked into the
     * remote's fs spec — a package written to `http://host/backup-….minisbak`
     * instead of `…/backups/…`.
     */
    fun usesAbsolutePaths(backendType: String): Boolean = backendType == "sftp"

    /** Ordered by how likely someone backing up a phone is to want it. */
    val all: List<Backend> = listOf(
        Backend(
            type = "smb",
            title = "SMB / Windows Share",
            subtitle = "NAS, Windows shared folder, Samba",
            fields = listOf(
                Field("host", "Server", "192.168.1.10"),
                // No "Share" field on purpose. rclone takes the share as a
                // config option, and mirroring that put a flat network name
                // ("backups") in front of a user whose NAS displays a disk path
                // ("/volume1/backups") — they type the path, and SMB reports a
                // missing share as a LOGON failure, so the app blamed their
                // credentials. Connecting share-less instead makes the share
                // the browser's first level, which is what every desktop SMB
                // client does. See the `share` strip in RcloneRemoteStore.
                //
                // Optional credentials: guest shares are a NAS default
                // (Synology/QNAP ship one), and requiring them left Connect
                // permanently greyed out with nothing explaining why.
                Field("user", "Username", "admin", isOptional = true),
                Field("pass", "Password", "", isSecret = true, isOptional = true),
                Field("domain", "Domain", "WORKGROUP", isOptional = true),
            ),
        ),
        Backend(
            type = "webdav",
            title = "WebDAV",
            subtitle = "Nextcloud, ownCloud, Synology, alist",
            fields = listOf(
                Field("url", "URL", "https://example.com/dav", keyboard = KeyboardKind.URL),
                // Optional on purpose: anonymous WebDAV shares are ordinary
                // (a guest NAS folder, alist public dir, a LAN test server).
                // Requiring credentials left Connect permanently greyed out
                // with no way to proceed and nothing explaining why.
                Field("user", "Username", "", isOptional = true),
                Field("pass", "Password", "", isSecret = true, isOptional = true),
            ),
        ),
        Backend(
            type = "sftp",
            title = "SFTP",
            subtitle = "SSH file transfer",
            fields = listOf(
                Field("host", "Server", "example.com"),
                Field("user", "Username", ""),
                Field("pass", "Password", "", isSecret = true),
                Field("port", "Port", "22", isOptional = true, keyboard = KeyboardKind.NUMERIC),
            ),
        ),
        Backend(
            type = "s3",
            title = "S3-Compatible Storage",
            subtitle = "MinIO, Cloudflare R2, Wasabi, Alibaba OSS, Tencent COS…",
            fields = listOf(
                Field("endpoint", "Endpoint", "s3.example.com", keyboard = KeyboardKind.URL),
                Field("access_key_id", "Access Key ID", ""),
                Field("secret_access_key", "Secret Access Key", "", isSecret = true),
                Field("region", "Region", "us-east-1", isOptional = true),
            ),
        ),
        Backend(
            type = "ftp",
            title = "FTP",
            subtitle = "Plain or explicit TLS",
            fields = listOf(
                Field("host", "Server", "ftp.example.com"),
                // Anonymous FTP is the protocol's own convention; leaving these
                // blank means exactly that.
                Field("user", "Username", "", isOptional = true),
                Field("pass", "Password", "", isSecret = true, isOptional = true),
                Field("port", "Port", "21", isOptional = true, keyboard = KeyboardKind.NUMERIC),
            ),
        ),
    )

    fun backend(type: String): Backend? = all.firstOrNull { it.type == type }

    /** The field whose value belongs in the encrypted store, if any. */
    fun secretField(type: String): String? =
        backend(type)?.fields?.firstOrNull { it.isSecret }?.key
}

// ─── Merged from RcloneBridge.kt ──────────────────────────────────────────────────

/**
 * Kotlin access to the bundled rclone library, mirroring
 * `src/ios/Agent/Backup/Remote/RcloneBridge.swift`.
 *
 * Everything rclone can do goes through ONE entry point — a JSON-RPC call:
 *
 *     RcloneBridge.rpc("operations/list", mapOf("fs" to "remote:", "remote" to "dir"))
 *
 * That is rclone's own librclone API, not an interface invented here. The
 * binding comes from `deps/rclone-mobile/gomobile`, built by
 * `deps/build_rclone_android.sh`; backends linked in are decided by
 * `deps/rclone-mobile/backends/backends.go` — the same list the iOS build uses,
 * so a remote configured on one platform behaves identically on the other.
 *
 * Every call blocks on network I/O. Callers must be off the main thread.
 */
object RcloneBridge {

    private const val TAG = "Rclone"

    /** rclone's RPC returns an HTTP-style status; anything but 200 is a failure. */
    class RPCException(val status: Int, payload: String) :
        Exception(extractMessage(status, payload)) {
        companion object {
            // rclone puts a human-readable reason in `error` when it can.
            private fun extractMessage(status: Int, payload: String): String =
                runCatching { JSONObject(payload).optString("error").takeIf { it.isNotEmpty() } }
                    .getOrNull() ?: "rclone RPC failed (status $status)"
        }
    }

    @Volatile
    private var initialised = false

    /** Idempotent. Must run before any RPC; safe to call from anywhere. */
    @Synchronized
    fun initializeIfNeeded() {
        if (initialised) return
        Gomobile.rcloneInitialize()
        initialised = true
        applyGlobalOptions()
        applyNetworkProxy()
        AppLogger.info(TAG, "[Rclone] initialised")
    }

    /**
     * [T-net-android-phase3] Settings > Network, "Backup" scope (iOS
     * `RcloneBridge.applyNetworkProxy`, a1cf633da..b379a4e6f).
     *
     * rclone's global HTTPProxy (fs/fshttp/http.go) is set to the local SOCKS5
     * entry with the backup tag, or cleared when the route is direct or the
     * entry is down. It covers HTTP-based backends (WebDAV, S3). A remote
     * rclone already built keeps its transport until its fs cache entry
     * expires. Never called with "" before a proxy was set, so an
     * unconfigured app sends rclone exactly the options it did before.
     *
     * [FEAT-261021-19, iOS d72a2b261 G2] SFTP and FTP dial through rclone's
     * own dialer, not an http.Transport, so HTTPProxy never reached them. The
     * same entry is now their `socks_proxy` (deps/rclone-mobile/rcloneroute,
     * as `--sftp-socks-proxy` would set it), and a change clears rclone's fs
     * cache so built remotes reconnect on the new route. SMB has no proxy
     * option in rclone and stays direct (owner's decision, 2026-10-21).
     */
    @Synchronized
    fun applyNetworkProxy() {
        val ep = com.openminis.app.network.NetworkRuntime.endpoint(com.openminis.app.network.NetworkScope.BACKUP.tag)
        applySocksProxy(ep?.let { "${it.tag}:${it.password}@${it.host}:${it.port}" } ?: "")
        val proxy = ep?.let { "socks5://${it.tag}:${it.password}@${it.host}:${it.port}" } ?: ""
        if (proxy == lastAppliedProxy) return
        try {
            rpcUnchecked("options/set", mapOf("main" to mapOf("HTTPProxy" to proxy)))
            lastAppliedProxy = proxy
            AppLogger.info(TAG, "[Net][Backup] rclone HTTPProxy ${if (proxy.isEmpty()) "cleared (direct)" else "-> local entry (backup)"}")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "[Net][Backup] couldn't set rclone HTTPProxy: ${e.message}")
        }
    }

    private var lastAppliedProxy = ""

    /**
     * SFTP / FTP `socks_proxy`: "user:pass@host:port", "" = unset (direct).
     * Only calls Go when the value changes, so an unconfigured app never
     * touches rclone's backend options.
     */
    private fun applySocksProxy(socks: String) {
        if (socks == lastAppliedSocks) return
        val reply = runCatching { Gomobile.rcloneSetSocks(socks) }.getOrElse { "{\"ok\":false,\"error\":\"${it.javaClass.simpleName}\"}" }
        if (!reply.startsWith("{\"ok\":true")) {
            AppLogger.warning(TAG, "[Net][Backup] couldn't set rclone SFTP/FTP socks_proxy: $reply")
            return
        }
        lastAppliedSocks = socks
        val snap = com.openminis.app.network.NetworkRuntime.snapshot
        AppLogger.info(TAG, "[Net][Backup] rclone SFTP/FTP socks_proxy " +
            (if (socks.isEmpty()) "unset (direct)" else "-> backup:<password>@127.0.0.1:${snap.port} route=${snap.route(com.openminis.app.network.NetworkScope.BACKUP.tag).raw}") +
            "; fs cache cleared")
    }

    private var lastAppliedSocks = ""

    /** Re-applies the backup route after a Settings > Network change, only if rclone already runs. */
    fun applyNetworkProxyIfInitialised() {
        if (initialised) applyNetworkProxy()
    }

    /** How long to wait for a server to accept a connection. */
    const val CONNECT_TIMEOUT_SECONDS = 20L

    /**
     * [T-android-backup-webdav-deadline] rclone's `Timeout`, which becomes Go's
     * `http.Transport.ResponseHeaderTimeout` (fs/fshttp/http.go).
     *
     * It is NOT a stall timeout, whatever this comment used to say. The timer
     * starts once the request body has been fully WRITTEN and is a flat
     * deadline that never resets while the server works. For a WebDAV `PUT`
     * to a gateway in front of a cloud drive (AList/OpenList), the server
     * re-uploads the whole file to the cloud before it answers, so 45 s meant
     * "must answer within 45 s after receiving everything", and those servers
     * failed EVERY backup even at full speed.
     *
     * Measured on a Pixel 4a against a WebDAV server that stalls 60 s after the
     * body arrives: 45 s failed each attempt 46 s after the body landed ("i/o
     * timeout"), twice, 92 s in all; 300 s succeeds at 60 s. Same finding and
     * value as iOS (ceac9df0b). 300 s is rclone's own default.
     *
     * A per-size value is no fix: the delay follows the BACKEND (a local disk
     * answers at once, a cloud relay takes minutes), not the byte count.
     * What bounds a server that is down is [CONNECT_TIMEOUT_SECONDS]; what
     * ends a genuinely wedged transfer is Stop, which reaches an upload in
     * flight through `job/stop` (RcloneChunkedUpload).
     */
    const val IO_TIMEOUT_SECONDS = 300L

    /**
     * Low-level and high-level retry budgets.
     *
     * Kept at one each: retries multiply every wait above, and rclone's
     * defaults (10 low-level) would turn one slow server into minutes of
     * apparent hang. The uploader adds its own single, backed-off retry.
     */
    const val LOW_LEVEL_RETRIES = 1L
    const val RETRIES = 1L

    /** Nanoseconds, as rclone's int64 Duration options expect. */
    fun durationNanos(seconds: Long): Long = seconds * 1_000_000_000L

    /**
     * The `options/set` payload for the global transfer budget.
     *
     * Split out from [applyGlobalOptions] so the unit tests can assert the
     * exact wire values — the nanosecond conversion is the easiest thing here
     * to get wrong by three orders of magnitude, and a `Timeout` of 45 (ns
     * interpreted as 45 billionths of a second) would fail every transfer
     * instantly rather than obviously.
     */
    fun globalTimeoutOptions(): Map<String, Any> = mapOf(
        "Timeout" to durationNanos(IO_TIMEOUT_SECONDS),
        "ConnectTimeout" to durationNanos(CONNECT_TIMEOUT_SECONDS),
        "LowLevelRetries" to LOW_LEVEL_RETRIES,
        "Retries" to RETRIES,
    )

    /**
     * rclone's defaults are tuned for a desktop batch job, not a phone.
     *
     * `Gomobile.rcloneRPC` is a BLOCKING call into the Go runtime — nothing on
     * the Kotlin side can interrupt it, so whatever rclone decides to wait for,
     * the calling thread waits too. Out of the box that is a 300s IO timeout
     * with 10 low-level retries, i.e. a server that accepts a connection and
     * then stops responding can hold the caller for the better part of an hour.
     * On a user's device that reads as "backup frozen" rather than "backup
     * failed, try again".
     *
     * Mirrors iOS `RcloneBridge.applyGlobalOptions` (a28f8721b).
     */
    fun applyGlobalOptions() {
        runCatching {
            rpcUnchecked("options/set", mapOf("main" to globalTimeoutOptions()))
        }.onSuccess {
            AppLogger.info(
                TAG,
                "[Rclone] timeouts set: connect=${CONNECT_TIMEOUT_SECONDS}s io=${IO_TIMEOUT_SECONDS}s " +
                    "lowLevelRetries=$LOW_LEVEL_RETRIES retries=$RETRIES",
            )
        }.onFailure {
            AppLogger.warning(TAG, "[Rclone] couldn't set timeouts: ${it.message}")
        }
    }

    /**
     * Whether to accept self-signed / untrusted TLS certificates.
     *
     * Applied GLOBALLY rather than per-remote on purpose: of the backends this
     * app offers, only FTP exposes a `no_check_certificate` option — WebDAV and
     * S3 have none, and their verification is governed by rclone's global
     * `InsecureSkipVerify`. A per-remote toggle would therefore be a lie for
     * exactly the backends most likely to need it (a NAS serving HTTPS WebDAV
     * with its own certificate).
     *
     * Mirrors iOS `RcloneBridge.setInsecureTLS` (a28f8721b).
     */
    fun setInsecureTLS(allow: Boolean) {
        runCatching {
            rpcUnchecked("options/set", mapOf("main" to mapOf("InsecureSkipVerify" to allow)))
        }.onSuccess {
            AppLogger.info(
                TAG,
                "[Rclone] TLS certificate verification ${if (allow) "DISABLED" else "enabled"}",
            )
        }.onFailure {
            AppLogger.warning(TAG, "[Rclone] couldn't set TLS option: ${it.message}")
        }
    }

    /**
     * `rpc` without the initialise guard — for calls made DURING
     * initialisation.
     *
     * [initializeIfNeeded] sets `initialised = true` before calling these, so
     * the guard would not actually recurse; going straight to the RPC keeps
     * that ordering from being load-bearing, so reordering the init sequence
     * later cannot silently reintroduce a re-entrant
     * `Gomobile.rcloneInitialize()`.
     */
    private fun rpcUnchecked(method: String, params: Map<String, Any?>): JSONObject {
        val input = JSONObject(params.mapValues { toJsonValue(it.value) }).toString()
        val result = RcloneRpcTracker.track(method) { Gomobile.rcloneRPC(method, input) }
        val output = result.output ?: ""
        if (result.status != 200L) throw RPCException(result.status.toInt(), output)
        return runCatching { JSONObject(output) }.getOrDefault(JSONObject())
    }

    /**
     * One RPC call. [params] is encoded to JSON; the reply is decoded from it.
     *
     * Values may be primitives, maps, or lists — `config/create` needs a nested
     * object for `parameters` and `opt`, so a flat string map isn't enough.
     */
    fun rpc(method: String, params: Map<String, Any?> = emptyMap()): JSONObject {
        initializeIfNeeded()
        val input = JSONObject(params.mapValues { toJsonValue(it.value) }).toString()
        // [T-rclone-rpc-concurrency-diag] Counted, never serialised: a lock here
        // would let a call stalled for IO_TIMEOUT_SECONDS hold back job/stop.
        val result = RcloneRpcTracker.track(method) { Gomobile.rcloneRPC(method, input) }
        val output = result.output ?: ""
        // gobind maps Go's int to a Java long here.
        if (result.status != 200L) throw RPCException(result.status.toInt(), output)
        return runCatching { JSONObject(output) }.getOrDefault(JSONObject())
    }

    private fun toJsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject(value.entries.associate { (k, v) -> k.toString() to toJsonValue(v) })
        is List<*> -> org.json.JSONArray(value.map { toJsonValue(it) })
        else -> value
    }

    /**
     * Smoke test: prove the Go runtime starts and answers on-device.
     *
     * `core/version` needs no config, no network and no credentials, so a
     * successful reply isolates exactly one thing — that the linked-in Go
     * runtime is alive inside this app, next to PRoot.
     */
    fun smokeTest(): String = try {
        val v = rpc("core/version")
        val version = v.optString("version", "?")
        val goVersion = v.optString("goVersion", "?")
        val arch = v.optString("arch", "?")
        AppLogger.info(TAG, "[Rclone] smoke OK version=$version go=$goVersion arch=$arch")
        "rclone $version ($goVersion, $arch)"
    } catch (e: Exception) {
        AppLogger.error(TAG, "[Rclone] smoke FAILED: ${e.message}")
        "FAILED: ${e.message}"
    }

    /** Backends actually compiled in — confirms the trim did what it claims. */
    fun supportedBackends(): List<String> = runCatching {
        val providers = rpc("config/providers").optJSONArray("providers") ?: return emptyList()
        (0 until providers.length())
            .mapNotNull { providers.optJSONObject(it)?.optString("Name") }
            .filter { it.isNotEmpty() }
            .sorted()
    }.getOrDefault(emptyList())
}

// ─── Merged from RcloneBrowser.kt ──────────────────────────────────────────────────

/**
 * [T-android-rclone-browse] Directory browsing + folder creation for the
 * add-destination flow, over librclone's `operations` RPCs. Mirrors the
 * folder-picker half of iOS `RcloneAddServerView`.
 *
 * SFTP absolute paths (iOS fix T-sftp-absolute-path, 4712be53f): browsing is
 * driven by an explicit `remote` PATH string that the caller controls, and the
 * add-server UI offers a free-text absolute-path field seeded from the browser.
 * There is no forced "start at home" — an empty path lists whatever the server
 * resolves as the connection root, and typing `/etc` (or any absolute path)
 * lists it directly on a non-chrooted account, exactly as rclone supports. A
 * chrooted SFTP account still cannot escape its home; that is a server-side
 * boundary, not an app limitation.
 *
 * Every call blocks on network I/O — run off the main thread.
 */
object RcloneBrowser {

    private const val TAG = "Rclone"

    /**
     * One entry in a remote directory listing.
     *
     * [modified] is epoch millis, or null when the backend does not report a
     * usable `ModTime` — several do not for directories (S3 synthesises them,
     * some WebDAV servers omit them entirely). The picker shows nothing rather
     * than a fabricated date in that case.
     */
    data class Entry(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val modified: Long? = null,
    )

    /**
     * Parse an rclone RFC3339 timestamp to epoch millis, tolerating the
     * varying sub-second precision different backends emit.
     */
    fun parseModTime(s: String?): Long? {
        if (s.isNullOrEmpty()) return null
        // rclone reports a zero time for "unknown"; showing 0001-01-01 would be
        // worse than showing nothing.
        if (s.startsWith("0001-01-01")) return null
        for (pattern in TIME_PATTERNS) {
            runCatching {
                val f = java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                return f.parse(s)?.time
            }
        }
        return null
    }

    private val TIME_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
    )

    /**
     * List directories under [path] on [remote]. Files are omitted — the picker
     * only navigates folders. [path] "" means the remote's connection root.
     */
    fun listDirectories(remote: RcloneRemoteStore.Remote, path: String): List<Entry> {
        val resp = RcloneBridge.rpc(
            "operations/list",
            mapOf(
                "fs" to remote.fsSpec,
                "remote" to path,
                // dirsOnly keeps the payload small and the picker folder-only.
                "opt" to mapOf("dirsOnly" to true),
            ),
        )
        val arr = resp.optJSONArray("list") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (!o.optBoolean("IsDir")) return@mapNotNull null
            Entry(
                name = o.optString("Name"),
                path = o.optString("Path"),
                isDir = true,
                modified = parseModTime(o.optString("ModTime")),
            )
        }.sortedBy { it.name.lowercase() }
    }

    /**
     * Create [name] under [parentPath] on [remote]. Returns the new folder's
     * full path. rclone's mkdir is idempotent (no error if it already exists).
     */
    fun createFolder(remote: RcloneRemoteStore.Remote, parentPath: String, name: String): String {
        val clean = name.trim().trim('/')
        require(clean.isNotEmpty()) { "Folder name is empty." }
        val full = if (parentPath.isEmpty()) clean else "${parentPath.trimEnd('/')}/$clean"
        RcloneBridge.rpc("operations/mkdir", mapOf("fs" to remote.fsSpec, "remote" to full))
        AppLogger.info(TAG, "[Rclone] created folder: $full on ${remote.name}")
        return full
    }

    /**
     * Prove a freshly-entered config actually connects, by listing its root.
     * Throws RcloneBridge.RPCException with the server's reason on failure, so
     * the add-server form can surface "auth failed" / "host not found" before
     * the remote is saved.
     */
    fun testConnection(remote: RcloneRemoteStore.Remote) {
        RcloneBridge.rpc(
            "operations/list",
            mapOf("fs" to remote.fsSpec, "remote" to "", "opt" to mapOf("dirsOnly" to true)),
        )
    }
}

// ─── Merged from RcloneRemoteStore.kt ──────────────────────────────────────────────────

/**
 * User-configured rclone remotes (SMB / WebDAV / S3 / …) usable as backup
 * destinations. Mirrors `src/ios/Agent/Backup/Remote/RcloneRemoteStore.swift`.
 *
 * ## Where the credentials live
 *
 * rclone's own config file stores passwords "obscured", which is reversible by
 * design (`rclone reveal` undoes it) — so it is NOT protection. Writing a
 * user's NAS password into a file in the app's data dir would be the same
 * mistake §5.4 calls out for backup packages.
 *
 * So the split matches iOS exactly:
 *   - **EncryptedSharedPreferences** (Keystore-backed) holds the secrets —
 *     Android's equivalent of the iOS Keychain.
 *   - **Plain SharedPreferences** holds everything non-secret: name, backend
 *     type, host, share, user, path.
 *   - rclone's in-memory config is populated per launch via `config/create`,
 *     with its config file pointed at a throwaway path so rclone itself
 *     persists nothing.
 *
 * That keeps exactly one copy of each secret, in the place the platform
 * provides for it, and means deleting a remote actually removes the credential.
 */
class RcloneRemoteStore internal constructor(
    private val context: Context,
    prefsOverride: SharedPreferences?,
    secretsOverride: SharedPreferences?,
) {

    constructor(context: Context) : this(context, null, null)

    /** A configured remote, minus its secret. */
    @Serializable
    data class Remote(
        /** rclone remote name — also the secret-store key. */
        val name: String,
        /** rclone backend type: "smb", "webdav", "s3", "sftp", … */
        val backend: String,
        /** Non-secret backend parameters (host, url, user, share, region…). */
        val params: Map<String, String> = emptyMap(),
        /** Directory inside the remote that backups are written to. */
        val path: String = "",
        val createdAt: Long = 0,
        /**
         * Whether new backups are delivered here.
         *
         * Disabling is NOT deleting: the server stays configured, with its
         * credential, so a user who wants to skip one destination for a while
         * doesn't have to re-enter an address and password to bring it back.
         * Defaults true so remotes stored before this field existed keep working.
         */
        val enabled: Boolean = true,
        /**
         * Accept a self-signed / untrusted TLS certificate for this server.
         *
         * Off by default: turning it on means an attacker who can intercept the
         * connection could impersonate the server, and a backup carries
         * everything the user has. It exists because a home NAS serving HTTPS
         * with its own certificate is completely ordinary, and without this the
         * only alternatives are "cannot use the app" or "downgrade to plain
         * HTTP", the second of which is strictly worse.
         *
         * The default also makes the decode tolerant: remotes stored before
         * this field existed have no key in their JSON and read back as false,
         * so adding it cannot make an existing destination vanish.
         */
        val allowInsecureTLS: Boolean = false,
    ) {
        /** `remote:` as rclone expects it. */
        val fsSpec: String get() = "$name:"

        /**
         * Join [child] under this remote's backup directory. Mirrors
         * `Remote.join` on iOS.
         *
         * Naive `"$path/$child"` breaks when the user picked the SERVER ROOT as
         * the destination: [path] is "" and the join yields "/child" — a
         * LEADING-SLASH path that rclone's WebDAV backend resolves against the
         * server root, ESCAPING the folder baked into the fs URL. Trimming
         * slashes on both sides keeps every produced path fs-relative.
         *
         * SFTP keeps its leading slash: there, `/srv/backup` and `srv/backup`
         * are DIFFERENT locations (absolute vs relative to the login user's
         * home), so trimming it rewrites the destination the user chose.
         */
        fun join(child: String): String {
            if (RcloneBackendCatalog.usesAbsolutePaths(backend)) {
                // `/` is the filesystem ROOT, not an empty path: dropping its
                // trailing slash would leave "" and silently retarget the
                // package at the login user's home.
                if (path == "/") return "/$child"
                val base = path.removeSuffix("/")
                return if (base.isEmpty()) child else "$base/$child"
            }
            val base = path.trim('/')
            return if (base.isEmpty()) child else "$base/$child"
        }

        /** This remote's backup directory as an fs-relative listing target. */
        val listRoot: String
            get() = if (RcloneBackendCatalog.usesAbsolutePaths(backend)) {
                if (path == "/") "/" else path.removeSuffix("/")
            } else {
                path.trim('/')
            }
    }

    class StoreException(message: String) : Exception(message)

    // Overridable so unit tests can run the edit rules against plain prefs:
    // the Keystore behind the encrypted store does not exist under Robolectric.
    private val prefs by lazy {
        prefsOverride ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val secrets by lazy {
        secretsOverride ?: EncryptedPrefsFactory.safeCreate(context, SECRETS_PREFS_NAME)
    }

    var remotes: List<Remote>
        get() = runCatching {
            prefs.getString(KEY_REMOTES, null)?.let { JSON.decodeFromString(REMOTE_LIST, it) }
        }.getOrNull() ?: emptyList()
        private set(value) {
            prefs.edit().putString(KEY_REMOTES, JSON.encodeToString(REMOTE_LIST, value)).apply()
        }

    fun remote(name: String): Remote? = remotes.firstOrNull { it.name == name }

    /** Remotes that new backups should actually be delivered to. */
    val enabledRemotes: List<Remote> get() = remotes.filter { it.enabled }

    // MARK: - Local folder destinations

    /**
     * [T-android-backup-local-folder] Register a SAF-picked folder on this
     * device as a backup destination.
     *
     * Local folders deliberately reuse the SAME [Remote] record and the same
     * saved list as rclone servers, rather than getting a parallel store. The
     * destination list, its enable/disable switch, the "is anything configured"
     * gate on the Start button and the history's per-destination outcomes all
     * key off that one list — a second list would have had to be threaded
     * through every one of them, and any spot that was missed would silently
     * treat a local folder as "no destination configured".
     *
     * The tree URI lives in [Remote.path] because that is the field every
     * caller already treats as "where in the destination the package goes".
     * [BACKEND_LOCAL] is not an rclone backend and is filtered out of
     * [syncToRclone]; delivery for it is a plain DocumentFile write.
     */
    fun addLocalFolder(name: String, treeUri: String, displayPath: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw StoreException("Choose a name for this folder.")
        if (remote(trimmed) != null) {
            throw StoreException("A destination named \"$trimmed\" already exists.")
        }
        if (remotes.any { it.backend == BACKEND_LOCAL && it.params[PARAM_TREE_URI] == treeUri }) {
            throw StoreException("That folder is already a backup destination.")
        }
        remotes = remotes + Remote(
            name = trimmed,
            backend = BACKEND_LOCAL,
            params = mapOf(PARAM_TREE_URI to treeUri),
            // Shown in the row subtitle. The raw tree URI is unreadable
            // ("content://com.android.externalstorage.documents/tree/primary%3ADocuments"),
            // so the human form is stored alongside it rather than re-derived
            // for display on every recomposition.
            path = displayPath,
            createdAt = System.currentTimeMillis(),
        )
        AppLogger.info(TAG, "[Rclone] local folder destination added: $trimmed -> $displayPath")
    }

    // MARK: - Registration

    /** Add a remote. [secret] goes to the encrypted store; everything else to prefs. */
    fun add(
        name: String,
        backend: String,
        params: Map<String, String>,
        secret: String?,
        path: String,
        allowInsecureTLS: Boolean = false,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || !trimmed.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            throw StoreException("Choose a name using letters, numbers, - or _.")
        }
        if (remote(trimmed) != null) {
            throw StoreException("A destination named \"$trimmed\" already exists.")
        }
        if (!secret.isNullOrEmpty()) storeSecret(trimmed, secret)
        remotes = remotes + Remote(
            name = trimmed, backend = backend, params = params,
            path = path, createdAt = System.currentTimeMillis(),
            allowInsecureTLS = allowInsecureTLS,
        )
        AppLogger.info(TAG, "[Rclone] remote added: $trimmed ($backend)")
    }

    /** Remove a remote and its credential. */
    fun remove(name: String) {
        remotes = remotes.filterNot { it.name == name }
        secrets.edit().remove(name).apply()
        AppLogger.info(TAG, "[Rclone] remote removed: $name")
    }

    /** Flip a remote on or off without touching its config or credential. */
    fun setEnabled(name: String, on: Boolean) {
        val all = remotes
        if (all.none { it.name == name }) return
        remotes = all.map { if (it.name == name) it.copy(enabled = on) else it }
        AppLogger.info(TAG, "[Rclone] remote '$name' ${if (on) "enabled" else "disabled"}")
    }

    // MARK: - Editing

    /** What an edit does to the stored secret. */
    sealed interface SecretEdit {
        /** Leave the stored secret as it is — the password field was left blank. */
        data object Keep : SecretEdit
        /** Remove it: the server is switched to anonymous access. */
        data object Clear : SecretEdit
        data class Replace(val value: String) : SecretEdit
    }

    /**
     * [T-android-backup-dest-edit] Change an existing destination in place.
     * Mirrors `RcloneRemoteStore.update` on iOS: rename, re-point the folder,
     * and edit the connection (params, secret, TLS opt-in). Every argument is
     * optional; null means "unchanged".
     *
     * ## Verify before anything is written
     *
     * [verify] receives the edited remote and the secret it would use, and is
     * expected to throw when that configuration cannot connect. It runs BEFORE
     * a single byte is persisted, so a failed test leaves the old params and
     * the old secret exactly as they were. iOS applies first and rolls the
     * params back on failure, which cannot restore the secret (it is not read
     * back) — a wrong new password replaced a working one. Testing the
     * candidate first removes the need for a rollback at all.
     *
     * ## Rename
     *
     * The secret is filed under the remote's name, so a rename moves it — new
     * key written before the old one is dropped, so a failure in between leaves
     * a duplicate credential rather than a remote with none. `enabled`,
     * `createdAt` and everything else ride along unchanged on the copied record.
     *
     * The caller syncs rclone afterwards ([syncToRclone]); the changed inputs
     * give the remote a new fingerprint, so its section is recreated.
     *
     * @return the remote as stored after the edit.
     * @throws StoreException for an unknown remote, an invalid or taken name.
     */
    fun update(
        name: String,
        newName: String? = null,
        newPath: String? = null,
        newParams: Map<String, String>? = null,
        secret: SecretEdit = SecretEdit.Keep,
        allowInsecureTLS: Boolean? = null,
        verify: ((candidate: Remote, secret: String?) -> Unit)? = null,
    ): Remote {
        val all = remotes
        val i = all.indexOfFirst { it.name == name }
        if (i < 0) throw StoreException("That destination no longer exists.")
        val before = all[i]

        val targetName = newName?.trim()?.takeIf { it != name }
        if (targetName != null) {
            validateRename(targetName, local = isLocalFolder(before.backend))
            if (all.any { it.name == targetName }) {
                throw StoreException("A destination named \"$targetName\" already exists.")
            }
        }

        var updated = before
        if (targetName != null) updated = updated.copy(name = targetName)
        if (newPath != null) updated = updated.copy(path = normalizedPath(before.backend, newPath))
        if (newParams != null) updated = updated.copy(params = mergedParams(before.params, newParams))
        if (allowInsecureTLS != null) updated = updated.copy(allowInsecureTLS = allowInsecureTLS)

        val currentSecret = loadSecret(name)
        val nextSecret = resolveSecret(currentSecret, secret)
        verify?.invoke(updated, nextSecret)

        // Verified (or nothing to verify): commit. Secret first, then the
        // record, so the record never names a credential that isn't there yet.
        val edit = secrets.edit()
        if (targetName != null) edit.remove(name)
        if (nextSecret.isNullOrEmpty()) edit.remove(updated.name) else edit.putString(updated.name, nextSecret)
        edit.apply()
        remotes = all.toMutableList().also { it[i] = updated }
        if (targetName != null) synchronized(syncLock) { syncedFingerprints.remove(name) }
        AppLogger.info(TAG, "[Rclone] remote updated: $name -> ${updated.name}:/${updated.path}")
        return updated
    }

    private fun validateRename(newName: String, local: Boolean) {
        if (newName.isEmpty()) throw StoreException("Choose a name for this destination.")
        // A local folder's name never reaches rclone, and the add path derives
        // it from the folder (spaces included), so only servers are held to
        // rclone's remote-name alphabet.
        if (!local && !newName.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            throw StoreException("Choose a name using letters, numbers, - or _.")
        }
    }

    /** Whether a secret is stored for [name]. Never returns the secret itself. */
    fun hasSecret(name: String): Boolean = !loadSecret(name).isNullOrEmpty()

    // MARK: - Secrets

    private fun storeSecret(name: String, secret: String) {
        secrets.edit().putString(name, secret).apply()
    }

    private fun loadSecret(name: String): String? = secrets.getString(name, null)

    /**
     * Which parameter carries the secret, per backend. rclone names these
     * differently and there is no generic "password" key, so the mapping is
     * explicit rather than guessed.
     */
    private fun secretKey(backend: String): String = secretKeyFor(backend)

    // MARK: - Handing config to rclone

    /**
     * Push every configured remote into rclone's in-memory config.
     *
     * Runs per launch, off the main thread. rclone is told to use a config path
     * under cacheDir so it never writes credentials to a file we would then
     * have to protect — the encrypted store stays the only copy.
     *
     * [T-restore-list-sync-race] Every server screen calls this right before
     * it lists, deletes or downloads, and two of them can overlap (a quick
     * second folder tap, a backup run while browsing). rclone's config/create
     * is not atomic per remote: it deletes the section, then sets `type`, then
     * each parameter in turn (fs/config/config.go CreateRemote). A listing that
     * lands in that window sees the remote without its password, and SMB
     * answers "The attempted logon is invalid" for correct credentials - the
     * same race iOS fixed in f465c10b1. So the whole sync runs under one
     * process-wide lock, and a remote whose inputs are unchanged since its
     * last successful create is not recreated at all: once synced, repeated
     * calls never touch a section another thread may be using.
     */
    fun syncToRclone() = synchronized(syncLock) {
        val configPath = File(context.cacheDir, "rclone-ephemeral.conf").absolutePath
        runCatching { RcloneBridge.rpc("config/setpath", mapOf("path" to configPath)) }

        // Global TLS relaxation is all-or-nothing (WebDAV and S3 expose no
        // per-remote option), so it goes on only when a configured server
        // actually asks for it — one NAS with a self-signed certificate must
        // not quietly lower the bar for every other destination the user has.
        RcloneBridge.setInsecureTLS(remotes.any { it.allowInsecureTLS })

        // A local-folder destination has no rclone backend to create — feeding
        // `type = "local-folder"` to config/create would make rclone reject it
        // and log a failure for a destination that is working fine.
        for (r in remotes.filterNot { it.backend == BACKEND_LOCAL }) {
            val params = buildConfigParams(
                backend = r.backend,
                params = r.params,
                secret = loadSecret(r.name),
                allowInsecureTLS = r.allowInsecureTLS,
                obscure = ::obscureViaRclone,
            ).toMutableMap<String, Any?>()
            val fp = configFingerprint(r.backend, r.params, loadSecret(r.name), r.allowInsecureTLS)
            if (syncedFingerprints[r.name] == fp) continue
            syncedFingerprints.remove(r.name)
            runCatching {
                RcloneBridge.rpc(
                    "config/create",
                    mapOf(
                        "name" to r.name,
                        "type" to r.backend,
                        "parameters" to params,
                        // Don't let rclone try to run an interactive OAuth flow.
                        "opt" to mapOf("nonInteractive" to true),
                    ),
                )
            }.onSuccess {
                syncedFingerprints[r.name] = fp
            }.onFailure {
                AppLogger.error(TAG, "[Rclone] config/create failed for '${r.name}': ${it.message}")
            }
        }
        AppLogger.info(TAG, "[Rclone] synced ${remotes.size} remote(s) into rclone config")
    }

    /**
     * [T-android-backup-dest-edit] Prove [candidate] connects, without touching
     * the remote's live rclone section or anything stored. Throws the rclone
     * error when it does not.
     *
     * The candidate is registered under a throwaway section name, listed, and
     * the section deleted again. The name contains a `.`, which [add] and
     * [update] never accept, so it cannot collide with a real destination.
     * Recreating the REAL section instead would have to be undone on failure,
     * and a backup running at the same moment would upload with the untested
     * settings.
     *
     * The global TLS relaxation is widened only for the duration of the probe
     * when the candidate asks for it, then put back to what the stored remotes
     * need — a failed "trust this certificate" attempt must not leave
     * verification off for every other server.
     */
    fun probeConnection(candidate: Remote, secret: String?) {
        val probe = candidate.copy(name = "${candidate.name}$PROBE_SUFFIX")
        try {
            RcloneBridge.setInsecureTLS(candidate.allowInsecureTLS || remotes.any { it.allowInsecureTLS })
            RcloneBridge.rpc(
                "config/create",
                mapOf(
                    "name" to probe.name,
                    "type" to probe.backend,
                    "parameters" to buildConfigParams(
                        backend = probe.backend,
                        params = probe.params,
                        secret = secret,
                        obscure = ::obscureViaRclone,
                        allowInsecureTLS = probe.allowInsecureTLS,
                    ),
                    "opt" to mapOf("nonInteractive" to true),
                ),
            )
            RcloneBrowser.listDirectories(probe, probe.listRoot)
        } finally {
            dropRcloneSection(probe.name)
            RcloneBridge.setInsecureTLS(remotes.any { it.allowInsecureTLS })
        }
    }

    /**
     * Delete one section from rclone's in-memory config. Used for the probe and
     * for the old name after a rename, which would otherwise linger with the
     * credential until the process exits. Best effort: a section that is
     * already gone is the desired end state.
     */
    fun dropRcloneSection(name: String) {
        runCatching { RcloneBridge.rpc("config/delete", mapOf("name" to name)) }
        synchronized(syncLock) { syncedFingerprints.remove(name) }
    }

    /** `core/obscure`, or null when the RPC is unavailable/fails. */
    private fun obscureViaRclone(clear: String): String? = runCatching {
        RcloneBridge.rpc("core/obscure", mapOf("clear" to clear))
            .optString("obscured").takeIf { it.isNotEmpty() }
    }.getOrNull()

    companion object {
        private const val TAG = "Rclone"

        /** [T-restore-list-sync-race] One rclone config writer at a time, process-wide. */
        private val syncLock = Any()

        /**
         * [T-restore-list-sync-race] Remote name -> [configFingerprint] of the
         * inputs of its last successful config/create in this process. rclone's
         * config lives in this process too, so a new process starts empty and
         * creates everything once.
         */
        private val syncedFingerprints = HashMap<String, String>()

        /**
         * Identity of everything a remote's rclone section is built from, taken
         * from the CLEAR inputs: the obscured password differs on every call
         * (random IV), so it cannot tell whether anything changed.
         */
        internal fun configFingerprint(
            backend: String,
            params: Map<String, String>,
            secret: String?,
            allowInsecureTLS: Boolean,
        ): String {
            val canonical = buildString {
                append(backend).append('\u0000')
                for ((k, v) in params.toSortedMap()) append(k).append('=').append(v).append('\u0000')
                append(if (secret == null) "-" else "+" + secret).append('\u0000')
                append(allowInsecureTLS)
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        /**
         * [T-android-backup-local-folder] Reserved [Remote.backend] marking a
         * destination that is a folder on this device, reached through SAF
         * rather than rclone. Hyphenated so it can never collide with a real
         * rclone backend name (those are bare lowercase words like "smb").
         */
        const val BACKEND_LOCAL = "local-folder"

        /** Key under [Remote.params] holding the persisted SAF tree URI. */
        const val PARAM_TREE_URI = "treeUri"

        /** Whether [backend] names a local folder rather than an rclone remote. */
        fun isLocalFolder(backend: String): Boolean = backend == BACKEND_LOCAL

        /**
         * [T-android-backup-dest-edit] Merge an edit form's values into the
         * stored params. Merge rather than replace: the form sends only the
         * fields it shows, and a remote may carry keys with no editor. A field
         * cleared in the form removes its key, so an optional value can be
         * taken away. Values are trimmed — a pasted host with a trailing space
         * is the classic "correct address, cannot connect".
         */
        fun mergedParams(old: Map<String, String>, edits: Map<String, String>): Map<String, String> {
            val out = old.toMutableMap()
            for ((k, v) in edits) {
                val t = v.trim()
                if (t.isEmpty()) out.remove(k) else out[k] = t
            }
            return out
        }

        /** The secret an edit leaves in place: kept, cleared or replaced. */
        fun resolveSecret(current: String?, edit: SecretEdit): String? = when (edit) {
            SecretEdit.Keep -> current
            SecretEdit.Clear -> null
            is SecretEdit.Replace -> edit.value.ifEmpty { current }
        }

        /**
         * Store a re-pointed folder the way the browser produces it. SFTP keeps
         * a leading `/` (absolute vs home-relative are different places, see
         * [Remote.join]); every other backend stores it fs-relative.
         */
        fun normalizedPath(backend: String, path: String): String {
            if (isLocalFolder(backend)) return path
            val trimmed = path.trim()
            if (RcloneBackendCatalog.usesAbsolutePaths(backend)) {
                return if (trimmed.length > 1 && trimmed.endsWith("/")) trimmed.dropLast(1) else trimmed
            }
            return trimmed.trim('/')
        }
        /** See [probeConnection]: `.` is outside the destination-name alphabet. */
        internal const val PROBE_SUFFIX = ".edit-probe"
        private const val PREFS_NAME = "backup_rclone_remotes"
        private const val SECRETS_PREFS_NAME = "backup_rclone_secrets"
        private const val KEY_REMOTES = "remotes"

        /**
         * Which parameter carries the secret, per backend. rclone names these
         * differently and there is no generic "password" key, so the mapping
         * is explicit rather than guessed.
         */
        fun secretKeyFor(backend: String): String = when (backend) {
            "s3" -> "secret_access_key"
            else -> "pass"
        }

        /**
         * Whether this backend's secret is a PASSWORD rclone will de-obscure,
         * as opposed to a key it uses literally.
         *
         * Obscuring is rclone's expected on-the-wire form for password fields
         * and it reveals them again on use — but `secret_access_key` is
         * consumed verbatim. Obscuring it makes the client sign every S3
         * request with the obscured blob, and all of them fail with
         * SignatureDoesNotMatch (iOS 21f08d85f, found against a real MinIO).
         */
        fun secretNeedsObscuring(backend: String): Boolean = backend != "s3"

        /**
         * Build the `config/create` parameters for one remote.
         *
         * Pure so the rules below can be tested without an rclone bridge:
         * [obscure] is the `core/obscure` call, returning null when it fails.
         *
         * This is the ONLY place that decides obscure-vs-verbatim. The
         * add-time connection test builds its config from the plaintext the
         * user just typed, so when the two disagree an S3 destination tests
         * fine at add time and fails on every later list/upload — exactly the
         * bug iOS hit. Both callers go through here.
         */
        fun buildConfigParams(
            backend: String,
            params: Map<String, String>,
            secret: String?,
            obscure: (String) -> String?,
            allowInsecureTLS: Boolean = false,
        ): Map<String, Any?> {
            val out = params.toMutableMap<String, Any?>()
            out["type"] = backend

            // FTP is the one backend here with a real per-remote switch, so use
            // it rather than relying only on the global InsecureSkipVerify —
            // this keeps FTP correct even when the global flag is off because
            // no other destination asked for it.
            if (backend == "ftp" && allowInsecureTLS) out["no_check_certificate"] = "true"

            // SMB is configured WITHOUT a share, so the share becomes the
            // first level of the folder browser — what Finder and Windows
            // Explorer do when they enumerate shares after connecting. A
            // `share` stored by an older build is DROPPED rather than
            // migrated: a share-less fs reaches every share the account can
            // see, so the stored path still resolves through the browser,
            // while keeping the key would pin that remote to the one share it
            // was created with.
            if (backend == "smb") out.remove("share")

            // Blank credentials mean GUEST, and that has to be said
            // explicitly: rclone's smb `user` option defaults to the OS login
            // name when the key is absent, so an omitted username would
            // silently try to authenticate as some unrelated account instead
            // of connecting as a guest.
            if (backend == "smb" && (params["user"] ?: "").isBlank()) out["user"] = ""

            val hasSecret = !secret.isNullOrEmpty()

            // Anonymous access needs an OBSCURED EMPTY password, not a missing
            // one. rclone marks `pass` as IsPassword and de-obscures whatever
            // it finds — including the option's own "" default — so a bare ""
            // or an absent key fails with "decrypt password: input too short"
            // before any connection is attempted.
            //
            // Measured against rclone v1.73.3, this bites per backend: FTP
            // fails outright on a bare "" (and reaches the server's own
            // "Login incorrect" once obscured), while WebDAV tolerates it and
            // gets as far as a 401. `obscure("")` is the single form that
            // works for BOTH, so it is applied uniformly rather than
            // per-backend — one rule, no backend-specific exceptions to keep
            // in sync as rclone's option defaults change.
            //
            // S3 is excluded: its secret is consumed verbatim, so an anonymous
            // bucket wants NO key rather than an obscured blank.
            if (backend != "s3" && !hasSecret && (out["pass"] as? String).isNullOrEmpty()) {
                obscure("")?.let { out["pass"] = it }
                return out
            }

            // An empty stored secret is never written as a parameter.
            if (!hasSecret) {
                out.remove(secretKeyFor(backend))
                return out
            }

            out[secretKeyFor(backend)] =
                if (secretNeedsObscuring(backend)) obscure(secret!!) ?: secret else secret
            return out
        }
        /**
         * Whether a failed connection looks like a TLS trust problem.
         *
         * Used to decide whether to OFFER the self-signed opt-in, so it errs
         * toward not offering: matching too eagerly would put a "don't verify
         * certificates" control in front of users whose real problem is a wrong
         * password. The strings are what Go's crypto/tls and x509 actually
         * surface through rclone — rclone passes the underlying error text
         * through rather than normalising it, so there is no error code to key
         * off instead.
         *
         * Pure, so the matching can be tested without a server.
         */
        fun looksLikeCertificateFailure(message: String?): Boolean {
            val m = message?.lowercase() ?: return false
            return m.contains("x509") ||
                m.contains("certificate") ||
                m.contains("tls: failed to verify") ||
                m.contains("unknown authority") ||
                m.contains("self-signed") ||
                m.contains("self signed")
        }

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val REMOTE_LIST = ListSerializer(Remote.serializer())
    }
}

// ─── Merged from RcloneRpcTracker.kt ──────────────────────────────────────────────────

/**
 * [T-rclone-rpc-concurrency-diag] Records how many rclone RPCs are inside the
 * Go runtime at once, and says so only when it is more than one.
 *
 * Two vivo users hit bare SIGABRTs that may involve `libgojni.so`; whether
 * concurrent RPCs play any part is unknown. This answers that question the
 * next time such a crash comes with its log, at no cost to normal runs: a
 * backup or restore drives rclone from ONE polling thread, so these lines
 * appear only when the user browses or edits destinations meanwhile.
 *
 * Deliberately NOT a lock. RPCs block for up to `IO_TIMEOUT_SECONDS` (300 s),
 * and any serialisation would let a stalled list or copy hold back the
 * `job/stop` that Stop sends (impact assessment
 * summarised in the commit). Every call proceeds exactly as before.
 *
 * Logs method names and timings only — never params: `core/obscure` and
 * `config/create` carry clear-text passwords, and paths can carry user names.
 */
internal object RcloneRpcTracker {

    private const val TAG = "Rclone"

    private data class InFlight(val method: String, val startMs: Long)

    private val nextToken = AtomicLong()
    private val inFlight = ConcurrentHashMap<Long, InFlight>()

    /** Calls currently inside rclone. */
    val inFlightCount: Int get() = inFlight.size

    /** Seams for tests; production uses AppLogger and the wall clock. */
    @Volatile internal var log: (String) -> Unit = { AppLogger.info(TAG, it) }
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }

    /** Run [block] (the native RPC) for [method], recording it while it runs. */
    fun <T> track(method: String, block: () -> T): T {
        val token = nextToken.incrementAndGet()
        val start = clock()
        inFlight[token] = InFlight(method, start)
        val others = inFlight.filterKeys { it != token }.values
        val concurrent = others.isNotEmpty()
        if (concurrent) {
            log(
                "[Rclone] concurrent RPC enter: $method with ${others.size + 1} in flight; others=" +
                    others.joinToString(prefix = "[", postfix = "]") { "${it.method} ${start - it.startMs}ms" },
            )
        }
        try {
            return block()
        } finally {
            inFlight.remove(token)
            if (concurrent) {
                log("[Rclone] concurrent RPC exit: $method after ${clock() - start}ms; ${inFlight.size} still in flight")
            }
        }
    }
}
