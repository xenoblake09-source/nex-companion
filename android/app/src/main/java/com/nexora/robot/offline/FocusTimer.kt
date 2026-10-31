package com.nexora.robot.offline

/**
 * A tiny local focus timer.
 *
 * This is one of the things NEX can honestly do with **no** network: start a countdown,
 * show the "Countdown focus pulse" animation, and celebrate when it finishes. Pure Kotlin,
 * driven by an injected clock, so `FocusTimerPolicyTest` can move time without waiting.
 */
data class FocusTimerState(
    val running: Boolean = false,
    val startedAtMs: Long = 0L,
    val durationMs: Long = 0L,
    val finished: Boolean = false,
) {
    fun remainingAt(nowMs: Long): Long {
        if (!running || durationMs <= 0L) return 0L
        val elapsed = (nowMs - startedAtMs).coerceAtLeast(0L)
        return (durationMs - elapsed).coerceAtLeast(0L)
    }

    fun progressAt(nowMs: Long): Float {
        if (!running || durationMs <= 0L) return 0f
        val elapsed = (nowMs - startedAtMs).coerceAtLeast(0L)
        return (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    fun isExpiredAt(nowMs: Long): Boolean = running && remainingAt(nowMs) <= 0L
}

object FocusTimerPolicy {

    /** 25 minutes, the classic study block. */
    const val DEFAULT_DURATION_MS: Long = 25L * 60_000L

    /** Refuse absurd timers: three hours is the ceiling. */
    const val MAX_DURATION_MS: Long = 10_800_000L

    val PRESET_MINUTES: List<Int> = listOf(5, 10, 15, 20, 25, 45)

    private val DURATION_PATTERN = Regex("(\\d{1,3})\\s*(minute|minutes|min|mins|hour|hours|hr|hrs|मिनट|घंटा|घंटे)?")

    private val HINDI_NUMBERS: Map<String, Int> = mapOf(
        "पाँच" to 5, "पांच" to 5, "दस" to 10, "पंद्रह" to 15, "बीस" to 20,
        "पच्चीस" to 25, "तीस" to 30, "पैंतालीस" to 45,
    )

    fun idle(): FocusTimerState = FocusTimerState()

    /**
     * Parse a requested duration from natural text ("25 minutes", "set a timer for 10 min",
     * "पच्चीस मिनट"). Returns null when no duration is present, so the caller can ask instead
     * of inventing one.
     */
    fun parseRequestedDurationMs(text: String): Long? {
        val normalized = text.lowercase().trim()
        if (normalized.isBlank()) return null

        val hindi = HINDI_NUMBERS.entries.firstOrNull { normalized.contains(it.key) }
        if (hindi != null) return minutesToMs(hindi.value)

        val match = DURATION_PATTERN.find(normalized) ?: return null
        val amount = match.groupValues[1].toIntOrNull() ?: return null
        if (amount <= 0) return null
        val unit = match.groupValues.getOrNull(2).orEmpty()
        val minutes = when {
            unit.startsWith("h") || unit.startsWith("घं") -> amount * 60
            else -> amount
        }
        return minutesToMs(minutes)
    }

    fun minutesToMs(minutes: Int): Long = (minutes.toLong() * 60_000L).coerceIn(60_000L, MAX_DURATION_MS)

    fun start(nowMs: Long, durationMs: Long): FocusTimerState {
        val safe = durationMs.coerceIn(60_000L, MAX_DURATION_MS)
        return FocusTimerState(running = true, startedAtMs = nowMs, durationMs = safe, finished = false)
    }

    /** Advance the timer; marks it finished exactly once when the time is up. */
    fun tick(state: FocusTimerState, nowMs: Long): FocusTimerState {
        if (!state.running) return state
        return if (state.isExpiredAt(nowMs)) {
            state.copy(running = false, finished = true)
        } else {
            state
        }
    }

    fun stop(): FocusTimerState = FocusTimerState()
}
