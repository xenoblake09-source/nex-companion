package com.nexora.robot.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NEX's spoken and on-screen copy.
 *
 * Two things are pinned here: the exact honest wording of the recovery message, and the rule
 * that ordinary silence never produces a "did not catch that" style reproach.
 */
class VoiceMessagesTest {

    private val everyMessage = listOf(
        VoiceMessages.RECOVERING,
        VoiceMessages.IDLE,
        VoiceMessages.PERMISSION_BLOCKED,
        VoiceMessages.BACKGROUND_PAUSED,
        VoiceMessages.PREPARING,
        VoiceMessages.LISTENING_FOR_WAKE,
        VoiceMessages.LISTENING,
        VoiceMessages.RECOGNIZING,
        VoiceMessages.THINKING,
        VoiceMessages.ANSWER_READY,
        VoiceMessages.SPEAKING,
        VoiceMessages.WAKE_ACK,
        VoiceMessages.ERROR,
        VoiceMessages.OFFLINE,
        VoiceMessages.NETWORK_WAITING,
        VoiceMessages.NO_SPEECH_SERVICE,
        VoiceMessages.RECOVERY_GIVE_UP,
        VoiceMessages.SILENCE_KEEP_CALM,
        VoiceMessages.CAMERA_IN_USE,
        VoiceMessages.CAMERA_STOPPED,
    )

    @Test
    fun theRecoveryMessageIsExactlyTheAgreedHonestLine() {
        assertEquals("VOICE SERVICE RECOVERING — PLEASE WAIT", VoiceMessages.RECOVERING)
    }

    @Test
    fun theWakeAcknowledgementIsShortAndWarm() {
        assertEquals("Hi! I’m listening.", VoiceMessages.WAKE_ACK)
        assertTrue(VoiceMessages.WAKE_ACK.length <= 40)
    }

    @Test
    fun ordinarySilenceKeepsTheCalmListeningLine() {
        assertEquals(VoiceMessages.LISTENING, VoiceMessages.SILENCE_KEEP_CALM)
        assertEquals("I’m listening.", VoiceMessages.SILENCE_KEEP_CALM)
        assertFalse(VoiceMessages.isForbidden(VoiceMessages.SILENCE_KEEP_CALM))
    }

    @Test
    fun noMessageEverBlamesTheUserForSilence() {
        everyMessage.forEach { message ->
            assertFalse("'$message' is forbidden copy", VoiceMessages.isForbidden(message))
        }
    }

    @Test
    fun forbiddenPhrasesAreDetectedWhateverTheCasing() {
        VoiceMessages.FORBIDDEN_PHRASES.forEach { phrase ->
            assertTrue(phrase, VoiceMessages.isForbidden(phrase))
            assertTrue(phrase, VoiceMessages.isForbidden("Sorry, ${phrase.uppercase()}."))
            assertTrue(phrase, VoiceMessages.isForbidden("NEX $phrase, please try again"))
        }
    }

    @Test
    fun forbiddenPhraseCheckIsSafeForBlankInput() {
        assertFalse(VoiceMessages.isForbidden(null))
        assertFalse(VoiceMessages.isForbidden(""))
        assertFalse(VoiceMessages.isForbidden("   "))
        assertFalse(VoiceMessages.isForbidden("I’m listening."))
    }

    @Test
    fun noMessageLeaksTechnicalDetail() {
        everyMessage.forEach { message ->
            assertTrue(message.isNotBlank())
            listOf("ERROR_", "Exception", "SpeechRecognizer", "null", "code=", "stack", "TODO")
                .forEach { leak ->
                    assertFalse("'$message' leaks '$leak'", message.contains(leak))
                }
        }
    }

    @Test
    fun theGiveUpMessageAsksTheUserForOneClearAction() {
        assertEquals(
            "My voice service is not recovering. Please toggle voice mode.",
            VoiceMessages.RECOVERY_GIVE_UP,
        )
        assertTrue(VoiceMessages.RECOVERY_GIVE_UP.contains("Please"))
    }

    @Test
    fun offlineAndNetworkCopyNeverClaimAnAnswerWasFetched() {
        listOf(VoiceMessages.OFFLINE, VoiceMessages.NETWORK_WAITING).forEach { message ->
            assertFalse(message.contains("here is the answer", ignoreCase = true))
            assertFalse(message.contains("i found", ignoreCase = true))
            assertTrue(message.contains("offline") || message.contains("server"))
        }
    }

    @Test
    fun cameraCopyStatesTheScopeOfUse() {
        assertEquals("Camera is on for this question only.", VoiceMessages.CAMERA_IN_USE)
        assertEquals("Camera is off.", VoiceMessages.CAMERA_STOPPED)
    }

    @Test
    fun theWakePhraseIsAnnouncedInTheListeningCopy() {
        assertTrue(VoiceMessages.LISTENING_FOR_WAKE.contains("Hello NEX"))
    }
}
