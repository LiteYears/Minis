package com.openminis.app.backup

import android.content.Context
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentRoster
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.BreakIterator
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.json.JSONObject

/**
 * Content-addressed blob writer for the staging directory (§2), mirroring
 * `src/ios/Agent/Backup/BackupBlobStore.swift`.
 *
 * Two jobs:
 *   1. **Dedup by content.** The same bytes referenced from several sessions —
 *      or from both a message attachment and a session's file tree — are stored
 *      once under their SHA-256. Callers don't coordinate; they offer files and
 *      get told which hash they landed on.
 *   2. **Enforce the §3.4 size cap.** Over-limit files are refused as
 *      [Outcome.SkippedTooLarge] and the caller records a tombstone instead of
 *      a blob. The cap is checked before any hashing or copying, so a 2GB file
 *      costs a `length()`, not a read.
 *
 * @param maxFileBytes null = unlimited (the §3.4 default).
 */
class BackupBlobStore(stagingRoot: File, private val maxFileBytes: Long?) {

    /**
     * `blobs/<first 2 hex>/<sha256>` — the two-level fan-out keeps any single
     * directory small enough to stay fast on huge packages.
     */
    private val blobsRoot = File(stagingRoot, "blobs")

    /**
     * The configured cap, for `manifest.limits.max_file_bytes`. Recorded even
     * when nothing was skipped, so a reader can tell "unlimited" apart from
     * "capped, but nothing exceeded it".
     */
    val maxFileBytesForManifest: Long? get() = maxFileBytes

    /** Hashes already written this run — cheap in-memory dedup. */
    private val seen = mutableSetOf<String>()

    private val _blobIndex = mutableListOf<BackupBlobIndexEntry>()
    val blobIndex: List<BackupBlobIndexEntry> get() = _blobIndex

    var totalBytesStored = 0L
        private set
    var skippedFiles = 0
        private set
    var skippedBytes = 0L
        private set

    /** Paths dropped by the cap, surfaced on the completion screen (§3.4). */
    val skippedPaths = mutableListOf<SkippedPath>()

    data class SkippedPath(val path: String, val size: Long)

    sealed interface Outcome {
        data class Stored(val sha256: String, val size: Long) : Outcome
        /** Same content already in the package; no new bytes written. */
        data class Duplicate(val sha256: String, val size: Long) : Outcome
        /** Excluded by the §3.4 cap. Caller must write a tombstone. */
        data class SkippedTooLarge(val size: Long) : Outcome
    }

    /**
     * Rebuild in-memory state from a staging tree left by an interrupted run.
     *
     * `blobs.index.jsonl` is only written at the very end of an export, so a
     * resumed run cannot read its own prior index back. `files.index.jsonl` IS
     * written incrementally and every stored entry carries the digest, so the
     * index is reconstructed from there and cross-checked against the blobs
     * actually on disk.
     *
     * Entries whose blob is missing (the run died between writing the index
     * line and copying the content) are deliberately NOT re-registered: the
     * digest stays out of [seen], so the next pass re-stores that file. That is
     * what stops a resumed package from referencing content it doesn't contain.
     */
    fun rehydrateFromFileIndex(fileIndex: File) {
        if (!fileIndex.exists()) return
        var restored = 0
        fileIndex.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val entry = runCatching {
                    BackupFormat.json.decodeFromString(BackupFileIndexEntry.serializer(), line)
                }.getOrNull() ?: continue
                val sha = entry.sha256 ?: continue
                if (entry.skipped != null || entry.isDirectory == true) continue
                if (!seen.add(sha)) continue
                val blob = blobFile(sha)
                if (!blob.exists()) {
                    seen.remove(sha)
                    continue
                }
                totalBytesStored += entry.size
                _blobIndex.add(
                    BackupBlobIndexEntry(sha, entry.size, entry.path, null, mimeType(entry.path))
                )
                restored += 1
            }
        }
        if (restored > 0) {
            AppLogger.info(TAG, "[Backup] resume: recovered $restored blob(s) from a previous attempt")
        }
    }

    /**
     * Offer a file on disk to the package. [logicalPath] is only recorded in
     * the index — the blob's location is derived purely from its content hash.
     */
    fun addFile(file: File, logicalPath: String, sessionId: String? = null): Outcome {
        val size = file.length()

        // Cap check FIRST — before hashing or copying. A skipped 2GB file must
        // cost a stat, not a full read.
        if (maxFileBytes != null && size > maxFileBytes) {
            skippedFiles += 1
            skippedBytes += size
            skippedPaths.add(SkippedPath(logicalPath, size))
            AppLogger.info(TAG, "[Backup] skip (size cap) $logicalPath size=$size > $maxFileBytes")
            return Outcome.SkippedTooLarge(size)
        }

        val digest = file.inputStream().use { sha256(it) }
        if (digest in seen) return Outcome.Duplicate(digest, size)

        val dest = blobFile(digest)
        dest.parentFile?.mkdirs()
        // An identical digest means identical content, so an existing file is
        // already correct — but `seen` missing it means a prior interrupted run
        // left it. Replace to be safe.
        if (dest.exists()) dest.delete()
        file.copyTo(dest, overwrite = true)

        seen.add(digest)
        totalBytesStored += size
        _blobIndex.add(BackupBlobIndexEntry(digest, size, logicalPath, sessionId, mimeType(logicalPath)))
        return Outcome.Stored(digest, size)
    }

    /** Offer in-memory bytes (generated content that never hits disk). */
    fun addBytes(data: ByteArray, logicalPath: String, sessionId: String? = null): Outcome {
        val size = data.size.toLong()
        if (maxFileBytes != null && size > maxFileBytes) {
            skippedFiles += 1
            skippedBytes += size
            skippedPaths.add(SkippedPath(logicalPath, size))
            return Outcome.SkippedTooLarge(size)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(data).toHex()
        if (digest in seen) return Outcome.Duplicate(digest, size)

        val dest = blobFile(digest)
        dest.parentFile?.mkdirs()
        dest.writeBytes(data)

        seen.add(digest)
        totalBytesStored += size
        _blobIndex.add(BackupBlobIndexEntry(digest, size, logicalPath, sessionId, mimeType(logicalPath)))
        return Outcome.Stored(digest, size)
    }

    private fun blobFile(digest: String) = File(File(blobsRoot, digest.take(2)), digest)

    companion object {
        private const val TAG = "Backup"

        /**
         * Streaming SHA-256 — never loads the file into memory. Media offloads
         * run to hundreds of MB, so this reads in 1MB chunks regardless of size.
         */
        fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
            return digest.digest().toHex()
        }

        fun sha256OfFile(file: File): String = file.inputStream().buffered().use { sha256(it) }

        private fun ByteArray.toHex(): String =
            joinToString("") { "%02x".format(it) }

        fun mimeType(path: String): String? =
            when (path.substringAfterLast('.', "").lowercase()) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "heic" -> "image/heic"
                "pdf" -> "application/pdf"
                "txt", "log" -> "text/plain"
                "md" -> "text/markdown"
                "json" -> "application/json"
                "zip" -> "application/zip"
                "mp4", "m4v" -> "video/mp4"
                "mov" -> "video/quicktime"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "wav" -> "audio/wav"
                else -> null
            }
    }
}

// ─── Merged from BackupCredentialRestore.kt ──────────────────────────────────────────────────

/**
 * [T-backup-restore-semantics / B01] Which credentials a restore writes over
 * the ones already on this device. Owner decision 2026-10-15, the same on iOS:
 *
 *  - API-key class ([Kind.API_KEY]: `apiKey`, and `manualOAuthToken` - a
 *    pasted bearer that never refreshes, so it has no "expired" state of its
 *    own): the backup's value replaces the device's.
 *  - OAuth class ([Kind.OAUTH]: `oauthToken` and the sign-in's `oauthEmail` /
 *    `oauthGcpProject`): the device's is kept unless the device's sign-in is
 *    already marked as needing a new login. That mark is the app's existing
 *    one ([com.openminis.app.auth.OAuthManager.needsReauth]); it is set only
 *    when the token endpoint REJECTED a refresh, so an unmarked sign-in is not
 *    proven valid - a network failure never marks it.
 *  - Nothing on the device: the backup's value is written (as before).
 *  - [T-backup-oauth-login-bundle] The three OAuth slots are ONE login, as on
 *    iOS (BackupCredentialPolicy.plan, replaceLogin): it is replaced when the
 *    device has no OAuth token or its sign-in needs a new login. Decided per
 *    slot, a signed-out device (logout drops the token but leaves the email
 *    and GCP project) took the backup's token next to its own stale email and
 *    project - Gemini Code Assist then called with one account's token and
 *    another's project.
 *
 * Returns whether anything was written; an identical value counts as kept.
 */
object BackupCredentialRestore {
    enum class Kind { API_KEY, OAUTH }

    enum class Slot(val kind: Kind) {
        API_KEY(Kind.API_KEY),
        MANUAL_OAUTH_TOKEN(Kind.API_KEY),
        OAUTH_TOKEN(Kind.OAUTH),
        OAUTH_EMAIL(Kind.OAUTH),
        OAUTH_GCP_PROJECT(Kind.OAUTH),
    }

    interface Store {
        fun supports(slot: Slot): Boolean
        fun read(slot: Slot): String?
        fun write(slot: Slot, value: String)
        fun oauthNeedsReauth(): Boolean
    }

    fun shouldWrite(slot: Slot, local: String?, backup: String, replaceLogin: Boolean): Boolean {
        if (local.isNullOrEmpty()) return true
        if (local == backup) return false
        return when (slot.kind) {
            Kind.API_KEY -> true
            Kind.OAUTH -> replaceLogin
        }
    }

    fun restore(backup: Map<Slot, String>, store: Store): Boolean {
        // Read the mark ONCE, before anything is written: a new token bundle
        // lapses it, and a written manual bearer suppresses it, either of which
        // would make the rest of an expired sign-in look valid.
        val hasOAuth = backup.keys.any { it.kind == Kind.OAUTH }
        val needsReauth = hasOAuth && store.oauthNeedsReauth()
        // [T-backup-oauth-login-bundle] Also read before any write.
        val replaceLogin = hasOAuth && (needsReauth || store.read(Slot.OAUTH_TOKEN).isNullOrEmpty())
        var wrote = false
        for (slot in Slot.entries) {
            val value = backup[slot] ?: continue
            if (!store.supports(slot)) continue
            if (!shouldWrite(slot, store.read(slot), value, slot.kind == Kind.OAUTH && replaceLogin)) continue
            store.write(slot, value)
            wrote = true
        }
        return wrote
    }
}

// ─── Merged from BackupCrypto.kt ──────────────────────────────────────────────────

// java.util.Base64 rather than android.util.Base64 (minSdk 26 has it): this
// layer is pure wire format with no framework dependency, so it stays testable
// under plain JVM unit tests — the module sets `unitTests.isReturnDefaultValues`,
// which would make android.util.Base64 silently return null and turn every
// cross-platform vector assertion into a false pass.

/**
 * Passphrase-based encryption for `.minisbak` packages
 * (docs/backup-restore-design.md §5, scheme `minisbak-enc/1`).
 *
 * This is the Android column of the §5.2.1 parameter table, implemented
 * against `src/ios/Agent/Backup/BackupCrypto.swift`. Every value here is wire
 * format — a deviation makes packages mutually unreadable:
 *
 *   - KDF: PBKDF2-HMAC-SHA256, 600 000 iterations, 16-byte salt, 256-bit KEK.
 *   - Subkeys: HKDF-SHA256 (empty salt — identical to CryptoKit's no-salt
 *     form, since HMAC pads both an empty key and 32 zero bytes to the same
 *     block) with infos `minisbak/{data,secrets,mac,verify}`.
 *   - Cipher: AES-256-GCM, 12-byte random nonce, 128-bit tag, wire layout per
 *     segment = `UInt32 BE length ‖ nonce ‖ ciphertext ‖ tag` (the length
 *     covers nonce+ct+tag, matching CryptoKit's `sealed.combined`).
 *   - Segments: 4 MiB plaintext each, AAD = UTF-8 `"<path>#<segment>"` —
 *     binds each segment to its member path AND index, so members can't be
 *     renamed to impersonate each other and segments can't be reordered.
 *   - Member magic: ASCII `MBK1`.
 *
 * Why PBKDF2 and not Argon2id: neither platform ships Argon2 in its standard
 * library, and §5.2.1 rules that dual-platform standard-library availability
 * beats single-platform optimality. `kdf.alg` keeps both decodable by
 * construction; an unknown alg is refused loudly, never silently substituted
 * (a silent fallback derives a different key and masquerades as "wrong
 * passphrase").
 */
object BackupCrypto {

    const val SCHEME = "minisbak-enc/1"
    const val PBKDF2_ITERATIONS = 600_000
    const val SALT_BYTES = 16

    /** 4 MiB plaintext per independently sealed segment (§5.3). */
    const val SEGMENT_SIZE = 4 * 1024 * 1024

    /** `MBK1` — identifies an encrypted member before any JSON parse attempt. */
    val MAGIC = byteArrayOf(0x4D, 0x42, 0x4B, 0x31)

    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    class CryptoException(message: String) : Exception(message)
    class WrongPassphraseException : Exception("Incorrect passphrase.")
    class ManifestTamperedException :
        Exception("The backup's manifest failed authentication — it may have been modified.")
    class CorruptMemberException(path: String) :
        Exception("Encrypted content is damaged or was tampered with: $path")
    class UnsupportedKDFException(alg: String) :
        Exception("This backup uses an unsupported key-derivation algorithm ($alg). Please update the app.")

    // MARK: - Key hierarchy (§5.2)

    /**
     * The derived key set. Lives only for the duration of an export/import;
     * call [destroy] when finished — the passphrase itself is never persisted.
     */
    class Keys internal constructor(kek: ByteArray) {
        /** Everything under `data/` and `blobs/`. */
        val dataKey: ByteArray = hkdfSha256(kek, "minisbak/data")
        /**
         * `secrets.json` only — the separate subkey is what makes "strip the
         * credentials from this package" a file removal, not a re-encrypt.
         */
        val secretsKey: ByteArray = hkdfSha256(kek, "minisbak/secrets")
        /** Authenticates manifest.json. */
        val macKey: ByteArray = hkdfSha256(kek, "minisbak/mac")
        /** Answers "is this passphrase right?" without touching any payload. */
        val verifierKey: ByteArray = hkdfSha256(kek, "minisbak/verify")

        init {
            kek.fill(0)
        }

        /**
         * `HMAC(verifier_key, "minisbak-v1")` truncated to 16 bytes, base64
         * (§5.2). Written to the manifest at export; recomputed at import so a
         * wrong passphrase fails instantly instead of surfacing as a decrypt
         * error halfway through a multi-GB restore.
         */
        val verifier: String
            get() {
                val mac = hmacSha256(verifierKey, "minisbak-v1".toByteArray(Charsets.UTF_8))
                return Base64.getEncoder().encodeToString(mac.copyOf(16))
            }

        fun destroy() {
            dataKey.fill(0); secretsKey.fill(0); macKey.fill(0); verifierKey.fill(0)
        }
    }

    // MARK: - Derivation

    fun makeSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    /**
     * Derive the key set from a passphrase. Deliberately synchronous and slow —
     * 600k PBKDF2 rounds are the point. Call off the main thread.
     */
    fun deriveKeys(passphrase: String, kdf: BackupManifest.Encryption.KDF): Keys {
        val salt = try {
            Base64.getDecoder().decode(kdf.salt)
        } catch (e: IllegalArgumentException) {
            throw CorruptMemberException("manifest.encryption.kdf.salt")
        }
        return when (kdf.alg) {
            "pbkdf2-hmac-sha256" -> {
                val iterations = kdf.iterations ?: PBKDF2_ITERATIONS
                Keys(pbkdf2(passphrase, salt, iterations))
            }
            // "argon2id" would come from a future build that vendors Argon2;
            // refusing loudly is correct — see the header comment.
            else -> throw UnsupportedKDFException(kdf.alg)
        }
    }

    /** The KDF descriptor to write into a new package. */
    fun currentKDF(salt: ByteArray) = BackupManifest.Encryption.KDF(
        alg = "pbkdf2-hmac-sha256",
        salt = Base64.getEncoder().encodeToString(salt),
        iterations = PBKDF2_ITERATIONS,
    )

    private fun pbkdf2(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        try {
            return factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * HKDF-SHA256, RFC 5869, empty salt, single-block expand (32 bytes out).
     * Matches CryptoKit's `HKDF<SHA256>.deriveKey(inputKeyMaterial:info:)`.
     */
    private fun hkdfSha256(ikm: ByteArray, info: String): ByteArray {
        val prk = hmacSha256(ByteArray(0), ikm) // extract, salt = empty
        return hmacSha256(prk, info.toByteArray(Charsets.UTF_8) + byteArrayOf(0x01))
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // HMAC forbids a zero-length key in JCA; pad to the block-size
        // equivalent, which HMAC's own key padding makes identical.
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    // MARK: - Member encryption (§5.3)

    /**
     * Encrypt one package member, streaming through 4 MiB segments.
     *
     * Layout: `MBK1` then, per segment, `UInt32 big-endian length` followed by
     * `nonce ‖ ciphertext ‖ tag`. A zero-length file writes zero segments; the
     * magic alone marks it as encrypted.
     */
    fun encryptFile(source: File, destination: File, key: ByteArray, path: String) {
        source.inputStream().buffered().use { input ->
            destination.outputStream().buffered().use { output ->
                output.write(MAGIC)
                var index = 0
                while (true) {
                    val chunk = readUpTo(input, SEGMENT_SIZE) ?: break
                    val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"),
                        GCMParameterSpec(TAG_BITS, nonce))
                    cipher.updateAAD(aad(path, index))
                    val sealed = cipher.doFinal(chunk)
                    val combined = nonce + sealed
                    output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                        .putInt(combined.size).array())
                    output.write(combined)
                    index += 1
                }
            }
        }
    }

    fun decryptFile(source: File, destination: File, key: ByteArray, path: String) {
        source.inputStream().buffered().use { input ->
            val header = ByteArray(MAGIC.size)
            if (!readFully(input, header) || !header.contentEquals(MAGIC)) {
                throw CorruptMemberException(path)
            }
            destination.outputStream().buffered().use { output ->
                decryptStream(input, output, key, path)
            }
        }
    }

    /** Decrypt a member whose `MBK1` magic has already been consumed. */
    fun decryptStream(input: InputStream, output: OutputStream, key: ByteArray, path: String) {
        var index = 0
        while (true) {
            val lenBytes = ByteArray(4)
            val first = input.read()
            if (first < 0) break // clean EOF at a segment boundary
            lenBytes[0] = first.toByte()
            if (!readFully(input, lenBytes, offset = 1)) throw CorruptMemberException(path)
            val length = ByteBuffer.wrap(lenBytes).order(ByteOrder.BIG_ENDIAN).int
            if (length < NONCE_BYTES + TAG_BITS / 8 || length > SEGMENT_SIZE + NONCE_BYTES + TAG_BITS / 8 + 64) {
                throw CorruptMemberException(path)
            }
            val body = ByteArray(length)
            if (!readFully(input, body)) throw CorruptMemberException(path)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
                    GCMParameterSpec(TAG_BITS, body, 0, NONCE_BYTES))
                cipher.updateAAD(aad(path, index))
                output.write(cipher.doFinal(body, NONCE_BYTES, body.size - NONCE_BYTES))
            } catch (e: Exception) {
                // An auth failure here is indistinguishable from a wrong key,
                // but the caller checks the verifier first, so by this point
                // the passphrase is known-good and this really is damage.
                throw CorruptMemberException(path)
            }
            index += 1
        }
    }

    private fun aad(path: String, segment: Int): ByteArray =
        "$path#$segment".toByteArray(Charsets.UTF_8)

    // MARK: - Manifest authentication (§5.3)

    /**
     * MAC over the RAW BYTES of manifest.json exactly as written to the
     * package — stored in the sidecar member `manifest.mac`, which both
     * platforms' readers prefer. (The embedded `manifest_mac` field is a
     * Swift-re-encoding MAC that Android neither writes nor can verify;
     * raw bytes never go through a decoder, so unknown fields cost nothing
     * and the sidecar stays valid across versions in both directions.)
     */
    fun manifestMac(rawBytes: ByteArray, key: ByteArray): String =
        Base64.getEncoder().encodeToString(hmacSha256(key, rawBytes))

    fun verifyManifestMac(rawBytes: ByteArray, expected: String, key: ByteArray) {
        val actual = Base64.getDecoder().decode(manifestMac(rawBytes, key))
        val exp = try {
            Base64.getDecoder().decode(expected.trim())
        } catch (e: IllegalArgumentException) {
            throw ManifestTamperedException()
        }
        // MessageDigest.isEqual is constant-time since API 16.
        if (!MessageDigest.isEqual(actual, exp)) throw ManifestTamperedException()
    }

    /** Constant-time verifier comparison, so a wrong passphrase can't be timed. */
    fun verifierMatches(stored: String, keys: Keys): Boolean {
        val a = try {
            Base64.getDecoder().decode(stored)
        } catch (e: IllegalArgumentException) {
            return false
        }
        val b = Base64.getDecoder().decode(keys.verifier)
        return MessageDigest.isEqual(a, b)
    }

    // MARK: - Stream helpers

    /** Read up to [max] bytes; null at clean EOF, never a zero-length array. */
    private fun readUpTo(input: InputStream, max: Int): ByteArray? {
        val buf = ByteArray(max)
        var read = 0
        while (read < max) {
            val n = input.read(buf, read, max - read)
            if (n < 0) break
            read += n
        }
        if (read == 0) return null
        return if (read == max) buf else buf.copyOf(read)
    }

    private fun readFully(input: InputStream, buf: ByteArray, offset: Int = 0): Boolean {
        var read = offset
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }
}

// ─── Merged from BackupFileTreeExporter.kt ──────────────────────────────────────────────────

/**
 * Walks a directory tree into the package: content into the blob store,
 * structure into `files.index.jsonl`. Mirrors
 * `src/ios/Agent/Backup/BackupFileTreeExporter.swift`.
 *
 * Shared by every category that carries files — Chats' `<sid>/` directories,
 * Shared Files, and Skills' attached files — so the §3.4 size cap and the dedup
 * behaviour are identical everywhere by construction, rather than by three
 * exporters agreeing to behave the same way.
 */
class BackupFileTreeExporter(
    private val blobStore: BackupBlobStore,
    private val fileIndex: BackupFileIndexWriter,
    /**
     * Snapshot cut-off. Files modified after this instant are not part of this
     * backup's view of the data.
     *
     * **Excludes only, never includes.** mtime is not always trustworthy: a
     * file restored from another backup, synced down by a provider, or copied
     * in can carry an mtime unrelated to when this device saw it, and a clock
     * change moves every comparison at once. So the rule is asymmetric on
     * purpose — a file is dropped ONLY when its mtime is clearly after the
     * cut-off, and anything unreadable or ambiguous is INCLUDED. Wrongly
     * including a slightly-too-new file costs a little package size; wrongly
     * excluding one silently loses data from the user's backup, which is the
     * failure this feature exists to prevent.
     */
    private val snapshotAtMillis: Long = Long.MAX_VALUE,
    /**
     * [T-android-backup-webdav-deadline] Stop. Polled every
     * [CANCEL_CHECK_EVERY] entries: a file boundary is a safe place to stop
     * (each file is stored and indexed whole), and checking every 32 keeps
     * Stop prompt without costing anything measurable in a walk over tens of
     * thousands of files. Mirrors iOS c82fe8eb4.
     */
    private val isCancelled: () -> Boolean = { false },
) {

    data class Result(
        var filesIncluded: Int = 0,
        var filesSkipped: Int = 0,
        var bytesIncluded: Long = 0,
        var directories: Int = 0,
        /**
         * Files excluded because they were modified after the snapshot cut-off.
         * NOT a gap in the backup — they are outside this backup's point in
         * time, so they get no tombstone.
         */
        var filesAfterSnapshot: Int = 0,
    )

    /**
     * Export everything under [root] into the package beneath [logicalPrefix].
     *
     * A missing root is not an error: a user with no skills, or a session with
     * no files, is the normal case and must not fail the whole backup.
     */
    fun export(
        root: File,
        logicalPrefix: String,
        category: BackupCategory,
        sessionId: String? = null,
    ): Result {
        val result = Result()
        if (!root.exists() || !root.isDirectory) return result

        val base = root.canonicalFile
        var scanned = 0
        for (entry in base.walkTopDown()) {
            if (++scanned % CANCEL_CHECK_EVERY == 0 && isCancelled()) {
                throw kotlinx.coroutines.CancellationException("Backup stopped")
            }
            // walkTopDown yields the root itself first; it is the prefix, not
            // an entry inside the package.
            if (entry == base) continue

            val rel = entry.relativeTo(base).path.replace(File.separatorChar, '/')
            if (rel.isEmpty()) continue
            val logicalPath = "$logicalPrefix/$rel"

            // Never package a backup artifact — see isBackupArtifact.
            if (isBackupArtifact(rel)) continue

            if (entry.isDirectory) {
                // Empty directories would otherwise vanish, since nothing
                // references them.
                if (entry.listFiles()?.isEmpty() == true) {
                    fileIndex.write(BackupFileIndexEntry.directory(logicalPath, category))
                    result.directories += 1
                }
                continue
            }
            if (!entry.isFile) continue // sockets, fifos, dangling symlinks

            // Snapshot cut-off. `> 0` guards against a filesystem reporting 0
            // for an unknown mtime, which must not read as "ancient".
            val modified = entry.lastModified()
            if (modified > 0 && modified > snapshotAtMillis) {
                result.filesAfterSnapshot += 1
                continue
            }

            when (val outcome = addFile(entry, logicalPath, sessionId, category, result)) {
                is BackupBlobStore.Outcome.Stored -> {
                    fileIndex.write(
                        BackupFileIndexEntry.file(logicalPath, outcome.size, outcome.sha256, category)
                    )
                    result.filesIncluded += 1
                    result.bytesIncluded += outcome.size
                }
                is BackupBlobStore.Outcome.Duplicate -> {
                    // The bytes are already in the package under this digest;
                    // only the tree needs a second entry pointing at them.
                    fileIndex.write(
                        BackupFileIndexEntry.file(logicalPath, outcome.size, outcome.sha256, category)
                    )
                    result.filesIncluded += 1
                }
                is BackupBlobStore.Outcome.SkippedTooLarge -> {
                    // §3.4: tombstone, never a silent drop.
                    fileIndex.write(
                        BackupFileIndexEntry.sizeSkipped(logicalPath, outcome.size, category)
                    )
                    result.filesSkipped += 1
                }
                null -> {
                    // Unreadable — permissions, a file deleted mid-walk, or I/O
                    // error. Recorded as a tombstone so the gap is visible on
                    // restore instead of the file simply not being there.
                    fileIndex.write(
                        BackupFileIndexEntry.unreadable(logicalPath, entry.length(), category)
                    )
                    result.filesSkipped += 1
                }
            }
        }
        return result
    }

    private fun addFile(
        file: File,
        logicalPath: String,
        sessionId: String?,
        category: BackupCategory,
        result: Result,
    ): BackupBlobStore.Outcome? = try {
        blobStore.addFile(file, logicalPath, sessionId)
    } catch (e: Exception) {
        // One bad file must not sink an entire backup — the tombstone above
        // keeps the gap visible.
        AppLogger.error(TAG, "[Backup] unreadable file $logicalPath: ${e.message}")
        null
    }

    /**
     * True for anything that is itself a backup artifact — a delivered
     * `.minisbak`, or the directory they are delivered into.
     *
     * Without this a backup sweeps up the previous backup as "user data",
     * nesting packages inside packages until the size runs away. Matched on the
     * leading path component as well as the extension, so a whole `Backups/`
     * tree is skipped in one step rather than file by file.
     */
    private fun isBackupArtifact(rel: String): Boolean {
        if (rel.endsWith(".${BackupFormat.FILE_EXTENSION}")) return true
        return rel.substringBefore('/') == BACKUPS_DIR_NAME
    }

    companion object {
        internal const val CANCEL_CHECK_EVERY = 32

        private const val TAG = "Backup"

        /** Sibling of `shared`/`skills`/`memory`, matching iOS's delivery directory. */
        const val BACKUPS_DIR_NAME = "Backups"
    }
}

/**
 * Append-only writer for `files.index.jsonl`.
 *
 * Flushed after every record, deliberately. This file is what a resumed run
 * reads back to discover which blobs an interrupted attempt already stored
 * ([BackupBlobStore.rehydrateFromFileIndex]) — and the interruption it has to
 * survive is the process dying, which is precisely when buffered-but-unwritten
 * lines are lost. A buffer here would make resume silently forget the tail of
 * the previous attempt and re-copy blobs that are already staged.
 *
 * The cost is one small write per file rather than per 8KB; next to hashing and
 * copying the file's contents, that is noise.
 */
class BackupFileIndexWriter(file: File) : AutoCloseable {
    private val stream = file.also { it.parentFile?.mkdirs() }.outputStream()

    var written = 0
        private set

    fun write(entry: BackupFileIndexEntry) {
        val line = BackupFormat.json.encodeToString(BackupFileIndexEntry.serializer(), entry) + "\n"
        stream.write(line.toByteArray(Charsets.UTF_8))
        stream.flush()
        written += 1
    }

    override fun close() {
        stream.flush()
        stream.close()
    }
}

// ─── Merged from BackupFormat.kt ──────────────────────────────────────────────────

/**
 * [T-android-envvar-iso8601-wire] A `createdAt`-style timestamp that WRITES an
 * ISO-8601 UTC string (matching iOS `JSONEncoder.dateEncodingStrategy =
 * .iso8601`) but READS either form:
 *   - a JSON string → parsed as ISO-8601 (iOS packages, and new Android ones);
 *   - a JSON number → treated as epoch milliseconds (old Android packages,
 *     which wrote `createdAt` as a bare Long — that number is exactly what iOS
 *     rejected with "isn't in the correct format").
 *
 * The in-memory value stays epoch-millis (Long) so callers are unchanged; only
 * the wire representation moves to a string. Kept in BackupFormat so both the
 * exporter and importer share one definition.
 */
object Iso8601MillisSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Iso8601Millis", PrimitiveKind.STRING)

    private fun fmt() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeString(fmt().format(value))
    }

    override fun deserialize(decoder: Decoder): Long {
        // Tolerate both a JSON string (ISO-8601) and a JSON number (legacy
        // epoch millis). Use JsonDecoder to peek at the actual element type so
        // an old numeric package doesn't throw.
        val jd = decoder as? JsonDecoder
            ?: return runCatching { fmt().parse(decoder.decodeString())?.time }.getOrNull() ?: 0L
        val el = jd.decodeJsonElement()
        val prim = el as? JsonPrimitive ?: return 0L
        prim.longOrNull?.let { return it } // legacy numeric epoch millis
        val s = prim.content
        return runCatching { fmt().parse(s)?.time }.getOrNull() ?: 0L
    }
}

/**
 * [T-android-provider-iso8601-wire] Nullable peer of [Iso8601MillisSerializer]
 * for optional timestamps (e.g. `ModelEntry.userModifiedAt`, which iOS decodes
 * as `Date?`). null round-trips as JSON null; otherwise identical rules —
 * writes ISO-8601, reads ISO strings or legacy epoch-millis numbers.
 */
object Iso8601MillisNullableSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Iso8601MillisNullable", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) encoder.encodeNull()
        else Iso8601MillisSerializer.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): Long? {
        val jd = decoder as? JsonDecoder ?: return Iso8601MillisSerializer.deserialize(decoder)
        val el = jd.decodeJsonElement()
        if (el is kotlinx.serialization.json.JsonNull) return null
        val prim = el as? JsonPrimitive ?: return null
        prim.longOrNull?.let { return it }
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(prim.content)?.time
        }.getOrNull()
    }
}

/**
 * On-the-wire types for the `.minisbak` backup package.
 *
 * Spec: `docs/backup-restore-design.md` §2 / §2.1. This is the FORMAT layer,
 * mirroring `src/ios/Agent/Backup/BackupFormat.swift` field-for-field —
 * §5.4 explicitly forbids inventing a second format, and the manifest keys
 * are sealed by `manifest_mac`, so every name here is wire-frozen.
 *
 * Two rules from §2.2 shape every type:
 *   - Unknown fields are ignored, missing fields get defaults. Every field
 *     that isn't structurally required has a default or is nullable; the
 *     shared [BackupFormat.json] instance is configured tolerant.
 *   - JSONL records carry `t` (type) and `v` (record version) so a reader can
 *     dispatch by type and migrate per record.
 */
object BackupFormat {
    /** Format major version. An unrecognised value must refuse the package. */
    const val CURRENT = "minisbak/1"

    /** File extension registered to the app for "open to import". */
    const val FILE_EXTENSION = "minisbak"

    /**
     * MIME type for a `.minisbak` package.
     *
     * Matches the `application/x-minisbak` iOS declares for the
     * `com.openminis.app.minisbak` UTI (src/ios/Info.plist), so both platforms
     * describe the same artefact. Used with SAF's CreateDocument, where the
     * MIME type drives the extension the picker appends: the generic
     * `application/octet-stream` made it save the package as `.bin`.
     */
    const val MIME_TYPE = "application/x-minisbak"

    /**
     * Cap for a single JSONL shard (§2). Beyond this the writer rolls over to
     * `messages-0002.jsonl` etc., so the importer never holds one giant file
     * in memory.
     */
    const val MAX_SHARD_BYTES = 64 * 1024 * 1024

    /**
     * Tolerant parser per §2.2 rule 2: unknown keys ignored, defaults filled,
     * `null` never emitted for absent optionals (iOS omits them entirely and
     * its hand-written decoders treat explicit null and absence the same).
     */
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * [T-port-backup-secrets-defaults] Encoder for `secrets.json`: [json] plus
     * `encodeDefaults`, so `v` and every section are written even when empty.
     *
     * [json] omits default values, so a phone with no MCP OAuth tokens (and,
     * with only one category selected, no provider keys or no env values)
     * wrote `{"envVars":[...]}`. iOS builds before e0fafc8cd (every 1.15
     * build shipped before 2026-10-01) decode BackupSecrets with the
     * synthesized Decodable, which requires `v`, `providers`, `envVars` and
     * `mcpOAuth`; the whole file failed to decode there and an Android -> iOS
     * restore silently lost every provider key and env var value.
     * `explicitNulls = false` still applies, so absent optional credentials
     * stay absent rather than becoming `null`, as iOS writes them.
     */
    private val secretsJson = Json(json) { encodeDefaults = true }

    fun encodeSecrets(secrets: BackupSecrets): String =
        secretsJson.encodeToString(BackupSecrets.serializer(), secrets)
}

/**
 * User-facing backup categories (§3). [key] is the manifest key and the
 * on-disk directory name, so renaming one is a format change.
 */
enum class BackupCategory(val key: String) {
    CHATS("chats"),
    SHARED_FILES("shared_files"),
    SKILLS("skills"),
    MEMORY("memory"),
    PROVIDERS("providers"),
    MCP_SERVERS("mcp_servers"),
    VOICE_CORRECTIONS("voice_corrections"),

    /**
     * Shell environment variables. The variable list (id/key/note) travels as
     * `data/env_vars.json` metadata; the VALUES ride in `secrets.json` under
     * the credential policy, exactly like iOS. Wire key must stay
     * `environment_variables` to match the iOS `BackupCategory` raw value so a
     * cross-platform package is decodable on both sides.
     */
    ENVIRONMENT_VARIABLES("environment_variables");

    /**
     * Categories that stream file trees through the blob store — the ones the
     * §3.4 size cap applies to.
     */
    val carriesFileTree: Boolean
        get() = when (this) {
            CHATS, SHARED_FILES, SKILLS -> true
            MEMORY, PROVIDERS, MCP_SERVERS, VOICE_CORRECTIONS,
            ENVIRONMENT_VARIABLES -> false
        }

    companion object {
        fun fromKey(key: String): BackupCategory? = entries.firstOrNull { it.key == key }

        /**
         * Categories a NEW backup may include. Voice Corrections is excluded on
         * the export side only (feature not mature enough to back up, 2026-08-15
         * decision) — the enum case stays because `voice_corrections` sections
         * exist in already-written packages and must remain decodable.
         */
        val backupable: List<BackupCategory>
            get() = entries.filter { it != VOICE_CORRECTIONS }
    }
}

/**
 * `manifest.json` — ALWAYS plaintext, even in an encrypted package (§2.1), so
 * the user can see what a package holds before being asked for a passphrase.
 *
 * Dates travel as ISO-8601 strings, exactly the bytes iOS's
 * `JSONEncoder.dateEncodingStrategy = .iso8601` writes. They are kept as
 * strings here rather than parsed eagerly: the manifest is MAC'd over raw
 * bytes, and a parse-reformat cycle is precisely what the sidecar MAC exists
 * to avoid.
 */
@Serializable
data class BackupManifest(
    val format: String = BackupFormat.CURRENT,
    @SerialName("created_at") val createdAt: String = "",
    /** Data cut-off instant; null on packages from older writers. */
    @SerialName("snapshot_at") val snapshotAt: String? = null,
    val app: AppInfo = AppInfo(),
    /** Display-only. Deliberately NOT the deviceId (§3.1). */
    @SerialName("device_name") val deviceName: String = "Unknown device",
    @SerialName("backup_id") val backupId: String = "",
    val categories: Map<String, CategoryStat> = emptyMap(),
    val limits: Limits = Limits(),
    /** Absent on unencrypted packages. */
    val encryption: Encryption? = null,
    /**
     * Path → SHA-256 of the bytes as stored in the package. For an encrypted
     * package that is the CIPHERTEXT hash, so integrity can be checked before
     * the passphrase is known (§5.3).
     */
    val integrity: Map<String, String> = emptyMap(),
    /**
     * Legacy embedded MAC over a canonical re-encoding of the manifest.
     * Android never writes it (Swift's re-encoding cannot be reproduced
     * byte-exactly here); the authoritative MAC is the raw-bytes sidecar
     * member `manifest.mac`, which both platforms' readers prefer.
     */
    @SerialName("manifest_mac") val manifestMac: String? = null,
) {
    @Serializable
    data class AppInfo(
        val platform: String = "unknown",
        val version: String = "?",
        val build: String = "?",
    )

    /** Per-category counters shown in the picker before anything is decrypted. */
    @Serializable
    data class CategoryStat(
        /**
         * The count in the UNIT THE USER THINKS IN for this category: messages
         * for chats, skills for skills, servers for MCP, provider instances for
         * providers. Never a file count for a category whose user-facing unit
         * is not "file" — a skills tree of 569 files across 12 skills reports
         * `entries = 12`, and the 569 goes in [files].
         */
        val entries: Int = 0,
        val bytes: Long = 0,
        val encrypted: Boolean = false,
        /** Chats only: split `entries` so the UI can say "342 messages + 1204 files". */
        val messages: Int? = null,
        /**
         * Files carried in this category's tree, when that differs from
         * [entries]. Chats and skills both set it. Optional so a package
         * written before it existed still decodes (the UI just omits the
         * detail).
         */
        val files: Int? = null,
        /** Providers only: false marks a "shared copy" with credentials stripped (§3.3). */
        @SerialName("includes_credentials") val includesCredentials: Boolean? = null,
        /**
         * Providers only: custom thinking rules travelling in the same
         * category.
         *
         * They ship inside `providers` because they have no category of their
         * own (adding one would change the cross-platform category set), but
         * they must NOT inflate `entries` — a user with 8 providers and 1 rule
         * saw "9 providers". Counted separately so the UI can name them.
         */
        @SerialName("thinking_rules") val thinkingRules: Int? = null,
    )

    /** §3.4. `maxFileBytes == null` means unlimited — the default. */
    @Serializable
    data class Limits(
        @SerialName("max_file_bytes") val maxFileBytes: Long? = null,
        @SerialName("skipped_files") val skippedFiles: Int = 0,
        @SerialName("skipped_bytes") val skippedBytes: Long = 0,
    )

    @Serializable
    data class Encryption(
        val scheme: String = "",
        val kdf: KDF,
        val verifier: String = "",
    ) {
        @Serializable
        data class KDF(
            /**
             * `alg` and `salt` genuinely have no safe default — a wrong guess
             * would derive the wrong key and surface as "wrong passphrase",
             * so these two stay required (decode throws when absent).
             */
            val alg: String,
            val salt: String,
            @SerialName("m_kib") val mKib: Int? = null,
            val t: Int? = null,
            val p: Int? = null,
            val iterations: Int? = null,
        )
    }
}

/**
 * One line of `files.index.jsonl` — the directory-tree index (§2).
 *
 * Exists ALONGSIDE `blobs.index.jsonl`: blobs.index is a content map and
 * structurally cannot express an empty directory, a skipped path, or the tree
 * shape itself — exactly what a restore needs to rebuild `<sid>/`.
 */
@Serializable
data class BackupFileIndexEntry(
    /** Package-relative logical path, e.g. `chats/<sid>/offloads/out.zip`. */
    val path: String,
    /** Original byte size — recorded even for skipped entries. */
    val size: Long = 0,
    /** null for directories and for size-skipped tombstones. */
    val sha256: String? = null,
    val category: String = "",
    /**
     * Why the content is absent from the package. Present ONLY on tombstones:
     * `"size"` (§3.4 cap), `"not_downloaded"` (cloud placeholder), or
     * `"unreadable"`.
     */
    val skipped: String? = null,
    val isDirectory: Boolean? = null,
) {
    companion object {
        fun file(path: String, size: Long, sha256: String, category: BackupCategory) =
            BackupFileIndexEntry(path, size, sha256, category.key)

        /**
         * A file the size cap excluded — no sha256 (the bytes aren't in the
         * package) but path and true size stay visible (§3.4 "tombstone,
         * don't silently drop").
         */
        fun sizeSkipped(path: String, size: Long, category: BackupCategory) =
            BackupFileIndexEntry(path, size, null, category.key, skipped = "size")

        fun unreadable(path: String, size: Long, category: BackupCategory) =
            BackupFileIndexEntry(path, size, null, category.key, skipped = "unreadable")

        /** Empty directories would otherwise vanish, since nothing references them. */
        fun directory(path: String, category: BackupCategory) =
            BackupFileIndexEntry(path, 0, null, category.key, isDirectory = true)
    }
}

/** One line of `blobs.index.jsonl` — content-addressed payload map (§2). */
@Serializable
data class BackupBlobIndexEntry(
    val sha256: String,
    val size: Long,
    /** First logical path this content was seen at; duplicates only add a files.index line. */
    val path: String,
    val sessionId: String? = null,
    val mime: String? = null,
)

/**
 * Generic JSONL record envelope, matching iOS `BackupRecordEnvelope` — `t` is a
 * type tag, `d` is the payload. Used by `thinking_rules.jsonl`.
 */
@Serializable
data class BackupRecordEnvelope<T>(
    val t: String,
    val d: T,
)

/**
 * One user-authored thinking rule on the wire (§ providers).
 *
 * IMPORTANT: field names ARE the JSON keys (camelCase) — iOS's
 * `BackupThinkingRuleRecord` uses Swift's synthesized `CodingKeys`, i.e. NO
 * snake_case remapping, unlike the manifest types. Do NOT add `@SerialName`
 * snake_case here or a cross-platform package stops decoding.
 *
 * `createdAt` / `updatedAt` are ISO-8601 strings (the bytes iOS's
 * `JSONEncoder.dateEncodingStrategy = .iso8601` writes). Kept as strings: the
 * Android `provider_thinking_rules` table has no time columns, so these are
 * carried for round-trip fidelity but not used for LWW on import (see
 * BackupImporter.importThinkingRules — Android does an id-keyed replace because
 * the local schema has no updated_at to compare against).
 */
@Serializable
data class BackupThinkingRuleRecord(
    val id: String,
    val instanceId: String,
    val sortOrder: Int = 0,
    val scopeKind: String = "allModels",
    val scopePattern: String? = null,
    /** Opaque ThinkingWireFormat JSON — carried verbatim, never parsed. */
    val wireFormatJson: String = "{}",
    val echoField: String? = null,
    val echoTiming: String? = null,
    val label: String = "",
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/**
 * Env-var metadata line of `data/env_vars.json` (an array). Mirrors iOS
 * `EnvVarEntry`'s exported shape: identity + note, NEVER the value. The value
 * lives in `secrets.json` (see [BackupSecrets.EnvVarSecret]) under the
 * credential policy.
 */
@Serializable
data class BackupEnvVarMeta(
    val id: String,
    val key: String,
    val note: String = "",
    /**
     * [T-android-envvar-iso8601-wire] Epoch millis in memory, but WRITTEN as an
     * ISO-8601 string on the wire so iOS `EnvVarEntry` (which decodes
     * `createdAt: Date` with `.iso8601`) can read Android packages. Reads both
     * ISO strings and legacy Android numeric epoch millis. Cosmetic on import
     * (Android restore keys env vars by name, not createdAt).
     */
    @Serializable(with = Iso8601MillisSerializer::class)
    val createdAt: Long = 0,
    /**
     * [T-envvar-disable] Same name and default on both platforms. Missing
     * (packages from before the feature, either platform) means enabled; with
     * the default encoder an enabled variable is written without it, a
     * disabled one as `"enabled": false`. Applied only to variables the
     * restore creates - an existing variable keeps its own state.
     */
    val enabled: Boolean = true,
)

/**
 * `secrets.json` — the ONE credential-carrying member, encrypted with the
 * separate `secretsKey` subkey so "strip credentials" is a file deletion.
 * Every value here is base64 of the raw UTF-8 secret (stage-3a: base64, not
 * yet encrypted; the package encryption pass seals the whole file).
 *
 * Schema is wire-frozen to iOS `BackupSecrets` (BackupSecrets.swift) so a
 * package restores across platforms.
 */
@Serializable
data class BackupSecrets(
    val v: Int = 1,
    val providers: List<ProviderSecret> = emptyList(),
    val envVars: List<EnvVarSecret> = emptyList(),
    val mcpOAuth: List<MCPOAuthSecret> = emptyList(),
) {
    @Serializable
    data class ProviderSecret(
        val instanceId: String,
        val label: String? = null,
        val providerType: String? = null,
        val apiKey: String? = null,
        val manualOAuthToken: String? = null,
        val oauthToken: String? = null,
        val oauthEmail: String? = null,
        val oauthGcpProject: String? = null,
    ) {
        val isEmpty: Boolean
            get() = apiKey == null && manualOAuthToken == null &&
                oauthToken == null && oauthEmail == null && oauthGcpProject == null
    }

    /** base64 of the value's UTF-8 bytes. */
    @Serializable
    data class EnvVarSecret(val name: String, val value: String)

    @Serializable
    data class MCPOAuthSecret(
        val serverId: String,
        val token: String,
        val clientSecret: String? = null,
    )
}

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

// ─── Merged from BackupHistory.kt ──────────────────────────────────────────────────

/**
 * [T-android-backup-history] Persistent record of backup runs. The Android peer
 * of `src/ios/Agent/Backup/BackupHistory.swift`.
 *
 * ## Why this exists
 *
 * The screen used to show a transient "Backup ready" card: it reflected only
 * the LAST run, held nothing once the user navigated away, and — the part that
 * actually mattered — never said which destinations the package reached. A
 * delivery that failed to one server out of three surfaced as a single red
 * line of text and then vanished, so "did last night's backup actually land on
 * the NAS?" was unanswerable.
 *
 * A record is written when a run starts and updated as it progresses, so an
 * interrupted run still leaves evidence rather than disappearing.
 *
 * ## Storage
 *
 * One JSON file in filesDir (NOT cacheDir — the system may evict the cache, and
 * a history that silently empties itself is worse than none). Records older
 * than [RETENTION_MS] are pruned on load: a heavy week can't push out a record
 * the user still cares about, and an idle month leaves nothing stale behind.
 * Same 30-day window as iOS.
 *
 * Readers only ever see the in-memory list; the file is replaced atomically
 * (temp file + rename, like iOS `.atomic`) on a background writer.
 */
class BackupHistory internal constructor(
    /** `filesDir/backup-history`; a parameter so JVM tests can use a temp dir. */
    private val dir: File,
    /** Where the file is written — off the caller's thread and off the cache lock. */
    private val writer: Executor = WRITER,
    /** [T-android-backup-log-sanitize] Applied once to each record's log on first load. */
    private val sanitize: (List<LogEntry>) -> List<LogEntry> = BackupLogRules::sanitize,
) {

    /**
     * Terminal state of a run.
     *
     * [COMPLETED_WITH_ISSUES] is the one that carries its weight: a run that
     * produced a package but couldn't reach a destination, or skipped files at
     * the size cap. Collapsing it into either "succeeded" or "failed" is what
     * made partial delivery invisible before.
     */
    @Serializable
    enum class Status { RUNNING, SUCCEEDED, COMPLETED_WITH_ISSUES, FAILED }

    /** One line of run progress, kept so a finished run can be inspected. */
    @Serializable
    data class LogEntry(
        val at: Long,
        val message: String,
        /** Rendered as a problem (destination failure, skipped file). */
        val isProblem: Boolean = false,
        /**
         * [T-android-backup-progress-log] A live progress line ("Saving to X…
         * 42%") that the NEXT line replaces instead of following — iOS
         * `LogEntry.isTransient`. See [BackupLogRules.append].
         *
         * Defaulted, so records written before this field existed decode with
         * `false` (kotlinx fills a missing key from the default).
         */
        val isTransient: Boolean = false,
    )

    /** Per-destination delivery outcome — the thing the old UI never showed. */
    @Serializable
    data class DestinationOutcome(
        val name: String,
        val succeeded: Boolean,
        val detail: String? = null,
        /**
         * Backend tag (`smb`, `webdav`, `sftp`, `s3`, …) and the folder the
         * package was written to, so a row can say WHERE the backup went
         * rather than only which saved destination was picked. The saved name
         * alone ("HomeLab") does not tell someone opening a months-old record
         * how to find the file by hand.
         *
         * Both optional: records written before these existed decode with null
         * and render exactly as they did before.
         */
        val kind: String? = null,
        val path: String? = null,
    )

    /** A file left out of the package, with the reason. */
    @Serializable
    data class SkippedEntry(
        val path: String,
        val size: Long,
        val reason: String? = null,
    ) {
        val fileName: String get() = path.substringAfterLast('/')
    }

    @Serializable
    data class Record(
        val id: String = UUID.randomUUID().toString(),
        val backupId: String,
        val startedAt: Long,
        val finishedAt: Long? = null,
        val status: Status,
        val categories: List<String> = emptyList(),
        val encrypted: Boolean = false,
        val totalBytes: Long = 0,
        val skippedFiles: Int = 0,
        val skippedEntries: List<SkippedEntry> = emptyList(),
        val packageName: String? = null,
        val destinations: List<DestinationOutcome> = emptyList(),
        val log: List<LogEntry> = emptyList(),
        val errorMessage: String? = null,
    ) {
        val durationMillis: Long? get() = finishedAt?.let { it - startedAt }
        val hasProblems: Boolean
            get() = destinations.any { !it.succeeded } || skippedFiles > 0
    }

    // MARK: - Storage

    private val storeFile: File
        get() = File(dir.apply { mkdirs() }, STORE_NAME)

    @Volatile
    private var cache: List<Record>? = null

    /**
     * [T-android-backup-history-atomic] Bumped under this object's lock with
     * every change to [cache]; the writer records the version it put on disk,
     * so an older snapshot can never land after a newer one.
     */
    private var version = 0L
    private val writeLock = Any()
    private var writtenVersion = 0L
    private val writeQueued = AtomicBoolean(false)

    /** Test seam: runs inside the disk step, under [writeLock] only. */
    @Volatile
    internal var writeHook: (() -> Unit)? = null

    /** Newest first. Expired records are dropped (and rewritten) on first read. */
    @Synchronized
    fun records(): List<Record> {
        cache?.let { return it }
        val (raw, loaded) = load()
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val kept = loaded.filter { it.startedAt >= cutoff }.sortedByDescending { it.startedAt }
        val cleaned = clean(kept)
        if (cleaned !== kept && raw != null) keepBackup(raw)
        if (cleaned !== kept || kept.size != loaded.size) persist(cleaned)
        cache = cleaned
        return cleaned
    }

    /**
     * [T-android-backup-log-sanitize] Records written before progress lines
     * replaced each other can hold tens of thousands of log lines (16,827 in
     * the KILO repro), and the detail screen froze on them for as long as the
     * record was kept. Only `log` changes. Returns [records] itself when
     * nothing changed — or when cleaning failed, which must never cost the
     * history.
     */
    private fun clean(records: List<Record>): List<Record> = runCatching {
        var changed = false
        val out = records.map { r ->
            val log = sanitize(r.log)
            if (log == r.log) r else { changed = true; r.copy(log = log) }
        }
        if (changed) out else records
    }.getOrElse {
        AppLogger.error(TAG, "[Backup] history log cleanup failed, kept as is: ${it.message}")
        records
    }

    /**
     * The file as it was before cleanup, as `records.json.bak` (the latest
     * only). Queued on [writer] ahead of the rewrite, so it lands first.
     */
    private fun keepBackup(raw: String) {
        runCatching {
            writer.execute {
                runCatching {
                    val tmp = File(dir, "$STORE_NAME.bak.tmp")
                    tmp.writeText(raw)
                    Files.move(
                        tmp.toPath(), File(dir, "$STORE_NAME.bak").toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                    )
                    AppLogger.info(TAG, "[Backup] history log cleaned; original kept as $STORE_NAME.bak")
                }.onFailure { AppLogger.error(TAG, "[Backup] history .bak write failed: ${it.message}") }
            }
        }.onFailure { AppLogger.error(TAG, "[Backup] history .bak not scheduled: ${it.message}") }
    }

    /**
     * Read the file once. A file that does not decode — truncated by a write
     * the process did not survive, say — is renamed aside rather than
     * silently treated as "no history", so what it held can still be looked
     * at; the history then starts empty.
     */
    private fun load(): Pair<String?, List<Record>> {
        val f = storeFile
        if (!f.exists()) return null to emptyList()
        val text = runCatching { f.readText() }.getOrElse {
            AppLogger.error(TAG, "[Backup] history read failed: ${it.message}")
            return null to emptyList()
        }
        return text to runCatching { JSON.decodeFromString(RECORD_LIST, text) }.getOrElse { e ->
            val aside = File(dir, "$STORE_NAME.corrupt-${System.currentTimeMillis()}")
            val moved = f.renameTo(aside)
            AppLogger.error(
                TAG,
                "[Backup] history file does not decode (${e.javaClass.simpleName}, ${text.length} chars); " +
                    "kept as ${aside.name} (moved=$moved), starting empty",
            )
            emptyList()
        }
    }

    /**
     * Update the cache now; write the file later, on [writer].
     *
     * Serializing and writing used to happen right here, under the lock every
     * reader takes — so the backup page's `records()` waited for a delivery
     * thread's disk write every few seconds. Only the cache swap is locked now.
     */
    @Synchronized
    private fun persist(list: List<Record>) {
        cache = list
        version++
        if (writeQueued.compareAndSet(false, true)) {
            runCatching { writer.execute { writeLatest() } }.onFailure {
                writeQueued.set(false)
                AppLogger.error(TAG, "[Backup] history write not scheduled: ${it.message}")
            }
        }
    }

    /**
     * Write whatever the cache holds NOW, if it is newer than the file: to a
     * temp file in the same directory, synced, then renamed over the store,
     * so a reader — or the next launch after a crash — sees the old file or
     * the new one, never half of one. A failure is logged only: history is a
     * convenience and must never fail a backup.
     */
    private fun writeLatest() = synchronized(writeLock) {
        // Cleared before the snapshot is taken: a change after this point
        // queues another pass, so it cannot be missed.
        writeQueued.set(false)
        val (snapshot, v) = synchronized(this) { cache to version }
        if (snapshot == null || v <= writtenVersion) return@synchronized
        runCatching {
            writeHook?.invoke()
            val bytes = JSON.encodeToString(RECORD_LIST, snapshot).toByteArray(Charsets.UTF_8)
            val tmp = File(dir.apply { mkdirs() }, "$STORE_NAME.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            Files.move(
                tmp.toPath(), storeFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
            writtenVersion = v
        }.onFailure { AppLogger.error(TAG, "[Backup] history write failed: ${it.message}") }
    }

    /** Write the latest state now, on the calling thread (tests). */
    internal fun flushWrites() = writeLatest()

    // MARK: - Mutation

    /** Insert a record, or replace the one carrying the same id. */
    @Synchronized
    fun upsert(record: Record) {
        val cur = records().toMutableList()
        val i = cur.indexOfFirst { it.id == record.id }
        if (i >= 0) cur[i] = record else cur.add(0, record)
        persist(cur.sortedByDescending { it.startedAt })
    }

    @Synchronized
    fun remove(id: String) {
        persist(records().filterNot { it.id == id })
    }

    /**
     * [T-android-backup-dest-edit] Follow a destination rename into the
     * records. An outcome is matched back to its destination BY NAME — the
     * "open destination" link and "delete with files" both look it up that way
     * — so without this every record written before the rename would report
     * the destination as removed while it is still configured under a new name.
     */
    @Synchronized
    fun renameDestination(from: String, to: String) {
        val cur = records()
        if (cur.none { r -> r.destinations.any { it.name == from } }) return
        persist(
            cur.map { r ->
                if (r.destinations.none { it.name == from }) r
                else r.copy(destinations = r.destinations.map { if (it.name == from) it.copy(name = to) else it })
            },
        )
    }

    @Synchronized
    fun clear() {
        persist(emptyList())
    }

    /**
     * Mark any record still flagged RUNNING as failed.
     *
     * A run that was killed — process death, a crash, the user swiping the app
     * away — leaves its record RUNNING forever, which would render as a
     * perpetual spinner in the list. Called at construction so the state is
     * reconciled before the UI ever sees it.
     */
    @Synchronized
    private fun reconcileInterrupted() {
        val cur = records()
        if (cur.none { it.status == Status.RUNNING }) return
        persist(
            cur.map {
                if (it.status != Status.RUNNING) it
                else it.copy(
                    status = Status.FAILED,
                    finishedAt = it.finishedAt ?: System.currentTimeMillis(),
                    errorMessage = it.errorMessage ?: INTERRUPTED_MARKER,
                )
            },
        )
    }

    companion object {
        private const val TAG = "Backup"

        /** 30 days, matching iOS `BackupHistory.retention`. */
        const val RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000

        /**
         * Recorded as the error for a run that never reported a terminal state.
         * Not localized on purpose: it is a stored marker, and the UI maps it to
         * a translated string at render time.
         */
        const val INTERRUPTED_MARKER = "interrupted"

        private const val STORE_NAME = "records.json"

        /** One writer thread for the process: writes are coalesced, never parallel. */
        private val WRITER: Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "BackupHistoryWriter").apply { isDaemon = true }
        }

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val RECORD_LIST = ListSerializer(Record.serializer())

        @Volatile
        private var instance: BackupHistory? = null

        fun get(context: Context): BackupHistory =
            instance ?: synchronized(this) {
                instance ?: BackupHistory(File(context.applicationContext.filesDir, "backup-history"))
                    .also { it.reconcileInterrupted(); instance = it }
            }
    }
}

// ─── Merged from BackupJsonlWriter.kt ──────────────────────────────────────────────────

/**
 * Append-only JSONL writer with automatic shard rollover (§2), mirroring
 * `src/ios/Agent/Backup/BackupJSONLWriter.swift`.
 *
 * Every record is one self-contained line, so the importer can parse
 * line-by-line and skip a bad record without losing the file (§2.2 rule 3).
 * Writes go straight to a stream — nothing accumulates in memory, which is
 * what lets a 100k-message export stay flat.
 *
 * @param baseName `messages` → `messages.jsonl`, then `messages-0002.jsonl`.
 */
class BackupJsonlWriter(
    private val directory: File,
    private val baseName: String,
    private val maxShardBytes: Int = BackupFormat.MAX_SHARD_BYTES,
) : AutoCloseable {

    private var stream: BufferedOutputStream? = null
    private var currentBytes = 0
    private var shardIndex = 1

    var writtenRecords = 0
        private set
    var totalBytes = 0L
        private set
    val shardPaths = mutableListOf<String>()

    /** True when nothing was ever written, so the caller can avoid emitting an empty category. */
    val isEmpty: Boolean get() = writtenRecords == 0

    /**
     * Write one record wrapped in the §2.2 rule-3 envelope: `t` dispatches on
     * the way in, `v` allows per-record migration.
     */
    fun write(type: String, version: Int = 1, payload: JsonElement) {
        val envelope = buildJsonObject {
            put("t", JsonPrimitive(type))
            put("v", JsonPrimitive(version))
            put("d", payload)
        }
        val line = (BackupFormat.json.encodeToString(JsonObject.serializer(), envelope) + "\n")
            .toByteArray(Charsets.UTF_8)
        append(line)
        writtenRecords += 1
    }

    private fun append(data: ByteArray) {
        if (stream == null || currentBytes + data.size > maxShardBytes) rollover()
        val out = stream ?: throw BackupException("no open shard for $baseName")
        out.write(data)
        currentBytes += data.size
        totalBytes += data.size
    }

    private fun rollover() {
        close()
        val name = if (shardIndex == 1) "$baseName.jsonl"
        else String.format("%s-%04d.jsonl", baseName, shardIndex)
        directory.mkdirs()
        stream = BufferedOutputStream(File(directory, name).outputStream())
        currentBytes = 0
        shardIndex += 1
        shardPaths.add(name)
    }

    override fun close() {
        stream?.flush()
        stream?.close()
        stream = null
    }
}

// ─── Merged from BackupLogRules.kt ──────────────────────────────────────────────────

/**
 * [T-android-backup-progress-log] How a run's log grows — the rules of iOS
 * `BackupHistory.log(_:_:isProblem:isTransient:)`.
 *
 * A backup to a local (SAF) folder reported progress once per 8 KB buffer and
 * every report became a log line: a 2.13 GB package left 16,827 lines after 13
 * minutes (2,600 per percent), each one rewriting the whole history file, and
 * the detail screen that composes them froze the app for 32–70 s.
 */
internal object BackupLogRules {

    /**
     * Append [entry] to [log] the way iOS does, returning false when it is
     * dropped:
     * - the same message as the last line is dropped — a long phase reports
     *   the same status repeatedly;
     * - a transient (progress) last line is removed first, so the log keeps
     *   one moving progress row instead of one row per report.
     */
    fun append(log: MutableList<BackupHistory.LogEntry>, entry: BackupHistory.LogEntry): Boolean {
        val last = log.lastOrNull()
        if (last?.message == entry.message) return false
        if (last?.isTransient == true) log.removeAt(log.lastIndex)
        log.add(entry)
        return true
    }

    // -- [T-android-backup-log-sanitize] Cleaning records written before ------

    /** Lines a record keeps; older ones are folded into one note. */
    const val MAX_LOG_LINES = 200

    /**
     * Stored as the note's message and mapped to a translated string when
     * shown — like [BackupHistory.INTERRUPTED_MARKER] — so a record cleaned
     * in one language reads correctly in another.
     */
    const val OMITTED_MARKER_PREFIX = "[omitted-earlier-lines]:"

    private val PROGRESS_LINE = Regex("""^(Saving|Sending) to .+… \d{1,3}%$""")

    private fun isProgress(e: BackupHistory.LogEntry) = e.isTransient || PROGRESS_LINE.matches(e.message)

    /** How many lines a note stands for, or null when [e] is not a note. */
    fun omittedCount(e: BackupHistory.LogEntry): Int? =
        e.message.takeIf { it.startsWith(OMITTED_MARKER_PREFIX) }
            ?.removePrefix(OMITTED_MARKER_PREFIX)?.toIntOrNull()

    /**
     * Clean a log written before progress lines replaced each other:
     * - each run of consecutive progress lines keeps only its last line,
     *   marked transient, as [append] would have left it;
     * - over [MAX_LOG_LINES] lines, only the newest are kept, behind one note
     *   saying how many were left out (an earlier note's count is folded in).
     * Every other line is kept as it is. Cleaning a cleaned log changes nothing.
     */
    fun sanitize(log: List<BackupHistory.LogEntry>): List<BackupHistory.LogEntry> {
        val merged = ArrayList<BackupHistory.LogEntry>(minOf(log.size, 1_024))
        for (e in log) {
            if (!isProgress(e)) {
                merged.add(e)
                continue
            }
            val line = if (e.isTransient) e else e.copy(isTransient = true)
            if (merged.isNotEmpty() && isProgress(merged.last())) merged[merged.lastIndex] = line
            else merged.add(line)
        }
        val earlier = merged.firstOrNull()?.let(::omittedCount)
        val body = if (earlier != null) merged.subList(1, merged.size) else merged
        if (body.size <= MAX_LOG_LINES) return merged
        val dropped = body.size - MAX_LOG_LINES
        val kept = body.subList(dropped, body.size)
        val note = BackupHistory.LogEntry(
            at = kept.first().at,
            message = OMITTED_MARKER_PREFIX + ((earlier ?: 0) + dropped),
        )
        return listOf(note) + kept
    }
}

/**
 * Lets a byte-level progress callback through only when the percentage
 * changed AND at least [minIntervalMs] passed since the last one — the
 * `lastPct` of [BackupRunController.copyWithProgress] plus the 1 s interval of
 * iOS `BackupProgressReporter`.
 */
internal class PercentGate(
    private val minIntervalMs: Long = 1_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastPct = Int.MIN_VALUE
    private var lastAt = 0L

    @Synchronized
    fun admit(pct: Int): Boolean {
        if (pct == lastPct) return false
        val now = clock()
        if (lastPct != Int.MIN_VALUE && now - lastAt < minIntervalMs) return false
        lastPct = pct
        lastAt = now
        return true
    }
}

/**
 * Coalesces history writes to at most one per [intervalMs]: a leading write,
 * then one trailing write scheduled for the end of the interval so the last
 * throttled line still lands (the running row's subtitle IS that line).
 *
 * [close] forces the final write and turns later requests — and a trailing
 * write still in flight — into no-ops, so nothing older can land after it.
 * [write] runs under this object's lock: a trailing write and the final one
 * never interleave.
 */
internal class ThrottledWriter(
    private val intervalMs: Long = 1_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val schedule: (delayMs: Long, block: () -> Unit) -> Unit,
    private val write: () -> Unit,
) {
    private val lock = Any()
    private var lastWriteAt = 0L
    private var written = false
    private var dirty = false
    private var trailingScheduled = false
    private var closed = false

    fun request() = synchronized(lock) {
        if (closed) return
        val now = clock()
        if (!written || now - lastWriteAt >= intervalMs) {
            doWrite(now)
            return
        }
        dirty = true
        if (!trailingScheduled) {
            trailingScheduled = true
            schedule(intervalMs - (now - lastWriteAt)) { trailing() }
        }
    }

    /** The run ended — succeeded, failed, cancelled or stopped. Always writes. */
    fun close() = synchronized(lock) {
        doWrite(clock())
        closed = true
    }

    private fun trailing() = synchronized(lock) {
        trailingScheduled = false
        if (closed || !dirty) return
        doWrite(clock())
    }

    private fun doWrite(now: Long) {
        dirty = false
        written = true
        lastWriteAt = now
        write()
    }
}

// ─── Merged from BackupPackageName.kt ──────────────────────────────────────────────────

/**
 * [T-android-backup-package-name-parity] The one place a `.minisbak` filename
 * is built — a port of iOS `BackupExporter.packageFileName` and its helpers,
 * which must produce the SAME name for the same inputs.
 *
 * Shape: `<device>-<yyyyMMdd>-<sortable-id>[-encrypted].minisbak`, e.g.
 * `Alexs-Pixel-20260817-m08r03g0nz0.minisbak`.
 *
 * Android used to write `backup-<yyyyMMdd-HHmm>-<6 hex of the id>.minisbak`,
 * the shape iOS dropped in [T-backup-package-name-device]. That name carries
 * no device, so packages from several devices in one NAS folder were
 * indistinguishable; an encrypted package looked the same as a plain one until
 * someone tried to open it; and the random id slice could not order two
 * backups taken in the same minute. Nothing parses the name (importers read
 * the manifest), so old packages stay restorable.
 */
object BackupPackageName {

    /**
     * Build the filename. [atMillis] is the run's snapshot instant, not "now",
     * so the date and the id agree and a retried run keeps its name. [zone]
     * only exists for tests; the date is local, as on iOS.
     */
    fun packageFileName(
        backupId: String,
        atMillis: Long,
        deviceName: String,
        encrypted: Boolean = false,
        zone: TimeZone = TimeZone.getDefault(),
    ): String {
        val device = filenameDeviceToken(deviceName)
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = zone }.format(atMillis)
        val suffix = if (encrypted) "-encrypted" else ""
        return "$device-$stamp-${sortableId(backupId, atMillis)}$suffix.${BackupFormat.FILE_EXTENSION}"
    }

    private const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"

    /**
     * 8 characters of Crockford-style Base32 over the millisecond timestamp,
     * then 3 from an FNV-1a hash of [backupId]. The alphabet is monotonic in
     * ASCII, so name order is time order — which is what allows the clock time
     * to be left out of the name. 40 bits of milliseconds last until year
     * 36812; the id part separates two runs in the same millisecond.
     */
    fun sortableId(backupId: String, atMillis: Long): String {
        val ms = atMillis.coerceAtLeast(0L) and 0xFF_FFFF_FFFFL
        var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325, FNV-1a offset basis
        for (b in backupId.toByteArray(Charsets.UTF_8)) {
            h = (h xor (b.toLong() and 0xFF)) * 0x100000001b3L // wraps like Swift's &*
        }
        return encode(ms, 8) + encode(h, 3)
    }

    private fun encode(value: Long, width: Int): String {
        val out = CharArray(width)
        var v = value
        for (i in width - 1 downTo 0) {
            out[i] = ALPHABET[(v and 31).toInt()]
            v = v ushr 5
        }
        return String(out)
    }

    /**
     * A filename-safe ASCII token from a user-controlled device name: ASCII
     * letters and digits kept, apostrophes elided (`Alex's` → `Alexs`), any
     * other run collapsed to one `-`, capped at 24 characters. The full name
     * still goes into the manifest's `device_name`.
     *
     * Walks grapheme clusters, not chars, because that is what a Swift
     * `Character` is: a decomposed `é` (e + U+0301) is one non-ASCII
     * character on iOS, and iterating chars would keep its `e` and give a
     * different token for the same name.
     */
    fun filenameDeviceToken(raw: String, fallback: String = "device"): String {
        val out = StringBuilder()
        var lastWasSeparator = false
        val it = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(raw) }
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val g = raw.substring(start, end)
            val c = g[0]
            when {
                g.length == 1 && (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') -> {
                    out.append(c)
                    lastWasSeparator = false
                }
                g == "'" || g == "’" -> Unit
                out.isNotEmpty() && !lastWasSeparator -> {
                    out.append('-')
                    lastWasSeparator = true
                }
            }
            start = end
            end = it.next()
        }
        var token = out.toString().trimEnd('-')
        if (token.length > 24) token = token.take(24).trimEnd('-')
        return token.ifEmpty { fallback }
    }
}

// ─── Merged from BackupPackageReader.kt ──────────────────────────────────────────────────

/**
 * Read-side entry point for a `.minisbak` package, implementing the §8.1
 * pre-flight order:
 *
 *     manifest (plaintext) → verifier (is the passphrase right?)
 *       → integrity (ciphertext hashes, no passphrase needed)
 *       → manifest MAC (was the plaintext manifest edited?)
 *       → only then decrypt anything
 *
 * The order matters: a wrong passphrase must fail instantly against the
 * 16-byte verifier rather than surfacing as a decrypt error partway through a
 * multi-GB restore, and integrity must be checkable by someone who does not
 * have the passphrase at all (§5.3).
 */
class BackupPackageReader(private val root: File) {

    private val TAG = "Backup"

    /** Present in every package by definition; always plaintext (§2.1). */
    val manifestFile = File(root, "manifest.json")

    /**
     * Raw manifest bytes. Kept as bytes rather than a re-encoding because the
     * sidecar MAC authenticates exactly these bytes — a parse/re-encode cycle
     * would change them and report a good package as tampered.
     */
    fun manifestBytes(): ByteArray {
        if (!manifestFile.exists()) throw BackupException("Package has no manifest.json")
        return manifestFile.readBytes()
    }

    fun readManifest(): BackupManifest {
        val raw = manifestBytes()
        val manifest = try {
            BackupFormat.json.decodeFromString(
                BackupManifest.serializer(), raw.toString(Charsets.UTF_8)
            )
        } catch (e: Exception) {
            throw BackupException("The backup's manifest could not be read.", e)
        }
        // §2.2 rule 1: an unrecognised format major version is refused outright
        // — never best-effort parsed.
        val major = manifest.format.substringBefore('/', "") +
            "/" + manifest.format.substringAfter('/', "").substringBefore('.')
        if (major != BackupFormat.CURRENT) {
            throw BackupException(
                "This backup was created by a newer version of Minis (${manifest.format}). Please update the app."
            )
        }
        return manifest
    }

    val isEncrypted: Boolean
        get() = runCatching { readManifest().encryption != null }.getOrDefault(false)

    /**
     * Downgrade guard — the Android half of iOS's review-S4 check.
     *
     * The manifest is plaintext by design, so anyone can strip its `encryption`
     * block. A reader that trusts it then skips decryption entirely while the
     * members are still `.enc`: nothing parses, every category reports zero
     * imported, and no error is raised — a silent empty restore the user reads
     * as success. If encrypted members are present, the manifest must say so.
     *
     * Call this before importing anything from a package that claims to be
     * unencrypted.
     */
    fun assertNoUndeclaredEncryption(manifest: BackupManifest = readManifest()) {
        if (manifest.encryption != null) return
        if (hasEncryptedMembers()) {
            throw BackupException(
                "This package contains encrypted content but its manifest declares none — " +
                    "it may have been modified."
            )
        }
    }

    private fun hasEncryptedMembers(): Boolean =
        root.walkTopDown().any { it.isFile && it.extension == "enc" }

    /**
     * Derive keys and prove the passphrase before touching any payload.
     *
     * @throws BackupCrypto.WrongPassphraseException when the verifier doesn't match.
     */
    fun unlock(passphrase: String, manifest: BackupManifest = readManifest()): BackupCrypto.Keys {
        val encryption = manifest.encryption
            ?: throw BackupException("This package is not encrypted.")
        if (encryption.scheme != BackupCrypto.SCHEME) {
            throw BackupException(
                "This backup uses an unsupported encryption scheme (${encryption.scheme}). Please update the app."
            )
        }
        val keys = BackupCrypto.deriveKeys(passphrase, encryption.kdf)
        if (!BackupCrypto.verifierMatches(encryption.verifier, keys)) {
            keys.destroy()
            throw BackupCrypto.WrongPassphraseException()
        }
        verifyManifestAuthenticity(manifest, keys)
        return keys
    }

    /**
     * Authenticate the plaintext manifest, so an attacker can't delete the
     * `providers` entry to hide that credentials are present, or downgrade the
     * KDF parameters (§5.3).
     *
     * Prefers the `manifest.mac` sidecar — it MACs the file's exact bytes, so
     * it stays valid when a reader meets a manifest field it doesn't know. The
     * embedded `manifest_mac` field MACs a Swift re-encoding of the decoded
     * struct, which this platform cannot reproduce byte-exactly; when only
     * that form is present the check is skipped rather than failed, because a
     * false "tampered" on a good package is the worse outcome and integrity
     * hashes still cover every member.
     */
    private fun verifyManifestAuthenticity(manifest: BackupManifest, keys: BackupCrypto.Keys) {
        val sidecar = File(root, "manifest.mac")
        if (sidecar.exists()) {
            BackupCrypto.verifyManifestMac(manifestBytes(), sidecar.readText(), keys.macKey)
            return
        }
        if (manifest.manifestMac != null) {
            AppLogger.info(
                TAG,
                "[Backup] package carries only the legacy embedded manifest_mac; " +
                    "skipping MAC check (integrity hashes still apply)"
            )
        }
    }

    /**
     * Verify every member listed in `manifest.integrity` against its stored
     * bytes. Runs WITHOUT the passphrase: the hashes are over ciphertext (§5.3).
     *
     * Returns the paths that failed, rather than throwing on the first one, so
     * the restore UI can tell the user which parts of a damaged package are
     * still usable — a partial restore beats an all-or-nothing refusal.
     */
    fun verifyIntegrity(
        manifest: BackupManifest = readManifest(),
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): List<String> {
        val failures = mutableListOf<String>()
        val total = manifest.integrity.size
        var done = 0
        for ((path, expected) in manifest.integrity) {
            val file = File(root, path)
            if (!file.exists()) {
                failures.add(path)
            } else if (BackupBlobStore.sha256OfFile(file) != expected) {
                failures.add(path)
            }
            done += 1
            onProgress?.invoke(done, total)
        }
        if (failures.isNotEmpty()) {
            AppLogger.error(TAG, "[Backup] integrity check failed for ${failures.size} member(s)")
        }
        return failures
    }

    /**
     * Resolve a logical member path to the file that actually ships, and
     * decrypt it into [scratch] when the package is encrypted.
     *
     * Members gain a `.enc` suffix on encryption, and the AAD binds each
     * segment to the name it ships under (`data/sessions.jsonl.enc`), so the
     * suffix is part of the authenticated path and must be passed through
     * exactly.
     *
     * @return the plaintext file, or null when the member isn't in the package.
     */
    fun materialize(logicalPath: String, keys: BackupCrypto.Keys?, scratch: File): File? {
        val plain = File(root, logicalPath)
        if (plain.exists()) return plain

        val encrypted = File(root, "$logicalPath.enc")
        if (!encrypted.exists()) return null
        val derived = keys ?: throw BackupException(
            "This backup is encrypted — a passphrase is required to read $logicalPath."
        )
        val key = if (logicalPath == "secrets.json") derived.secretsKey else derived.dataKey
        val out = File(scratch, logicalPath).also { it.parentFile?.mkdirs() }
        BackupCrypto.decryptFile(encrypted, out, key, "$logicalPath.enc")
        return out
    }

    /** Members present in the package, logical names with any `.enc` stripped. */
    fun memberNames(): List<String> {
        val base = root.canonicalFile
        return base.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(base).path.replace(File.separatorChar, '/').removeSuffix(".enc") }
            .toList()
    }
}

// ─── Merged from BackupProviderMerge.kt ──────────────────────────────────────────────────

/**
 * [T-backup-restore-semantics / B01] The restore-only merge of a backup's
 * provider_config.json into this device's config. Pure, so the shared scenario
 * file (`src/shared/backup-restore/b01_restore_scenarios.json`) drives it on
 * both platforms. Owner decision 2026-10-15, the same on iOS:
 *
 *  - instances: union by id in the BACKUP's order, local-only appended. A
 *    same-id instance takes the backup's fields (label, base URL, type, ...);
 *    only the cached image-endpoint probe stays local when the mode matches.
 *  - entries: union by `{instanceId}/{modelId}`, non-custom entries included.
 *    On the same key the backup's `overrides` and `isHidden` win, with no
 *    timestamp comparison; `baseModel` (the API's truth) and `absentSince`
 *    stay the device's.
 *  - groups: a same-id group takes the backup's name, strategies, thinking
 *    level, context limits and member order, local-only members appended. A
 *    backup group whose NAME matches a local group with another id is merged
 *    into that local group the same way: one group, the backup's content, the
 *    LOCAL id. The backup's id is recorded in [Result.groupIdRewrites] and
 *    every reference to it is rewritten.
 *  - agent-loop lists: union. Sub agents: union by id / name, as before.
 *  - nothing only on this device is deleted, and the backup's own tombstones
 *    are never applied (Android keeps none).
 *
 * This used to keep the LOCAL copy of every same-id group and existing entry
 * and leave a same-name backup group as a second group, so a restore could
 * not bring back parameters or group contents the user had changed since.
 */
object BackupProviderMerge {

    data class Result(
        val config: ProviderConfig,
        /** Backup group id -> the local id it was merged into (same name). */
        val groupIdRewrites: Map<String, String>,
        /** Entries the backup added / whose parameters it replaced. */
        val entriesAdded: Int = 0,
        val entriesUpdated: Int = 0,
        /** Backup instances this device did not have. */
        val instancesAdded: Set<String> = emptySet(),
        /**
         * Instances already here whose content the backup really changed: their
         * own fields, or one of their model entries (added, or its parameters,
         * hidden state or name replaced). An instance the backup matched
         * exactly is not in here.
         */
        val instancesChanged: Set<String> = emptySet(),
        /** Backup groups this device did not have (by id or by name). */
        val groupsAdded: Int = 0,
        /** Groups already here whose content the backup changed, plus every same-name merge. */
        val groupsChanged: Int = 0,
    )

    /** What the restore page shows for the providers category. */
    data class RestoreCounts(val imported: Int, val updated: Int, val skipped: Int)

    /**
     * [T-b01-restore-counts] The providers category's restore counts, by what
     * really changed. The unit is the package's instances: one not on this
     * device is `imported`; one whose content the backup changed - its fields,
     * one of its model entries, or a credential written over a different value
     * ([credentialsWritten]) - is `updated`, once however many of those
     * happened; one the backup matched exactly is `skipped` ("up to date").
     * Groups are counted on top: a restored group is `imported`, a changed or
     * same-name-merged one `updated`; an unchanged group is not counted.
     *
     * The two false readings this sits between: restoring a package onto the
     * device that produced it used to say "Updated 9" while changing nothing
     * (fix 93cad55ae, iOS), and after B01 a restore that put back a deleted
     * group, a model's name and an API key said "Up to date 27".
     */
    fun counts(result: Result, packageInstanceIds: Collection<String>, credentialsWritten: Collection<String>): RestoreCounts {
        val pkg = packageInstanceIds.toSet()
        val added = result.instancesAdded intersect pkg
        val changed = ((result.instancesChanged + credentialsWritten) intersect pkg) - added
        return RestoreCounts(
            imported = added.size + result.groupsAdded,
            updated = changed.size + result.groupsChanged,
            skipped = pkg.size - added.size - changed.size,
        )
    }

    private fun key(e: ModelEntry) = "${e.providerInstanceId}/${e.baseModel.id}"

    fun merge(local: ProviderConfig, remote: ProviderConfig, log: ((String) -> Unit)? = null): Result {
        // Group members and agent-loop entries may name an entry by its legacy
        // uuid (an older package) or by its composite key. Resolve both sides
        // to the composite key, the id every lookup uses after a save.
        val localIds = local.modelEntries.associate { it.uuid to key(it) }
        val remoteIds = remote.modelEntries.associate { it.uuid to key(it) }
        fun localKey(id: String) = localIds[id] ?: id
        fun remoteKey(id: String) = remoteIds[id] ?: id

        // ── instances ──
        val localInstances = local.instances.associateBy { it.id }
        val instances = mutableListOf<ProviderInstance>()
        val placedInstances = mutableSetOf<String>()
        val instancesAdded = mutableSetOf<String>()
        val instancesChanged = mutableSetOf<String>()
        for (ri in remote.instances) {
            if (!placedInstances.add(ri.id)) continue
            val li = localInstances[ri.id]
            if (li == null) {
                instances += ri
                instancesAdded += ri.id
                continue
            }
            val next = ri.copy(
                // A per-device probe cache, valid only for the mode it probed.
                imageEndpointResolved = if (li.imageEndpointMode == ri.imageEndpointMode) li.imageEndpointResolved else null,
            )
            // createdAt is not content, and an iOS package carries it without
            // the milliseconds: comparing it would call every instance an iOS
            // backup round-tripped "updated".
            if (next.copy(createdAt = li.createdAt) != li) instancesChanged += li.id
            instances += next
        }
        for (li in local.instances) if (placedInstances.add(li.id)) instances += li

        // ── entries ──
        val entries = local.modelEntries.toMutableList()
        val indexByKey = HashMap<String, Int>()
        entries.forEachIndexed { i, e -> indexByKey.putIfAbsent(key(e), i) }
        var added = 0
        var updated = 0
        for (re in remote.modelEntries) {
            val k = key(re)
            val i = indexByKey[k]
            if (i == null) {
                indexByKey[k] = entries.size
                entries += re.copy(uuid = k)
                added++
                if (re.providerInstanceId in localInstances) instancesChanged += re.providerInstanceId
                continue
            }
            val le = entries[i]
            var next = le
            if (le.overrides != re.overrides || le.isHidden != re.isHidden) {
                next = next.copy(
                    overrides = re.overrides,
                    isHidden = re.isHidden,
                    userModifiedAt = re.userModifiedAt ?: le.userModifiedAt,
                )
                updated++
            }
            // A local name that is only the raw id gains the backup's readable
            // one (kept from the previous merge; the API never sent a name).
            val ln = le.baseModel.displayName
            val rn = re.baseModel.displayName
            if ((ln.isBlank() || ln == le.baseModel.id) && rn.isNotBlank() && rn != re.baseModel.id) {
                next = next.copy(baseModel = next.baseModel.copy(displayName = rn))
            }
            if (next != le) instancesChanged += le.providerInstanceId
            entries[i] = next
        }

        // ── groups ──
        val localGroups = local.modelGroups.map { it.copy(memberEntryIds = it.memberEntryIds.map(::localKey).toMutableList()) }
        val remoteGroups = remote.modelGroups.map { it.copy(memberEntryIds = it.memberEntryIds.map(::remoteKey).toMutableList()) }
        val localById = localGroups.associateBy { it.id }
        // Local groups an id match claims are not available to a name match.
        val claimed = remoteGroups.map { it.id }.filter { it in localById }.toMutableSet()
        val rewrites = LinkedHashMap<String, String>()
        val groups = mutableListOf<ModelGroup>()
        val placedGroups = mutableSetOf<String>()
        var groupsAdded = 0
        var groupsChanged = 0
        for (rg in remoteGroups) {
            val target = localById[rg.id]
                ?: localGroups.firstOrNull { it.name == rg.name && it.id !in claimed }
                    ?.also {
                        claimed += it.id
                        rewrites[rg.id] = it.id
                        log?.invoke("[Restore] group '${rg.name}' ${rg.id} merged into local ${it.id} (same name)")
                    }
            val id = target?.id ?: rg.id
            if (!placedGroups.add(id)) continue
            if (target == null) {
                groups += rg
                groupsAdded++
                continue
            }
            val next = backupWins(rg, target)
            // A same-name merge always counts: the backup's group was folded
            // into this one even when its content matched.
            if (next != target || rg.id != target.id) groupsChanged++
            groups += next
        }
        for (lg in localGroups) if (placedGroups.add(lg.id)) groups += lg

        // ── agent loop, sub agents, pointers ──
        fun rw(id: String?) = id?.let { rewrites[it] ?: it }
        val agentEntries = (local.agentLoopModelEntryIds.map(::localKey) + remote.agentLoopModelEntryIds.map(::remoteKey)).distinct()
        val agentGroups = (local.agentLoopGroupIds + remote.agentLoopGroupIds).map { rewrites[it] ?: it }.distinct()

        val subAgents = rewriteSubAgents(local.subAgents, rewrites).toMutableList()
        for (r in rewriteSubAgents(remote.subAgents, rewrites)) {
            val clash = subAgents.any { it.id == r.id || SubAgentRoster.nameKey(it.name) == SubAgentRoster.nameKey(r.name) }
            if (!clash) subAgents += r
        }

        val merged = local.copy(
            instances = instances,
            modelEntries = entries,
            modelGroups = groups,
            subAgents = SubAgentRoster.normalize(subAgents, log).toMutableList(),
            agentLoopModelEntryIds = agentEntries.toMutableList(),
            agentLoopGroupIds = agentGroups.toMutableList(),
            defaultPrimaryGroupId = rw(local.defaultPrimaryGroupId),
            defaultSubGroupId = rw(local.defaultSubGroupId),
            voiceInputGroupId = rw(local.voiceInputGroupId),
            voiceOutputGroupId = rw(local.voiceOutputGroupId),
            visionGroupId = rw(local.visionGroupId),
        )
        return Result(
            merged, rewrites, added, updated,
            instancesAdded = instancesAdded,
            instancesChanged = instancesChanged,
            groupsAdded = groupsAdded,
            groupsChanged = groupsChanged,
        )
    }

    /** The backup's group content under [local]'s id; local-only members follow. */
    private fun backupWins(backup: ModelGroup, local: ModelGroup): ModelGroup {
        val members = backup.memberEntryIds.distinct().toMutableList()
        for (m in local.memberEntryIds) if (m !in members) members += m
        return backup.copy(id = local.id, memberEntryIds = members)
    }

    /**
     * A session's / scheduled task's `modelBinding` with a swallowed group id
     * rewritten. Anything that is not a group binding to a rewritten id comes
     * back as it went in, byte for byte.
     */
    fun rewriteBindingJson(binding: String?, rewrites: Map<String, String>): String? {
        if (binding.isNullOrBlank() || rewrites.isEmpty()) return binding
        val o = runCatching { JSONObject(binding) }.getOrNull() ?: return binding
        if (o.optString("type") != "group") return binding
        val to = rewrites[o.optString("groupId")] ?: return binding
        return o.put("groupId", to).toString()
    }

    /** A scheduled task's legacy `modelId` of the form `group:<id>`. */
    fun rewriteLegacyModelId(modelId: String?, rewrites: Map<String, String>): String? {
        if (modelId == null || !modelId.startsWith("group:")) return modelId
        val to = rewrites[modelId.removePrefix("group:")] ?: return modelId
        return "group:$to"
    }

    fun rewriteSubAgents(agents: List<SubAgentDefinition>, rewrites: Map<String, String>): List<SubAgentDefinition> =
        if (rewrites.isEmpty()) agents else agents.map { a ->
            val to = a.modelGroupId?.let { rewrites[it] }
            if (to == null) a else a.copy(modelGroupId = to)
        }
}

// ─── Merged from BackupProviderWire.kt ──────────────────────────────────────────────────

/**
 * The `data/provider_config.json` a backup carries: how this device's
 * [ProviderConfig] is written into a package.
 *
 * [B01 cross-platform read] [BackupFormat.json] leaves out every field equal
 * to its default, and iOS decodes several of those as REQUIRED. A real Pixel
 * package failed on vphone with `keyNotFound 'isEnabled'`: the whole file
 * unreadable, no provider restored, and behind it the same for
 * `modelEntries[].isHidden` / `isCustom` and the top-level `sessionBindings`;
 * groups without `strategy` were then dropped one by one. So the backup
 * writes those fields even at their default, plus `sessionBindings` (Android
 * keeps no session bindings in its config; iOS requires the key).
 *
 * Only these fields are added, not every default: a blanket `encodeDefaults`
 * would also put Android-only fields and values in front of iOS for the first
 * time, each one a new chance of a type mismatch there.
 *
 * `fallbackStrategy` is written as iOS spells it: iOS's `FallbackStrategy` has
 * `limited` and `always`, Android's `default` is iOS's `limited`, and a value
 * iOS does not know fails its decode just like a missing one. Android reads
 * `limited` back as `default`.
 *
 * Backups only: the device's own config (ProviderRepository) is persisted
 * elsewhere, with its own encoder, and does not change.
 */
object BackupProviderWire {

    /** Fields iOS requires per array element, written even at their default. */
    private val REQUIRED_FIELDS = mapOf(
        "instances" to listOf("isEnabled", "appendV1Suffix"),
        "modelEntries" to listOf("isHidden", "isCustom"),
        "modelGroups" to listOf("strategy", "fallbackStrategy"),
    )

    /** Android's name for the default fallback strategy, and iOS's. */
    private const val ANDROID_DEFAULT_FALLBACK = "default"
    private const val IOS_DEFAULT_FALLBACK = "limited"

    private val withDefaults = Json(BackupFormat.json) { encodeDefaults = true }

    fun encode(config: ProviderConfig): String {
        val slim = BackupFormat.json.encodeToJsonElement(ProviderConfig.serializer(), config).jsonObject
        // The same config with its defaults, so a missing field's value is read
        // from the model rather than restated here.
        val full = withDefaults.encodeToJsonElement(ProviderConfig.serializer(), config).jsonObject

        val out = LinkedHashMap<String, JsonElement>(slim)
        for ((key, fields) in REQUIRED_FIELDS) {
            val fullItems = full[key] as? JsonArray ?: JsonArray(emptyList())
            val slimItems = slim[key] as? JsonArray ?: JsonArray(emptyList())
            out[key] = JsonArray(
                slimItems.mapIndexed { index, item ->
                    val obj = item as? JsonObject ?: return@mapIndexed item
                    val withFields = withFields(obj, fullItems.getOrNull(index) as? JsonObject, fields)
                    if (key == "modelGroups") iosFallback(withFields) else withFields
                },
            )
        }
        if ("sessionBindings" !in out) out["sessionBindings"] = JsonObject(emptyMap())
        return BackupFormat.json.encodeToString(JsonObject.serializer(), JsonObject(out))
    }

    private fun withFields(obj: JsonObject, full: JsonObject?, fields: List<String>): JsonObject {
        if (full == null || fields.all { it in obj }) return obj
        val merged = LinkedHashMap<String, JsonElement>(obj)
        for (field in fields) {
            if (field !in merged) full[field]?.let { merged[field] = it }
        }
        return JsonObject(merged)
    }

    private fun iosFallback(group: JsonObject): JsonObject {
        val value = (group["fallbackStrategy"] as? JsonPrimitive)?.content
        if (value != ANDROID_DEFAULT_FALLBACK) return group
        return JsonObject(group + ("fallbackStrategy" to JsonPrimitive(IOS_DEFAULT_FALLBACK)))
    }
}

// ─── Merged from BackupRecordMapper.kt ──────────────────────────────────────────────────

/**
 * Backup record -> Room entity, and the merge rule that decides whether a
 * restored row may replace the local one.
 *
 * Moved out of [BackupImporter]'s DAO loops unchanged so the rules can be
 * tested directly. They are the error-prone part of a restore — a wrong
 * default or merge comparison silently drops or overwrites user data and a
 * restore still reports success — and while they lived inline next to Room
 * the only available tests pinned source text or kept their own copies.
 * The importer keeps everything that needs the database: looking up the
 * existing row, the parent-session check, transactions and the report.
 */
internal object BackupRecordMapper {

    // -- Merge ----------------------------------------------------------------

    /**
     * §8.2: newer `updatedAt` wins. An equal stamp keeps the local row, so
     * re-running the same restore is a no-op and an older backup never undoes
     * work the user did after taking it.
     */
    fun incomingWins(existingUpdatedAt: Long?, incomingUpdatedAt: Long): Boolean =
        existingUpdatedAt == null || incomingUpdatedAt > existingUpdatedAt

    // -- Sessions -------------------------------------------------------------

    sealed class Decoded<out T> {
        /** No id: the record cannot be placed at all. */
        object Unreadable : Decoded<Nothing>()
        /** Readable, but the local row is as new or newer; keep it. */
        data class Stale(val id: String) : Decoded<Nothing>()
        data class Apply<T>(val id: String, val entity: T, val isNew: Boolean) : Decoded<T>()
    }

    /**
     * A `SessionV2` payload, in either shape: iOS nests the session under
     * `"session"` with wrapper fields beside it; Android wrote it flat before
     * 1.14 and nested since. The two levels are merged, inner winning.
     */
    fun session(payload: JsonObject, existing: ChatSessionEntity?): Decoded<ChatSessionEntity> {
        val s = payload.unwrapNested("session")
        val id = s.str("id") ?: return Decoded.Unreadable
        val incomingUpdated = s.millis("updatedAt") ?: 0
        if (!incomingWins(existing?.updatedAt, incomingUpdated)) return Decoded.Stale(id)
        return Decoded.Apply(
            id,
            ChatSessionEntity(
                id = id,
                title = s.str("title"),
                modelId = s.str("modelId") ?: existing?.modelId ?: "",
                createdAt = s.millis("createdAt") ?: incomingUpdated,
                updatedAt = incomingUpdated,
                category = s.str("category"),
                lastMessage = s.str("lastMessage"),
                modelBinding = s.str("modelBinding"),
                source = s.str("source"),
                memoryEnabled = if (s.bool("memoryEnabled") != false) 1 else 0,
                pinnedAt = s.millis("pinnedAt"),
                editCount = s.int("editCount") ?: 0,
                thinkingOverride = s.str("thinkingOverride"),
                folderId = s.str("folderId"),
                parentSessionId = s.str("parentSessionId"),
                parentToolUseId = s.str("parentToolUseId"),
            ),
            isNew = existing == null,
        )
    }

    // -- Folders --------------------------------------------------------------

    fun folder(f: JsonObject, existing: FolderEntity?): Decoded<FolderEntity> {
        val id = f.str("id") ?: return Decoded.Unreadable
        val incomingUpdated = f.millis("updatedAt") ?: 0
        // Merge by the same rule as sessions: an older backup must not undo a
        // rename the user made after taking it.
        if (!incomingWins(existing?.updatedAt, incomingUpdated)) return Decoded.Stale(id)
        return Decoded.Apply(
            id,
            FolderEntity(
                id = id,
                name = f.str("name") ?: "",
                icon = f.str("icon"),
                color = f.str("color"),
                origin = f.str("origin") ?: FolderEntity.ORIGIN_MANUAL,
                sortIndex = f.int("sortIndex") ?: 0,
                pinnedAt = f.millis("pinnedAt"),
                // iOS names this field `desc` (ChatStore.Folder.desc); Android
                // wrote `description` (and since 1.14 writes both). Accept
                // either, or a folder restored from an iPhone silently loses
                // its one-line description.
                description = f.str("description") ?: f.str("desc"),
                createdAt = f.millis("createdAt") ?: incomingUpdated,
                updatedAt = incomingUpdated,
            ),
            isNew = existing == null,
        )
    }

    // -- Messages ---------------------------------------------------------------

    /** Null when id or sessionId is missing. The parent check is the caller's. */
    fun message(m: JsonObject): MessageEntity? {
        val id = m.str("id") ?: return null
        val sessionId = m.str("sessionId") ?: return null
        val createdAt = m.millis("createdAt") ?: 0
        return MessageEntity(
            id = id,
            sessionId = sessionId,
            role = m.str("role") ?: "user",
            // Re-serialised from the parsed element, so any part type this
            // build doesn't model is preserved verbatim.
            partsJson = (m["parts"]?.toString()) ?: "[]",
            createdAt = createdAt,
            tokenUsage = m["tokenUsage"]?.takeIf { it.toString() != "null" }?.toString(),
            sortOrder = m.int("sortOrder") ?: 0,
            reasoningContent = m.str("reasoningContent"),
            streamInterruptCount = m.int("streamInterruptCount") ?: 0,
            updatedAt = createdAt,
            // errorInfo is device-local (§0.2) and is never restored.
            errorInfo = null,
            // [T-token-attribution-snapshot] Absent in packages written before
            // this existed (and in any category the other platform hasn't
            // updated yet) — null then, which is exactly the "estimated" state
            // the Usage page renders.
            modelId = m.str("modelId"),
            modelDisplayName = m.str("modelDisplayName"),
            providerType = m.str("providerType"),
            providerInstanceId = m.str("providerInstanceId"),
        )
    }

    // -- Compact markers --------------------------------------------------------

    /** Null when id or sessionId is missing. The parent check is the caller's. */
    fun compactMarker(c: JsonObject): CompactMarkerEntity? {
        val id = c.str("id") ?: return null
        val sessionId = c.str("sessionId") ?: return null
        return CompactMarkerEntity(
            id = id,
            sessionId = sessionId,
            summary = c.str("summary") ?: "",
            firstKeptSortOrder = c.int("firstKeptSortOrder") ?: 0,
            compactedCount = c.int("compactedCount") ?: 0,
            createdAt = c.millis("createdAt") ?: 0,
            uiBoundarySortOrder = c.int("uiBoundarySortOrder"),
            boundaryMessageId = c.str("boundaryMessageId"),
            firstKeptMessageId = c.str("firstKeptMessageId"),
            lastCompactedMessageId = c.str("lastCompactedMessageId"),
            // [T-backup-marker-version] Without this a v2 marker restored as v1 and
            // resolved through the legacy firstKeptSortOrder chain (v2 writes
            // Int.MAX_VALUE there). Older packages carry no key: they are v1.
            version = c.int("version") ?: 1,
        )
    }

    // -- JSON helpers -------------------------------------------------------------

    /**
     * [T-android-restore-ios-session-nesting] Flatten `{outer…, key:{inner…}}`
     * into one object, inner winning.
     *
     * iOS wraps some records — a session arrives as
     * `{"memoryEnabled":true,"session":{"id":…,"title":…}}`. Merging instead
     * of choosing means one reader handles both shapes, and the wrapper's own
     * fields (`memoryEnabled`) stay reachable by their plain names.
     *
     * Returns `this` unchanged when [key] is absent or is not an object, so a
     * flat record costs nothing.
     */
    fun JsonObject.unwrapNested(key: String): JsonObject {
        val inner = (this[key] as? JsonObject) ?: return this
        return JsonObject(this.filterKeys { it != key } + inner)
    }

    fun JsonObject.str(key: String): String? =
        this[key]?.takeIf { it.toString() != "null" }?.runCatching { jsonPrimitive.content }
            ?.getOrNull()

    fun JsonObject.int(key: String): Int? =
        this[key]?.runCatching { jsonPrimitive.content.toInt() }?.getOrNull()

    fun JsonObject.bool(key: String): Boolean? =
        this[key]?.runCatching { jsonPrimitive.content.toBooleanStrict() }?.getOrNull()

    /**
     * Parse an ISO-8601 instant into epoch millis.
     *
     * iOS writes dates as ISO-8601 strings, but a package written by a future
     * build (or by a tool) could carry a numeric epoch, so both are accepted —
     * §2.2's tolerance rule applied to a value, not just a key.
     */
    fun JsonObject.millis(key: String): Long? {
        val raw = str(key) ?: return null
        raw.toLongOrNull()?.let { return it }
        for (pattern in ISO_PATTERNS) {
            runCatching {
                val f = SimpleDateFormat(pattern, Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                return f.parse(raw)?.time
            }
        }
        return null
    }

    private val ISO_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
    )
}

// ─── Merged from BackupRestoreFiles.kt ──────────────────────────────────────────────────

/**
 * Writes a package's file tree back onto the device.
 *
 * Split out of the importer because the two rules it enforces are the ones with
 * teeth, and they must apply identically to every category rather than being
 * re-stated per call site.
 */
object BackupRestoreFiles {

    private const val TAG = "Restore"

    data class Result(
        var written: Int = 0,
        var bytes: Long = 0,
        /**
         * Index entries whose content blob is MISSING from the package.
         *
         * The export is only a snapshot to the extent its cut-off holds: a file
         * recorded in files.index between the index pass and the blob copy can
         * be referenced and then never stored. This used to be worth a silent
         * skip; it is not. The file simply never appears and the restore still
         * reports success, which is the one outcome a backup tool must never
         * produce. Counted and surfaced instead (iOS review S7).
         */
        var missingBlobs: Int = 0,
        /** §3.4 tombstones — the export excluded these by the size cap. */
        var sizeSkippedInPackage: Int = 0,
        /** Tombstones for files that were not downloaded on the source device. */
        var notDownloadedInPackage: Int = 0,
        /** Entries refused because their path escaped the category root. */
        var rejectedPaths: Int = 0,
    )

    /**
     * Restore every [fileIndex] entry belonging to [category].
     *
     * @param destinationFor maps a package-relative logical path to its
     *   destination on this device, or null to ignore the entry.
     * @param containmentRoot every write must land inside this directory.
     */
    fun restore(
        packageRoot: File,
        fileIndex: List<BackupFileIndexEntry>,
        category: BackupCategory,
        containmentRoot: File,
        destinationFor: (String) -> File?,
    ): Result {
        val result = Result()
        val root = containmentRoot.canonicalFile

        for (entry in fileIndex) {
            if (entry.category != category.key) continue

            // Tombstones carry no content by design — counted, never written.
            if (entry.skipped != null) {
                when (entry.skipped) {
                    "size" -> result.sizeSkippedInPackage += 1
                    "not_downloaded" -> result.notDownloadedInPackage += 1
                    else -> result.sizeSkippedInPackage += 1
                }
                continue
            }

            val dest = destinationFor(entry.path) ?: continue

            // Path traversal via index CONTENT.
            //
            // `entry.path` comes from files.index.jsonl INSIDE the package,
            // which is attacker-controlled whenever a user restores a file
            // someone sent them — and this feature exists to accept packages
            // shared over messaging apps and Files. The ZIP extractor's own
            // traversal guard does NOT cover this: the archive's entry names
            // are all benign (`blobs/<xx>/<sha>`), and the malicious path lives
            // in the index content, not in an entry name.
            //
            // Checked on the canonical path, which is what the filesystem will
            // actually use.
            if (!isContained(dest, root)) {
                AppLogger.error(TAG, "[Restore] REJECTED path escaping its category root: ${entry.path}")
                result.rejectedPaths += 1
                continue
            }

            if (entry.isDirectory == true) {
                dest.mkdirs()
                continue
            }
            val sha = entry.sha256 ?: continue
            val blob = File(File(File(packageRoot, "blobs"), sha.take(2)), sha)
            if (!blob.isFile) {
                AppLogger.info(
                    TAG,
                    "[Restore] index references a blob not in the package: ${entry.path} sha=${sha.take(12)}"
                )
                result.missingBlobs += 1
                continue
            }

            dest.parentFile?.mkdirs()
            if (writeViaStagedSwap(blob, dest, entry.path)) {
                result.written += 1
                result.bytes += entry.size
            }
        }
        return result
    }

    /**
     * Copy to a sibling temp file, then rename over the destination.
     *
     * NOT delete-then-copy. That pair leaves the user's existing file
     * NON-EXISTENT for the whole duration of the copy, so a process kill in
     * that window deletes it permanently with nothing written back — and the
     * window is entered once per file. Merge idempotency does not help a file
     * that was deleted and never rewritten. A rename within one filesystem is
     * atomic, so the destination is either the old bytes or the new ones, never
     * missing (iOS review I2).
     */
    private fun writeViaStagedSwap(blob: File, dest: File, logicalPath: String): Boolean {
        val staged = File(dest.parentFile, ".restore-${UUID.randomUUID()}.tmp")
        return try {
            blob.copyTo(staged, overwrite = true)
            if (!staged.renameTo(dest)) {
                // Rename can fail across filesystems or if the destination is a
                // non-empty directory. Fall back to an explicit replace, which
                // still never leaves a gap longer than the delete itself.
                if (dest.exists() && !dest.delete()) throw BackupException("cannot replace $logicalPath")
                if (!staged.renameTo(dest)) throw BackupException("cannot finalise $logicalPath")
            }
            true
        } catch (e: Exception) {
            AppLogger.error(TAG, "[Restore] failed to write $logicalPath: ${e.message}")
            staged.delete()
            false
        }
    }

    /**
     * True when [candidate] resolves inside [root].
     *
     * Canonicalises both sides so `..` segments and symlinks are resolved the
     * way the kernel will resolve them. The parent is canonicalised when the
     * candidate does not exist yet, since a non-existent path has no canonical
     * form of its own on every filesystem.
     */
    private fun isContained(candidate: File, root: File): Boolean = runCatching {
        val rootPath = root.canonicalPath
        val target = if (candidate.exists()) {
            candidate.canonicalFile
        } else {
            File(candidate.parentFile?.canonicalFile ?: return false, candidate.name)
        }
        val path = target.path
        path == rootPath || path.startsWith(rootPath + File.separator)
    }.getOrDefault(false)
}

// ─── Merged from BackupSubAgents.kt ──────────────────────────────────────────────────

/**
 * [T-android-backup-subagents] One custom sub agent in `data/sub_agents.jsonl`
 * (envelope type `SubAgentV1`). Field-for-field iOS `BackupSubAgentRecord`
 * (Agent/Backup/BackupFormat.swift, 451f62e52 + db5bfa95f).
 *
 * Why this file exists on Android at all: iOS moved sub agents out of its
 * ProviderConfig into their own store and writes them ONLY here, while
 * Android wrote them ONLY inside `provider_config.json` and never read this
 * file. Each platform's restore therefore dropped the other's custom sub
 * agents, silently. Android now writes and reads both.
 *
 * The fields without defaults are the ones iOS's decoder requires. Kotlinx
 * omits a field equal to its default (encodeDefaults is off), so a default
 * here would drop, say, an empty `instructions` and make iOS reject the record.
 */
@Serializable
data class BackupSubAgentRecord(
    val id: String,
    val name: String,
    val subAgentDescription: String,
    val instructions: String,
    val modelGroupId: String? = null,
    val sortOrder: Int,
    /** ISO-8601, whole seconds, UTC (`2026-09-28T12:00:00Z`). */
    val updatedAt: String,
    /** iOS ThinkingLevel raw value (`"high"`); absent = not set. */
    val thinkingLevelOverride: String? = null,
)

internal object BackupSubAgentMapping {

    const val FILE_BASE = "sub_agents"
    const val RECORD_TYPE = "SubAgentV1"

    /**
     * The built-in is never written: it ships with the app and its name and
     * description are canonical, so an old package's copy must not overwrite
     * a newer build's (same rule as iOS and as built-in thinking rules).
     */
    fun exportable(roster: List<SubAgentDefinition>): List<SubAgentDefinition> =
        roster.filter { !it.isBuiltIn && it.id != SubAgentDefinition.BUILT_IN_ID }

    fun toRecord(def: SubAgentDefinition): BackupSubAgentRecord = BackupSubAgentRecord(
        id = def.id,
        name = def.name,
        subAgentDescription = def.description,
        instructions = def.instructions,
        modelGroupId = def.modelGroupId,
        sortOrder = def.sortOrder,
        // Whole seconds: Swift's `.iso8601` date strategy REJECTS fractional
        // seconds, and one undecodable date fails the whole record on iOS.
        updatedAt = Instant.ofEpochMilli(def.updatedAt).truncatedTo(ChronoUnit.SECONDS).toString(),
        // iOS's raw value. iOS also accepts "HIGH" now (4722d537d), but the
        // file format is iOS's, so write it the way iOS does.
        thinkingLevelOverride = def.thinkingLevelOverride?.name?.lowercase(),
    )

    /**
     * Null for a record that must not be restored: the built-in, or one
     * without a name (the roster matches on names). An unreadable date
     * becomes 0 = older than anything local, so it can add a new agent but
     * never overwrite one the user has.
     */
    fun fromRecord(r: BackupSubAgentRecord): SubAgentDefinition? {
        if (r.id.isBlank() || r.id == SubAgentDefinition.BUILT_IN_ID || r.name.isBlank()) return null
        return SubAgentDefinition(
            id = r.id,
            name = r.name,
            description = r.subAgentDescription,
            instructions = r.instructions,
            modelGroupId = r.modelGroupId?.takeIf { it.isNotBlank() },
            // Case-insensitive: "high" (iOS) and "HIGH" (older Android) both work.
            thinkingLevelOverride = r.thinkingLevelOverride?.let { ThinkingLevel.parseOrNull(it) },
            isBuiltIn = false,
            sortOrder = r.sortOrder,
            updatedAt = parseMillis(r.updatedAt),
        )
    }

    internal fun parseMillis(iso: String): Long =
        runCatching { Instant.parse(iso).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
            .getOrDefault(0L)
}

// ─── Merged from BackupZip.kt ──────────────────────────────────────────────────

/**
 * ZIP packaging for `.minisbak`, on both sides of the wire.
 *
 * ## Why STORED and not DEFLATE
 *
 * iOS reads packages with a hand-rolled parser
 * (`BackupZipExtractor.swift` / `BackupPackageReader.swift`) that streams
 * STORED entries in 4MB chunks but must inflate a DEFLATE entry **as a unit**,
 * into memory. A backup's largest members are already-compressed blobs
 * (media, zips) that deflate would barely shrink, so compressing them buys
 * almost nothing and hands the iOS importer a multi-hundred-MB allocation on a
 * device with a documented jetsam history. Everything is therefore written
 * STORED, which is also what `NSFileCoordinator(.forUploading)` produces on
 * the iOS side — one archive shape for both writers.
 *
 * STORED via [ZipOutputStream] requires size and CRC up front, which means one
 * hashing pass over each member before it is written. That pass is streaming,
 * so peak memory stays at the copy buffer regardless of member size.
 *
 * ## ZIP64
 *
 * A package past 4 GB or 65 535 members is written as ZIP64:
 * `ZipOutputStream` switches on its own once a member, an offset or the entry
 * count overflows the classic fields. Both readers take it — iOS's
 * `BackupZipExtractor` reads the ZIP64 EOCD and the per-entry extra field
 * (since 91edbde9a), and [extract] reads the local ZIP64 sizes. iOS's own
 * `BackupZipWriter` emits ZIP64 the same way.
 *
 * This used to be refused up front, on the grounds that iOS read only the
 * 16-bit count and would restore 65 535 members as a success. That reader was
 * replaced two days later, and the refusal stayed: a backup whose chats carried
 * 8.5 GB of media failed at packaging with no way out (OpenMinis#464). A reader
 * older than that fix still does not lose data quietly: the manifest's
 * integrity list names every member, so a short read fails the restore.
 */
object BackupZip {

    private const val TAG = "Backup"

    /**
     * [T-android-restore-gc-storm] Shared copy-buffer size. 256KB stays under
     * ART's Large Object Space threshold's practical impact zone while still
     * amortising syscalls; the buffer is allocated ONCE per archive/extract
     * call and reused across members, so the size no longer multiplies by the
     * package's file count.
     */
    private const val COPY_BUFFER_BYTES = 256 * 1024

    /**
     * [T-android-zip-store-by-content] How much of a member to sniff.
     *
     * Bounded on purpose: the probe exists to avoid a full deflate pass, so it
     * must not scale with the member. 64 KB is enough for both the magic check
     * and a representative entropy sample.
     */
    private const val PROBE_BYTES = 64 * 1024

    /**
     * Largest member that may be DEFLATED.
     *
     * Not a compression judgement — a memory one. iOS's reader inflates a
     * DEFLATE member as a single in-memory unit, so the cap is what keeps a
     * huge member from becoming one huge allocation on the restoring device.
     * 32 MB comfortably covers the jsonl families this change exists for while
     * leaving every large blob on the STORED path it was already using.
     */
    private const val MAX_DEFLATE_BYTES = 32L * 1024 * 1024

    /**
     * Leading bytes of formats that already carry their own compression.
     * Checked before the entropy probe because they are exact.
     */
    private val INCOMPRESSIBLE_MAGIC: List<ByteArray> = listOf(
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),                 // JPEG
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),                             // PNG
        byteArrayOf(0x47, 0x49, 0x46, 0x38),                                      // GIF8
        byteArrayOf(0x52, 0x49, 0x46, 0x46),                                      // RIFF (WebP/WAV/AVI)
        byteArrayOf(0x50, 0x4B, 0x03, 0x04),                                      // ZIP
        byteArrayOf(0x25, 0x50, 0x44, 0x46),                                      // %PDF
        byteArrayOf(0x49, 0x44, 0x33),                                            // MP3 w/ ID3
        byteArrayOf(0xFF.toByte(), 0xFB.toByte()),                                // MP3 frame
        byteArrayOf(0x4F, 0x67, 0x67, 0x53),                                      // OggS
        byteArrayOf(0x66, 0x4C, 0x61, 0x43),                                      // FLAC
        byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()),                    // Matroska/WebM
        byteArrayOf(0x42, 0x5A, 0x68),                                            // BZh
        byteArrayOf(0x1F, 0x8B.toByte()),                                         // gzip
        byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A),                       // xz
        byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C),        // 7z
        byteArrayOf(0x52, 0x61, 0x72, 0x21),                                      // Rar!
    )

    class ZipException(message: String) : Exception(message)

    // MARK: - Writing

    /**
     * Archive every file under [staging] into [destination], STORED, with
     * package-relative entry names.
     *
     * Entry order follows a directory walk; the manifest is written last by
     * the exporter, so it lands near the end of the archive exactly as it does
     * on iOS (which is why a duplicate copy exists for rescue).
     */
    fun archive(staging: File, destination: File) {
        val base = staging.canonicalFile
        val members = base.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(base).invariantPath() }
            .toList()

        ZipOutputStream(destination.outputStream().buffered()).use { zos ->
            // Per-entry method; see [shouldStore]. The stream-level default
            // stays STORED so an entry that does not set one explicitly keeps
            // the old behaviour.
            zos.setMethod(ZipOutputStream.STORED)
            // [T-android-restore-gc-storm] ONE copy buffer for the whole
            // archive. Allocating per member (as `copyTo(…, 4MB)` does — a
            // fresh buffer every call) put every allocation in the Large
            // Object Space, and a package with thousands of blobs churned
            // gigabytes of cumulative LOS. The GC log showed it plainly:
            // every collection was "N (N×4MB) LOS objects" with almost
            // nothing actually freed, and allocator threads stalling in
            // "Waiting for a blocking GC Alloc" often enough to jank the
            // main thread into an ANR kill on a Pixel 6.
            val buf = ByteArray(COPY_BUFFER_BYTES)
            members.forEach { file ->
                writeEntry(zos, file.relativeTo(base).invariantPath(), file, buf)
            }
        }
    }

    /**
     * [T-android-zip-store-by-content] Write one member, choosing STORED or
     * DEFLATED from what the member actually contains.
     *
     * Every entry used to be STORED unconditionally. That is right for the
     * media blobs — they carry their own compression, and deflating them costs
     * CPU on the way in and again on every restore — but it was also being
     * applied to the `data/` jsonl members, which are the bulk of a text-heavy
     * package and compress to roughly a sixth of their size. On a measured
     * Android package `data/messages.jsonl` alone was 7.11 MB of 10.4 MB,
     * stored raw, and every byte is re-uploaded on every backup.
     *
     * The mirror of iOS `BackupZipWriter.shouldStore` (1fbcf5886), which had
     * the opposite half of the same bug: it decided from the filename
     * extension, and blobs are content-addressed with no extension, so it
     * deflated 22,845 of 23,206 entries including all the photos and video.
     * One writer stored everything, the other deflated everything; both now
     * decide from content.
     */
    private fun writeEntry(zos: ZipOutputStream, name: String, file: File, buf: ByteArray) {
        val size = file.length()
        // A DEFLATE entry forces the iOS reader to inflate the WHOLE member
        // into one allocation (`compression_decode_buffer`,
        // BackupZipExtractor.swift:364) — an app with a jetsam history cannot
        // hand it a multi-hundred-MB member. So size gates the decision before
        // content does: anything large is stored no matter how well it would
        // compress. The members that motivated this change are the jsonl
        // families, which are megabytes, not hundreds of them.
        val deflatable = size <= MAX_DEFLATE_BYTES && !shouldStore(file, buf)
        if (deflatable) writeDeflatedEntry(zos, name, file, buf)
        else writeStoredEntry(zos, name, file, size, buf)
    }

    /**
     * True when the member will not meaningfully shrink, so deflating it would
     * only burn CPU here and again on every restore.
     *
     * Magic first — exact, and it covers the blobs that matter. The entropy
     * probe runs only when the magic says nothing, and reads a bounded sample
     * rather than the whole file, so the check cannot itself become the cost
     * it exists to avoid.
     */
    internal fun shouldStore(file: File, buf: ByteArray): Boolean {
        val head = ByteArray(minOf(PROBE_BYTES.toLong(), file.length()).toInt())
        if (head.isEmpty()) return true // Nothing to compress.
        file.inputStream().use { input ->
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            if (read < head.size) return shouldStoreBytes(head.copyOf(read))
        }
        return shouldStoreBytes(head)
    }

    /** Split out so a test can drive the decision without touching disk. */
    internal fun shouldStoreBytes(head: ByteArray): Boolean {
        if (head.isEmpty()) return true
        for (magic in INCOMPRESSIBLE_MAGIC) {
            if (head.size >= magic.size && magic.indices.all { head[it] == magic[it] }) return true
        }
        // ISO base media (MP4 / MOV / M4A / HEIC / AVIF): "ftyp" at byte 4,
        // not 0, so it cannot be expressed as a leading-bytes match.
        if (head.size >= 8 && head[4] == 0x66.toByte() && head[5] == 0x74.toByte() &&
            head[6] == 0x79.toByte() && head[7] == 0x70.toByte()
        ) {
            return true
        }
        // Deflate the sample and see whether it was worth it. Under a 5% gain
        // the pass does not pay for itself — here, or on the restore that has
        // to undo it.
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)
        try {
            deflater.setInput(head)
            deflater.finish()
            val out = ByteArray(head.size + 64)
            var total = 0
            while (!deflater.finished() && total < out.size) {
                val n = deflater.deflate(out, total, out.size - total)
                if (n == 0) break
                total += n
            }
            // Did not finish inside the bound → it is not shrinking. Store it.
            if (!deflater.finished()) return true
            return total > (head.size * 95) / 100
        } finally {
            deflater.end()
        }
    }

    private fun writeStoredEntry(
        zos: ZipOutputStream,
        name: String,
        file: File,
        size: Long,
        buf: ByteArray,
    ) {
        // STORED demands size and CRC up front, which is why this path reads
        // the member twice. DEFLATED does not, so it reads once.
        val crc = CRC32()
        file.inputStream().buffered().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            this.size = size
            compressedSize = size
            this.crc = crc.value
            time = file.lastModified()
        }
        zos.putNextEntry(entry)
        file.inputStream().buffered().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                zos.write(buf, 0, n)
            }
        }
        zos.closeEntry()
    }

    private fun writeDeflatedEntry(zos: ZipOutputStream, name: String, file: File, buf: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.DEFLATED
            time = file.lastModified()
        }
        zos.putNextEntry(entry)
        file.inputStream().buffered().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                zos.write(buf, 0, n)
            }
        }
        zos.closeEntry()
    }

    // MARK: - Reading

    /**
     * Extract every entry of [zipFile] under [destination].
     *
     * Uses a forward [ZipInputStream] scan rather than random access, so a
     * package whose central directory is damaged still yields whatever
     * precedes the damage — the same tolerance iOS's forward-scan rescue path
     * provides.
     */
    fun extract(zipFile: File, destination: File, onProgress: ((String) -> Unit)? = null) {
        destination.mkdirs()
        val root = destination.canonicalFile
        // [T-android-restore-gc-storm] One buffer for the whole archive, not
        // one per entry — see `archive` for the GC storm the per-entry
        // allocation caused during a large restore.
        val buf = ByteArray(COPY_BUFFER_BYTES)
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = safeResolve(root, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().buffered().use { sink ->
                        while (true) {
                            val n = zis.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                        }
                    }
                    onProgress?.invoke(entry.name)
                }
                zis.closeEntry()
            }
        }
    }

    /**
     * Read one small entry by name, without extracting the archive.
     *
     * Names are matched on suffix as well as equality: iOS packages the
     * staging tree through `NSFileCoordinator(.forUploading)`, which wraps
     * everything in an outer folder, so entries arrive as
     * `minisbak-<uuid>/manifest.json` rather than bare `manifest.json`.
     */
    fun readEntry(zipFile: File, name: String, maxBytes: Int = 32 * 1024 * 1024): ByteArray? {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.name == name || entry.name.endsWith("/$name")) {
                    return zis.readAtMost(maxBytes)
                }
                zis.closeEntry()
            }
        }
        return null
    }

    /** Entry names in the archive, in stream order. */
    fun listEntries(zipFile: File): List<String> {
        val out = mutableListOf<String>()
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                out.add(entry.name)
                zis.closeEntry()
            }
        }
        return out
    }

    /**
     * Strip the outer wrapper directory iOS's zipper adds, if there is one.
     *
     * After extraction the real package root may be `<dest>/minisbak-<uuid>/`
     * rather than `<dest>/`. Detected by looking for `manifest.json`, which
     * every package has at its root by definition.
     */
    fun packageRoot(extracted: File): File {
        if (File(extracted, "manifest.json").exists()) return extracted
        val dirs = extracted.listFiles()?.filter { it.isDirectory } ?: emptyList()
        val wrapped = dirs.firstOrNull { File(it, "manifest.json").exists() }
        if (wrapped != null) {
            AppLogger.info(TAG, "[Backup] package root is wrapped in '${wrapped.name}'")
            return wrapped
        }
        return extracted
    }

    /**
     * §5.5's path-traversal rule: a malicious package must not write outside
     * the destination. Absolute paths and any `..` component are refused
     * rather than normalised away.
     */
    private fun safeResolve(root: File, entryName: String): File {
        if (entryName.startsWith("/") || entryName.startsWith("\\")) {
            throw ZipException("Refusing unsafe entry path: $entryName")
        }
        val parts = entryName.split('/', '\\').filter { it.isNotEmpty() }
        if (parts.any { it == ".." }) {
            throw ZipException("Refusing unsafe entry path: $entryName")
        }
        val resolved = parts.fold(root) { acc, part -> File(acc, part) }
        // Belt and braces: a symlink or exotic name must not escape either.
        if (!resolved.canonicalPath.startsWith(root.canonicalPath + File.separator) &&
            resolved.canonicalPath != root.canonicalPath
        ) {
            throw ZipException("Refusing unsafe entry path: $entryName")
        }
        return resolved
    }

    private fun File.invariantPath(): String = path.replace(File.separatorChar, '/')

    private fun InputStream.readAtMost(max: Int): ByteArray {
        val buf = ByteArray(minOf(max, 64 * 1024))
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (total < max) {
            val n = read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}

// The `copyTo(out, bufferSize)` helper that used to live here is deliberately
// gone: it allocated its buffer per call, which is exactly the per-member
// allocation storm [T-android-restore-gc-storm] removed. Callers now thread
// one reusable buffer through the whole archive/extract operation.
