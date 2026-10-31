package com.nexora.robot.voice

/**
 * What a recogniser turn produced: ordinary silence, or a real failure.
 *
 * Keeping these apart is a product rule, not a detail. A silent turn must never be shown
 * as "did not catch that", and a broken recogniser must never be treated as silence.
 */
sealed interface RecognitionOutcome {

    /** The user simply did not speak. Normal, calm, retry quickly. */
    data object NormalSilence : RecognitionOutcome

    /** Something actually failed. Retry only when [retryable], with bounded backoff. */
    data class Failure(
        val kind: RecognizerFailureKind,
        val retryable: Boolean,
        val errorCode: Int,
        val userMessage: String,
    ) : RecognitionOutcome
}

/**
 * Pure mapping from Android `SpeechRecognizer` error codes to NEX behaviour.
 *
 * The numeric values mirror `android.speech.SpeechRecognizer` (AOSP):
 * NETWORK_TIMEOUT=1, NETWORK=2, AUDIO=3, SERVER=4, CLIENT=5, SPEECH_TIMEOUT=6, NO_MATCH=7,
 * RECOGNIZER_BUSY=8, INSUFFICIENT_PERMISSIONS=9, TOO_MANY_REQUESTS=10, SERVER_DISCONNECTED=11,
 * LANGUAGE_NOT_SUPPORTED=12, LANGUAGE_UNAVAILABLE=13, CANNOT_CHECK_SUPPORT=14.
 * They are mirrored as plain constants so this rule stays unit testable on the JVM.
 */
object RecognizerErrorPolicy {

    const val ERROR_NETWORK_TIMEOUT = 1
    const val ERROR_NETWORK = 2
    const val ERROR_AUDIO = 3
    const val ERROR_SERVER = 4
    const val ERROR_CLIENT = 5
    const val ERROR_SPEECH_TIMEOUT = 6
    const val ERROR_NO_MATCH = 7
    const val ERROR_RECOGNIZER_BUSY = 8
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9
    const val ERROR_TOO_MANY_REQUESTS = 10
    const val ERROR_SERVER_DISCONNECTED = 11
    const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    const val ERROR_LANGUAGE_UNAVAILABLE = 13
    const val ERROR_CANNOT_CHECK_SUPPORT = 14

    /** Codes that mean "nobody spoke" once the recogniser has genuinely been listening. */
    private val SILENCE_CODES = setOf(ERROR_NO_MATCH, ERROR_SPEECH_TIMEOUT)

    const val MESSAGE_SILENCE_KEEPS_CALM = ""
    const val MESSAGE_CLIENT = "My voice service hiccuped. One moment."
    const val MESSAGE_BUSY = "My voice service is busy. One moment."
    const val MESSAGE_NETWORK = "I could not reach the voice service. Checking again."
    const val MESSAGE_PERMISSION = "Microphone permission is off, so I cannot listen."
    const val MESSAGE_UNAVAILABLE = "This device has no speech service I can use."
    const val MESSAGE_OTHER = "My voice service needs a moment."

    /**
     * @param errorCode raw `SpeechRecognizer` error code
     * @param elapsedSinceStartMs how long the turn had been open when the error arrived
     */
    fun classify(errorCode: Int, elapsedSinceStartMs: Long): RecognitionOutcome {
        val immediate = elapsedSinceStartMs < TimingPolicy.IMMEDIATE_FAILURE_WINDOW_MS

        if (errorCode in SILENCE_CODES) {
            // A real "nobody spoke" takes seconds. The same code arriving instantly means the
            // recogniser bailed out, which is a client failure and must be retried with backoff.
            return if (immediate) {
                RecognitionOutcome.Failure(
                    kind = RecognizerFailureKind.CLIENT,
                    retryable = true,
                    errorCode = errorCode,
                    userMessage = MESSAGE_CLIENT,
                )
            } else {
                RecognitionOutcome.NormalSilence
            }
        }

        return when (errorCode) {
            ERROR_CLIENT, ERROR_AUDIO -> failure(RecognizerFailureKind.CLIENT, MESSAGE_CLIENT, errorCode, retryable = true)
            ERROR_RECOGNIZER_BUSY, ERROR_TOO_MANY_REQUESTS ->
                failure(RecognizerFailureKind.BUSY, MESSAGE_BUSY, errorCode, retryable = true)
            ERROR_NETWORK, ERROR_NETWORK_TIMEOUT, ERROR_SERVER, ERROR_SERVER_DISCONNECTED ->
                failure(RecognizerFailureKind.NETWORK, MESSAGE_NETWORK, errorCode, retryable = true)
            ERROR_INSUFFICIENT_PERMISSIONS ->
                failure(RecognizerFailureKind.PERMISSION, MESSAGE_PERMISSION, errorCode, retryable = false)
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE, ERROR_CANNOT_CHECK_SUPPORT ->
                failure(RecognizerFailureKind.UNAVAILABLE, MESSAGE_UNAVAILABLE, errorCode, retryable = false)
            else -> failure(RecognizerFailureKind.OTHER, MESSAGE_OTHER, errorCode, retryable = true)
        }
    }

    /** True when the outcome must never be shown as a "did not catch that" style error. */
    fun isOrdinarySilence(outcome: RecognitionOutcome): Boolean = outcome is RecognitionOutcome.NormalSilence

    private fun failure(
        kind: RecognizerFailureKind,
        message: String,
        errorCode: Int,
        retryable: Boolean,
    ): RecognitionOutcome.Failure =
        RecognitionOutcome.Failure(kind = kind, retryable = retryable, errorCode = errorCode, userMessage = message)
}
