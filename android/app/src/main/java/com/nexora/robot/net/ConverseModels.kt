package com.nexora.robot.net

/** One grounded source, exactly as the backend reports it: title and URL metadata only. */
data class SourceLink(
    val title: String,
    val url: String,
)

/** Stable error codes shared with the backend contract (see docs/API_CONTRACT.md). */
object NexErrorCodes {
    /** No backend URL configured in this build. */
    const val NOT_CONFIGURED = "backend_not_configured"

    /** No network route at all. */
    const val OFFLINE = "offline"

    /** The backend did not answer in time. */
    const val TIMEOUT = "timeout"

    /** The backend answered with a 4xx and a user-safe message. */
    const val REJECTED = "rejected"

    /** The backend is up but has no AI key configured. */
    const val SERVER_NOT_READY = "provider_not_configured"

    /** The answer could not be parsed. */
    const val BAD_RESPONSE = "bad_response"

    /** TLS or certificate problem: NEX refuses to continue. */
    const val INSECURE = "insecure_connection"
}

/** Result of one conversation turn. Never throws across the boundary. */
sealed interface ConverseResult {

    /** The backend answered. [grounded] is only true when the server really used search. */
    data class Success(
        val reply: String,
        val language: String,
        val grounded: Boolean,
        val sources: List<SourceLink>,
    ) : ConverseResult

    /** The backend explicitly refused (validation, safety, not configured). Message is user-safe. */
    data class Rejected(
        val code: String,
        val message: String,
        val httpStatus: Int,
    ) : ConverseResult

    /** NEX could not reach the backend at all. The caller must answer honestly offline. */
    data class Unavailable(
        val code: String,
        val message: String,
    ) : ConverseResult
}

/** Result of one explicitly requested vision turn. */
sealed interface VisionResult {
    data class Success(
        val reply: String,
        val language: String,
        val grounded: Boolean,
        val sources: List<SourceLink>,
        val imageStored: Boolean,
    ) : VisionResult

    data class Rejected(val code: String, val message: String, val httpStatus: Int) : VisionResult

    data class Unavailable(val code: String, val message: String) : VisionResult
}

/** Result of the readiness probe. */
sealed interface HealthResult {
    data class Ok(
        val provider: String,
        val model: String,
        val ready: Boolean,
        val groundingEnabled: Boolean,
        val version: String,
    ) : HealthResult

    data class Unreachable(val code: String, val message: String) : HealthResult
}
