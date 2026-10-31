package com.nexora.robot.animation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The catalogue is the product contract for NEX's face.
 *
 * It is pure Kotlin — no `Context`, no Robolectric, no Android imports — so these tests run on
 * a plain JVM. They pin down: exactly 56 animations, ids 1..56, the agreed names, screen-only
 * rendering, no speaking-mouth animation, no strobing, and a reduced-motion variant of every
 * single entry.
 */
class AnimationCatalogTest {

    /** The 56 agreed names, in catalogue order. Rewording one is a contract change. */
    private val expectedNames = listOf(
        "Calm idle breathing",
        "Soft blink",
        "Double blink",
        "Curious eyebrow lift",
        "Happy eye crescent",
        "Gentle greeting glow",
        "Listening pulse",
        "Wake-word sparkle",
        "Thinking orbit",
        "Deep-thinking orbit",
        "Tiny processing dots",
        "Answer-ready shimmer",
        "Speaking-status halo",
        "Quiet/listening settle",
        "Confused tilt",
        "Asking-for-clarification ripple",
        "Proud achievement glow",
        "Encouragement sparkle",
        "Celebration burst",
        "Study-focus grid",
        "Book-learning particles",
        "Math-symbol float",
        "Science-atom orbit",
        "Coding-pixel stream",
        "Creativity paint-light trail",
        "Memory-save sparkle",
        "Memory-recall constellation",
        "Friendly wave-light",
        "Playful bounce",
        "Sleepy slow blink",
        "Rest mode moon glow",
        "Good-morning sunrise glow",
        "Good-night star fade",
        "Weather-sun icon glow",
        "Weather-rain ripple",
        "Weather-cloud drift",
        "Weather-lightning flash",
        "Internet-search scan",
        "Source-found beacon",
        "Camera-looking frame",
        "Vision-analyzing scanline",
        "Safe-warning amber pulse",
        "Privacy-shield glow",
        "Permission-request ring",
        "Microphone-off muted ripple",
        "Voice-recovering calm pulse",
        "Network-waiting wave",
        "Offline-mode ember",
        "Error-soft-red pulse",
        "Success-check sparkle",
        "Language-switch swirl",
        "Hindi/Hinglish hello shimmer",
        "Music-note visualizer",
        "Countdown focus pulse",
        "Timer-complete celebration",
        "Farewell star trail",
    )

    @Test
    fun catalogueHasExactlyFiftySixEntries() {
        assertEquals(56, AnimationCatalog.SIZE)
        assertEquals(56, AnimationCatalog.all.size)
        assertEquals(56, expectedNames.size)
    }

    @Test
    fun idsRunFromOneToFiftySixWithNoGapsAndNoDuplicates() {
        val ids = AnimationCatalog.all.map { it.id }
        assertEquals((1..56).toList(), ids)
        assertEquals("ids must be distinct", ids.size, ids.toSet().size)
        assertEquals(ids, AnimationCatalog.ids())
    }

    @Test
    fun namesMatchTheAgreedCatalogueExactly() {
        assertEquals(expectedNames, AnimationCatalog.names())
    }

    @Test
    fun namesAndSlugsAreUnique() {
        val names = AnimationCatalog.names()
        assertEquals(names.size, names.toSet().size)
        val slugs = AnimationCatalog.slugs()
        assertEquals(slugs.size, slugs.toSet().size)
    }

    @Test
    fun everyAnimationIsScreenOnlyAndNeverAnimatesAMouth() {
        AnimationCatalog.all.forEach { spec ->
            assertTrue("${spec.id} must be screen only", spec.screenOnly)
            assertFalse("${spec.id} must not animate a mouth", spec.animatesMouth)
        }
    }

    @Test
    fun noAnimationStrobes() {
        AnimationCatalog.all.forEach { spec ->
            assertTrue("${spec.id} may flash at most once", spec.flashCount in 0..1)
            assertTrue("${spec.id} speed must stay calm", spec.speed <= 3f)
            assertTrue("${spec.id} element count must stay bounded", spec.elementCount <= AnimationSpec.MAX_ELEMENTS)
        }
        // Exactly one curated animation flashes at all: the weather lightning cue.
        val flashing = AnimationCatalog.all.filter { it.flashCount == 1 }.map { it.id }
        assertEquals(listOf(37), flashing)
    }

    @Test
    fun everyEntryHasASlowerCalmerReducedMotionVariant() {
        AnimationCatalog.all.forEach { spec ->
            assertTrue(
                "${spec.id} reduced motion must be slower",
                spec.reducedMotionDurationMs > spec.durationMs,
            )
            assertTrue(
                "${spec.id} reduced motion must be calmer",
                spec.reducedMotionAmplitude <= spec.amplitude,
            )
            assertTrue("${spec.id} reduced motion amplitude must not go negative", spec.reducedMotionAmplitude >= 0f)
        }
    }

    @Test
    fun variantsKeepSharedMotionProfilesVisuallyDistinct() {
        AnimationCatalog.all.forEach { spec ->
            assertTrue("${spec.id} variant must be set", spec.variant in 1..9)
        }
        // Two entries that share a motion profile must not look identical. Variant picks the
        // renderer path; palette, amplitude, duration and looping pick everything else.
        AnimationCatalog.all
            .groupBy { it.profile }
            .forEach { (profile, specs) ->
                if (specs.size > 1) {
                    val shapes = specs.map { spec ->
                        listOf(
                            spec.variant,
                            spec.elementCount,
                            spec.speed,
                            spec.amplitude,
                            spec.durationMs,
                            spec.loop,
                            spec.palette,
                            spec.flashCount,
                        )
                    }
                    assertEquals(
                        "profile $profile has visually identical entries: ${specs.map { it.id }}",
                        specs.size,
                        shapes.distinct().size,
                    )
                }
            }
    }

    @Test
    fun everyCategoryAndEveryMotionProfileIsUsed() {
        AnimationCategory.entries.forEach { category ->
            assertTrue(
                "category $category is never used",
                AnimationCatalog.forCategory(category).isNotEmpty(),
            )
        }
        MotionProfile.entries.forEach { profile ->
            assertTrue(
                "motion profile $profile is never used",
                AnimationCatalog.forProfile(profile).isNotEmpty(),
            )
        }
    }

    @Test
    fun byIdReturnsTheCatalogueEntryAndRejectsOutOfRangeIds() {
        AnimationCatalog.all.forEach { spec ->
            assertSame(spec, AnimationCatalog.byId(spec.id))
        }
        assertRejectsId(0)
        assertRejectsId(-1)
        assertRejectsId(57)
        assertRejectsId(9_999)
    }

    private fun assertRejectsId(id: Int) {
        try {
            AnimationCatalog.byId(id)
            fail("id $id must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun bySlugFindsEveryEntry() {
        AnimationCatalog.all.forEach { spec ->
            assertEquals(spec, AnimationCatalog.bySlug(spec.slug))
        }
        assertEquals(null, AnimationCatalog.bySlug("not-a-real-animation"))
    }

    @Test
    fun resolveKeepsNormalTimingWhenReducedMotionIsOff() {
        val spec = AnimationCatalog.byId(1)
        val resolved = AnimationCatalog.resolve(spec, reducedMotion = false)
        assertEquals(spec.durationMs, resolved.durationMs)
        assertEquals(spec.amplitude, resolved.amplitude, 0.0001f)
        assertEquals(spec.loop, resolved.loop)
        assertFalse(resolved.reducedMotion)
        assertEquals(spec.id, resolved.id)
        assertEquals(spec.slug, resolved.slug)
        assertSame(spec.profile, resolved.profile)
        assertSame(spec.palette, resolved.palette)
    }

    @Test
    fun resolveSlowsAndCalmsEveryAnimationUnderReducedMotion() {
        AnimationCatalog.all.forEach { spec ->
            val resolved = AnimationCatalog.resolve(spec.id, reducedMotion = true)
            assertTrue(resolved.reducedMotion)
            assertEquals(spec.reducedMotionDurationMs, resolved.durationMs)
            assertEquals(spec.reducedMotionAmplitude, resolved.amplitude, 0.0001f)
            assertTrue(
                "${spec.id} reduced motion must be slower",
                resolved.durationMs > spec.durationMs,
            )
        }
    }

    @Test
    fun reducedMotionNeverLoopsAFlashingAnimation() {
        AnimationCatalog.all
            .filter { it.flashCount > 0 }
            .forEach { spec ->
                assertFalse(
                    "${spec.id} must not repeat its flash under reduced motion",
                    AnimationCatalog.resolve(spec, reducedMotion = true).loop,
                )
            }
    }

    @Test
    fun everyCuePointsAtOneUniqueAnimationAndEveryAnimationHasACue() {
        val cueIds = AnimationCue.entries.map { it.animationId }
        assertEquals(56, cueIds.size)
        assertEquals((1..56).toList(), cueIds.sorted())
        assertEquals("each cue must point at a distinct animation", cueIds.size, cueIds.toSet().size)
        AnimationCue.entries.forEach { cue ->
            assertEquals(cue.animationId, AnimationCatalog.byId(cue.animationId).id)
        }
    }
}
