package com.nexora.robot.animation

/**
 * Screen-only animation vocabulary for NEX.
 *
 * Hard product rules encoded in these types (and enforced by [AnimationCatalog]'s
 * `init` block, so a violation fails at class-load time and in unit tests):
 *
 *  * every animation is **screen only** — no speaker, vibrator or notification output;
 *  * **no animation moves a mouth** — NEX has no speaking-mouth animation;
 *  * **no strobe** — at most one soft flash per animation, never a rapid flicker;
 *  * every animation has a **reduced-motion** variant that is slower and smaller.
 *
 * This file is pure Kotlin (no Android imports) so it can be unit tested on the JVM
 * without a Context, Robolectric or an emulator.
 */

/** What the animation is communicating. */
enum class AnimationCategory {
    IDLE,
    ATTENTION,
    COGNITION,
    SPEECH_STATUS,
    EMOTION,
    STUDY,
    TIME_OF_DAY,
    WEATHER,
    INFORMATION,
    PERMISSION_PRIVACY,
    SYSTEM_STATUS,
    LANGUAGE,
    MEDIA,
    FAREWELL,
}

/**
 * The drawable motion primitive behind an animation.
 *
 * Several animations share a primitive and stay visually distinct through palette,
 * element count, speed and amplitude. `NexAnimationView` switches on this enum with an
 * exhaustive `when` expression, so adding a profile without drawing it is a compile error.
 */
enum class MotionProfile {
    BREATHING,
    BLINK,
    BROW,
    CRESCENT,
    RADIAL_GLOW,
    PULSE,
    SPARKLE,
    ORBIT,
    DOTS,
    SHIMMER,
    HALO,
    TILT,
    RIPPLE,
    BURST,
    GRID,
    PARTICLES,
    SYMBOLS,
    TRAIL,
    WAVE,
    SCAN,
    FRAME,
    BEACON,
    BOUNCE,
    WASH,
    BARS,
    COUNTDOWN,
    STAR_TRAIL,
}

/** Warm, non-clashing colour families used by the renderer. */
enum class AnimationPalette {
    NEUTRAL,
    WARM,
    JOY,
    FOCUS,
    NATURE,
    NIGHT,
    WATER,
    ALERT,
    SUCCESS,
    PRIVACY,
    OFFLINE,
    LANGUAGE,
    MEDIA,
    CELEBRATION,
}

/**
 * One of the exactly 56 curated NEX animations.
 *
 * @property id stable 1..56 identifier
 * @property name human readable catalogue name
 * @property slug stable machine identifier
 * @property durationMs nominal length; looping animations treat it as one cycle
 * @property variant renderer sub-style, so animations sharing a [profile] stay distinct
 * @param reducedMotionDurationMs slower cycle used when the user asks for reduced motion
 * @param reducedMotionAmplitude smaller movement used when the user asks for reduced motion
 */
data class AnimationSpec(
    val id: Int,
    val name: String,
    val slug: String,
    val category: AnimationCategory,
    val profile: MotionProfile,
    val palette: AnimationPalette,
    val durationMs: Long,
    val loop: Boolean,
    val elementCount: Int,
    val speed: Float,
    val amplitude: Float,
    val flashCount: Int,
    /** Which renderer path a shared [MotionProfile] should take. 1 is the default look. */
    val variant: Int,
    val reducedMotionDurationMs: Long,
    val reducedMotionAmplitude: Float,
    val screenOnly: Boolean = true,
    val animatesMouth: Boolean = false,
) {
    init {
        require(id in MIN_ID..MAX_ID) { "animation id must be in 1..56, was $id" }
        require(name.isNotBlank()) { "animation name must not be blank" }
        require(durationMs > 0) { "duration must be positive" }
        require(elementCount in 0..MAX_ELEMENTS) { "elementCount out of range" }
        require(speed in 0.1f..3f) { "speed out of range" }
        require(amplitude in 0f..1f) { "amplitude out of range" }
        require(flashCount in 0..1) { "NEX never strobes: at most one soft flash" }
        require(variant in 0..9) { "variant out of range" }
        require(screenOnly) { "NEX animations are screen only" }
        require(!animatesMouth) { "NEX has no speaking-mouth animation" }
        require(reducedMotionDurationMs >= durationMs) { "reduced motion must be slower or equal" }
        require(reducedMotionAmplitude <= amplitude) { "reduced motion must be calmer or equal" }
        require(reducedMotionAmplitude >= 0f) { "reduced motion amplitude must be positive" }
    }

    companion object {
        const val MIN_ID = 1
        const val MAX_ID = 56
        const val MAX_ELEMENTS = 64
    }
}

/** An animation resolved for the user's motion preference. */
data class ResolvedAnimation(
    val spec: AnimationSpec,
    val durationMs: Long,
    val amplitude: Float,
    val loop: Boolean,
    val reducedMotion: Boolean,
) {
    val id: Int get() = spec.id
    val slug: String get() = spec.slug
    val profile: MotionProfile get() = spec.profile
    val palette: AnimationPalette get() = spec.palette
}
