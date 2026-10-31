package com.nexora.robot.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local focus timer: one of the things NEX can honestly do with no network at all.
 *
 * The clock is injected, so these tests move time instead of waiting for it.
 */
class FocusTimerPolicyTest {

    @Test
    fun theBoundsAreThreeHoursAndOneMinute() {
        assertEquals(10_800_000L, FocusTimerPolicy.MAX_DURATION_MS)
        assertEquals(60_000L, FocusTimerPolicy.minutesToMs(1))
        assertEquals(1_500_000L, FocusTimerPolicy.DEFAULT_DURATION_MS)
        assertEquals(25L * 60_000L, FocusTimerPolicy.DEFAULT_DURATION_MS)
        assertEquals(listOf(5, 10, 15, 20, 25, 45), FocusTimerPolicy.PRESET_MINUTES)
    }

    @Test
    fun minutesAreClampedIntoTheSupportedRange() {
        // Three hours is the ceiling; anything above it becomes three hours rather than failing.
        assertEquals(FocusTimerPolicy.MAX_DURATION_MS, FocusTimerPolicy.minutesToMs(180))
        assertEquals(FocusTimerPolicy.MAX_DURATION_MS, FocusTimerPolicy.minutesToMs(181))
        assertEquals(FocusTimerPolicy.MAX_DURATION_MS, FocusTimerPolicy.minutesToMs(10_000))
        // Zero or negative becomes the one-minute floor, never a zero-length timer.
        assertEquals(60_000L, FocusTimerPolicy.minutesToMs(0))
        assertEquals(60_000L, FocusTimerPolicy.minutesToMs(-5))
        assertEquals(600_000L, FocusTimerPolicy.minutesToMs(10))
        assertEquals(2_700_000L, FocusTimerPolicy.minutesToMs(45))
    }

    @Test
    fun everyPresetFitsInsideTheBounds() {
        FocusTimerPolicy.PRESET_MINUTES.forEach { minutes ->
            val duration = FocusTimerPolicy.minutesToMs(minutes)
            assertTrue(duration >= 60_000L)
            assertTrue(duration <= FocusTimerPolicy.MAX_DURATION_MS)
            assertEquals(minutes.toLong() * 60_000L, duration)
        }
    }

    @Test
    fun englishDurationsAreParsed() {
        assertEquals(1_500_000L, FocusTimerPolicy.parseRequestedDurationMs("set a timer for 25 minutes"))
        assertEquals(600_000L, FocusTimerPolicy.parseRequestedDurationMs("10 min please"))
        assertEquals(300_000L, FocusTimerPolicy.parseRequestedDurationMs("start a 5 minute timer"))
        assertEquals(7_200_000L, FocusTimerPolicy.parseRequestedDurationMs("2 hours"))
        assertEquals(3_600_000L, FocusTimerPolicy.parseRequestedDurationMs("1 hr"))
        assertEquals(420_000L, FocusTimerPolicy.parseRequestedDurationMs("timer 7"))
    }

    @Test
    fun hindiDurationsAreParsed() {
        assertEquals(1_500_000L, FocusTimerPolicy.parseRequestedDurationMs("पच्चीस मिनट का टाइमर लगाओ"))
        assertEquals(600_000L, FocusTimerPolicy.parseRequestedDurationMs("दस मिनट"))
        assertEquals(300_000L, FocusTimerPolicy.parseRequestedDurationMs("पाँच मिनट"))
        assertEquals(300_000L, FocusTimerPolicy.parseRequestedDurationMs("पांच मिनट"))
        assertEquals(2_700_000L, FocusTimerPolicy.parseRequestedDurationMs("पैंतालीस मिनट"))
        assertEquals(1_800_000L, FocusTimerPolicy.parseRequestedDurationMs("तीस मिनट"))
        assertEquals(1_200_000L, FocusTimerPolicy.parseRequestedDurationMs("बीस मिनट"))
        assertEquals(900_000L, FocusTimerPolicy.parseRequestedDurationMs("पंद्रह मिनट"))
        // A Hindi hour request still needs digits: word numbers are limited to the presets.
        assertEquals(3_600_000L, FocusTimerPolicy.parseRequestedDurationMs("1 घंटा"))
        assertNull(FocusTimerPolicy.parseRequestedDurationMs("एक घंटा"))
    }

    @Test
    fun aRequestWithoutADurationReturnsNullSoNexCanAsk() {
        assertNull(FocusTimerPolicy.parseRequestedDurationMs("set a timer"))
        assertNull(FocusTimerPolicy.parseRequestedDurationMs("remind me later"))
        assertNull(FocusTimerPolicy.parseRequestedDurationMs(""))
        assertNull(FocusTimerPolicy.parseRequestedDurationMs("   "))
        assertNull(FocusTimerPolicy.parseRequestedDurationMs("0 minutes"))
    }

    @Test
    fun absurdDurationsAreClampedNotRejected() {
        assertEquals(FocusTimerPolicy.MAX_DURATION_MS, FocusTimerPolicy.parseRequestedDurationMs("999 minutes"))
        assertEquals(FocusTimerPolicy.MAX_DURATION_MS, FocusTimerPolicy.parseRequestedDurationMs("5000 minutes"))
        assertEquals(FocusTimerPolicy.MAX_DURATION_MS, FocusTimerPolicy.parseRequestedDurationMs("100 hours"))
    }

    @Test
    fun startProducesARunningTimerWithASafeDuration() {
        val started = FocusTimerPolicy.start(nowMs = 1_000L, durationMs = 1_500_000L)
        assertTrue(started.running)
        assertEquals(1_000L, started.startedAtMs)
        assertEquals(1_500_000L, started.durationMs)
        assertFalse(started.finished)
        assertEquals(1_500_000L, started.remainingAt(1_000L))
        assertEquals(0f, started.progressAt(1_000L), 0.0001f)
        assertFalse(started.isExpiredAt(1_000L))

        assertEquals(60_000L, FocusTimerPolicy.start(0L, 0L).durationMs)
        assertEquals(FocusTimerPolicy.MAX_DURATION_MS, FocusTimerPolicy.start(0L, Long.MAX_VALUE).durationMs)
    }

    @Test
    fun remainingAndProgressFollowTheClock() {
        val started = FocusTimerPolicy.start(nowMs = 0L, durationMs = 600_000L)
        assertEquals(600_000L, started.remainingAt(0L))
        assertEquals(300_000L, started.remainingAt(300_000L))
        assertEquals(0.5f, started.progressAt(300_000L), 0.0001f)
        assertEquals(1L, started.remainingAt(599_999L))
        assertEquals(0L, started.remainingAt(600_000L))
        assertEquals(1f, started.progressAt(600_000L), 0.0001f)
        // Never negative, never above one.
        assertEquals(0L, started.remainingAt(900_000L))
        assertEquals(1f, started.progressAt(900_000L), 0.0001f)
        assertTrue(started.isExpiredAt(600_000L))
        assertTrue(started.isExpiredAt(900_000L))
        assertFalse(started.isExpiredAt(599_999L))
    }

    @Test
    fun aClockGoingBackwardsCannotProduceANegativeRemaining() {
        val started = FocusTimerPolicy.start(nowMs = 10_000L, durationMs = 600_000L)
        assertEquals(600_000L, started.remainingAt(0L))
        assertEquals(0f, started.progressAt(0L), 0.0001f)
        assertFalse(started.isExpiredAt(0L))
    }

    @Test
    fun tickMarksTheTimerFinishedExactlyOnce() {
        val started = FocusTimerPolicy.start(nowMs = 0L, durationMs = 60_000L)
        assertSame("ticking early must not create a new state", started, FocusTimerPolicy.tick(started, 30_000L))

        val finished = FocusTimerPolicy.tick(started, 60_000L)
        assertFalse(finished.running)
        assertTrue(finished.finished)
        assertEquals(0L, finished.remainingAt(60_000L))

        // A finished timer stays finished; ticking it again changes nothing.
        assertSame(finished, FocusTimerPolicy.tick(finished, 120_000L))
        assertTrue(FocusTimerPolicy.tick(finished, 120_000L).finished)
    }

    @Test
    fun idleAndStopAreTheSameCalmState() {
        listOf(FocusTimerPolicy.idle(), FocusTimerPolicy.stop()).forEach { state ->
            assertFalse(state.running)
            assertFalse(state.finished)
            assertEquals(0L, state.startedAtMs)
            assertEquals(0L, state.durationMs)
            assertEquals(0L, state.remainingAt(1_000_000L))
            assertEquals(0f, state.progressAt(1_000_000L), 0.0001f)
            assertFalse(state.isExpiredAt(1_000_000L))
        }
    }

    @Test
    fun tickingAnIdleTimerDoesNotStartOne() {
        val idle = FocusTimerPolicy.idle()
        assertSame(idle, FocusTimerPolicy.tick(idle, 5_000L))
        assertFalse(FocusTimerPolicy.tick(idle, 5_000L).finished)
    }

    @Test
    fun aFullTimerLifecycleWorksOnAnInjectedClock() {
        var now = 0L
        var state = FocusTimerPolicy.idle()
        val requested = FocusTimerPolicy.parseRequestedDurationMs("start a 5 minute timer")
        assertEquals(300_000L, requested)

        state = FocusTimerPolicy.start(now, requested!!)
        assertEquals(300_000L, state.remainingAt(now))

        now += 60_000L
        state = FocusTimerPolicy.tick(state, now)
        assertTrue(state.running)
        assertEquals(240_000L, state.remainingAt(now))

        now += 240_000L
        state = FocusTimerPolicy.tick(state, now)
        assertFalse(state.running)
        assertTrue(state.finished)

        state = FocusTimerPolicy.stop()
        assertFalse(state.running)
        assertFalse(state.finished)
    }
}
