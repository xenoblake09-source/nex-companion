package com.nexora.robot.util

/**
 * Privacy scrubbing for anything NEX writes to logcat.
 *
 * Pure Kotlin (no Android imports) so `LogScrubberTest` can prove it works on the JVM.
 * The rule is simple: no user speech, no identifiers, no numbers that could be a phone
 * number, roll number or account number, and no credential-shaped strings.
 */
object LogScrubber {

    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val PHONE_LIKE = Regex("(?<!\\d)(?:\\+?\\d[\\d\\s().-]{7,}\\d)(?!\\d)")
    private val LONG_DIGITS = Regex("\\d{6,}")
    private val CONTROL = Regex("[\\p{Cntrl}&&[^\n\t]]")
    private val QUOTED_SPEECH = Regex("\"[^\"]{12,}\"")

    private val CREDENTIAL_SHAPES = listOf(
        Regex("\\bAIza[0-9A-Za-z_\\-]{20,}\\b"),
        Regex("\\bsk-[A-Za-z0-9_\\-]{16,}\\b"),
        Regex("\\bghp_[A-Za-z0-9]{20,}\\b"),
        Regex("\\beyJ[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{5,}\\b"),
        Regex("\\bxox[baprs]-[A-Za-z0-9\\-]{10,}\\b"),
    )

    const val MAX_LENGTH = 200

    /** Redact one log message. Never throws. */
    fun scrub(message: String?): String {
        if (message.isNullOrBlank()) return ""
        var text = CONTROL.replace(message, " ")
        text = EMAIL.replace(text, "[email]")
        text = PHONE_LIKE.replace(text, "[phone]")
        text = LONG_DIGITS.replace(text, "[digits]")
        text = QUOTED_SPEECH.replace(text, "[speech]")
        CREDENTIAL_SHAPES.forEach { text = it.replace(text, "[redacted]") }
        text = text.replace(Regex("\\s+"), " ").trim()
        return if (text.length > MAX_LENGTH) text.take(MAX_LENGTH) + "…" else text
    }

    /** Length-only description, for cases where even a redacted message is too much. */
    fun sizeOnly(value: String?): String = "chars=${value?.length ?: 0}"
}
