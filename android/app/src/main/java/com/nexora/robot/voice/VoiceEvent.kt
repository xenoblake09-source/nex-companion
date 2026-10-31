package com.nexora.robot.voice

/** Why a speech-recogniser turn failed. */
enum class RecognizerFailureKind {
    /** The recogniser client itself broke (ERROR_CLIENT / ERROR_AUDIO). */
    CLIENT,

    /** Another recognition session owns the microphone (ERROR_RECOGNIZER_BUSY). */
    BUSY,

    /** No recogniser service on the device (ERROR_INSUFFICIENT_PERMISSIONS is handled separately). */
    UNAVAILABLE,

    /** Network or upstream service problem. */
    NETWORK,

    /** Microphone permission is missing or was revoked. */
    PERMISSION,

    /** Anything else that is not ordinary silence. */
    OTHER,
}

/**
 * Every input the state machine accepts. Android glue ([SpeechController]) is only allowed
 * to talk to NEX through these events, which keeps [VoiceReducer] a pure function.
 */
sealed interface VoiceEvent {

    // --- consent, permissions and lifecycle ------------------------------
    data object VoiceModeEnabled : VoiceEvent
    data object VoiceModeDisabled : VoiceEvent
    data object MicrophonePermissionGranted : VoiceEvent
    data object MicrophonePermissionRevoked : VoiceEvent
    data object CameraPermissionGranted : VoiceEvent
    data object CameraPermissionRevoked : VoiceEvent
    data object AppForegrounded : VoiceEvent
    data object AppBackgrounded : VoiceEvent

    // --- voice services ---------------------------------------------------
    data object VoiceServicesPreparing : VoiceEvent
    data object VoiceServicesReady : VoiceEvent
    data class VoiceServicesUnavailable(val reason: String, val retryable: Boolean = true) : VoiceEvent

    // --- wake phrase and recognition --------------------------------------
    data object WakePhraseDetected : VoiceEvent
    data object WakePhraseWindowElapsed : VoiceEvent
    data object MicrophoneOpened : VoiceEvent
    data class MicrophoneRejected(val reason: String) : VoiceEvent
    data class Transcription(val text: String) : VoiceEvent
    data object RecognizerSilence : VoiceEvent
    data class RecognizerFailure(val kind: RecognizerFailureKind) : VoiceEvent

    // --- turn progression --------------------------------------------------
    data object ThinkingStarted : VoiceEvent
    data class ReplyReady(val grounded: Boolean, val sourceCount: Int) : VoiceEvent
    data class ServerUnavailable(val offline: Boolean) : VoiceEvent
    data object SpeechStarted : VoiceEvent
    data class SpeechCompleted(val atMs: Long) : VoiceEvent

    // --- camera ------------------------------------------------------------
    data object CameraSessionStarted : VoiceEvent
    data object CameraSessionStopped : VoiceEvent

    // --- recovery and failure ----------------------------------------------
    data object RecoverySucceeded : VoiceEvent
    data class Fatal(val message: String) : VoiceEvent
    data object Reset : VoiceEvent
}

/**
 * Stable label used for logs and tests.
 *
 * This is the *class name only*: a label never carries user speech, so it is safe to log.
 */
val VoiceEvent.label: String
    get() = this::class.simpleName ?: "UnknownEvent"
