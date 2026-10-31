package com.nexora.robot.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.nexora.robot.animation.AnimationCue
import com.nexora.robot.language.CurrentInfoDetector
import com.nexora.robot.language.IntentDetector
import com.nexora.robot.language.TextNormalizer
import com.nexora.robot.language.UserIntent
import com.nexora.robot.memory.LocalMemoryStore
import com.nexora.robot.memory.MemoryWriteResult
import com.nexora.robot.net.ConverseResult
import com.nexora.robot.net.NexApiClient
import com.nexora.robot.net.NexErrorCodes
import com.nexora.robot.net.SourceLink
import com.nexora.robot.offline.FocusTimerPolicy
import com.nexora.robot.offline.OfflineResponses
import com.nexora.robot.util.AppSettings
import com.nexora.robot.util.NexLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Android half of the voice experience.
 *
 * Everything that decides *what happens next* lives in [VoiceReducer] (pure Kotlin, unit
 * tested). This class only performs the physical work the reducer asks for: opening and
 * closing the microphone, driving TTS, calling the backend, and publishing state to the UI.
 *
 * Contract notes:
 *  * [scope] must use the main dispatcher — `SpeechRecognizer` and `TextToSpeech` are
 *    main-thread APIs, and every callback is marshalled with [onMain] regardless;
 *  * the wake phrase works **foreground only**. A standard app cannot wake itself from a
 *    force-stopped state, and NEX never claims otherwise;
 *  * a recognition turn is never started while speech is playing, and never inside the
 *    post-speech audio-route settle window ([TimingPolicy.POST_SPEECH_MIC_SETTLE_MS]).
 */
class SpeechController(
    private val context: Context,
    private val settings: AppSettings,
    private val api: NexApiClient,
    private val memory: LocalMemoryStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    /**
     * Approximate locality, for a direct weather or place question only. Returns `null` unless
     * the user granted coarse location and a fresh fix exists — NEX never guesses a city.
     */
    private val coarseLocationHint: () -> String? = { null },
) : TtsVoice.Listener {

    private val _state = MutableStateFlow(VoiceReducer.initial(appInForeground = true))
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _transcript = MutableStateFlow<List<TranscriptLine>>(emptyList())
    val transcript: StateFlow<List<TranscriptLine>> = _transcript.asStateFlow()

    private val _sources = MutableStateFlow<List<SourceLink>>(emptyList())
    val sources: StateFlow<List<SourceLink>> = _sources.asStateFlow()

    private val _signals = MutableSharedFlow<UiSignal>(extraBufferCapacity = 16)
    val signals: SharedFlow<UiSignal> = _signals.asSharedFlow()

    private val _cueOverride = MutableStateFlow<AnimationCue?>(null)

    /** The animation the UI should play: a transient cue when set, otherwise the state's cue. */
    val animationCue: StateFlow<AnimationCue> = combine(_state, _cueOverride) { voiceState, override ->
        override ?: voiceState.animationCue
    }.stateIn(scope, SharingStarted.Eagerly, _state.value.animationCue)

    private val tts = TtsVoice(context, this)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var turnStartedAtMs = 0L
    private var scheduleJob: Job? = null
    private var scheduledTarget = 0L
    private var turnJob: Job? = null
    private var speechWatchdogJob: Job? = null
    private var turnWatchdogJob: Job? = null
    private var timerJob: Job? = null
    private var overrideJob: Job? = null
    private var voiceServicesPreparing = false
    private var expectedSpeechMs = 0L
    private var pendingStopAfterSpeech = false
    private var focusTimer = FocusTimerPolicy.idle()

    // ------------------------------------------------------------------
    // Public control surface
    // ------------------------------------------------------------------

    /** Voice mode on: prepare services, then hunt for "Hello NEX". */
    fun startVoiceMode() {
        pendingStopAfterSpeech = false
        tts.prepare()
        dispatch(VoiceEvent.VoiceModeEnabled)
    }

    /** Voice mode off: microphone closed, recogniser destroyed, TTS stopped. */
    fun stopVoiceMode() {
        cancelRecognizer()
        tts.stop()
        turnJob?.cancel()
        dispatch(VoiceEvent.VoiceModeDisabled)
    }

    fun onForeground() {
        dispatch(VoiceEvent.AppForegrounded)
    }

    fun onBackground() {
        // The microphone must be physically closed before the state changes.
        cancelRecognizer()
        dispatch(VoiceEvent.AppBackgrounded)
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        dispatch(
            if (granted) VoiceEvent.MicrophonePermissionGranted else VoiceEvent.MicrophonePermissionRevoked
        )
    }

    fun onCameraPermissionResult(granted: Boolean) {
        dispatch(
            if (granted) VoiceEvent.CameraPermissionGranted else VoiceEvent.CameraPermissionRevoked
        )
    }

    /** Speak something the activity produced (for example a vision answer). */
    fun speakExternal(text: String, language: String = settings.language(), cue: AnimationCue? = null) {
        onMain {
            if (cue != null) showTransientCue(cue, TRANSIENT_CUE_MS)
            appendLine(Speaker.NEX, text)
            speak(text, language)
        }
    }

    fun showTransientCue(cue: AnimationCue, durationMs: Long) {
        overrideJob?.cancel()
        _cueOverride.value = cue
        _signals.tryEmit(UiSignal.TransientCue(cue, durationMs))
        overrideJob = scope.launch {
            delay(durationMs.coerceAtLeast(0L))
            _cueOverride.value = null
        }
    }

    fun clearTranscript() {
        _transcript.value = emptyList()
        _sources.value = emptyList()
    }

    /** Stop everything and release every resource. Called from `onDestroy`. */
    fun release() {
        cancelRecognizer()
        listOf(scheduleJob, turnJob, speechWatchdogJob, turnWatchdogJob, timerJob, overrideJob)
            .forEach { it?.cancel() }
        tts.shutdown()
    }

    /** The single entry point into the state machine. */
    fun dispatch(event: VoiceEvent) {
        val now = clock()
        val previous = _state.value
        val next = VoiceReducer.reduce(previous, event, now)
        if (next != previous) {
            _state.value = next
            val ignored = if (next.ignoredEvent != null) " ignored=${next.ignoredEvent}" else ""
            NexLog.d("voice ${previous.state} -> ${next.state} event=${event.label}$ignored")
        }
        act(next)
    }

    // ------------------------------------------------------------------
    // Acting on a new state
    // ------------------------------------------------------------------

    private fun act(state: VoiceState) {
        if (state.state == ConversationState.PREPARING_VOICE) {
            if (!voiceServicesPreparing) {
                voiceServicesPreparing = true
                prepareVoiceServices()
            }
        } else {
            voiceServicesPreparing = false
        }
        scheduleNextAction(state)
    }

    private fun prepareVoiceServices() {
        onMain {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                NexLog.w("no speech recognition service on this device")
                dispatch(
                    VoiceEvent.VoiceServicesUnavailable(
                        VoiceMessages.NO_SPEECH_SERVICE,
                        retryable = false,
                    )
                )
                return@onMain
            }
            tts.prepare() // completion arrives through onEngineReady / onEngineUnavailable
        }
    }

    private fun scheduleNextAction(state: VoiceState) {
        val target = state.nextActionAtMs
        if (target <= 0L) {
            scheduleJob?.cancel()
            scheduleJob = null
            scheduledTarget = 0L
            return
        }
        if (scheduleJob?.isActive == true && scheduledTarget == target) return

        scheduleJob?.cancel()
        scheduledTarget = target
        val delayMs = (target - clock()).coerceAtLeast(TimingPolicy.MIN_TURN_GAP_MS.coerceAtMost(1L))
        scheduleJob = scope.launch {
            delay(delayMs)
            scheduledTarget = 0L
            onActionDue()
        }
    }

    private fun onActionDue() {
        val state = _state.value
        val now = clock()
        when {
            state.state == ConversationState.RECOVERING_VOICE_SERVICE -> {
                NexLog.i("recovery attempt=${state.retryAttempt}")
                startRecognitionTurn()
            }
            state.canOpenMicrophone(now) -> startRecognitionTurn()
            else -> {
                // The gate refused (usually the post-speech settle window). Wait it out honestly
                // instead of forcing the microphone open.
                val waitUntil = state.micBlockedUntilMs
                if (waitUntil > now) {
                    scheduleJob?.cancel()
                    scheduleJob = scope.launch {
                        delay(waitUntil - now)
                        scheduledTarget = 0L
                        onActionDue()
                    }
                } else {
                    NexLog.d("scheduled action skipped, mic gate refused")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Recognition turns
    // ------------------------------------------------------------------

    private fun startRecognitionTurn() = onMain {
        val now = clock()
        val state = _state.value
        if (!state.canOpenMicrophone(now)) {
            NexLog.i("mic gate refused before start: ${state.micDecision(now)}")
            return@onMain
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            dispatch(
                VoiceEvent.VoiceServicesUnavailable(VoiceMessages.NO_SPEECH_SERVICE, retryable = false)
            )
            return@onMain
        }

        cancelRecognizer()
        val created = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }
            .onFailure { NexLog.e("could not create SpeechRecognizer", it) }
            .getOrNull()
        if (created == null) {
            dispatch(VoiceEvent.RecognizerFailure(RecognizerFailureKind.CLIENT))
            return@onMain
        }

        recognizer = created
        created.setRecognitionListener(recognitionListener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionLocale())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        turnStartedAtMs = now
        runCatching { created.startListening(intent) }
            .onFailure {
                NexLog.e("startListening failed", it)
                dispatch(VoiceEvent.RecognizerFailure(RecognizerFailureKind.CLIENT))
                return@onMain
            }
        dispatch(VoiceEvent.MicrophoneOpened)

        // Defensive: if the reducer refused the turn after all, release the microphone at once.
        if (_state.value.state != ConversationState.RECOGNIZING) {
            NexLog.w("recognizer started but state is ${_state.value.state}, releasing microphone")
            cancelRecognizer()
        }
    }

    private fun recognitionLocale(): String = if (settings.hindiMode) "hi-IN" else "en-IN"

    private fun cancelRecognizer() {
        val current = recognizer ?: return
        recognizer = null
        runCatching { current.cancel() }.onFailure { NexLog.w("recognizer cancel failed", it) }
        runCatching { current.destroy() }.onFailure { NexLog.w("recognizer destroy failed", it) }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            NexLog.d("recognizer ready for speech")
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            val elapsed = clock() - turnStartedAtMs
            val outcome = RecognizerErrorPolicy.classify(error, elapsed)
            cancelRecognizer()
            when (outcome) {
                RecognitionOutcome.NormalSilence -> {
                    // Ordinary silence: stay calm, no error copy, new turn after 650 ms.
                    dispatch(VoiceEvent.RecognizerSilence)
                }
                is RecognitionOutcome.Failure -> {
                    NexLog.w("recognizer failure code=$error kind=${outcome.kind} elapsed=${elapsed}ms")
                    if (outcome.kind == RecognizerFailureKind.PERMISSION) {
                        dispatch(VoiceEvent.MicrophonePermissionRevoked)
                    } else {
                        dispatch(VoiceEvent.RecognizerFailure(outcome.kind))
                    }
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            cancelRecognizer()
            if (text.isBlank()) {
                dispatch(VoiceEvent.RecognizerSilence)
                return
            }
            val state = _state.value
            if (state.isSpeaking) {
                // Never accept a recognition result while NEX is speaking.
                NexLog.i("result discarded while speaking")
                return
            }
            if (state.wakePhraseRequired) {
                val phrase = WakePhraseMatcher.findWakePhrase(text)
                if (phrase == null) {
                    // Not the wake phrase yet: keep hunting calmly, no error shown.
                    dispatch(VoiceEvent.RecognizerSilence)
                } else {
                    NexLog.i("wake phrase heard")
                    dispatch(VoiceEvent.WakePhraseDetected)
                    acknowledgeWake()
                }
            } else {
                handleTranscript(text)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun acknowledgeWake() {
        val language = settings.language()
        val ack = if (language == "hi") "नमस्ते! मैं सुन रहा हूँ।" else VoiceMessages.WAKE_ACK
        // Animation 8 in the catalogue: "Wake-word sparkle".
        showTransientCue(AnimationCue.WAKE_WORD_DETECTED, TRANSIENT_CUE_MS)
        appendLine(Speaker.NEX, ack)
        speak(ack, language)
    }

    // ------------------------------------------------------------------
    // Turn handling
    // ------------------------------------------------------------------

    private fun handleTranscript(text: String) {
        val intent = IntentDetector.detect(text)
        val language = settings.language()
        appendLine(Speaker.USER, text)
        dispatch(VoiceEvent.Transcription(text))
        if (_state.value.state != ConversationState.THINKING) return

        when (intent) {
            UserIntent.VISION_REQUEST -> {
                armTurnWatchdog()
                _signals.tryEmit(UiSignal.VisionRequested(text))
                showTransientCue(AnimationCue.CAMERA_ACTIVE, TRANSIENT_CUE_MS)
            }
            UserIntent.MEMORY_SAVE -> handleMemorySave(text, language)
            UserIntent.MEMORY_RECALL, UserIntent.MEMORY_LIST -> handleMemoryList(language)
            UserIntent.MEMORY_DELETE -> handleMemoryDelete(language)
            UserIntent.SETTINGS_OR_VOICE_COMMAND -> handleVoiceCommand(text, language)
            UserIntent.TIMER_START -> startFocusTimer(text, language)
            UserIntent.WEATHER, UserIntent.LOCATION_QUERY -> {
                requestCoarseLocationIfNeeded()
                runServerTurn(text, intent)
            }
            else -> runServerTurn(text, intent)
        }
    }

    /**
     * Coarse location, asked for only when the user directly asked about their local weather
     * or a place. Never fine location, never in the background, and the turn proceeds either
     * way: without the permission NEX answers honestly instead of guessing a city.
     */
    private fun requestCoarseLocationIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        NexLog.i("coarse location requested for a direct local ask")
        _signals.tryEmit(UiSignal.PermissionNeeded(Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun runServerTurn(text: String, intent: UserIntent) {
        turnJob?.cancel()
        armTurnWatchdog()
        turnJob = scope.launch {
            val language = settings.language()
            val decision = CurrentInfoDetector.decide(text)
            if (decision.needed) {
                NexLog.i("turn may need live data reason=${decision.reason}")
                showTransientCue(AnimationCue.INTERNET_SEARCH, TRANSIENT_CUE_MS)
            }
            when (val result = api.converse(outgoingText(text, intent), language, decision.needed)) {
                is ConverseResult.Success -> {
                    turnWatchdogJob?.cancel()
                    _sources.value = result.sources
                    dispatch(VoiceEvent.ReplyReady(result.grounded, result.sources.size))
                    appendLine(Speaker.NEX, result.reply, result.grounded, result.sources)
                    speak(result.reply, result.language)
                }
                is ConverseResult.Rejected -> {
                    turnWatchdogJob?.cancel()
                    _sources.value = emptyList()
                    val offline = result.code == NexErrorCodes.SERVER_NOT_READY
                    dispatch(VoiceEvent.ServerUnavailable(offline))
                    appendLine(Speaker.NEX, result.message)
                    showTransientCue(
                        if (offline) AnimationCue.OFFLINE else AnimationCue.SAFE_WARNING,
                        TRANSIENT_CUE_MS,
                    )
                    speak(result.message, language)
                }
                is ConverseResult.Unavailable -> {
                    turnWatchdogJob?.cancel()
                    _sources.value = emptyList()
                    val offline = result.code == NexErrorCodes.OFFLINE
                    dispatch(VoiceEvent.ServerUnavailable(offline))
                    // Truthful fallback: never invent an answer, never queue it for later.
                    val reply = if (offline) {
                        OfflineResponses.forIntent(intent, language).text
                    } else {
                        OfflineResponses.serverUnreachable(language).text
                    }
                    appendLine(Speaker.NEX, reply)
                    showTransientCue(
                        if (offline) AnimationCue.OFFLINE else AnimationCue.NETWORK_WAITING,
                        TRANSIENT_CUE_MS,
                    )
                    speak(reply, language)
                }
            }
        }
    }

    /**
     * Only a direct weather or place question may carry an approximate location, and only when
     * the user granted coarse location. Everything else is forwarded exactly as it was said.
     * The transcript always keeps the user's own words, never the attached hint.
     */
    private fun outgoingText(text: String, intent: UserIntent): String {
        if (intent != UserIntent.WEATHER && intent != UserIntent.LOCATION_QUERY) return text
        val hint = coarseLocationHint() ?: return text
        NexLog.i("approximate location attached to a direct local ask")
        return "$text (approximate location from this device: $hint)"
    }

    /** If a turn stalls (no server answer, no vision answer), say so and reopen the microphone. */
    private fun armTurnWatchdog(timeoutMs: Long = TURN_WATCHDOG_MS) {
        turnWatchdogJob?.cancel()
        turnWatchdogJob = scope.launch {
            delay(timeoutMs)
            if (_state.value.isThinking) {
                NexLog.w("turn watchdog fired")
                val language = settings.language()
                dispatch(VoiceEvent.ServerUnavailable(offline = false))
                val reply = OfflineResponses.serverUnreachable(language).text
                appendLine(Speaker.NEX, reply)
                speak(reply, language)
            }
        }
    }

    // ------------------------------------------------------------------
    // Local-only capabilities (work with no network at all)
    // ------------------------------------------------------------------

    private fun handleMemorySave(text: String, language: String) {
        if (!memory.hasConsent()) {
            val message = if (language == "hi") {
                "मैं इसे इस डिवाइस पर याद रख सकता हूँ, लेकिन पहले आपकी अनुमति चाहिए। मेमोरी पैनल खोलिए।"
            } else {
                "I can remember that on this device, but I need your permission first. Please open the Memory panel."
            }
            _signals.tryEmit(UiSignal.Notice(message))
            finishLocalTurn(message, language, AnimationCue.PERMISSION_REQUEST)
            return
        }
        val key = "note-${memory.entryCount() + 1}"
        when (val result = memory.remember(key, text)) {
            is MemoryWriteResult.Stored -> {
                val message = if (result.evicted != null) {
                    if (language == "hi") "ठीक है, याद रख लिया। जगह बनाने के लिए मैंने अपनी सबसे पुरानी याद हटा दी।"
                    else "Okay, I remembered it. I let go of my oldest note to make room."
                } else {
                    OfflineResponses.memorySavedAck(language).text
                }
                finishLocalTurn(message, language, AnimationCue.MEMORY_SAVE)
            }
            MemoryWriteResult.RefusedNoConsent -> finishLocalTurn(
                OfflineResponses.privacyHelp(language).text, language, AnimationCue.PRIVACY_SHIELD,
            )
            is MemoryWriteResult.Rejected -> finishLocalTurn(
                if (language == "hi") "मैं यह याद नहीं रख पाया।" else "I could not remember that one.",
                language,
                AnimationCue.CONFUSED,
            )
        }
    }

    private fun handleMemoryList(language: String) {
        val entries = memory.snapshot()
        val message = when {
            !memory.hasConsent() -> if (language == "hi") {
                "मैंने अभी कुछ भी याद नहीं रखा है, क्योंकि मेमोरी की अनुमति बंद है।"
            } else {
                "I am not remembering anything, because local memory is turned off."
            }
            entries.isEmpty() -> OfflineResponses.nothingRemembered(language).text
            else -> {
                val shown = entries.take(MEMORY_LINES_SPOKEN).joinToString(". ") { it.value }
                val count = entries.size
                if (language == "hi") "मुझे इस डिवाइस पर $count बातें याद हैं: $shown"
                else "I remember $count things on this device: $shown"
            }
        }
        finishLocalTurn(message, language, AnimationCue.MEMORY_RECALL)
    }

    private fun handleMemoryDelete(language: String) {
        val removed = memory.deleteAll()
        val message = when {
            removed == 0 -> if (language == "hi") "मेरे पास हटाने के लिए कुछ भी नहीं था।"
            else "I did not have anything stored to delete."
            language == "hi" -> "ठीक है, मैंने इस डिवाइस से $removed यादें हटा दीं।"
            else -> "Okay, I deleted $removed memories from this device."
        }
        finishLocalTurn(message, language, AnimationCue.PRIVACY_SHIELD)
    }

    private fun handleVoiceCommand(text: String, language: String) {
        val normalized = TextNormalizer.normalize(text)
        when {
            normalized.containsAnyOf("stop listening", "pause listening", "band karo", "ruk jao", "रुको", "बंद करो", "चुप") -> {
                pendingStopAfterSpeech = true
                finishLocalTurn(
                    if (language == "hi") "ठीक है, मैं सुनना बंद कर रहा हूँ।" else "Okay, I will stop listening.",
                    language,
                    AnimationCue.MICROPHONE_OFF,
                )
            }
            normalized.containsAnyOf("sleep", "so jao", "rest mode", "सो जाओ", "आराम") -> {
                pendingStopAfterSpeech = true
                finishLocalTurn(
                    if (language == "hi") "ठीक है, मैं आराम कर रहा हूँ।" else "Okay, going to rest now.",
                    language,
                    AnimationCue.REST_MODE,
                )
            }
            normalized.containsAnyOf("wake up", "utho", "उठो", "start listening") -> {
                finishLocalTurn(
                    if (language == "hi") "मैं सुन रहा हूँ।" else VoiceMessages.WAKE_ACK,
                    language,
                    AnimationCue.GREETING,
                )
            }
            normalized.containsAnyOf("hindi", "हिंदी", "हिन्दी") -> {
                settings.hindiMode = true
                finishLocalTurn("ठीक है, अब मैं हिंदी में बात करूँगा।", "hi", AnimationCue.LANGUAGE_SWITCH)
            }
            normalized.containsAnyOf("english", "अंग्रेजी") -> {
                settings.hindiMode = false
                finishLocalTurn("Okay, switching back to English.", "en", AnimationCue.LANGUAGE_SWITCH)
            }
            normalized.containsAnyOf("reduced motion", "reduce motion", "कम एनिमेशन") -> {
                val next = !(settings.reducedMotionOverride ?: false)
                settings.reducedMotionOverride = next
                _signals.tryEmit(UiSignal.ReducedMotionChanged(next))
                finishLocalTurn(
                    if (next) "Okay, calmer animations from now on." else "Okay, full animations again.",
                    language,
                    AnimationCue.LANGUAGE_SWITCH,
                )
            }
            else -> finishLocalTurn(
                OfflineResponses.forIntent(UserIntent.SETTINGS_OR_VOICE_COMMAND, language).text,
                language,
                AnimationCue.SUCCESS,
            )
        }
    }

    private fun startFocusTimer(text: String, language: String) {
        val requestedMs = FocusTimerPolicy.parseRequestedDurationMs(text)
            ?: FocusTimerPolicy.DEFAULT_DURATION_MS
        val minutes = (requestedMs / 60_000L).toInt().coerceAtLeast(1)
        focusTimer = FocusTimerPolicy.start(clock(), minutes * 60_000L)
        _signals.tryEmit(UiSignal.FocusTimerStarted(minutes))
        showTransientCue(AnimationCue.COUNTDOWN, TRANSIENT_CUE_MS)
        finishLocalTurn(
            OfflineResponses.forIntent(UserIntent.TIMER_START, language, timerMinutes = minutes).text,
            language,
            AnimationCue.COUNTDOWN,
        )

        timerJob?.cancel()
        timerJob = scope.launch {
            delay(minutes * 60_000L)
            focusTimer = FocusTimerPolicy.tick(focusTimer, clock())
            _signals.tryEmit(UiSignal.FocusTimerComplete)
            showTransientCue(AnimationCue.TIMER_COMPLETE, CELEBRATION_CUE_MS)
            val message = if (language == "hi") "आपका फोकस टाइमर पूरा हुआ। शाबाश!"
            else "Your focus timer is done. Well done!"
            appendLine(Speaker.NEX, message)
            speak(message, language)
        }
    }

    /** Remaining time on the local focus timer, for the UI. */
    fun focusRemainingMs(): Long = focusTimer.remainingAt(clock())

    /** Finish a locally answered turn: one honest sentence, then back to listening. */
    private fun finishLocalTurn(message: String, language: String, cue: AnimationCue) {
        turnWatchdogJob?.cancel()
        _sources.value = emptyList()
        appendLine(Speaker.NEX, message)
        showTransientCue(cue, TRANSIENT_CUE_MS)
        speak(message, language)
    }

    // ------------------------------------------------------------------
    // Speech output
    // ------------------------------------------------------------------

    private fun speak(text: String, language: String) {
        val safe = text.trim()
        if (safe.isEmpty()) {
            dispatch(VoiceEvent.SpeechCompleted(clock()))
            return
        }
        expectedSpeechMs = estimateSpeechMs(safe)
        tts.speak(safe, language)
    }

    private fun estimateSpeechMs(text: String): Long = 900L + text.length * MS_PER_CHARACTER

    private fun armSpeechWatchdog() {
        speechWatchdogJob?.cancel()
        speechWatchdogJob = scope.launch {
            delay(expectedSpeechMs + TimingPolicy.SPEECH_COMPLETION_GRACE_MS)
            if (_state.value.isSpeaking) {
                NexLog.w("speech watchdog fired, releasing the microphone gate")
                dispatch(VoiceEvent.SpeechCompleted(clock()))
            }
        }
    }

    // ------------------------------------------------------------------
    // TtsVoice.Listener — always marshalled to the main thread
    // ------------------------------------------------------------------

    override fun onEngineReady() = onMain {
        NexLog.i("voice services ready")
        dispatch(VoiceEvent.VoiceServicesReady)
    }

    override fun onEngineUnavailable(reason: String) = onMain {
        dispatch(VoiceEvent.VoiceServicesUnavailable(reason, retryable = false))
    }

    override fun onSpeechStarted(utteranceId: String) = onMain {
        dispatch(VoiceEvent.SpeechStarted)
        armSpeechWatchdog()
    }

    override fun onSpeechCompleted(utteranceId: String) = onMain {
        speechWatchdogJob?.cancel()
        dispatch(VoiceEvent.SpeechCompleted(clock()))
        if (pendingStopAfterSpeech) {
            pendingStopAfterSpeech = false
            stopVoiceMode()
        }
    }

    override fun onSpeechFailed(utteranceId: String) = onMain {
        speechWatchdogJob?.cancel()
        // The answer stays on screen; the microphone gate still has to be released honestly.
        appendLine(Speaker.SYSTEM, "Voice output failed, so the answer stayed as text.")
        dispatch(VoiceEvent.SpeechCompleted(clock()))
    }

    override fun onLanguageFallback(requested: String, used: String) = onMain {
        appendLine(
            Speaker.SYSTEM,
            "This device cannot speak $requested, so I answered in $used.",
        )
    }

    // ------------------------------------------------------------------
    // Transcript and helpers
    // ------------------------------------------------------------------

    private fun appendLine(
        speaker: Speaker,
        text: String,
        grounded: Boolean = false,
        sources: List<SourceLink> = emptyList(),
    ) {
        if (text.isBlank()) return
        val line = TranscriptLine(
            speaker = speaker,
            text = text.trim(),
            grounded = grounded,
            sources = sources,
            atMs = clock(),
        )
        val updated = (_transcript.value + line)
        _transcript.value = if (updated.size > MAX_TRANSCRIPT_LINES) updated.takeLast(MAX_TRANSCRIPT_LINES) else updated
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun String.containsAnyOf(vararg needles: String): Boolean =
        needles.any { contains(it) }

    companion object {
        /** How long a transient animation cue stays on screen. */
        const val TRANSIENT_CUE_MS = 2_200L

        /** Celebration cues get a little longer. */
        const val CELEBRATION_CUE_MS = 3_000L

        /** If a turn has not produced any answer by now, NEX says so honestly. */
        const val TURN_WATCHDOG_MS = 45_000L

        private const val MAX_TRANSCRIPT_LINES = 40
        private const val MEMORY_LINES_SPOKEN = 5
        private const val MS_PER_CHARACTER = 62L
    }
}
