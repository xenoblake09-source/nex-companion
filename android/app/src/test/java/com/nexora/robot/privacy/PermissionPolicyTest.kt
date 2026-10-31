package com.nexora.robot.privacy

import com.nexora.robot.language.UserIntent
import com.nexora.robot.voice.TimingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single authority on what NEX may touch, and when.
 *
 * These tests pin the priority order of the microphone gate (permission beats voice mode, which
 * beats foreground, which beats speech, and so on), the rule that a picture is only ever taken
 * for an explicit request, and the list of permissions NEX never asks for.
 */
class PermissionPolicyTest {

    private val now = 10_000L

    private fun decision(
        microphonePermissionGranted: Boolean = true,
        appInForeground: Boolean = true,
        voiceModeEnabled: Boolean = true,
        speaking: Boolean = false,
        thinking: Boolean = false,
        microphoneAlreadyOpen: Boolean = false,
        micBlockedUntilMs: Long = 0L,
        nowMs: Long = now,
    ): MicDecision = PermissionPolicy.microphoneDecision(
        microphonePermissionGranted = microphonePermissionGranted,
        appInForeground = appInForeground,
        voiceModeEnabled = voiceModeEnabled,
        speaking = speaking,
        thinking = thinking,
        microphoneAlreadyOpen = microphoneAlreadyOpen,
        micBlockedUntilMs = micBlockedUntilMs,
        nowMs = nowMs,
    )

    @Test
    fun everythingClearMeansTheMicrophoneMayOpen() {
        assertEquals(MicDecision.Allowed, decision())
        assertTrue(PermissionPolicy.mayOpenMicrophone(
            microphonePermissionGranted = true,
            appInForeground = true,
            voiceModeEnabled = true,
            speaking = false,
            micBlockedUntilMs = 0L,
            nowMs = now,
        ))
    }

    @Test
    fun missingPermissionBeatsEveryOtherReason() {
        assertEquals(
            MicDecision.Blocked(MicBlockReason.PERMISSION_NOT_GRANTED),
            decision(
                microphonePermissionGranted = false,
                voiceModeEnabled = false,
                appInForeground = false,
                speaking = true,
                thinking = true,
                microphoneAlreadyOpen = true,
                micBlockedUntilMs = now + 5_000L,
            ),
        )
    }

    @Test
    fun voiceModeOffBeatsBackgroundSpeechAndSettling() {
        assertEquals(
            MicDecision.Blocked(MicBlockReason.VOICE_MODE_OFF),
            decision(
                voiceModeEnabled = false,
                appInForeground = false,
                speaking = true,
                thinking = true,
                microphoneAlreadyOpen = true,
                micBlockedUntilMs = now + 5_000L,
            ),
        )
    }

    @Test
    fun backgroundBeatsSpeechThinkingAndSettling() {
        assertEquals(
            MicDecision.Blocked(MicBlockReason.APP_IN_BACKGROUND),
            decision(
                appInForeground = false,
                speaking = true,
                thinking = true,
                microphoneAlreadyOpen = true,
                micBlockedUntilMs = now + 5_000L,
            ),
        )
    }

    @Test
    fun speakingBeatsThinkingAnOpenMicAndSettling() {
        assertEquals(
            MicDecision.Blocked(MicBlockReason.SPEECH_IN_PROGRESS),
            decision(
                speaking = true,
                thinking = true,
                microphoneAlreadyOpen = true,
                micBlockedUntilMs = now + 5_000L,
            ),
        )
    }

    @Test
    fun aTurnWithTheServerBeatsAnOpenMicAndSettling() {
        assertEquals(
            MicDecision.Blocked(MicBlockReason.TURN_IN_PROGRESS),
            decision(thinking = true, microphoneAlreadyOpen = true, micBlockedUntilMs = now + 5_000L),
        )
    }

    @Test
    fun anAlreadyOpenMicrophoneIsNeverOpenedTwice() {
        assertEquals(
            MicDecision.Blocked(MicBlockReason.MICROPHONE_ALREADY_OPEN),
            decision(microphoneAlreadyOpen = true, micBlockedUntilMs = now + 5_000L),
        )
    }

    @Test
    fun theAudioRouteSettleWindowIsRespectedToTheMillisecond() {
        val gate = PermissionPolicy.micGateAfterSpeech(speechCompletedAtMs = now)
        assertEquals(now + TimingPolicy.POST_SPEECH_MIC_SETTLE_MS, gate)
        assertEquals(TimingPolicy.gateAfterSpeechCompleted(now), gate)
        assertEquals(now + 850L, gate)

        assertEquals(MicDecision.Blocked(MicBlockReason.AUDIO_ROUTE_SETTLING), decision(micBlockedUntilMs = gate, nowMs = gate - 1L))
        assertEquals(MicDecision.Allowed, decision(micBlockedUntilMs = gate, nowMs = gate))
        assertEquals(MicDecision.Allowed, decision(micBlockedUntilMs = gate, nowMs = gate + 1L))
    }

    @Test
    fun mayOpenMicrophoneAgreesWithTheDecisionForEveryCombination() {
        listOf(true, false).forEach { permission ->
            listOf(true, false).forEach { foreground ->
                listOf(true, false).forEach { voiceMode ->
                    listOf(true, false).forEach { speaking ->
                        listOf(true, false).forEach { thinking ->
                            listOf(0L, now + 5_000L).forEach { gate ->
                                val allowed = PermissionPolicy.mayOpenMicrophone(
                                    microphonePermissionGranted = permission,
                                    appInForeground = foreground,
                                    voiceModeEnabled = voiceMode,
                                    speaking = speaking,
                                    micBlockedUntilMs = gate,
                                    nowMs = now,
                                    thinking = thinking,
                                )
                                val decided = PermissionPolicy.microphoneDecision(
                                    microphonePermissionGranted = permission,
                                    appInForeground = foreground,
                                    voiceModeEnabled = voiceMode,
                                    speaking = speaking,
                                    thinking = thinking,
                                    micBlockedUntilMs = gate,
                                    nowMs = now,
                                )
                                assertEquals(
                                    "permission=$permission foreground=$foreground voice=$voiceMode " +
                                        "speaking=$speaking thinking=$thinking gate=$gate",
                                    decided == MicDecision.Allowed,
                                    allowed,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun theCameraNeedsPermissionForegroundAndAnExplicitRequest() {
        assertEquals(
            CameraDecision.Blocked(CameraBlockReason.PERMISSION_NOT_GRANTED),
            PermissionPolicy.cameraDecision(
                cameraPermissionGranted = false,
                appInForeground = true,
                explicitUserRequest = true,
            ),
        )
        assertEquals(
            CameraDecision.Blocked(CameraBlockReason.APP_IN_BACKGROUND),
            PermissionPolicy.cameraDecision(
                cameraPermissionGranted = true,
                appInForeground = false,
                explicitUserRequest = true,
            ),
        )
        assertEquals(
            CameraDecision.Blocked(CameraBlockReason.NO_EXPLICIT_USER_REQUEST),
            PermissionPolicy.cameraDecision(
                cameraPermissionGranted = true,
                appInForeground = true,
                explicitUserRequest = false,
            ),
        )
        assertEquals(
            CameraDecision.Blocked(CameraBlockReason.NO_EXPLICIT_USER_REQUEST),
            PermissionPolicy.cameraDecision(
                cameraPermissionGranted = true,
                appInForeground = true,
                explicitUserRequest = false,
                intent = UserIntent.GENERAL,
            ),
        )
        assertEquals(
            CameraDecision.Allowed,
            PermissionPolicy.cameraDecision(
                cameraPermissionGranted = true,
                appInForeground = true,
                explicitUserRequest = true,
            ),
        )
        // A detected vision intent counts as the explicit request.
        assertEquals(
            CameraDecision.Allowed,
            PermissionPolicy.cameraDecision(
                cameraPermissionGranted = true,
                appInForeground = true,
                explicitUserRequest = false,
                intent = UserIntent.VISION_REQUEST,
            ),
        )
    }

    @Test
    fun coarseLocationIsOnlyForADirectLocalAsk() {
        assertTrue(
            PermissionPolicy.shouldRequestCoarseLocation(
                intent = UserIntent.WEATHER,
                directLocationRequest = true,
                permissionAlreadyGranted = false,
                appInForeground = true,
            ),
        )
        assertTrue(
            PermissionPolicy.shouldRequestCoarseLocation(
                intent = UserIntent.LOCATION_QUERY,
                directLocationRequest = true,
                permissionAlreadyGranted = false,
                appInForeground = true,
            ),
        )
        // Not direct.
        assertFalse(
            PermissionPolicy.shouldRequestCoarseLocation(
                intent = UserIntent.WEATHER,
                directLocationRequest = false,
                permissionAlreadyGranted = false,
                appInForeground = true,
            ),
        )
        // Already granted.
        assertFalse(
            PermissionPolicy.shouldRequestCoarseLocation(
                intent = UserIntent.WEATHER,
                directLocationRequest = true,
                permissionAlreadyGranted = true,
                appInForeground = true,
            ),
        )
        // In the background: never.
        assertFalse(
            PermissionPolicy.shouldRequestCoarseLocation(
                intent = UserIntent.WEATHER,
                directLocationRequest = true,
                permissionAlreadyGranted = false,
                appInForeground = false,
            ),
        )
        // An unrelated intent: never.
        UserIntent.entries
            .filter { it != UserIntent.WEATHER && it != UserIntent.LOCATION_QUERY }
            .forEach { intent ->
                assertFalse(
                    "$intent must never trigger a location request",
                    PermissionPolicy.shouldRequestCoarseLocation(
                        intent = intent,
                        directLocationRequest = true,
                        permissionAlreadyGranted = false,
                        appInForeground = true,
                    ),
                )
            }
    }

    @Test
    fun captureMustStopInBackgroundWithoutPermissionOrWithVoiceOff() {
        assertTrue(PermissionPolicy.mustStopCapture(false, true, true))
        assertTrue(PermissionPolicy.mustStopCapture(true, false, true))
        assertTrue(PermissionPolicy.mustStopCapture(true, true, false))
        assertTrue(PermissionPolicy.mustStopCapture(false, false, false))
        assertFalse(PermissionPolicy.mustStopCapture(true, true, true))
    }

    @Test
    fun theRequestableListIsExactlyTheThreeRuntimePermissions() {
        assertEquals(
            listOf(
                "android.permission.RECORD_AUDIO",
                "android.permission.CAMERA",
                "android.permission.ACCESS_COARSE_LOCATION",
            ),
            PermissionPolicy.REQUESTABLE,
        )
    }

    @Test
    fun nexNeverRequestsContactsSmsCallLogsStorageAccessibilityNotificationsOrBackgroundSensors() {
        val never = PermissionPolicy.NEVER_REQUESTED
        listOf(
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
        ).forEach { permission ->
            assertTrue("$permission must be refused", never.contains(permission))
        }
    }

    @Test
    fun theRequestableAndNeverRequestedListsDoNotOverlap() {
        PermissionPolicy.REQUESTABLE.forEach { permission ->
            assertFalse(permission, PermissionPolicy.NEVER_REQUESTED.contains(permission))
        }
    }
}
