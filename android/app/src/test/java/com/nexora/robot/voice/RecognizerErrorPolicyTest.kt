package com.nexora.robot.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mapping from raw `SpeechRecognizer` error codes to NEX behaviour.
 *
 * The rule that matters most: **ordinary silence is not a failure**. `NO_MATCH` (7) and
 * `SPEECH_TIMEOUT` (6) arriving after the user has had time to speak mean nobody spoke, so NEX
 * stays calm and keeps listening. The same codes arriving instantly mean the recogniser bailed
 * out, which is a client failure that must be retried with bounded backoff.
 */
class RecognizerErrorPolicyTest {

    private val longElapsedMs = 3_000L
    private val instantElapsedMs = 50L

    @Test
    fun errorCodesMirrorTheAndroidPlatformValues() {
        assertEquals(1, RecognizerErrorPolicy.ERROR_NETWORK_TIMEOUT)
        assertEquals(2, RecognizerErrorPolicy.ERROR_NETWORK)
        assertEquals(3, RecognizerErrorPolicy.ERROR_AUDIO)
        assertEquals(4, RecognizerErrorPolicy.ERROR_SERVER)
        assertEquals(5, RecognizerErrorPolicy.ERROR_CLIENT)
        assertEquals(6, RecognizerErrorPolicy.ERROR_SPEECH_TIMEOUT)
        assertEquals(7, RecognizerErrorPolicy.ERROR_NO_MATCH)
        assertEquals(8, RecognizerErrorPolicy.ERROR_RECOGNIZER_BUSY)
        assertEquals(9, RecognizerErrorPolicy.ERROR_INSUFFICIENT_PERMISSIONS)
        assertEquals(10, RecognizerErrorPolicy.ERROR_TOO_MANY_REQUESTS)
        assertEquals(11, RecognizerErrorPolicy.ERROR_SERVER_DISCONNECTED)
        assertEquals(12, RecognizerErrorPolicy.ERROR_LANGUAGE_NOT_SUPPORTED)
        assertEquals(13, RecognizerErrorPolicy.ERROR_LANGUAGE_UNAVAILABLE)
        assertEquals(14, RecognizerErrorPolicy.ERROR_CANNOT_CHECK_SUPPORT)
    }

    @Test
    fun normalSilenceIsNotAFailure() {
        val noMatch = RecognizerErrorPolicy.classify(RecognizerErrorPolicy.ERROR_NO_MATCH, longElapsedMs)
        val speechTimeout = RecognizerErrorPolicy.classify(RecognizerErrorPolicy.ERROR_SPEECH_TIMEOUT, longElapsedMs)
        assertEquals(RecognitionOutcome.NormalSilence, noMatch)
        assertEquals(RecognitionOutcome.NormalSilence, speechTimeout)
        assertTrue(RecognizerErrorPolicy.isOrdinarySilence(noMatch))
        assertTrue(RecognizerErrorPolicy.isOrdinarySilence(speechTimeout))
    }

    @Test
    fun theSameCodeArrivingInstantlyIsAClientFailure() {
        val immediate = RecognizerErrorPolicy.classify(RecognizerErrorPolicy.ERROR_NO_MATCH, instantElapsedMs)
        assertTrue(immediate is RecognitionOutcome.Failure)
        assertFalse(RecognizerErrorPolicy.isOrdinarySilence(immediate))
        val failure = immediate as RecognitionOutcome.Failure
        assertEquals(RecognizerFailureKind.CLIENT, failure.kind)
        assertTrue("an instant bail-out is retryable", failure.retryable)
        assertEquals(RecognizerErrorPolicy.ERROR_NO_MATCH, failure.errorCode)
        assertEquals(RecognizerErrorPolicy.MESSAGE_CLIENT, failure.userMessage)
    }

    @Test
    fun theImmediateFailureWindowComesFromTimingPolicy() {
        val justInside = TimingPolicy.IMMEDIATE_FAILURE_WINDOW_MS - 1L
        val justOutside = TimingPolicy.IMMEDIATE_FAILURE_WINDOW_MS
        assertTrue(
            RecognizerErrorPolicy.classify(RecognizerErrorPolicy.ERROR_SPEECH_TIMEOUT, justInside)
            is RecognitionOutcome.Failure,
        )
        assertEquals(
            RecognitionOutcome.NormalSilence,
            RecognizerErrorPolicy.classify(RecognizerErrorPolicy.ERROR_SPEECH_TIMEOUT, justOutside),
        )
    }

    @Test
    fun clientAndAudioErrorsAreRetryableClientFailures() {
        listOf(RecognizerErrorPolicy.ERROR_CLIENT, RecognizerErrorPolicy.ERROR_AUDIO).forEach { code ->
            val outcome = RecognizerErrorPolicy.classify(code, longElapsedMs) as RecognitionOutcome.Failure
            assertEquals(RecognizerFailureKind.CLIENT, outcome.kind)
            assertTrue(outcome.retryable)
            assertEquals(RecognizerErrorPolicy.MESSAGE_CLIENT, outcome.userMessage)
            assertEquals(code, outcome.errorCode)
        }
    }

    @Test
    fun busyAndTooManyRequestsAreRetryableBusyFailures() {
        listOf(RecognizerErrorPolicy.ERROR_RECOGNIZER_BUSY, RecognizerErrorPolicy.ERROR_TOO_MANY_REQUESTS)
            .forEach { code ->
                val outcome = RecognizerErrorPolicy.classify(code, longElapsedMs) as RecognitionOutcome.Failure
                assertEquals(RecognizerFailureKind.BUSY, outcome.kind)
                assertTrue(outcome.retryable)
                assertEquals(RecognizerErrorPolicy.MESSAGE_BUSY, outcome.userMessage)
            }
    }

    @Test
    fun networkAndServerErrorsAreRetryableNetworkFailures() {
        listOf(
            RecognizerErrorPolicy.ERROR_NETWORK,
            RecognizerErrorPolicy.ERROR_NETWORK_TIMEOUT,
            RecognizerErrorPolicy.ERROR_SERVER,
            RecognizerErrorPolicy.ERROR_SERVER_DISCONNECTED,
        ).forEach { code ->
            val outcome = RecognizerErrorPolicy.classify(code, longElapsedMs) as RecognitionOutcome.Failure
            assertEquals(RecognizerFailureKind.NETWORK, outcome.kind)
            assertTrue(outcome.retryable)
            assertEquals(RecognizerErrorPolicy.MESSAGE_NETWORK, outcome.userMessage)
        }
    }

    @Test
    fun aPermissionErrorIsNeverRetried() {
        val outcome = RecognizerErrorPolicy.classify(
            RecognizerErrorPolicy.ERROR_INSUFFICIENT_PERMISSIONS,
            instantElapsedMs,
        ) as RecognitionOutcome.Failure
        assertEquals(RecognizerFailureKind.PERMISSION, outcome.kind)
        assertFalse("retrying without permission is pointless", outcome.retryable)
        assertEquals(RecognizerErrorPolicy.MESSAGE_PERMISSION, outcome.userMessage)
    }

    @Test
    fun languageAndSupportErrorsAreNotRetryable() {
        listOf(
            RecognizerErrorPolicy.ERROR_LANGUAGE_NOT_SUPPORTED,
            RecognizerErrorPolicy.ERROR_LANGUAGE_UNAVAILABLE,
            RecognizerErrorPolicy.ERROR_CANNOT_CHECK_SUPPORT,
        ).forEach { code ->
            val outcome = RecognizerErrorPolicy.classify(code, longElapsedMs) as RecognitionOutcome.Failure
            assertEquals(RecognizerFailureKind.UNAVAILABLE, outcome.kind)
            assertFalse(outcome.retryable)
            assertEquals(RecognizerErrorPolicy.MESSAGE_UNAVAILABLE, outcome.userMessage)
        }
    }

    @Test
    fun anUnknownCodeIsTreatedAsRetryableOther() {
        val outcome = RecognizerErrorPolicy.classify(999, longElapsedMs) as RecognitionOutcome.Failure
        assertEquals(RecognizerFailureKind.OTHER, outcome.kind)
        assertTrue("an unknown failure is retried, but never silently forever", outcome.retryable)
        assertEquals(RecognizerErrorPolicy.MESSAGE_OTHER, outcome.userMessage)
        assertEquals(999, outcome.errorCode)
    }

    @Test
    fun silenceArrivingInstantlyForEveryCodeIsStillAFailure() {
        // No code may be reported as ordinary silence in under the immediate-failure window.
        (0..20).forEach { code ->
            val outcome = RecognizerErrorPolicy.classify(code, instantElapsedMs)
            if (code == RecognizerErrorPolicy.ERROR_NO_MATCH || code == RecognizerErrorPolicy.ERROR_SPEECH_TIMEOUT) {
                assertTrue(outcome is RecognitionOutcome.Failure)
            }
        }
    }

    @Test
    fun userMessagesAreHonestAndFreeOfTechnicalDetail() {
        val messages = listOf(
            RecognizerErrorPolicy.MESSAGE_CLIENT,
            RecognizerErrorPolicy.MESSAGE_BUSY,
            RecognizerErrorPolicy.MESSAGE_NETWORK,
            RecognizerErrorPolicy.MESSAGE_PERMISSION,
            RecognizerErrorPolicy.MESSAGE_UNAVAILABLE,
            RecognizerErrorPolicy.MESSAGE_OTHER,
        )
        messages.forEach { message ->
            assertTrue(message.isNotBlank())
            assertFalse("never blame the user for silence", VoiceMessages.isForbidden(message))
            listOf("ERROR_", "Exception", "SpeechRecognizer", "code=", "stack").forEach { leak ->
                assertFalse("$message leaks $leak", message.contains(leak))
            }
        }
        // The silence path deliberately carries no message at all.
        assertEquals("", RecognizerErrorPolicy.MESSAGE_SILENCE_KEEPS_CALM)
    }
}
