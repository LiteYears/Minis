package com.openminis.app.speech

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.openminis.app.MinisApp
import com.openminis.app.provider.voice.VoiceInputRequest
import com.openminis.app.provider.voice.VoiceProvider
import com.openminis.app.provider.voice.VoiceProviderException
import com.openminis.app.provider.voice.VoiceProviderFactory
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * [T-android-provider-voice] Provider-backed transcription engine — the
 * formerly-stubbed Phase 2. Captures PCM16 mono 16 kHz via [AudioRecord],
 * wraps the take in a WAV container on stop, and ships it to the VoiceProvider
 * resolved from the Voice Input group (VoiceProviderFactory + the instance's
 * stored API key). Mirrors the iOS provider ASR path (VoiceProvider.transcribe:
 * dedicated Whisper-style endpoint, vendor adapters, or chat-based ASR for
 * audio chat models).
 *
 * No partial results: cloud transcription is one-shot on stop. The System
 * engine remains the streaming/live option.
 */
class ProviderSpeechRecognitionEngine(private val appContext: Context) : SpeechRecognitionEngine {

    companion object {
        private const val TAG = "ProviderASR"

        /**
         * [T-android-voice-asr-stall-skip] How long an attempt may go without a
         * result before "Switch Model" is offered. iOS 4a1f44220: 5 s (the user
         * found 10 s too long). Only an offer: an attempt the user does not skip
         * keeps running and lands normally.
         */
        const val ASR_STALL_THRESHOLD_MS = 5_000L

        /** nextEntryId meaning "System recognition" (Android candidates never include System). */
        private const val SYSTEM_NEXT = "__system_asr__"
        private const val SAMPLE_RATE = 16_000
        /** Hard cap on a single take (60 s at 16 kHz PCM16 mono ≈ 1.9 MB). */
        private const val MAX_RECORD_SECONDS = 60

        // ── [T-android-vad] ──
        /**
         * Cloud ASR has no per-request length ceiling the way Apple's system
         * recogniser does, so a segment may run to the full session cap
         * (iOS VoiceInputPanel.swift:488-489).
         */
        private const val CLOUD_MAX_SEGMENT_SECONDS = 300

        /**
         * Segments shorter than this are dropped on a silence close, matching
         * iOS `minSegmentSeconds` (VoiceInputPanel.swift:607). A cough or a
         * door would otherwise cost a paid transcription request.
         */
        private const val MIN_SEGMENT_SECONDS = 2.0f

        /** Below this we stay silent; above it the user gets told why (iOS :661). */
        private const val TOO_SHORT_TOAST_FLOOR = 0.3f

        /**
         * [T-android-vad-merge-segments] How long to keep the mic open waiting
         * for the user to top up a sub-2s utterance. Matches the iOS
         * force-flush timer (VoiceInputPanel.swift:609).
         */
        private const val HOLD_FLUSH_MS = 5_000L
    }

    override val id: String = "provider"
    override val displayName: String = "Provider transcription"
    override val supportsPartialResults: Boolean = false

    /** Cheap check only: is a provider ASR selection resolvable right now? */
    override val isAvailable: Boolean
        get() = !degraded && repository()?.resolveVoiceInputEntry() != null

    /** Cloud ASR is language-agnostic (auto-detect); no fixed locale list. */
    override val supportedLocales: List<Locale> = emptyList()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var transcribeJob: Job? = null
    /** [T-android-vad-merge-segments] Sub-threshold segments held for merge. */
    private val pendingSegments = mutableListOf<ByteArray>()
    private var holdFlushJob: Job? = null
    private val recording = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private var degraded = false

    // [T-android-voice-system-fallback-cancel] One number per take. The System
    // recognition fallback (after a user skip exhausts the chain) is handed the
    // audio and runs on its own engine; nothing used to stop it. Tapping X, or
    // sending, cancelled only this engine, and seconds later the System result
    // still reached the listener: the old dictation reappeared in a cleared
    // composer, and if a new take had started, its onFinal forced the panel to
    // IDLE under a live mic (the next tap then failed RECOGNIZER_BUSY). iOS
    // awaits the System transcription inside its cancellable task and drops
    // stale results by transcriptGeneration; this is the same guard.
    private val takeGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    /** The take whose audio went to the System recognizer, or -1. */
    private val systemReplayGeneration = java.util.concurrent.atomic.AtomicInteger(-1)

    /**
     * [T-android-vad] Live Silero detector for the segmented path, null when
     * idle or when running the legacy record-until-stop loop.
     */
    @Volatile
    private var detector: VoiceActivityDetector? = null

    /**
     * [T-android-vad-manual-stop] Everything [stop] needs to finalise a take
     * the user ended by hand.
     *
     * On the VAD path audio reaches transcription ONLY through the detector's
     * `onVoiceEnd` callback, and that callback closes over the locale, repo,
     * candidate chain and listener for the session. `stop()` takes no
     * arguments, so without holding them here it could tear the detector down
     * but had no way to transcribe what had already been captured.
     */
    private class VadSession(
        val locale: Locale,
        val repo: com.openminis.app.data.repository.ProviderRepository,
        val candidates: List<Pair<com.openminis.app.data.model.ProviderInstance, com.openminis.app.data.model.ModelEntry>>,
        val listener: SpeechRecognitionEngine.Listener,
    )

    @Volatile
    private var vadSession: VadSession? = null

    /**
     * Whether to segment with Silero instead of recording until the user taps
     * stop. Defaults ON: this engine had no endpointing at all, so the VAD is
     * strictly better here. Kept as a flag so a device where the native VAD
     * fails to load can be dropped back to the legacy loop rather than losing
     * voice input entirely.
     */
    @Volatile
    var useVad: Boolean = true

    /**
     * [T-voice-asr-group-failover] STICKY fail-over (mirrors iOS
     * VoiceInputViewModel and the text agent loop's nextFallback): once a take
     * succeeds on a group member, later takes start there — only advance when
     * IT fails. Cleared implicitly when the entry leaves the candidate set
     * (the ordering below just falls back to chain order then).
     */
    @Volatile
    private var stickyEntryId: String? = null

    /**
     * [T-android-voice-asr-stall-skip] Every candidate attempt runs through
     * this, so one that stalls past [ASR_STALL_THRESHOLD_MS] can be skipped to
     * the next voice model (System recognition last). Port of iOS
     * VoiceInputViewModel.asrAttempts.
     */
    val asrAttempts = SkippableAsrAttempts(ASR_STALL_THRESHOLD_MS).also { a ->
        a.log = { msg -> Log.i(TAG, msg); VoicePipelineLog.event("engine.asr.stall", "msg" to msg.take(80)) }
    }

    /**
     * [T-android-voice-asr-stall-skip] Set when the user skipped past the last
     * candidate: the rest of this take goes straight to System recognition
     * instead of queueing on a server that just hung. Cleared by [start].
     */
    @Volatile
    private var systemAsrPinned = false

    /** Candidate chain of the running take, for naming a skip's target. */
    @Volatile
    private var currentCandidates: List<Pair<com.openminis.app.data.model.ProviderInstance, com.openminis.app.data.model.ModelEntry>> = emptyList()

    // [T-android-safemode-lateinit-crash-147] subsystemsReady() first — the
    // safe call rules out a null Application, not an unassigned lateinit,
    // whose getter throws. Speech recognition can be started from a
    // shortcut/assistant intent that never went through MainActivity's guard.
    // Every caller already treats null as "no provider configured".
    private fun repository() =
        (appContext.applicationContext as? MinisApp)
            ?.takeIf { it.subsystemsReady() }
            ?.providerRepository

    override fun markDegraded() {
        degraded = true
    }

    override fun clearDegraded() {
        degraded = false
    }

    @SuppressLint("MissingPermission") // caller ensures RECORD_AUDIO per interface contract
    override fun start(locale: Locale, listener: SpeechRecognitionEngine.Listener) {
        val repo = repository()
        // [T-voice-asr-group-failover] Resolve the whole ordered candidate
        // chain instead of one entry. loadBalance groups get a fresh rotation
        // seed per capture so takes spread across members; fallback groups
        // keep declaration order. Provider construction is deferred to the
        // transcription step, where a failing member advances to the next.
        val candidates = repo?.resolveVoiceInputCandidates(
            loadBalanceSeed = kotlin.random.Random.nextInt(Int.MAX_VALUE),
        ).orEmpty()
        systemAsrPinned = false
        currentCandidates = candidates
        if (repo == null || candidates.isEmpty()) {
            listener.onError(
                RecognitionError.OEM_NO_SERVICE,
                "No provider voice-input model configured. Add an ASR model to the Voice Input group.",
            )
            return
        }
        if (recording.getAndSet(true)) {
            listener.onError(RecognitionError.RECOGNIZER_BUSY, "A capture is already in flight.")
            return
        }
        supersedeTake()
        cancelled.set(false)
        // [T-android-vad-merge-segments] A fresh session must not inherit audio
        // held from the previous one.
        pendingSegments.clear()
        holdFlushJob?.cancel()
        holdFlushJob = null

        // [T-android-vad] VAD-segmented path. Before this, the provider engine
        // had NO endpointing at all: it recorded until the user tapped stop or
        // hit the 60 s cap, so a custom-model user had to manually bracket
        // every utterance. Silero now closes a segment after ~5 s of silence
        // and the mic stops, exactly as on iOS.
        if (useVad) {
            startVadCapture(locale, repo, candidates, listener)
            return
        }

        captureJob = scope.launch {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                recording.set(false)
                listener.onError(RecognitionError.AUDIO_ERROR, "AudioRecord unsupported buffer size.")
                return@launch
            }
            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 4,
                )
            } catch (e: Exception) {
                recording.set(false)
                listener.onError(MicInUse.captureFailure(appContext), "AudioRecord init failed: ${e.message}")
                return@launch
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                recording.set(false)
                listener.onError(MicInUse.captureFailure(appContext), "AudioRecord not initialized.")
                return@launch
            }

            val pcm = ByteArrayOutputStream()
            val buf = ByteArray(minBuf)
            val maxBytes = SAMPLE_RATE * 2 * MAX_RECORD_SECONDS
            try {
                recorder.startRecording()
                listener.onReadyForSpeech()
                // [T-voice-mic-preempted] Same silenced-capture check as the VAD.
                var nextSilenceCheckAtMs = System.currentTimeMillis() + 400L
                while (recording.get() && pcm.size() < maxBytes) {
                    val now = System.currentTimeMillis()
                    if (now >= nextSilenceCheckAtMs) {
                        nextSilenceCheckAtMs = now + 1_000L
                        if (MicInUse.isSilenced(appContext, recorder.audioSessionId)) {
                            recording.set(false)
                            listener.onError(RecognitionError.MIC_IN_USE, "Recording silenced by the system")
                            return@launch
                        }
                    }
                    val n = recorder.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    pcm.write(buf, 0, n)
                    listener.onRmsDb(rmsDb(buf, n))
                }
            } catch (e: Exception) {
                Log.e(TAG, "capture failed: ${e.message}", e)
                recording.set(false)
                listener.onError(MicInUse.captureFailure(appContext, recorder.audioSessionId), e.message)
                return@launch
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
            }

            if (cancelled.get()) {
                VoicePipelineLog.event("engine.capture.cancelled")
                return@launch
            }
            val audio = pcm.toByteArray()
            VoicePipelineLog.event("engine.capture.end", "bytes" to audio.size)
            if (audio.isEmpty()) {
                listener.onError(RecognitionError.NO_MATCH, "No audio captured.")
                return@launch
            }

            // One-shot cloud transcription of the whole take, with SEAMLESS
            // fail-over across the candidate chain (mirrors iOS
            // transcribeWithFailover / VoiceOutputPlayer): a candidate that
            // throws advances to the next; the first success becomes the
            // STICKY member for later takes; only when EVERY candidate fails
            // does the error surface (last error wins).
            transcribeJob = scope.launch {
                VoicePipelineLog.event("engine.transcribe.begin", "candidates" to candidates.size)
                val wav = VoiceProvider.wrapPcm16InWav(audio, SAMPLE_RATE)
                // [T-android-voice-asr-stall-skip] Same fail-over as a VAD
                // segment: each attempt is skippable once stalled.
                transcribeAndDeliver(wav, locale, repo, candidates, listener, logEvents = true)
            }
        }
    }

    // ── [T-android-vad] VAD-segmented capture ─────────────────────────────

    /**
     * Silence-segmented capture. Silero closes a segment after ~5 s of silence;
     * we then transcribe that segment and STOP the mic, matching iOS
     * (`VoiceInputPanel.swift:642-669`) — the user re-taps for the next
     * utterance rather than the mic staying hot.
     */
    private fun startVadCapture(
        locale: Locale,
        repo: com.openminis.app.data.repository.ProviderRepository,
        candidates: List<Pair<com.openminis.app.data.model.ProviderInstance, com.openminis.app.data.model.ModelEntry>>,
        listener: SpeechRecognitionEngine.Listener,
    ) {
        val det = VoiceActivityDetector(
            appContext,
            object : VoiceActivityListener {
                override fun onVoiceStart() {
                    Log.i(TAG, "[vad] speech start")
                }

                override fun onLevel(level: Float) {
                    // Map the detector's perceptual [0,1] back onto the [0,12]
                    // dB-ish scale SpeechRecognitionManager normalizes from, so
                    // both engines drive the waveform identically.
                    listener.onRmsDb(level * 12f)
                }

                override fun onVoiceEnd(wav: ByteArray, reason: SegmentEndReason, spokenSeconds: Float) {
                    if (cancelled.get()) return

                    // [T-android-vad-merge-segments] ACCUMULATE, don't discard.
                    //
                    // iOS holds each silence-closed segment in `pendingSegments`
                    // and tests the 2 s minimum against the RUNNING TOTAL
                    // (VoiceInputPanel.swift:635-663). Android used to drop every
                    // sub-2s segment on its own, so natural stop-start speech —
                    // "yes" (0.8 s), pause, "send it" (0.9 s) — could NEVER be
                    // dictated: each piece was binned and the mic switched off.
                    // The 2 s floor still does its job (one isolated cough is
                    // still one short segment), it just applies to the whole
                    // held utterance now.
                    pendingSegments.add(wav)
                    val heldSeconds = WavSegmentMerger.totalSeconds(pendingSegments)

                    if (reason == SegmentEndReason.SILENCE_DETECTED &&
                        heldSeconds < MIN_SEGMENT_SECONDS
                    ) {
                        Log.i(
                            TAG,
                            "[vad] holding ${"%.2f".format(spokenSeconds)}s segment " +
                                "(total ${"%.2f".format(heldSeconds)}s < ${MIN_SEGMENT_SECONDS}s)",
                        )
                        // Keep the mic OPEN so the next burst can top it up.
                        // Force-flush after HOLD_FLUSH_MS of real silence so a
                        // genuine cough still resolves (to a "too short" toast)
                        // instead of leaving the mic on indefinitely.
                        holdFlushJob?.cancel()
                        holdFlushJob = scope.launch {
                            kotlinx.coroutines.delay(HOLD_FLUSH_MS)
                            if (cancelled.get()) return@launch
                            val held = WavSegmentMerger.totalSeconds(pendingSegments)
                            Log.i(TAG, "[vad] hold expired at ${"%.2f".format(held)}s — settling")
                            stopVad()
                            // [T-android-vad-hold-expiry-terminal] Settling the mic
                            // ends the take, so it MUST end in a terminal callback
                            // on every path. A sub-floor take (a cough) used to get
                            // none: SpeechRecognitionManager stayed RECORDING with
                            // a dead mic, and a later stop() skipped the flush
                            // (detector already null) — the panel spun on
                            // "Recognizing..." forever. The floor now only decides
                            // whether the user sees a reason: NO_MATCH with a null
                            // message is the silent outcome (the panel swallows
                            // NO_MATCH). The session is disarmed so nothing can
                            // finalise it a second time.
                            vadSession = null
                            listener.onError(
                                RecognitionError.NO_MATCH,
                                if (held > TOO_SHORT_TOAST_FLOOR) "Too short — hold the mic and speak." else null,
                            )
                            pendingSegments.clear()
                        }
                        return
                    }

                    holdFlushJob?.cancel()
                    holdFlushJob = null
                    val merged = WavSegmentMerger.merge(pendingSegments) ?: wav
                    if (pendingSegments.size > 1) {
                        Log.i(
                            TAG,
                            "[vad] merged ${pendingSegments.size} segments → " +
                                "${"%.2f".format(heldSeconds)}s",
                        )
                    }
                    pendingSegments.clear()

                    if (reason == SegmentEndReason.SILENCE_DETECTED) {
                        // Silence = the user stopped. Mic off, then transcribe.
                        // The detector already delivered this segment, so the
                        // manual-stop flush must not fire for it as well.
                        vadSession = null
                        stopVad()
                    }
                    transcribeJob = scope.launch {
                        transcribeSegment(merged, locale, repo, candidates, listener)
                    }
                }

                override fun onSessionLimit(limit: SessionLimit) {
                    // Allowance exhausted, not a failure. The library has
                    // already delivered any closed segment, so settle the mic.
                    Log.i(TAG, "[vad] session limit $limit — stopping")
                    VoicePipelineLog.event("engine.sessionLimit", "limit" to limit.name)
                    stopVad()
                    // [T-android-vad-manual-stop] …but settling the mic is not
                    // a terminal callback, and SpeechRecognitionManager is left
                    // mid-session waiting for one. Flush whatever is held (and
                    // emit NO_MATCH when nothing is) so the state machine
                    // always returns to IDLE — same invariant as stop().
                    flushVadOnManualStop()
                }

                override fun onCaptureError(message: String) {
                    stopVad()
                    recording.set(false)
                    listener.onError(RecognitionError.AUDIO_ERROR, message)
                }

                // [T-voice-mic-preempted] Another app holds the mic.
                override fun onMicInUse(detail: String) {
                    Log.w(TAG, "[vad] microphone in use by another app: $detail")
                    stopVad()
                    recording.set(false)
                    listener.onError(RecognitionError.MIC_IN_USE, detail)
                }
            },
        ).also {
            // iOS splits this by engine: 59 s for Apple's system ASR (which
            // rejects >60 s per request) and the full 300 s session cap for
            // cloud providers, which have no such per-request ceiling
            // (VoiceInputPanel.swift:488-489). This IS the cloud path.
            it.maxSegmentSeconds = CLOUD_MAX_SEGMENT_SECONDS
        }

        val err = det.start()
        if (err != null) {
            recording.set(false)
            // [T-voice-mic-preempted] "not initialized" is usually another
            // app holding the input; blamed on it only with evidence.
            listener.onError(MicInUse.captureFailure(appContext), err)
            return
        }
        detector = det
        vadSession = VadSession(locale, repo, candidates, listener)
        listener.onReadyForSpeech()
    }

    /** Transcribe one segment. Extracted so the VAD and legacy paths share it. */
    private suspend fun transcribeSegment(
        wav: ByteArray,
        locale: Locale,
        repo: com.openminis.app.data.repository.ProviderRepository,
        candidates: List<Pair<com.openminis.app.data.model.ProviderInstance, com.openminis.app.data.model.ModelEntry>>,
        listener: SpeechRecognitionEngine.Listener,
    ) = transcribeAndDeliver(wav, locale, repo, candidates, listener, logEvents = false)

    /**
     * Transcribe [wav] with SEAMLESS fail-over across [candidates] (mirrors iOS
     * transcribeWithFailover): the last successful member goes first (sticky),
     * a candidate that throws advances to the next, and only when every
     * candidate fails does the error surface, with the audio handed back for
     * Retry / Discard.
     *
     * [T-android-voice-asr-stall-skip] Every attempt runs through
     * [asrAttempts]. An attempt with somewhere to go next — the next
     * candidate, or System recognition after the last one — is offered as
     * stalled after [ASR_STALL_THRESHOLD_MS]; a user skip continues the chain,
     * and when a skip exhausts it System recognition transcribes the same
     * audio. A chain that merely FAILS keeps the existing Retry / Discard
     * prompt.
     */
    private suspend fun transcribeAndDeliver(
        wav: ByteArray,
        locale: Locale,
        repo: com.openminis.app.data.repository.ProviderRepository,
        candidates: List<Pair<com.openminis.app.data.model.ProviderInstance, com.openminis.app.data.model.ModelEntry>>,
        listener: SpeechRecognitionEngine.Listener,
        logEvents: Boolean,
    ) {
        if (systemAsrPinned) {
            Log.i(TAG, "ASR: System recognition pinned for this take after a skip")
            transcribeWithSystem(wav, locale, listener)
            return
        }
        if (logEvents) VoicePipelineLog.event("engine.transcribe.begin", "candidates" to candidates.size)
        // Sticky-first try order: rotate the chain so the last successful
        // member goes first, the rest wrap around.
        val ordered = stickyEntryId
            ?.let { sticky -> candidates.indexOfFirst { it.second.id == sticky } }
            ?.takeIf { it > 0 }
            ?.let { i -> candidates.drop(i) + candidates.take(i) }
            ?: candidates
        val systemAvailable = systemEngine() != null
        var lastError: Exception? = null
        var userSkipped = false
        for ((i, pair) in ordered.withIndex()) {
            val (instance, entry) = pair
            if (cancelled.get()) return
            val provider = VoiceProviderFactory.make(instance, repo.loadApiKey(instance.id))
            if (provider == null) {
                Log.w(TAG, "candidate ${instance.label} cannot serve voice input — skipping")
                if (logEvents) VoicePipelineLog.event("engine.transcribe.skip", "model" to entry.baseModel.id)
                continue
            }
            val nextEntryId: String? = when {
                i + 1 < ordered.size -> ordered[i + 1].second.id
                systemAvailable -> SYSTEM_NEXT
                else -> null
            }
            val tryStartedMs = System.currentTimeMillis()
            if (logEvents) VoicePipelineLog.event("engine.transcribe.try", "model" to entry.baseModel.id)
            try {
                val response = asrAttempts.run(entry.model.displayName, nextEntryId, canSkip = nextEntryId != null) {
                    provider.transcribe(
                        VoiceInputRequest(
                            audioData = wav,
                            model = entry.baseModel.id,
                            language = locale.toLanguageTag(),
                            resolvedModel = entry.model,
                        ),
                    ).text
                }
                if (cancelled.get()) {
                    if (logEvents) VoicePipelineLog.event("engine.transcribe.cancelledAfterResponse")
                    return
                }
                stickyEntryId = entry.id
                val text = response.trim()
                // Length only — never the transcript.
                if (logEvents) {
                    VoicePipelineLog.event(
                        "engine.transcribe.ok",
                        "model" to entry.baseModel.id,
                        "ms" to (System.currentTimeMillis() - tryStartedMs),
                        "chars" to text.length,
                    )
                }
                if (text.isEmpty()) {
                    // A successful round-trip that heard nothing is a semantic
                    // no-match, not a provider failure — do NOT advance for it.
                    listener.onError(RecognitionError.NO_MATCH, "Empty transcription.")
                } else {
                    listener.onFinal(text)
                }
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SkippableAsrAttempts.Skipped) {
                userSkipped = true
                lastError = e
                Log.i(TAG, "ASR fail-over: ${entry.model.displayName} skipped by the user after " +
                    "${ASR_STALL_THRESHOLD_MS / 1000}s without a result" +
                    if (i + 1 < ordered.size) " — trying next candidate" else " — falling back to System")
                if (logEvents) VoicePipelineLog.event("engine.transcribe.skipped", "model" to entry.baseModel.id)
            } catch (e: Exception) {
                Log.e(TAG, "transcribe failed on ${entry.model.displayName}: ${e.message} — trying next candidate")
                // Class name only: a provider message can echo the request.
                if (logEvents) {
                    VoicePipelineLog.event(
                        "engine.transcribe.fail",
                        "model" to entry.baseModel.id,
                        "ms" to (System.currentTimeMillis() - tryStartedMs),
                        "err" to VoicePipelineLog.errorKind(e),
                    )
                }
                lastError = e
            }
        }
        if (cancelled.get()) return
        // [T-android-voice-asr-stall-skip] The user chose to move on and the
        // chain has nothing left: System recognition is the last resort. Only
        // after a user skip — a chain that simply FAILED keeps Retry / Discard.
        if (userSkipped && systemAvailable) {
            systemAsrPinned = true
            Log.i(TAG, "ASR fail-over: chain exhausted after a user skip — using System recognition for this take")
            transcribeWithSystem(wav, locale, listener)
            return
        }
        if (logEvents) VoicePipelineLog.event("engine.transcribe.exhausted", "err" to VoicePipelineLog.errorKind(lastError))
        val e = lastError
        val kind = when {
            e is VoiceProviderException.Auth -> RecognitionError.PERMISSION_DENIED
            e is java.io.IOException -> RecognitionError.NETWORK
            else -> RecognitionError.UNKNOWN
        }
        // [T-voice-asr-failure-retry-prompt] Every candidate failed (or none
        // could serve): hand the audio back so the user is asked to retry
        // instead of losing the take.
        listener.onRetainedAudio(wav)
        listener.onError(kind, e?.message ?: "No usable voice-input model could transcribe this.")
    }

    /**
     * [T-android-voice-asr-stall-skip] System recognition as the last resort,
     * fed the same audio through [SystemSpeechRecognitionEngine.transcribeRetained]
     * (API 33+; below that it hands the audio back for Retry / Discard).
     */
    private fun transcribeWithSystem(wav: ByteArray, locale: Locale, listener: SpeechRecognitionEngine.Listener) {
        val system = systemEngine()
        if (system == null) {
            listener.onRetainedAudio(wav)
            listener.onError(RecognitionError.OEM_NO_SERVICE, "System speech recognition is not available.")
            return
        }
        // [T-android-voice-system-fallback-cancel] Only this take may hear
        // back; a newer take or a cancel supersedes it (see takeGeneration).
        val gen = takeGeneration.get()
        systemReplayGeneration.set(gen)
        system.transcribeRetained(wav, locale, object : SpeechRecognitionEngine.Listener {
            private fun live(what: String): Boolean =
                (takeGeneration.get() == gen).also { if (!it) Log.i(TAG, "ASR: dropped stale System $what (take superseded)") }
            override fun onPartial(text: String) { if (live("partial")) listener.onPartial(text) }
            override fun onFinal(text: String) { if (live("final")) listener.onFinal(text) }
            override fun onError(error: RecognitionError, message: String?) {
                if (live("error")) listener.onError(error, message)
            }
            override fun onRmsDb(rms: Float) { if (takeGeneration.get() == gen) listener.onRmsDb(rms) }
            override fun onReadyForSpeech() { if (takeGeneration.get() == gen) listener.onReadyForSpeech() }
            override fun onRetainedAudio(wav: ByteArray) { if (live("audio")) listener.onRetainedAudio(wav) }
        })
    }

    /**
     * [T-android-voice-system-fallback-cancel] Start a new take (a capture, a
     * retry, or a cancel): the previous take's System fallback, if it started
     * one, is cancelled and anything it still reports is dropped.
     */
    private fun supersedeTake() {
        val old = takeGeneration.getAndIncrement()
        if (systemReplayGeneration.compareAndSet(old, -1)) {
            Log.i(TAG, "ASR: take superseded — cancelling its System recognition fallback")
            systemEngine()?.cancel()
        }
    }

    /** The on-device/System recognizer, when it can replay retained audio (API 33+). */
    private fun systemEngine(): SystemSpeechRecognitionEngine? =
        SpeechRecognitionManager.availableEngines()
            .firstOrNull { it is SystemSpeechRecognitionEngine && it.isAvailable }
            ?.takeIf { android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU }
            as? SystemSpeechRecognitionEngine

    /**
     * [T-android-voice-asr-stall-skip] The user tapped "Switch Model": abandon
     * every attempt stalled past the threshold. Each abandoned transcription
     * continues with its next voice model (System last); the audio is not lost.
     * The NEXT segments are re-pointed here, synchronously, so audio captured
     * after the tap does not queue on the server that just hung. Returns the
     * name of the model now used (null when nothing was stalled), for the toast.
     */
    fun skipStalledTranscription(systemName: String, genericName: String): String? {
        val nexts = asrAttempts.skipStalled()
        if (nexts.isEmpty()) return null
        val targets = nexts.filterNotNull()
        val nextCandidate = targets.firstOrNull { it != SYSTEM_NEXT }
            ?.let { id -> currentCandidates.firstOrNull { it.second.id == id } }
        val name = when {
            nextCandidate != null -> {
                stickyEntryId = nextCandidate.second.id
                nextCandidate.second.model.displayName
            }
            targets.contains(SYSTEM_NEXT) -> {
                systemAsrPinned = true
                systemName
            }
            // The candidate set changed under the skip: the abandoned attempts
            // still move on inside their own loops.
            else -> genericName
        }
        Log.i(TAG, "ASR stall: skip → next segments start on $name")
        return name
    }

    /**
     * [T-voice-asr-failure-retry-prompt] Retry a segment whose transcription
     * failed. Re-resolves the candidate chain (the user may have fixed a key or
     * switched model since), then goes through the same fail-over as a live
     * take. With no usable candidate the audio is handed back again, so a
     * "no provider" failure stays retryable rather than being dropped.
     */
    override fun transcribeRetained(wav: ByteArray, locale: Locale, listener: SpeechRecognitionEngine.Listener) {
        val repo = repository()
        val candidates = repo?.resolveVoiceInputCandidates(
            loadBalanceSeed = kotlin.random.Random.nextInt(Int.MAX_VALUE),
        ).orEmpty()
        if (repo == null || candidates.isEmpty()) {
            listener.onRetainedAudio(wav)
            listener.onError(
                RecognitionError.OEM_NO_SERVICE,
                "No provider voice-input model configured. Add an ASR model to the Voice Input group.",
            )
            return
        }
        supersedeTake()
        cancelled.set(false)
        currentCandidates = candidates
        // [T-android-voice-asr-stall-skip] A retry re-resolves the chain, so a
        // System pin from an earlier skip no longer applies (iOS clears it when
        // the candidates are re-resolved); the retry starts on the chain again.
        systemAsrPinned = false
        VoicePipelineLog.event("engine.retry.begin", "candidates" to candidates.size)
        transcribeJob = scope.launch {
            transcribeSegment(wav, locale, repo, candidates, listener)
        }
    }

    private fun stopVad() {
        detector?.let { runCatching { it.stop() } }
        detector = null
        recording.set(false)
    }

    /**
     * [T-android-vad-manual-stop] Finalise a VAD take the user ended by hand.
     *
     * The detector emits a segment only when IT decides the utterance ended
     * (silence, length cap). `VoiceActivityDetector.stop()` is documented as
     * "any segment already delivered stands; nothing new is emitted", so
     * tearing it down on a manual stop meant the audio held in
     * [pendingSegments] was never merged, never transcribed, and — the part
     * the user actually sees — NO terminal callback ever reached
     * SpeechRecognitionManager. The manager had already moved to FINISHING, so
     * the panel sat on "Recognizing…" forever with no text and no error.
     *
     * This is the flush iOS performs with `vad.flush()` on a manual stop
     * (VoiceInputPanel.swift), and the case `SegmentEndReason.MANUAL_FLUSH`
     * was reserved for: take whatever is held, transcribe it, and when there
     * is nothing held still deliver an error so the state machine leaves
     * FINISHING. Every path out of here MUST end in onFinal or onError.
     */
    private fun flushVadOnManualStop() {
        val session = vadSession
        vadSession = null
        holdFlushJob?.cancel()
        holdFlushJob = null
        if (session == null) return
        val held = pendingSegments.toList()
        pendingSegments.clear()
        val merged = if (held.isEmpty()) null else WavSegmentMerger.merge(held) ?: held.firstOrNull()
        val seconds = if (held.isEmpty()) 0f else WavSegmentMerger.totalSeconds(held)
        VoicePipelineLog.event(
            "engine.manualStop.flush",
            "segments" to held.size,
            "seconds" to "%.2f".format(seconds),
        )
        val outcome = VadManualStopOutcome.decide(
            hasSession = true,
            heldSeconds = if (merged == null) 0f else seconds,
            floorSeconds = TOO_SHORT_TOAST_FLOOR,
        )
        if (outcome != VadManualStopOutcome.TRANSCRIBE || merged == null) {
            // Nothing usable — still a terminal callback, never silence.
            session.listener.onError(
                RecognitionError.NO_MATCH,
                "Too short — hold the mic and speak.",
            )
            return
        }
        transcribeJob = scope.launch {
            transcribeSegment(merged, session.locale, session.repo, session.candidates, session.listener)
        }
    }

    /** [T-android-vad] See SystemSpeechRecognitionEngine.setBackgrounded. */
    fun setBackgrounded(backgrounded: Boolean) {
        detector?.isBackgrounded = backgrounded
    }

    override fun stop() {
        // Flip the capture loop off. On the LEGACY path the capture coroutine
        // then hands the take to the transcription step itself, which delivers
        // onFinal/onError.
        recording.set(false)
        // [T-android-vad-manual-stop] The VAD path has no such coroutine: its
        // audio only ever leaves through the detector's onVoiceEnd callback,
        // so it must be flushed explicitly BEFORE the detector is destroyed.
        //
        // Two different buffers are in play and BOTH have to be collected:
        //  - the segment the library still has OPEN (the user tapped stop
        //    mid-sentence, so onVoiceEnd will never fire for it) — only
        //    VoiceActivityDetector.flush() can produce it;
        //  - segments already closed and held in pendingSegments for merge.
        val det = detector
        val wasVad = det != null
        val openSegment = det?.let { runCatching { it.flush() }.getOrNull() }
        if (openSegment != null) pendingSegments.add(openSegment)
        det?.let { runCatching { it.stop() } }
        detector = null
        if (wasVad) flushVadOnManualStop()
    }

    override fun cancel() {
        supersedeTake()
        cancelled.set(true)
        recording.set(false)
        transcribeJob?.cancel()
        holdFlushJob?.cancel()
        holdFlushJob = null
        // [T-android-vad-manual-stop] A cancelled session must not be
        // finalisable afterwards.
        vadSession = null
        // Drop held audio: a cancelled session's partial utterance must never
        // surface in a later one.
        pendingSegments.clear()
    }

    /** Rough dB estimate over the chunk for the UI waveform. */
    private fun rmsDb(buf: ByteArray, len: Int): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < len) {
            val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            sum += sample.toDouble() * sample
            count++
            i += 2
        }
        if (count == 0) return 0f
        val rms = sqrt(sum / count)
        if (rms <= 1.0) return 0f
        // Map amplitude RMS onto the [0, 12]-ish scale the System engine's
        // onRmsChanged reports, so the shared normalizer behaves identically.
        return (20 * log10(rms / 32768.0) + 50).toFloat().coerceIn(0f, 12f)
    }
}

// ─── Merged from SpeechRecognitionEngine.kt ──────────────────────────────────────────────────

/**
 * Adapter for a single speech-to-text backend.
 *
 * Implementations exist for:
 *  - [SystemSpeechRecognitionEngine]: wraps android.speech.SpeechRecognizer,
 *    smooths over OEM fragmentation (Pixel/Samsung/Xiaomi … AOSP / HarmonyOS).
 *  - [ProviderSpeechRecognitionEngine]: captures PCM via AudioRecord and
 *    dispatches it to the resolved cloud provider's transcription endpoint
 *    through the VoiceProvider stack. One-shot on stop — it reports
 *    `supportsPartialResults = false`, so the System engine remains the
 *    streaming/live-transcript option.
 *
 * Engines are stateless w.r.t. other engines: the [SpeechRecognitionManager]
 * owns selection and state aggregation.
 */
interface SpeechRecognitionEngine {

    /** Stable identifier persisted to preferences (e.g. "system", "provider:openai-whisper"). */
    val id: String

    /** Human-readable name shown in a picker (localized externally). */
    val displayName: String

    /**
     * Best-effort availability. Implementations should perform *cheap* checks
     * only (package visibility, service presence). A `true` result does not
     * guarantee that [start] will succeed — handle errors via [Listener.onError]
     * and downgrade through [markDegraded] if necessary.
     */
    val isAvailable: Boolean

    /**
     * Whether this engine streams interim ("partial") results. Used by the UI
     * to decide if it should show a live transcription caret.
     */
    val supportsPartialResults: Boolean

    /**
     * Locales the engine can transcribe. Can be expensive to compute (involves
     * a broadcast on Android) — implementations should cache.
     */
    val supportedLocales: List<Locale>

    /**
     * Begin recognition. The engine is responsible for audio capture and will
     * invoke [listener] callbacks until [stop] or a terminal error fires.
     * Callers must ensure RECORD_AUDIO permission is granted before calling.
     */
    fun start(locale: Locale, listener: Listener)

    /** Stop capture; a final result (or error) is still delivered via [Listener]. */
    fun stop()

    /** Abort capture; no further callbacks will fire. */
    fun cancel()

    /**
     * Optional: called by [SpeechRecognitionManager] when a prior [start] ended
     * in an unrecoverable error so the engine can remember it and report
     * `isAvailable = false`.
     *
     * Degradation is PER-ENGINE by design: `refreshAvailability()` is
     * `engines.any { it.isAvailable }`, so a poisoned system engine must not
     * mask a working provider engine.
     */
    fun markDegraded() {}

    /**
     * [T-android-voice-entry-always-available] Undo [markDegraded].
     *
     * Degradation used to be permanent for the process lifetime with no reset
     * path, so a single transient failure (mic held by another app, permission
     * not yet granted, a provider that was misconfigured and has since been
     * fixed) disabled the engine until the app was restarted. The user
     * explicitly re-entering voice mode or picking this engine is the signal
     * that conditions may have changed — give it another chance.
     */
    fun clearDegraded() {}

    /**
     * [T-voice-asr-failure-retry-prompt] Re-transcribe audio kept from an
     * earlier failed take ([Listener.onRetainedAudio]). No capture: the result
     * arrives through [listener] exactly like a live take's terminal callback,
     * and a second failure hands the audio back again.
     *
     * [wav] is mono PCM16 with a 44-byte header, at whatever rate the SAME
     * engine handed it back with (retries always go to the engine that
     * produced the audio). The default reports an
     * error so an engine that cannot replay audio still ends in a terminal
     * callback.
     */
    fun transcribeRetained(wav: ByteArray, locale: Locale, listener: Listener) {
        listener.onError(RecognitionError.UNKNOWN, "This recognizer cannot re-transcribe saved audio.")
    }

    interface Listener {
        /** Incremental interim result. Only fires if [supportsPartialResults]. */
        fun onPartial(text: String)

        /** Terminal — capture has finished cleanly. */
        fun onFinal(text: String)

        /** Terminal — capture failed. */
        fun onError(error: RecognitionError, message: String? = null)

        /** Audio level in dB for UI animation; safe to ignore. */
        fun onRmsDb(rms: Float) {}

        /** Fires when the engine starts actually listening (after warmup). */
        fun onReadyForSpeech() {}

        /**
         * [T-voice-asr-failure-retry-prompt] Called immediately BEFORE a
         * terminal [onError] when the engine still holds the audio of the
         * utterance that failed to transcribe (mono PCM16 WAV; the header
         * carries the sample rate). The
         * caller keeps it and offers a retry instead of the speech being
         * dropped — long dictation is hard to repeat.
         */
        fun onRetainedAudio(wav: ByteArray) {}
    }
}

enum class RecognitionError {
    /** No speech detected / silence. Recoverable — the user can try again. */
    NO_MATCH,

    /**
     * [T-android-asr-silent-failure] Speech WAS detected (our VAD saw a
     * voiced segment) but the recognizer produced no text. Distinct from
     * [NO_MATCH] because the UI deliberately swallows NO_MATCH — it means
     * "you didn't say anything", which is exactly the wrong message when the
     * user spoke for 30 s and got nothing. This one must be shown.
     */
    TRANSCRIPTION_FAILED,

    /** Engine reported network failure (cloud recognizers). */
    NETWORK,

    /** RECORD_AUDIO denied at runtime. */
    PERMISSION_DENIED,

    /**
     * The host OS / ROM ships no recognition service. Permanent for the
     * current process; the UI should hide the mic button.
     */
    OEM_NO_SERVICE,

    /** System recognizer is busy (multi-process contention). Retry later. */
    RECOGNIZER_BUSY,

    /** Requested locale not supported by this engine. */
    LANGUAGE_UNSUPPORTED,

    /** Audio capture failed (mic hardware / HAL). */
    AUDIO_ERROR,

    /**
     * [T-voice-mic-preempted] Another app holds the microphone (a call, a
     * voice recorder). Transient, and not this engine's fault: it must not
     * mark the engine degraded. See [MicInUse].
     */
    MIC_IN_USE,

    /**
     * [T-voice-asr-failure-retry-prompt] The recognizer produced no text
     * before its (audio-length-scaled) watchdog fired. A failure — the audio
     * is kept and the user asked to retry — not a "no speech" result.
     */
    TIMED_OUT,

    /** Any other failure. */
    UNKNOWN,
}

/** What the manager is currently doing. Mirrors iOS SpeechRecognitionManager.State. */
enum class RecognitionState {
    /** Not listening. Default. */
    IDLE,

    /** Permission request / engine warmup in flight. */
    STARTING,

    /** Capturing audio and delivering partial/final results. */
    RECORDING,

    /**
     * Capture stopped locally; waiting for the engine to flush the final
     * result. Brief; transitions to [IDLE].
     */
    FINISHING,
}

// ─── Merged from SpeechRecognitionManager.kt ──────────────────────────────────────────────────

/**
 * Application-scoped orchestrator for speech-to-text. Owns:
 *
 *  - A registry of [SpeechRecognitionEngine] adapters (system + provider).
 *  - The user's selected engine id (persisted) and active locale.
 *  - Lifecycle of the current capture session (single in-flight request).
 *  - Public state flows the UI collects:
 *      * [isAvailable] — whether the mic button should render at all.
 *      * [state] — idle / starting / recording / finishing.
 *      * [recognizedText] — live transcription (empty between sessions).
 *      * [locale] — current recognition locale.
 *
 * Mirrors the responsibilities of iOS `SpeechRecognitionManager.swift`.
 */
object SpeechRecognitionManager {

    private const val TAG = "SpeechManager"
    private const val PREFS = "speech_recognition"
    private const val KEY_ENGINE_ID = "engineId"
    private const val KEY_LOCALE = "locale"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private val engines = mutableListOf<SpeechRecognitionEngine>()

    private val _state = MutableStateFlow(RecognitionState.IDLE)
    val state: StateFlow<RecognitionState> = _state.asStateFlow()

    /**
     * [T-android-tts-stop-on-capture] Resume read-aloud the moment capture
     * ends, whichever way it ended.
     *
     * There are five separate writes of IDLE in this file — final result,
     * engine error, engine-start throw, cancelRecording, and the mid-session
     * locale restart — plus FINISHING from stopRecording. Hanging the resume
     * off each one individually means a sixth path added later silently leaves
     * TTS muted forever, which is a much worse failure than a missed pause.
     * Funnelling every transition through one setter makes that impossible by
     * construction.
     *
     * FINISHING deliberately does NOT resume: the engine is still delivering
     * the final result and the mic may still be hot.
     */
    private fun setState(next: RecognitionState) {
        val prev = _state.value
        _state.value = next
        if (next == RecognitionState.IDLE && prev != RecognitionState.IDLE) {
            VoiceOutputState.resumeAllAfterCapture()
        }
    }

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _locale = MutableStateFlow(Locale.getDefault())
    val locale: StateFlow<Locale> = _locale.asStateFlow()

    private val _selectedEngineId = MutableStateFlow("system")
    val selectedEngineId: StateFlow<String> = _selectedEngineId.asStateFlow()

    private val _lastError = MutableStateFlow<RecognitionError?>(null)
    val lastError: StateFlow<RecognitionError?> = _lastError.asStateFlow()

    /**
     * Whether SOME engine can transcribe right now. This is a runtime probe —
     * it flips as engines degrade, providers get configured, etc.
     *
     * [T-android-voice-entry-always-available] Do NOT use this to decide
     * whether the mic / keyboard-toggle button RENDERS. Gating a control on a
     * runtime probe is what stranded users inside voice mode when the active
     * engine degraded mid-session: the toggle was the only way out and it
     * vanished with the probe. Use [hasMicrophoneHardware] for existence, and
     * this flow only to decide what the voice panel SHOWS once entered.
     */
    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    /**
     * [T-android-voice-entry-always-available] Structural, device-level fact:
     * does this device have microphone hardware at all?
     *
     * Unlike [isAvailable] this never changes at runtime, so it is the only
     * thing the composer's voice button existence depends on. A device with no
     * mic genuinely cannot offer voice input; everything else (no speech
     * service installed, engine degraded, no ASR provider configured) is a
     * RECOVERABLE state that must be explained inside the panel rather than by
     * silently removing the control.
     */
    val hasMicrophoneHardware: Boolean
        get() = if (::appContext.isInitialized) {
            runCatching {
                appContext.packageManager.hasSystemFeature(
                    android.content.pm.PackageManager.FEATURE_MICROPHONE,
                )
            }.getOrDefault(true)
        } else {
            // Not initialised yet — assume present. Showing the button and
            // explaining inside the panel beats hiding the entry point.
            true
        }

    /**
     * Locales the active engine reports as supported. Populated lazily after
     * the first availability probe; see [refreshSupportedLocales]. The UI's
     * language picker observes this; while empty it falls back to showing the
     * current locale only.
     */
    private val _supportedLocales = MutableStateFlow<List<Locale>>(emptyList())
    val supportedLocales: StateFlow<List<Locale>> = _supportedLocales.asStateFlow()

    /**
     * Rolling window of normalized (0f..1f) audio levels for the live waveform.
     * New samples push in at the tail; oldest drop off the head. Mirrors iOS
     * `SpeechRecognitionManager.audioLevels` (40-element buffer).
     */
    private val _audioLevels = MutableStateFlow(List(WAVEFORM_SIZE) { 0f })
    val audioLevels: StateFlow<List<Float>> = _audioLevels.asStateFlow()

    /** How many rolling level samples to keep. */
    const val WAVEFORM_SIZE = 40

    /**
     * Android's onRmsChanged reports values roughly in [-2, 10]. Clamp to
     * [0, 12] and scale linearly to [0, 1]; negatives (quieter than the
     * recognizer's own reference) land on 0 and read as silence.
     *
     * [T-android-voice-parity] The previous doc-comment claimed "a floor at the
     * mid-point of the noise range" — no such floor exists here or ever did,
     * and adding one now would raise the idle baseline and make silence look
     * like speech, which is the opposite of what that sentence promised. The
     * visual floor is applied at render time instead (bars are drawn at
     * `5.dp + level * 21.dp`, so 0 still shows a 5 dp stub), matching iOS,
     * which likewise floors in the view layer.
     *
     * Note this is a plain linear ramp, whereas iOS applies a perceptual
     * `sqrt(rms * 14) * 0.95` curve (VoiceInputPanel.swift:1177). Full parity
     * would need iOS's raw-PCM level source; with onRmsChanged's already-dB-ish
     * scale a second curve would double-compress the top end.
     */
    private fun normalizeRms(db: Float): Float {
        val clamped = db.coerceIn(0f, 12f)
        return (clamped / 12f).coerceIn(0f, 1f)
    }

    internal fun pushLevel(rawDb: Float) {
        val n = normalizeRms(rawDb)
        val current = _audioLevels.value
        val updated = ArrayList<Float>(current.size)
        for (i in 1 until current.size) updated.add(current[i])
        updated.add(n)
        _audioLevels.value = updated
    }

    private fun resetLevels() {
        _audioLevels.value = List(WAVEFORM_SIZE) { 0f }
    }

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        engines.clear()
        engines.add(SystemSpeechRecognitionEngine(appContext))
        engines.add(ProviderSpeechRecognitionEngine(appContext))

        _selectedEngineId.value = prefs.getString(KEY_ENGINE_ID, null) ?: pickDefaultEngineId()
        prefs.getString(KEY_LOCALE, null)?.let { tag ->
            runCatching { _locale.value = Locale.forLanguageTag(tag) }
        }
        refreshAvailability()
        refreshSupportedLocales()
        observeAppLifecycle()
    }

    /**
     * [T-android-vad] Feed foreground/background transitions to the engines so
     * their detectors can apply iOS's 15 s backgrounded auto-stop
     * (VoiceInputPanel.swift:252-260, 285-295). Capture survives a brief switch
     * away — a notification pull-down, a glance at another app — but not a real
     * one, which would otherwise leave the mic live indefinitely.
     *
     * Observer registration is main-thread only.
     */
    private fun observeAppLifecycle() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
                    androidx.lifecycle.LifecycleEventObserver { _, event ->
                        when (event) {
                            androidx.lifecycle.Lifecycle.Event.ON_STOP -> setBackgrounded(true)
                            androidx.lifecycle.Lifecycle.Event.ON_START -> setBackgrounded(false)
                            else -> {}
                        }
                    },
                )
            }.onFailure { Log.w(TAG, "lifecycle observer failed: ${it.message}") }
        }
    }

    private fun setBackgrounded(backgrounded: Boolean) {
        for (e in engines) {
            when (e) {
                is SystemSpeechRecognitionEngine -> e.setBackgrounded(backgrounded)
                is ProviderSpeechRecognitionEngine -> e.setBackgrounded(backgrounded)
            }
        }
    }

    /**
     * Ask the active engine which languages it supports (async) and update
     * [_supportedLocales]. Also re-validates the current locale: if the saved
     * locale isn't in the list (e.g. user changed regions) it falls back to
     * the system default, then en-US.
     */
    fun refreshSupportedLocales() {
        val engine = currentEngine() ?: return
        if (engine is SystemSpeechRecognitionEngine) {
            engine.fetchSupportedLocales { locales ->
                _supportedLocales.value = locales
                if (locales.isNotEmpty() && locales.none { it.toLanguageTag() == _locale.value.toLanguageTag() }) {
                    val fallback = locales.firstOrNull { it.language == Locale.getDefault().language }
                        ?: locales.firstOrNull { it.language == "en" }
                        ?: locales.first()
                    _locale.value = fallback
                }
            }
        } else {
            _supportedLocales.value = engine.supportedLocales
        }
    }

    private fun pickDefaultEngineId(): String =
        engines.firstOrNull { it.isAvailable }?.id ?: "system"

    fun selectEngine(id: String) {
        if (_selectedEngineId.value == id) return
        _selectedEngineId.value = id
        prefs.edit().putString(KEY_ENGINE_ID, id).apply()
        // [T-android-voice-entry-always-available] Deliberately picking an
        // engine is an explicit "try this one" — clear its degraded flag so a
        // past transient failure (mic busy, permission not yet granted, a
        // provider that was misconfigured and has since been fixed) doesn't
        // leave it permanently unselectable. `degraded` is otherwise
        // process-scoped with no reset path at all.
        engines.firstOrNull { it.id == id }?.clearDegraded()
        refreshAvailability()
    }

    /**
     * [T-android-voice-entry-always-available] Give every engine a fresh start.
     * Called when the user re-enters voice mode, so a transient failure does
     * not poison the feature for the rest of the process — the user's retry is
     * the signal that conditions may have changed (permission granted, other
     * app released the mic, provider configured).
     */
    fun clearDegradationAndRefresh() {
        engines.forEach { it.clearDegraded() }
        refreshAvailability()
    }

    fun selectLocale(locale: Locale) {
        if (_locale.value.toLanguageTag() == locale.toLanguageTag()) return
        _locale.value = locale
        prefs.edit().putString(KEY_LOCALE, locale.toLanguageTag()).apply()
        // If the user changed language mid-recording, iOS restarts the
        // session in the new language. Matching that behavior.
        if (_state.value == RecognitionState.RECORDING || _state.value == RecognitionState.STARTING) {
            val cb = activeCallbacks
            if (cb != null) {
                currentEngine()?.cancel()
                setState(RecognitionState.IDLE)
                startRecording(cb.onPartialOrFinal, cb.onError, cb.onFailedAudio)
            }
        }
    }

    /** Callbacks from the most recent [startRecording] so [selectLocale] can restart with them. */
    private class Callbacks(
        val onPartialOrFinal: (String, Boolean) -> Unit,
        val onError: (RecognitionError, String?) -> Unit,
        val onFailedAudio: ((FailedAudio) -> Unit)?,
    )
    private var activeCallbacks: Callbacks? = null

    /**
     * [T-voice-asr-failure-retry-prompt] One utterance whose transcription
     * failed, with its audio kept so the user can retry instead of repeating
     * it. [engineId] is the engine that produced it (and will retry it).
     */
    class FailedAudio(
        val wav: ByteArray,
        val seconds: Double,
        val error: RecognitionError,
        val message: String?,
        val engineId: String,
    )

    /** Engine running a [retryTranscription], so stop/cancel reach it. */
    @Volatile
    private var retryEngine: SpeechRecognitionEngine? = null

    fun availableEngines(): List<SpeechRecognitionEngine> = engines.toList()

    private val noAsrStall = MutableStateFlow(false).asStateFlow()

    /**
     * [T-android-voice-asr-stall-skip] True while a cloud ASR attempt has gone
     * [ProviderSpeechRecognitionEngine.ASR_STALL_THRESHOLD_MS] without a result
     * and there is a next voice model to switch to — the voice panel shows its
     * "Switch Model" pill (and hides transcript delete) exactly while this is
     * true. Drops as soon as the stall ends: skipped, finished, failed or
     * cancelled.
     */
    fun asrStalled(): StateFlow<Boolean> =
        engines.filterIsInstance<ProviderSpeechRecognitionEngine>().firstOrNull()?.asrAttempts?.stalled
            ?: noAsrStall

    /**
     * [T-android-voice-asr-stall-skip] Skip every stalled attempt to its next
     * voice model (System last). Returns the model now used, for the toast, or
     * null when nothing was stalled.
     */
    fun skipStalledTranscription(systemName: String, genericName: String): String? =
        engines.filterIsInstance<ProviderSpeechRecognitionEngine>().firstOrNull()
            ?.skipStalledTranscription(systemName, genericName)

    /**
     * Recompute [isAvailable]. Cheap — the UI may call this before rendering
     * the mic button so runtime degradation (first-failure observations in
     * the active engine) takes effect.
     */
    fun refreshAvailability() {
        _isAvailable.value = engines.any { it.isAvailable }
    }

    private fun currentEngine(): SpeechRecognitionEngine? {
        val byId = engines.firstOrNull { it.id == _selectedEngineId.value }
        if (byId != null && byId.isAvailable) return byId
        return engines.firstOrNull { it.isAvailable }
    }

    /**
     * Start a capture session. [onPartialOrFinal] fires with the current best
     * transcription; the caller is responsible for delta-appending into the
     * composer (mirrors iOS `lastRecognizedLength` algorithm).
     *
     * No-op if a session is already in flight.
     */
    fun startRecording(
        onPartialOrFinal: (text: String, isFinal: Boolean) -> Unit,
        onError: (RecognitionError, String?) -> Unit,
        // [T-voice-asr-failure-retry-prompt] Receives failures whose audio the
        // engine kept (instead of [onError]). Null keeps the old behaviour.
        onFailedAudio: ((FailedAudio) -> Unit)? = null,
    ) {
        if (_state.value != RecognitionState.IDLE) {
            Log.d(TAG, "startRecording ignored; state=${_state.value}")
            VoicePipelineLog.event("manager.start.rejected", "state" to _state.value)
            return
        }
        val engine = currentEngine()
        if (engine == null) {
            _lastError.value = RecognitionError.OEM_NO_SERVICE
            VoicePipelineLog.event("manager.start.noEngine")
            onError(RecognitionError.OEM_NO_SERVICE, "No recognition engine available")
            return
        }
        VoicePipelineLog.event("manager.start", "engine" to engine.id)

        // [T-android-tts-stop-on-capture] Silence read-aloud BEFORE the engine
        // opens the mic. Done here rather than at the UI call sites so every
        // entry point is covered — the voice panel, the voice_chat shortcut and
        // hands-free re-arm all funnel through startRecording.
        //
        // Ordering matters: this runs before STARTING is published, so playback
        // is already stopping by the time the recognizer warms up, rather than
        // racing the first audio frames.
        VoiceOutputState.suspendAllForCapture()

        setState(RecognitionState.STARTING)
        _recognizedText.value = ""
        _lastError.value = null
        resetLevels()
        activeCallbacks = Callbacks(onPartialOrFinal, onError, onFailedAudio)

        val listener = makeListener(engine.id, onPartialOrFinal, onError, onFailedAudio)

        try {
            engine.start(_locale.value, listener)
        } catch (e: Throwable) {
            setState(RecognitionState.IDLE)
            _lastError.value = RecognitionError.UNKNOWN
            onError(RecognitionError.UNKNOWN, e.message)
        }
    }

    /**
     * [T-voice-asr-failure-retry-prompt] Re-transcribe audio kept from a failed
     * take. Runs as a FINISHING session (so the panel shows "Recognizing…" and
     * voice mode still owes text), and reports through the same callbacks as a
     * capture; a second failure comes back through [onFailedAudio] again.
     *
     * @return false when another session is in flight — the caller keeps the
     *   utterance queued and asks again later.
     */
    fun retryTranscription(
        failed: FailedAudio,
        onPartialOrFinal: (text: String, isFinal: Boolean) -> Unit,
        onError: (RecognitionError, String?) -> Unit,
        onFailedAudio: (FailedAudio) -> Unit,
    ): Boolean {
        if (_state.value != RecognitionState.IDLE) {
            VoicePipelineLog.event("manager.retry.rejected", "state" to _state.value)
            return false
        }
        val engine = engines.firstOrNull { it.id == failed.engineId } ?: currentEngine()
        if (engine == null) {
            onFailedAudio(failed)
            return true
        }
        VoicePipelineLog.event("manager.retry", "engine" to engine.id, "seconds" to "%.1f".format(failed.seconds))
        setState(RecognitionState.FINISHING)
        _lastError.value = null
        retryEngine = engine
        val listener = makeListener(engine.id, onPartialOrFinal, onError, onFailedAudio)
        try {
            engine.transcribeRetained(failed.wav, _locale.value, listener)
        } catch (e: Throwable) {
            retryEngine = null
            setState(RecognitionState.IDLE)
            onFailedAudio(FailedAudio(failed.wav, failed.seconds, RecognitionError.UNKNOWN, e.message, engine.id))
        }
        return true
    }

    private fun makeListener(
        engineId: String,
        onPartialOrFinal: (text: String, isFinal: Boolean) -> Unit,
        onError: (RecognitionError, String?) -> Unit,
        onFailedAudio: ((FailedAudio) -> Unit)?,
    ): SpeechRecognitionEngine.Listener {
        // [T-voice-asr-failure-retry-prompt] Audio the engine handed back just
        // before its terminal onError.
        var retained: ByteArray? = null
        return object : SpeechRecognitionEngine.Listener {
            override fun onReadyForSpeech() {
                setState(RecognitionState.RECORDING)
            }

            override fun onPartial(text: String) {
                _recognizedText.value = text
                onPartialOrFinal(text, false)
            }

            override fun onFinal(text: String) {
                // Length only — never the recognised text itself.
                VoicePipelineLog.event("manager.final", "chars" to text.length, "state" to _state.value)
                retained = null
                retryEngine = null
                if (text.isNotEmpty()) _recognizedText.value = text
                onPartialOrFinal(text, true)
                setState(RecognitionState.IDLE)
                resetLevels()
                refreshAvailability()
            }

            override fun onRetainedAudio(wav: ByteArray) {
                retained = wav
            }

            override fun onError(error: RecognitionError, message: String?) {
                VoicePipelineLog.event("manager.error", "kind" to error.name, "state" to _state.value)
                _lastError.value = error
                retryEngine = null
                val audio = retained
                retained = null
                val seconds = audio?.let { VoiceAsrWatchdog.wavSeconds(it) } ?: 0.0
                // [T-voice-asr-failure-retry-prompt] A failure with kept audio
                // goes to the retry prompt instead of the error pill, and the
                // state reaches IDLE only AFTER the caller has queued it — so a
                // send waiting on IDLE sees the failure, not a clean finish.
                val failed = if (audio != null && onFailedAudio != null &&
                    VoiceAsrFailurePolicy.shouldPromptRetry(error, seconds)
                ) FailedAudio(audio, seconds, error, message, engineId) else null
                if (failed != null) onFailedAudio?.invoke(failed)
                setState(RecognitionState.IDLE)
                resetLevels()
                if (failed == null) onError(error, message)
                refreshAvailability()
            }

            override fun onRmsDb(rms: Float) { pushLevel(rms) }
        }
    }

    fun stopRecording() {
        if (_state.value != RecognitionState.RECORDING && _state.value != RecognitionState.STARTING) {
            VoicePipelineLog.event("manager.stop.ignored", "state" to _state.value)
            return
        }
        VoicePipelineLog.event("manager.stop", "state" to _state.value)
        setState(RecognitionState.FINISHING)
        currentEngine()?.stop()
    }

    fun cancelRecording() {
        VoicePipelineLog.event("manager.cancel", "state" to _state.value)
        // [T-voice-asr-failure-retry-prompt] A retry may run on an engine other
        // than the current selection; cancel that one too.
        retryEngine?.let { if (it !== currentEngine()) it.cancel() }
        retryEngine = null
        currentEngine()?.cancel()
        setState(RecognitionState.IDLE)
        _recognizedText.value = ""
    }
}
