package com.nexora.robot.voice

import com.nexora.robot.animation.AnimationCue

/**
 * One-shot instructions from the voice core to the activity.
 *
 * The voice core owns the conversation; the activity owns views, permission launchers and the
 * camera preview. Signals keep that boundary clean instead of handing Android widgets into the
 * state machine.
 */
sealed interface UiSignal {

    /** Ask the activity to run the camera + vision flow for this explicit user request. */
    data class VisionRequested(val prompt: String) : UiSignal

    /** Ask the activity to request a runtime permission the user just justified. */
    data class PermissionNeeded(val permission: String) : UiSignal

    /** Reduced-motion preference changed, so animations must be re-resolved. */
    data class ReducedMotionChanged(val enabled: Boolean) : UiSignal

    /** The local focus timer started. */
    data class FocusTimerStarted(val minutes: Int) : UiSignal

    /** The local focus timer finished. */
    data object FocusTimerComplete : UiSignal

    /** Play a one-off animation that is not implied by the conversation state. */
    data class TransientCue(val cue: AnimationCue, val durationMs: Long) : UiSignal

    /** Something the user should see as a banner, in the user's own words. */
    data class Notice(val message: String) : UiSignal
}
