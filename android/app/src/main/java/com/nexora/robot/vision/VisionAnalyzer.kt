package com.nexora.robot.vision

import com.nexora.robot.net.SourceLink
import com.nexora.robot.net.VisionResult
import com.nexora.robot.net.NexApiClient

/** What a vision turn produced. Never a made-up description. */
sealed interface VisionOutcome {
    data class Described(
        val text: String,
        val grounded: Boolean,
        val sources: List<SourceLink>,
        val imageStored: Boolean,
    ) : VisionOutcome

    /** Refused or failed, with an honest reason the user can be told. */
    data class Refused(val message: String, val reason: String) : VisionOutcome
}

/** What the camera UI should show. The in-use state is always visible to the user. */
enum class VisionUiState {
    IDLE,
    PERMISSION_NEEDED,
    STARTING_CAMERA,
    CAMERA_ON,
    ANALYZING,
    FINISHED,
    FAILED,
}

interface VisionAnalyzer {
    suspend fun describe(
        jpegBytes: ByteArray,
        mimeType: String,
        prompt: String,
        language: String?,
    ): VisionOutcome
}

/**
 * Sends one explicitly requested frame to the NEX backend, which forwards it to the model.
 *
 * The frame lives in memory only. It is never written to storage, never cached, and never
 * reused for anything else — the backend response even carries `image_stored: false` so the
 * app can say so honestly.
 */
class RemoteVisionAnalyzer(private val api: NexApiClient) : VisionAnalyzer {

    override suspend fun describe(
        jpegBytes: ByteArray,
        mimeType: String,
        prompt: String,
        language: String?,
    ): VisionOutcome = when (val result = api.describeImage(jpegBytes, mimeType, prompt, language)) {
        is VisionResult.Success -> if (result.reply.isBlank()) {
            VisionOutcome.Refused(
                message = "I could not describe that picture. Please try once more.",
                reason = "empty_reply",
            )
        } else {
            VisionOutcome.Described(
                text = result.reply,
                grounded = result.grounded,
                sources = result.sources,
                imageStored = result.imageStored,
            )
        }
        is VisionResult.Rejected -> VisionOutcome.Refused(result.message, result.code)
        is VisionResult.Unavailable -> VisionOutcome.Refused(result.message, result.code)
    }
}
