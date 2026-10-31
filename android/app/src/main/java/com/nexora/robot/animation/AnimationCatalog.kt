package com.nexora.robot.animation

import com.nexora.robot.animation.AnimationCategory.ATTENTION
import com.nexora.robot.animation.AnimationCategory.COGNITION
import com.nexora.robot.animation.AnimationCategory.EMOTION
import com.nexora.robot.animation.AnimationCategory.FAREWELL
import com.nexora.robot.animation.AnimationCategory.IDLE
import com.nexora.robot.animation.AnimationCategory.INFORMATION
import com.nexora.robot.animation.AnimationCategory.LANGUAGE
import com.nexora.robot.animation.AnimationCategory.MEDIA
import com.nexora.robot.animation.AnimationCategory.PERMISSION_PRIVACY
import com.nexora.robot.animation.AnimationCategory.SPEECH_STATUS
import com.nexora.robot.animation.AnimationCategory.STUDY
import com.nexora.robot.animation.AnimationCategory.SYSTEM_STATUS
import com.nexora.robot.animation.AnimationCategory.TIME_OF_DAY
import com.nexora.robot.animation.AnimationCategory.WEATHER
import com.nexora.robot.animation.AnimationPalette.ALERT
import com.nexora.robot.animation.AnimationPalette.CELEBRATION
import com.nexora.robot.animation.AnimationPalette.FOCUS
import com.nexora.robot.animation.AnimationPalette.JOY
import com.nexora.robot.animation.AnimationPalette.LANGUAGE as LANGUAGE_PALETTE
import com.nexora.robot.animation.AnimationPalette.MEDIA as MEDIA_PALETTE
import com.nexora.robot.animation.AnimationPalette.NATURE
import com.nexora.robot.animation.AnimationPalette.NEUTRAL
import com.nexora.robot.animation.AnimationPalette.NIGHT
import com.nexora.robot.animation.AnimationPalette.OFFLINE
import com.nexora.robot.animation.AnimationPalette.PRIVACY
import com.nexora.robot.animation.AnimationPalette.SUCCESS
import com.nexora.robot.animation.AnimationPalette.WARM
import com.nexora.robot.animation.AnimationPalette.WATER
import com.nexora.robot.animation.MotionProfile.BARS
import com.nexora.robot.animation.MotionProfile.BEACON
import com.nexora.robot.animation.MotionProfile.BLINK
import com.nexora.robot.animation.MotionProfile.BOUNCE
import com.nexora.robot.animation.MotionProfile.BROW
import com.nexora.robot.animation.MotionProfile.BURST
import com.nexora.robot.animation.MotionProfile.COUNTDOWN
import com.nexora.robot.animation.MotionProfile.CRESCENT
import com.nexora.robot.animation.MotionProfile.DOTS
import com.nexora.robot.animation.MotionProfile.FRAME
import com.nexora.robot.animation.MotionProfile.GRID
import com.nexora.robot.animation.MotionProfile.HALO
import com.nexora.robot.animation.MotionProfile.ORBIT
import com.nexora.robot.animation.MotionProfile.PARTICLES
import com.nexora.robot.animation.MotionProfile.PULSE
import com.nexora.robot.animation.MotionProfile.RADIAL_GLOW
import com.nexora.robot.animation.MotionProfile.RIPPLE
import com.nexora.robot.animation.MotionProfile.SCAN
import com.nexora.robot.animation.MotionProfile.SHIMMER
import com.nexora.robot.animation.MotionProfile.SPARKLE
import com.nexora.robot.animation.MotionProfile.STAR_TRAIL
import com.nexora.robot.animation.MotionProfile.SYMBOLS
import com.nexora.robot.animation.MotionProfile.TILT
import com.nexora.robot.animation.MotionProfile.TRAIL
import com.nexora.robot.animation.MotionProfile.WASH
import com.nexora.robot.animation.MotionProfile.WAVE

/**
 * The complete, closed catalogue of NEX animations: **exactly 56**, ids 1..56.
 *
 * Deliberately a plain Kotlin `object` — not an Android `View`, not a resource array —
 * so it can be verified by ordinary JVM unit tests (`AnimationCatalogTest`) with no
 * Context, no Robolectric and no device.
 *
 * The catalogue is closed: [all] is the only source of truth, ids are contiguous from 1,
 * and the `init` block rejects any violation (duplicates, gaps, strobing, mouth motion,
 * non-screen output, or a missing reduced-motion variant) the moment the class loads.
 */
object AnimationCatalog {

    /** Number of curated animations. Changing this without changing the list is a load-time failure. */
    const val SIZE = 56

    /** A reduced-motion cycle is slower than a normal cycle. */
    private const val REDUCED_MOTION_TIME_SCALE = 1.6f

    /** Reduced motion also moves less. */
    private const val REDUCED_MOTION_AMPLITUDE_SCALE = 0.35f

    private fun spec(
        id: Int,
        name: String,
        slug: String,
        category: AnimationCategory,
        profile: MotionProfile,
        palette: AnimationPalette,
        durationMs: Long,
        loop: Boolean,
        elementCount: Int,
        speed: Float,
        amplitude: Float,
        flashCount: Int = 0,
        variant: Int = 1,
    ): AnimationSpec = AnimationSpec(
        id = id,
        name = name,
        slug = slug,
        category = category,
        profile = profile,
        palette = palette,
        durationMs = durationMs,
        loop = loop,
        elementCount = elementCount,
        speed = speed,
        amplitude = amplitude,
        flashCount = flashCount,
        variant = variant,
        reducedMotionDurationMs = (durationMs * REDUCED_MOTION_TIME_SCALE).toLong(),
        reducedMotionAmplitude = amplitude * REDUCED_MOTION_AMPLITUDE_SCALE,
    )

    /**
     * The 56 curated animations, in catalogue order.
     *
     * Names are part of the product contract and must not be reworded casually: the
     * unit test `AnimationCatalogTest` asserts this exact list.
     */
    val all: List<AnimationSpec> = listOf(
        // 1-6 · idle life and first impressions
        spec(1, "Calm idle breathing", "calm-idle-breathing", IDLE, MotionProfile.BREATHING, NEUTRAL, 4200, true, 1, 0.60f, 0.10f),
        spec(2, "Soft blink", "soft-blink", IDLE, BLINK, NEUTRAL, 620, false, 2, 1.00f, 0.18f),
        spec(3, "Double blink", "double-blink", IDLE, BLINK, NEUTRAL, 900, false, 2, 1.20f, 0.20f, variant = 2),
        spec(4, "Curious eyebrow lift", "curious-eyebrow-lift", ATTENTION, BROW, WARM, 780, false, 2, 1.00f, 0.22f),
        spec(5, "Happy eye crescent", "happy-eye-crescent", EMOTION, CRESCENT, JOY, 900, false, 2, 1.00f, 0.28f),
        spec(6, "Gentle greeting glow", "gentle-greeting-glow", ATTENTION, RADIAL_GLOW, WARM, 1600, false, 1, 0.90f, 0.35f),

        // 7-14 · listening, thinking and speaking status
        spec(7, "Listening pulse", "listening-pulse", ATTENTION, PULSE, FOCUS, 1400, true, 3, 1.00f, 0.30f),
        spec(8, "Wake-word sparkle", "wake-word-sparkle", ATTENTION, SPARKLE, JOY, 1100, false, 12, 1.30f, 0.55f),
        spec(9, "Thinking orbit", "thinking-orbit", COGNITION, ORBIT, FOCUS, 2000, true, 3, 1.00f, 0.40f),
        spec(10, "Deep-thinking orbit", "deep-thinking-orbit", COGNITION, ORBIT, FOCUS, 3200, true, 5, 0.70f, 0.45f, variant = 2),
        spec(11, "Tiny processing dots", "tiny-processing-dots", COGNITION, DOTS, NEUTRAL, 1200, true, 3, 1.10f, 0.22f),
        spec(12, "Answer-ready shimmer", "answer-ready-shimmer", SPEECH_STATUS, SHIMMER, SUCCESS, 900, false, 1, 1.20f, 0.35f),
        spec(13, "Speaking-status halo", "speaking-status-halo", SPEECH_STATUS, HALO, WARM, 1500, true, 1, 1.00f, 0.28f),
        spec(14, "Quiet/listening settle", "quiet-listening-settle", ATTENTION, RADIAL_GLOW, NEUTRAL, 2600, true, 1, 0.50f, 0.18f),

        // 15-19 · feelings
        spec(15, "Confused tilt", "confused-tilt", EMOTION, TILT, WARM, 1100, false, 1, 0.90f, 0.16f),
        spec(16, "Asking-for-clarification ripple", "asking-for-clarification-ripple", EMOTION, RIPPLE, WARM, 1800, true, 3, 0.90f, 0.30f),
        spec(17, "Proud achievement glow", "proud-achievement-glow", EMOTION, RADIAL_GLOW, SUCCESS, 1800, false, 1, 0.90f, 0.42f),
        spec(18, "Encouragement sparkle", "encouragement-sparkle", EMOTION, SPARKLE, JOY, 1400, false, 10, 1.10f, 0.45f),
        spec(19, "Celebration burst", "celebration-burst", EMOTION, BURST, CELEBRATION, 1800, false, 24, 1.40f, 0.70f),

        // 20-27 · study, learning and memory
        spec(20, "Study-focus grid", "study-focus-grid", STUDY, GRID, FOCUS, 2600, true, 16, 0.80f, 0.30f),
        spec(21, "Book-learning particles", "book-learning-particles", STUDY, PARTICLES, NATURE, 3000, true, 14, 0.70f, 0.35f),
        spec(22, "Math-symbol float", "math-symbol-float", STUDY, SYMBOLS, FOCUS, 2800, true, 6, 0.80f, 0.38f),
        spec(23, "Science-atom orbit", "science-atom-orbit", STUDY, ORBIT, NATURE, 2600, true, 4, 1.20f, 0.42f, variant = 3),
        spec(24, "Coding-pixel stream", "coding-pixel-stream", STUDY, GRID, SUCCESS, 2200, true, 24, 1.50f, 0.32f, variant = 2),
        spec(25, "Creativity paint-light trail", "creativity-paint-light-trail", STUDY, TRAIL, JOY, 2600, true, 8, 1.00f, 0.55f),
        spec(26, "Memory-save sparkle", "memory-save-sparkle", COGNITION, SPARKLE, FOCUS, 1200, false, 8, 1.00f, 0.40f),
        spec(27, "Memory-recall constellation", "memory-recall-constellation", COGNITION, PARTICLES, NIGHT, 3000, true, 12, 0.60f, 0.40f, variant = 2),

        // 28-33 · warmth and time of day
        spec(28, "Friendly wave-light", "friendly-wave-light", EMOTION, TRAIL, WARM, 1800, false, 6, 1.10f, 0.50f, variant = 2),
        spec(29, "Playful bounce", "playful-bounce", EMOTION, BOUNCE, JOY, 1400, false, 1, 1.30f, 0.45f),
        spec(30, "Sleepy slow blink", "sleepy-slow-blink", TIME_OF_DAY, BLINK, NIGHT, 1800, false, 2, 0.50f, 0.22f, variant = 3),
        spec(31, "Rest mode moon glow", "rest-mode-moon-glow", TIME_OF_DAY, RADIAL_GLOW, NIGHT, 4000, true, 1, 0.40f, 0.25f, variant = 2),
        spec(32, "Good-morning sunrise glow", "good-morning-sunrise-glow", TIME_OF_DAY, WASH, WARM, 2600, false, 1, 0.70f, 0.55f),
        spec(33, "Good-night star fade", "good-night-star-fade", TIME_OF_DAY, PARTICLES, NIGHT, 3200, false, 16, 0.50f, 0.45f, variant = 3),

        // 34-37 · weather
        spec(34, "Weather-sun icon glow", "weather-sun-icon-glow", WEATHER, RADIAL_GLOW, WARM, 2400, true, 1, 0.80f, 0.40f, variant = 3),
        spec(35, "Weather-rain ripple", "weather-rain-ripple", WEATHER, RIPPLE, WATER, 2200, true, 4, 0.90f, 0.35f, variant = 2),
        spec(36, "Weather-cloud drift", "weather-cloud-drift", WEATHER, WAVE, WATER, 3600, true, 3, 0.50f, 0.25f),
        // One soft flash only. NEX never strobes: flashCount is capped at 1 by AnimationSpec.
        spec(37, "Weather-lightning flash", "weather-lightning-flash", WEATHER, WASH, ALERT, 900, false, 1, 1.60f, 0.60f, flashCount = 1, variant = 2),

        // 38-41 · information, search and vision
        spec(38, "Internet-search scan", "internet-search-scan", INFORMATION, SCAN, FOCUS, 2000, true, 1, 1.10f, 0.45f),
        spec(39, "Source-found beacon", "source-found-beacon", INFORMATION, BEACON, SUCCESS, 1600, false, 1, 1.00f, 0.55f),
        spec(40, "Camera-looking frame", "camera-looking-frame", INFORMATION, FRAME, NEUTRAL, 2400, true, 4, 0.80f, 0.30f),
        spec(41, "Vision-analyzing scanline", "vision-analyzing-scanline", INFORMATION, SCAN, FOCUS, 1800, true, 1, 1.30f, 0.40f, variant = 2),

        // 42-50 · safety, privacy, permissions and system status
        spec(42, "Safe-warning amber pulse", "safe-warning-amber-pulse", SYSTEM_STATUS, PULSE, ALERT, 1500, true, 3, 0.90f, 0.38f),
        spec(43, "Privacy-shield glow", "privacy-shield-glow", PERMISSION_PRIVACY, RADIAL_GLOW, PRIVACY, 2600, true, 1, 0.60f, 0.32f, variant = 4),
        spec(44, "Permission-request ring", "permission-request-ring", PERMISSION_PRIVACY, HALO, PRIVACY, 1800, true, 1, 0.90f, 0.36f, variant = 2),
        spec(45, "Microphone-off muted ripple", "microphone-off-muted-ripple", PERMISSION_PRIVACY, RIPPLE, NEUTRAL, 2000, true, 2, 0.70f, 0.28f, variant = 3),
        spec(46, "Voice-recovering calm pulse", "voice-recovering-calm-pulse", SYSTEM_STATUS, PULSE, WARM, 2400, true, 2, 0.60f, 0.22f),
        spec(47, "Network-waiting wave", "network-waiting-wave", SYSTEM_STATUS, WAVE, WATER, 2200, true, 3, 0.80f, 0.30f, variant = 2),
        spec(48, "Offline-mode ember", "offline-mode-ember", SYSTEM_STATUS, RADIAL_GLOW, OFFLINE, 3400, true, 1, 0.40f, 0.20f, variant = 5),
        spec(49, "Error-soft-red pulse", "error-soft-red-pulse", SYSTEM_STATUS, PULSE, ALERT, 1800, true, 2, 0.80f, 0.30f),
        spec(50, "Success-check sparkle", "success-check-sparkle", SYSTEM_STATUS, SPARKLE, SUCCESS, 1200, false, 9, 1.20f, 0.45f, variant = 2),

        // 51-56 · language, media, timers and farewell
        spec(51, "Language-switch swirl", "language-switch-swirl", LANGUAGE, WAVE, LANGUAGE_PALETTE, 1600, false, 5, 1.30f, 0.50f, variant = 3),
        spec(52, "Hindi/Hinglish hello shimmer", "hindi-hinglish-hello-shimmer", LANGUAGE, SHIMMER, LANGUAGE_PALETTE, 1500, false, 1, 1.00f, 0.42f, variant = 2),
        spec(53, "Music-note visualizer", "music-note-visualizer", MEDIA, BARS, MEDIA_PALETTE, 2000, true, 12, 1.20f, 0.55f),
        spec(54, "Countdown focus pulse", "countdown-focus-pulse", STUDY, COUNTDOWN, FOCUS, 1000, true, 1, 1.00f, 0.25f),
        spec(55, "Timer-complete celebration", "timer-complete-celebration", STUDY, BURST, CELEBRATION, 1800, false, 20, 1.40f, 0.65f, variant = 2),
        spec(56, "Farewell star trail", "farewell-star-trail", FAREWELL, STAR_TRAIL, NIGHT, 2800, false, 14, 0.80f, 0.50f),
    )

    init {
        require(all.size == SIZE) { "NEX must ship exactly $SIZE animations, found ${all.size}" }

        val ids = all.map { it.id }
        require(ids.distinct().size == SIZE) { "animation ids must be unique" }
        require(ids == (AnimationSpec.MIN_ID..AnimationSpec.MAX_ID).toList()) {
            "animation ids must be contiguous and ordered 1..$SIZE"
        }
        require(all.map { it.name }.distinct().size == SIZE) { "animation names must be unique" }
        require(all.map { it.slug }.distinct().size == SIZE) { "animation slugs must be unique" }

        require(all.all { it.screenOnly }) { "every NEX animation must be screen only" }
        require(all.none { it.animatesMouth }) { "NEX must not animate a speaking mouth" }
        require(all.none { it.flashCount > 1 }) { "NEX must never strobe" }
        require(all.all { it.reducedMotionDurationMs > it.durationMs }) {
            "every animation needs a slower reduced-motion variant"
        }
        require(all.all { it.reducedMotionAmplitude < it.amplitude }) {
            "every animation needs a calmer reduced-motion variant"
        }

        require(MotionProfile.entries.all { profile -> all.any { it.profile == profile } }) {
            "every MotionProfile must be drawn by the catalogue (or removed from the enum)"
        }
        require(AnimationCategory.entries.all { category -> all.any { it.category == category } }) {
            "every AnimationCategory must be used by the catalogue"
        }
    }

    private val indexById: Map<Int, AnimationSpec> = all.associateBy { it.id }
    private val indexBySlug: Map<String, AnimationSpec> = all.associateBy { it.slug }

    /** Look up by stable id. Throws for an id outside 1..56 — there are no hidden animations. */
    fun byId(id: Int): AnimationSpec =
        requireNotNull(indexById[id]) { "no NEX animation with id $id" }

    /** Look up by stable slug, or null when unknown (used for deep links and debugging). */
    fun bySlug(slug: String): AnimationSpec? = indexBySlug[slug]

    fun ids(): List<Int> = all.map { it.id }

    fun names(): List<String> = all.map { it.name }

    fun slugs(): List<String> = all.map { it.slug }

    fun forCategory(category: AnimationCategory): List<AnimationSpec> = all.filter { it.category == category }

    fun forProfile(profile: MotionProfile): List<AnimationSpec> = all.filter { it.profile == profile }

    /** Resolve one animation for the user's motion preference. */
    fun resolve(id: Int, reducedMotion: Boolean): ResolvedAnimation = resolve(byId(id), reducedMotion)

    /** Resolve one animation for the user's motion preference. */
    fun resolve(spec: AnimationSpec, reducedMotion: Boolean): ResolvedAnimation =
        if (reducedMotion) {
            ResolvedAnimation(
                spec = spec,
                durationMs = spec.reducedMotionDurationMs,
                amplitude = spec.reducedMotionAmplitude,
                // Reduced motion keeps gentle loops but never repeats a one-shot flash.
                loop = spec.loop && spec.flashCount == 0,
                reducedMotion = true,
            )
        } else {
            ResolvedAnimation(
                spec = spec,
                durationMs = spec.durationMs,
                amplitude = spec.amplitude,
                loop = spec.loop,
                reducedMotion = false,
            )
        }
}
