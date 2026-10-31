package com.nexora.robot.voice

/**
 * Bounded retry backoff for immediate speech-recogniser / client / busy / network errors.
 *
 * The schedule is exactly **1.2 s -> 2.5 s -> 5 s -> 8 s (maximum)** inside one failure
 * burst, and a burst expires after [BURST_WINDOW_MS] of quiet so an old failure cannot
 * poison a later one. A successful utterance or a stable normal turn resets the burst.
 */
object RetryPolicy {

    /** The four legal backoff steps, in order. */
    val BACKOFF_MS: List<Long> = listOf(1_200L, 2_500L, 5_000L, 8_000L)

    /** Hard ceiling: NEX never waits longer than this between recovery attempts. */
    const val MAX_BACKOFF_MS: Long = 8_000L

    /** A failure burst older than this is treated as a new burst. */
    const val BURST_WINDOW_MS: Long = 20_000L

    /** Attempts after which NEX stops retrying on its own and asks the user. */
    const val MAX_ATTEMPTS_BEFORE_USER_PROMPT: Int = 4

    /**
     * Delay before the next attempt.
     *
     * @param attempt 1-based attempt number inside the current burst. `attempt <= 0` means
     *   "no failure yet" and therefore no delay.
     */
    fun backoffFor(attempt: Int): Long = when {
        attempt <= 0 -> 0L
        attempt > BACKOFF_MS.size -> MAX_BACKOFF_MS
        else -> BACKOFF_MS[attempt - 1]
    }

    /** True while [burstStartedAtMs] is inside the current failure burst window. */
    fun isInsideBurst(burstStartedAtMs: Long?, nowMs: Long): Boolean {
        if (burstStartedAtMs == null) return false
        if (burstStartedAtMs > nowMs) return true
        return nowMs - burstStartedAtMs <= BURST_WINDOW_MS
    }

    /**
     * Resolve the next attempt number, rolling the burst over when it has expired.
     *
     * @return pair of (burst start to store, attempt number to store)
     */
    fun nextAttempt(previousBurstStartedAtMs: Long?, previousAttempt: Int, nowMs: Long): Pair<Long, Int> =
        if (isInsideBurst(previousBurstStartedAtMs, nowMs)) {
            (previousBurstStartedAtMs ?: nowMs) to (previousAttempt + 1)
        } else {
            nowMs to 1
        }

    /** Whether NEX should stop retrying silently and show an honest prompt instead. */
    fun shouldAskUser(attempt: Int): Boolean = attempt >= MAX_ATTEMPTS_BEFORE_USER_PROMPT
}
