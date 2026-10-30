package com.openminis.app.ui.chat.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.speech.RecognitionState
import com.openminis.app.speech.RetryPromptQueue
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.speech.VoiceFinishTracker
import com.openminis.app.ui.components.PickerModalityFilter
import com.openminis.app.ui.components.UnifiedModelPickerSheet
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * [T-android-voice-panel] Single-select voice INPUT engine picker — Android
 * port of iOS UnifiedModelPicker(config: .voiceInput()).
 *
 * [T-android-unified-model-picker] Now a thin config over the shared
 * [UnifiedModelPickerSheet], exactly how iOS's voiceInput() is a config over
 * UnifiedModelPicker. The previous bespoke body (VoicePickerSheetImpl) drew
 * its own flat radio rows and had visibly drifted from the main model
 * pickers' sectioned style; the shared sheet reuses modelEntryPickerItems,
 * so the System Recognition (Online/Offline) rows, modality chips, search,
 * and Quick Test all render identically to every other picker.
 *
 * Selection writes ProviderRepository.voiceInputOverrideEntryId (System
 * composite id / entry id / null = follow the group).
 */
@Composable
fun VoiceInputPickerSheet(
    providerRepository: ProviderRepository,
    onDismiss: () -> Unit,
    onEditProvider: ((instanceId: String) -> Unit)? = null,
) {
    val config by providerRepository.config.collectAsState()
    UnifiedModelPickerSheet(
        providerRepository = providerRepository,
        title = stringResource(R.string.voice_input_picker_title),
        modalityFilter = PickerModalityFilter.AUDIO_INPUT,
        boundGroup = config.voiceInputGroupId?.let { gid ->
            config.modelGroups.find { it.id == gid }
        },
        boundGroupName = providerRepository.voiceInputGroupName(),
        selectedId = providerRepository.voiceInputOverrideEntryId,
        onSelect = { providerRepository.voiceInputOverrideEntryId = it },
        onDismiss = onDismiss,
        onEditProvider = onEditProvider,
    )
}

/**
 * [T-android-tts-capsule] Single-select voice OUTPUT (TTS) model picker — the
 * output twin, mirroring iOS UnifiedModelPicker(config: .voiceOutput()) which
 * the speech-player capsule's model chip opens. Selection writes
 * ProviderRepository.voiceOutputOverrideEntryId — the per-device override
 * slot resolveVoiceOutputChoice() consults first.
 */
@Composable
fun VoiceOutputPickerSheet(
    providerRepository: ProviderRepository,
    onDismiss: () -> Unit,
    onEditProvider: ((instanceId: String) -> Unit)? = null,
) {
    val config by providerRepository.config.collectAsState()
    UnifiedModelPickerSheet(
        providerRepository = providerRepository,
        title = stringResource(R.string.tts_capsule_picker_title),
        modalityFilter = PickerModalityFilter.AUDIO_OUTPUT,
        boundGroup = config.voiceOutputGroupId?.let { gid ->
            config.modelGroups.find { it.id == gid }
        },
        boundGroupName = providerRepository.voiceOutputGroupName(),
        selectedId = providerRepository.voiceOutputOverrideEntryId,
        onSelect = { providerRepository.voiceOutputOverrideEntryId = it },
        onDismiss = onDismiss,
        onEditProvider = onEditProvider,
    )
}

// ─── Merged from VoiceSendGate.kt ──────────────────────────────────────────────────

/**
 * Shared state between the inline voice panel and the chat composer's send
 * paths. Process-scoped like [VoiceModePrefs]: only one voice panel is ever on
 * screen, and the composer (which owns Send) and the panel (which owns the
 * recognizer callbacks) are separate composables.
 *
 * Two jobs, mirroring iOS VoiceInputViewModel:
 *
 *  - [T-voice-send-waits-for-asr] "Does voice mode still owe text?" and the
 *    wait-then-send that Send performs when it does ([finishCaptureForSend]).
 *    Before this, Send while recording cleared the composer, which made the
 *    panel CANCEL the capture — the last sentence spoken before the tap never
 *    reached the message.
 *  - [T-voice-asr-failure-retry-prompt] The queue of failed utterances whose
 *    audio is kept until the user picks Retry or Discard.
 */
object VoiceSendGate {

    private const val TAG = "VoiceSend"

    /** One failed utterance awaiting the user's Retry / Discard decision. */
    class FailedUtterance(
        val id: Long,
        val audio: SpeechRecognitionManager.FailedAudio,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val nextId = AtomicLong(1)

    /**
     * Bumped every failure, synchronously on the reporting thread — before
     * SpeechRecognitionManager publishes IDLE — so a waiting send can never
     * mistake a failed take for a clean finish.
     */
    private val failureCount = AtomicInteger(0)

    /** Failed utterances, oldest first. Main-thread writes only. */
    var queue by mutableStateOf(RetryPromptQueue<FailedUtterance>())
        private set

    /** True while a send is waiting for capture to stop and ASR to finish. */
    var isFinishingForSend by mutableStateOf(false)
        private set

    /**
     * Composition generation: bumped when the composer is emptied from outside
     * the panel (a send). A failure belonging to an older generation is logged,
     * not prompted — its composition is already gone.
     */
    var generation by mutableIntStateOf(0)
        private set

    /**
     * [T-voice-send-busy] The pending wait, by attempt number; null when none.
     * A wait whose attempt is no longer pending was abandoned and neither
     * sends nor releases anything when it ends. Main-thread writes only.
     */
    private var attempts = 0
    private var pendingAttempt: Int? = null

    /**
     * [T-voice-send-busy] Take a pending send back and release the button: the
     * user cancelled the transcription, tapped the mic to record again, edited
     * the text (keyboard, backspace, clear all, AI correction), or left voice
     * mode / the chat (the panel went away). Recognition still running keeps
     * running and its text still lands in the transcript - it is just no
     * longer sent on its own. Port of iOS abandonFinishForSend (b48cfdbfe).
     */
    fun abandonFinishForSend(reason: String) {
        if (pendingAttempt == null) return
        AppLogger.info(TAG, "✋ [voice-send] pending send abandoned ($reason) — not sending")
        pendingAttempt = null
        isFinishingForSend = false
    }

    /** Latest panel transcript, so a deferred send can copy it explicitly. */
    @Volatile
    var transcript: String = ""

    fun bumpGeneration() {
        generation += 1
    }

    /**
     * Anything captured that has not become composer text yet: the mic is
     * capturing, audio is held below the segment threshold (the provider
     * engine keeps the mic open then), a transcription or retry is in flight,
     * or a send is already waiting on one.
     */
    fun owesText(): Boolean =
        VoiceModePrefs.isVoiceActive &&
            (isFinishingForSend || SpeechRecognitionManager.state.value != RecognitionState.IDLE)

    // ── [T-voice-asr-failure-retry-prompt] failure queue ────────────────────

    /**
     * Queue a failed utterance for the retry prompt. Safe from any thread (the
     * provider engine reports on an IO thread).
     */
    fun recordFailure(failed: SpeechRecognitionManager.FailedAudio, captureGeneration: Int) {
        if (captureGeneration != generation) {
            AppLogger.info(
                TAG,
                "[voice-failure] ${"%.1f".format(failed.seconds)}s failed after the composition " +
                    "was sent/cleared — not prompting (${failed.error})",
            )
            return
        }
        failureCount.incrementAndGet()
        AppLogger.info(TAG, "⚠️ [voice-failure] ${"%.1f".format(failed.seconds)}s kept for retry (${failed.error})")
        val item = FailedUtterance(nextId.getAndIncrement(), failed)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            queue = queue.enqueue(item)
        } else {
            mainHandler.post { queue = queue.enqueue(item) }
        }
    }

    /** User chose Retry: remove the head and return it for re-transcription. */
    fun takeForRetry(): FailedUtterance? {
        val (item, rest) = queue.takeForRetry()
        queue = rest
        if (item != null) {
            AppLogger.info(TAG, "🔁 [voice-failure] retry requested (${"%.1f".format(item.audio.seconds)}s)")
        }
        return item
    }

    /** Put an utterance back (a retry that could not start right now). */
    fun requeue(item: FailedUtterance) {
        queue = queue.enqueue(item)
    }

    /** User chose Discard: drop the head's audio. */
    fun discardCurrent() {
        queue.current?.let {
            AppLogger.info(TAG, "🗑️ [voice-failure] discarded by user (${"%.1f".format(it.audio.seconds)}s)")
        }
        queue = queue.discard()
    }

    // ── [T-voice-send-waits-for-asr] finish before send ─────────────────────

    /**
     * Called by every send entry (button, swipe, IME return) before it sends.
     *
     * @return false when voice mode owes nothing — the caller sends normally.
     *   true when the send was deferred (or a deferral is already pending, in
     *   which case this repeated tap is ignored so nothing is sent twice); the
     *   caller must stop, and [onReady] runs once every captured sample has
     *   been transcribed into the composer.
     */
    fun deferSendIfOwed(scope: CoroutineScope, context: Context, onReady: () -> Unit): Boolean {
        if (!owesText()) return false
        finishCaptureForSend(scope, context, onReady)
        return true
    }

    /**
     * Stop the mic, push EVERY captured sample to recognition — the manual
     * stop flushes the open VAD segment AND audio held below the segment
     * minimum — and run [onReady] once all transcriptions have landed.
     *
     * [onReady] does not run when a transcription failed while waiting (the
     * retry prompt takes over; sending now would drop that speech) or when
     * recognition is still running after 60 s (a toast asks the user to tap
     * Send again once the text appears; the result still lands in the
     * composer when it arrives).
     */
    private fun finishCaptureForSend(scope: CoroutineScope, context: Context, onReady: () -> Unit) {
        if (isFinishingForSend) {
            AppLogger.info(TAG, "[voice-send] finish already pending — ignoring repeated send")
            return
        }
        isFinishingForSend = true
        val attempt = ++attempts
        pendingAttempt = attempt
        val failuresBefore = failureCount.get()
        val state = SpeechRecognitionManager.state.value
        AppLogger.info(TAG, "🎙️ [voice-send] send while state=$state — finishing recognition first")
        if (state == RecognitionState.RECORDING || state == RecognitionState.STARTING) {
            SpeechRecognitionManager.stopRecording()
        }
        val app = context.applicationContext
        scope.launch {
            val tracker = VoiceFinishTracker()
            val started = SystemClock.elapsedRealtime()
            var step: VoiceFinishTracker.Step
            try {
                do {
                    delay(100)
                    if (pendingAttempt != attempt) return@launch
                    step = tracker.tick(
                        idle = SpeechRecognitionManager.state.value == RecognitionState.IDLE,
                        failedSinceStart = failureCount.get() > failuresBefore,
                        elapsedMs = SystemClock.elapsedRealtime() - started,
                    )
                } while (step == VoiceFinishTracker.Step.WAIT)
            } finally {
                // Only the pending attempt releases the button; an abandoned
                // one already did, and must not touch a newer wait.
                if (pendingAttempt == attempt) {
                    pendingAttempt = null
                    isFinishingForSend = false
                }
            }
            val elapsedS = (SystemClock.elapsedRealtime() - started) / 1000.0
            when (step) {
                VoiceFinishTracker.Step.SEND -> {
                    AppLogger.info(TAG, "✅ [voice-send] recognition finished in ${"%.1f".format(elapsedS)}s — sending")
                    onReady()
                }
                VoiceFinishTracker.Step.FAILED ->
                    AppLogger.info(TAG, "⚠️ [voice-send] a transcription failed while finishing — send held for the retry prompt")
                VoiceFinishTracker.Step.TIMED_OUT -> {
                    AppLogger.info(TAG, "⏳ [voice-send] recognition still running after ${elapsedS.toInt()}s — send abandoned")
                    android.widget.Toast.makeText(
                        app,
                        app.getString(R.string.voice_send_still_transcribing),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                VoiceFinishTracker.Step.WAIT -> Unit
            }
        }
    }
}

// ─── Merged from VoiceTranscriptEditor.kt ──────────────────────────────────────────────────

/**
 * [T-android-voice-dblclick-select] The voice transcript's edit mode.
 *
 * It used to be a Compose `BasicTextField`. Compose text fields (1.9) count
 * clicks for the MOUSE only: on touch, a second tap just moves the caret, and
 * selecting a word takes a long press. So once the transcript was in edit
 * mode, double-tapping a word did nothing visible - unlike iOS, where the
 * editor is a UITextView and a double tap selects the word or phrase.
 *
 * A platform EditText behaves like the iOS editor: double tap selects the word
 * under the finger (the platform word iterator, which segments Chinese by
 * dictionary rather than one character at a time), followed by the system
 * selection handles and the copy / cut / paste menu. Long press and caret
 * placement are the platform's own as well.
 *
 * The caller keeps its String; [onValueChange] reports every edit. Focus and
 * the keyboard are taken when the editor appears and given back when it
 * leaves, as the Compose field did.
 */
@Composable
internal fun VoiceTranscriptEditor(
    value: String,
    onValueChange: (String) -> Unit,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f).toArgb()
    val maxHeightPx = with(LocalDensity.current) { maxHeight.roundToPx() }
    val latestOnChange = rememberUpdatedState(onValueChange)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            EditText(context).apply {
                background = null
                setPadding(0, 0, 0, 0)
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                isVerticalScrollBarEnabled = true
                setText(value)
                setSelection(text.length)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        val now = s?.toString().orEmpty()
                        if (tag != now) {
                            tag = now
                            latestOnChange.value(now)
                        }
                    }
                })
                tag = value
                post {
                    requestFocus()
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        },
        update = { edit ->
            edit.setTextColor(textColor)
            edit.highlightColor = highlight
            edit.maxHeight = maxHeightPx
            // An outside change (Clear, backspace button): replace the text,
            // keeping the caret where it was as far as the new text allows.
            if (edit.tag != value) {
                val caret = edit.selectionEnd.coerceIn(0, value.length)
                edit.tag = value
                edit.setText(value)
                edit.setSelection(caret)
            }
        },
        onRelease = { edit ->
            (edit.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(edit.windowToken, 0)
        },
    )
}
