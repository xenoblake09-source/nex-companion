package com.nexora.robot.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tiny text normaliser that intent detection and wake-phrase matching share.
 *
 * Letters from any script must survive (Devanagari matters as much as Latin for NEX), digits
 * must survive, punctuation must become a space, and nothing may throw on odd input.
 */
class TextNormalizerTest {

    @Test
    fun lowercasesAndStripsPunctuation() {
        assertEquals("hello nex", TextNormalizer.normalize("Hello, NEX!"))
        assertEquals("what is this", TextNormalizer.normalize("WHAT IS THIS??"))
        assertEquals("stop listening now", TextNormalizer.normalize("  Stop; listening… now. "))
    }

    @Test
    fun keepsDevanagariLetters() {
        assertEquals("नमस्ते नेक्स", TextNormalizer.normalize("नमस्ते नेक्स"))
        assertEquals("आज मौसम कैसा है", TextNormalizer.normalize("आज मौसम कैसा है?"))
        assertEquals("नमस्ते nex", TextNormalizer.normalize("नमस्ते NEX!"))
    }

    @Test
    fun keepsDigitsApostrophesAndHyphens() {
        assertEquals("class 10 maths", TextNormalizer.normalize("Class 10 Maths"))
        assertEquals("what's this", TextNormalizer.normalize("what's this"))
        assertEquals("state-of-the-art", TextNormalizer.normalize("state-of-the-art"))
    }

    @Test
    fun convertsTypographicApostrophesToAscii() {
        assertEquals("it's fine", TextNormalizer.normalize("it’s fine"))
        assertEquals("don't", TextNormalizer.normalize("don’t"))
        assertEquals("'hello'", TextNormalizer.normalize("‘hello’"))
    }

    @Test
    fun collapsesWhitespace() {
        assertEquals("multi space text", TextNormalizer.normalize("multi   space\ttext"))
        assertEquals("a b", TextNormalizer.normalize("a\n\n\n  b"))
    }

    @Test
    fun replacesUnmatchedSymbolsWithASpace() {
        assertEquals("emoji party", TextNormalizer.normalize("emoji 🎉 party"))
        assertEquals("100 percent", TextNormalizer.normalize("100% percent"))
    }

    @Test
    fun blankInputBecomesAnEmptyString() {
        assertEquals("", TextNormalizer.normalize(""))
        assertEquals("", TextNormalizer.normalize("   "))
        assertEquals("", TextNormalizer.normalize("\t\n"))
        assertEquals("", TextNormalizer.normalize("…"))
    }

    @Test
    fun tokensSplitOnSingleSpacesAndDropBlanks() {
        assertEquals(listOf("hello", "nex"), TextNormalizer.tokens("Hello, NEX!"))
        assertEquals(listOf("नमस्ते", "नेक्स"), TextNormalizer.tokens("नमस्ते नेक्स"))
        assertEquals(emptyList<String>(), TextNormalizer.tokens(""))
        assertEquals(emptyList<String>(), TextNormalizer.tokens("   "))
        assertEquals(listOf("a", "b"), TextNormalizer.tokens("  a   b  "))
    }

    @Test
    fun tokensNeverContainPunctuation() {
        TextNormalizer.tokens("stop; listening… now!").forEach { token ->
            assertFalse(token.contains(';'))
            assertFalse(token.contains('!'))
            assertFalse(token.contains('…'))
        }
    }

    @Test
    fun containsPhraseMatchesSubstrings() {
        assertTrue(TextNormalizer.containsPhrase("hello nex how are you", setOf("hello nex")))
        assertTrue(TextNormalizer.containsPhrase("what's this", setOf("what's this")))
        assertFalse(TextNormalizer.containsPhrase("hello there", setOf("hello nex")))
    }

    @Test
    fun containsPhraseIsSafeForBlankInputAndBlankNeedles() {
        assertFalse(TextNormalizer.containsPhrase("", setOf("hello")))
        assertFalse(TextNormalizer.containsPhrase("   ", setOf("hello")))
        assertFalse(TextNormalizer.containsPhrase("hello", setOf("", "  ")))
        assertFalse(TextNormalizer.containsPhrase("hello", emptySet()))
    }

    @Test
    fun containsTokenMatchesWholeTokensOnly() {
        assertTrue(TextNormalizer.containsToken(listOf("hello", "nex"), setOf("nex")))
        assertFalse(TextNormalizer.containsToken(listOf("netflix"), setOf("net")))
        assertFalse(TextNormalizer.containsToken(listOf("know"), setOf("now")))
        assertFalse(TextNormalizer.containsToken(emptyList(), setOf("hello")))
        assertFalse(TextNormalizer.containsToken(listOf("hello"), emptySet()))
    }

    @Test
    fun normalizingTwiceChangesNothing() {
        listOf("Hello, NEX!", "नमस्ते नेक्स", "what's this", "100% party 🎉", "   ").forEach { input ->
            val once = TextNormalizer.normalize(input)
            assertEquals(once, TextNormalizer.normalize(once))
        }
    }
}
