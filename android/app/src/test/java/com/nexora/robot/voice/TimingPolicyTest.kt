package com.nexora.robot.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timing contract that keeps NEX's microphone stable.
 *
 * These numbers are the product requirement, not tuning trivia:
 *  * the microphone stays closed for at least 850 ms after speech ends, so NEX never hears
 *    itself and never reopens into a half-settled audio route;
 *  * ordinary silence opens a new turn after about 650 ms instead of being treated as an error.
 */
class TimingPolicyTest {

    @Test
    fun postSpeechSettleWindowIsAtLeast850Milliseconds() {
        assertEquals(850L, TimingPolicy.POST_SPEECH_MIC_SETTLE_MS)
        assertTrue(TimingPolicy.POST_SPEECH_MIC_SETTLE_MS >= 850L)
    }

    @Test
    fun newTurnAfterNormalSilenceIsAbout650Milliseconds() {
        assertEquals(650L, TimingPolicy.NEW_TURN_AFTER_SILENCE_MS)
        assertTrue(
            "silence must reopen the mic faster than the settle window",
            TimingPolicy.NEW_TURN_AFTER_SILENCE_MS < TimingPolicy.POST_SPEECH_MIC_SETTLE_MS + 1_000L,
        )
    }

    @Test
    fun recognitionTurnBudgetAndImmediateFailureWindowAreAsAgreed() {
        assertEquals(12_000L, TimingPolicy.RECOGNITION_TURN_BUDGET_MS)
        assertEquals(400L, TimingPolicy.IMMEDIATE_FAILURE_WINDOW_MS)
        assertEquals(120L, TimingPolicy.MIN_TURN_GAP_MS)
        assertEquals(30_000L, TimingPolicy.SPEECH_COMPLETION_GRACE_MS)
    }

    @Test
    fun theImmediateFailureWindowIsShorterThanTheSilenceWindow() {
        // A code-7 arriving in under 400 ms is a recogniser failure; the same code after
        // seconds of listening is ordinary silence. The two windows must not overlap.
        assertTrue(TimingPolicy.IMMEDIATE_FAILURE_WINDOW_MS < TimingPolicy.NEW_TURN_AFTER_SILENCE_MS)
    }

    @Test
    fun gateAfterSpeechCompletedAddsTheSettleWindow() {
        assertEquals(1_850L, TimingPolicy.gateAfterSpeechCompleted(1_000L))
        assertEquals(850L, TimingPolicy.gateAfterSpeechCompleted(0L))
        assertEquals(-150L, TimingPolicy.gateAfterSpeechCompleted(-1_000L))
    }

    @Test
    fun gateAfterSpeechCompletedNeverAllowsAnImmediateReopen() {
        val speechEndedAt = 5_000L
        val gate = TimingPolicy.gateAfterSpeechCompleted(speechEndedAt)
        assertTrue(gate - speechEndedAt >= 850L)
        assertFalse("the mic must not reopen at the instant speech ends", gate <= speechEndedAt)
    }

    @Test
    fun nextTurnAfterSilenceAddsTheSilenceWindow() {
        assertEquals(1_650L, TimingPolicy.nextTurnAfterSilence(1_000L))
        assertEquals(650L, TimingPolicy.nextTurnAfterSilence(0L))
    }

    @Test
    fun theTwoGatesAreIndependent() {
        val now = 10_000L
        val afterSpeech = TimingPolicy.gateAfterSpeechCompleted(now)
        val afterSilence = TimingPolicy.nextTurnAfterSilence(now)
        assertEquals(850L, afterSpeech - now)
        assertEquals(650L, afterSilence - now)
        assertTrue(afterSpeech > afterSilence)
    }
}
