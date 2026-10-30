package com.openminis.app.sandbox.offload

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.scheduled.PrefilledToolCall
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTargetDelivery
import com.openminis.app.scheduled.ScheduledTargetMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskManager
import com.openminis.app.scheduled.ScheduledTriggerKind
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * android-photos — query and manage the device photo library via MediaStore.
 *
 * Mirrors apple-photos (NativeOffloads/PhotosOffload.m) — same subcommand
 * names and flag conventions so prompts and the agent loop are platform-
 * agnostic. Some iOS concepts have no native Android equivalent (smart
 * albums; stable per-asset string identifiers); these are documented at
 * each subcommand.
 *
 * Usage:
 *   android-photos list [--type photo|video|all] [--start ISO --end ISO | --days N]
 *                       [--limit N]
 *   android-photos stats
 *   android-photos near --lat L --lon N [--radius KM] [--limit N]
 *   android-photos albums [--type user|smart|all]
 *   android-photos album [--id <bucket_id> | --name <bucket_name>] [--limit N]
 *   android-photos export --id <id> [--size thumb|medium|original]
 *   android-photos import --path <file> [--album-name <name>]
 *   android-photos create-album --name <name>
 *   android-photos favorite --id <id>
 *   android-photos delete --ids <id1,id2,...> --confirm
 *
 * Backwards compatibility:
 *   - `near <lat> <lon>` legacy positional form still works.
 *   - `--max` is accepted as an alias for `--limit`.
 */
class PhotosOffloadHandler(private val context: Context) : NativeOffloadHandler {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1), booleanFlags = setOf("confirm"))
        if (args.hasFlag("h", "help") || args.positional.isEmpty()) {
            return NativeOffloadResult(if (args.positional.isEmpty()) 2 else 0, HELP)
        }
        // T330: tri-state agent gate before requesting media permissions.
        OffloadGate.enforce("photos", "android-photos", args, request)?.let { return it }

        ensureMediaPermission(args)?.let { return it }

        return try {
            when (val sub = args.positional[0]) {
                "list" -> handleList(args)
                "stats" -> NativeOffloadResult(0, OffloadOutput.formatBody(photoStats(), args) + "\n")
                "near" -> handleNear(args)
                "albums" -> handleAlbums(args)
                "album" -> handleAlbum(args)
                // [T-offload-caller-session] The session whose /var/minis this
                // shell sees: the PARENT for a sub agent. With the raw
                // MINIS_CHAT_SESSION_ID a sub agent's export was written to
                // the helper's own offloads dir while the result pointed it at
                // /var/minis/offloads/..., a path its shell then could not read.
                "export" -> {
                    val caller = OffloadCallerSession.resolve(request.sessionId)
                    AppLogger.info(TAG, "export caller=${caller.sid?.take(8) ?: "nil"} via=${caller.source}")
                    handleExport(args, caller.sid)
                }
                "import", "save" -> handleImport(args)
                "create-album" -> handleCreateAlbum(args)
                "favorite" -> handleFavorite(args)
                "delete" -> handleDelete(args)
                else -> NativeOffloadResult(2, "android-photos: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: SecurityException) {
            AppLogger.warning(TAG, "SecurityException: ${e.message}")
            val body = JSONObject().put("error", "mediastore_blocked")
                .put("message", "Media access refused by the system. ${permissionHint()}. Underlying: ${e.message}")
                .toString()
            NativeOffloadResult(77, OffloadOutput.formatBody(body, args) + "\n")
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            val body = JSONObject().put("error", "mediastore_error")
                .put("message", e.message ?: "MediaStore query failed")
                .toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    // ── list (T59: + --type, --start, --end, --days, --limit) ────────────

    private fun handleList(args: OffloadArgs): NativeOffloadResult {
        val limit = (args.getInt("limit") ?: args.getInt("max") ?: 20).coerceIn(1, 100)
        val type = (args.get("type") ?: "all").lowercase()
        if (type !in setOf("photo", "video", "all")) {
            return NativeOffloadResult(2, "android-photos list: --type must be photo|video|all\n")
        }
        val (startMs, endMs) = resolveDateRange(args)
        AppLogger.info(TAG, "list: type=$type limit=$limit range=${startMs?.let { formatIso(it) }}..${endMs?.let { formatIso(it) }}")
        val arr = JSONArray()
        if (type == "photo" || type == "all") queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "photo", startMs, endMs, limit - arr.length(), arr)
        if (type == "video" || type == "all") queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video", startMs, endMs, limit - arr.length(), arr)
        val data = JSONObject()
            .put("media", arr)
            .put("count", arr.length())
            .put("type", type)
        if (startMs != null) data.put("range_start", formatIso(startMs))
        if (endMs != null) data.put("range_end", formatIso(endMs))
        return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
    }

    /**
     * MediaStore.Images and MediaStore.Video share the same column names for
     * the fields we care about, so one helper handles both. DATE_TAKEN is
     * milliseconds since epoch; we filter on it when --start/--end/--days
     * supply a window, and fall back to the (cheaper) implicit "all rows"
     * query when no range is set.
     */
    private fun queryMedia(uri: Uri, kind: String, startMs: Long?, endMs: Long?, max: Int, sink: JSONArray) {
        if (max <= 0) return
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()
        if (startMs != null) {
            selectionParts.add("${MediaStore.Images.Media.DATE_TAKEN} >= ?")
            selectionArgs.add(startMs.toString())
        }
        if (endMs != null) {
            selectionParts.add("${MediaStore.Images.Media.DATE_TAKEN} <= ?")
            selectionArgs.add(endMs.toString())
        }
        val selection = if (selectionParts.isEmpty()) null else selectionParts.joinToString(" AND ")
        val selArgs = if (selectionArgs.isEmpty()) null else selectionArgs.toTypedArray()
        context.contentResolver.query(uri, projection, selection, selArgs, "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { c ->
            var n = 0
            while (c.moveToNext() && n < max) {
                val p = JSONObject()
                    .put("id", c.getLong(0))
                    .put("name", c.getString(1) ?: "")
                    .put("media_type", kind)
                val dt = c.getLong(2)
                if (dt > 0) p.put("date", dateFormat.format(Date(dt))).put("date_iso", formatIso(dt))
                p.put("width", c.getInt(3))
                    .put("height", c.getInt(4))
                    .put("size_bytes", c.getLong(5))
                    .put("mime_type", c.getString(6) ?: "")
                    .put("bucket_id", c.getLong(7))
                    .put("bucket_name", c.getString(8) ?: "")
                sink.put(p)
                n++
            }
        }
    }

    // ── near (T59: + --lat/--lon flags + --limit) ────────────────────────

    private fun handleNear(args: OffloadArgs): NativeOffloadResult {
        // Flag form (--lat/--lon, mirrors apple-photos near) preferred;
        // legacy positional <lat> <lon> kept as alias.
        val lat = args.getDouble("lat") ?: args.positional.getOrNull(1)?.toDoubleOrNull()
        val lon = args.getDouble("lon", "lng") ?: args.positional.getOrNull(2)?.toDoubleOrNull()
        if (lat == null || lon == null) {
            return NativeOffloadResult(
                2,
                "android-photos near: --lat <lat> --lon <lon> required (positional <lat> <lon> also accepted)\n",
            )
        }
        ensureMediaLocationPermission(args)?.let { return it }
        val radius = args.getDouble("radius") ?: 1.0
        val max = (args.getInt("limit") ?: args.getInt("max") ?: 20).coerceIn(1, 100)
        AppLogger.info(TAG, "near: lat=$lat lon=$lon radius=${radius}km max=$max")
        return NativeOffloadResult(0, OffloadOutput.formatBody(nearPhotos(lat, lon, radius, max), args) + "\n")
    }

    private fun hasMediaPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED) return true
        // A14+ partial access counts
        if (Build.VERSION.SDK_INT >= 34) {
            val partial = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
            if (ContextCompat.checkSelfPermission(context, partial) == PackageManager.PERMISSION_GRANTED) return true
        }
        return false
    }

    private fun permissionHint(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "READ_MEDIA_IMAGES (or partial READ_MEDIA_VISUAL_USER_SELECTED on A14+) not granted. Ask the user to grant Photos/Media permission in Android settings."
        } else {
            "READ_EXTERNAL_STORAGE not granted. Ask the user to grant Storage permission in Android settings."
        }
    }

    /**
     * Drive the system-dialog → settings-gate flow. On A13+ we ask for the
     * granular media permissions; A14+ treats visual-user-selected as partial
     * access, which [hasMediaPermission] already accepts.
     */
    private fun ensureMediaPermission(args: OffloadArgs): NativeOffloadResult? {
        if (hasMediaPermission()) return null
        AppLogger.warning(TAG, "media permission not granted — routing through permission flow")
        val requestList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            buildList {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                if (Build.VERSION.SDK_INT >= 34) add("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
            }
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val result = runBlocking {
            var r = OffloadPermissionManager.requestAndroidPermission(requestList)
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED &&
                OffloadPermissionManager.pollForPermissionGrant({ hasMediaPermission() })
            ) {
                AppLogger.info(TAG, "Photos permission granted during post-DENY poll")
                r = OffloadPermissionManager.AndroidPermissionResult.GRANTED
            }
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED) {
                r = OffloadPermissionManager.requestSettingsGate(
                    OffloadPermissionManager.SettingsGateRequest(
                        id = "photos_media",
                        title = "Photos permission needed",
                        message = "Minis needs media permission to read your photo library. Open Settings to allow it.",
                        settingsAction = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        requiresPackageUri = true,
                        positiveLabel = "Open Settings",
                    ),
                    check = { hasMediaPermission() },
                )
            }
            r
        }
        return when (result) {
            OffloadPermissionManager.AndroidPermissionResult.GRANTED -> null
            OffloadPermissionManager.AndroidPermissionResult.DENIED -> NativeOffloadResult(
                77,
                OffloadOutput.formatBody(
                    JSONObject().put("error", "permission_denied")
                        .put("message", "The user declined the photos/media permission.")
                        .toString(),
                    args,
                ) + "\n",
            )
            OffloadPermissionManager.AndroidPermissionResult.TIMEOUT -> NativeOffloadResult(
                77,
                OffloadOutput.formatBody(
                    JSONObject().put("error", "timeout")
                        .put("message", "Timed out waiting for the user to grant the photos/media permission.")
                        .toString(),
                    args,
                ) + "\n",
            )
        }
    }

    private fun hasMediaLocationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context, "android.permission.ACCESS_MEDIA_LOCATION",
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * `near` reads GPS EXIF; without ACCESS_MEDIA_LOCATION the MediaStore
     * strips it from the streams we open and the result set is always empty.
     * On A10+ it's a runtime permission — manifest entry alone is not enough.
     * Pre-Q the permission doesn't exist; treat it as granted.
     */
    private fun ensureMediaLocationPermission(args: OffloadArgs): NativeOffloadResult? {
        if (hasMediaLocationPermission()) return null
        AppLogger.warning(TAG, "ACCESS_MEDIA_LOCATION not granted — routing through permission flow")
        val result = runBlocking {
            var r = OffloadPermissionManager.requestAndroidPermission(
                listOf("android.permission.ACCESS_MEDIA_LOCATION"),
            )
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED &&
                OffloadPermissionManager.pollForPermissionGrant({ hasMediaLocationPermission() })
            ) {
                AppLogger.info(TAG, "Media location permission granted during post-DENY poll")
                r = OffloadPermissionManager.AndroidPermissionResult.GRANTED
            }
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED) {
                r = OffloadPermissionManager.requestSettingsGate(
                    OffloadPermissionManager.SettingsGateRequest(
                        id = "ACCESS_MEDIA_LOCATION",
                        title = "Photo location needed",
                        message = "Minis needs photo-location permission to read GPS EXIF for the `near` query. Open Settings to allow it.",
                        settingsAction = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        requiresPackageUri = true,
                        positiveLabel = "Open Settings",
                    ),
                    check = { hasMediaLocationPermission() },
                )
            }
            r
        }
        return when (result) {
            OffloadPermissionManager.AndroidPermissionResult.GRANTED -> null
            OffloadPermissionManager.AndroidPermissionResult.DENIED -> NativeOffloadResult(
                77,
                OffloadOutput.formatBody(
                    JSONObject().put("error", "permission_denied")
                        .put("message", "The user declined the photo-location permission. Without it, GPS EXIF is redacted and `near` cannot match any photos.")
                        .toString(),
                    args,
                ) + "\n",
            )
            OffloadPermissionManager.AndroidPermissionResult.TIMEOUT -> NativeOffloadResult(
                77,
                OffloadOutput.formatBody(
                    JSONObject().put("error", "timeout")
                        .put("message", "Timed out waiting for the user to grant the photo-location permission.")
                        .toString(),
                    args,
                ) + "\n",
            )
        }
    }

    // ── albums (T64) ─────────────────────────────────────────────────────

    /**
     * List buckets (Android's equivalent of iOS PHAssetCollection — every
     * media row carries BUCKET_ID/BUCKET_DISPLAY_NAME). MediaStore has no
     * native "smart album" concept, so apple-photos's user/smart distinction
     * collapses on Android: --type smart returns an empty list with a
     * structured note; --type user and --type all are equivalent.
     */
    private fun handleAlbums(args: OffloadArgs): NativeOffloadResult {
        val type = (args.get("type") ?: "all").lowercase()
        if (type !in setOf("user", "smart", "all")) {
            return NativeOffloadResult(2, "android-photos albums: --type must be user|smart|all\n")
        }
        if (type == "smart") {
            val data = JSONObject()
                .put("albums", JSONArray())
                .put("count", 0)
                .put("note", "MediaStore has no smart-album concept on Android. Use --type user or --type all to list buckets.")
            return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
        }
        val items = JSONArray()
        // Aggregate over the IMAGES table; videos share the same bucket
        // namespace so we union by id afterwards.
        val seen = mutableSetOf<Long>()
        listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .forEach { uri ->
                val proj = arrayOf(
                    MediaStore.Images.Media.BUCKET_ID,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                )
                context.contentResolver.query(uri, proj, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val bid = c.getLong(0)
                        if (bid == 0L || bid in seen) continue
                        seen.add(bid)
                        val name = c.getString(1) ?: ""
                        items.put(JSONObject().put("id", bid).put("name", name).put("type", "user"))
                    }
                }
            }
        AppLogger.info(TAG, "albums: type=$type count=${items.length()}")
        val data = JSONObject().put("albums", items).put("count", items.length())
        return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
    }

    /**
     * List media inside one bucket. --id (Long) wins; --name does
     * case-insensitive contains lookup, mirroring apple-photos. Returns
     * the same shape as `list` so downstream tooling can treat them the
     * same.
     */
    private fun handleAlbum(args: OffloadArgs): NativeOffloadResult {
        val bucketId = args.getLong("id")
        val bucketName = args.get("name")
        if (bucketId == null && bucketName == null) {
            return NativeOffloadResult(2, "android-photos album: --id <bucket_id> or --name <bucket_name> is required\n")
        }
        val limit = (args.getInt("limit") ?: args.getInt("max") ?: 50).coerceIn(1, 200)
        val arr = JSONArray()
        val selection: String
        val selArgs: Array<String>
        if (bucketId != null) {
            selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
            selArgs = arrayOf(bucketId.toString())
        } else {
            selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?"
            selArgs = arrayOf("%${bucketName!!}%")
        }
        // Inline the projection so we can pass selection — queryMedia hides
        // selection behind the start/end filters.
        listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "photo",
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "video",
        ).forEach { (uri, kind) ->
            if (arr.length() >= limit) return@forEach
            val proj = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            )
            context.contentResolver.query(uri, proj, selection, selArgs, "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { c ->
                while (c.moveToNext() && arr.length() < limit) {
                    val p = JSONObject()
                        .put("id", c.getLong(0))
                        .put("name", c.getString(1) ?: "")
                        .put("media_type", kind)
                    val dt = c.getLong(2)
                    if (dt > 0) p.put("date", dateFormat.format(Date(dt))).put("date_iso", formatIso(dt))
                    p.put("size_bytes", c.getLong(3))
                        .put("mime_type", c.getString(4) ?: "")
                        .put("bucket_id", c.getLong(5))
                        .put("bucket_name", c.getString(6) ?: "")
                    arr.put(p)
                }
            }
        }
        AppLogger.info(TAG, "album: id=$bucketId name='$bucketName' count=${arr.length()}")
        val data = JSONObject()
            .put("media", arr)
            .put("count", arr.length())
            .put("album", JSONObject().apply {
                if (bucketId != null) put("id", bucketId)
                if (bucketName != null) put("name", bucketName)
            })
        return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
    }

    // ── export (T64) ─────────────────────────────────────────────────────

    /**
     * Copy the asset bytes into the CALLING SESSION's offloads dir
     * (`<filesDir>/minis-sessions/<sessionId>/offloads/`) and report the
     * sandbox-visible `/var/minis/offloads/<name>` path plus a `minis://`
     * URL, matching iOS `PhotosOffload.m` (which exports to
     * `/var/minis/offloads/` directly).
     *
     * [GH#139] This used to write to `<filesDir>/photos-export/` and return
     * only `host_path`. That path is inside no PRoot bind mount, so the
     * Linux sandbox cannot read it and `minis-open` rejects it (it accepts
     * only http/https/about/minis URLs) — the agent could list photo
     * metadata but never actually look at an exported photo. An older
     * comment here claimed the handler "doesn't see the session id"; that
     * stopped being true when T340 added `sessionId` to
     * [NativeOffloadRequest], so the export is now session-scoped the same
     * way `ModelUseOffloadHandler.sessionScopedHostFile` already does it.
     *
     * `sessionId` is null for offloads launched outside a chat (interactive
     * terminal). There is no session offloads dir to write into then, so we
     * keep the app-private fallback — but say plainly in the response that
     * the file is NOT reachable from Linux, instead of implying it is.
     *
     * --size thumb / medium re-encode to JPEG at 256px / 1024px max edge
     * via BitmapFactory; original copies the resource bytes as-is.
     */
    private fun handleExport(args: OffloadArgs, sessionId: String?): NativeOffloadResult {
        val id = args.getLong("id")
            ?: return NativeOffloadResult(2, "android-photos export: --id <asset_id> is required\n")
        val size = (args.get("size") ?: "original").lowercase()
        if (size !in setOf("thumb", "medium", "original")) {
            return NativeOffloadResult(2, "android-photos export: --size must be thumb|medium|original\n")
        }

        // Try Images first then Video.
        var srcUri: Uri? = null
        var mediaType = "photo"
        var mimeType = ""
        var displayName = "export"
        var width = 0
        var height = 0
        for ((uri, kind) in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "photo",
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "video",
        )) {
            val proj = arrayOf(
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
            )
            context.contentResolver.query(uri, proj, "${MediaStore.Images.Media._ID} = ?", arrayOf(id.toString()), null)?.use { c ->
                if (c.moveToFirst()) {
                    srcUri = ContentUris.withAppendedId(uri, id)
                    mediaType = kind
                    displayName = c.getString(0) ?: "export"
                    mimeType = c.getString(1) ?: ""
                    width = c.getInt(2); height = c.getInt(3)
                }
            }
            if (srcUri != null) break
        }
        val src = srcUri ?: return NativeOffloadResult(
            1,
            OffloadOutput.formatBody(
                JSONObject().put("error", "not_found").put("id", id)
                    .put("message", "No asset with id=$id (already deleted, or not visible to this app).")
                    .toString(),
                args,
            ) + "\n",
        )

        // [GH#139] Session-scoped when we know the caller's chat, so the export
        // lands in the dir PRoot bind-mounts at /var/minis/offloads for THIS
        // session. Mirrors ModelUseOffloadHandler.sessionScopedHostFile and
        // PRootKernel.resolveSessionHostPath, which use the same layout.
        val sandboxVisible = sessionId != null
        val outDir = if (sandboxVisible) {
            File(context.filesDir, "minis-sessions/$sessionId/offloads").also { it.mkdirs() }
        } else {
            File(context.filesDir, "photos-export").also { it.mkdirs() }
        }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        val ext = displayName.substringAfterLast('.', "")
            .ifEmpty { if (mediaType == "video") "mp4" else "jpg" }
        val sizeSuffix = if (size == "original") "" else "_$size"
        val outFile = File(outDir, "${id}${sizeSuffix}_$safeName${if (size == "original") "" else ".jpg"}")
            .let { if (it.name.endsWith(".$ext", true) || size != "original") it else File(outDir, "${id}${sizeSuffix}_$safeName.$ext") }

        return try {
            val bytesWritten = if (size == "original" || mediaType == "video") {
                copyStream(src, outFile)
            } else {
                val maxEdge = if (size == "thumb") 256 else 1024
                copyResized(src, outFile, maxEdge)
            }
            AppLogger.info(TAG, "export: id=$id size=$size bytes=$bytesWritten path=${outFile.absolutePath}")
            val data = JSONObject()
                .put("id", id)
                .put("host_path", outFile.absolutePath)
                .put("size_bytes", bytesWritten)
                .put("media_type", mediaType)
                .put("format", if (size == "original") "original" else "jpeg")
                .put("export_size", size)
            // [GH#139] Hand back the paths the agent can actually USE: the
            // sandbox path for shell tools, and the minis:// URL that
            // `minis-open` accepts for in-chat preview / model rendering.
            if (sandboxVisible) {
                data.put("linux_path", "/var/minis/offloads/${outFile.name}")
                    .put("minis_url", "minis://offloads/${outFile.name}")
                    .put(
                        "note",
                        "Exported into this chat's offloads dir. Use `linux_path` from shell " +
                            "tools, or `minis_url` with minis-open to preview it in chat.",
                    )
            } else {
                data.put(
                    "note",
                    "No chat session for this offload (interactive terminal), so the export " +
                        "went to app-private storage: `host_path` is NOT reachable from the " +
                        "Linux sandbox and minis-open cannot open it. Run the export from a " +
                        "chat to get a /var/minis/offloads path.",
                )
            }
            if (width > 0) data.put("width", width)
            if (height > 0) data.put("height", height)
            NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
        } catch (e: Throwable) {
            val body = JSONObject().put("error", "export_failed").put("id", id)
                .put("message", e.message ?: "Failed to copy asset bytes")
                .toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    private fun copyStream(src: Uri, dst: File): Long {
        var written = 0L
        context.contentResolver.openInputStream(src)?.use { input ->
            dst.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf); if (n <= 0) break
                    out.write(buf, 0, n); written += n
                }
            }
        } ?: throw java.io.IOException("Could not open source asset stream")
        return written
    }

    /** Re-encode an image asset to JPEG with max edge [maxEdge]. */
    private fun copyResized(src: Uri, dst: File, maxEdge: Int): Long {
        val bytes = context.contentResolver.openInputStream(src)?.use { it.readBytes() }
            ?: throw java.io.IOException("Could not read source bytes")
        val opts = android.graphics.BitmapFactory.Options()
        opts.inJustDecodeBounds = true
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val w = opts.outWidth; val h = opts.outHeight
        var sample = 1
        while (w / sample > maxEdge * 2 || h / sample > maxEdge * 2) sample *= 2
        val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            ?: throw java.io.IOException("Failed to decode source image")
        val scale = minOf(maxEdge.toFloat() / bmp.width, maxEdge.toFloat() / bmp.height, 1f)
        val out = if (scale < 1f) {
            android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        } else bmp
        dst.outputStream().use { os ->
            out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, os)
        }
        return dst.length()
    }

    // ── import (T64) ─────────────────────────────────────────────────────

    /**
     * Copy a host-filesystem file into MediaStore. Mirrors apple-photos
     * import. --album-name creates the bucket implicitly via RELATIVE_PATH
     * (Android API 29+) — there's no "create empty album" API.
     */
    private fun handleImport(args: OffloadArgs): NativeOffloadResult {
        // [T-photos-positional-path] Accept `import <file>` as well as
        // `import --path <file>`, matching apple-photos (iOS 048d2d9f). The
        // file is this subcommand's only natural argument, so the bare form is
        // what an LLM (or a human) reaches for first; requiring the flag turned
        // a correct-looking call into an argument error. positional[0] is the
        // subcommand itself, so the path is positional[1].
        val path = args.get("path") ?: args.positional.getOrNull(1)
            ?: return NativeOffloadResult(
                2,
                "android-photos import: a file path is required (positional or --path <file>)\n",
            )
        val src = File(path)
        if (!src.exists() || !src.isFile) {
            return NativeOffloadResult(
                1,
                OffloadOutput.formatBody(
                    JSONObject().put("error", "no_file").put("path", path)
                        .put("message", "Source file does not exist or is not a regular file.")
                        .toString(),
                    args,
                ) + "\n",
            )
        }
        val albumName = args.get("album-name")
        val ext = src.extension.lowercase()
        val isVideo = ext in setOf("mp4", "mov", "m4v", "3gp", "mkv", "webm")
        val mime = when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "heic" -> "image/heic"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "m4v" -> "video/mp4"
            "3gp" -> "video/3gpp"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            else -> if (isVideo) "video/*" else "image/*"
        }
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                         else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rel = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                val path = if (albumName != null) "$rel/${albumName.replace('/', '_')}" else rel
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        return try {
            val uri = context.contentResolver.insert(collection, values)
                ?: return NativeOffloadResult(
                    1,
                    OffloadOutput.formatBody(
                        JSONObject().put("error", "insert_failed").put("message", "ContentResolver.insert returned null").toString(),
                        args,
                    ) + "\n",
                )
            val written = context.contentResolver.openOutputStream(uri)?.use { os ->
                FileInputStream(src).use { input ->
                    var n = 0L
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = input.read(buf); if (r <= 0) break
                        os.write(buf, 0, r); n += r
                    }
                    n
                }
            } ?: throw java.io.IOException("openOutputStream returned null")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val finishValues = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(uri, finishValues, null, null)
            }
            val newId = ContentUris.parseId(uri)
            AppLogger.info(TAG, "import: path='${src.name}' id=$newId bytes=$written album='$albumName'")
            val data = JSONObject()
                .put("id", newId)
                .put("name", src.name)
                .put("media_type", if (isVideo) "video" else "photo")
                .put("size_bytes", written)
                .put("mime_type", mime)
            if (albumName != null) data.put("album", albumName)
            NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
        } catch (e: Throwable) {
            val body = JSONObject().put("error", "import_failed").put("path", path)
                .put("message", e.message ?: "Failed to insert media")
                .toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    // ── create-album (T64) ───────────────────────────────────────────────

    /**
     * Android has no "create empty album" API — buckets are created
     * implicitly when files land in them. We return a structured note so
     * the model knows to use `import --album-name` instead of failing
     * silently. Mirrors apple-photos create-album shape (id/name/note).
     */
    private fun handleCreateAlbum(args: OffloadArgs): NativeOffloadResult {
        val name = args.get("name")
            ?: return NativeOffloadResult(2, "android-photos create-album: --name <name> is required\n")
        AppLogger.info(TAG, "create-album: name='$name' (Android implicit-create)")
        val data = JSONObject()
            .put("name", name)
            .put("created", false)
            .put("warning", "android_implicit_album")
            .put("message", "Android MediaStore creates album buckets implicitly on first file insert. Use `android-photos import --path <file> --album-name '$name'` to create '$name' as a side-effect of saving a file. The bucket id is assigned by the system at that point.")
        return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
    }

    // ── favorite (T64) ───────────────────────────────────────────────────

    /**
     * Toggle MediaStore.MediaColumns.IS_FAVORITE (Android 11+). Mirrors
     * apple-photos favorite — toggles the flag and returns the new state.
     * On API < 30 returns a structured error since the column doesn't
     * exist.
     */
    private fun handleFavorite(args: OffloadArgs): NativeOffloadResult {
        val id = args.getLong("id")
            ?: return NativeOffloadResult(2, "android-photos favorite: --id <asset_id> is required\n")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val body = JSONObject().put("error", "not_supported")
                .put("message", "Favorite flag requires Android 11 (API 30) or later. This device runs API ${Build.VERSION.SDK_INT}.")
                .toString()
            return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
        // Find the row in either Images or Video.
        var srcUri: Uri? = null
        var currentlyFavorite = false
        for (uri in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.IS_FAVORITE),
                "${MediaStore.Images.Media._ID} = ?",
                arrayOf(id.toString()), null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    srcUri = ContentUris.withAppendedId(uri, id)
                    currentlyFavorite = c.getInt(0) == 1
                }
            }
            if (srcUri != null) break
        }
        val target = srcUri ?: return NativeOffloadResult(
            1,
            OffloadOutput.formatBody(
                JSONObject().put("error", "not_found").put("id", id)
                    .put("message", "No asset with id=$id.").toString(),
                args,
            ) + "\n",
        )
        val newState = !currentlyFavorite
        return try {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_FAVORITE, if (newState) 1 else 0) }
            val rows = context.contentResolver.update(target, values, null, null)
            AppLogger.info(TAG, "favorite: id=$id was=$currentlyFavorite now=$newState rows=$rows")
            val data = JSONObject()
                .put("id", id)
                .put("favorite", newState)
                .put("rows_updated", rows)
            NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
        } catch (e: SecurityException) {
            // RecoverableSecurityException on A11+ when the user hasn't
            // explicitly granted write access to this asset. We can't
            // launch the system dialog from a CLI sandbox.
            val body = JSONObject().put("error", "write_denied")
                .put("id", id)
                .put("message", "Android requires explicit user consent to modify another app's media. Open the asset in the system Photos app and toggle favorite there. Underlying: ${e.message}")
                .toString()
            NativeOffloadResult(77, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    // ── delete (T64) ─────────────────────────────────────────────────────

    /**
     * Delete one or more assets. --ids comma-separated, --confirm
     * required (mirrors apple-photos delete). On A11+ MediaStore raises
     * RecoverableSecurityException when the asset wasn't created by us;
     * we surface that as a structured error since the CLI sandbox can't
     * show the system consent dialog.
     */
    private fun handleDelete(args: OffloadArgs): NativeOffloadResult {
        val idsStr = args.get("ids")
            ?: return NativeOffloadResult(2, "android-photos delete: --ids <id1,id2,...> is required\n")
        if (!args.hasFlag("confirm")) {
            return NativeOffloadResult(2, "android-photos delete: --confirm flag is required to delete assets\n")
        }
        val ids = idsStr.split(",").mapNotNull { it.trim().toLongOrNull() }.filter { it > 0 }
        if (ids.isEmpty()) {
            return NativeOffloadResult(2, "android-photos delete: --ids must contain at least one numeric id\n")
        }
        val deletedIds = JSONArray()
        val failed = JSONArray()
        for (id in ids) {
            // Try Images then Video.
            var rows = 0
            for (uri in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
                try {
                    rows = context.contentResolver.delete(
                        uri,
                        "${MediaStore.Images.Media._ID} = ?",
                        arrayOf(id.toString()),
                    )
                    if (rows > 0) break
                } catch (e: SecurityException) {
                    failed.put(JSONObject().put("id", id).put("error", "write_denied").put("message", e.message ?: "RecoverableSecurityException"))
                    break
                } catch (e: Throwable) {
                    failed.put(JSONObject().put("id", id).put("error", "delete_failed").put("message", e.message ?: "unknown"))
                    break
                }
            }
            if (rows > 0) deletedIds.put(id)
            else if (failed.length() == 0 || failed.getJSONObject(failed.length() - 1).optLong("id") != id) {
                failed.put(JSONObject().put("id", id).put("error", "not_found").put("message", "No asset with this id"))
            }
        }
        AppLogger.info(TAG, "delete: requested=${ids.size} deleted=${deletedIds.length()} failed=${failed.length()}")
        val data = JSONObject()
            .put("deleted_ids", deletedIds)
            .put("deleted_count", deletedIds.length())
            .put("requested_count", ids.size)
        if (failed.length() > 0) data.put("failed", failed)
        return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * Mirror apple-photos resolve_date_range: --days N (last N days
     * through now) wins over --start/--end. Returns (startMs?, endMs?)
     * as a Pair; either side may be null when no flag set.
     */
    private fun resolveDateRange(args: OffloadArgs): Pair<Long?, Long?> {
        args.getInt("days")?.let { days ->
            val n = if (days <= 0) 7 else days
            val cal = Calendar.getInstance().apply {
                timeInMillis = System.currentTimeMillis() - n * 24L * 60 * 60 * 1000
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            return cal.timeInMillis to System.currentTimeMillis()
        }
        val startMs = args.get("start")?.let { parseDate(it) }
        val endMs = args.get("end")?.let { parseDate(it) }
        return startMs to endMs
    }

    private fun parseDate(s: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd",
        )
        for (p in patterns) {
            try {
                val sdf = SimpleDateFormat(p, Locale.US).apply {
                    timeZone = if (p.endsWith("'Z'")) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
                }
                return sdf.parse(s)?.time
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun formatIso(ms: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        return sdf.format(Date(ms))
    }

    private fun photoStats(): String {
        var count = 0L
        var earliest = Long.MAX_VALUE
        var latest = 0L
        var totalSize = 0L
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.SIZE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                count++
                val d = c.getLong(0)
                if (d > 0) {
                    if (d < earliest) earliest = d
                    if (d > latest) latest = d
                }
                totalSize += c.getLong(1)
            }
        }
        val json = JSONObject()
            .put("total_photos", count)
            .put("total_size_mb", String.format("%.1f", totalSize / 1e6))
        if (earliest < Long.MAX_VALUE) json.put("earliest", dateFormat.format(Date(earliest)))
        if (latest > 0) json.put("latest", dateFormat.format(Date(latest)))
        return json.toString(2)
    }

    /**
     * `near` reads EXIF from each candidate photo rather than relying on the
     * MediaStore LATITUDE/LONGITUDE columns — those were deprecated in API 29
     * and now return 0 on most OEM Gallery implementations. Requires
     * ACCESS_MEDIA_LOCATION on A10+ and setRequireOriginal() to retrieve
     * un-redacted EXIF.
     *
     * NOTE: this is O(N) disk reads; we scan at most the newest 500 photos
     * to keep runtime bounded. Photos in encrypted "Secure Folder" / MIUI
     * private album / Huawei vault are not exposed to MediaStore and will
     * be invisible — we note that in the response when zero results come back.
     */
    private fun nearPhotos(lat: Double, lon: Double, radiusKm: Double, max: Int): String {
        val photos = JSONArray()
        val scanLimit = 500
        // ensureMediaLocationPermission() already guaranteed this on the
        // happy path; if a caller bypasses the gate we still degrade safely.
        val hasMediaLoc = hasMediaLocationPermission()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
        )
        var scanned = 0
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC",
        )?.use { c ->
            while (c.moveToNext() && photos.length() < max && scanned < scanLimit) {
                scanned++
                val id = c.getLong(0)
                val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val uriToRead = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasMediaLoc) {
                    runCatching { MediaStore.setRequireOriginal(contentUri) }.getOrDefault(contentUri)
                } else contentUri
                val coords = try {
                    context.contentResolver.openInputStream(uriToRead)?.use { input ->
                        val exif = ExifInterface(input)
                        val ll = FloatArray(2)
                        if (exif.getLatLong(ll)) doubleArrayOf(ll[0].toDouble(), ll[1].toDouble()) else null
                    }
                } catch (_: Throwable) { null } ?: continue

                val dist = haversine(lat, lon, coords[0], coords[1])
                if (dist <= radiusKm) {
                    val p = JSONObject()
                        .put("id", id)
                        .put("name", c.getString(1) ?: "")
                    val dt = c.getLong(2)
                    if (dt > 0) p.put("date", dateFormat.format(Date(dt)))
                    p.put("latitude", coords[0])
                        .put("longitude", coords[1])
                        .put("distance_km", String.format("%.2f", dist))
                    photos.put(p)
                }
            }
        }
        if (photos.length() == 0) {
            val hint = if (!hasMediaLoc && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "  (GPS EXIF is redacted without the ACCESS_MEDIA_LOCATION permission; add it to AndroidManifest and request it from the user.)"
            } else if (scanned == scanLimit) {
                "  (scanned the newest $scanLimit photos; no matches in that window)"
            } else ""
            return "No photos found within ${radiusKm}km of ($lat, $lon).$hint"
        }
        return photos.toString(2)
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    companion object {
        private const val TAG = "PhotosOffload"
        private const val HELP = """android-photos — query and manage device photos & videos
                              (mirrors apple-photos)

Usage:
  android-photos list [--type photo|video|all]
                      [--start ISO --end ISO | --days N] [--limit N]
  android-photos stats
  android-photos near --lat L --lon N [--radius KM] [--limit N]
                      (legacy: near <lat> <lon>)
  android-photos albums [--type user|smart|all]
  android-photos album --id <bucket_id> | --name <bucket_name> [--limit N]
  android-photos export --id <asset_id> [--size thumb|medium|original]
  android-photos import <file> [--album-name <name>]          (alias: save)
                       (--path <file> also accepted)
  android-photos create-album --name <name>
  android-photos favorite --id <asset_id>                     (Android 11+)
  android-photos delete --ids <id1,id2,...> --confirm

Aliases for backwards compatibility:
  --max ↔ --limit
  --lng ↔ --lon
  positional `near <lat> <lon>` (flag form preferred)

Android edge cases vs apple-photos:
  - No native smart-album concept. `albums --type smart` returns empty
    with a structured note. `--type user` and `--type all` are equivalent.
  - No "create empty album" API. `create-album` returns an instructional
    note pointing at `import --album-name <name>`.
  - On Android 11+ modifying or deleting another app's media triggers
    RecoverableSecurityException. Surfaced as `error: write_denied`
    since the CLI sandbox can't show the system consent dialog.
  - Export writes into the calling chat's offloads dir and returns
    `linux_path` (/var/minis/offloads/...) and `minis_url`
    (minis://offloads/...) alongside `host_path`, matching iOS. Outside a
    chat (interactive terminal) there is no session dir, so only
    `host_path` is returned and the note says it is not reachable from
    the Linux sandbox.

Errors return JSON: {"error":"...","message":"..."}.
"""
    }
}

// ─── Merged from ScheduledTaskOffloadHandler.kt ──────────────────────────────────────────────────

/**
 * [T-android-scheduled-tasks-full] minis-scheduled — CLI surface for the
 * Scheduled Tasks feature, exposing the same option set as the editor UI and
 * the iOS Shortcuts intents so the agent can create / inspect / fire timed
 * AI actions from a prompt.
 *
 *   minis-scheduled list
 *   minis-scheduled create --prompt "..." [--label L]
 *                          [--after 30m] | [--interval 10m [--count N]]
 *                          | [--time HH:MM [--repeat once|daily|weekdays|custom --days mon,tue,...]]
 *                          | [--trigger on-completion --of <taskId|jobId>]
 *                          [--target new|follow-up|rerun]
 *                          [--session <id>] [--message <id>]
 *                          [--model entry:<id>|group:<id>|<modelId>] [--thinking off|low|medium|high|xhigh|max|ultra]
 *                          [--command "<cmd>" [--command-timeout 2m]]   (alias --shell)
 *                          [--tool <name> --tool-args '<json>']
 *                          [--start YYYY-MM-DD] [--end YYYY-MM-DD]
 *                          [--disabled]
 *   minis-scheduled delete --id <taskId>
 *   minis-scheduled enable  --id <taskId>
 *   minis-scheduled disable --id <taskId>
 *   minis-scheduled run     --id <taskId>     (fire immediately, off-schedule)
 *
 * Target modes mirror iOS App Intents:
 *   new        ≈ SendPrompt(no session)   — run prompt in a fresh chat
 *   follow-up  ≈ FollowUpSession           — append prompt to --session
 *   rerun      ≈ RetryRun                  — re-run --session from --message
 *
 * [T-android-scheduled-triggers] Triggers follow iOS ScheduledOffload
 * (8b315deba): --after (once, relative), --interval/--count (loop),
 * --time (calendar), --trigger on-completion --of (after another task or agent
 * job), inferred when --trigger is omitted. One Android difference is kept on
 * purpose: `--time` without --trigger/--repeat still means ONCE, as it always
 * has here; an explicit `--trigger cron` defaults to daily, as on iOS.
 *
 * [T-scheduled-tool-prefill] `--command` / `--tool` prefill a tool call: it is
 * executed when the task fires, before the model is asked anything, and the
 * model receives it already answered — one model request instead of two.
 * `--prompt` then says what to do with the result.
 */
class ScheduledTaskOffloadHandler(private val context: Context) : NativeOffloadHandler {

    private val manager get() = ScheduledTaskManager(context)

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(
            request.argv.drop(1),
            booleanFlags = setOf("disabled", "h", "help"),
        )
        // [T-offload-defaults-batch-android] Help only on explicit
        // --help/-h; no subcommand defaults to `list`.
        if (args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, HELP)
        }
        return try {
            when (val sub = args.positional.firstOrNull() ?: "list") {
                "list" -> handleList()
                "create", "add" -> {
                    // [T-offload-caller-session] The chat a default (follow-up)
                    // job belongs to and fires into: the session whose files
                    // this shell sees, i.e. the PARENT chat for a sub agent.
                    // The raw MINIS_CHAT_SESSION_ID is the helper's own hidden
                    // session, so a task created from a sub agent fired into a
                    // chat the user never sees while `create` still reported
                    // "IN THIS CHAT". iOS resolves the caller the same way
                    // (7effb6f2f; a helper runs under its parent's fs_context).
                    val caller = OffloadCallerSession.resolve(request.sessionId)
                    AppLogger.info(TAG, "create caller=${caller.sid?.take(8) ?: "nil"} via=${caller.source}")
                    handleCreate(args, caller.sid)
                }
                "delete", "remove", "rm" -> handleDelete(args)
                "enable" -> handleSetEnabled(args, true)
                "disable" -> handleSetEnabled(args, false)
                "run" -> handleRun(args)
                else -> NativeOffloadResult(2, "minis-scheduled: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: IllegalArgumentException) {
            NativeOffloadResult(2, "minis-scheduled: ${e.message}")
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "handle failed: ${e.message}")
            NativeOffloadResult(1, "minis-scheduled: ${e.message}")
        }
    }

    private fun handleList(): NativeOffloadResult {
        val arr = JSONArray()
        for (t in manager.list()) arr.put(taskJson(t))
        val out = JSONObject().put("tasks", arr).put("count", arr.length())
        return NativeOffloadResult(0, out.toString(2))
    }

    /**
     * [T-android-scheduled-triggers] The parsed trigger, ready to go into a
     * [ScheduledTask]. Calendar fields keep their defaults for the relative
     * kinds (the row still stores an hour/minute; nothing reads it).
     */
    internal data class Trigger(
        val kind: ScheduledTriggerKind,
        val hour: Int = 9,
        val minute: Int = 0,
        val repeat: ScheduledRepeatMode = ScheduledRepeatMode.ONCE,
        val customDays: Set<Int> = emptySet(),
        val delaySec: Long? = null,
        val intervalSec: Long? = null,
        val maxFires: Int? = null,
        val onCompletionOf: String? = null,
    )

    private fun handleCreate(args: OffloadArgs, callerSessionId: String?): NativeOffloadResult {
        val label = args.get("label", "l") ?: ""
        val trigger = parseTrigger(args) { ofRaw -> resolveUpstream(ofRaw) }
        val thinking = parseThinking(args.get("thinking"))
        // [T-android-scheduled-default-follow-up] Without --target, a task
        // created inside a chat fires back into that chat (iOS c32dda5a7).
        val (target, defaulted) = resolveTarget(args, callerSessionId)
        val prompt = args.get("prompt", "p") ?: ""
        if (target !is ScheduledTargetMode.RerunMessage && prompt.isBlank()) {
            throw IllegalArgumentException("--prompt required (except for rerun target)")
        }
        val prefill = parsePrefill(args, label)
        if (prefill != null && target is ScheduledTargetMode.RerunMessage) {
            throw IllegalArgumentException("--command/--tool cannot be used with the rerun target (it replays an existing message)")
        }
        // [OpenMinis#445] Resolved and checked now, stored as the binding the
        // task editor writes; the raw string used to be kept as a model id and
        // silently fell back to the default model when it matched nothing.
        val model = args.get("model", "m")?.let { raw ->
            if (target !is ScheduledTargetMode.NewSession) {
                throw IllegalArgumentException(
                    "--model only applies to --target new; a follow-up or rerun runs in an existing chat and keeps that chat's model",
                )
            }
            resolveModel(raw)
        }
        val task = manager.create(
            ScheduledTask(
                label = label,
                timeOfDayHour = trigger.hour,
                timeOfDayMinute = trigger.minute,
                repeatMode = trigger.repeat,
                customDays = trigger.customDays,
                prompt = prompt,
                targetMode = target,
                modelId = model?.modelId,
                modelBinding = model?.bindingJson,
                thinkingLevel = thinking,
                prefillToolCall = prefill,
                enabled = !args.hasFlag("disabled"),
                startDateMs = args.get("start")?.let { parseDate(it) },
                endDateMs = args.get("end")?.let { parseDate(it) },
                triggerKind = trigger.kind,
                delaySec = trigger.delaySec,
                intervalSec = trigger.intervalSec,
                maxFires = trigger.maxFires,
                onCompletionOf = trigger.onCompletionOf,
                // [T-scheduled-active-session-android] The chat running the CLI,
                // so a follow-up aimed back at it keeps it processing.
                creatorSessionId = callerSessionId,
            ),
        )
        val destination = when (target) {
            is ScheduledTargetMode.AppendToSession -> ScheduledTargetDelivery.Destination.FollowUp(
                target.sessionId, sessionTitle(target.sessionId), target.sessionId == callerSessionId,
            )
            is ScheduledTargetMode.RerunMessage -> ScheduledTargetDelivery.Destination.Rerun(
                target.sessionId, target.messageId, sessionTitle(target.sessionId),
            )
            else -> ScheduledTargetDelivery.Destination.NewChat(label.ifBlank { null })
        }
        val out = JSONObject()
            .put("created", taskJson(task))
            .put("delivery", ScheduledTargetDelivery.sentence(destination, defaulted))
            .put("deliveryTarget", JSONObject(ScheduledTargetDelivery.summary(destination, defaulted)))
        return NativeOffloadResult(0, out.toString(2))
    }

    /**
     * [T-android-scheduled-triggers] `--of`: a scheduled task (by id, or by
     * label as iOS accepts for --id) or a live agent job (a background sub
     * agent's job_id). Anything else is refused at create time: a typo would
     * otherwise leave a task that silently never fires.
     */
    private fun resolveUpstream(raw: String): String {
        val tasks = manager.list()
        tasks.firstOrNull { it.id == raw }?.let { return it.id }
        tasks.filter { it.label == raw }.let { byLabel ->
            if (byLabel.size == 1) return byLabel.first().id
            if (byLabel.size > 1) throw IllegalArgumentException("--of '$raw' matches ${byLabel.size} tasks by label; use its id")
        }
        if (com.openminis.app.agent.jobs.AgentJobRegistry.job(raw) != null) return raw
        throw IllegalArgumentException("--of: no scheduled task or agent job with id or label '$raw' (see `minis-scheduled list`)")
    }

    private fun handleDelete(args: OffloadArgs): NativeOffloadResult {
        val id = args.get("id") ?: throw IllegalArgumentException("--id required")
        if (manager.get(id) == null) return NativeOffloadResult(1, "minis-scheduled: no task with id=$id")
        manager.delete(id)
        return NativeOffloadResult(0, JSONObject().put("deleted", id).toString())
    }

    private fun handleSetEnabled(args: OffloadArgs, enabled: Boolean): NativeOffloadResult {
        val id = args.get("id") ?: throw IllegalArgumentException("--id required")
        if (manager.get(id) == null) return NativeOffloadResult(1, "minis-scheduled: no task with id=$id")
        manager.setEnabled(id, enabled)
        return NativeOffloadResult(0, JSONObject().put("id", id).put("enabled", enabled).toString())
    }

    private fun handleRun(args: OffloadArgs): NativeOffloadResult {
        val id = args.get("id") ?: throw IllegalArgumentException("--id required")
        val task = manager.get(id) ?: return NativeOffloadResult(1, "minis-scheduled: no task with id=$id")
        // Fire immediately, off-schedule. Blocks until the agent loop finishes
        // (ScheduledAgentRunner waits internally). Mirrors the editor "Run now".
        val sessionId = runBlocking {
            com.openminis.app.scheduled.ScheduledAgentRunner.run(context, task)
        }
        val out = JSONObject()
            .put("id", id)
            .put("ran", sessionId != null)
            .apply { if (sessionId != null) put("sessionId", sessionId) }
        return NativeOffloadResult(if (sessionId != null) 0 else 1, out.toString(2))
    }

    /**
     * [T-android-scheduled-default-follow-up] The target mode and whether it was
     * defaulted. Where fires land is settled here, at create time: an explicit
     * follow-up to a session that does not exist is an error (instead of a
     * failure on every fire), and a DEFAULTED follow-up whose caller session is
     * gone falls back to `new`.
     */
    private fun resolveTarget(args: OffloadArgs, callerSessionId: String?): Pair<ScheduledTargetMode, Boolean> {
        val session = args.get("session", "s")
        val (target, defaulted) = ScheduledTargetDelivery.resolveTarget(args.get("target"), session, callerSessionId)
        return when (target) {
            "new" -> ScheduledTargetMode.NewSession to defaulted
            "follow-up", "followup", "follow" -> {
                val sid = session ?: callerSessionId
                    ?: throw IllegalArgumentException("--session required for follow-up target")
                when {
                    sessionExists(sid) -> ScheduledTargetMode.AppendToSession(sid) to defaulted
                    defaulted -> ScheduledTargetMode.NewSession to true
                    else -> throw IllegalArgumentException("no chat session with id=$sid for the follow-up target")
                }
            }
            "rerun", "re-run", "retry" -> {
                val sid = session ?: throw IllegalArgumentException("--session required for rerun target")
                val mid = args.get("message")
                    ?: throw IllegalArgumentException("--message required for rerun target")
                ScheduledTargetMode.RerunMessage(sid, mid) to defaulted
            }
            else -> throw IllegalArgumentException("--target must be follow-up|new|rerun")
        }
    }

    private fun resolveModel(raw: String): com.openminis.app.scheduled.ScheduledModelSource.Resolved {
        val repo = (context.applicationContext as? com.openminis.app.MinisApp)?.providerRepositoryOrNull
            ?: throw IllegalStateException("--model: model settings are not loaded yet; try again")
        val cfg = repo.config.value
        return com.openminis.app.scheduled.ScheduledModelSource.resolve(
            raw,
            com.openminis.app.scheduled.ScheduledModelSource.entryRefs(cfg.modelEntries, repo.allVisibleEntries()),
            com.openminis.app.scheduled.ScheduledModelSource.groupRefs(cfg.modelGroups),
        )
    }

    private fun sessionEntity(id: String) =
        (context.applicationContext as? com.openminis.app.MinisApp)?.chatRepositoryOrNull?.let { repo ->
            runBlocking { repo.getSession(id) }
        }

    /** Unknown (repository not ready) counts as existing: never refuse on a guess. */
    private fun sessionExists(id: String): Boolean {
        val repo = (context.applicationContext as? com.openminis.app.MinisApp)?.chatRepositoryOrNull ?: return true
        return runBlocking { repo.getSession(id) } != null
    }

    private fun sessionTitle(id: String): String? = sessionEntity(id)?.title?.takeIf { it.isNotBlank() }

    /** YYYY-MM-DD → local start-of-day epoch ms. */
    private fun parseDate(s: String): Long {
        val parts = s.split("-")
        require(parts.size == 3) { "--start/--end must be YYYY-MM-DD" }
        val y = parts[0].toInt(); val mo = parts[1].toInt(); val d = parts[2].toInt()
        return Calendar.getInstance().apply {
            clear(); set(y, mo - 1, d, 0, 0, 0)
        }.timeInMillis
    }

    private fun taskJson(t: ScheduledTask): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("label", t.label)
        put("time", "%02d:%02d".format(t.timeOfDayHour, t.timeOfDayMinute))
        put("repeat", t.repeatMode.name.lowercase())
        if (t.customDays.isNotEmpty()) put("days", JSONArray(t.customDays.toList()))
        put("prompt", t.prompt)
        put("target", t.targetMode.encode())
        // [T-android-scheduled-triggers] The trigger in the CLI's own words.
        put(
            "trigger",
            when (t.triggerKind) {
                ScheduledTriggerKind.CALENDAR -> if (t.repeatMode == ScheduledRepeatMode.ONCE) "once" else "cron"
                ScheduledTriggerKind.AFTER -> "once"
                ScheduledTriggerKind.INTERVAL -> "loop"
                ScheduledTriggerKind.ON_COMPLETION -> "on-completion"
            },
        )
        t.delaySec?.let { put("afterSec", it) }
        t.intervalSec?.let { put("intervalSec", it) }
        t.maxFires?.let { put("count", it) }
        t.remainingFires?.let { put("remaining", it) }
        t.onCompletionOf?.let { put("of", it) }
        if (t.usesAnchor) put("firedCount", t.triggeredCount ?: 0)
        t.thinkingLevel?.let { put("thinking", it.name.lowercase()) }
        com.openminis.app.scheduled.ScheduledModelSource.display(t.modelBinding, t.modelId)?.let { put("model", it) }
        t.prefillToolCall?.let { put("prefillTool", it.toJson()) }
        put("enabled", t.enabled)
        t.nextTriggerMs()?.let { put("nextTriggerMs", it) }
        if (t.startDateMs != null) put("startDateMs", t.startDateMs)
        if (t.endDateMs != null) put("endDateMs", t.endDateMs)
        if (t.lastFiredAt != null) put("lastFiredAt", t.lastFiredAt)
        if (t.lastResultPreview != null) put("lastResultPreview", t.lastResultPreview)
        if (t.lastResultSessionId != null) put("lastResultSessionId", t.lastResultSessionId)
    }

    companion object {
        private const val TAG = "ScheduledTaskOffload"

        /** Loop floor, as iOS: a tighter loop is a runaway, not a schedule. */
        internal const val MIN_INTERVAL_SEC = 60L

        /**
         * [T-android-scheduled-triggers] Trigger from the CLI flags, following
         * iOS ScheduledOffloadBridge.create: an explicit --trigger wins,
         * otherwise --after -> once, --interval -> loop, --time -> calendar,
         * --of -> on-completion. [resolveOf] maps --of to a known id.
         */
        internal fun parseTrigger(args: OffloadArgs, resolveOf: (String) -> String): Trigger {
            for (name in listOf("after", "interval", "count", "of", "trigger")) {
                if (args.hasFlag(name)) throw IllegalArgumentException("--$name needs a value")
            }
            val kind = args.get("trigger")?.lowercase()
                ?: when {
                    args.get("after") != null -> "once"
                    args.get("interval") != null -> "loop"
                    args.get("time", "t") != null -> "calendar"
                    args.get("of") != null -> "on-completion"
                    else -> throw IllegalArgumentException(
                        "give a trigger: --after <dur> (once), --interval <dur> [--count N] (loop), " +
                            "--time HH:MM [--repeat ...] (at a time of day), or --trigger on-completion --of <taskId|jobId>",
                    )
                }
            return when (kind) {
                "once" -> {
                    val after = args.get("after")
                    when {
                        after != null -> {
                            val secs = parseDurationSec(after)?.takeIf { it >= 1 }
                                ?: throw IllegalArgumentException("--after must be a duration like 30m, 2h, 90s")
                            Trigger(ScheduledTriggerKind.AFTER, delaySec = secs.toLong())
                        }
                        args.get("time", "t") != null -> calendar(args, defaultRepeat = ScheduledRepeatMode.ONCE)
                        else -> throw IllegalArgumentException("once needs --after <duration> or --time HH:MM")
                    }
                }
                "loop" -> {
                    val secs = args.get("interval")?.let { parseDurationSec(it) }
                        ?.takeIf { it >= MIN_INTERVAL_SEC }
                        ?: throw IllegalArgumentException("--interval must be a duration of at least 60s (e.g. 10m)")
                    val count = args.get("count")?.let {
                        it.toIntOrNull()?.takeIf { n -> n >= 1 }
                            ?: throw IllegalArgumentException("--count must be a positive integer")
                    }
                    Trigger(ScheduledTriggerKind.INTERVAL, intervalSec = secs.toLong(), maxFires = count)
                }
                // Android's own spelling: --time alone keeps meaning once.
                "calendar" -> calendar(args, defaultRepeat = ScheduledRepeatMode.ONCE)
                // iOS's: an explicit cron repeats, daily unless told otherwise.
                "cron" -> calendar(args, defaultRepeat = ScheduledRepeatMode.DAILY)
                "on-completion", "oncompletion", "on_completion" -> {
                    val of = args.get("of")
                        ?: throw IllegalArgumentException("--of <taskId|jobId> required for the on-completion trigger")
                    Trigger(ScheduledTriggerKind.ON_COMPLETION, onCompletionOf = resolveOf(of))
                }
                else -> throw IllegalArgumentException("--trigger must be once|loop|cron|on-completion")
            }
        }

        private fun calendar(args: OffloadArgs, defaultRepeat: ScheduledRepeatMode): Trigger {
            val (hour, minute) = parseTime(
                args.get("time", "t") ?: throw IllegalArgumentException("--time HH:MM required"),
            )
            val repeat = args.get("repeat", "r")?.let { parseRepeat(it) } ?: defaultRepeat
            val customDays = if (repeat == ScheduledRepeatMode.CUSTOM) {
                parseDays(args.get("days") ?: throw IllegalArgumentException("--days required for custom repeat"))
            } else emptySet()
            return Trigger(ScheduledTriggerKind.CALENDAR, hour, minute, repeat, customDays)
        }

        /**
         * [T-android-scheduled-triggers] `--thinking`, validated rather than
         * coerced (iOS 993b0a5e0): a typo must not quietly run every fire at
         * the wrong level. null = leave it to the session, as before.
         */
        internal fun parseThinking(raw: String?): com.openminis.app.data.model.ThinkingLevel? {
            if (raw == null) return null
            return com.openminis.app.data.model.ThinkingLevel.parseOrNull(raw)
                ?: throw IllegalArgumentException(
                    "--thinking must be one of: " +
                        com.openminis.app.data.model.ThinkingLevel.entries.joinToString(", ") { it.name.lowercase() },
                )
        }

        private fun parseTime(s: String): Pair<Int, Int> {
            val parts = s.split(":")
            require(parts.size == 2) { "--time must be HH:MM" }
            val h = parts[0].toIntOrNull()?.takeIf { it in 0..23 }
                ?: throw IllegalArgumentException("bad hour in --time")
            val m = parts[1].toIntOrNull()?.takeIf { it in 0..59 }
                ?: throw IllegalArgumentException("bad minute in --time")
            return h to m
        }

        private fun parseRepeat(s: String?): ScheduledRepeatMode = when (s?.lowercase()) {
            null, "once" -> ScheduledRepeatMode.ONCE
            "daily" -> ScheduledRepeatMode.DAILY
            "weekdays" -> ScheduledRepeatMode.WEEKDAYS
            "custom" -> ScheduledRepeatMode.CUSTOM
            else -> throw IllegalArgumentException("--repeat must be once|daily|weekdays|custom")
        }

        private fun parseDays(s: String): Set<Int> {
            val map = mapOf(
                "sun" to Calendar.SUNDAY, "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY,
                "wed" to Calendar.WEDNESDAY, "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY,
                "sat" to Calendar.SATURDAY,
            )
            return s.split(",").mapNotNull { map[it.trim().lowercase().take(3)] }.toSet()
        }

        /**
         * [T-scheduled-tool-prefill] Prefilled-command flags, named as on iOS
         * (ScheduledOffload.m / ScheduledPresetToolCall.fromCLI) so one
         * instruction works on both platforms:
         *
         *   --shell "<cmd>"            alias --command
         *   --shell-timeout <dur>      alias --command-timeout; 90 / 90s / 2m / 1h,
         *                              1s..1h (the shell tool's own ceiling)
         *   --tool <name> --tool-args '<json>'   the general form; only
         *                              shell_execute (alias shell/sh/bash) today,
         *                              args limited to command / timeout / tool_title
         *
         * The general form exists so another tool can be enabled later by adding
         * it to [PrefilledToolCall.SUPPORTED_TOOLS] without a new flag.
         */
        internal fun parsePrefill(args: OffloadArgs, label: String): PrefilledToolCall? {
            val names = listOf("shell", "command", "shell-timeout", "command-timeout", "tool", "tool-args")
            // A value-less flag (or one whose value starts with '-') parses as
            // a bare flag, which would silently create a task with no prefill.
            for (name in names) {
                if (args.hasFlag(name)) {
                    throw IllegalArgumentException("--$name needs a value (write --$name=<value> if it starts with '-')")
                }
            }
            val command = args.get("shell", "command")
            val timeoutRaw = args.get("shell-timeout", "command-timeout")
            val tool = args.get("tool")
            val toolArgs = args.get("tool-args")
            if (command == null && timeoutRaw == null && tool == null && toolArgs == null) return null
            if (command != null && toolArgs != null) {
                throw IllegalArgumentException("use either --command or --tool/--tool-args, not both")
            }
            val toolName = tool?.let { PrefilledToolCall.canonicalToolName(it) } ?: PrefilledToolCall.SHELL_TOOL
            if (toolName !in PrefilledToolCall.SUPPORTED_TOOLS) {
                throw IllegalArgumentException(
                    "--tool $tool is not supported as a prefilled tool yet; only shell_execute (alias: shell) is",
                )
            }
            var timeoutSec: Int? = timeoutRaw?.let {
                parseDurationSec(it) ?: throw IllegalArgumentException("--command-timeout must be a duration like 90s, 2m, 1h")
            }
            var cmd = command
            var title: String? = null
            if (toolArgs != null) {
                val obj = try {
                    JSONObject(toolArgs)
                } catch (e: Exception) {
                    throw IllegalArgumentException("--tool-args must be a JSON object, e.g. '{\"command\":\"date\"}'")
                }
                val unknown = obj.keys().asSequence().toSet() - setOf("command", "timeout", "tool_title")
                if (unknown.isNotEmpty()) {
                    throw IllegalArgumentException(
                        "--tool-args: unsupported key(s) ${unknown.sorted().joinToString()} for shell_execute (allowed: command, timeout)",
                    )
                }
                cmd = obj.opt("command") as? String
                    ?: throw IllegalArgumentException("--tool-args needs a \"command\" string")
                if (obj.has("timeout")) {
                    val t = obj.opt("timeout") as? Number
                        ?: throw IllegalArgumentException("--tool-args \"timeout\" must be a number of seconds")
                    // [T-android-scheduled-duration-finite] Through a Double
                    // and saturated: Number.toInt() on a Long wraps, so
                    // {"timeout": 4294967356} (2^32 + 60) came out as 60 and
                    // passed the 1..3600 check below.
                    if (timeoutSec == null) timeoutSec = saturatingSeconds(t.toDouble())
                }
                title = obj.optString("tool_title", "").ifBlank { null }
            }
            if (cmd.isNullOrBlank()) throw IllegalArgumentException("--command needs a shell command")
            val t = timeoutSec
            if (t != null && t !in PrefilledToolCall.TIMEOUT_RANGE_SEC) {
                throw IllegalArgumentException("--command-timeout must be between 1s and 1h")
            }
            val call = PrefilledToolCall.shell(cmd, title = title ?: label.ifBlank { null }, timeoutSec = t)
            call.validationError()?.let { throw IllegalArgumentException(it) }
            return call
        }

        /** `90`, `90s`, `2m`, `1h` → seconds; null when unparseable. */
        internal fun parseDurationSec(raw: String): Int? {
            val m = Regex("""^\s*(\d+(?:\.\d+)?)\s*([smh]?)\s*$""").find(raw.lowercase()) ?: return null
            val n = m.groupValues[1].toDoubleOrNull() ?: return null
            val mult = when (m.groupValues[2]) { "m" -> 60.0; "h" -> 3600.0; else -> 1.0 }
            return saturatingSeconds(n * mult)
        }

        /**
         * [T-android-scheduled-duration-finite] Seconds as an Int that never
         * wraps: rounded, then clamped to the Int range, with NaN / ±Inf mapped
         * outside any valid range. Port of iOS 40d74b5df's rule (reject
         * inf / nan / 1e20 before any Int conversion). `Math.round(x).toInt()`
         * truncated the Long, so `--shell-timeout 4294967356` (2^32 + 60)
         * became 60 and was accepted; now it saturates and the 1s..1h range
         * check rejects it.
         */
        internal fun saturatingSeconds(value: Double): Int = when {
            value.isNaN() -> Int.MIN_VALUE
            value >= Int.MAX_VALUE.toDouble() -> Int.MAX_VALUE
            value <= Int.MIN_VALUE.toDouble() -> Int.MIN_VALUE
            else -> Math.round(value).toInt()
        }

        private val HELP = """
            minis-scheduled — manage timed AI tasks (the same tasks as Settings › Scheduled Tasks).

            (no subcommand)  Same as `list`
            list
            create --prompt "..." [--label L]  plus ONE trigger:
                   --after 30m                               once, after a delay (90s, 30m, 2h)
                   --interval 10m [--count N]                every interval (>= 60s), N times (default: until deleted)
                   --time HH:MM [--repeat once|daily|weekdays|custom --days mon,tue,...]
                                                             at a time of day (default once)
                   --trigger on-completion --of <taskId|jobId>
                                                             once, when that task or agent job finishes;
                                                             a task finishes when its schedule is over
                                                             (its one fire, or its last --count fire;
                                                             a daily task or endless --interval never);
                                                             {{result}} in --prompt becomes its result
                   [--trigger once|loop|cron|on-completion]  optional; inferred from the flags above
                   [--target follow-up|new|rerun --session <id> --message <id>]
                   [--model entry:<id>|group:<id>|<modelId>] [--thinking off|low|medium|high|xhigh|max|ultra]
                   [--start YYYY-MM-DD] [--end YYYY-MM-DD] [--disabled]
                   [--command "<shell command>" [--command-timeout 2m]]   prefilled first step
                   [--tool shell_execute --tool-args '{"command":"..."}']
            delete  --id <taskId>
            enable  --id <taskId>          re-enabling an --after/--interval task starts it over
            disable --id <taskId>
            run     --id <taskId>          fire immediately, off-schedule (not counted by --count)

            TARGET (default: follow-up when run inside a chat, new otherwise):
              follow-up  each fire is a new turn IN THIS CHAT (or in --session <id>); the
                         result comes back to the conversation that created the task
              new        each fire opens a separate new chat "Scheduled · <label>"; the
                         result does NOT appear in this conversation — only when the user
                         asks for that
              rerun      re-run a chat (--session) from a chosen user message (--message,
                         prompt ignored)

            MODEL (--model, --target new only): entry:<id> or a bare entry id pins that exact
              entry, group:<id> a model group (the forms `minis-config get session.primaryModel`
              prints; ids from `minis-config get models`). A bare model id is accepted only
              when one visible entry serves it; otherwise create fails and lists the entry ids.

            OUTPUT: `create` answers with a `delivery` line saying where every fire will
            land (this chat, another chat, a new chat, a rerun). Check it; if it is not
            what the user asked for, `delete --id` and create again with the right --target.

            PREFILLED TOOL CALL (--command, alias --shell):
              The shell command runs as the FIRST step of every fire, before the model is
              asked anything; the model receives its real output as an already-completed
              tool call and only has to act on it — one model request per fire instead of
              two. Use it when the command is the same every time (fetch a page, check a
              status, run a script). --command-timeout (alias --shell-timeout) takes 90s,
              2m, 1h (1s..1h; default 15m). --tool/--tool-args is the general form; only
              shell_execute is supported today (args: command, timeout).
              A failing command is not a failed task: the model sees its output and exit
              status and decides what to do (explain, retry, or report).

            Tasks are kept by the system alarm service: they fire even when Minis is in the
            background or closed, and survive a reboot (a force-stop pauses them until the
            app is next opened).

            EXAMPLES:
              minis-scheduled create --prompt "Remind me to drink water" --after 30m
              minis-scheduled create --prompt "Check the build status" --interval 10m --count 6 --label build-watch
              minis-scheduled create --time 08:00 --repeat daily --label Weather \
                --command "curl -s 'wttr.in/Shanghai?format=3'" \
                --prompt "Summarise today's weather and say whether I need an umbrella."
              minis-scheduled create --trigger on-completion --of build-watch \
                --prompt "The build watch ended with: {{result}}. Tell me if anything failed."
        """.trimIndent()
    }
}
