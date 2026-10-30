package com.openminis.app.speech

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.widget.Toast
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.voice.VoiceOutputRequest
import com.openminis.app.provider.voice.VoiceProviderFactory
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [T-voice-mic-preempted] Tells "another app holds the microphone" apart from
 * a genuine capture failure. Port of iOS 3a2e3e305 (issue #283).
 *
 * On iOS a mic taken by FaceTime / a call surfaced as "Parse failed:
 * Microphone input unavailable". Android had two versions of the same fault:
 *  - the recorder could not be created or started, and the panel showed the
 *    engine's raw English ("AudioRecord not initialized.", "Microphone
 *    unavailable: <exception>");
 *  - more often, since Android 10's concurrent-capture rules, the recording
 *    STARTS but the system feeds it silence while a call or another recorder
 *    owns the input. Nothing noticed, so the mic appeared to listen until the
 *    silence timeout and the take ended as "nothing recognized".
 *
 * Both now end as [RecognitionError.MIC_IN_USE] with a translated message
 * naming the cause and the fix. The engine detail goes to the log only.
 */
object MicInUse {

    /**
     * A capture failure is blamed on another app only with evidence: a call is
     * active, or the system reports our recording as silenced. Otherwise the
     * cause is unknown (route mid-change, hardware) and blaming another app
     * would be wrong - the same split iOS makes.
     */
    fun classify(callActive: Boolean, silenced: Boolean): RecognitionError =
        if (callActive || silenced) RecognitionError.MIC_IN_USE else RecognitionError.AUDIO_ERROR

    /** The error to report for a capture that could not be created, started or read. */
    fun captureFailure(context: Context, audioSessionId: Int? = null): RecognitionError =
        classify(isCallActive(context), audioSessionId?.let { isSilenced(context, it) } == true)

    /** Audio modes that mean a call or VoIP session owns the audio path. */
    internal fun isCallMode(mode: Int): Boolean = when (mode) {
        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_IN_COMMUNICATION,
        4 /* MODE_CALL_SCREENING, API 30 */,
        5 /* MODE_CALL_REDIRECT, API 33 */,
        6 /* MODE_COMMUNICATION_REDIRECT, API 33 */,
        -> true
        else -> false
    }

    fun isCallActive(context: Context): Boolean = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        isCallMode(am.mode)
    }.getOrDefault(false)

    /**
     * Whether the system is feeding the recording with [audioSessionId]
     * silence because another app holds the input (Android 10+). False when
     * unknown: our own config is not listed yet, or the API is missing.
     */
    fun isSilenced(context: Context, audioSessionId: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            am.activeRecordingConfigurations.any { it.clientAudioSessionId == audioSessionId && it.isClientSilenced }
        }.getOrDefault(false)
    }
}

// ─── Merged from ReadAloudAudioFocus.kt ──────────────────────────────────────────────────

/**
 * [T-readaloud-focus] The single audio-focus holder for read-aloud, shared by
 * the system-engine and provider-TTS paths of every [ReadAloudPlayer].
 *
 * ## Why
 * The system engine never requested focus, so other apps' music/video was not
 * ducked while a reply was read with the device voice (the "can't hear it over
 * a video" report). The provider path requested and abandoned focus PER
 * SENTENCE, so music pumped back up between sentences, and nothing listened
 * for focus loss — a phone call did not stop it.
 *
 * ## Model
 * - Owners: each player is an owner. Focus is requested when the first owner
 *   [acquire]s (right before audio starts — never during synthesis, which
 *   would duck the user's music over silence) and abandoned when the last
 *   owner is gone. Owners are a set, so a duplicate acquire/release never
 *   unbalances anything.
 * - Grace: after an utterance [releaseSoon] keeps the owner for a while; the
 *   next sentence starting inside that window cancels the release, so a run of
 *   sentences holds focus continuously. The window depends on what comes next:
 *   [SYNTH_WAIT_MS] when more sentences are already queued (the next one is
 *   being synthesized — provider TTS measured 3-4 s per sentence on device,
 *   which a short grace turned into a release/re-request per sentence), and
 *   [GRACE_MS] when the queue is empty (end of a run, or a streaming reply
 *   waiting on the model). A gap longer than the window lets the user's music
 *   back up and focus is re-requested when speech resumes.
 * - Immediate release: [releaseNow] for stop / mute / dictation / dispose.
 * - Loss: LOSS and LOSS_TRANSIENT stop every owner (neither engine can pause
 *   mid-utterance) and release; LOSS_TRANSIENT_CAN_DUCK (a navigation prompt)
 *   is ignored and reading continues.
 * - Watchdog: if no new acquire happens for [maxHoldMs], focus is abandoned
 *   anyway. This bounds the worst case — an utterance that never completes —
 *   to minutes of ducked music instead of forever.
 *
 * Main-thread confined, like [ReadAloudPlayer]; focus callbacks are delivered
 * on the main looper.
 */
class ReadAloudAudioFocus internal constructor(
    private val gateway: Gateway,
    private val scheduler: Scheduler,
    private val graceMs: Long = GRACE_MS,
    private val maxHoldMs: Long = MAX_HOLD_MS,
    private val log: (String) -> Unit = { AppLogger.info(TAG, it) },
) {

    /** The platform focus API, behind an interface so tests can fake it. */
    interface Gateway {
        /** Focus-change callback target; set once by the holder. */
        var onFocusChange: (Int) -> Unit

        /** @return an AudioManager.AUDIOFOCUS_REQUEST_* code. */
        fun request(): Int
        fun abandon()
    }

    /** Delayed execution; returns a canceller. */
    fun interface Scheduler {
        fun schedule(delayMs: Long, block: () -> Unit): () -> Unit
    }

    private class Owner(var onLost: () -> Unit) {
        var cancelGrace: (() -> Unit)? = null
    }

    private val owners = LinkedHashMap<Any, Owner>()
    private var cancelWatchdog: (() -> Unit)? = null

    /** True while the platform has granted us focus and we have not abandoned it. */
    var isHeld: Boolean = false
        private set

    val ownerCount: Int get() = owners.size

    init {
        gateway.onFocusChange = ::onFocusChange
    }

    /** Audio is about to start for [owner]; [onLost] is called if focus is taken away. */
    fun acquire(owner: Any, onLost: () -> Unit) {
        val entry = owners.getOrPut(owner) { Owner(onLost) }
        entry.onLost = onLost
        entry.cancelGrace?.invoke()
        entry.cancelGrace = null
        armWatchdog()
        if (isHeld) return
        val rc = gateway.request()
        isHeld = rc == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        log("request MAY_DUCK -> ${if (isHeld) "granted" else "not granted ($rc), playing anyway"} owners=${owners.size}")
    }

    /**
     * An utterance ended; drop [owner] unless another one starts within
     * [delayMs] ([graceMs] by default; [SYNTH_WAIT_MS] when more is queued).
     */
    fun releaseSoon(owner: Any, delayMs: Long = graceMs) {
        val entry = owners[owner] ?: return
        entry.cancelGrace?.invoke()
        entry.cancelGrace = scheduler.schedule(delayMs) {
            if (owners[owner] === entry) {
                entry.cancelGrace = null
                remove(owner, "idle ${delayMs}ms")
            }
        }
    }

    /** Stop, mute, dictation, dispose: drop [owner] now. Idempotent. */
    fun releaseNow(owner: Any) {
        val entry = owners[owner] ?: return
        entry.cancelGrace?.invoke()
        remove(owner, "released")
    }

    private fun remove(owner: Any, reason: String) {
        owners.remove(owner)
        if (owners.isEmpty()) abandon(reason)
    }

    private fun abandon(reason: String) {
        cancelWatchdog?.invoke()
        cancelWatchdog = null
        owners.values.forEach { it.cancelGrace?.invoke() }
        owners.clear()
        if (!isHeld) return
        isHeld = false
        gateway.abandon()
        log("abandon ($reason)")
    }

    private fun armWatchdog() {
        cancelWatchdog?.invoke()
        cancelWatchdog = scheduler.schedule(maxHoldMs) {
            cancelWatchdog = null
            log("watchdog: no speech started for ${maxHoldMs}ms with focus held — forcing release")
            abandon("watchdog")
        }
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                log("focus lost ($change) — stopping read-aloud")
                // Snapshot: onLost -> player.stop() -> releaseNow mutates owners.
                val lost = owners.values.map { it.onLost }
                lost.forEach { runCatching { it() } }
                abandon("lost $change")
            }
            // A short prompt (navigation, notification): keep reading.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> log("transient duck request — continuing")
            else -> Unit
        }
    }

    private class PlatformGateway(context: Context) : Gateway {
        private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        override var onFocusChange: (Int) -> Unit = {}

        // One request object for the holder's lifetime: abandonAudioFocusRequest
        // matches by request, so abandon always targets what was requested.
        private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(ATTRIBUTES)
            .setOnAudioFocusChangeListener({ onFocusChange(it) }, Handler(Looper.getMainLooper()))
            .build()

        override fun request(): Int = audioManager.requestAudioFocus(request)
        override fun abandon() {
            audioManager.abandonAudioFocusRequest(request)
        }
    }

    companion object {
        private const val TAG = "ReadAloudFocus"

        /** Queue empty: bridges streaming gaps; longer gaps release (see class KDoc). */
        const val GRACE_MS = 2_500L

        /**
         * More sentences queued: covers provider synthesis of the next one
         * (measured 3-4 s for Gemini TTS on a Pixel 4a). Bounded so a stalled
         * request does not keep the user's music ducked over silence for long.
         */
        const val SYNTH_WAIT_MS = 8_000L

        /** Worst-case bound on holding focus without any speech starting. */
        const val MAX_HOLD_MS = 5 * 60_000L

        /** Read-aloud playback attributes, shared by the focus request and MediaPlayer. */
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        @Volatile
        private var shared: ReadAloudAudioFocus? = null

        /** Process-wide holder: every player shares one focus request. */
        fun shared(context: Context): ReadAloudAudioFocus =
            shared ?: synchronized(this) {
                shared ?: run {
                    val main = Handler(Looper.getMainLooper())
                    ReadAloudAudioFocus(
                        gateway = PlatformGateway(context.applicationContext),
                        scheduler = { delayMs, block ->
                            val r = Runnable(block)
                            main.postDelayed(r, delayMs)
                            ({ main.removeCallbacks(r) })
                        },
                    )
                }.also { shared = it }
            }
    }
}

// ─── Merged from ReadAloudBoost.kt ──────────────────────────────────────────────────

/**
 * [T-readaloud-volume-boost] Read-aloud volume above 100 %.
 *
 * Both playback paths cap their own gain at 1.0 (MediaPlayer.setVolume,
 * TextToSpeech KEY_PARAM_VOLUME), so a slider value v in 0..2 is split in two:
 * a linear gain min(v, 1) for the player, and for v > 1 the rest as a
 * [LoudnessEnhancer] on the playback's audio session. LoudnessEnhancer is a
 * gain stage followed by a limiter, so the boost raises quiet speech without
 * hard clipping the peaks — a plain sample multiply would.
 *
 * One instance per audio session. Failures (an OEM build without the effect,
 * a session already gone) are logged and leave playback at 100 %: the boost
 * is a nice-to-have, never a reason to lose the audio.
 */
internal class ReadAloudBoost(private val sessionId: Int) {
    private var enhancer: LoudnessEnhancer? = null
    private var failed = false
    private var lastGainMb = 0

    /** Apply the boost part of [volume]; a no-op (effect disabled) at ≤ 100 %. */
    fun apply(volume: Float) {
        val gainMb = boostMillibels(volume)
        if (gainMb == lastGainMb && (gainMb == 0 || enhancer != null)) return
        lastGainMb = gainMb
        if (gainMb <= 0) {
            enhancer?.runCatching { enabled = false }
            return
        }
        if (failed || sessionId == 0) return
        val fx = enhancer ?: runCatching { LoudnessEnhancer(sessionId) }
            .onFailure {
                failed = true
                AppLogger.error(TAG, "LoudnessEnhancer unavailable on session $sessionId, read-aloud stays at 100%: $it")
            }
            .getOrNull()
            ?.also { enhancer = it }
            ?: return
        fx.runCatching {
            setTargetGain(gainMb)
            enabled = true
        }.onFailure { AppLogger.error(TAG, "LoudnessEnhancer gain $gainMb mB rejected: $it") }
    }

    fun release() {
        enhancer?.runCatching { release() }
        enhancer = null
        lastGainMb = 0
    }

    companion object {
        private const val TAG = "ReadAloud"

        /** The part of [volume] the player itself can apply: 0..1. */
        fun linearGain(volume: Float): Float = volume.coerceIn(0f, 1f)

        /** The part above 100 % as a gain in millibels (200 % ≈ 602 mB); 0 at ≤ 100 %. */
        fun boostMillibels(volume: Float): Int =
            if (volume <= 1f || volume.isNaN()) 0 else (2000.0 * log10(volume.toDouble())).roundToInt()
    }
}

// ─── Merged from ReadAloudPlayer.kt ──────────────────────────────────────────────────

/**
 * [T-android-provider-tts-readaloud] Read-aloud playback that routes through
 * the resolved Voice OUTPUT selection, falling back to the on-device engine.
 *
 * ## Why this exists
 * Read-aloud previously constructed a bare [TextToSpeechManager], so it always
 * used the on-device Android engine. The whole provider TTS stack —
 * `VoiceProvider.synthesize()` plus ~723 lines of vendor adapters (MiniMax,
 * Doubao, ElevenLabs, Azure, Gemini, Xunfei, …) — was reachable only from the
 * settings Quick Test sheet, so no provider voice was usable in chat.
 *
 * ## Routing
 * Each utterance asks [com.openminis.app.data.repository.ProviderRepository
 * .resolveVoiceOutputChoice] (added in the P0-1 commit) where to go:
 *  - System choice, no credentials, unsupported vendor, or ANY synthesis
 *    failure → [TextToSpeechManager] (the on-device engine).
 *  - Provider choice → `synthesize()` then play the returned bytes.
 *
 * Fallback is per-utterance and silent by design: a mid-reply network blip
 * degrades to the device voice rather than dropping the sentence.
 *
 * ## Ordering
 * Utterances go through a [Channel] consumed by a single worker coroutine, so
 * sentence N+1 is synthesized/played only after N finishes. Without this,
 * concurrent `synthesize()` calls would return out of order and the reply would
 * be read scrambled. This is the Android stand-in for iOS's
 * `VoiceOutputPlayer` queue (we deliberately do NOT prefetch-ahead yet — that
 * is P1 territory).
 *
 * Text is sanitized via [VoiceTextSanitizer] before either path, so Markdown,
 * URLs and emoji are never pronounced.
 *
 * Not thread-safe beyond the channel; call from the main thread.
 */
class ReadAloudPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val system = TextToSpeechManager().also { it.init(appContext) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = Channel<String>(Channel.UNLIMITED)
    private var worker: Job? = null

    /** Currently playing provider audio, if any — held so [stop] can cancel it. */
    private var player: MediaPlayer? = null

    /** [T-readaloud-volume-boost] Volume above 100 % on [player]'s session. */
    private var playerBoost: ReadAloudBoost? = null

    /**
     * [T-readaloud-volume-boost] The system engine plays on an audio session
     * of our own (KEY_PARAM_SESSION_ID), so the boost above 100 % can sit on
     * it. 0 when no session could be allocated: then no boost, as before.
     */
    private val systemSessionId: Int = runCatching {
        appContext.getSystemService(AudioManager::class.java).generateAudioSessionId()
    }.getOrDefault(0).coerceAtLeast(0)
    private val systemBoost = ReadAloudBoost(systemSessionId)

    /**
     * Completes the in-flight [playBytes] suspension when [stop] tears the
     * MediaPlayer down. Releasing the player means its completion/error
     * callbacks will never fire, so without this the worker coroutine would
     * wait forever and the queue would wedge.
     */
    private var playbackFinisher: (() -> Unit)? = null

    /**
     * [T-android-tts-broken-listener] Latched once the system engine misses a
     * completion callback. Some OEM engines speak fine but never deliver
     * UtteranceProgressListener events; without this every sentence waited out
     * its whole (>=10 s) budget and was then cut off mid-word.
     */
    @Volatile
    private var progressListenerBroken = false

    /** Utterances enqueued but not yet finished; drives [isSpeaking]. */
    private val pending = AtomicInteger(0)

    /**
     * [T-android-tts-player-registry] True when THIS player owns the capsule.
     *
     * Two players can be alive at once (the reply channel, and the selection
     * Read-Aloud player). Both used to write the shared
     * isSpeaking/isSynthesizing/activeModelLabel mirrors unconditionally, so a
     * background player's state flickered the capsule of the foreground one.
     * Only the registry's top may drive the UI.
     */
    private fun ownsCapsule(): Boolean = VoiceOutputState.isActivePlayer(this)

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    /**
     * [T-readaloud-focus] Shared MAY_DUCK focus holder for both engines, held
     * across a whole run of sentences (see [ReadAloudAudioFocus]).
     */
    private val focus = ReadAloudAudioFocus.shared(appContext)

    /** Another app took focus (call, music started): stop, as a user stop would. */
    private val onFocusLost: () -> Unit = {
        AppLogger.info(TAG, "audio focus lost — stopping read-aloud")
        stop()
    }

    /**
     * Rolling buffer for streaming input. Mirrors [TextToSpeechManager]'s own
     * buffer, but lives here so the sentence split happens BEFORE the routing
     * decision (each sentence is independently a provider or system utterance).
     */
    private val sentenceBuffer = StringBuilder()

    /**
     * [T-android-tts-stop-on-capture] Utterances parked by [suspendForCapture]
     * while the microphone is open, replayed by [resumeAfterCapture].
     *
     * Guarded by the same main-thread confinement the rest of this class
     * relies on (see the queue-state note on [stop]) — both entry points are
     * driven from the recognizer's state flow, which is collected on main.
     */
    private val heldForCapture = mutableListOf<String>()

    /** True between [suspendForCapture] and [resumeAfterCapture]/[stop]. */
    @Volatile
    private var capturePaused = false

    private val linkPhrases by lazy {
        VoiceTextSanitizer.LinkPhrases(
            linkToHost = appContext.getString(R.string.voice_link_to_host),
            bareLink = appContext.getString(R.string.voice_bare_link),
        )
    }

    init {
        // [T-android-tts-capsule] Register as the active player so the global
        // capsule's mute/close/stop actions reach this instance, and mirror the
        // live speaking flag into the global state the capsule renders from.
        VoiceOutputState.init(appContext)
        VoiceOutputState.registerPlayer(this)
        worker = scope.launch {
            for (text in queue) {
                try {
                    runCatching { speakOne(text) }
                        .onFailure { AppLogger.error(TAG, "utterance failed: $it") }
                } finally {
                    // [T-readaloud-focus] Every utterance ends here — done,
                    // failed, or cancelled — so focus can never outlive it by
                    // more than the window. The next sentence starting inside
                    // it keeps focus held without a re-request. `pending` still
                    // counts this utterance, so > 1 means another is queued and
                    // only waiting on synthesis: hold longer for it.
                    val moreQueued = pending.get() > 1
                    focus.releaseSoon(
                        this@ReadAloudPlayer,
                        if (moreQueued) ReadAloudAudioFocus.SYNTH_WAIT_MS else ReadAloudAudioFocus.GRACE_MS,
                    )
                }
                // Queue drained → speech is over. Counter rather than
                // Channel.isEmpty, which is experimental API.
                if (pending.decrementAndGet() <= 0) {
                    _isSpeaking.value = false
                    if (ownsCapsule()) VoiceOutputState.isSpeaking.value = false
                }
            }
        }
        // [T-readaloud-volume] Follow the read-aloud volume slider live on the
        // provider path: MediaPlayer.setVolume applies to audio already
        // playing, so the sentence being read changes as the thumb moves. The
        // system engine cannot do this (KEY_PARAM_VOLUME is per utterance);
        // speakViaSystem picks the value up from the next sentence.
        // [T-readaloud-volume-boost] The part above 100 % is an effect on the
        // audio session, so it follows the thumb live on both paths.
        scope.launch {
            VoiceOutputState.volume.collect { v ->
                val gain = ReadAloudBoost.linearGain(v)
                player?.runCatching { setVolume(gain, gain) }
                playerBoost?.apply(v)
                systemBoost.apply(v)
            }
        }
    }

    /**
     * Feed a chunk of streaming assistant output. Complete sentences are
     * enqueued as they appear so the first one starts speaking the moment its
     * terminator arrives, instead of waiting for the whole reply.
     */
    fun appendText(chunk: String) {
        if (chunk.isEmpty()) return
        sentenceBuffer.append(chunk)
        for (sentence in extractCompleteSentences(sentenceBuffer)) {
            enqueue(sentence)
        }
    }

    /** Emit the buffered tail (a fragment with no terminator). Call at stream end. */
    fun flush() {
        if (sentenceBuffer.isEmpty()) return
        // [T-android-tts-scope-align] Terminal extraction first (streaming =
        // false): a held-back unclosed code fence is dropped here rather than
        // spoken raw, and a trailing decimal look-ahead commits. Only the
        // genuine no-terminator remainder goes out as the tail unit.
        for (s in SpeechSentenceSplitter.extractCompleteSentences(sentenceBuffer, streaming = false)) {
            enqueue(s)
        }
        val tail = sentenceBuffer.toString().trim()
        sentenceBuffer.setLength(0)
        if (tail.isNotEmpty()) enqueue(tail)
    }

    /**
     * Speak [text] after stopping anything in flight, one sentence per queued
     * utterance.
     *
     * [T-android-readaloud-split] It used to be ONE utterance. "Read from
     * Start" passes a whole reply, and the system engine refuses input longer
     * than `TextToSpeech.getMaxSpeechInputLength()` (~4000 chars): speak()
     * returns ERROR and nothing plays, so a long reply was silent even with
     * read-replies on. iOS `readReplyFromStart` splits into sentences first
     * and queues each; this does the same through the streaming path's own
     * splitter and [flush], so a menu read and live reply TTS cut text the
     * same way (and provider TTS gets request-sized pieces too).
     */
    fun speak(text: String) {
        stop()
        sentenceBuffer.append(text)
        flush()
    }

    /**
     * Speak [text] AFTER whatever is already queued, without interrupting it.
     *
     * [speak] stops playback first, which is right for a user-initiated "read
     * this" but wrong for an announcement that has to slot into a running
     * narration — a tool announcement must follow the sentence that preceded
     * the tool call and precede whatever the model says next. Mirrors iOS
     * `speakQueued` (AIChatViewModel+SSEStream), which exists for the same
     * reason.
     *
     * Goes through [enqueue], so it inherits the mute / mic-capture / sanitize
     * gating rather than re-deriving it.
     */
    fun speakQueued(text: String) {
        if (text.isBlank()) return
        enqueue(text)
    }

    private fun enqueue(raw: String) {
        // [T-android-tts-capsule] Temporary mute suppresses NEW utterances too
        // (not only current playback) — matching iOS, where `canPlay =
        // isEnabled && !isMuted` gates production. Dropping here rather than at
        // playback keeps a long mute from piling up a backlog that would all
        // blurt out on un-mute.
        if (!VoiceOutputState.canPlay) return
        // [T-android-tts-scope-align] Mic-capture suppression, from iOS
        // canSpeakNow (feedDynamicTTS logs "DROP — read-replies off
        // (capturing)"): while the VAD/recognizer is actively capturing,
        // reply TTS must not play — the speaker feeds straight back into the
        // dictation mic (echo), and capture/playback also fight over the
        // audio route. iOS drops the text outright; mirrored here.
        val recState = SpeechRecognitionManager.state.value
        if (recState == RecognitionState.RECORDING || recState == RecognitionState.STARTING) {
            AppLogger.info(TAG, "enqueue dropped — mic capturing (state=$recState)")
            return
        }
        // Sanitize here, not at playback: a chunk that is pure Markdown (e.g. a
        // fenced code block) sanitizes to empty and must not occupy the queue.
        val clean = VoiceTextSanitizer.sanitize(raw, linkPhrases)
        if (clean.isBlank()) return
        pending.incrementAndGet()
        _isSpeaking.value = true
        if (ownsCapsule()) VoiceOutputState.isSpeaking.value = true
        queue.trySend(clean)
    }

    /**
     * [T-android-tts-stop-on-capture] Silence playback for a dictation turn,
     * KEEPING what has not been spoken yet so [resumeAfterCapture] can pick it
     * back up.
     *
     * Distinct from [stop], which drops the queue: this is a pause, not a
     * cancel. The utterance that is mid-playback is abandoned (there is no
     * seek-within-utterance on either engine, and re-speaking a whole paragraph
     * to recover two words is worse than losing them), but everything still
     * queued behind it is preserved and re-queued on resume.
     */
    fun suspendForCapture() {
        if (capturePaused) return
        capturePaused = true
        // Drain into the holding list rather than onto the floor.
        val held = mutableListOf<String>()
        while (true) {
            val r = queue.tryReceive()
            if (r.isSuccess) held.add(r.getOrThrow()) else break
        }
        // The un-terminated tail counts as pending speech too.
        val tail = sentenceBuffer.toString().trim()
        sentenceBuffer.setLength(0)
        if (tail.isNotEmpty()) held.add(tail)
        heldForCapture.addAll(held)

        playbackFinisher?.invoke()
        releasePlayer()
        system.stop()
        focus.releaseNow(this)
        pending.set(0)
        _isSpeaking.value = false
        if (ownsCapsule()) {
            VoiceOutputState.isSpeaking.value = false
            VoiceOutputState.isSynthesizing.value = false
        }
        AppLogger.info(TAG, "suspended for mic capture — held ${heldForCapture.size} utterance(s)")
    }

    /** Re-queue whatever [suspendForCapture] held back. */
    fun resumeAfterCapture() {
        if (!capturePaused) return
        capturePaused = false
        if (heldForCapture.isEmpty()) return
        val toSpeak = heldForCapture.toList()
        heldForCapture.clear()
        // Back through enqueue so the mute / still-capturing / sanitize gates
        // all re-apply — the user may have muted or started talking again
        // while we were paused.
        toSpeak.forEach { enqueue(it) }
        AppLogger.info(TAG, "resumed after mic capture — re-queued ${toSpeak.size} utterance(s)")
    }

    /** Stop playback and drop everything pending. */
    fun stop() {
        // A real stop supersedes a capture pause: nothing held is still wanted.
        capturePaused = false
        heldForCapture.clear()
        sentenceBuffer.setLength(0)
        while (queue.tryReceive().isSuccess) { /* drain */ }
        // Unblock the worker BEFORE releasing, so the in-flight utterance's
        // suspension completes instead of waiting on callbacks that a released
        // MediaPlayer will never deliver.
        playbackFinisher?.invoke()
        releasePlayer()
        system.stop()
        // [T-readaloud-focus] Stop / mute / disable / dispose: no grace.
        focus.releaseNow(this)
        pending.set(0)
        _isSpeaking.value = false
        if (ownsCapsule()) {
            VoiceOutputState.isSpeaking.value = false
            if (ownsCapsule()) VoiceOutputState.isSynthesizing.value = false
        }
    }

    /** Release both engines. Call from the owner's DisposableEffect. */
    fun shutdown() {
        stop()
        worker?.cancel()
        queue.close()
        scope.cancel()
        system.shutdown()
        systemBoost.release()
        // Deregister by identity: removing a mid-stack player leaves the rest
        // intact, and dropping the top hands the capsule back to the player
        // beneath (typically the reply channel) instead of orphaning it.
        VoiceOutputState.unregisterPlayer(this)
    }

    // -- internals --

    private suspend fun speakOne(text: String) {
        val entry = runCatching {
            (appContext as? MinisApp)?.providerRepository?.resolveVoiceOutputEntry()
        }.getOrNull()

        if (entry != null) {
            val (instance, modelEntry) = entry
            // [T-android-tts-capsule] Surface the ACTUALLY-synthesizing model
            // on the capsule's chip (falls back to the system label below when
            // this path fails over).
            if (ownsCapsule()) {
                VoiceOutputState.activeModelLabel.value =
                    modelEntry.model.displayName.ifBlank { modelEntry.model.id }
            }
            val ok = runCatching { speakViaProvider(instance, modelEntry, text) }
                .getOrElse {
                    AppLogger.error(
                        TAG,
                        "provider TTS failed (model=${modelEntry.model.id} " +
                            "vendor=${instance.providerType}), falling back to system engine: $it",
                    )
                    false
                }
            if (ok) return
        }
        if (ownsCapsule()) VoiceOutputState.activeModelLabel.value = null // system engine
        if (!speakViaSystem(text)) {
            // [T-android-tts-silent-blackhole] Terminal state: the provider path
            // did not produce audio AND the device speech engine is unusable.
            // iOS can't reach this (AVSpeechSynthesizer always exists), which is
            // why read-aloud "just works" there while Android could go silent
            // with no feedback at all — the reported OPPO symptom. Tell the user
            // once instead of swallowing it.
            notifySpeechUnavailable(providerConfigured = entry != null)
        }
    }

    /** One-shot user-visible signal that read-aloud cannot produce sound. */
    private var unavailableNotified = false

    private fun notifySpeechUnavailable(providerConfigured: Boolean) {
        AppLogger.error(
            TAG,
            "read-aloud produced NO audio: providerConfigured=$providerConfigured " +
                "systemEngine=${if (system.initFailed) "init-failed" else "unavailable"}",
        )
        if (unavailableNotified) return
        unavailableNotified = true
        // The worker runs on Dispatchers.Main.immediate, so Toast is safe here.
        Toast.makeText(
            appContext,
            appContext.getString(R.string.read_aloud_unavailable),
            Toast.LENGTH_LONG,
        ).show()
    }

    /** @return true when provider audio was synthesized AND played. */
    private suspend fun speakViaProvider(
        instance: com.openminis.app.data.model.ProviderInstance,
        modelEntry: com.openminis.app.data.model.ModelEntry,
        text: String,
    ): Boolean {
        // [T-android-safemode-lateinit-crash-147] `?.` guards a null
        // Application but NOT an unassigned lateinit — the getter throws.
        // Read-aloud can be driven from a notification action with no
        // Activity, so gate on subsystemsReady() before touching a repository.
        // Each bail-out logs its reason: these gates used to return false
        // silently, which made "no sound" undiagnosable from a user device
        // (the daily log files showed nothing at all).
        val repo = (appContext as? MinisApp)
            ?.takeIf { it.subsystemsReady() }
            ?.providerRepository
            ?: return false.also { AppLogger.error(TAG, "provider TTS skipped: repository unavailable") }
        val apiKey = repo.loadApiKey(instance.id)
            ?: return false.also { AppLogger.error(TAG, "provider TTS skipped: no API key for ${instance.id}") }
        val voice = VoiceProviderFactory.make(instance, apiKey)
            ?: return false.also { AppLogger.error(TAG, "provider TTS skipped: no voice adapter for ${instance.providerType}/${instance.customBaseURL}") }
        if (!voice.supportsVoiceOutput) {
            AppLogger.error(TAG, "provider TTS skipped: ${voice.javaClass.simpleName} does not support output")
            return false
        }

        // [T-android-tts-capsule] Drives the capsule's rotating-arc loading
        // indicator while the network request is in flight (iOS
        // voicePlayer.isSynthesizing).
        if (ownsCapsule()) VoiceOutputState.isSynthesizing.value = true
        val data = try {
            withContext(Dispatchers.IO) {
                voice.synthesize(
                    // The entry id doubles as the voice id — template voices carry
                    // the voice id as the model id (same convention QuickTestSheet
                    // relies on, iOS 0a52bdbf).
                    VoiceOutputRequest(
                        input = text,
                        model = modelEntry.model.id,
                        voice = modelEntry.model.id,
                        resolvedModel = modelEntry.model,
                    ),
                )
            }
        } finally {
            VoiceOutputState.isSynthesizing.value = false
        }
        if (data.isEmpty()) {
            AppLogger.error(TAG, "provider TTS returned empty audio (model=${modelEntry.model.id})")
            return false
        }
        playBytes(data)
        return true
    }

    /**
     * Write [data] to a cache file and play it to completion. Same approach as
     * QuickTestSheet — MediaPlayer needs a file/descriptor, not a byte array.
     */
    private suspend fun playBytes(data: ByteArray) {
        val file = withContext(Dispatchers.IO) {
            File(appContext.cacheDir, "readaloud_tts.audio").apply { writeBytes(data) }
        }
        suspendCancellableCoroutine { cont ->
            val mp = MediaPlayer()
            player = mp
            var resumed = false
            fun finish() {
                if (resumed) return
                resumed = true
                playbackFinisher = null
                releasePlayer()
                if (cont.isActive) cont.resume(Unit)
            }
            // Let stop()/shutdown() complete this suspension after releasing
            // the player, since the callbacks below can no longer fire.
            playbackFinisher = ::finish
            runCatching {
                // [T-android-tts-silent-blackhole] Explicit attributes + audio
                // focus. iOS routes playback through AudioSessionCoordinator;
                // Android had neither, and some OEM ROMs (ColorOS among them)
                // will silently mute focus-less media playback. Focus denial is
                // logged but non-fatal — muting is the exception, not the rule.
                // [T-readaloud-focus] Focus is now the shared run-long holder,
                // acquired just before start() below.
                mp.setAudioAttributes(ReadAloudAudioFocus.ATTRIBUTES)
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener { finish() }
                mp.setOnErrorListener { _, what, extra ->
                    AppLogger.error(TAG, "MediaPlayer error what=$what extra=$extra")
                    finish()
                    true
                }
                mp.prepare()
                // [T-readaloud-volume] Same read-aloud volume as the system
                // path; the collector in init keeps it in step while playing.
                // [T-readaloud-volume-boost] Up to 100 % on the player, the
                // rest as a LoudnessEnhancer on its session.
                val volume = VoiceOutputState.volume.value
                val gain = ReadAloudBoost.linearGain(volume)
                mp.setVolume(gain, gain)
                playerBoost = ReadAloudBoost(mp.audioSessionId).also { it.apply(volume) }
                logVolume("provider", volume)
                // [T-android-tts-rom-compat] start() FIRST, speed second, each
                // in its own guard. The previous order relied on
                // setPlaybackParams-on-prepared implicitly starting playback —
                // documented AOSP behaviour, but some OEM MediaPlayer builds
                // throw IllegalStateException there, and the failure landed in
                // the OUTER runCatching which skipped the utterance entirely.
                // Now a ROM that rejects the speed change still plays at 1×;
                // only the speed is lost, never the audio.
                focus.acquire(this@ReadAloudPlayer, onFocusLost)
                mp.start()
                val speed = VoiceOutputState.speed.value
                if (speed != 1.0f) {
                    runCatching {
                        mp.playbackParams = mp.playbackParams.setSpeed(speed)
                    }.onFailure {
                        AppLogger.error(TAG, "playback speed $speed rejected by this ROM's MediaPlayer, playing at 1×: $it")
                    }
                }
            }.onFailure {
                AppLogger.error(TAG, "MediaPlayer setup failed: $it")
                finish()
            }
            cont.invokeOnCancellation { releasePlayer() }
        }
    }

    /**
     * Speak via the on-device engine and wait for it to finish, so the queue
     * stays ordered. [TextToSpeechManager.isSpeaking] flips false on the last
     * utterance's onDone.
     *
     * @return false when the engine is unusable (init failed / never bound) —
     *   the caller surfaces that instead of pretending the sentence was spoken.
     */
    private suspend fun speakViaSystem(text: String): Boolean {
        // [T-android-tts-silent-blackhole] Engine binding is async; speaking
        // before it settles used to drop the text on the floor. Wait (bounded)
        // for a verdict first.
        if (!system.awaitReady()) return false
        // [T-android-tts-capsule] Apply the capsule's speed each utterance —
        // the property setter forwards to tts.setSpeechRate, so mid-reply
        // speed changes take effect from the next sentence.
        system.speechRate = VoiceOutputState.speed.value
        // [T-android-system-voice-catalog] Apply the picked system voice (null
        // = engine default / auto language), and surface its label on the
        // capsule chip — matching iOS, whose chip shows "Tingting (Chinese)"
        // rather than a generic "System TTS" once a voice is chosen.
        system.preferredVoiceName = VoiceOutputState.systemVoiceName.value
        // [T-readaloud-volume] Per-utterance gain via KEY_PARAM_VOLUME
        // (buildSpeechParams), capped at 1.0 by the engine.
        // [T-readaloud-volume-boost] Above 100 % the utterance plays on our own
        // session, which carries the LoudnessEnhancer for the rest.
        val volume = VoiceOutputState.volume.value
        system.speechVolume = ReadAloudBoost.linearGain(volume)
        system.audioSessionId = systemSessionId
        systemBoost.apply(volume)
        logVolume("system", volume)
        if (ownsCapsule()) {
            VoiceOutputState.activeModelLabel.value = VoiceOutputState.systemVoiceLabel.value
        }
        // [T-readaloud-focus] The system engine now ducks other apps too.
        focus.acquire(this, onFocusLost)
        system.speak(text)
        // [T-android-tts-rom-compat] speak() may have been REJECTED by the
        // engine (missing language data on AOSP/OEM engines) — the manager
        // clears isSpeaking in that case, so the loop below exits immediately
        // and we report failure instead of pretending the text was spoken.
        if (!system.isSpeaking.value) return false
        // Poll rather than plumb a callback through: utterances are short and
        // this keeps TextToSpeechManager's public surface unchanged.
        //
        // [T-android-tts-rom-compat] BOUNDED. Some OEM engines accept the
        // utterance but never deliver UtteranceProgressListener callbacks
        // (fragmented listener support), which left this loop spinning forever
        // and wedged the whole read-aloud queue. Budget scales with length —
        // generous enough for 0.5× zh speech — and on expiry we log and move
        // on rather than freeze the worker for the rest of the process.
        // [T-android-tts-broken-listener] Once this engine has proved its
        // progress listener never fires, stop paying the budget on EVERY
        // sentence. Estimate the duration instead and let the utterance run to
        // completion — crucially without stop(), which is what used to cut
        // words in half.
        if (progressListenerBroken) {
            kotlinx.coroutines.delay(estimatedSpeechMs(text))
            return true
        }
        val budgetMs = (text.length * 600L + 10_000L).coerceAtMost(180_000L)
        val deadline = System.currentTimeMillis() + budgetMs
        var timedOut = false
        while (system.isSpeaking.value) {
            if (System.currentTimeMillis() > deadline) {
                AppLogger.error(
                    TAG,
                    "system TTS completion never reported (len=${text.length}, " +
                        "waited ${budgetMs}ms) — engine's progress listener is broken; moving on",
                )
                timedOut = true
                break
            }
            kotlinx.coroutines.delay(POLL_MS)
        }
        if (timedOut) {
            // Do NOT system.stop() here. The engine is very likely still
            // speaking correctly — only its CALLBACKS are broken — so stopping
            // truncates the sentence mid-word. The next utterance uses
            // QUEUE_FLUSH anyway, so ordering is preserved without it.
            //
            // Report failure so the "read-aloud unavailable" path runs ONCE,
            // and latch the flag so subsequent sentences take the estimated
            // -duration path above instead of burning >=10 s of dead air each.
            progressListenerBroken = true
            return false
        }
        return true
    }

    /**
     * Rough utterance duration for the broken-listener path. Deliberately on
     * the short side of real speech: overlapping slightly is far less bad than
     * the multi-second dead air the full budget produced.
     */
    private fun estimatedSpeechMs(text: String): Long =
        (text.length * 90L + 400L).coerceIn(600L, 30_000L)

    /** Last (path, volume) logged — one line per change, not per sentence. */
    private var lastVolumeLog: Pair<String, Float>? = null

    private fun logVolume(path: String, volume: Float) {
        if (lastVolumeLog == path to volume) return
        lastVolumeLog = path to volume
        AppLogger.info(TAG, "read-aloud volume=$volume path=$path")
    }

    private fun releasePlayer() {
        playerBoost?.release()
        playerBoost = null
        player?.runCatching {
            if (isPlaying) stop()
            release()
        }
        player = null
    }

    /**
     * [T-android-tts-intranumber-guard] Delegates to the shared
     * [SpeechSentenceSplitter] so this path and [TextToSpeechManager] apply the
     * same terminator set AND the same intra-number guards ("3.14" is never cut
     * into "3." + "14").
     */
    private fun extractCompleteSentences(buffer: StringBuilder): List<String> =
        SpeechSentenceSplitter.extractCompleteSentences(buffer)

    companion object {
        private const val TAG = "ReadAloud"
        private const val POLL_MS = 80L
    }
}

// ─── Merged from RecognizerErrorCodes.kt ──────────────────────────────────────────────────

/**
 * [T-431-error-codes] One table for the `SpeechRecognizer.ERROR_*` codes, shared
 * by the chat voice input ([SystemSpeechRecognitionEngine]) and the
 * `android-speech` CLI.
 *
 * The codes from 10 up need API 31/33 constants, so they are written as
 * literals. The chat engine used to label them one slot off (11 as
 * LANGUAGE_NOT_SUPPORTED, 12 as LANGUAGE_UNAVAILABLE). The SDK
 * (`android/speech/SpeechRecognizer.java`, android-34) defines:
 *   10 ERROR_TOO_MANY_REQUESTS, 11 ERROR_SERVER_DISCONNECTED,
 *   12 ERROR_LANGUAGE_NOT_SUPPORTED, 13 ERROR_LANGUAGE_UNAVAILABLE,
 *   14 ERROR_CANNOT_CHECK_SUPPORT, 15 ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS.
 * With the old labels 13 fell through to UNKNOWN, so the on-device fallback
 * never fired for it, and a dropped service (11) was reported as a language
 * problem.
 */
object RecognizerErrorCodes {
    const val TOO_MANY_REQUESTS = 10
    const val SERVER_DISCONNECTED = 11
    const val LANGUAGE_NOT_SUPPORTED = 12
    const val LANGUAGE_UNAVAILABLE = 13
    const val CANNOT_CHECK_SUPPORT = 14
    const val CANNOT_LISTEN_TO_DOWNLOAD_EVENTS = 15

    /** The SDK constant name, e.g. "ERROR_CLIENT". */
    fun name(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
        SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
        LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
        LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
        CANNOT_CHECK_SUPPORT -> "ERROR_CANNOT_CHECK_SUPPORT"
        CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"
        else -> "ERROR_UNKNOWN_$code"
    }

    /** True for the two language-pack codes (12, 13). */
    fun isLanguageError(code: Int): Boolean =
        code == LANGUAGE_NOT_SUPPORTED || code == LANGUAGE_UNAVAILABLE

    /** Category the chat voice input acts on. */
    fun classify(code: Int): RecognitionError = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RecognitionError.NO_MATCH
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> RecognitionError.NETWORK
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> RecognitionError.PERMISSION_DENIED
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> RecognitionError.RECOGNIZER_BUSY
        SpeechRecognizer.ERROR_AUDIO -> RecognitionError.AUDIO_ERROR
        SpeechRecognizer.ERROR_CLIENT -> RecognitionError.OEM_NO_SERVICE
        SpeechRecognizer.ERROR_SERVER -> RecognitionError.NETWORK
        LANGUAGE_NOT_SUPPORTED, LANGUAGE_UNAVAILABLE -> RecognitionError.LANGUAGE_UNSUPPORTED
        else -> RecognitionError.UNKNOWN
    }

    /** Short English description, shown by the chat panel and the CLI. */
    fun describe(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client error: the system could not connect Minis to a speech recognition service"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "RECORD_AUDIO required"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
        TOO_MANY_REQUESTS -> "Too many recognition requests - the system recognizer service is saturated"
        SERVER_DISCONNECTED -> "The recognition service disconnected"
        LANGUAGE_NOT_SUPPORTED -> "Language not supported by the recognizer"
        LANGUAGE_UNAVAILABLE -> "Language pack unavailable - download it in the system speech settings"
        CANNOT_CHECK_SUPPORT -> "The recognizer cannot report which languages it supports"
        else -> "Unknown error ($code)"
    }
}

// ─── Merged from RecognizerFallback.kt ──────────────────────────────────────────────────

/**
 * [T-431-fallback] When one kind of system recognizer fails in a way the other
 * kind may not, try the other kind once. Shared by the chat voice input
 * ([SystemSpeechRecognitionEngine]) and the `android-speech` CLI.
 *
 * - The on-device recognizer (SODA on Pixels) answers 12/13 when it has no
 *   language pack for the locale; the regular, usually cloud, recognizer
 *   often handles the same locale (a zh-CN Pixel 4a).
 * - ERROR_CLIENT / ERROR_SERVER_DISCONNECTED mean Android could not keep a
 *   connection to that recognizer's service. The other kind is bound to a
 *   different service, so it is worth one try (GH #431: a vivo ROM whose
 *   default service refuses third-party apps).
 *
 * The chat voice input only ever falls back FROM on-device: it honours the
 * user's online/offline choice and starts on-device only when asked to. The
 * CLI has no stored choice, so it falls back in both directions.
 */
object RecognizerFallback {
    enum class Kind(val label: String) {
        REGULAR("regular"),
        ON_DEVICE("on_device"),
    }

    /** Errors after which the other kind of recognizer is worth one try. */
    fun shouldSwitch(code: Int): Boolean =
        code == SpeechRecognizer.ERROR_CLIENT ||
            code == RecognizerErrorCodes.SERVER_DISCONNECTED ||
            RecognizerErrorCodes.isLanguageError(code)

    /**
     * The recognizers to try, in order: the preferred one, then the other one
     * when it exists. On-device needs API 31 and a configured service.
     */
    fun plan(preferOnDevice: Boolean, sdkInt: Int, onDeviceAvailable: Boolean?): List<Kind> {
        val onDeviceUsable = sdkInt >= 31 && onDeviceAvailable == true
        return when {
            preferOnDevice && onDeviceUsable -> listOf(Kind.ON_DEVICE, Kind.REGULAR)
            onDeviceUsable -> listOf(Kind.REGULAR, Kind.ON_DEVICE)
            else -> listOf(Kind.REGULAR)
        }
    }

    /** The next recognizer to try after [code] from attempt [index] of [plan], or null. */
    fun next(plan: List<Kind>, index: Int, code: Int): Kind? =
        if (shouldSwitch(code)) plan.getOrNull(index + 1) else null
}

// ─── Merged from RecognizerLocales.kt ──────────────────────────────────────────────────

/**
 * [T-431] Locale helpers shared by the chat voice input
 * ([SystemSpeechRecognitionEngine]) and the `android-speech` CLI, so both send
 * the recognizer the same tag and offer the same fallback list.
 */
object RecognizerLocales {
    /**
     * Curated list the chat voice input offers when the system refuses to
     * enumerate (the device default goes first, see [SystemSpeechRecognitionEngine]).
     * It covers the major languages Google and most OEM recognizers support; it
     * is NOT a report of what this device's recognizer can do.
     */
    val BUILTIN_TAGS: List<String> = listOf(
        "en-US", "zh-CN", "zh-TW", "ja-JP", "ko-KR", "es-ES", "fr-FR", "de-DE", "it-IT",
        "pt-BR", "ru-RU", "ar-SA", "hi-IN", "id-ID", "th-TH", "vi-VN", "tr-TR",
    )

    /**
     * [T-android-asr-script-subtag] Strip the SCRIPT subtag from a language tag
     * before handing it to the recognizer.
     *
     * `Locale.getDefault()` on a Simplified-Chinese device is `zh-Hans-CN`, and
     * `toLanguageTag()` faithfully returns "zh-Hans-CN". Google's recognizer
     * matches its language packs on plain `language-REGION` tags, so the script
     * subtag makes it report "Locale not supported" / "Failed to get language
     * pack of required locale" and fail the session with ERROR_LANGUAGE_
     * NOT_SUPPORTED (12) — the mic opens and immediately closes. Observed on a
     * Pixel 4a whose system locale is zh-Hans-CN.
     *
     * Reducing to language(+region) keeps the meaningful part ("zh-CN") and is
     * safe for every other locale: tags without a script subtag are unchanged.
     */
    fun recognizerTag(locale: Locale): String {
        val lang = locale.language
        var region = locale.country
        // A script-only locale carries no region ("zh-Hans"), and a bare "zh"
        // is just as unresolvable to the recognizer as "zh-Hans" — it needs a
        // concrete language pack. Derive the region from the script, which is
        // exactly the information the script subtag encodes.
        if (region.isNullOrEmpty() && lang == "zh") {
            region = if (locale.script == "Hant") "TW" else "CN"
        }
        // Last resort for any other script-only/region-less locale: ask ICU to
        // fill in the likely region (zh-Hans -> zh-Hans-CN, etc.).
        if (region.isNullOrEmpty()) {
            region = runCatching { Locale.Builder().setLocale(locale).build() }
                .getOrNull()?.country.orEmpty()
        }
        return if (region.isEmpty()) lang else "$lang-$region"
    }

    /** [recognizerTag] for a user-typed tag ("zh_Hans_CN", "zh-Hans-CN", "en"). */
    fun recognizerTag(tag: String): String =
        recognizerTag(Locale.forLanguageTag(tag.trim().replace('_', '-')))
}

// ─── Merged from ReentrantSafeRelease.kt ──────────────────────────────────────────────────

/**
 * [T-android-vad-reentrant-release] Owns a native object that must never be
 * freed while a call into it is still on the stack — including a call that
 * re-enters from its own callback.
 *
 * [VoiceActivityDetector] needs both guarantees:
 *  - Cross-thread (T-android-vad, 91c7b2944): stop() on another thread must
 *    wait for an in-flight `processAudio` instead of freeing underneath it.
 *    A plain lock held across the call does that.
 *  - Same-thread re-entrancy: the library fires `onVoiceEnd` from INSIDE
 *    `RealTimeCutVAD::algorithm()`, and a listener that stops the take there
 *    (ProviderSpeechRecognitionEngine, on a silence close) reaches
 *    `release()` on the capture thread that already holds the lock. JVM
 *    monitors are re-entrant, so the lock did not stop it:
 *    `destroyVADInstance` freed the object, the callback returned into
 *    `algorithm()`, and its next read of a member through the freed `this`
 *    faulted — SIGSEGV, fault addr 0x38 at
 *    libRealtimeCutVadLibrary.so `RealTimeCutVAD::algorithm+0x2a4`.
 *
 * [tearDown] reached from inside [call] therefore only detaches the object;
 * [call] frees it after the native call has fully returned. Holding [lock]
 * while inside [call] is what makes [inCall] unambiguous: another thread in
 * [tearDown] blocks on the lock, so seeing `inCall == true` there means this
 * is the calling thread re-entering.
 */
internal class ReentrantSafeRelease<T : Any>(private val release: (T) -> Unit) {
    private val lock = Any()
    private var live: T? = null // guarded by lock
    private var inCall = false // guarded by lock
    private var deferred: T? = null // guarded by lock

    fun attach(value: T) = synchronized(lock) { live = value }

    /**
     * Run [block] on the live object under the lock. Returns false when the
     * object was already torn down (block not run) or was torn down during
     * [block] by a re-entrant [tearDown]; in that case it is freed here, after
     * [block] returns.
     */
    fun call(block: (T) -> Unit): Boolean = synchronized(lock) {
        val target = live ?: return false
        inCall = true
        try {
            block(target)
        } finally {
            inCall = false
            deferred?.let { d ->
                deferred = null
                runCatching { release(d) }
            }
        }
        live != null
    }

    /** Detach and free — or, from inside [call], detach now and free when it returns. */
    fun tearDown() {
        synchronized(lock) {
            val target = live ?: return
            live = null
            if (inCall) {
                deferred = target
            } else {
                runCatching { release(target) }
            }
        }
    }
}

// ─── Merged from SpeechSentenceSplitter.kt ──────────────────────────────────────────────────

/**
 * [T-android-tts-intranumber-guard] Streaming sentence extraction for the TTS
 * paths. Shared by [ReadAloudPlayer] and [TextToSpeechManager] so the two can
 * never drift — they previously carried byte-identical copies of this loop and
 * a fix to one would have silently missed the other.
 *
 * Port of the guards in iOS `AIChatViewModel+SSEStream.extractSentencesStatic`
 * (T-tts-decimal-split).
 */
object SpeechSentenceSplitter {

    /** Terminator set — the exact one iOS uses in `extractNewSentences`. */
    val SENTENCE_ENDERS: Set<Char> =
        charArrayOf('。', '！', '？', '.', '!', '?', '\n').toSet()

    /**
     * [T-android-tts-scope-align] Clause-pause marks — iOS `speechPauses`.
     * Not terminators on their own; they only become cut points when a run
     * exceeds [SOFT_LIMIT] without a real ender, so an ender-less clause
     * stream (long Chinese enumerations, comma-chained English) still speaks
     * in bounded units instead of accumulating into one giant utterance.
     */
    private val SPEECH_PAUSES: Set<Char> =
        charArrayOf('，', ',', '、', '：', ':').toSet()

    /** Force-cut threshold for pause-mark fallback — iOS `speechSoftLimit`. */
    private const val SOFT_LIMIT = 60

    /**
     * A `.` or `,` flanked by digits on BOTH sides is an intra-number separator
     * (decimal point "3.14" / "$1.5", European decimal comma "€28,98", or
     * thousands grouping "1,000,000"), NOT a sentence boundary. Splitting there
     * mangles the number into "3." + "14" and the TTS reads it wrong.
     *
     * BOTH neighbours must be digits, so a real sentence end after a number
     * ("He scored 5. Then…") still cuts. Uses [Char.isDigit] so ASCII and
     * full-width digits both count.
     */
    fun isIntraNumberSeparator(text: CharSequence, idx: Int): Boolean {
        val ch = text[idx]
        if (ch != '.' && ch != ',') return false
        if (idx <= 0 || idx + 1 >= text.length) return false
        return text[idx - 1].isDigit() && text[idx + 1].isDigit()
    }

    /**
     * Scan [buffer] for sentence terminators, returning each trimmed sentence
     * and removing the consumed prefix.
     *
     * Two number-aware guards, both from iOS:
     *  1. [isIntraNumberSeparator] positions are skipped outright.
     *  2. Streaming look-ahead — a `.`/`,` that trails a digit at the CURRENT
     *     END of the buffer might be a decimal point whose fractional part
     *     hasn't streamed yet ("price 28." with "98" still in flight).
     *     Committing it now would speak "28." early and split the number, so we
     *     stop scanning WITHOUT consuming it; the next delta reveals whether a
     *     digit (→ intra-number, skip) or a non-digit (→ real boundary) follows.
     *     Only applies at the very last char, so fully-arrived text is never
     *     delayed. Set [streaming] = false to disable this (e.g. a final flush,
     *     where no more text is coming and "28." IS the end).
     */
    private fun isFence(buffer: CharSequence, idx: Int): Boolean =
        idx + 2 < buffer.length && buffer[idx] == '`' &&
            buffer[idx + 1] == '`' && buffer[idx + 2] == '`'

    fun extractCompleteSentences(
        buffer: StringBuilder,
        streaming: Boolean = true,
    ): List<String> {
        val sentences = mutableListOf<String>()
        var lastBoundary = 0
        var lastPause = -1
        var i = 0
        while (i < buffer.length) {
            // [T-android-tts-scope-align] Fenced code blocks are EXCLUDED at
            // the splitter, matching iOS extractSentencesStatic. Previously
            // fences reached the per-sentence sanitizer already CHOPPED at the
            // '\n' / '.' enders inside the code, so its ```…``` regex never
            // saw a complete pair and code fragments were read aloud — iOS
            // has never spoken fenced code. A complete block is skipped in one
            // go (text before it flushes as a unit); an UNCLOSED fence stops
            // the scan without consuming it, waiting for a later delta — same
            // contract as the streaming decimal-point look-ahead below.
            if (isFence(buffer, i)) {
                var j = i + 3
                var foundClose = false
                while (j < buffer.length) {
                    if (isFence(buffer, j)) { foundClose = true; break }
                    j++
                }
                if (!foundClose) {
                    if (!streaming) {
                        // Terminal flush: nothing more is coming; drop the
                        // malformed open fence and everything after it.
                        val s = buffer.substring(lastBoundary, i).trim()
                        if (s.isNotEmpty()) sentences.add(s)
                        lastBoundary = buffer.length
                    }
                    break
                }
                val s = buffer.substring(lastBoundary, i).trim()
                if (s.isNotEmpty()) sentences.add(s)
                i = j + 3
                lastBoundary = i
                lastPause = -1
                continue
            }
            val ch = buffer[i]
            if (isIntraNumberSeparator(buffer, i)) {
                i++
                continue
            }
            if (streaming &&
                i == buffer.length - 1 &&
                (ch == '.' || ch == ',') &&
                i > 0 && buffer[i - 1].isDigit()
            ) {
                // Might be a decimal point mid-stream — wait for the next delta.
                break
            }
            if (ch in SPEECH_PAUSES) lastPause = i
            val runLen = i - lastBoundary + 1
            if (ch in SENTENCE_ENDERS) {
                val s = buffer.substring(lastBoundary, i + 1).trim()
                if (s.isNotEmpty()) sentences.add(s)
                lastBoundary = i + 1
                lastPause = -1
            } else if (runLen >= SOFT_LIMIT && lastPause >= lastBoundary) {
                // [T-android-tts-scope-align] Pause-mark force-cut (iOS
                // softLimit tier): an ender-less run past the limit cuts at
                // the last clause pause so it speaks as a bounded unit.
                val s = buffer.substring(lastBoundary, lastPause + 1).trim()
                if (s.isNotEmpty()) sentences.add(s)
                lastBoundary = lastPause + 1
                lastPause = -1
            }
            i++
        }
        if (lastBoundary > 0) buffer.delete(0, lastBoundary)
        return sentences
    }
}

// ─── Merged from SystemTtsVoiceCatalog.kt ──────────────────────────────────────────────────

/**
 * [T-android-system-voice-catalog] Enumerates the device TTS engine's voices —
 * the Android counterpart of iOS `SystemVoiceCatalog`, which is what lets the
 * iOS voice-output picker list "Tingting (Chinese)…, Anna (German)…" as
 * individually selectable rows while Android only offered a single generic
 * "System Voice (Auto)".
 *
 * Android's [Voice] API exposes no human display name (names are technical,
 * e.g. "cmn-cn-x-ssa-local"), so rows are labelled from the voice's locale
 * plus a variant marker derived from the technical name — "中文 (中国) · ssa"
 * — with a network tag where the voice is cloud-only. Not as pretty as iOS's
 * curated names, but honest about what the engine actually reports.
 *
 * Enumeration requires a BOUND engine, so the first call spins up a throwaway
 * TextToSpeech, waits for init (bounded), snapshots `voices`, and shuts it
 * down; the result is cached for the process (the installed voice set doesn't
 * change under a running app in practice).
 */
object SystemTtsVoiceCatalog {

    data class SystemVoice(
        /** Engine voice name — the stable id passed to TextToSpeech.setVoice. */
        val name: String,
        /** Human-facing label: localized locale name + variant + network tag. */
        val label: String,
        val locale: Locale,
        val networkRequired: Boolean,
    )

    private const val TAG = "SystemTtsVoiceCatalog"
    private const val INIT_TIMEOUT_MS = 4_000L

    @Volatile
    private var cache: List<SystemVoice>? = null
    private val lock = Mutex()

    suspend fun voices(context: Context): List<SystemVoice> {
        cache?.let { return it }
        return lock.withLock {
            cache?.let { return it }
            val listed = enumerate(context.applicationContext)
            // [T-android-tts-catalog-empty-cache] Never cache an EMPTY result.
            // Enumeration fails transiently — cold boot before the TTS service
            // binds, or the 4 s timeout expiring on a slow OEM engine — and
            // caching that for the process left the voice picker permanently
            // blank, including after the user installed a TTS engine from the
            // prompt. Only a non-empty listing is a real answer worth keeping;
            // an empty one just means "ask again next time".
            if (listed.isNotEmpty()) cache = listed
            listed
        }
    }

    private suspend fun enumerate(context: Context): List<SystemVoice> {
        val ready = CompletableDeferred<Boolean>()
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            ready.complete(status == TextToSpeech.SUCCESS)
        }
        val ok = kotlinx.coroutines.withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } ?: false
        val raw: Set<Voice> = if (ok) {
            // voices can THROW on some engines (documented fragmentation).
            runCatching { tts.voices ?: emptySet() }.getOrElse { emptySet() }
        } else emptySet()
        runCatching { tts.shutdown() }
        if (raw.isEmpty()) {
            AppLogger.info(TAG, "no voices enumerated (initOk=$ok)")
            return emptyList()
        }

        val uiLocale = Locale.getDefault()
        val mapped = raw
            .filterNot { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true }
            .map { v ->
                val variant = v.name.substringAfterLast("-x-", "")
                    .substringBefore("-").ifBlank { null }
                val net = v.isNetworkConnectionRequired
                val base = v.locale.getDisplayName(uiLocale).ifBlank { v.locale.toLanguageTag() }
                val label = buildString {
                    append(base)
                    if (variant != null) append(" · ").append(variant)
                    if (net) append(" ☁")
                }
                SystemVoice(name = v.name, label = label, locale = v.locale, networkRequired = net)
            }
            // UI language's voices first, then other Chinese/English, then rest;
            // stable alphabetical inside each tier so the list is reproducible.
            .sortedWith(
                compareBy(
                    { tier(it.locale, uiLocale) },
                    { it.locale.toLanguageTag() },
                    { it.label },
                ),
            )
        AppLogger.info(TAG, "enumerated ${mapped.size} voices (raw=${raw.size})")
        return mapped
    }

    private fun tier(l: Locale, ui: Locale): Int = when {
        l.language == ui.language -> 0
        l.language == "zh" || l.language == "en" -> 1
        else -> 2
    }
}

// ─── Merged from ToolSpeech.kt ──────────────────────────────────────────────────

/**
 * Spoken announcement for a tool call — "正在执行脚本:安装依赖" /
 * "Executing script: install deps".
 *
 * Port of iOS `AIChatViewModel.makeToolSpeech`
 * (Agent/Chat/AIChatViewModel+SSEStream.swift), including its phrasing, its
 * 40-character clip and its per-tool argument fallbacks — a listener who
 * switches platforms should hear the same sentence.
 *
 * Deliberately NOT the on-screen preview text ("Executing…", "Reading /x…").
 * Those are terse because the screen shows the arguments next to them; spoken
 * aloud with no visual context they say almost nothing, so this builds a
 * natural cue instead.
 *
 * Pure and framework-free so it is unit-testable — the announcement is the part
 * that has to stay aligned across platforms, and it should not need a device to
 * verify.
 */
object ToolSpeech {

    /** Longest detail we will speak; beyond this the tail becomes "…". */
    private const val DETAIL_MAX = 40

    private val HAN = Regex("\\p{IsHan}")

    /**
     * Build the announcement for a tool call.
     *
     * @param name tool name as the model called it (`shell_execute`, `file_read`…)
     * @param argsJson raw JSON arguments; malformed or partial JSON is tolerated
     *   and simply yields no argument fallback.
     * @param title the model's own `tool_title` summary, when it supplied one.
     */
    fun announcement(name: String, argsJson: String?, title: String?): String {
        val args = parseArgs(argsJson)
        // Language follows the model's own summary, matching iOS: the title is
        // written in the user's language (the tool schema asks for it), so it
        // is a better signal than the device locale — a Chinese user talking to
        // an English-answering model should hear one language, not a mix.
        val zh = title?.let { HAN.containsMatchIn(it) } ?: false
        val detail = clip(title)

        return when (name) {
            "shell_execute" -> {
                val d = detail ?: clip(args?.optString("command")) ?: ""
                if (zh) "正在执行脚本:$d" else "Executing script: $d"
            }
            "file_read" -> {
                val d = detail ?: fileName(args?.optString("path"), zh)
                if (zh) "正在读取文件:$d" else "Reading file: $d"
            }
            "file_write" -> {
                val d = detail ?: fileName(args?.optString("path"), zh)
                if (zh) "正在写入文件:$d" else "Writing file: $d"
            }
            "file_edit" -> {
                val d = detail ?: fileName(args?.optString("path"), zh)
                if (zh) "正在编辑文件:$d" else "Editing file: $d"
            }
            "browser_use" -> {
                val d = detail ?: clip(args?.optString("action")) ?: ""
                if (zh) "正在操作浏览器:$d" else "Using browser: $d"
            }
            "read_image" -> {
                val d = detail ?: fileName(args?.optString("path"), zh)
                if (zh) "正在读取图片:$d" else "Reading image: $d"
            }
            else -> {
                val d = detail ?: name
                if (zh) "正在执行:$d" else "Running: $d"
            }
        }
    }

    /**
     * Tool args arrive as the raw JSON the model streamed, which can be
     * incomplete when a call is announced mid-stream. A parse failure is not an
     * error here — it just means no argument fallback is available and the
     * tool name carries the announcement.
     */
    private fun parseArgs(argsJson: String?): JSONObject? {
        if (argsJson.isNullOrBlank()) return null
        return runCatching { JSONObject(argsJson) }.getOrNull()
    }

    private fun clip(s: String?): String? {
        val t = s?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        return if (t.length > DETAIL_MAX) t.take(DETAIL_MAX) + "…" else t
    }

    /** Last path component — the full path is noise when spoken. */
    private fun fileName(path: String?, zh: Boolean): String {
        val p = path?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            ?: return if (zh) "文件" else "a file"
        return p.trimEnd('/').substringAfterLast('/').ifEmpty { p }
    }
}

// ─── Merged from TtsLocaleResolver.kt ──────────────────────────────────────────────────

/**
 * [T-android-tts-locale GH#324] Decides which [Locale] the system TTS engine
 * should speak a given piece of text in.
 *
 * ## The bug this replaces
 *
 * `TextToSpeechManager.autoDetectAndSetLanguage` was a two-way branch:
 *
 * ```
 * val locale = if (HAN_REGEX.containsMatchIn(text)) SIMPLIFIED_CHINESE else Locale.US
 * ```
 *
 * Han characters got Chinese; **everything else got US English**. Spanish,
 * German, French, Russian, Japanese, Korean, Croatian and every other shipped
 * language was read aloud by an American English voice, which for es/de/hr is
 * not an accent but genuinely unintelligible. Reported as GH#324.
 *
 * Critically, that branch ran on EVERY utterance and consulted nothing but the
 * text's script — not the app's UI language, not the system locale. So no
 * amount of localisation work could fix it: shipping `values-es` changes which
 * strings the UI renders, and changes nothing about which voice speaks them.
 *
 * ## Why the UI language is the DEFAULT, and not text detection
 *
 * Reliable natural-language identification needs a real model (ML Kit's
 * language-id, or similar); this project has no such dependency and adding one
 * to pick a TTS voice would be disproportionate. More importantly, statistical
 * detection is least reliable exactly where TTS needs it most — short replies
 * ("Vale.", "Danke!", "OK") carry almost no signal, and a wrong guess is the
 * same disaster we are fixing.
 *
 * Script is different: it is decidable from the characters themselves with no
 * model and no ambiguity, so scripts that identify a language (Han, Kana,
 * Hangul, Cyrillic, Greek, Thai, Hebrew, Arabic, Devanagari) are honoured
 * first. For Latin script — where es/de/fr/hr/pt/… are mutually
 * indistinguishable without a model — the best available evidence is the
 * language the user chose for the app, which is also what they are almost
 * certainly reading and conversing in.
 *
 * Everything here is pure: no Android types, so it is unit-testable.
 */
object TtsLocaleResolver {

    /**
     * Scripts that identify a language on sight. Order matters only in that
     * each range is disjoint, so the first match wins unambiguously.
     *
     * Deliberately NOT here: Latin (shared by ~40 of the world's languages and
     * by most of what this app ships) and Han-vs-Kana overlap, handled below.
     */
    private val HAN = Regex("[\\u4e00-\\u9fff\\u3400-\\u4dbf]")

    /** Hiragana + Katakana. Japanese text almost always contains some. */
    private val KANA = Regex("[\\u3040-\\u309f\\u30a0-\\u30ff]")

    private val HANGUL = Regex("[\\uac00-\\ud7af\\u1100-\\u11ff\\u3130-\\u318f]")
    private val CYRILLIC = Regex("[\\u0400-\\u04ff]")
    private val GREEK = Regex("[\\u0370-\\u03ff]")
    private val THAI = Regex("[\\u0e00-\\u0e7f]")
    private val HEBREW = Regex("[\\u0590-\\u05ff]")
    private val ARABIC = Regex("[\\u0600-\\u06ff]")
    private val DEVANAGARI = Regex("[\\u0900-\\u097f]")

    /**
     * Resolve the locale for [text].
     *
     * @param appLanguageTag the user's saved in-app language (the value the
     *   Appearance screen writes to `KEY_LANGUAGE`), or null/blank for
     *   "follow system".
     * @param systemLocale the device default, used when the app follows the
     *   system.
     *
     * Resolution order ([T-android-tts-locale-priority], set by the user):
     *   1. the app's UI language — the default, and the tier the user asked to
     *      be authoritative;
     *   2. a language-identifying SCRIPT in the text (zh / ja / ko / ru / …),
     *      consulted only when no app language is set;
     *   3. the system locale;
     *   4. [Locale.US] as the final backstop — reachable, but no longer the
     *      default for four-fifths of the world.
     *
     * A tier ABOVE all of these lives in [TextToSpeechManager]: an explicitly
     * chosen voice (`preferredVoiceName`) or an explicit `setLanguage` call
     * wins outright and never reaches this resolver.
     */
    fun resolve(
        text: String,
        appLanguageTag: String?,
        systemLocale: Locale = Locale.getDefault(),
    ): Locale {
        // [T-android-tts-locale-priority] App UI language FIRST.
        //
        // The user's product rule: the default speaking language follows the
        // language they picked for the app. Switch the UI to Spanish and
        // replies are spoken in Spanish; switch to Croatian and they are
        // spoken in Croatian — without having to find a second, separate
        // voice setting.
        parseTag(appLanguageTag)?.let { return it }

        // Script detection is the FALLBACK, not the default. It applies only
        // when no app language is set (follow-system). It still earns its
        // place: it is what keeps Chinese sounding like Chinese for a user on
        // an English UI — the one case the original zh/US branch got right,
        // and which deleting this tier would regress.
        scriptLocale(text)?.let { return it }

        // A system locale with no language at all (possible on odd ROMs) is
        // useless to the engine — fall through rather than hand it garbage.
        if (systemLocale.language.isNotBlank()) return systemLocale
        return Locale.US
    }

    /**
     * The locale implied by the text's script, or null when the script does
     * not identify one (Latin, digits, punctuation, emoji only).
     */
    fun scriptLocale(text: String): Locale? {
        if (text.isBlank()) return null
        // Kana BEFORE Han: Japanese mixes kanji with kana, so a text
        // containing both is Japanese, not Chinese. Testing Han first — as the
        // old code effectively did — would speak Japanese prose in Mandarin.
        if (KANA.containsMatchIn(text)) return Locale.JAPANESE
        if (HANGUL.containsMatchIn(text)) return Locale.KOREAN
        if (HAN.containsMatchIn(text)) return Locale.SIMPLIFIED_CHINESE
        if (CYRILLIC.containsMatchIn(text)) return Locale("ru")
        if (GREEK.containsMatchIn(text)) return Locale("el")
        if (THAI.containsMatchIn(text)) return Locale("th")
        if (HEBREW.containsMatchIn(text)) return Locale("he")
        if (ARABIC.containsMatchIn(text)) return Locale("ar")
        if (DEVANAGARI.containsMatchIn(text)) return Locale("hi")
        return null
    }

    /**
     * Parse a tag the way the Appearance picker stores it.
     *
     * Mirrors `LocaleWrap.parseLocale`: bare codes (`es`, `de`) go through the
     * `Locale(code)` constructor, anything with a separator (`zh-Hant`,
     * `pt-BR`) through `forLanguageTag`.
     *
     * Indonesian is deliberately NOT special-cased. Android normalises the tag
     * to the legacy `in` internally while the JVM reports `id` (and maps
     * `Locale("in")` back to `id`) — verified, not assumed, after a test that
     * asserted one spelling failed on the other platform. Either code reaches
     * the same voice data, so forcing a spelling here would only encode a
     * platform difference that does not affect the outcome.
     */
    fun parseTag(tag: String?): Locale? {
        val code = tag?.trim().orEmpty()
        if (code.isEmpty()) return null
        return runCatching {
            if ('-' in code || '_' in code) {
                Locale.forLanguageTag(code.replace('_', '-'))
            } else {
                Locale(code)
            }
        }.getOrNull()?.takeIf { it.language.isNotBlank() }
    }

    /**
     * Progressively less specific candidates to hand the engine, most specific
     * first.
     *
     * A device may have `es` voice data but not `es-MX`, or `pt` but not
     * `pt-BR`. Trying the region-qualified form and then the bare language is
     * what turns "no exact match" into "close enough voice" instead of the
     * silent fallback to whatever was set last — which, before this change,
     * meant English.
     */
    fun candidates(locale: Locale): List<Locale> {
        val out = LinkedHashSet<Locale>()
        out.add(locale)
        if (locale.country.isNotBlank() || locale.variant.isNotBlank()) {
            out.add(Locale(locale.language))
        }
        return out.toList()
    }
}

// ─── Merged from VadCaptureWindow.kt ──────────────────────────────────────────────────

/**
 * [T-android-vad-manual-stop] How much audio around the VAD's own segment a
 * flushed take should carry.
 *
 * Silero reports speech only once it is CONFIRMED and ends a segment as soon
 * as its silence window is satisfied, so the audio it hands over is trimmed at
 * both ends: the onset spoken while it was still deciding, and the decay of
 * the final syllable. Feeding that to a recogniser drops leading and trailing
 * words — reported as "识别到的内容一直没有前面两个字".
 *
 * Both ends are therefore padded from the raw capture: a pre-roll ring held
 * before confirmation, and a tail hold kept after the endpoint. This object
 * exists so the frame arithmetic is checkable without a microphone.
 */
internal object VadCaptureWindow {

    /** Frames of post-endpoint audio to keep, for a given frame size. */
    fun tailFrames(sampleRate: Int, frameSamples: Int, tailSeconds: Float): Int =
        (sampleRate * tailSeconds / frameSamples).toInt()

    /** Bytes of pre-confirmation audio to retain (PCM16 mono). */
    fun prerollCapacityBytes(sampleRate: Int, prerollSeconds: Float): Int =
        (sampleRate * prerollSeconds).toInt() * 2

    /**
     * Duration a flushed take should span: the pre-roll actually held, plus
     * everything since speech was confirmed. Used to reason about the expected
     * flush length in traces — a flush that merely equals
     * `confirmedSeconds` means the pre-roll never filled, which is exactly how
     * the first attempt at this failed (the staging guard skipped every frame
     * while `isSpeaking` was false, so the ring stayed empty).
     */
    fun expectedFlushSeconds(prerollHeldSeconds: Float, confirmedSeconds: Float): Float =
        prerollHeldSeconds + confirmedSeconds
}

// ─── Merged from VadManualStopOutcome.kt ──────────────────────────────────────────────────

/**
 * [T-android-vad-manual-stop] What a manually-stopped VAD take should do with
 * the audio it is holding.
 *
 * Extracted from [ProviderSpeechRecognitionEngine] so the rule can be tested
 * without an AudioRecord: the engine itself binds the mic and a Context and
 * cannot be constructed in a JVM test, and this decision is the whole of the
 * bug that was fixed — a manual stop used to fall off the end of the VAD path
 * producing NO terminal callback at all, leaving SpeechRecognitionManager in
 * FINISHING and the panel spinning on "Recognizing…" forever.
 */
internal enum class VadManualStopOutcome {
    /** Nothing was captured for this session — no session, no held audio. */
    TRANSCRIBE,

    /** Held audio is too short to be speech; report a no-match. */
    NO_MATCH,

    /** No active VAD session (already settled, or cancelled). */
    NOTHING_TO_DO,
    ;

    companion object {
        /**
         * @param hasSession an un-settled VAD session is present.
         * @param heldSeconds total duration of the segments held for merge.
         * @param floorSeconds below this a take counts as noise, not speech.
         *
         * Note both non-idle outcomes end in a terminal callback
         * (onFinal via transcription, or onError) — that is the invariant the
         * fix exists to guarantee.
         */
        fun decide(hasSession: Boolean, heldSeconds: Float, floorSeconds: Float): VadManualStopOutcome = when {
            !hasSession -> NOTHING_TO_DO
            heldSeconds > floorSeconds -> TRANSCRIBE
            else -> NO_MATCH
        }
    }
}

// ─── Merged from VoiceAsrRetry.kt ──────────────────────────────────────────────────

/**
 * Pure rules behind two voice-input behaviours, kept free of Android types so
 * they can be unit-tested on the JVM (the engines bind AudioRecord /
 * SpeechRecognizer and cannot be constructed there).
 *
 *  - [T-voice-send-waits-for-asr] Send while voice mode still owes text waits
 *    for capture to stop and recognition to land ([VoiceFinishTracker]).
 *  - [T-voice-asr-failure-retry-prompt] A failed transcription keeps its audio
 *    and asks the user to retry or discard ([RetryPromptQueue],
 *    [VoiceAsrFailurePolicy], [VoiceAsrWatchdog]).
 *
 * Mirrors iOS VoiceInputPanel.swift (`finishCaptureForSend`,
 * `FailedUtterance`, `retryFailedUtterance`) and VoiceProvider+System.swift
 * (`recognitionWatchdogSeconds`).
 */

/**
 * [T-voice-asr-failure-retry-prompt] How long a recognizer may take over one
 * utterance before we call it a timeout. A flat limit cut long dictation off
 * mid-recognition; scale with the audio instead: at least 8 s, plus the
 * audio's own duration, capped at 2 minutes.
 */
object VoiceAsrWatchdog {
    const val MIN_SECONDS = 8.0
    const val MAX_SECONDS = 120.0

    fun seconds(audioSeconds: Double): Double {
        val audio = if (audioSeconds.isNaN() || audioSeconds < 0) 0.0 else audioSeconds
        return (audio + MIN_SECONDS).coerceIn(MIN_SECONDS, MAX_SECONDS)
    }

    /** Duration of mono PCM16 audio of [pcmBytes] bytes at [sampleRate]. */
    fun pcmSeconds(pcmBytes: Int, sampleRate: Int = 16_000): Double =
        if (sampleRate <= 0 || pcmBytes <= 0) 0.0 else pcmBytes / 2.0 / sampleRate

    /**
     * Duration of a canonical 44-byte-header PCM16 WAV, reading the byte rate
     * at offset 28. An unreadable header yields 0 (so the watchdog falls back
     * to its 8 s floor).
     */
    fun wavSeconds(wav: ByteArray): Double {
        if (wav.size <= 44) return 0.0
        val rate = (wav[28].toLong() and 0xFF) or
            ((wav[29].toLong() and 0xFF) shl 8) or
            ((wav[30].toLong() and 0xFF) shl 16) or
            ((wav[31].toLong() and 0xFF) shl 24)
        if (rate <= 0) return 0.0
        return (wav.size - 44).toDouble() / rate
    }
}

/**
 * [T-voice-asr-failure-retry-prompt] Which terminal errors are "the speech was
 * captured but could not be transcribed" — and therefore keep the audio and ask
 * the user — versus outcomes where there is nothing worth retrying.
 */
object VoiceAsrFailurePolicy {
    /** Below this there is no speech worth asking about (matches the VAD's too-short floor). */
    const val MIN_AUDIO_SECONDS = 0.3

    fun shouldPromptRetry(error: RecognitionError, audioSeconds: Double): Boolean {
        if (audioSeconds <= MIN_AUDIO_SECONDS) return false
        // NO_MATCH is "you said nothing" — an empty-but-successful result, not
        // a failure. Everything else with real audio behind it (HTTP / auth /
        // network errors, timeouts, no usable provider, a recognizer that
        // heard speech and returned nothing) is worth asking about: only
        // engines that actually captured audio hand it back, so e.g. a
        // PERMISSION_DENIED here is a provider 401, not a refused microphone.
        // [T-voice-mic-preempted] MIC_IN_USE neither: the system was feeding
        // the take silence, so the kept audio holds nothing worth re-sending,
        // and the message already tells the user when to try again.
        return error != RecognitionError.NO_MATCH && error != RecognitionError.MIC_IN_USE
    }
}

/**
 * [T-voice-asr-failure-retry-prompt] Failed utterances awaiting a Retry /
 * Discard decision, oldest first. Immutable so a Compose state holder can swap
 * it atomically.
 *
 * Retry removes the head and hands it back for re-transcription; if that fails
 * again the caller [enqueue]s it once more (at the tail, like iOS). Discard
 * drops the head.
 */
class RetryPromptQueue<T> private constructor(val items: List<T>) {
    constructor() : this(emptyList())

    val current: T? get() = items.firstOrNull()
    val size: Int get() = items.size
    fun isEmpty(): Boolean = items.isEmpty()

    fun enqueue(item: T): RetryPromptQueue<T> = RetryPromptQueue(items + item)

    /** @return the utterance to retry (null when empty) and the queue without it. */
    fun takeForRetry(): Pair<T?, RetryPromptQueue<T>> =
        if (items.isEmpty()) null to this else items.first() to RetryPromptQueue(items.drop(1))

    fun discard(): RetryPromptQueue<T> =
        if (items.isEmpty()) this else RetryPromptQueue(items.drop(1))
}

/**
 * [T-voice-send-waits-for-asr] Decides, one poll tick at a time, when a send
 * that is waiting on voice input may proceed.
 *
 * Poll rather than observe: stopping capture delivers its final segment
 * asynchronously, so "nothing pending" is only trustworthy after it has had a
 * moment to land. Require [requiredIdleTicks] consecutive idle ticks after a
 * short [settleMs]. A failure while waiting holds the send (the retry prompt
 * takes over); past [timeoutMs] the send is abandoned rather than sent
 * half-done.
 */
class VoiceFinishTracker(
    private val timeoutMs: Long = TIMEOUT_MS,
    private val settleMs: Long = 300,
    private val requiredIdleTicks: Int = 2,
) {
    enum class Step { WAIT, SEND, FAILED, TIMED_OUT }

    private var idleTicks = 0

    fun tick(idle: Boolean, failedSinceStart: Boolean, elapsedMs: Long): Step {
        idleTicks = if (idle && elapsedMs >= settleMs) idleTicks + 1 else 0
        return when {
            failedSinceStart -> Step.FAILED
            idleTicks >= requiredIdleTicks -> Step.SEND
            elapsedMs >= timeoutMs -> Step.TIMED_OUT
            else -> Step.WAIT
        }
    }

    companion object {
        const val TIMEOUT_MS = 60_000L
    }
}

// ─── Merged from VoiceOutputState.kt ──────────────────────────────────────────────────

/**
 * [T-android-tts-capsule] GLOBAL read-replies (TTS output) state — the single
 * source of truth shared by the composer's "Read replies" pill and the floating
 * speech-player capsule. Port of iOS `VoiceOutputState`
 * (VoiceProviderResolver.swift:66).
 *
 * Three-level model, in lockstep with iOS:
 *  • disabled (!isEnabled)          → no capsule, no TTS.
 *  • muted    (isEnabled && isMuted)→ capsule stays VISIBLE (so the user can
 *                                     un-mute), playback stopped, new reply
 *                                     TTS suppressed.
 *  • active   (isEnabled && !muted) → replies are spoken.
 *
 * Persisted in the same `voice_prefs` file the pill already used, under the
 * SAME "readReplies" key — so the pill, the capsule, and the streaming
 * read-aloud effect can never disagree about whether TTS is on.
 */
object VoiceOutputState {

    private const val PREFS = "voice_prefs"
    private const val KEY_ENABLED = "readReplies"
    private const val KEY_MUTED = "readReplies.muted"
    private const val KEY_SPEED = "readReplies.speed"
    private const val KEY_VOLUME = "readReplies.volume"
    private const val KEY_SYSTEM_VOICE = "readReplies.systemVoice"
    private const val KEY_SYSTEM_VOICE_LABEL = "readReplies.systemVoiceLabel"

    // [T-android-tts-capsule-drag] Dragged position, stored in DP as an offset
    // RELATIVE to the resting bottom-end corner — never as an absolute screen
    // coordinate. Same choice as iOS `VoiceOutputPreferences.capsuleAnchorOffset`
    // and for the same reason: a relative offset stays meaningful after a
    // rotation, a window resize, or a keyboard-driven height change, whereas an
    // absolute point would put the capsule off-screen. x is negative moving
    // LEFT from the right edge, y negative moving UP from the bottom.
    private const val KEY_DRAG_X_DP = "readReplies.capsuleDragXDp"
    private const val KEY_DRAG_Y_DP = "readReplies.capsuleDragYDp"

    /** Speed cycle, matching iOS `VoiceOutputPreferences.speedSteps`. */
    val SPEED_STEPS = floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f)

    // @Volatile: init() runs from both ChatScreen's LaunchedEffect and
    // ReadAloudPlayer.init, and a player can be constructed off the main
    // thread — without it a caller could observe a half-published object.
    @Volatile
    private var prefs: SharedPreferences? = null

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    /**
     * [T-readaloud-volume] Read-aloud gain, 0..[MAX_VOLUME], applied to BOTH
     * playback paths (system engine KEY_PARAM_VOLUME and provider
     * MediaPlayer.setVolume).
     *
     * Up to 1.0 (the default, and the pre-slider behaviour) it is a plain gain
     * on top of the media volume. [T-readaloud-volume-boost] Above 1.0 the
     * players are at full scale and the rest is a LoudnessEnhancer on the
     * playback's audio session ([ReadAloudBoost]), so quiet voices can be
     * read up to 200 %. Values stored by older builds (0..1) mean the same.
     */
    private val _volume = MutableStateFlow(DEFAULT_VOLUME)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    const val DEFAULT_VOLUME = 1.0f
    const val MAX_VOLUME = 2.0f

    fun clampVolume(v: Float): Float = if (v.isNaN()) DEFAULT_VOLUME else v.coerceIn(0f, MAX_VOLUME)

    /**
     * [T-android-tts-capsule-drag] Persisted drag offset in DP from the resting
     * bottom-end corner, or null when the user has never dragged (rest at the
     * default corner). Exposed as a flow so the capsule restores it on mount.
     */
    private val _dragOffsetDp = MutableStateFlow<Pair<Float, Float>?>(null)
    val dragOffsetDp: StateFlow<Pair<Float, Float>?> = _dragOffsetDp.asStateFlow()

    // Live state mirrored from the active ReadAloudPlayer so the capsule can
    // render playback/synthesis without owning the player.
    val isSpeaking = MutableStateFlow(false)
    val isSynthesizing = MutableStateFlow(false)

    /** Label of the model that is ACTUALLY synthesizing (post fail-over). */
    val activeModelLabel = MutableStateFlow<String?>(null)

    /**
     * [T-android-system-voice-catalog] Selected SYSTEM engine voice (engine
     * voice name, e.g. "cmn-cn-x-ssa-local"); null = engine default ("Auto").
     * Deliberately a system-engine detail rather than a provider-override
     * entry: it layers under resolveVoiceOutputChoice() without touching
     * ProviderRepository, while the picker still renders each voice as its own
     * selectable row like iOS.
     */
    private val _systemVoiceName = MutableStateFlow<String?>(null)
    val systemVoiceName: StateFlow<String?> = _systemVoiceName.asStateFlow()

    /** Human label for [systemVoiceName], persisted so the capsule chip can
     *  show it without re-enumerating the catalog. */
    private val _systemVoiceLabel = MutableStateFlow<String?>(null)
    val systemVoiceLabel: StateFlow<String?> = _systemVoiceLabel.asStateFlow()

    fun setSystemVoice(name: String?, label: String?) {
        _systemVoiceName.value = name
        _systemVoiceLabel.value = label
        prefs?.edit()
            ?.putString(KEY_SYSTEM_VOICE, name)
            ?.putString(KEY_SYSTEM_VOICE_LABEL, label)
            ?.apply()
    }

    /**
     * [T-android-tts-player-registry] Stack of live players, most recent last.
     * [activePlayer] is the top — the one the capsule's mute/close/speed act on.
     *
     * This was a single last-writer-wins slot, which broke as soon as TWO
     * players existed (the reply channel in ChatScreen, and the selection
     * Read-Aloud player that constructs lazily on first use):
     *
     *  - the selection player's `init` overwrote the slot mid-reply, so the
     *    capsule's mute/close stopped IT while the reply kept speaking;
     *  - when the selection player shut down, the slot went to null rather
     *    than back to the reply player, so the capsule permanently lost its
     *    handle even after the thief was gone.
     *
     * A stack fixes both: whoever is most recent gets the controls, and
     * dismissing them restores the previous owner. Deregistration is by
     * identity so a mid-stack player leaving doesn't disturb the rest.
     */
    private val players = ArrayDeque<ReadAloudPlayer>()

    val activePlayer: ReadAloudPlayer?
        get() = synchronized(players) { players.lastOrNull() }

    fun registerPlayer(player: ReadAloudPlayer) = synchronized(players) {
        players.remove(player)
        players.addLast(player)
    }

    fun unregisterPlayer(player: ReadAloudPlayer) = synchronized(players) {
        players.remove(player)
    }

    /** True when [player] currently owns the capsule — gates the live-state
     *  mirror writes so a background player can't flicker the UI. */
    fun isActivePlayer(player: ReadAloudPlayer): Boolean =
        synchronized(players) { players.lastOrNull() === player }

    /** Effective gate for producing reply audio: enabled AND not muted. */
    val canPlay: Boolean get() = _isEnabled.value && !_isMuted.value

    /**
     * [T-android-tts-stop-on-capture] Silence EVERY live player because the mic
     * is about to open, holding unspoken text for [resumeAllAfterCapture].
     *
     * ReadAloudPlayer.enqueue already refuses NEW utterances while the
     * recognizer is capturing, but that only covered text arriving after the
     * mic opened. Audio already in flight kept playing straight into the
     * microphone — the speaker feeds back into dictation, so the assistant's
     * own narration gets transcribed on top of what the user said, and
     * playback fights the recognizer for the audio route.
     *
     * Applies to all registered players, not just [activePlayer]: the reply
     * channel and the selection Read-Aloud player can both be live, and
     * leaving the non-active one talking is exactly the echo this prevents.
     */
    fun suspendAllForCapture() {
        val live = synchronized(players) { players.toList() }
        live.forEach { runCatching { it.suspendForCapture() } }
    }

    /** Resume every player parked by [suspendAllForCapture]. */
    fun resumeAllAfterCapture() {
        val live = synchronized(players) { players.toList() }
        live.forEach { runCatching { it.resumeAfterCapture() } }
    }

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _isEnabled.value = p.getBoolean(KEY_ENABLED, false)
        _isMuted.value = p.getBoolean(KEY_MUTED, false)
        _speed.value = p.getFloat(KEY_SPEED, 1.0f)
        _volume.value = clampVolume(p.getFloat(KEY_VOLUME, DEFAULT_VOLUME))
        _systemVoiceName.value = p.getString(KEY_SYSTEM_VOICE, null)
        _systemVoiceLabel.value = p.getString(KEY_SYSTEM_VOICE_LABEL, null)
        // [T-android-tts-capsule-drag] Both keys must be present: a half-written
        // pair (or a default sentinel) would silently anchor the capsule to a
        // bogus corner, so treat anything incomplete as "never dragged".
        _dragOffsetDp.value =
            if (p.contains(KEY_DRAG_X_DP) && p.contains(KEY_DRAG_Y_DP)) {
                p.getFloat(KEY_DRAG_X_DP, 0f) to p.getFloat(KEY_DRAG_Y_DP, 0f)
            } else {
                null
            }
    }

    /**
     * [T-android-tts-capsule-drag] Persist the capsule's dragged position.
     * Called once on drag RELEASE, not per movement frame — a SharedPreferences
     * write per pointer event would be dozens of disk commits per gesture.
     */
    fun setCapsuleDragOffsetDp(xDp: Float, yDp: Float) {
        _dragOffsetDp.value = xDp to yDp
        prefs?.edit()
            ?.putFloat(KEY_DRAG_X_DP, xDp)
            ?.putFloat(KEY_DRAG_Y_DP, yDp)
            ?.apply()
    }

    fun setEnabled(value: Boolean) {
        if (_isEnabled.value == value) return
        _isEnabled.value = value
        prefs?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        if (!value) {
            // Off → stop whatever is being read, drop the queue, clear the
            // temporary mute (meaningless once fully off; a fresh enable
            // should start un-muted). Mirrors iOS isEnabled.didSet.
            activePlayer?.stop()
            setMuted(false)
        }
    }

    /**
     * [T-android-readaloud-menu-enable] The user explicitly asked to hear
     * something (a long-press "Read Selection" / "Read from Start"), so turn
     * read-replies on and lift a temporary mute before it is spoken.
     *
     * Every utterance goes through [ReadAloudPlayer.enqueue], which drops it
     * unless [canPlay] (read-replies ON and not muted). Read-replies defaults
     * to OFF, so with default settings both menu actions were silent — no
     * sound, no error. A menu tap is a deliberate request, not the automatic
     * reply TTS the switch exists to govern.
     *
     * Mirrors iOS, where all three explicit entry points do this before
     * speaking: `readReplyFromStart`, `speakText` (T-issue316-read-selection-
     * enable) and `activateReadAloudState` (T-readaloud-menu-force-unmute).
     * Both setters persist, exactly like the user flipping the switch — so,
     * as on iOS, later replies are read aloud too until it is switched off.
     */
    fun activateForExplicitReadAloud() {
        if (!_isEnabled.value) setEnabled(true)
        if (_isMuted.value) setMuted(false)
    }

    fun setMuted(value: Boolean) {
        if (_isMuted.value == value) return
        _isMuted.value = value
        prefs?.edit()?.putBoolean(KEY_MUTED, value)?.apply()
        // Mute silences the CURRENT playback too, not just future utterances.
        if (value) activePlayer?.stop()
    }

    fun setSpeed(value: Float) {
        if (_speed.value == value) return
        _speed.value = value
        prefs?.edit()?.putFloat(KEY_SPEED, value)?.apply()
    }

    /**
     * [T-readaloud-volume] Update the live volume. With [persist] = false
     * (slider drag) only the flow changes, so an in-flight provider utterance
     * follows the thumb without a SharedPreferences write per frame; the
     * drag's end calls [persistVolume].
     */
    fun setVolume(value: Float, persist: Boolean = true) {
        _volume.value = clampVolume(value)
        if (persist) persistVolume()
    }

    fun persistVolume() {
        prefs?.edit()?.putFloat(KEY_VOLUME, _volume.value)?.apply()
    }

    /** Cycle 1× → 1.25× → 1.5× → 2× → 1×. */
    fun nextSpeed() {
        // Tolerance, not exact float equality: the current value round-trips
        // through SharedPreferences.getFloat, and any future step that isn't
        // exactly representable would miss, making idx -1 → the cycle would
        // RESET to 1× instead of advancing. Today's steps happen to round-trip
        // exactly; this stops that from being load-bearing.
        val idx = SPEED_STEPS.indexOfFirst { kotlin.math.abs(it - _speed.value) < 0.001f }
        setSpeed(SPEED_STEPS[(if (idx < 0) 0 else idx + 1) % SPEED_STEPS.size])
    }

    fun speedLabel(s: Float = _speed.value): String =
        if (s == s.toInt().toFloat()) "${s.toInt()}×" else "$s×"
}

// ─── Merged from VoicePipelineLog.kt ──────────────────────────────────────────────────

/**
 * [T-android-voice-pipeline-trace] One tagged event per stage of a voice-input
 * take, so a "recording finished but no text appeared" report can be pinned to
 * a specific seam instead of guessed at.
 *
 * The chain a take passes through:
 *
 *   panel.start → manager.start → engine.start → capture.begin →
 *   capture.end → transcribe.begin → (transcribe.try …) →
 *   engine.final | engine.error → manager.final | manager.error →
 *   panel.commit
 *
 * A missing event names the broken link: capture.end with no transcribe.begin
 * is a capture-side stall, transcribe.begin with no engine.final is the
 * network round-trip, engine.final with no panel.commit is the UI hand-off
 * (the panel drops a result while editing, and dropping it silently is
 * exactly the reported symptom).
 *
 * PRIVACY: events and metadata only — never recognised text, never audio.
 * Lengths and byte counts are recorded because "empty vs non-empty" is the
 * question being asked; the content itself never is. Model and engine ids are
 * configuration, not user data. Provider error messages are NOT logged
 * verbatim (they can echo request content); only the exception class name.
 */
internal object VoicePipelineLog {
    private const val TAG = "VoicePipeline"

    /** @param meta already-sanitised `k=v` pairs. */
    fun event(name: String, vararg meta: Pair<String, Any?>) {
        val suffix = if (meta.isEmpty()) "" else
            meta.joinToString(" ", prefix = " ") { (k, v) -> "$k=${v ?: "-"}" }
        AppLogger.info(TAG, "$name$suffix")
    }

    /** Class name only — a provider message can quote the request. */
    fun errorKind(e: Throwable?): String = e?.javaClass?.simpleName ?: "none"
}

// ─── Merged from VoiceTextSanitizer.kt ──────────────────────────────────────────────────

/**
 * [T-android-voice-text-sanitizer] Cleans a text unit before it is handed to
 * TTS. Port of iOS `Providers/Voice/VoiceTextSanitizer.swift`.
 *
 * Without this, read-aloud pronounces raw Markdown — "star star important star
 * star", every table pipe, and whole URLs character by character — and stumbles
 * over emoji and box-drawing glyphs. It removes the syntax that shouldn't be
 * spoken while keeping the visible text and the punctuation that drives
 * prosody.
 *
 * Ordering matters and mirrors iOS: fenced code → inline code → links/images →
 * emphasis → line-leading block markers → leftover markers.
 *
 * DIVERGENCE FROM iOS (deliberate): iOS hardcodes the Chinese link phrases
 * "<host> 的链接" / "链接". Here the phrase is supplied by the caller via
 * [LinkPhrases] so it can come from `R.string` and follow the device locale —
 * the project requires localized user-facing strings, and TTS output is
 * user-facing. Defaults are English so the class stays usable (and unit
 * testable) without a Context.
 */
object VoiceTextSanitizer {

    /**
     * Localized wording for spoken link substitutions.
     *
     * @param linkToHost formats a known host; must contain a single `%s`.
     * @param bareLink used when no host can be extracted.
     */
    data class LinkPhrases(
        val linkToHost: String = "link to %s",
        val bareLink: String = "link",
    )

    private val DEFAULT_PHRASES = LinkPhrases()

    /**
     * Strip Markdown syntax and non-speakable glyphs, then tidy whitespace.
     * Returns the cleaned, speakable string — which may legitimately be empty
     * (e.g. a message that was nothing but a fenced code block), and callers
     * must treat empty as "nothing to speak" rather than speaking the original.
     */
    fun sanitize(text: String, phrases: LinkPhrases = DEFAULT_PHRASES): String {
        var s = stripMarkdown(text, phrases)
        // Any remaining bare URL in plain text → spoken phrase, so the reader
        // never voices a long, unstoppable URL.
        s = rewriteBareUrls(s, phrases)
        // Replace non-speakable glyphs with a space rather than deleting them,
        // so "word🔥word" doesn't fuse into one token.
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            sb.append(if (isSpeakable(cp)) String(Character.toChars(cp)) else " ")
            i += Character.charCount(cp)
        }
        // Collapse the whitespace the removals left behind.
        return sb.toString()
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .trim()
    }

    /**
     * Remove the Markdown syntax that shouldn't be pronounced, keeping the
     * visible text.
     */
    private fun stripMarkdown(text: String, phrases: LinkPhrases): String {
        // [T-md-inline-html] <br> → space and whitelisted style tags out, so
        // "b" / "br" are never read aloud; code is left for the rules below.
        var s = com.openminis.app.ui.markdown.InlineHtmlTags.stripForPreview(text)
        // Fenced code blocks are dropped entirely — reading code aloud is noise.
        s = s.replace(Regex("```[\\s\\S]*?```"), " ")
        // Inline `code` → keep the inner text.
        s = s.replace(Regex("`([^`]+)`"), "$1")
        // Links/images before emphasis, so a description containing * is safe.
        s = rewriteMarkdownLinks(s, phrases)
        // Emphasis: **x** __x__ *x* _x_ ~~x~~ → x
        s = s.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        s = s.replace(Regex("__([^_]+)__"), "$1")
        s = s.replace(Regex("\\*([^*]+)\\*"), "$1")
        // Intra-word underscores (snake_case) must survive, hence the guards.
        s = s.replace(Regex("(?<!\\w)_([^_]+)_(?!\\w)"), "$1")
        s = s.replace(Regex("~~([^~]+)~~"), "$1")
        // Line-leading block markers.
        s = s.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")        // # headings
        s = s.replace(Regex("(?m)^\\s{0,3}>\\s?"), "")             // > blockquote
        s = s.replace(Regex("(?m)^\\s{0,3}[-*+]\\s+"), "")         // - bullet
        s = s.replace(Regex("(?m)^\\s{0,3}\\d+[.)]\\s+"), "")      // 1. ordered
        s = s.replace(Regex("(?m)^\\s*\\|?[-:| ]+\\|?\\s*$"), " ") // table separator
        s = s.replace(Regex("\\|"), " ")                           // table pipes
        // Horizontal rules --- *** ___
        s = s.replace(Regex("(?m)^\\s*([-*_])\\1{2,}\\s*$"), " ")
        // Stray leftover emphasis markers.
        //
        // DIVERGENCE FROM iOS (bug fix): iOS strips `[*_~`]{1,}` unconditionally,
        // which also eats the underscores in identifiers — "read_image_file"
        // became "readimagefile", defeating the (?<!\w)_..._(?!\w) guard above.
        // Underscores are only stripped when NOT sitting between word
        // characters, so snake_case survives; the other markers are never
        // meaningful mid-identifier and stay unconditional.
        s = s.replace(Regex("(?<!\\w)_+|_+(?!\\w)"), "")
        s = s.replace(Regex("[*~`]+"), "")
        return s
    }

    /**
     * Rewrite `[desc](url)` / `![alt](url)`: a non-empty description is kept
     * verbatim; an empty one becomes the spoken link phrase.
     */
    private fun rewriteMarkdownLinks(text: String, phrases: LinkPhrases): String {
        val re = Regex("!?\\[([^\\]]*)\\]\\(([^)\\s]+)[^)]*\\)")
        return re.replace(text) { m ->
            val desc = m.groupValues[1].trim()
            if (desc.isEmpty()) linkPhrase(m.groupValues[2], phrases) else desc
        }
    }

    /** Replace bare http(s) URLs in plain text with the spoken link phrase. */
    private fun rewriteBareUrls(text: String, phrases: LinkPhrases): String =
        Regex("https?://[^\\s)\\]]+").replace(text) { m -> linkPhrase(m.value, phrases) }

    private fun linkPhrase(url: String, phrases: LinkPhrases): String {
        val host = hostOf(url)
        return if (host.isEmpty()) phrases.bareLink else phrases.linkToHost.format(host)
    }

    /** Host without a leading "www.", or "" when nothing usable can be parsed. */
    internal fun hostOf(url: String): String {
        var s = url
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        val cut = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (cut >= 0) s = s.substring(0, cut)
        // Strip userinfo and port so "user@host:8080" reads as "host".
        s.indexOf('@').let { if (it >= 0) s = s.substring(it + 1) }
        s.indexOf(':').let { if (it >= 0) s = s.substring(0, it) }
        if (s.startsWith("www.")) s = s.substring(4)
        return s
    }

    /**
     * True when the code point should be spoken. Drops emoji, dingbats, box
     * drawing and other symbol ranges that a TTS engine either skips or
     * verbalizes awkwardly. Ranges mirror the iOS implementation.
     */
    private fun isSpeakable(cp: Int): Boolean {
        // Keep all ASCII: letters, digits, punctuation, whitespace.
        if (cp < 0x80) return true
        return when (cp) {
            in 0x200B..0x200F,        // zero-width / directional marks
            in 0xFE00..0xFE0F,        // variation selectors
            in 0x2190..0x21FF,        // arrows
            in 0x2300..0x23FF,        // misc technical
            in 0x2460..0x24FF,        // enclosed alphanumerics
            in 0x2500..0x257F,        // box drawing
            in 0x2580..0x259F,        // block elements
            in 0x25A0..0x25FF,        // geometric shapes
            in 0x2600..0x26FF,        // misc symbols
            in 0x2700..0x27BF,        // dingbats
            in 0x2B00..0x2BFF,        // misc symbols & arrows
            in 0x1F000..0x1FAFF,      // emoji / pictographs
            in 0xE0000..0xE007F,      // tags
            -> false
            0x20E3 -> false           // combining keycap
            else -> true
        }
    }
}

// ─── Merged from WavSegmentMerger.kt ──────────────────────────────────────────────────

/**
 * [T-android-vad-merge-segments] Concatenate consecutive 16 kHz mono PCM16 WAV
 * segments into one WAV. Port of iOS `VoiceInputPanel.mergeWavSegments`
 * (VoiceInputPanel.swift:1202).
 *
 * ## Why this exists
 * The VAD closes a segment on every silence gap, and a segment shorter than
 * [ProviderSpeechRecognitionEngine.MIN_SEGMENT_SECONDS] used to be DISCARDED
 * outright — a cough filter. That is right for one isolated blip and wrong for
 * natural speech: "yes" (0.8 s), pause, "send it" (0.9 s) is two sub-threshold
 * segments in a row, so dictation could never complete. iOS accumulates
 * segments and tests the 2 s minimum against the RUNNING TOTAL; this is the
 * byte-level half of that.
 *
 * ## Format assumption
 * Every segment is a complete WAV with a canonical 44-byte header
 * ([VoiceActivityDetector.WAV_HEADER_BYTES]), mono PCM16 at one sample rate —
 * which is exactly what the VAD emits, and the same assumption iOS makes. The
 * merge keeps the first header, appends only the PCM payloads, then rewrites
 * the two length fields. No resampling, no format negotiation: segments from a
 * single capture session are homogeneous by construction.
 */
object WavSegmentMerger {

    private const val HEADER = VoiceActivityDetector.WAV_HEADER_BYTES

    /** RIFF chunk size lives at byte 4, data chunk size at byte 40. */
    private const val OFFSET_RIFF_SIZE = 4
    private const val OFFSET_DATA_SIZE = 40

    /**
     * @return one WAV containing every segment's audio in order, or null when
     *   nothing usable was supplied. A single valid segment is returned as-is.
     */
    fun merge(segments: List<ByteArray>): ByteArray? {
        val valid = segments.filter { it.size > HEADER }
        if (valid.isEmpty()) return null
        if (valid.size == 1) return valid[0]

        val payloadBytes = valid.sumOf { it.size - HEADER }
        val out = ByteArray(HEADER + payloadBytes)

        // Header of the first segment verbatim — sample rate, channel count and
        // bit depth are shared across a session, so the first one describes all.
        System.arraycopy(valid[0], 0, out, 0, HEADER)
        var pos = HEADER
        for (seg in valid) {
            val n = seg.size - HEADER
            System.arraycopy(seg, HEADER, out, pos, n)
            pos += n
        }

        putLeUInt32(out, OFFSET_RIFF_SIZE, (out.size - 8).toLong())
        putLeUInt32(out, OFFSET_DATA_SIZE, payloadBytes.toLong())
        return out
    }

    /** Total PCM duration of [segments] at [sampleRate], header bytes excluded. */
    fun totalSeconds(segments: List<ByteArray>, sampleRate: Int = 16_000): Float {
        if (sampleRate <= 0) return 0f
        val payload = segments.sumOf { maxOf(0, it.size - HEADER) }
        // PCM16 mono → 2 bytes per frame.
        return payload / 2f / sampleRate
    }

    private fun putLeUInt32(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
