package com.nexora.robot.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nothing sensitive may reach logcat.
 *
 * [LogScrubber] is the last line of defence: it removes credential-shaped strings, e-mail
 * addresses, phone-number-shaped digit runs, long digit runs, quoted speech and control
 * characters, and it caps the length of any message.
 */
class LogScrubberTest {

    @Test
    fun theLengthCapIsTwoHundredCharacters() {
        assertEquals(200, LogScrubber.MAX_LENGTH)
    }

    @Test
    fun blankAndNullInputBecomeAnEmptyString() {
        assertEquals("", LogScrubber.scrub(null))
        assertEquals("", LogScrubber.scrub(""))
        assertEquals("", LogScrubber.scrub("   "))
        assertEquals("", LogScrubber.scrub("\n\t"))
    }

    @Test
    fun anOrdinaryTechnicalMessagePassesThroughUnchanged() {
        assertEquals("voice mode started", LogScrubber.scrub("voice mode started"))
        assertEquals("turn finished ms=42", LogScrubber.scrub("turn finished ms=42"))
        assertEquals("camera bound then unbound", LogScrubber.scrub("camera bound then unbound"))
    }

    @Test
    fun emailAddressesAreRedacted() {
        assertEquals("user [email] asked", LogScrubber.scrub("user student@example.com asked"))
        assertFalse(LogScrubber.scrub("contact a.b+c@sub.domain.co.in now").contains("domain.co.in"))
        assertTrue(LogScrubber.scrub("contact a.b+c@sub.domain.co.in now").contains("[email]"))
    }

    @Test
    fun phoneNumberShapedRunsAreRedacted() {
        assertEquals("call [phone] now", LogScrubber.scrub("call +91 98765 43210 now"))
        assertTrue(LogScrubber.scrub("number 9876543210").contains("[phone]"))
        assertFalse(LogScrubber.scrub("number 9876543210").contains("9876543210"))
        assertTrue(LogScrubber.scrub("dial (0612) 221-4455").contains("[phone]"))
    }

    @Test
    fun longDigitRunsAreRedacted() {
        assertEquals("roll [digits]", LogScrubber.scrub("roll 123456"))
        assertFalse(LogScrubber.scrub("account 4455667788990011").contains("445566"))
        // Short numbers are technical values and stay readable.
        assertEquals("ms=42 attempt=3", LogScrubber.scrub("ms=42 attempt=3"))
    }

    @Test
    fun quotedSpeechIsReplacedSoTranscriptsNeverReachTheLog() {
        val scrubbed = LogScrubber.scrub("turn text=\"explain photosynthesis in detail\" ms=42")
        assertEquals("turn text=[speech] ms=42", scrubbed)
        assertFalse(scrubbed.contains("photosynthesis"))
        // A short quoted token is not speech and is left alone.
        assertEquals("state=\"idle\"", LogScrubber.scrub("state=\"idle\""))
    }

    @Test
    fun credentialShapedStringsAreRedacted() {
        val cases = listOf(
            "key=AIzaSyDExampleKeyValueWithoutDigitsHere" to "key=[redacted]",
            "token sk-ExampleSecretKeyWithoutDigits" to "token [redacted]",
            "github ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZab" to "github [redacted]",
            "jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJuZXh1c2VyIn0.dGhpcy1pcy1hLXNpZw" to "jwt [redacted]",
            "slack xoxb-ABCDEFGHIJKLMNOP" to "slack [redacted]",
        )
        cases.forEach { (input, expected) ->
            assertEquals(expected, LogScrubber.scrub(input))
        }
    }

    @Test
    fun aKeyHiddenInsideASentenceIsStillRedacted() {
        val scrubbed = LogScrubber.scrub(
            "POST https://example.com with x-goog-api-key AIzaSyDExampleKeyValueWithoutDigitsHere failed",
        )
        assertFalse(scrubbed.contains("AIzaSyD"))
        assertTrue(scrubbed.contains("[redacted]"))
        assertTrue(scrubbed.contains("failed"))
    }

    @Test
    fun controlCharactersAreRemovedButNewlinesBecomeSpaces() {
        assertEquals("a b c", LogScrubber.scrub("a\u0000b\u0007c"))
        assertEquals("line1 line2 tab", LogScrubber.scrub("line1\nline2\ttab"))
        assertEquals("x y", LogScrubber.scrub("x\u001Fy"))
        assertFalse(LogScrubber.scrub("a\u0000b").contains('\u0000'))
    }

    @Test
    fun overlongMessagesAreTruncatedWithAnEllipsis() {
        val long = "a".repeat(400)
        val scrubbed = LogScrubber.scrub(long)
        assertEquals(LogScrubber.MAX_LENGTH + 1, scrubbed.length)
        assertTrue(scrubbed.endsWith("…"))
        // Exactly at the cap nothing is cut; one character below it nothing is added.
        assertEquals(200, LogScrubber.scrub("b".repeat(200)).length)
        assertEquals(199, LogScrubber.scrub("b".repeat(199)).length)
    }

    @Test
    fun sizeOnlyReportsLengthsWithoutContent() {
        assertEquals("chars=5", LogScrubber.sizeOnly("hello"))
        assertEquals("chars=0", LogScrubber.sizeOnly(""))
        assertEquals("chars=0", LogScrubber.sizeOnly(null))
        assertEquals("chars=1234", LogScrubber.sizeOnly("x".repeat(1_234)))
        assertFalse(LogScrubber.sizeOnly("hunter2").contains("hunter2"))
    }

    @Test
    fun scrubNeverThrowsAndNeverExceedsTheCap() {
        val oddInputs = listOf(
            "\"\"\"",
            "@",
            "🎉🎉🎉",
            "नमस्ते नेक्स",
            "\u0000\u0001\u0002",
            "9".repeat(500),
            "a@b.c",
            "((((0612))))",
            "AIza" + "-".repeat(500),
            "mixed \"speech\" and student@example.com and 9876543210 and AIzaSyDExampleKeyValue",
        )
        oddInputs.forEach { input ->
            val scrubbed = LogScrubber.scrub(input)
            assertTrue(scrubbed.length <= LogScrubber.MAX_LENGTH + 1)
            assertFalse(scrubbed.contains("student@example.com"))
            assertFalse(scrubbed.contains("9876543210"))
        }
    }

    @Test
    fun scrubbedOutputKeepsTheUsefulTechnicalShape() {
        val scrubbed = LogScrubber.scrub(
            "converse status=200 ms=412 rid=req_abc grounded=true sources=2 text=\"what is the weather\"",
        )
        assertTrue(scrubbed.contains("status=200"))
        assertTrue(scrubbed.contains("ms=412"))
        assertTrue(scrubbed.contains("grounded=true"))
        assertTrue(scrubbed.contains("sources=2"))
        assertTrue(scrubbed.contains("[speech]"))
        assertFalse(scrubbed.contains("weather"))
    }
}
