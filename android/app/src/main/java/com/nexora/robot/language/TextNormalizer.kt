package com.nexora.robot.language

/**
 * Tiny shared text normaliser. Pure Kotlin so intent detection and wake-phrase
 * matching can both be unit tested on the JVM.
 *
 * Letters from any script (Latin and Devanagari both matter for NEX), digits,
 * apostrophes and hyphens survive; punctuation becomes a space.
 */
object TextNormalizer {

    private val NON_WORD = Regex("[^\\p{L}\\p{N}\\s'\\-]")
    private val WHITESPACE = Regex("\\s+")

    fun normalize(text: String): String {
        if (text.isBlank()) return ""
        val lowered = text.lowercase().replace('\u2019', '\'').replace('\u2018', '\'')
        val stripped = NON_WORD.replace(lowered, " ")
        return WHITESPACE.replace(stripped, " ").trim()
    }

    fun tokens(text: String): List<String> =
        normalize(text).split(' ').filter { it.isNotBlank() }

    /** True when the normalised text contains any of [needles] as a substring. */
    fun containsPhrase(normalizedText: String, needles: Collection<String>): Boolean {
        if (normalizedText.isBlank()) return false
        return needles.any { it.isNotBlank() && normalizedText.contains(it) }
    }

    /** True when any token exactly matches one of [needles]. */
    fun containsToken(tokens: List<String>, needles: Collection<String>): Boolean =
        tokens.any { it in needles }
}
