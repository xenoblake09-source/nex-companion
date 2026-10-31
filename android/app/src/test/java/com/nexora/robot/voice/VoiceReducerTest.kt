package com.nexora.robot.voice

import com.nexora.robot.animation.AnimationCue
import com.nexora.robot.privacy.MicBlockReason
import com.nexora.robot.privacy.MicDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conversation state machine, tested as a pure function: `(state, event, clock) -> state`.
 *
 * These tests are the proof behind NEX's microphone rules:
 *  * no recognition turn is ever started while NEX is speaking;
 *  * the microphone stays closed for at least 850 ms after speech ends;
 *  * ordinary silence stays calm and opens a new turn after ~650 ms, with no error copy and no
 *    retry counter movement;
 *  * real failures climb 1200 → 2500 → 5000 → 8000 ms and then ask the user;
 *  * a permission revoke, a background transition or voice-off stops voice and camera safely;
 *  * recovery never forces the user to repeat the wake phrase in the foreground.
 */
class VoiceReducerTest {

    private val t0 = 1_000L

    /** A session that is awake, in conversation, with the microphone gate already open. */
    private fun conversationReady(now: Long = t0): VoiceState {
        var state = VoiceReducer.initial(
            microphonePermissionGranted = true,
            cameraPermissionGranted = true,
            appInForeground = true,
        )
        state = VoiceReducer.reduce(state, VoiceEvent.VoiceModeEnabled, now)
        state = VoiceReducer.reduce(state, VoiceEvent.VoiceServicesReady, now + 100L)
        state = VoiceReducer.reduce(state, VoiceEvent.WakePhraseDetected, now + 200L)
        // now + 200 + 850 is the earliest instant the microphone may reopen.
        state = VoiceReducer.reduce(state, VoiceEvent.MicrophoneOpened, now + 1_100L)
        return state
    }

    /** Awake, hunting for the wake phrase, microphone gate open. */
    private fun wakeHunting(now: Long = t0): VoiceState {
        var state = VoiceReducer.initial(microphonePermissionGranted = true, appInForeground = true)
        state = VoiceReducer.reduce(state, VoiceEvent.VoiceModeEnabled, now)
        return VoiceReducer.reduce(state, VoiceEvent.VoiceServicesReady, now + 100L)
    }

    private fun inConversation(now: Long = t0): VoiceState {
        var state = VoiceReducer.initial(microphonePermissionGranted = true, appInForeground = true)
        state = VoiceReducer.reduce(state, VoiceEvent.VoiceModeEnabled, now)
        state = VoiceReducer.reduce(state, VoiceEvent.VoiceServicesReady, now + 100L)
        return VoiceReducer.reduce(state, VoiceEvent.WakePhraseDetected, now + 200L)
    }

    // ------------------------------------------------------------------
    // Initial state and reachability
    // ------------------------------------------------------------------

    @Test
    fun aFreshSessionIsIdleCalmAndSilent() {
        val initial = VoiceReducer.initial()
        assertEquals(ConversationState.IDLE, initial.state)
        assertFalse(initial.voiceModeEnabled)
        assertFalse(initial.microphonePermissionGranted)
        assertFalse(initial.cameraPermissionGranted)
        assertTrue(initial.appInForeground)
        assertTrue(initial.wakePhraseRequired)
        assertFalse(initial.microphoneOpen)
        assertFalse(initial.cameraInUse)
        assertEquals(0L, initial.nextActionAtMs)
        assertEquals(VoiceMessages.IDLE, initial.statusMessage)
        assertEquals(AnimationCue.CALM_IDLE_BREATHING, initial.animationCue)
        assertFalse(initial.canOpenMicrophone(0L))
    }

    @Test
    fun allTenConversationStatesAreReachable() {
        val reached = linkedSetOf<ConversationState>()
        var state = VoiceReducer.initial()
        reached += state.state

        state = VoiceReducer.reduce(state, VoiceEvent.VoiceModeEnabled, 1_000L)
        reached += state.state // blocked: no microphone permission yet

        state = VoiceReducer.reduce(state, VoiceEvent.MicrophonePermissionGranted, 1_100L)
        reached += state.state // preparing voice

        state = VoiceReducer.reduce(state, VoiceEvent.VoiceServicesReady, 1_200L)
        reached += state.state // listening for the wake phrase

        state = VoiceReducer.reduce(state, VoiceEvent.WakePhraseDetected, 1_300L)
        reached += state.state // listening for conversation

        state = VoiceReducer.reduce(state, VoiceEvent.MicrophoneOpened, 2_200L)
        reached += state.state // recognising

        state = VoiceReducer.reduce(state, VoiceEvent.Transcription("what is the weather"), 2_500L)
        reached += state.state // thinking

        state = VoiceReducer.reduce(state, VoiceEvent.ReplyReady(grounded = false, sourceCount = 0), 2_600L)
        state = VoiceReducer.reduce(state, VoiceEvent.SpeechStarted, 2_700L)
        reached += state.state // speaking

        state = VoiceReducer.reduce(state, VoiceEvent.SpeechCompleted(3_000L), 3_000L)
        state = VoiceReducer.reduce(state, VoiceEvent.RecognizerFailure(RecognizerFailureKind.CLIENT), 4_000L)
        reached += state.state // recovering

        state = VoiceReducer.reduce(state, VoiceEvent.Fatal("unrecoverable voice error"), 4_500L)
        reached += state.state // error

        assertEquals(ConversationState.entries.toSet(), reached)
        assertEquals(10, reached.size)
    }

    @Test
    fun everyStateHasHonestDefaultCopy() {
        ConversationState.entries.forEach { state ->
            val message = VoiceReducer.defaultStatusFor(state)
            assertTrue("$state has blank copy", message.isNotBlank())
            assertFalse("$state uses forbidden copy", VoiceMessages.isForbidden(message))
        }
    }

    // ------------------------------------------------------------------
    // Consent and permission gates
    // ------------------------------------------------------------------

    @Test
    fun enablingVoiceWithoutMicrophonePermissionIsBlockedNotListening() {
        val state = VoiceReducer.reduce(VoiceReducer.initial(), VoiceEvent.VoiceModeEnabled, t0)
        assertEquals(ConversationState.PAUSED_OR_PERMISSION_BLOCKED, state.state)
        assertTrue(state.voiceModeEnabled)
        assertFalse(state.microphoneOpen)
        assertEquals(VoiceMessages.PERMISSION_BLOCKED, state.statusMessage)
        assertEquals(AnimationCue.PERMISSION_REQUEST, state.animationCue)
        assertEquals(0L, state.nextActionAtMs)
        assertFalse(state.canOpenMicrophone(t0 + 60_000L))
        assertEquals(
            MicDecision.Blocked(MicBlockReason.PERMISSION_NOT_GRANTED),
            state.micDecision(t0 + 60_000L),
        )
    }

    @Test
    fun grantingTheMicrophoneAfterConsentStartsPreparingThenListening() {
        var state = VoiceReducer.reduce(VoiceReducer.initial(), VoiceEvent.VoiceModeEnabled, t0)
        state = VoiceReducer.reduce(state, VoiceEvent.MicrophonePermissionGranted, t0 + 50L)
        assertEquals(ConversationState.PREPARING_VOICE, state.state)
        assertEquals(VoiceMessages.PREPARING, state.statusMessage)
        assertEquals(AnimationCue.QUIET_LISTENING, state.animationCue)

        state = VoiceReducer.reduce(state, VoiceEvent.VoiceServicesReady, t0 + 150L)
        assertEquals(ConversationState.LISTENING_FOR_WAKE_PHRASE, state.state)
        assertEquals(VoiceMessages.LISTENING_FOR_WAKE, state.statusMessage)
        assertEquals(AnimationCue.LISTENING, state.animationCue)
        assertTrue(state.wakePhraseRequired)
    }

    @Test
    fun revokingTheMicrophoneStopsEverythingImmediately() {
        val listening = conversationReady()
        val revoked = VoiceReducer.reduce(listening, VoiceEvent.MicrophonePermissionRevoked, 5_000L)
        assertEquals(ConversationState.PAUSED_OR_PERMISSION_BLOCKED, revoked.state)
        assertFalse(revoked.microphonePermissionGranted)
        assertFalse(revoked.microphoneOpen)
        assertFalse(revoked.cameraInUse)
        assertFalse(revoked.voiceServicesReady)
        assertEquals(0L, revoked.nextActionAtMs)
        assertEquals(0L, revoked.micBlockedUntilMs)
        assertEquals(0, revoked.retryAttempt)
        assertNull(revoked.failureBurstStartedAtMs)
        assertEquals(VoiceMessages.PERMISSION_BLOCKED, revoked.statusMessage)
        assertEquals(AnimationCue.MICROPHONE_OFF, revoked.animationCue)
        assertFalse(revoked.canOpenMicrophone(99_999L))
    }

    @Test
    fun aPermissionFailureFromTheRecogniserIsTreatedAsARevoke() {
        val listening = inConversation()
        val state = VoiceReducer.reduce(
            listening,
            VoiceEvent.RecognizerFailure(RecognizerFailureKind.PERMISSION),
            6_000L,
        )
        assertEquals(ConversationState.PAUSED_OR_PERMISSION_BLOCKED, state.state)
        assertFalse(state.microphoneOpen)
        assertFalse(state.microphonePermissionGranted)
        assertEquals(0L, state.nextActionAtMs)
    }

    @Test
    fun cameraPermissionRevokedStopsTheCameraAndSaysSo() {
        var state = inConversation()
        state = VoiceReducer.reduce(state, VoiceEvent.CameraPermissionGranted, 6_800L)
        assertTrue(state.cameraPermissionGranted)
        state = VoiceReducer.reduce(state, VoiceEvent.CameraSessionStarted, 7_000L)
        assertTrue(state.cameraInUse)
        assertEquals(VoiceMessages.CAMERA_IN_USE, state.statusMessage)
        assertEquals(AnimationCue.CAMERA_ACTIVE, state.animationCue)

        val revoked = VoiceReducer.reduce(state, VoiceEvent.CameraPermissionRevoked, 7_500L)
        assertFalse(revoked.cameraPermissionGranted)
        assertFalse(revoked.cameraInUse)
        assertEquals(VoiceMessages.CAMERA_STOPPED, revoked.statusMessage)
        // The conversation itself survives: only the camera is taken away.
        assertEquals(ConversationState.LISTENING_FOR_CONVERSATION, revoked.state)
    }

    // ------------------------------------------------------------------
    // Background and voice-off
    // ------------------------------------------------------------------

    @Test
    fun goingToBackgroundStopsVoiceAndCameraAndSchedulesNothing() {
        var state = conversationReady()
        state = VoiceReducer.reduce(state, VoiceEvent.CameraSessionStarted, 8_000L)
        val backgrounded = VoiceReducer.reduce(state, VoiceEvent.AppBackgrounded, 8_500L)
        assertEquals(ConversationState.PAUSED_OR_PERMISSION_BLOCKED, backgrounded.state)
        assertFalse(backgrounded.appInForeground)
        assertFalse(backgrounded.microphoneOpen)
        assertFalse(backgrounded.cameraInUse)
        assertFalse(backgrounded.voiceServicesReady)
        assertEquals(0L, backgrounded.nextActionAtMs)
        assertEquals(0L, backgrounded.micBlockedUntilMs)
        assertEquals(VoiceMessages.BACKGROUND_PAUSED, backgrounded.statusMessage)
        assertEquals(
            MicDecision.Blocked(MicBlockReason.APP_IN_BACKGROUND),
            backgrounded.micDecision(9_000L),
        )
    }

    @Test
    fun returningToTheForegroundResumesWithoutDemandingTheWakePhraseAgain() {
        var state = inConversation()
        assertFalse("the wake phrase was already heard", state.wakePhraseRequired)
        state = VoiceReducer.reduce(state, VoiceEvent.AppBackgrounded, 9_000L)
        state = VoiceReducer.reduce(state, VoiceEvent.AppForegrounded, 10_000L)
        assertEquals(ConversationState.PREPARING_VOICE, state.state)
        assertFalse(state.microphoneOpen)

        state = VoiceReducer.reduce(state, VoiceEvent.VoiceServicesReady, 10_100L)
        assertEquals(ConversationState.LISTENING_FOR_CONVERSATION, state.state)
        assertFalse("foreground recovery must not force a wake-phrase repeat", state.wakePhraseRequired)
        assertEquals(VoiceMessages.LISTENING, state.statusMessage)
    }

    @Test
    fun turningVoiceOffReturnsToIdleAndArmsTheWakePhraseForNextTime() {
        val listening = conversationReady()
        val disabled = VoiceReducer.reduce(listening, VoiceEvent.VoiceModeDisabled, 11_000L)
        assertEquals(ConversationState.IDLE, disabled.state)
        assertFalse(disabled.voiceModeEnabled)
        assertFalse(disabled.microphoneOpen)
        assertFalse(disabled.cameraInUse)
        assertFalse(disabled.voiceServicesReady)
        assertTrue(disabled.wakePhraseRequired)
        assertEquals(0L, disabled.nextActionAtMs)
        assertEquals(0, disabled.retryAttempt)
        assertNull(disabled.failureBurstStartedAtMs)
        assertEquals(VoiceMessages.IDLE, disabled.statusMessage)
        assertEquals(AnimationCue.CALM_IDLE_BREATHING, disabled.animationCue)
    }

    @Test
    fun resetKeepsThePermissionFactsButEndsTheSession() {
        val listening = conversationReady()
        val reset = VoiceReducer.reduce(listening, VoiceEvent.Reset, 12_000L)
        assertEquals(ConversationState.IDLE, reset.state)
        assertFalse(reset.voiceModeEnabled)
        assertTrue(reset.microphonePermissionGranted)
        assertTrue(reset.cameraPermissionGranted)
        assertTrue(reset.appInForeground)
        assertTrue(reset.wakePhraseRequired)
    }

    // ------------------------------------------------------------------
    // Microphone stability
    // ------------------------------------------------------------------

    @Test
    fun theWakePhraseIsAcknowledgedAndThenGatesTheMicrophoneFor850Milliseconds() {
        val hunting = wakeHunting()
        assertEquals(ConversationState.LISTENING_FOR_WAKE_PHRASE, hunting.state)

        val woken = VoiceReducer.reduce(hunting, VoiceEvent.WakePhraseDetected, 2_000L)
        assertEquals(ConversationState.LISTENING_FOR_CONVERSATION, woken.state)
        assertFalse(woken.wakePhraseRequired)
        assertEquals(VoiceMessages.WAKE_ACK, woken.statusMessage)
        assertEquals(AnimationCue.WAKE_WORD_DETECTED, woken.animationCue)
        assertEquals(2_850L, woken.micBlockedUntilMs)
        assertEquals(2_850L, woken.nextActionAtMs)

        // Just before the settle window ends the microphone must stay closed.
        assertEquals(
            MicDecision.Blocked(MicBlockReason.AUDIO_ROUTE_SETTLING),
            woken.micDecision(2_849L),
        )
        assertEquals(MicDecision.Allowed, woken.micDecision(2_850L))

        val tooEarly = VoiceReducer.reduce(woken, VoiceEvent.MicrophoneOpened, 2_849L)
        assertEquals(ConversationState.LISTENING_FOR_CONVERSATION, tooEarly.state)
        assertFalse(tooEarly.microphoneOpen)
        assertEquals("MicrophoneOpened", tooEarly.ignoredEvent)

        val onTime = VoiceReducer.reduce(woken, VoiceEvent.MicrophoneOpened, 2_850L)
        assertEquals(ConversationState.RECOGNIZING, onTime.state)
        assertTrue(onTime.microphoneOpen)
        assertEquals(VoiceMessages.RECOGNIZING, onTime.statusMessage)
        assertEquals(AnimationCue.LISTENING, onTime.animationCue)
        assertEquals(0L, onTime.nextActionAtMs)
    }

    @Test
    fun speechCompletedClosesTheMicrophoneForTheFullSettleWindow() {
        val speaking = VoiceReducer.reduce(conversationReady(), VoiceEvent.SpeechStarted, 20_000L)
        assertEquals(ConversationState.SPEAKING, speaking.state)
        assertFalse(speaking.microphoneOpen)

        val completed = VoiceReducer.reduce(speaking, VoiceEvent.SpeechCompleted(21_000L), 21_000L)
        assertEquals(ConversationState.LISTENING_FOR_CONVERSATION, completed.state)
        assertEquals(21_850L, completed.micBlockedUntilMs)
        assertEquals(21_850L, completed.nextActionAtMs)
        assertFalse(completed.microphoneOpen)
        assertTrue(completed.voiceServicesReady)
        assertEquals(0, completed.retryAttempt)
        assertNull(completed.failureBurstStartedAtMs)
        assertEquals(0, completed.consecutiveSilences)
        assertFalse(completed.lastTurnWasSilence)
        assertEquals(VoiceMessages.LISTENING, completed.statusMessage)

        assertTrue(completed.micBlockedUntilMs - 21_000L >= TimingPolicy.POST_SPEECH_MIC_SETTLE_MS)
        assertFalse(completed.canOpenMicrophone(21_500L))
        assertTrue(completed.canOpenMicrophone(21_850L))
    }

    @Test
    fun speechCompletedReturnsToWakeHuntingOnlyWhenTheWakePhraseIsStillRequired() {
        val hunting = wakeHunting()
        val speaking = VoiceReducer.reduce(hunting, VoiceEvent.SpeechStarted, 22_000L)
        val completed = VoiceReducer.reduce(speaking, VoiceEvent.SpeechCompleted(23_000L), 23_000L)
        assertTrue(completed.wakePhraseRequired)
        assertEquals(ConversationState.LISTENING_FOR_WAKE_PHRASE, completed.state)
        assertEquals(VoiceMessages.LISTENING_FOR_WAKE, completed.statusMessage)
    }

    @Test
    fun noListeningOrTranscriptionEventIsAcceptedWhileSpeaking() {
        val speaking = VoiceReducer.reduce(conversationReady(), VoiceEvent.SpeechStarted, 30_000L)
        assertEquals(ConversationState.SPEAKING, speaking.state)

        val events = listOf<VoiceEvent>(
            VoiceEvent.MicrophoneOpened,
            VoiceEvent.WakePhraseDetected,
            VoiceEvent.Transcription("hello again"),
            VoiceEvent.RecognizerSilence,
            VoiceEvent.RecognizerFailure(RecognizerFailureKind.NETWORK),
            VoiceEvent.ThinkingStarted,
            VoiceEvent.ReplyReady(grounded = true, sourceCount = 2),
        )
        events.forEach { event ->
            val after = VoiceReducer.reduce(speaking, event, 30_500L)
            assertEquals("$event changed the state during speech", ConversationState.SPEAKING, after.state)
            assertFalse("$event opened the microphone during speech", after.microphoneOpen)
            assertEquals(event.label, after.ignoredEvent)
            assertEquals(event.label, after.lastEventLabel)
        }
    }

    @Test
    fun anOpenMicrophoneCannotBeOpenedAgainAndAThinkingTurnBlocksItToo() {
        val recognizing = conversationReady()
        assertEquals(ConversationState.RECOGNIZING, recognizing.state)
        assertEquals(
            MicDecision.Blocked(MicBlockReason.MICROPHONE_ALREADY_OPEN),
            recognizing.micDecision(99_999L),
        )

        val thinking = VoiceReducer.reduce(recognizing, VoiceEvent.Transcription("explain gravity"), 31_000L)
        assertEquals(ConversationState.THINKING, thinking.state)
        assertFalse(thinking.microphoneOpen)
        assertEquals(
            MicDecision.Blocked(MicBlockReason.TURN_IN_PROGRESS),
            thinking.micDecision(99_999L),
        )
        assertFalse(thinking.canOpenMicrophone(99_999L))
    }

    @Test
    fun voiceModeOffIsAHardGateEvenWithPermissionAndForeground() {
        val idle = VoiceReducer.reduce(
            VoiceReducer.initial(microphonePermissionGranted = true, appInForeground = true),
            VoiceEvent.VoiceServicesReady,
            t0,
        )
        assertEquals(ConversationState.IDLE, idle.state)
        assertEquals(MicDecision.Blocked(MicBlockReason.VOICE_MODE_OFF), idle.micDecision(t0))
    }

    // ------------------------------------------------------------------
    // Ordinary silence versus real failure
    // ------------------------------------------------------------------

    @Test
    fun ordinarySilenceStaysCalmAndSchedulesANewTurnIn650Milliseconds() {
        val listening = inConversation()
        val silence = VoiceReducer.reduce(listening, VoiceEvent.RecognizerSilence, 40_000L)
        assertEquals(ConversationState.LISTENING_FOR_CONVERSATION, silence.state)
        assertFalse(silence.isRecovering)
        assertFalse(silence.isError)
        assertEquals(VoiceMessages.SILENCE_KEEP_CALM, silence.statusMessage)
        assertEquals(VoiceMessages.LISTENING, silence.statusMessage)
        assertFalse(VoiceMessages.isForbidden(silence.statusMessage))
        assertEquals(AnimationCue.LISTENING, silence.animationCue)
        assertEquals(40_650L, silence.nextActionAtMs)
        assertEquals(40_650L, TimingPolicy.nextTurnAfterSilence(40_000L))
        assertEquals(1, silence.consecutiveSilences)
        assertTrue(silence.lastTurnWasSilence)
        assertFalse(silence.microphoneOpen)
        // Silence is not a failure: no retry, no burst.
        assertEquals(0, silence.retryAttempt)
        assertNull(silence.failureBurstStartedAtMs)
    }

    @Test
    fun anEmptyTranscriptionIsTreatedAsSilenceNotAsAnError() {
        val listening = inConversation()
        val blank = VoiceReducer.reduce(listening, VoiceEvent.Transcription("   "), 41_000L)
        assertEquals(ConversationState.LISTENING_FOR_CONVERSATION, blank.state)
        assertEquals(1, blank.consecutiveSilences)
        assertTrue(blank.lastTurnWasSilence)
        assertEquals(0, blank.retryAttempt)
        assertEquals(41_650L, blank.nextActionAtMs)
        assertFalse(VoiceMessages.isForbidden(blank.statusMessage))
    }

    @Test
    fun repeatedSilenceNeverEscalatesIntoAnErrorOrAReproach() {
        var state = inConversation()
        repeat(6) { index ->
            state = VoiceReducer.reduce(state, VoiceEvent.RecognizerSilence, 42_000L + index * 1_000L)
            assertEquals(ConversationState.LISTENING_FOR_CONVERSATION, state.state)
            assertEquals(index + 1, state.consecutiveSilences)
            assertEquals(0, state.retryAttempt)
            assertNull(state.failureBurstStartedAtMs)
            assertFalse(VoiceMessages.isForbidden(state.statusMessage))
            assertNotEquals(VoiceMessages.RECOVERING, state.statusMessage)
        }
    }

    @Test
    fun silenceWhileHuntingForTheWakePhraseKeepsHunting() {
        val hunting = wakeHunting()
        val silence = VoiceReducer.reduce(hunting, VoiceEvent.RecognizerSilence, 43_000L)
        assertEquals(ConversationState.LISTENING_FOR_WAKE_PHRASE, silence.state)
        assertEquals(VoiceMessages.LISTENING_FOR_WAKE, silence.statusMessage)
        assertEquals(43_650L, silence.nextActionAtMs)
    }

    @Test
    fun aRealFailureEntersRecoveryWithHonestCopyAndBoundedBackoff() {
        val listening = inConversation()
        val failure = VoiceReducer.reduce(
            listening,
            VoiceEvent.RecognizerFailure(RecognizerFailureKind.NETWORK),
            50_000L,
        )
        assertEquals(ConversationState.RECOVERING_VOICE_SERVICE, failure.state)
        assertTrue(failure.isRecovering)
        assertEquals(VoiceMessages.RECOVERING, failure.statusMessage)
        assertEquals("VOICE SERVICE RECOVERING — PLEASE WAIT", failure.statusMessage)
        assertEquals(AnimationCue.NETWORK_WAITING, failure.animationCue)
        assertEquals(1, failure.retryAttempt)
        assertEquals(50_000L, failure.failureBurstStartedAtMs)
        assertEquals(51_200L, failure.nextActionAtMs)
        assertFalse(failure.microphoneOpen)
        assertEquals(0L, failure.micBlockedUntilMs)
        assertTrue(failure.isActionDue(51_200L))
    }

    @Test
    fun theBackoffLadderClimbs1200_2500_5000AndThenAsksTheUser() {
        var state = inConversation()
        var now = 60_000L
        val observed = mutableListOf<Long>()

        repeat(3) {
            state = VoiceReducer.reduce(state, VoiceEvent.RecognizerFailure(RecognizerFailureKind.CLIENT), now)
            observed += state.nextActionAtMs - now
            assertEquals(VoiceMessages.RECOVERING, state.statusMessage)
            assertEquals(60_000L, state.failureBurstStartedAtMs)
            now = state.nextActionAtMs
        }
        assertEquals(listOf(1_200L, 2_500L, 5_000L), observed)
        assertEquals(3, state.retryAttempt)

        // The fourth failure inside the same burst exhausts it.
        state = VoiceReducer.reduce(state, VoiceEvent.RecognizerFailure(RecognizerFailureKind.CLIENT), now)
        assertEquals(4, state.retryAttempt)
        assertEquals(AnimationCue.VOICE_RECOVERING, state.animationCue)
        assertEquals(0L, state.nextActionAtMs)
        assertEquals(VoiceMessages.RECOVERY_GIVE_UP, state.statusMessage)
        assertEquals(ConversationState.RECOVERING_VOICE_SERVICE, state.state)
        assertFalse(state.microphoneOpen)
    }

    @Test
    fun anySuccessResetsTheFailureBurst() {
        var state = inConversation()
        state = VoiceReducer.reduce(state, VoiceEvent.RecognizerFailure(RecognizerFailureKind.BUSY), 70_000L)
        state = VoiceReducer.reduce(state, VoiceEvent.RecognizerFailure(RecognizerFailureKind.BUSY), 71_500L)
        assertEquals(2, state.retryAttempt)

        val recovered = VoiceReducer.reduce(state, VoiceEvent.RecoverySucceeded, 74_000L)
        assertEquals(0, recovered.retryAttempt)
        assertNull(recovered.failureBurstStartedAtMs)
        assertTrue(recovered.voiceServicesReady)
        assertEquals(ConversationState.LISTENING_FOR_CONVERSATION, recovered.state)
        assertFalse(recovered.wakePhraseRequired)

        val reopened = VoiceReducer.reduce(state, VoiceEvent.MicrophoneOpened, 75_000L)
        assertEquals(ConversationState.RECOGNIZING, reopened.state)
        assertEquals(0, reopened.retryAttempt)
        assertNull(reopened.failureBurstStartedAtMs)
    }

    @Test
    fun aMicrophoneRejectionRecoversWithoutLeakingTheTechnicalReason() {
        val listening = inConversation()
        val rejected = VoiceReducer.reduce(
            listening,
            VoiceEvent.MicrophoneRejected("AudioRecord initialisation failed, status -3"),
            80_000L,
        )
        assertEquals(ConversationState.RECOVERING_VOICE_SERVICE, rejected.state)
        assertEquals(VoiceMessages.RECOVERING, rejected.statusMessage)
        assertFalse(rejected.statusMessage.contains("AudioRecord"))
        assertFalse(rejected.statusMessage.contains("-3"))
        assertNull(rejected.ignoredEvent)
    }

    @Test
    fun unavailableVoiceServicesRecoverWhenRetryableAndBlockWhenNot() {
        val listening = inConversation()

        val retryable = VoiceReducer.reduce(
            listening,
            VoiceEvent.VoiceServicesUnavailable("engine restarting", retryable = true),
            81_000L,
        )
        assertEquals(ConversationState.RECOVERING_VOICE_SERVICE, retryable.state)
        assertEquals(VoiceMessages.NO_SPEECH_SERVICE, retryable.statusMessage)
        assertEquals(AnimationCue.VOICE_RECOVERING, retryable.animationCue)
        assertFalse(retryable.voiceServicesReady)
        assertFalse(retryable.microphoneOpen)

        val permanent = VoiceReducer.reduce(
            listening,
            VoiceEvent.VoiceServicesUnavailable("", retryable = false),
            82_000L,
        )
        assertEquals(ConversationState.PAUSED_OR_PERMISSION_BLOCKED, permanent.state)
        assertEquals(VoiceMessages.NO_SPEECH_SERVICE, permanent.statusMessage)
        assertEquals(0L, permanent.nextActionAtMs)

        val withReason = VoiceReducer.reduce(
            listening,
            VoiceEvent.VoiceServicesUnavailable("No speech engine is installed", retryable = false),
            83_000L,
        )
        assertEquals("No speech engine is installed", withReason.statusMessage)
    }

    @Test
    fun voiceServiceEventsAreIgnoredWhileVoiceModeIsOff() {
        val idle = VoiceReducer.initial(microphonePermissionGranted = true)
        val ignored = VoiceReducer.reduce(idle, VoiceEvent.VoiceServicesUnavailable("no engine", retryable = false), 84_000L)
        assertEquals(ConversationState.IDLE, ignored.state)
        assertEquals("VoiceServicesUnavailable", ignored.ignoredEvent)

        val preparing = VoiceReducer.reduce(idle, VoiceEvent.VoiceServicesPreparing, 84_500L)
        assertEquals(ConversationState.IDLE, preparing.state)
        assertEquals("VoiceServicesPreparing", preparing.ignoredEvent)
    }

    // ------------------------------------------------------------------
    // Turn progression and grounding honesty
    // ------------------------------------------------------------------

    @Test
    fun aTranscriptionMovesToThinkingAndRecordsOnlyTheUsersWords() {
        val recognizing = conversationReady()
        val thinking = VoiceReducer.reduce(
            recognizing,
            VoiceEvent.Transcription("  explain photosynthesis  "),
            90_000L,
        )
        assertEquals(ConversationState.THINKING, thinking.state)
        assertEquals("explain photosynthesis", thinking.lastTranscript)
        assertEquals("Transcription", thinking.lastEventLabel)
        assertFalse(thinking.lastEventLabel.contains("photosynthesis"))
        assertFalse(thinking.microphoneOpen)
        assertEquals(0, thinking.consecutiveSilences)
        assertEquals(0, thinking.retryAttempt)
        assertEquals(VoiceMessages.THINKING, thinking.statusMessage)
        assertEquals(AnimationCue.THINKING, thinking.animationCue)
    }

    @Test
    fun aGroundedReplyCelebratesASourceAndAnUngroundedOneDoesNot() {
        var state = VoiceReducer.reduce(conversationReady(), VoiceEvent.Transcription("today's news"), 91_000L)

        val grounded = VoiceReducer.reduce(state, VoiceEvent.ReplyReady(grounded = true, sourceCount = 2), 91_500L)
        assertEquals(AnimationCue.SOURCE_FOUND, grounded.animationCue)
        assertEquals(VoiceMessages.ANSWER_READY, grounded.statusMessage)

        state = VoiceReducer.reduce(conversationReady(), VoiceEvent.Transcription("explain gravity"), 92_000L)
        val ungrounded = VoiceReducer.reduce(state, VoiceEvent.ReplyReady(grounded = false, sourceCount = 0), 92_500L)
        assertEquals(AnimationCue.ANSWER_READY, ungrounded.animationCue)
        assertNotEquals(AnimationCue.SOURCE_FOUND, ungrounded.animationCue)
    }

    @Test
    fun serverUnavailableReturnsToListeningWithAnHonestLine() {
        val thinking = VoiceReducer.reduce(conversationReady(), VoiceEvent.Transcription("latest scores"), 93_000L)

        val offline = VoiceReducer.reduce(thinking, VoiceEvent.ServerUnavailable(offline = true), 94_000L)
        assertEquals(ConversationState.LISTENING_FOR_CONVERSATION, offline.state)
        assertEquals(VoiceMessages.OFFLINE, offline.statusMessage)
        assertEquals(AnimationCue.OFFLINE, offline.animationCue)
        assertEquals(94_650L, offline.nextActionAtMs)
        assertFalse(offline.microphoneOpen)

        val slow = VoiceReducer.reduce(thinking, VoiceEvent.ServerUnavailable(offline = false), 95_000L)
        assertEquals(VoiceMessages.NETWORK_WAITING, slow.statusMessage)
        assertEquals(AnimationCue.NETWORK_WAITING, slow.animationCue)
    }

    @Test
    fun serverUnavailableOutsideThinkingIsIgnored() {
        val listening = inConversation()
        val ignored = VoiceReducer.reduce(listening, VoiceEvent.ServerUnavailable(offline = true), 96_000L)
        assertEquals(listening.state, ignored.state)
        assertEquals("ServerUnavailable", ignored.ignoredEvent)
        assertEquals(VoiceMessages.LISTENING, ignored.statusMessage)
    }

    @Test
    fun aFatalEventMovesToErrorAndStopsCapture() {
        var state = conversationReady()
        state = VoiceReducer.reduce(state, VoiceEvent.CameraSessionStarted, 97_000L)
        val error = VoiceReducer.reduce(state, VoiceEvent.Fatal("recogniser died"), 97_500L)
        assertEquals(ConversationState.ERROR, error.state)
        assertTrue(error.isError)
        assertFalse(error.microphoneOpen)
        assertFalse(error.cameraInUse)
        assertEquals(0L, error.nextActionAtMs)
        assertEquals("recogniser died", error.statusMessage)
        assertEquals(AnimationCue.ERROR, error.animationCue)

        val blankFatal = VoiceReducer.reduce(state, VoiceEvent.Fatal("  "), 98_000L)
        assertEquals(VoiceMessages.ERROR, blankFatal.statusMessage)
    }

    // ------------------------------------------------------------------
    // Camera and bookkeeping
    // ------------------------------------------------------------------

    @Test
    fun cameraSessionEventsToggleTheVisibleInUseState() {
        val listening = inConversation()
        val started = VoiceReducer.reduce(listening, VoiceEvent.CameraSessionStarted, 99_000L)
        assertTrue(started.cameraInUse)
        assertEquals(VoiceMessages.CAMERA_IN_USE, started.statusMessage)
        assertEquals(AnimationCue.CAMERA_ACTIVE, started.animationCue)

        val stopped = VoiceReducer.reduce(started, VoiceEvent.CameraSessionStopped, 99_500L)
        assertFalse(stopped.cameraInUse)
        assertEquals(VoiceMessages.LISTENING, stopped.statusMessage)
        assertEquals(AnimationCue.LISTENING, stopped.animationCue)
    }

    @Test
    fun aWakePhraseOutsideWakeHuntingIsIgnored() {
        val listening = inConversation()
        val ignored = VoiceReducer.reduce(listening, VoiceEvent.WakePhraseDetected, 100_000L)
        assertEquals(ConversationState.LISTENING_FOR_CONVERSATION, ignored.state)
        assertEquals("WakePhraseDetected", ignored.ignoredEvent)
        assertEquals(VoiceMessages.LISTENING, ignored.statusMessage)
        assertNotEquals(VoiceMessages.WAKE_ACK, ignored.statusMessage)
    }

    @Test
    fun aWakePhraseWindowExpiryChangesNothing() {
        val hunting = wakeHunting()
        val after = VoiceReducer.reduce(hunting, VoiceEvent.WakePhraseWindowElapsed, 101_000L)
        assertEquals(hunting.state, after.state)
        assertEquals(hunting.statusMessage, after.statusMessage)
        assertEquals("WakePhraseWindowElapsed", after.ignoredEvent)
    }

    @Test
    fun reduceNeverMutatesTheIncomingState() {
        val before = inConversation()
        val snapshot = before.copy()
        val after = VoiceReducer.reduce(before, VoiceEvent.RecognizerSilence, 102_000L)
        assertNotSame(before, after)
        assertEquals(snapshot, before)
        assertEquals(0, before.consecutiveSilences)
        assertEquals(1, after.consecutiveSilences)
    }

    @Test
    fun theStateOnlyEverSchedulesOneActionAtATime() {
        val listening = inConversation()
        val events = listOf<VoiceEvent>(
            VoiceEvent.RecognizerSilence,
            VoiceEvent.RecognizerFailure(RecognizerFailureKind.CLIENT),
            VoiceEvent.MicrophoneOpened,
        )
        events.forEach { event ->
            val after = VoiceReducer.reduce(listening, event, 103_000L)
            assertTrue(
                "$event left an impossible schedule",
                after.nextActionAtMs == 0L || after.nextActionAtMs >= 103_000L,
            )
        }
    }
}
