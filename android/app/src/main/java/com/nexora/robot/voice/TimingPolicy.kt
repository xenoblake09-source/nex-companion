package com.nexora.robot.voice

/**
 * Fixed timing constants for the microphone.
 *
 * These numbers are the product's stability contract and are asserted by
 * `TimingPolicyTest`, so they cannot drift silently:
 *
 *  * after speech ends the audio route needs [POST_SPEECH_MIC_SETTLE_MS] before the
 *    microphone may reopen — never start a recognition turn while TTS is still playing;
 *  * a recognition turn that ends in ordinary silence is **normal**, so NEX stays calm and
 *    opens a new legal turn after [NEW_TURN_AFTER_SILENCE_MS].
 */
object TimingPolicy {

    /** Minimum delay after speech completes before the microphone may reopen. */
    const val POST_SPEECH_MIC_SETTLE_MS = 850L

    /** Delay before starting a new recognition turn after a normal silent turn. */
    const val NEW_TURN_AFTER_SILENCE_MS = 650L

    /** How long one recognition turn may stay open before Android ends it anyway. */
    const val RECOGNITION_TURN_BUDGET_MS = 12_000L

    /** An error this soon after start is a broken service, not a silent user. */
    const val IMMEDIATE_FAILURE_WINDOW_MS = 400L

    /** Minimum gap between two recognition turns, to avoid restarting in the same frame. */
    const val MIN_TURN_GAP_MS = 120L

    /** Grace period for TTS to report completion before we assume it is stuck. */
    const val SPEECH_COMPLETION_GRACE_MS = 30_000L

    fun gateAfterSpeechCompleted(speechCompletedAtMs: Long): Long =
        speechCompletedAtMs + POST_SPEECH_MIC_SETTLE_MS

    fun nextTurnAfterSilence(silenceAtMs: Long): Long =
        silenceAtMs + NEW_TURN_AFTER_SILENCE_MS
}
