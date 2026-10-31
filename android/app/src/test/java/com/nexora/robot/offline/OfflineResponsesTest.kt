package com.nexora.robot.offline

import com.nexora.robot.animation.AnimationCue
import com.nexora.robot.animation.AnimationSpec
import com.nexora.robot.language.UserIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline honesty.
 *
 * With no network NEX must still be useful — greetings, privacy help, study prompts, memory
 * acknowledgements, settings commands and the local focus timer — while never inventing an
 * online answer, never claiming it searched, and never silently queueing anything for later.
 */
class OfflineResponsesTest {

    private val devanagari = Regex("[\\u0900-\\u097F]")

    @Test
    fun everyIntentHasAnOfflineReply() {
        UserIntent.entries.forEach { intent ->
            val reply = OfflineResponses.forIntent(intent)
            assertTrue("$intent has a blank offline reply", reply.text.isNotBlank())
            assertTrue(
                "$intent maps to an unknown animation",
                reply.animationCue.animationId in AnimationSpec.MIN_ID..AnimationSpec.MAX_ID,
            )
        }
    }

    @Test
    fun noOfflineReplyEverClaimsInternetAccess() {
        val forbidden = listOf(
            "i searched",
            "i looked it up",
            "according to the internet",
            "the web says",
            "i browsed",
            "live data",
            "here are the latest",
            "i found online",
            "queued",
            "i will upload",
        )
        val replies = buildList {
            UserIntent.entries.forEach { intent ->
                add(OfflineResponses.forIntent(intent))
                add(OfflineResponses.forIntent(intent, "hi"))
                add(OfflineResponses.forIntent(intent, "hi", timerMinutes = 25))
            }
            add(OfflineResponses.offlineNotice("en"))
            add(OfflineResponses.offlineNotice("hi"))
            add(OfflineResponses.serverUnreachable("en"))
            add(OfflineResponses.serverUnreachable("hi"))
            add(OfflineResponses.visionUnavailable("en"))
            add(OfflineResponses.visionUnavailable("hi"))
            add(OfflineResponses.privacyHelp("en"))
            add(OfflineResponses.privacyHelp("hi"))
            add(OfflineResponses.permissionHelp("en"))
            add(OfflineResponses.permissionHelp("hi"))
            add(OfflineResponses.nothingRemembered("en"))
            add(OfflineResponses.memoryDeletedAck("en"))
            add(OfflineResponses.greeting("en"))
            add(OfflineResponses.farewell("en"))
            add(OfflineResponses.thanks("en"))
        }
        replies.forEach { reply ->
            assertFalse("'$reply' claims internet access", reply.claimsInternet)
            forbidden.forEach { phrase ->
                assertFalse(
                    "'${reply.text}' claims '$phrase'",
                    reply.text.lowercase().contains(phrase),
                )
            }
            assertFalse("no reply may claim to be online", reply.text.lowercase().contains("i am online"))
        }
    }

    @Test
    fun liveInformationIsRefusedRatherThanGuessed() {
        listOf(UserIntent.WEATHER, UserIntent.NEWS, UserIntent.TIME_SENSITIVE_QUERY, UserIntent.LOCATION_QUERY)
            .forEach { intent ->
                val reply = OfflineResponses.forIntent(intent)
                assertEquals(AnimationCue.OFFLINE, reply.animationCue)
                assertTrue(reply.text.contains("offline"))
                assertTrue(
                    "'${reply.text}' must refuse to guess",
                    reply.text.contains("not guess") ||
                        reply.text.contains("will not invent") ||
                        reply.text.contains("cannot"),
                )
            }
    }

    @Test
    fun greetingsPrivacyHelpAndStudyPromptsStillWorkOffline() {
        assertEquals(AnimationCue.GREETING, OfflineResponses.greeting("en").animationCue)
        assertEquals(AnimationCue.HINDI_HELLO, OfflineResponses.greeting("hi").animationCue)
        assertTrue(OfflineResponses.greeting("en").text.contains("NEX"))

        val privacy = OfflineResponses.privacyHelp("en")
        assertEquals(AnimationCue.PRIVACY_SHIELD, privacy.animationCue)
        assertTrue(privacy.text.contains("microphone"))
        assertTrue(privacy.text.contains("camera"))
        assertTrue(privacy.text.contains("coarse location"))
        assertTrue(privacy.text.contains("this device"))

        val permissions = OfflineResponses.permissionHelp("en")
        assertEquals(AnimationCue.PERMISSION_REQUEST, permissions.animationCue)
        assertTrue(permissions.text.contains("revoke"))

        val study = OfflineResponses.studyPrompt(UserIntent.MATHS, "en")
        assertEquals(AnimationCue.MATHS, study.animationCue)
        assertTrue(study.text.startsWith("We are offline"))
    }

    @Test
    fun studyPromptsAreLocallyWrittenAndNeverClaimedAsResearch() {
        listOf(
            UserIntent.MATHS,
            UserIntent.SCIENCE,
            UserIntent.CODING,
            UserIntent.CREATIVITY,
            UserIntent.BOOK_OR_DOCUMENT,
            UserIntent.STUDY_HELP,
        ).forEach { intent ->
            val prompts = OfflineResponses.studyPrompts(intent, "en")
            assertEquals(3, prompts.size)
            prompts.forEach { assertTrue(it.isNotBlank()) }
            val reply = OfflineResponses.studyPrompt(intent, "en")
            assertEquals(prompts.first(), reply.text.removePrefix("We are offline, so here is a practice prompt. "))
            assertFalse(reply.claimsInternet)
        }
        assertEquals(3, OfflineResponses.studyPrompts(UserIntent.MATHS, "hi").size)
    }

    @Test
    fun studyAnimationsFollowTheSubject() {
        assertEquals(AnimationCue.MATHS, OfflineResponses.studyPrompt(UserIntent.MATHS, "en").animationCue)
        assertEquals(AnimationCue.SCIENCE, OfflineResponses.studyPrompt(UserIntent.SCIENCE, "en").animationCue)
        assertEquals(AnimationCue.CODING, OfflineResponses.studyPrompt(UserIntent.CODING, "en").animationCue)
        assertEquals(
            AnimationCue.CREATIVITY,
            OfflineResponses.studyPrompt(UserIntent.CREATIVITY, "en").animationCue,
        )
        assertEquals(
            AnimationCue.BOOK_LEARNING,
            OfflineResponses.studyPrompt(UserIntent.BOOK_OR_DOCUMENT, "en").animationCue,
        )
        assertEquals(
            AnimationCue.STUDY_FOCUS,
            OfflineResponses.studyPrompt(UserIntent.STUDY_HELP, "en").animationCue,
        )
    }

    @Test
    fun hindiAndEnglishRepliesDiffer() {
        UserIntent.entries.forEach { intent ->
            val english = OfflineResponses.forIntent(intent, "en").text
            val hindi = OfflineResponses.forIntent(intent, "hi").text
            assertNotEquals("$intent has no Hindi reply", english, hindi)
            assertTrue("$intent Hindi reply has no Devanagari", devanagari.containsMatchIn(hindi))
        }
    }

    @Test
    fun languageDetectionIsAPrefixCheck() {
        assertTrue(OfflineResponses.isHindi("hi"))
        assertTrue(OfflineResponses.isHindi("hi-IN"))
        assertTrue(OfflineResponses.isHindi("HI"))
        assertFalse(OfflineResponses.isHindi("en"))
        assertFalse(OfflineResponses.isHindi("en-IN"))
        assertFalse(OfflineResponses.isHindi(""))
        assertFalse(OfflineResponses.isHindi(null))
    }

    @Test
    fun theTimerReplyDependsOnWhetherADurationWasReallyParsed() {
        val running = OfflineResponses.forIntent(UserIntent.TIMER_START, "en", timerMinutes = 25)
        assertEquals(AnimationCue.COUNTDOWN, running.animationCue)
        assertTrue(running.text.contains("25 minute"))
        assertTrue(running.text.contains("focus timer is running"))

        val asking = OfflineResponses.forIntent(UserIntent.TIMER_START, "en")
        assertEquals(AnimationCue.COUNTDOWN, asking.animationCue)
        assertTrue(asking.text.contains("How many minutes"))

        val zero = OfflineResponses.forIntent(UserIntent.TIMER_START, "en", timerMinutes = 0)
        assertEquals(asking.text, zero.text)
    }

    @Test
    fun serverUnreachableIsDifferentFromBeingOffline() {
        val offline = OfflineResponses.offlineNotice("en")
        val unreachable = OfflineResponses.serverUnreachable("en")
        assertNotEquals(offline.text, unreachable.text)
        assertEquals(AnimationCue.OFFLINE, offline.animationCue)
        assertEquals(AnimationCue.NETWORK_WAITING, unreachable.animationCue)
        assertTrue(unreachable.text.contains("server"))
        assertTrue(unreachable.text.contains("not guess"))
        assertTrue(offline.text.contains("offline"))
    }

    @Test
    fun visionNeedsTheServerAndSaysSo() {
        val reply = OfflineResponses.visionUnavailable("en")
        assertEquals(AnimationCue.OFFLINE, reply.animationCue)
        assertTrue(reply.text.contains("server"))
        assertTrue(reply.text.contains("offline"))
        assertFalse(reply.claimsInternet)
    }

    @Test
    fun memoryAcknowledgementsAreDeviceLocal() {
        val saved = OfflineResponses.forIntent(UserIntent.MEMORY_SAVE, "en")
        assertEquals(OfflineResponses.memorySavedAck("en"), saved)
        assertEquals(AnimationCue.MEMORY_SAVE, saved.animationCue)
        assertTrue(saved.text.contains("this device only"))

        val recalled = OfflineResponses.forIntent(UserIntent.MEMORY_RECALL, "en")
        assertEquals(AnimationCue.MEMORY_RECALL, recalled.animationCue)
        assertTrue(recalled.text.contains("stored on this device"))
        assertEquals(recalled, OfflineResponses.forIntent(UserIntent.MEMORY_LIST, "en"))

        val deleted = OfflineResponses.memoryDeletedAck("en")
        assertEquals(AnimationCue.PRIVACY_SHIELD, deleted.animationCue)
        assertTrue(deleted.text.contains("deleted from this device"))

        val nothing = OfflineResponses.nothingRemembered("en")
        assertEquals(AnimationCue.MEMORY_RECALL, nothing.animationCue)
        assertTrue(nothing.text.contains("anything remembered yet"))
    }

    @Test
    fun musicIsHonestlyUnavailableInThisBuild() {
        val reply = OfflineResponses.forIntent(UserIntent.MUSIC, "en")
        assertEquals(AnimationCue.CONFUSED, reply.animationCue)
        assertTrue(reply.text.contains("not part of this build"))
    }

    @Test
    fun theOfflineMarkerIsTheWordOffline() {
        assertEquals("offline", OfflineResponses.OFFLINE_MARKER)
    }

    @Test
    fun everyReplyIsMeantToBeSpoken() {
        UserIntent.entries.forEach { intent ->
            assertTrue(
                "$intent produced a silent reply",
                OfflineResponses.forIntent(intent).speak,
            )
        }
    }
}
