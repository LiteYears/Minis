package com.openminis.app.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import io.codeconcept.realtimecutvadlibrary.VADCallback
import io.codeconcept.realtimecutvadlibrary.VADWrapper
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages Android TextToSpeech engine for reading assistant responses aloud.
 *
 * [T-android-tts-locale GH#324] Language is resolved per utterance by
 * [TtsLocaleResolver]: an identifying script wins, else the user's in-app
 * language, else the system locale. This class used to support only en-US and
 * zh-CN, which meant every other shipped language was read aloud in American
 * English.
 */
class TextToSpeechManager : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TextToSpeech"

        /**
         * [T-android-tts-silent-blackhole] Upper bound on waiting for the
         * engine to bind. Generous because low-end devices cold-start their
         * TTS service; a device with NO engine may never call onInit, and this
         * is what turns that into a detectable failure instead of a hang.
         */
        const val INIT_TIMEOUT_MS = 4_000L
        // [T-android-tts-intranumber-guard] The sentence-boundary set moved to
        // SpeechSentenceSplitter.SENTENCE_ENDERS — a single source of truth
        // shared with ReadAloudPlayer, so the two TTS paths can't drift.
    }

    // @Volatile: written on the binder thread in onInit / nulled on main in
    // shutdown(), and read from both — the posted pre-init replay relies on
    // seeing a torn-down engine rather than a stale non-null.
    @Volatile
    private var tts: TextToSpeech? = null

    /**
     * [T-android-tts-locale GH#324] Application context, kept for reading the
     * saved UI language. Application-scoped, so no Activity is leaked.
     */
    @Volatile
    private var appContext: Context? = null

    /**
     * [T-android-tts-locale GH#324] Set when a caller picked the language
     * explicitly via [setLanguage] — today `android-speak --voice <tag>`.
     *
     * Without this the explicit choice was silently discarded: SpeakOffloadHandler
     * calls `setLanguage(...)` and then `speak(text)`, and `speak` re-ran the
     * detector, overwriting the caller's locale microseconds later. So
     * `--voice es-ES` has never actually worked. Cleared by [stop]/[shutdown]
     * so one explicit utterance cannot pin every later one.
     */
    @Volatile
    private var explicitLocale: Locale? = null
    @Volatile
    var isInitialized = false
        private set

    /**
     * [T-android-tts-silent-blackhole] True when the engine reported ERROR at
     * init — typically a device with no functional TTS engine installed
     * (common on Chinese ROMs without Google TTS, e.g. ColorOS). Callers can
     * use this + [awaitReady] to distinguish "not ready YET" from "will never
     * speak", instead of silently dropping text.
     */
    var initFailed = false
        private set

    /**
     * Resolves once [onInit] runs: true on SUCCESS, false on ERROR. Engine
     * binding is asynchronous, so the first utterances can easily arrive
     * before this settles — see [preInitQueue].
     */
    private val initResult = kotlinx.coroutines.CompletableDeferred<Boolean>()

    /**
     * [T-android-tts-silent-blackhole] Utterances that arrived before init
     * settled. `speak()` used to DROP text whenever `!isInitialized` — since
     * init is async, the very first utterance after construction was silently
     * lost (LazyReadAloudPlayer constructs the engine on first tap, so the
     * first Read Aloud tap spoke nothing on every device). Buffered here and
     * replayed in order from [onInit] instead. Guarded by [queueLock];
     * `onInit` may arrive on a binder thread.
     */
    private val preInitQueue = mutableListOf<String>()
    private val queueLock = Any()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var isPaused = false
    private var pendingTexts = mutableListOf<String>()
    private var pausedAtIndex = 0

    /**
     * Rolling buffer of partial-streaming text that hasn't hit a sentence
     * boundary yet. Cleared by [flush] or [stop]. See [appendText].
     */
    private val sentenceBuffer = StringBuilder()

    var speechRate: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.1f, 3.0f)
            tts?.setSpeechRate(field)
        }

    var speechPitch: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
            tts?.setPitch(field)
        }

    var speechVolume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    /**
     * [T-readaloud-volume-boost] Audio session the engine plays each utterance
     * on (KEY_PARAM_SESSION_ID), so the caller can attach effects to it; 0 =
     * the engine's own session.
     */
    var audioSessionId: Int = 0

    /**
     * Initializes the TTS engine. Must be called before speaking.
     */
    fun init(context: Context) {
        // [T-android-tts-locale GH#324] Retained so the per-utterance locale
        // resolution can read the user's in-app language choice. The manager
        // previously kept no Context at all, which is part of why the language
        // decision could only ever look at the text's script.
        appContext = context.applicationContext
        tts = TextToSpeech(context.applicationContext, this)
    }

    /**
     * [T-android-tts-locale GH#324] The user's saved in-app language tag, or
     * null when they follow the system. Read fresh each utterance rather than
     * cached: the Appearance screen writes this preference and the user can
     * change it without the TTS engine being re-created.
     */
    private fun savedAppLanguageTag(): String? = runCatching {
        appContext
            ?.getSharedPreferences(
                com.openminis.app.ui.settings.PREF_APPEARANCE,
                Context.MODE_PRIVATE,
            )
            ?.getString(com.openminis.app.ui.settings.KEY_LANGUAGE, "")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            tts?.apply {
                setSpeechRate(speechRate)
                setPitch(speechPitch)
                // [T-android-tts-locale GH#324] Was `setLanguage(Locale.US)`.
                // Every utterance re-resolves the language anyway, so this only
                // ever decided the engine's resting state — but on a device
                // whose first utterance raced init, that resting state WAS the
                // voice. Seed from the user's own language instead of pinning
                // American English for everyone.
                applyResolvedLocale(TtsLocaleResolver.resolve("", savedAppLanguageTag()))
                setOnUtteranceProgressListener(createProgressListener())
            }
            Log.d(TAG, "TTS initialized successfully")
            // Replay whatever arrived while the engine was binding, in order.
            val queued = synchronized(queueLock) {
                val copy = preInitQueue.toList()
                preInitQueue.clear()
                copy
            }
            // [T-android-tts-init-thread] Post the replay to the MAIN thread.
            //
            // onInit arrives on a binder thread. speakQueued touches
            // pendingTexts (a plain MutableList that speak()/stop()/the
            // progress listener also mutate) and `tts` (which shutdown()
            // nulls), with no happens-before against any of them. Disposing the
            // ChatScreen inside the ~4 s init window — rotate the device, or
            // navigate away right after enabling read-replies — therefore
            // raced a ConcurrentModificationException on pendingTexts, or a
            // speak() against an already shut-down engine. Same lifecycle shape
            // as the VAD SIGSEGV fixed in 91c7b2944.
            //
            // Re-check liveness inside the post: shutdown() can land between
            // the binder callback and the main-thread turn.
            if (queued.isNotEmpty()) {
                Handler(Looper.getMainLooper()).post {
                    if (tts != null && isInitialized) {
                        for (text in queued) speakQueued(text)
                    } else {
                        Log.d(TAG, "pre-init replay dropped — engine shut down during init")
                    }
                }
            }
        } else {
            initFailed = true
            synchronized(queueLock) { preInitQueue.clear() }
            _isSpeaking.value = false
            Log.e(TAG, "TTS initialization failed with status: $status")
        }
        initResult.complete(status == TextToSpeech.SUCCESS)
    }

    /**
     * [T-android-tts-silent-blackhole] Suspend until init settles (bounded by
     * [timeoutMs] — a device with no engine may never call [onInit] at all).
     * @return true when the engine is usable.
     */
    suspend fun awaitReady(timeoutMs: Long = INIT_TIMEOUT_MS): Boolean {
        if (isInitialized) return true
        if (initFailed) return false
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { initResult.await() } ?: false
    }

    /**
     * Speaks text immediately, interrupting any ongoing speech (QUEUE_FLUSH).
     * Automatically detects language from text content.
     */
    fun speak(text: String) {
        if (text.isBlank()) return
        if (!isInitialized) {
            // [T-android-tts-silent-blackhole] Engine still binding (or dead).
            // Buffer instead of dropping; onInit replays in order. On a failed
            // init the queue is discarded there — nothing can ever speak it.
            if (!initFailed) {
                synchronized(queueLock) {
                    preInitQueue.clear() // speak() semantics: flush what came before
                    preInitQueue.add(text)
                }
                _isSpeaking.value = true
            }
            return
        }

        isPaused = false
        pendingTexts.clear()
        pendingTexts.add(text)
        pausedAtIndex = 0
        sentenceBuffer.setLength(0)

        autoDetectAndSetLanguage(text)

        val params = buildSpeechParams()
        // [T-android-tts-rom-compat] speak() returns ERROR synchronously when
        // the engine rejects the request (missing language data, engine died —
        // both real on OEM/AOSP engines without Google TTS). The result used
        // to be discarded, so isSpeaking stayed true with NO utterance in
        // flight and no progress callback ever coming — ReadAloudPlayer's
        // completion poll then spun forever and the whole read-aloud queue
        // wedged for the rest of the process.
        val rc = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, generateUtteranceId())
        if (rc != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speak rejected by engine (rc=$rc len=${text.length})")
            _isSpeaking.value = false
            return
        }
        _isSpeaking.value = true
    }

    /**
     * Adds text to the speech queue without interrupting (QUEUE_ADD).
     */
    fun speakQueued(text: String) {
        if (text.isBlank()) return
        if (!isInitialized) {
            // [T-android-tts-diag] A field report of "read replies is on but
            // nothing is spoken" could not be diagnosed from the log: this
            // path emitted nothing at all, so whether text ever reached the
            // engine was unknowable. Queued-before-init is the one silent
            // outcome that looks identical to success from the caller's side.
            Log.i(TAG, "speakQueued deferred (init pending) len=${text.length} initFailed=$initFailed")
            if (!initFailed) {
                synchronized(queueLock) { preInitQueue.add(text) }
                _isSpeaking.value = true
            }
            return
        }
        Log.i(TAG, "speakQueued len=${text.length}")

        pendingTexts.add(text)
        autoDetectAndSetLanguage(text)

        val params = buildSpeechParams()
        // [T-android-tts-rom-compat] Same rejected-enqueue guard as speak().
        val rc = tts?.speak(text, TextToSpeech.QUEUE_ADD, params, generateUtteranceId())
        if (rc != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speakQueued rejected by engine (rc=$rc len=${text.length})")
            // Only clear the flag when nothing else is in flight — a QUEUE_ADD
            // rejection must not mark an ongoing earlier utterance as done.
            if (pendingTexts.isEmpty()) _isSpeaking.value = false
            return
        }
        _isSpeaking.value = true
    }

    /**
     * Feed a chunk of streaming AI output. Any complete sentences the chunk
     * produces (boundary characters: `。！？.!?\n` — same set as iOS
     * `extractNewSentences` in AIChatViewModel) are immediately enqueued for
     * TTS via [speakQueued], so the first sentence starts speaking the moment
     * its terminator arrives instead of waiting for the full response.
     *
     * Incomplete tail text stays in [sentenceBuffer] until the next call, or
     * until [flush] runs at stream end.
     *
     * Safe to call before [init]/onInit — chunks are still split; the TTS
     * engine simply drops them if not yet ready (speakQueued guards).
     */
    fun appendText(chunk: String) {
        if (chunk.isEmpty()) return
        sentenceBuffer.append(chunk)
        val sentences = extractCompleteSentences(sentenceBuffer)
        if (sentences.isEmpty()) return
        for (sentence in sentences) {
            speakQueued(sentence)
        }
    }

    /**
     * Emit the remaining buffered tail (a sentence fragment with no trailing
     * punctuation) as a final utterance. Call at the end of a streaming
     * response. Mirrors iOS's post-stream "flush remaining speech" logic.
     */
    fun flush() {
        if (sentenceBuffer.isEmpty()) return
        val tail = sentenceBuffer.toString().trim()
        sentenceBuffer.setLength(0)
        if (tail.isNotEmpty()) {
            speakQueued(tail)
        }
    }

    /**
     * [T-android-tts-intranumber-guard] Delegates to the shared
     * [SpeechSentenceSplitter] so this path and [ReadAloudPlayer] apply the same
     * terminator set AND the same intra-number guards ("3.14" is never cut into
     * "3." + "14").
     */
    private fun extractCompleteSentences(buffer: StringBuilder): List<String> =
        SpeechSentenceSplitter.extractCompleteSentences(buffer)

    /**
     * Stops all speech immediately.
     */
    fun stop() {
        tts?.stop()
        // [T-android-tts-locale GH#324] One explicit `--voice` utterance must
        // not pin the language for every later one.
        explicitLocale = null
        isPaused = false
        pendingTexts.clear()
        pausedAtIndex = 0
        sentenceBuffer.setLength(0)
        // A stop while the engine is still binding must also drop the buffered
        // utterances, or they'd blurt out the moment onInit lands.
        synchronized(queueLock) { preInitQueue.clear() }
        _isSpeaking.value = false
    }

    /**
     * Toggles pause/resume. Since Android TTS doesn't natively support pause,
     * this stops playback and tracks position for manual resume.
     */
    fun togglePause() {
        if (isPaused) {
            // Resume: replay remaining texts from where we paused
            isPaused = false
            if (pausedAtIndex < pendingTexts.size) {
                val remaining = pendingTexts.subList(pausedAtIndex, pendingTexts.size)
                remaining.forEachIndexed { index, text ->
                    autoDetectAndSetLanguage(text)
                    val params = buildSpeechParams()
                    val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    tts?.speak(text, queueMode, params, generateUtteranceId())
                }
                _isSpeaking.value = true
            }
        } else {
            // Pause: stop and mark position
            isPaused = true
            tts?.stop()
            _isSpeaking.value = false
        }
    }

    /**
     * Sets the TTS language explicitly.
     */
    fun setLanguage(locale: Locale) {
        if (!isInitialized) return
        // [T-android-tts-locale GH#324] Remember it, or the very next speak()
        // would re-run detection and throw this away.
        explicitLocale = locale
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Language not supported: ${locale.toLanguageTag()}")
        } else {
            Log.d(TAG, "Language set to: ${locale.toLanguageTag()}")
        }
    }

    /**
     * Cleans up the TTS engine. Must be called when no longer needed.
     */
    fun shutdown() {
        stop()
        tts?.shutdown()
        explicitLocale = null
        appContext = null
        tts = null
        isInitialized = false
        Log.d(TAG, "TTS shut down")
    }

    /**
     * Detects if text contains Han characters and sets language accordingly.
     */
    /**
     * [T-android-system-voice-catalog] Engine voice name to speak with; null =
     * auto (per-utterance language detection below). Set per utterance by
     * ReadAloudPlayer from the capsule's picked system voice. When the named
     * voice exists it wins over language auto-detection — same as iOS, where a
     * picked voice is used regardless of the reply's language.
     */
    @Volatile
    var preferredVoiceName: String? = null

    private fun autoDetectAndSetLanguage(text: String) {
        preferredVoiceName?.let { wanted ->
            val v = runCatching { tts?.voices?.firstOrNull { it.name == wanted } }.getOrNull()
            if (v != null) {
                val rc = tts?.setVoice(v)
                if (rc == TextToSpeech.SUCCESS) return
                Log.w(TAG, "setVoice($wanted) rejected (rc=$rc) — falling back to auto language")
            } else {
                Log.w(TAG, "preferred voice '$wanted' not found on this engine — falling back to auto language")
            }
        }
        // [T-android-tts-locale GH#324] An explicit caller choice outranks
        // detection — see [explicitLocale] for why this guard has to exist.
        explicitLocale?.let { applyResolvedLocale(it); return }

        applyResolvedLocale(TtsLocaleResolver.resolve(text, savedAppLanguageTag()))
    }

    /**
     * [T-android-tts-locale GH#324] Hand [wanted] to the engine, degrading to
     * the bare language when the region-qualified form has no voice data.
     *
     * The degradation is the point: a device may ship `pt` but not `pt-BR`, and
     * `setLanguage` with an unsupported locale is a no-op that leaves whatever
     * was set last in place. Before this change that leftover was English, so
     * an unsupported locale produced exactly the reported bug rather than an
     * approximation. When nothing matches we log and leave the engine alone —
     * a wrong-but-present voice beats silence, and the log says which language
     * the device is missing.
     */
    private fun applyResolvedLocale(wanted: Locale) {
        val engine = tts ?: return
        for (candidate in TtsLocaleResolver.candidates(wanted)) {
            val availability = runCatching { engine.isLanguageAvailable(candidate) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (availability == TextToSpeech.LANG_MISSING_DATA ||
                availability == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                continue
            }
            val rc = runCatching { engine.setLanguage(candidate) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (rc != TextToSpeech.LANG_MISSING_DATA && rc != TextToSpeech.LANG_NOT_SUPPORTED) {
                return
            }
        }
        Log.w(
            TAG,
            "no voice data for ${wanted.toLanguageTag()} — keeping the engine's " +
                "current language (install the language's TTS data to fix)",
        )
    }

    private fun buildSpeechParams(): android.os.Bundle {
        return android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, speechVolume)
            if (audioSessionId > 0) putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, audioSessionId)
        }
    }

    private fun generateUtteranceId(): String = UUID.randomUUID().toString()

    private fun createProgressListener(): UtteranceProgressListener =
        object : UtteranceProgressListener() {

            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                // [T-android-tts-init-thread] Progress callbacks arrive on a
                // binder thread. This used to bump pausedAtIndex and read
                // pendingTexts.size straight from there, while togglePause()
                // takes a subList VIEW of that same list on main — a
                // concurrent stop() clearing it makes the view throw. Confine
                // both to the main thread so pendingTexts/pausedAtIndex have a
                // single owner.
                Handler(Looper.getMainLooper()).post {
                    pausedAtIndex++
                    // Only mark as not speaking if nothing else is queued
                    if (pausedAtIndex >= pendingTexts.size) {
                        _isSpeaking.value = false
                    }
                }
            }

            @Deprecated("Deprecated in API level 21")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error for utterance: $utteranceId")
                _isSpeaking.value = false
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS error for utterance $utteranceId, code: $errorCode")
                _isSpeaking.value = false
            }
        }
}

// ─── Merged from VoiceActivityDetector.kt ──────────────────────────────────────────────────

/** Why a speech segment ended. Mirrors iOS `SegmentEndReason`. */
enum class SegmentEndReason {
    /** VAD saw the configured silence window — the mic should STOP. */
    SILENCE_DETECTED,

    /** Segment hit the length cap — flush it but KEEP recording. */
    MAX_LENGTH_REACHED,

    /**
     * User tapped stop mid-utterance.
     *
     * NOT CURRENTLY EMITTED on Android, and the difference from iOS is
     * deliberate rather than an omission. iOS calls `vad.flush()` to cut a
     * segment on demand and then holds a sub-2 s result in `pendingSegments`
     * to merge with the next utterance (VoiceInputPanel.swift:679-689). The
     * Android library owns segmentation and exposes no flush entry point, so
     * there is nothing to hold: a manual stop tears the detector down and the
     * platform recogniser (System engine) or the user's own tap (Provider
     * engine) finalises whatever it already had. Kept in the enum so the
     * distinction stays visible if the library ever gains a flush API.
     */
    MANUAL_FLUSH,
}

/** Delegate for [VoiceActivityDetector]. All callbacks arrive off the main thread. */
interface VoiceActivityListener {
    fun onVoiceStart() {}

    /**
     * A segment closed. [wav] is a complete 16 kHz WAV (header included).
     *
     * [spokenSeconds] is the ACCUMULATED SPEECH in the segment, derived from
     * the payload, not wall-clock. Callers gate the minimum-length rule on it:
     * wall-clock between speech-start and the close necessarily includes the
     * ~5 s silence window that triggered the close, so it can never fall below
     * a 2 s threshold and the rule would silently never fire.
     */
    fun onVoiceEnd(wav: ByteArray, reason: SegmentEndReason, spokenSeconds: Float)

    /** Live level in [0,1] for the waveform, from the AGC-boosted tap. */
    fun onLevel(level: Float) {}

    /** Capture died and could not be recovered; UI should return to idle. */
    fun onCaptureError(message: String) {}

    /**
     * [T-voice-mic-preempted] Capture failed or was silenced because another
     * app holds the microphone (see [MicInUse]). Capture has stopped. Defaults
     * to [onCaptureError] for listeners that do not tell the two apart.
     */
    fun onMicInUse(detail: String) { onCaptureError(detail) }

    /**
     * A session guard fired and capture has stopped. Distinct from
     * [onCaptureError]: nothing went wrong, the session simply ran out of its
     * allowance, so the UI should settle rather than show a failure.
     */
    fun onSessionLimit(limit: SessionLimit) {}
}

/** Why a capture session ended on its own. Mirrors iOS's panel timers. */
enum class SessionLimit {
    /** No speech for 30 s — a forgotten mic (iOS `idleTimeout`). */
    IDLE,

    /** Backgrounded 15 s mid-capture (iOS `backgroundTimeout`). */
    BACKGROUNDED,

    /** 300 s of continuous capture (iOS `maxTotalRecordingSeconds`). */
    MAX_DURATION,
}

/**
 * [T-android-vad] Silero-VAD speech segmentation for Android.
 *
 * ## Why this exists
 *
 * Android previously had no VAD at all. The System engine relied on the
 * platform recognizer's own endpointing — which is OEM-specific, so the same
 * app segmented differently on Pixel vs vivo vs Xiaomi — and the Provider
 * (custom-model) engine had *no* endpointing whatsoever: it recorded until the
 * user tapped stop or hit a 60 s cap. This class gives both engines one
 * deterministic, device-independent notion of "the user stopped talking".
 *
 * ## Same model as iOS, on purpose
 *
 * `RealTimeCutVADLibraryForAndroid` is the Android build of the very library
 * iOS uses through SPM (`RealTimeCutVADLibrary`) — same author, same Silero v5
 * + ONNX Runtime + WebRTC APM stack, same six-parameter threshold call. So the
 * tunables below are copied verbatim from
 * `src/ios/Providers/Voice/VoiceActivityDetector.swift` and the two platforms
 * segment identically.
 *
 * ## Do not use the library's default thresholds
 *
 * The library's README suggests `(0.7, 0.7, 0.5, 0.95, 10, 57)`. Two of those
 * are wrong for us:
 *  - end frames `57` ≈ 1.8 s, where iOS waits ~5 s ([END_FRAMES]); and
 *  - start/end probability `0.7`, which iOS tried and reverted — its comment
 *    records that at 0.7 the VAD "logged speechDetected=false across hundreds
 *    of frames while the user was actually speaking, so no segment was ever
 *    emitted."
 * [applyThresholds] therefore always calls `setVADThreshold` explicitly.
 */
class VoiceActivityDetector(
    private val context: Context,
    private val listener: VoiceActivityListener,
) {

    companion object {
        private const val TAG = "VAD"

        /** [T-voice-mic-preempted] First silenced-capture check, then the period. */
        private const val SILENCE_CHECK_FIRST_MS = 400L
        private const val SILENCE_CHECK_INTERVAL_MS = 1_000L

        /** Capture rate. 48 kHz matches iOS and the APM resamples to 16 kHz for Silero. */
        const val SAMPLE_RATE = 48_000

        // ── Silero thresholds (iOS VoiceActivityDetector.swift:128-146) ──
        private const val START_PROBABILITY = 0.5f
        private const val END_PROBABILITY = 0.5f
        private const val START_TRUE_RATIO = 0.5f

        /** Deliberately conservative so we don't clip the tail of a word. */
        private const val END_FALSE_RATIO = 0.95f

        /** 10 frames ≈ 0.32 s to confirm speech onset. */
        private const val START_FRAMES = 10

        /**
         * 156 frames ≈ 5.0 s of trailing silence before a segment closes.
         * The single most user-visible knob: on silence the mic STOPS (see
         * [SegmentEndReason.SILENCE_DETECTED]), matching iOS.
         */
        private const val END_FRAMES = 156

        // ── Noise-aware AGC (iOS :62-71, :617-655) ──
        private const val GATE_RATIO = 2.5f
        private const val AGC_TARGET_RMS = 0.12f
        private const val MAX_GAIN = 6.0f
        private const val GAIN_SMOOTHING = 0.3f
        private const val INITIAL_NOISE_FLOOR = 0.003f
        private const val NOISE_FLOOR_MIN = 0.0005f
        private const val NOISE_FLOOR_MAX = 0.02f
        private const val NOISE_ADAPT_DOWN = 0.05f
        private const val NOISE_ADAPT_UP = 0.005f

        /** VAD frame size. 512 @ 48 kHz ≈ 10 ms, matching the library's frame. */
        private const val FRAME_SAMPLES = 512

        /** Level mapping for the waveform (iOS computeWaveformLevels :1158-1180). */
        private const val LEVEL_GAIN = 14f
        private const val LEVEL_SCALE = 0.95f
        private const val LEVEL_FLOOR = 0.04f

        /** The library always emits 16 kHz mono PCM16 WAV. */
        // internal, not private: WavSegmentMerger needs the same two constants
        // to concatenate segments, and a second copy would be free to drift.
        internal const val WAV_SAMPLE_RATE = 16_000
        internal const val WAV_HEADER_BYTES = 44

        /**
         * Pre-roll replayed into the VAD when capture starts, matching iOS
         * `startBackfillSeconds` (VoiceActivityDetector.swift:97).
         *
         * Speech-start confirmation is inherently late — [START_FRAMES] alone
         * is 0.32 s, and longer for quiet speech whose per-frame probability
         * hovers at the threshold. Without a pre-roll the segment begins
         * mid-syllable; iOS's comment records device forensics showing "every
         * capture starting mid-syllable with 0 ms of leading silence" back
         * when this was a fixed 0.3 s.
         */
        private const val START_BACKFILL_SECONDS = 1.5f

        /**
         * [T-android-vad-manual-stop] How much pre-confirmation audio flush()
         * prepends. Silero's own start window is a few hundred ms; 1.0 s
         * covers it with margin while staying far short of the 2 s minimum
         * segment, so a flush can never be mostly pre-roll.
         */
        private const val PREROLL_SECONDS = 1.0f

        /** [T-android-vad-manual-stop] Post-endpoint hold; see tailFramesRemaining. */
        private const val TAIL_SECONDS = 1.0f
        private val TAIL_FRAMES =
            VadCaptureWindow.tailFrames(SAMPLE_RATE, FRAME_SAMPLES, TAIL_SECONDS)

        // ── Session-level timers (iOS VoiceInputPanel.swift:180, 227, 229) ──
        /** No speech at all for this long → stop, so a forgotten mic can't run on. */
        const val IDLE_TIMEOUT_MS = 30_000L

        /** Backgrounded this long mid-capture → stop. */
        const val BACKGROUND_TIMEOUT_MS = 15_000L

        /** Hard ceiling on one continuous capture session. */
        const val MAX_TOTAL_RECORDING_MS = 300_000L
    }

    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    @Volatile
    var isSpeaking: Boolean = false
        private set

    val isRunning: Boolean get() = running.get()

    /**
     * Segment length cap in seconds. Apple's ASR rejects >60 s, so iOS uses 59
     * for system recognition and the full session cap for cloud providers; the
     * caller sets the same way.
     */
    @Volatile
    var maxSegmentSeconds: Int = 59

    /**
     * Owns [VADWrapper]'s native side. Every `processAudio` runs through
     * [ReentrantSafeRelease.call] and every release through
     * [ReentrantSafeRelease.tearDown], so the native object is never freed
     * while a call into it is on the stack — neither by stop() on another
     * thread, nor by a listener that stops us from inside the library's own
     * onVoiceEnd callback. See the call site in [captureLoop].
     */
    private val vad = ReentrantSafeRelease<VADWrapper> { it.release() }

    /**
     * [T-android-vad] Optional tap on the RAW captured PCM16, before AGC.
     *
     * This exists so ONE AudioRecord can serve both the VAD and a consumer
     * that needs the same audio — specifically `SpeechRecognizer` fed through
     * `RecognizerIntent.EXTRA_AUDIO_SOURCE`. Opening a second AudioRecord
     * instead does not work: Android hands the real stream to one client and
     * the other is starved, which on a Pixel 6 left the recogniser reporting
     * `onStartOfSpeech` and then `withSpeech: false` — it had been given
     * nothing to transcribe.
     *
     * Deliberately the pre-AGC bytes: the AGC exists to make the VAD's job
     * easier on quiet input, and its soft-clipping is not something a
     * recogniser's own front-end should have to undo.
     *
     * Called on the capture thread; must not block.
     */
    @Volatile
    var rawAudioSink: ((ByteArray, Int) -> Unit)? = null

    private var recorder: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // AGC state, carried frame to frame.
    private var smoothedGain = 1.0f
    private var noiseFloorRms = INITIAL_NOISE_FLOOR

    /** Samples fed to the VAD since the current segment opened; drives the length cap. */
    private var segmentSamples = 0L

    /**
     * Rolling pre-roll of AGC-processed frames, [START_BACKFILL_SECONDS] deep.
     *
     * The library owns segmentation and gives us no way to inject audio behind
     * a segment boundary, so — unlike iOS, which splices the back-fill into its
     * own capture buffer — we keep the VAD permanently primed: every frame goes
     * in, so by the time the model confirms speech it has already consumed the
     * run-up. This ring exists to bound what we replay after a stop/start cycle
     * so a new capture doesn't open on a cold model.
     */
    private val backfill = ArrayDeque<FloatArray>()
    private var backfillSamples = 0
    private val backfillCapacity = (SAMPLE_RATE * START_BACKFILL_SECONDS).toInt()

    /**
     * [T-android-vad-manual-stop] PCM of the segment currently OPEN, i.e. the
     * speech the library has confirmed but not yet closed.
     *
     * The library owns segmentation and exposes no flush entry point
     * (VADWrapper: setVADModel / setVADSampleRate / setVADThreshold /
     * processAudio / release / setVADCallback — that is the whole API), so a
     * user who taps stop mid-sentence would otherwise lose the sentence: it
     * lives inside the library, `onVoiceEnd` never fires, and tearing the
     * wrapper down discards it. `onVoiceDidContinue` streams exactly that
     * audio frame by frame, already at [WAV_SAMPLE_RATE] PCM16, so mirroring it
     * here costs one copy per frame and makes [flush] possible.
     *
     * Fed from [captureLoop] with the PRE-AGC microphone bytes — the same
     * source [rawAudioSink] exposes, and for the same reason its own comment
     * gives: the AGC's `tanh` soft-clip exists to help the VAD on quiet input
     * and is not something a recogniser's front-end should have to undo. A
     * first attempt mirrored the library's `onVoiceDidContinue` instead; that
     * stream is post-AGC and a distant voice came back gain-pinned to ~80 % FS
     * with a flat envelope (mean |Δ| 10232 against a 27528 peak — near-square,
     * not speech), which the ASR duly transcribed as unrelated words.
     *
     * Held at the CAPTURE rate ([SAMPLE_RATE], 48 kHz) and downsampled once in
     * [flush], since the library's own segments are 16 kHz and the two kinds
     * get concatenated by WavSegmentMerger.
     *
     * Guarded by [openLock]: written from the capture thread, read by whichever
     * thread calls [flush].
     */
    private val openSegment = java.io.ByteArrayOutputStream()
    private val openLock = Any()

    /**
     * [T-android-vad-manual-stop] Pre-AGC pre-roll, [PREROLL_SECONDS] deep.
     *
     * Silero only reports speech once it is CONFIRMED, so the first syllables
     * — the run-up it needed to make that decision — are already spoken by the
     * time `onVoiceStart` fires. Recording [openSegment] from that moment
     * therefore loses the head of the utterance: "看看附近的天气情况" reached
     * the recogniser without its first two characters, which is the reported
     * symptom. The existing [backfill] ring solves the same problem for the
     * VAD itself but holds AGC-processed FloatArrays; this keeps the raw PCM16
     * bytes the recogniser should get.
     *
     * Bounded to [prerollCapacityBytes]; oldest frames fall off the front.
     */
    private val preroll = ArrayDeque<ByteArray>()
    private var prerollBytes = 0
    private val prerollCapacityBytes =
        VadCaptureWindow.prerollCapacityBytes(SAMPLE_RATE, PREROLL_SECONDS)

    /**
     * [T-android-vad-manual-stop] Frames still to be appended AFTER the VAD
     * called the utterance finished.
     *
     * The endpoint fires as soon as the silence window is satisfied, which cuts
     * the decay of the final syllable — the mirror image of the onset problem
     * the pre-roll solves. Counted in frames so the capture loop can decrement
     * without consulting a clock.
     */
    private var tailFramesRemaining = 0

    /** Wall clock of capture start and of the last confirmed speech, for the timers. */
    @Volatile private var captureStartedAtMs = 0L
    @Volatile private var lastSpeechAtMs = 0L

    /** Set while the app is backgrounded, so the shorter timeout applies. */
    @Volatile
    var isBackgrounded: Boolean = false
        set(value) {
            field = value
            backgroundedAtMs = if (value) System.currentTimeMillis() else 0L
        }
    @Volatile private var backgroundedAtMs = 0L

    /**
     * Start capture. Returns an error string on failure, null on success.
     * Caller must already hold RECORD_AUDIO.
     */
    @Suppress("MissingPermission")
    fun start(): String? {
        if (running.getAndSet(true)) return null
        cancelled.set(false)
        smoothedGain = 1.0f
        noiseFloorRms = INITIAL_NOISE_FLOOR
        segmentSamples = 0
        isSpeaking = false

        val wrapper = try {
            VADWrapper(context).also {
                it.setVADModel(VADWrapper.SileroModelVersion.V5)
                it.setVADSampleRate(VADWrapper.SampleRate.SAMPLERATE_48)
                applyThresholds(it)
                it.setVADCallback(vadCallback)
            }
        } catch (t: Throwable) {
            // Missing native lib / unsupported ABI must degrade, not crash.
            running.set(false)
            Log.e(TAG, "VAD init failed", t)
            return "Voice detection unavailable: ${t.javaClass.simpleName}"
        }
        vad.attach(wrapper)

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            stopInternal()
            return "AudioRecord unsupported buffer size."
        }

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf * 4, FRAME_SAMPLES * 2 * 8),
            )
        } catch (t: Throwable) {
            stopInternal()
            return "AudioRecord init failed: ${t.message}"
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            stopInternal()
            return "AudioRecord not initialized."
        }
        recorder = rec

        captureJob = scope.launch { captureLoop(rec) }
        return null
    }

    private fun applyThresholds(w: VADWrapper) {
        w.setVADThreshold(
            START_PROBABILITY,
            END_PROBABILITY,
            START_TRUE_RATIO,
            END_FALSE_RATIO,
            START_FRAMES,
            END_FRAMES,
        )
    }

    private fun captureLoop(rec: AudioRecord) {
        val shorts = ShortArray(FRAME_SAMPLES)
        val floats = FloatArray(FRAME_SAMPLES)
        // Little-endian PCM16 staging for rawAudioSink; reused per frame.
        val sinkBytes = ByteArray(FRAME_SAMPLES * 2)
        try {
            rec.startRecording()
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording failed", t)
            val detail = "Microphone unavailable: ${t.message}"
            if (MicInUse.captureFailure(context, rec.audioSessionId) == RecognitionError.MIC_IN_USE) {
                listener.onMicInUse(detail)
            } else {
                listener.onCaptureError(detail)
            }
            stopInternal()
            return
        }
        // [T-voice-mic-preempted] While a call or another recorder owns the
        // input, Android 10+ starts this recording anyway and feeds it silence.
        // Checked shortly after start (the config is listed asynchronously) and
        // then about once a second, so a call arriving mid-take is caught too.
        var nextSilenceCheckAtMs = System.currentTimeMillis() + SILENCE_CHECK_FIRST_MS

        val maxSamples = SAMPLE_RATE.toLong() * maxSegmentSeconds
        captureStartedAtMs = System.currentTimeMillis()
        lastSpeechAtMs = captureStartedAtMs
        while (running.get() && !cancelled.get()) {
            // Session guards, checked before each read so they fire even while
            // the user is silent. iOS keeps these as Timers on the panel; a
            // poll on the capture thread is equivalent and cannot leak.
            val now = System.currentTimeMillis()
            if (now - captureStartedAtMs >= MAX_TOTAL_RECORDING_MS) {
                Log.i(TAG, "session hit ${MAX_TOTAL_RECORDING_MS / 1000}s cap — stopping")
                listener.onSessionLimit(SessionLimit.MAX_DURATION)
                break
            }
            if (!isSpeaking && now - lastSpeechAtMs >= IDLE_TIMEOUT_MS) {
                Log.i(TAG, "no speech for ${IDLE_TIMEOUT_MS / 1000}s — stopping")
                listener.onSessionLimit(SessionLimit.IDLE)
                break
            }
            if (isBackgrounded && backgroundedAtMs > 0 &&
                now - backgroundedAtMs >= BACKGROUND_TIMEOUT_MS
            ) {
                Log.i(TAG, "backgrounded ${BACKGROUND_TIMEOUT_MS / 1000}s — stopping")
                listener.onSessionLimit(SessionLimit.BACKGROUNDED)
                break
            }

            if (now >= nextSilenceCheckAtMs) {
                nextSilenceCheckAtMs = now + SILENCE_CHECK_INTERVAL_MS
                if (MicInUse.isSilenced(context, rec.audioSessionId)) {
                    Log.w(TAG, "recording silenced by the system — another app holds the microphone")
                    listener.onMicInUse("Recording silenced by the system (another app holds the microphone)")
                    break
                }
            }

            val n = try {
                rec.read(shorts, 0, FRAME_SAMPLES)
            } catch (t: Throwable) {
                Log.e(TAG, "read failed", t)
                listener.onCaptureError("Microphone read failed")
                break
            }
            if (n <= 0) continue

            // Hand the RAW frame to any consumer sharing this capture (the
            // recogniser pipe) before AGC touches the samples.
            // [T-android-vad-manual-stop] Stage the PRE-AGC bytes once and use
            // them for both consumers: the optional rawAudioSink (System
            // engine) and the open-segment mirror that backs flush().
            // Stage unconditionally: the pre-roll ring needs frames exactly
            // when `isSpeaking` is FALSE (that is the audio Silero is still
            // deciding on), and gating on it left the ring permanently empty —
            // the onset was still lost after the ring was added.
            var staged = 0
            for (i in 0 until n) {
                val v = shorts[i].toInt()
                sinkBytes[staged++] = (v and 0xFF).toByte()
                sinkBytes[staged++] = ((v shr 8) and 0xFF).toByte()
            }
            rawAudioSink?.let { sink -> runCatching { sink(sinkBytes, staged) } }
            if (staged > 0) {
                synchronized(openLock) {
                    if (isSpeaking || tailFramesRemaining > 0) {
                        // Segment OPEN — or within the tail hold just after it
                        // closed, so a trailing syllable clipped by the VAD's
                        // endpoint still reaches the recogniser.
                        openSegment.write(sinkBytes, 0, staged)
                        if (!isSpeaking) tailFramesRemaining--
                    } else {
                        // Not yet confirmed — hold it as pre-roll so the onset
                        // survives if speech starts on a later frame.
                        preroll.addLast(sinkBytes.copyOf(staged))
                        prerollBytes += staged
                        while (prerollBytes > prerollCapacityBytes && preroll.isNotEmpty()) {
                            prerollBytes -= preroll.removeFirst().size
                        }
                    }
                }
            }

            // PCM16 -> float [-1,1], then noise-aware AGC.
            var sumSq = 0.0f
            for (i in 0 until n) {
                val f = shorts[i] / 32768.0f
                floats[i] = f
                sumSq += f * f
            }
            val rms = if (n > 0) sqrt(sumSq / n) else 0f
            applyAgc(floats, n, rms)

            // Waveform is driven from the boosted TAP, not the library's
            // onVoiceDidContinue: that only fires DURING a detected segment, so
            // relying on it leaves the bars flat until VAD trips (iOS :657-664).
            listener.onLevel(levelFor(floats, n))

            val frame = if (n == FRAME_SAMPLES) floats else floats.copyOf(n)

            // Keep a bounded pre-roll of processed frames. The VAD is fed every
            // frame from the moment the mic opens, so the run-up before
            // speech-start confirmation is already inside the model; this ring
            // caps how much we would replay on a restart and keeps the memory
            // constant rather than growing with session length.
            backfill.addLast(frame)
            backfillSamples += n
            while (backfillSamples > backfillCapacity && backfill.isNotEmpty()) {
                backfillSamples -= backfill.removeFirst().size
            }

            // [T-android-vad] processAudio and release() MUST be mutually
            // exclusive.
            //
            // `vad?.processAudio()` alone is not safe: the null check and the
            // call are separate steps, and stop() runs on another thread. A
            // release() landing in between frees the native RealTimeCutVAD
            // while the JNI call is already inside it, and the next frame
            // dereferences freed memory — SIGSEGV in
            // RealTimeCutVAD::process(std::vector<float>&), which is exactly
            // what a Pixel 6 produced when the recognizer reported error 7 and
            // tore the session down 12 ms before the fault.
            //
            // Holding the lock across the whole call makes release() wait for
            // the in-flight frame instead of pulling the object out from under
            // it. Frames are ~10 ms of audio and inference is well under that,
            // so the wait is bounded and stop() stays responsive.
            //
            // [T-android-vad-reentrant-release] The lock alone did not cover
            // the SAME thread: onVoiceEnd fires inside processAudio, and a
            // listener that stops the take there reached release() through
            // the re-entrant monitor — freeing RealTimeCutVAD while
            // algorithm() was still on the stack (SIGSEGV, fault addr 0x38).
            // ReentrantSafeRelease defers that free until processAudio has
            // returned, and reports the take as torn down.
            var failure: Throwable? = null
            val alive = vad.call { w ->
                try { w.processAudio(frame) } catch (t: Throwable) { failure = t }
            }
            if (!alive) break
            val err = failure
            if (err != null) {
                Log.e(TAG, "processAudio failed", err)
                listener.onCaptureError("Voice detection failed")
                break
            }
            if (isSpeaking) lastSpeechAtMs = System.currentTimeMillis()

            // Length cap. The library owns segment cutting and exposes no
            // "flush now" call, so we cannot split a segment mid-utterance the
            // way iOS does. What we CAN do is refuse to let a runaway segment
            // grow past what the provider will accept: mark the reason so the
            // eventual onVoiceEnd is attributed as a cap hit rather than a
            // silence stop (the two mean different things to the caller — a
            // silence stop turns the mic off, a cap hit must not), and warn
            // once so an over-long take is visible in a field log.
            if (isSpeaking) {
                segmentSamples += n
                if (segmentSamples >= maxSamples && pendingReason == null) {
                    Log.w(TAG, "segment exceeded ${maxSegmentSeconds}s — will report MAX_LENGTH_REACHED")
                    pendingReason = SegmentEndReason.MAX_LENGTH_REACHED
                }
            }
        }

        try { rec.stop() } catch (_: Throwable) {}
        rec.release()
    }

    /** iOS's noise-aware AGC, ported verbatim (`VoiceActivityDetector.swift:617-655`). */
    private fun applyAgc(buf: FloatArray, n: Int, rms: Float) {
        val gate = noiseFloorRms * GATE_RATIO
        val targetGain: Float
        if (rms < gate) {
            targetGain = 1.0f
            // Adapt ASYMMETRICALLY and never mid-segment. A symmetric rate let
            // quiet speech drag the floor up, raising the gate, so each further
            // frame was even less likely to be amplified — a runaway that
            // starved the VAD of exactly the low-volume speech users report as
            // "it doesn't hear me".
            if (!isSpeaking) {
                val rate = if (rms < noiseFloorRms) NOISE_ADAPT_DOWN else NOISE_ADAPT_UP
                noiseFloorRms += (rms - noiseFloorRms) * rate
                noiseFloorRms = max(NOISE_FLOOR_MIN, min(NOISE_FLOOR_MAX, noiseFloorRms))
            }
        } else {
            targetGain = min(MAX_GAIN, max(1.0f, AGC_TARGET_RMS / max(rms, 0.0001f)))
        }
        smoothedGain += (targetGain - smoothedGain) * GAIN_SMOOTHING
        if (smoothedGain > 1.01f) {
            for (i in 0 until n) buf[i] = tanh(buf[i] * smoothedGain) // soft-clip
        }
    }

    /** Perceptual level for the waveform (iOS `computeWaveformLevels`). */
    private fun levelFor(buf: FloatArray, n: Int): Float {
        var sumSq = 0.0f
        for (i in 0 until n) sumSq += buf[i] * buf[i]
        val rms = if (n > 0) sqrt(sumSq / n) else 0f
        val level = sqrt(min(1.0f, rms * LEVEL_GAIN)) * LEVEL_SCALE
        return min(1.0f, max(LEVEL_FLOOR, level))
    }

    /** Set when the length cap trips so the next onVoiceEnd is attributed correctly. */
    @Volatile
    private var pendingReason: SegmentEndReason? = null

    private val vadCallback = object : VADCallback {
        override fun onVoiceStart() {
            segmentSamples = 0
            synchronized(openLock) {
                openSegment.reset()
                // Seed with the pre-roll: Silero confirms speech only after the
                // onset has been spoken, so without this the utterance reaches
                // the recogniser with its first syllables missing.
                for (chunk in preroll) openSegment.write(chunk, 0, chunk.size)
                preroll.clear()
                prerollBytes = 0
                tailFramesRemaining = 0
                // Set INSIDE the lock so the capture thread cannot append a
                // frame to openSegment between the reset and the seed.
                isSpeaking = true
            }
            listener.onVoiceStart()
        }

        override fun onVoiceEnd(wav: ByteArray?) {
            isSpeaking = false
            segmentSamples = 0
            // The library closed this segment and is handing over its own
            // finished WAV, so the mirror's copy is redundant for THIS segment
            // — but keep capturing briefly: `flush()` is not involved here, and
            // arming the tail means a manual stop moments later still carries
            // the decay rather than starting from nothing.
            synchronized(openLock) {
                openSegment.reset()
                preroll.clear()
                prerollBytes = 0
                tailFramesRemaining = TAIL_FRAMES
            }
            lastSpeechAtMs = System.currentTimeMillis()
            val reason = pendingReason ?: SegmentEndReason.SILENCE_DETECTED
            pendingReason = null
            if (wav == null || wav.isEmpty()) return
            // Duration from the payload, NOT wall-clock: the interval between
            // speech-start and this callback necessarily contains the ~5 s
            // silence that closed the segment, so a wall-clock figure can never
            // drop below a 2 s minimum and the caller's rule would never fire.
            val spoken = max(0, wav.size - WAV_HEADER_BYTES) /
                (WAV_SAMPLE_RATE * 2).toFloat()
            listener.onVoiceEnd(wav, reason, spoken)
        }

        override fun onVoiceDidContinue(pcm: ByteArray?) {
            // Unused: the waveform is driven from the raw tap so it stays live
            // before VAD confirms speech. See captureLoop.
        }
    }

    /**
     * [T-android-vad-manual-stop] Cut the segment that is open right now and
     * return it as a WAV, or null when no speech is in flight.
     *
     * This is the flush the library does not provide, reconstructed from the
     * PCM mirrored in [onVoiceDidContinue]. It is what a manual stop needs:
     * the user tapped stop mid-sentence, so the library will never emit this
     * audio on its own and destroying the wrapper would discard it.
     *
     * Does NOT stop capture — the caller decides what happens next — but it
     * does consume the buffer, so a following [stop] cannot hand the same
     * audio out twice.
     */
    fun flush(): ByteArray? {
        val pcm = synchronized(openLock) {
            val bytes = openSegment.toByteArray()
            openSegment.reset()
            bytes
        }
        if (pcm.isEmpty()) return null
        isSpeaking = false
        segmentSamples = 0
        // 48 kHz in, 16 kHz out — matching the library's own segments so the
        // merger can concatenate the two kinds and the ASR hears real time.
        return com.openminis.app.provider.voice.VoiceProvider.wrapPcm16InWav(
            downsampleTo16k(pcm),
            WAV_SAMPLE_RATE,
        )
    }

    /**
     * [T-android-vad-manual-stop] 48 kHz → 16 kHz PCM16, mono.
     *
     * Plain 3:1 decimation with a 3-sample box average. The capture path
     * already low-passes nothing, so averaging the samples that are dropped is
     * what keeps aliasing out of the band the recogniser listens to; picking
     * every third sample instead measurably degraded recognition of sibilants
     * in local testing. The ratio is fixed because both rates are compile-time
     * constants — [SAMPLE_RATE] is what we open AudioRecord with and
     * [WAV_SAMPLE_RATE] is what the library emits.
     */
    private fun downsampleTo16k(pcm48: ByteArray): ByteArray {
        val ratio = SAMPLE_RATE / WAV_SAMPLE_RATE // 3
        val inSamples = pcm48.size / 2
        val outSamples = inSamples / ratio
        val out = ByteArray(outSamples * 2)
        var si = 0
        for (o in 0 until outSamples) {
            var acc = 0
            for (k in 0 until ratio) {
                val b = si * 2
                acc += ((pcm48[b + 1].toInt() shl 8) or (pcm48[b].toInt() and 0xFF)).toShort().toInt()
                si++
            }
            val v = (acc / ratio).coerceIn(-32768, 32767)
            out[o * 2] = (v and 0xFF).toByte()
            out[o * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Stop capture. Any segment already delivered stands; nothing new is emitted. */
    fun stop() {
        stopInternal()
    }

    /** Stop and suppress any in-flight segment delivery. */
    fun cancel() {
        cancelled.set(true)
        stopInternal()
    }

    private fun stopInternal() {
        running.set(false)
        isSpeaking = false
        synchronized(openLock) {
            openSegment.reset()
            preroll.clear()
            prerollBytes = 0
            tailFramesRemaining = 0
        }
        backfill.clear()
        backfillSamples = 0
        captureStartedAtMs = 0
        lastSpeechAtMs = 0
        backgroundedAtMs = 0
        captureJob?.let { runCatching { it.cancel() } }
        captureJob = null

        // [T-android-vad-recorder-release] Release the mic HERE rather than
        // trusting captureLoop's tail to do it.
        //
        // `captureJob = scope.launch { captureLoop(rec) }` dispatches
        // asynchronously, so a stop() arriving before the coroutine body has
        // started cancels it before its first line runs — and the
        // `rec.stop(); rec.release()` at the end of captureLoop, which was the
        // only release for the running case, then never executes. AudioRecord
        // holds a native input session, so losing it leaks the microphone for
        // the life of the process: the mic indicator stays lit and the NEXT
        // start() gets an AudioRecord that never reaches STATE_INITIALIZED,
        // i.e. voice input is dead until the app restarts.
        //
        // That window is widest in exactly the situation this teardown exists
        // for — a provider/relay failure tearing the session down milliseconds
        // after start(). Releasing from the owner closes it.
        //
        // Double release is safe and is the normal path: captureLoop's own
        // tail may still run and call stop()/release() again, and AudioRecord
        // tolerates release-after-release (the second is a no-op on an already
        // released object); both sides are wrapped so neither can throw into
        // its caller. stop() on a non-recording instance throws
        // IllegalStateException, which is why it is guarded rather than
        // conditioned on a state read that could race.
        recorder?.let { rec ->
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        recorder = null
        // Detach and free the native object. From another thread this waits for
        // any in-flight frame; from inside a VAD callback (same thread, already
        // in processAudio) the free is deferred until processAudio returns.
        vad.tearDown()
    }
}
