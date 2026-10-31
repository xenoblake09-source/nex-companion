package com.nexora.robot.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The foreground wake phrase: "Hello NEX".
 *
 * Rules pinned here:
 *  * it must survive punctuation, casing, filler words and one-letter misrecognitions;
 *  * it must work in Hindi too ("हैलो नेक्स");
 *  * it must not fire on near-neighbours such as "new", "net", "next" or "netflix";
 *  * [WakePhraseMatcher.findWakePhrase] returns only the two matched tokens, never the whole
 *    utterance, so nothing the user said can leak into a log line.
 */
class WakePhraseMatcherTest {

    @Test
    fun theExactWakePhraseMatches() {
        assertTrue(WakePhraseMatcher.matches("hello nex"))
        assertTrue(WakePhraseMatcher.matches("Hello NEX"))
        assertTrue(WakePhraseMatcher.matches("Hello, NEX!"))
        assertTrue(WakePhraseMatcher.matches("  hello   nex  "))
    }

    @Test
    fun theWakePhraseMatchesInsideALongerUtterance() {
        assertTrue(WakePhraseMatcher.matches("uh hello nex can you hear me"))
        assertTrue(WakePhraseMatcher.matches("okay hello nex what is the time"))
        assertTrue(WakePhraseMatcher.matches("please wake up, hello nex!"))
    }

    @Test
    fun everyAttentionWordInTheCatalogueWakesNex() {
        WakePhraseMatcher.GREETINGS.forEach { attention ->
            assertTrue("'$attention nex' must wake NEX", WakePhraseMatcher.matches("$attention nex"))
        }
        WakePhraseMatcher.GREETINGS_DEVANAGARI.forEach { attention ->
            assertTrue("'$attention नेक्स' must wake NEX", WakePhraseMatcher.matches("$attention नेक्स"))
        }
    }

    @Test
    fun hindiWakePhraseMatches() {
        assertTrue(WakePhraseMatcher.matches("हैलो नेक्स"))
        assertTrue(WakePhraseMatcher.matches("नमस्ते नेक्स, आज मौसम कैसा है"))
        assertTrue(WakePhraseMatcher.matches("ओके नेक्स"))
    }

    @Test
    fun oneLetterMisrecognitionsStillWake() {
        assertTrue(WakePhraseMatcher.matches("helo nex"))
        assertTrue(WakePhraseMatcher.matches("hello nexs"))
        assertTrue(WakePhraseMatcher.matches("hello nexa"))
        assertTrue(WakePhraseMatcher.matches("hell nex"))
    }

    @Test
    fun nearNeighbourWordsNeverWake() {
        listOf(
            "hello new",
            "hello net",
            "hello nee",
            "hello sew",
            "hello next",
            "hello netflix",
            "hello neks",
            "my new phone",
            "next chapter please",
            "हैलो नेक्स्ट",
        ).forEach { transcript ->
            assertFalse("'$transcript' must not wake NEX", WakePhraseMatcher.matches(transcript))
        }
    }

    @Test
    fun theNameAloneDoesNotWakeWithoutAnAttentionWord() {
        assertFalse(WakePhraseMatcher.matches("nex"))
        assertFalse(WakePhraseMatcher.matches("nex is here"))
        assertFalse(WakePhraseMatcher.matches("talk to nex"))
        assertFalse(WakePhraseMatcher.matches("नेक्स"))
    }

    @Test
    fun blankAndSingleTokenInputIsNeverAWakePhrase() {
        assertFalse(WakePhraseMatcher.matches(""))
        assertFalse(WakePhraseMatcher.matches("   "))
        assertFalse(WakePhraseMatcher.matches("hello"))
        assertNull(WakePhraseMatcher.findWakePhrase("hello"))
        assertNull(WakePhraseMatcher.findWakePhrase(""))
    }

    @Test
    fun findWakePhraseReturnsOnlyTheTwoMatchedTokens() {
        assertEquals("hello nex", WakePhraseMatcher.findWakePhrase("hello nex"))
        assertEquals("hello nex", WakePhraseMatcher.findWakePhrase("Uh, hello NEX — what is the time?"))
        assertEquals("hey nex", WakePhraseMatcher.findWakePhrase("hey nex thanks"))
        assertEquals("हैलो नेक्स", WakePhraseMatcher.findWakePhrase("हैलो नेक्स, सुप्रभात"))
        // Privacy: the returned phrase never carries the rest of the sentence.
        val matched = WakePhraseMatcher.findWakePhrase("hello nex my password is hunter2")
        assertEquals("hello nex", matched)
        assertFalse(matched.orEmpty().contains("hunter2"))
    }

    @Test
    fun isAttentionWordAcceptsGreetingsAndNearMissesOfHello() {
        assertTrue(WakePhraseMatcher.isAttentionWord("hello"))
        assertTrue(WakePhraseMatcher.isAttentionWord("hey"))
        assertTrue(WakePhraseMatcher.isAttentionWord("hi"))
        assertTrue(WakePhraseMatcher.isAttentionWord("okay"))
        assertTrue(WakePhraseMatcher.isAttentionWord("hullo"))
        assertTrue(WakePhraseMatcher.isAttentionWord("हैलो"))
        assertFalse(WakePhraseMatcher.isAttentionWord("please"))
        assertFalse(WakePhraseMatcher.isAttentionWord("what"))
        assertFalse(WakePhraseMatcher.isAttentionWord(""))
        assertFalse(WakePhraseMatcher.isAttentionWord("hi "))
    }

    @Test
    fun isNameAcceptsTheNameAndRefusesBlocklistedNeighbours() {
        assertTrue(WakePhraseMatcher.isName(WakePhraseMatcher.TARGET))
        assertTrue(WakePhraseMatcher.isName("nexs"))
        assertTrue(WakePhraseMatcher.isName("नेक्स"))
        WakePhraseMatcher.TARGET_DEVANAGARI.forEach { assertTrue(WakePhraseMatcher.isName(it)) }
        assertFalse(WakePhraseMatcher.isName("new"))
        assertFalse(WakePhraseMatcher.isName("net"))
        assertFalse(WakePhraseMatcher.isName("next"))
        assertFalse(WakePhraseMatcher.isName("nexus"))
        assertFalse(WakePhraseMatcher.isName("nx"))
        assertFalse(WakePhraseMatcher.isName(""))
    }

    @Test
    fun fuzzyNameLengthIsBoundedSoLongWordsCannotMatch() {
        assertFalse(WakePhraseMatcher.isName("netflix"))
        assertFalse(WakePhraseMatcher.isName("nexamarvel"))
        assertFalse(WakePhraseMatcher.isName("a"))
        assertFalse(WakePhraseMatcher.isName("ab"))
    }

    @Test
    fun editDistanceIsPlainLevenshtein() {
        assertEquals(0, WakePhraseMatcher.editDistance("nex", "nex"))
        assertEquals(1, WakePhraseMatcher.editDistance("nex", "ne"))
        assertEquals(1, WakePhraseMatcher.editDistance("nex", "nexs"))
        assertEquals(3, WakePhraseMatcher.editDistance("kitten", "sitting"))
        assertEquals(2, WakePhraseMatcher.editDistance("flaw", "lawn"))
        assertEquals(5, WakePhraseMatcher.editDistance("", "hello"))
        assertEquals(4, WakePhraseMatcher.editDistance("nexa", ""))
        assertEquals(WakePhraseMatcher.MAX_NAME_DISTANCE, 1)
        assertEquals(2, WakePhraseMatcher.editDistance("neks", "nex"))
    }
}
