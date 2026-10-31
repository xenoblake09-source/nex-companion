package com.nexora.robot.voice

import com.nexora.robot.animation.AnimationCue
import com.nexora.robot.animation.AnimationDirector

/**
 * The one deterministic controller/reducer for NEX.
 *
 * `reduce(state, event, clock) -> state` is a pure function: no Android types, no
 * coroutines, no randomness, no side effects. [SpeechController] is the only component that
 * performs real work (opening the microphone, calling TTS, calling the API) and it does so
 * by replaying what this reducer decided.
 *
 * The `when` below is exhaustive over the sealed [VoiceEvent] hierarchy, so adding an event
 * without handling it is a compile error rather than a silent bug.
 *
 * Microphone rules enforced here (and pinned by `VoiceReducerTest` / `TimingPolicyTest`):
 *  * no recognition turn may start while TTS or server speech is playing;
 *  * after speech ends the microphone stays closed for [TimingPolicy.POST_SPEECH_MIC_SETTLE_MS];
 *  * ordinary silence keeps a calm listening state and schedules a new legal turn after
 *    [TimingPolicy.NEW_TURN_AFTER_SILENCE_MS] — never an error message;
 *  * immediate recogniser / client / busy / network failures use [RetryPolicy] backoff
 *    (1.2 s, 2.5 s, 5 s, then 8 s maximum) inside one failure burst;
 *  * a successful utterance or a stable turn resets the burst;
 *  * backgrounding, permission loss or switching voice mode off stops everything.
 */
object VoiceReducer {

    /** A handler returns the *same* instance to say "this event is refused right now". */
    fun reduce(state: VoiceState, event: VoiceEvent, nowMs: Long): VoiceState {
        val next = transition(state, event, nowMs)
        return if (next === state) {
            state.copy(lastEventLabel = event.label, ignoredEvent = event.label)
        } else {
            next.copy(lastEventLabel = event.label, ignoredEvent = null)
        }
    }

    /** Fresh state for a new session, keeping the facts we already know. */
    fun initial(
        microphonePermissionGranted: Boolean = false,
        cameraPermissionGranted: Boolean = false,
        appInForeground: Boolean = true,
        voiceModeEnabled: Boolean = false,
    ): VoiceState = VoiceState(
        state = if (voiceModeEnabled) ConversationState.PREPARING_VOICE else ConversationState.IDLE,
        voiceModeEnabled = voiceModeEnabled,
        microphonePermissionGranted = microphonePermissionGranted,
        cameraPermissionGranted = cameraPermissionGranted,
        appInForeground = appInForeground,
        statusMessage = defaultStatusFor(
            if (voiceModeEnabled) ConversationState.PREPARING_VOICE else ConversationState.IDLE
        ),
        animationCue = AnimationDirector.cueForState(
            if (voiceModeEnabled) ConversationState.PREPARING_VOICE else ConversationState.IDLE
        ),
    )

    /** Canonical honest copy for a state, used when a more specific message does not apply. */
    fun defaultStatusFor(state: ConversationState): String = when (state) {
        ConversationState.IDLE -> VoiceMessages.IDLE
        ConversationState.PREPARING_VOICE -> VoiceMessages.PREPARING
        ConversationState.LISTENING_FOR_WAKE_PHRASE -> VoiceMessages.LISTENING_FOR_WAKE
        ConversationState.LISTENING_FOR_CONVERSATION -> VoiceMessages.LISTENING
        ConversationState.RECOGNIZING -> VoiceMessages.RECOGNIZING
        ConversationState.THINKING -> VoiceMessages.THINKING
        ConversationState.SPEAKING -> VoiceMessages.SPEAKING
        ConversationState.RECOVERING_VOICE_SERVICE -> VoiceMessages.RECOVERING
        ConversationState.PAUSED_OR_PERMISSION_BLOCKED -> VoiceMessages.PERMISSION_BLOCKED
        ConversationState.ERROR -> VoiceMessages.ERROR
    }

    // ------------------------------------------------------------------
    // Transition table
    // ------------------------------------------------------------------

    private fun transition(state: VoiceState, event: VoiceEvent, now: Long): VoiceState = when (event) {
        VoiceEvent.VoiceModeEnabled -> onVoiceModeEnabled(state, now)
        VoiceEvent.VoiceModeDisabled -> onVoiceModeDisabled(state)
        VoiceEvent.MicrophonePermissionGranted -> onMicrophonePermissionGranted(state, now)
        VoiceEvent.MicrophonePermissionRevoked -> onMicrophonePermissionRevoked(state)
        VoiceEvent.CameraPermissionGranted -> state.copy(cameraPermissionGranted = true)
        VoiceEvent.CameraPermissionRevoked -> state.copy(
            cameraPermissionGranted = false,
            cameraInUse = false,
            animationCue = AnimationDirector.cueForState(state.state),
            statusMessage = if (state.cameraInUse) VoiceMessages.CAMERA_STOPPED else state.statusMessage,
        )
        VoiceEvent.AppForegrounded -> onAppForegrounded(state, now)
        VoiceEvent.AppBackgrounded -> onAppBackgrounded(state)
        VoiceEvent.VoiceServicesPreparing -> onVoiceServicesPreparing(state)
        VoiceEvent.VoiceServicesReady -> onVoiceServicesReady(state, now)
        is VoiceEvent.VoiceServicesUnavailable -> onVoiceServicesUnavailable(state, event, now)
        VoiceEvent.WakePhraseDetected -> onWakePhraseDetected(state, now)
        VoiceEvent.WakePhraseWindowElapsed -> state
        VoiceEvent.MicrophoneOpened -> onMicrophoneOpened(state, now)
        is VoiceEvent.MicrophoneRejected -> onMicrophoneRejected(state, now)
        is VoiceEvent.Transcription -> onTranscription(state, event, now)
        VoiceEvent.RecognizerSilence -> onRecognizerSilence(state, now)
        is VoiceEvent.RecognizerFailure -> onRecognizerFailure(state, event, now)
        VoiceEvent.ThinkingStarted -> onThinkingStarted(state)
        is VoiceEvent.ReplyReady -> onReplyReady(state, event)
        is VoiceEvent.ServerUnavailable -> onServerUnavailable(state, event, now)
        VoiceEvent.SpeechStarted -> onSpeechStarted(state, now)
        is VoiceEvent.SpeechCompleted -> onSpeechCompleted(state, event)
        VoiceEvent.CameraSessionStarted -> onCameraSessionStarted(state)
        VoiceEvent.CameraSessionStopped -> onCameraSessionStopped(state)
        VoiceEvent.RecoverySucceeded -> onRecoverySucceeded(state, now)
        is VoiceEvent.Fatal -> onFatal(state, event)
        VoiceEvent.Reset -> initial(
            microphonePermissionGranted = state.microphonePermissionGranted,
            cameraPermissionGranted = state.cameraPermissionGranted,
            appInForeground = state.appInForeground,
            voiceModeEnabled = false,
        )
    }

    // ------------------------------------------------------------------
    // Consent, permission and lifecycle
    // ------------------------------------------------------------------

    private fun onVoiceModeEnabled(state: VoiceState, now: Long): VoiceState {
        val base = state.copy(
            voiceModeEnabled = true,
            retryAttempt = 0,
            failureBurstStartedAtMs = null,
            consecutiveSilences = 0,
            lastTurnWasSilence = false,
        )
        return when {
            !base.microphonePermissionGranted ->
                blocked(base, VoiceMessages.PERMISSION_BLOCKED, AnimationCue.PERMISSION_REQUEST)
            !base.appInForeground ->
                blocked(base, VoiceMessages.BACKGROUND_PAUSED, AnimationCue.MICROPHONE_OFF)
            else -> base.copy(
                state = ConversationState.PREPARING_VOICE,
                voiceServicesReady = false,
                microphoneOpen = false,
                micBlockedUntilMs = 0L,
                nextActionAtMs = now,
                statusMessage = VoiceMessages.PREPARING,
                animationCue = AnimationCue.QUIET_LISTENING,
            )
        }
    }

    private fun onVoiceModeDisabled(state: VoiceState): VoiceState = state.copy(
        state = ConversationState.IDLE,
        voiceModeEnabled = false,
        microphoneOpen = false,
        cameraInUse = false,
        voiceServicesReady = false,
        wakePhraseRequired = true,
        micBlockedUntilMs = 0L,
        nextActionAtMs = 0L,
        retryAttempt = 0,
        failureBurstStartedAtMs = null,
        consecutiveSilences = 0,
        lastTurnWasSilence = false,
        statusMessage = VoiceMessages.IDLE,
        animationCue = AnimationCue.CALM_IDLE_BREATHING,
    )

    private fun onMicrophonePermissionGranted(state: VoiceState, now: Long): VoiceState {
        val base = state.copy(microphonePermissionGranted = true)
        return when {
            !base.voiceModeEnabled -> idle(base)
            !base.appInForeground -> blocked(base, VoiceMessages.BACKGROUND_PAUSED, AnimationCue.MICROPHONE_OFF)
            else -> base.copy(
                state = ConversationState.PREPARING_VOICE,
                voiceServicesReady = false,
                microphoneOpen = false,
                retryAttempt = 0,
                failureBurstStartedAtMs = null,
                nextActionAtMs = now,
                statusMessage = VoiceMessages.PREPARING,
                animationCue = AnimationCue.QUIET_LISTENING,
            )
        }
    }

    private fun onMicrophonePermissionRevoked(state: VoiceState): VoiceState = blocked(
        state.copy(
            microphonePermissionGranted = false,
            voiceServicesReady = false,
            microphoneOpen = false,
            cameraInUse = false,
        ),
        VoiceMessages.PERMISSION_BLOCKED,
        AnimationCue.MICROPHONE_OFF,
    )

    private fun onAppForegrounded(state: VoiceState, now: Long): VoiceState {
        val base = state.copy(appInForeground = true)
        return when {
            !base.voiceModeEnabled -> idle(base)
            !base.microphonePermissionGranted ->
                blocked(base, VoiceMessages.PERMISSION_BLOCKED, AnimationCue.MICROPHONE_OFF)
            else -> base.copy(
                state = ConversationState.PREPARING_VOICE,
                voiceServicesReady = false,
                microphoneOpen = false,
                cameraInUse = false,
                nextActionAtMs = now,
                statusMessage = VoiceMessages.PREPARING,
                animationCue = AnimationCue.QUIET_LISTENING,
            )
        }
    }

    private fun onAppBackgrounded(state: VoiceState): VoiceState {
        val base = state.copy(
            appInForeground = false,
            microphoneOpen = false,
            cameraInUse = false,
            voiceServicesReady = false,
            micBlockedUntilMs = 0L,
            nextActionAtMs = 0L,
        )
        return if (base.voiceModeEnabled) {
            blocked(base, VoiceMessages.BACKGROUND_PAUSED, AnimationCue.MICROPHONE_OFF)
        } else {
            idle(base)
        }
    }

    // ------------------------------------------------------------------
    // Voice services
    // ------------------------------------------------------------------

    private fun onVoiceServicesPreparing(state: VoiceState): VoiceState {
        if (!state.voiceModeEnabled || !state.microphonePermissionGranted || !state.appInForeground) return state
        return state.copy(
            state = ConversationState.PREPARING_VOICE,
            voiceServicesReady = false,
            microphoneOpen = false,
            nextActionAtMs = 0L,
            statusMessage = VoiceMessages.PREPARING,
            animationCue = AnimationCue.QUIET_LISTENING,
        )
    }

    private fun onVoiceServicesReady(state: VoiceState, now: Long): VoiceState {
        val base = state.copy(voiceServicesReady = true)
        return when {
            !base.voiceModeEnabled -> idle(base)
            !base.microphonePermissionGranted ->
                blocked(base, VoiceMessages.PERMISSION_BLOCKED, AnimationCue.MICROPHONE_OFF)
            !base.appInForeground ->
                blocked(base, VoiceMessages.BACKGROUND_PAUSED, AnimationCue.MICROPHONE_OFF)
            else -> enterListening(base, now)
        }
    }

    private fun onVoiceServicesUnavailable(
        state: VoiceState,
        event: VoiceEvent.VoiceServicesUnavailable,
        now: Long,
    ): VoiceState {
        if (!state.voiceModeEnabled) return state
        val base = state.copy(voiceServicesReady = false, microphoneOpen = false)
        return if (event.retryable) {
            enterRecovery(base, RecognizerFailureKind.UNAVAILABLE, now)
        } else {
            blocked(
                base,
                event.reason.ifBlank { VoiceMessages.NO_SPEECH_SERVICE },
                AnimationCue.MICROPHONE_OFF,
            )
        }
    }

    // ------------------------------------------------------------------
    // Wake phrase and recognition turns
    // ------------------------------------------------------------------

    private fun onWakePhraseDetected(state: VoiceState, now: Long): VoiceState {
        // Never accept a wake phrase while NEX is speaking.
        if (state.isSpeaking) return state
        // A wake phrase only means something while NEX is actually hunting for one.
        if (state.state != ConversationState.LISTENING_FOR_WAKE_PHRASE) return state
        val gate = now + TimingPolicy.POST_SPEECH_MIC_SETTLE_MS
        return state.copy(
            state = ConversationState.LISTENING_FOR_CONVERSATION,
            wakePhraseRequired = false,
            microphoneOpen = false,
            consecutiveSilences = 0,
            lastTurnWasSilence = false,
            // Conservative gate until the acknowledgement has finished and TTS reports done.
            micBlockedUntilMs = maxOf(state.micBlockedUntilMs, gate),
            nextActionAtMs = maxOf(state.micBlockedUntilMs, gate),
            statusMessage = VoiceMessages.WAKE_ACK,
            animationCue = AnimationCue.WAKE_WORD_DETECTED,
        )
    }

    private fun onMicrophoneOpened(state: VoiceState, now: Long): VoiceState {
        if (state.isSpeaking || state.isThinking) return state
        if (!state.canOpenMicrophone(now)) return state
        return state.copy(
            state = ConversationState.RECOGNIZING,
            microphoneOpen = true,
            lastTurnWasSilence = false,
            // A successful open ends any recovery burst.
            retryAttempt = 0,
            failureBurstStartedAtMs = null,
            nextActionAtMs = 0L,
            statusMessage = VoiceMessages.RECOGNIZING,
            animationCue = AnimationCue.LISTENING,
        )
    }

    private fun onMicrophoneRejected(state: VoiceState, now: Long): VoiceState {
        if (!state.voiceModeEnabled || state.isBlocked) return state
        // The technical reason stays in logs; the visible copy comes from enterRecovery.
        return enterRecovery(state.copy(microphoneOpen = false), RecognizerFailureKind.CLIENT, now)
    }

    private fun onTranscription(state: VoiceState, event: VoiceEvent.Transcription, now: Long): VoiceState {
        // A recognition result is never accepted while NEX is speaking.
        if (state.isSpeaking) return state
        val text = event.text.trim()
        if (text.isEmpty()) return onRecognizerSilence(state, now)
        return state.copy(
            state = ConversationState.THINKING,
            microphoneOpen = false,
            lastTranscript = text,
            lastTurnWasSilence = false,
            consecutiveSilences = 0,
            retryAttempt = 0,
            failureBurstStartedAtMs = null,
            micBlockedUntilMs = now,
            nextActionAtMs = 0L,
            statusMessage = VoiceMessages.THINKING,
            animationCue = AnimationCue.THINKING,
        )
    }

    private fun onRecognizerSilence(state: VoiceState, now: Long): VoiceState {
        if (state.isSpeaking || state.isThinking) return state
        if (!state.voiceModeEnabled) return state
        val wakeMode = state.wakePhraseRequired
        return state.copy(
            state = if (wakeMode) {
                ConversationState.LISTENING_FOR_WAKE_PHRASE
            } else {
                ConversationState.LISTENING_FOR_CONVERSATION
            },
            microphoneOpen = false,
            consecutiveSilences = state.consecutiveSilences + 1,
            lastTurnWasSilence = true,
            // retryAttempt and the failure burst are deliberately untouched: silence is not a failure.
            nextActionAtMs = TimingPolicy.nextTurnAfterSilence(now),
            statusMessage = if (wakeMode) VoiceMessages.LISTENING_FOR_WAKE else VoiceMessages.SILENCE_KEEP_CALM,
            animationCue = AnimationCue.LISTENING,
        )
    }

    private fun onRecognizerFailure(
        state: VoiceState,
        event: VoiceEvent.RecognizerFailure,
        now: Long,
    ): VoiceState {
        if (state.isSpeaking) return state
        if (!state.voiceModeEnabled) return state
        return if (event.kind == RecognizerFailureKind.PERMISSION) {
            onMicrophonePermissionRevoked(state)
        } else {
            enterRecovery(state.copy(microphoneOpen = false), event.kind, now)
        }
    }

    // ------------------------------------------------------------------
    // Turn progression
    // ------------------------------------------------------------------

    private fun onThinkingStarted(state: VoiceState): VoiceState {
        if (state.isSpeaking) return state
        return state.copy(
            state = ConversationState.THINKING,
            microphoneOpen = false,
            nextActionAtMs = 0L,
            statusMessage = VoiceMessages.THINKING,
            animationCue = AnimationCue.THINKING,
        )
    }

    private fun onReplyReady(state: VoiceState, event: VoiceEvent.ReplyReady): VoiceState {
        if (state.isSpeaking) return state
        return state.copy(
            statusMessage = VoiceMessages.ANSWER_READY,
            animationCue = if (event.grounded) {
                AnimationDirector.cueForGrounding(true, event.sourceCount)
            } else {
                AnimationCue.ANSWER_READY
            },
        )
    }

    private fun onServerUnavailable(
        state: VoiceState,
        event: VoiceEvent.ServerUnavailable,
        now: Long,
    ): VoiceState {
        if (state.state != ConversationState.THINKING) return state
        val wakeMode = state.wakePhraseRequired
        return state.copy(
            state = if (wakeMode) {
                ConversationState.LISTENING_FOR_WAKE_PHRASE
            } else {
                ConversationState.LISTENING_FOR_CONVERSATION
            },
            microphoneOpen = false,
            nextActionAtMs = TimingPolicy.nextTurnAfterSilence(now),
            statusMessage = if (event.offline) VoiceMessages.OFFLINE else VoiceMessages.NETWORK_WAITING,
            animationCue = if (event.offline) AnimationCue.OFFLINE else AnimationCue.NETWORK_WAITING,
        )
    }

    private fun onSpeechStarted(state: VoiceState, now: Long): VoiceState = state.copy(
        state = ConversationState.SPEAKING,
        microphoneOpen = false,
        lastTurnWasSilence = false,
        // The microphone is closed for the whole of this state, and stays closed afterwards
        // until the settle window has passed.
        micBlockedUntilMs = maxOf(state.micBlockedUntilMs, now),
        nextActionAtMs = 0L,
        statusMessage = VoiceMessages.SPEAKING,
        animationCue = AnimationCue.SPEAKING,
    )

    private fun onSpeechCompleted(state: VoiceState, event: VoiceEvent.SpeechCompleted): VoiceState {
        if (!state.voiceModeEnabled) return state
        if (state.isBlocked || state.isError) return state
        val gate = TimingPolicy.gateAfterSpeechCompleted(maxOf(event.atMs, 0L))
        val wakeMode = state.wakePhraseRequired
        return state.copy(
            state = if (wakeMode) {
                ConversationState.LISTENING_FOR_WAKE_PHRASE
            } else {
                ConversationState.LISTENING_FOR_CONVERSATION
            },
            microphoneOpen = false,
            voiceServicesReady = true,
            micBlockedUntilMs = gate,
            nextActionAtMs = gate,
            // A completed, stable turn resets the recovery burst.
            retryAttempt = 0,
            failureBurstStartedAtMs = null,
            consecutiveSilences = 0,
            lastTurnWasSilence = false,
            statusMessage = if (wakeMode) VoiceMessages.LISTENING_FOR_WAKE else VoiceMessages.LISTENING,
            animationCue = AnimationCue.LISTENING,
        )
    }

    // ------------------------------------------------------------------
    // Camera
    // ------------------------------------------------------------------

    private fun onCameraSessionStarted(state: VoiceState): VoiceState = state.copy(
        cameraInUse = true,
        statusMessage = VoiceMessages.CAMERA_IN_USE,
        animationCue = AnimationCue.CAMERA_ACTIVE,
    )

    private fun onCameraSessionStopped(state: VoiceState): VoiceState = state.copy(
        cameraInUse = false,
        statusMessage = if (state.statusMessage == VoiceMessages.CAMERA_IN_USE) {
            defaultStatusFor(state.state)
        } else {
            state.statusMessage
        },
        animationCue = AnimationDirector.cueForState(state.state),
    )

    // ------------------------------------------------------------------
    // Recovery and failure
    // ------------------------------------------------------------------

    private fun onRecoverySucceeded(state: VoiceState, now: Long): VoiceState {
        if (!state.voiceModeEnabled) return state
        if (state.isBlocked || state.isError) return state
        return enterListening(
            state.copy(
                voiceServicesReady = true,
                retryAttempt = 0,
                failureBurstStartedAtMs = null,
            ),
            now,
        )
    }

    private fun onFatal(state: VoiceState, event: VoiceEvent.Fatal): VoiceState = state.copy(
        state = ConversationState.ERROR,
        microphoneOpen = false,
        cameraInUse = false,
        nextActionAtMs = 0L,
        statusMessage = event.message.ifBlank { VoiceMessages.ERROR },
        animationCue = AnimationCue.ERROR,
    )

    // ------------------------------------------------------------------
    // Shared transitions
    // ------------------------------------------------------------------

    private fun enterListening(state: VoiceState, now: Long): VoiceState {
        val wakeMode = state.wakePhraseRequired
        val gate = maxOf(now, state.micBlockedUntilMs)
        return state.copy(
            state = if (wakeMode) {
                ConversationState.LISTENING_FOR_WAKE_PHRASE
            } else {
                ConversationState.LISTENING_FOR_CONVERSATION
            },
            microphoneOpen = false,
            nextActionAtMs = gate,
            statusMessage = if (wakeMode) VoiceMessages.LISTENING_FOR_WAKE else VoiceMessages.LISTENING,
            animationCue = AnimationCue.LISTENING,
        )
    }

    private fun enterRecovery(
        state: VoiceState,
        kind: RecognizerFailureKind,
        now: Long,
    ): VoiceState {
        val (burstStart, attempt) = RetryPolicy.nextAttempt(state.failureBurstStartedAtMs, state.retryAttempt, now)
        val exhausted = RetryPolicy.shouldAskUser(attempt)
        val message = when {
            kind == RecognizerFailureKind.UNAVAILABLE -> VoiceMessages.NO_SPEECH_SERVICE
            exhausted -> VoiceMessages.RECOVERY_GIVE_UP
            else -> VoiceMessages.RECOVERING
        }
        return state.copy(
            state = ConversationState.RECOVERING_VOICE_SERVICE,
            microphoneOpen = false,
            retryAttempt = attempt,
            failureBurstStartedAtMs = burstStart,
            lastTurnWasSilence = false,
            micBlockedUntilMs = 0L,
            // Stop retrying silently once the burst is exhausted; the user decides what happens next.
            nextActionAtMs = if (exhausted) 0L else now + RetryPolicy.backoffFor(attempt),
            statusMessage = message,
            animationCue = if (kind == RecognizerFailureKind.NETWORK) {
                AnimationCue.NETWORK_WAITING
            } else {
                AnimationCue.VOICE_RECOVERING
            },
        )
    }

    private fun blocked(state: VoiceState, message: String, cue: AnimationCue): VoiceState = state.copy(
        state = ConversationState.PAUSED_OR_PERMISSION_BLOCKED,
        microphoneOpen = false,
        cameraInUse = false,
        voiceServicesReady = false,
        micBlockedUntilMs = 0L,
        nextActionAtMs = 0L,
        retryAttempt = 0,
        failureBurstStartedAtMs = null,
        statusMessage = message,
        animationCue = cue,
    )

    private fun idle(state: VoiceState): VoiceState = state.copy(
        state = ConversationState.IDLE,
        microphoneOpen = false,
        cameraInUse = false,
        micBlockedUntilMs = 0L,
        nextActionAtMs = 0L,
        statusMessage = VoiceMessages.IDLE,
        animationCue = AnimationCue.CALM_IDLE_BREATHING,
    )
}
