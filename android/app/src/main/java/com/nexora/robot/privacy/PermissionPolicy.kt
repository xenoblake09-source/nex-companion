package com.nexora.robot.privacy

import com.nexora.robot.language.UserIntent
import com.nexora.robot.voice.TimingPolicy

/** Why the microphone may not open right now. */
enum class MicBlockReason {
    PERMISSION_NOT_GRANTED,
    VOICE_MODE_OFF,
    APP_IN_BACKGROUND,
    SPEECH_IN_PROGRESS,
    TURN_IN_PROGRESS,
    MICROPHONE_ALREADY_OPEN,
    AUDIO_ROUTE_SETTLING,
}

/** Result of a microphone gate check. */
sealed interface MicDecision {
    data object Allowed : MicDecision
    data class Blocked(val reason: MicBlockReason) : MicDecision
}

/** Why the camera may not be used right now. */
enum class CameraBlockReason {
    PERMISSION_NOT_GRANTED,
    NO_EXPLICIT_USER_REQUEST,
    APP_IN_BACKGROUND,
}

sealed interface CameraDecision {
    data object Allowed : CameraDecision
    data class Blocked(val reason: CameraBlockReason) : CameraDecision
}

/**
 * The single authority on what NEX is allowed to touch, and when.
 *
 * Pure Kotlin: the speech controller, the camera controller and the UI all ask this object
 * instead of re-deriving the rules, and `PermissionPolicyTest` pins the behaviour down.
 *
 * NEX never requests: contacts, SMS, call logs, storage/media, accessibility services,
 * notification listener access, background microphone, fine location or background location.
 */
object PermissionPolicy {

    /** Android permissions NEX deliberately never asks for. */
    val NEVER_REQUESTED: List<String> = listOf(
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.SEND_SMS",
        "android.permission.READ_CALL_LOG",
        "android.permission.CALL_PHONE",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.BIND_ACCESSIBILITY_SERVICE",
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
        "android.permission.RECORD_AUDIO_BACKGROUND",
        "android.permission.FOREGROUND_SERVICE_MICROPHONE",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
    )

    /** Everything NEX may request, with the moment it may be requested. */
    val REQUESTABLE: List<String> = listOf(
        "android.permission.RECORD_AUDIO",
        "android.permission.CAMERA",
        "android.permission.ACCESS_COARSE_LOCATION",
    )

    /**
     * The microphone gate. All conditions must hold at the same instant.
     *
     * @param speaking true while TTS or server audio is still playing
     * @param thinking true while a turn is with the server (the answer is still coming)
     * @param micBlockedUntilMs earliest instant the microphone may reopen (audio-route settle)
     */
    fun microphoneDecision(
        microphonePermissionGranted: Boolean,
        appInForeground: Boolean,
        voiceModeEnabled: Boolean,
        speaking: Boolean,
        micBlockedUntilMs: Long,
        nowMs: Long,
        microphoneAlreadyOpen: Boolean = false,
        thinking: Boolean = false,
    ): MicDecision = when {
        !microphonePermissionGranted -> MicDecision.Blocked(MicBlockReason.PERMISSION_NOT_GRANTED)
        !voiceModeEnabled -> MicDecision.Blocked(MicBlockReason.VOICE_MODE_OFF)
        !appInForeground -> MicDecision.Blocked(MicBlockReason.APP_IN_BACKGROUND)
        speaking -> MicDecision.Blocked(MicBlockReason.SPEECH_IN_PROGRESS)
        thinking -> MicDecision.Blocked(MicBlockReason.TURN_IN_PROGRESS)
        microphoneAlreadyOpen -> MicDecision.Blocked(MicBlockReason.MICROPHONE_ALREADY_OPEN)
        nowMs < micBlockedUntilMs -> MicDecision.Blocked(MicBlockReason.AUDIO_ROUTE_SETTLING)
        else -> MicDecision.Allowed
    }

    /** Convenience for the common "may I open the mic this very moment?" question. */
    fun mayOpenMicrophone(
        microphonePermissionGranted: Boolean,
        appInForeground: Boolean,
        voiceModeEnabled: Boolean,
        speaking: Boolean,
        micBlockedUntilMs: Long,
        nowMs: Long,
        thinking: Boolean = false,
    ): Boolean = microphoneDecision(
        microphonePermissionGranted = microphonePermissionGranted,
        appInForeground = appInForeground,
        voiceModeEnabled = voiceModeEnabled,
        speaking = speaking,
        micBlockedUntilMs = micBlockedUntilMs,
        nowMs = nowMs,
        thinking = thinking,
    ) == MicDecision.Allowed

    /**
     * The camera gate. A picture is only ever taken when the user explicitly asked about
     * something in front of them in this turn.
     */
    fun cameraDecision(
        cameraPermissionGranted: Boolean,
        appInForeground: Boolean,
        explicitUserRequest: Boolean,
        intent: UserIntent? = null,
    ): CameraDecision = when {
        !cameraPermissionGranted -> CameraDecision.Blocked(CameraBlockReason.PERMISSION_NOT_GRANTED)
        !appInForeground -> CameraDecision.Blocked(CameraBlockReason.APP_IN_BACKGROUND)
        !explicitUserRequest && intent != UserIntent.VISION_REQUEST ->
            CameraDecision.Blocked(CameraBlockReason.NO_EXPLICIT_USER_REQUEST)
        else -> CameraDecision.Allowed
    }

    /** True only for a direct location or local-weather request. Never background, never fine. */
    fun shouldRequestCoarseLocation(
        intent: UserIntent,
        directLocationRequest: Boolean,
        permissionAlreadyGranted: Boolean,
        appInForeground: Boolean,
    ): Boolean = appInForeground &&
        !permissionAlreadyGranted &&
        directLocationRequest &&
        intent in setOf(UserIntent.LOCATION_QUERY, UserIntent.WEATHER)

    /** Anything that must immediately stop voice and camera. */
    fun mustStopCapture(
        appInForeground: Boolean,
        microphonePermissionGranted: Boolean,
        voiceModeEnabled: Boolean,
    ): Boolean = !appInForeground || !microphonePermissionGranted || !voiceModeEnabled

    /** The settle deadline after speech ends. */
    fun micGateAfterSpeech(speechCompletedAtMs: Long): Long =
        TimingPolicy.gateAfterSpeechCompleted(speechCompletedAtMs)
}
