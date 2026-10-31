package com.nexora.robot.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deciding whether an answer might need live information.
 *
 * The app only ever *signals* this to the backend; the server still decides whether search
 * grounding genuinely helped, and only reports sources when it did. So the important properties
 * here are: live intents are always flagged, a stable machine-readable reason is produced, and
 * ordinary study questions are not flagged (which would invite needless grounding).
 */
class CurrentInfoDetectorTest {

    @Test
    fun liveIntentsAreTheOnesThatChangeWithTimeOrPlace() {
        assertEquals(
            setOf(
                UserIntent.WEATHER,
                UserIntent.NEWS,
                UserIntent.LOCATION_QUERY,
                UserIntent.TIME_SENSITIVE_QUERY,
            ),
            CurrentInfoDetector.LIVE_INTENTS,
        )
        assertFalse(UserIntent.MATHS in CurrentInfoDetector.LIVE_INTENTS)
        assertFalse(UserIntent.STUDY_HELP in CurrentInfoDetector.LIVE_INTENTS)
        assertFalse(UserIntent.MEMORY_SAVE in CurrentInfoDetector.LIVE_INTENTS)
    }

    @Test
    fun liveIntentsAreAlwaysFlagged() {
        assertFlagged("what is the weather today", "intent:weather", UserIntent.WEATHER)
        assertFlagged("give me the headlines", "intent:news", UserIntent.NEWS)
        assertFlagged("where is the nearest library", "intent:location_query", UserIntent.LOCATION_QUERY)
        assertFlagged("what is the latest update", "intent:time_sensitive_query", UserIntent.TIME_SENSITIVE_QUERY)
        assertFlagged("aaj barish hogi kya", "intent:weather", UserIntent.WEATHER)
    }

    @Test
    fun studyQuestionsAreNotFlagged() {
        assertNotFlagged("explain photosynthesis", UserIntent.SCIENCE)
        assertNotFlagged("explain this fraction to me", UserIntent.MATHS)
        assertNotFlagged("help me with my homework", UserIntent.STUDY_HELP)
        assertNotFlagged("remember that my exam is on monday", UserIntent.MEMORY_SAVE)
        assertNotFlagged("hello", UserIntent.GREETING)
        assertNotFlagged("", UserIntent.GENERAL)
    }

    @Test
    fun aTimeWordInsideAnotherWordIsNotATimeMarker() {
        // "know" contains "now"; "believe" contains "live". Neither means "as of today".
        assertNotFlagged("do you know the answer", UserIntent.GENERAL)
        assertNotFlagged("I believe in you", UserIntent.GENERAL)
        assertNotFlagged("generate a function that operates on lists", UserIntent.CODING)
    }

    @Test
    fun aSingleTimeWordStillFlagsTheTurn() {
        assertFlagged("what is the price of this book", "time-marker:price", UserIntent.BOOK_OR_DOCUMENT)
        assertFlagged("इस किताब की कीमत क्या है", "time-marker:कीमत", UserIntent.BOOK_OR_DOCUMENT)
    }

    @Test
    fun aMultiWordTimeMarkerStillFlagsTheTurn() {
        assertFlagged(
            "tell me about this month in my notebook",
            "time-marker:this month",
            UserIntent.GENERAL,
        )
    }

    @Test
    fun anExplicitClientSignalAlwaysWins() {
        val decision = CurrentInfoDetector.decide("explain photosynthesis", explicitSignal = true)
        assertTrue(decision.needed)
        assertEquals("explicit-client-signal", decision.reason)
        assertEquals(UserIntent.SCIENCE, decision.intent)
    }

    @Test
    fun needsCurrentInfoAgreesWithDecide() {
        listOf(
            "what is the weather today",
            "explain photosynthesis",
            "do you know the answer",
            "what is the price of this book",
            "",
        ).forEach { utterance ->
            assertEquals(
                CurrentInfoDetector.decide(utterance).needed,
                CurrentInfoDetector.needsCurrentInfo(utterance),
            )
        }
    }

    @Test
    fun reasonsAreStableMachineReadableStrings() {
        val pattern = Regex("^(explicit-client-signal|intent:[a-z_]+|time-marker:\\S.*|no-current-info-needed)$")
        listOf(
            "what is the weather today",
            "explain photosynthesis",
            "what is the price of this book",
            "tell me about this month in my notebook",
        ).forEach { utterance ->
            val reason = CurrentInfoDetector.decide(utterance).reason
            assertTrue("'$reason' is not a stable reason string", pattern.matches(reason))
        }
        assertEquals("no-current-info-needed", CurrentInfoDetector.decide("explain photosynthesis").reason)
    }

    @Test
    fun timeMarkersCoverEnglishHindiAndHinglish() {
        val markers = CurrentInfoDetector.TIME_MARKERS
        listOf("today", "now", "latest", "price", "result").forEach { assertTrue(markers.contains(it)) }
        listOf("aaj", "abhi", "kal", "keemat", "nateeja").forEach { assertTrue(markers.contains(it)) }
        listOf("आज", "अभी", "कीमत", "नतीजा").forEach { assertTrue(markers.contains(it)) }
        assertTrue(markers.contains("this week"))
    }

    private fun assertFlagged(utterance: String, expectedReason: String, expectedIntent: UserIntent) {
        val decision = CurrentInfoDetector.decide(utterance)
        assertTrue("'$utterance' should need current info", decision.needed)
        assertEquals(expectedReason, decision.reason)
        assertEquals(expectedIntent, decision.intent)
    }

    private fun assertNotFlagged(utterance: String, expectedIntent: UserIntent) {
        val decision = CurrentInfoDetector.decide(utterance)
        assertFalse("'$utterance' should not need current info", decision.needed)
        assertEquals("no-current-info-needed", decision.reason)
        assertEquals(expectedIntent, decision.intent)
    }
}
