package com.nexora.robot.vision

import com.nexora.robot.util.NexLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ties the camera, the analyser and the UI indicator together for one explicit question.
 *
 * Privacy shape of a turn:
 *  1. permission is checked again here (never trusted from an earlier turn);
 *  2. the camera is bound, the visible indicator goes on;
 *  3. **one** frame is captured into memory;
 *  4. the camera is unbound immediately — before any analysis happens;
 *  5. the frame goes to the NEX backend once and is dropped.
 */
class VisionCoordinator(
    private val camera: CameraSessionController,
    private val analyzer: VisionAnalyzer,
    private val cameraPermissionGranted: () -> Boolean,
    private val onUiState: (VisionUiState) -> Unit,
) {

    private val _state = MutableStateFlow(VisionUiState.IDLE)
    val state: StateFlow<VisionUiState> = _state.asStateFlow()

    suspend fun answerQuestion(prompt: String, language: String): VisionOutcome {
        if (!cameraPermissionGranted()) {
            publish(VisionUiState.PERMISSION_NEEDED)
            return VisionOutcome.Refused(
                message = "Camera permission is off, so I cannot look at it.",
                reason = "camera_permission_denied",
            )
        }

        publish(VisionUiState.STARTING_CAMERA)
        val started = camera.start()
        if (!started) {
            camera.stop()
            publish(VisionUiState.FAILED)
            return VisionOutcome.Refused(
                message = "I could not start the camera on this device.",
                reason = "camera_unavailable",
            )
        }
        publish(VisionUiState.CAMERA_ON)

        val frame: ByteArray?
        try {
            frame = camera.captureSingleFrame()
        } catch (error: Exception) {
            NexLog.e("vision capture failed", error)
            frame = null
        } finally {
            // The camera goes off the moment the frame is taken, before any analysis.
            camera.stop()
        }

        if (frame == null || frame.isEmpty()) {
            publish(VisionUiState.FAILED)
            return VisionOutcome.Refused(
                message = "I could not take a clear picture. Please try once more.",
                reason = "capture_failed",
            )
        }

        publish(VisionUiState.ANALYZING)
        val outcome = analyzer.describe(frame, "image/jpeg", prompt, language)
        publish(if (outcome is VisionOutcome.Described) VisionUiState.FINISHED else VisionUiState.FAILED)
        return outcome
    }

    /** Cancel and switch the camera off. Called from `onPause` and from a permission revoke. */
    fun release() {
        camera.stop()
        publish(VisionUiState.IDLE)
    }

    private fun publish(state: VisionUiState) {
        _state.value = state
        onUiState(state)
    }
}
