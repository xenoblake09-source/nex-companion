package com.nexora.robot.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bounded backoff for voice-service failures: 1.2 s → 2.5 s → 5 s → 8 s, capped at 8 s,
 * inside a 20 s burst, after which NEX stops retrying silently and tells the user the truth.
 */
class RetryPolicyTest {

    @Test
    fun backoffLadderIsExactly1200_2500_5000_8000() {
        assertEquals(listOf(1_200L, 2_500L, 5_000L, 8_000L), RetryPolicy.BACKOFF_MS)
        assertEquals(1_200L, RetryPolicy.backoffFor(1))
        assertEquals(2_500L, RetryPolicy.backoffFor(2))
        assertEquals(5_000L, RetryPolicy.backoffFor(3))
        assertEquals(8_000L, RetryPolicy.backoffFor(4))
    }

    @Test
    fun backoffIsCappedAtEightSeconds() {
        assertEquals(RetryPolicy.MAX_BACKOFF_MS, RetryPolicy.backoffFor(5))
        assertEquals(RetryPolicy.MAX_BACKOFF_MS, RetryPolicy.backoffFor(99))
        assertEquals(8_000L, RetryPolicy.MAX_BACKOFF_MS)
        // The ladder never decreases and never exceeds the cap.
        var previous = 0L
        for (attempt in 1..20) {
            val backoff = RetryPolicy.backoffFor(attempt)
            assertTrue("backoff must not shrink at attempt $attempt", backoff >= previous)
            assertTrue("backoff must respect the cap", backoff <= RetryPolicy.MAX_BACKOFF_MS)
            previous = backoff
        }
    }

    @Test
    fun noFailureMeansNoDelay() {
        assertEquals(0L, RetryPolicy.backoffFor(0))
        assertEquals(0L, RetryPolicy.backoffFor(-3))
    }

    @Test
    fun burstWindowIsTwentySeconds() {
        assertEquals(20_000L, RetryPolicy.BURST_WINDOW_MS)
        assertEquals(4, RetryPolicy.MAX_ATTEMPTS_BEFORE_USER_PROMPT)
    }

    @Test
    fun firstFailureStartsABurstAtAttemptOne() {
        val (burstStart, attempt) = RetryPolicy.nextAttempt(previousBurstStartedAtMs = null, previousAttempt = 0, nowMs = 1_000L)
        assertEquals(1_000L, burstStart)
        assertEquals(1, attempt)
        assertEquals(RetryPolicy.backoffFor(attempt), 1_200L)
    }

    @Test
    fun failuresInsideTheBurstClimbTheLadder() {
        var burst: Long? = 1_000L
        var attempt = 0
        val observed = mutableListOf<Long>()
        for (now in listOf(2_200L, 4_700L, 9_700L, 17_700L)) {
            val (nextBurst, nextAttempt) = RetryPolicy.nextAttempt(burst, attempt, now)
            burst = nextBurst
            attempt = nextAttempt
            observed += RetryPolicy.backoffFor(attempt)
        }
        assertEquals(listOf(1_200L, 2_500L, 5_000L, 8_000L), observed)
        assertEquals("the burst start must not move while the burst is live", 1_000L, burst)
        assertEquals(4, attempt)
        assertTrue(RetryPolicy.shouldAskUser(attempt))
    }

    @Test
    fun aFailureAfterTheBurstExpiredStartsAFreshBurst() {
        val (burstStart, attempt) = RetryPolicy.nextAttempt(
            previousBurstStartedAtMs = 1_000L,
            previousAttempt = 4,
            nowMs = 1_000L + RetryPolicy.BURST_WINDOW_MS + 1L,
        )
        assertEquals(21_001L, burstStart)
        assertEquals(1, attempt)
    }

    @Test
    fun isInsideBurstCoversTheWholeWindowAndNothingBeyond() {
        assertFalse(RetryPolicy.isInsideBurst(null, 1_000L))
        assertTrue(RetryPolicy.isInsideBurst(1_000L, 1_000L))
        assertTrue(RetryPolicy.isInsideBurst(1_000L, 20_999L))
        assertTrue(RetryPolicy.isInsideBurst(1_000L, 21_000L))
        assertFalse(RetryPolicy.isInsideBurst(1_000L, 21_001L))
        // A clock in the future still counts as inside the burst rather than resetting it.
        assertTrue(RetryPolicy.isInsideBurst(5_000L, 1_000L))
    }

    @Test
    fun nexAsksTheUserAfterFourAttemptsAndNeverBefore() {
        assertFalse(RetryPolicy.shouldAskUser(0))
        assertFalse(RetryPolicy.shouldAskUser(1))
        assertFalse(RetryPolicy.shouldAskUser(2))
        assertFalse(RetryPolicy.shouldAskUser(3))
        assertTrue(RetryPolicy.shouldAskUser(4))
        assertTrue(RetryPolicy.shouldAskUser(5))
        assertTrue(RetryPolicy.shouldAskUser(100))
    }

    @Test
    fun totalSilentRetryTimeInsideOneBurstIsBounded() {
        val total = (1..RetryPolicy.MAX_ATTEMPTS_BEFORE_USER_PROMPT).sumOf { RetryPolicy.backoffFor(it) }
        assertEquals(16_700L, total)
        assertTrue("silent retries must fit inside the burst window", total <= RetryPolicy.BURST_WINDOW_MS)
    }
}
