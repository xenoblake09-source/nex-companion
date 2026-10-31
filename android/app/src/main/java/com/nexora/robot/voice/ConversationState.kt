package com.nexora.robot.voice

/**
 * The one deterministic conversation state machine for NEX.
 *
 * Exactly ten states, no hidden sub-states. Every transition happens in
 * [VoiceReducer.reduce], which is a pure function of (state, event, clock) — that is what
 * makes microphone timing rules testable without a device.
 */
enum class ConversationState {
    /** Voice mode is off. Nothing is listening, nothing is recorded. */
    IDLE,

    /** Voice services (recogniser / TTS) are being initialised after explicit consent. */
    PREPARING_VOICE,

    /** Foreground wake-word hunting for "Hello NEX". Foreground only, by design. */
    LISTENING_FOR_WAKE_PHRASE,

    /** Calm conversational listening; no wake phrase needed between turns. */
    LISTENING_FOR_CONVERSATION,

    /** A recognition turn is running and the microphone is open. */
    RECOGNIZING,

    /** The utterance is with the server (or being answered offline). */
    THINKING,

    /** NEX is speaking. The microphone must stay closed for the whole of this state. */
    SPEAKING,

    /** A voice-service failure burst is being retried with bounded backoff. */
    RECOVERING_VOICE_SERVICE,

    /** Blocked: backgrounded, permission revoked, or voice mode switched off. */
    PAUSED_OR_PERMISSION_BLOCKED,

    /** A hard failure. NEX stops and waits for the user. */
    ERROR,
}
