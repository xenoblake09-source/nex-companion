package com.nexora.robot.voice

/**
 * Every user-visible voice string NEX can show while conversing.
 *
 * These live in the pure-Kotlin core (not in `strings.xml`) for one reason: they are part of
 * the honesty contract and must be assertable by unit tests. Two rules matter most:
 *
 *  * a silent turn is **never** reported as an error — [SILENCE_KEEP_CALM] is the same calm
 *    "I'm listening." as an ordinary turn;
 *  * a real failure always says so, in [RECOVERING], without pretending to work.
 */
object VoiceMessages {

    /** Shown during a real voice-service recovery burst. Exact product copy. */
    const val RECOVERING = "VOICE SERVICE RECOVERING — PLEASE WAIT"

    const val IDLE = "Voice is off. Tap Start voice when you want me to listen."
    const val PERMISSION_BLOCKED = "Microphone permission is off, so I cannot listen."
    const val BACKGROUND_PAUSED = "Listening is paused while NEX is in the background."
    const val PREPARING = "Getting my voice ready…"
    const val LISTENING_FOR_WAKE = "Listening for “Hello NEX”."
    const val LISTENING = "I’m listening."
    const val RECOGNIZING = "Go on, I’m listening."
    const val THINKING = "Thinking…"
    const val ANSWER_READY = "Answer ready."
    const val SPEAKING = "Speaking…"

    /** Spoken and shown right after the wake phrase. */
    const val WAKE_ACK = "Hi! I’m listening."

    const val ERROR = "Something went wrong. Voice mode is paused."
    const val OFFLINE = "I am offline, so I cannot reach my server right now."
    const val NETWORK_WAITING = "My server is taking a while. Still trying."
    const val NO_SPEECH_SERVICE = "This device has no speech service I can use."
    const val RECOVERY_GIVE_UP = "My voice service is not recovering. Please toggle voice mode."

    /**
     * What NEX shows after an ordinary silent turn: the same calm listening copy.
     * Deliberately identical to [LISTENING] so silence never looks like an error.
     */
    const val SILENCE_KEEP_CALM = LISTENING

    const val CAMERA_IN_USE = "Camera is on for this question only."
    const val CAMERA_STOPPED = "Camera is off."

    /** Copy that must never appear anywhere in NEX. Present so tests can forbid it. */
    val FORBIDDEN_PHRASES: List<String> = listOf(
        "did not catch that",
        "didn't catch that",
        "i did not understand",
        "say that again",
        "could not hear you",
    )

    /** True when [message] is one of the phrases NEX must never show for normal silence. */
    fun isForbidden(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val lowered = message.lowercase()
        return FORBIDDEN_PHRASES.any { lowered.contains(it) }
    }
}
