package com.nexora.robot.voice

import com.nexora.robot.animation.AnimationCue
import com.nexora.robot.privacy.MicDecision
import com.nexora.robot.privacy.PermissionPolicy

/**
 * The complete, immutable value of the conversation state machine.
 *
 * [VoiceReducer.reduce] is the only way to produce a new instance: `(state, event, clock) ->
 * state`. Nothing else may mutate it, which is what makes the microphone rules checkable.
 */
data class VoiceState(
    val state: ConversationState = ConversationState.IDLE,
    val voiceModeEnabled: Boolean = false,
    val microphonePermissionGranted: Boolean = false,
    val cameraPermissionGranted: Boolean = false,
    val appInForeground: Boolean = true,
    val voiceServicesReady: Boolean = false,
    val microphoneOpen: Boolean = false,
    val cameraInUse: Boolean = false,
    /** False once the wake phrase has been heard; stays false for the whole foreground session. */
    val wakePhraseRequired: Boolean = true,
    /** Earliest instant the microphone may reopen (audio-route settling after speech). */
    val micBlockedUntilMs: Long = 0L,
    /** When the controller should next act. `0` means nothing is scheduled. */
    val nextActionAtMs: Long = 0L,
    val retryAttempt: Int = 0,
    val failureBurstStartedAtMs: Long? = null,
    val consecutiveSilences: Int = 0,
    val lastTurnWasSilence: Boolean = false,
    val statusMessage: String = VoiceMessages.IDLE,
    val lastTranscript: String = "",
    /** Class name of the last event. Never contains user speech. */
    val lastEventLabel: String = "",
    /** Set when an event was refused by the state machine (for example, during speech). */
    val ignoredEvent: String? = null,
    val animationCue: AnimationCue = AnimationCue.CALM_IDLE_BREATHING,
) {

    val isSpeaking: Boolean get() = state == ConversationState.SPEAKING

    val isThinking: Boolean get() = state == ConversationState.THINKING

    val isRecovering: Boolean get() = state == ConversationState.RECOVERING_VOICE_SERVICE

    val isBlocked: Boolean get() = state == ConversationState.PAUSED_OR_PERMISSION_BLOCKED

    val isError: Boolean get() = state == ConversationState.ERROR

    val isListeningForWakePhrase: Boolean get() = state == ConversationState.LISTENING_FOR_WAKE_PHRASE

    val isInConversation: Boolean get() = state == ConversationState.LISTENING_FOR_CONVERSATION

    /** The single microphone gate. Everything else defers to [PermissionPolicy]. */
    fun micDecision(nowMs: Long): MicDecision = PermissionPolicy.microphoneDecision(
        microphonePermissionGranted = microphonePermissionGranted,
        appInForeground = appInForeground,
        voiceModeEnabled = voiceModeEnabled,
        speaking = isSpeaking,
        thinking = isThinking,
        micBlockedUntilMs = micBlockedUntilMs,
        nowMs = nowMs,
        microphoneAlreadyOpen = microphoneOpen,
    )

    fun canOpenMicrophone(nowMs: Long): Boolean = micDecision(nowMs) == MicDecision.Allowed

    /** True when a scheduled action is due. */
    fun isActionDue(nowMs: Long): Boolean = nextActionAtMs > 0L && nowMs >= nextActionAtMs

    /** True while a recovery burst is inside its window. */
    fun isInsideFailureBurst(nowMs: Long): Boolean =
        RetryPolicy.isInsideBurst(failureBurstStartedAtMs, nowMs)
}
