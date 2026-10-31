package com.nexora.robot.voice

import com.nexora.robot.language.TextNormalizer

/**
 * Foreground wake-phrase matching for "Hello NEX".
 *
 * Honest scope, stated plainly because it is easy to over-promise:
 *
 *  * This matches on the **transcript** of an ordinary recognition turn. It is not a
 *    low-power DSP hotword model, and it is not a privileged system hotword integration.
 *  * Therefore NEX can only hear the wake phrase while the app is in the foreground with
 *    voice mode on and the microphone permission granted. A standard app cannot wake itself
 *    from a force-stopped or closed state, and NEX does not claim to.
 *  * Matching is tolerant (ASR writes "next", "necks", "hex" and similar) but deliberately
 *    narrow: it needs an attention word immediately followed by something close to "nex".
 */
object WakePhraseMatcher {

    /** The name NEX listens for. */
    const val TARGET = "nex"

    /** Latin attention words accepted before the name. */
    val GREETINGS: Set<String> = setOf(
        "hello", "helo", "hullo", "hallo", "hi", "hii", "hey", "ok", "okay",
    )

    /** Devanagari attention words, for Hindi speakers. */
    val GREETINGS_DEVANAGARI: Set<String> = setOf("हैलो", "नमस्ते", "हे", "ओके")

    /** Exact Devanagari spellings of the name. */
    val TARGET_DEVANAGARI: Set<String> = setOf("नेक्स")

    /**
     * Near-neighbours that must never wake NEX. "next" is one edit away from "nex" and is a
     * common classroom word, so it is refused explicitly in both scripts.
     */
    private val NAME_BLOCKLIST: Set<String> = setOf("new", "net", "nee", "sew", "next", "नेक्स्ट")

    /** Maximum Levenshtein distance accepted for the name token. */
    const val MAX_NAME_DISTANCE = 1

    /** Minimum length for a fuzzy name match, so stray two-letter tokens cannot trigger it. */
    private const val MIN_FUZZY_NAME_LENGTH = 3
    private const val MAX_FUZZY_NAME_LENGTH = 5

    /**
     * True when [transcript] contains the wake phrase.
     *
     * Scans every adjacent token pair, because the recogniser may prepend filler
     * ("uh, hello NEX") or append trailing words.
     */
    fun matches(transcript: String): Boolean = findWakePhrase(transcript) != null

    /** The matched phrase, or null. Returns only the two matched tokens, never the whole utterance. */
    fun findWakePhrase(transcript: String): String? {
        val tokens = TextNormalizer.tokens(transcript)
        if (tokens.size < 2) return null
        for (index in 0 until tokens.size - 1) {
            val attention = tokens[index]
            val name = tokens[index + 1]
            if (isAttentionWord(attention) && isName(name)) {
                return "$attention $name"
            }
        }
        return null
    }

    fun isAttentionWord(token: String): Boolean {
        if (token.isBlank()) return false
        if (token in GREETINGS || token in GREETINGS_DEVANAGARI) return true
        return token.length >= 4 && editDistance(token, "hello") <= 1
    }

    fun isName(token: String): Boolean {
        if (token.isBlank()) return false
        if (token == TARGET || token in TARGET_DEVANAGARI) return true
        if (token in NAME_BLOCKLIST) return false
        if (token.length < MIN_FUZZY_NAME_LENGTH || token.length > MAX_FUZZY_NAME_LENGTH) return false
        return editDistance(token, TARGET) <= MAX_NAME_DISTANCE
    }

    /** Classic Levenshtein distance with a bounded two-row matrix. */
    fun editDistance(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length

        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)

        for (i in 1..left.length) {
            current[0] = i
            for (j in 1..right.length) {
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,        // insertion
                    previous[j] + 1,           // deletion
                    previous[j - 1] + cost,    // substitution
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }
}
