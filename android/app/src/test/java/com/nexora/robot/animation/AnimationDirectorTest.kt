package com.nexora.robot.animation

import com.nexora.robot.language.UserIntent
import com.nexora.robot.language.WeatherKind
import com.nexora.robot.voice.ConversationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AnimationDirector] is the decision table between "what is happening" and "what NEX shows".
 * It is pure Kotlin, so the whole table is checked here without an emulator.
 */
class AnimationDirectorTest {

    @Test
    fun everyConversationStateMapsToAValidCue() {
        ConversationState.entries.forEach { state ->
            val cue = AnimationDirector.cueForState(state)
            assertTrue(
                "$state mapped to an unknown animation ${cue.animationId}",
                cue.animationId in AnimationSpec.MIN_ID..AnimationSpec.MAX_ID,
            )
        }
    }

    @Test
    fun everyUserIntentMapsToAValidCue() {
        UserIntent.entries.forEach { intent ->
            val cue = AnimationDirector.cueForIntent(intent)
            assertTrue(
                "$intent mapped to an unknown animation ${cue.animationId}",
                cue.animationId in AnimationSpec.MIN_ID..AnimationSpec.MAX_ID,
            )
        }
    }

    @Test
    fun stateMappingsAreTheAgreedOnes() {
        assertEquals(AnimationCue.CALM_IDLE_BREATHING, AnimationDirector.cueForState(ConversationState.IDLE))
        assertEquals(AnimationCue.QUIET_LISTENING, AnimationDirector.cueForState(ConversationState.PREPARING_VOICE))
        assertEquals(
            AnimationCue.LISTENING,
            AnimationDirector.cueForState(ConversationState.LISTENING_FOR_WAKE_PHRASE),
        )
        assertEquals(
            AnimationCue.LISTENING,
            AnimationDirector.cueForState(ConversationState.LISTENING_FOR_CONVERSATION),
        )
        assertEquals(AnimationCue.LISTENING, AnimationDirector.cueForState(ConversationState.RECOGNIZING))
        assertEquals(AnimationCue.THINKING, AnimationDirector.cueForState(ConversationState.THINKING))
        assertEquals(AnimationCue.SPEAKING, AnimationDirector.cueForState(ConversationState.SPEAKING))
        assertEquals(
            AnimationCue.VOICE_RECOVERING,
            AnimationDirector.cueForState(ConversationState.RECOVERING_VOICE_SERVICE),
        )
        assertEquals(
            AnimationCue.MICROPHONE_OFF,
            AnimationDirector.cueForState(ConversationState.PAUSED_OR_PERMISSION_BLOCKED),
        )
        assertEquals(AnimationCue.ERROR, AnimationDirector.cueForState(ConversationState.ERROR))
    }

    @Test
    fun listeningStatesAreDistinguishableFromSpeakingAndRecovering() {
        val listening = AnimationDirector.cueForState(ConversationState.LISTENING_FOR_CONVERSATION)
        assertNotEquals(listening, AnimationDirector.cueForState(ConversationState.SPEAKING))
        assertNotEquals(listening, AnimationDirector.cueForState(ConversationState.RECOVERING_VOICE_SERVICE))
        assertNotEquals(listening, AnimationDirector.cueForState(ConversationState.IDLE))
    }

    @Test
    fun speakingNeverUsesAMouthAnimation() {
        val cue = AnimationDirector.cueForState(ConversationState.SPEAKING)
        val spec = AnimationDirector.specFor(cue)
        assertEquals(13, spec.id)
        assertEquals("Speaking-status halo", spec.name)
        assertFalse("NEX has no speaking mouth", spec.animatesMouth)
        assertTrue(spec.screenOnly)
    }

    @Test
    fun weatherKindsMapToTheirOwnAnimation() {
        assertEquals(AnimationCue.WEATHER_SUN, AnimationDirector.cueForWeather(WeatherKind.SUN))
        assertEquals(AnimationCue.WEATHER_RAIN, AnimationDirector.cueForWeather(WeatherKind.RAIN))
        assertEquals(AnimationCue.WEATHER_CLOUD, AnimationDirector.cueForWeather(WeatherKind.CLOUD))
        assertEquals(AnimationCue.WEATHER_STORM, AnimationDirector.cueForWeather(WeatherKind.STORM))
        assertEquals(AnimationCue.WEATHER_CLOUD, AnimationDirector.cueForWeather(WeatherKind.UNKNOWN))
        val cues = WeatherKind.entries.map { AnimationDirector.cueForWeather(it).animationId }
        assertEquals(4, cues.toSet().size)
    }

    @Test
    fun groundingOnlyCelebratesARealSource() {
        assertEquals(
            AnimationCue.SOURCE_FOUND,
            AnimationDirector.cueForGrounding(grounded = true, sourceCount = 2),
        )
        assertEquals(
            AnimationCue.INTERNET_SEARCH,
            AnimationDirector.cueForGrounding(grounded = true, sourceCount = 0),
        )
        // An ungrounded answer must never look like a live-source answer.
        assertEquals(AnimationCue.THINKING, AnimationDirector.cueForGrounding(grounded = false, sourceCount = 0))
        assertEquals(AnimationCue.THINKING, AnimationDirector.cueForGrounding(grounded = false, sourceCount = 5))
    }

    @Test
    fun specForAgreesWithTheCatalogueForEveryCue() {
        AnimationCue.entries.forEach { cue ->
            assertEquals(AnimationCatalog.byId(cue.animationId), AnimationDirector.specFor(cue))
        }
    }

    @Test
    fun isLoopingMatchesTheCatalogueFlag() {
        AnimationCue.entries.forEach { cue ->
            assertEquals(AnimationCatalog.byId(cue.animationId).loop, AnimationDirector.isLooping(cue))
        }
    }

    @Test
    fun resolveHonoursReducedMotionForEveryCue() {
        AnimationCue.entries.forEach { cue ->
            val normal = AnimationDirector.resolve(cue, reducedMotion = false)
            val reduced = AnimationDirector.resolve(cue, reducedMotion = true)
            assertTrue(reduced.durationMs > normal.durationMs)
            assertTrue(reduced.amplitude <= normal.amplitude)
            assertTrue(reduced.reducedMotion)
            assertFalse(normal.reducedMotion)
        }
    }

    @Test
    fun allCuesReturnsExactlyTheCatalogueSize() {
        assertEquals(AnimationCatalog.SIZE, AnimationDirector.allCues().size)
        assertEquals(AnimationCue.entries.toList(), AnimationDirector.allCues())
    }

    @Test
    fun defaultCueIsTheCalmIdleAnimation() {
        assertEquals(AnimationCue.CALM_IDLE_BREATHING, AnimationDirector.DEFAULT_CUE)
        assertEquals(1, AnimationDirector.DEFAULT_CUE.animationId)
    }

    @Test
    fun privacyAndPermissionIntentsUseTheirOwnVisualLanguage() {
        assertEquals(
            AnimationCue.PRIVACY_SHIELD,
            AnimationDirector.cueForIntent(UserIntent.PRIVACY_HELP),
        )
        assertEquals(
            AnimationCue.PERMISSION_REQUEST,
            AnimationDirector.cueForIntent(UserIntent.PERMISSION_HELP),
        )
        assertEquals(
            AnimationCue.PRIVACY_SHIELD,
            AnimationDirector.cueForIntent(UserIntent.MEMORY_DELETE),
        )
        assertEquals(
            AnimationCue.VISION_ANALYZING,
            AnimationDirector.cueForIntent(UserIntent.VISION_REQUEST),
        )
    }
}
