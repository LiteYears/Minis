package com.openminis.app.ui.chat

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Debug
import android.system.Os
import android.system.OsConstants
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.openminis.app.R
import com.openminis.app.agent.jobs.AgentCallback
import com.openminis.app.data.ContextOffload
import com.openminis.app.data.db.FolderMemberTitle
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.effectiveInputModalities
import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkHandler
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledSessionHold
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTriggerKind
import com.openminis.app.speech.ReadAloudPlayer
import com.openminis.app.speech.correction.ScreenContextBuilder
import com.openminis.app.speech.correction.TypedVocabularyBuilder
import com.openminis.app.ui.sandbox.FileItem
import java.io.File
import java.text.BreakIterator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * LazyColumn stops running its drag handler once canScrollForward/Backward
 * are both false, so the platform stretch overscroll never fires when the
 * list content fits the viewport. Wrap the list in this box to restore it:
 * the outer scrollable always accepts gestures, consumes 0, and pipes the
 * delta into a shared stretch effect. The inner LazyColumn, given the same
 * effect, forwards its own overflow too.
 *
 * Usage:
 *   AlwaysStretchOverscrollBox { effect ->
 *     LazyColumn(overscrollEffect = effect, ...) { ... }
 *   }
 */
@Composable
fun AlwaysStretchOverscrollBox(
    modifier: Modifier = Modifier,
    content: @Composable (OverscrollEffect?) -> Unit,
) {
    val effect = rememberOverscrollEffect()
    val noopState = rememberScrollableState { 0f }
    val boxModifier = if (effect != null) {
        modifier
            .fillMaxSize()
            .scrollable(
                state = noopState,
                orientation = Orientation.Vertical,
                overscrollEffect = effect,
            )
            .overscroll(effect)
    } else {
        modifier.fillMaxSize()
    }
    Box(modifier = boxModifier) {
        content(effect)
    }
}

// ─── Merged from AudioWaveformView.kt ──────────────────────────────────────────────────

/**
 * Horizontal row of animated bars driven by a rolling RMS window.
 *
 * Mirrors iOS `AudioWaveformView.swift`: each bar animates between a small
 * resting height and the container height proportional to its normalized
 * level. The newest sample lands on the right so the waveform reads as a
 * "now" indicator instead of a scrolling history.
 */
@Composable
fun AudioWaveformView(
    levels: List<Float>,
    modifier: Modifier = Modifier,
    barColor: Color,
    heightDp: Int = 28,
    barSpacingDp: Int = 2,
) {
    val animatedLevels = levels.map { level ->
        val animated by animateFloatAsState(
            targetValue = level.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 100),
            label = "waveformBar",
        )
        animated
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp),
    ) {
        val barCount = animatedLevels.size
        if (barCount == 0) return@Canvas

        val totalSpacing = barSpacingDp.dp.toPx()
        val barWidth = ((size.width - totalSpacing * (barCount - 1)) / barCount).coerceAtLeast(1f)
        val maxBarHeight = size.height
        val minBarHeight = 3.dp.toPx()
        val cornerRadiusPx = barWidth / 2f

        animatedLevels.forEachIndexed { index, level ->
            val barHeight = (minBarHeight + level * (maxBarHeight - minBarHeight))
                .coerceIn(minBarHeight, maxBarHeight)
            val x = index * (barWidth + totalSpacing)
            val y = (size.height - barHeight) / 2f
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
            )
        }
    }
}

// ─── Merged from BubbleRowLocator.kt ──────────────────────────────────────────────────

/**
 * [T-android-bubble-anchor] Maps a user bubble to the persisted row it came
 * from, for retry, delete-from-here and edit. Port of iOS `locateUserBubble`
 * (6c0de68f5, which replaced 8a1bf2e8b): link, don't count.
 *
 * All three operations cut the DB at a row and rebuild the model-facing
 * history from what survives, so the row they pick is exactly what the model
 * keeps. They used to pick it with three private copies of an ordinal match
 * ("the n-th user bubble is the n-th visible user row"), each with its own
 * idea of "visible":
 *  - retry and delete skipped rows with no text part and `<system-reminder>`
 *    rows;
 *  - edit additionally stripped the `<user-attached-files>` XML first;
 *  - the UI side counted every user bubble.
 * An image-only message renders a bubble but has no text part, so it was
 * counted on screen and skipped in the DB: every later retry/delete/edit
 * anchored one turn too late. When the target was the last user turn the
 * match ran off the end, nothing was cut, and an edit then appended the new
 * text after the old one - the model still read the pre-edit message (the
 * iOS field report, reproduced here through a different skew).
 *
 * Every user bubble already carries its row: a live send, a mid-loop queue
 * injection and a steer take the persisted row's id as the bubble id, a loaded
 * bubble has `id == row id` plus [ChatMessage.sourceDbIds], and the bubbles of
 * an end-of-run queue drain are linked through [ChatMessage.sourceDbIds]
 * ([T-android-bubble-anchor-drain]). So the row is found by id, with no
 * counting. Only a bubble with no row - a queued placeholder that was
 * never persisted - falls back to the ordinal rule, with one shared
 * definition of a visible row and a warning in the log.
 *
 * iOS also needs a "pending" state for a bubble whose history entry is not
 * appended yet. Android creates the bubble only after its row is persisted,
 * and all three operations are refused while a turn is running, so that
 * window does not exist here.
 */
internal object BubbleRowLocator {

    enum class Via { LINKED, ORDINAL, NONE }

    class Located(val row: MessageEntity?, val via: Via)

    /** The persisted user row behind the user bubble at [index] in [messages]. */
    fun locateUserRow(messages: List<ChatMessage>, index: Int, rows: List<MessageEntity>): Located {
        val bubble = messages[index]
        val ids = HashSet<String>(bubble.sourceDbIds.size + 1).apply {
            add(bubble.id)
            addAll(bubble.sourceDbIds)
        }
        rows.firstOrNull { it.role == "user" && it.id in ids }?.let { return Located(it, Via.LINKED) }

        // Unlinked: the ordinal rule, counting the same rows the chat renders.
        val ordinal = messages.subList(0, index).count { it.role == "user" }
        var n = 0
        for (row in rows) {
            if (row.role != "user" || !isVisibleUserRow(row.partsJson)) continue
            if (n == ordinal) return Located(row, Via.ORDINAL)
            n++
        }
        return Located(null, Via.NONE)
    }

    /**
     * [T-android-bubble-anchor-drain] The bubbles that share the target's
     * persisted row: an end-of-run queue drain folds several queued prompts
     * into ONE user row but keeps one bubble per prompt, each linked to that
     * row through [ChatMessage.sourceDbIds]. Port of iOS 6c0de68f5's merged
     * group.
     *
     * The DB cut is the same for every member (they share the row), so the
     * chat must be cut as a whole group too, or it would show what the model
     * no longer has (or hide what it still has):
     *  - delete / edit remove from the group's FIRST bubble;
     *  - retry keeps through its LAST bubble.
     *
     * A bubble with no [ChatMessage.sourceDbIds] (live send, mid-loop inject,
     * steer, unpersisted placeholder) is a group of one.
     */
    fun groupSpan(messages: List<ChatMessage>, index: Int): IntRange {
        val target = messages[index]
        if (target.role != "user" || target.sourceDbIds.isEmpty()) return index..index
        val rows = target.sourceDbIds.toSet()
        var first = index
        var last = index
        messages.forEachIndexed { i, m ->
            if (m.role == "user" && (m.id in rows || m.sourceDbIds.any { it in rows })) {
                if (i < first) first = i
                if (i > last) last = i
            }
        }
        return first..last
    }

    /**
     * Where to cut so the located bubble is kept ([keepBubble], retry) or
     * removed with everything after it (delete / edit): a `sort_order` for
     * `ChatDao.deleteMessagesAfter` (`sort_order >= cutoff`), or -1 when no
     * row was found, which callers treat as "leave the DB alone".
     */
    fun cutoffFor(located: Located, keepBubble: Boolean): Int {
        val row = located.row ?: return -1
        return if (keepBubble) row.sortOrder + 1 else row.sortOrder
    }

    /**
     * Whether a user row renders a bubble, matching loadSessionMessages: it
     * has text other than a synthetic `<system-reminder>` or the
     * `<user-attached-files>` inventory, or it carries media. Tool-result-only
     * rows and the stop-continue reminder row do not.
     */
    fun isVisibleUserRow(partsJson: String): Boolean = try {
        val arr = org.json.JSONArray(partsJson)
        (0 until arr.length()).any { i ->
            val o = arr.getJSONObject(i)
            when (o.optString("type")) {
                "text" -> {
                    val v = o.optString("value", "")
                    !v.trimStart().startsWith("<system-reminder>") && stripAttachedFiles(v).isNotBlank()
                }
                "mediaRef", "image" -> true
                else -> false
            }
        }
    } catch (_: Exception) {
        true
    }

    private fun stripAttachedFiles(text: String): String {
        val start = text.indexOf("<user-attached-files>")
        if (start < 0) return text
        val endTag = "</user-attached-files>"
        val end = text.indexOf(endTag, start)
        return if (end >= 0) text.substring(0, start) + text.substring(end + endTag.length) else text.substring(0, start)
    }
}

// ─── Merged from ChatLinkResolver.kt ──────────────────────────────────────────────────

/**
 * Decides what should happen when a link inside chat markdown is tapped.
 *
 * Routing order:
 *  1. Recognized minis:// deep-link action  → DeepLink (delegated to MainActivity via Intent.ACTION_VIEW)
 *  2. minis://<sandbox path>, file://, or absolute /var/minis|/root path → SandboxFile
 *     (a folder → SandboxFolder, opened in the in-app file browser)
 *  3. Non-http(s) external schemes (intent://, mailto:, tel:, geo:, …)   → ExternalApp
 *  4. Anything else (http(s), about, file)                                → Web
 */
sealed class ChatLinkAction {
    data class DeepLink(val action: DeepLinkAction) : ChatLinkAction()
    data class SandboxFile(val item: FileItem) : ChatLinkAction()
    /** [T-minis-folder-link] A sandbox folder, as its Linux path (/var/minis/workspace/dir). */
    data class SandboxFolder(val linuxPath: String) : ChatLinkAction()
    data class ExternalApp(val url: String) : ChatLinkAction()
    data class Web(val url: String) : ChatLinkAction()
}

object ChatLinkResolver {

    fun resolve(rawUrl: String, sessionId: String? = null, context: Context? = null): ChatLinkAction {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ChatLinkAction.Web(rawUrl)

        val uri = runCatching { trimmed.toUri() }.getOrNull()
        val scheme = uri?.scheme?.lowercase()

        // 1. minis:// deep links — only branch out when the URL maps to a known action,
        //    otherwise fall through to sandbox-path handling.
        if (scheme == "minis") {
            val action = DeepLinkHandler.parse(uri)
            if (action !is DeepLinkAction.Unknown) {
                return ChatLinkAction.DeepLink(action)
            }
        }

        // 2. Sandbox file resolution — prefer a session-scoped resolver when
        //    the caller knows which chat this link belongs to. The global
        //    `PRootKernel.bindMounts` is last-writer-wins, so on a device
        //    with multiple sessions the resolver otherwise points at
        //    whichever session booted its shell most recently.
        val hostFile = resolveSandboxFile(trimmed, scheme, sessionId, context)
        android.util.Log.w("ChatLinkDiag",
            "resolve url=${trimmed.take(200)} sid=$sessionId hostFile=${hostFile?.absolutePath} exists=${hostFile?.exists()}")
        if (hostFile != null && hostFile.exists() && !hostFile.isDirectory) {
            FileItem.from(hostFile)?.let { return ChatLinkAction.SandboxFile(it) }
        }
        // [T-minis-folder-link] Agents link folders too (minis://workspace/dir/).
        // Before, a folder fell through to Web, and a web preview of a
        // minis:// folder URL renders nothing — the link looked dead.
        if (hostFile != null && hostFile.isDirectory) {
            sandboxFolderLinuxPath(trimmed, scheme, sessionId, context)
                ?.let { return ChatLinkAction.SandboxFolder(it) }
        }

        // T136: intent://, mailto:, tel:, geo:, market: etc. need a system
        // dispatch — the in-app preview WebView's `loadUrl(...)` doesn't
        // trip `shouldOverrideUrlLoading` for the initial URL, so without
        // this hop those schemes hit the WebView and surface as
        // ERR_UNKNOWN_URL_SCHEME.
        if (com.openminis.app.ui.browser.BrowserExternalSchemeHandler.shouldHandleExternally(trimmed)) {
            return ChatLinkAction.ExternalApp(trimmed)
        }

        return ChatLinkAction.Web(trimmed)
    }

    /**
     * [T-minis-folder-link] The Linux path of the folder a sandbox link names,
     * so the file browser can open at it. Picks the same decode candidate
     * [resolveSandboxFile] would. `file://` links carry a host path, not a
     * Linux one, so they are not handled here.
     */
    private fun sandboxFolderLinuxPath(
        raw: String,
        scheme: String?,
        sessionId: String?,
        context: Context?,
    ): String? {
        fun isDir(linuxPath: String): Boolean =
            (if (sessionId != null && context != null) {
                PRootKernel.resolveSessionHostPath(sessionId, linuxPath, context)
            } else {
                PRootKernel.resolveHostPath(linuxPath)
            })?.isDirectory == true
        val candidates = when (scheme) {
            "minis" -> minisPathCandidates(raw.removePrefix("minis://").substringBefore('?'))
                .map { if (it.startsWith("/")) it else "/var/minis/$it" }
            null -> listOf(raw).filter { it.startsWith("/") }
            else -> emptyList()
        }
        return candidates.firstOrNull { isDir(it) }?.trimEnd('/')?.ifEmpty { "/" }
    }

    /**
     * Map a chat link to a host File when it points into the sandbox, else null.
     * Accepts:
     *   minis://attachments/foo.png        → /var/minis/attachments/foo.png
     *   minis:///var/minis/workspace/x.csv → /var/minis/workspace/x.csv (absolute)
     *   file:///path/to/file               → /path/to/file
     *   /var/minis/workspace/x.csv         → resolved via bind mount
     *   /root/whatever                     → resolved relative to rootfs
     */
    private fun resolveSandboxFile(
        raw: String,
        scheme: String?,
        sessionId: String?,
        context: Context?,
    ): File? {
        fun lookup(linuxPath: String): File? =
            if (sessionId != null && context != null) {
                PRootKernel.resolveSessionHostPath(sessionId, linuxPath, context)
            } else {
                PRootKernel.resolveHostPath(linuxPath)
            }
        return when (scheme) {
            "minis" -> {
                // Keep '#' — attachment filenames legitimately contain it.
                // `minis://` URLs don't use fragments, so stripping at '#'
                // would truncate filenames like `foo #China.mp4`.
                val stripped = raw.removePrefix("minis://").substringBefore('?')
                // [T-android-minis-url-double-encoding] Try each decode
                // candidate and take the first that exists on disk. See
                // [minisPathCandidates] for why one decode pass isn't enough.
                minisPathCandidates(stripped)
                    .asSequence()
                    .map { candidate ->
                        val linuxPath = if (candidate.startsWith("/")) candidate else "/var/minis/$candidate"
                        lookup(linuxPath)
                    }
                    .firstOrNull { it != null && it.exists() }
                    // Nothing existed — hand back the primary candidate so the
                    // caller's own exists() check reports against the path the
                    // user actually meant, and diagnostics stay readable.
                    ?: lookup(
                        minisPathCandidates(stripped).first().let {
                            if (it.startsWith("/")) it else "/var/minis/$it"
                        },
                    )
            }
            "file" -> {
                val path = raw.removePrefix("file://").substringBefore('?')
                if (path.isEmpty()) null else {
                    val candidates = minisPathCandidates(path).map { File(it) }
                    candidates.firstOrNull { it.exists() } ?: candidates.first()
                }
            }
            null -> {
                if (raw.startsWith("/")) lookup(raw) else null
            }
            else -> null
        }
    }

    /**
     * [T-android-minis-url-double-encoding] Decode candidates for the path part
     * of a `minis://` URL, in priority order. Ported from iOS
     * `MinisURLPathDecoding` (T-fix-double-encoding).
     *
     * A correctly-formed minis URL percent-encodes each segment exactly once,
     * and one decode pass recovers the real UTF-8 name. But links reach us
     * double-encoded when the agent — or an intermediate Markdown
     * autolink/sanitize step — re-encodes the literal `%` of an
     * already-encoded URL, turning `%E5` into `%25E5`. One decode pass then
     * yields a literal `%E5…`, which matches no file on disk.
     *
     * The user-visible symptom is a tap that does NOTHING, which is why this
     * is worth the tolerance: [resolve] falls through to `ChatLinkAction.Web`,
     * and a web preview of a `minis://` URL renders nothing at all. There is
     * no error, no toast, no navigation — the link just looks dead.
     *
     * Note this is NOT specific to CJK. Any non-ASCII segment percent-encodes
     * to `%XX` bytes and is equally affected; CJK paths merely make it far
     * more likely, since every character encodes (an ASCII path often survives
     * because it has nothing to encode in the first place).
     *
     * Disk existence is the disambiguator, so the extra candidate is only ever
     * reached when the correct single decode found nothing — a filename that
     * legitimately contains a `%` still resolves via the first candidate and
     * never sees the second decode.
     *
     * Percent-decoding is done by hand rather than with
     * [java.net.URLDecoder], which implements
     * `application/x-www-form-urlencoded` — where `+` means SPACE. A path
     * segment like `a+b/file.pdf` is a real directory name on disk, and
     * URLDecoder silently turns it into `a b/file.pdf`, resolving to nothing
     * and producing the same dead-link symptom. (`java.net.URI` is no help
     * either: its multi-arg constructor ENCODES its input, so `getPath()`
     * hands the string straight back undecoded.)
     */
    internal fun minisPathCandidates(strippedPath: String): List<String> {
        // Decode %XX only, never mapping '+' to space (see KDoc). Done by hand
        // rather than with URLDecoder (form semantics: '+' → space) or
        // java.net.URI (its multi-arg constructor ENCODES its input, so
        // getPath() hands the string straight back). Invalid escapes are
        // emitted verbatim so a stray '%' degrades instead of throwing.
        fun decodeOnce(s: String): String {
            if (!s.contains('%')) return s
            val out = java.io.ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val hex = s.substring(i + 1, i + 3)
                    val byte = hex.toIntOrNull(16)
                    if (byte != null) {
                        out.write(byte)
                        i += 3
                        continue
                    }
                }
                // Non-escape (or malformed escape): keep the character's own
                // UTF-8 bytes so already-decoded CJK passes through intact.
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }

        val once = decodeOnce(strippedPath)
        val candidates = mutableListOf(once)
        val twice = decodeOnce(once)
        if (twice != once) candidates.add(twice)
        return candidates
    }

    /** Fire a system intent so MainActivity's BROWSABLE filter picks the deep link up. */
    fun dispatchDeepLink(context: Context, originalUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, originalUrl.toUri()).apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}

// ─── Merged from ChatNestedScrollFollow.kt ──────────────────────────────────────────────────

/**
 * [T-android-userask-edge-bounce] A scroller nested in the chat list (the
 * pending user_ask card's body) hands its leftover drag to the list once it
 * reaches its own edge. Compose moves the list for that with a raw delta: the
 * list's `isScrollInProgress` stays false and it emits no DragInteraction, so
 * every "is the user scrolling?" check the follow-bottom code has said no.
 * While a question waits the run still counts as streaming, so the
 * layout-drift safety net snapped the list back to the bottom a frame after
 * each push - finger pushes, net pulls back: the card "bounces back and forth
 * at its edge" (field report 2026-10-09; reproduced on a Pixel 4a, log
 * `scroll src=LAYOUT-DRIFT-SNAP` + `arm SUPPRESSED (streaming content
 * growth)` during the drag).
 *
 * The decisions, kept pure so they can be tested without a list.
 */
object ChatNestedScrollFollow {

    /**
     * How long after the last child-driven delta the list still counts as
     * being moved by the user. Covers the frames between two drag deltas and
     * the gap before the gesture's end is reported; the end itself clears the
     * state at once.
     */
    const val HOLD_MS = 300L

    /**
     * Whether a nested pre-scroll is a CHILD's user drag reaching the list.
     * The list's own drag also passes through the connection, but it already
     * reports itself ([listIsDragged]) and has its own handling.
     */
    fun isChildUserScroll(sourceIsUserInput: Boolean, deltaY: Float, listIsDragged: Boolean): Boolean =
        sourceIsUserInput && deltaY != 0f && !listIsDragged

    /** Whether the user is moving the list through a child right now. */
    fun childScrollActive(gestureOpen: Boolean, msSinceLastDelta: Long): Boolean =
        gestureOpen && msSinceLastDelta in 0 until HOLD_MS

    /**
     * Whether follow-bottom may snap the list back. Never while the user moves
     * it - whether by dragging the list or through a child.
     */
    fun followMaySnap(userScrolledAway: Boolean, listScrollInProgress: Boolean, childScrollActive: Boolean): Boolean =
        !userScrolledAway && !listScrollInProgress && !childScrollActive

    /**
     * What a finished child gesture leaves behind: the same rule as a finger
     * lifted from the list itself - away from the bottom means the user is
     * reading, near it means keep following.
     */
    fun scrolledAwayAfterGesture(nearBottom: Boolean): Boolean = !nearBottom
}

/**
 * [T-android-userask-edge-bounce] A fling inside a nested scroller stops at
 * that scroller's edge: its leftover velocity is consumed instead of flinging
 * the chat list behind it. A drag past the edge still scrolls the chat.
 */
object StopFlingAtEdgeConnection : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        // SideEffect = the fling's own deltas; UserInput (a drag) passes on.
        if (source == NestedScrollSource.UserInput) Offset.Zero else available

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

// ─── Merged from ChatToolFormatting.kt ──────────────────────────────────────────────────

// [T-android-split-chat] Pure tool-label / duration / timestamp formatting
// helpers extracted verbatim from ChatScreen.kt. `internal` so the rest of the
// chat package (still in ChatScreen.kt) can call them across the file boundary.
// No logic change — code moved as-is.

internal val stepTimestampFormatter: java.text.SimpleDateFormat =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

internal fun formatStepTimestamp(epochMs: Long): String =
    synchronized(stepTimestampFormatter) {
        stepTimestampFormatter.format(java.util.Date(epochMs))
    }

// [T-step-timestamp v2 aa8b1128] Short "elapsed-or-final duration"
// label for the tool detail header. Cross-platform format contract:
//   < 60s   → "3s"
//   < 1h    → "2m30s" (drops the seconds suffix when seconds == 0)
//   ≥ 1h    → "1h12m"
// When `stillRunning` is true the result is suffixed "…" so the
// header reads "12s…" while the tool is in flight.
// Negative / non-positive values clamp to 0.
internal fun formatStepDuration(seconds: Long, stillRunning: Boolean): String {
    val safe = seconds.coerceAtLeast(0L)
    val base = when {
        safe < 60L -> "${safe}s"
        safe < 3600L -> {
            val m = safe / 60L
            val s = safe % 60L
            if (s == 0L) "${m}m" else "${m}m${s}s"
        }
        else -> {
            val h = safe / 3600L
            val m = (safe % 3600L) / 60L
            if (m == 0L) "${h}h" else "${h}h${m}m"
        }
    }
    return if (stillRunning) "$base…" else base
}

// Helper: tool accent color
internal fun toolAccentColor(toolName: String): Color = when (toolName) {
    "shell_execute" -> Color(0xFF34C759)
    "file_read" -> Color(0xFF32ADE6)
    "file_write" -> Color(0xFF007AFF)
    "file_edit" -> Color(0xFFFF9500)
    "browser_use" -> Color(0xFF007AFF)
    "read_image" -> Color(0xFFAF52DE)
    "memory_write", "memory_get" -> Color(0xFFFF2D55)
    "web_search" -> Color(0xFF32ADE6)    // iOS: .cyan for search
    // [T-sub-agents-v1] Current name + the pre-rename one still in shipped transcripts.
    "subagent_task", "delegate_task", "agent_status" -> HelperAccentStatic  // iOS: HelperAccent.color (electric violet)
    else -> Color(0xFF8E8E93)
}

// Helper: tool icon (iOS: distinct SF Symbols per tool type)
internal fun toolIconFor(toolName: String) = when (toolName) {
    "shell_execute" -> Icons.Default.Terminal
    "file_read" -> Icons.Default.Description         // iOS: doc.text
    "file_write" -> Icons.AutoMirrored.Filled.NoteAdd   // iOS: doc.text.fill (filled variant)
    "file_edit" -> Icons.Default.EditNote             // iOS: square.and.pencil
    "browser_use" -> Icons.Default.Language            // iOS: globe
    "read_image" -> Icons.Default.Image                // iOS: photo
    "memory_write", "memory_get" -> Icons.Default.Psychology // iOS: brain.head.profile
    "web_search" -> Icons.Default.Search               // iOS: magnifyingglass
    "subagent_task", "delegate_task", "agent_status" -> Icons.Default.Groups  // iOS: person.2.wave.2
    else -> Icons.Default.Build
}

// [T-android-overlay-two-row] View-layer twin of [toolIconFor], for callers
// that build a classic View hierarchy instead of Compose (the floating-window
// capsule in ToolOverlayController). An ImageVector cannot be handed to an
// ImageView, so the same vocabulary has to exist a second time as drawable
// resources — but it lives HERE, beside the Compose map, so the two are edited
// together and cannot silently diverge. Adding a tool means adding it to both.
@androidx.annotation.DrawableRes
internal fun toolIconResFor(toolName: String?): Int = when (toolName) {
    "shell_execute" -> R.drawable.ic_tool_terminal
    "file_read" -> R.drawable.ic_tool_description
    "file_write" -> R.drawable.ic_tool_note_add
    "file_edit" -> R.drawable.ic_tool_edit_note
    "browser_use" -> R.drawable.ic_tool_globe
    "read_image" -> R.drawable.ic_tool_image
    "memory_write", "memory_get" -> R.drawable.ic_tool_psychology
    "web_search" -> R.drawable.ic_tool_search
    "subagent_task", "delegate_task", "agent_status" -> R.drawable.ic_tool_groups
    else -> R.drawable.ic_tool_build
}

// [T-android-overlay-two-row] View-layer twin of [toolAccentColor]. Same
// literals, as a packed ARGB Int rather than a Compose Color.
@androidx.annotation.ColorInt
internal fun toolAccentColorInt(toolName: String?): Int = when (toolName) {
    "shell_execute" -> 0xFF34C759.toInt()
    "file_read" -> 0xFF32ADE6.toInt()
    "file_write" -> 0xFF007AFF.toInt()
    "file_edit" -> 0xFFFF9500.toInt()
    "browser_use" -> 0xFF007AFF.toInt()
    "read_image" -> 0xFFAF52DE.toInt()
    "memory_write", "memory_get" -> 0xFFFF2D55.toInt()
    "web_search" -> 0xFF32ADE6.toInt()
    "subagent_task", "delegate_task", "agent_status" -> 0xFFAF52DE.toInt()
    else -> 0xFF8E8E93.toInt()
}

/**
 * [T-android-overlay-two-row] Humanized fallback title for a tool call whose
 * model-supplied `tool_title` has not arrived (or was never sent).
 *
 * This mirrors ChatViewModel.friendlyToolTitle, which is what the chat stream's
 * tool pill shows in the same situation. Kept as a separate copy rather than
 * hoisting that private member, because ChatViewModel is a very large file that
 * several sessions edit concurrently; the vocabulary here is small and stable.
 */
internal fun friendlyToolTitleFor(toolName: String?): String = when (toolName) {
    null, "" -> "Minis"
    "shell_execute" -> "Execute Shell"
    "file_read" -> "Read File"
    "file_write" -> "Write File"
    "file_edit" -> "Edit File"
    "browser_use" -> "Browse Web"
    "read_image" -> "Read Image"
    "memory_write" -> "Write Memory"
    "memory_get" -> "Read Memory"
    "web_search" -> "Search Web"
    else -> toolName
        .split('_')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }
}

// Helper: tool display name for "Minis is using X"
internal fun toolDisplayName(toolName: String): String = when (toolName) {
    "shell_execute" -> "terminal"
    "file_read" -> "file reader"
    "file_write" -> "file writer"
    "file_edit" -> "file editor"
    "browser_use" -> "browser"
    "read_image" -> "image viewer"
    "memory_write" -> "memory"
    "memory_get" -> "memory"
    "web_search" -> "search"
    "subagent_task", "delegate_task", "agent_status" -> "agent"
    else -> toolName
}

/**
 * Full "Minis is …" label shown in the tool detail sheet's bottom bar.
 * Mirrors iOS ToolLiveSheet.toolTitle so the wording matches per tool.
 */
internal fun toolTitleLabel(toolName: String): String = when (toolName) {
    "shell_execute" -> "Minis is using Shell"
    "file_read" -> "Minis is reading File"
    "file_write" -> "Minis is using Editor"
    "file_edit" -> "Minis is editing File"
    "browser_use" -> "Minis is using Browser"
    "read_image" -> "Minis is reading Image"
    "memory_write", "memory_get" -> "Minis is using Memory"
    "web_search" -> "Minis is using Search"
    "subagent_task", "delegate_task", "agent_status" -> "Minis is using an Agent"
    else -> "Minis is using ${toolDisplayName(toolName)}"
}

// Helper: format duration (iOS: < 1s → "0.1s", < 60s → "45s", >= 60s → "2m 10s")
internal fun formatToolDuration(ms: Long): String {
    val seconds = ms / 1000.0
    return when {
        seconds < 1 -> String.format("%.1fs", seconds)
        seconds < 60 -> String.format("%.0fs", seconds)
        else -> {
            val m = (seconds / 60).toInt()
            val s = (seconds % 60).toInt()
            "${m}m ${s}s"
        }
    }
}

// ─── Merged from ComposerHintCarousel.kt ──────────────────────────────────────────────────

/**
 * [T-port-composer-hint-carousel] The composer's usage figures as separate
 * one-line pages that take turns, instead of one joined "context · quota" line
 * that a narrow screen or a large font cut off at the quota. Port of iOS
 * b76030535 (pages), 81488a84d (5 s, focus-rotation entries) and ffa76773a
 * (3 rounds, then settle on the context page).
 *
 * Pure (no Android / Compose types) so the timing rules are JVM-tested.
 */
internal enum class UsageHintPage { CONTEXT, QUOTA }

/** Context first, then the quota when the model has one. */
internal fun ContextUsageHint.pages(): List<UsageHintPage> =
    if (quotaText.isNullOrEmpty()) listOf(UsageHintPage.CONTEXT)
    else listOf(UsageHintPage.CONTEXT, UsageHintPage.QUOTA)

/**
 * Which page of the transient usage line is up, and when it turns.
 *
 * - A new figure set (a new [ContextUsageHint.generation]) starts on the
 *   context page with its own [ROUNDS] rounds.
 * - Every [INTERVAL_MS] the line turns to the next page while it may turn
 *   ([canTurn]). After [ROUNDS] x page-count turns it is back on the context
 *   page and stops for good for these figures.
 * - The count survives a pause (typing aside, the composer leaving the
 *   screen), so coming back does not start the rounds over.
 */
internal class ComposerHintCarousel {
    var generation: Int = -1
        private set
    var page: Int = 0
        private set
    var turns: Int = 0
        private set
    var finished: Boolean = false
        private set

    /** Adopt the figures of [generation]; a different generation starts over. */
    fun adopt(generation: Int) {
        if (generation == this.generation) return
        this.generation = generation
        page = 0
        turns = 0
        finished = false
    }

    /**
     * Whether the line may turn now: more than one page, rounds left, the line
     * up on an empty composer, and no screen reader (rotating would
     * re-announce the field over the user).
     */
    fun canTurn(pageCount: Int, lineShowing: Boolean, composerEmpty: Boolean, screenReaderOn: Boolean): Boolean =
        pageCount > 1 && !finished && lineShowing && composerEmpty && !screenReaderOn

    /** Turn to the next page; returns it. */
    fun turn(pageCount: Int): Int {
        if (pageCount <= 1 || finished) return page
        page = (page + 1) % pageCount
        turns += 1
        if (turns >= ROUNDS * pageCount) {
            finished = true
            page = 0
        }
        return page
    }

    companion object {
        const val INTERVAL_MS = 5_000L
        const val ROUNDS = 3
    }
}

/**
 * [T-port-composer-hint-carousel] The focus-rotation pool: the text entries
 * ([ComposerPlaceholderRotation.POOL_SIZE]) followed by one entry per usage
 * page of the latest figures, as on iOS. An index at or past the text entries
 * is a usage page; with no figures (or fewer pages than when the index was
 * picked) it falls back to the default entry.
 */
internal object ComposerPlaceholderPool {
    fun size(latest: ContextUsageHint?): Int =
        ComposerPlaceholderRotation.POOL_SIZE + (latest?.pages()?.size ?: 0)

    /** The usage page entry [index] stands for, or null for a text entry. */
    fun usagePage(index: Int, latest: ContextUsageHint?): UsageHintPage? {
        if (index < ComposerPlaceholderRotation.POOL_SIZE) return null
        return latest?.pages()?.getOrNull(index - ComposerPlaceholderRotation.POOL_SIZE)
    }

    /** A text entry index safe to render: a stale usage index becomes the default. */
    fun textIndex(index: Int): Int =
        if (index in 0 until ComposerPlaceholderRotation.POOL_SIZE) index else ComposerPlaceholderRotation.DEFAULT_INDEX
}

// ─── Merged from ComposerInputModePrefs.kt ──────────────────────────────────────────────────

/**
 * [T-voice-input-mode-preference-android] Remembers the user's preferred
 * composer input method (voice vs text).
 *
 * Preference update rule:
 *  - tapping the mic so a recording actually STARTS → "voice"
 *  - sending a message composed without any voice session since the
 *    composer was last cleared → "text"
 *
 * Default is "text".
 *
 * [T-android-remove-auto-enter-voice] The preference is recorded but NO LONGER
 * drives auto-entry into voice on cold launch / new chat (that behaviour, added
 * in ec95451a, was removed). The composer always starts in text mode; voice is
 * entered only when the user taps the mic. The launch-landing one-shot
 * (markLaunchChatLanding / consumeLaunchLanding) and the unused isVoice() reader
 * were removed along with the auto-enter logic; only save() remains, still
 * called on send so the stored preference stays current for any future use.
 */
object ComposerInputModePrefs {

    private const val PREFS = "composer_input_prefs"
    private const val KEY_MODE = "composer_input_mode_pref"

    fun save(context: Context, voice: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, if (voice) "voice" else "text")
            .apply()
    }
}

// ─── Merged from ComposerPlaceholderRotation.kt ──────────────────────────────────────────────────

/**
 * [T-android-composer-placeholder-rotation] Picks which composer placeholder
 * to show, rotating through feature hints as the user focuses the input.
 *
 * Ported from iOS `457ee8cb4`. The rotation is driven by the **focus edge**,
 * not a timer: the composer is static most of the time, and the focus moment
 * is exactly when the user is looking at the field anyway. A timer would burn
 * work on every idle screen for the same discovery value.
 *
 * Semantics that matter (all pinned by `ComposerPlaceholderRotationTest`):
 *
 *  - **The default is element 0 of the pool**, a rotation candidate like any
 *    other, so it cycles back instead of being replaced forever.
 *  - **First focus is split by session state.** A session WITH messages
 *    rotates immediately — the default has nothing left to teach someone
 *    already mid-conversation. An EMPTY session keeps the default, because
 *    there it is the only line telling a new user what the field is for.
 *  - **Never repeats the entry already on screen**, so a rotation always
 *    visibly changes something.
 *  - **Accessibility**: with a screen reader active the index is pinned and
 *    never rotates — swapping the label would retrigger announcements over
 *    the user mid-read.
 *
 * State is a plain index so the caller can hold it in `rememberSaveable` and
 * survive rotation/process death. Pure and side-effect free: no Android types,
 * no Compose types, unit-testable without a device.
 */
internal object ComposerPlaceholderRotation {

    /** Index of the default placeholder within the pool. */
    const val DEFAULT_INDEX = 0

    /**
     * Total pool size = the default plus [HINT_COUNT] feature hints. Keep in
     * sync with the string list built in `composerPlaceholderPool`.
     */
    const val HINT_COUNT = 5
    const val POOL_SIZE = HINT_COUNT + 1

    /**
     * Resolve the next placeholder index on a focus gain.
     *
     * @param current        index currently on screen
     * @param hasFocusedBefore whether the composer has been focused before in
     *                       this session — distinguishes the first focus
     * @param sessionHasMessages whether the conversation already has messages
     * @param screenReaderOn whether a screen reader (TalkBack) is active
     * @param randomIndex    supplier of a candidate index in [0, bound);
     *                       injected so tests are deterministic
     * @param poolSize       entries to pick from. [T-port-composer-hint-carousel]
     *                       The text entries plus one per usage page of the
     *                       latest figures (ComposerPlaceholderPool.size).
     */
    fun nextIndex(
        current: Int,
        hasFocusedBefore: Boolean,
        sessionHasMessages: Boolean,
        screenReaderOn: Boolean,
        randomIndex: (Int) -> Int,
        poolSize: Int = POOL_SIZE,
    ): Int {
        // TalkBack: pin whatever is showing. Rotating re-announces the field
        // while the user may be partway through reading it.
        if (screenReaderOn) return current

        // First focus in an empty session: the default is the new user's only
        // explanation of the field. It survives the first tap.
        if (!hasFocusedBefore && !sessionHasMessages) return current

        // Pick anything but what's already displayed, so the rotation is
        // always visible. Drawing from a size-1 space below the cap and
        // shifting past `current` gives a uniform pick without a retry loop.
        // A current index outside the pool (usage figures gone since it was
        // picked) excludes nothing.
        if (current !in 0 until poolSize) return randomIndex(poolSize)
        val draw = randomIndex(poolSize - 1)
        return if (draw >= current) draw + 1 else draw
    }
}

// ─── Merged from ComposerPlaceholderUi.kt ──────────────────────────────────────────────────

/**
 * [T-android-composer-placeholder-rotation] Whether a screen reader (TalkBack)
 * is currently driving the UI.
 *
 * Registers a listener rather than sampling once, so toggling TalkBack
 * mid-session takes effect immediately — a user who turns it on partway
 * through must not keep getting placeholder rotations under it.
 */
@Composable
internal fun rememberScreenReaderEnabled(): Boolean {
    val context = LocalContext.current
    val am = remember(context) {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }
    var enabled by remember { mutableStateOf(am?.isTouchExplorationEnabled == true) }
    DisposableEffect(am) {
        if (am == null) return@DisposableEffect onDispose { }
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on ->
            enabled = on
        }
        am.addTouchExplorationStateChangeListener(listener)
        // Re-sync on (re)subscribe in case it changed while we weren't listening.
        enabled = am.isTouchExplorationEnabled
        onDispose { am.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/**
 * [T-android-composer-placeholder-rotation] Whether the user has asked the
 * system to suppress animations ("Remove animations" in Accessibility, or a
 * developer-options animator scale of 0).
 *
 * Android's equivalent of iOS Reduce Motion is the global animator duration
 * scale; a value of 0 means animations are off system-wide, and honouring it
 * is what turns the placeholder crossfade into an instant swap.
 */
internal fun animationsDisabled(context: Context): Boolean =
    android.provider.Settings.Global.getFloat(
        context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f

/**
 * [T-android-composer-placeholder-rotation] Resolve pool entry [index] to its
 * display string.
 *
 * Index 0 is the default "Message <soul> (@ to mention files)"; 1..N are the
 * feature hints. Resolving by index at RENDER time (rather than caching the
 * resolved string when the rotation happens) is deliberate: a soul rename or
 * an in-app locale switch then re-resolves to the correctly-translated current
 * hint instead of leaving a stale string on screen. iOS records the same
 * requirement in `refreshPlaceholderText`.
 *
 * The soul name is interpolated INTO each localized hint via `%1$s`, never
 * concatenated onto it, so every hint stays one translatable entry rather than
 * one per soul name.
 */
@Composable
internal fun composerPlaceholderText(index: Int, soulName: String): String {
    val hints = listOf(
        R.string.chat_input_placeholder_hint_1,
        R.string.chat_input_placeholder_hint_2,
        R.string.chat_input_placeholder_hint_3,
        R.string.chat_input_placeholder_hint_4,
        // Takes no `%1$s` — this hint points at the global Return Key setting
        // rather than the soul. `stringResource` ignores the surplus argument.
        R.string.chat_input_placeholder_hint_5,
    )
    // Defensive clamp: a saved index from a build with a larger pool must not
    // crash after the pool shrinks (rememberSaveable survives app upgrades).
    val safe = index.coerceIn(0, hints.size)
    return if (safe == ComposerPlaceholderRotation.DEFAULT_INDEX) {
        stringResource(R.string.chat_input_placeholder, soulName)
    } else {
        stringResource(hints[safe - 1], soulName)
    }
}

// ─── Merged from ContextTrimPlanner.kt ──────────────────────────────────────────────────

/**
 * [T-ctx-trim-ladder] Brings an outbound history down to a safe watermark by
 * trimming in order of cost, re-measuring after every level and stopping as
 * soon as it fits.
 *
 * Why: a long agent run on a thinking model (DeepSeek, max) replays every past
 * turn's reasoning_content. Field run 2026-10-07 (sid 41295a4c): the request
 * reached 341,873 prompt tokens on a 128K window while the meter, which did not
 * count reasoning, read 146,874. Compaction kept triggering and freed nothing,
 * because the slice it keeps (the last 3 user turns) was the whole task and the
 * reasoning inside it was never touched.
 *
 * Levels (each applies only to iterations older than the protected tail):
 *  - L1  reasoning_content of older assistant turns emptied, keeping the last
 *        K (2 -> 1 -> 0). DeepSeek accepts the empty field.
 *  - L2  large tool results moved to disk, replaced by the offload stub.
 *  - L3  large tool arguments (file_write bodies ...) replaced by the
 *        placeholder notice the write tools refuse to reuse.
 *  - SK  "skeleton": older iterations keep the call and a one-line result.
 *  - L4  oldest iterations collapsed into an index line each (only when the
 *        caller allows it - after a model summary had its chance).
 *
 * The result is described by a [State] of cut-off keys (tool-call ids), not by
 * the trimmed list: re-applying the same State to a history that only grew
 * yields byte-identical output for the old part, so the provider's prefix cache
 * keeps hitting until the next re-plan.
 *
 * Pure apart from [Offloader], which the caller backs with real disk writes.
 */
internal object ContextTrimPlanner {

    // ── Watermarks ──────────────────────────────────────────────────────

    enum class Mode { NORMAL, TIGHT, UNUSABLE }

    /** All values in measured (calibrated) tokens. */
    data class Watermarks(
        val window: Int,
        val fixed: Int,
        val reserve: Int,
        val hard: Int,
        val usable: Int,
        val trigger: Int,
        val target: Int,
        val mode: Mode,
    )

    const val TRIGGER_SHARE = 0.90
    const val TARGET_SHARE = 0.50

    /**
     * Watermarks from the space history can really use: the window minus the
     * output reserve minus the fixed system prompt + tool schemas. A share of
     * the raw window does not work for small windows - with a ~21K prompt, half
     * of a 32K window is below the fixed cost itself.
     */
    fun watermarks(window: Int, fixed: Int, maxOutput: Int): Watermarks? {
        if (window <= 0) return null
        val reserve = minOf(maxOutput.coerceAtLeast(1), maxOf(4096, (window * 0.15).toInt()))
        val hard = window - reserve
        val usable = hard - fixed
        val trigger = fixed + (usable * TRIGGER_SHARE).toInt()
        val target = fixed + (usable * TARGET_SHARE).toInt()
        val mode = when {
            usable <= 0 -> Mode.UNUSABLE
            usable < maxOf(8192, (window * 0.25).toInt()) -> Mode.TIGHT
            else -> Mode.NORMAL
        }
        return Watermarks(window, fixed, reserve, hard, usable, trigger, target, mode)
    }

    /**
     * [T-ctx-trim-trigger-margin] Where the ladder re-plans: the tier's
     * compaction line when it has one, but never above the watermark trigger
     * (fixed + 0.9 x usable), which sits a tenth of the usable space below
     * [Watermarks.hard].
     *
     * The line alone leaves no margin, or is past the hard watermark:
     *  - a capped group compacts at 85% of its window and the output reserve
     *    is 15%, so line == hard (128K: both 108,800);
     *  - a native window of 128K or more compacts at window - 20K, which is
     *    ABOVE hard (1M: line 980,000, hard 850,000 or higher) - the ladder
     *    did nothing until the request no longer left room for the answer.
     * A lower line still wins; no line (small windows) uses the watermark.
     */
    fun trigger(policyLine: Int?, watermarks: Watermarks): Int =
        policyLine?.takeIf { it > 0 }?.let { minOf(it, watermarks.trigger) } ?: watermarks.trigger

    /**
     * [T-ctx-trim-escalate] Whether a plan that could NOT collapse won back so
     * little that a summary / collapse should be tried now: it ended above the
     * midpoint between target and trigger, i.e. recovered less than half the
     * room. A small miss does not escalate.
     *
     * Needed since the trigger sits below the compaction line: such a plan
     * keeps the request just under that line, the compaction check never
     * fires, and the loop re-plans every few requests for less and less (iOS
     * on-device: 100,967 -> 83,919, -> 89,148, -> 91,946, three minutes apart,
     * each one changing the request prefix).
     */
    fun shouldEscalate(after: Int, target: Int, trigger: Int): Boolean =
        after > target + (trigger - target) / 2

    /**
     * [T-ctx-trim-plan-window] Whether a frozen plan made for [planWindow]
     * still belongs to the window the next request is judged by. A plan is
     * sized for one window: its target is half of THAT window's usable space,
     * so keeping it after the window changed applies the wrong amount of trim.
     *
     * iOS on-device 2026-10-07: a plan made under a 128K cap (reasoning emptied
     * on 343 of 345 iterations, 179 collapsed) stayed in force after the
     * session moved to a 1M window. Nothing re-planned, because the trimmed
     * request was far below the new trigger - 560 steps went out as 81K of 1M.
     */
    fun planFitsWindow(planWindow: Int?, window: Int): Boolean = planWindow == window

    /**
     * [T-ctx-trim-plan-marker] Whether a frozen plan made on top of the
     * compaction marker [planMarkerId] (null = no compaction) still belongs to
     * the history the next request is built from. A compaction or a revert
     * replaces what the plan was sized for. The plan's cut-off keys do not
     * always notice: they usually name RECENT iterations, which a compaction
     * keeps, so the old extents went on being applied to the kept slice of a
     * history that had just become small.
     */
    fun planFitsMarker(planMarkerId: String?, markerId: String?): Boolean = planMarkerId == markerId

    // ── Plan state ──────────────────────────────────────────────────────

    /**
     * Prefix of a cut-off that INCLUDES the named iteration ("up to and
     * including"). Used when a level covers every iteration that existed at
     * plan time: iterations added later must stay untouched until the next
     * re-plan, which a plain "all" would not guarantee.
     */
    const val THROUGH = "<="

    /**
     * Each field names where that level stops: the key of the first iteration
     * NOT affected, or [THROUGH] + the key of the last one affected
     * (null = level off).
     */
    data class State(
        val reasoningBefore: String? = null,
        val shrinkBefore: String? = null,
        val skeletonBefore: String? = null,
        val collapseBefore: String? = null,
    ) {
        val isEmpty: Boolean
            get() = reasoningBefore == null && shrinkBefore == null && skeletonBefore == null && collapseBefore == null
    }

    /**
     * [reasoningFloor] newest iterations whose reasoning L1 never empties (1 in
     * normal mode: the reasoning behind the step in flight; 0 in tight mode).
     * [keepRecentIterations] newest iterations L2/L3, skeleton and collapse
     * never touch.
     */
    data class Options(
        val stripReasoning: Boolean,
        val reasoningFloor: Int = 1,
        val keepRecentIterations: Int = 3,
        val allowCollapse: Boolean = false,
    )

    data class Result(
        val state: State,
        val messages: List<LLMMessage>,
        val before: Int,
        val after: Int,
        val target: Int,
        val steps: List<String>,
    ) {
        val reachedTarget: Boolean get() = after <= target
    }

    /** Writes [content] to disk and returns its guest path, or null when it could not. */
    fun interface Offloader {
        fun offload(content: String, toolId: String, toolName: String): String?
    }

    const val SHRINK_CHARS = 2_000
    const val SKELETON_CHARS = 200
    const val INDEX_MAX_LINES = 60

    // ── Iterations ──────────────────────────────────────────────────────

    /**
     * One iteration: an assistant message plus the user-role messages that
     * carry ITS tool results. [pinned] when one of those carriers also holds
     * text (a compaction summary appended after tool results): it can be
     * shortened but never removed.
     */
    internal data class Iteration(val key: String, val assistantIdx: Int, val carrierIdx: List<Int>, val pinned: Boolean)

    internal fun iterations(messages: List<LLMMessage>): List<Iteration> {
        val out = mutableListOf<Iteration>()
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            if (m.role != LLMMessage.Role.ASSISTANT) { i++; continue }
            val useIds = m.contentParts.filterIsInstance<AgentContentPart.ToolUse>().map { it.id }.toSet()
            val key = useIds.firstOrNull()?.let { m.contentParts.filterIsInstance<AgentContentPart.ToolUse>().first().id }
                ?: "msg:${m.dbMessageId ?: "$i:${m.content.hashCode()}"}"
            val carriers = mutableListOf<Int>()
            var pinned = false
            var j = i + 1
            while (useIds.isNotEmpty() && j < messages.size) {
                val c = messages[j]
                if (c.role != LLMMessage.Role.USER) break
                val results = c.contentParts.filterIsInstance<AgentContentPart.ToolResult>()
                if (results.isEmpty() || results.none { it.id in useIds }) break
                carriers += j
                if (c.contentParts.any { it !is AgentContentPart.ToolResult }) pinned = true
                j++
            }
            out += Iteration(key, i, carriers, pinned)
            i = maxOf(i + 1, j)
        }
        return out
    }

    /** Position of [key] among [its], or null when a stored key no longer exists. */
    private fun cut(key: String?, its: List<Iteration>): Int? = when {
        key == null -> 0
        key.startsWith(THROUGH) -> its.indexOfFirst { it.key == key.removePrefix(THROUGH) }.takeIf { it >= 0 }?.plus(1)
        else -> its.indexOfFirst { it.key == key }.takeIf { it >= 0 }
    }

    /** Key that affects iterations [0, n) of [its] - and no iteration added later. */
    internal fun keyFor(n: Int, its: List<Iteration>): String? = when {
        n <= 0 || its.isEmpty() -> null
        n >= its.size -> THROUGH + its.last().key
        else -> its[n].key
    }

    // ── Apply ───────────────────────────────────────────────────────────

    /** Re-applies [state]; null when one of its keys is gone (the caller re-plans). */
    fun apply(messages: List<LLMMessage>, state: State, offloader: Offloader): List<LLMMessage>? {
        if (state.isEmpty) return messages
        val its = iterations(messages)
        val rB = cut(state.reasoningBefore, its) ?: return null
        val shB = cut(state.shrinkBefore, its) ?: return null
        val skB = cut(state.skeletonBefore, its) ?: return null
        val cB = cut(state.collapseBefore, its) ?: return null

        val iterOf = HashMap<Int, Int>()
        its.forEachIndexed { n, it -> iterOf[it.assistantIdx] = n; it.carrierIdx.forEach { c -> iterOf[c] = n } }

        val collapsed = mutableListOf<Iteration>()
        val out = ArrayList<LLMMessage>(messages.size)
        var indexHostAt = -1
        for ((idx, m) in messages.withIndex()) {
            val n = iterOf[idx]
            if (n == null) {
                out += m
                // The index lands on the last plain user message before the collapsed range.
                if (m.role == LLMMessage.Role.USER && n == null && collapsed.isEmpty()) indexHostAt = out.size - 1
                continue
            }
            val it = its[n]
            if (n < cB && !it.pinned) {
                if (idx == it.assistantIdx) collapsed += it
                continue
            }
            out += transform(m, n, rB, shB, skB, offloader)
        }
        if (collapsed.isEmpty()) return out
        val index = indexText(collapsed.map { messages[it.assistantIdx] to it.carrierIdx.map { c -> messages[c] } })
        return if (indexHostAt >= 0) {
            val host = out[indexHostAt]
            out[indexHostAt] = host.copy(
                content = host.content + "\n\n" + index,
                contentParts = if (host.contentParts.isEmpty()) host.contentParts else host.contentParts + AgentContentPart.Text(index),
            )
            out
        } else {
            out.apply { add(0, LLMMessage(role = LLMMessage.Role.USER, content = index, contentParts = listOf(AgentContentPart.Text(index)))) }
        }
    }

    private fun transform(m: LLMMessage, n: Int, rB: Int, shB: Int, skB: Int, offloader: Offloader): LLMMessage {
        val skeleton = n < skB
        val shrink = n < shB
        var msg = m
        if (n < rB && m.role == LLMMessage.Role.ASSISTANT && !m.reasoningContent.isNullOrEmpty()) {
            msg = msg.copy(reasoningContent = "")
        }
        if (!skeleton && !shrink) return msg
        // Levels stack: a skeleton is built on top of the shrunk part, so it is
        // never larger than what shrink alone produced.
        val parts = msg.contentParts.map { part ->
            when (part) {
                is AgentContentPart.ToolUse -> {
                    val shrunk = if (shrink) shortenArgs(part, SHRINK_CHARS, offloader) else part
                    if (skeleton) shortenArgs(shrunk, SKELETON_CHARS, offloader) else shrunk
                }
                is AgentContentPart.ToolResult -> {
                    val shrunk = if (shrink) shrinkResult(part, offloader) else part
                    if (skeleton) skeletonResult(shrunk, offloader) else shrunk
                }
                else -> part
            }
        }
        return if (parts == msg.contentParts) msg else msg.copy(contentParts = parts)
    }

    private fun isStub(text: String) =
        text.trimStart().startsWith(ContextOffload.OFFLOADED_PREFIX) || ContextOffload.isOffloadPlaceholder(text)

    private fun approxTokens(text: String) = com.openminis.app.data.ContextSizeMeter.estimateTokens(text)

    private fun shrinkResult(part: AgentContentPart.ToolResult, offloader: Offloader): AgentContentPart.ToolResult {
        if (part.content.length <= SHRINK_CHARS || isStub(part.content)) return part
        val bytes = part.content.toByteArray().size
        val path = offloader.offload(part.content, part.id, part.name)
        val text = if (!path.isNullOrEmpty()) ContextOffload.stub(approxTokens(part.content), bytes, path)
        else part.content.take(SHRINK_CHARS / 2) + "\n… [${part.content.length - SHRINK_CHARS / 2} chars omitted from context]"
        return part.copy(content = text)
    }

    private fun skeletonResult(part: AgentContentPart.ToolResult, offloader: Offloader): AgentContentPart.ToolResult {
        val noImage = part.copy(imageData = null, imageMimeType = null)
        if (part.content.length <= SKELETON_CHARS + 100 && part.imageData == null) return part
        // An offload stub is already as small as a skeleton; rewriting it would only grow it.
        if (isStub(part.content)) return if (part.imageData == null) part else noImage
        val path = if (isStub(part.content)) {
            Regex("""saved to: (\S+)""").find(part.content)?.groupValues?.get(1)
        } else {
            offloader.offload(part.content, part.id, part.name)
        }
        val first = part.content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith(ContextOffload.OFFLOADED_PREFIX) }
            ?.take(SKELETON_CHARS).orEmpty()
        val where = if (!path.isNullOrEmpty()) "full output: $path" else "full output kept in the chat history"
        val image = if (part.imageData != null) " (image omitted)" else ""
        val text = "$first\n[shortened for context$image; $where]"
        if (text.length >= part.content.length) return if (part.imageData == null) part else noImage
        return noImage.copy(content = text)
    }

    private fun shortenArgs(part: AgentContentPart.ToolUse, limit: Int, offloader: Offloader): AgentContentPart.ToolUse {
        val input = part.input
        var changed = false
        val copy = JSONObject(input.toString())
        for (k in input.keys()) {
            val v = input.opt(k) as? String ?: continue
            if (v.length <= limit || ContextOffload.isOffloadPlaceholder(v)) continue
            val notice = "${ContextOffload.SYSTEM_REMINDER_OPEN}\n${ContextOffload.SYSTEM_NOTICE_TAG} Argument shortened for context " +
                "(${v.length} chars). NOT the real value - never reuse it. Starts with: «${v.take(120)}»\n</system-reminder>"
            // The notice is ~330 chars: on a short value it would GROW the
            // request (field 2026-10-07: 25 of 71 shortened shell commands).
            if (notice.length >= v.length) continue
            val path = if (limit >= SHRINK_CHARS) offloader.offload(v, part.id, "args-${part.name}") else null
            copy.put(
                k,
                if (!path.isNullOrEmpty()) ContextOffload.prunedArgumentNotice(approxTokens(v), v.toByteArray().size, path) else notice,
            )
            changed = true
        }
        return if (changed) part.copy(input = copy, isOffloadedArgument = true) else part
    }

    /** Index of collapsed iterations, newest [INDEX_MAX_LINES] listed, the rest counted. */
    internal fun indexText(collapsed: List<Pair<LLMMessage, List<LLMMessage>>>): String {
        val lines = mutableListOf<String>()
        val counts = linkedMapOf<String, Int>()
        for ((n, pair) in collapsed.withIndex()) {
            val (asst, carriers) = pair
            val results = carriers.flatMap { it.contentParts.filterIsInstance<AgentContentPart.ToolResult>() }.associateBy { it.id }
            val uses = asst.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
            if (uses.isEmpty()) {
                val note = asst.content.ifBlank { asst.contentParts.filterIsInstance<AgentContentPart.Text>().joinToString(" ") { it.text } }
                if (note.isNotBlank()) lines += "#${n + 1} note: ${note.lineSequence().first().take(100)}"
                continue
            }
            for (u in uses) {
                counts[u.name] = (counts[u.name] ?: 0) + 1
                val title = u.input.optString("tool_title").ifBlank { u.input.optString("title") }.take(60)
                val r = results[u.id]
                val status = when { r == null -> "no result"; r.isError -> "error"; else -> "ok" }
                val path = r?.content?.let { Regex("""saved to: (\S+)""").find(it)?.groupValues?.get(1) }
                lines += "#${n + 1} ${u.name}${if (title.isNotBlank()) " «$title»" else ""} → $status${path?.let { " ($it)" } ?: ""}"
            }
        }
        val shown = lines.takeLast(INDEX_MAX_LINES)
        val hidden = lines.size - shown.size
        val summary = counts.entries.joinToString(", ") { "${it.key} ${it.value}" }
        return buildString {
            append(ContextOffload.SYSTEM_REMINDER_OPEN).append('\n')
            append("[Minis context] ${collapsed.size} earlier step(s) of this task were collapsed to save context. ")
            append("Their full records stay in the chat history; large outputs are on disk at the paths shown. ")
            append("Do not redo a step listed as ok - read its output path if you need the details.\n")
            append("Tool calls collapsed: $summary\n")
            if (hidden > 0) append("($hidden older line(s) not listed)\n")
            shown.forEach { append(it).append('\n') }
            append("</system-reminder>")
        }
    }

    // ── Plan ────────────────────────────────────────────────────────────

    private class Level(val label: String, val max: Int, val set: (State, String?) -> State)

    /**
     * Cheapest level first. Each level affects the SMALLEST oldest-first
     * prefix of iterations that reaches [target] (binary search, re-measuring
     * every candidate); only a level that cannot reach it alone runs at full
     * extent and hands over to the next one. A restore pass then shrinks the
     * earlier, full-extent levels back as far as the result still fits - so
     * as much context as possible survives. [measure] returns measured tokens
     * for a history (fixed cost included).
     */
    fun plan(
        messages: List<LLMMessage>,
        target: Int,
        options: Options,
        measure: (List<LLMMessage>) -> Int,
        offloader: Offloader,
    ): Result {
        val its = iterations(messages)
        val before = measure(messages)
        val steps = mutableListOf<String>()
        if (before <= target || its.isEmpty()) return Result(State(), messages, before, before, target, steps)

        val older = (its.size - options.keepRecentIterations.coerceIn(0, its.size)).coerceAtLeast(0)
        val levels = listOf(
            Level("L1", if (options.stripReasoning) (its.size - options.reasoningFloor.coerceAtLeast(0)).coerceAtLeast(0) else 0) { s, k -> s.copy(reasoningBefore = k) },
            Level("L2L3", older) { s, k -> s.copy(shrinkBefore = k) },
            Level("SK", older) { s, k -> s.copy(skeletonBefore = k) },
            Level("L4", if (options.allowCollapse) older else 0) { s, k -> s.copy(collapseBefore = k) },
        )
        val extent = IntArray(levels.size)
        fun stateOf(ext: IntArray) = levels.indices.fold(State()) { s, i -> levels[i].set(s, keyFor(ext[i], its)) }
        val memo = HashMap<List<Int>, Pair<List<LLMMessage>, Int>>()
        fun eval(ext: IntArray): Pair<List<LLMMessage>, Int> = memo.getOrPut(ext.toList()) {
            val applied = apply(messages, stateOf(ext), offloader) ?: messages
            applied to measure(applied)
        }
        /** Smallest n in [lo, hi] for level [li] that fits, others as in [extent]; assumes hi fits. */
        fun smallest(li: Int, lo: Int, hi: Int): Int {
            var a = lo
            var b = hi
            while (a < b) {
                val mid = (a + b) / 2
                val e = extent.copyOf().also { it[li] = mid }
                if (eval(e).second <= target) b = mid else a = mid + 1
            }
            return b
        }
        fun label(li: Int) = "${levels[li].label}(n=${extent[li]}/${its.size})"

        var size = before
        var last = -1
        for (li in levels.indices) {
            val lv = levels[li]
            if (lv.max <= 0) continue
            last = li
            val full = extent.copyOf().also { it[li] = lv.max }
            val fullSize = eval(full).second
            // A level with nothing to take here (no reasoning, no large output) is skipped, not logged.
            if (fullSize >= size) continue
            if (fullSize > target) {
                extent[li] = lv.max
                steps += "${label(li)}:${size - fullSize}"
                size = fullSize
                continue
            }
            extent[li] = smallest(li, 1, lv.max)
            val m = eval(extent).second
            steps += "${label(li)}:${size - m}"
            size = m
            break
        }
        // Restore: the level that closed the gap may have freed more than the
        // earlier ones needed to give up. Most informative first: skeleton,
        // then offload, then reasoning.
        if (size <= target && last > 0) {
            for (li in (0 until last).reversed()) {
                if (extent[li] == 0) continue
                val was = extent[li]
                val zero = extent.copyOf().also { it[li] = 0 }
                extent[li] = if (eval(zero).second <= target) 0 else smallest(li, 0, was)
                if (extent[li] == was) continue
                val m = eval(extent).second
                steps += "${levels[li].label}-restore(n=${extent[li]}/${its.size}):${size - m}"
                size = m
            }
        }
        val (current, after) = eval(extent)
        return Result(stateOf(extent), current, before, after, target, steps)
    }
}

// ─── Merged from ContextUsageHint.kt ──────────────────────────────────────────────────

/**
 * [T-android-context-usage-hint] Context-window pressure model shared by the
 * composer placeholder line, the composer's inner glow and the voice panel's
 * status row.
 *
 * Port of iOS `ContextUsage` / `ContextUsageHint` / `ContextTierCrossingTracker`
 * (`src/ios/Agent/Chat/ChatModels.swift`). Product behaviour is aligned with
 * iOS; the shape is Kotlin-idiomatic (data classes + a pure tracker) rather
 * than a transliteration, and everything here is free of Android and Compose
 * types so it is unit-testable on the JVM — the same rule
 * [ComposerPlaceholderRotation] follows.
 */

/**
 * A single observation of "how full is the context window right now".
 *
 * [usedTokens] is the most recent `LLMUsage.latestContextTokens` (input +
 * cache_read + cache_creation — the whole prompt the model just saw), and
 * [windowTokens] is the effective window for the bound model/group. Both come
 * from data the ViewModel already tracks; nothing here re-derives them.
 */
internal data class ContextUsage(
    val usedTokens: Int,
    val windowTokens: Int,
) {
    val fraction: Double
        get() = if (windowTokens > 0) usedTokens.toDouble() / windowTokens.toDouble() else 0.0

    val percent: Int
        get() = Math.round(fraction * 100).toInt()

    val tier: Tier
        get() = when {
            fraction >= CRITICAL_FRACTION -> Tier.CRITICAL
            fraction >= WARNING_FRACTION -> Tier.WARNING
            else -> Tier.NORMAL
        }

    /**
     * Pressure bands. [rank] exists so "crossed upward" is an ordering
     * comparison rather than a `when` over pairs — the tracker below leans on
     * it, and adding a band later cannot silently break that comparison.
     */
    internal enum class Tier(val rank: Int) {
        /** Below 70%: no line, no glow. The feature is invisible here. */
        NORMAL(0),

        /** 70–80%: amber numbers, amber glow. */
        WARNING(1),

        /** 80%+: red numbers, red glow, breathing. */
        CRITICAL(2),
    }

    companion object {
        const val WARNING_FRACTION = 0.70
        const val CRITICAL_FRACTION = 0.80

        /**
         * Build a usage from raw numbers, or null when there is nothing
         * trustworthy to show.
         *
         * Guarding on BOTH numbers being positive is what keeps a provider
         * that omits usage from rendering a confident "Context 0% used · 0 /
         * 0". A missing measurement must look like no measurement, never like
         * an empty context.
         */
        fun from(usedTokens: Int, windowTokens: Int?): ContextUsage? {
            if (usedTokens <= 0) return null
            if (windowTokens == null || windowTokens <= 0) return null
            return ContextUsage(usedTokens, windowTokens)
        }
    }
}

/**
 * A formatted line ready for the composer, plus the substrings that get the
 * highlight colour.
 *
 * [generation] is the de-duplication key: the UI renders a given generation
 * once, so a recomposition (or a second collector) cannot re-show a line the
 * user already dismissed by typing. [highlights] carries the number segments
 * so the renderer can colour them without re-parsing the localized string —
 * which would break the moment a translation reorders `%1$s` / `%2$s`.
 */
internal data class ContextUsageHint(
    val generation: Int,
    val text: String,
    val highlights: List<String>,
    val tier: ContextUsage.Tier,
    /**
     * [T-quota-composer-hint] The current model's quota, already worded
     * ("Balance CN¥36.51", "Quota 5h 14% / 7d 32% used"), appended after the
     * context figures with " · ". null = the line is the context figures alone.
     */
    val quotaText: String? = null,
    /** The figure inside [quotaText], tinted by [quotaTier]. */
    val quotaHighlight: String? = null,
    /**
     * The quota's own tier, independent of the context tier: a nearly full
     * context says nothing about the balance.
     */
    val quotaTier: ContextUsage.Tier = ContextUsage.Tier.NORMAL,
)

/**
 * Short token counts ("850", "1.2k", "124k").
 *
 * Deliberately identical to iOS `TokenCountFormatter.short` and to the
 * existing `formatTokenCount` in `ChatAssistantMessageUI.kt`, so the usage
 * line and the per-message usage capsule can never disagree on rounding for
 * the same number.
 */
internal object TokenCountFormatter {
    fun short(count: Int): String {
        if (count < 1000) return count.toString()
        val k = count / 1000.0
        return if (k % 1.0 == 0.0) "${k.toInt()}k"
        else String.format(java.util.Locale.US, "%.1fk", k)
    }

    /** The "124k / 200k" half of the line. */
    fun sizePair(usage: ContextUsage): String =
        "${short(usage.usedTokens)} / ${short(usage.windowTokens)}"
}

/**
 * [T-android-context-usage-hint] Decides whether a usage update is a "first
 * crossing" worth interrupting the user for while a turn is still running.
 *
 * The window is not monotonic — switching models changes the denominator and a
 * compaction shrinks the numerator — so the tier can rise, fall and rise again
 * inside one session. Hence: every observation updates [lastTier], but only an
 * observation whose tier is strictly higher than the previous one counts as a
 * crossing.
 *
 * The rate limit is deliberately narrower than "one hint per interval". A
 * value flapping across a line (69% → 71% → 69% → 71%) must not flash the
 * composer, but a genuine climb past a HIGHER line — warning then critical a
 * second later — is exactly the case the user most needs to see, so it is
 * never held back. That is why [observe] only applies the interval when the
 * new tier is at or below the last tier actually announced.
 *
 * Pure: `now` is injected rather than read from the clock, so the tests below
 * can pin rate-limit behaviour without sleeping.
 */
internal class ContextTierCrossingTracker {

    var lastTier: ContextUsage.Tier = ContextUsage.Tier.NORMAL
        private set

    var lastFiredAtMs: Long? = null
        private set

    var lastFiredTier: ContextUsage.Tier? = null
        private set

    /**
     * Session load / switch: adopt the loaded tier WITHOUT treating it as a
     * crossing, and forget the previous session's rate-limit state.
     *
     * Without this, opening an already-long session would fire a hint for a
     * threshold the user crossed hours ago on a different conversation.
     */
    fun reset(tier: ContextUsage.Tier = ContextUsage.Tier.NORMAL) {
        lastTier = tier
        lastFiredAtMs = null
        lastFiredTier = null
    }

    /**
     * Record [tier] and report whether it is an upward crossing that is not
     * rate-limited.
     *
     * Does NOT mark a firing — the caller still has veto conditions the
     * tracker cannot see (composer non-empty, sub-agent turn, no usage data).
     * It calls [markFired] only once a hint is genuinely shown, so a
     * suppressed crossing does not start the cooldown.
     */
    fun observe(tier: ContextUsage.Tier, nowMs: Long): Boolean {
        val previous = lastTier
        lastTier = tier
        if (tier.rank <= previous.rank) return false
        val firedAt = lastFiredAtMs
        if (firedAt != null &&
            nowMs - firedAt < MIN_INTERVAL_MS &&
            tier.rank <= (lastFiredTier?.rank ?: -1)
        ) {
            return false
        }
        return true
    }

    fun markFired(tier: ContextUsage.Tier, nowMs: Long) {
        lastFiredAtMs = nowMs
        lastFiredTier = tier
    }

    /**
     * True when a hint for this same tier fired less than [MIN_INTERVAL_MS]
     * ago — lets the loop-END path skip a duplicate line for a tier the
     * mid-loop path just announced.
     */
    fun recentlyFired(tier: ContextUsage.Tier, nowMs: Long): Boolean {
        val firedAt = lastFiredAtMs ?: return false
        if (lastFiredTier != tier) return false
        return nowMs - firedAt < MIN_INTERVAL_MS
    }

    companion object {
        /** Matches iOS `ContextTierCrossingTracker.minInterval` (4s). */
        const val MIN_INTERVAL_MS = 4_000L
    }
}

/**
 * [T-android-context-usage-hint] Lifecycle of the usage line once it is on
 * screen: how focus and typing retire it.
 *
 * Kept separate from the tracker (which decides when a line is BORN) because
 * the two answer different questions and are driven by different events — the
 * tracker by usage arriving, this by the user touching the composer. Both are
 * pure so `ContextUsageHintTest` can drive them without a device.
 *
 * The rule set, mirroring iOS `rotatePlaceholderOnFocus`:
 *
 *  - **The first focus gain is protected.** After a reply lands, the keyboard
 *    may auto-raise (a Minis setting) and that focus must not instantly swap
 *    the line the user has not read yet. The user's own first tap is protected
 *    identically — there is no way to tell the two apart, and both deserve it.
 *  - **The second focus retires it**, handing the label back to the ordinary
 *    rotation. Someone who has focused twice without typing is no longer
 *    reading the figure.
 *  - **Typing retires it immediately** — the line is behind the text anyway.
 *  - **Clearing the text does not revive it.** A retired hint is gone for
 *    good; only a NEW generation (the next reply) shows a line again.
 */
internal class ContextUsageHintLifecycle {

    /** The line currently owning the placeholder, or null. */
    var hint: ContextUsageHint? = null
        private set

    /** Whether the one protected focus gain has been spent. */
    var hasProtectedFirstFocus: Boolean = false
        private set

    /** Highest generation this lifecycle has ever accepted. */
    private var lastAcceptedGeneration: Int = -1

    val isShowing: Boolean get() = hint != null

    /**
     * Adopt [candidate], unless it is one we have already shown.
     *
     * The generation guard is what makes `present` idempotent: Compose may
     * re-deliver the same state object across recompositions, and without this
     * a line the user retired by typing would pop back on the next frame.
     */
    fun present(candidate: ContextUsageHint): Boolean {
        if (candidate.generation <= lastAcceptedGeneration) return false
        lastAcceptedGeneration = candidate.generation
        hint = candidate
        hasProtectedFirstFocus = false
        return true
    }

    /**
     * A composer "focus event" happened. Returns true when the caller should
     * run its normal placeholder rotation, false when the usage line keeps
     * the label.
     *
     * [T-android-context-usage-second-focus] "Focus event" deliberately means
     * *the keyboard came up*, not *Compose's `isFocused` went true* — see
     * [onImeShown] for why the two are not the same on Android.
     */
    fun onFocusGained(): Boolean {
        if (hint == null) return true
        if (!hasProtectedFirstFocus) {
            hasProtectedFirstFocus = true
            return false
        }
        retire()
        return true
    }

    /**
     * [T-android-context-usage-second-focus] The IME became visible.
     *
     * This is the Android-correct trigger for "the user came back to the
     * composer", and it exists because the original wiring — an `isFocused`
     * rising edge — cannot see the second visit at all.
     *
     * The back key hides the keyboard *without clearing Compose focus*
     * (verified on a Pixel 4a: `mInputShown=false` while the field still held
     * focus). So after the first visit `isFocused` stays true forever, no
     * rising edge is ever produced again, and the hint sat in its protected
     * state permanently instead of retiring on the second visit. iOS does not
     * have this problem because dismissing the keyboard resigns first
     * responder, making focus loss and keyboard dismissal the same event.
     *
     * Keyboard visibility is the signal that actually tracks the user's
     * attention here, and it is correct for every dismissal route (back key,
     * scroll-to-dismiss, send, an explicit `clearFocus`), not just the ones a
     * focus edge happens to cover.
     */
    fun onImeShown(): Boolean = onFocusGained()

    /**
     * Composer text changed. Any non-empty text retires the line.
     *
     * Note the asymmetry with [present]: going back to empty does NOT restore
     * it, because [lastAcceptedGeneration] has already consumed that
     * generation. "Cleared the box" is not "asked to see the figure again".
     */
    fun onTextChanged(text: String) {
        if (text.isNotEmpty()) retire()
    }

    /** Drop the line without consuming a generation (session switch). */
    fun retire() {
        hint = null
        hasProtectedFirstFocus = false
    }
}

// ─── Merged from DebugUsageHint.kt ──────────────────────────────────────────────────

/**
 * [T-port-composer-hint-debug] Builds a composer usage line with chosen
 * figures, for the DEV_TOOLS-only `debug.chat.usageHint` RPC. The real line
 * only appears once a turn ends at 70%+ of the context window, which is hard
 * to reach on a device; this lets the carousel (two pages, 5 s x 3 rounds),
 * the shrink-to-fit and the focus-rotation entries be tested directly.
 * Pure, so the figures are unit-tested.
 */
internal object DebugUsageHint {
    /** A quota page given by hand. */
    data class Quota(val text: String, val highlight: String?, val tier: ContextUsage.Tier)

    /** The fake quota page used when the caller asks for one without text. */
    val SAMPLE_QUOTA = Quota("Quota 5h 14% / 7d 32% used", "14%", ContextUsage.Tier.NORMAL)

    fun build(generation: Int, percent: Int, windowTokens: Int, quota: Quota?): ContextUsageHint {
        val window = windowTokens.coerceAtLeast(1)
        val used = (window.toLong() * percent.coerceIn(1, 100) / 100).toInt().coerceAtLeast(1)
        val usage = ContextUsage(used, window)
        return ContextUsageHint(
            generation = generation,
            text = "",
            highlights = listOf("${usage.percent}%", TokenCountFormatter.sizePair(usage)),
            tier = usage.tier,
            quotaText = quota?.text,
            quotaHighlight = quota?.highlight,
            quotaTier = quota?.tier ?: ContextUsage.Tier.NORMAL,
        )
    }

    fun tierOf(name: String?): ContextUsage.Tier = when (name?.lowercase()) {
        "warning", "low" -> ContextUsage.Tier.WARNING
        "critical", "depleted" -> ContextUsage.Tier.CRITICAL
        else -> ContextUsage.Tier.NORMAL
    }
}

// ─── Merged from EmptyTurnFallback.kt ──────────────────────────────────────────────────

/**
 * [T-b13-empty-fallback] Where an EMPTY model turn goes in a model group: the
 * same model again (the one-shot reminder round), the next member, or nowhere
 * (the empty-response hint). Port of iOS B13 (f1d0c3beb, 488fb0128,
 * 1086b19a9) and the B15 wording (6ba931405).
 *
 * Before, the agent loop's empty-turn branch never looked at the group: it
 * sent the reminder round to the model that had just come back empty and then
 * ended the run with a hint, so one member answering with nothing (or with
 * thinking only) ended the turn although the group had others. Only a stream
 * that closed with no stop reason at all reached the fallback, as an error.
 *
 * Rules (iOS owner decisions, adapted to Android's loop):
 * - Always strategy: an empty response is a failure, so the member that came
 *   back empty is not asked again. The reminder round, when one is still due,
 *   goes to the next member; otherwise the plain request does.
 * - Default (limited) strategy: the reminder round stays on the same model,
 *   as before; once it is spent (or not possible) the next member answers
 *   instead of the run ending.
 * - No other member to go to, or a session pinned to one model (no group, as
 *   iOS never leaves a pinned model): exactly the old behaviour.
 * - A DSML leak never reaches this (the loop resends it first), and a
 *   refusal or a max-tokens stop is not an empty turn.
 *
 * Which members count as "other" is the loop's business: one that already
 * came back empty in this streak, or failed with an error, is never picked
 * again, so the walk visits each member at most once.
 */
internal object EmptyTurnFallback {

    enum class Route {
        /** The one-shot reminder round, on the model that came back empty. */
        SAME_MODEL_REMINDER,

        /** The same request (reminder spent or not possible) to the next member. */
        NEXT_MEMBER,

        /** The reminder round, sent to the next member instead of this one. */
        NEXT_MEMBER_WITH_REMINDER,

        /** Nowhere to go: the empty-response hint with Resume. */
        GIVE_UP,
    }

    /**
     * @param inGroup the session is bound to a model group (not a pinned entry).
     * @param reminderDue the reminder round is still possible this run
     *   ([EmptyTurnRecovery.plan] is not GiveUp).
     * @param hasNextMember the group has a member that is neither the current
     *   one nor already spent in this streak.
     */
    fun route(
        strategy: FallbackStrategy,
        inGroup: Boolean,
        reminderDue: Boolean,
        hasNextMember: Boolean,
    ): Route {
        if (!inGroup || !hasNextMember) {
            return if (reminderDue) Route.SAME_MODEL_REMINDER else Route.GIVE_UP
        }
        return when (strategy) {
            FallbackStrategy.always ->
                if (reminderDue) Route.NEXT_MEMBER_WITH_REMINDER else Route.NEXT_MEMBER
            FallbackStrategy.default ->
                if (reminderDue) Route.SAME_MODEL_REMINDER else Route.NEXT_MEMBER
        }
    }

    /** Whether [route] moves to another member (and so needs a candidate). */
    fun movesMember(route: Route): Boolean =
        route == Route.NEXT_MEMBER || route == Route.NEXT_MEMBER_WITH_REMINDER

    /**
     * [T-ios-reasoning-only-wording] The reason recorded for the member in the
     * fallback trail ("⚠️ <model>: <reason>"). A model that answered with its
     * thinking only did not return "an empty response" in the user's eyes, and
     * is not an overloaded server: say what happened. Internal, verbatim text
     * like the trail's other reasons ("Rate limited", provider details).
     */
    fun trailReason(reasoningOnly: Boolean, afterReminder: Boolean): String = when {
        reasoningOnly && afterReminder ->
            "The model returned only its thinking after a tool result, even after a reminder — no reply and no tool call."
        reasoningOnly -> "The model returned only its thinking — no reply and no tool call."
        afterReminder -> "Empty response, even after a reminder"
        else -> "Empty response"
    }
}

// ─── Merged from EmptyTurnRecovery.kt ──────────────────────────────────────────────────

/**
 * [T-android-empty-turn-reminder-any-tail] Decides how the agent loop recovers
 * from an EMPTY model turn — one where the model produced no text and no tool
 * call and simply stopped.
 *
 * Background: `runAgentLoop` classifies a turn as empty via
 * `turnText.isEmpty() && toolCalls.isEmpty()`. An empty turn is high-
 * probability whenever the conversation ends on a STATEMENT the model owes no
 * reply to. The one that triggered this work is a finished sub-agent callback
 * (`<agent_callback kind="finished">…</agent_callback>`): a "here is a completed
 * result" notice with no instruction. Measured against the live Codex endpoint
 * on GPT-5.6 Terra, a request ending on such a callback returned an empty turn
 * 7/10 times, so a user tapping Retry saw "Model returned an empty response"
 * over and over. A tool_result tail (the case the loop already handled) is just
 * one shape of the same problem.
 *
 * The recovery is: remove the empty assistant turn, append a one-shot
 * `<system-reminder>` nudging the model to continue, and retry ONE round. This
 * object decides WHERE the reminder goes; the loop performs the mutation and
 * owns the once-per-run guard. Keeping the decision pure lets it be tested
 * without a live ViewModel (mirrors ImageInputPreflight / FallbackPulseGate).
 */
internal object EmptyTurnRecovery {

    /** The reminder text appended to the history tail before the retry. */
    const val REMINDER: String =
        "\n\n<system-reminder>The previous response was empty. You MUST continue: " +
            "if the last message was a completed sub-agent result or a notice, acknowledge it " +
            "and take the next step or give the user a final answer; if more work is needed, " +
            "respond with the next tool call(s). Do not return an empty response.</system-reminder>"

    /**
     * [T-android-empty-turn-stopreason] Whether a finished turn should be
     * classified as EMPTY (and thus routed to recovery/hint).
     *
     * This encodes the loop's gate `!hasVisibleContent && finishedCleanly` in
     * one testable place, and — following the iOS reasoning-only guidance
     * (AIChatViewModel.swift isEmptyResponse) — makes the stop-reason
     * NARROWING explicit rather than implicit:
     *
     *  - A turn that produced text or a tool call is never empty.
     *  - Only a terminal "done" stop (`stop` / `end_turn`, or a null stop from
     *    a dropped stream that the caller has already decided to treat as
     *    empty) qualifies. `length` (truncated / max_output_tokens — often the
     *    reasoning phase) does NOT: it kept partial output.
     *  - `tool_calls` / `tool_use` with no tool call and no text IS empty
     *    ([T-android-tooluse-stop-no-calls]): there is nothing to continue the
     *    loop with, so without this the run just stopped silently.
     *  - REASONING-ONLY is the subtle case. Interleaved thinking emits a
     *    reasoning block and then CALLS A TOOL — that turn ends on `tool_use`
     *    and has [hasToolCall] = true, so it is excluded here (and in the loop
     *    it never even reaches this gate: the block is inside
     *    `if (toolCalls.isEmpty())`). A reasoning-only turn that ends on
     *    `stop` / `end_turn` with no text and no tool is the stall the user
     *    sees as "the run just ended" — it IS empty and must be recovered.
     *    So `hasReasoning` is deliberately NOT an exemption on this API:
     *    reasoning that "led somewhere" already shows up as a tool call.
     *
     * @param hasText the turn produced non-blank assistant text.
     * @param hasToolCall the turn produced at least one tool call.
     * @param stopReason the turn's finish reason (provider string, or null).
     */
    fun shouldClassifyAsEmptyTurn(hasText: Boolean, hasToolCall: Boolean, stopReason: String?): Boolean {
        if (hasText || hasToolCall) return false
        if (stopReason == null || stopReason == "stop" || stopReason == "end_turn") return true
        // [T-android-tooluse-stop-no-calls] A turn that ended on
        // `tool_calls` / `tool_use` but delivered NO tool call (and no text) is
        // empty too. The stop reason promises a tool round, yet there is nothing
        // to execute, so the loop's `toolCalls.isEmpty()` branch breaks out and
        // the run ended silently with a blank bubble — no recovery, no hint.
        // Seen when a provider (or our parser) drops the tool-call payload but
        // keeps the finish reason. Routing it through empty-turn recovery
        // gives it the one-shot nudge and then the empty-response hint.
        // Mirrors iOS 910708619.
        return stopReason == "tool_calls" || stopReason == "tool_use"
    }

    /**
     * [T-android-empty-turn-this-turn] Whether THIS turn produced visible text.
     *
     * The empty-turn gate used to look at the whole bubble (`accumulatedText` and
     * every block in it). A bubble spans every turn of a run, so once an earlier
     * turn had written narration or called a tool, a later turn that stopped
     * with nothing still counted as "has content": no nudge, no hint, and the
     * run ended silently. Field case: DeepSeek turn 45 (sid 41295a4c) stopped
     * after reasoning only, in a bubble that already held 40+ tool calls.
     *
     * Only this turn's own output decides — its streamed text, plus text blocks
     * appended since [turnStartBlockIndex]. Tool calls are not counted: this
     * gate is only reached when the turn made none. Same rule the sub agent
     * closing request already uses.
     */
    fun turnHasText(turnText: String, blocks: List<AssistantBlock>, turnStartBlockIndex: Int): Boolean =
        turnText.isNotBlank() ||
            blocks.drop(turnStartBlockIndex.coerceAtLeast(0)).any { it.kind == "text" && it.content.isNotBlank() }

    sealed class Plan {
        /**
         * Append [REMINDER] onto the content-part at [tailIndex]/[partIndex] of
         * the tail user turn (a tool_result part, or a text part).
         */
        data class NudgeExistingPart(val tailIndex: Int, val partIndex: Int, val isToolResult: Boolean) : Plan()

        /** Append a fresh standalone user turn carrying [REMINDER]. */
        object AppendStandalone : Plan()

        /** No user turn to nudge (e.g. tail is an assistant row) — fall through to the error hint. */
        object GiveUp : Plan()
    }

    /**
     * @param history the FULL agentHistory, whose LAST entry is the empty
     *   assistant turn the loop just appended. The tail being replied to is at
     *   `history[size - 2]`.
     * @param alreadyFired the once-per-run guard: when true, always [GiveUp].
     */
    fun plan(history: List<LLMMessage>, alreadyFired: Boolean): Plan {
        if (alreadyFired) return Plan.GiveUp
        if (history.size < 2) return Plan.GiveUp
        val tailIdx = history.size - 2
        val tail = history[tailIdx]
        if (tail.role != LLMMessage.Role.USER) return Plan.GiveUp

        // Prefer a trailing tool_result (original behaviour, byte-identical for
        // that case), else a trailing text part (the callback / statement tail).
        val trPartIdx = tail.contentParts.indexOfLast { it is AgentContentPart.ToolResult }
        if (trPartIdx >= 0) return Plan.NudgeExistingPart(tailIdx, trPartIdx, isToolResult = true)
        val txtPartIdx = tail.contentParts.indexOfLast { it is AgentContentPart.Text }
        if (txtPartIdx >= 0) return Plan.NudgeExistingPart(tailIdx, txtPartIdx, isToolResult = false)

        // User tail with no appendable part (image-only, etc.).
        return Plan.AppendStandalone
    }
}

// ─── Merged from EvictionGuard.kt ──────────────────────────────────────────────────

/**
 * [T-android-vm-evict-busy] Decides whether a cached ChatViewModel has work in
 * flight that evicting it would destroy.
 *
 * `ChatViewModelStore` used `SessionActivityTracker.activeSessions` as its only
 * "busy" signal. That set is populated by `setActive`, which the stream job
 * calls AFTER `SessionConcurrencyManager.acquireSlot()` — a suspension that
 * can last as long as other sessions hold the slots. So a session whose user
 * had just pressed Send, but which was still waiting for a slot, read as idle;
 * an LRU trim or an `onTrimMemory` pass cleared its store, `viewModelScope`
 * was cancelled with it, and the send died with no reply and no error.
 *
 * The same blind spot covers a parent whose background sub agents are still
 * running or queued (post 519c76825 the parent's own turn ends while children
 * run): the tracker is inactive, the parent is evicted, `onCleared` removes
 * its queued-delegation starter and the child's finished callback has nowhere
 * to land.
 *
 * Every input here is a fact the view model can read synchronously; the
 * store combines this with the tracker rather than replacing it.
 */
internal object EvictionGuard {

    fun hasWorkInFlight(
        isStreaming: Boolean,
        streamJobActive: Boolean,
        hasAgentWork: Boolean,
        hasQueuedPrompts: Boolean,
    ): Boolean = isStreaming || streamJobActive || hasAgentWork || hasQueuedPrompts
}

// ─── Merged from FolderOfferFormat.kt ──────────────────────────────────────────────────

/**
 * [T-autogroup-folder-titles] Up to three titles of a group's recent chats
 * after its line in the title prompt's group offer, so the title model sees
 * what a group holds instead of guessing from its name (iOS FolderOfferFormat,
 * 72350576a; Windows `folder_offer_format`, whose vectors the test reuses).
 *
 * `"Work" — desc (e.g. "A", "B", "C")`, or `"Work" (e.g. …)` without a
 * description. Groups with no titled chat keep their plain line, and when no
 * group has one the offer is byte-identical to before.
 */
internal object FolderOfferFormat {
    const val TITLES_PER_FOLDER = 3

    /** Rows kept per group: more than shown, so duplicates can be skipped. */
    const val FETCH_PER_FOLDER = 8
    const val TITLE_LIMIT = 40

    /** Only the most recently active groups get titles, so many groups do not grow the prompt without bound. */
    const val MAX_TITLED_FOLDERS = 20

    /** The prompt sentence added when some group carries example titles (iOS, verbatim). */
    const val EXAMPLES_NOTE =
        "The quoted titles after \"e.g.\" are recent chats already in that group; " +
            "they only show the group's topic and are not titles for this conversation. "

    /** A group's newest member activity (ms) and its newest-first titles. */
    data class Recent(val activity: Long, val titles: List<String>)

    /**
     * One title as shown: one line, spaces collapsed, at most [TITLE_LIMIT]
     * code points (an ellipsis marks a cut). Double quotes become single as on
     * iOS; on top of that, as the group names already are
     * ([ChatViewModel.promptSafe]), nothing in a title may close the quoted
     * example or the bracketed group list: curly double quotes become single,
     * brackets become parentheses, `;` (the list separator) a comma, braces
     * and backslashes a space, and invisible format/bidi controls go.
     */
    fun clean(title: String): String {
        val flat = title
            .replace(Regex("[\"\\u201C\\u201D]"), "'")
            .replace('[', '(').replace(']', ')')
            .replace(';', ',')
            .replace(Regex("[{}\\\\]"), " ")
            .replace(Regex("\\p{Cf}"), "")
            .replace(Regex("[\\s\\u2028\\u2029\\u00A0]+"), " ")
            .trim()
        if (flat.codePointCount(0, flat.length) <= TITLE_LIMIT) return flat
        return flat.substring(0, flat.offsetByCodePoints(0, TITLE_LIMIT - 1)) + "…"
    }

    /** Newest-first titles → at most [TITLES_PER_FOLDER] distinct, non-empty ones. */
    fun sample(titles: List<String>): List<String> {
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (t in titles) {
            val c = clean(t)
            if (c.isEmpty() || !seen.add(c)) continue
            out += c
            if (out.size == TITLES_PER_FOLDER) break
        }
        return out
    }

    /**
     * The offer's lines with titles added: [ids] in offer order, [lines] their
     * plain lines; [recent]: group id → activity and titles. Returns the lines
     * and how many got titles.
     */
    fun lines(ids: List<String>, lines: List<String>, recent: Map<String, Recent>): Pair<List<String>, Int> {
        // Most recent activity first; equal times keep the offer order.
        val titled = ids.withIndex()
            .mapNotNull { (i, id) -> recent[id]?.let { Triple(i, id, it.activity) } }
            .sortedWith(compareByDescending<Triple<Int, String, Long>> { it.third }.thenBy { it.first })
            .take(MAX_TITLED_FOLDERS)
            .map { it.second }
            .toSet()
        var withTitles = 0
        val out = ids.zip(lines).map { (id, line) ->
            val r = recent[id]?.takeIf { id in titled } ?: return@map line
            val examples = sample(r.titles)
            if (examples.isEmpty()) return@map line
            withTitles++
            line + " (e.g. " + examples.joinToString(", ") { "\"$it\"" } + ")"
        }
        return out to withTitles
    }

    /**
     * Rows from [com.openminis.app.data.db.ChatDao.titledFolderMembers]
     * (ordered by group, newest first) → at most [perFolder] titles per group,
     * activity = its newest titled member.
     */
    fun group(rows: List<FolderMemberTitle>, perFolder: Int = FETCH_PER_FOLDER): Map<String, Recent> {
        val out = linkedMapOf<String, Recent>()
        for (row in rows) {
            val r = out[row.folderId]
            when {
                r == null -> out[row.folderId] = Recent(row.updatedAt, listOf(row.title))
                r.titles.size < perFolder -> out[row.folderId] = r.copy(titles = r.titles + row.title)
            }
        }
        return out
    }
}

// ─── Merged from ImageInputPreflight.kt ──────────────────────────────────────────────────

/**
 * [T-android-image-input-preflight] Pre-send modality check for image
 * attachments.
 *
 * Background: attaching an image while a text-only model is selected (the
 * official DeepSeek chat/reasoner endpoints, many relay-hosted text models)
 * used to go straight to the network and come back as
 * `Provider error: [400] 请求参数或所选模型能力不受支持`. The OpenAI-compatible
 * provider already swaps pixels for a text placeholder when
 * [LLMModel.hasImageInput] is false, so that path never 400s — but it also
 * silently drops the user's image, and a model whose vision support is only
 * *assumed* (no declared modality list; provider-table default) still gets
 * the image and fails upstream.
 *
 * This object is pure so both rules are unit-testable:
 *  - [check]: refuse to send at all when the selected model is known not to
 *    accept images. The composer keeps its text and attachments so the user
 *    can switch model (or enable "Image Input" in the model's details when
 *    the catalogue is simply wrong) and resend.
 *  - [isLikelyImageRejection]: when an upstream 400 arrives for a request
 *    that did carry images, tell the user *why* it most likely failed instead
 *    of showing the bare provider message.
 */
internal object ImageInputPreflight {

    /**
     * [T-android-image-path-metadata] The BLOCKING half of this object
     * (`Verdict` / `check`) was removed.
     *
     * It refused the send outright when the resolved model's declared input
     * modalities lacked "image". That is not how the app decides image
     * handling anywhere else, and iOS has no such gate: the modality question
     * is answered while BUILDING the request, where a non-vision model gets a
     * text placeholder carrying the image's sandbox path instead of pixels.
     * Blocking at the composer pre-empted that, and also pre-empted every
     * non-vision use of an attachment — `shell_execute` can inspect, convert
     * or OCR the file without any vision capability at all.
     *
     * What remains is the REACTIVE half, which never blocks anything: when a
     * request that carried images comes back 400, it recognises the shape so
     * the user gets an actionable hint instead of the bare upstream string.
     */
    fun isLikelyImageRejection(error: Throwable, sentImageCount: Int): Boolean {
        if (sentImageCount <= 0) return false
        val providerError = error as? LLMError.ProviderError ?: return false
        if (providerError.httpStatus != 400) return false
        // [T-android-image-hint-not-for-tool-pairing] A 400 whose message names
        // a different cause is not about images. Field report: DeepSeek's
        // `No tool output found for tool call call_01_…` (a malformed parallel
        // tool run, T-android-responses-toolresult-image-split) came back with
        // "this model may not support image input" appended, and the user went
        // looking at the image. Generic 400s keep the hint: a text-only model's
        // rejection often names nothing at all ("请求参数或所选模型能力不受支持").
        val detail = providerError.detail.lowercase()
        return NOT_IMAGE_RELATED.none { it in detail }
    }

    /** Fragments of 400 messages that point at something other than the images. */
    private val NOT_IMAGE_RELATED = listOf(
        "tool output", "tool call", "tool_call", "function_call", "tool_result", "tool_use",
        "context length", "context_length", "maximum context", "prompt is too long", "too many tokens",
    )
}

// ─── Merged from InputAttachment.kt ──────────────────────────────────────────────────

data class InputAttachment(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val uri: Uri,
    val mimeType: String,
    val kind: Kind,
) {
    enum class Kind { IMAGE, DOCUMENT }

    val isImage: Boolean get() = kind == Kind.IMAGE
}

// ─── Merged from LazyReadAloudPlayer.kt ──────────────────────────────────────────────────

/**
 * [T-android-selection-readaloud] Defers [ReadAloudPlayer] construction until
 * something is actually spoken.
 *
 * Constructing the player binds an Android TextToSpeech engine (see
 * `TextToSpeechManager.init`), so building one eagerly for every ChatScreen
 * would pay that cost for the large majority of sessions that never use the
 * selection toolbar's Read Aloud action. [shutdown] is safe to call when
 * nothing was ever created.
 */
internal class LazyReadAloudPlayer(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var player: ReadAloudPlayer? = null

    /**
     * Speak [text] sentence by sentence, stopping anything already in flight.
     *
     * Only the explicit long-press actions call this, so read-replies is
     * switched on (and un-muted) first — otherwise, with the default settings,
     * the player drops the text silently. [T-android-readaloud-menu-enable]
     */
    fun speak(text: String) {
        if (text.isBlank()) return
        com.openminis.app.speech.VoiceOutputState.activateForExplicitReadAloud()
        val p = player ?: synchronized(this) {
            player ?: ReadAloudPlayer(appContext).also { player = it }
        }
        p.speak(text)
    }

    /**
     * [T-android-readaloud-stop-stale] Stop whatever is currently being read.
     *
     * Deliberately does NOT bind an engine: if nothing has ever been spoken
     * there is nothing to stop, and constructing a TTS engine here would undo
     * the whole point of the lazy player (an unused ChatScreen never binds
     * one). Idempotent and safe to call on every new reply.
     */
    fun stop() {
        player?.stop()
    }

    /** Release the engine if one was ever bound. Idempotent. */
    fun shutdown() {
        synchronized(this) {
            player?.shutdown()
            player = null
        }
    }
}

// ─── Merged from MarkdownClipboard.kt ──────────────────────────────────────────────────

/**
 * Clipboard helpers that mirror the iOS chat-message context menu actions
 * (`SelectableMarkdownTextView` → `copyMarkdown` / `copyRichText`).
 *
 * iOS exports rich text as RTF via `UIPasteboard.setData(rtfData, forPasteboardType: "public.rtf")`.
 * Android's pasteboard equivalent is [ClipData.newHtmlText], which carries both
 * plain-text and HTML payloads — Notes, Gmail, Docs, etc. all consume the HTML
 * variant when pasting "with formatting." We render the markdown to a
 * lightweight HTML subset locally instead of pulling in a parser dependency.
 *
 * Three flavors:
 *   - [copyPlain]    — markdown stripped to readable plain text (default Copy)
 *   - [copyMarkdown] — raw markdown source verbatim
 *   - [copyRichText] — HTML+plain dual payload for paste-with-formatting
 */
object MarkdownClipboard {

    fun copyPlain(context: Context, markdown: String, label: String = "Message") {
        val plain = markdownToPlainText(markdown)
        clipboard(context).setPrimaryClip(ClipData.newPlainText(label, plain))
    }

    fun copyMarkdown(context: Context, markdown: String, label: String = "Markdown") {
        clipboard(context).setPrimaryClip(ClipData.newPlainText(label, markdown))
    }

    fun copyRichText(context: Context, markdown: String, label: String = "Rich Text") {
        val plain = markdownToPlainText(markdown)
        val html = markdownToHtml(markdown)
        clipboard(context).setPrimaryClip(ClipData.newHtmlText(label, plain, html))
    }

    private fun clipboard(context: Context): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /**
     * Strip markdown syntax, leaving the visible reading order intact. Goals:
     *   - emphasis/strong/strike markers removed, content kept
     *   - inline code / fenced code unwrapped (content preserved verbatim)
     *   - link `[text](url)` → `text` (drop URL, matches iOS Copy behavior)
     *   - images `![alt](url)` → `alt`
     *   - heading `#`, blockquote `>`, list bullets `- * +` and ordered `1.` stripped
     *   - tables flattened to ` | ` separated lines (good enough for paste targets that don't render markdown)
     */
    fun markdownToPlainText(markdown: String): String {
        val out = StringBuilder()
        val lines = markdown.lines()
        var i = 0
        var inFence = false
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence
                i++; continue
            }
            if (inFence) {
                out.append(line).append('\n')
                i++; continue
            }
            // Heading
            val heading = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
            if (heading != null) {
                out.append(stripInline(heading.groupValues[2])).append('\n')
                i++; continue
            }
            // Blockquote
            if (trimmed.startsWith(">")) {
                out.append(stripInline(trimmed.removePrefix(">").trimStart())).append('\n')
                i++; continue
            }
            // Unordered list
            val ul = Regex("^([-*+])\\s+(.*)$").find(trimmed)
            if (ul != null) {
                out.append("• ").append(stripInline(ul.groupValues[2])).append('\n')
                i++; continue
            }
            // Ordered list
            val ol = Regex("^(\\d+)\\.\\s+(.*)$").find(trimmed)
            if (ol != null) {
                out.append(ol.groupValues[1]).append(". ")
                    .append(stripInline(ol.groupValues[2])).append('\n')
                i++; continue
            }
            // Horizontal rule
            if (Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(trimmed)) {
                out.append('\n'); i++; continue
            }
            // Table separator row (|---|---|): drop entirely.
            if (Regex("^\\|?\\s*:?-{3,}.*$").matches(trimmed) && trimmed.contains('-')) {
                i++; continue
            }
            // Generic line — strip inline markers
            out.append(stripInline(line)).append('\n')
            i++
        }
        return out.toString().trimEnd('\n')
    }

    /**
     * Minimal markdown → HTML for paste-with-formatting. Covers the syntax
     * the chat actually produces. Not a full CommonMark renderer; we lean on
     * reader-side leniency (Gmail/Notes/Docs all tolerate sparse HTML).
     */
    fun markdownToHtml(markdown: String): String {
        val sb = StringBuilder()
        sb.append("<html><body>")
        val lines = markdown.lines()
        var i = 0
        var inFence = false
        var fenceLang: String? = null
        val fenceBuf = StringBuilder()
        var listType: String? = null  // "ul" | "ol"
        fun closeList() {
            if (listType != null) {
                sb.append("</$listType>")
                listType = null
            }
        }
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            // Fenced code
            val fenceMatch = Regex("^(```|~~~)(.*)$").find(trimmed)
            if (fenceMatch != null) {
                if (!inFence) {
                    closeList()
                    inFence = true
                    fenceLang = fenceMatch.groupValues[2].trim().ifEmpty { null }
                    fenceBuf.clear()
                } else {
                    inFence = false
                    sb.append("<pre><code")
                    fenceLang?.let { sb.append(" class=\"language-").append(escapeHtml(it)).append("\"") }
                    sb.append(">").append(escapeHtml(fenceBuf.toString().trimEnd('\n'))).append("</code></pre>")
                    fenceBuf.clear()
                }
                i++; continue
            }
            if (inFence) {
                fenceBuf.append(line).append('\n')
                i++; continue
            }
            // Blank line: close paragraph context
            if (trimmed.isEmpty()) {
                closeList()
                i++; continue
            }
            // Heading
            val heading = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
            if (heading != null) {
                closeList()
                val level = heading.groupValues[1].length
                sb.append("<h").append(level).append(">")
                    .append(inlineToHtml(heading.groupValues[2]))
                    .append("</h").append(level).append(">")
                i++; continue
            }
            // Blockquote
            if (trimmed.startsWith(">")) {
                closeList()
                sb.append("<blockquote>")
                    .append(inlineToHtml(trimmed.removePrefix(">").trimStart()))
                    .append("</blockquote>")
                i++; continue
            }
            // Horizontal rule
            if (Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(trimmed)) {
                closeList(); sb.append("<hr/>"); i++; continue
            }
            // Unordered list
            val ul = Regex("^([-*+])\\s+(.*)$").find(trimmed)
            if (ul != null) {
                if (listType != "ul") { closeList(); sb.append("<ul>"); listType = "ul" }
                sb.append("<li>").append(inlineToHtml(ul.groupValues[2])).append("</li>")
                i++; continue
            }
            // Ordered list
            val ol = Regex("^(\\d+)\\.\\s+(.*)$").find(trimmed)
            if (ol != null) {
                if (listType != "ol") { closeList(); sb.append("<ol>"); listType = "ol" }
                sb.append("<li>").append(inlineToHtml(ol.groupValues[2])).append("</li>")
                i++; continue
            }
            // Plain paragraph line
            closeList()
            sb.append("<p>").append(inlineToHtml(line)).append("</p>")
            i++
        }
        closeList()
        if (inFence && fenceBuf.isNotEmpty()) {
            sb.append("<pre><code>").append(escapeHtml(fenceBuf.toString().trimEnd('\n'))).append("</code></pre>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    /** Strip inline markdown markers, return readable text. */
    private fun stripInline(s: String): String {
        // [T-md-inline-html] <br> -> newline, paired style tags dropped (their
        // text kept), before the code unwrap below so `<br>` in code survives.
        var t = com.openminis.app.ui.markdown.InlineHtmlTags.toPlain(s)
        // Images: ![alt](url) → alt
        t = Regex("!\\[([^\\]]*)\\]\\([^)]*\\)").replace(t, "$1")
        // Links: [text](url) → text
        t = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)").replace(t, "$1")
        // Inline code: `code` → code
        t = Regex("`([^`]+)`").replace(t, "$1")
        // Bold/italic/strike — order matters: longest delimiter first.
        t = Regex("\\*\\*\\*(.+?)\\*\\*\\*").replace(t, "$1")
        t = Regex("___(.+?)___").replace(t, "$1")
        t = Regex("\\*\\*(.+?)\\*\\*").replace(t, "$1")
        t = Regex("__(.+?)__").replace(t, "$1")
        t = Regex("(?<!\\*)\\*(?!\\*)([^*\\n]+)\\*").replace(t, "$1")
        t = Regex("(?<!_)_(?!_)([^_\\n]+)_").replace(t, "$1")
        t = Regex("~~(.+?)~~").replace(t, "$1")
        return t
    }

    /** Inline markdown → HTML, escaping non-markdown characters. */
    private fun inlineToHtml(s: String): String {
        // Pull code spans out first so their contents aren't re-escaped/processed.
        val codePlaceholder = " CODE "
        val codes = mutableListOf<String>()
        var work = Regex("`([^`]+)`").replace(s) { m ->
            codes.add(m.groupValues[1])
            "$codePlaceholder${codes.size - 1}$codePlaceholder"
        }
        // [T-md-inline-html] <br> and paired whitelisted tags become real HTML;
        // every other `<` is escaped below and shows as text.
        val htmlTags = mutableListOf<String>()
        work = com.openminis.app.ui.markdown.InlineHtmlTags.protectForHtml(work, htmlTags, HTML_TAG_MARK)
        work = escapeHtml(work)
        // Images: ![alt](url)
        work = Regex("!\\[([^\\]]*)\\]\\(([^)]+)\\)").replace(work) { m ->
            "<img alt=\"${m.groupValues[1]}\" src=\"${m.groupValues[2]}\"/>"
        }
        // Links: [text](url)
        work = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)").replace(work) { m ->
            "<a href=\"${m.groupValues[2]}\">${m.groupValues[1]}</a>"
        }
        // Bold ***x*** / ___x___
        work = Regex("\\*\\*\\*(.+?)\\*\\*\\*").replace(work, "<strong><em>$1</em></strong>")
        work = Regex("___(.+?)___").replace(work, "<strong><em>$1</em></strong>")
        // Bold **x**
        work = Regex("\\*\\*(.+?)\\*\\*").replace(work, "<strong>$1</strong>")
        work = Regex("__(.+?)__").replace(work, "<strong>$1</strong>")
        // Italic *x* / _x_
        work = Regex("(?<!\\*)\\*(?!\\*)([^*\\n]+)\\*").replace(work, "<em>$1</em>")
        work = Regex("(?<!_)_(?!_)([^_\\n]+)_").replace(work, "<em>$1</em>")
        // Strikethrough ~~x~~
        work = Regex("~~(.+?)~~").replace(work, "<del>$1</del>")
        // Restore code spans (and HTML-escape their inside)
        work = Regex("$codePlaceholder(\\d+)$codePlaceholder").replace(work) { m ->
            "<code>${escapeHtml(codes[m.groupValues[1].toInt()])}</code>"
        }
        if (htmlTags.isNotEmpty()) {
            work = Regex("$HTML_TAG_MARK(\\d+)$HTML_TAG_MARK").replace(work) { m -> htmlTags[m.groupValues[1].toInt()] }
        }
        return work
    }

    /** Private-use marker around protected HTML tags; never in model text. */
    private const val HTML_TAG_MARK = '\uE000'

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}

// ─── Merged from MathLineBreaker.kt ──────────────────────────────────────────────────

/**
 * [T-android-math-overflow-wrap] Where a formula wider than the line may be
 * broken into rows. Port of iOS `SwiftMathRenderer.splitAtTopLevelRelations`
 * (7238a39d4); pure so `MathLineBreakerTest` runs it on the JVM.
 */
internal object MathLineBreaker {

    /**
     * Relations a formula may break before. Commands must not be followed by
     * a letter, so `\le` does not match `\left`.
     */
    private val BREAK_RELATIONS = listOf(
        "\\Leftrightarrow", "\\Rightarrow", "\\approx", "\\equiv",
        "\\neq", "\\leq", "\\geq", "\\ne", "\\le", "\\ge",
        "=", "<", ">",
    )

    /**
     * Split [latex] before every relation that sits at brace depth 0 and
     * outside `\left…\right`. Environments are left whole: their `&` / `\\`
     * layout must not be cut apart. Returns `[latex]` when there is nowhere
     * to break.
     */
    fun splitAtTopLevelRelations(latex: String): List<String> {
        if (latex.contains("\\begin")) return listOf(latex)
        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var leftRight = 0
        var i = 0

        fun matches(token: String, at: Int): Boolean {
            if (!latex.startsWith(token, at)) return false
            val next = at + token.length
            return !(token[0] == '\\' && next < latex.length && latex[next].isLetter())
        }

        while (i < latex.length) {
            val c = latex[i]
            // An escaped brace is a literal glyph, not grouping.
            if (c == '\\' && i + 1 < latex.length && (latex[i + 1] == '{' || latex[i + 1] == '}')) {
                current.append(c).append(latex[i + 1])
                i += 2
                continue
            }
            if (c == '{') depth++ else if (c == '}') depth = maxOf(0, depth - 1)
            if (matches("\\left", i)) leftRight++ else if (matches("\\right", i)) leftRight = maxOf(0, leftRight - 1)
            if (depth == 0 && leftRight == 0 && current.isNotBlank()) {
                val token = BREAK_RELATIONS.firstOrNull { matches(it, i) }
                if (token != null) {
                    pieces.add(current.toString())
                    current.setLength(0)
                    current.append(token)
                    i += token.length
                    continue
                }
            }
            current.append(c)
            i++
        }
        if (current.isNotEmpty()) pieces.add(current.toString())
        return pieces
    }

    /**
     * Greedy row packing, as iOS `wrapAtRelations`: keep appending pieces to
     * the current row while [fits] says the joined row fits, else start a new
     * row with the piece. A lone piece that does not fit still gets its own
     * row (the caller scales it down).
     */
    suspend fun packRows(pieces: List<String>, fits: suspend (String) -> Boolean): List<String> {
        val rows = mutableListOf<String>()
        var current = ""
        for (piece in pieces) {
            val candidate = current + piece
            if (current.isNotEmpty() && !fits(candidate)) {
                rows.add(current)
                current = piece
            } else {
                current = candidate
            }
        }
        if (current.isNotEmpty()) rows.add(current)
        return rows
    }
}

// ─── Merged from MessageBoundsRegistry.kt ──────────────────────────────────────────────────

/**
 * Per-conversation registry of currently-laid-out message bounds. Lets the
 * outer [androidx.compose.foundation.text.selection.SelectionContainer]'s
 * custom [androidx.compose.ui.platform.TextToolbar] look up which message
 * a selection rect belongs to so it can offer "Copy Markdown" / "Copy Rich
 * Text" of the originating message's full markdown.
 *
 * The registry is keyed by message id and stores the message's bounds in
 * window coordinates plus a snapshot of its joined markdown source. Each
 * `AssistantMessageView` writes into the registry via `onGloballyPositioned`
 * and clears its entry on dispose.
 *
 * Lookup picks the message whose bounds vertically contain the y-center of
 * the query rect — selection rects are typically short horizontal ranges,
 * but they cleanly belong to a single message in the y axis. When no entry
 * matches (cross-message selection or a rect that misses everything) the
 * lookup returns null and the caller falls back to the system Copy bar
 * behavior.
 */
class MessageBoundsRegistry {
    /**
     * One slot per (messageId, slotKey) so a message that flattens into
     * multiple LazyColumn items (text block + tool pills + ...) can register
     * each piece independently. The markdown is the message-wide markdown,
     * shared across all of that message's slots.
     */
    private data class Entry(val bounds: Rect, val markdown: String)
    private val entries = mutableMapOf<Pair<String, String>, Entry>()

    fun put(messageId: String, slotKey: String, bounds: Rect, markdown: String) {
        entries[messageId to slotKey] = Entry(bounds, markdown)
    }

    fun remove(messageId: String, slotKey: String) {
        entries.remove(messageId to slotKey)
    }

    /**
     * Find the markdown for the message whose bounds vertically contain
     * [rect]'s y-center. Returns null if no slot matches.
     */
    fun markdownAt(rect: Rect): String? {
        val y = (rect.top + rect.bottom) / 2f
        return entries.values.firstOrNull { y in it.bounds.top..it.bounds.bottom }?.markdown
    }

    /**
     * Direct messageId → markdown lookup, used by the MinisTextKit selection
     * toolbar so it can resolve the parent message's source even when both
     * selection endpoints' shards have scrolled out of the viewport. Falls
     * back to any registered slot for that message (any slot's markdown ==
     * the message-wide joined markdown by construction in buildFlatChatItems).
     */
    fun markdownFor(messageId: String): String? {
        return entries.entries.firstOrNull { it.key.first == messageId }?.value?.markdown
    }
}

val LocalMessageBoundsRegistry = compositionLocalOf<MessageBoundsRegistry?> { null }

// ─── Merged from OrphanedRunningToolGate.kt ──────────────────────────────────────────────────

/**
 * [T-android-orphaned-running-tool-spin] Decides whether a tool block may keep
 * rendering as RUNNING.
 *
 * Measured on a Pixel 6: opening a 144-message session took the app from 0% to
 * a sustained 85-130% of a CPU core while completely idle, producing 730 frames
 * in 8 seconds (91 fps) with no stream, no task and nothing on screen changing.
 * Backgrounding the app dropped it to 0%; reopening that one session brought it
 * straight back. Smaller sessions never did it.
 *
 * The session contained exactly one tool block still marked RUNNING, left over
 * from a sub-agent delegation whose completion never landed. A RUNNING block
 * draws a `rememberInfiniteTransition` shimmer (ChatAssistantMessageUI
 * `toolPillShimmer`), and an infinite transition invalidates every frame for as
 * long as it is composed — so one orphaned block pins the whole UI at display
 * refresh rate forever, heating the device and draining battery with no work
 * being done.
 *
 * Two things conspired to make the state permanent:
 *
 *  - `delegateBlockOverrides` (ChatViewModel) is a process-lifetime map keyed
 *    by tool id. It is cleared per-tool on completion but never on session
 *    load, so a delegate that never completed keeps re-applying RUNNING over
 *    the freshly rebuilt blocks on every single reload.
 *  - The DB rebuild path itself can never produce RUNNING (it derives
 *    SUCCESS / FAILED / CANCELLED from the persisted tool_result), so the
 *    stale override is the only source of truth that says "still running" —
 *    and nothing ever contradicts it.
 *
 * The rule below closes that: RUNNING is a claim about *right now*, so it must
 * be backed by a live job. A block whose job is gone is not running, whatever
 * the persisted or overridden status says.
 */
internal object OrphanedRunningToolGate {

    /**
     * Whether [status] may be shown as-is, given whether the owning job is
     * still live.
     *
     * Only the spinner-bearing states are gated. A terminal status is a
     * historical fact and is always allowed through — re-deriving those would
     * risk rewriting a correct SUCCESS into something else.
     *
     * @param isLiveRun true when this view model is actively streaming this
     *   very turn. During a live turn the registry may not have registered the
     *   job yet (the block is created before the child starts), so the
     *   in-flight case is trusted and never downgraded.
     * @param jobIsAlive whether a job backing this block is present and active
     *   in `AgentJobRegistry`.
     */
    fun resolve(
        status: ToolBlockStatus?,
        isLiveRun: Boolean,
        jobIsAlive: Boolean,
    ): ToolBlockStatus? {
        if (status == null) return null
        if (!isSpinner(status)) return status
        if (isLiveRun || jobIsAlive) return status
        // Orphaned: the run it belonged to is not happening any more. TIMEOUT
        // rather than FAILED — we do not know that the tool errored, only that
        // its outcome never came back, which is exactly what a timeout is. It
        // also renders without a spinner, which is the point.
        return ToolBlockStatus.TIMEOUT
    }

    /** The states that draw an infinite shimmer / progress animation. */
    fun isSpinner(status: ToolBlockStatus?): Boolean =
        status == ToolBlockStatus.RUNNING ||
            status == ToolBlockStatus.STREAMING ||
            status == ToolBlockStatus.PENDING

    /**
     * Whether a stale override may be applied on top of a rebuilt block.
     *
     * `mergeDelegateOverrides` re-applies the in-memory override after every
     * reload. A spinner override from a dead run must not win over the status
     * the DB just derived, or the orphan is immortal.
     */
    fun overrideMayApply(
        overrideStatus: ToolBlockStatus?,
        isLiveRun: Boolean,
        jobIsAlive: Boolean,
    ): Boolean {
        if (!isSpinner(overrideStatus)) return true
        return isLiveRun || jobIsAlive
    }
}

// ─── Merged from PromptAttachments.kt ──────────────────────────────────────────────────

/**
 * [T-headless-own-attachments] Which files a prompt sends, and whether it
 * empties the composer.
 *
 * The composer's staged files belong to the user's own send. A prompt nobody
 * typed - a scheduled fire, a CLI / RPC prompt, a sub agent's result or
 * progress report, a steer nudge, the answer to a deferred question - sends
 * only the files it brought and leaves the composer alone, as iOS leaves the
 * draft text alone (2a06de66a). iOS still shares its `attachments` staging
 * area with programmatic prompts on purpose, because its CLI and Shortcuts
 * stage files there first; Android's headless callers pass their files as
 * an argument instead, so nothing needs the shared area.
 */
internal object PromptAttachments {
    fun <T> of(ownsComposer: Boolean, own: List<T>, composer: List<T>): List<T> = if (ownsComposer) composer else own

    fun clearsComposer(ownsComposer: Boolean): Boolean = ownsComposer
}

// ─── Merged from ProviderAvailability.kt ──────────────────────────────────────────────────

/**
 * [ISS-036, T-provider-live-check] Whether the provider behind the model a
 * running task uses can still take a request. Port of iOS
 * ProviderAvailabilityRules (a45ef439b).
 *
 * The agent loop builds its provider object once, with the API key in memory,
 * and reuses it for every round. Removing or disabling the provider in
 * Settings did not stop a task that was already running: "I deleted every
 * provider, the models are all gone, and it can still be used". So the loop
 * asks this before every request.
 *
 * Only the provider instance decides. A hidden model entry is not a reason,
 * and neither is a model entry that vanished while its provider is still
 * present and enabled (a model-list refresh) - the request would still work.
 */
internal object ProviderAvailability {

    enum class Problem { REMOVED, DISABLED }

    /** Who a provider object was built for, kept because removal deletes the entry too. */
    data class Identity(val entryId: String, val instanceId: String, val instanceLabel: String, val modelName: String)

    fun problem(instanceExists: Boolean, instanceEnabled: Boolean): Problem? = when {
        !instanceExists -> Problem.REMOVED
        !instanceEnabled -> Problem.DISABLED
        else -> null
    }
}

// ─── Merged from ProviderEdits.kt ──────────────────────────────────────────────────

/**
 * Ids of provider instances present in both snapshots whose settings differ:
 * what was edited between [before] and [now]. Added and removed instances are
 * not edits - a removed one cannot be rebuilt, and an added one has no open
 * chat built from it yet.
 */
internal fun editedProviderInstanceIds(
    before: Map<String, ProviderInstance>,
    now: Map<String, ProviderInstance>,
): Set<String> = now.filter { (id, inst) -> before[id] != null && before[id] != inst }.keys

// ─── Merged from QueuedCallbackReminder.kt ──────────────────────────────────────────────────

/**
 * [T-callback-queued-reminder] Tells a running parent loop that a sub agent it
 * delegated has finished and its result is waiting in the prompt queue.
 *
 * Why: a finished `<agent_callback>` is a PROGRAMMATIC prompt, so it waits for
 * the loop to end ([QueuedPromptBatching]) and is never slipped in at a tool
 * boundary. That delivery rule stays exactly as it is. But until the loop
 * ends the parent model cannot know a result is there, and keeps working
 * through a long plan the result might have changed. This adds one
 * `<system-reminder>` to the next model request, so the model can decide to
 * wrap up or carry on.
 *
 * Rules:
 *  - only finished sub agent callbacks still in the queue count: not scheduled
 *    fires, not user follow-ups, not progress reports, not other RPC sends;
 *  - one reminder per job ([QueuedPrompt.reminderSent]); a later reminder
 *    lists only jobs that arrived since;
 *  - the text goes into the REQUEST only. It is not written to agentHistory,
 *    not persisted and not shown;
 *  - a job is marked reminded once the request carrying it got an answer, and
 *    a retry of the same turn re-sends the same text. So a failed request
 *    neither loses the reminder nor sends a second one later.
 */
internal object QueuedCallbackReminder {

    /** One reminder: the queued prompts it covers and the text to send. */
    class Plan(val promptIds: List<String>, val text: String)

    /** Finished sub agent callbacks in [queue] the model has not been told about. */
    fun pending(queue: List<QueuedPrompt>): List<Pair<QueuedPrompt, AgentCallback>> =
        queue.mapNotNull { prompt ->
            if (prompt.reminderSent || prompt.isScheduledFire) return@mapNotNull null
            if (prompt.origin != QueuedPromptOrigin.PROGRAMMATIC) return@mapNotNull null
            val callback = AgentCallback.parse(prompt.text) ?: return@mapNotNull null
            if (callback.kind != AgentCallback.Kind.FINISHED) return@mapNotNull null
            prompt to callback
        }

    /** The reminder owed for [queue], or null when there is nothing new to say. */
    fun plan(queue: List<QueuedPrompt>): Plan? {
        val pending = pending(queue)
        if (pending.isEmpty()) return null
        return Plan(pending.map { it.first.id }, text(pending.map { it.second }))
    }

    fun text(callbacks: List<AgentCallback>): String {
        val jobs = callbacks.joinToString("; ") {
            "${it.title.ifBlank { "sub agent" }} (job ${it.jobId}, ${it.status})"
        }
        return "<system-reminder>\n" +
            "Background update: sub agent(s) you delegated have finished, and the result is queued for this conversation: $jobs\n" +
            "\n" +
            "It has NOT been delivered to you yet. It arrives as a new message only after your current turn ends, i.e. when you reply without calling more tools. Your call:\n" +
            "- If the result could change what you do next, or your remaining work is short or non-essential, wrap up now and end the turn with a brief summary. The result will then arrive.\n" +
            "- If your current work is independent of it and must finish first, keep going. The result stays queued and won't be lost.\n" +
            "\n" +
            "Don't poll for it, re-delegate, or mention this reminder to the user.\n" +
            "</system-reminder>"
    }

    /**
     * [messages] with [text] appended to the end of the last message, or null
     * when that message is not a user turn (nothing is sent then, and the jobs
     * stay unreminded for the next request).
     *
     * The message keeps its shape: the text joins the last text or tool_result
     * part (the same place the empty-turn nudge uses), so a tool_result stays a
     * tool_result and no new message or role is introduced. The input list and
     * its messages are not modified.
     */
    fun inject(messages: List<LLMMessage>, text: String): List<LLMMessage>? {
        val tail = messages.lastOrNull() ?: return null
        if (tail.role != LLMMessage.Role.USER) return null
        val suffix = "\n\n$text"
        val parts = tail.contentParts
        val newTail = when (val last = parts.lastOrNull()) {
            null -> tail.copy(content = tail.content + suffix)
            is AgentContentPart.Text -> tail.copy(contentParts = parts.dropLast(1) + AgentContentPart.Text(last.text + suffix))
            is AgentContentPart.ToolResult -> tail.copy(contentParts = parts.dropLast(1) + last.copy(content = last.content + suffix))
            else -> tail.copy(contentParts = parts + AgentContentPart.Text(text))
        }
        return messages.dropLast(1) + newTail
    }

    /** [queue] with the prompts of [plan] marked as reminded. */
    fun markSent(queue: List<QueuedPrompt>, plan: Plan): List<QueuedPrompt> {
        val ids = plan.promptIds.toSet()
        return queue.map { if (it.id in ids && !it.reminderSent) it.copy(reminderSent = true) else it }
    }
}

// ─── Merged from QueuedPromptBatching.kt ──────────────────────────────────────────────────

/**
 * [T-scheduled-preemptive-insert] Which queued prompts go out together, and
 * when.
 *
 * Two delivery points exist. The TOOL BOUNDARY — a turn's tool calls have all
 * finished and their results are in history, the next model request has not
 * been made — is where a prompt can be slipped into a running loop without
 * cutting any tool short. The DRAIN runs once the loop has converged (and
 * after a Stop).
 *
 * The rules, and why:
 *  - A scheduled fire always travels ALONE. It carries its own envelope and
 *    possibly its own prefilled tool call; merged with other prompts it
 *    became one message holding several unrelated triggers, and a prefill
 *    had no single turn to belong to.
 *  - At the tool boundary only a user follow-up or a scheduled fire may go
 *    in. Other programmatic prompts (agent callbacks, RPC/CLI sends) keep
 *    waiting for the drain, as [QueuedPromptOrigin] describes.
 *  - One scheduled fire per boundary. Several pending fires are delivered one
 *    boundary (or drain round) at a time, oldest first, so the original task
 *    still gets a model turn between them.
 *  - Non-scheduled prompts keep their old behaviour: merged into one message.
 */
internal object QueuedPromptBatching {

    /** What to slip in at a tool boundary; empty = nothing may go in now. */
    fun nextInsertBatch(queue: List<QueuedPrompt>): List<QueuedPrompt> {
        val first = queue.firstOrNull { it.isScheduledFire || it.origin == QueuedPromptOrigin.USER }
            ?: return emptyList()
        return if (first.isScheduledFire) listOf(first) else queue.filterNot { it.isScheduledFire }
    }

    /** What the drain sends as its next turn; empty = the queue is empty. */
    fun nextDrainBatch(queue: List<QueuedPrompt>): List<QueuedPrompt> {
        val first = queue.firstOrNull() ?: return emptyList()
        return if (first.isScheduledFire) listOf(first) else queue.filterNot { it.isScheduledFire }
    }

    /**
     * Ids of queued fires a new fire of [taskId] replaces. Two undelivered
     * fires of the same task would run the same check twice in a row; the
     * newer one has the fresher prefilled output, so it wins. Fires of
     * different tasks never replace each other.
     */
    fun supersededBy(queue: List<QueuedPrompt>, taskId: String): List<String> =
        queue.filter { it.scheduledTaskId == taskId }.map { it.id }
}

// ─── Merged from ScheduledPillModel.kt ──────────────────────────────────────────────────

/**
 * [T-port-sched-pill] The scheduled-task pill under the chat's top bar, as
 * plain data: which task it shows, its phase, the ring, the clock and the run
 * counter. Port of iOS `ScheduledBannerModel` (5d471e56d, e07004f03,
 * 33b4c3cc0, 9b60d1d89, 8e4c92542, 49540e6c5, 1a71c8333 - final state). Pure,
 * so the rules are JVM-tested.
 */
internal data class PillJob(
    val id: String,
    val title: String,
    /** Next planned run (wall ms); null for an on-completion task. */
    val nextFireAt: Long?,
    /** Where the wait the ring measures began. */
    val windowStart: Long,
    /** Runs left including the next one; null when unbounded / not counted. */
    val remaining: Int?,
    /** A run of this task is being processed right now. */
    val isFiringNow: Boolean,
    /** Runs already started. */
    val runsDone: Int,
    /** Planned runs in all (a counted loop); null when unbounded. */
    val runTotal: Int?,
    /** Runs more than once (loop / repeating calendar): only those show a counter. */
    val repeats: Boolean,
)

internal object ScheduledPillModel {
    enum class Phase {
        /** Waiting for the next run: countdown. */
        COUNTDOWN,
        /** A run of this task is in progress in the chat. */
        RUNNING,
        /** An on-completion task: no time to count down to. */
        WAITING,
    }

    data class State(val job: PillJob, val phase: Phase, /** "+N" */ val others: Int)

    /**
     * Which task the pill shows: one running now wins; else the soonest next
     * run; an on-completion task only when nothing has a time. null hides it.
     */
    fun state(jobs: List<PillJob>): State? {
        if (jobs.isEmpty()) return null
        val others = jobs.size - 1
        val soonest = compareBy<PillJob>({ it.nextFireAt ?: Long.MAX_VALUE }, { it.id })
        jobs.filter { it.isFiringNow }.minWithOrNull(soonest)?.let { return State(it, Phase.RUNNING, others) }
        jobs.filter { it.nextFireAt != null }.minWithOrNull(soonest)?.let { return State(it, Phase.COUNTDOWN, others) }
        val waiting = jobs.minWith(compareBy({ it.title }, { it.id }))
        return State(waiting, Phase.WAITING, others)
    }

    /** "00:48" / "59:59", seconds rounded up; null from an hour up (the caller writes "1h 5m"). */
    fun countdown(remainingMs: Long): String? {
        val s = ((remainingMs.coerceAtLeast(0) + 999) / 1000)
        if (s >= 3600) return null
        return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    /**
     * The coarse clock from an hour up, as (first unit, second unit) amounts:
     * hours + minutes (minutes rounded up) below a day, else days + hours.
     * The caller localizes it.
     */
    data class Coarse(val days: Long, val hours: Long, val minutes: Long)

    fun coarse(remainingMs: Long): Coarse {
        val s = (remainingMs.coerceAtLeast(0) + 999) / 1000
        if (s < 86_400) {
            val mins = (s + 59) / 60
            return Coarse(0, mins / 60, mins % 60)
        }
        return Coarse(s / 86_400, (s % 86_400) / 3600, 0)
    }

    /** Share of the wait already elapsed, 0..1; 0 without a next run. */
    fun progress(job: PillJob, now: Long): Float {
        val end = job.nextFireAt ?: return 0f
        val total = end - job.windowStart
        if (total <= 0) return 1f
        return ((now - job.windowStart).toFloat() / total).coerceIn(0f, 1f)
    }

    /** "This run": the one counting down, or the one in progress. */
    fun runNumber(job: PillJob, running: Boolean): Int? {
        if (!job.repeats) return null
        val n = if (running) maxOf(1, job.runsDone) else job.runsDone + 1
        return job.runTotal?.let { minOf(n, it) } ?: n
    }

    /** "2/30", "#7", or null for a one-shot. */
    fun runCounter(job: PillJob, running: Boolean): String? {
        val n = runNumber(job, running) ?: return null
        return job.runTotal?.let { "$n/$it" } ?: "#$n"
    }

    /**
     * The clock's width floor: every run of digits as "00" (at least as many
     * zeros as it has digits), so a ticking monospaced clock never changes
     * the pill's width until its format does.
     */
    fun widthTemplate(clock: String): String {
        val out = StringBuilder()
        var run = 0
        fun flush() {
            if (run > 0) repeat(maxOf(2, run)) { out.append('0') }
            run = 0
        }
        for (ch in clock) {
            if (ch in '0'..'9') run++ else { flush(); out.append(ch) }
        }
        flush()
        return out.toString()
    }

    /** "Hide this countdown" covers the tasks live when chosen; a new one brings the pill back. */
    fun isDismissed(live: Set<String>, hidden: Set<String>): Boolean =
        live.isNotEmpty() && hidden.containsAll(live)

    // --- From scheduled tasks ---------------------------------------------------

    /**
     * The tasks keeping [sessionId] active, as the pill sees them: the same
     * rule as busy / Stop / the ongoing notification
     * ([ScheduledSessionHold.heldSession]), plus a task whose run is in
     * progress here ([ScheduledSessionHold.runHold]) - the pill stays up,
     * with "Running", through a fire, even the last one the alarm already
     * disabled.
     *
     * [FIX-261025-03] Deliberately WITHOUT Settings > Background's countdown
     * lead (the default: the whole wait): the pill is the in-chat place to see
     * and cancel a schedule, while the lead only decides when the chat counts
     * as busy and the notification / status chip show the wait.
     */
    fun jobs(
        sessionId: String,
        tasks: List<ScheduledTask>,
        runningTaskIds: Set<String>,
        now: Long,
        upstreamUnfinished: (String) -> Boolean,
    ): List<PillJob> = tasks.mapNotNull { t ->
        val firing = t.id in runningTaskIds
        val held = ScheduledSessionHold.heldSession(t, now, upstreamUnfinished) == sessionId
        val runningHere = firing && ScheduledSessionHold.runHold(t) == sessionId
        if (!held && !runningHere) return@mapNotNull null
        pillJob(t, now, firing)
    }

    fun pillJob(t: ScheduledTask, now: Long, firing: Boolean): PillJob {
        val next = t.nextTriggerMs(now)
        val interval = t.triggerKind == ScheduledTriggerKind.INTERVAL
        val repeats = interval || (t.isCalendar && t.repeatMode != ScheduledRepeatMode.ONCE)
        val windowStart = when {
            interval && next != null && t.intervalSec != null -> next - t.intervalSec * 1000
            t.usesAnchor -> t.anchorMs ?: t.createdAt
            else -> t.lastFiredAt ?: t.createdAt
        }
        // An interval counts alarm fires when they happen (the running one
        // included); a calendar fire is recorded when its run completes.
        val runsDone = if (interval) t.triggeredCount ?: 0 else t.firesSoFar + if (firing) 1 else 0
        return PillJob(
            id = t.id,
            title = t.label.ifBlank { t.prompt.take(40) },
            nextFireAt = next,
            windowStart = windowStart,
            remaining = t.remainingFires,
            isFiringNow = firing,
            runsDone = runsDone,
            runTotal = if (interval) t.maxFires else null,
            repeats = repeats,
        )
    }
}

// ─── Merged from SessionTitleRules.kt ──────────────────────────────────────────────────

/** [T-android-chat-title-follows-store] How a stored session title is shown and when auto-title may write. */
internal object SessionTitleRules {

    /** What the top bar shows for a stored title: the title, or [untitled] when there is none. */
    fun display(stored: String?, untitled: String): String = stored?.takeIf { it.isNotBlank() } ?: untitled

    /**
     * Whether a title generated automatically (after the first reply) may be
     * written over [stored]: only while the session is still untitled. A
     * title the user typed or regenerated in the meantime stays.
     */
    fun autoTitleMayReplace(stored: String?, untitled: String): Boolean =
        stored.isNullOrBlank() || stored == untitled
}

// ─── Merged from SettleAfterInteractionGate.kt ──────────────────────────────────────────────────

/**
 * [T-android-settle-programmatic-scroll] Decides whether the post-drag
 * "settle" re-pin (T170, mirrors iOS settleAfterInteraction) may fire.
 *
 * The settle exists for one situation: the user's finger was on the list, the
 * streaming auto-follow was suppressed while they dragged, content grew in the
 * meantime, and the finger lifted near the bottom — so one extra pin catches
 * the growth that was missed. It was driven from the
 * `isScrollInProgress -> false` edge, which is wrong: the auto-follow glide is
 * itself a `listState.scroll { }` block, so every glide that finished also
 * flipped that edge and the settle snapped `scrollToItem(0, 0)` on top of it.
 * Measured on a Pixel 4a during a 300-paragraph stream: 103 settle fires in
 * one turn, and on screen a repeating pattern of an eased glide followed by an
 * instant jump — the "来回抖" the user reported. Programmatic scrolls emit no
 * DragInteraction; only a real finger lift records [msSinceDrag]'s timestamp.
 *
 * Pure so it can be unit-tested; the composable passes its own state in.
 */
object SettleAfterInteractionGate {

    /**
     * How long after a `DragInteraction.Stop` a scroll-end still counts as the
     * end of THAT drag (including its fling). Same window the return-to-bottom
     * re-arm already uses for "recent user interaction".
     */
    const val RECENT_DRAG_MS = 1500L

    /**
     * @param scrollInProgress current `listState.isScrollInProgress`
     * @param userScrolledAway the user has parked the viewport away from the bottom
     * @param nearBottom       viewport is within the near-bottom threshold
     * @param streaming        a reply is currently streaming
     * @param msSinceDrag      now - lastInterruptMs (last DragInteraction.Stop);
     *                         `Long.MAX_VALUE`-ish when no drag has ever happened
     * @return true when the settle re-pin should run
     */
    fun shouldSettle(
        scrollInProgress: Boolean,
        userScrolledAway: Boolean,
        nearBottom: Boolean,
        streaming: Boolean,
        msSinceDrag: Long,
    ): Boolean {
        if (scrollInProgress) return false
        if (userScrolledAway) return false
        if (!nearBottom) return false
        if (!streaming) return false
        // The discriminator: a scroll that just ended is only "the user's drag
        // ending" if a finger actually lifted recently. The auto-follow glide,
        // pin-to-bottom and the settle itself all end scrolls without one.
        return msSinceDrag in 0..RECENT_DRAG_MS
    }
}

// ─── Merged from StopConfirmPolicy.kt ──────────────────────────────────────────────────

/**
 * [T-stop-confirm] What a tap on the composer's stop button does. Mirrors iOS
 * `StopConfirmPolicy` (ChatSendButton.swift) so both platforms ask the same
 * way. Only that button asks: programmatic cancels (debug RPC
 * chat.session.cancel, shortcuts, scheduled and sub-agent internals, a sub
 * agent card's Stop, New Chat's own "stop and start new" dialog, cancelling a
 * queued prompt, read-aloud / voice) call `stopByUser()` / `cancelStream()`
 * directly and never pass through here.
 *
 * Why: accidental one-handed taps on the composer's stop button ended long
 * agent runs halfway (Telegram feedback, 2026-10-04..07).
 */
object StopConfirmPolicy {

    /** Appearance pref key; same name as iOS `@AppStorage("chat.confirmBeforeStopping")`. */
    const val SETTINGS_KEY = "chat.confirmBeforeStopping"

    /**
     * [T-stop-mode-longpress] Default OFF (stop at once), as on iOS; opt in from
     * Settings > Appearance, the stop button's long-press, or minis-config.
     */
    const val DEFAULT_ENABLED = false

    enum class TapAction {
        /**
         * [T-stop-choice] Something besides the turn would be lost (a sub agent,
         * or a scheduled task still due): ask "Stop Current Tool Only / Stop
         * All". Replaces the scheduled-tasks-only warning, which never mentioned
         * the sub agents that Stop always ended too.
         */
        CHOOSE_SCOPE,

        /** Ask "Stop this task?" first. */
        CONFIRM_STOP,

        /** Stop at once (the setting is off). */
        STOP_NOW,
    }

    /**
     * Only reached while the stop button is shown, i.e. the conversation is busy.
     * [hasBackground] is [StopSummary.hasBackground]: without background work
     * nothing changes - the setting decides, and by default Stop is immediate.
     */
    fun onTap(hasBackground: Boolean, confirmEnabled: Boolean): TapAction = when {
        hasBackground -> TapAction.CHOOSE_SCOPE
        confirmEnabled -> TapAction.CONFIRM_STOP
        else -> TapAction.STOP_NOW
    }

    /**
     * Whether the dialog's Stop still refers to the task it was opened for.
     * [turnAtOpen] / [turnNow] are the chat loop's live-turn tokens
     * (`LiveTurnGuard.liveGeneration()`, null when no own turn is running).
     * Nothing is stopped once the conversation is idle, and a dialog opened
     * during one turn never stops the next one (a queued prompt that started
     * while the dialog was up has a different token). One opened while only
     * sub agents or timers kept the conversation busy (token null) applies
     * while that is still the case.
     */
    fun confirmStillApplies(turnAtOpen: Long?, turnNow: Long?, isBusy: Boolean): Boolean =
        isBusy && turnAtOpen == turnNow
}

// ─── Merged from StopLateGuard.kt ──────────────────────────────────────────────────

/**
 * [T-android-stop-late-write] Which agent turn is live right now, so a write
 * that arrives after Stop can be recognised and dropped.
 *
 * Field report (2026-10-02): after Stop, a shell pill kept its red Stop square
 * and the tool bar kept saying "Live" although the command was already dead.
 * SIGKILL makes proot print "terminated with signal 9" on stderr; the command's
 * still-open reader delivered that line to executeShellCommand's lineCallback,
 * which re-published the turn-local block snapshot (status RUNNING) through
 * updateAssistantMessage(isStreaming = true). That re-created a streaming
 * overlay entry for a message whose turn was over, and since no stream-end
 * path would ever drain it, the overlay painted RUNNING over the cancelled
 * message for good.
 *
 * Every turn now carries a generation number. Cancel bumps it, and a turn that
 * ends clears it only if it is still its own (a resume may already have
 * started the next one). The overlay accepts a streaming write only for the
 * live turn's current assistant id, and a shell line is dropped once its
 * turn's generation is gone.
 *
 * Thread-safe: shell readers call [isLive] from their own threads.
 */
internal class LiveTurnGuard {
    private data class Live(val generation: Long, val assistantId: String?)

    private val generation = AtomicLong(0)

    @Volatile private var live: Live? = null

    /** A turn starts; returns its token. Any earlier turn stops being live. */
    @Synchronized
    fun begin(): Long {
        val gen = generation.incrementAndGet()
        live = Live(gen, null)
        return gen
    }

    /** The turn [gen] writes into [assistantId] from now on (first bubble, or after a seal). */
    @Synchronized
    fun markAssistant(gen: Long, assistantId: String) {
        if (live?.generation == gen) live = Live(gen, assistantId)
    }

    /** The turn [gen] ended by itself. Compare-and-clear: a newer turn stays live. */
    @Synchronized
    fun end(gen: Long) {
        if (live?.generation == gen) live = null
    }

    /**
     * Stop: the live turn, if any, is over from this instant. Returns the
     * assistant id it was writing, for logging.
     */
    @Synchronized
    fun cancel(): String? {
        val was = live?.assistantId
        generation.incrementAndGet()
        live = null
        return was
    }

    fun isLive(gen: Long): Boolean = live?.generation == gen

    /** The live turn's token, or null when no turn is running. */
    fun liveGeneration(): Long? = live?.generation

    /** The bubble the live turn is writing, or null when no turn is running. */
    fun liveAssistantId(): String? = live?.assistantId

    /** Whether a streaming (overlay) write for [assistantId] belongs to the live turn. */
    fun acceptsStreamingWrite(assistantId: String): Boolean {
        val l = live ?: return false
        return l.assistantId == assistantId
    }
}

/**
 * [T-android-stop-late-write] Pure rules for healing tool blocks that claim to
 * be in flight when no turn is running them, and for the per-tool Stop.
 */
internal object InFlightToolHeal {

    /** The statuses that draw a spinner and a Stop square. */
    fun isInFlight(status: ToolBlockStatus?): Boolean =
        status == ToolBlockStatus.RUNNING ||
            status == ToolBlockStatus.STREAMING ||
            status == ToolBlockStatus.PENDING

    private fun isTerminal(status: ToolBlockStatus?): Boolean =
        status != null && !isInFlight(status)

    data class Healed(
        val messages: List<ChatMessage>,
        /** (messageId, blockId, toolName) of every tool_use block that was flipped. */
        val toolUses: List<Triple<String, String, String>>,
        val blockCount: Int,
    )

    /**
     * Flip every in-flight block to CANCELLED in every assistant message except
     * those in [keep] (the bubble a running turn is still writing, and any the
     * caller heals itself).
     *
     * The old cleanup looked only at the LAST assistant message. After a resume
     * the stuck block sits in an earlier one, so it was never repaired.
     * Blocks for which [ownLifecycle] is true are left alone: a background sub
     * agent's block is legitimately RUNNING after its parent turn ended, and
     * OrphanedRunningToolGate already judges it against its job.
     * Returns the same list instance when nothing changed.
     */
    fun sweep(
        messages: List<ChatMessage>,
        keep: Set<String>,
        ownLifecycle: (AssistantBlock) -> Boolean = { false },
    ): Healed {
        var out: MutableList<ChatMessage>? = null
        val toolUses = mutableListOf<Triple<String, String, String>>()
        var blocks = 0
        fun stale(b: AssistantBlock) = isInFlight(b.toolStatus) && !ownLifecycle(b)
        for ((i, m) in messages.withIndex()) {
            if (m.role != "assistant" || m.id in keep) continue
            if (m.toolBlocks.none { stale(it) }) continue
            val fixed = m.toolBlocks.map { b ->
                if (!stale(b)) b else {
                    blocks++
                    if (b.kind == "tool_use") toolUses += Triple(m.id, b.id, b.toolName)
                    b.copy(toolStatus = ToolBlockStatus.CANCELLED)
                }
            }
            if (out == null) out = messages.toMutableList()
            out[i] = m.copy(toolBlocks = fixed, isStreaming = false, isAwaitingModelResponse = false)
        }
        return Healed(out ?: messages, toolUses, blocks)
    }

    /**
     * Fold an overlay's blocks into the canonical ones without letting the
     * overlay undo a finished status: a block the canonical message already
     * holds as SUCCESS / FAILED / CANCELLED / TIMEOUT keeps that status even if
     * the overlay still says RUNNING. That regression is exactly how a later
     * cancel's flushAllStreamingDeltas copied a stale RUNNING back into history.
     */
    fun mergeOverlayBlocks(
        canonical: List<AssistantBlock>,
        overlay: List<AssistantBlock>,
    ): List<AssistantBlock> {
        if (canonical.isEmpty()) return overlay
        val done = HashMap<String, ToolBlockStatus>()
        for (b in canonical) if (isTerminal(b.toolStatus)) done[b.id] = b.toolStatus!!
        if (done.isEmpty()) return overlay
        return overlay.map { b ->
            val kept = done[b.id]
            if (kept != null && isInFlight(b.toolStatus)) b.copy(toolStatus = kept) else b
        }
    }

    enum class StopAction {
        /** The block is in flight in the running turn: stop that turn (today's Stop). */
        STOP_TURN,
        /** A leftover from a turn that is over: cancel just this block, leave any stream alone. */
        CANCEL_STALE_BLOCK,
        /** Nothing in flight to stop. */
        NONE,
    }

    /**
     * What a tool's own Stop does.
     *
     * The pill's red square used to call the session-wide cancelStream(), so a
     * stuck pill from an earlier turn stopped whatever reply was live when it
     * was tapped. Now only a block of the running turn can stop the turn.
     *
     * @param liveBlocks the running turn's blocks (its overlay, else its
     *   canonical message); empty when no turn is running.
     * @param blockStatus the tapped block's current displayed status.
     */
    fun stopAction(
        blockId: String,
        isStreaming: Boolean,
        liveBlocks: List<AssistantBlock>,
        blockStatus: ToolBlockStatus?,
    ): StopAction {
        if (isStreaming && liveBlocks.any { it.id == blockId && isInFlight(it.toolStatus) }) {
            return StopAction.STOP_TURN
        }
        return if (isInFlight(blockStatus)) StopAction.CANCEL_STALE_BLOCK else StopAction.NONE
    }

    /**
     * Which of [toolUseIds] may get a CANCELLED tool_result appended to the
     * stored history ([rowsParts]: each stored row's parts JSON, in order).
     *
     * Only an unanswered call whose tool_use is in the LAST stored row: a
     * result appended after later rows would sit away from its call, and the
     * provider rejects a tool_result that does not follow its tool_use.
     */
    fun appendableCancelResults(rowsParts: List<String>, toolUseIds: Collection<String>): Set<String> {
        if (toolUseIds.isEmpty() || rowsParts.isEmpty()) return emptySet()
        val answered = HashSet<String>()
        val useRow = HashMap<String, Int>()
        for ((row, json) in rowsParts.withIndex()) {
            val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val part = arr.optJSONObject(i) ?: continue
                val id = part.optJSONObject("value")?.optString("toolUseId", "").orEmpty()
                if (id.isEmpty()) continue
                val key = com.openminis.app.provider.ToolPairing.key(id)
                when (part.optString("type")) {
                    "toolResult" -> answered += key
                    "toolUse" -> useRow[key] = row
                }
            }
        }
        val last = rowsParts.lastIndex
        return toolUseIds.filterTo(HashSet()) { id ->
            val key = com.openminis.app.provider.ToolPairing.key(id)
            key !in answered && useRow[key] == last
        }
    }

    /** Set [blockId] to CANCELLED wherever it is in flight; same instance when absent. */
    fun cancelBlock(blocks: List<AssistantBlock>, blockId: String): List<AssistantBlock> {
        if (blocks.none { it.id == blockId && isInFlight(it.toolStatus) }) return blocks
        return blocks.map {
            if (it.id == blockId && isInFlight(it.toolStatus)) it.copy(toolStatus = ToolBlockStatus.CANCELLED) else it
        }
    }
}

// ─── Merged from StopScope.kt ──────────────────────────────────────────────────

/**
 * [T-stop-choice] What a Stop can reach in one conversation, counted at tap
 * time. The composer asks "stop the current tool only, or everything?" only
 * when something besides the turn itself would be lost: a sub agent (running
 * or queued for a slot) or a scheduled task this chat created that would
 * still fire. Background shell processes are not counted - nothing registers
 * them - so the dialog only mentions that Stop All ends them too.
 */
data class StopSummary(
    /** Tool calls of the running turn that are executing right now. */
    val runningTools: List<String> = emptyList(),
    val subAgentsRunning: Int = 0,
    val subAgentsQueued: Int = 0,
    val scheduledTasks: Int = 0,
) {
    val hasBackground: Boolean get() = subAgentsRunning > 0 || subAgentsQueued > 0 || scheduledTasks > 0
}

/**
 * [T-stop-choice] How "Stop Current Tool Only" ends the foreground work.
 *
 *  - [INTERRUPT_TOOLS]: tool calls are executing. Each is cut short and
 *    answers with [ForegroundToolInterrupts.INTERRUPTED_RESULT]; the loop
 *    carries on to its post-tool boundary, where a queued user message goes
 *    in as the next round (the existing QueueInterrupt path) and otherwise
 *    the turn ends there ([endsTurnAtBoundary]).
 *  - [END_TURN]: no tool is executing (the model is writing, or the request
 *    is in flight). The turn ends the way Stop always ended it, minus the
 *    background: sub agents, their results and scheduled tasks are kept.
 */
enum class ToolOnlyStop { INTERRUPT_TOOLS, END_TURN }

object StopScopePolicy {

    fun toolOnlyStop(toolsRunning: Boolean): ToolOnlyStop =
        if (toolsRunning) ToolOnlyStop.INTERRUPT_TOOLS else ToolOnlyStop.END_TURN

    /**
     * After a tool-only stop the loop does not ask the model again on its own:
     * with nothing of the user's to answer it would only retry the tool that
     * was just stopped. A user message (or a due scheduled fire) waiting to be
     * inserted keeps it going, as an inserted round.
     */
    fun endsTurnAtBoundary(stopRequestedThisTurn: Boolean, insertPending: Boolean): Boolean =
        stopRequestedThisTurn && !insertPending

    /** The model-facing note for scheduled tasks a Stop All turned off (iOS parity). */
    fun scheduledCancelledNotice(labels: List<String>): String? {
        if (labels.isEmpty()) return null
        val names = labels.joinToString(", ") { "\"${it.take(80)}\"" }
        return "<system-reminder>The user pressed Stop All in this conversation. This also cancelled " +
            "${labels.size} scheduled task(s) created here: $names. They are disabled and will not fire again " +
            "unless the user turns them back on - do not wait for them or reschedule them unless asked." +
            "</system-reminder>"
    }
}

/**
 * [T-stop-choice] The tool calls of this chat's loop that are executing right
 * now, so "Stop Current Tool Only" can cut them short without cancelling the
 * turn they belong to (which would take the whole loop down with them).
 *
 * Each call runs as a child of its own slot; cancelling that child ends only
 * the call, and [run] turns the cancellation into an ordinary tool result so
 * tool_use / tool_result stay paired and the loop continues. A cancellation
 * that comes from above (the turn itself was stopped) is rethrown untouched.
 *
 * A call registered as not [Entry.cancellable] is never cancelled here; its
 * owner ends it another way (a shell's process group is killed so the call
 * returns by itself; a waiting delegation is moved to the background).
 */
class ForegroundToolInterrupts {

    private class Entry(val name: String, val job: Job, val cancellable: Boolean)

    private val running = ConcurrentHashMap<String, Entry>()
    private val interrupted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    data class Outcome<T>(val value: T, val interrupted: Boolean)

    fun runningNames(): List<String> = running.values.map { it.name }

    fun isEmpty(): Boolean = running.isEmpty()

    fun isRunning(id: String): Boolean = running.containsKey(id)

    fun nameOf(id: String): String? = running[id]?.name

    /**
     * [FIX-261025-05] Whether [id] was interrupted and is still running. A
     * non-cancellable call (a shell) checks this while it is still waiting -
     * a `delay` countdown, a bash install - so it can stop before starting
     * anything, instead of only when its process group is killed.
     */
    fun isInterrupted(id: String): Boolean = interrupted.contains(id)

    fun idsNamed(name: String): List<String> = running.entries.filter { it.value.name == name }.map { it.key }

    /**
     * Run one tool call. [onInterrupted] supplies the result when the call was
     * cancelled by [interrupt]; a call that returned by itself after an
     * interrupt (a killed shell) comes back with [Outcome.interrupted] set.
     */
    suspend fun <T> run(
        id: String,
        name: String,
        cancellable: Boolean,
        onInterrupted: () -> T,
        body: suspend () -> T,
    ): Outcome<T> = coroutineScope {
        val call = async { body() }
        running[id] = Entry(name, call, cancellable)
        try {
            val value = call.await()
            Outcome(value, interrupted.contains(id))
        } catch (e: CancellationException) {
            if (interrupted.contains(id) && currentCoroutineContext().isActive) {
                Outcome(onInterrupted(), true)
            } else {
                throw e
            }
        } finally {
            running.remove(id)
            interrupted.remove(id)
        }
    }

    /**
     * Interrupt [ids] (all running calls when null). Returns the ids marked;
     * cancellable ones are cancelled at once.
     */
    fun interrupt(ids: Collection<String>? = null): List<String> {
        val targets = ids?.filter { running.containsKey(it) } ?: running.keys.toList()
        for (id in targets) {
            interrupted.add(id)
            // Finished meanwhile: its finally already ran, so drop the mark here.
            val entry = running[id] ?: run { interrupted.remove(id); null } ?: continue
            if (entry.cancellable) entry.job.cancel(CancellationException("stopped by the user (tool only)"))
        }
        return targets
    }

    companion object {
        /** What a cut-short call answers. Read by the model, so it says what to do. */
        const val INTERRUPTED_RESULT =
            "[Stopped by the user] This tool call was interrupted before it finished; any output above is partial. " +
                "Do not run it again on your own - wait for the user's next message."

        fun withInterruptNote(output: String): String =
            if (output.isBlank()) INTERRUPTED_RESULT else "$output\n\n$INTERRUPTED_RESULT"
    }
}

// ─── Merged from StreamFollowDeadband.kt ──────────────────────────────────────────────────

/**
 * [T-android-stream-follow-deadband] Should the streaming auto-follow act on
 * this position sample, or sit this one out?
 *
 * The streaming follow loop glides the viewport toward the bottom whenever the
 * growing message pushes it away. The distance it chases is
 * `firstVisibleItemIndex * avgItemSize + firstVisibleItemScrollOffset`, and that
 * number does not only GROW — a line can get SHORTER after it was already laid
 * out, because inline markdown re-measures as it streams:
 *
 *   `` `applicationId` `` arrives one character at a time. Until the closing
 *   backtick lands, parseInline renders it as plain text; the moment it lands
 *   the span becomes an inline-code chip with its own padding and a monospace
 *   face, and the line's measured height changes.
 *
 * Measured from a user report (2026-09-02, three frames 17s apart): the viewport
 * moved -18px, then +8px — up, then back down — while a whole line of new text
 * was arriving. Content that only ever grows cannot move the viewport DOWN; the
 * reversal was the re-measure landing mid-glide, and the follow loop dutifully
 * "corrected" for it. That correction is the visible jitter.
 *
 * The rule: while the stream is actively growing, ignore a SMALL reversal for a
 * short settle window. Anything larger, or anything still present after the
 * window, is treated as a real layout change and followed normally.
 *
 * Deliberately amplitude-based rather than component-based. We cannot ask "was
 * that an image?" from here, and we should not try: a text reflow is bounded by
 * roughly one line, while an image/table/code-block settling its height moves
 * hundreds of pixels. One threshold separates them without enumerating widget
 * types, so a future widget needs no new case.
 */
object StreamFollowDeadband {

    /**
     * Reversals smaller than this are candidates for suppression.
     *
     * ~1.5 lines at this app's body size. The observed inline-code reflow was
     * 8px and CJK line-height shifts land in the 8–18px band; a genuine block
     * change (image finishing decode, table settling, code block measuring) is
     * an order of magnitude larger and must pass through.
     */
    const val SHRINK_DEADBAND_PX = 24f

    /**
     * How long after the last real growth a small reversal is still assumed to
     * be re-measure noise.
     *
     * Sized against the follow loop's own 120ms sample: two ticks. Long enough
     * to swallow the reflow that follows a chunk, short enough that a reversal
     * which is genuinely the new resting position is honoured on the next tick
     * rather than stranding the viewport. Without this bound, suppression would
     * be permanent and a real shrink at end-of-stream would never be corrected.
     */
    const val SHRINK_SETTLE_MS = 250L

    /**
     * @param current    this sample's distance-from-bottom, in px
     * @param previous   the distance at the last sample the loop acted on
     * @param msSinceGrowth elapsed ms since the distance last INCREASED
     * @return true when the follow loop should skip this sample
     */
    fun shouldSuppress(
        current: Float,
        previous: Float,
        msSinceGrowth: Long,
    ): Boolean {
        val delta = current - previous
        // Growing (or unchanged) — always follow. This is the common path.
        if (delta >= 0f) return false
        // Reversal. Large ones are real layout, not a text reflow.
        if (-delta >= SHRINK_DEADBAND_PX) return false
        // Small reversal, but the stream has gone quiet — accept it as the new
        // resting position instead of suppressing forever.
        if (msSinceGrowth >= SHRINK_SETTLE_MS) return false
        return true
    }
}

// ─── Merged from StreamGrowReveal.kt ──────────────────────────────────────────────────

/**
 * [T-android-stream-grow-reveal] A streaming reply's new lines glide in over
 * 0.2 s instead of appearing in one frame.
 *
 * The chat list is `reverseLayout`: while it is pinned to the bottom, the
 * newest row's bottom edge is the anchor. When a flush makes a row taller,
 * every row above it moves up by the whole growth in a single frame and the
 * new lines are simply there — the "stiff" stream next to iOS.
 *
 * iOS (`NoAnimationCollectionView.layoutSubviews`, T-ios-stream-grow-anim)
 * lets the cell frames land at once and animates only the contentOffset over
 * 0.20 s ease-out, so the whole conversation slides up in lock-step and the
 * new lines rise out from under the composer. A reversed LazyColumn has no
 * offset left to animate at the pinned bottom, so the live reply's rows
 * animate their own height instead: content top-aligned and clipped, the
 * reported height easing from the old value to the new one. With the bottom
 * edge anchored, the rows above glide up together and the new lines are
 * uncovered from the bottom — the same picture. A row that appears empty and
 * then fills (a new paragraph's first measure is 0 px high) opens from 0 the
 * same way.
 *
 * Compose's own `animateContentSize` does this. A hand-written layout node
 * that eased a reported height (the first version of this change) measured
 * fine frame by frame on a Pixel 6, but the list did not always re-place its
 * rows between flushes: a paragraph drew over the one below it and the whole
 * growth landed in one frame at the next flush. With animateContentSize plus
 * the re-pin below, one-line jumps on the same device and prompt shape went
 * from ~16 per reply to none, with no overlap.
 *
 * Only the live reply's rows carry it (the caller applies it only while that
 * reply streams), so history, cold-loaded sessions and finished replies keep
 * their plain layout and cost nothing. The duration matches the VM flush
 * cadence (200 ms below 500 chars), as iOS pairs its 0.2 s flush tier with
 * its glide, so back-to-back flushes read as one continuous motion.
 */
internal fun Modifier.streamGrowReveal(): Modifier = this
    .clipToBounds()
    .animateContentSize(
        animationSpec = tween<IntSize>(STREAM_GROW_DURATION_MS, easing = STREAM_GROW_EASE_OUT),
        alignment = Alignment.TopStart,
    )

/** iOS: `UIView.animate(withDuration: 0.20 …)`. */
internal const val STREAM_GROW_DURATION_MS = 200

/** iOS `.curveEaseOut` (Core Animation's easeOut control points). */
internal val STREAM_GROW_EASE_OUT = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/**
 * [T-android-stream-grow-reveal] Whether the list should re-pin to the bottom
 * because rows of the live reply just appeared there.
 *
 * A reversed LazyColumn keeps its scroll position by KEY: when a new row is
 * inserted at the bottom while the list sits at (0, 0), the previous bottom
 * row keeps its place and the new one lands BELOW the viewport (the
 * "fIdx=1..5" the follow loop then has to glide back from). For a row of the
 * streaming reply that arrives while following, the screen instead re-pins
 * with `requestScrollToItem(0, 0)` in the same pass, and the row opens from 0
 * through [streamGrowReveal] — the same 0.2 s glide as text growth, iOS's one
 * motion for both.
 *
 * Deliberately narrow; anything else keeps today's behaviour:
 *  - every new key must belong to the live reply (a send also inserts the
 *    user bubble, and the send path owns that scroll);
 *  - the previous bottom row must still be there (the typing row being
 *    replaced by the first block is a swap, not growth);
 *  - at most [MAX_NEW_ROWS] rows at once (a reconnect / resync dump is not a
 *    stream);
 *  - the first list a session shows is never "new".
 */
internal class StreamGrowRevealTracker {
    data class Row(val key: String, val ownerMessageId: String?)

    private var previousKeys: Set<String>? = null
    private var previousNewestKey: String? = null

    /** Rows in display order, oldest first; [pinned] = list at (0, 0) and following. */
    fun onRows(rows: List<Row>, liveMessageId: String?, pinned: Boolean): Boolean {
        val before = previousKeys
        val newestBefore = previousNewestKey
        val keys = rows.mapTo(HashSet(rows.size)) { it.key }
        previousKeys = keys
        previousNewestKey = rows.lastOrNull()?.key
        if (liveMessageId == null || before == null || !pinned) return false

        val added = rows.filter { it.key !in before }
        if (added.isEmpty() || added.size > MAX_NEW_ROWS) return false
        return added.all { it.ownerMessageId == liveMessageId } &&
            newestBefore != null && newestBefore in keys &&
            // The new rows are the bottom of the list, not something inserted above it.
            rows.takeLast(added.size).map { it.key } == added.map { it.key }
    }

    companion object {
        const val MAX_NEW_ROWS = 3
    }
}

/** The reply a row belongs to (the id before any `#` split suffix); null for user-side rows. */
internal fun FlatChatItem.growOwnerMessageId(): String? = when (this) {
    is FlatChatItem.UserBubble,
    is FlatChatItem.AgentCallbackCard,
    is FlatChatItem.ScheduledTaskCardItem -> null
    is FlatChatItem.AssistantHeader -> messageId
    is FlatChatItem.AssistantText -> messageId
    is FlatChatItem.AssistantMarkdownBlock -> messageId
    is FlatChatItem.AssistantThinking -> messageId
    is FlatChatItem.AssistantToolUse -> messageId
    is FlatChatItem.AssistantToolGroup -> messageId
    is FlatChatItem.AssistantInfo -> messageId
    is FlatChatItem.AssistantTyping -> messageId
    is FlatChatItem.AssistantError -> messageId
    is FlatChatItem.AssistantUsage -> messageId
    is FlatChatItem.AssistantLegacyContent -> messageId
}?.substringBefore('#')

// ─── Merged from SystemResourceMonitor.kt ──────────────────────────────────────────────────

/**
 * Lightweight CPU/memory sampler used for shell-tool live HUDs.
 *
 * - **CPU**: This app process's own CPU usage, reported in top-style IRIX
 *   percentage where 100% means one fully-loaded core. On an 8-core
 *   device a hf-render burst that occupies 1.1 cores reads ~110%; full
 *   saturation reads up to 800%. Picked over the iOS-style normalized
 *   0-100% because it matches what `adb shell top` shows for the same
 *   process — a dev cross-checking the HUD against top sees identical
 *   numbers, and the value visually tracks the actual load (a busy
 *   render no longer reads as a deceptively-quiet 12%).
 *
 *   Computed from `/proc/self/stat`: `(Δutime + Δstime) / (Δwall * CLK_TCK)
 *   * 100`. Field 14 (utime) and 15 (stime) are 1-indexed in man proc, but
 *   field 2 is the parenthesized comm name which can contain spaces or
 *   close-parens — so we locate the *last* `)` and parse from there to
 *   avoid mis-splitting. Sampled every 2 s and sum-averaged over the last
 *   5 samples (≈10-s rolling window) — gives a stable reading that doesn't
 *   drop to 0 on a single GC pause or IO block. The first sample after
 *   construction returns 0% — there's no prior baseline to diff against.
 *
 *   T303 history: T244 (commit 8750340) had switched to `/proc/stat` to
 *   capture the system-wide load thinking shell_execute child processes
 *   wouldn't be attributed to the app — but in practice `/proc/stat` is
 *   permission-denied to apps on Android 8+, so the read silently failed
 *   and the HUD read "CPU 0%" under any load. PRoot on Android works via
 *   ptrace, so foreign syscalls run inside the app's own PID anyway and
 *   `/proc/self/stat` does cover hf-render etc. (verified: `top` reports
 *   the same ~110% on com.openminis.app while hf-render runs).
 * - **Memory**: total PSS for "used" (matches what task_info(TASK_VM_INFO)
 *   reports on iOS — proportional set size, the closest cross-platform
 *   equivalent to "what this process owns now"); `ActivityManager.MemoryInfo
 *   .totalMem` for the device cap.
 *
 *   [T-android-resource-hud-offmain] Read from `/proc/self/smaps_rollup`
 *   (`Pss` + `SwapPss`, the same total `Debug.MemoryInfo.getTotalPss()`
 *   reports), falling back to `Debug.getMemoryInfo()` where the rollup file
 *   is missing. `Debug.getMemoryInfo()` parses the FULL `/proc/self/smaps`,
 *   one record per mapping, and Minis maps a lot (WebView, the ONNX runtime,
 *   the proot loaders): profiled on a Pixel 6 perf build it cost ~20-25 ms
 *   per call — 5% of all app CPU during a shell run — and it ran on the main
 *   thread every 2 s, dropping a frame or two each time. The rollup is the
 *   kernel's pre-summed version: a few lines, well under a millisecond. The
 *   sampling also moved off the main thread (see [rememberSystemResourceMonitor]).
 *
 * The class itself is stateful (last-tick baselines) but doesn't push state
 * into Compose — pair it with [rememberSystemResourceMonitor] to drive a
 * recompose loop while the caller wants live numbers.
 */
class SystemResourceMonitor {
    // [T-android-resource-hud-offmain] Written by the IO sampler, read by the
    // UI — volatile so the UI sees each completed sample.
    @Volatile var cpuUsage: Float = 0f          // top-style IRIX %; 100% = 1 core, up to numCores * 100
        private set
    @Volatile var memUsedBytes: Long = 0L
        private set
    @Volatile var memTotalBytes: Long = 0L
        private set

    private var prevCpuTicks: Long? = null
    private var prevSampleNanos: Long? = null

    /**
     * Rolling window of recent (tickDelta, wallNanos) pairs. cpuUsage is
     * computed as the *sum-weighted* average over the window — that is,
     * sum(ticks)/(sum(wallSec) * CLK_TCK * cores), which mathematically
     * collapses to "what fraction of CPU did this app actually use over
     * the last [windowSec] seconds." This is strictly more accurate than
     * a single-window snapshot or an EMA: snapshots aliased at 2s missed
     * load that landed mostly inside the prior window, and EMAs trade off
     * recency vs. noise asymmetrically (slow to climb, slow to fall).
     *
     * Window size 5 with 1s sampling = 5-second running average. Long
     * enough that GC pauses / IO blocks inside the app don't drop the
     * reading to 0; short enough that the user sees load shifts within
     * the same shell command.
     */
    private val recentDeltas = ArrayDeque<Pair<Long, Long>>()
    private val windowSize = 5

    /** Resolved once. CLK_TCK is effectively 100 on Android historically,
     *  but ask the kernel rather than hard-coding so we stay correct on
     *  hypothetical future builds with a different jiffy rate. */
    private val clockTicksPerSec: Long = runCatching {
        Os.sysconf(OsConstants._SC_CLK_TCK)
    }.getOrNull()?.takeIf { it > 0 } ?: 100L

    // Sampled on IO while reset() can run on Main; one lock keeps the window consistent.
    @Synchronized
    fun sampleOnce(context: Context) {
        sampleCpu()
        sampleMemory(context)
    }

    /**
     * Reset baselines so a freshly-(re)started monitor reports 0% on its
     * first sample rather than averaging across a long idle gap.
     */
    @Synchronized
    fun reset() {
        prevCpuTicks = null
        prevSampleNanos = null
        recentDeltas.clear()
        cpuUsage = 0f
    }

    private fun sampleCpu() {
        val ticks = readSelfCpuTicks() ?: return
        val nowNanos = System.nanoTime()
        val prevTicks = prevCpuTicks
        val prevNanos = prevSampleNanos
        if (prevTicks != null && prevNanos != null) {
            val tickDelta = ticks - prevTicks
            val wallNanos = nowNanos - prevNanos
            if (tickDelta >= 0 && wallNanos > 0) {
                recentDeltas.addLast(tickDelta to wallNanos)
                while (recentDeltas.size > windowSize) recentDeltas.removeFirst()

                // Sum-weighted (not arithmetic) average: collapses the per-
                // sample fractions back into a single fraction over the full
                // window, which is the mathematically correct "% CPU over
                // last N seconds" — no bias toward shorter or longer slices.
                var sumTicks = 0L
                var sumNanos = 0L
                for ((t, n) in recentDeltas) { sumTicks += t; sumNanos += n }
                val sumWallSec = sumNanos / 1_000_000_000.0
                if (sumWallSec > 0) {
                    // top-style IRIX percentage: 100% = one fully-loaded
                    // core. On an 8-core device, hf-render burning 1.1
                    // cores → 110%; full saturation → 800%. Matches what
                    // `top` shows for the same process so the HUD and a
                    // dev's `adb shell top` agree.
                    val raw = sumTicks.toDouble() /
                        (clockTicksPerSec.toDouble() * sumWallSec)
                    cpuUsage = (raw * 100.0).toFloat()
                }
            }
        }
        prevCpuTicks = ticks
        prevSampleNanos = nowNanos
    }

    /**
     * Read `/proc/self/stat` and return `utime + stime` in clock ticks.
     *
     * Format (man proc):
     *   pid (comm) state ppid pgrp ... utime stime cutime cstime ...
     *
     * `comm` is parenthesized and may itself contain spaces or `)` — so we
     * locate the *last* `)` and split the remainder. After that anchor the
     * fields are 0-indexed: state=0, ppid=1, ..., utime=11, stime=12.
     *
     * cutime/cstime (waited-for-children CPU) are intentionally excluded —
     * shell_execute doesn't actually fork (PRoot uses ptrace), so the work
     * runs inside the app's own utime/stime; folding cutime in would only
     * double-count any genuine child reaper work.
     */
    private fun readSelfCpuTicks(): Long? = try {
        val raw = File("/proc/self/stat").readText()
        val rparen = raw.lastIndexOf(')')
        if (rparen < 0 || rparen + 2 >= raw.length) {
            null
        } else {
            val tail = raw.substring(rparen + 2)
                .split(' ')
                .filter { it.isNotEmpty() }
            val utime = tail[11].toLong()
            val stime = tail[12].toLong()
            utime + stime
        }
    } catch (_: Throwable) { null }

    private fun sampleMemory(context: Context) {
        val pssKb = try {
            parseTotalPssKb(File("/proc/self/smaps_rollup").readText())
        } catch (_: Throwable) { null }
            ?: try {
                // Fallback for kernels without smaps_rollup (pre-4.14).
                val mi = Debug.MemoryInfo()
                Debug.getMemoryInfo(mi)
                mi.totalPss.toLong()
            } catch (_: Throwable) { null }
        // PSS is in KiB; bring to bytes for the formatter.
        if (pssKb != null) memUsedBytes = pssKb * 1024L
        // Device RAM never changes: ask once.
        if (memTotalBytes == 0L) try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val sysMem = ActivityManager.MemoryInfo()
                am.getMemoryInfo(sysMem)
                memTotalBytes = sysMem.totalMem
            }
        } catch (_: Throwable) { /* keep prior value */ }
    }

    companion object {
        /**
         * [T-android-resource-hud-offmain] Total PSS in KiB from the text of
         * `/proc/<pid>/smaps_rollup`: `Pss` + `SwapPss`, the same total
         * `Debug.MemoryInfo.getTotalPss()` reports. Null when there is no
         * `Pss` line (not a rollup file).
         */
        internal fun parseTotalPssKb(rollup: String): Long? {
            var pss: Long? = null
            var swapPss = 0L
            for (line in rollup.lineSequence()) {
                val t = line.trim()
                val value = t.substringAfter(':', "").trim().substringBefore(' ').toLongOrNull() ?: continue
                when (t.substringBefore(':')) {
                    "Pss" -> pss = value
                    "SwapPss" -> swapPss = value
                }
            }
            return pss?.let { it + swapPss }
        }
    }

    fun formattedCpu(): String =
        // Allow values >100% (top-style IRIX) — coerce only the lower
        // bound so a transient negative tick delta doesn't leak through.
        // %.0f%% (no width) avoids reserving a column when the value is
        // 1- or 2-digit and overflowing visually when it's 3-digit.
        String.format("CPU %.0f%%", cpuUsage.coerceAtLeast(0f))

    fun formattedMem(compact: Boolean = false): String {
        val usedGB = memUsedBytes / 1_073_741_824.0
        if (compact) return String.format("MEM %.1fG", usedGB)
        val totalGB = memTotalBytes / 1_073_741_824.0
        return String.format("MEM %.1f/%.1f GB", usedGB, totalGB)
    }
}

/**
 * Compose-friendly entrypoint. Polls every 2 s while [active] is true,
 * triggering a recompose on every sample so the consumer can read the
 * monitor's fresh values straight from the returned instance.
 *
 * Stops sampling when [active] flips false (LaunchedEffect cancels its
 * coroutine). Cheap on backgrounded shells: no thread, no broadcast,
 * no allocations beyond the file reads.
 */
@Composable
fun rememberSystemResourceMonitor(active: Boolean): SystemResourceMonitor {
    val context = LocalContext.current
    val monitor = remember { SystemResourceMonitor() }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(active) {
        if (!active) {
            monitor.reset()
            return@LaunchedEffect
        }
        // Prime the baseline; first read returns 0% by design (no prior
        // tick snapshot to subtract). Two seconds later we have a real
        // delta, matching iOS's first-tick behavior.
        // [T-android-resource-hud-offmain] Sample on IO; only the tick that
        // publishes the result runs on the main thread. This used to sample
        // right here, on Main, every 2 s.
        withContext(Dispatchers.IO) { monitor.sampleOnce(context) }
        tick++
        while (isActive) {
            delay(2000)
            withContext(Dispatchers.IO) { monitor.sampleOnce(context) }
            tick++
        }
    }
    // Read `tick` so Compose tracks this snapshot state and recomposes
    // whenever it advances. Without the read the LaunchedEffect would
    // tick happily but no consumer ever sees fresh values.
    @Suppress("UNUSED_VARIABLE") val t = tick
    return monitor
}

// ─── Merged from ThinkingChunking.kt ──────────────────────────────────────────────────

/**
 * [#467] Chunking for the "Show full thinking" viewer ([ThinkingFullDialog]).
 *
 * The inline thinking block shows only its last 8000 characters, so the
 * viewer is the one place the whole text is drawn - up to hundreds of
 * thousands of characters. Compose `Text` measures its whole string, so the
 * text is cut into chunks, each its own lazy item, and only a few are put on
 * screen at first; more follow as the user scrolls near the end. The tuning
 * matches iOS ToolLiveSheet ([T-ios-tool-result-lazy-render] /
 * [T-ios-tool-sheet-watchdog]): 40 lines a chunk, 5 chunks to start (at most
 * 10 KB), 5 more per batch.
 *
 * A chunk is cut after its 40th line, or earlier at [CHUNK_MAX_CHARS]: a
 * single line of 50,000 characters (model output without newlines is
 * common) must not become one huge item. Such a cut falls on the last
 * newline inside the limit, else on a grapheme boundary, so an emoji, a
 * surrogate pair or a CJK character is never split.
 *
 * Pure Kotlin (java.text only) so it runs in JVM unit tests.
 */
object ThinkingChunking {
    /** Lines per chunk (iOS LazyRenderTuning.chunkLines). */
    const val CHUNK_LINES = 40

    /** A chunk is never longer than this, whatever its line count. */
    const val CHUNK_MAX_CHARS = 4000

    /** Chunks revealed when the viewer opens (iOS initialChunks). */
    const val INITIAL_CHUNKS = 5

    /** ...unless they reach this many characters first (iOS initialByteCap). */
    const val INITIAL_CHAR_CAP = 10 * 1024

    /** Chunks added per batch while the viewer keeps up (iOS batchChunks). */
    const val BATCH_CHUNKS = 5

    /**
     * A batch whose frame took longer than this switches the viewer to one
     * chunk at a time, on request (the watchdog, see [paceAfter]).
     */
    const val SLOW_BATCH_MS = 500L

    /** Character range `[start, end)` of one chunk. */
    data class Chunk(val start: Int, val end: Int) {
        val length: Int get() = end - start
    }

    /** How the viewer reveals more: [batch] chunks at once, [auto] = as the end scrolls into view. */
    data class Pacing(val batch: Int = BATCH_CHUNKS, val auto: Boolean = true)

    /** The chunks of [text], in order, covering it exactly. Empty text has none. */
    fun chunks(text: String, lines: Int = CHUNK_LINES, maxChars: Int = CHUNK_MAX_CHARS): List<Chunk> {
        require(lines > 0 && maxChars > 0)
        if (text.isEmpty()) return emptyList()
        val graphemes = BreakIterator.getCharacterInstance().apply { setText(text) }
        val out = ArrayList<Chunk>()
        var pos = 0
        while (pos < text.length) {
            val limit = minOf(text.length, pos + maxChars)
            // After the `lines`-th newline, if it comes before the limit.
            var end = -1
            var seen = 0
            var i = pos
            while (i < limit) {
                if (text[i] == '\n' && ++seen == lines) {
                    end = i + 1
                    break
                }
                i++
            }
            if (end < 0) {
                end = if (limit == text.length) {
                    limit
                } else {
                    val nl = text.lastIndexOf('\n', limit - 1)
                    if (nl >= pos) nl + 1 else graphemeCut(text, graphemes, pos, limit)
                }
            }
            out += Chunk(pos, end)
            pos = end
        }
        return out
    }

    /**
     * The last grapheme boundary in `(pos, limit]`; the end of the first
     * grapheme when it alone is longer. Boundaries inside an emoji sequence
     * (a ZWJ family, a skin tone, a variation selector) are skipped too: the
     * JDK's character iterator does not know them (Android's ICU one does).
     */
    private fun graphemeCut(text: String, graphemes: BreakIterator, pos: Int, limit: Int): Int {
        var b = if (graphemes.isBoundary(limit)) limit else graphemes.preceding(limit)
        while (b > pos && joined(text, b)) b = graphemes.preceding(b)
        if (b > pos) return b
        b = graphemes.following(pos)
        while (b < text.length && joined(text, b)) b = graphemes.following(b)
        return b
    }

    /** Whether a cut at [at] would split an emoji sequence. */
    private fun joined(text: String, at: Int): Boolean {
        if (at <= 0 || at >= text.length) return false
        val next = text.codePointAt(at)
        return text[at - 1] == '\u200D' ||
            next == 0x200D ||
            next in 0xFE00..0xFE0F ||
            next in 0x1F3FB..0x1F3FF ||
            next in 0xE0020..0xE007F
    }

    /** Text of chunk [index]. */
    fun chunkText(text: String, chunks: List<Chunk>, index: Int): String =
        chunks[index].let { text.substring(it.start, it.end) }

    /** Chunks shown when the viewer opens: up to [INITIAL_CHUNKS], stopping once [INITIAL_CHAR_CAP] is reached. */
    fun initialCount(chunks: List<Chunk>): Int {
        if (chunks.isEmpty()) return 0
        var count = 0
        var chars = 0
        for (c in chunks.take(INITIAL_CHUNKS)) {
            chars += c.length
            count++
            if (chars >= INITIAL_CHAR_CAP) break
        }
        return count
    }

    /** Chunks shown after one more batch. */
    fun nextCount(revealed: Int, total: Int, pacing: Pacing = Pacing()): Int =
        minOf(total, revealed + pacing.batch)

    fun hasMore(revealed: Int, total: Int): Boolean = revealed < total

    /** Characters on screen with [revealed] chunks shown: `[0, end)`. */
    fun renderedRange(chunks: List<Chunk>, revealed: Int): IntRange =
        if (revealed <= 0 || chunks.isEmpty()) IntRange.EMPTY else 0 until chunks[minOf(revealed, chunks.size) - 1].end

    /**
     * The watchdog: a batch whose first frame took [frameMs] or more slows
     * the viewer to one chunk per tap, so a device that cannot keep up is
     * never frozen by automatic loading. It does not speed up again.
     */
    fun paceAfter(pacing: Pacing, frameMs: Long): Pacing =
        if (frameMs >= SLOW_BATCH_MS) Pacing(batch = 1, auto = false) else pacing

    /** What "Copy All" copies: the whole thinking text, not what is on screen. */
    fun copyText(full: String): String = full
}

// ─── Merged from ThinkingLevelLocalized.kt ──────────────────────────────────────────────────

/**
 * Localized display name for [ThinkingLevel]. The data-layer
 * [ThinkingLevel.displayName] returns hard-coded English (it predates the
 * UI i18n pass and is also consumed by debug surfaces) — this extension
 * provides the user-facing translation for the slash panel + Thinking
 * level chips, threading through Context to reach strings.xml.
 */
fun ThinkingLevel.localizedName(context: Context): String =
    context.getString(
        when (this) {
            ThinkingLevel.OFF -> R.string.thinking_level_off
            ThinkingLevel.LOW -> R.string.thinking_level_low
            ThinkingLevel.MEDIUM -> R.string.thinking_level_medium
            ThinkingLevel.HIGH -> R.string.thinking_level_high
            // [T-android-thinking-level-arch] XHIGH now has its own label; "Max"
            // moved to the new MAX case, plus ULTRA.
            ThinkingLevel.XHIGH -> R.string.thinking_level_xhigh
            ThinkingLevel.MAX -> R.string.thinking_level_max
            ThinkingLevel.ULTRA -> R.string.thinking_level_ultra
        },
    )

// ─── Merged from ThinkingRowSettle.kt ──────────────────────────────────────────────────

/**
 * [T-android-thinking-row-follows-run] The "… is thinking" row must not
 * outlive the run.
 *
 * Three things on screen say "a turn is running", and each read a different
 * state:
 *  - the thinking row: the assistant BUBBLE's own flags
 *    (`isStreaming && (no visible content || isAwaitingModelResponse)`);
 *  - the composer's stop / send button: the ViewModel's `isStreaming`;
 *  - the background capsule and notification: SessionActivityTracker.
 * The last two are cleared by every exit. The bubble's flags were cleared by
 * whichever exit path remembered to (normal end, the error banner, Stop's
 * cleanup). One did not: switching model while the turn sat in the auto-retry
 * ladder cancels the job and clears the other two, and deliberately skips
 * Stop's cleanup - so the placeholder bubble kept saying "thinking" next to a
 * send button, with nothing running (user report 2026-10-07).
 *
 * So the bubble's flags are now settled from the run state itself: whenever
 * the ViewModel is not streaming, no bubble may still claim to be.
 */
internal object ThinkingRowSettle {

    /**
     * [messages] with every bubble's streaming / awaiting flag cleared, or
     * null when none was set (nothing to publish). Content, tool blocks and
     * errors are left as they are.
     */
    fun settled(messages: List<ChatMessage>): List<ChatMessage>? {
        if (messages.none { it.isStreaming || it.isAwaitingModelResponse }) return null
        return messages.map {
            if (it.isStreaming || it.isAwaitingModelResponse) it.copy(isStreaming = false, isAwaitingModelResponse = false) else it
        }
    }
}

// ─── Merged from ToolDurationWire.kt ──────────────────────────────────────────────────

/**
 * [T-tool-duration-persist] A tool call's execution time, persisted with its
 * result in the iOS wire shape so it survives a reload and crosses platforms.
 *
 * iOS stores it as `ToolResult.snapshot.duration` (`ToolSnapshot.duration:
 * TimeInterval?`, SECONDS, a Double) and restores `block.toolDuration` from it
 * on reload (T-tool-duration-restore). Android's toolResult part already
 * writes iOS's `snapshot` object, so the time goes in the same object under
 * the same key and unit. Backup and sync copy the parts JSON verbatim, so an
 * iOS row's duration reaches Android and Android's reaches iOS.
 *
 * In memory Android keeps milliseconds (`AssistantBlock.durationMs`); only the
 * wire is seconds. A missing, null or non-positive value means "not recorded"
 * (rows written before this, cancelled or blocked calls) and restores as 0,
 * which shows no badge: a time is never invented.
 */
internal object ToolDurationWire {
    /** Key inside `toolResult.value.snapshot`, verbatim from iOS ToolSnapshot. */
    const val KEY = "duration"

    /** `,"duration":<seconds>` to append inside the snapshot object, or "" when unknown. */
    fun snapshotField(durationMs: Long?): String =
        if (durationMs == null || durationMs <= 0) "" else ",\"$KEY\":${durationMs / 1000.0}"

    /** Milliseconds from a toolResult part's `value`; 0 when not recorded. */
    fun restoreMs(value: JSONObject?): Long {
        val snapshot = value?.optJSONObject("snapshot") ?: return 0L
        if (!snapshot.has(KEY) || snapshot.isNull(KEY)) return 0L
        val seconds = snapshot.optDouble(KEY, Double.NaN)
        if (seconds.isNaN() || seconds.isInfinite() || seconds <= 0) return 0L
        return Math.round(seconds * 1000)
    }
}

// ─── Merged from ToolFoldPlanner.kt ──────────────────────────────────────────────────

/**
 * [T-port-fold-tools] Decides which runs of consecutive tool calls in one
 * assistant message the chat list folds into a single "Ran N tools · 9s" row.
 * Pure: it sees only a compact description of each block, so the rule is
 * unit-tested without the list. Port of iOS `ToolFoldPlanner` (b42f64364,
 * 55a66303b, 1b5d69c60, fe1500201, e5651c84f, 2f59b72ee), final state.
 *
 * A run starts at the first tool block and continues through
 * - more finished tool blocks (counted),
 * - thinking blocks (folded along, not counted),
 * - empty text blocks (a text block that has not received content yet),
 * - one-line remarks between tool calls ([Input.NOTE], see [markingNotes]):
 *   folded along, not counted.
 *
 * It ends at a non-empty text block, an info block, a sub agent / user_ask
 * block, or a tool that has not finished. Only a run of at least
 * [MINIMUM_TOOL_COUNT] tools that ends at a NON-EMPTY text block folds. A fold
 * covers the first tool through the last tool (or note) of the run.
 *
 * Thinking right before the run's first tool (only empty text between them)
 * folds with the run too. Thinking after the last member stays outside, next
 * to the reply it leads into.
 *
 * A note is also a soft end: when the run is then cut by an unfinished tool, a
 * barrier or the end of the message, the part up to its latest note still
 * folds.
 */
object ToolFoldPlanner {
    /** The minimum number of tool blocks in a run before it folds. */
    const val MINIMUM_TOOL_COUNT = 2

    /**
     * The fewest lines a request's reply needs for the end of that request to
     * fold the tool runs before it.
     */
    const val SEPARATOR_MINIMUM_LINES = 2

    enum class ToolOutcome { UNFINISHED, SUCCEEDED, FAILED, CANCELLED }

    /** What the planner needs to know about one block. */
    sealed class Input {
        data class Tool(val outcome: ToolOutcome) : Input()
        object Thinking : Input() { override fun toString() = "Thinking" }
        data class Text(val isEmpty: Boolean) : Input()
        /** A one-line text with a tool call after it - a remark, not the reply. */
        object Note : Input() { override fun toString() = "Note" }
        /** Always visible and ends a run: info rows, sub agent and user_ask blocks. */
        object Barrier : Input() { override fun toString() = "Barrier" }
    }

    data class Group(
        /** Indices of the folded blocks, first member through last member. */
        val range: IntRange,
        val toolCount: Int,
        val failedCount: Int,
        val cancelledCount: Int,
    )

    fun plan(blocks: List<Input>, enabled: Boolean): List<Group> {
        if (!enabled) return emptyList()
        val groups = mutableListOf<Group>()
        var start: Int? = null
        var leadThinking: Int? = null
        var lastMember = 0
        var tools = 0
        var failed = 0
        var cancelled = 0
        var softEnd: Group? = null

        fun reset() {
            start = null
            tools = 0; failed = 0; cancelled = 0
            softEnd = null
        }
        fun closeAtSoftEnd() {
            softEnd?.let { if (it.toolCount >= MINIMUM_TOOL_COUNT) groups.add(it) }
        }

        for ((i, block) in blocks.withIndex()) {
            when (block) {
                is Input.Barrier -> {
                    closeAtSoftEnd(); reset(); leadThinking = null
                }
                is Input.Tool -> {
                    if (block.outcome == ToolOutcome.UNFINISHED) {
                        closeAtSoftEnd(); reset(); leadThinking = null
                        continue
                    }
                    if (start == null) start = leadThinking ?: i
                    leadThinking = null
                    lastMember = i
                    tools += 1
                    if (block.outcome == ToolOutcome.FAILED) failed += 1
                    if (block.outcome == ToolOutcome.CANCELLED) cancelled += 1
                }
                is Input.Note -> {
                    // Only after a tool: a remark before the first call is not
                    // part of any run, and it separates thinking before it
                    // from the call after it.
                    val s = start
                    if (s == null) {
                        leadThinking = null
                        continue
                    }
                    lastMember = i
                    softEnd = Group(s..i, tools, failed, cancelled)
                }
                is Input.Thinking -> if (start == null && leadThinking == null) leadThinking = i
                is Input.Text -> {
                    if (block.isEmpty) continue
                    val s = start
                    if (s != null && tools >= MINIMUM_TOOL_COUNT) {
                        groups.add(Group(s..lastMember, tools, failed, cancelled))
                    }
                    reset()
                    leadThinking = null
                }
            }
        }
        // A run still open here has no reply after it: only its part up to the
        // latest note folds.
        closeAtSoftEnd()
        return groups
    }

    /**
     * Marks the one-line remarks a model writes between tool calls as
     * [Input.NOTE]: a non-empty text is a note when [isSingleLine] holds AND a
     * tool call comes after it. Everything after the last tool call is the
     * reply and is never a note.
     */
    fun markingNotes(inputs: List<Input>, isSingleLine: (Int) -> Boolean): List<Input> {
        val lastTool = inputs.indexOfLast { it is Input.Tool }
        if (lastTool < 0) return inputs
        val out = inputs.toMutableList()
        for (i in 0 until lastTool) {
            if (out[i] == Input.Text(isEmpty = false) && isSingleLine(i)) out[i] = Input.Note
        }
        return out
    }

    /** One line by line BREAKS: no newline between the first and last visible character. */
    fun isSingleLine(text: String): Boolean {
        val first = text.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return true
        val last = text.indexOfLast { !it.isWhitespace() }
        for (i in first..last) if (text[i] == '\n' || text[i] == '\r') return false
        return true
    }

    private fun lines(text: String): Sequence<String> = text.splitToSequence("\r\n", "\n", "\r")

    /** Lines with at least one visible character. */
    fun nonEmptyLineCount(text: String): Int = lines(text).count { line -> line.any { !it.isWhitespace() } }

    /**
     * Whether a request whose reply text (all its text blocks, joined) is
     * [requestText] folds the runs before it when it ends.
     */
    fun isFoldSeparator(requestText: String): Boolean {
        var n = 0
        for (line in lines(requestText)) {
            if (line.any { !it.isWhitespace() }) {
                n += 1
                if (n >= SEPARATOR_MINIMUM_LINES) return true
            }
        }
        return false
    }

    /**
     * For a message whose task is still running: how many leading blocks may
     * be planned - through the last block of the latest separator request; 0
     * before the first one. Ids no longer in the message are ignored.
     */
    fun <ID> foldHorizon(blockIds: List<ID>, separators: Set<ID>): Int {
        if (separators.isEmpty()) return 0
        val last = blockIds.indexOfLast { it in separators }
        return if (last < 0) 0 else last + 1
    }

    /**
     * Whether a run that is due to fold is held back for now because folding
     * it would move what the user is reading: only while they are scrolled up,
     * never for a run already folded once, and only when it belongs to the
     * running message or its blocks are on the list as separate rows right
     * now. [shownAsRows] is only called when the cheaper conditions leave the
     * answer open.
     */
    fun holdsBack(
        browsing: Boolean,
        alreadyFolded: Boolean,
        inRunningMessage: Boolean,
        shownAsRows: () -> Boolean,
    ): Boolean {
        if (!browsing || alreadyFolded) return false
        return inRunningMessage || shownAsRows()
    }

    // --- AssistantBlock adapters ---------------------------------------------

    /** The planner's view of one block. */
    fun input(block: AssistantBlock): Input = when (block.kind) {
        "text" -> Input.Text(isEmpty = block.content.none { !it.isWhitespace() })
        "thinking" -> Input.Thinking
        "info" -> Input.Barrier
        else -> when {
            // iOS: delegate and user_ask blocks are never folded.
            com.openminis.app.agent.jobs.HelperRunner.isSubAgentToolName(block.toolName) -> Input.Barrier
            block.toolName == com.openminis.app.agent.userask.UserAskPromptText.TOOL_NAME -> Input.Barrier
            else -> when (block.toolStatus) {
                ToolBlockStatus.SUCCESS -> Input.Tool(ToolOutcome.SUCCEEDED)
                // A timed-out call ended in failure; the capsule shows it as such.
                ToolBlockStatus.FAILED, ToolBlockStatus.TIMEOUT -> Input.Tool(ToolOutcome.FAILED)
                ToolBlockStatus.CANCELLED -> Input.Tool(ToolOutcome.CANCELLED)
                ToolBlockStatus.STREAMING, ToolBlockStatus.PENDING, ToolBlockStatus.RUNNING, null ->
                    Input.Tool(ToolOutcome.UNFINISHED)
            }
        }
    }

    /** The planner's view of a message's blocks, remarks marked as notes. */
    fun inputs(blocks: List<AssistantBlock>): List<Input> =
        markingNotes(blocks.map(::input)) { isSingleLine(blocks[it].content) }

    /** One folded run, as the list draws it. */
    data class FoldedRun(
        /**
         * Expanded-state key: the first TOOL's id (the provider's tool_use
         * id, which survives a reload), not a leading thinking block's.
         */
        val key: String,
        val firstBlockId: String,
        val memberIds: List<String>,
        val group: Group,
        /** Summed execution time of the folded calls; null when none recorded one. */
        val durationMs: Long?,
    )

    fun foldedRuns(blocks: List<AssistantBlock>): List<FoldedRun> =
        plan(inputs(blocks), enabled = true).map { g ->
            val members = blocks.subList(g.range.first, g.range.last + 1)
            val firstTool = members.firstOrNull { it.kind == "tool_use" } ?: members.first()
            val durations = members.filter { it.kind == "tool_use" && it.durationMs > 0 }.map { it.durationMs }
            FoldedRun(
                key = firstTool.id,
                firstBlockId = members.first().id,
                memberIds = members.map { it.id },
                group = g,
                durationMs = if (durations.isEmpty()) null else durations.sum(),
            )
        }

    /**
     * [T-port-fold-tools] The live message's fold horizon, from its blocks.
     *
     * iOS marks a request's end from inside the agent loop. Android derives it
     * from the blocks the loop has written, which carry the same information:
     * - a request is the stretch of blocks one LLM call produced. A new one
     *   starts at the first non-tool block after a tool block (the model
     *   speaks again only after the previous call's tools returned);
     * - a request has ended when a later request has started, or when it has
     *   tool calls and none of them is still streaming its arguments (the LLM
     *   stream is over; its tools may still be running). A request with no
     *   tool calls is the final reply, which ends with the task, and then the
     *   whole message is planned anyway;
     * - an ended request whose text has [SEPARATOR_MINIMUM_LINES]+ lines is a
     *   separator, and the horizon runs through its last block.
     * Text between parallel calls of one request counts as a new request here,
     * a small difference from iOS that can only make a separator come later.
     */
    fun liveHorizon(blocks: List<AssistantBlock>): Int {
        if (blocks.isEmpty()) return 0
        val separators = HashSet<String>()
        var start = 0
        var i = 0
        while (start < blocks.size) {
            // Extend through the non-tool lead-in, then the tool calls.
            i = start
            while (i < blocks.size && blocks[i].kind != "tool_use") i++
            while (i < blocks.size && blocks[i].kind == "tool_use") i++
            val end = i - 1 // inclusive
            val request = blocks.subList(start, end + 1)
            val isLast = i >= blocks.size
            val tools = request.filter { it.kind == "tool_use" }
            val ended = !isLast || (tools.isNotEmpty() && tools.none { it.toolStatus == ToolBlockStatus.STREAMING })
            if (ended) {
                val text = request.filter { it.kind == "text" }.joinToString("\n") { it.content }
                if (isFoldSeparator(text)) separators.add(blocks[end].id)
            }
            start = i
        }
        return foldHorizon(blocks.map { it.id }, separators)
    }

    /** Runs to fold in [message]: up to its live horizon while it is running, else all of it. */
    fun foldedRuns(message: ChatMessage): List<FoldedRun> {
        val blocks = message.toolBlocks
        if (blocks.isEmpty()) return emptyList()
        val horizon = if (message.isStreaming) liveHorizon(blocks) else blocks.size
        if (horizon <= 0) return emptyList()
        return foldedRuns(if (horizon >= blocks.size) blocks else blocks.subList(0, horizon))
    }
}

// ─── Merged from ToolFoldRows.kt ──────────────────────────────────────────────────

/**
 * [T-port-fold-tools] The block a list row draws, or null for rows that are
 * not one of a message's blocks (header, typing, usage, error, ...). An
 * assistant text block is drawn as one or more markdown fragments, all
 * belonging to the same block.
 */
internal fun FlatChatItem.foldBlockId(): String? = when (this) {
    is FlatChatItem.AssistantToolUse -> block.id
    is FlatChatItem.AssistantThinking -> block.id
    is FlatChatItem.AssistantMarkdownBlock -> parentBlockId
    is FlatChatItem.AssistantText -> block.id
    else -> null
}

/** The message a block row belongs to, without the defensive `#n` key suffix. */
internal fun FlatChatItem.foldMessageId(): String? = when (this) {
    is FlatChatItem.AssistantToolUse -> messageId
    is FlatChatItem.AssistantThinking -> messageId
    is FlatChatItem.AssistantMarkdownBlock -> messageId
    is FlatChatItem.AssistantText -> messageId
    else -> null
}?.substringBefore('#')

/**
 * [T-port-fold-tools] Replace each folded run's rows with one
 * [FlatChatItem.AssistantToolGroup] row, placed where its first member was.
 * An [expanded] run keeps its members after the group row, so tapping the
 * row opens it in place. Rows of other blocks are passed through untouched
 * (same instances), so LazyColumn sees no change for them. With no runs the
 * input list itself is returned.
 */
internal fun applyToolFolds(
    rows: List<FlatChatItem>,
    runsByMessage: Map<String, List<ToolFoldPlanner.FoldedRun>>,
    expanded: Set<String>,
): List<FlatChatItem> {
    if (runsByMessage.isEmpty()) return rows
    val runOfBlock = HashMap<String, HashMap<String, ToolFoldPlanner.FoldedRun>>()
    for ((mid, runs) in runsByMessage) {
        val byBlock = runOfBlock.getOrPut(mid) { HashMap() }
        for (run in runs) for (id in run.memberIds) byBlock[id] = run
    }
    val emitted = HashSet<String>()
    val out = ArrayList<FlatChatItem>(rows.size)
    for (row in rows) {
        val bid = row.foldBlockId()
        val run = bid?.let { b -> row.foldMessageId()?.let { runOfBlock[it]?.get(b) } }
        if (run == null) {
            out.add(row)
            continue
        }
        val messageId = (row.foldMessageId() ?: "")
        val isExpanded = run.key in expanded
        if (emitted.add("$messageId\u0000${run.key}")) {
            out.add(
                FlatChatItem.AssistantToolGroup(
                    messageId = messageId,
                    runKey = run.key,
                    toolCount = run.group.toolCount,
                    failedCount = run.group.failedCount,
                    cancelledCount = run.group.cancelledCount,
                    durationMs = run.durationMs,
                    expanded = isExpanded,
                ),
            )
        }
        if (isExpanded) out.add(row)
    }
    return out
}

/**
 * [T-port-fold-tools] One chat's folding state: the setting, which runs the
 * user has opened, which runs have been folded once, and whether the user is
 * browsing (scrolled up). [changes] ticks whenever an input that is not part
 * of the message list changes, so the list pipeline re-folds.
 *
 * Called from the list pipeline's collector, one call at a time.
 */
internal class ToolFoldController {
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private val _expanded = MutableStateFlow<Set<String>>(emptySet())
    val expanded: StateFlow<Set<String>> = _expanded.asStateFlow()

    @Volatile var enabled: Boolean = false
        set(value) { if (field != value) { field = value; bump() } }

    /** True while the user is scrolled up, reading. */
    @Volatile var browsing: Boolean = false
        set(value) { if (field != value) { field = value; bump() } }

    /** Runs folded at least once: they stay folded (no hold-back). */
    private val foldedOnce = HashSet<String>()

    /**
     * The rows the last [fold] call published. Their keys are only collected
     * when a hold-back check needs them (scrolled up, a run not folded yet),
     * not on every streaming tick.
     */
    private var shownRows: List<FlatChatItem> = emptyList()

    private class Cached(val blocks: List<AssistantBlock>, val streaming: Boolean, val runs: List<ToolFoldPlanner.FoldedRun>)
    private val planCache = HashMap<String, Cached>()

    fun toggle(runKey: String) {
        _expanded.value = _expanded.value.let { if (runKey in it) it - runKey else it + runKey }
        bump()
    }

    private fun bump() { _changes.value = _changes.value + 1 }

    /**
     * [T-in-chat-search] Opens [runKey] without toggling (a search result in
     * a folded run must open it, never close an open one). iOS:
     * expandedToolGroups.insert(run.key).
     */
    fun expand(runKey: String) {
        if (runKey in _expanded.value) return
        _expanded.value = _expanded.value + runKey
        bump()
    }

    /** [T-in-chat-search] The folded run of [messageId] that holds block [blockId], if any. */
    fun runKeyContaining(messageId: String, blockId: String): String? =
        planCache[messageId]?.runs?.firstOrNull { blockId in it.memberIds }?.key

    private fun runsFor(message: ChatMessage): List<ToolFoldPlanner.FoldedRun> {
        val c = planCache[message.id]
        if (c != null && c.blocks === message.toolBlocks && c.streaming == message.isStreaming) return c.runs
        val runs = ToolFoldPlanner.foldedRuns(message)
        planCache[message.id] = Cached(message.toolBlocks, message.isStreaming, runs)
        return runs
    }

    /**
     * Fold [rows], built from [messages] (with the live overlay applied to the
     * message(s) in [live], which take precedence by id).
     */
    fun fold(rows: List<FlatChatItem>, messages: List<ChatMessage>, live: List<ChatMessage>): List<FlatChatItem> {
        if (!enabled) {
            shownRows = emptyList()
            return rows
        }
        val liveById = if (live.isEmpty()) emptyMap() else live.associateBy { it.id }
        val accepted = HashMap<String, List<ToolFoldPlanner.FoldedRun>>()
        var rowKeys: Set<String>? = null
        for (m in messages) {
            // [T-fold-live-turn] The live copy first: during a turn the stored
            // message has no tool blocks yet (they stream into the overlay), so
            // checking the stored one never folded a running task until it ended.
            val message = liveById[m.id] ?: m
            if (message.role != "assistant" || message.toolBlocks.size < ToolFoldPlanner.MINIMUM_TOOL_COUNT) continue
            val runs = runsFor(message)
            if (runs.isEmpty()) continue
            val kept = runs.filter { run ->
                val held = ToolFoldPlanner.holdsBack(
                    browsing = browsing,
                    alreadyFolded = run.key in foldedOnce,
                    inRunningMessage = message.isStreaming,
                ) {
                    val keys = rowKeys ?: shownRows.mapTo(HashSet(shownRows.size)) { it.key }.also { rowKeys = it }
                    "tool:${message.id}:${run.key}" in keys &&
                        "toolgroup:${message.id}:${run.key}" !in keys
                }
                if (!held) foldedOnce.add(run.key)
                !held
            }
            if (kept.isNotEmpty()) accepted[message.id] = kept
        }
        val out = applyToolFolds(rows, accepted, _expanded.value)
        shownRows = out
        return out
    }
}

// ─── Merged from ToolTextTail.kt ──────────────────────────────────────────────────

/**
 * [T-android-tool-text-line-cap] Bounded slices of tool output for places that
 * hand it to a Compose `Text(String)`.
 *
 * A `Text` measures every paragraph it is given in full before `maxLines` or
 * `overflow` get a say: EmojiCompat scans the string, MeasuredText measures
 * every glyph and LineBreaker breaks the whole paragraph. Cutting by LINES
 * alone therefore does nothing for a single huge line. A 200 KB shell line
 * with no newline froze the main thread for 30 s in the tool preview tile
 * (Alice Q4), and because the tile re-renders the session's last tool block
 * on open, the session froze again every time it was reopened.
 *
 * Both helpers here look at a bounded window of the input and bound every
 * line they emit, so their cost and their output size are independent of how
 * large the tool output is.
 */
internal object ToolTextTail {
    /** Longest line handed to a `Text`. A 100dp tile shows ~30 chars of it. */
    const val MAX_LINE_CHARS = 400

    /**
     * How far back from the end [lastLines] looks. Lines that start before
     * this window are cut at its edge; nothing before it is ever scanned.
     */
    const val MAX_SCAN_CHARS = 8_000

    const val ELLIPSIS = "\u2026"

    /**
     * Longest line the tool detail sheet lays out. The sheet shows the whole
     * output, so a cut there keeps both ends of the line; Copy still copies
     * the untouched `block.content`.
     */
    const val DETAIL_MAX_LINE_CHARS = 4_000

    /**
     * The last [n] lines of [text], each cut to its last [maxLineChars]
     * characters with a leading [ELLIPSIS] when it was longer. A single
     * trailing newline does not count as a blank line, and a `\r` before a
     * `\n` is dropped. Only the last [maxScanChars] characters are read.
     */
    fun lastLines(
        text: String,
        n: Int,
        maxLineChars: Int = MAX_LINE_CHARS,
        maxScanChars: Int = MAX_SCAN_CHARS,
    ): String {
        if (text.isEmpty() || n <= 0) return ""
        var end = text.length
        if (text[end - 1] == '\n') end--
        val floor = surrogateSafeStart(text, (end - maxScanChars).coerceAtLeast(0), end)
        val lines = ArrayList<String>(n)
        var lineEnd = end
        while (lines.size < n) {
            // Manual scan instead of lastIndexOf: lastIndexOf would keep going
            // below `floor`, which is exactly the unbounded walk to avoid.
            var nl = lineEnd - 1
            while (nl >= floor && text[nl] != '\n') nl--
            val hitFloor = nl < floor
            val start = if (hitFloor) floor else nl + 1
            var stop = lineEnd
            if (stop > start && text[stop - 1] == '\r') stop--
            lines += tailOf(text, start, stop, maxLineChars, cutBefore = hitFloor && floor > 0)
            if (hitFloor) break
            lineEnd = nl
        }
        lines.reverse()
        return lines.joinToString("\n")
    }

    /**
     * The first [maxChars] characters of [text], with a trailing [ELLIPSIS]
     * when it was longer. For single-line headers (a shell command), where the
     * start is the informative part.
     */
    fun head(text: String, maxChars: Int = MAX_LINE_CHARS): String {
        if (text.length <= maxChars) return text
        var e = maxChars
        if (e > 0 && Character.isHighSurrogate(text[e - 1])) e--
        return text.substring(0, e) + ELLIPSIS
    }

    /**
     * [text] with every line longer than [maxLineChars] replaced by its first
     * and last halves around an [ELLIPSIS]. Returns [text] itself when no
     * line is that long, so the common case allocates nothing.
     */
    fun capLongLines(text: String, maxLineChars: Int = DETAIL_MAX_LINE_CHARS): String {
        var sb: StringBuilder? = null
        var start = 0
        var copied = 0
        while (start <= text.length) {
            var stop = text.indexOf('\n', start)
            if (stop < 0) stop = text.length
            if (stop - start > maxLineChars) {
                val b = sb ?: StringBuilder(text.length.coerceAtMost(1 shl 16)).also { sb = it }
                val half = maxLineChars / 2
                var headEnd = start + half
                if (Character.isHighSurrogate(text[headEnd - 1])) headEnd--
                val tailStart = surrogateSafeStart(text, stop - half, stop)
                b.append(text, copied, headEnd).append(' ').append(ELLIPSIS).append(' ')
                copied = tailStart
            }
            start = stop + 1
        }
        val b = sb ?: return text
        return b.append(text, copied, text.length).toString()
    }

    private fun tailOf(text: String, start: Int, stop: Int, maxChars: Int, cutBefore: Boolean): String {
        if (stop - start <= maxChars) {
            val line = text.substring(start, stop)
            return if (cutBefore) ELLIPSIS + line else line
        }
        return ELLIPSIS + text.substring(surrogateSafeStart(text, stop - maxChars, stop), stop)
    }

    /** Moves [i] forward past a low surrogate so a cut never splits a pair. */
    private fun surrogateSafeStart(text: String, i: Int, limit: Int): Int =
        if (i in 1 until limit && Character.isLowSurrogate(text[i])) i + 1 else i
}

// ─── Merged from ToolTitleReminder.kt ──────────────────────────────────────────────────

/**
 * [T-tool-title-reminder] Gentle one-time nudge for a missing `tool_title`.
 * Mirrors iOS `ToolTitleReminder` (AIChatViewModel+ConcurrentTools.swift),
 * with the identical text and marker, so a session synced between platforms is
 * recognised as already reminded on either side.
 *
 * Some models skip `tool_title` even though the schema marks it required. The
 * tool still runs, but its card can only show the raw command. A short, soft
 * `<system-reminder>` is appended to the model-facing result of a titled tool
 * that came back without a title — on the 1st, 11th, 21st… such call. Counting
 * and throttling live in [com.openminis.app.agent.ToolLoopDetector.recordToolTitle]
 * (the loop detector's lock, warning buckets and reset lifecycle); this object
 * only holds the text and the display-side helpers. The UI never shows it: the
 * live card is filled from the tool's own output, and the reload path strips it
 * with [stripForDisplay].
 */
object ToolTitleReminder {
    /** Unique tag, so detection/stripping never touch any other reminder. */
    const val MARKER = "[minis:tool-title-reminder]"

    const val TEXT = "<system-reminder>$MARKER This is a soft reminder. " +
        "This tool call has no `tool_title`; please include a short one next time.</system-reminder>"

    /** True when the call has no usable title: absent, not a string, or blank. */
    fun isMissingTitle(args: JSONObject): Boolean {
        val v = args.opt("tool_title") as? String ?: return true
        return v.isBlank()
    }

    /** Only tools whose schema asks for a title; MCP tools etc. are never "missing" one. */
    fun declaresTitle(parameterNames: Collection<String>): Boolean = "tool_title" in parameterNames

    /** Append the note after the real output. */
    fun appending(content: String): String = if (content.isEmpty()) TEXT else content + "\n\n" + TEXT

    private val displayRe = Regex("""\n*<system-reminder>\[minis:tool-title-reminder\][\s\S]*?</system-reminder>""")

    /** The tool output as the user should see it: only this note removed. */
    fun stripForDisplay(text: String): String =
        if (!text.contains(MARKER)) text else displayRe.replace(text, "")
}

// ─── Merged from VoiceScreenContext.kt ──────────────────────────────────────────────────

/**
 * [T-android-voice-viewport-context] Bridges the chat list to voice
 * correction's screen context: which rows are on screen, and what text each
 * row carries.
 *
 * The chat LazyColumn renders `flatItems.asReversed()` keyed by
 * [FlatChatItem.key], so a laid-out row's key identifies its flat item
 * exactly; no index arithmetic against the list's extra header items
 * (compact progress, resume banner) is needed.
 */
internal class VoiceScreenItemsHolder {
    /** The rows currently rendered, oldest first. Written from composition. */
    @Volatile
    var items: List<FlatChatItem> = emptyList()
}

/**
 * What a row contributes: user text, assistant reply text, or a tool call's
 * TITLE (the pill's line: `tool_title`, else the tool name; never arguments or
 * output). Headers, thinking, usage, cards and notices carry no text but keep
 * their place for geometry.
 */
internal fun FlatChatItem.toScreenSegment(): ScreenContextBuilder.Segment = when (this) {
    is FlatChatItem.UserBubble -> ScreenContextBuilder.Segment(
        key, message.id, ScreenContextBuilder.Kind.USER,
        TypedVocabularyBuilder.stripAttachmentMarkup(message.content),
    )
    is FlatChatItem.AssistantText -> ScreenContextBuilder.Segment(
        key, messageId, ScreenContextBuilder.Kind.ASSISTANT, block.content,
    )
    is FlatChatItem.AssistantMarkdownBlock -> ScreenContextBuilder.Segment(
        key, messageId, ScreenContextBuilder.Kind.ASSISTANT, rawText,
    )
    is FlatChatItem.AssistantLegacyContent -> ScreenContextBuilder.Segment(
        key, messageId, ScreenContextBuilder.Kind.ASSISTANT, content,
    )
    is FlatChatItem.AssistantToolUse -> ScreenContextBuilder.Segment(
        key, messageId, ScreenContextBuilder.Kind.TOOL, block.toolTitle.ifBlank { block.toolName },
    )
    else -> ScreenContextBuilder.Segment(key, "", null, "")
}

/**
 * Capture the snapshot. Call on the main thread: layout info is consistent
 * only there. Returns null when the list has not been laid out.
 */
internal fun captureVoiceScreenSnapshot(
    listState: LazyListState,
    items: List<FlatChatItem>,
    reverseLayout: Boolean,
): ScreenContextBuilder.Snapshot? {
    val info = listState.layoutInfo
    if (items.isEmpty() || info.visibleItemsInfo.isEmpty()) return null
    return ScreenContextBuilder.Snapshot(
        segments = items.map { it.toScreenSegment() },
        visible = info.visibleItemsInfo.mapNotNull { v ->
            (v.key as? String)?.let { ScreenContextBuilder.VisibleItem(it, v.offset, v.size) }
        },
        viewportStart = info.viewportStartOffset,
        viewportEnd = info.viewportEndOffset,
        olderAtLargerOffsets = reverseLayout,
    )
}

// ─── Merged from SelectionDocumentOrder.kt ──────────────────────────────────────────────────

/**
 * [T-android-selection-offscreen-order] (GH#296) Where a text shard sits in the
 * document, independent of whether it is composed right now.
 *
 * A selection that grows past one screen scrolls its start out of the
 * LazyColumn (the handle drag auto-scrolls at the edge). The start shard then
 * unregisters, and everything that ordered shards by their on-screen y — which
 * endpoint comes first, which shards lie between them, which text to copy —
 * lost its footing: the highlight vanished from every paragraph between the
 * endpoints, and a copy returned only the tail of the last shard.
 *
 * The chat already had a document-order key (`shardOrderKey`, 0224dacb1), but
 * it parsed the number after the last ':' of the shard id, and 958ff9290 then
 * suffixed every MdText id with `#<subIndex>` — "3#0" is not an int, so the key
 * was null for every shard in the chat and ordering silently fell back to y.
 * It was also only an index within one parent block, so it could not order
 * blocks of different parents, nor different messages.
 *
 * [DocOrder] is (row in the flattened chat list, sub-index within that row),
 * which orders any two shards of the chat, across blocks and messages.
 */
data class DocOrder(val row: Int, val sub: Int) : Comparable<DocOrder> {
    override fun compareTo(other: DocOrder): Int =
        compareValuesBy(this, other, DocOrder::row, DocOrder::sub)
}

/**
 * What ordering and copy need to know about a shard: its text and where it
 * was. Captured from a live [TextShard] on register, and FROZEN on unregister
 * while a selection is active (see SelectionController.retired), so a shard
 * that scrolled away still contributes its text to the copy.
 *
 * Kept separate from [TextShard] so the logic can be exercised without a
 * Compose TextLayoutResult.
 */
class ShardText(
    val id: TextShardId,
    val plainText: String,
    val rawMarkdown: String?,
    /** Current window y, or null once the shard is gone / unmeasurable. */
    val y: () -> Float?,
)

/**
 * The flattened-row key (FlatChatItem.key) a chat shard belongs to, or null
 * when the shard id has no chat row shape. Mirrors the shard ids ChatScreen
 * builds for each row kind:
 *
 *   shard "text:<blockId>[#n]"            -> row "text:<messageId>:<blockId>"
 *   shard "mdblock:<parent>:<index>[#n]"  -> row "mdblock:<messageId>:<parent>:<index>"
 *   shard "legacy[#n]"                    -> row "legacy:<messageId>"
 *
 * The `#n` is the MdText sub-index (StreamingMarkdownText's
 * ShardSubIndexAllocator) and is not part of the row key.
 */
internal fun chatRowKeyForShard(id: TextShardId): String? {
    val base = shardBaseId(id.shardId)
    return when {
        base.startsWith("text:") -> "text:${id.messageId}:${base.removePrefix("text:")}"
        base.startsWith("mdblock:") -> "mdblock:${id.messageId}:${base.removePrefix("mdblock:")}"
        base == "legacy" -> "legacy:${id.messageId}"
        else -> null
    }
}

/** The MdText sub-index suffix (`#n`) of a shard id, 0 when absent. */
internal fun shardSubIndex(shardId: String): Int {
    val hash = shardId.lastIndexOf('#')
    if (hash < 0) return 0
    return shardId.substring(hash + 1).toIntOrNull() ?: 0
}

private fun shardBaseId(shardId: String): String {
    val hash = shardId.lastIndexOf('#')
    return if (hash >= 0 && shardId.substring(hash + 1).toIntOrNull() != null) shardId.substring(0, hash) else shardId
}

/**
 * The chat's [SelectionController.documentOrder]: a shard's row position from
 * the flattened-row index ChatScreen already publishes (`flatRowIndexByKey`),
 * plus its sub-index. Null when the row is not in the loaded window.
 */
internal fun chatDocumentOrder(rowIndexByKey: () -> Map<String, Int>): (TextShardId) -> DocOrder? = { id ->
    chatRowKeyForShard(id)
        ?.let { rowIndexByKey()[it] }
        ?.let { DocOrder(it, shardSubIndex(id.shardId)) }
}

// ─── Merged from StreamingFade.kt ──────────────────────────────────────────────────

/**
 * [T-android-stream-fade] Word-level fade-in for streamed markdown text.
 *
 * Mirror of iOS TextFadeAnimator: newly appended characters render at α=0
 * and ease to α=1 over [FADE_DURATION_MS], with a small staggered delay
 * across word boundaries so the appearance reads as "words landing in
 * sequence" rather than "a single block flashing on". Only the streaming
 * last block opts in via [LocalAppendOnlyFade]; everything else (history,
 * cold-loaded sessions, completed messages) renders fully opaque.
 *
 * Implementation:
 *  - Each MdText with the local set to true holds a [FadeController] that
 *    tracks the previous plainText prefix. When the new plainText extends
 *    that prefix, the suffix gets sliced into word ranges, each tagged with
 *    a (startTimeNanos, staggerOffsetMs) pair.
 *  - A single `withFrameNanos` loop in the composable advances animation
 *    progress and writes the current alpha to a snapshot-state map. The
 *    MdText reads that map ONLY from a draw lambda ([forEachActive]) that
 *    paints a background-coloured mask over each fading word inside its own
 *    graphics layer. Composition never reads it, so a frame costs a few
 *    small paths — not a recomposition, not a paragraph re-layout.
 *
 *    [T-android-stream-fade-relayout] It used to be consumed in composition:
 *    `overlay()` rebuilt the whole AnnotatedString with per-word alpha spans
 *    every frame, which made `Text` re-lay-out and re-record the ENTIRE live
 *    paragraph per vsync. Measured on a Pixel 4a: record(draw) 8→18 ms as the
 *    live block grew from 2k to 8k chars, ~93 % of frames over budget for the
 *    whole stream — the fade (350 ms + 300 ms stagger) never went idle between
 *    500 ms publishes, so this was the steady state, not a burst.
 *  - When all ranges reach α=1 the loop suspends until a new append
 *    arrives, keeping idle cost at zero.
 *  - A guard caps the in-flight word count: bursts beyond [MAX_FADE_WORDS]
 *    are emitted fully opaque instead, matching iOS's TextFadeAnimator
 *    `maxAnimatedWords = 160` short-circuit so a 1k-token reflow can't
 *    saturate the frame budget.
 */

internal val LocalAppendOnlyFade = compositionLocalOf { false }

/**
 * [T-android-stream-fade-reentry] How a streaming text node's fade starts
 * when its [FadeController] is created WITH fade on.
 *
 * Being created with fade on used to mean "this paragraph just appeared",
 * so all of its first text faded in. It does not always mean that. The node
 * is created from scratch whenever its composition is: re-entering a chat
 * whose reply is still streaming, or scrolling the streaming message out of
 * the list and back. Its text was on screen before, and fading it from
 * alpha 0 replays the animation over text the user had already read.
 * [FadeShownText] remembers what each node has shown, outside composition,
 * so a recreated node can tell the two apart.
 */
internal sealed class FadeBirth {
    /** Genuinely new paragraph: fade all of it. */
    object FadeAll : FadeBirth()

    /** All of it was on screen already: adopt it, fade nothing. */
    object SeedAll : FadeBirth()

    /** [shown] was on screen; only what follows it is new and fades. */
    data class SeedPrefix(val shown: String) : FadeBirth()
}

/**
 * [T-android-stream-fade-reentry] Decide how a new controller starts.
 *
 * @param fadeFromBirth fade was on at this node's first composition.
 * @param shown texts [FadeShownText] recorded as on screen for this node's
 *   fragment (most recent first).
 * @param text the text it is being created with.
 */
internal fun fadeBirthPlan(fadeFromBirth: Boolean, shown: List<String>, text: String): FadeBirth {
    // Flag flipped on later (the existing T-android-stream-fade-seed case):
    // the node rendered this text opaque before it had a controller.
    if (!fadeFromBirth) return FadeBirth.SeedAll
    // The longest recorded text this one extends: that much was on screen, so
    // only what follows it is new. A node recreated mid-stream finds its own
    // earlier text here; a genuinely new paragraph matches nothing.
    val prior = shown.filter { it.isNotEmpty() && text.startsWith(it) }.maxByOrNull { it.length }
        ?: return FadeBirth.FadeAll
    return if (prior.length == text.length) FadeBirth.SeedAll else FadeBirth.SeedPrefix(prior)
}

/**
 * [T-android-stream-fade-reentry] What the fading text nodes of each
 * streaming fragment have put on screen, keyed by "messageId/baseShardId"
 * (the fragment's selection shard id). Deliberately NOT the per-node shard
 * id: that carries a sub-index handed out in composition order, which can
 * change when the fragment is rebuilt — exactly when this is needed. A few
 * recent texts are kept per fragment; a recreated node finds its own earlier
 * text among them by prefix. Process-wide so it outlives the composition;
 * LRU-bounded like [TableHScrollStates]. Pure Kotlin (not
 * android.util.LruCache) so it is unit-testable.
 */
internal object FadeShownText {
    private const val MAX_FRAGMENTS = 64
    private const val TEXTS_PER_FRAGMENT = 6

    private val shown = object : LinkedHashMap<String, ArrayDeque<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayDeque<String>>): Boolean =
            size > MAX_FRAGMENTS
    }

    /** Recorded texts for [key], most recent first. */
    fun get(key: String): List<String> = synchronized(shown) { shown[key]?.toList() ?: emptyList() }

    /**
     * Record [text] as on screen. A text that extends the most recent entry
     * replaces it (the same paragraph growing); anything else is a new entry.
     */
    fun put(key: String, text: String) {
        if (text.isEmpty()) return
        synchronized(shown) {
            val q = shown.getOrPut(key) { ArrayDeque() }
            val head = q.firstOrNull()
            if (head != null && (text.startsWith(head) || head.startsWith(text))) {
                if (text.length >= head.length) q[0] = text
            } else {
                q.addFirst(text)
                while (q.size > TEXTS_PER_FRAGMENT) q.removeLast()
            }
        }
    }

    internal fun clearForTests() = synchronized(shown) { shown.clear() }
}

// [T-android-streaming-incremental-inline] True only for the LIVE streaming tail
// block. When set, RenderBlock's Paragraph branch routes inline/math through
// the incremental cache (frozen closed prefix + fresh unclosed suffix) instead
// of re-scanning the whole growing paragraph every throttle tick. Off (false)
// for every frozen/history block, which keeps the plain per-block cache.
internal val LocalLiveIncremental = compositionLocalOf { false }

// [T-android-stream-fade] Timings are iOS TextFadeAnimator's
// (`wordFadeDuration` 0.45 s, `staggerWindow` 0.10 s split evenly across the
// batch's words, no per-word floor): every batch finishes in ~window+duration
// no matter how many words it carries, and consecutive batches overlap rather
// than queueing. Android used to spread a batch over 300 ms (90 ms a word, 350 ms
// fade) to make the left-to-right ripple more visible; next to the iOS build it
// read as text trailing the stream, so it now matches iOS exactly.
// [T-android-stream-fade-ios-timing]
internal const val FADE_DURATION_MS = 450L
internal const val STAGGER_WINDOW_MS = 100L
private const val MAX_FADE_WORDS = 160

/** iOS `TextFadeAnimator.stagger(forWordCount:)`: the window divided across the batch. */
internal fun perWordStaggerMs(wordCount: Int): Long =
    if (wordCount <= 0) 0L else STAGGER_WINDOW_MS / wordCount

private data class FadeRange(
    val start: Int,
    val end: Int,
    val staggerMs: Long,
)

internal class FadeController {
    /** plainText prefix already seen — anything beyond this is fresh. */
    var lastPlainText: String = ""
        private set

    /**
     * [T-android-stream-fade-seed] Whether this controller has ever been
     * handed text. Lets the caller tell a controller's FIRST text apart from
     * an append, which the prefix-diff in [ingest] cannot do on its own: a
     * fresh controller's `lastPlainText` is "", every string starts with "",
     * so the whole initial text would be sliced into fade ranges and drawn
     * at alpha 0 — erasing text the user was already reading.
     */
    var hasSeenText: Boolean = false
        private set

    /** Active animating ranges. Frozen at α=1 ranges are removed each tick. */
    private val rangesState: SnapshotStateList<FadeRange> = mutableListOf<FadeRange>().toMutableStateList()

    /** start-time nanos per range (parallel to rangesState; same indexing). */
    private val rangeStartNanos = ArrayDeque<Long>()

    /** Per-range current alpha, updated each frame; read in [overlay]. */
    val alphas: SnapshotStateMap<Int, Float> = SnapshotStateMap()

    /** True when at least one range is still under α=1. */
    val hasActiveRanges: Boolean get() = rangesState.isNotEmpty()

    /**
     * [T-android-stream-fade-lost-wake] Signalled whenever [ingest] adds fade
     * ranges; [runTicks] waits on it while idle. Conflated, so a signal sent
     * while the ticker is still busy is kept for its next wait instead of
     * lost. See [runTicks].
     */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * [T-android-stream-fade-seed] Adopt [plainText] as already-visible
     * content: record it as the prefix and create NO fade ranges. For a
     * controller created after its block was rendered opaque — a list that
     * only became the live tail once its second item arrived, or any block
     * whose fade flag flipped on later — the text it is handed at birth was
     * on screen before the controller existed. Fading it would paint the
     * background over it.
     *
     * Subsequent [ingest] calls diff against this prefix normally, so words
     * appended AFTER seeding still fade in.
     */
    fun seed(plainText: String) {
        hasSeenText = true
        rangesState.clear()
        rangeStartNanos.clear()
        alphas.clear()
        lastPlainText = plainText
    }

    fun ingest(newPlainText: String) {
        hasSeenText = true
        if (newPlainText == lastPlainText) return
        // On a hard reset (text shrank or diverged from prefix), drop all
        // in-flight ranges — the caller is rendering a brand-new block.
        if (!newPlainText.startsWith(lastPlainText)) {
            rangesState.clear()
            rangeStartNanos.clear()
            alphas.clear()
            lastPlainText = newPlainText
            return
        }
        val base = lastPlainText.length
        val suffix = newPlainText.substring(base)
        lastPlainText = newPlainText
        if (suffix.isEmpty()) return

        // Split suffix into word-like runs separated by whitespace. Punctuation
        // stays attached to its preceding word (iOS does the same), keeping
        // the rhythm of "words landing" rather than "every glyph landing".
        val words = mutableListOf<IntRange>()
        var cursor = 0
        var inWord = false
        var wordStart = 0
        for (i in suffix.indices) {
            val c = suffix[i]
            val isWs = c.isWhitespace()
            if (!isWs && !inWord) {
                wordStart = i; inWord = true
            } else if (isWs && inWord) {
                words.add(wordStart until i); inWord = false
            }
        }
        if (inWord) words.add(wordStart until suffix.length)

        // Whitespace-only suffix: nothing visible to fade — skip.
        if (words.isEmpty()) return

        // Stagger budget mirrors iOS: the window is fixed, so the per-word
        // stagger shrinks as the batch grows.
        val totalWords = words.size + rangesState.size
        if (totalWords > MAX_FADE_WORDS) {
            // Too many in flight — flush everything to α=1 and skip the new
            // ranges so we don't spend frames rendering an invisible wall.
            rangesState.clear()
            rangeStartNanos.clear()
            alphas.clear()
            return
        }
        val perWordStaggerMs = perWordStaggerMs(words.size)

        for ((idx, wr) in words.withIndex()) {
            val absStart = base + wr.first
            val absEnd = base + wr.last + 1
            val staggerMs = idx * perWordStaggerMs
            rangesState.add(FadeRange(absStart, absEnd, staggerMs))
            rangeStartNanos.addLast(System.nanoTime())
        }
        wake.trySend(Unit)
    }

    /**
     * [T-android-stream-fade-lost-wake] The per-frame loop: tick until every
     * range is opaque, wait for [ingest] to add more, repeat. Never returns.
     *
     * Reported on a Pixel 4a: a reply showed only its first character above
     * a running tool. The accessibility tree held the whole 27-character
     * paragraph, two lines tall, so the text was laid out — every character
     * after the first sat under a fully opaque mask, permanently.
     *
     * The old driver was `LaunchedEffect(hasActiveRanges)` whose loop broke
     * once [tick] emptied the ranges. A frame runs animation callbacks (the
     * tick) BEFORE recomposition, so when the last range finished in the same
     * frame whose recomposition ingested more text, the loop had already
     * exited while `hasActiveRanges` read true both before and after: the
     * effect key never changed, nothing relaunched it, and the new ranges
     * were never ticked. [forEachActive] reports an unticked range at α=0, so
     * their mask stayed at full strength. The window is one frame; a heavy
     * recomposition — a tool call landing right after text — widens it.
     *
     * Waiting on [wake] instead of on a composition key closes it: an ingest
     * during a busy loop leaves a token, so the next wait returns at once.
     *
     * @param awaitFrame runs one frame callback and returns its result;
     *   `withFrameNanos` in production, a fake clock in tests.
     */
    suspend fun runTicks(awaitFrame: suspend (onFrame: (Long) -> Boolean) -> Boolean): Nothing {
        while (true) {
            while (awaitFrame(::tick)) Unit
            wake.receive()
        }
    }

    /**
     * Advance every range to its current alpha based on [nowNanos]. Returns
     * false when no ranges remain animating (caller can suspend the loop).
     */
    fun tick(nowNanos: Long): Boolean {
        if (rangesState.isEmpty()) return false
        val finished = mutableListOf<Int>()
        for (i in rangesState.indices) {
            val r = rangesState[i]
            val startNs = rangeStartNanos.elementAt(i)
            val elapsedMs = (nowNanos - startNs) / 1_000_000L - r.staggerMs
            val alpha = if (elapsedMs <= 0) 0f
            else if (elapsedMs >= FADE_DURATION_MS) 1f
            else {
                val t = elapsedMs.toFloat() / FADE_DURATION_MS
                // Ease-out cubic 1 - (1-t)^3 (matches iOS animator curve).
                val inv = 1f - t
                1f - inv * inv * inv
            }
            alphas[r.start] = alpha
            if (alpha >= 1f) finished.add(i)
        }
        // Pop finished ranges from the end so indices shift predictably.
        for (i in finished.asReversed()) {
            val r = rangesState.removeAt(i)
            rangeStartNanos.removeAt(i)
            alphas.remove(r.start)
        }
        return rangesState.isNotEmpty()
    }

    /**
     * Visit every range still fading, with its current alpha. Call this from a
     * DRAW scope only: the snapshot reads here are what schedule the next
     * redraw, and keeping them out of composition is the whole point.
     */
    fun forEachActive(block: (start: Int, end: Int, alpha: Float) -> Unit) {
        for (r in rangesState) block(r.start, r.end, alphas[r.start] ?: 0f)
    }
}

@Composable
internal fun rememberFadeController(): FadeController =
    remember { FadeController() }

/**
 * Drives the per-frame tick for [controller]. Suspends when nothing is
 * animating and wakes when [FadeController.ingest] adds ranges. Single
 * instance per MdText so each animating block runs independently.
 *
 * [T-android-stream-fade-lost-wake] Keyed on the controller, not on
 * `hasActiveRanges`: a composition key cannot see a range set that emptied
 * and refilled within one frame, which left new text masked forever. See
 * [FadeController.runTicks].
 */
@Composable
internal fun FadeFrameDriver(controller: FadeController) {
    LaunchedEffect(controller) {
        controller.runTicks { onFrame -> withFrameNanos(onFrame) }
    }
}

/**
 * Hold a stable mutable holder for the most recent base color so the
 * overlay() call doesn't need MdText to pass it through composition every
 * time. Currently unused externally but kept as a hook for future fade
 * extensions (color-shift, ramp-up speed) that depend on the surrounding
 * theme color.
 */
internal data class FadeColorHolder(var color: Color = Color.Unspecified) {
    val state = mutableStateOf(color)
}
